package it.comune.trieste.ouf.onboarding.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.authorization.TestAuthorization;
import it.comune.trieste.ouf.onboarding.installation.InstallationConfigurationService;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class InstallationBootstrapApiTest {
  private final ObjectMapper json = new ObjectMapper();

  private MockHttpServletRequest request(String actor, Set<String> capabilities, boolean writeProof) {
    var request = new MockHttpServletRequest();
    TestAuthorization.bind(request, "human:installer", actor, capabilities);
    if (writeProof) TrustedWriteProof.markStatelessBearer(request);
    return request;
  }

  @Test
  void createRequiresHumanWriteCapabilityCorrelationAndTrustedWriteProof() throws Exception {
    var service = mock(InstallationConfigurationService.class);
    var payload = json.readTree("{\"installationId\":\"install-a\"}");
    var expected = new InstallationConfigurationService.Revision(
        "install-a", 1, "a".repeat(64), "VALIDATED", json.createArrayNode(), payload);
    when(service.create(eq(payload), any())).thenReturn(expected);

    var out = new InstallationBootstrapApi(service).create(
        "install-a",
        payload,
        "corr-create",
        request("HUMAN", Set.of("installation.configuration.write"), true));

    assertThat(out).isSameAs(expected);
    verify(service).create(
        eq(payload),
        argThat(a -> a.subject().equals("human:installer")
            && a.correlationId().equals("corr-create")));

    assertThatThrownBy(() -> new InstallationBootstrapApi(service).create(
        "install-a",
        payload,
        null,
        request("HUMAN", Set.of("installation.configuration.write"), true)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("400");

    assertThatThrownBy(() -> new InstallationBootstrapApi(service).create(
        "install-a",
        payload,
        "corr-no-proof",
        request("HUMAN", Set.of("installation.configuration.write"), false)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("403");
  }

  @Test
  void serviceIdentityCannotUseInstallationBootstrapEvenWithCapability() throws Exception {
    var service = mock(InstallationConfigurationService.class);
    var payload = json.readTree("{\"installationId\":\"install-a\"}");

    assertThatThrownBy(() -> new InstallationBootstrapApi(service).create(
        "install-a",
        payload,
        "corr-service",
        request("SERVICE", Set.of("installation.configuration.write"), true)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("403");

    verifyNoInteractions(service);
  }

  @Test
  void readUsesDedicatedReadCapability() {
    var service = mock(InstallationConfigurationService.class);
    when(service.active("install-a")).thenReturn(java.util.Optional.of(
        new InstallationConfigurationService.Active("install-a", 3, "b".repeat(64))));

    var out = new InstallationBootstrapApi(service).active(
        "install-a",
        request("HUMAN", Set.of("installation.configuration.read"), false));

    assertThat(out.getBody()).isEqualTo(
        new InstallationConfigurationService.Active("install-a", 3, "b".repeat(64)));

    assertThatThrownBy(() -> new InstallationBootstrapApi(service).active(
        "install-a",
        request("HUMAN", Set.of(), false)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("403");
  }

  @Test
  void environmentValidationUsesWriteCapabilityAndCorrelation() {
    var service = mock(InstallationConfigurationService.class);
    var evidence = new InstallationConfigurationService.EnvironmentValidation(
        java.util.UUID.randomUUID(),
        "install-a",
        2,
        "PASS",
        json.createArrayNode(),
        "corr-validate");
    when(service.validateEnvironment(eq("install-a"), eq(2L), any())).thenReturn(evidence);

    var out = new InstallationBootstrapApi(service).validateEnvironment(
        "install-a",
        2,
        "corr-validate",
        request("HUMAN", Set.of("installation.configuration.write"), true));

    assertThat(out).isSameAs(evidence);
    verify(service).validateEnvironment(
        eq("install-a"),
        eq(2L),
        argThat(a -> a.correlationId().equals("corr-validate")));
  }

  @Test
  void activationAndRollbackUseDedicatedActivationCapability() {
    var service = mock(InstallationConfigurationService.class);
    when(service.activate(eq("install-a"), eq(2L), any())).thenReturn(
        new InstallationConfigurationService.Active("install-a", 2, "c".repeat(64)));

    var api = new InstallationBootstrapApi(service);
    var active = api.activate(
        "install-a",
        2,
        "corr-activate",
        request("HUMAN", Set.of("installation.configuration.activate"), true));
    assertThat(active.revision()).isEqualTo(2);

    when(service.active("install-a")).thenReturn(java.util.Optional.of(
        new InstallationConfigurationService.Active("install-a", 2, "c".repeat(64))));
    when(service.activate(eq("install-a"), eq(1L), any())).thenReturn(
        new InstallationConfigurationService.Active("install-a", 1, "a".repeat(64)));

    var rolledBack = api.rollback(
        "install-a",
        1,
        "corr-rollback",
        request("HUMAN", Set.of("installation.configuration.activate"), true));
    assertThat(rolledBack.revision()).isEqualTo(1);

    assertThatThrownBy(() -> api.rollback(
        "install-a",
        2,
        "corr-invalid",
        request("HUMAN", Set.of("installation.configuration.activate"), true)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409");
  }

  @Test
  void revokeUsesActivationCapabilityAndTrustedWriteProof() {
    var service = mock(InstallationConfigurationService.class);
    var api = new InstallationBootstrapApi(service);

    api.revoke(
        "install-a",
        2,
        "corr-revoke",
        request("HUMAN", Set.of("installation.configuration.activate"), true));

    verify(service).revoke(
        eq("install-a"),
        eq(2L),
        argThat(a -> a.correlationId().equals("corr-revoke")));

    assertThatThrownBy(() -> api.revoke(
        "install-a",
        2,
        "corr-revoke",
        request("HUMAN", Set.of("installation.configuration.activate"), false)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("403");
  }
}
