package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.onboarding.application.OnboardingService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/ths/v1/approval-challenges")
public class TrustedHumanApi {
  private final OnboardingService onboarding;private final TrustedActorResolver actors;
  public TrustedHumanApi(OnboardingService onboarding,TrustedActorResolver actors){this.onboarding=onboarding;this.actors=actors;}
  @GetMapping("/{challengeId}") Map<String,Object> card(@PathVariable UUID challengeId,HttpServletRequest request){requireHuman(request);return onboarding.approvalCard(challengeId);}
  @PostMapping("/{challengeId}/confirm") Map<String,Object> confirm(@PathVariable UUID challengeId,HttpServletRequest request,@RequestHeader HttpHeaders headers){var actor=requireHuman(request);Map<String,Object> card=onboarding.approvalCard(challengeId);return onboarding.confirm(String.valueOf(card.get("source_id")),(UUID)card.get("onboarding_version_id"),challengeId,actor,correlation(headers),actors.authenticationContextRef(request));}
  @PostMapping("/{challengeId}/reject") Map<String,Object> reject(@PathVariable UUID challengeId,HttpServletRequest request,@RequestHeader HttpHeaders headers){var actor=requireHuman(request);Map<String,Object> card=onboarding.approvalCard(challengeId);return onboarding.reject(String.valueOf(card.get("source_id")),(UUID)card.get("onboarding_version_id"),challengeId,actor,correlation(headers),actors.authenticationContextRef(request));}
  @PostMapping("/{challengeId}/activate") Map<String,Object> activate(@PathVariable UUID challengeId,HttpServletRequest request,@RequestHeader HttpHeaders headers){var actor=requireHuman(request);Map<String,Object> card=onboarding.approvalCard(challengeId);return onboarding.activate(String.valueOf(card.get("source_id")),(UUID)card.get("onboarding_version_id"),actor,correlation(headers));}
  private OnboardingService.Actor requireHuman(HttpServletRequest request){var actor=actors.actor(request);if(!"HUMAN_USER".equals(actor.type()))throw new it.comune.trieste.ouf.onboarding.domain.DomainFailure(HttpStatus.FORBIDDEN,"THS_HUMAN_USER_REQUIRED","Trusted Human Surface requires HUMAN_USER");return actor;}
  private static String correlation(HttpHeaders headers){return Optional.ofNullable(headers.getFirst("X-Correlation-ID")).filter(x->!x.isBlank()).orElseGet(()->UUID.randomUUID().toString());}
}
