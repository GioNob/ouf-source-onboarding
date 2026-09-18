package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class InstallationConfigurationContractTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Path SCHEMA = Path.of("contracts/installation/installation-configuration-v1.json");
  private static final Path LAB = Path.of("contracts/installation/examples/ouf-lab-v1.json");

  @Test
  void productSchemaContainsNoLaboratoryCoordinates() throws Exception {
    String raw = Files.readString(SCHEMA);
    assertThat(raw)
        .doesNotContain("ouf-lab.it")
        .doesNotContain("62.83.33.202")
        .doesNotContain("ouf-backend")
        .doesNotContain("ouf-postgres");
    JsonNode schema = JSON.readTree(raw);
    assertThat(schema.path("required")).isNotEmpty();
    assertThat(schema.path("properties").has("iam")).isTrue();
    assertThat(schema.path("properties").has("gateway")).isTrue();
    assertThat(schema.path("properties").has("secrets")).isTrue();
    assertThat(schema.path("properties").has("lifecycle")).isTrue();
  }

  @Test
  void laboratoryExampleUsesSecretReferencesInsteadOfSecretValues() throws Exception {
    JsonNode lab = JSON.readTree(Files.readString(LAB));
    assertThat(lab.path("iam").path("issuerUrl").asText()).isEqualTo("https://auth.ouf-lab.it/realms/ouf");
    assertThat(lab.path("gateway").path("publicApiBaseUrl").asText()).isEqualTo("https://api.ouf-lab.it");
    JsonNode references = lab.path("secrets").path("references");
    assertThat(references.path("mcpClientSecret").asText()).startsWith("/opt/ouf/secrets/");
    assertThat(references.path("mcpFingerprintKey").asText()).startsWith("/opt/ouf/secrets/");
    assertThat(Files.readString(LAB))
        .doesNotContain("client_secret")
        .doesNotContain("password=")
        .doesNotContain("Bearer ");
  }

  @Test
  void installationLifecycleStartsVersionedAndNonActiveInLaboratoryExample() throws Exception {
    JsonNode lab = JSON.readTree(Files.readString(LAB));
    assertThat(lab.path("schemaVersion").asText()).isEqualTo("1.0");
    assertThat(lab.path("lifecycle").path("revision").asInt()).isEqualTo(1);
    assertThat(lab.path("lifecycle").path("status").asText()).isEqualTo("VALIDATED");
  }
}
