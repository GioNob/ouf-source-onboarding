package it.comune.trieste.ouf.onboarding.authorization;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.Grant;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.GrantConstraints;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Resource attributes supplied on at least one owner decision path for each declared capability. */
@Component
public final class PermissionConstraintCatalogue {
    static final String RESOURCE = "catalogue/resource-attributes-capabilities.json";
    private final Map<String, Set<String>> attributes;

    public PermissionConstraintCatalogue(ObjectMapper json) throws IOException {
        try (var input = new ClassPathResource(RESOURCE).getInputStream()) {
            attributes = read(json, input);
        }
    }

    static Map<String, Set<String>> read(ObjectMapper json, InputStream input) throws IOException {
        JsonNode root = json.reader().withFeatures(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(input);
        if (root == null || !root.isObject() || root.size() != 2 || !root.path("version").isInt()
                || root.get("version").intValue() != 1 || !root.path("capabilities").isObject()
                || root.get("capabilities").isEmpty())
            throw new IllegalStateException("AUTH_RESOURCE_CATALOGUE_INVALID " + RESOURCE);
        var entries = new HashMap<String, Set<String>>();
        root.get("capabilities").fields().forEachRemaining(entry -> {
            var names = new HashSet<String>();
            if (!entry.getKey().matches("[a-z][a-z0-9.-]*") || !entry.getValue().isArray())
                throw new IllegalStateException("AUTH_RESOURCE_CATALOGUE_INVALID " + entry.getKey());
            for (var name : entry.getValue()) {
                if (!name.isTextual() || !name.textValue().matches("[a-zA-Z][a-zA-Z0-9]*") || !names.add(name.textValue()))
                    throw new IllegalStateException("AUTH_RESOURCE_CATALOGUE_INVALID " + entry.getKey());
            }
            entries.put(entry.getKey(), Set.copyOf(names));
        });
        return Map.copyOf(entries);
    }

    public enum Kind { INERT_UNCHANGED, UNVERIFIABLE }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Finding(String role, String grantId, String capabilityId, String field,
            String missingResourceAttribute, @JsonInclude(JsonInclude.Include.NON_NULL) Kind kind) {}

    static final class Assessment {
        private final List<Finding> warnings = new ArrayList<>();
        private final List<Finding> violations = new ArrayList<>();
        private boolean truncated;

        List<Finding> warnings() { return List.copyOf(warnings); }

        List<Finding> violations() { return List.copyOf(violations); }
        boolean truncated() { return truncated; }

        void requireApplicable() {
            if (!violations.isEmpty())
                throw new DomainFailure(HttpStatus.UNPROCESSABLE_ENTITY, "AUTH_GRANT_CONSTRAINTS_INAPPLICABLE",
                        "Correggere i vincoli per gli attributi che l’owner non fornisce.",
                        Map.of("violations", violations(), "truncated", truncated));
        }

        void add(Finding finding) {
            if (finding.kind() != null) warnings.add(finding);
            else if (violations.size() < 64) violations.add(finding);
            else truncated = true;
        }
    }

    private record RolePermission(String role, String capabilityId) {}

    Assessment assess(String tenant, OufRoleCatalogue.Snapshot before, PermissionProposalService.Change change,
            List<Grant> proposedGrants) {
        var result = new Assessment();
        var after = change.roleCatalogue() == null ? before : change.roleCatalogue();
        if (after != null) {
            var baseline = new HashMap<RolePermission, OufRoleCatalogue.Permission>();
            if (before != null) {
                for (var role : before.roles()) {
                    for (var permission : role.permissions())
                        baseline.put(new RolePermission(role.roleId(), permission.capabilityId()), permission);
                }
            }
            for (var role : after.roles()) {
                for (var permission : role.permissions()) {
                    var key = new RolePermission(role.roleId(), permission.capabilityId());
                    inspect(result, role.roleId(), null, permission.capabilityId(), permission.constraints(),
                            permission.equals(baseline.get(key)));
                }
            }
        }
        for (var grant : proposedGrants) {
            if (!tenant.equals(grant.tenantId())) continue;
            // Old direct proposals did not snapshot roles. In that case retain the compiled grant identity.
            if (after != null && grant.grantId().startsWith(OufRoleCatalogue.PREFIX)) continue;
            inspect(result, null, grant.grantId(), grant.capabilityId(), grant.constraints(),
                    !grant.grantId().equals(change.grantId()));
        }
        return result;
    }

    private void inspect(Assessment result, String role, String grantId, String capabilityId,
            GrantConstraints constraints, boolean unchanged) {
        if (constraints == null) return;
        if (!constraints.allowedDataLabels().isEmpty())
            check(result, role, grantId, capabilityId, "allowedDataLabels", "dataAccessLabel", unchanged);
        if (!constraints.allowedDetailLevels().isEmpty())
            check(result, role, grantId, capabilityId, "allowedDetailLevels", "detailLevel", unchanged);
    }

    private void check(Assessment result, String role, String grantId, String capabilityId,
            String field, String attribute, boolean unchanged) {
        var supplied = attributes.get(capabilityId);
        if (supplied != null && supplied.contains(attribute)) return;
        Kind kind = supplied == null ? Kind.UNVERIFIABLE : (unchanged ? Kind.INERT_UNCHANGED : null);
        result.add(new Finding(role, grantId, capabilityId, field, attribute, kind));
    }
}
