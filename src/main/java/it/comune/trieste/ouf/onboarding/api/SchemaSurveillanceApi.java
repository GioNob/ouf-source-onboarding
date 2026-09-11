package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.onboarding.application.SchemaSurveillanceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/onboarding/v1/sources/{sourceId}/schema-surveillance")
public class SchemaSurveillanceApi {
  private final SchemaSurveillanceService service;private final TrustedActorResolver actors;
  public SchemaSurveillanceApi(SchemaSurveillanceService service,TrustedActorResolver actors){this.service=service;this.actors=actors;}
  public record ReportRequest(@NotBlank String observedSchemaRef,String previousFingerprint,@NotBlank String observedFingerprint,@NotBlank String classification,Map<String,Object> detail){}
  @PostMapping ResponseEntity<Map<String,Object>> report(@PathVariable String sourceId,@Valid @RequestBody ReportRequest body,HttpServletRequest request){actors.actor(request);return ResponseEntity.status(HttpStatus.CREATED).body(service.report(sourceId,body.observedSchemaRef(),body.previousFingerprint(),body.observedFingerprint(),body.classification(),body.detail()));}
  @GetMapping List<Map<String,Object>> issues(@PathVariable String sourceId,HttpServletRequest request){actors.actor(request);return service.issues(sourceId);}
  @PostMapping("/{issueId}/resolve") Map<String,Object> resolve(@PathVariable String sourceId,@PathVariable UUID issueId,HttpServletRequest request){var actor=actors.actor(request);if(!"HUMAN_USER".equals(actor.type()))throw new it.comune.trieste.ouf.onboarding.domain.DomainFailure(HttpStatus.FORBIDDEN,"ONB_HUMAN_RESOLUTION_REQUIRED","Schema issue resolution requires HUMAN_USER");return service.resolve(sourceId,issueId);}
}
