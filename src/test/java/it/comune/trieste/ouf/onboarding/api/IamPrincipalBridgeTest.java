package it.comune.trieste.ouf.onboarding.api;

import static org.assertj.core.api.Assertions.assertThat;

import it.comune.trieste.ouf.authorization.ServletAuthorization;
import it.comune.trieste.ouf.authorization.TestAuthorization;
import it.comune.trieste.ouf.authorization.TrustedPrincipal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

class IamPrincipalBridgeTest {
  private static Jwt jwt(String subject) {
    return new Jwt(
        "token",
        Instant.now().minusSeconds(10),
        Instant.now().plusSeconds(300),
        Map.of("alg", "none"),
        Map.of("sub", subject, "iss", "https://auth.ouf-lab.it/realms/ouf"));
  }

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void servicePrincipalIsBoundServerSideEvenWhenServletPrincipalIsAbsent() throws Exception {
    var trusted = TestAuthorization.principal(
        "workload:ouf-mcp-server", "SERVICE", Set.of("authorization.bundle.read"));
    var authentication = new IamSecurityConfiguration.TrustedJwtAuthenticationToken(
        jwt("workload:ouf-mcp-server"), trusted, Set.of());
    SecurityContextHolder.getContext().setAuthentication(authentication);

    var request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer test-token");
    request.getServletContext().setAttribute(
        ServletAuthorization.RUNTIME,
        TestAuthorization.runtime(
            "workload:ouf-mcp-server", "SERVICE", Set.of("authorization.bundle.read")));
    assertThat(request.getUserPrincipal()).isNull();

    new IamSecurityConfiguration.TrustedBearerPrincipalBridgeFilter().doFilter(
        request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(request.getAttribute(ServletAuthorization.TRUSTED_PRINCIPAL)).isSameAs(trusted);
    assertThat(request.getAttribute("ouf.csrfValidated")).isNull();
    var resolved = ServletAuthorization.require(request, "authorization.bundle.read", false);
    assertThat(resolved.principal().subjectId()).isEqualTo("workload:ouf-mcp-server");
  }

  @Test
  void authenticatedHumanBearerGetsTrustedBindingAndWriteMarker() throws Exception {
    var trusted = TestAuthorization.principal(
        "human:admin", "HUMAN", Set.of("authorization.bootstrap"));
    var authentication = new IamSecurityConfiguration.TrustedJwtAuthenticationToken(
        jwt("human:admin"), trusted, Set.of());
    SecurityContextHolder.getContext().setAuthentication(authentication);

    var request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer test-token");

    new IamSecurityConfiguration.TrustedBearerPrincipalBridgeFilter().doFilter(
        request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(request.getAttribute(ServletAuthorization.TRUSTED_PRINCIPAL)).isSameAs(trusted);
    assertThat(request.getAttribute("ouf.csrfValidated")).isEqualTo(Boolean.TRUE);
  }

  @Test
  void unauthenticatedRequestCannotForgeTrustedBindingThroughHeaders() throws Exception {
    var request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer attacker-controlled");
    request.addHeader("X-OUF-Subject", "workload:forged");

    new IamSecurityConfiguration.TrustedBearerPrincipalBridgeFilter().doFilter(
        request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(request.getAttribute(ServletAuthorization.TRUSTED_PRINCIPAL)).isNull();
    assertThat(request.getAttribute("ouf.csrfValidated")).isNull();
  }
}
