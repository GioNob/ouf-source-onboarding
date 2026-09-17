package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.onboarding.application.SemanticGapService;
import it.comune.trieste.ouf.authorization.ServletAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** Flat, bounded Gateway binding; this command creates proposals, never semantic authority. */
@RestController @RequestMapping("/api/onboarding/v1/access-semantic-proposals")
public class AccessSemanticProposalApi {
  private final SemanticGapService service;private final TrustedActorResolver actors;
  public AccessSemanticProposalApi(SemanticGapService service,TrustedActorResolver actors){this.service=service;this.actors=actors;}
  public record Request(@NotBlank String sourceId,@NotNull UUID versionId){}
  @PostMapping List<Map<String,Object>> propose(@Valid @RequestBody Request body,HttpServletRequest request){
    ServletAuthorization.require(request,"ouf.source-onboarding.semantic-gap.create",false);
    return service.proposeAccessSchema(body.sourceId(),body.versionId(),actors.actor(request));
  }
}
