package it.comune.trieste.ouf.onboarding.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import it.comune.trieste.ouf.authorization.AuthorizationPolicy.PolicyBundle;
import it.comune.trieste.ouf.onboarding.authorization.AuthorizationPolicyRegistry;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AuthorizationBundleApiTest {
  @Test
  void authorizedServiceReadsExactActiveBundle() {
    var registry = mock(AuthorizationPolicyRegistry.class);
    var request = new org.springframework.mock.web.MockHttpServletRequest();
    it.comune.trieste.ouf.authorization.TestAuthorization.bind(request,"service:mcp","SERVICE",Set.of("authorization.bundle.read"));
    Instant activatedAt = Instant.parse("2026-09-16T12:00:00Z");
    var bundle = new PolicyBundle(
        "bundle-main", 7, Instant.parse("2026-09-16T11:59:00Z"), List.of(), List.of());
    when(registry.active()).thenReturn(Optional.of(
        new AuthorizationPolicyRegistry.ActiveBundle("bundle-main", 7, activatedAt)));
    when(registry.load("bundle-main", 7)).thenReturn(bundle);

    var api = new AuthorizationBundleApi(registry, new TrustedActorResolver());
    var out = api.active(request);

    assertEquals("bundle-main", out.bundleId());
    assertEquals(7, out.bundleVersion());
    assertEquals(activatedAt, out.activatedAt());
    assertSame(bundle, out.bundle());
  }

  @Test
  void bundleDistributionFailsClosedWithoutServiceCapability() {
    var registry = mock(AuthorizationPolicyRegistry.class);
    var request = mock(HttpServletRequest.class);
    Principal principal = () -> "service:mcp";
    when(request.getUserPrincipal()).thenReturn(principal);
    when(request.isUserInRole("OUF_SERVICE")).thenReturn(true);
    when(request.getAttribute("ouf.authorizedCapabilities")).thenReturn(Set.of());

    var api = new AuthorizationBundleApi(registry, new TrustedActorResolver());
    assertThrows(DomainFailure.class, () -> api.active(request));
    verifyNoInteractions(registry);
  }
}
