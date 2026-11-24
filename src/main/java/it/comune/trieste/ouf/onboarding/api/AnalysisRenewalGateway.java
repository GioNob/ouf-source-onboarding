package it.comune.trieste.ouf.onboarding.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/** The Gateway verifies the HUMAN. MCP owns link versions, replay receipts and the commit. */
@Component
@ConditionalOnProperty(name = "ouf.workflow.ths.gateway-base-url")
class AnalysisRenewalGateway {
  private static final String ROOT = "/internal/analysis/v1/renewals/";
  private static final Map<Integer, Set<String>> ERRORS = Map.of(
      400, Set.of("ANALYSIS_INPUT_INVALID"), 403, Set.of("ANALYSIS_AUTHORITY_DENIED"),
      404, Set.of("ANALYSIS_MANDATE_UNKNOWN"), 409, Set.of("ANALYSIS_STALE_EXECUTION",
          "ANALYSIS_COMMAND_CONFLICT", "ANALYSIS_RENEWAL_USED"),
      410, Set.of("ANALYSIS_MANDATE_EXPIRED"), 429, Set.of("ANALYSIS_BACKOFF"),
      503, Set.of("ANALYSIS_AUTHORITY_UNAVAILABLE"));
  private final URI origin;
  private final ObjectMapper json;
  private final int timeoutSeconds;
  final int messageBytes;
  private final HttpClient http;

  AnalysisRenewalGateway(ObjectMapper json, @Value("${ouf.workflow.ths.gateway-base-url}") String base,
      @Value("${ouf.workflow.ths.analysis-renewal.timeout-seconds:15}") int timeoutSeconds,
      @Value("${ouf.workflow.ths.analysis-renewal.message-bytes:65536}") int messageBytes) {
    this.json = json;
    origin = WorkflowThsApi.gatewayOrigin(base);
    if (timeoutSeconds < 1 || timeoutSeconds > 25 || messageBytes < 1 || messageBytes > 131072)
      throw new IllegalArgumentException("THS_RENEWAL_LIMIT_INVALID");
    this.timeoutSeconds = timeoutSeconds;
    this.messageBytes = messageBytes;
    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds))
        .followRedirects(HttpClient.Redirect.NEVER).build();
  }

  ResponseEntity<JsonNode> read(String token, String ref) {
    return post("read", token, json.createObjectNode().put("reviewRef", ref));
  }

  ResponseEntity<JsonNode> decide(String token, JsonNode command) {
    return post("decide", token, command);
  }

  private ResponseEntity<JsonNode> post(String action, String token, JsonNode body) {
    byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
    if (payload.length > messageBytes)
      return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
          .body(json.createObjectNode().put("code", "ANALYSIS_INPUT_INVALID"));
    var request = HttpRequest.newBuilder(origin.resolve(ROOT + action)).timeout(Duration.ofSeconds(timeoutSeconds))
        .header("Authorization", "Bearer " + token).header("Accept", "application/json")
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
    var pending = http.sendAsync(request, info -> new ManagedProfileRecoveryThs.BoundedBody(messageBytes));
    try {
      var response = pending.get(timeoutSeconds, TimeUnit.SECONDS);
      int status = response.statusCode();
      JsonNode data = json.readTree(response.body());
      if (status >= 200 && status < 300 && data != null && data.isObject())
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(data);
      String code = data == null ? "" : data.path("code").asText("");
      if (ERRORS.getOrDefault(status, Set.of()).contains(code)) {
        var result = ResponseEntity.status(status).cacheControl(CacheControl.noStore());
        if (status == 429 || status == 503)
          response.headers().firstValue(HttpHeaders.RETRY_AFTER).ifPresent(value -> result.header(HttpHeaders.RETRY_AFTER, value));
        return result.body(json.createObjectNode().put("code", code));
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } catch (IOException | TimeoutException | ExecutionException unavailable) {
      // A lost decision response requires a fresh read. Never resubmit here.
    } finally {
      if (!pending.isDone()) pending.cancel(true);
    }
    return ResponseEntity.status(502).cacheControl(CacheControl.noStore())
        .body(json.createObjectNode().put("code", "THS_RENEWAL_UNCONFIRMED"));
  }
}
