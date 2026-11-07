package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.onboarding.application.OnboardingService;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import it.comune.trieste.ouf.authorization.PrincipalContext;
import it.comune.trieste.ouf.authorization.ServletAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class TrustedActorResolver {
  private static final Logger LOG = LoggerFactory.getLogger(TrustedActorResolver.class);
  public OnboardingService.Actor actor(HttpServletRequest request){return actor(context(request));}
  public OnboardingService.Actor requireHuman(HttpServletRequest request,String capability){return require(request,capability,PrincipalContext.ActorType.HUMAN);}
  public OnboardingService.Actor requireService(HttpServletRequest request,String capability){return require(request,capability,PrincipalContext.ActorType.SERVICE);}
  public String authenticationContextRef(HttpServletRequest request){return context(request).principal().authenticationContextRef();}
  private OnboardingService.Actor require(HttpServletRequest request,String capability,PrincipalContext.ActorType expected){
    var c=context(request);
    if(c.principal().actorType()!=expected)throw new DomainFailure(HttpStatus.FORBIDDEN,"ONB_ACTOR_TYPE_DENIED","Required actor type "+expected);
    if (!c.capabilities().contains(capability)) {
      if ("ouf.managed-source.file.upload".equals(capability)
          && "/trusted-human/managed-files/".equals(request.getRequestURI())) {
        var decision = c.decisions().get(capability);
        LOG.atWarn()
            .addKeyValue("event", "THS_UPLOAD_CAPABILITY_DENIED")
            .addKeyValue("correlationId", CorrelationFilter.get(request))
            .addKeyValue("decisionCode", decision == null ? "CAPABILITY_NOT_EVALUATED" : decision.decisionCode())
            .addKeyValue("policyVersion", decision == null ? null : decision.bundleVersion())
            .log("THS upload capability denied");
      }
      throw new DomainFailure(HttpStatus.FORBIDDEN, "ONB_CAPABILITY_DENIED", "Required capability " + capability);
    }
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
