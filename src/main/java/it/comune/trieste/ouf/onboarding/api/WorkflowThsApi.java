package it.comune.trieste.ouf.onboarding.api;

import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
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

/** Browser adapter only: domain decisions still traverse Gateway to their original owner. */
@RestController
@RequestMapping("/trusted-human/{flow:onboarding|semantic}")
@ConditionalOnProperty(name="ouf.workflow.ths.gateway-base-url")
public class WorkflowThsApi {
  private final OAuth2AuthorizedClientService clients;
  private final ObjectMapper json;
  private final URI gateway;
  private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
      .followRedirects(HttpClient.Redirect.NEVER).build();
  public WorkflowThsApi(OAuth2AuthorizedClientService clients,ObjectMapper json,
      @Value("${ouf.workflow.ths.gateway-base-url}") String baseUrl){
    this.clients=clients;this.json=json;
    gateway=URI.create(baseUrl);
    if(gateway.getHost()==null||gateway.getRawUserInfo()!=null||gateway.getRawQuery()!=null||gateway.getRawFragment()!=null
        ||!(gateway.getPath().isEmpty()||"/".equals(gateway.getPath()))
        ||!("https".equals(gateway.getScheme())||"http".equals(gateway.getScheme())&&Set.of("localhost","127.0.0.1").contains(gateway.getHost())))
      throw new IllegalArgumentException("THS_WORKFLOW_GATEWAY_INVALID");
  }
  private URI owner(String flow,UUID id,String suffix){
    String path=switch(flow){case "onboarding"->"/api/trusted-human/v1/approval-challenges/"+id+"/review";
      case "semantic"->"/api/trusted-human/v1/semantic-approval-challenges/"+id;
      default->throw new IllegalArgumentException("THS_WORKFLOW_INVALID");};
    return URI.create(gateway.toString().replaceAll("/+$","")+path+suffix);
  }
  @GetMapping("/") public ResponseEntity<byte[]> page(HttpServletRequest request)throws IOException{token(request);return asset("workflow-review.html",MediaType.TEXT_HTML);}
  @GetMapping("/review.js") public ResponseEntity<byte[]> script(HttpServletRequest request)throws IOException{token(request);return asset("workflow-review.js",MediaType.valueOf("text/javascript"));}
  @GetMapping("/review.css") public ResponseEntity<byte[]> css(HttpServletRequest request)throws IOException{token(request);return asset("workflow-review.css",MediaType.valueOf("text/css"));}
  @GetMapping("/api/{id}") public ResponseEntity<?> card(@PathVariable String flow,@PathVariable UUID id,HttpServletRequest request)throws Exception{
    String access=token(request);var response=send(HttpRequest.newBuilder(owner(flow,id,"")).GET(),access);
    if(response.statusCode()!=200)return upstream(response);
    CsrfToken csrf=(CsrfToken)request.getAttribute(CsrfToken.class.getName());
    if(csrf==null)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"THS_CSRF_REQUIRED");
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("card",json.readTree(response.body()),"csrfHeader",csrf.getHeaderName(),"csrfToken",csrf.getToken()));
  }
  @PostMapping("/api/{id}/{action}") public ResponseEntity<String> action(@PathVariable String flow,@PathVariable UUID id,
      @PathVariable String action,@RequestBody byte[] body,HttpServletRequest request)throws Exception{
    String access=token(request);if(body.length>4096)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
    JsonNode proposed=json.readTree(body);
    if(proposed==null||!proposed.isObject())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"THS_REVIEW_INVALID");
    if("onboarding".equals(flow)){
      if(!Set.of("confirm","reject","activate","resume").contains(action)||proposed.size()!=("resume".equals(action)?4:2)
          ||!proposed.hasNonNull("configurationHash")||!proposed.has("expectedVersion")
          ||!proposed.get("configurationHash").isTextual()||!proposed.get("configurationHash").asText().matches("sha256:[a-f0-9]{64}")
          ||!proposed.get("expectedVersion").isIntegralNumber()||!proposed.get("expectedVersion").canConvertToLong()||proposed.get("expectedVersion").asLong()<0)
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"THS_REVIEW_INVALID");
      if("resume".equals(action)&&(!proposed.has("expectedGeneration")||!proposed.get("expectedGeneration").isIntegralNumber()
          ||!proposed.get("expectedGeneration").canConvertToLong()||proposed.get("expectedGeneration").asLong()<1
          ||!proposed.path("blockedCode").isTextual()||!"ONB_READINESS_DEPENDENCY_403".equals(proposed.get("blockedCode").asText())))
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"THS_REVIEW_INVALID");
    }else{
      if(!"semantic".equals(flow)||!Set.of("decision","publish").contains(action))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"THS_REVIEW_INVALID");
      if("decision".equals(action)){
        if(proposed.size()!=2||!proposed.hasNonNull("expectedContentHash")||!proposed.hasNonNull("decision")
            ||!proposed.get("expectedContentHash").isTextual()||!proposed.get("expectedContentHash").asText().matches("[a-f0-9]{64}")
            ||!proposed.get("decision").isTextual()||!Set.of("APPROVED","REJECTED").contains(proposed.get("decision").asText()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"THS_REVIEW_INVALID");
        var current=send(HttpRequest.newBuilder(owner(flow,id,"")).GET(),access);
        if(current.statusCode()!=200)return upstream(current);
        if(!Objects.equals(proposed.get("expectedContentHash").asText(),json.readTree(current.body()).path("target_content_hash").asText()))
          throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,"THS_REVIEW_STALE_CARD");
        body=json.writeValueAsBytes(Map.of("decision",proposed.get("decision").asText()));
      }else if(proposed.size()!=2||!proposed.hasNonNull("approvalDecisionId")||!proposed.hasNonNull("contentHash")
          ||!proposed.get("approvalDecisionId").isTextual()||!proposed.get("approvalDecisionId").asText().matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")
          ||!proposed.get("contentHash").isTextual()||!proposed.get("contentHash").asText().matches("[a-f0-9]{64}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"THS_REVIEW_INVALID");
    }
    var builder=HttpRequest.newBuilder(owner(flow,id,"/"+action)).header("Content-Type","application/json")
        .POST(HttpRequest.BodyPublishers.ofByteArray(body));
    if("publish".equals(action))builder.header("Idempotency-Key","ths-semantic-publish:"+id);
    return upstream(send(builder,access));
  }
  private HttpResponse<String> send(HttpRequest.Builder builder,String access)throws Exception{
    var response=http.send(builder.header("Authorization","Bearer "+access).header("Accept","application/json")
        .timeout(Duration.ofSeconds(builder.build().uri().getPath().endsWith("/review/activate")?55:15)).build(),HttpResponse.BodyHandlers.ofString());
    if(response.body().length()>1048576)throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"THS_OWNER_RESPONSE_TOO_LARGE");return response;
  }
  private String token(HttpServletRequest request){
    if(!Boolean.TRUE.equals(request.getAttribute("ouf.permissionThsSession"))
        ||!(SecurityContextHolder.getContext().getAuthentication() instanceof OAuth2AuthenticationToken authentication))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,"THS_SESSION_REQUIRED");
    var client=clients.loadAuthorizedClient(authentication.getAuthorizedClientRegistrationId(),authentication.getName());
    if(client==null||!"ouf-ths".equals(client.getClientRegistration().getRegistrationId()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"THS_SESSION_REQUIRED");
    return client.getAccessToken().getTokenValue();
  }
  private static ResponseEntity<String> upstream(HttpResponse<String> response){
    int status=response.statusCode();if(status<200||status>=500||status>=300&&status<400)
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"THS_WORKFLOW_OWNER_UNAVAILABLE");
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore()).body(response.body());
  }
  private static ResponseEntity<byte[]> asset(String name,MediaType type)throws IOException{
    return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options","nosniff").header("Referrer-Policy","no-referrer").header("X-Frame-Options","DENY")
        .header("Content-Security-Policy","default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
        .body(new ClassPathResource("ths/"+name).getContentAsByteArray());
  }
}
