package it.comune.trieste.ouf.onboarding.authorization;

import it.comune.trieste.ouf.authorization.PrincipalContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Installation-owned initial designation; never a fallback after installation. */
@Component
public final class BootstrapAdministrator {
  public static final String CAPABILITY = "authorization.policy.admin";
  private final String issuer;
  private final String role;
  private final String tenant;

  public BootstrapAdministrator(
      @Value("${ouf.authorization.bootstrap.admin-issuer:}") String issuer,
      @Value("${ouf.authorization.bootstrap.superadmin-role:}") String role,
      @Value("${ouf.authorization.bootstrap.admin-tenant:}") String tenant) {
    this.issuer = issuer; this.role = role; this.tenant = tenant;
  }
  public String issuer() { return issuer; }
  public String role() { return role; }
  public String tenant() { return tenant; }
  public void requireIdentity(PrincipalContext p) {
    if (issuer.isBlank() || !validRole(role) || tenant.isBlank())
      throw new SecurityException("AUTH_BOOTSTRAP_ADMIN_NOT_CONFIGURED");
    if (!matches(p, issuer, tenant, role)) throw new SecurityException("AUTH_BOOTSTRAP_ADMIN_MISMATCH");
  }
  public void requirePrincipal(PrincipalContext p) {
    requireIdentity(p);
    if (!p.scopes().contains("authorization.bootstrap")) throw new SecurityException("AUTH_BOOTSTRAP_SCOPE_REQUIRED");
  }
  public static boolean validRole(String role) {
    return role != null && role.matches("[A-Za-z0-9_:./-]{1,128}");
  }
  public static boolean matches(PrincipalContext p, String issuer, String tenant, String role) {
    return p != null && p.actorType() == PrincipalContext.ActorType.HUMAN
        && issuer.equals(p.issuer()) && tenant.equals(p.tenantId())
        && p.claims() != null && p.claims().externalRoleRefs().contains(role);
  }
}
