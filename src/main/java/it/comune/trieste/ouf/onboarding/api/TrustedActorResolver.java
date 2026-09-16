package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.onboarding.application.OnboardingService;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import it.comune.trieste.ouf.authorization.ServletAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class TrustedActorResolver {
  public OnboardingService.Actor actor(HttpServletRequest request){
    var c=context(request);
    // Explicit domain compatibility bridge: persisted Onboarding vocabulary remains HUMAN_USER.
    String type=c.principal().actorType().name();if("HUMAN".equals(type))type="HUMAN_USER";
    return new OnboardingService.Actor(c.principal().subjectId(),type,c.capabilities());
  }
  public String authenticationContextRef(HttpServletRequest request){return context(request).principal().authenticationContextRef();}
  private ServletAuthorization.Context context(HttpServletRequest request){
    try{return ServletAuthorization.resolve(request);}catch(SecurityException e){throw new DomainFailure(HttpStatus.FORBIDDEN,"ONB_AUTHORIZATION_DENIED",e.getMessage());}
  }
}
