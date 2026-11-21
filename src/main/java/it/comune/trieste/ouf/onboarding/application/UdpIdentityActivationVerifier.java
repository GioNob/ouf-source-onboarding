package it.comune.trieste.ouf.onboarding.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Checks UDP's current coverage, bound to the exact frozen Onboarding configuration. */
@Component
public class UdpIdentityActivationVerifier {
  private final URI gateway;
  private final Path token;
  private final ObjectMapper json;
  private final HttpClient http;
  public UdpIdentityActivationVerifier(ObjectMapper json,
      @Value("${ouf.onboarding.udp-identity.gateway-url:}") String gateway,
      @Value("${ouf.onboarding.udp-identity.token-file:}") String token){
    this.json=json;this.gateway=gateway.isBlank()?null:URI.create(gateway);
    this.token=token.isBlank()?null:Path.of(token);
    this.http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER).build();
  }
  public void requireCurrent(String sourceId,String configurationHash,Map<String,Object> policy){
    if(gateway==null||token==null||!Set.of("http","https").contains(gateway.getScheme())
        ||gateway.getHost()==null||gateway.getUserInfo()!=null||gateway.getQuery()!=null
        ||gateway.getFragment()!=null||!Set.of("","/").contains(gateway.getPath()))throw unavailable();
    try{
      String credential=Files.readString(token).strip();
      if(credential.isEmpty()||credential.length()>16384||credential.chars().anyMatch(Character::isWhitespace))throw unavailable();
      String path="/api/udp/v1/governance/internal/identity/preflight?configurationHash="
          +escape(configurationHash)+"&sourceId="+escape(sourceId);
      var request=HttpRequest.newBuilder(gateway.resolve(path)).timeout(Duration.ofSeconds(5))
          .header("Authorization","Bearer "+credential).GET().build();
      var response=http.send(request,HttpResponse.BodyHandlers.ofByteArray());
      int status=response.statusCode();
      if(Set.of(408,429,502,503,504).contains(status))
        throw new TransientFailure("ONB_READINESS_DEPENDENCY_"+status,
            ManagedActivationCoordinator.retryAfter(response.headers().firstValue("Retry-After").orElse("")));
      if(status!=200||response.body().length>65536)throw unavailable();
      @SuppressWarnings("unchecked") Map<String,Object> attestation=json.readValue(response.body(),Map.class);
      if(!Boolean.TRUE.equals(attestation.get("valid"))
          ||!Objects.equals(configurationHash,attestation.get("configurationHash"))
          ||!Objects.equals(sourceId,attestation.get("sourceId"))
          ||!Objects.equals(policy.get("tenantId"),attestation.get("tenantId"))
          ||!Objects.equals(policy.get("canonicalClass"),attestation.get("canonicalClass"))
          ||!Objects.equals(policy.get("ref"),attestation.get("policyRef"))
          ||!Objects.equals(policy.get("version"),attestation.get("policyVersion"))
          ||!(attestation.get("coverageRef") instanceof String ref)
          ||!ref.startsWith("coverage://"))throw unavailable();
    }catch(InterruptedException failure){
      Thread.currentThread().interrupt();
      throw new TransientFailure("ONB_READINESS_INTERRUPTED",0);
    }catch(HttpTimeoutException|ConnectException failure){
      for(Throwable cause=failure.getCause();cause!=null;cause=cause.getCause())
        if(cause instanceof java.nio.channels.UnresolvedAddressException||cause instanceof UnknownHostException
            ||cause instanceof javax.net.ssl.SSLException)throw unavailable();
      throw new TransientFailure("ONB_READINESS_DEPENDENCY_UNAVAILABLE",0);
    }
    catch(java.io.IOException|IllegalArgumentException failure){throw unavailable();}
  }
  static final class TransientFailure extends DomainFailure {
    final int retryAfterSeconds;
    TransientFailure(String code,int retryAfterSeconds){
      super(HttpStatus.SERVICE_UNAVAILABLE,code,"UDP identity verification temporarily unavailable");
      this.retryAfterSeconds=retryAfterSeconds;
    }
  }
  private static String escape(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
  private static DomainFailure unavailable(){return new DomainFailure(HttpStatus.CONFLICT,
      "ONB_UDP_IDENTITY_EVIDENCE_UNPROVEN","UDP identity coverage for the frozen configuration is unavailable or stale");}
}
