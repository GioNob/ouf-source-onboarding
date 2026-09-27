package it.comune.trieste.ouf.onboarding.api;

import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.io.ClassPathResource;
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
import java.io.OutputStream;
import java.net.URI;
import java.net.HttpURLConnection;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.application.ManagedFileStagingStore;
import it.comune.trieste.ouf.onboarding.application.ManagedFileChatHandoff;

/** Session-backed browser adapter to the existing governed upload capability. */
@RestController
@ConditionalOnBean(ManagedFileStagingStore.class)
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="ouf.managed-file-picker.gateway-upload-url")
public class ManagedFilePickerPage {
  private static final long MAX_BYTES = 10L * 1024 * 1024;
  private final URI uploadUrl;
  private final OAuth2AuthorizedClientService clients;
  private final TrustedActorResolver actors;
  private final ObjectMapper json;
  private final ManagedFileChatHandoff handoffs;

  public ManagedFilePickerPage(@Value("${ouf.managed-file-picker.gateway-upload-url}") String url,
      OAuth2AuthorizedClientService clients, TrustedActorResolver actors, ObjectMapper json,
      ManagedFileChatHandoff handoffs) {
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

  private void requireSession(HttpServletRequest request) {
    if (!Boolean.TRUE.equals(request.getAttribute("ouf.permissionThsSession")))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "THS_SESSION_REQUIRED");
    actors.requireHuman(request, "ouf.managed-source.file.upload");
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

  /** Browser session adapter; invokes the one existing Gateway upload API. */
  @PostMapping(path="/trusted-human/managed-files/upload", consumes="text/csv")
  public ResponseEntity<Map<String,String>> upload(HttpServletRequest request,
      @RequestHeader("X-Content-SHA256") String hash,
      @RequestParam(value="handoff",required=false) UUID handoff) throws IOException {
    requireSession(request);
    long declaredSize = request.getContentLengthLong();
    if (declaredSize > MAX_BYTES)
      throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "UPLOAD_SIZE_INVALID");
    if (declaredSize == 0 || !hash.matches("sha256:[0-9a-f]{64}"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UPLOAD_METADATA_INVALID");
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (!(auth instanceof OAuth2AuthenticationToken session))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "THS_SESSION_REQUIRED");
    var client = clients.loadAuthorizedClient(session.getAuthorizedClientRegistrationId(), session.getName());
    if (client == null || !"ouf-ths".equals(client.getClientRegistration().getRegistrationId()))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "THS_SESSION_REQUIRED");
    var connection = (HttpURLConnection) uploadUrl.toURL().openConnection();
    try {
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(5000);
      connection.setReadTimeout(30000);
      connection.setRequestMethod("POST");
      connection.setDoOutput(true);
      if (declaredSize > 0) connection.setFixedLengthStreamingMode(declaredSize);
      else connection.setChunkedStreamingMode(65536);
      connection.setRequestProperty("Authorization", "Bearer " + client.getAccessToken().getTokenValue());
      connection.setRequestProperty("Content-Type", "text/csv");
      connection.setRequestProperty("X-Content-SHA256", hash);
      try (var source = request.getInputStream(); var target = connection.getOutputStream()) {
        copyBounded(source, target, declaredSize);
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
        if (handoff != null) handoffs.complete(handoff, UUID.fromString(id), actors.requireHuman(request,
            "ouf.managed-source.file.upload").subject());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(Map.of("assetId", id, "status", "STAGED"));
      }
    } finally {
      connection.disconnect();
    }
  }

  static long copyBounded(InputStream source, OutputStream target, long declaredSize) throws IOException {
    byte[] chunk = new byte[65536];
    long count = 0;
    for (int n; (n = source.read(chunk)) != -1;) {
      count += n;
      if (count > MAX_BYTES || declaredSize > 0 && count > declaredSize)
        throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "UPLOAD_SIZE_INVALID");
      target.write(chunk, 0, n);
    }
    if (count == 0 || declaredSize > 0 && count != declaredSize)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UPLOAD_SIZE_MISMATCH");
    return count;
  }

  private ResponseEntity<byte[]> asset(String name, MediaType type) throws IOException {
    return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options", "nosniff")
        .header("Referrer-Policy", "no-referrer")
        .header("X-Frame-Options", "DENY")
        .header("Content-Security-Policy", "default-src 'none'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
        .body(new ClassPathResource("ths/" + name).getContentAsByteArray());
  }
}
