package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.authorization.PrincipalContext;
import it.comune.trieste.ouf.onboarding.application.*;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class ManagedFileIntakeTenantRuntimeTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->System.getenv("OUF_ONB_DB_URL"));r.add("spring.datasource.username",()->System.getenv("OUF_ONB_DB_USER"));r.add("spring.datasource.password",()->System.getenv("OUF_ONB_DB_PASSWORD"));}
  @Autowired ManagedFileService files;@Autowired ManagedProfileJobs jobs;@Autowired JdbcClient db;
  @Autowired CanonicalHash hashes;@Autowired ManagedFileProfiler profiler;@Autowired OnboardingService onboarding;@Autowired TransactionTemplate tx;
  final byte[] csv="\uFEFFid;label\r\nA;Alpha\r\n".getBytes(StandardCharsets.UTF_8);
  @BeforeEach void clean(){db.sql("truncate ouf_onboarding.source,ouf_onboarding.managed_file_asset restart identity cascade").update();}
  Map<String,Object> owned(String ref,String tenant){return files.registerOwned(null,ref,hashes.ofBytes(csv),"text/csv",csv.length,"alice","retention://30d",tenant);}
  UUID failed(UUID asset){UUID job=UUID.randomUUID();
    db.sql("insert into ouf_onboarding.file_profile_job(job_id,asset_id,idempotency_key,state,attempts,error_code,last_error,completed_at) values(:j,:a,'same-operation','FAILED',5,'ONB_FILE_PROFILE_FAILED','Gateway object read failed with status 403',clock_timestamp())").param("j",job).param("a",asset).update();
    db.sql("insert into ouf_onboarding.managed_file_quarantine(quarantine_id,asset_id,profile_job_id,reason_code,evidence_ref,safe_detail,correlation_id) values(gen_random_uuid(),:a,:j,'ONB_FILE_PROFILE_FAILED','quarantine://fixture','Gateway object read failed with status 403','fixture')").param("a",asset).param("j",job).update();return job;
  }
  void receipt(UUID asset,String tenant){db.sql("insert into ouf_onboarding.managed_file_upload_attempt(tenant_id,subject_id,file_id,idempotency_key,content_hash,size_bytes,asset_id) values(:t,'alice','file_fixture','fixture',:h,:z,:a)").param("t",tenant).param("h",hashes.ofBytes(csv)).param("z",csv.length).param("a",asset).update();}
  String assetState(UUID asset){return db.sql("select to_jsonb(a)::text from ouf_onboarding.managed_file_asset a where asset_id=:a").param("a",asset).query(String.class).single();}
  @Test void nativeIntakeOwnershipSurvivesNewServiceInstanceWithoutUploadOrReviewReceipt(){
    UUID asset=(UUID)owned("object://fixture/native","tenant-a").get("asset_id");
    var restarted=new ManagedFileService(db,new ObjectMapper(),hashes,profiler,tx,onboarding,jobs);
    restarted.requireTenantOwner(asset,"tenant-a","alice");
    assertThat(restarted.ownedAssets("tenant-a","alice",20,null).get("items")).asList().hasSize(1);
    assertThat(db.sql("select count(*) from ouf_onboarding.managed_file_upload_attempt").query(Long.class).single()).isZero();
    assertThat(db.sql("select count(*) from ouf_onboarding.managed_onboarding_review").query(Long.class).single()).isZero();
  }
  @Test void nativeRetainedFailedFiveCardQueuesNothingAndGrantsNoBudget(){
    UUID asset=(UUID)owned("object://fixture/failed","tenant-a").get("asset_id"),job=failed(asset);
    String before=db.sql("select to_jsonb(j)::text from ouf_onboarding.file_profile_job j where job_id=:j").param("j",job).query(String.class).single();
    var p=new PrincipalContext("alice","tenant-a",PrincipalContext.ActorType.HUMAN,null,"fixture","https://iam.test","ouf",Set.of());
    var actor=new OnboardingService.Actor("alice","HUMAN_USER",Set.of("ouf.managed-source.file.profile","ouf.onboarding.quarantine.release"));
    assertThat(files.profileRecoveryCard(asset,job,p,actor)).containsEntry("status","FAILED").containsEntry("cumulativeAttempts",5).containsEntry("canResume",false);
    assertThat(db.sql("select to_jsonb(j)::text from ouf_onboarding.file_profile_job j where job_id=:j").param("j",job).query(String.class).single()).isEqualTo(before);
    assertThat(db.sql("select count(*) from ouf_onboarding.managed_profile_budget_session").query(Long.class).single()).isZero();
    assertThat(db.sql("select count(*) from ouf_onboarding.managed_profile_event").query(Long.class).single()).isZero();
  }
  @Test void anotherTenantOrSubjectCannotUseNativeBindingEvenWithConflictingLegacyReceipt(){
    UUID asset=(UUID)owned("object://fixture/isolation","tenant-a").get("asset_id");receipt(asset,"tenant-b");
    assertThatThrownBy(()->files.requireTenantOwner(asset,"tenant-b","alice")).hasMessageContaining("owner scope");
    assertThatThrownBy(()->files.requireTenantOwner(asset,"tenant-a","bob")).hasMessageContaining("owner scope");
    assertThat(files.ownedAssets("tenant-b","alice",20,null).get("items")).asList().isEmpty();
    assertThat(files.ownedAssets("tenant-a","bob",20,null).get("items")).asList().isEmpty();
  }
  @Test void legacyUnboundAssetCannotBeClaimedBySameSubjectsCurrentTenant(){
    UUID asset=(UUID)files.register(null,"object://fixture/legacy",hashes.ofBytes(csv),"text/csv",csv.length,"alice","retention://30d").get("asset_id");failed(asset);String before=assetState(asset);
    assertThatThrownBy(()->files.requireTenantOwner(asset,"tenant-a","alice")).hasMessageContaining("owner scope");
    assertThatThrownBy(()->owned("object://fixture/legacy","tenant-a")).hasMessageContaining("reconciliation required");
    assertThat(assetState(asset)).isEqualTo(before);
    assertThat(db.sql("select attempts from ouf_onboarding.file_profile_job where asset_id=:a").param("a",asset).query(Integer.class).single()).isEqualTo(5);
  }
  @Test void existingDurableDelegatedReceiptRemainsValidForLegacyUnboundAsset(){
    UUID asset=(UUID)files.register(null,"object://fixture/receipt",hashes.ofBytes(csv),"text/csv",csv.length,"alice","retention://30d").get("asset_id");receipt(asset,"tenant-a");
    files.requireTenantOwner(asset,"tenant-a","alice");
    assertThat(assetState(asset)).contains("\"tenant_id\": null");
  }
  @Test void ownerTenantCannotBeReboundButOperationalStateMayChange(){
    UUID asset=(UUID)owned("object://fixture/immutable","tenant-a").get("asset_id");
    assertThatThrownBy(()->db.sql("update ouf_onboarding.managed_file_asset set tenant_id='tenant-b' where asset_id=:a").param("a",asset).update()).hasMessageContaining("immutable");
    assertThatThrownBy(()->db.sql("update ouf_onboarding.managed_file_asset set uploaded_by='bob' where asset_id=:a").param("a",asset).update()).hasMessageContaining("immutable");
    db.sql("update ouf_onboarding.managed_file_asset set status='QUARANTINED' where asset_id=:a").param("a",asset).update();
    files.requireTenantOwner(asset,"tenant-a","alice");
  }
  @Test void identicalRegistrationReconcilesAndConcurrentDifferentTenantCannotClaimSameObject()throws Exception{
    var first=owned("object://fixture/retry","tenant-a");assertThat(owned("object://fixture/retry","tenant-a")).isEqualTo(first);
    var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
    try{var futures=new ArrayList<Future<Boolean>>();for(String tenant:List.of("tenant-a","tenant-b"))futures.add(pool.submit(()->{start.await();try{owned("object://fixture/race",tenant);return true;}catch(it.comune.trieste.ouf.onboarding.domain.DomainFailure e){assertThat(e.getMessage()).contains("reconciliation required");return false;}}));
      start.countDown();int successes=0;for(var future:futures)if(future.get(10,TimeUnit.SECONDS))successes++;assertThat(successes).isOne();
      assertThat(db.sql("select count(*) from ouf_onboarding.managed_file_asset where staging_ref='object://fixture/race'").query(Long.class).single()).isOne();
    }finally{pool.shutdownNow();}
  }
}
