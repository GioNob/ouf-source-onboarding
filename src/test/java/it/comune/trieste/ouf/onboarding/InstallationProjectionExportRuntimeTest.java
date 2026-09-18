package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

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
class InstallationProjectionExportRuntimeTest {
  @DynamicPropertySource
  static void db(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> System.getenv("OUF_ONB_DB_URL"));
    r.add("spring.datasource.username", () -> System.getenv("OUF_ONB_DB_USER"));
    r.add("spring.datasource.password", () -> System.getenv("OUF_ONB_DB_PASSWORD"));
  }

  @Autowired InstallationConfigurationService configurations;
  @Autowired InstallationProjectionExportService exports;
  @Autowired ObjectMapper json;
  @Autowired JdbcClient db;
  @MockBean InstallationEnvironmentProbe environmentProbe;

  @BeforeEach
  void clean() {
    when(environmentProbe.inspect(any())).thenReturn(List.of(
        new InstallationEnvironmentProbe.Finding("fixture", "PASS", "ok")));
    db.sql("""
      truncate ouf_installation.installation_projection_export_event,
               ouf_installation.installation_environment_validation,
               ouf_installation.installation_configuration_lifecycle_event,
               ouf_installation.installation_configuration_active,
               ouf_installation.installation_configuration_revision
      cascade
      """).update();
  }

  private JsonNode lab() throws Exception {
    return json.readTree(Files.readString(
        Path.of("contracts/installation/examples/ouf-lab-v1.json")));
  }

  private InstallationConfigurationService.Actor lifecycleActor() {
    return new InstallationConfigurationService.Actor(
        "installer-human", UUID.randomUUID().toString());
  }

  private InstallationProjectionExportService.Actor exportActor(String correlation) {
    return new InstallationProjectionExportService.Actor(
        "installer-human", correlation);
  }

  @Test
  void exportsOnlyActiveRevisionAndAuditsImmutableEvidence() throws Exception {
    var r1 = configurations.create(lab(), lifecycleActor());
    var changed = (com.fasterxml.jackson.databind.node.ObjectNode) lab();
    ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("gateway"))
        .put("publicApiBaseUrl", "https://api-v2.ouf-lab.it");
    var r2 = configurations.create(changed, lifecycleActor());

    configurations.validateEnvironment(r1.installationId(), r1.revision(), lifecycleActor());
    configurations.validateEnvironment(r2.installationId(), r2.revision(), lifecycleActor());
    configurations.activate(r1.installationId(), r1.revision(), lifecycleActor());

    var projection = exports.exportActive(
        r1.installationId(), OptionalLong.of(r1.revision()), exportActor("corr-export-1"));

    assertThat(projection.revision()).isEqualTo(r1.revision());
    assertThat(projection.checksum()).isEqualTo(r1.checksum());

    var audit = db.sql("""
        select installation_id,revision,configuration_checksum,actor_subject,correlation_id
        from ouf_installation.installation_projection_export_event
        """).query((rs,n) -> List.of(
            rs.getString(1), Long.toString(rs.getLong(2)), rs.getString(3),
            rs.getString(4), rs.getString(5))).single();

    assertThat(audit).containsExactly(
        r1.installationId(),
        Long.toString(r1.revision()),
        r1.checksum(),
        "installer-human",
        "corr-export-1");

    assertThatThrownBy(() -> exports.exportActive(
        r1.installationId(), OptionalLong.of(r2.revision()), exportActor("corr-export-2")))
        .hasMessageContaining("INSTALLATION_EXPORT_REVISION_NOT_ACTIVE");

    assertThatThrownBy(() ->
        db.sql("delete from ouf_installation.installation_projection_export_event").update())
        .hasStackTraceContaining("installation projection export audit is append-only");

    String serialized = json.writeValueAsString(projection);
    assertThat(serialized)
        .contains("MCP_OIDC_CLIENT_SECRET_FILE")
        .contains("/opt/ouf/secrets/mcp-client-secret")
        .doesNotContain("client_secret")
        .doesNotContain("Bearer ")
        .doesNotContain("password=");
  }


  @Test
  void exportsValidatedCandidateWithoutActivePointerAndAuditsPurpose() throws Exception {
    var r1 = configurations.create(lab(), lifecycleActor());

    var projection = exports.exportCandidate(
        r1.installationId(), r1.revision(), exportActor("corr-candidate-1"));

    assertThat(projection.revision()).isEqualTo(r1.revision());
    assertThat(projection.checksum()).isEqualTo(r1.checksum());
    assertThat(configurations.active(r1.installationId())).isEmpty();

    var audit = db.sql("""
        select export_purpose,correlation_id
        from ouf_installation.installation_projection_export_event
        where installation_id=:id and revision=:rev
        """)
        .param("id", r1.installationId())
        .param("rev", r1.revision())
        .query((rs,n) -> List.of(rs.getString(1), rs.getString(2))).single();

    assertThat(audit).containsExactly("CANDIDATE", "corr-candidate-1");
  }

  @Test
  void exportFailsWhenInstallationHasNoActiveRevision() throws Exception {
    var revision = configurations.create(lab(), lifecycleActor());

    assertThatThrownBy(() -> exports.exportActive(
        revision.installationId(), OptionalLong.empty(), exportActor("corr-no-active")))
        .hasMessageContaining("INSTALLATION_ACTIVE_NOT_FOUND");

    Integer count = db.sql("""
        select count(*) from ouf_installation.installation_projection_export_event
        """).query(Integer.class).single();
    assertThat(count).isZero();
  }
}
