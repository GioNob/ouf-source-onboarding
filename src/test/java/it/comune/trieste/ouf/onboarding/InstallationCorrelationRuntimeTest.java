package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.installation.InstallationConfigurationService;
import it.comune.trieste.ouf.onboarding.installation.InstallationEnvironmentProbe;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class InstallationCorrelationRuntimeTest {
  @DynamicPropertySource
  static void db(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> System.getenv("OUF_ONB_DB_URL"));
    r.add("spring.datasource.username", () -> System.getenv("OUF_ONB_DB_USER"));
    r.add("spring.datasource.password", () -> System.getenv("OUF_ONB_DB_PASSWORD"));
  }

  @Autowired InstallationConfigurationService configurations;
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

  @Test
  void createAndEnvironmentValidationPersistCorrelationEvidence() throws Exception {
    var created = configurations.create(
        lab(),
        new InstallationConfigurationService.Actor("installer-human", "corr-create"));

    assertThat(db.sql("""
        select created_correlation_id
        from ouf_installation.installation_configuration_revision
        where installation_id=:id and revision=:rev
        """)
        .param("id", created.installationId())
        .param("rev", created.revision())
        .query(String.class).single())
        .isEqualTo("corr-create");

    var validation = configurations.validateEnvironment(
        created.installationId(),
        created.revision(),
        new InstallationConfigurationService.Actor("installer-human", "corr-env"));

    assertThat(validation.correlationId()).isEqualTo("corr-env");
    assertThat(db.sql("""
        select correlation_id
        from ouf_installation.installation_environment_validation
        where validation_id=:validation
        """)
        .param("validation", validation.validationId())
        .query(String.class).single())
        .isEqualTo("corr-env");
  }
}
