package it.comune.trieste.ouf.onboarding.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import it.comune.trieste.ouf.authorization.*;
import it.comune.trieste.ouf.onboarding.application.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ManagedProfileRecoveryApiTest {
  @Test void bodyAndAuthenticatedPrincipalAloneCannotGrantWithoutStatelessHumanWriteProof(){
    var files=mock(ManagedFileService.class);var actors=mock(TrustedActorResolver.class);var api=new ManagedProfileRecoveryApi(files,actors);
    var request=new MockHttpServletRequest("POST","/api/onboarding/v1/managed-files/a/profile-jobs/j/recovery/budget");
    request.setAttribute(ServletAuthorization.TRUSTED_PRINCIPAL,new TrustedPrincipal(new PrincipalContext("alice","t",PrincipalContext.ActorType.HUMAN,null,"a","https://iam.test","ouf",Set.of())));
    assertThatThrownBy(()->api.budget(UUID.randomUUID(),UUID.randomUUID(),null,request)).isInstanceOf(SecurityException.class).hasMessageContaining("WRITE_PROOF");
    verifyNoInteractions(files,actors);
  }
  @Test void ownerApiRequiresBothCurrentCapabilitiesAndReturnsNoStore(){
    var files=mock(ManagedFileService.class);var actors=mock(TrustedActorResolver.class);var api=new ManagedProfileRecoveryApi(files,actors);
    var p=new PrincipalContext("alice","t",PrincipalContext.ActorType.HUMAN,null,"a","https://iam.test","ouf",Set.of());
    var request=new MockHttpServletRequest("POST","/api/onboarding/v1/managed-files/a/profile-jobs/j/recovery/budget");request.setAttribute(ServletAuthorization.TRUSTED_PRINCIPAL,new TrustedPrincipal(p));TrustedWriteProof.markStatelessBearer(request);
    var actor=new OnboardingService.Actor("alice","HUMAN_USER",Set.of("ouf.managed-source.file.profile","ouf.onboarding.quarantine.release"));
    when(actors.requireHuman(request,"ouf.managed-source.file.profile")).thenReturn(actor);when(actors.requireHuman(request,"ouf.onboarding.quarantine.release")).thenReturn(actor);
    UUID asset=UUID.randomUUID(),job=UUID.randomUUID();var d=new ManagedProfileJobs.BudgetDecision(0,0,"sha256:"+"a".repeat(64),"sha256:"+"b".repeat(64),"key","Dependency repaired",3,900);
    when(files.grantProfileBudget(asset,job,p,actor,d)).thenReturn(Map.of("status","FAILED","cumulativeAttempts",5));
    var result=api.budget(asset,job,d,request);assertThat(result.getHeaders().getCacheControl()).contains("no-store");assertThat(result.getBody()).containsEntry("status","FAILED");
    verify(actors).requireHuman(request,"ouf.onboarding.quarantine.release");verify(files).grantProfileBudget(asset,job,p,actor,d);
  }
}
