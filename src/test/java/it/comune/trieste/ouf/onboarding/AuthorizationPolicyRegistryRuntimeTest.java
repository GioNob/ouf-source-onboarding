package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;

import it.comune.trieste.ouf.authorization.AuthorizationPolicy;
import it.comune.trieste.ouf.onboarding.authorization.AuthorizationPolicyRegistry;
import it.comune.trieste.ouf.authorization.PrincipalContext;
import it.comune.trieste.ouf.authorization.ResourceContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class AuthorizationPolicyRegistryRuntimeTest {
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> required("OUF_ONB_DB_URL"));
    r.add("spring.datasource.username", () -> required("OUF_ONB_DB_USER"));
    r.add("spring.datasource.password", () -> required("OUF_ONB_DB_PASSWORD"));
  }

  @Autowired AuthorizationPolicyRegistry registry;
  @Autowired JdbcClient db;

  @BeforeEach
  void clean() {
    db.sql("truncate table ouf_authorization.authorization_decision_audit,ouf_authorization.active_policy_bundle,ouf_authorization.policy_bundle cascade").update();
  }

  @Test
  void publishActivateEvaluateAndAuditArePinnedToExactBundle() {
    Instant now = Instant.parse("2026-09-16T12:00:00Z");
    var bundle = bundle("baseline", 1, now);
    var published = registry.publishAndActivate(bundle, "human:policy-admin");
    assertThat(published.bundleId()).isEqualTo("baseline");
    assertThat(published.version()).isEqualTo(1);

    var principal = new PrincipalContext(
        "human:alice", "tenant-a", PrincipalContext.ActorType.HUMAN, null,
        "acr:mfa", "issuer:scenario-neutral", "ouf", Set.of("operations.status.read"));
    var resource = new ResourceContext("OUF_SYSTEM", "system", "tenant-a", "org-1", Map.of());
    var decision = registry.authorizeActive(principal, resource, "ouf.system.status", "READ", now.plusSeconds(1));

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.decisionRef()).isEqualTo("baseline:1:ouf.system.status");
    assertThat(registry.decisionAuditCount("tenant-a")).isEqualTo(1);
    assertThat(db.sql("select bundle_version from ouf_authorization.authorization_decision_audit").query(Long.class).single()).isEqualTo(1);
  }

  @Test
  void sameVersionIsIdempotentButDifferentContentFailsClosed() {
    Instant now = Instant.parse("2026-09-16T12:00:00Z");
    var original = bundle("baseline", 1, now);
    assertThat(registry.publish(original, "human:admin").inserted()).isTrue();
    assertThat(registry.publish(original, "human:admin").inserted()).isFalse();

    var changed = new AuthorizationPolicy.PolicyBundle(
        "baseline", 1, now,
        List.of(new AuthorizationPolicy.CapabilityDescriptor(
            "ouf.system.status", "READ", "different.scope", Set.of(PrincipalContext.ActorType.HUMAN))),
        original.grants());
    assertThatThrownBy(() -> registry.publish(changed, "human:admin"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("different content");
  }

  @Test
  void activationMovesOnlyPointerAndPublishedBundleRemainsImmutable() {
    Instant now = Instant.parse("2026-09-16T12:00:00Z");
    registry.publishAndActivate(bundle("baseline", 1, now), "human:admin");
    registry.publish(bundle("baseline", 2, now.plusSeconds(10)), "human:admin");
    registry.activate("baseline", 2);

    assertThat(registry.active().orElseThrow().version()).isEqualTo(2);
    assertThatThrownBy(() -> db.sql("update ouf_authorization.policy_bundle set published_by='forged' where bundle_id='baseline' and version=1").update())
        .hasStackTraceContaining("policy_bundle is immutable");
  }

  private static AuthorizationPolicy.PolicyBundle bundle(String id, long version, Instant now) {
    return new AuthorizationPolicy.PolicyBundle(
        id,
        version,
        now,
        List.of(new AuthorizationPolicy.CapabilityDescriptor(
            "ouf.system.status", "READ", "operations.status.read", Set.of(PrincipalContext.ActorType.HUMAN))),
        List.of(new AuthorizationPolicy.Grant(
            "grant-status", "ouf.system.status", "tenant-a", "human:alice", null, "org-1",
            now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.DAYS))));
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null) throw new IllegalStateException(name + " required");
    return value;
  }
}
