package it.comune.trieste.ouf.onboarding.authorization;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.AuthorizationDecision;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.CapabilityDescriptor;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.Grant;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.GrantConstraints;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.PolicyBundle;
import it.comune.trieste.ouf.authorization.PrincipalContext;
import it.comune.trieste.ouf.authorization.PrincipalContext.ActorType;
import it.comune.trieste.ouf.authorization.ResourceContext;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeatriHumanRoleEquivalenceTest {
    private static final String TENANT = "ouf-lab";
    private static final String SUBJECT = "b93d8cf6-cd14-4ee6-91d7-84cd76c4f500";
    private static final String ISSUER = "https://issuer.example/realms/ouf";
    private static final Instant START = Instant.parse("2026-09-25T00:00:00Z");
    private static final Instant OVERLAP = Instant.parse("2026-11-11T12:00:00Z");
    private static final Instant NOMINAL_END = Instant.parse("2026-12-31T23:00:00Z");
    private static final Instant AFTER_NOMINAL_END = Instant.parse("2027-01-01T00:00:00Z");
    private static final Instant ROLE_END = Instant.parse("2036-09-15T07:13:50.968730Z");
    private static final Instant AFTER_ROLE_END = Instant.parse("2036-09-15T07:13:50.968731Z");

    private static final String PREVIEW = "ouf.managed-source.preview";
    private static final Map<String, String> NOMINAL_IDS = Map.of(
            "ouf.onboarding.configuration.write", "grant-onboarding-configuration-write-human-admin",
            "ouf.managed-source.file.upload", "grant-managed-file-upload-human-admin",
            "ouf.managed-source.file.profile", "grant-managed-file-profile-human-admin",
            PREVIEW, "grant-managed-file-preview-human-admin",
            "ouf.managed-source.onboarding.create", "grant-managed-file-onboarding-create-human-admin");
    private static final CapabilityDescriptor UNRELATED = descriptor("ingestion.contract.read", "READ");
    private static final List<CapabilityDescriptor> TARGETS = NOMINAL_IDS.keySet().stream()
            .sorted().map(id -> descriptor(id, PREVIEW.equals(id) ? "READ" : "COMMAND")).toList();
    private static final List<CapabilityDescriptor> DESCRIPTORS = Stream
            .concat(TARGETS.stream(), Stream.of(UNRELATED)).toList();

    record Addition(String tenantId, OufRoleCatalogue.Role role, OufRoleCatalogue.Assignment assignment) {}
    private record Scenario(CapabilityDescriptor capability, PrincipalContext principal, ResourceContext resource) {
        AuthorizationDecision decision(PolicyBundle bundle, Instant at) {
            return AuthorizationPolicy.evaluate(bundle, principal, resource, capability.capabilityId(),
                    capability.operation(), at);
        }
    }

    private static List<Grant> generated;

    @BeforeAll
    static void compileProposedCatalogue() throws IOException {
        var addition = JsonMapper.builder().addModule(new JavaTimeModule()).build().readValue(
                Path.of("catalogue/teatri-human-role-phase-1.json").toFile(), Addition.class);
        assertEquals(TENANT, addition.tenantId());
        assertEquals(SUBJECT, addition.assignment().subjectId());
        assertEquals(START, addition.assignment().validFrom());
        assertEquals(ROLE_END, addition.assignment().validUntil());
        var catalogue = new OufRoleCatalogue.Snapshot(ISSUER, List.of(addition.role()),
                List.of(addition.assignment()));
        generated = OufRoleCatalogue.compile(catalogue, addition.tenantId(), ISSUER, DESCRIPTORS);
        assertEquals(5, generated.size());
        var emptyAllow = new GrantConstraints("ALLOW", null, null, null, Map.of(), Set.of(), Set.of(),
                null, Set.of(), null);
        for (var grant : generated) {
            assertTrue(NOMINAL_IDS.containsKey(grant.capabilityId()));
            assertEquals(SUBJECT, grant.subjectId());
            assertEquals(TENANT, grant.tenantId());
            assertEquals(emptyAllow, grant.constraints());
        }
    }

    @Test
    void nominalAndCompiledRoleGiveIdenticalDecisionsDuringOverlap() {
        var nominal = bundle(63, nominalGrants(NOMINAL_END));
        var role = bundle(63, generated);
        targetScenarios().forEach(s ->
                assertEquals(s.decision(nominal, OVERLAP), s.decision(role, OVERLAP), s::toString));
    }

    @Test
    void proposedRolePreservesNominalDecisionsAfterNominalExpiry() {
        var nominal = bundle(63, nominalGrants(NOMINAL_END));
        var proposed = bundle(64, combine(nominal.grants(), generated));
        targetScenarios().forEach(s -> assertSameOutcome(
                s.decision(nominal, OVERLAP), s.decision(proposed, AFTER_NOMINAL_END), s));
    }

    @Test
    void compiledRoleGrantsNothingAfterTheAssignmentExpires() {
        var proposed = bundle(64, combine(nominalGrants(NOMINAL_END), generated));
        targetScenarios().forEach(s ->
                assertFalse(s.decision(proposed, AFTER_ROLE_END).allowed(), s::toString));
    }

    @Test
    void unrelatedCapabilityDecisionsAreUnchanged() {
        var existing = new Grant("existing-historical-contract", UNRELATED.capabilityId(), TENANT,
                SUBJECT, null, null, START, ROLE_END, new GrantConstraints("ALLOW", null, null,
                null, Map.of(), Set.of("OPEN", "ANONYMOUS"), Set.of(), null, Set.of(), null));
        var active = bundle(63, combine(nominalGrants(NOMINAL_END), List.of(existing)));
        var proposed = bundle(64, combine(active.grants(), generated));
        scenarios(UNRELATED).forEach(s -> {
            for (var at : List.of(OVERLAP, AFTER_NOMINAL_END, AFTER_ROLE_END)) {
                assertSameOutcome(s.decision(active, at), s.decision(proposed, at), s);
            }
        });
    }

    @Test
    void anExistingDetailGrantKeepsItsIndependentAuthority() {
        var existing = new Grant("existing-profile-preview", PREVIEW, TENANT, SUBJECT, null, null,
                START, ROLE_END, new GrantConstraints("ALLOW", null, "managed-profile-job", null,
                Map.of(), Set.of(), Set.of("TENANT_OPERATIONAL"), null, Set.of(), null));
        var proposed = bundle(64, combine(combine(nominalGrants(NOMINAL_END), generated), List.of(existing)));
        var reference = bundle(63, combine(nominalGrants(ROLE_END), List.of(existing)));
        targetScenarios().forEach(s -> assertSameOutcome(
                s.decision(reference, AFTER_NOMINAL_END), s.decision(proposed, AFTER_NOMINAL_END), s));
    }

    private static CapabilityDescriptor descriptor(String id, String operation) {
        return new CapabilityDescriptor(id, operation, id, Set.of(ActorType.HUMAN));
    }

    private static List<Grant> nominalGrants(Instant validUntil) {
        return TARGETS.stream().map(c -> new Grant(NOMINAL_IDS.get(c.capabilityId()), c.capabilityId(),
                TENANT, SUBJECT, null, null, START, validUntil)).toList();
    }

    private static List<Grant> combine(List<Grant> before, List<Grant> added) {
        return Stream.concat(before.stream(), added.stream()).toList();
    }

    private static PolicyBundle bundle(long version, List<Grant> grants) {
        return new PolicyBundle("ouf-lab-authorization", version, START, DESCRIPTORS, grants);
    }

    private static void assertSameOutcome(AuthorizationDecision expected, AuthorizationDecision actual,
                                          Scenario scenario) {
        assertEquals(expected.allowed(), actual.allowed(), scenario::toString);
        assertEquals(expected.decisionCode(), actual.decisionCode(), scenario::toString);
        assertEquals(expected.permittedDetailLevel(), actual.permittedDetailLevel(), scenario::toString);
        assertEquals(expected.resourceScope(), actual.resourceScope(), scenario::toString);
    }

    private static Stream<Scenario> targetScenarios() {
        return TARGETS.stream().flatMap(TeatriHumanRoleEquivalenceTest::scenarios);
    }

    private static Stream<Scenario> scenarios(CapabilityDescriptor capability) {
        var principals = new ArrayList<PrincipalContext>();
        for (var actor : List.of(ActorType.HUMAN, ActorType.SERVICE)) {
            for (var subject : List.of(SUBJECT, "other-subject")) {
                for (var tenant : List.of(TENANT, "other-tenant")) {
                    principals.add(new PrincipalContext(subject, tenant, actor,
                            actor == ActorType.SERVICE ? "fixture-service" : null,
                            "authentication", ISSUER, "fixture-audience", Set.of(capability.requiredScope())));
                }
            }
        }
        var resources = new ArrayList<ResourceContext>();
        for (var type : List.of("capability", "source", "managed-profile-job")) {
            for (var label : Arrays.asList(null, "OPEN", "ANONYMOUS", "PERSONAL", "RESTRICTED", "SENSITIVE")) {
                for (var detail : Arrays.asList(null, "PUBLIC_OPERATIONAL", "TENANT_OPERATIONAL",
                        "RESTRICTED_OPERATIONAL", "SECURITY_SENSITIVE")) {
                    for (var tenant : List.of(TENANT, "other-tenant")) {
                        var attributes = new HashMap<String, String>();
                        if (label != null) attributes.put("dataAccessLabel", label);
                        if (detail != null) attributes.put("detailLevel", detail);
                        if ("source".equals(type)) {
                            attributes.put("onboardingVersionId", "00000000-0000-0000-0000-000000000001");
                        }
                        resources.add(new ResourceContext(type, "capability".equals(type) ? null : "fixture-id",
                                tenant, null, attributes));
                    }
                }
            }
        }
        return principals.stream().flatMap(p -> resources.stream().map(r -> new Scenario(capability, p, r)));
    }
}
