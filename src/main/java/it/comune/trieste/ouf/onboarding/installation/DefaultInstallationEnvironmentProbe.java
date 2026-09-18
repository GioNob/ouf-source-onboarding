package it.comune.trieste.ouf.onboarding.installation;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class DefaultInstallationEnvironmentProbe implements InstallationEnvironmentProbe {
  private final HttpClient http;
  private final ObjectMapper json;

  public DefaultInstallationEnvironmentProbe(ObjectMapper json) {
    this.json = json;
    this.http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  @Override
  public List<Finding> inspect(JsonNode config) {
    List<Finding> out = new ArrayList<>();
    String issuer = config.at("/iam/issuerUrl").asText("");
    String tokenEndpoint = config.at("/iam/tokenEndpoint").asText("");
    String api = config.at("/gateway/publicApiBaseUrl").asText("");
    checkDns("iam.dns", issuer, out);
    checkDns("gateway.dns", api, out);
    checkIssuer(issuer, tokenEndpoint, out);
    checkApi(api, out);
    checkServiceRef("postgres.tcp", config.at("/persistence/postgresServiceRef").asText(""), out);

    JsonNode objectStorage = config.at("/persistence/objectStorage");
    if ("S3_COMPATIBLE".equals(objectStorage.path("mode").asText())) {
      checkServiceRef("object-storage.tcp", objectStorage.path("endpoint").asText(""), out);
    }
    return List.copyOf(out);
  }

  private void checkDns(String name, String raw, List<Finding> out) {
    try {
      URI uri = URI.create(raw);
      String host = uri.getHost();
      if (host == null || host.isBlank()) throw new IllegalArgumentException("host missing");
      InetAddress[] addresses = InetAddress.getAllByName(host);
      if (addresses.length == 0) throw new UnknownHostException(host);
      out.add(new Finding(name, "PASS", "resolved " + host));
    } catch (Exception e) {
      out.add(new Finding(name, "FAIL", safe(e)));
    }
  }

  private void checkIssuer(String issuer, String configuredTokenEndpoint, List<Finding> out) {
    try {
      URI discovery = URI.create(stripSlash(issuer) + "/.well-known/openid-configuration");
      HttpResponse<String> response = send(discovery);
      if (response.statusCode() != 200) {
        out.add(new Finding("iam.oidc-discovery", "FAIL", "HTTP " + response.statusCode()));
        return;
      }
      JsonNode body = json.readTree(response.body());
      String returnedIssuer = body.path("issuer").asText("");
      String returnedTokenEndpoint = body.path("token_endpoint").asText("");
      out.add(new Finding("iam.oidc-discovery", "PASS", "HTTP 200"));
      if (!issuer.equals(returnedIssuer)) {
        out.add(new Finding("iam.issuer-match", "FAIL", "issuer mismatch"));
      } else {
        out.add(new Finding("iam.issuer-match", "PASS", "issuer exact"));
      }
      if (!configuredTokenEndpoint.equals(returnedTokenEndpoint)) {
        out.add(new Finding("iam.token-endpoint-match", "FAIL", "token endpoint mismatch"));
      } else {
        out.add(new Finding("iam.token-endpoint-match", "PASS", "token endpoint exact"));
      }
    } catch (Exception e) {
      out.add(new Finding("iam.oidc-discovery", "FAIL", safe(e)));
    }
  }

  private void checkApi(String api, List<Finding> out) {
    try {
      HttpResponse<String> response = send(URI.create(api));
      int code = response.statusCode();
      if (code >= 200 && code < 500) {
        out.add(new Finding("gateway.https", "PASS", "HTTP " + code));
      } else {
        out.add(new Finding("gateway.https", "FAIL", "HTTP " + code));
      }
    } catch (Exception e) {
      out.add(new Finding("gateway.https", "FAIL", safe(e)));
    }
  }

  private HttpResponse<String> send(URI uri) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(5))
        .GET()
        .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private void checkServiceRef(String name, String ref, List<Finding> out) {
    try {
      URI uri = URI.create(ref);
      if (!"service".equals(uri.getScheme()) || uri.getHost() == null || uri.getPort() < 1)
        throw new IllegalArgumentException("expected service://host:port");
      try (var socket = new java.net.Socket()) {
        socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 3000);
      }
      out.add(new Finding(name, "PASS", "reachable " + uri.getHost() + ":" + uri.getPort()));
    } catch (Exception e) {
      out.add(new Finding(name, "FAIL", safe(e)));
    }
  }

  private String stripSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  private String safe(Exception e) {
    String name = e.getClass().getSimpleName();
    String message = e.getMessage();
    if (message == null || message.isBlank()) return name;
    if (message.length() > 160) message = message.substring(0, 160);
    return name + ": " + message;
  }
}
