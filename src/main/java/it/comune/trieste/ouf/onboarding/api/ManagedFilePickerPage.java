package it.comune.trieste.ouf.onboarding.api;

import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import it.comune.trieste.ouf.onboarding.application.ManagedFileContentInspector;
import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.beans.factory.annotation.Value;
import jakarta.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.net.URI;
import java.net.HttpURLConnection;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.application.ManagedFileStagingStore;
import it.comune.trieste.ouf.onboarding.application.ManagedFileChatHandoff;
import it.comune.trieste.ouf.authorization.PrincipalContext;
import it.comune.trieste.ouf.authorization.ServletAuthorization;

/** Session-backed browser adapter to the existing governed upload capability. */
@RestController
@ConditionalOnBean(ManagedFileStagingStore.class)
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="ouf.managed-file-picker.gateway-upload-url")
public class ManagedFilePickerPage {
  private final ManagedFileContentInspector inspector;
  private final URI uploadUrl;
  private final OAuth2AuthorizedClientService clients;
  private final TrustedActorResolver actors;
  private final ObjectMapper json;
  private final ManagedFileChatHandoff handoffs;

  public ManagedFilePickerPage(@Value("${ouf.managed-file-picker.gateway-upload-url}") String url,
      OAuth2AuthorizedClientService clients, TrustedActorResolver actors, ObjectMapper json,
      ManagedFileChatHandoff handoffs, ManagedFileContentInspector inspector) {
    this.inspector = inspector;
    this.uploadUrl = URI.create(url);
    if (!"/api/managed-sources/v1/files".equals(uploadUrl.getPath()) || uploadUrl.getRawQuery() != null
        || uploadUrl.getRawFragment() != null || uploadUrl.getUserInfo() != null
        || !("http".equals(uploadUrl.getScheme()) && "ouf-apisix".equals(uploadUrl.getHost()) && uploadUrl.getPort() == 9080))
      throw new IllegalArgumentException("picker must call the exact governed Gateway upload binding");
    this.clients = clients;
    this.actors = actors;
    this.json = json;
    this.handoffs = handoffs;
  }

