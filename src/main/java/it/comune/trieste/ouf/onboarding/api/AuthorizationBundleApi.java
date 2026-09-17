package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.authorization.AuthorizationPolicy.PolicyBundle;
import it.comune.trieste.ouf.onboarding.authorization.AuthorizationPolicyRegistry;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/internal/v1/authorization/policy-bundle")
public class AuthorizationBundleApi {
  private final AuthorizationPolicyRegistry registry;
  private final TrustedActorResolver actors;

  public AuthorizationBundleApi(AuthorizationPolicyRegistry registry, TrustedActorResolver actors) {
    this.registry = registry;
    this.actors = actors;
  }

  @GetMapping("/active")
  ActivePolicyBundleView active(HttpServletRequest request) {
    var actor = actors.actor(request);
    if (!"SERVICE".equals(actor.type()) || !actor.capabilities().contains("authorization.bundle.read")) {
      throw new DomainFailure(
          HttpStatus.FORBIDDEN,
          "AUTH_BUNDLE_READ_REQUIRED",
          "Active policy bundle distribution requires an authorized service principal");
    }
    var pointer = registry.active().orElseThrow(() -> new DomainFailure(
        HttpStatus.SERVICE_UNAVAILABLE,
        "AUTH_POLICY_BUNDLE_UNAVAILABLE",
        "No active Authorization policy bundle is available"));
    PolicyBundle bundle = registry.load(pointer.bundleId(), pointer.version());
    return new ActivePolicyBundleView(pointer.bundleId(), pointer.version(), pointer.activatedAt(), bundle, registry.transportHash(bundle));
  }

  public record ActivePolicyBundleView(
      String bundleId,
      long bundleVersion,
      Instant activatedAt,
      PolicyBundle bundle, String contentHash) {
    public ActivePolicyBundleView(String bundleId,long bundleVersion,Instant activatedAt,PolicyBundle bundle){this(bundleId,bundleVersion,activatedAt,bundle,null);}
  }
}
