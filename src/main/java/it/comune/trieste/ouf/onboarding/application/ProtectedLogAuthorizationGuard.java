package it.comune.trieste.ouf.onboarding.application;

import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ProtectedLogAuthorizationGuard {
  public void require(OnboardingService.Actor actor,String capability,String purpose,String authorizationContextRef){
    if(!"HUMAN_USER".equals(actor.type()))throw denied("Protected log operations require HUMAN_USER");
    if(!actor.capabilities().contains(capability))throw denied("Authorization did not grant "+capability);
    if(purpose==null||purpose.isBlank()||purpose.length()>500)throw new DomainFailure(HttpStatus.UNPROCESSABLE_ENTITY,"THS_LOG_PURPOSE_REQUIRED","A bounded purpose/reason is required");
    if(authorizationContextRef==null||authorizationContextRef.isBlank())throw denied("A trusted Authorization decision context is required");
  }
  private static DomainFailure denied(String message){return new DomainFailure(HttpStatus.FORBIDDEN,"THS_LOG_ACCESS_DENIED",message);}
}
