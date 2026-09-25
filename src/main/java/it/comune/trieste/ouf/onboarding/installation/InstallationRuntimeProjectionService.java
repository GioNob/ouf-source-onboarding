package it.comune.trieste.ouf.onboarding.installation;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.*;

import org.springframework.stereotype.Service;

@Service
public class InstallationRuntimeProjectionService {
  private final InstallationConfigurationService configurations;

  public InstallationRuntimeProjectionService(InstallationConfigurationService configurations) {
    this.configurations = configurations;
  }

  public record CaddyProjection(
      String backendNetwork,
      String edgeNetwork,
      String internalIssuerHost,
      String internalApiHost,
      String oidcDiscoveryUrl) {}

  public record GatewayProjection(
      String issuerUrl,
      String requiredAudience,
      String publicApiBaseUrl,
      String internalServiceRef) {}

  public record IamProjection(
      Map<String,String> workloadClients) {}

  public record McpProjection(
      Map<String,String> environment,
      Map<String,String> secretReferences) {}

  public record OnboardingProjection(
      Map<String,String> environment) {}

  public record ServicesProjection(
      Map<String,String> bindings) {}

  public record Projection(
      String installationId,
      long revision,
      String checksum,
      CaddyProjection caddy,
      GatewayProjection gateway,
      IamProjection iam,
      McpProjection mcp,
      OnboardingProjection onboarding,
      ServicesProjection services) {}

  public Projection project(String installationId, long revision) {
    var source = configurations.get(installationId, revision);
    if (!"VALIDATED".equals(source.validationState()))
      throw new IllegalStateException("INSTALLATION_REVISION_NOT_VALIDATED");
    JsonNode p = source.payload();

    String issuer = required(p, "/iam/issuerUrl");
    String tokenEndpoint = required(p, "/iam/tokenEndpoint");
    String api = stripSlash(required(p, "/gateway/publicApiBaseUrl"));
    String issuerHost = host(issuer, "INSTALLATION_ISSUER_HOST_INVALID");
    String apiHost = host(api, "INSTALLATION_API_HOST_INVALID");
    String mcpClientId = required(p, "/iam/workloadClients/mcpServer");
    String tenantId = required(p, "/organization/tenantId");

    Map<String,String> serviceBindings = new TreeMap<>();
    JsonNode serviceNode = p.at("/networking/serviceBindings");
    if (serviceNode.isObject()) {
      serviceNode.fields().forEachRemaining(e -> {
        if (e.getValue().isTextual() && !e.getValue().asText().isBlank())
          serviceBindings.put(e.getKey(), e.getValue().asText());
      });
    }

    Map<String,String> workloadClients = new TreeMap<>();
    JsonNode workloadNode = p.at("/iam/workloadClients");
    if (workloadNode.isObject()) {
      workloadNode.fields().forEachRemaining(e -> {
        if (e.getValue().isTextual() && !e.getValue().asText().isBlank())
          workloadClients.put(e.getKey(), e.getValue().asText());
      });
    }

    Map<String,String> refs = new TreeMap<>();
    JsonNode refNode = p.at("/secrets/references");
    if (refNode.isObject()) {
      refNode.fields().forEachRemaining(e -> {
        if (e.getValue().isTextual()) refs.put(e.getKey(), e.getValue().asText());
      });
    }

    String mcpClientSecret = requiredRef(refs, "mcpClientSecret");
    String mcpFingerprintKey = requiredRef(refs, "mcpFingerprintKey");

    CaddyProjection caddy = new CaddyProjection(
        required(p, "/networking/backendNetwork"),
        required(p, "/networking/edgeNetwork"),
        issuerHost,
        apiHost,
        stripSlash(issuer) + "/.well-known/openid-configuration");

    GatewayProjection gateway = new GatewayProjection(
        issuer,
        required(p, "/iam/gatewayAudience"),
        api,
        required(p, "/gateway/internalServiceRef"));

    Map<String,String> env = new TreeMap<>();
    env.put("MCP_OIDC_TOKEN_ENDPOINT", tokenEndpoint);
    env.put("MCP_OIDC_CLIENT_ID", mcpClientId);
    env.put("MCP_GATEWAY_ENDPOINT", api + "/internal/capabilities/v1/execute");
    env.put("MCP_AUTHORIZATION_BUNDLE_ENDPOINT",
        api + "/internal/capabilities/v1/authorization/policy-bundle/active");
    env.put("MCP_GATEWAY_RECOVERY_ENDPOINT", api + "/internal/capabilities/v1/recovery");

    Map<String,String> secretRefs = new TreeMap<>();
    secretRefs.put("MCP_OIDC_CLIENT_SECRET_FILE", mcpClientSecret);
    secretRefs.put("MCP_FINGERPRINT_KEY_FILE", mcpFingerprintKey);

    Map<String,String> onboardingEnv = new TreeMap<>();
    onboardingEnv.put("OUF_RUNTIME_PUBLICATIONS_TENANT_ID", tenantId);

    return new Projection(
        installationId,
        revision,
        source.checksum(),
        caddy,
        gateway,
        new IamProjection(Map.copyOf(workloadClients)),
        new McpProjection(Map.copyOf(env), Map.copyOf(secretRefs)),
        new OnboardingProjection(Map.copyOf(onboardingEnv)),
        new ServicesProjection(Map.copyOf(serviceBindings)));
  }

  public Projection projectActive(String installationId) {
    var active = configurations.active(installationId)
        .orElseThrow(() -> new NoSuchElementException("INSTALLATION_ACTIVE_NOT_FOUND"));
    return project(installationId, active.revision());
  }

  private String required(JsonNode root, String pointer) {
    JsonNode n = root.at(pointer);
    if (!n.isTextual() || n.asText().isBlank())
      throw new IllegalStateException("INSTALLATION_PROJECTION_REQUIRED:" + pointer);
    return n.asText();
  }

  private String requiredRef(Map<String,String> refs, String key) {
    String value = refs.get(key);
    if (value == null || value.isBlank())
      throw new IllegalStateException("INSTALLATION_SECRET_REFERENCE_REQUIRED:" + key);
    return value;
  }

  private String host(String raw, String code) {
    try {
      String host = URI.create(raw).getHost();
      if (host == null || host.isBlank()) throw new IllegalArgumentException(code);
      return host;
    } catch (RuntimeException e) {
      throw new IllegalStateException(code, e);
    }
  }

  private String stripSlash(String raw) {
    return raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
  }
}
