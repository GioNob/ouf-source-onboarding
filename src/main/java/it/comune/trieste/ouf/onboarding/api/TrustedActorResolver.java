package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.onboarding.application.OnboardingService;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class TrustedActorResolver {
  public OnboardingService.Actor actor(HttpServletRequest request){
    Principal principal=request.getUserPrincipal();
    if(principal==null||principal.getName()==null||principal.getName().isBlank())throw new DomainFailure(HttpStatus.UNAUTHORIZED,"ONB_AUTHENTICATION_REQUIRED","A validated request principal is required");
    String type;
    if(request.isUserInRole("OUF_HUMAN_USER"))type="HUMAN_USER";
    else if(request.isUserInRole("OUF_AI_AGENT"))type="AI_AGENT";
    else if(request.isUserInRole("OUF_SERVICE"))type="SERVICE";
    else throw new DomainFailure(HttpStatus.FORBIDDEN,"ONB_ACTOR_ROLE_REQUIRED","The validated principal has no supported OUF actor role");
    Object authorized=request.getAttribute("ouf.authorizedCapabilities");Set<String> capabilities=new LinkedHashSet<>();if(authorized instanceof Collection<?> values)values.stream().map(String::valueOf).forEach(capabilities::add);
    return new OnboardingService.Actor(principal.getName(),type,capabilities);
  }

  public String authenticationContextRef(HttpServletRequest request){
    Object value=request.getAttribute("ouf.authenticationContextRef");
    if(!(value instanceof String ref)||ref.isBlank())throw new DomainFailure(HttpStatus.FORBIDDEN,"ONB_AUTHENTICATION_CONTEXT_REQUIRED","A trusted authentication context reference is required");
    return ref;
  }
}
