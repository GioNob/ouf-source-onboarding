package it.comune.trieste.ouf.onboarding.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import it.comune.trieste.ouf.authorization.ServletAuthorization;
import it.comune.trieste.ouf.authorization.TestAuthorization;
import it.comune.trieste.ouf.onboarding.authorization.AuthorizationAdminService;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthorizationAdminBoundaryPrincipalTest {
  @Test
  void bootstrapUsesServerBoundPrincipalWhenServletPrincipalIsAbsent() throws Exception {
    var service = mock(AuthorizationAdminService.class);
    when(service.bootstrapOpen()).thenReturn(true);
    var boundary = new AuthorizationAdminBoundary(service);

    var request = new MockHttpServletRequest("POST", "/api/trusted-human/v1/authorization/policies");
    request.setAttribute(
        ServletAuthorization.TRUSTED_PRINCIPAL,
        TestAuthorization.principal("human:admin", "HUMAN", Set.of("authorization.bootstrap")));
    request.setAttribute("ouf.csrfValidated", Boolean.TRUE);
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    assertThat(request.getUserPrincipal()).isNull();
    boundary.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isNotNull();
  }
}
