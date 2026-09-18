package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import it.comune.trieste.ouf.onboarding.installation.InstallationConfigurationService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.*;

@SpringBootTest
class InstallationConfigurationLifecycleRuntimeTest {
  @DynamicPropertySource
  static void db(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> System.getenv("OUF_ONB_DB_URL"));
    r.add("spring.datasource.username", () -> System.getenv("OUF_ONB_DB_USER"));
    r.add("spring.datasource.password", () -> System.getenv("OUF_ONB_DB_PASSWORD"));
  }

  @Autowired InstallationConfigurationService service;
  @Autowired ObjectMapper json;
  @Autowired JdbcClient db;

  private InstallationConfigurationService.Actor actor() {
    return new InstallationConfigurationService.Actor("installer-human", UUID.randomUUID().toString());
  }

  @BeforeEach
  void clean() {
    db.sql("""
      truncate ouf_installation.installation_configuration_lifecycle_event,
               ouf_installation.installation_configuration_active,
               ouf_installation.installation_configuration_revision
      cascade
      """).update();
  }

  private JsonNode valid(String id, String apiHost) {
    return json.valueToTree(Map.of(
        "schemaVersion", "1.0",
        "installationId", id,
        "organization", Map.of(
            "organizationId", "org-a",
            "displayName", "Org A",
            "tenantId", "tenant-a",
            "timezone", "Europe/Rome"),
        "environment", "production",
        "networking", Map.of(
            "backendNetwork", "backend-a",
            "edgeNetwork", "edge-a",
            "gatewayControlNetwork", "control-a",
            "internalDnsStrategy", "REVERSE_PROXY_ALIAS"),
        "iam", Map.of(
            "issuerUrl", "https://iam.example.test/realms/ouf",
            "realm", "ouf",
            "humanAdminClientId", "human-admin",
            "gatewayAudience", "gateway"),
        "gateway", Map.of(
            "publicApiBaseUrl", apiHost,
            "publicMcpPath", "/mcp",
            "tlsTermination", "CADDY",
            "internalServiceRef", "service://gateway:9080"),
        "persistence", Map.of(
            "postgresServiceRef", "service://postgres:5432",
            "moduleDatabases", Map.of("mcp", "mcpdb"),
            "objectStorage", Map.of("mode", "NONE")),
        "secrets", Map.of(
            "provider", "FILES",
            "references", Map.of(
                "mcpClientSecret", "/run/secrets/mcp-client-secret",
                "mcpFingerprintKey", "/run/secrets/mcp-fingerprint-key")),
        "observability", Map.of(
            "metricsEnabled", true,
            "logSink", "local"),
        "lifecycle", Map.of(
            "revision", 1,
            "status", "VALIDATED",
            "checksum", "placeholder")
    ));
  }

  @Test
  void revisionsAreImmutableAndChecksummed() {
    var first = service.create(valid("install-a", "https://api-a.example.test"), actor());
    var second = service.create(valid("install-a", "https://api-b.example.test"), actor());

    assertThat(first.revision()).isEqualTo(1);
    assertThat(second.revision()).isEqualTo(2);
    assertThat(first.validationState()).isEqualTo("VALIDATED");
    assertThat(first.checksum()).hasSize(64).isNotEqualTo(second.checksum());

    assertThatThrownBy(() ->
        db.sql("""
          update ouf_installation.installation_configuration_revision
          set checksum=:x where installation_id='install-a' and revision=1
          """).param("x", "0".repeat(64)).update())
        .hasStackTraceContaining("installation configuration revisions are immutable");
  }

  @Test
  void rejectedConfigurationIsPersistedButCannotActivate() {
    ObjectNode bad = (ObjectNode) valid("install-b", "https://api.example.test");
    ((ObjectNode) bad.path("iam")).put("issuerUrl", "http://insecure.example.test");
    ((ObjectNode) bad.path("secrets")).put("clientSecret", "forbidden");

    var revision = service.create(bad, actor());

    assertThat(revision.validationState()).isEqualTo("REJECTED");
    assertThat(revision.validationFindings().toString())
        .contains("INSTALLATION_HTTPS_REQUIRED")
        .contains("INSTALLATION_SECRET_VALUE_FORBIDDEN");

    assertThatThrownBy(() -> service.activate("install-b", revision.revision(), actor()))
        .hasMessageContaining("INSTALLATION_REVISION_NOT_VALIDATED");
  }

  @Test
  void activationSupersedeRollbackAndRevocationAreAudited() {
    var r1 = service.create(valid("install-c", "https://api-v1.example.test"), actor());
    var r2 = service.create(valid("install-c", "https://api-v2.example.test"), actor());

    assertThat(service.activate("install-c", r1.revision(), actor()).revision()).isEqualTo(1);
    assertThat(service.activate("install-c", r2.revision(), actor()).revision()).isEqualTo(2);
    assertThat(service.activate("install-c", r1.revision(), actor()).revision()).isEqualTo(1);

    var actions = db.sql("""
        select action from ouf_installation.installation_configuration_lifecycle_event
        where installation_id='install-c'
        order by occurred_at,event_id
        """).query(String.class).list();
    assertThat(actions)
        .contains("VALIDATED", "ACTIVATED", "SUPERSEDED", "ROLLBACK_ACTIVATED");

    service.revoke("install-c", r1.revision(), actor());
    assertThat(service.active("install-c")).isEmpty();

    assertThatThrownBy(() -> service.activate("install-c", r1.revision(), actor()))
        .hasMessageContaining("INSTALLATION_REVISION_REVOKED");

    assertThatThrownBy(() ->
        db.sql("delete from ouf_installation.installation_configuration_lifecycle_event").update())
        .hasStackTraceContaining("installation configuration lifecycle is append-only");
  }
}
