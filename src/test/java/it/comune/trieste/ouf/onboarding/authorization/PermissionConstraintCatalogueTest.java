package it.comune.trieste.ouf.onboarding.authorization;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.GrantConstraints;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class PermissionConstraintCatalogueTest {
    private final ObjectMapper json = new ObjectMapper();
    private final PermissionConstraintCatalogue catalogue;

    PermissionConstraintCatalogueTest() throws Exception {
        catalogue = new PermissionConstraintCatalogue(json);
    }

    private static GrantConstraints levels(Set<String> values) {
        return new GrantConstraints("ALLOW", null, null, null, Map.of(), Set.of(), values, null, Set.of(), null);
    }

    private static OufRoleCatalogue.Snapshot snapshot(List<OufRoleCatalogue.Role> roles) {
        return new OufRoleCatalogue.Snapshot("fixture-issuer", roles, List.of());
    }

    private PermissionConstraintCatalogue.Assessment assess(OufRoleCatalogue.Snapshot before, List<OufRoleCatalogue.Role> roles) {
        var change = new PermissionProposalService.Change("REPLACE_ROLES", null, null, "fixture", snapshot(roles));
        return catalogue.assess("fixture-tenant", before, change, List.of());
    }

    private static OufRoleCatalogue.Role role(String roleId, String capability, Set<String> values) {
        return new OufRoleCatalogue.Role(roleId, roleId, List.of(new OufRoleCatalogue.Permission(capability, levels(values))));
    }

    @Test void malformedReleaseDataCannotBecomeAnEmptyDeclaration() {
        String valid = "{\"version\":1,\"capabilities\":{\"cap\":[]}}";
        for (String invalid : List.of(valid.replace("1", "2"), valid.replace("[]", "null"),
                valid.replace("[]", "[\"detailLevel\",\"detailLevel\"]"),
                valid.replace("\"cap\":[]", "\"cap\":[],\"cap\":[]"), valid + "{}")) {
            var input = new java.io.ByteArrayInputStream(invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThatThrownBy(() -> PermissionConstraintCatalogue.read(json, input))
                    .isInstanceOfAny(IllegalStateException.class, java.io.IOException.class);
        }
    }

    @Test void everyReleasedSemanticAndOnboardingCapabilityHasAnExplicitDeclaration() throws Exception {
        for (String file : ReleaseCapabilityRegistration.CATALOGUES) {
            try (var input = new ClassPathResource(file).getInputStream()) {
                for (var entry : json.readTree(input)) {
                    if (!Set.of("semantic","onboarding").contains(entry.required("ownerRef").textValue())) continue;
                    String id = entry.required("descriptor").required("capabilityId").textValue();
                    var result = assess(snapshot(List.of()), List.of(role("reader", id, Set.of("TENANT_OPERATIONAL"))));
                    assertThat(result.warnings()).as(id).isEmpty();
                }
            }
        }
    }

    @Test void comparisonUsesBothRoleAndCapabilityAndDetectsConstraintChanges() {
        var old = role("reader", "ouf.semantic.search", Set.of("PUBLIC_OPERATIONAL"));
        var changed = role("reader", "ouf.semantic.search", Set.of("TENANT_OPERATIONAL"));
        var additional = role("operator", "ouf.semantic.search", Set.of("PUBLIC_OPERATIONAL"));
        var result = assess(snapshot(List.of(old)), List.of(changed, additional));
        assertThat(result.violations()).extracting(PermissionConstraintCatalogue.Finding::role)
                .containsExactly("reader", "operator");
        assertThat(result.warnings()).isEmpty();
    }

    @Test void declaredDetailLevelAndEmptyListsNeedNoCorrection() {
        var result = assess(snapshot(List.of()), List.of(
                role("reviewer", "ouf.onboarding.quarantine.release", Set.of("TENANT_OPERATIONAL")),
                role("reader", "ouf.semantic.search", Set.of()),
                role("other", "undeclared", Set.of())));
        result.requireApplicable();
        assertThat(result.warnings()).isEmpty();
    }

    @Test void violationLimitReportsWhetherAnotherEntryWasOmitted() {
        var roles = new ArrayList<OufRoleCatalogue.Role>();
        for (int i = 0; i < 65; i++) roles.add(role("reader-" + i, "ouf.semantic.search", Set.of("PUBLIC_OPERATIONAL")));
        for (int count : List.of(64, 65)) {
            var result = assess(snapshot(List.of()), roles.subList(0, count));
            assertThat(result.violations()).hasSize(64);
            assertThatThrownBy(result::requireApplicable).isInstanceOfSatisfying(DomainFailure.class,
                    e -> assertThat(e.properties()).containsEntry("truncated", count > 64));
        }
    }
}
