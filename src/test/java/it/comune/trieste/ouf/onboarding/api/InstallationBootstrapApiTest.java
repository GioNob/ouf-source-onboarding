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
        new InstallationConfigurationService.Active("install-a", 3, "b".repeat(64), 7)));

    var out = new InstallationBootstrapApi(service).active(
        "install-a",
        request("HUMAN", Set.of("installation.configuration.read"), false));

    assertThat(out.getBody()).isEqualTo(
        new InstallationConfigurationService.Active("install-a", 3, "b".repeat(64), 7));

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
    when(service.activate(eq("install-a"), eq(2L), any(), any())).thenReturn(
        new InstallationConfigurationService.Active("install-a", 2, "c".repeat(64), 7));

    var api = new InstallationBootstrapApi(service);
    var active = api.activate(
        "install-a",
        2,
        "corr-activate",
        "\"2:" + "c".repeat(64) + ":7\"", null,
        request("HUMAN", Set.of("installation.configuration.activate"), true));
    assertThat(active.revision()).isEqualTo(2);

    when(service.active("install-a")).thenReturn(java.util.Optional.of(
        new InstallationConfigurationService.Active("install-a", 2, "c".repeat(64), 7)));
    when(service.activate(eq("install-a"), eq(1L), any(), any())).thenReturn(
        new InstallationConfigurationService.Active("install-a", 1, "a".repeat(64), 7));

    var rolledBack = api.rollback(
        "install-a",
        1,
        "corr-rollback",
        "\"2:" + "c".repeat(64) + ":7\"", null,
        request("HUMAN", Set.of("installation.configuration.activate"), true));
    assertThat(rolledBack.revision()).isEqualTo(1);

    assertThatThrownBy(() -> api.rollback(
        "install-a",
        2,
        "corr-invalid",
        "\"2:" + "c".repeat(64) + ":7\"", null,
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
        "\"2:" + "c".repeat(64) + ":7\"", null,
        request("HUMAN", Set.of("installation.configuration.activate"), true));

    verify(service).revoke(
        eq("install-a"),
        eq(2L),
        argThat(a -> a.correlationId().equals("corr-revoke")), any());

    assertThatThrownBy(() -> api.revoke(
        "install-a",
        2,
        "corr-revoke",
        "\"2:" + "c".repeat(64) + ":7\"", null,
        request("HUMAN", Set.of("installation.configuration.activate"), false)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("403");
  }

  @Test
  void missingWeakOrMalformedPreconditionsNeverReachTheOwner() {
    var service = mock(InstallationConfigurationService.class);
    var api = new InstallationBootstrapApi(service);
    var human = request("HUMAN", Set.of("installation.configuration.activate"), true);
    assertThatThrownBy(() -> api.activate("install-a", 2, "corr", null, null, human))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("428");
    for (String header : new String[]{"*", "W/\"1:" + "a".repeat(64) + ":1\"",
        "\"1:" + "a".repeat(64) + ":0\"", "\"9223372036854775808:" + "a".repeat(64) + ":1\""}) {
      assertThatThrownBy(() -> api.activate("install-a", 2, "corr", header, null, human))
          .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
    }
    assertThatThrownBy(() -> api.activate("install-a", 2, "corr", "\"1:" + "a".repeat(64) + ":1\"", "*", human))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
    verifyNoInteractions(service);
  }

  @Test
  void absenceIsExplicitAndStaleOwnerPreconditionIs412() {
    var service = mock(InstallationConfigurationService.class);
    var api = new InstallationBootstrapApi(service);
    var human = request("HUMAN", Set.of("installation.configuration.activate"), true);
    when(service.activate(eq("install-a"), eq(1L), any(), eq(InstallationConfigurationService.ExpectedActive.absent())))
        .thenReturn(new InstallationConfigurationService.Active("install-a", 1, "a".repeat(64), 1));
    assertThat(api.activate("install-a", 1, "corr", null, "*", human).generation()).isEqualTo(1);
    when(service.activate(eq("install-a"), eq(2L), any(), any()))
        .thenThrow(new IllegalStateException("INSTALLATION_ACTIVE_PRECONDITION_FAILED"));
    assertThatThrownBy(() -> api.activate("install-a", 2, "corr", "\"1:" + "a".repeat(64) + ":1\"", null, human))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("412");
  }

  @Test
  void strongConditionNeverSubstitutesHumanCapabilityOrTrustedWriteProof() {
    var service = mock(InstallationConfigurationService.class);
    var api = new InstallationBootstrapApi(service);
    String tag = "\"1:" + "a".repeat(64) + ":1\"";
    for (var denied : new MockHttpServletRequest[]{
        request("SERVICE", Set.of("installation.configuration.activate"), true),
        request("HUMAN", Set.of(), true),
        request("HUMAN", Set.of("installation.configuration.activate"), false)}) {
      assertThatThrownBy(() -> api.activate("install-a", 2, "corr", tag, null, denied))
          .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
    }
    verifyNoInteractions(service);
  }

  @Test
  void httpHeaderBindingExposesStrongEtagAndRequiresItForMutations() throws Exception {
    var service = mock(InstallationConfigurationService.class);
    var active = new InstallationConfigurationService.Active("install-a", 1, "a".repeat(64), 9);
    when(service.active("install-a")).thenReturn(java.util.Optional.of(active));
    when(service.activate(eq("install-a"), eq(2L), any(), eq(InstallationConfigurationService.ExpectedActive.from(active))))
        .thenReturn(new InstallationConfigurationService.Active("install-a", 2, "b".repeat(64), 10));
    var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new InstallationBootstrapApi(service))
        .addInterceptors(new org.springframework.web.servlet.HandlerInterceptor() {
          @Override public boolean preHandle(jakarta.servlet.http.HttpServletRequest req, jakarta.servlet.http.HttpServletResponse res, Object handler) {
            TestAuthorization.bind(req, "human:installer", "HUMAN", Set.of("installation.configuration.read", "installation.configuration.activate"));
            TrustedWriteProof.markStatelessBearer(req); return true;
          }
        }).build();
    String path = "/api/trusted-human/v1/installations/install-a";
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path + "/active"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("ETag", active.etag()));
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path + "/revisions/2:activate")
        .header("X-Correlation-ID", "fixture"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(428));
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path + "/revisions/2:activate")
        .header("X-Correlation-ID", "fixture").header("If-Match", active.etag()))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
  }
}
