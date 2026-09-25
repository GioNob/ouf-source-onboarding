package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import it.comune.trieste.ouf.onboarding.installation.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.*;

@SpringBootTest
class InstallationRuntimeProjectionTest {
  @DynamicPropertySource
  static void db(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> System.getenv("OUF_ONB_DB_URL"));
    r.add("spring.datasource.username", () -> System.getenv("OUF_ONB_DB_USER"));
    r.add("spring.datasource.password", () -> System.getenv("OUF_ONB_DB_PASSWORD"));
  }

  @Autowired InstallationConfigurationService configurations;
  @Autowired InstallationRuntimeProjectionService projections;
  @Autowired ObjectMapper json;
  @Autowired JdbcClient db;
  @MockBean InstallationEnvironmentProbe environmentProbe;

  @BeforeEach
  void clean() {
    db.sql("""
      truncate ouf_installation.installation_environment_validation,
               ouf_installation.installation_configuration_lifecycle_event,
               ouf_installation.installation_configuration_active,
               ouf_installation.installation_configuration_revision
      cascade
      """).update();
  }

  private InstallationConfigurationService.Actor actor() {
    return new InstallationConfigurationService.Actor("installer-human", UUID.randomUUID().toString());
  }

  @Test
  void laboratoryExampleProjectsRuntimeCoordinatesWithoutSecretValues() throws Exception {
    JsonNode lab = json.readTree(Files.readString(
        Path.of("contracts/installation/examples/ouf-lab-v1.json")));

    var revision = configurations.create(lab, actor());
    assertThat(revision.validationState()).isEqualTo("VALIDATED");

    var projection = projections.project(revision.installationId(), revision.revision());

    assertThat(projection.caddy().backendNetwork()).isEqualTo("ouf-backend");
    assertThat(projection.caddy().edgeNetwork()).isEqualTo("ouf-edge");
    assertThat(projection.caddy().internalIssuerHost()).isEqualTo("auth.ouf-lab.it");
    assertThat(projection.caddy().internalApiHost()).isEqualTo("api.ouf-lab.it");
    assertThat(projection.caddy().oidcDiscoveryUrl())
        .isEqualTo("https://auth.ouf-lab.it/realms/ouf/.well-known/openid-configuration");

    assertThat(projection.gateway().issuerUrl())
        .isEqualTo("https://auth.ouf-lab.it/realms/ouf");
    assertThat(projection.gateway().requiredAudience()).isEqualTo("ouf-api-gateway");
    assertThat(projection.gateway().publicApiBaseUrl()).isEqualTo("https://api.ouf-lab.it");

    assertThat(projection.iam().workloadClients())
        .containsEntry("mcpServer", "ouf-mcp-server");

    assertThat(projection.mcp().environment())
        .containsEntry("MCP_OIDC_CLIENT_ID", "ouf-mcp-server")
        .containsEntry("MCP_OIDC_TOKEN_ENDPOINT",
            "https://auth.ouf-lab.it/realms/ouf/protocol/openid-connect/token")
        .containsEntry("MCP_GATEWAY_ENDPOINT",
            "https://api.ouf-lab.it/internal/capabilities/v1/execute")
        .containsEntry("MCP_AUTHORIZATION_BUNDLE_ENDPOINT",
            "https://api.ouf-lab.it/internal/capabilities/v1/authorization/policy-bundle/active")
        .containsEntry("MCP_GATEWAY_RECOVERY_ENDPOINT",
            "https://api.ouf-lab.it/internal/capabilities/v1/recovery");

    assertThat(projection.mcp().secretReferences())
        .containsEntry("MCP_OIDC_CLIENT_SECRET_FILE", "/opt/ouf/secrets/mcp-client-secret")
        .containsEntry("MCP_FINGERPRINT_KEY_FILE", "/opt/ouf/secrets/mcp-fingerprint-key");

    assertThat(projection.onboarding().environment())
        .containsEntry("OUF_RUNTIME_PUBLICATIONS_TENANT_ID", "ouf-lab");

    String serialized = json.writeValueAsString(projection);
    assertThat(serialized)
        .doesNotContain("client_secret")
        .doesNotContain("Bearer ")
        .doesNotContain("password=");
  }
  @Test
  void projectsAdditionalWorkloadBindingsWithoutSecretValues() throws Exception {
    var lab = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Files.readString(
        Path.of("contracts/installation/examples/ouf-lab-v1.json")));
    ((com.fasterxml.jackson.databind.node.ObjectNode) lab.path("iam").path("workloadClients"))
        .put("ingestion", "ouf-ingestion");

    var revision = configurations.create(lab, actor());
    var projection = projections.project(revision.installationId(), revision.revision());

    assertThat(projection.iam().workloadClients())
        .containsEntry("mcpServer", "ouf-mcp-server")
        .containsEntry("ingestion", "ouf-ingestion");
    assertThat(json.writeValueAsString(projection))
        .doesNotContain("client_secret")
        .doesNotContain("Bearer ");
  }
}
