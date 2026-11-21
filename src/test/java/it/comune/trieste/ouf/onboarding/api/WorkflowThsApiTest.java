package it.comune.trieste.ouf.onboarding.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
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
      String card="{\"challenge_id\":\""+id+"\",\"target_content_hash\":\""+hash+"\",\"status\":\"OPEN\"}";
      if(path.get().endsWith("/review"))card="{\"challenge_id\":\""+id+"\",\"configuration_hash\":\"sha256:"+hash+"\",\"lockVersion\":2,"
          +"\"configuration\":{\"semanticReferenceBindings\":[],\"semanticMapping\":{\"targetClasses\":[],\"propertyMappings\":[]}}}";
      byte[] out=card.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,out.length);
      try(var stream=exchange.getResponseBody()){stream.write(out);}
    });server.start();
    try{
      var clients=mock(OAuth2AuthorizedClientService.class);var request=session(clients);
      var json=new ObjectMapper();var api=new WorkflowThsApi(clients,json,"http://127.0.0.1:"+server.getAddress().getPort());
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
      api.action("onboarding",id,"resume",json.writeValueAsBytes(Map.of("configurationHash","sha256:"+hash,"expectedVersion",3,"expectedGeneration",1,"blockedCode","ONB_READINESS_DEPENDENCY_403")),request);
      assertThat(posts).hasValue(4);assertThat(path.get()).endsWith("/approval-challenges/"+id+"/review/resume");
      assertThat(json.readTree(body.get()).path("expectedGeneration").asLong()).isEqualTo(1);
      assertThatThrownBy(()->api.action("onboarding",id,"resume",json.writeValueAsBytes(Map.of("configurationHash","sha256:"+hash,"expectedVersion",3,"expectedGeneration",1,"blockedCode","ONB_READINESS_OUTCOME_UNCONFIRMED")),request)).hasMessageContaining("THS_REVIEW_INVALID");

      var budget=new LinkedHashMap<String,Object>();budget.put("configurationHash","sha256:"+hash);budget.put("expectedVersion",3);
      budget.put("expectedGeneration",4);budget.put("commandKey","fixture-budget-one");budget.put("reason","Dependency recovery verified");
      budget.put("maximumAttempts",3);budget.put("maximumElapsedSeconds",900);
      api.action("onboarding",id,"budget",json.writeValueAsBytes(budget),request);
      assertThat(path.get()).endsWith("/approval-challenges/"+id+"/review/budget");assertThat(posts).hasValue(5);
      assertThat(json.readTree(body.get())).isEqualTo(json.valueToTree(budget));
      budget.put("maximumAttempts",4);
      assertThatThrownBy(()->api.action("onboarding",id,"budget",json.writeValueAsBytes(budget),request)).hasMessageContaining("THS_REVIEW_INVALID");
      assertThat(posts).hasValue(5);
      var feedback=List.of(Map.of("section","FIELD","target","codice","comment","Verificare il tipo"));
      var changes=Map.of("configurationHash","sha256:"+hash,"expectedVersion",2,"reviewFeedback",feedback);
      api.action("onboarding",id,"changes",json.writeValueAsBytes(changes),request);
      assertThat(path.get()).endsWith("/review/changes");assertThat(posts).hasValue(6);
      assertThat(json.readTree(body.get())).isEqualTo(json.valueToTree(changes));
      assertThatThrownBy(()->api.action("onboarding",id,"confirm",json.writeValueAsBytes(changes),request)).hasMessageContaining("THS_REVIEW_INVALID");
      assertThat(posts).hasValue(6);
      request.removeAttribute("ouf.permissionThsSession");
      assertThatThrownBy(()->api.card("semantic",id,request)).hasMessageContaining("THS_SESSION_REQUIRED");
    }finally{SecurityContextHolder.clearContext();server.stop(0);}
  }
  @Test void onboardingReadsPinnedPublishedTermsWithHumanTokenAndRechecksBeforeConfirm()throws Exception{
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    var json=new ObjectMapper();
    var id=UUID.randomUUID();var revision=UUID.randomUUID().toString();var set=UUID.randomUUID().toString();
    String iri="urn:fixture:Place",hash="sha256:"+"c".repeat(64);
    var pin=Map.of("semanticId",iri,"semanticVersion","2","revisionId",revision,"publicationSetId",set);
    var property=Map.of("semanticId","urn:fixture:name","semanticVersion","1","revisionId",UUID.randomUUID().toString(),"publicationSetId",set);
    var config=Map.of("semanticMapping",Map.of("targetClasses",List.of(Map.of("classIri",iri)),
        "propertyMappings",List.of(Map.of("targetPropertyIri",property.get("semanticId")))),
        "semanticReferenceBindings",List.of(pin,pin,property),"semanticReviewTerms",Map.of(iri,Map.of("labelIt","Falso")));
    byte[] card=json.writeValueAsBytes(Map.of("challenge_id",id,"configuration_hash",hash,"lockVersion",3,"configuration",config,
        "verifiedTerms",Map.of(iri,Map.of("labelIt","Falso")),"termVerification","COMPLETE"));
    ObjectNode active=json.valueToTree(pin);
    active.put("status","ACTIVE").put("artifactType","CLASS").put("authority_ref","Comune").put("license","CC BY");
    active.putObject("label").put("it","Luogo");active.putObject("description").put("it","Un luogo pubblico");
    ObjectNode activeProperty=json.valueToTree(property);
    activeProperty.put("status","ACTIVE").put("artifactType","PROPERTY").put("publisher","Registro");
    activeProperty.putObject("label").put("it","Nome");
    var registryBody=new AtomicReference<>(json.writeValueAsBytes(Map.of("terms",List.of(activeProperty,active),"missing",List.of())));
    var status=new AtomicInteger(200);var posts=new AtomicInteger();var reads=new AtomicInteger();
    var query=new AtomicReference<String>();var auth=new AtomicReference<String>();
    String prefix="/api/trusted-human/v1/";
    server.createContext(prefix+"approval-challenges/"+id+"/review",exchange->{
      if(exchange.getRequestMethod().equals("POST"))posts.incrementAndGet();
      exchange.sendResponseHeaders(200,card.length);try(var out=exchange.getResponseBody()){out.write(card);}
    });
    server.createContext(prefix+"semantic-published-terms",exchange->{
      reads.incrementAndGet();query.set(exchange.getRequestURI().getRawQuery());auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
      var bytes=registryBody.get();exchange.sendResponseHeaders(status.get(),bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}
    });
    server.start();
    try{
      var clients=mock(OAuth2AuthorizedClientService.class);var request=session(clients);
      var api=new WorkflowThsApi(clients,json,"http://127.0.0.1:"+server.getAddress().getPort());
      var view=json.valueToTree(api.card("onboarding",id,request).getBody()).path("card");
      assertThat(view.path("termVerification").textValue()).isEqualTo("COMPLETE");
      var verified=view.path("verifiedTerms").path(iri);
      assertThat(verified.path("labelIt").textValue()).isEqualTo("Luogo");
      assertThat(verified.path("descriptionIt").textValue()).isEqualTo("Un luogo pubblico");
      assertThat(verified.path("publisher").textValue()).isEqualTo("Comune");
      assertThat(view.path("verifiedTerms").path(property.get("semanticId")).path("labelIt").textValue()).isEqualTo("Nome");
      assertThat(query.get()).isEqualTo("revisionIds="+revision+","+property.get("revisionId"));
      assertThat(auth.get()).isEqualTo("Bearer private-human-token");
      var confirm=json.writeValueAsBytes(Map.of("configurationHash",hash,"expectedVersion",3));
      api.action("onboarding",id,"confirm",confirm,request);assertThat(posts).hasValue(1);
      status.set(503);
      view=json.valueToTree(api.card("onboarding",id,request).getBody()).path("card");
      assertThat(view.path("termVerification").textValue()).isEqualTo("UNAVAILABLE");
      assertThat(view.path("verifiedTerms").size()).isZero();
      assertThatThrownBy(()->api.action("onboarding",id,"confirm",confirm,request)).hasMessageContaining("THS_REVIEW_TERMS_UNVERIFIED");
      status.set(200);
      registryBody.set(json.writeValueAsBytes(Map.of("terms",List.of(activeProperty),"missing",List.of(revision))));
      view=json.valueToTree(api.card("onboarding",id,request).getBody()).path("card");
      assertThat(view.path("termVerification").textValue()).isEqualTo("INCOMPLETE");
      assertThat(view.path("verifiedTerms").size()).isEqualTo(1);
      assertThat(view.path("verifiedTerms").has(iri)).isFalse();
      for(var bad:List.of(Map.of("terms",List.of(),"missing",List.of(revision)),
          Map.of("terms",List.of(active),"missing",List.of(revision)),
          Map.of("terms",List.of(active.deepCopy().put("revisionId",UUID.randomUUID().toString())),"missing",List.of()),
          Map.of("terms",List.of(active.deepCopy().put("artifactType","PROPERTY")),"missing",List.of()),
          Map.of("terms",List.of(active.deepCopy().put("semanticId","urn:wrong")),"missing",List.of()),
          Map.of("terms",List.of(active.deepCopy().put("status","DRAFT")),"missing",List.of()))){
        registryBody.set(json.writeValueAsBytes(bad));
        view=json.valueToTree(api.card("onboarding",id,request).getBody()).path("card");
        assertThat(view.path("termVerification").textValue()).isEqualTo("INCOMPLETE");
        assertThat(view.path("verifiedTerms").size()).isZero();
        assertThatThrownBy(()->api.action("onboarding",id,"confirm",confirm,request)).hasMessageContaining("THS_REVIEW_TERMS_UNVERIFIED");
      }
      // Publication sets are cumulative: the same immutable revision in a newer set stays verified.
      registryBody.set(json.writeValueAsBytes(Map.of("terms",List.of(activeProperty,active.deepCopy().put("publicationSetId","newer-set")),"missing",List.of())));
      view=json.valueToTree(api.card("onboarding",id,request).getBody()).path("card");
      assertThat(view.path("verifiedTerms").has(iri)).isTrue();
      assertThat(posts).hasValue(1);
      int before=reads.get();
      api.action("onboarding",id,"reject",confirm,request);
      assertThat(posts).hasValue(2);assertThat(reads).hasValue(before);
    }finally{server.stop(0);SecurityContextHolder.clearContext();}
  }
  @Test void gatewayOriginCannotBeSuppliedByTheBrowser(){
    assertThatThrownBy(()->new WorkflowThsApi(mock(OAuth2AuthorizedClientService.class),new ObjectMapper(),"http://elsewhere.example")).hasMessage("THS_WORKFLOW_GATEWAY_INVALID");
    assertThatThrownBy(()->new WorkflowThsApi(mock(OAuth2AuthorizedClientService.class),new ObjectMapper(),"https://gateway.example/arbitrary")).hasMessage("THS_WORKFLOW_GATEWAY_INVALID");
  }
  private MockHttpServletRequest session(OAuth2AuthorizedClientService clients){
    var registration=ClientRegistration.withRegistrationId("ouf-ths").clientId("test").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).redirectUri("http://localhost/callback").authorizationUri("http://localhost/auth").tokenUri("http://localhost/token").build();
    var access=new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,"private-human-token",Instant.now().minusSeconds(1),Instant.now().plusSeconds(60));
    when(clients.loadAuthorizedClient("ouf-ths","test-human")).thenReturn(new OAuth2AuthorizedClient(registration,"test-human",access));
    var principal=new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")),Map.of("sub","test-human"),"sub");
    SecurityContextHolder.getContext().setAuthentication(new OAuth2AuthenticationToken(principal,principal.getAuthorities(),"ouf-ths"));
    var request=new MockHttpServletRequest();request.setAttribute("ouf.permissionThsSession",Boolean.TRUE);
    request.setAttribute(CsrfToken.class.getName(),new DefaultCsrfToken("X-CSRF-TOKEN","_csrf","csrf-test"));return request;
  }
}

