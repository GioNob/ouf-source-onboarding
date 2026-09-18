package it.comune.trieste.ouf.onboarding.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import it.comune.trieste.ouf.authorization.AuthorizationPolicy.PolicyBundle;
import it.comune.trieste.ouf.authorization.TestAuthorization;
import it.comune.trieste.ouf.onboarding.authorization.AuthorizationPolicyRegistry;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class AuthorizationBundleApiTest {
  private MockHttpServletRequest serviceRequest(Set<String> scopes) {
    var request = new MockHttpServletRequest();
    request.setUserPrincipal(TestAuthorization.principal("ouf-mcp-server", "SERVICE", scopes));
    return request;
  }

  @Test
  void authorizedServiceReadsExactActiveBundle() {
    var registry = mock(AuthorizationPolicyRegistry.class);
    var request = serviceRequest(Set.of("authorization.bundle.read"));
    Instant activatedAt = Instant.parse("2026-09-16T12:00:00Z");
    var bundle = new PolicyBundle(
        "bundle-main", 7, Instant.parse("2026-09-16T11:59:00Z"), List.of(), List.of());
    when(registry.active()).thenReturn(Optional.of(
        new AuthorizationPolicyRegistry.ActiveBundle("bundle-main", 7, activatedAt)));
    when(registry.load("bundle-main", 7)).thenReturn(bundle);

    var api = new AuthorizationBundleApi(registry);
    var out = api.active(request);

    assertEquals("bundle-main", out.bundleId());
    assertEquals(7, out.bundleVersion());
    assertEquals(activatedAt, out.activatedAt());
    assertSame(bundle, out.bundle());
  }

  @Test
  void authorizedServiceGets503BeforeFirstActiveBundle() {
    var registry = mock(AuthorizationPolicyRegistry.class);
    var request = serviceRequest(Set.of("authorization.bundle.read"));
    when(registry.active()).thenReturn(Optional.empty());

    var failure = assertThrows(DomainFailure.class, () -> new AuthorizationBundleApi(registry).active(request));

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.status());
    verify(registry).active();
    verifyNoMoreInteractions(registry);
  }

  @Test
  void bundleDistributionRejectsServiceWithoutRequiredScope() {
    var registry = mock(AuthorizationPolicyRegistry.class);
    var request = serviceRequest(Set.of());

    var failure = assertThrows(DomainFailure.class, () -> new AuthorizationBundleApi(registry).active(request));

    assertEquals(HttpStatus.FORBIDDEN, failure.status());
    verifyNoInteractions(registry);
  }

  @Test
  void bundleDistributionRejectsHumanEvenWithBundleReadScope() {
    var registry = mock(AuthorizationPolicyRegistry.class);
    var request = new MockHttpServletRequest();
    request.setUserPrincipal(TestAuthorization.principal("human:admin", "HUMAN", Set.of("authorization.bundle.read")));

    var failure = assertThrows(DomainFailure.class, () -> new AuthorizationBundleApi(registry).active(request));

    assertEquals(HttpStatus.FORBIDDEN, failure.status());
    verifyNoInteractions(registry);
  }
}
