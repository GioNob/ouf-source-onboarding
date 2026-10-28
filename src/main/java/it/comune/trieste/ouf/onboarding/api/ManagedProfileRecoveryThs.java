package it.comune.trieste.ouf.onboarding.api;

import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.*;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Session/CSRF adapter only. Every card and decision traverses the existing governed Gateway. */
@RestController
@RequestMapping("/trusted-human/managed-files/recovery")
@ConditionalOnProperty(name="ouf.workflow.ths.gateway-base-url")
public class ManagedProfileRecoveryThs {
  private final OAuth2AuthorizedClientService clients;private final ObjectMapper json;private final URI gateway;
  private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
  public ManagedProfileRecoveryThs(OAuth2AuthorizedClientService clients,ObjectMapper json,@Value("${ouf.workflow.ths.gateway-base-url}") String base){
    this.clients=clients;this.json=json;gateway=URI.create(base);
    if(gateway.getHost()==null||gateway.getRawUserInfo()!=null||gateway.getRawQuery()!=null||gateway.getRawFragment()!=null||!(gateway.getPath().isEmpty()||"/".equals(gateway.getPath()))
        ||!("https".equals(gateway.getScheme())||"http".equals(gateway.getScheme())&&Set.of("localhost","127.0.0.1").contains(gateway.getHost())))throw new IllegalArgumentException("THS_PROFILE_GATEWAY_INVALID");
  }
  private String token(HttpServletRequest r){
    if(!Boolean.TRUE.equals(r.getAttribute("ouf.permissionThsSession"))||!(SecurityContextHolder.getContext().getAuthentication() instanceof OAuth2AuthenticationToken a))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"THS_SESSION_REQUIRED");
    var c=clients.loadAuthorizedClient(a.getAuthorizedClientRegistrationId(),a.getName());if(c==null||!"ouf-ths".equals(c.getClientRegistration().getRegistrationId()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"THS_SESSION_REQUIRED");return c.getAccessToken().getTokenValue();
  }
  @GetMapping("/") public ResponseEntity<byte[]> page(HttpServletRequest r)throws IOException{token(r);return asset("profile-recovery.html",MediaType.TEXT_HTML);}
  @GetMapping("/recovery.js") public ResponseEntity<byte[]> script(HttpServletRequest r)throws IOException{token(r);return asset("profile-recovery.js",MediaType.valueOf("text/javascript"));}
  @GetMapping("/recovery.css") public ResponseEntity<byte[]> css(HttpServletRequest r)throws IOException{token(r);return asset("profile-recovery.css",MediaType.valueOf("text/css"));}
  @GetMapping("/api/{assetId}/{jobId}") public ResponseEntity<?> card(@PathVariable UUID assetId,@PathVariable UUID jobId,HttpServletRequest r)throws Exception{
    return card(assetId,jobId,"",r);
  }
  @GetMapping("/api/{assetId}/{jobId}/ownership") public ResponseEntity<?> ownership(@PathVariable UUID assetId,@PathVariable UUID jobId,HttpServletRequest r)throws Exception{
    return card(assetId,jobId,"/ownership",r);
  }
  private ResponseEntity<?> card(UUID assetId,UUID jobId,String suffix,HttpServletRequest r)throws Exception{
    var result=send(assetId,jobId,suffix,null,token(r));if(result.getStatusCode().value()!=200)return result;
    var csrf=(CsrfToken)r.getAttribute(CsrfToken.class.getName());if(csrf==null)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"THS_CSRF_REQUIRED");
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("card",json.readTree(result.getBody()),"csrfHeader",csrf.getHeaderName(),"csrfToken",csrf.getToken()));
  }
  @PostMapping("/api/{assetId}/{jobId}/{action:budget|resume|cancel|ownership}") public ResponseEntity<String> action(@PathVariable UUID assetId,@PathVariable UUID jobId,@PathVariable String action,@RequestBody byte[] body,HttpServletRequest r)throws Exception{
    String access=token(r);if(body.length>4096)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"THS_PROFILE_BODY_LIMIT");
    var d=json.readTree(body);if(d==null||!d.isObject())throw invalid();
    Set<String> fields=switch(action){case "budget"->Set.of("expectedVersion","expectedGeneration","inputBindingHash","reviewHash","commandKey","reason","maximumAttempts","maximumElapsedSeconds");case "ownership"->Set.of("expectedVersion","expectedGeneration","inputBindingHash","reviewHash","commandKey","reason","confirmed");case "resume"->Set.of("expectedVersion","expectedGeneration","inputBindingHash","sessionId");case "cancel"->Set.of("expectedVersion","expectedGeneration","inputBindingHash","commandKey","reason");default->throw invalid();};
    var actual=new HashSet<String>();d.fieldNames().forEachRemaining(actual::add);if(!actual.equals(fields))throw invalid();
    for(String n:List.of("expectedVersion","expectedGeneration"))if(!d.path(n).isIntegralNumber()||!d.path(n).canConvertToLong()||d.path(n).asLong()<0||d.path(n).asLong()>9007199254740991L)throw invalid();
    if(!d.path("inputBindingHash").isTextual()||!d.path("inputBindingHash").asText().matches("sha256:[a-f0-9]{64}"))throw invalid();
    if("resume".equals(action)){if(!d.path("sessionId").isTextual()||!d.path("sessionId").asText().matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}"))throw invalid();}
    else if(!d.path("commandKey").isTextual()||!d.path("commandKey").asText().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")||!d.path("reason").isTextual()||d.path("reason").asText().strip().length()<10||d.path("reason").asText().length()>500||d.path("reason").asText().chars().anyMatch(Character::isISOControl))throw invalid();
    if("budget".equals(action)&&(!d.path("reviewHash").isTextual()||!d.path("reviewHash").asText().matches("sha256:[a-f0-9]{64}")||!d.path("maximumAttempts").isIntegralNumber()||!d.path("maximumAttempts").canConvertToInt()||d.path("maximumAttempts").asInt()!=3||!d.path("maximumElapsedSeconds").isIntegralNumber()||!d.path("maximumElapsedSeconds").canConvertToInt()||d.path("maximumElapsedSeconds").asInt()!=900))throw invalid();
    if("ownership".equals(action)&&(!d.path("reviewHash").isTextual()||!d.path("reviewHash").asText().matches("sha256:[a-f0-9]{64}")||!d.path("confirmed").isBoolean()||!d.path("confirmed").asBoolean()))throw invalid();
    return send(assetId,jobId,"/"+action,body,access);
  }
  private ResponseEntity<String> send(UUID asset,UUID job,String suffix,byte[] body,String access){
    URI target=URI.create(gateway.toString().replaceAll("/+$","")+"/api/onboarding/v1/managed-files/"+asset+"/profile-jobs/"+job+"/recovery"+suffix);
    var builder=HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+access).header("Accept","application/json");
    if(body==null)builder.GET();else builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body));
    CompletableFuture<HttpResponse<byte[]>> pending=http.sendAsync(builder.build(),info->new BoundedBody());
    try{var response=pending.get(15,TimeUnit.SECONDS);int code=response.statusCode();
      if(code<200||code>=500||code>=300&&code<400)throw new IOException();
      return ResponseEntity.status(code).contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore()).body(new String(response.body(),java.nio.charset.StandardCharsets.UTF_8));
    }catch(Exception unavailable){pending.cancel(true);if(unavailable instanceof InterruptedException)Thread.currentThread().interrupt();throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"THS_PROFILE_OWNER_UNAVAILABLE");}
  }
  private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]>{
    private final CompletableFuture<byte[]> result=new CompletableFuture<>();private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();private Flow.Subscription subscription;
    @Override public CompletionStage<byte[]> getBody(){return result;}
    @Override public void onSubscribe(Flow.Subscription s){subscription=s;s.request(1);}
    @Override public void onNext(List<ByteBuffer> buffers){
      for(var buffer:buffers){if(buffer.remaining()>65536-bytes.size()){subscription.cancel();result.completeExceptionally(new IOException("THS_PROFILE_RESPONSE_LIMIT"));return;}
        byte[] part=new byte[buffer.remaining()];buffer.get(part);bytes.writeBytes(part);}subscription.request(1);
    }
    @Override public void onError(Throwable error){result.completeExceptionally(new IOException("THS_PROFILE_OWNER_UNAVAILABLE"));}
    @Override public void onComplete(){result.complete(bytes.toByteArray());}
  }

  private static ResponseStatusException invalid(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"THS_PROFILE_REVIEW_INVALID");}
  private ResponseEntity<byte[]> asset(String name,MediaType type)throws IOException{return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noStore())
      .header("X-Content-Type-Options","nosniff").header("Referrer-Policy","no-referrer").header("X-Frame-Options","DENY")
      .header("Content-Security-Policy","default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
      .body(new ClassPathResource("ths/"+name).getContentAsByteArray());}
}

