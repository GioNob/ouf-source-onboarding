package it.comune.trieste.ouf.onboarding.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Session adapter for the versioned Ingestion HUMAN API. Ingestion owns recovery decisions. */
@RestController
@RequestMapping(IngestionRunRecoveryThs.PATH)
@ConditionalOnProperty(name="ouf.workflow.ths.gateway-base-url")
public class IngestionRunRecoveryThs {
  static final String PATH = "/trusted-human/ingestion-run-recovery";
  static final long MAX_CONTROL_VERSION = 9007199254740991L; // Exact in the browser and in JSON.
  private final OAuth2AuthorizedClientService clients;
  private final ObjectMapper json;
  private final URI gateway;
  private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
      .followRedirects(HttpClient.Redirect.NEVER).build();

  public IngestionRunRecoveryThs(OAuth2AuthorizedClientService clients,ObjectMapper json,
      @Value("${ouf.workflow.ths.gateway-base-url}") String base){
    this.clients=clients;
    this.json=json;
    gateway=URI.create(base);
    if(gateway.getHost()==null||gateway.getRawUserInfo()!=null||gateway.getRawQuery()!=null||gateway.getRawFragment()!=null
        ||!(gateway.getPath().isEmpty()||"/".equals(gateway.getPath()))
        ||!("https".equals(gateway.getScheme())||"http".equals(gateway.getScheme())&&Set.of("localhost","127.0.0.1").contains(gateway.getHost())))
      throw new IllegalArgumentException("THS_INGESTION_GATEWAY_INVALID");
  }

  @GetMapping("/") public ResponseEntity<byte[]> page(HttpServletRequest request)throws IOException{
    return asset(request,"ingestion-run-recovery.html",MediaType.parseMediaType("text/html;charset=UTF-8"));
  }

  @GetMapping("/recovery.js") public ResponseEntity<byte[]> script(HttpServletRequest request)throws IOException{
    return asset(request,"ingestion-run-recovery.js",MediaType.valueOf("text/javascript"));
  }

  @GetMapping("/recovery.css") public ResponseEntity<byte[]> css(HttpServletRequest request)throws IOException{
    return asset(request,"ingestion-run-recovery.css",MediaType.valueOf("text/css"));
  }

  @GetMapping("/api/{runId}/{version}")
  public ResponseEntity<?> card(@PathVariable UUID runId,@PathVariable long version,HttpServletRequest request){
    String token=WorkflowThsApi.token(request,clients);
    validateVersion(version);
    var owner=send(runId,null,version,token);
    if(!owner.getStatusCode().is2xxSuccessful())return owner;
    var card=owner.getBody();
    long observedVersion=observedVersion(card,runId);
    var csrf=(CsrfToken)request.getAttribute(CsrfToken.class.getName());
    if(csrf==null)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"THS_CSRF_REQUIRED");
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of(
        "card",card,"linkCurrent",observedVersion==version,"csrfHeader",csrf.getHeaderName(),"csrfToken",csrf.getToken()));
  }

  @PostMapping("/api/{runId}/{version}/{action:resume|abort}")
  public ResponseEntity<?> decide(@PathVariable UUID runId,@PathVariable long version,@PathVariable String action,HttpServletRequest request){
    String token=WorkflowThsApi.token(request,clients);
    validateVersion(version);
    var owner=send(runId,null,version,token);
    if(!owner.getStatusCode().is2xxSuccessful())return owner;
    var card=owner.getBody();
    if(observedVersion(card,runId)!=version||!"PAUSED".equals(card.path("state").textValue()))
      return error(HttpStatus.CONFLICT,"THS_INGESTION_STALE_CARD");
    if("resume".equals(action)&&"ABORT_REQUIRED".equals(card.path("lake_next_action").textValue()))
      return error(HttpStatus.CONFLICT,"ING_LAKE_ABORT_REQUIRED");
    return send(runId,action,version,token);
  }

  private static void validateVersion(long version){
    if(version<0||version>MAX_CONTROL_VERSION)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"THS_INGESTION_VERSION_INVALID");
  }

  private static long observedVersion(JsonNode card,UUID runId){
    if(card==null||!runId.toString().equals(card.path("run_id").textValue())||!card.path("control_version").isIntegralNumber()
        ||!card.path("control_version").canConvertToLong()||!card.path("state").isTextual())
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"THS_INGESTION_OWNER_RESPONSE_INVALID");
    long version=card.path("control_version").longValue();
    if(version<0||version>MAX_CONTROL_VERSION)
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"THS_INGESTION_OWNER_RESPONSE_INVALID");
    return version;
  }

  private ResponseEntity<JsonNode> send(UUID runId,String action,long version,String token){
    String path="/api/trusted-human/v1/ingestion/runs/"+runId+(action==null?"":"/"+action);
    var builder=HttpRequest.newBuilder(gateway.resolve(path)).timeout(Duration.ofSeconds(15))
        .header("Authorization","Bearer "+token).header("Accept","application/json");
    if(action==null)builder.GET();
    else builder.header("Content-Type","application/json")
        .POST(HttpRequest.BodyPublishers.ofString("{\"expectedVersion\":"+version+"}"));
    var pending=http.sendAsync(builder.build(),info->new ManagedProfileRecoveryThs.BoundedBody());
    try{
      var owner=pending.get(15,TimeUnit.SECONDS);
      var status=HttpStatus.resolve(owner.statusCode());
      if(status==null||!status.is2xxSuccessful()&&!status.is4xxClientError())
        return error(HttpStatus.BAD_GATEWAY,"THS_INGESTION_OWNER_UNAVAILABLE");
      if(status.is4xxClientError())return error(status,"THS_INGESTION_OWNER_REJECTED");
      var card=json.readTree(owner.body());
      observedVersion(card,runId);
      return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(browserCard(card));
    }catch(InterruptedException interrupted){
      Thread.currentThread().interrupt();
      return error(HttpStatus.BAD_GATEWAY,"THS_INGESTION_OWNER_UNAVAILABLE");
    }catch(TimeoutException|ExecutionException|IOException unavailable){
      return error(HttpStatus.BAD_GATEWAY,"THS_INGESTION_OWNER_UNAVAILABLE");
    }finally{
      if(!pending.isDone())pending.cancel(true);
    }
  }

  private JsonNode browserCard(JsonNode card){
    var projected=json.createObjectNode();
    copyFields(card,projected,"run_id","control_version","source_id","state","failure_code","lake_cause_code","lake_next_action");
    if(card.path("recoveryBudget").isObject())
      copyFields(card.path("recoveryBudget"),projected.putObject("recoveryBudget"),
          "state","max_attempts","remaining_attempts","cumulative_attempts","deadline_at");
    if(card.path("effects").isObject())
      copyFields(card.path("effects"),projected.putObject("effects"),"lakeWritesConfirmed","handoffsAcknowledged","handoffsUnconfirmed");
    return projected;
  }

  private static void copyFields(JsonNode from,ObjectNode to,String... fields){
    for(String field:fields)if(from.path(field).isValueNode())to.set(field,from.get(field));
  }

  private ResponseEntity<JsonNode> error(HttpStatus status,String code){
    return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(json.createObjectNode().put("code",code));
  }

  private ResponseEntity<byte[]> asset(HttpServletRequest request,String name,MediaType type)throws IOException{
    WorkflowThsApi.token(request,clients);
    return WorkflowThsApi.asset(name,type);
  }
}
