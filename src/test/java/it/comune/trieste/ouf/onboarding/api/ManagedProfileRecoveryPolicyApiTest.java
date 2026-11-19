package it.comune.trieste.ouf.onboarding.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import it.comune.trieste.ouf.authorization.*;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.*;
import it.comune.trieste.ouf.onboarding.application.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ManagedProfileRecoveryPolicyApiTest {
  final String profile="ouf.managed-source.file.profile",release="ouf.onboarding.quarantine.release";
  final UUID asset=UUID.fromString("00000000-0000-4000-8000-000000000001"),job=UUID.fromString("00000000-0000-4000-8000-000000000002");
  ResourceContext resource(UUID j){return new ResourceContext("managed-profile-job",j.toString(),"tenant-a",null,Map.of("module","ONBOARDING","jobRef",j.toString(),"assetRef",asset.toString(),"detailLevel","TENANT_OPERATIONAL"));}
  Grant grant(String cap,UUID selected){var n=Instant.now();return new Grant("one:"+cap,cap,"tenant-a","alice",null,null,n.minusSeconds(60),n.plusSeconds(3600),
    new GrantConstraints("ALLOW",null,"managed-profile-job",selected.toString(),Map.of("module","ONBOARDING","assetRef",asset.toString(),"jobRef",selected.toString()),Set.of(),Set.of("TENANT_OPERATIONAL"),null,Set.of(),null));}
  MockHttpServletRequest request(List<Grant> grants)throws Exception{
    var r=new MockHttpServletRequest("POST","/api/onboarding/v1/managed-files/"+asset+"/profile-jobs/"+job+"/recovery/budget");
    r.setAttribute(ServletAuthorization.TRUSTED_PRINCIPAL,TestAuthorization.principal("alice","HUMAN",Set.of(profile,release)));
    var n=Instant.now();var runtime=new LocalAuthorization(Clock.systemUTC(),Duration.ofHours(1));
    TestAuthorization.install(runtime,new PolicyBundle("profile-access",1,n,List.of(
      new CapabilityDescriptor(profile,"EXECUTE",profile,Set.of(PrincipalContext.ActorType.HUMAN)),
      new CapabilityDescriptor(release,"EXECUTE",release,Set.of(PrincipalContext.ActorType.HUMAN))),grants));
    r.getServletContext().setAttribute(ServletAuthorization.RUNTIME,runtime);TrustedWriteProof.markStatelessBearer(r);return r;
  }
  ManagedFileService files(){
    var f=mock(ManagedFileService.class);when(f.profileRecoveryResource(eq(asset),any(),any())).thenAnswer(i->resource(i.getArgument(1)));
    return f;
  }
  @Test void exactOwnerResourceUsesRealEvaluatorAndBothCapabilitiesBeforeBudget()throws Exception{
    var r=request(List.of(grant(profile,job),grant(release,job)));r.addHeader("X-OUF-Resource-ID","caller-substitution");
    var f=files();var api=new ManagedProfileRecoveryApi(f,new TrustedActorResolver());
    when(f.grantProfileBudget(eq(asset),eq(job),any(),any(),isNull())).thenReturn(Map.of("status","FAILED","cumulativeAttempts",5));
    assertThat(api.budget(asset,job,null,r).getBody()).containsEntry("cumulativeAttempts",5);
    assertThat(r.getAttribute(ServletAuthorization.RESOURCE)).isEqualTo(resource(job));
    verify(f).grantProfileBudget(eq(asset),eq(job),any(),argThat(a->a.capabilities().containsAll(Set.of(profile,release))),isNull());
  }
  @Test void missingRecoveryPermissionCannotGrantBudgetEvenWhenCardPermissionIsValid()throws Exception{
    var r=request(List.of(grant(profile,job)));var f=files();var api=new ManagedProfileRecoveryApi(f,new TrustedActorResolver());
    when(f.profileRecoveryCard(eq(asset),eq(job),any(),any())).thenReturn(Map.of("status","FAILED","recoveryPermissionAvailable",false));
    assertThat(api.card(asset,job,r).getBody()).containsEntry("recoveryPermissionAvailable",false);
    assertThatThrownBy(()->api.budget(asset,job,null,r)).hasMessageContaining(release);
    verify(f,never()).grantProfileBudget(any(),any(),any(),any(),any());
    verify(f,never()).resumeProfile(any(),any(),any(),any(),any());
  }
  @Test void permissionForAnotherJobDoesNotBecomeAControlWildcard()throws Exception{
    var r=request(List.of(grant(profile,UUID.randomUUID()),grant(release,UUID.randomUUID())));var f=files();
    assertThatThrownBy(()->new ManagedProfileRecoveryApi(f,new TrustedActorResolver()).card(asset,job,r)).hasMessageContaining(profile);
    verify(f,never()).profileRecoveryCard(any(),any(),any(),any());
  }
}
