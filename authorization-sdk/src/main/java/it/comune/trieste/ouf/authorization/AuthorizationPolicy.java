package it.comune.trieste.ouf.authorization;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class AuthorizationPolicy {
    private AuthorizationPolicy() {}

    public record CapabilityDescriptor(
            String capabilityId,
            String operation,
            String requiredScope,
            Set<PrincipalContext.ActorType> allowedActors) {
        public CapabilityDescriptor {
            capabilityId = require(capabilityId, "capabilityId");
            operation = require(operation, "operation");
            requiredScope = require(requiredScope, "requiredScope");
            allowedActors = allowedActors == null ? Set.of() : Set.copyOf(allowedActors);
        }
    }

    public record Grant(
            String grantId,
            String capabilityId,
            String tenantId,
            String subjectId,
            String servicePrincipalId,
            String organizationId,
            Instant validFrom,
            Instant validUntil) {
        public Grant {
            grantId = require(grantId, "grantId");
            capabilityId = require(capabilityId, "capabilityId");
            tenantId = require(tenantId, "tenantId");
            validFrom = Objects.requireNonNull(validFrom, "validFrom");
            validUntil = Objects.requireNonNull(validUntil, "validUntil");
            if (!validUntil.isAfter(validFrom)) {
                throw new IllegalArgumentException("validUntil must be after validFrom");
            }
            if ((subjectId == null || subjectId.isBlank()) && (servicePrincipalId == null || servicePrincipalId.isBlank())) {
                throw new IllegalArgumentException("grant requires subjectId or servicePrincipalId");
            }
        }

        boolean appliesTo(PrincipalContext principal, ResourceContext resource, Instant now) {
            if (!tenantId.equals(principal.tenantId()) || !tenantId.equals(resource.tenantId())) return false;
            if (now.isBefore(validFrom) || !now.isBefore(validUntil)) return false;
            if (organizationId != null && !organizationId.isBlank() && !organizationId.equals(resource.organizationId())) return false;
            if (subjectId != null && !subjectId.isBlank() && !subjectId.equals(principal.subjectId())) return false;
            return servicePrincipalId == null || servicePrincipalId.isBlank() || servicePrincipalId.equals(principal.servicePrincipalId());
        }
    }

    public record PolicyBundle(
            String bundleId,
            long version,
            Instant publishedAt,
            List<CapabilityDescriptor> capabilities,
            List<Grant> grants) {
        public PolicyBundle {
            bundleId = require(bundleId, "bundleId");
            if (version < 1) throw new IllegalArgumentException("version must be positive");
            publishedAt = Objects.requireNonNull(publishedAt, "publishedAt");
            capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
            grants = grants == null ? List.of() : List.copyOf(grants);
        }
    }

    public record AuthorizationDecision(
            boolean allowed,
            String decisionCode,
            String decisionRef,
            String bundleId,
            long bundleVersion) {}

    public static AuthorizationDecision evaluate(
            PolicyBundle bundle,
            PrincipalContext principal,
            ResourceContext resource,
            String capabilityId,
            String operation,
            Instant now) {
        Objects.requireNonNull(bundle, "bundle");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(now, "now");
        String ref = bundle.bundleId() + ":" + bundle.version() + ":" + capabilityId;
        if (!principal.tenantId().equals(resource.tenantId())) {
            return deny("TENANT_MISMATCH", ref, bundle);
        }
        CapabilityDescriptor descriptor = bundle.capabilities().stream()
                .filter(c -> c.capabilityId().equals(capabilityId) && c.operation().equals(operation))
                .findFirst().orElse(null);
        if (descriptor == null) return deny("CAPABILITY_NOT_DECLARED", ref, bundle);
        if (!descriptor.allowedActors().contains(principal.actorType())) return deny("ACTOR_NOT_ALLOWED", ref, bundle);
        if (!principal.scopes().contains(descriptor.requiredScope())) return deny("SCOPE_MISSING", ref, bundle);
        boolean grant = bundle.grants().stream()
                .filter(g -> g.capabilityId().equals(capabilityId))
                .anyMatch(g -> g.appliesTo(principal, resource, now));
        if (!grant) return deny("NO_APPLICABLE_GRANT", ref, bundle);
        return new AuthorizationDecision(true, "ALLOW", ref, bundle.bundleId(), bundle.version());
    }

    private static AuthorizationDecision deny(String code, String ref, PolicyBundle bundle) {
        return new AuthorizationDecision(false, code, ref, bundle.bundleId(), bundle.version());
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
