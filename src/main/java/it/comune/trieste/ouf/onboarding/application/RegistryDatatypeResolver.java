package it.comune.trieste.ouf.onboarding.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import static it.comune.trieste.ouf.onboarding.application.DatatypeResolver.unresolved;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import org.springframework.http.HttpStatus;
import java.io.IOException;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

/** Workload-side reader. Browser review credentials belong to the separate HUMAN surface. */
@Component
final class RegistryDatatypeResolver implements DatatypeResolver {
  private final ObjectMapper json;
  private final RestClient client;
  private final URI gateway;
  private final Supplier<String> credential;

  @Autowired RegistryDatatypeResolver(ObjectMapper json,@Value("${ouf.onboarding.semantic.gateway-url:}") String gateway,
      @Value("${ouf.onboarding.semantic.token-file:}") String tokenFile){
    this(json,gateway,transport(),()->{
      if(tokenFile.isBlank())throw unresolved();
      try{return Files.readString(Path.of(tokenFile)).strip();}catch(IOException e){throw unresolved();}
    });
  }
  RegistryDatatypeResolver(ObjectMapper json,String gateway,RestClient.Builder builder,Supplier<String> credential){
    this.json=json;this.gateway=URI.create(gateway);this.client=builder.build();this.credential=credential;
  }
  private static RestClient.Builder transport(){
    var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    var factory=new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(Duration.ofSeconds(5));
    return RestClient.builder().requestFactory(factory);
  }
  static String path(String id,String revision,String set){
    return "/api/semantic/v1/references:resolve?semanticId="+URLEncoder.encode(id,StandardCharsets.UTF_8)
        +"&revisionId="+UUID.fromString(revision)+"&publicationSetId="+UUID.fromString(set);
  }
  @Override @SuppressWarnings("unchecked")
  public Map<String,Object> resolve(String id,String revision,String set){
    try{
      if(gateway.getHost()==null||gateway.getUserInfo()!=null||gateway.getQuery()!=null||gateway.getFragment()!=null
          ||!Set.of("","/").contains(gateway.getPath())||!Set.of("https","http").contains(gateway.getScheme()))throw unresolved();
      String token=credential.get();
      if(token.isEmpty()||token.length()>16384||token.chars().anyMatch(Character::isWhitespace))throw unresolved();
      return client.get().uri(gateway.resolve(path(id,revision,set))).headers(h->h.setBearerAuth(token)).exchange((request,response)->{
        int status=response.getStatusCode().value();
        if(Set.of(408,429,502,503,504).contains(status)){
          int retryAfter=ManagedActivationCoordinator.retryAfter(Objects.toString(response.getHeaders().getFirst("Retry-After"),""));
          throw unavailable(retryAfter);
        }
        if(status!=200)throw unresolved();
        // references:resolve can include the Registry's bounded RDF snapshot in addition to the definition.
        int limit=16*1024*1024;
        if(response.getHeaders().getContentLength()>limit)throw unresolved();
        byte[] body=response.getBody().readNBytes(limit+1);
        if(body.length>limit)throw unresolved();
        try{return json.readValue(body,Map.class);}
        catch(JsonProcessingException malformed){throw unresolved();}
      });
    }catch(ResourceAccessException failure){
      for(Throwable cause=failure;cause!=null;cause=cause.getCause())
        if(cause instanceof ConnectException || cause instanceof SocketTimeoutException
            || cause instanceof java.net.http.HttpTimeoutException)throw unavailable(0);
      throw unresolved();
    }catch(RestClientException|IllegalArgumentException failure){throw unresolved();}
  }

  private static DomainFailure unavailable(int retryAfter){
    return new DomainFailure(HttpStatus.SERVICE_UNAVAILABLE,"ONB_DATATYPE_LOOKUP_UNAVAILABLE",
        "Registry read temporarily unavailable",Map.of("retryAfterSeconds",retryAfter));
  }
}
