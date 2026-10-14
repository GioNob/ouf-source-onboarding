package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.onboarding.application.OnboardingService;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import it.comune.trieste.ouf.authorization.PrincipalContext;
import it.comune.trieste.ouf.authorization.ServletAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class TrustedActorResolver {
  public OnboardingService.Actor actor(HttpServletRequest request){return actor(context(request));}
  public OnboardingService.Actor requireHuman(HttpServletRequest request,String capability){return require(request,capability,PrincipalContext.ActorType.HUMAN);}
  public OnboardingService.Actor requireService(HttpServletRequest request,String capability){return require(request,capability,PrincipalContext.ActorType.SERVICE);}
  public String authenticationContextRef(HttpServletRequest request){return context(request).principal().authenticationContextRef();}
  private OnboardingService.Actor require(HttpServletRequest request,String capability,PrincipalContext.ActorType expected){
    var c=context(request);
    if(c.principal().actorType()!=expected)throw new DomainFailure(HttpStatus.FORBIDDEN,"ONB_ACTOR_TYPE_DENIED","Required actor type "+expected);
    if(!c.capabilities().contains(capability))throw new DomainFailure(HttpStatus.FORBIDDEN,"ONB_CAPABILITY_DENIED","Required capability "+capability);
    return actor(c);
  }
  private OnboardingService.Actor actor(ServletAuthorization.Context c){
    String type=c.principal().actorType().name();if("HUMAN".equals(type))type="HUMAN_USER";
    return new OnboardingService.Actor(c.principal().subjectId(),type,c.capabilities());
  }
  private ServletAuthorization.Context context(HttpServletRequest request){
    try{return ServletAuthorization.resolve(request);}catch(SecurityException e){throw new DomainFailure(HttpStatus.FORBIDDEN,"ONB_AUTHORIZATION_DENIED",e.getMessage());}
  }
}
