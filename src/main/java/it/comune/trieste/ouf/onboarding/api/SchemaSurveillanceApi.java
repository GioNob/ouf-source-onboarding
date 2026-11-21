package it.comune.trieste.ouf.onboarding.api;

import com.fasterxml.jackson.databind.*;
import it.comune.trieste.ouf.authorization.PrincipalContext;
import it.comune.trieste.ouf.onboarding.application.*;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/onboarding/v1/sources/{sourceId}/schema-surveillance")
public class SchemaSurveillanceApi {
  private final SchemaSurveillanceService service;
  private final TrustedActorResolver actors;
  private final ObjectMapper json;
  public SchemaSurveillanceApi(SchemaSurveillanceService service,TrustedActorResolver actors,ObjectMapper json) {
    this.service=service; this.actors=actors; this.json=json;
  }
  public record ReportRequest(String observedSchemaRef,String previousFingerprint,String observedFingerprint,
      String classification,Map<String,Object> detail) {}
  @PostMapping ResponseEntity<?> report(@PathVariable String sourceId,@RequestBody JsonNode body,HttpServletRequest request) {
    String tenant=principal(request,PrincipalContext.ActorType.SERVICE).tenantId();
    if(!body.isObject()) throw new IllegalArgumentException();
    Map<String,Object> receipt;
    if(body.has("incidentKey") || body.has("state") || body.has("observedSchema")) {
      if(!body.path("incidentKey").isTextual() || !body.path("state").isTextual() || !body.path("observedAt").isTextual()
          || !body.path("observationPolls").isIntegralNumber() || !body.path("observationPolls").canConvertToLong())
        throw new IllegalArgumentException();
      receipt=service.observe(tenant,sourceId,json.convertValue(body,SchemaObservation.class));
    } else {
      var r=json.convertValue(body,ReportRequest.class);
      receipt=service.legacy(tenant,sourceId,r.observedSchemaRef(),r.previousFingerprint(),r.observedFingerprint(),r.classification(),r.detail());
    }
    return ResponseEntity.status(HttpStatus.CREATED).body(receipt);
  }
  @GetMapping List<Map<String,Object>> issues(@PathVariable String sourceId,HttpServletRequest request) {
    return service.issues(principal(request,PrincipalContext.ActorType.SERVICE).tenantId(),sourceId);
  }
  @PostMapping("/{issueId}/resolve") Map<String,Object> resolve(@PathVariable String sourceId,@PathVariable UUID issueId,HttpServletRequest request) {
    return service.resolve(principal(request,PrincipalContext.ActorType.HUMAN).tenantId(),sourceId,issueId);
  }
  private PrincipalContext principal(HttpServletRequest request,PrincipalContext.ActorType type) {
    var p=actors.principal(request);
    if(p.actorType()!=type) throw new DomainFailure(HttpStatus.FORBIDDEN,"ONB_ACTOR_TYPE_DENIED","Invalid actor for schema surveillance");
    return p;
  }
  @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> invalid() {
    return ResponseEntity.unprocessableEntity().body(Map.of("code","ONB_SCHEMA_SURVEILLANCE_INVALID"));
  }
}
