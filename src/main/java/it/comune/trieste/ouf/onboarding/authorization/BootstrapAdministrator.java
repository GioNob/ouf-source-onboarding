package it.comune.trieste.ouf.onboarding.authorization;

import it.comune.trieste.ouf.authorization.AuthorizationPolicy;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.PolicyBundle;
import it.comune.trieste.ouf.authorization.PrincipalContext;
import it.comune.trieste.ouf.authorization.ResourceContext;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Installation designation, used only while the persistent bootstrap latch is open. */
@Component
public final class BootstrapAdministrator {
  public static final String CAPABILITY = "authorization.policy.admin";
  private final String issuer;
  private final String subject;
  private final String tenant;

  public BootstrapAdministrator(
      @Value("${ouf.authorization.bootstrap.admin-issuer:}") String issuer,
      @Value("${ouf.authorization.bootstrap.admin-subject:}") String subject,
      @Value("${ouf.authorization.bootstrap.admin-tenant:}") String tenant) {
    this.issuer = issuer;
    this.subject = subject;
    this.tenant = tenant;
  }

  public void requirePrincipal(PrincipalContext principal) {
    if (issuer.isBlank() || subject.isBlank() || tenant.isBlank())
      throw new SecurityException("AUTH_BOOTSTRAP_ADMIN_NOT_CONFIGURED");
    if (principal.actorType() != PrincipalContext.ActorType.HUMAN
        || !issuer.equals(principal.issuer())
        || !subject.equals(principal.subjectId())
        || !tenant.equals(principal.tenantId()))
      throw new SecurityException("AUTH_BOOTSTRAP_ADMIN_MISMATCH");
    if (!principal.scopes().contains("authorization.bootstrap"))
      throw new SecurityException("AUTH_BOOTSTRAP_SCOPE_REQUIRED");
  }

  public void requireInitialPolicy(PolicyBundle policy, PrincipalContext principal, Instant now) {
    requirePrincipal(principal);
    // Evaluate the durable OUF permission independently of organizational role membership.
    // The admin scope will still be required on subsequent requests; bootstrap is not a token mint.
    var nominal = policy.grants().stream().anyMatch(g ->
        CAPABILITY.equals(g.capabilityId()) && subject.equals(g.subjectId())
        && tenant.equals(g.tenantId()) && g.servicePrincipalId() == null
        && !now.isBefore(g.validFrom()) && now.isBefore(g.validUntil())
        && (g.constraints() == null || ("ALLOW".equals(g.constraints().effect())
            && g.constraints().externalRoleRef() == null)));
    var identity = new PrincipalContext(subject, tenant, PrincipalContext.ActorType.HUMAN,
        null, principal.authenticationContextRef(), issuer, principal.audience(),
        Set.of(CAPABILITY), new PrincipalContext.IdentityClaims(Set.of(),
            principal.claims() == null ? null : principal.claims().acr(),
            principal.claims() == null ? Set.of() : principal.claims().amr(),
            principal.claims() == null ? null : principal.claims().authenticatedAt()));
    var decision = AuthorizationPolicy.evaluate(policy, identity,
        new ResourceContext("capability", null, tenant, null, Map.of()),
        CAPABILITY, "EXECUTE", now);
    var actualIdentity = new PrincipalContext(subject, tenant, PrincipalContext.ActorType.HUMAN,
        null, principal.authenticationContextRef(), issuer, principal.audience(),
        Set.of(CAPABILITY), principal.claims());
    var actualDecision = AuthorizationPolicy.evaluate(policy, actualIdentity,
        new ResourceContext("capability", null, tenant, null, Map.of()),
        CAPABILITY, "EXECUTE", now);
    if (!nominal || !decision.allowed() || !actualDecision.allowed())
      throw new SecurityException("AUTH_BOOTSTRAP_ADMIN_GRANT_REQUIRED");
  }
}
