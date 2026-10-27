package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import it.comune.trieste.ouf.onboarding.application.*;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.*;

@SpringBootTest
class ManagedProfileFailureRuntimeTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->System.getenv("OUF_ONB_DB_URL"));r.add("spring.datasource.username",()->System.getenv("OUF_ONB_DB_USER"));r.add("spring.datasource.password",()->System.getenv("OUF_ONB_DB_PASSWORD"));}
  @Autowired ManagedFileService files;@Autowired JdbcClient db;@Autowired CanonicalHash hashes;
  @BeforeEach void clean(){db.sql("truncate ouf_onboarding.source,ouf_onboarding.managed_file_asset restart identity cascade").update();}
  UUID asset(){byte[] bytes="id,label\nA,Alpha\n".getBytes();return(UUID)files.register(null,"object://fixture/"+UUID.randomUUID(),hashes.ofBytes(bytes),"text/csv",bytes.length,"alice","retention://fixture").get("asset_id");}
  @Test void forbiddenIsTerminalAfterOneAttemptAndQuarantineReleaseCannotCreateAnotherBudget(){
    UUID asset=asset(),job=(UUID)files.requestProfile(asset,"initial").get("job_id");var reads=new AtomicInteger();
    ManagedFileObjectStore objects=(reference,size)->{reads.incrementAndGet();throw ManagedFileObjectReadFailure.http(403,null,Instant.now());};
    var worker=new ManagedFileProfileWorker(files,objects,"ci-worker",Duration.ofMinutes(2),Duration.ofSeconds(30));
    worker.runOnce();worker.runOnce();assertThat(reads).hasValue(1);
    var failed=files.profileJob(asset,job);assertThat(failed).containsEntry("state","FAILED").containsEntry("attempts",1);
    assertThat(failed.get("last_error")).isEqualTo("Gateway object read failed with status 403");
    var quarantine=files.quarantines("OPEN").getFirst();assertThat(quarantine).containsEntry("profile_job_id",job);
    files.resolveQuarantine((UUID)quarantine.get("quarantine_id"),"RELEASED",new OnboardingService.Actor("alice","HUMAN_USER",Set.of("ouf.onboarding.quarantine.release")),"fixture-decision","fixture-correlation");
    assertThat(files.requestProfile(asset,"different-chat-key").get("job_id")).isEqualTo(job);
    assertThat(files.profileJob(asset,job)).isEqualTo(failed);worker.runOnce();assertThat(reads).hasValue(1);
    assertThat(db.sql("select count(*) from ouf_onboarding.file_profile_job where asset_id=:a").param("a",asset).query(Long.class).single()).isOne();
  }
  @Test void exhaustedFiveAttemptHistoryIsPreservedAcrossNewRequestKeys(){
    UUID asset=asset(),job=(UUID)files.requestProfile(asset,"initial").get("job_id");
    db.sql("update ouf_onboarding.file_profile_job set state='FAILED',attempts=5,error_code='ONB_FILE_PROFILE_FAILED',last_error='retained evidence',completed_at=clock_timestamp() where job_id=:j").param("j",job).update();
    var before=files.profileJob(asset,job);
    for(int n=0;n<5;n++)assertThat(files.requestProfile(asset,"new-chat-"+n).get("job_id")).isEqualTo(job);
    assertThat(files.profileJob(asset,job)).isEqualTo(before);
    assertThat(files.claimProfile("another-worker",Duration.ofMinutes(2),Instant.now())).isEmpty();
  }
  @Test void concurrentNewKeysShareOneLogicalJob()throws Exception{
    UUID asset=asset();var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
    try{
      var first=pool.submit(()->{gate.await();return files.requestProfile(asset,"first").get("job_id");});
      var second=pool.submit(()->{gate.await();return files.requestProfile(asset,"second").get("job_id");});gate.countDown();
      assertThat(first.get(10,TimeUnit.SECONDS)).isEqualTo(second.get(10,TimeUnit.SECONDS));
      assertThat(db.sql("select count(*) from ouf_onboarding.file_profile_job where asset_id=:a").param("a",asset).query(Long.class).single()).isOne();
    }finally{pool.shutdownNow();}
  }
  @Test void ambiguousRetainedMultiJobHistoryIsNotAdoptedAsANewBudget(){
    UUID asset=asset();files.requestProfile(asset,"original");
    db.sql("insert into ouf_onboarding.file_profile_job(job_id,asset_id,idempotency_key) values(gen_random_uuid(),:a,'legacy-second')").param("a",asset).update();
    assertThatThrownBy(()->files.requestProfile(asset,"fresh-key")).hasMessageContaining("Multiple retained profile jobs");
    assertThat(db.sql("select count(*) from ouf_onboarding.file_profile_job where asset_id=:a").param("a",asset).query(Long.class).single()).isEqualTo(2);
  }
}
