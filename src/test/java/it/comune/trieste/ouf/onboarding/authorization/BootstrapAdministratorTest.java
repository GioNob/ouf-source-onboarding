package it.comune.trieste.ouf.onboarding.authorization;

import static org.assertj.core.api.Assertions.*;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.*;
import it.comune.trieste.ouf.authorization.PrincipalContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BootstrapAdministratorTest {
  private static final Instant NOW = Instant.parse("2026-09-19T12:00:00Z");
  private static final String ADMIN = BootstrapAdministrator.CAPABILITY;
  private final BootstrapAdministrator bootstrap = new BootstrapAdministrator("issuer", "person-1", "tenant-1");

  private PrincipalContext person(String issuer, String subject, String tenant, Set<String> roles) {
    return new PrincipalContext(subject, tenant, PrincipalContext.ActorType.HUMAN, null,
        "authentication", issuer, "ouf", Set.of("authorization.bootstrap"),
        new PrincipalContext.IdentityClaims(roles, "1", Set.of("pwd"), NOW.minusSeconds(30)));
  }
  private PrincipalContext person() { return person("issuer", "person-1", "tenant-1", Set.of()); }
  private Grant grant(String id, String subject, Instant from, Instant until, GrantConstraints constraints) {
    return new Grant(id, ADMIN, "tenant-1", subject, null, null, from, until, constraints);
  }
  private Grant grant() { return grant("initial-admin", "person-1", NOW.minusSeconds(60), NOW.plusSeconds(3600), null); }
  private GrantConstraints condition(String effect, String role) {
    return new GrantConstraints(effect, role, null, null, Map.of(), Set.of(), Set.of(), null, Set.of(), null);
  }
  private PolicyBundle policy(Grant... grants) {
    return new PolicyBundle("ouf", 1, NOW,
        List.of(new CapabilityDescriptor(ADMIN, "EXECUTE", ADMIN, Set.of(PrincipalContext.ActorType.HUMAN))),
        List.of(grants));
  }
  @Test void bootstrapBindsIssuerSubjectAndTenantInsteadOfAnIamAdminRole() {
    assertThatCode(() -> bootstrap.requireInitialPolicy(policy(grant()), person(), NOW)).doesNotThrowAnyException();
    for (var other : List.of(person("other", "person-1", "tenant-1", Set.of()),
        person("issuer", "other", "tenant-1", Set.of()), person("issuer", "person-1", "other", Set.of())))
      assertThatThrownBy(() -> bootstrap.requirePrincipal(other)).hasMessage("AUTH_BOOTSTRAP_ADMIN_MISMATCH");
  }
  @Test void missingDesignationFailsClosed() {
    for (var config : List.of(new BootstrapAdministrator("", "person-1", "tenant-1"),
        new BootstrapAdministrator("issuer", "", "tenant-1"), new BootstrapAdministrator("issuer", "person-1", "")))
      assertThatThrownBy(() -> config.requirePrincipal(person())).hasMessage("AUTH_BOOTSTRAP_ADMIN_NOT_CONFIGURED");
  }
  @Test void noGrantWrongPersonExpiredAndFutureGrantsCannotCloseBootstrap() {
    for (var policy : List.of(policy(), policy(grant("g", "other", NOW.minusSeconds(60), NOW.plusSeconds(60), null)),
        policy(grant("g", "person-1", NOW.minusSeconds(60), NOW, null)),
        policy(grant("g", "person-1", NOW.plusSeconds(1), NOW.plusSeconds(60), null))))
      assertThatThrownBy(() -> bootstrap.requireInitialPolicy(policy, person(), NOW)).hasMessage("AUTH_BOOTSTRAP_ADMIN_GRANT_REQUIRED");
  }
  @Test void organizationalRoleMappingDoesNotDesignateTheOufAdministrator() {
    var organizationalAdmin = grant("role", null, NOW.minusSeconds(60), NOW.plusSeconds(60), condition("ALLOW", "ente:it"));
    assertThatThrownBy(() -> bootstrap.requireInitialPolicy(policy(organizationalAdmin), person("issuer", "person-1", "tenant-1", Set.of("ente:it")), NOW))
        .hasMessage("AUTH_BOOTSTRAP_ADMIN_GRANT_REQUIRED");
  }
  @Test void explicitDenialStillOverridesTheNomination() {
    for (String role : new String[]{null, "ente:it"}) {
      var denied = grant("deny", "person-1", NOW.minusSeconds(60), NOW.plusSeconds(60), condition("DENY", role));
      assertThatThrownBy(() -> bootstrap.requireInitialPolicy(policy(grant(), denied), person("issuer", "person-1", "tenant-1", Set.of("ente:it")), NOW))
          .hasMessage("AUTH_BOOTSTRAP_ADMIN_GRANT_REQUIRED");
    }
  }
  @Test void designationDoesNotReplaceTheBootstrapAuthenticationScope() {
    var p = person();
    var noScope = new PrincipalContext(p.subjectId(), p.tenantId(), p.actorType(), null,
        p.authenticationContextRef(), p.issuer(), p.audience(), Set.of());
    assertThatThrownBy(() -> bootstrap.requirePrincipal(noScope)).hasMessage("AUTH_BOOTSTRAP_SCOPE_REQUIRED");
  }
}
