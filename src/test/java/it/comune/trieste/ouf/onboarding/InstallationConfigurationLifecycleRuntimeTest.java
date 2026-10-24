package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.comune.trieste.ouf.onboarding.installation.InstallationConfigurationService;
import it.comune.trieste.ouf.onboarding.installation.InstallationEnvironmentProbe;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  @MockitoBean InstallationEnvironmentProbe environmentProbe;

  private InstallationConfigurationService.Actor actor() {
    return new InstallationConfigurationService.Actor("installer-human", UUID.randomUUID().toString());
  }

  @BeforeEach
  void clean() {
    when(environmentProbe.inspect(any())).thenReturn(List.of(
        new InstallationEnvironmentProbe.Finding("fixture", "PASS", "ok")));
    db.sql("""
      truncate ouf_installation.installation_environment_validation,
               ouf_installation.installation_configuration_lifecycle_event,
               ouf_installation.installation_configuration_active,
               ouf_installation.installation_configuration_revision
      cascade
      """).update();
  }

  private JsonNode valid(String id, String apiHost) {
    Map<String,Object> root = new LinkedHashMap<>();
    root.put("schemaVersion", "1.0");
    root.put("installationId", id);
    root.put("organization", Map.of(
        "organizationId", "org-a",
        "displayName", "Org A",
        "tenantId", "tenant-a",
        "timezone", "Europe/Rome"));
    root.put("environment", "production");
    root.put("networking", Map.of(
        "backendNetwork", "backend-a",
        "edgeNetwork", "edge-a",
        "gatewayControlNetwork", "control-a",
        "internalDnsStrategy", "REVERSE_PROXY_ALIAS"));
    root.put("iam", Map.of(
        "issuerUrl", "https://iam.example.test/realms/ouf",
        "tokenEndpoint", "https://iam.example.test/realms/ouf/token",
        "realm", "ouf",
        "humanAdminClientId", "human-admin",
        "gatewayAudience", "gateway",
        "workloadClients", Map.of("mcpServer", "mcp-server")));
    root.put("gateway", Map.of(
        "publicApiBaseUrl", apiHost,
        "publicMcpPath", "/mcp",
        "tlsTermination", "CADDY",
        "internalServiceRef", "service://gateway:9080"));
    root.put("persistence", Map.of(
        "postgresServiceRef", "service://postgres:5432",
        "moduleDatabases", Map.of("mcp", "mcpdb"),
        "objectStorage", Map.of("mode", "NONE")));
    root.put("secrets", Map.of(
        "provider", "FILES",
        "references", Map.of(
            "mcpClientSecret", "/run/secrets/mcp-client-secret",
            "dbPassword", "/run/secrets/db-password",
            "mcpFingerprintKey", "/run/secrets/mcp-fingerprint-key")));
    root.put("observability", Map.of(
        "metricsEnabled", true,
        "logSink", "local"));
    root.put("lifecycle", Map.of(
        "revision", 1,
        "status", "VALIDATED",
        "checksum", "placeholder"));
    return json.valueToTree(root);
  }

  private InstallationConfigurationService.ExpectedActive current(String id) {
    return service.active(id).map(InstallationConfigurationService.ExpectedActive::from)
        .orElseGet(InstallationConfigurationService.ExpectedActive::absent);
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
  void credentialNamedReferencesAreAllowedButInlineValuesAreRejected() {
    var revision = service.create(valid("install-ref", "https://api.example.test"), actor());
    assertThat(revision.validationState()).isEqualTo("VALIDATED");
    assertThat(revision.validationFindings()).isEmpty();
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

    assertThatThrownBy(() -> service.activate("install-b", revision.revision(), actor(), current("install-b")))
        .hasMessageContaining("INSTALLATION_REVISION_NOT_VALIDATED");
  }


  @Test
  void latestEnvironmentFailureBlocksActivationAndEvidenceIsImmutable() {
    var revision = service.create(valid("install-env", "https://api.example.test"), actor());

    assertThatThrownBy(() -> service.activate("install-env", revision.revision(), actor(), current("install-env")))
        .hasMessageContaining("INSTALLATION_ENVIRONMENT_VALIDATION_REQUIRED");

    var pass = service.validateEnvironment("install-env", revision.revision(), actor());
    assertThat(pass.overallStatus()).isEqualTo("PASS");
    assertThat(service.activate("install-env", revision.revision(), actor(), current("install-env")).revision()).isEqualTo(1);

    when(environmentProbe.inspect(any())).thenReturn(List.of(
        new InstallationEnvironmentProbe.Finding("gateway.https", "FAIL", "unreachable")));
    var fail = service.validateEnvironment("install-env", revision.revision(), actor());
    assertThat(fail.overallStatus()).isEqualTo("FAIL");

    db.sql("delete from ouf_installation.installation_configuration_active where installation_id='install-env'").update();
    assertThatThrownBy(() -> service.activate("install-env", revision.revision(), actor(), current("install-env")))
        .hasMessageContaining("INSTALLATION_ENVIRONMENT_VALIDATION_REQUIRED");

    assertThatThrownBy(() ->
        db.sql("delete from ouf_installation.installation_environment_validation").update())
        .hasStackTraceContaining("installation environment validation evidence is append-only");
  }

  @Test
  void activationSupersedeRollbackAndRevocationAreAudited() {
    var r1 = service.create(valid("install-c", "https://api-v1.example.test"), actor());
    var r2 = service.create(valid("install-c", "https://api-v2.example.test"), actor());

    service.validateEnvironment("install-c", r1.revision(), actor());
    service.validateEnvironment("install-c", r2.revision(), actor());
    assertThat(service.activate("install-c", r1.revision(), actor(), current("install-c")).revision()).isEqualTo(1);
    assertThat(service.activate("install-c", r2.revision(), actor(), current("install-c")).revision()).isEqualTo(2);
    assertThat(service.activate("install-c", r1.revision(), actor(), current("install-c")).revision()).isEqualTo(1);

    var actions = db.sql("""
        select action from ouf_installation.installation_configuration_lifecycle_event
        where installation_id='install-c'
        order by occurred_at,event_id
        """).query(String.class).list();
    assertThat(actions)
        .contains("VALIDATED", "ACTIVATED", "SUPERSEDED", "ROLLBACK_ACTIVATED");

    service.revoke("install-c", r1.revision(), actor(), current("install-c"));
    assertThat(service.active("install-c")).isEmpty();

    assertThatThrownBy(() -> service.activate("install-c", r1.revision(), actor(), current("install-c")))
        .hasMessageContaining("INSTALLATION_REVISION_REVOKED");

    assertThatThrownBy(() ->
        db.sql("delete from ouf_installation.installation_configuration_lifecycle_event").update())
        .hasStackTraceContaining("installation configuration lifecycle is append-only");
  }

  @Test
  void createCorrelationIsDeduplicatedAndChangedPayloadCannotReuseIt() {
    var actor = new InstallationConfigurationService.Actor("human-a", "create-one");
    var payload = valid("install-dedup", "https://api.example.test");
    var first = service.create(payload, actor);
    assertThat(service.create(payload.deepCopy(), actor)).isEqualTo(first);
    assertThat(db.sql("select count(*) from ouf_installation.installation_configuration_revision").query(Long.class).single()).isEqualTo(1);
    assertThatThrownBy(() -> service.create(valid("install-dedup", "https://changed.example.test"), actor))
        .hasMessageContaining("INSTALLATION_CREATE_CORRELATION_CONFLICT");
    assertThat(service.create(payload, new InstallationConfigurationService.Actor("human-b", "create-one")).revision()).isEqualTo(2);
  }

  @Test
  void staleActivationAfterRollbackCannotReuseTheSameRevisionAndChecksum() {
    var a = service.create(valid("install-aba", "https://api-a.example.test"), actor());
    var b = service.create(valid("install-aba", "https://api-b.example.test"), actor());
    service.validateEnvironment("install-aba", a.revision(), actor());
    service.validateEnvironment("install-aba", b.revision(), actor());
    var first = service.activate("install-aba", a.revision(), actor(), current("install-aba"));
    var stale = InstallationConfigurationService.ExpectedActive.from(first);
    service.activate("install-aba", b.revision(), actor(), stale);
    var rolledBack = service.activate("install-aba", a.revision(), actor(), current("install-aba"));
    assertThat(rolledBack.checksum()).isEqualTo(first.checksum());
    assertThat(rolledBack.generation()).isGreaterThan(first.generation());
    assertThatThrownBy(() -> service.activate("install-aba", b.revision(), actor(), stale))
        .hasMessageContaining("INSTALLATION_ACTIVE_PRECONDITION_FAILED");
    assertThatThrownBy(() -> service.revoke("install-aba", a.revision(), actor(), stale))
        .hasMessageContaining("INSTALLATION_ACTIVE_PRECONDITION_FAILED");
    assertThat(service.active("install-aba")).contains(rolledBack);
  }

  @Test
  void competingOperatorsWithOneExpectedGenerationCanCommitOnlyOneActivation() throws Exception {
    String id = "install-race";
    var a = service.create(valid(id, "https://api-a.example.test"), actor());
    var b = service.create(valid(id, "https://api-b.example.test"), actor());
    var c = service.create(valid(id, "https://api-c.example.test"), actor());
    for (var rev : List.of(a, b, c)) service.validateEnvironment(id, rev.revision(), actor());
    service.activate(id, a.revision(), actor(), current(id));
    var expected = current(id);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    var start = new java.util.concurrent.CountDownLatch(1);
    try {
      var futures = new ArrayList<java.util.concurrent.Future<String>>();
      for (var target : List.of(b, c)) futures.add(pool.submit(() -> {
        start.await();
        try { service.activate(id, target.revision(), actor(), expected); return "COMMITTED"; }
        catch (IllegalStateException error) { return error.getMessage(); }
      }));
      start.countDown();
      var results = new ArrayList<String>();
      for (var future : futures) results.add(future.get(20, java.util.concurrent.TimeUnit.SECONDS));
      assertThat(results).containsExactlyInAnyOrder("COMMITTED", "INSTALLATION_ACTIVE_PRECONDITION_FAILED");
      assertThat(service.active(id).orElseThrow().generation()).isEqualTo(expected.generation() + 1);
    } finally { start.countDown(); pool.shutdownNow(); }
  }

  @Test
  void environmentProbeHasNoTransactionAndRefusesAnOuterTransaction() {
    var revision = service.create(valid("install-no-tx", "https://api.example.test"), actor());
    when(environmentProbe.inspect(any())).thenAnswer(invocation -> {
      assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      return List.of(new InstallationEnvironmentProbe.Finding("fixture", "PASS", "ok"));
    });
    assertThat(service.validateEnvironment("install-no-tx", revision.revision(), actor()).overallStatus()).isEqualTo("PASS");
    org.mockito.Mockito.clearInvocations(environmentProbe);
    var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
    assertThatThrownBy(() -> transaction.execute(status -> service.validateEnvironment("install-no-tx", revision.revision(), actor())))
        .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    org.mockito.Mockito.verifyNoInteractions(environmentProbe);
  }

  @Test
  void revocationDuringProbeWinsWithoutHoldingTheInstallationLock() throws Exception {
    String id = "install-revoke-probe";
    var revision = service.create(valid(id, "https://api.example.test"), actor());
    var started = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    when(environmentProbe.inspect(any())).thenAnswer(invocation -> {
      assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      started.countDown();
      assertThat(release.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      return List.of(new InstallationEnvironmentProbe.Finding("fixture", "PASS", "ok"));
    });
    var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      var future = pool.submit(() -> service.validateEnvironment(id, revision.revision(), actor()));
      assertThat(started.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      service.revoke(id, revision.revision(), actor(), current(id));
      release.countDown();
      assertThatThrownBy(() -> future.get(20, java.util.concurrent.TimeUnit.SECONDS))
          .hasCauseInstanceOf(IllegalStateException.class).hasStackTraceContaining("INSTALLATION_REVISION_REVOKED");
      assertThat(service.latestEnvironmentValidation(id, revision.revision())).isEmpty();
    } finally { release.countDown(); pool.shutdownNow(); }
  }
}