  private PrincipalContext requireSession(HttpServletRequest request) {
    if (!Boolean.TRUE.equals(request.getAttribute("ouf.permissionThsSession")))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "THS_SESSION_REQUIRED");
    actors.requireHuman(request, "ouf.managed-source.file.upload");
    return ServletAuthorization.principal(request).context();
  }

  @GetMapping("/trusted-human/managed-files/")
  public ResponseEntity<byte[]> page(HttpServletRequest request) throws IOException {
    requireSession(request);
    return asset("managed-file-picker.html", MediaType.TEXT_HTML);
  }

  @GetMapping("/trusted-human/managed-files/picker.js")
  public ResponseEntity<byte[]> script(HttpServletRequest request) throws IOException {
    requireSession(request);
    return asset("managed-file-picker.js", MediaType.valueOf("text/javascript"));
  }

  @GetMapping("/trusted-human/managed-files/session")
  public ResponseEntity<Map<String,String>> session(HttpServletRequest request) {
    requireSession(request);
    var csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
    if (csrf == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "THS_CSRF_REQUIRED");
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(Map.of("csrfToken", csrf.getToken(), "csrfHeader", csrf.getHeaderName()));
  }

  public record Rejection(String code) {}

  @PostMapping("/trusted-human/managed-files/rejection")
  public ResponseEntity<Void> reject(@RequestParam("handoff") UUID handoff,
      @org.springframework.web.bind.annotation.RequestBody Rejection rejection, HttpServletRequest request) {
    var principal = requireSession(request);
    if (rejection == null || rejection.code() == null || !java.util.Set.of("FORMAT_UNSUPPORTED", "SIZE_INVALID").contains(rejection.code()))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UPLOAD_REJECTION_INVALID");
    handoffs.reject(handoff, principal.tenantId(), principal.subjectId(), rejection.code());
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }

  /** Browser session adapter; invokes the one existing Gateway upload API. */
  @PostMapping(path="/trusted-human/managed-files/upload")
  public ResponseEntity<Map<String,String>> upload(HttpServletRequest request,
      @RequestHeader("X-Content-SHA256") String hash,
      @RequestParam(value="handoff",required=false) UUID handoff) throws IOException {
    var principal = requireSession(request);
    long declaredSize = request.getContentLengthLong();
    var format = inspector.requireFormat(request.getContentType());
    inspector.checkSize(format, declaredSize);
    if (declaredSize == 0 || !hash.matches("sha256:[0-9a-f]{64}"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UPLOAD_METADATA_INVALID");
    if (handoff != null) handoffs.checkUpload(handoff, principal.tenantId(), principal.subjectId());
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (!(auth instanceof OAuth2AuthenticationToken session))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "THS_SESSION_REQUIRED");
    var client = clients.loadAuthorizedClient(session.getAuthorizedClientRegistrationId(), session.getName());
    if (client == null || !"ouf-ths".equals(client.getClientRegistration().getRegistrationId()))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "THS_SESSION_REQUIRED");
    byte[] verified;
    try (var source = request.getInputStream()) {
      verified = verifiedBody(source, declaredSize, format, inspector);
    }
    var connection = (HttpURLConnection) uploadUrl.toURL().openConnection();
    try {
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(5000);
      connection.setReadTimeout(30000);
      connection.setRequestMethod("POST");
      connection.setDoOutput(true);
      connection.setFixedLengthStreamingMode(verified.length);
      connection.setRequestProperty("Authorization", "Bearer " + client.getAccessToken().getTokenValue());
      connection.setRequestProperty("Content-Type", format.mediaType());
      connection.setRequestProperty("X-Content-SHA256", hash);
      String fileName=request.getHeader("X-OUF-File-Name");
      if(fileName!=null && fileName.matches("[!-~]{1,3072}"))connection.setRequestProperty("X-OUF-File-Name",fileName);
      try (var target = connection.getOutputStream()) {
        target.write(verified);
      }
      int code = connection.getResponseCode();
      if (code != 201)
        throw new ResponseStatusException(HttpStatus.valueOf(code >= 400 && code < 600 ? code : 502), "GATEWAY_UPLOAD_REJECTED");
      try (InputStream body = connection.getInputStream()) {
        byte[] bounded = body.readNBytes(4097);
        if (bounded.length > 4096) throw new IOException("Gateway upload result exceeded limit");
        var result = json.readTree(bounded);
        var id = result.path("asset_id").asText();
        if (!id.matches("[0-9a-f-]{36}")) throw new IOException("Gateway upload result missing asset ID");
        if (handoff != null) handoffs.complete(handoff, UUID.fromString(id), principal.tenantId(), principal.subjectId());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(Map.of("assetId", id, "status", "STAGED", "mediaType", format.mediaType()));
      }
    } finally {
      connection.disconnect();
    }
  }

  static byte[] verifiedBody(InputStream source, long declaredSize, ManagedFileContentInspector.Format format,
      ManagedFileContentInspector inspector) throws IOException {
    byte[] bytes = source.readNBytes(format.maxBytes() + 1);
    inspector.checkSize(format, bytes.length);
    if (bytes.length == 0 || declaredSize > 0 && declaredSize != bytes.length)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UPLOAD_SIZE_MISMATCH");
    inspector.verify(format, bytes);
    return bytes;
  }

  private ResponseEntity<byte[]> asset(String name, MediaType type) throws IOException {
    byte[] body = ThsAssets.read(name);
    if (type.equals(MediaType.TEXT_HTML)) {
      // Extensions and MIME values come exclusively from the closed format registry.
      String html = new String(body, StandardCharsets.UTF_8)
          .replace("{{formats}}", json.writeValueAsString(inspector.formats()))
          .replace("{{extensions}}", "." + String.join(",.", inspector.formats().keySet()))
          .replace("{{labels}}", String.join(", ", inspector.formats().keySet()).toUpperCase(java.util.Locale.ROOT));
      body = html.getBytes(StandardCharsets.UTF_8);
    }
    return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options", "nosniff")
        .header("Referrer-Policy", "no-referrer")
        .header("X-Frame-Options", "DENY")
        .header("Content-Security-Policy", "default-src 'none'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
        .body(body);
  }
}
