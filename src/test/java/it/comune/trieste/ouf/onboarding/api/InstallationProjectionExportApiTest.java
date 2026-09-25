package it.comune.trieste.ouf.onboarding.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import it.comune.trieste.ouf.authorization.TestAuthorization;
import it.comune.trieste.ouf.onboarding.installation.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class InstallationProjectionExportApiTest {
  private InstallationRuntimeProjectionService.Projection projection() {
    return new InstallationRuntimeProjectionService.Projection(
        "install-a",
        7,
        "a".repeat(64),
        new InstallationRuntimeProjectionService.CaddyProjection(
            "backend-a",
            "edge-a",
            "iam.example.test",
            "api.example.test",
            "https://iam.example.test/realms/ouf/.well-known/openid-configuration"),
        new InstallationRuntimeProjectionService.GatewayProjection(
            "https://iam.example.test/realms/ouf",
            "gateway-audience",
            "https://api.example.test",
            "service://gateway:9080"),
        new InstallationRuntimeProjectionService.IamProjection(
            Map.of("mcpServer", "mcp-workload")),
        new InstallationRuntimeProjectionService.McpProjection(
            Map.of("MCP_OIDC_CLIENT_ID", "mcp-workload"),
            Map.of("MCP_OIDC_CLIENT_SECRET_FILE", "/run/secrets/mcp-client-secret")),
        new InstallationRuntimeProjectionService.OnboardingProjection(
            Map.of("OUF_RUNTIME_PUBLICATIONS_TENANT_ID", "tenant-a")),
        new InstallationRuntimeProjectionService.ServicesProjection(
            Map.of("ouf-semantic-registry", "semantic-runtime")));
  }

  private MockHttpServletRequest request(String actor, Set<String> capabilities) {
    var request = new MockHttpServletRequest();
    TestAuthorization.bind(request, "principal-1", actor, capabilities);
    return request;
  }

  @Test
  void humanWithExportCapabilityReceivesActiveProjectionWithNoStoreHeaders() {
    var exports = mock(InstallationProjectionExportService.class);
    when(exports.exportActive(
        eq("install-a"),
        any(OptionalLong.class),
        any(InstallationProjectionExportService.Actor.class)))
        .thenReturn(projection());

    var response = new InstallationProjectionExportApi(exports).exportActive(
        "install-a",
        7L,
        "corr-1",
        request("HUMAN", Set.of("installation.configuration.export")));

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    assertThat(response.getHeaders().getFirst("Content-Disposition"))
        .contains("installation-projection-install-a-r7.json");
    assertThat(response.getHeaders().getFirst("X-OUF-Installation-Revision")).isEqualTo("7");
    assertThat(response.getHeaders().getFirst("X-OUF-Installation-Checksum")).isEqualTo("a".repeat(64));

    verify(exports).exportActive(
        eq("install-a"),
        argThat(v -> v.isPresent() && v.getAsLong() == 7L),
        argThat(a -> a.subject().equals("principal-1") && a.correlationId().equals("corr-1")));
  }


  @Test
  void humanCanExportValidatedCandidateBeforeActivation() {
    var exports = mock(InstallationProjectionExportService.class);
    when(exports.exportCandidate(
        eq("install-a"),
        eq(7L),
        any(InstallationProjectionExportService.Actor.class)))
        .thenReturn(projection());

    var response = new InstallationProjectionExportApi(exports).exportCandidate(
        "install-a",
        7L,
        "corr-candidate",
        request("HUMAN", Set.of("installation.configuration.export")));

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getHeaders().getFirst("X-OUF-Projection-Purpose"))
        .isEqualTo("CANDIDATE");
    assertThat(response.getHeaders().getFirst("X-OUF-Installation-Revision")).isEqualTo("7");
    assertThat(response.getHeaders().getFirst("X-OUF-Installation-Checksum")).isEqualTo("a".repeat(64));
  }

  @Test
  void serviceIdentityIsRejectedEvenWithExportCapability() {
    var exports = mock(InstallationProjectionExportService.class);

    assertThatThrownBy(() -> new InstallationProjectionExportApi(exports).exportActive(
        "install-a",
        null,
        "corr-service",
        request("SERVICE", Set.of("installation.configuration.export"))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("403");

    verifyNoInteractions(exports);
  }

  @Test
  void humanWithoutExportCapabilityIsRejected() {
    var exports = mock(InstallationProjectionExportService.class);

    assertThatThrownBy(() -> new InstallationProjectionExportApi(exports).exportActive(
        "install-a",
        null,
        "corr-denied",
        request("HUMAN", Set.of())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("403");

    verifyNoInteractions(exports);
  }

  @Test
  void missingCorrelationIsRejectedBeforeExport() {
    var exports = mock(InstallationProjectionExportService.class);

    assertThatThrownBy(() -> new InstallationProjectionExportApi(exports).exportActive(
        "install-a",
        null,
        null,
        request("HUMAN", Set.of("installation.configuration.export"))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("400");

    verifyNoInteractions(exports);
  }

  @Test
  void nonActiveExpectedRevisionMapsToConflict() {
    var exports = mock(InstallationProjectionExportService.class);
    when(exports.exportActive(
        eq("install-a"),
        any(OptionalLong.class),
        any(InstallationProjectionExportService.Actor.class)))
        .thenThrow(new IllegalStateException("INSTALLATION_EXPORT_REVISION_NOT_ACTIVE"));

    assertThatThrownBy(() -> new InstallationProjectionExportApi(exports).exportActive(
        "install-a",
        3L,
        "corr-stale",
        request("HUMAN", Set.of("installation.configuration.export"))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409");
  }
}
