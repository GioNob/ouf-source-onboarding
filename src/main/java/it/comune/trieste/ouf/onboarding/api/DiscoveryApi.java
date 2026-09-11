package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.onboarding.application.DiscoveryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/onboarding/v1/sources/{sourceId}")
public class DiscoveryApi {
  private final DiscoveryService service; public DiscoveryApi(DiscoveryService service){this.service=service;}
  public record BindingRequest(@NotBlank String protocol,@NotBlank String routeRef,String credentialRef,Map<String,Object> configuration){}
  public record DiscoveryRequest(@NotBlank String bindingId,Map<String,Object> options){}

  @PutMapping("/bindings/{bindingId}") ResponseEntity<Map<String,Object>> binding(@PathVariable String sourceId,@PathVariable String bindingId,@Valid @RequestBody BindingRequest request,@RequestHeader(value=HttpHeaders.IF_MATCH,required=false) String match){var out=service.configureBinding(sourceId,bindingId,request.protocol(),request.routeRef(),request.credentialRef(),request.configuration()==null?Map.of():request.configuration(),lock(match));return ResponseEntity.ok().eTag("W/\"binding:"+out.get("lock_version")+"\"").body(out);}
  @GetMapping("/bindings/{bindingId}") Map<String,Object> binding(@PathVariable String sourceId,@PathVariable String bindingId){return service.binding(sourceId,bindingId);}
  @PostMapping("/discovery-runs") ResponseEntity<Map<String,Object>> start(@PathVariable String sourceId,@Valid @RequestBody DiscoveryRequest request,@RequestHeader("Idempotency-Key") String idempotencyKey){var out=service.start(sourceId,request.bindingId(),idempotencyKey,request.options());return ResponseEntity.created(URI.create("/api/onboarding/v1/sources/"+sourceId+"/discovery-runs/"+out.get("discovery_run_id"))).body(out);}
  @GetMapping("/discovery-runs/{runId}") Map<String,Object> run(@PathVariable String sourceId,@PathVariable UUID runId){Map<String,Object> out=service.run(runId);if(!sourceId.equals(out.get("source_id")))throw new IllegalArgumentException("sourceId/runId mismatch");return out;}
  @GetMapping("/types") List<Map<String,Object>> types(@PathVariable String sourceId){return service.types(sourceId);}
  @GetMapping("/types/{typeCode}/fields") List<Map<String,Object>> fields(@PathVariable String sourceId,@PathVariable String typeCode){return service.fields(sourceId,typeCode);}
  @GetMapping("/types/{typeCode}/states") List<Map<String,Object>> states(@PathVariable String sourceId,@PathVariable String typeCode){return service.states(sourceId,typeCode);}
  private static long lock(String value){if(value==null)return 0;try{return Long.parseLong(value.replace("W/\"binding:","").replace("\"",""));}catch(Exception e){throw new IllegalArgumentException("invalid If-Match");}}
}
