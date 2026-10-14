package it.comune.trieste.ouf.onboarding.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import it.comune.trieste.ouf.authorization.PrincipalContext;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.net.InetSocketAddress;
import java.util.*;
import org.junit.jupiter.api.*;

class ManagedActivationCoordinatorTest {
  private final ObjectMapper json=new ObjectMapper();
  private final ManagedOnboardingReviewService reviews=mock(ManagedOnboardingReviewService.class);
  private final UUID challenge=UUID.randomUUID(),version=UUID.randomUUID();
  private final String hash="sha256:"+"a".repeat(64);
  private final PrincipalContext principal=new PrincipalContext("test-human","test-tenant",PrincipalContext.ActorType.HUMAN,"test-client","test-acr","https://issuer.test","test-audience",Set.of("ouf.onboarding.configuration.write"),new PrincipalContext.IdentityClaims(Set.of(),"test-acr",Set.of(),null));
  private final ManagedOnboardingReviewService.Decision decision=new ManagedOnboardingReviewService.Decision(hash,3);
  private HttpServer server;private ManagedActivationCoordinator coordinator;
  private final List<String> requests=Collections.synchronizedList(new ArrayList<>());
  private int readinessStatus=200;private boolean wrongHash=false;private boolean invalidJson=false;private boolean oversized=false;
  @BeforeEach void start() throws Exception {
    server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/",exchange->{
      requests.add(exchange.getRequestURI().getPath());
      assertThat(exchange.getRequestMethod()).isEqualTo("POST");
      assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer fixture-human");
      var body=json.readValue(exchange.getRequestBody(),Map.class);
      assertThat(body).containsEntry("sourceId","test-source").containsEntry("configurationHash",hash);
      boolean readiness=exchange.getRequestURI().getPath().endsWith("onboarding-readiness");
      if(readiness)assertThat(body).containsEntry("onboardingVersionId",version.toString());
      else assertThat(body).containsKey("configuration");
      var response=new LinkedHashMap<String,Object>();response.put("sourceId","test-source");response.put("configurationHash",wrongHash?"sha256:"+"b".repeat(64):hash);
      if(readiness){response.put("status","PASS");response.put("attestationSubmitted",true);response.put("onboardingVersionId",version.toString());}
      else response.put("valid",true);
      byte[] bytes=oversized?new byte[65537]:invalidJson?"invalid-json".getBytes():json.writeValueAsBytes(response);
      exchange.getResponseHeaders().set("Retry-After","93");
      exchange.sendResponseHeaders(readiness?readinessStatus:200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    });
    server.start();coordinator=new ManagedActivationCoordinator(reviews,json,"http://127.0.0.1:"+server.getAddress().getPort());
    when(reviews.finishActivation(eq(principal),any(),eq("test-auth"))).thenReturn(Map.of("state","ACTIVE"));
  }
  @AfterEach void stop(){server.stop(0);}
  private ManagedOnboardingReviewService.ActivationClaim claim(boolean compatible,boolean reconciled){
    var claim=new ManagedOnboardingReviewService.ActivationClaim(challenge,version,"test-source",hash,3,1,Map.of("frozen","configuration"),reconciled?Map.of("state","ACTIVE"):Map.of("ingestionCompatibilityObserved",compatible),reconciled);
    when(reviews.claimActivation(principal,challenge,decision)).thenReturn(claim);return claim;
  }
  private Map<String,Object> activate(){return coordinator.activate(principal,challenge,decision,"test-auth","Bearer fixture-human");}
  @Test void explicitActivationUsesActualReadinessThenIdentityBeforeOwnerMutation(){
    var claim=claim(false,false);assertThat(activate()).containsEntry("state","ACTIVE");
    assertThat(requests).containsExactly("/api/trusted-human/v1/ingestion/onboarding-readiness","/api/udp/v1/governance/identity/preflight");
    var order=inOrder(reviews);order.verify(reviews).claimActivation(principal,challenge,decision);order.verify(reviews).activationPhase(claim,"CHECKING_IDENTITY");order.verify(reviews).activationPhase(claim,"ACTIVATING");order.verify(reviews).finishActivation(principal,claim,"test-auth");
  }
  @Test void retainedCompatibilityAvoidsAnotherProbe(){
    claim(true,false);activate();assertThat(requests).containsExactly("/api/udp/v1/governance/identity/preflight");
  }
  @Test void alreadyActiveReconcilesWithoutReplayingDependenciesOrActivation(){
    claim(false,true);assertThat(activate()).containsEntry("state","ACTIVE");assertThat(requests).isEmpty();verify(reviews,never()).finishActivation(any(),any(),anyString());
  }
  @Test void transientDependencyPausesOneAttemptAndHonorsRetryAfter(){
    var claim=claim(false,false);readinessStatus=503;
    assertThatThrownBy(this::activate).isInstanceOf(DomainFailure.class);
    assertThat(requests).hasSize(1);verify(reviews).activationFailure(claim,"ONB_READINESS_DEPENDENCY_503",true,93);verify(reviews,never()).finishActivation(any(),any(),anyString());
  }
  @Test void receivedResponseIsBoundedBeforeBuffering(){
    var claim=claim(false,false);oversized=true;assertThatThrownBy(this::activate).isInstanceOf(DomainFailure.class);verify(reviews).activationFailure(claim,"ONB_READINESS_RESPONSE_TOO_LARGE",false,0);verify(reviews,never()).finishActivation(any(),any(),anyString());
  }
  @Test void hashDriftAndMalformedEvidenceBlockInsteadOfRetrying(){
    var claim=claim(false,false);wrongHash=true;
    assertThatThrownBy(this::activate).isInstanceOf(DomainFailure.class);verify(reviews).activationFailure(claim,"ONB_READINESS_COMPATIBILITY_UNPROVEN",false,0);
    wrongHash=false;invalidJson=true;assertThatThrownBy(this::activate).isInstanceOf(DomainFailure.class);verify(reviews).activationFailure(claim,"ONB_READINESS_RESPONSE_INVALID",false,0);verify(reviews,never()).finishActivation(any(),any(),anyString());
  }
}
