package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import it.comune.trieste.ouf.authorization.*;
import it.comune.trieste.ouf.onboarding.application.*;
import it.comune.trieste.ouf.onboarding.authorization.AuthorizationPolicyRegistry;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real lifecycle/PG transactions; authorization allow/deny is a TEST fixture, never a deployment grant. */
@SpringBootTest(properties="ouf.authorization.ths.public-origin=https://review.example")
class ManagedOnboardingReviewRuntimeTest {
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->required("OUF_ONB_DB_URL"));r.add("spring.datasource.username",()->required("OUF_ONB_DB_USER"));r.add("spring.datasource.password",()->required("OUF_ONB_DB_PASSWORD"));}
  @Autowired ManagedOnboardingReviewService reviews; @Autowired ManagedFileService files;
  @Autowired OnboardingService onboarding; @Autowired CanonicalHash hashes; @Autowired JdbcClient db;
  @MockitoBean(answers=Answers.RETURNS_DEEP_STUBS) AuthorizationPolicyRegistry policy;
  private final OnboardingService.Actor human=new OnboardingService.Actor("test-human","HUMAN_USER");
  private PrincipalContext principal(String tenant,String subject){return new PrincipalContext(subject,tenant,PrincipalContext.ActorType.HUMAN,"test-mcp","test-acr","https://issuer.example","test-audience",Set.of(ManagedOnboardingReviewService.PREPARE,ManagedOnboardingReviewService.READ,"ouf.onboarding.configuration.write"),new PrincipalContext.IdentityClaims(Set.of(),"test-acr",Set.of(),null));}
  @BeforeEach void clean(){db.sql("truncate ouf_onboarding.source,ouf_onboarding.managed_file_asset restart identity cascade").update();when(policy.authorizeActive(any(),any(),anyString(),anyString(),any()).allowed()).thenReturn(true);}
  @Test void preparationRetryAndReadPreserveOneFrozenChallengeAndHumanApprovalRemainsSeparate(){
    UUID version=draft();var p=principal("test-tenant","test-human");
    var command=new ManagedOnboardingReviewService.Prepare("review-test",version,1,"prepare:one",null);
    var prepared=reviews.prepare(p,command,"test-correlation");
    assertThat(prepared).containsEntry("state","IN_REVIEW").containsEntry("humanApprovalRequired",true).containsEntry("ingestionCompatibilityObserved",false);
    assertThat(prepared.get("reviewUrl").toString()).startsWith("https://review.example/trusted-human/onboarding/?challenge=");
    assertThat(prepared).doesNotContainKeys("configuration","credentialRef","stagingRef");
    assertThat(reviews.prepare(p,command,"retry-after-lost-response")).isEqualTo(prepared);
    assertThat(reviews.read(p,"review-test",version)).isEqualTo(prepared);
    assertThat(db.sql("select count(*) from ouf_onboarding.approval_challenge").query(Long.class).single()).isOne();
    assertThat(db.sql("select count(*) from ouf_onboarding.approval_decision").query(Long.class).single()).isZero();
    assertThatThrownBy(()->reviews.prepare(p,new ManagedOnboardingReviewService.Prepare("review-test",version,1,"changed-key",null),"c")).hasMessage("ONB_REVIEW_COMMAND_CONFLICT");
    UUID challenge=(UUID)prepared.get("challengeId");String hash=prepared.get("configurationHash").toString();
    assertThat(reviews.humanCard(p,challenge)).containsKey("configuration");
    assertThatThrownBy(()->reviews.humanAction(p,challenge,"confirm",new ManagedOnboardingReviewService.Decision(hash,1),"test-auth")).hasMessage("ONB_REVIEW_STALE_CARD");
    var approved=reviews.humanAction(p,challenge,"confirm",new ManagedOnboardingReviewService.Decision(hash,2),"test-auth");
    assertThat(approved).containsEntry("state","APPROVED").containsEntry("humanApprovalRequired",false);
    assertThatThrownBy(()->reviews.humanAction(p,challenge,"activate",new ManagedOnboardingReviewService.Decision(hash,3),"test-auth")).hasMessageContaining("Ingestion Runtime");
    assertThat(db.sql("select count(*) from ouf_onboarding.published_configuration").query(Long.class).single()).isZero();
    assertThat(reviews.read(p,"review-test",version)).containsEntry("state","APPROVED");
    when(policy.authorizeActive(any(),any(),anyString(),anyString(),any()).allowed()).thenReturn(false);
    assertThatThrownBy(()->reviews.read(p,"review-test",version)).hasMessage("ONB_REVIEW_AUTHORIZATION_DENIED");
  }
  @Test void assetOwnershipAndTenantCannotBeChangedByAReviewRetry(){
    UUID version=draft();var command=new ManagedOnboardingReviewService.Prepare("review-test",version,1,"prepare:one",null);
    assertThatThrownBy(()->reviews.prepare(principal("test-tenant","other-human"),command,"c")).hasMessageContaining("not owned");
    reviews.prepare(principal("test-tenant","test-human"),command,"c");
    assertThatThrownBy(()->reviews.prepare(principal("other-tenant","test-human"),command,"c")).hasMessage("ONB_REVIEW_COMMAND_CONFLICT");
  }
  @Test void readinessLeaseAndBudgetSurviveRetryAndFenceLateActivation(){
    UUID version=draft();var p=principal("test-tenant","test-human");
    var prepared=reviews.prepare(p,new ManagedOnboardingReviewService.Prepare("review-test",version,1,"prepare:one",null),"c");UUID challenge=(UUID)prepared.get("challengeId");String hash=prepared.get("configurationHash").toString();
    reviews.humanAction(p,challenge,"confirm",new ManagedOnboardingReviewService.Decision(hash,2),"test-auth");
    var decision=new ManagedOnboardingReviewService.Decision(hash,3);var first=reviews.claimActivation(p,challenge,decision);
    assertThatThrownBy(()->reviews.claimActivation(p,challenge,decision)).hasMessage("ONB_READINESS_IN_PROGRESS");
    db.sql("update ouf_onboarding.managed_onboarding_review set readiness_lease_until=clock_timestamp()-interval '1 second' where challenge_id=:c").param("c",challenge).update();
    var second=reviews.claimActivation(p,challenge,decision);assertThat(second.generation()).isGreaterThan(first.generation());
    assertThatThrownBy(()->reviews.finishActivation(p,first,"test-auth")).hasMessage("ONB_READINESS_LEASE_LOST");
    reviews.activationFailure(first,"LATE_FAILURE",false);
    reviews.activationFailure(second,"DEPENDENCY_PAUSED",true,90);
    assertThatThrownBy(()->reviews.claimActivation(p,challenge,decision)).hasMessage("ONB_READINESS_BACKOFF");
    assertThat(db.sql("select readiness_next_at>clock_timestamp()+interval '80 seconds' from ouf_onboarding.managed_onboarding_review where challenge_id=:c").param("c",challenge).query(Boolean.class).single()).isTrue();
    when(policy.authorizeActive(any(),any(),anyString(),anyString(),any()).allowed()).thenReturn(false);
    assertThatThrownBy(()->reviews.claimActivation(p,challenge,decision)).hasMessage("ONB_REVIEW_AUTHORIZATION_DENIED");
    assertThat(db.sql("select readiness_attempts from ouf_onboarding.managed_onboarding_review where challenge_id=:c").param("c",challenge).query(Integer.class).single()).isEqualTo(2);
    when(policy.authorizeActive(any(),any(),anyString(),anyString(),any()).allowed()).thenReturn(true);
    db.sql("update ouf_onboarding.managed_onboarding_review set readiness_started_at=clock_timestamp()-interval '25 hours',readiness_next_at=null where challenge_id=:c").param("c",challenge).update();
    assertThatThrownBy(()->reviews.claimActivation(p,challenge,decision)).hasMessage("ONB_READINESS_BUDGET_EXHAUSTED");
    assertThat(db.sql("select readiness_phase from ouf_onboarding.managed_onboarding_review where challenge_id=:c").param("c",challenge).query(String.class).single()).isEqualTo("EXHAUSTED");
    assertThat(db.sql("select readiness_attempts from ouf_onboarding.managed_onboarding_review where challenge_id=:c").param("c",challenge).query(Integer.class).single()).isEqualTo(2);
    assertThat(db.sql("select count(*) from ouf_onboarding.published_configuration").query(Long.class).single()).isZero();
  }
  @Test void exactCompatibilityAcknowledgmentReconcilesWithoutAnotherEvidenceWrite(){
    UUID version=draft();var p=principal("test-tenant","test-human");var prepared=reviews.prepare(p,new ManagedOnboardingReviewService.Prepare("review-test",version,1,"prepare:one",null),"c");String hash=prepared.get("configurationHash").toString();
    var workload=new OnboardingService.Actor("test-ingestion","SERVICE",Set.of("ouf.ingestion.configuration.attest","ouf.onboarding.configuration.read"));
    assertThat(onboarding.ingestionCompatibility("review-test",version,hash,workload)).containsEntry("status","ABSENT").containsEntry("compatible",false);
    var first=onboarding.attestIngestionCompatibilityBound("review-test",version,hash,true,"Real probe fixture",workload,"c");
    assertThat(onboarding.attestIngestionCompatibilityBound("review-test",version,hash,true,"Ambiguous acknowledgment retry",workload,"c")).isEqualTo(first);
    assertThat(onboarding.ingestionCompatibility("review-test",version,hash,workload)).containsEntry("status","PRESENT").containsEntry("compatible",true);
    assertThatThrownBy(()->onboarding.attestIngestionCompatibilityBound("review-test",version,"sha256:"+"0".repeat(64),true,"drift",workload,"c")).hasMessageContaining("Frozen configuration identity changed");
    assertThat(db.sql("select count(*) from ouf_onboarding.consumer_compatibility_attestation").query(Long.class).single()).isOne();
  }
  private record Exhausted(UUID challenge,UUID version,String hash,PrincipalContext principal){}
  private Exhausted exhausted(){
    UUID version=draft();var p=principal("test-tenant","test-human");
    var prepared=reviews.prepare(p,new ManagedOnboardingReviewService.Prepare("review-test",version,1,"prepare:finite",null),"c");
    UUID challenge=(UUID)prepared.get("challengeId");String hash=prepared.get("configurationHash").toString();
    reviews.humanAction(p,challenge,"confirm",new ManagedOnboardingReviewService.Decision(hash,2),"test-auth");
    db.sql("update ouf_onboarding.managed_onboarding_review set readiness_started_at=clock_timestamp()-interval '25 hours',readiness_attempts=3,readiness_generation=4,readiness_phase='PAUSED',readiness_last_code='ONB_READINESS_DEPENDENCY_503' where challenge_id=:c").param("c",challenge).update();
    return new Exhausted(challenge,version,hash,p);
  }
  private ManagedOnboardingReviewService.BudgetDecision budget(Exhausted e,String key,long generation){
    return new ManagedOnboardingReviewService.BudgetDecision(e.hash(),3,generation,key,"Dependency path recovered and exact resource verified",3,900);
  }
  @SuppressWarnings("unchecked") private Map<String,Object> progress(Map<String,Object> result){return (Map<String,Object>)result.get("activationProgress");}
  @Test void freshHumanBudgetRetainsCumulativeHistoryAndDoesNotActivate(){
    var e=exhausted();var old=db.sql("select readiness_started_at from ouf_onboarding.managed_onboarding_review where challenge_id=:c").param("c",e.challenge()).query().singleRow();
    assertThat(progress(reviews.read(e.principal(),"review-test",e.version()))).containsEntry("budgetExhausted",true).containsEntry("humanBudgetGrantAvailable",true);
    var grant=reviews.grantReadinessBudget(e.principal(),e.challenge(),budget(e,"grant:one",4));
    assertThat(grant).containsEntry("state","APPROVED").containsEntry("ingestionCompatibilityObserved",false);
    assertThat(progress(grant)).containsEntry("cumulativeAttempts",3).containsEntry("sessionAttempts",0L).containsEntry("maximumAttempts",3).containsEntry("maximumElapsedSeconds",900).containsEntry("budgetExhausted",false);
    assertThat(db.sql("select readiness_started_at from ouf_onboarding.managed_onboarding_review where challenge_id=:c").param("c",e.challenge()).query().singleRow()).isEqualTo(old);
    var history=db.sql("select base_attempts,prior_generation,prior_phase,prior_last_code,reason,subject_id,expires_at-issued_at duration from ouf_onboarding.managed_readiness_budget_session").query().singleRow();
    assertThat(history).containsEntry("base_attempts",3).containsEntry("prior_generation",4L).containsEntry("prior_phase","PAUSED").containsEntry("prior_last_code","ONB_READINESS_DEPENDENCY_503").containsEntry("subject_id","test-human");
    assertThat(db.sql("select count(*) from ouf_onboarding.published_configuration").query(Long.class).single()).isZero();
    var next=reviews.claimActivation(e.principal(),e.challenge(),new ManagedOnboardingReviewService.Decision(e.hash(),3));
    assertThat(progress(next.progress())).containsEntry("cumulativeAttempts",4).containsEntry("sessionAttempts",1L);
    assertThat(next.generation()).isEqualTo(6);
  }
  @Test void explicitActivationAfterNewSessionPublishesOnceAndReconcilesARepeatedCompletion(){
    var e=exhausted();reviews.grantReadinessBudget(e.principal(),e.challenge(),budget(e,"grant:activate",4));
    var workload=new OnboardingService.Actor("test-ingestion","SERVICE",Set.of("ouf.ingestion.configuration.attest","ouf.onboarding.configuration.read"));
    onboarding.attestIngestionCompatibilityBound("review-test",e.version(),e.hash(),true,"Exact fixture compatibility",workload,"session-compatibility");
    var decision=new ManagedOnboardingReviewService.Decision(e.hash(),3);
    var claim=reviews.claimActivation(e.principal(),e.challenge(),decision);
    reviews.activationPhase(claim,"CHECKING_IDENTITY");reviews.activationPhase(claim,"ACTIVATING");
    var completed=reviews.finishActivation(e.principal(),claim,"test-auth");
    assertThat(completed).containsEntry("state","ACTIVE").containsKey("publication");
    assertThat(db.sql("select state from ouf_onboarding.managed_readiness_budget_session").query(String.class).single()).isEqualTo("COMPLETED");
    assertThat(db.sql("select count(*) from ouf_onboarding.published_configuration where onboarding_version_id=:v").param("v",e.version()).query(Long.class).single()).isOne();
    assertThat(reviews.claimActivation(e.principal(),e.challenge(),decision).reconciled()).isTrue();
    assertThat(progress(reviews.read(e.principal(),"review-test",e.version()))).containsEntry("cumulativeAttempts",4);
    assertThat(db.sql("select count(*) from ouf_onboarding.published_configuration").query(Long.class).single()).isOne();
  }
  @Test void lostGrantResponseAndConcurrentHumanCommandsCreateOneSession()throws Exception{
    var e=exhausted();var command=budget(e,"grant:concurrent",4);
    try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
      var start=new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.Callable<Map<String,Object>> call=()->{start.await();return reviews.grantReadinessBudget(e.principal(),e.challenge(),command);};
      var one=pool.submit(call);var two=pool.submit(call);start.countDown();
      assertThat(progress(one.get()).get("sessionId")).isEqualTo(progress(two.get()).get("sessionId"));
    }
    assertThat(db.sql("select count(*) from ouf_onboarding.managed_readiness_budget_session").query(Long.class).single()).isOne();
    assertThatThrownBy(()->reviews.grantReadinessBudget(e.principal(),e.challenge(),budget(e,"another-key",4))).hasMessage("ONB_REVIEW_STALE_CARD");
    var changed=new ManagedOnboardingReviewService.BudgetDecision(e.hash(),3,4,"grant:concurrent","A different declared reason",3,900);
    assertThatThrownBy(()->reviews.grantReadinessBudget(e.principal(),e.challenge(),changed)).hasMessage("ONB_READINESS_BUDGET_COMMAND_CONFLICT");
    assertThat(progress(reviews.read(e.principal(),"review-test",e.version())).get("cumulativeAttempts")).isEqualTo(3);
  }
  @Test void newBudgetFencesOldWorkerAndNeverRefundsAnAttempt(){
    var e=exhausted();var old=new ManagedOnboardingReviewService.ActivationClaim(e.challenge(),e.version(),"review-test",e.hash(),3,4,Map.of(),Map.of(),false);
    var grant=reviews.grantReadinessBudget(e.principal(),e.challenge(),budget(e,"grant:fence",4));
    reviews.activationFailure(old,"LATE_FAILURE",false);
    assertThatThrownBy(()->reviews.activationPhase(old,"ACTIVATING")).hasMessage("ONB_READINESS_LEASE_LOST");
    assertThatThrownBy(()->reviews.finishActivation(e.principal(),old,"test-auth")).hasMessage("ONB_READINESS_LEASE_LOST");
    assertThat(progress(reviews.read(e.principal(),"review-test",e.version()))).isEqualTo(progress(grant));
    assertThat(db.sql("select count(*) from ouf_onboarding.published_configuration").query(Long.class).single()).isZero();
  }
  @Test void threeAdditionalAttemptsExhaustSessionAndNextHumanSessionKeepsBothHistories(){
    var e=exhausted();reviews.grantReadinessBudget(e.principal(),e.challenge(),budget(e,"grant:first",4));
    var activation=new ManagedOnboardingReviewService.Decision(e.hash(),3);
    for(int i=0;i<3;i++){
      var claim=reviews.claimActivation(e.principal(),e.challenge(),activation);
      reviews.activationFailure(claim,"ONB_READINESS_DEPENDENCY_503",true);
      db.sql("update ouf_onboarding.managed_onboarding_review set readiness_next_at=null where challenge_id=:c").param("c",e.challenge()).update();
    }
    assertThatThrownBy(()->reviews.claimActivation(e.principal(),e.challenge(),activation)).hasMessage("ONB_READINESS_BUDGET_EXHAUSTED");
    var current=progress(reviews.read(e.principal(),"review-test",e.version()));
    assertThat(current).containsEntry("cumulativeAttempts",6).containsEntry("sessionAttempts",3L);
    long generation=((Number)current.get("readiness_generation")).longValue();
    var next=reviews.grantReadinessBudget(e.principal(),e.challenge(),budget(e,"grant:second",generation));
    assertThat(progress(next)).containsEntry("cumulativeAttempts",6).containsEntry("sessionAttempts",0L);
    assertThat(db.sql("select base_attempts from ouf_onboarding.managed_readiness_budget_session order by issued_at").query(Integer.class).list()).containsExactly(3,6);
    assertThat(db.sql("select count(*) from ouf_onboarding.managed_readiness_budget_session where state='ACTIVE'").query(Long.class).single()).isOne();
    assertThat(progress(reviews.claimActivation(e.principal(),e.challenge(),activation).progress())).containsEntry("cumulativeAttempts",7);
  }
  @Test void cumulativeCountCanPassLegacy128ButCanNeverRegressBelowSessionFloor(){
    var e=exhausted();
    db.sql("update ouf_onboarding.managed_onboarding_review set readiness_attempts=128 where challenge_id=:c").param("c",e.challenge()).update();
    var command=budget(e,"grant:past128",4);reviews.grantReadinessBudget(e.principal(),e.challenge(),command);
    var claim=reviews.claimActivation(e.principal(),e.challenge(),new ManagedOnboardingReviewService.Decision(e.hash(),3));
    assertThat(progress(claim.progress())).containsEntry("cumulativeAttempts",129).containsEntry("sessionAttempts",1L);
    reviews.activationFailure(claim,"ONB_READINESS_DEPENDENCY_503",true);
    db.sql("update ouf_onboarding.managed_onboarding_review set readiness_attempts=127,readiness_next_at=null where challenge_id=:c").param("c",e.challenge()).update();
    assertThatThrownBy(()->reviews.claimActivation(e.principal(),e.challenge(),new ManagedOnboardingReviewService.Decision(e.hash(),3))).hasMessage("ONB_READINESS_COUNTER_LINEAGE_UNPROVEN");
    db.sql("update ouf_onboarding.managed_readiness_budget_session set issued_at=issued_at-interval '1 hour',expires_at=expires_at-interval '1 hour'").update();
    long generation=((Number)progress(reviews.read(e.principal(),"review-test",e.version())).get("readiness_generation")).longValue();
    assertThatThrownBy(()->reviews.grantReadinessBudget(e.principal(),e.challenge(),budget(e,"grant:regressed",generation))).hasMessage("ONB_READINESS_BUDGET_RECONCILIATION_REQUIRED");
    assertThat(db.sql("select count(*) from ouf_onboarding.managed_readiness_budget_session").query(Long.class).single()).isOne();
  }
  @Test void expiredSessionCannotBeExtendedBySameKeyOrRestartedController(){
    var e=exhausted();var key=budget(e,"grant:expired",4);reviews.grantReadinessBudget(e.principal(),e.challenge(),key);
    db.sql("update ouf_onboarding.managed_readiness_budget_session set issued_at=issued_at-interval '1 hour',expires_at=expires_at-interval '1 hour'").update();
    assertThatThrownBy(()->reviews.claimActivation(e.principal(),e.challenge(),new ManagedOnboardingReviewService.Decision(e.hash(),3))).hasMessage("ONB_READINESS_BUDGET_EXHAUSTED");
    var reconciled=reviews.grantReadinessBudget(e.principal(),e.challenge(),key);
    assertThat(progress(reconciled)).containsEntry("budgetExhausted",true).containsEntry("cumulativeAttempts",3);
    assertThat(db.sql("select count(*) from ouf_onboarding.managed_readiness_budget_session").query(Long.class).single()).isOne();
  }
  @Test void renewalNeverOverridesUnknownOutcomeLeaseBackoffRevocationOrHash(){
    var e=exhausted();
    for(String phase:List.of("BLOCKED","ACTIVATING","CHECKING_COMPATIBILITY")){
      db.sql("update ouf_onboarding.managed_onboarding_review set readiness_phase=:p where challenge_id=:c").param("p",phase).param("c",e.challenge()).update();
      assertThatThrownBy(()->reviews.grantReadinessBudget(e.principal(),e.challenge(),budget(e,"grant:blocked",4))).hasMessage("ONB_READINESS_BUDGET_RECONCILIATION_REQUIRED");
    }
    db.sql("update ouf_onboarding.managed_onboarding_review set readiness_phase='PAUSED',readiness_last_code='ONB_READINESS_OUTCOME_UNCONFIRMED' where challenge_id=:c").param("c",e.challenge()).update();
    assertThatThrownBy(()->reviews.grantReadinessBudget(e.principal(),e.challenge(),budget(e,"grant:unknown",4))).hasMessage("ONB_READINESS_BUDGET_RECONCILIATION_REQUIRED");
    db.sql("update ouf_onboarding.managed_onboarding_review set readiness_last_code='ONB_READINESS_DEPENDENCY_503',readiness_lease_until=clock_timestamp()+interval '1 minute' where challenge_id=:c").param("c",e.challenge()).update();
    assertThatThrownBy(()->reviews.grantReadinessBudget(e.principal(),e.challenge(),budget(e,"grant:leased",4))).hasMessage("ONB_READINESS_BUDGET_RECONCILIATION_REQUIRED");
    db.sql("update ouf_onboarding.managed_onboarding_review set readiness_lease_until=null,readiness_next_at=clock_timestamp()+interval '1 hour' where challenge_id=:c").param("c",e.challenge()).update();
    assertThatThrownBy(()->reviews.grantReadinessBudget(e.principal(),e.challenge(),budget(e,"grant:delayed",4))).hasMessage("ONB_READINESS_BUDGET_RECONCILIATION_REQUIRED");
    when(policy.authorizeActive(any(),any(),anyString(),anyString(),any()).allowed()).thenReturn(false);
    assertThatThrownBy(()->reviews.grantReadinessBudget(e.principal(),e.challenge(),budget(e,"grant:revoked",4))).hasMessage("ONB_REVIEW_AUTHORIZATION_DENIED");
    when(policy.authorizeActive(any(),any(),anyString(),anyString(),any()).allowed()).thenReturn(true);
    var wrong=new ManagedOnboardingReviewService.BudgetDecision("sha256:"+"0".repeat(64),3,4,"grant:hash","Do not adopt changed configuration",3,900);
    assertThatThrownBy(()->reviews.grantReadinessBudget(e.principal(),e.challenge(),wrong)).hasMessage("ONB_REVIEW_STALE_CARD");
    assertThat(db.sql("select count(*) from ouf_onboarding.managed_readiness_budget_session").query(Long.class).single()).isZero();
  }
  @SuppressWarnings("unchecked") private UUID draft(){
    byte[] csv="id,name\n1,Test\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    UUID asset=(UUID)files.register(null,"object://fixture/review.csv",hashes.ofBytes(csv),"text/csv",csv.length,"test-human","retention://test").get("asset_id");
    UUID profile=(UUID)files.profile(asset,csv,"object://fixture/review-sample.json").get("profile_id");
    var draft=files.onboard(asset,profile,"review-test","Test CSV","Test owner","urn:test:Object",List.of("test@1"),List.of("id"),human,"c");
    UUID version=(UUID)draft.get("onboarding_version_id");
    var config=(Map<String,Object>)onboarding.version("review-test",version).get("configuration");
    var runtime=(Map<String,Object>)((Map<String,Object>)config.get("extractionProfile")).get("runtime");
    runtime.put("execution",Map.of("acquisitionMode","INTERNAL_MANAGED_CSV","adapterId","managed-csv","adapterRuntimeVersion","1","sourceSchemaRef","test-schema","sourceSchemaId","test-schema","sourceSchemaVersion","1","semanticPublicationSetRef","test-set","adapterProfileRef","test-profile","observationPolicy","CONTENT_HASH"));
    // Declared legacy fixture contract, not the target GOVERNED_IDENTITY profile.
    runtime.put("udp",Map.of("resolution",Map.of("strategyId","EXACT_SOURCE_KEY","strategyVersion","1",
        "policyRef","test-resolution-policy","canonicalType","urn:test:Object",
        "canonicalKeyProperty","urn:test:id","matchProperty","urn:test:id"),"materialization",Map.of("policyRef","test-policy")));
    config.put("semanticReferenceBindings",List.of(Map.of("semanticId","test","semanticVersion","1","revisionId","test-revision","publicationSetId","test-set")));
    onboarding.patchVersion("review-test",version,0,config,human,"test-complete-configuration");return version;
  }
  private static String required(String name){return Objects.requireNonNull(System.getenv(name),name+" required");}
}
