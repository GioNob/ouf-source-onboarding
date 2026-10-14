package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.authorization.ServletAuthorization;
import it.comune.trieste.ouf.onboarding.application.ManagedOnboardingReviewService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** Direct HUMAN owner API. The MCP receipt paths never reach these actions. */
@RestController
@RequestMapping("/api/trusted-human/v1/approval-challenges")
public class ManagedOnboardingHumanApi {
  private final ManagedOnboardingReviewService service;
  private final TrustedActorResolver actors;
  public ManagedOnboardingHumanApi(ManagedOnboardingReviewService service,TrustedActorResolver actors){this.service=service;this.actors=actors;}
  @GetMapping("/{id}/review") public Map<String,Object> card(@PathVariable UUID id,HttpServletRequest request){
    actors.requireHuman(request,"ouf.onboarding.configuration.write");
    return service.humanCard(ServletAuthorization.principal(request).context(),id);
  }
  @PostMapping("/{id}/review/{action:confirm|reject|activate}") public Map<String,Object> action(
      @PathVariable UUID id,@PathVariable String action,@RequestBody ManagedOnboardingReviewService.Decision body,HttpServletRequest request){
    actors.requireHuman(request,"ouf.onboarding.configuration.write");
    return service.humanAction(ServletAuthorization.principal(request).context(),id,action,body,actors.authenticationContextRef(request));
  }
}
