package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.onboarding.application.SemanticGapService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/onboarding/v1/sources/{sourceId}/onboarding-versions/{versionId}/semantic-gaps")
public class SemanticGapApi {
  private final SemanticGapService gaps;private final TrustedActorResolver actors;
  public SemanticGapApi(SemanticGapService gaps,TrustedActorResolver actors){this.gaps=gaps;this.actors=actors;}
  public record CreateRequest(@NotBlank String typeCode,String fieldPath,@NotBlank String description){}
  public record SearchRequest(@NotBlank String discoveryRequestRef){}
  public record CandidateRequest(@NotEmpty List<Map<String,Object>> candidates){}
  public record SelectionRequest(@NotNull UUID candidateId){}
  @PostMapping ResponseEntity<Map<String,Object>> create(@PathVariable String sourceId,@PathVariable UUID versionId,@Valid @RequestBody CreateRequest r){return ResponseEntity.status(HttpStatus.CREATED).body(gaps.create(sourceId,versionId,r.typeCode(),r.fieldPath(),r.description()));}
  @PostMapping("/from-access-schema") List<Map<String,Object>> accessProposals(@PathVariable String sourceId,@PathVariable UUID versionId,HttpServletRequest request){it.comune.trieste.ouf.authorization.ServletAuthorization.require(request,"ouf.source-onboarding.semantic-gap.create",false);return gaps.proposeAccessSchema(sourceId,versionId,actors.actor(request));}
  @GetMapping List<Map<String,Object>> list(@PathVariable String sourceId,@PathVariable UUID versionId){return gaps.list(sourceId,versionId);}
  @PostMapping("/{gapId}/search") ResponseEntity<Map<String,Object>> search(@PathVariable String sourceId,@PathVariable UUID versionId,@PathVariable UUID gapId,@Valid @RequestBody SearchRequest r){return ResponseEntity.accepted().body(gaps.requestSearch(sourceId,versionId,gapId,r.discoveryRequestRef()));}
  @PostMapping("/{gapId}/candidates") Map<String,Object> candidates(@PathVariable String sourceId,@PathVariable UUID versionId,@PathVariable UUID gapId,@Valid @RequestBody CandidateRequest r,HttpServletRequest request){return gaps.recordCandidates(sourceId,versionId,gapId,r.candidates(),actors.actor(request));}
  @PostMapping("/{gapId}/select-candidate") Map<String,Object> select(@PathVariable String sourceId,@PathVariable UUID versionId,@PathVariable UUID gapId,@Valid @RequestBody SelectionRequest r){return gaps.select(sourceId,versionId,gapId,r.candidateId());}
}
