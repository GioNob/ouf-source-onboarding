package it.comune.trieste.ouf.onboarding.api;

import static org.assertj.core.api.Assertions.*;
import it.comune.trieste.ouf.onboarding.authorization.StorageRecoveryAuthorityFence.Proof;
import it.comune.trieste.ouf.onboarding.installation.FilePlatformRecoveryAuthority;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class StorageRecoveryHumanApprovalTest {
  StorageRecoveryHumanApproval.Command command(){return new StorageRecoveryHumanApproval.Command(UUID.randomUUID(),UUID.randomUUID(),"a".repeat(64),1,"OWNER_OUTCOME_VERIFIED",new Proof("lab-installation","platform-tenant","owner-policy",1,2,Instant.now().minusSeconds(1),Instant.now().plusSeconds(120),true));}
  @Test void verifiedSignatureBindsServerActorDecisionAndExactCommand()throws Exception{
    var f=new RecoveryHumanIamFixture();var c=command();
    try(var approved=f.approve(c)){
      assertThat(approved.request().actorRef()).isEqualTo("operator");assertThat(approved.request().decisionRef()).isEqualTo("human-recovery:1:installation.storage.authority.reconcile");
      assertThat(approved.lease().current().requestSha256()).isEqualTo(FilePlatformRecoveryAuthority.reconciliationFingerprint(approved.request()));
      assertThat(Duration.between(approved.lease().current().issuedAt(),approved.lease().current().expiresAt())).isLessThanOrEqualTo(Duration.ofSeconds(30));
    }
  }
  @Test void invalidSignatureIssuerAudienceAndExpiredBearerAreDenied()throws Exception{
    var f=new RecoveryHumanIamFixture();var c=command();
    assertThatThrownBy(()->f.approve(c,Map.of(),true,true)).isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
    for(var claims:List.<Map<String,Object>>of(Map.of("iss","https://wrong.invalid"),Map.of("aud",List.of("wrong")),Map.of("exp",Date.from(Instant.now().minusSeconds(120)))))
      assertThatThrownBy(()->f.approve(c,claims,true,false)).isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
  }
  @Test void headersCannotReplaceWriteProofOrHumanAssurance()throws Exception{
    var f=new RecoveryHumanIamFixture();var c=command();
    assertThatThrownBy(()->f.approve(c,Map.of(),false,false)).hasMessageContaining("WRITE_PROOF_REQUIRED");
    for(var claims:List.<Map<String,Object>>of(Map.of("ouf_actor_type","SERVICE","client_id","workload"),Map.of("ouf_actor_type","AI_AGENT"),Map.of("tenant_id","other"),Map.of("scope","profile"),Map.of("amr",List.of("pwd")),Map.of("auth_time",Instant.now().minusSeconds(301).getEpochSecond()),Map.of("auth_time",Instant.now().plusSeconds(30).getEpochSecond())))
      assertThatThrownBy(()->f.approve(c,claims,true,false)).isInstanceOf(SecurityException.class);
  }
  @Test void revocationBeforeSealDeniesAndAfterSealCannotBeAcknowledgedUntilClose()throws Exception{
    var f=new RecoveryHumanIamFixture();var c=command();
    try(var old=f.approve(c)){f.publish(2,false);assertThatThrownBy(old.lease()::current).hasMessageContaining("policy changed");assertThatThrownBy(old.lease()::seal).hasMessageContaining("policy changed");}
    f.publish(3,true);
    try(var held=f.approve(c)){held.lease().seal();assertThatThrownBy(()->f.publish(4,false)).hasMessage("POLICY_PUBLICATION_CONTENDED");assertThat(held.lease().current()).isNotNull();}
    f.publish(4,false);assertThatThrownBy(()->f.approve(c)).hasMessageContaining("policy denied");
  }
  @Test void closeCancelsAndShortBearerDeadlineIsNotExtended()throws Exception{
    var f=new RecoveryHumanIamFixture();var c=command();Instant until=Instant.now().plusSeconds(4).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    var approved=f.approve(c,Map.of("exp",Date.from(until)),true,false);assertThat(approved.lease().current().expiresAt()).isEqualTo(until);
    approved.close();approved.close();assertThatThrownBy(approved.lease()::current).hasMessageContaining("closed");
  }
}
