package it.comune.trieste.ouf.onboarding;

import it.comune.trieste.ouf.onboarding.authorization.AuthorizationPolicy;
import it.comune.trieste.ouf.onboarding.authorization.PrincipalContext;
import it.comune.trieste.ouf.onboarding.authorization.ResourceContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationPolicyTest {
    private static final Instant NOW = Instant.parse("2026-09-16T12:00:00Z");

    private AuthorizationPolicy.PolicyBundle bundle() {
        return new AuthorizationPolicy.PolicyBundle(
                "bundle-main",
                7,
                NOW.minusSeconds(60),
                List.of(new AuthorizationPolicy.CapabilityDescriptor(
                        "ouf.operations.summary", "READ", "operations.summary.read",
                        Set.of(PrincipalContext.ActorType.HUMAN, PrincipalContext.ActorType.SERVICE))),
                List.of(new AuthorizationPolicy.Grant(
                        "grant-1", "ouf.operations.summary", "tenant-a", "alice", null,
                        "org-a", NOW.minusSeconds(60), NOW.plusSeconds(3600))));
    }

    private PrincipalContext principal(String tenant, Set<String> scopes) {
        return new PrincipalContext(
                "alice", tenant, PrincipalContext.ActorType.HUMAN, null,
                "authn-1", "https://iam.example/issuer", "ouf-api", scopes);
    }

    @Test
    void defaultDeniesUndeclaredCapability() {
        var decision = AuthorizationPolicy.evaluate(bundle(), principal("tenant-a", Set.of("operations.summary.read")),
                new ResourceContext("operations", "summary", "tenant-a", "org-a", Map.of()),
                "ouf.unknown", "READ", NOW);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.decisionCode()).isEqualTo("CAPABILITY_NOT_DECLARED");
    }

    @Test
    void deniesCrossTenantEvenWhenScopeExists() {
        var decision = AuthorizationPolicy.evaluate(bundle(), principal("tenant-a", Set.of("operations.summary.read")),
                new ResourceContext("operations", "summary", "tenant-b", "org-a", Map.of()),
                "ouf.operations.summary", "READ", NOW);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.decisionCode()).isEqualTo("TENANT_MISMATCH");
    }

    @Test
    void requiresScopeAndApplicableGrant() {
        var noScope = AuthorizationPolicy.evaluate(bundle(), principal("tenant-a", Set.of()),
                new ResourceContext("operations", "summary", "tenant-a", "org-a", Map.of()),
                "ouf.operations.summary", "READ", NOW);
        assertThat(noScope.decisionCode()).isEqualTo("SCOPE_MISSING");

        var wrongOrg = AuthorizationPolicy.evaluate(bundle(), principal("tenant-a", Set.of("operations.summary.read")),
                new ResourceContext("operations", "summary", "tenant-a", "org-b", Map.of()),
                "ouf.operations.summary", "READ", NOW);
        assertThat(wrongOrg.decisionCode()).isEqualTo("NO_APPLICABLE_GRANT");
    }

    @Test
    void allowsOnlyAgainstPinnedBundleVersion() {
        var decision = AuthorizationPolicy.evaluate(bundle(), principal("tenant-a", Set.of("operations.summary.read")),
                new ResourceContext("operations", "summary", "tenant-a", "org-a", Map.of()),
                "ouf.operations.summary", "READ", NOW);
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.decisionCode()).isEqualTo("ALLOW");
        assertThat(decision.bundleId()).isEqualTo("bundle-main");
        assertThat(decision.bundleVersion()).isEqualTo(7);
        assertThat(decision.decisionRef()).contains("bundle-main:7:ouf.operations.summary");
    }
}
