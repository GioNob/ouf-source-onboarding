package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import it.comune.trieste.ouf.authorization.PrincipalContext;
import it.comune.trieste.ouf.onboarding.application.*;
import it.comune.trieste.ouf.onboarding.domain.*;
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
class ManagedFileOwnershipRecoveryRuntimeTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->System.getenv("OUF_ONB_DB_URL"));r.add("spring.datasource.username",()->System.getenv("OUF_ONB_DB_USER"));r.add("spring.datasource.password",()->System.getenv("OUF_ONB_DB_PASSWORD"));}
  @Autowired ManagedFileOwnershipRecovery ownership;@Autowired ManagedFileService files;@Autowired ManagedProfileJobs jobs;@Autowired JdbcClient db;@Autowired CanonicalHash hashes;@Autowired TransactionTemplate tx;
  final PrincipalContext p=principal("alice","tenant-a");
  final OnboardingService.Actor actor=new OnboardingService.Actor("alice","HUMAN_USER",Set.of("ouf.managed-source.file.profile","ouf.onboarding.quarantine.release"));
  final byte[] csv="\uFEFFid;label\r\nA;Alpha\r\n".getBytes(StandardCharsets.UTF_8);
  UUID asset,job;
  static PrincipalContext principal(String subject,String tenant){return new PrincipalContext(subject,tenant,PrincipalContext.ActorType.HUMAN,null,"fixture","https://iam.test","ouf",Set.of());}
  @BeforeEach void setup(){db.sql("truncate ouf_onboarding.source,ouf_onboarding.managed_file_asset restart identity cascade").update();
    asset=(UUID)files.register(null,"object://fixture/retained",hashes.ofBytes(csv),"text/csv",csv.length,"alice","retention://30d").get("asset_id");job=UUID.randomUUID();
    db.sql("insert into ouf_onboarding.file_profile_job(job_id,asset_id,idempotency_key,state,attempts,error_code,last_error,completed_at) values(:j,:a,'same-operation','FAILED',5,'ONB_FILE_PROFILE_FAILED','Gateway object read failed with status 403',clock_timestamp())").param("j",job).param("a",asset).update();
    db.sql("insert into ouf_onboarding.managed_file_quarantine(quarantine_id,asset_id,profile_job_id,reason_code,evidence_ref,safe_detail,correlation_id) values(gen_random_uuid(),:a,:j,'ONB_FILE_PROFILE_FAILED','quarantine://fixture','Gateway object read failed with status 403','fixture')").param("a",asset).param("j",job).update();
    db.sql("update ouf_onboarding.managed_file_asset set status='QUARANTINED' where asset_id=:a").param("a",asset).update();
  }
  ManagedFileOwnershipRecovery.Decision decision(String key){var r=ownership.preview(asset,job,p,actor);return new ManagedFileOwnershipRecovery.Decision(((Number)r.get("expectedVersion")).longValue(),((Number)r.get("expectedGeneration")).longValue(),(String)r.get("inputBindingHash"),(String)r.get("reviewHash"),key,"Original uploader confirms this retained file belongs to the displayed tenant",true);}
  long receipts(){return db.sql("select count(*) from ouf_onboarding.managed_file_owner_reconciliation").query(Long.class).single();}
  Map<String,String> snapshot(){var out=new TreeMap<String,String>();for(String table:List.of("managed_file_asset","file_profile_job","managed_file_quarantine","managed_file_upload_attempt","source","managed_onboarding_review","managed_profile_budget_session","managed_profile_event"))out.put(table,db.sql("select coalesce(jsonb_agg(to_jsonb(x) order by to_jsonb(x)::text),'[]'::jsonb)::text from ouf_onboarding."+table+" x").query(String.class).single());return out;}
  @Test void previewIsReadOnlyAndExposesOnlyFreshSessionIdentityAndExactRetainedInput(){var before=snapshot();var review=ownership.preview(asset,job,p,actor);
    assertThat(review).containsEntry("status","OWNER_BINDING_REVIEW_REQUIRED").containsEntry("tenantId","tenant-a").containsEntry("subjectId","alice").containsEntry("cumulativeAttempts",5).containsEntry("humanBudgetGranted",false).containsEntry("resumed",false);
    assertThat(review).doesNotContainKeys("staging_ref","last_error","configuration","redacted_sample");assertThat(snapshot()).isEqualTo(before);assertThat(receipts()).isZero();
  }
  @Test void confirmationCreatesOneImmutableProofAndLeavesFailedFiveWithoutAnyBudgetOrResume(){var d=decision("one");var before=snapshot();
    var result=ownership.reconcile(asset,job,p,actor,d);assertThat(result).containsEntry("status","OWNER_BINDING_RECONCILED").containsEntry("humanBudgetGranted",false).containsEntry("resumed",false);
    assertThat(receipts()).isOne();assertThat(snapshot()).isEqualTo(before);files.requireTenantOwner(asset,"tenant-a","alice");
    assertThat(files.profileRecoveryCard(asset,job,p,actor)).containsEntry("status","FAILED").containsEntry("cumulativeAttempts",5).containsEntry("canResume",false);
    assertThat(files.ownedAssets("tenant-a","alice",20,null).get("items")).asList().hasSize(1);
    var restarted=new ManagedFileOwnershipRecovery(db,tx,hashes,files,jobs);
    assertThat(restarted.preview(asset,job,p,actor)).containsEntry("status","OWNER_BINDING_ALREADY_PROVEN");
    assertThat(snapshot()).isEqualTo(before);
  }
  @Test void lostResponseReconcilesSameCommandAndSameDecisionAfterNewServiceInstance(){var d=decision("lost-response");var first=ownership.reconcile(asset,job,p,actor,d);var before=snapshot();
    var restarted=new ManagedFileOwnershipRecovery(db,tx,hashes,files,jobs);var same=restarted.reconcile(asset,job,p,actor,d);
    assertThat(same).containsEntry("reconciled",true).containsEntry("decisionRef",first.get("decisionRef"));assertThat(receipts()).isOne();assertThat(snapshot()).isEqualTo(before);
  }
  @Test void uncommittedEffectsRollbackAndSameCommandCanBeReconciledWithoutDuplicatingProof(){var d=decision("rollback");var before=snapshot();
    tx.execute(status->{ownership.reconcile(asset,job,p,actor,d);status.setRollbackOnly();return null;});assertThat(receipts()).isZero();assertThat(snapshot()).isEqualTo(before);
    ownership.reconcile(asset,job,p,actor,d);assertThat(receipts()).isOne();assertThat(snapshot()).isEqualTo(before);
  }
  @Test void sameKeyCannotChangeReasonOrBindAnotherTenant(){var d=decision("fixed-key");ownership.reconcile(asset,job,p,actor,d);var before=snapshot();
    var changed=new ManagedFileOwnershipRecovery.Decision(d.expectedVersion(),d.expectedGeneration(),d.inputBindingHash(),d.reviewHash(),d.commandKey(),"A different reason is a different command",true);
    assertThatThrownBy(()->ownership.reconcile(asset,job,p,actor,changed)).hasMessageContaining("COMMAND_CONFLICT");
    assertThatThrownBy(()->ownership.reconcile(asset,job,principal("alice","tenant-b"),actor,d)).hasMessageContaining("COMMAND_CONFLICT");
    assertThatThrownBy(()->files.requireTenantOwner(asset,"tenant-b","alice")).hasMessageContaining("owner scope");assertThat(snapshot()).isEqualTo(before);assertThat(receipts()).isOne();
  }
  @Test void twoConcurrentCommandsCreateOneProofWithoutChangingTheJob()throws Exception{var d=decision("first");var other=new ManagedFileOwnershipRecovery.Decision(d.expectedVersion(),d.expectedGeneration(),d.inputBindingHash(),d.reviewHash(),"second",d.reason(),true);var before=snapshot();
    var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
    try{var futures=new ArrayList<Future<Boolean>>();for(var command:List.of(d,other))futures.add(pool.submit(()->{start.await();try{ownership.reconcile(asset,job,p,actor,command);return true;}catch(DomainFailure conflict){assertThat(conflict.getMessage()).contains("COMMAND_CONFLICT");return false;}}));start.countDown();int successes=0;for(var f:futures)if(f.get(10,TimeUnit.SECONDS))successes++;assertThat(successes).isOne();
    }finally{pool.shutdownNow();}
    assertThat(receipts()).isOne();assertThat(snapshot()).isEqualTo(before);
  }
  @Test void missingConfirmationOrCurrentRecoveryPermissionCannotCreateProof(){var d=decision("unconfirmed");var before=snapshot();
    var no=new ManagedFileOwnershipRecovery.Decision(d.expectedVersion(),d.expectedGeneration(),d.inputBindingHash(),d.reviewHash(),d.commandKey(),d.reason(),false);
    assertThatThrownBy(()->ownership.reconcile(asset,job,p,actor,no)).hasMessageContaining("DECISION_INVALID");
    var readOnly=new OnboardingService.Actor("alice","HUMAN_USER",Set.of("ouf.managed-source.file.profile"));
    assertThatThrownBy(()->ownership.preview(asset,job,p,readOnly)).hasMessageContaining("Both current HUMAN");
    assertThatThrownBy(()->ownership.reconcile(asset,job,p,readOnly,d)).hasMessageContaining("Both current HUMAN");assertThat(receipts()).isZero();assertThat(snapshot()).isEqualTo(before);
  }
  @Test void anotherUploaderCannotPreviewOrReconcile(){var d=decision("other-user");var before=snapshot();var bob=principal("bob","tenant-a");var b=new OnboardingService.Actor("bob","HUMAN_USER",actor.capabilities());
    assertThatThrownBy(()->ownership.preview(asset,job,bob,b)).hasMessageContaining("not owned");assertThatThrownBy(()->ownership.reconcile(asset,job,bob,b,d)).hasMessageContaining("not owned");assertThat(receipts()).isZero();assertThat(snapshot()).isEqualTo(before);
  }
  @Test void changedGenerationOrSessionTenantInvalidatesTheReview(){var d=decision("stale");
    assertThatThrownBy(()->ownership.reconcile(asset,job,principal("alice","tenant-b"),actor,d)).hasMessageContaining("STALE_REVIEW");
    db.sql("update ouf_onboarding.file_profile_job set generation=generation+1 where job_id=:j").param("j",job).update();var before=snapshot();
    assertThatThrownBy(()->ownership.reconcile(asset,job,p,actor,d)).hasMessageContaining("STALE_REVIEW");assertThat(receipts()).isZero();assertThat(snapshot()).isEqualTo(before);
  }
  @Test void changedInputInvalidatesTheReview(){var d=decision("changed-input");db.sql("update ouf_onboarding.managed_file_asset set content_hash=:h where asset_id=:a").param("h","sha256:"+"a".repeat(64)).param("a",asset).update();var before=snapshot();
    assertThatThrownBy(()->ownership.reconcile(asset,job,p,actor,d)).hasMessageContaining("STALE_REVIEW");assertThat(receipts()).isZero();assertThat(snapshot()).isEqualTo(before);
  }
  @Test void conflictingUploadReceiptOrExistingSourceIsNotOverridden(){var d=decision("conflicting-proof");
    db.sql("insert into ouf_onboarding.managed_file_upload_attempt(tenant_id,subject_id,file_id,idempotency_key,content_hash,size_bytes,asset_id) values('tenant-b','alice','file_fixture','fixture',:h,:z,:a)").param("h",hashes.ofBytes(csv)).param("z",csv.length).param("a",asset).update();var before=snapshot();
    assertThatThrownBy(()->ownership.preview(asset,job,p,actor)).hasMessageContaining("EXISTING_PROVENANCE");assertThatThrownBy(()->ownership.reconcile(asset,job,p,actor,d)).hasMessageContaining("EXISTING_PROVENANCE");assertThat(receipts()).isZero();assertThat(snapshot()).isEqualTo(before);
  }
  @Test void receiptIsAppendOnlyAndCannotBeBypassedByALaterConflictingLegacyReceipt(){ownership.reconcile(asset,job,p,actor,decision("immutable"));
    assertThatThrownBy(()->db.sql("update ouf_onboarding.managed_file_owner_reconciliation set tenant_id='tenant-b'").update()).hasMessageContaining("immutable");
    assertThatThrownBy(()->db.sql("delete from ouf_onboarding.managed_file_owner_reconciliation").update()).hasMessageContaining("immutable");
    db.sql("insert into ouf_onboarding.managed_file_upload_attempt(tenant_id,subject_id,file_id,idempotency_key,content_hash,size_bytes,asset_id) values('tenant-b','alice','file_fixture','fixture',:h,:z,:a)").param("h",hashes.ofBytes(csv)).param("z",csv.length).param("a",asset).update();
    files.requireTenantOwner(asset,"tenant-a","alice");assertThatThrownBy(()->files.requireTenantOwner(asset,"tenant-b","alice")).hasMessageContaining("owner scope");
  }
  @Test void existingRealProofNeedsNoReconciliation(){db.sql("insert into ouf_onboarding.managed_file_upload_attempt(tenant_id,subject_id,file_id,idempotency_key,content_hash,size_bytes,asset_id) values('tenant-a','alice','file_fixture','fixture',:h,:z,:a)").param("h",hashes.ofBytes(csv)).param("z",csv.length).param("a",asset).update();var before=snapshot();
    assertThat(ownership.preview(asset,job,p,actor)).containsEntry("status","OWNER_BINDING_ALREADY_PROVEN");assertThat(receipts()).isZero();assertThat(snapshot()).isEqualTo(before);
  }
}
