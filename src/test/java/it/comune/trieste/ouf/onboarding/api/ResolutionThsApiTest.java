package it.comune.trieste.ouf.onboarding.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.CsrfToken;

class ResolutionThsApiTest {
  @Test void forwardsOnlyServerSideHumanTokenThroughFixedGatewayPath() throws Exception {
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    var forwarded=new AtomicReference<String>();
    var path=new AtomicReference<String>();
    server.createContext("/api/udp/v1/governance/resolution/issues/package",exchange->{
      forwarded.set(exchange.getRequestHeaders().getFirst("Authorization"));
      path.set(exchange.getRequestURI().getPath());
      String payload="POST".equals(exchange.getRequestMethod())
          ?"{\"packageId\":\"tested\",\"issueCount\":1}":"{\"tenantId\":\"default\",\"snapshotHash\":\"sha256:test\",\"issues\":[]}";
      byte[] bytes=payload.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type","application/json");
      exchange.sendResponseHeaders(200,bytes.length);
      try(var out=exchange.getResponseBody()){out.write(bytes);}
    });
    server.start();
    try{
      OAuth2AuthorizedClientService clients=mock(OAuth2AuthorizedClientService.class);
      var registration=ClientRegistration.withRegistrationId("ouf-ths").clientId("test")
          .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
          .redirectUri("http://localhost/callback").authorizationUri("http://localhost/auth")
          .tokenUri("http://localhost/token").build();
      var access=new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,"private-human-token",
          Instant.now().minusSeconds(1),Instant.now().plusSeconds(60));
      when(clients.loadAuthorizedClient("ouf-ths","subject-1"))
          .thenReturn(new OAuth2AuthorizedClient(registration,"subject-1",access));
      var principal=new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")),
          Map.of("sub","subject-1"),"sub");
      SecurityContextHolder.getContext().setAuthentication(new OAuth2AuthenticationToken(
          principal,principal.getAuthorities(),"ouf-ths"));
      var request=new MockHttpServletRequest();
      request.setAttribute("ouf.permissionThsSession",Boolean.TRUE);
      CsrfToken csrf=new DefaultCsrfToken("X-CSRF-TOKEN","_csrf","csrf-test");
      request.setAttribute(CsrfToken.class.getName(),csrf);
      var api=new ResolutionThsApi(clients,new ObjectMapper(),
          "http://127.0.0.1:"+server.getAddress().getPort());
      var response=api.prepare(request);
      assertThat(response.getBody().toString()).contains("sha256:test","csrf-test")
          .doesNotContain("private-human-token");
      assertThat(forwarded.get()).isEqualTo("Bearer private-human-token");
      assertThat(path.get()).isEqualTo("/api/udp/v1/governance/resolution/issues/package");
      var confirmed=api.confirm("{}".getBytes(StandardCharsets.UTF_8),request);
      assertThat(confirmed.getBody()).contains("tested");
      assertThat(path.get()).endsWith("/package/confirm");
    }finally{SecurityContextHolder.clearContext();server.stop(0);}
  }

  @Test void rejectsAnUntrustedGatewayOrigin(){
    assertThatThrownBy(()->new ResolutionThsApi(mock(OAuth2AuthorizedClientService.class),
        new ObjectMapper(),"http://untrusted.example"))
        .hasMessage("THS_RESOLUTION_GATEWAY_INVALID");
  }
}
