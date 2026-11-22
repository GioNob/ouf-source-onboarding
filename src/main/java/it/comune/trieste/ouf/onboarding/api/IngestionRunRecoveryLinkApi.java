package it.comune.trieste.ouf.onboarding.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import it.comune.trieste.ouf.authorization.PrincipalContext;
import it.comune.trieste.ouf.onboarding.application.ManagedFileService;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Pattern;
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
  static final String SEMANTIC_PATH = "/api/internal/v1/onboarding/ths-links/semantic-review";
  static final String SEMANTIC_CAPABILITY = "ouf.onboarding.ths-links.semantic-review";
  private static final Pattern CANONICAL_UUID = Pattern.compile("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
  private static final String RECOVERY_ARGUMENTS = "A source ID, canonical run UUID and decimal control version are required";
  private static final String SEMANTIC_ARGUMENTS = "A canonical challenge UUID is required";

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
    var caller = verifyDelegation(request, CAPABILITY, raw);
    JsonNode args = readArguments(raw, 3, RECOVERY_ARGUMENTS);
    String source = args.path("sourceId").textValue();
    String run = args.path("runId").textValue();
    JsonNode version = args.path("expectedVersion");
    if (source == null || source.isBlank() || source.length() > 160
        || !canonicalUuid(run)
        || !version.isIntegralNumber() || !version.canConvertToLong()
        || version.longValue() < 0 || version.longValue() > IngestionRunRecoveryThs.MAX_CONTROL_VERSION) {
      throw invalidRequest(RECOVERY_ARGUMENTS);
    }
    files.requireManagedSourceOwner(source, caller.tenantId(), caller.subjectId());
    return reviewLink(IngestionRunRecoveryThs.PATH + "/?runId=" + run + "&expectedVersion=" + version.longValue());
  }

  @PostMapping(SEMANTIC_PATH)
  public ResponseEntity<Map<String,String>> semanticReview(@RequestBody byte[] raw, HttpServletRequest request) {
    // The semantic THS reads the challenge as the logged-in HUMAN from its owner on opening.
    verifyDelegation(request, SEMANTIC_CAPABILITY, raw);
    String challenge = readArguments(raw, 1, SEMANTIC_ARGUMENTS).path("challengeId").textValue();
    if (!canonicalUuid(challenge)) throw invalidRequest(SEMANTIC_ARGUMENTS);
    return reviewLink("/trusted-human/semantic/?challenge=" + challenge);
  }

  private PrincipalContext verifyDelegation(HttpServletRequest request, String capability, byte[] raw) {
    return receipts.verifyManagedFile(request.getHeader("X-OUF-Managed-File-Receipt"),
        request.getRequestURI(), capability, raw).principal();
  }

  private JsonNode readArguments(byte[] raw, int size, String errorDetail) {
    JsonNode args;
    try {
      args = requestJson.readTree(raw);
    } catch (IOException failure) {
      throw invalidRequest(errorDetail);
    }
    if (args == null || !args.isObject() || args.size() != size) throw invalidRequest(errorDetail);
    return args;
  }

  private static boolean canonicalUuid(String value) {
    return value != null && CANONICAL_UUID.matcher(value).matches();
  }

  private ResponseEntity<Map<String,String>> reviewLink(String path) {
    if (origin == null) {
      throw new DomainFailure(HttpStatus.SERVICE_UNAVAILABLE, "ONB_THS_LINK_ORIGIN_REQUIRED",
          "The public HTTPS origin for THS links is unavailable");
    }
    URI review = origin.resolve(path);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of(
        "reviewUrl", review.toASCIIString(), "expiresAt", Instant.now().plus(presentationTtl).toString()));
  }

  @ExceptionHandler(SecurityException.class)
  ResponseEntity<Map<String,String>> denied() {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("code", "ONB_MANAGED_FILE_RECEIPT_INVALID"));
  }

  private static DomainFailure invalidRequest(String detail) {
    return new DomainFailure(HttpStatus.BAD_REQUEST, "ONB_THS_LINK_ARGUMENTS_INVALID", detail);
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
