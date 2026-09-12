package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.onboarding.application.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/trusted-human/v1/managed-file-quarantine")
public class ManagedFileQuarantineTrustedHumanApi {
  private final ManagedFileService files;private final TrustedActorResolver actors;
  public ManagedFileQuarantineTrustedHumanApi(ManagedFileService files,TrustedActorResolver actors){this.files=files;this.actors=actors;}
  @GetMapping Map<String,Object> list(@RequestParam(defaultValue="OPEN") String state,HttpServletRequest request){var a=actors.actor(request);require(a,"ouf.onboarding.quarantine.read");return Map.of("items",files.quarantines(state));}
  @GetMapping("/{id}") Map<String,Object> get(@PathVariable UUID id,HttpServletRequest request){var a=actors.actor(request);require(a,"ouf.onboarding.quarantine.read");return files.quarantine(id);}
  @PostMapping("/{id}/release") Map<String,Object> release(@PathVariable UUID id,HttpServletRequest request,@RequestHeader HttpHeaders h){return files.resolveQuarantine(id,"RELEASED",actors.actor(request),actors.authenticationContextRef(request),correlation(h));}
  @PostMapping("/{id}/reject") Map<String,Object> reject(@PathVariable UUID id,HttpServletRequest request,@RequestHeader HttpHeaders h){return files.resolveQuarantine(id,"REJECTED",actors.actor(request),actors.authenticationContextRef(request),correlation(h));}
  private static void require(OnboardingService.Actor a,String c){if(!"HUMAN_USER".equals(a.type())||!a.capabilities().contains(c))throw new it.comune.trieste.ouf.onboarding.domain.DomainFailure(org.springframework.http.HttpStatus.FORBIDDEN,"ONB_QUARANTINE_CAPABILITY_REQUIRED","Authorization did not grant quarantine access");}
  private static String correlation(HttpHeaders h){return Optional.ofNullable(h.getFirst("X-Correlation-ID")).orElseGet(()->UUID.randomUUID().toString());}
}
