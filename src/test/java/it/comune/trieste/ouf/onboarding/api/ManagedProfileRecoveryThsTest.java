package it.comune.trieste.ouf.onboarding.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.csrf.DefaultCsrfToken;

class ManagedProfileRecoveryThsTest {
  @Test void ownershipUsesSeparateExactNativePathAndRequiresExplicitConfirmationWithoutTenantInput()throws Exception{
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var gets=new AtomicInteger();var posts=new AtomicInteger();UUID asset=UUID.randomUUID(),job=UUID.randomUUID();
    String expected="/api/onboarding/v1/managed-files/"+asset+"/profile-jobs/"+job+"/recovery/ownership";
    server.createContext("/",exchange->{assertThat(exchange.getRequestURI().getPath()).isEqualTo(expected);assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer fixture-private-token");
      if(exchange.getRequestMethod().equals("GET"))gets.incrementAndGet();else posts.incrementAndGet();
      byte[] body="{\"status\":\"OWNER_BINDING_REVIEW_REQUIRED\"}".getBytes();exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
    try{var api=new ManagedProfileRecoveryThs(session(),new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort());var r=request();
      assertThat(api.ownership(asset,job,r).getBody().toString()).contains("fixture-csrf").doesNotContain("fixture-private-token");
      var command=new LinkedHashMap<String,Object>();command.put("expectedVersion",0);command.put("expectedGeneration",0);command.put("inputBindingHash","sha256:"+"a".repeat(64));command.put("reviewHash","sha256:"+"b".repeat(64));command.put("commandKey","one");command.put("reason","Original uploader confirms displayed tenant");command.put("confirmed",false);
      assertThatThrownBy(()->api.action(asset,job,"ownership",new ObjectMapper().writeValueAsBytes(command),r)).hasMessageContaining("REVIEW_INVALID");assertThat(posts).hasValue(0);
      command.put("confirmed",true);command.put("tenantId","caller-selected");assertThatThrownBy(()->api.action(asset,job,"ownership",new ObjectMapper().writeValueAsBytes(command),r)).hasMessageContaining("REVIEW_INVALID");assertThat(posts).hasValue(0);
      command.remove("tenantId");assertThat(api.action(asset,job,"ownership",new ObjectMapper().writeValueAsBytes(command),r).getStatusCode().value()).isEqualTo(200);assertThat(gets).hasValue(1);assertThat(posts).hasValue(1);
    }finally{server.stop(0);}
  }
  @AfterEach void clear(){SecurityContextHolder.clearContext();}
  OAuth2AuthorizedClientService session(){var clients=mock(OAuth2AuthorizedClientService.class);
    var registration=ClientRegistration.withRegistrationId("ouf-ths").clientId("fixture").clientSecret("fixture-only").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).redirectUri("https://ouf.test/login/oauth2/code/ouf-ths")
        .authorizationUri("https://iam.test/authorize").tokenUri("https://iam.test/token").build();
    var user=new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("HUMAN")),Map.of("sub","alice"),"sub");
    SecurityContextHolder.getContext().setAuthentication(new OAuth2AuthenticationToken(user,user.getAuthorities(),"ouf-ths"));
    var client=new OAuth2AuthorizedClient(registration,"alice",new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,"fixture-private-token",Instant.now(),Instant.now().plusSeconds(300)));
    when(clients.loadAuthorizedClient("ouf-ths","alice")).thenReturn(client);return clients;
  }
  MockHttpServletRequest request(){var r=new MockHttpServletRequest();r.setAttribute("ouf.permissionThsSession",true);r.setAttribute(org.springframework.security.web.csrf.CsrfToken.class.getName(),new DefaultCsrfToken("X-CSRF-TOKEN","_csrf","fixture-csrf"));return r;}
  @Test void missingSessionCannotReachOwnerAndGatewayConfigurationCannotContainCredentials(){
    var clients=mock(OAuth2AuthorizedClientService.class);var api=new ManagedProfileRecoveryThs(clients,new ObjectMapper(),"https://ouf.test");
    assertThatThrownBy(()->api.card(UUID.randomUUID(),UUID.randomUUID(),new MockHttpServletRequest())).hasMessageContaining("SESSION_REQUIRED");verifyNoInteractions(clients);
    assertThatThrownBy(()->new ManagedProfileRecoveryThs(clients,new ObjectMapper(),"https://user:secret@ouf.test")).hasMessageContaining("GATEWAY_INVALID");
  }
  @Test void csrfCardAndExactCommandsTraverseOnlyPinnedOwnerPathWithoutExposingBearer()throws Exception{
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var gets=new AtomicInteger();var posts=new AtomicInteger();UUID asset=UUID.randomUUID(),job=UUID.randomUUID();
    String expected="/api/onboarding/v1/managed-files/"+asset+"/profile-jobs/"+job+"/recovery";
    server.createContext("/",exchange->{assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer fixture-private-token");
      if(exchange.getRequestMethod().equals("GET")){gets.incrementAndGet();assertThat(exchange.getRequestURI().getPath()).isEqualTo(expected);}else{posts.incrementAndGet();assertThat(exchange.getRequestURI().getPath()).isEqualTo(expected+"/budget");}
      exchange.getResponseHeaders().set("Content-Type","application/json");byte[] body="{\"status\":\"FAILED\",\"cumulativeAttempts\":5}".getBytes();exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
    try{var api=new ManagedProfileRecoveryThs(session(),new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort());var r=request();
      assertThat(api.card(asset,job,r).getBody().toString()).contains("fixture-csrf").doesNotContain("fixture-private-token");
      byte[] command=new ObjectMapper().writeValueAsBytes(Map.of("expectedVersion",0,"expectedGeneration",0,"inputBindingHash","sha256:"+"a".repeat(64),"reviewHash","sha256:"+"b".repeat(64),"commandKey","one","reason","Dependency repaired and input verified","maximumAttempts",3,"maximumElapsedSeconds",900));
      assertThat(api.action(asset,job,"budget",command,r).getStatusCode().value()).isEqualTo(200);assertThat(gets).hasValue(1);assertThat(posts).hasValue(1);
      assertThatThrownBy(()->api.action(asset,job,"budget","{\"allowAutoApproval\":true}".getBytes(),r)).hasMessageContaining("REVIEW_INVALID");assertThat(posts).hasValue(1);
    }finally{server.stop(0);}
  }
}

