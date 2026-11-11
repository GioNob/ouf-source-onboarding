package it.comune.trieste.ouf.onboarding.api;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.web.csrf.*;

class WorkflowThsApiTest {
  @Test void ownerRoutingAndFrozenHashAreBoundWithoutExposingBearerOrRetryingDecision()throws Exception{
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    var posts=new AtomicInteger();var path=new AtomicReference<String>();var body=new AtomicReference<String>();
    var bearer=new AtomicReference<String>();var key=new AtomicReference<String>();
    String hash="a".repeat(64);UUID id=UUID.randomUUID();
    server.createContext("/api/trusted-human/v1/",exchange->{
      path.set(exchange.getRequestURI().getPath());bearer.set(exchange.getRequestHeaders().getFirst("Authorization"));
      key.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
      if("POST".equals(exchange.getRequestMethod())){posts.incrementAndGet();body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));}
      byte[] out=("{\"challenge_id\":\""+id+"\",\"target_content_hash\":\""+hash+"\",\"status\":\"OPEN\"}").getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,out.length);
      try(var stream=exchange.getResponseBody()){stream.write(out);}
    });server.start();
    try{
      var request=session();
      var json=new ObjectMapper();var api=new WorkflowThsApi(json,"http://127.0.0.1:"+server.getAddress().getPort());
      assertThat(api.card("semantic",id,request).getBody().toString()).contains("csrf-test",hash).doesNotContain("private-human-token");
      assertThat(bearer.get()).isEqualTo("Bearer private-human-token");assertThat(posts).hasValue(0);
      assertThatThrownBy(()->api.action("semantic",id,"decision",json.writeValueAsBytes(Map.of("decision","APPROVED","expectedContentHash","b".repeat(64))),request)).hasMessageContaining("THS_REVIEW_STALE_CARD");
      assertThat(posts).hasValue(0);
      api.action("semantic",id,"decision",json.writeValueAsBytes(Map.of("decision","APPROVED","expectedContentHash",hash)),request);
      assertThat(posts).hasValue(1);assertThat(path.get()).endsWith("/semantic-approval-challenges/"+id+"/decision");
      assertThat(json.readTree(body.get())).isEqualTo(json.readTree("{\"decision\":\"APPROVED\"}"));
      api.action("semantic",id,"publish",json.writeValueAsBytes(Map.of("approvalDecisionId",id.toString(),"contentHash",hash)),request);
      assertThat(key.get()).isEqualTo("ths-semantic-publish:"+id);assertThat(posts).hasValue(2);
      api.action("onboarding",id,"confirm",json.writeValueAsBytes(Map.of("configurationHash","sha256:"+hash,"expectedVersion",2)),request);
      assertThat(path.get()).endsWith("/approval-challenges/"+id+"/review/confirm");assertThat(posts).hasValue(3);
      assertThatThrownBy(()->api.action("onboarding",id,"confirm",json.writeValueAsBytes(Map.of("configurationHash","sha256:"+hash,"expectedVersion",2,"actor","HUMAN")),request)).hasMessageContaining("THS_REVIEW_INVALID");
      assertThat(posts).hasValue(3);
      request.removeAttribute("ouf.permissionThsSession");
      assertThatThrownBy(()->api.card("semantic",id,request)).hasMessageContaining("THS_SESSION_REQUIRED");
    }finally{server.stop(0);}
  }
  @Test void gatewayOriginCannotBeSuppliedByTheBrowser(){
    assertThatThrownBy(()->new WorkflowThsApi(new ObjectMapper(),"http://elsewhere.example")).hasMessage("THS_WORKFLOW_GATEWAY_INVALID");
    assertThatThrownBy(()->new WorkflowThsApi(new ObjectMapper(),"https://gateway.example/arbitrary")).hasMessage("THS_WORKFLOW_GATEWAY_INVALID");
  }
  private MockHttpServletRequest session(){
    var access=new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,"private-human-token",Instant.now().minusSeconds(1),Instant.now().plusSeconds(60));

    var request=new MockHttpServletRequest();request.setAttribute("ouf.permissionThsSession",Boolean.TRUE);
    request.setAttribute(PermissionThsSecurity.REQUEST_ACCESS_TOKEN,access);
    request.setAttribute(CsrfToken.class.getName(),new DefaultCsrfToken("X-CSRF-TOKEN","_csrf","csrf-test"));return request;
  }
}
