package it.comune.trieste.ouf.onboarding.api;

import static it.comune.trieste.ouf.onboarding.authorization.AnalysisMandate.*;
import static it.comune.trieste.ouf.onboarding.authorization.AnalysisMandate.Error.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import it.comune.trieste.ouf.onboarding.authorization.AnalysisMandate;
import it.comune.trieste.ouf.onboarding.authorization.AnalysisMandateRegistry;
import it.comune.trieste.ouf.onboarding.authorization.AnalysisMandateSettings;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(AnalysisMandateApi.PATH)
@ConditionalOnProperty(name = "ouf.authorization.analysis-mandate.enabled", havingValue = "true")
public class AnalysisMandateApi {
  static final String PATH = "/api/internal/v1/authorization/analysis-mandates";
  private static final Logger LOG = LoggerFactory.getLogger(AnalysisMandateApi.class);
  private static final int MAX_REQUEST = 16_384;
  private final AnalysisMandateSettings settings;
  private final AnalysisMandateRegistry registry;
  private final ObjectMapper json;
  private final CanonicalHash hashes;

  AnalysisMandateApi(AnalysisMandateSettings settings, AnalysisMandateRegistry registry, ObjectMapper json,
                     CanonicalHash hashes) {
    this.settings = settings;
    this.registry = registry;
    this.hashes = hashes;
    this.json = json.copy().disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    this.json.coercionConfigFor(LogicalType.Textual)
        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
  }

  @PostMapping(value = "/{action:issue|admit|revoke|expire|read}",
      consumes = "application/json", produces = "application/json")
  public ResponseEntity<?> execute(@PathVariable String action, HttpServletRequest request) {
    var denied = gatewayAuthentication(request);
    if (denied != null) return error(denied.status, denied.name(), null);

    var authorization = Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION));
    if (!"revoke".equals(action) && !authorization.isEmpty()) {
      return error(INVALID_REQUEST.status, INVALID_REQUEST.name(), null);
    }
    String bearer = authorization.size() == 1 && validBearer(authorization.getFirst())
        ? authorization.getFirst() : null;
    try {
      var view = switch (action) {
        case "issue" -> registry.issue(body(request, Issue.class));
        case "admit" -> registry.admit(body(request, Admit.class));
        case "revoke" -> registry.revoke(body(request, Revoke.class), bearer);
        case "expire" -> registry.expire(body(request, Expire.class));
        case "read" -> registry.read(body(request, Read.class));
        default -> throw new IllegalStateException("Unknown mandate route");
      };
      return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view);
    } catch (Failure e) {
      return error(e.error.status, e.error.name(), e.retryAfter);
    } catch (IOException e) {
      return error(INVALID_REQUEST.status, INVALID_REQUEST.name(), null);
    } catch (DataAccessException | TransactionException e) {
      boolean transientFailure = transientDatabaseFailure(e);
      String code = transientFailure ? REGISTRY_UNAVAILABLE.name() : "REGISTRY_FAILURE";
      LOG.warn("Analysis mandate {} failed with {}", action, code);
      return error(transientFailure ? 503 : 500, code, null);
    }
  }

  private AnalysisMandate.Error gatewayAuthentication(HttpServletRequest request) {
    if (!request.isSecure() || !(request.getAttribute("jakarta.servlet.request.X509Certificate")
        instanceof X509Certificate[] chain) || chain.length == 0) {
      return GATEWAY_AUTHENTICATION_REQUIRED;
    }
    try {
      chain[0].checkValidity();
      String pin = hashes.ofBytes(chain[0].getEncoded()).substring(7);
      return settings.gatewayCertSha256().equals(pin) ? null : GATEWAY_WORKLOAD_DENIED;
    } catch (CertificateException e) {
      return GATEWAY_WORKLOAD_DENIED;
    }
  }

  private <T> T body(HttpServletRequest request, Class<T> type) throws IOException {
    byte[] bytes = request.getInputStream().readNBytes(MAX_REQUEST + 1);
    if (bytes.length > MAX_REQUEST) throw new IOException("Mandate request too large");
    try (var parser = json.getFactory().createParser(bytes)) {
      parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
      T value = json.readerFor(type).readValue(parser);
      if (value == null) throw new IOException("Missing mandate request");
      return value;
    }
  }

  private static boolean validBearer(String value) {
    return value.length() <= 8192 && value.matches("(?i:Bearer) [A-Za-z0-9._~+/-]+=*");
  }

  private static ResponseEntity<?> error(int status, String code, String retryAfter) {
    var response = ResponseEntity.status(status).cacheControl(CacheControl.noStore());
    if (retryAfter != null) response.header(HttpHeaders.RETRY_AFTER, retryAfter);
    return response.body(Map.of("code", code));
  }

  private static boolean transientDatabaseFailure(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql && sql.getSQLState() != null) {
        String state = sql.getSQLState();
        return state.startsWith("08") || state.startsWith("40") || state.equals("25006")
            || state.equals("55P03") || state.equals("57014") || state.equals("57P01")
            || state.equals("57P02") || state.equals("57P03") || state.equals("53300");
      }
    }
    return false;
  }
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ouf.authorization.analysis-mandate.enabled", havingValue = "true")
@EnableConfigurationProperties(AnalysisMandateSettings.class)
class AnalysisMandateSecurity {
  @Bean
  @Order(0)
  SecurityFilterChain analysisMandateChain(HttpSecurity http) throws Exception {
    return http.securityMatcher(AnalysisMandateApi.PATH + "/**")
        .csrf(csrf -> csrf.disable())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // The controller authenticates the TLS peer. The HUMAN bearer is for the rights authority.
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .build();
  }
}
