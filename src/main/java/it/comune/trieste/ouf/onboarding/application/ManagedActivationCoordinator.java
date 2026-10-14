package it.comune.trieste.ouf.onboarding.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.authorization.PrincipalContext;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** One bounded, explicitly HUMAN-requested activation attempt; domain owners retain every completed phase. */
@Component
public class ManagedActivationCoordinator {
  private final ManagedOnboardingReviewService reviews;private final ObjectMapper json;private final URI gateway;
  private final java.util.concurrent.Semaphore admission=new java.util.concurrent.Semaphore(2);
  private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
  public ManagedActivationCoordinator(ManagedOnboardingReviewService reviews,ObjectMapper json,
      @Value("${ouf.onboarding.udp-identity.gateway-url:}") String origin){
    this.reviews=reviews;this.json=json;this.gateway=origin.isBlank()?null:URI.create(origin);
    if(gateway!=null&&(!Set.of("http","https").contains(gateway.getScheme())||gateway.getHost()==null||gateway.getRawUserInfo()!=null||gateway.getRawQuery()!=null||gateway.getRawFragment()!=null||!Set.of("","/").contains(gateway.getPath())))throw new IllegalArgumentException("ONB_READINESS_GATEWAY_INVALID");
  }
  public Map<String,Object> activate(PrincipalContext principal,UUID challenge,ManagedOnboardingReviewService.Decision decision,String authentication,String bearer){
    if(gateway==null||bearer==null||!bearer.startsWith("Bearer ")||bearer.length()>16400||bearer.substring(7).isBlank()||bearer.substring(7).chars().anyMatch(Character::isWhitespace))throw new DomainFailure(HttpStatus.SERVICE_UNAVAILABLE,"ONB_READINESS_TRANSPORT_REQUIRED","Activation readiness transport is unavailable");
    var claim=reviews.claimActivation(principal,challenge,decision);if(claim.reconciled())return claim.progress();
    boolean admitted=admission.tryAcquire();
    try{
      if(!admitted)throw new Failure("ONB_READINESS_CAPACITY_PAUSED",true);
      if(!Boolean.TRUE.equals(claim.progress().get("ingestionCompatibilityObserved"))){
        var proof=post("/api/trusted-human/v1/ingestion/onboarding-readiness",Map.of("sourceId",claim.source(),"onboardingVersionId",claim.version(),"configurationHash",claim.hash()),bearer,35);
        if(!"PASS".equals(proof.get("status"))||!Boolean.TRUE.equals(proof.get("attestationSubmitted"))||!claim.source().equals(proof.get("sourceId"))||!claim.version().toString().equals(proof.get("onboardingVersionId"))||!claim.hash().equals(proof.get("configurationHash")))throw new Failure("ONB_READINESS_COMPATIBILITY_UNPROVEN",false);
      }
      reviews.activationPhase(claim,"CHECKING_IDENTITY");
      var identity=post("/api/udp/v1/governance/identity/preflight",Map.of("sourceId",claim.source(),"configurationHash",claim.hash(),"configuration",claim.configuration()),bearer,8);
      if(!Boolean.TRUE.equals(identity.get("valid"))||!claim.source().equals(identity.get("sourceId"))||!claim.hash().equals(identity.get("configurationHash")))throw new Failure("ONB_READINESS_IDENTITY_UNPROVEN",false);
      reviews.activationPhase(claim,"ACTIVATING");
      return reviews.finishActivation(principal,claim,authentication);
    }catch(Failure error){reviews.activationFailure(claim,error.getMessage(),error.transientFailure,error.retryAfterSeconds);throw new DomainFailure(error.transientFailure?HttpStatus.SERVICE_UNAVAILABLE:HttpStatus.CONFLICT,error.getMessage(),"Activation is paused or blocked; reload the same review to reconcile retained progress");}
    catch(DomainFailure error){reviews.activationFailure(claim,error.code(),false);throw error;}
    catch(RuntimeException error){reviews.activationFailure(claim,"ONB_READINESS_OUTCOME_UNCONFIRMED",false);throw error;}
    finally{if(admitted)admission.release();}
  }
  @SuppressWarnings("unchecked") private Map<String,Object> post(String path,Map<String,Object> body,String bearer,int seconds){
    try{
      var request=HttpRequest.newBuilder(gateway.resolve(path)).timeout(Duration.ofSeconds(seconds)).header("Authorization",bearer).header("Content-Type","application/json").header("Accept","application/json").header("X-Correlation-ID",UUID.randomUUID().toString()).POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
      var response=http.send(request,ignored->new BoundedResponse());
      if(response.statusCode()<200||response.statusCode()>=300){
        boolean transientFailure=Set.of(408,429,502,503,504).contains(response.statusCode());
        String code="ONB_READINESS_DEPENDENCY_"+response.statusCode();
        throw new Failure(code,transientFailure,retryAfter(response.headers().firstValue("Retry-After").orElse("")));
      }
      return json.readValue(response.body(),Map.class);
    }catch(InterruptedException error){Thread.currentThread().interrupt();throw new Failure("ONB_READINESS_INTERRUPTED",true);}
    catch(com.fasterxml.jackson.core.JsonProcessingException error){throw new Failure("ONB_READINESS_RESPONSE_INVALID",false);}
    catch(IOException error){for(Throwable cause=error;cause!=null;cause=cause.getCause())if(cause instanceof ResponseLimit)throw new Failure("ONB_READINESS_RESPONSE_TOO_LARGE",false);throw new Failure("ONB_READINESS_DEPENDENCY_UNAVAILABLE",true);}
  }
  /** Limits received bytes before buffering while preserving HttpClient's whole-response deadline. */
  private static final class BoundedResponse implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
    private java.util.concurrent.Flow.Subscription subscription;private long received;private boolean failed;
    public java.util.concurrent.CompletionStage<byte[]> getBody(){return delegate.getBody();}
    public void onSubscribe(java.util.concurrent.Flow.Subscription value){subscription=value;delegate.onSubscribe(value);}
    public void onNext(List<java.nio.ByteBuffer> buffers){
      if(failed)return;
      for(var buffer:buffers){received+=buffer.remaining();if(received>65536){failed=true;subscription.cancel();delegate.onError(new ResponseLimit());return;}}
      delegate.onNext(buffers);
    }
    public void onError(Throwable error){if(!failed){failed=true;delegate.onError(error);}}
    public void onComplete(){if(!failed)delegate.onComplete();}
  }
  private static final class ResponseLimit extends IOException {}
  private static int retryAfter(String value){
    try {long seconds=value.matches("[0-9]{1,9}")?Long.parseLong(value):java.time.Duration.between(java.time.Instant.now(),java.time.ZonedDateTime.parse(value,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toSeconds();return (int)Math.min(86400,Math.max(0,seconds));}catch(RuntimeException invalid){return 0;}
  }
  private static final class Failure extends RuntimeException{final boolean transientFailure;final int retryAfterSeconds;Failure(String code,boolean transientFailure){this(code,transientFailure,0);}Failure(String code,boolean transientFailure,int retryAfterSeconds){super(code);this.transientFailure=transientFailure;this.retryAfterSeconds=retryAfterSeconds;}}
}
