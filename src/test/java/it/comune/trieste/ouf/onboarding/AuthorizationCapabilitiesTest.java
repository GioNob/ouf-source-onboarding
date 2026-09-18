package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import it.comune.trieste.ouf.authorization.PrincipalContext;
import it.comune.trieste.ouf.onboarding.authorization.AuthorizationCapabilities;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AuthorizationCapabilitiesTest {

  @Test
  void definesCanonicalPlatformAuthorizationDescriptors() {
    var admin = AuthorizationCapabilities.POLICY_ADMIN;
    assertThat(AuthorizationCapabilities.OWNER_REF).isEqualTo("authorization");
    assertThat(admin.capabilityId()).isEqualTo("authorization.policy.admin");
    assertThat(admin.operation()).isEqualTo("EXECUTE");
    assertThat(admin.requiredScope()).isEqualTo("authorization.policy.admin");
    assertThat(admin.allowedActors()).isEqualTo(Set.of(PrincipalContext.ActorType.HUMAN));

    var reader = AuthorizationCapabilities.BUNDLE_READ;
    assertThat(reader.capabilityId()).isEqualTo("authorization.bundle.read");
    assertThat(reader.operation()).isEqualTo("READ");
    assertThat(reader.requiredScope()).isEqualTo("authorization.bundle.read");
    assertThat(reader.allowedActors()).isEqualTo(Set.of(PrincipalContext.ActorType.SERVICE));

    assertThat(AuthorizationCapabilities.bootstrapDescriptors())
        .containsExactly(admin, reader);

    var export = AuthorizationCapabilities.INSTALLATION_CONFIGURATION_EXPORT;
    assertThat(AuthorizationCapabilities.INSTALLATION_OWNER_REF).isEqualTo("installation");
    assertThat(export.capabilityId()).isEqualTo("installation.configuration.export");
    assertThat(export.operation()).isEqualTo("READ");
    assertThat(export.requiredScope()).isEqualTo("installation.configuration.export");
    assertThat(export.allowedActors()).isEqualTo(Set.of(PrincipalContext.ActorType.HUMAN));
    var read = AuthorizationCapabilities.INSTALLATION_CONFIGURATION_READ;
    var write = AuthorizationCapabilities.INSTALLATION_CONFIGURATION_WRITE;
    var activate = AuthorizationCapabilities.INSTALLATION_CONFIGURATION_ACTIVATE;

    assertThat(read.capabilityId()).isEqualTo("installation.configuration.read");
    assertThat(read.operation()).isEqualTo("READ");
    assertThat(read.requiredScope()).isEqualTo("installation.configuration.read");
    assertThat(read.allowedActors()).isEqualTo(Set.of(PrincipalContext.ActorType.HUMAN));

    assertThat(write.capabilityId()).isEqualTo("installation.configuration.write");
    assertThat(write.operation()).isEqualTo("EXECUTE");
    assertThat(write.requiredScope()).isEqualTo("installation.configuration.write");
    assertThat(write.allowedActors()).isEqualTo(Set.of(PrincipalContext.ActorType.HUMAN));

    assertThat(activate.capabilityId()).isEqualTo("installation.configuration.activate");
    assertThat(activate.operation()).isEqualTo("EXECUTE");
    assertThat(activate.requiredScope()).isEqualTo("installation.configuration.activate");
    assertThat(activate.allowedActors()).isEqualTo(Set.of(PrincipalContext.ActorType.HUMAN));

    assertThat(AuthorizationCapabilities.installationDescriptors())
        .containsExactly(read, write, activate, export);
  }
}
