package it.comune.trieste.ouf.onboarding.authorization;

import static it.comune.trieste.ouf.onboarding.authorization.AuthorizationGrantExpiry.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.*;
import it.comune.trieste.ouf.authorization.PrincipalContext.ActorType;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class AuthorizationGrantExpiryTest {
  private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
  private static final String STORAGE = "ouf.object-storage.content.read";
  private static final String THS = "ouf.onboarding.configuration.write";
  private static final String INGESTION = "grant-managed-file-read-ingestion";

  private Grant serviceGrant(String id, String tenant, Instant until, GrantConstraints constraints) {
    return new Grant(id, STORAGE, tenant, null, "fixture-ingestion", null,
        until.minus(Duration.ofDays(90)), until, constraints);
  }

  private PolicyBundle policy(long version, Grant... grants) {
    return new PolicyBundle("fixture", version, NOW, List.of(
        new CapabilityDescriptor(STORAGE, "READ", STORAGE, Set.of(ActorType.SERVICE)),
        new CapabilityDescriptor(THS, "EXECUTE", THS, Set.of(ActorType.HUMAN))), Arrays.asList(grants));
  }

  @Test void timeWindowsAreInclusiveAndPartitioned() {
    var bundle = policy(63,
        serviceGrant("outside", "ouf-lab", NOW.plus(Duration.ofDays(30)).plusNanos(1), null),
        serviceGrant("day30", "ouf-lab", NOW.plus(Duration.ofDays(30)), null),
        serviceGrant("day7-plus", "ouf-lab", NOW.plus(Duration.ofDays(7)).plusNanos(1), null),
        serviceGrant("day7", "ouf-lab", NOW.plus(Duration.ofDays(7)), null),
        serviceGrant("expires-now", "ouf-lab", NOW, null));
    var report = inspect(bundle, NOW, grant -> true);
    assertThat(report.policyRef()).isEqualTo("fixture:63");
    assertThat(report.status()).isEqualTo(Status.EXPIRED_GRANTS);
    assertThat(report.findings()).extracting(Finding::grantId)
        .containsExactly("expires-now", "day7", "day7-plus", "day30");
    assertThat(report.findings()).extracting(Finding::window)
        .containsExactly(Window.EXPIRED, Window.DUE_7D, Window.DUE_30D, Window.DUE_30D);
  }

  @Test void dependencyRequiresExactKnownGrantAndDenyExpiryHasDifferentMeaning() {
    var deny = new GrantConstraints("DENY", null, null, null, null, null, null, null, null, null);
    var human = new Grant("grant-onboarding-configuration-write-human-admin", THS,
        "ouf-lab", "admin", null, null, NOW.minusSeconds(1), NOW.plusSeconds(1));
    var report = inspect(policy(63, serviceGrant(INGESTION, "ouf-lab", NOW, null),
        serviceGrant("other", "ouf-lab", NOW, deny), human), NOW, grant -> true);
    assertThat(report.findings()).extracting(Finding::potentialDependency).containsExactly(
        "INGESTION_MANAGED_FILE_READINESS_AND_READ", "DENY_EXPIRY_REQUIRES_REVIEW",
        "ONBOARDING_THS_REVIEW_AND_ACTIVATION");
    var otherTenant = inspect(policy(63, serviceGrant(INGESTION, "other", NOW, null)), NOW, grant -> true);
    assertThat(otherTenant.findings().getFirst().potentialDependency()).isEqualTo("UNMAPPED");
    assertThat(inspect(policy(63, serviceGrant(INGESTION, "other", NOW, null)), NOW,
        grant -> grant.tenantId().equals("ouf-lab")).status()).isEqualTo(Status.OK);
  }

  @Test void monitorAlertsOnEntryAndEscalationAndMarksUnavailableReads(CapturedOutput output) {
    var registry = mock(AuthorizationPolicyRegistry.class);
    var active = new AuthorizationPolicyRegistry.ActiveBundle("fixture", 63, NOW);
    when(registry.active()).thenReturn(Optional.of(active));
    when(registry.load("fixture", 63)).thenReturn(
        policy(63, serviceGrant(INGESTION, "ouf-lab", Instant.now().plus(Duration.ofDays(20)), null)));
    var meters = new SimpleMeterRegistry();
    try {
      var monitor = new AuthorizationGrantExpiryMonitor(registry, meters);
      monitor.checkActivePolicy();
      monitor.checkActivePolicy();
      assertThat(meters.get("ouf.authorization.grant.expiry.count").tag("window", "DUE_30D").gauge().value())
          .isEqualTo(1);
      String due30 = "AUTH_GRANT_EXPIRY window=DUE_30D potentialDependency=INGESTION_MANAGED_FILE_READINESS_AND_READ";
      assertThat(output.getAll().split(due30, -1)).hasSize(2);

      when(registry.active()).thenReturn(Optional.empty());
      monitor.checkActivePolicy();
      monitor.checkActivePolicy();
      assertThat(meters.get("ouf.authorization.grant.expiry.scan.available").gauge().value()).isZero();
      assertThat(meters.get("ouf.authorization.grant.expiry.count").tag("window", "DUE_30D").gauge().value()).isNaN();
      assertThat(output.getAll().split("AUTH_GRANT_EXPIRY_CHECK_UNAVAILABLE", -1)).hasSize(2);

      when(registry.active()).thenReturn(Optional.of(active));
      when(registry.load("fixture", 63)).thenReturn(
          policy(63, serviceGrant(INGESTION, "ouf-lab", Instant.now().plus(Duration.ofDays(5)), null)));
      monitor.checkActivePolicy();
      assertThat(meters.get("ouf.authorization.grant.expiry.scan.available").gauge().value()).isEqualTo(1);
      assertThat(meters.get("ouf.authorization.grant.expiry.count").tag("window", "DUE_7D").gauge().value())
          .isEqualTo(1);
      assertThat(output.getAll()).contains("AUTH_GRANT_EXPIRY window=DUE_7D")
          .doesNotContain("fixture-ingestion");
    } finally {
      meters.close();
    }
  }
}
