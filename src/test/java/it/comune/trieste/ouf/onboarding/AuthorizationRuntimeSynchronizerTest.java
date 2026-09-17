package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.*;
import it.comune.trieste.ouf.authorization.LocalAuthorization;
import it.comune.trieste.ouf.authorization.PrincipalContext;
import it.comune.trieste.ouf.authorization.TestAuthorization;
import it.comune.trieste.ouf.onboarding.authorization.AuthorizationPolicyRegistry;
import it.comune.trieste.ouf.onboarding.authorization.AuthorizationRuntimeSynchronizer;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AuthorizationRuntimeSynchronizerTest {
  private static PolicyBundle bundle(String id, long version) {
    var capability = new CapabilityDescriptor(
        "authorization.policy.admin", "EXECUTE", "authorization.policy.admin",
        Set.of(PrincipalContext.ActorType.HUMAN));
    return new PolicyBundle(id, version, Instant.parse("2026-09-17T12:00:00Z"), List.of(capability), List.of());
  }

  @Test
  void installsExactActiveBundleAndReportsReady() throws Exception {
    var registry = mock(AuthorizationPolicyRegistry.class);
    var runtime = new LocalAuthorization(Clock.systemUTC(), Duration.ofMinutes(5));
    var json = new ObjectMapper().findAndRegisterModules();
    var policy = bundle("ouf-lab-authorization", 1);
    byte[] raw = json.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsBytes(policy);
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
    when(registry.active()).thenReturn(java.util.Optional.of(
        new AuthorizationPolicyRegistry.ActiveBundle(policy.bundleId(), policy.version(), Instant.now())));
    when(registry.load(policy.bundleId(), policy.version())).thenReturn(policy);
    when(registry.transportHash(policy)).thenReturn(hash);

    new AuthorizationRuntimeSynchronizer(registry, runtime, json).refreshActive();

    assertThat(runtime.health().ready()).isTrue();
    assertThat(runtime.health().bundleId()).isEqualTo(policy.bundleId());
    assertThat(runtime.health().version()).isEqualTo(1);
  }

  @Test
  void rejectsActiveLineageChangeWithoutRestart() throws Exception {
    var registry = mock(AuthorizationPolicyRegistry.class);
    var runtime = new LocalAuthorization(Clock.systemUTC(), Duration.ofMinutes(5));
    TestAuthorization.install(runtime, bundle("old-lineage", 1));
    var json = new ObjectMapper().findAndRegisterModules();
    var next = bundle("new-lineage", 1);
    byte[] raw = json.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsBytes(next);
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
    when(registry.active()).thenReturn(java.util.Optional.of(
        new AuthorizationPolicyRegistry.ActiveBundle(next.bundleId(), next.version(), Instant.now())));
    when(registry.load(next.bundleId(), next.version())).thenReturn(next);
    when(registry.transportHash(next)).thenReturn(hash);

    var synchronizer = new AuthorizationRuntimeSynchronizer(registry, runtime, json);

    assertThatThrownBy(synchronizer::refreshActive)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("BUNDLE_LINEAGE_CHANGE_REQUIRES_RESTART");
  }
}
