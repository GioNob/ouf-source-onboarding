package it.comune.trieste.ouf.onboarding.authorization;

import it.comune.trieste.ouf.authorization.AuthorizationPolicy.Grant;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.PolicyBundle;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Observes the configured lifetime of a grant without evaluating effective permissions. */
public final class AuthorizationGrantExpiry {
  private AuthorizationGrantExpiry() {}

  public enum Window { EXPIRED, DUE_7D, DUE_30D }
  public enum Status { OK, IN_EXPIRY_WINDOW, EXPIRED_GRANTS }

  public record Finding(String tenantId, String grantId, String capabilityId, String effect,
                        Instant validFrom, Instant validUntil, Window window, String potentialDependency) {}
  public record Report(String policyRef, Instant checkedAt, Status status, List<Finding> findings) {}

  private record Dependency(String capability, String name, boolean service) {}

  private static final Map<String, Dependency> TEATRI_DEPENDENCIES = Map.of(
      "grant-onboarding-configuration-write-human-admin",
      new Dependency("ouf.onboarding.configuration.write", "ONBOARDING_THS_REVIEW_AND_ACTIVATION", false),
      "grant-managed-file-upload-human-admin",
      new Dependency("ouf.managed-source.file.upload", "ONBOARDING_MANAGED_FILE_UPLOAD", false),
      "grant-managed-file-profile-human-admin",
      new Dependency("ouf.managed-source.file.profile", "ONBOARDING_MANAGED_FILE_PROFILE", false),
      "grant-managed-file-preview-human-admin",
      new Dependency("ouf.managed-source.preview", "ONBOARDING_MANAGED_FILE_PREVIEW", false),
      "grant-managed-file-onboarding-create-human-admin",
      new Dependency("ouf.managed-source.onboarding.create", "ONBOARDING_MANAGED_DRAFT_CREATION", false),
      "grant-managed-file-read-ingestion",
      new Dependency("ouf.object-storage.content.read", "INGESTION_MANAGED_FILE_READINESS_AND_READ", true),
      "grant-managed-file-read-onboarding",
      new Dependency("ouf.object-storage.content.read", "ONBOARDING_MANAGED_FILE_READ", true));

  static Report inspect(PolicyBundle bundle, Instant now, Predicate<Grant> scope) {
    Instant inSevenDays = now.plus(Duration.ofDays(7));
    Instant inThirtyDays = now.plus(Duration.ofDays(30));
    var findings = bundle.grants().stream()
        .filter(scope)
        .filter(grant -> !grant.validUntil().isAfter(inThirtyDays))
        .map(grant -> {
          Window window = !grant.validUntil().isAfter(now) ? Window.EXPIRED
              : !grant.validUntil().isAfter(inSevenDays) ? Window.DUE_7D : Window.DUE_30D;
          String effect = grant.constraints() == null ? "ALLOW" : grant.constraints().effect();
          return new Finding(grant.tenantId(), grant.grantId(), grant.capabilityId(), effect,
              grant.validFrom(), grant.validUntil(), window, dependency(grant, effect));
        })
        .sorted(Comparator.comparing(Finding::validUntil).thenComparing(Finding::grantId))
        .toList();
    Status status = findings.isEmpty() ? Status.OK
        : findings.getFirst().window() == Window.EXPIRED ? Status.EXPIRED_GRANTS : Status.IN_EXPIRY_WINDOW;
    return new Report(bundle.bundleId() + ":" + bundle.version(), now, status, findings);
  }

  private static String dependency(Grant grant, String effect) {
    if (!"ALLOW".equals(effect)) return "DENY_EXPIRY_REQUIRES_REVIEW";
    Dependency dependency = TEATRI_DEPENDENCIES.get(grant.grantId());
    boolean service = grant.servicePrincipalId() != null && grant.subjectId() == null;
    boolean human = grant.subjectId() != null && grant.servicePrincipalId() == null;
    return "ouf-lab".equals(grant.tenantId()) && dependency != null
        && dependency.capability().equals(grant.capabilityId())
        && (dependency.service() ? service : human) ? dependency.name() : "UNMAPPED";
  }
}
