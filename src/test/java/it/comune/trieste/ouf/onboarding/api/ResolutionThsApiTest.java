package it.comune.trieste.ouf.onboarding.api;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
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

      var access=new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,"private-human-token",
          Instant.now().minusSeconds(1),Instant.now().plusSeconds(60));

      var request=new MockHttpServletRequest();
      request.setAttribute("ouf.permissionThsSession",Boolean.TRUE);
      request.setAttribute(PermissionThsSecurity.REQUEST_ACCESS_TOKEN,access);
      CsrfToken csrf=new DefaultCsrfToken("X-CSRF-TOKEN","_csrf","csrf-test");
      request.setAttribute(CsrfToken.class.getName(),csrf);
      var api=new ResolutionThsApi(new ObjectMapper(),
          "http://127.0.0.1:"+server.getAddress().getPort());
      var response=api.prepare(request);
      assertThat(response.getBody().toString()).contains("sha256:test","csrf-test")
          .doesNotContain("private-human-token");
      assertThat(forwarded.get()).isEqualTo("Bearer private-human-token");
      assertThat(path.get()).isEqualTo("/api/udp/v1/governance/resolution/issues/package");
      var confirmed=api.confirm("{}".getBytes(StandardCharsets.UTF_8),request);
      assertThat(confirmed.getBody()).contains("tested");
      assertThat(path.get()).endsWith("/package/confirm");
    }finally{server.stop(0);}
  }

  @Test void rejectsAnUntrustedGatewayOrigin(){
    assertThatThrownBy(()->new ResolutionThsApi(
        new ObjectMapper(),"http://untrusted.example"))
        .hasMessage("THS_RESOLUTION_GATEWAY_INVALID");
  }
}
