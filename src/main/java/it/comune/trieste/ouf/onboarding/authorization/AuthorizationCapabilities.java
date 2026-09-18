package it.comune.trieste.ouf.onboarding.authorization;

import it.comune.trieste.ouf.authorization.AuthorizationPolicy.CapabilityDescriptor;
import it.comune.trieste.ouf.authorization.PrincipalContext;
import java.util.List;
import java.util.Set;

/** Canonical platform-owned Authorization capability descriptors used for bootstrap policy construction. */
public final class AuthorizationCapabilities {
  public static final String OWNER_REF = "authorization";
  public static final String INSTALLATION_OWNER_REF = "installation";

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

  public static final CapabilityDescriptor INSTALLATION_CONFIGURATION_EXPORT =
      new CapabilityDescriptor(
          "installation.configuration.export",
          "READ",
          "installation.configuration.export",
          Set.of(PrincipalContext.ActorType.HUMAN));

  public static final CapabilityDescriptor INSTALLATION_CONFIGURATION_READ =
      new CapabilityDescriptor(
          "installation.configuration.read",
          "READ",
          "installation.configuration.read",
          Set.of(PrincipalContext.ActorType.HUMAN));

  public static final CapabilityDescriptor INSTALLATION_CONFIGURATION_WRITE =
      new CapabilityDescriptor(
          "installation.configuration.write",
          "EXECUTE",
          "installation.configuration.write",
          Set.of(PrincipalContext.ActorType.HUMAN));

  public static final CapabilityDescriptor INSTALLATION_CONFIGURATION_ACTIVATE =
      new CapabilityDescriptor(
          "installation.configuration.activate",
          "EXECUTE",
          "installation.configuration.activate",
          Set.of(PrincipalContext.ActorType.HUMAN));

  private AuthorizationCapabilities() {}

  public static List<CapabilityDescriptor> bootstrapDescriptors() {
    return List.of(POLICY_ADMIN, BUNDLE_READ);
  }

  public static List<CapabilityDescriptor> installationDescriptors() {
    return List.of(
        INSTALLATION_CONFIGURATION_READ,
        INSTALLATION_CONFIGURATION_WRITE,
        INSTALLATION_CONFIGURATION_ACTIVATE,
        INSTALLATION_CONFIGURATION_EXPORT);
  }
}
