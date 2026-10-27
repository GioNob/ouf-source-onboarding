package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import it.comune.trieste.ouf.onboarding.application.*;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.*;

/** Real PG ownership, pagination and tenant-fenced command history. */
@SpringBootTest
class ManagedFileRecoveryRuntimeTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->required("OUF_ONB_DB_URL"));r.add("spring.datasource.username",()->required("OUF_ONB_DB_USER"));r.add("spring.datasource.password",()->required("OUF_ONB_DB_PASSWORD"));}
  @Autowired ManagedFileService files;@Autowired OnboardingService onboarding;@Autowired CanonicalHash hashes;@Autowired JdbcClient db;
  final byte[] csv="id,label\nA,Alpha\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
  @BeforeEach void clean(){db.sql("truncate ouf_onboarding.source,ouf_onboarding.managed_file_asset restart identity cascade").update();}
  UUID asset(String subject){return (UUID)files.register(null,"object://fixture/recovery/"+UUID.randomUUID(),hashes.ofBytes(csv),"text/csv",csv.length,subject,"retention://fixture").get("asset_id");}
  void proof(UUID asset,String tenant,String subject){db.sql("insert into ouf_onboarding.managed_file_upload_attempt(tenant_id,subject_id,file_id,idempotency_key,content_hash,size_bytes,asset_id) values(:t,:s,:f,:k,:h,:n,:a)")
    .param("t",tenant).param("s",subject).param("f","file_"+UUID.randomUUID()).param("k","upload:"+UUID.randomUUID()).param("h",hashes.ofBytes(csv)).param("n",csv.length).param("a",asset).update();}
  @SuppressWarnings("unchecked") @Test void paginationCannotExposeAnotherTenantSubjectOrUnprovenLegacyAsset(){
    var expected=new HashSet<UUID>();for(int n=0;n<3;n++){UUID a=asset("alice");proof(a,"tenant-a","alice");expected.add(a);}
    UUID foreign=asset("alice");proof(foreign,"tenant-b","alice");UUID other=asset("bob");proof(other,"tenant-a","bob");asset("alice");
    var first=files.ownedAssets("tenant-a","alice",2,null);assertThat(first).containsEntry("hasMore",true).containsEntry("historicalCoverage","UPLOAD_OR_REVIEW_RECEIPTS");
    var entries=(List<Map<String,Object>>)first.get("items");assertThat(entries).hasSize(2);
    var second=files.ownedAssets("tenant-a","alice",2,(UUID)first.get("nextAfterAssetId"));assertThat(second).containsEntry("hasMore",false);
    var found=new HashSet<UUID>();entries.forEach(e->found.add((UUID)e.get("assetId")));((List<Map<String,Object>>)second.get("items")).forEach(e->found.add((UUID)e.get("assetId")));
    assertThat(found).isEqualTo(expected);for(var e:entries)assertThat(e).doesNotContainKeys("stagingRef","contentHash","uploadedBy");
    assertThatThrownBy(()->files.ownedAssets("tenant-a","alice",2,foreign)).hasMessageContaining("not proven");
    assertThatThrownBy(()->files.ownedAssets("tenant-a","alice",51,null)).hasMessageContaining("Bounded");
  }
  @SuppressWarnings("unchecked") @Test void nativeDraftRecordsAuthenticatedTenantAndCrossTenantRetryCannotReuseIt(){
    UUID asset=asset("alice");proof(asset,"tenant-a","alice");proof(asset,"tenant-b","alice");
    UUID profile=(UUID)files.profile(asset,csv,"object://fixture/recovery/sample").get("profile_id");String source="recovery-source";
    var fields=List.of(new ManagedFileService.FieldDecision("id","INCLUDE","RESTRICTED","urn:fixture:id","IDENTITY",null,null,null),new ManagedFileService.FieldDecision("label","INCLUDE","RESTRICTED","urn:fixture:label","IDENTITY",null,null,null));
    var actor=new OnboardingService.Actor("alice","HUMAN_USER");
    var draft=files.onboardIdempotent(asset,profile,source,"Recovery","Fixture","urn:fixture:Thing",List.of("fixture@1"),List.of("id"),fields,null,actor,"c","draft-one","tenant-a");
    assertThat((Map<String,Object>)onboarding.source(source).get("metadata")).containsEntry("managedTenantId","tenant-a");
    assertThat(files.onboardIdempotent(asset,profile,source,"Recovery","Fixture","urn:fixture:Thing",List.of("fixture@1"),List.of("id"),fields,null,actor,"c","draft-one","tenant-a")).isEqualTo(draft);
    assertThatThrownBy(()->files.onboardIdempotent(asset,profile,source,"Recovery","Fixture","urn:fixture:Thing",List.of("fixture@1"),List.of("id"),fields,null,actor,"c","draft-one","tenant-b")).hasMessageContaining("different onboarding arguments");
    var a=(List<Map<String,Object>>)files.ownedAssets("tenant-a","alice",20,null).get("items");assertThat(a.getFirst()).containsEntry("sourceId",source).containsEntry("profileId",profile);
    var b=(List<Map<String,Object>>)files.ownedAssets("tenant-b","alice",20,null).get("items");assertThat(b.getFirst()).doesNotContainKey("sourceId");
    assertThat(db.sql("select count(*) from ouf_onboarding.onboarding_version where source_id=:s").param("s",source).query(Long.class).single()).isOne();
  }
  @SuppressWarnings("unchecked") @Test void failedAndRunningJobsAreDiscoverableWithoutNewJobOrPrivateDetails(){
    UUID a=asset("alice");proof(a,"tenant-a","alice");
    var first=files.requestProfile(a,"retained-job");UUID job=UUID.fromString(first.get("jobId").toString());
    db.sql("update ouf_onboarding.file_profile_job set state='FAILED',attempts=5,error_code='ONB_FILE_PROFILE_FAILED',last_error='PRIVATE_CREDENTIAL_AND_OBJECT_PATH' where job_id=:j").param("j",job).update();
    for(int chat=0;chat<2;chat++){
      var page=files.ownedAssets("tenant-a","alice",10,null);
      var item=((List<Map<String,Object>>)page.get("items")).getFirst();
      assertThat((String)item.get("workLabel")).startsWith("CSV · ");
      assertThat((Map<String,Object>)item.get("profileJob")).containsEntry("jobId",job).containsEntry("status","FAILED").containsEntry("errorCode","ONB_FILE_PROFILE_FAILED").doesNotContainKeys("last_error","lastError","idempotencyKey","claimedBy");
      assertThat(page.toString()).doesNotContain("PRIVATE_CREDENTIAL_AND_OBJECT_PATH","object://");
    }
    assertThat(db.sql("select count(*) from ouf_onboarding.file_profile_job where asset_id=:a").param("a",a).query(Long.class).single()).isOne();
    assertThat(((List<?>)files.ownedAssets("tenant-b","alice",10,null).get("items"))).isEmpty();
  }
  @SuppressWarnings("unchecked") @Test void runningJobUsesARealOwnerLeaseAndHidesUnknownErrorCodes(){
    UUID a=asset("alice");proof(a,"tenant-a","alice");
    var retained=files.requestProfile(a,"active-job");UUID job=UUID.fromString(retained.get("jobId").toString());
    var claimed=files.claimProfile("fixture-worker",java.time.Duration.ofMinutes(2),java.time.Instant.now()).orElseThrow();
    assertThat(claimed.get("job_id")).isEqualTo(job);
    db.sql("update ouf_onboarding.file_profile_job set error_code='PRIVATE_UNKNOWN_CODE' where job_id=:j").param("j",job).update();
    var item=((List<Map<String,Object>>)files.ownedAssets("tenant-a","alice",10,null).get("items")).getFirst();
    assertThat((Map<String,Object>)item.get("profileJob")).containsEntry("status","RUNNING").doesNotContainKey("errorCode");
  }
  @SuppressWarnings("unchecked") @Test void latestJobSelectionIsDeterministicAndNoJobRemainsAbsent(){
    UUID a=asset("alice");proof(a,"tenant-a","alice");
    var empty=((List<Map<String,Object>>)files.ownedAssets("tenant-a","alice",10,null).get("items")).getFirst();assertThat(empty).doesNotContainKey("profileJob");
    files.requestProfile(a,"older");UUID id=UUID.randomUUID();
    // Retained multi-job history predates the admission guard; discovery still reports it.
    db.sql("insert into ouf_onboarding.file_profile_job(job_id,asset_id,idempotency_key) values(:j,:a,'retained-newer')").param("j",id).param("a",a).update();
    db.sql("update ouf_onboarding.file_profile_job set created_at=created_at+interval '1 hour' where job_id=:j").param("j",id).update();
    var item=((List<Map<String,Object>>)files.ownedAssets("tenant-a","alice",10,null).get("items")).getFirst();assertThat((Map<String,Object>)item.get("profileJob")).containsEntry("jobId",id).containsEntry("status","QUEUED");
  }
  private static String required(String name){return Objects.requireNonNull(System.getenv(name),name);}
}
