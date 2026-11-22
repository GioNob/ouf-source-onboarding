package it.comune.trieste.ouf.onboarding.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import it.comune.trieste.ouf.onboarding.application.ManagedFileService;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IngestionRunRecoveryLinkApi {
  static final String PATH = "/api/internal/v1/onboarding/ths-links/ingestion-run-recovery";
  static final String CAPABILITY = "ouf.onboarding.ths-links.ingestion-run-recovery";

  private final PermissionDelegationVerifier receipts;
  private final ManagedFileService files;
  private final ObjectReader requestJson;
  private final URI origin;
  private final Duration presentationTtl;

  public IngestionRunRecoveryLinkApi(PermissionDelegationVerifier receipts, ManagedFileService files, ObjectMapper json,
      @Value("${ouf.authorization.ths.public-origin:}") String publicOrigin,
      @Value("${ouf.onboarding.ths-links.ingestion-run-recovery.presentation-ttl:PT10M}") Duration presentationTtl) {
    if (presentationTtl.isZero() || presentationTtl.isNegative()) {
      throw new IllegalArgumentException("THS link presentation TTL must be positive");
    }
    this.receipts = receipts;
    this.files = files;
    this.requestJson = json.reader().withFeatures(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
        DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    this.origin = httpsOrigin(publicOrigin);
    this.presentationTtl = presentationTtl;
  }

  @PostMapping(PATH)
  public ResponseEntity<Map<String,String>> link(@RequestBody byte[] raw, HttpServletRequest request) {
    var caller = receipts.verifyManagedFile(request.getHeader("X-OUF-Managed-File-Receipt"),
        request.getRequestURI(), CAPABILITY, raw).principal();
    JsonNode args;
    try {
      args = requestJson.readTree(raw);
    } catch (IOException failure) {
      throw invalidRequest();
    }
    if (args == null || !args.isObject() || args.size() != 3) throw invalidRequest();
    String source = args.path("sourceId").textValue();
    String run = args.path("runId").textValue();
    JsonNode version = args.path("expectedVersion");
    if (source == null || source.isBlank() || source.length() > 160
        || run == null || !run.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
        || !version.isIntegralNumber() || !version.canConvertToLong()
        || version.longValue() < 0 || version.longValue() > IngestionRunRecoveryThs.MAX_CONTROL_VERSION) {
      throw invalidRequest();
    }
    files.requireManagedSourceOwner(source, caller.tenantId(), caller.subjectId());
    if (origin == null) {
      throw new DomainFailure(HttpStatus.SERVICE_UNAVAILABLE, "ONB_THS_LINK_ORIGIN_REQUIRED",
          "The public HTTPS origin for THS links is unavailable");
    }
    URI review = origin.resolve(IngestionRunRecoveryThs.PATH + "/?runId=" + run + "&expectedVersion=" + version.longValue());
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of(
        "reviewUrl", review.toASCIIString(), "expiresAt", Instant.now().plus(presentationTtl).toString()));
  }

  @ExceptionHandler(SecurityException.class)
  ResponseEntity<Map<String,String>> denied() {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("code", "ONB_MANAGED_FILE_RECEIPT_INVALID"));
  }

  private static DomainFailure invalidRequest() {
    return new DomainFailure(HttpStatus.BAD_REQUEST, "ONB_THS_LINK_ARGUMENTS_INVALID",
        "A source ID, canonical run UUID and decimal control version are required");
  }

  private static URI httpsOrigin(String value) {
    try {
      URI uri = URI.create(value);
      if ("https".equals(uri.getScheme()) && uri.getHost() != null && uri.getRawUserInfo() == null
          && uri.getRawQuery() == null && uri.getRawFragment() == null
          && (uri.getPath().isEmpty() || "/".equals(uri.getPath()))) return uri;
    } catch (IllegalArgumentException ignored) {
      // Report configuration errors through the authenticated endpoint.
    }
    return null;
  }
}
