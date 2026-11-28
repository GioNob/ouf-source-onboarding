package it.comune.trieste.ouf.onboarding.authorization;

import static it.comune.trieste.ouf.onboarding.authorization.AnalysisMandate.*;
import static it.comune.trieste.ouf.onboarding.authorization.AnalysisMandate.Error.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLParameters;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ouf.authorization.analysis-mandate.enabled", havingValue = "true")
class AnalysisMandateRights {
  private final AnalysisMandateSettings settings;
  private final ObjectMapper json;
  private final CanonicalHash hashes;
  private final HttpClient client;

  AnalysisMandateRights(AnalysisMandateSettings settings, ObjectMapper json, CanonicalHash hashes,
                        SslBundles ssl) {
    this.settings = settings;
    this.json = json;
    this.hashes = hashes;
    var uri = settings.rightsUri();
    if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
        || uri.getUserInfo() != null || uri.getFragment() != null
        || settings.rightsSslBundle() == null || settings.rightsSslBundle().isBlank()) {
      throw new IllegalStateException("Invalid analysis mandate rights configuration");
    }
    var bundle = ssl.getBundle(settings.rightsSslBundle());
    if (bundle.getStores().getKeyStore() == null || bundle.getStores().getTrustStore() == null) {
      throw new IllegalStateException("Analysis mandate rights require client key material and trust anchors");
    }
    var tls = new SSLParameters();
    tls.setEndpointIdentificationAlgorithm("HTTPS");
    this.client = HttpClient.newBuilder().sslContext(bundle.createSslContext()).sslParameters(tls)
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofMillis(settings.clockSkewMs())).build();
  }

  Evidence fetch(Binding binding, long version, long generation, String action, Admit dispatch, String bearer) {
    var query = new LinkedHashMap<String, Object>();
    query.put("contract", "ouf.analysis.batch.mandate.v1");
    query.put("action", action);
    query.put("installation", settings.installation());
    query.put("version", version);
    query.put("generation", generation);
    query.put("issuer", binding.issuer());
    query.put("tenant", binding.tenant());
    query.put("subject", binding.subject());
    query.put("client", binding.client());
    query.put("workload", binding.workload());
    query.put("analysisId", binding.analysisId());
    query.put("policyEpoch", binding.policyEpoch());
    query.put("startRef", binding.startRef());
    query.put("mode", "BATCH_V1");
    query.put("assetId", binding.assetId());
    query.put("profileId", binding.profileId());
    query.put("schemaHash", binding.schemaHash());
    query.put("commandKey", binding.commandKey());
    query.put("scope", binding.scope());
    query.put("nonce", UUID.randomUUID().toString());
    if (dispatch != null) {
      query.put("operation", dispatch.operation());
      query.put("argumentsHash", dispatch.argumentsHash());
      query.put("attemptId", dispatch.attemptId());
      if (dispatch.requestId() != null) query.put("requestId", dispatch.requestId());
    }
    byte[] body = hashes.definitionBytes(query);
    var request = HttpRequest.newBuilder(settings.rightsUri())
        .timeout(Duration.ofMillis(settings.clockSkewMs()))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofByteArray(body));
    if (bearer != null) request.header("Authorization", bearer);

    long started = System.nanoTime();
    var responseBody = new ByteArrayOutputStream();
    var pending = client.sendAsync(request.build(), response -> HttpResponse.BodySubscribers.ofByteArrayConsumer(
        chunk -> chunk.ifPresent(bytes -> {
          require(responseBody.size() + bytes.length <= 16_384, RIGHTS_EVIDENCE_INVALID);
          responseBody.writeBytes(bytes);
        })));
    HttpResponse<Void> response;
    try {
      response = pending.get(settings.clockSkewMs(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      pending.cancel(true);
      throw new Failure(CURRENT_RIGHTS_UNAVAILABLE);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof Failure failure) throw failure;
      if (e.getCause() instanceof java.net.http.HttpTimeoutException
          || e.getCause() instanceof java.net.ConnectException
          || e.getCause() instanceof java.net.SocketException
          || e.getCause() instanceof java.io.EOFException) throw new Failure(CURRENT_RIGHTS_UNAVAILABLE);
      throw new Failure(RIGHTS_EVIDENCE_INVALID);
    } catch (TimeoutException e) {
      pending.cancel(true);
      throw new Failure(CURRENT_RIGHTS_UNAVAILABLE);
    }
    long received = System.nanoTime();
    if (response.statusCode() == 401 && bearer != null) throw new Failure(HUMAN_AUTHENTICATION_REQUIRED);
    if (List.of(429, 502, 503, 504).contains(response.statusCode())) {
      throw new Failure(CURRENT_RIGHTS_UNAVAILABLE, response.headers().firstValue("Retry-After")
          .filter(AnalysisMandateRights::validRetryAfter).orElse(null));
    }
    require(response.statusCode() == 200, RIGHTS_EVIDENCE_INVALID);

    JsonNode value;
    try (var parser = json.getFactory().createParser(responseBody.toByteArray())) {
      parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
      value = json.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(parser);
    } catch (java.io.IOException e) {
      throw new Failure(RIGHTS_EVIDENCE_INVALID);
    }
    require(value != null && value.isObject() && hashes.ofBytes(body).substring(7).equals(text(value, "requestHash"))
        && value.path("clockSynchronized").isBoolean() && value.path("clockSynchronized").booleanValue()
        && value.path("active").isBoolean() && value.path("allowed").isBoolean(), RIGHTS_EVIDENCE_INVALID);
    long observed = millis(value, "observedAtMs");
    long until = millis(value, "validUntilMs");
    require(observed > 0 && until > observed && until - observed <= EVIDENCE_MS, RIGHTS_EVIDENCE_INVALID);
    return new Evidence(value, observed, until, started, received);
  }

  private static boolean validRetryAfter(String value) {
    if (value.matches("[0-9]{1,10}")) return true;
    if (value.length() > 40) return false;
    try {
      ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
      return true;
    } catch (DateTimeParseException e) {
      return false;
    }
  }

  private static long millis(JsonNode value, String field) {
    var node = value.path(field);
    require(node.isIntegralNumber() && node.canConvertToLong(), RIGHTS_EVIDENCE_INVALID);
    return node.longValue();
  }

  static String text(JsonNode value, String field) { return value.path(field).textValue(); }

  static Snapshot snapshot(Evidence proof, CanonicalHash hashes) {
    var value = proof.value();
    require(value.path("active").booleanValue(), PRINCIPAL_INACTIVE);
    require(value.path("allowed").booleanValue(), CURRENT_RIGHTS_DENIED);
    String epoch = text(value, "policyEpoch");
    String bundleHash = text(value, "policyBundleHash");
    String acr = text(value, "acr");
    String scopeText = text(value, "scope");
    var roles = new ArrayList<String>();
    var roleNodes = value.path("roles");
    require(roleNodes.isArray() && roleNodes.size() <= 32 && scopeText != null && scopeText.length() <= 4096,
        RIGHTS_EVIDENCE_INVALID);
    roleNodes.forEach(role -> roles.add(role.textValue()));
    var scopes = scopeText.isBlank() ? List.<String>of() : List.of(scopeText.trim().split(" +"));
    var grantNodes = value.path("grants");
    var grants = new LinkedHashMap<String, String>();
    require(grantNodes.isObject() && grantNodes.size() == OPERATIONS.size(), RIGHTS_EVIDENCE_INVALID);
    for (String operation : OPERATIONS) {
      String grant = text(grantNodes, operation);
      require(hash(grant), RIGHTS_EVIDENCE_INVALID);
      grants.put(operation, grant);
    }
    require(hash(epoch) && hash(bundleHash) && identity(acr) && reference(text(value, "decisionRef"))
        && validScopes(scopes) && validTokens(roles), RIGHTS_EVIDENCE_INVALID);
    String rightsHash = hashes.of(Map.of("acr", acr, "grants", grants, "scope", sorted(scopes),
        "roles", sorted(roles))).substring(7);
    return new Snapshot(epoch, bundleHash, rightsHash, sorted(scopes));
  }

  record Snapshot(String policyEpoch, String policyBundleHash, String rightsHash, List<String> scope) {}

  record Evidence(JsonNode value, long observedAtMs, long validUntilMs, long started, long received) {
    long check(Long dbNow, long skewMs) {
      long tick = System.nanoTime();
      long elapsed = TimeUnit.NANOSECONDS.toMillis(tick - started);
      require(tick - received >= 0 && received - started >= 0
          && TimeUnit.NANOSECONDS.toMillis(received - started) <= skewMs
          && elapsed < validUntilMs - observedAtMs, RIGHTS_EVIDENCE_INVALID);
      long lower = observedAtMs + TimeUnit.NANOSECONDS.toMillis(tick - received);
      long upper = observedAtMs + elapsed;
      if (dbNow != null) {
        require(upper - dbNow <= skewMs && dbNow - lower <= skewMs, CLOCK_UNVERIFIED);
        return Math.max(dbNow, upper);
      }
      return upper;
    }
  }
}
