package it.comune.trieste.ouf.onboarding.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.*;

class ThsErrorsTest {
  @ParameterizedTest
  @CsvSource({"403,Accesso negato","404,Pagina non trovata","503,verifica lo stato"})
  void pageErrorDispatchRendersGuidanceWithoutInternalDetail(int status,String guidance) throws Exception {
    var request = new MockHttpServletRequest("GET", "/error");
    request.addHeader("Accept", "text/html");
    request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/trusted-human/semantic/missing");
    request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
    request.setAttribute(RequestDispatcher.ERROR_MESSAGE, "internal-fixture-detail");
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();
    new ThsErrors().thsErrorPages().getFilter().doFilter(request, response, chain);
    assertThat(chain.getRequest()).isNull();
    assertThat(response.getStatus()).isEqualTo(status);
    assertThat(response.getContentAsString()).contains("Errore " + status, guidance)
        .doesNotContain("internal-fixture-detail","Whitelabel");
  }

  @ParameterizedTest
  @CsvSource({"/trusted-human/semantic/api/missing","/api/onboarding/v1/missing"})
  void otherErrorDispatchesKeepTheirExistingHandler(String uri) throws Exception {
    var request = new MockHttpServletRequest("GET", "/error");
    request.addHeader("Accept", "text/html");
    request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, uri);
    request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 404);
    var chain = new MockFilterChain();
    new ThsErrors().thsErrorPages().getFilter().doFilter(request, new MockHttpServletResponse(), chain);
    assertThat(chain.getRequest()).isSameAs(request);
  }
}
