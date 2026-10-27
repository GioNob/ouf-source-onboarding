package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import it.comune.trieste.ouf.authorization.PrincipalContext;
import it.comune.trieste.ouf.onboarding.application.*;
import it.comune.trieste.ouf.onboarding.domain.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class ManagedProfileBudgetRuntimeTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->System.getenv("OUF_ONB_DB_URL"));r.add("spring.datasource.username",()->System.getenv("OUF_ONB_DB_USER"));r.add("spring.datasource.password",()->System.getenv("OUF_ONB_DB_PASSWORD"));}
  @Autowired ManagedFileService files;@Autowired ManagedProfileJobs jobs;@Autowired JdbcClient db;@Autowired CanonicalHash hashes;@Autowired TransactionTemplate tx;
  final byte[] csv="id,label\nA,Alpha\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
  final PrincipalContext principal=new PrincipalContext("alice","tenant-a",PrincipalContext.ActorType.HUMAN,null,"fixture-auth","https://iam.test","ouf",Set.of());
  final OnboardingService.Actor actor=new OnboardingService.Actor("alice","HUMAN_USER",Set.of("ouf.managed-source.file.profile","ouf.onboarding.quarantine.release"));
  @BeforeEach void clean(){db.sql("truncate ouf_onboarding.source,ouf_onboarding.managed_file_asset restart identity cascade").update();}
  UUID asset(){return(UUID)files.registerDelegatedUpload("tenant-a","alice","file_"+UUID.randomUUID(),"upload:"+UUID.randomUUID(),hashes.ofBytes(csv),csv.length,()->"object://fixture/"+UUID.randomUUID()).get("assetId");}
  UUID failed(UUID a){
    UUID j=UUID.randomUUID();db.sql("insert into ouf_onboarding.file_profile_job(job_id,asset_id,idempotency_key,state,attempts,error_code,last_error,completed_at) values(:j,:a,'retained-key','FAILED',5,'ONB_FILE_PROFILE_FAILED','Gateway object read failed with status 403',clock_timestamp())").param("j",j).param("a",a).update();
    db.sql("insert into ouf_onboarding.managed_file_quarantine(quarantine_id,asset_id,profile_job_id,reason_code,evidence_ref,safe_detail,correlation_id) values(gen_random_uuid(),:a,:j,'ONB_FILE_PROFILE_FAILED','quarantine://fixture','Gateway object read failed with status 403','fixture')").param("a",a).param("j",j).update();
    db.sql("update ouf_onboarding.managed_file_asset set status='QUARANTINED' where asset_id=:a").param("a",a).update();return j;
  }
  Map<String,Object> card(UUID a,UUID j){return files.profileRecoveryCard(a,j,principal,actor);}
  ManagedProfileJobs.BudgetDecision decision(Map<String,Object> c,String key){return new ManagedProfileJobs.BudgetDecision(((Number)c.get("expectedVersion")).longValue(),((Number)c.get("expectedGeneration")).longValue(),(String)c.get("inputBindingHash"),(String)c.get("reviewHash"),key,"Dependency permission repaired; inspect the same immutable input",3,900);}
  Map<String,Object> grant(UUID a,UUID j,String key){return files.grantProfileBudget(a,j,principal,actor,decision(card(a,j),key));}
  ManagedProfileJobs.Resume resumeDecision(Map<String,Object> c){return new ManagedProfileJobs.Resume(((Number)c.get("expectedVersion")).longValue(),((Number)c.get("expectedGeneration")).longValue(),(String)c.get("inputBindingHash"),(UUID)c.get("sessionId"));}
  Map<String,Object> resume(UUID a,UUID j,Map<String,Object> c){return files.resumeProfile(a,j,principal,actor,resumeDecision(c));}
  long generation(Map<String,Object> c){return((Number)c.get("generation")).longValue();}
  Map<String,Object> claim(){return files.claimProfile("same-worker",Duration.ofMinutes(2),Instant.now()).orElseThrow();}
  void due(UUID job){db.sql("update ouf_onboarding.file_profile_job set next_attempt_at=clock_timestamp()-interval '1 second' where job_id=:j").param("j",job).update();}

  @Test void grantRetainsFailedFiveAndQueuesNothingUntilSeparateHumanResume(){
    UUID a=asset(),j=failed(a);var before=files.profileJob(a,j);String original=db.sql("select to_jsonb(j)::text from ouf_onboarding.file_profile_job j where job_id=:j").param("j",j).query(String.class).single();
    var card=card(a,j);assertThat(card).containsEntry("budgetAvailableToGrant",true).containsEntry("canResume",false).containsEntry("cumulativeAttempts",5);
    var budget=grant(a,j,"one");assertThat(budget).containsEntry("status","FAILED").containsEntry("canResume",true).containsEntry("sessionBaseAttempts",5);
    assertThat(files.profileJob(a,j)).containsEntry("state",before.get("state")).containsEntry("attempts",5).containsEntry("last_error",before.get("last_error")).containsEntry("completed_at",before.get("completed_at"));
    assertThat(db.sql("select prior_job::text from ouf_onboarding.managed_profile_budget_session").query(String.class).single()).isEqualTo(original);
    assertThat(files.claimProfile("worker",Duration.ofMinutes(2),Instant.now())).isEmpty();
    assertThat(files.requestProfile(a,"new-chat").get("jobId")).isEqualTo(j);
    var command=resumeDecision(budget);resume(a,j,budget);files.resumeProfile(a,j,principal,actor,command);
    var attempt=claim();assertThat(attempt).containsEntry("attempts",6).containsEntry("job_id",j);
    files.completeProfile(j,"same-worker",generation(attempt),csv,"profile://fixture");
    assertThat(files.profileJob(a,j)).containsEntry("state","SUCCEEDED").containsEntry("attempts",6);
    assertThat(card(a,j)).containsEntry("sessionState","COMPLETED");
    assertThat(db.sql("select count(*) from ouf_onboarding.file_profile_job").query(Long.class).single()).isOne();
    assertThat(db.sql("select count(*) from ouf_onboarding.file_profile").query(Long.class).single()).isOne();
    assertThat(db.sql("select count(*) from ouf_onboarding.source").query(Long.class).single()).isZero();
  }
  @Test void lostGrantResponseReconcilesOriginalSessionAcrossNewServiceInstanceWithoutExtendingExpiry(){
    UUID a=asset(),j=failed(a);var d=decision(card(a,j),"lost-response");var first=files.grantProfileBudget(a,j,principal,actor,d);
    var restarted=new ManagedProfileJobs(db,tx,hashes);var replay=restarted.grant(a,j,"tenant-a","alice",d);
    assertThat(replay).containsEntry("reconciledSessionId",first.get("sessionId")).containsEntry("sessionExpiresAt",first.get("sessionExpiresAt"));
    assertThat(db.sql("select count(*) from ouf_onboarding.managed_profile_budget_session").query(Long.class).single()).isOne();
    assertThatThrownBy(()->files.grantProfileBudget(a,j,principal,actor,decision(card(a,j),"lost-response"))).hasMessageContaining("COMMAND_CONFLICT");
  }
  @Test void concurrentHumanCommandsCreateOnlyOneSession()throws Exception{
    UUID a=asset(),j=failed(a);var c=card(a,j);var one=decision(c,"one");var two=decision(c,"two");var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
    try{var x=pool.submit(()->{gate.await();try{files.grantProfileBudget(a,j,principal,actor,one);return true;}catch(DomainFailure expected){return false;}});
      var y=pool.submit(()->{gate.await();try{files.grantProfileBudget(a,j,principal,actor,two);return true;}catch(DomainFailure expected){return false;}});gate.countDown();
      assertThat(List.of(x.get(10,TimeUnit.SECONDS),y.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
      assertThat(db.sql("select count(*) from ouf_onboarding.managed_profile_budget_session").query(Long.class).single()).isOne();
    }finally{pool.shutdownNow();}
  }
  @Test void threeNewAttemptsExhaustAtCumulativeEightAndAnotherSessionRequiresAnotherHumanDecision(){
    UUID a=asset(),j=failed(a);resume(a,j,grant(a,j,"first-session"));
    for(int n=6;n<=8;n++){var attempt=claim();assertThat(attempt).containsEntry("attempts",n);
      files.failProfile(j,"same-worker",generation(attempt),"ONB_FILE_PROFILE_FAILED","Gateway object read failed with status 503",Duration.ZERO,true);if(n<8)due(j);}
    assertThat(files.profileJob(a,j)).containsEntry("state","FAILED").containsEntry("attempts",8).containsEntry("failure_class","EXHAUSTED");
    assertThat(card(a,j)).containsEntry("sessionState","EXHAUSTED").containsEntry("budgetAvailableToGrant",true);
    assertThat(files.claimProfile("worker",Duration.ofMinutes(2),Instant.now())).isEmpty();
    assertThat(grant(a,j,"second-session")).containsEntry("sessionBaseAttempts",8).containsEntry("status","FAILED");
    assertThat(db.sql("select count(*) from ouf_onboarding.managed_profile_budget_session").query(Long.class).single()).isEqualTo(2);
  }
  @Test void sameWorkerNameCannotCommitAnExpiredGeneration(){
    UUID a=asset();UUID j=(UUID)files.requestProfile(a,"initial").get("job_id");var first=claim();
    db.sql("update ouf_onboarding.file_profile_job set lease_until=clock_timestamp()-interval '1 second' where job_id=:j").param("j",j).update();var second=claim();
    assertThat(generation(second)).isGreaterThan(generation(first));
    assertThatThrownBy(()->files.completeProfile(j,"same-worker",generation(first),csv,"profile://late")).hasMessageContaining("CLAIM_FENCED");
    assertThatThrownBy(()->files.failProfile(j,"same-worker",generation(first),"ONB_FILE_PROFILE_FAILED","late",Duration.ZERO,true)).hasMessageContaining("CLAIM_FENCED");
    assertThat(db.sql("select count(*) from ouf_onboarding.file_profile").query(Long.class).single()).isZero();
    files.completeProfile(j,"same-worker",generation(second),csv,"profile://current");
    assertThat(db.sql("select count(*) from ouf_onboarding.file_profile").query(Long.class).single()).isOne();
  }
  @Test void humanCancelFencesRunningWorkerAndIsIdempotent(){
    UUID a=asset(),j=failed(a);resume(a,j,grant(a,j,"session"));var running=claim();var c=card(a,j);
    var cancel=new ManagedProfileJobs.Cancel(((Number)c.get("expectedVersion")).longValue(),((Number)c.get("expectedGeneration")).longValue(),(String)c.get("inputBindingHash"),"cancel","Operator cancelled this recovery operation");
    files.cancelProfile(a,j,principal,actor,cancel);files.cancelProfile(a,j,principal,actor,cancel);
    assertThatThrownBy(()->files.completeProfile(j,"same-worker",generation(running),csv,"profile://late")).hasMessageContaining("CLAIM_FENCED");
    assertThat(card(a,j)).containsEntry("status","CANCELLED").containsEntry("sessionState","CANCELLED").containsEntry("cumulativeAttempts",6);
    assertThat(files.claimProfile("worker",Duration.ofMinutes(2),Instant.now())).isEmpty();
  }
  @Test void transientBackoffHasJitterAndRetryAfterBeyondElapsedBudgetStopsImmediately(){
    UUID a=asset(),j=(UUID)files.requestProfile(a,"initial").get("job_id");var attempt=claim();
    files.failProfile(j,"same-worker",generation(attempt),"ONB_FILE_PROFILE_FAILED","Gateway object read failed with status 503",Duration.ZERO,true);
    double delay=db.sql("select extract(epoch from next_attempt_at-updated_at)::float8 from ouf_onboarding.file_profile_job where job_id=:j").param("j",j).query(Double.class).single();assertThat(delay).isBetween(3.5,6.5);
    due(j);attempt=claim();files.failProfile(j,"same-worker",generation(attempt),"ONB_FILE_PROFILE_FAILED","Gateway object read failed with status 503",Duration.ofHours(2),true);
    assertThat(files.profileJob(a,j)).containsEntry("state","FAILED").containsEntry("failure_class","EXHAUSTED").containsEntry("attempts",2);
    assertThat(files.claimProfile("worker",Duration.ofMinutes(2),Instant.now())).isEmpty();
  }
  @Test void hoursOldQueuedOperationCannotRestartItsElapsedBudget(){
    UUID a=asset(),j=UUID.randomUUID();db.sql("insert into ouf_onboarding.file_profile_job(job_id,asset_id,idempotency_key,created_at,budget_profile) values(:j,:a,'old',clock_timestamp()-interval '25 hours','managed-profile-initial.v1')").param("j",j).param("a",a).update();
    assertThat(files.claimProfile("worker",Duration.ofMinutes(2),Instant.now())).isEmpty();
    assertThat(files.profileJob(a,j)).containsEntry("attempts",0).containsEntry("state","FAILED").containsEntry("failure_class","EXHAUSTED");
    assertThat(files.requestProfile(a,"fresh-key").get("job_id")).isEqualTo(j);
  }
  @Test void unknownOutcomeOrInputDriftCannotBeOverriddenByBudget(){
    UUID a=asset(),j=(UUID)files.requestProfile(a,"initial").get("job_id");var attempt=claim();
    files.failProfile(j,"same-worker",generation(attempt),"ONB_FILE_PROFILE_FAILED","Profile failure requires owner reconciliation",Duration.ZERO,false);
    assertThat(card(a,j)).containsEntry("budgetAvailableToGrant",false);
    assertThatThrownBy(()->grant(a,j,"not-a-reconciliation")).hasMessageContaining("RECOVERY_RECONCILIATION_REQUIRED");
    UUID b=asset(),other=failed(b);var c=grant(b,other,"before-drift");db.sql("update ouf_onboarding.managed_file_asset set content_hash=:hash where asset_id=:a").param("hash","sha256:"+"b".repeat(64)).param("a",b).update();
    assertThatThrownBy(()->resume(b,other,c)).hasMessageContaining("SESSION_MISMATCH");
    assertThatThrownBy(()->resume(b,other,card(b,other))).hasMessageContaining("SESSION_MISMATCH");
  }
  @Test void anotherTenantServiceOrMissingRecoveryCapabilityCannotGrant(){
    UUID a=asset(),j=failed(a);var d=decision(card(a,j),"denied");
    var foreign=new PrincipalContext("alice","tenant-b",PrincipalContext.ActorType.HUMAN,null,"auth","https://iam.test","ouf",Set.of());
    var service=new PrincipalContext("alice","tenant-a",PrincipalContext.ActorType.SERVICE,"fixture-service","auth","https://iam.test","ouf",Set.of());
    assertThatThrownBy(()->files.grantProfileBudget(a,j,foreign,actor,d)).hasMessageContaining("not proven");
    assertThatThrownBy(()->files.grantProfileBudget(a,j,service,actor,d)).hasMessageContaining("HUMAN");
    assertThatThrownBy(()->files.grantProfileBudget(a,j,principal,new OnboardingService.Actor("alice","HUMAN_USER",Set.of("ouf.managed-source.file.profile")),d)).hasMessageContaining("HUMAN");
    assertThat(db.sql("select count(*) from ouf_onboarding.managed_profile_budget_session").query(Long.class).single()).isZero();
  }
  @Test void oneLiveReadProtectsDependencyAndIndependentJobContinuesAfterBlockedFile(){
    UUID a=asset(),b=asset();UUID j=(UUID)files.requestProfile(a,"one").get("job_id");files.requestProfile(b,"two");var first=claim();
    assertThat(files.claimProfile("other-worker",Duration.ofMinutes(2),Instant.now())).isEmpty();
    files.failProfile(j,"same-worker",generation(first),"ONB_FILE_PROFILE_FAILED","Gateway object read failed with status 403",Duration.ZERO,false);
    assertThat(claim()).containsEntry("asset_id",b);
  }
  @Test void immutableSessionEventsAndCumulativeProgressRejectResetAndPrivateDataIsNotProjected(){
    UUID a=asset(),j=failed(a);grant(a,j,"session");
    assertThatThrownBy(()->db.sql("update ouf_onboarding.managed_profile_budget_session set reason='silently rewritten'").update()).hasMessageContaining("immutable");
    assertThatThrownBy(()->db.sql("delete from ouf_onboarding.managed_profile_event").update()).hasMessageContaining("append only");
    assertThatThrownBy(()->db.sql("update ouf_onboarding.file_profile_job set attempts=0 where job_id=:j").param("j",j).update()).hasMessageContaining("cannot be reset");
    assertThat(card(a,j).toString()).doesNotContain("object://","last_error","prior_job","retained-key","claimed_by");
  }
  void expiredSessionFixture(UUID a,UUID j,boolean activated){
    grant(a,j,"before-outage");UUID expired=UUID.randomUUID();
    db.sql("update ouf_onboarding.managed_profile_budget_session set state='EXHAUSTED' where state='ACTIVE'").update();
    db.sql("""
        insert into ouf_onboarding.managed_profile_budget_session(session_id,job_id,asset_id,tenant_id,subject_id,command_key,
          request_hash,input_binding_hash,decision_ref,reason,profile,base_attempts,prior_job,prior_session_id,prior_quarantine_id,
          issued_job_version,issued_generation,maximum_attempts,maximum_elapsed_seconds,issued_at,expires_at,state,activated_at,resume_request_hash)
        select :id,job_id,asset_id,tenant_id,subject_id,'hours-old',request_hash,input_binding_hash,gen_random_uuid(),reason,profile,
          base_attempts,prior_job,session_id,prior_quarantine_id,issued_job_version,issued_generation,3,900,issued_at-interval '2 hours',
          expires_at-interval '2 hours','ACTIVE',case when :active then issued_at-interval '2 hours' else null end,
          case when :active then request_hash else null end from ouf_onboarding.managed_profile_budget_session where job_id=:j
        """).param("id",expired).param("active",activated).param("j",j).update();
    db.sql("update ouf_onboarding.file_profile_job set recovery_session_id=:s,state=:state,completed_at=null,generation=generation+1,lock_version=lock_version+1 where job_id=:j")
        .param("s",expired).param("state",activated?"READY":"FAILED").param("j",j).update();
    if(activated)db.sql("update ouf_onboarding.managed_file_asset set status='STAGED' where asset_id=:a").param("a",a).update();
  }
  @Test void hoursExpiredSessionStopsBeforeAnotherAttemptAndRetainsCumulativeFive(){
    UUID a=asset(),j=failed(a);expiredSessionFixture(a,j,true);
    assertThat(files.claimProfile("worker",Duration.ofMinutes(2),Instant.now())).isEmpty();
    assertThat(files.profileJob(a,j)).containsEntry("attempts",5).containsEntry("state","FAILED").containsEntry("failure_class","EXHAUSTED");
    assertThat(card(a,j)).containsEntry("sessionState","EXHAUSTED");
  }
  @Test void expiredUnstartedGrantCannotBeResumedAndNextGrantIsAnotherExplicitSession(){
    UUID a=asset(),j=failed(a);expiredSessionFixture(a,j,false);var before=card(a,j);
    assertThat(before).containsEntry("canResume",false).containsEntry("budgetAvailableToGrant",true);
    assertThatThrownBy(()->resume(a,j,before)).hasMessageContaining("RECOVERY_RECONCILIATION_REQUIRED");
    var next=grant(a,j,"after-outage");assertThat(next).containsEntry("status","FAILED").containsEntry("cumulativeAttempts",5).containsEntry("sessionBaseAttempts",5);
    assertThat(db.sql("select count(*) from ouf_onboarding.managed_profile_budget_session").query(Long.class).single()).isEqualTo(3);
  }
  @Test void slowRemoteReadHoldsNoJobTransactionAndHumanCancelFencesItsLateResponse()throws Exception{
    UUID a=asset(),j=(UUID)files.requestProfile(a,"initial").get("job_id");var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
    ManagedFileObjectStore objects=(ref,size)->{entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("fixture deadline");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException();}return csv;};
    try{var worker=new ManagedFileProfileWorker(files,objects,"same-worker",Duration.ofMinutes(2),Duration.ofSeconds(30));var running=pool.submit(worker::runOnce);assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
      var c=card(a,j);var cancel=new ManagedProfileJobs.Cancel(((Number)c.get("expectedVersion")).longValue(),((Number)c.get("expectedGeneration")).longValue(),(String)c.get("inputBindingHash"),"cancel-slow","Cancel while remote read is still waiting");
      files.cancelProfile(a,j,principal,actor,cancel);release.countDown();running.get(5,TimeUnit.SECONDS);
      assertThat(files.profileJob(a,j)).containsEntry("state","CANCELLED").containsEntry("attempts",1);
      assertThat(db.sql("select count(*) from ouf_onboarding.file_profile").query(Long.class).single()).isZero();
    }finally{release.countDown();pool.shutdownNow();}
  }
}
