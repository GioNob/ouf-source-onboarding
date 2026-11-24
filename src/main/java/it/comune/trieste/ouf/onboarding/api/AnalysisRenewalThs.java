package it.comune.trieste.ouf.onboarding.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(AnalysisRenewalThs.PATH)
@ConditionalOnProperty(name = "ouf.workflow.ths.gateway-base-url")
class AnalysisRenewalThs {
  static final String PATH = "/trusted-human/analysis-renewal";
  private static final Set<String> COMMENT_FIELDS = Set.of("Calls", "Attempts", "Objects", "Bytes", "Millis", "general");
  private static final String REVIEW_REF = "[A-Za-z0-9_-]{22,128}";
  private static final String UUID_V4 = "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
  private final OAuth2AuthorizedClientService clients;
  private final AnalysisRenewalGateway gateway;
  private final ObjectMapper json;

  AnalysisRenewalThs(OAuth2AuthorizedClientService clients, AnalysisRenewalGateway gateway, ObjectMapper json) {
    this.clients = clients;
    this.gateway = gateway;
    this.json = json;
  }

  @GetMapping("/{reviewRef}")
  ResponseEntity<?> page(@PathVariable String reviewRef, HttpServletRequest request) throws IOException {
    String token = WorkflowThsApi.token(request, clients);
    // The static page carries no data: its JSON read explains a malformed link in Italian.
    if (!MediaType.APPLICATION_JSON_VALUE.equals(request.getHeader("Accept")))
      return WorkflowThsApi.asset("analysis-renewal.html", MediaType.parseMediaType("text/html;charset=UTF-8"));
    if (!reviewRef.matches(REVIEW_REF)) return error(400, "ANALYSIS_INPUT_INVALID");
    var csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
    if (csrf == null) return error(403, "THS_CSRF_REQUIRED");
    var result = gateway.read(token, reviewRef);
    if (!result.getStatusCode().is2xxSuccessful()) return result;
    JsonNode card = result.getBody();
    if (card.has("reviewRef") && !reviewRef.equals(card.path("reviewRef").textValue()))
      return error(409, "ANALYSIS_STALE_EXECUTION");
    var view = json.createObjectNode();
    for (String field : Set.of("analysisId", "incidentId", "proposalVersion", "expectedSeq", "proposalHash",
        "reason", "remainingOrdinals", "initialMaximum", "consumed", "increment", "totalMaximum", "expiresAt",
        "previous", "requestedChanges", "proposalState", "planLabel"))
      if (card.has(field)) view.set(field, card.get(field));
    var envelope = json.createObjectNode().put("csrfHeader", csrf.getHeaderName()).put("csrfToken", csrf.getToken());
    envelope.set("card", view);
    if (json.writeValueAsBytes(envelope).length > gateway.messageBytes) return error(502, "THS_RENEWAL_UNCONFIRMED");
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).varyBy("Accept").body(envelope);
  }

  @GetMapping("/assets/renewal.js")
  ResponseEntity<byte[]> script(HttpServletRequest request) throws IOException {
    WorkflowThsApi.token(request, clients);
    return WorkflowThsApi.asset("analysis-renewal.js", MediaType.valueOf("text/javascript"));
  }

  @GetMapping("/assets/renewal.css")
  ResponseEntity<byte[]> style(HttpServletRequest request) throws IOException {
    WorkflowThsApi.token(request, clients);
    return WorkflowThsApi.asset("analysis-renewal.css", MediaType.valueOf("text/css"));
  }

  @PostMapping(value = "/{reviewRef}/decision", consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> decide(@PathVariable String reviewRef, HttpServletRequest request) throws IOException {
    String token = WorkflowThsApi.token(request, clients);
    if (!reviewRef.matches(REVIEW_REF)) return error(400, "ANALYSIS_INPUT_INVALID");
    byte[] body = request.getInputStream().readNBytes(gateway.messageBytes + 1);
    if (body.length > gateway.messageBytes) return error(400, "ANALYSIS_INPUT_INVALID");
    JsonNode command;
    try {
      command = json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(body);
    } catch (JsonProcessingException malformed) {
      return error(400, "ANALYSIS_INPUT_INVALID");
    }
    if (!valid(command, reviewRef)) return error(400, "ANALYSIS_INPUT_INVALID");
    return gateway.decide(token, command);
  }

  private static boolean valid(JsonNode command, String reviewRef) {
    if (command == null || !command.isObject() || command.size() != 7
        || !reviewRef.equals(command.path("reviewRef").textValue())
        || !matches(command.path("proposalHash"), "[0-9a-f]{64}") || !matches(command.path("commandKey"), UUID_V4)
        || !version(command.path("proposalVersion")) || !sequence(command.path("expectedSeq"))) return false;
    var decision = command.path("decision").asText("");
    var items = command.path("items");
    if (!Set.of("APPROVE", "CHANGES_REQUESTED", "CLOSE_PARTIAL").contains(decision) || !items.isArray()
        || items.size() > 16 || ("CHANGES_REQUESTED".equals(decision) == items.isEmpty())) return false;
    for (var item : items) {
      String field = item.path("field").asText("");
      var comment = item.path("comment");
      if (!item.isObject() || item.size() != 2 || !COMMENT_FIELDS.contains(field)
          || !comment.isTextual() || comment.textValue().isBlank()
          || comment.textValue().codePointCount(0, comment.textValue().length()) > 500) return false;
    }
    return true;
  }

  private static boolean matches(JsonNode node, String pattern) {
    return node.isTextual() && node.textValue().matches(pattern);
  }

  private static boolean sequence(JsonNode node) {
    if (!matches(node, "0|[1-9][0-9]{0,18}")) return false;
    String value = node.textValue();
    return value.length() < 19 || value.compareTo("9223372036854775807") <= 0;
  }

  private static boolean version(JsonNode node) {
    return node.isIntegralNumber() && node.canConvertToLong() && node.longValue() >= 1
        && node.longValue() <= IngestionRunRecoveryThs.MAX_CONTROL_VERSION;
  }

  private ResponseEntity<JsonNode> error(int status, String code) {
    return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(json.createObjectNode().put("code", code));
  }
}
