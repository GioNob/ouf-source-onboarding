package it.comune.trieste.ouf.onboarding.authorization;

import it.comune.trieste.ouf.authorization.AuthorizationPolicy.CapabilityDescriptor;
import it.comune.trieste.ouf.authorization.PrincipalContext;
import java.util.List;
import java.util.Set;

/** Canonical platform-owned Authorization capability descriptors used for bootstrap policy construction. */
public final class AuthorizationCapabilities {
  public static final String OWNER_REF = "authorization";

  public static final CapabilityDescriptor POLICY_ADMIN = new CapabilityDescriptor(
      "authorization.policy.admin",
      "EXECUTE",
      "authorization.policy.admin",
      Set.of(PrincipalContext.ActorType.HUMAN));

  public static final CapabilityDescriptor BUNDLE_READ = new CapabilityDescriptor(
      "authorization.bundle.read",
      "READ",
      "authorization.bundle.read",
      Set.of(PrincipalContext.ActorType.SERVICE));

  private AuthorizationCapabilities() {}

  public static List<CapabilityDescriptor> bootstrapDescriptors() {
    return List.of(POLICY_ADMIN, BUNDLE_READ);
  }
}
