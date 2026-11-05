package it.comune.trieste.ouf.onboarding.application;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.*;
import it.comune.trieste.ouf.onboarding.domain.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.*;

@EnabledIfEnvironmentVariable(named="OUF_UPLOAD_NATIVE",matches="true")
class ManagedFileUploadNativeTest {
  static final ObjectMapper JSON=new ObjectMapper();
  static final CanonicalHash HASHES=new CanonicalHash(JSON);
  static final byte[] CSV="\uFEFFid;name\r\n1;Trieste\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
  static JdbcClient db;static TransactionTemplate tx;static MinioClient client;static MinioManagedFileStagingStore real;
  static Path evidence;Path spool;String key;CountingStore store;
  @BeforeAll static void setup()throws Exception{
    var ds=UploadCrashProcess.datasource();db=JdbcClient.create(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
    Flyway.configure().dataSource(ds).schemas("ouf_onboarding").defaultSchema("ouf_onboarding").load().migrate();
    client=UploadCrashProcess.client();real=new MinioManagedFileStagingStore(client,"upload-fixture");
    if(!client.bucketExists(BucketExistsArgs.builder().bucket("upload-fixture").build()))client.makeBucket(MakeBucketArgs.builder().bucket("upload-fixture").build());
    evidence=Path.of("target/upload-native");Files.createDirectories(evidence);
  }
  @BeforeEach void before()throws Exception{
    db.sql("truncate ouf_onboarding.source,ouf_onboarding.managed_file_asset restart identity cascade").update();
    db.sql("update ouf_onboarding.managed_file_storage_control set epoch=epoch+1,phase='ACTIVE'").update();
    spool=Files.createTempFile("ouf-upload-fixture-",".csv");Files.write(spool,CSV);key=UUID.randomUUID().toString();store=new CountingStore();
  }
  @AfterEach void proof(TestInfo info)throws Exception{
    // Synthetic fixture rows only; no credentials, user content or signed URLs.
    String rows=db.sql("select coalesce(json_agg(i order by created_at),'[]'::json)::text from ouf_onboarding.managed_file_upload_intent i").query(String.class).single();
    Files.writeString(evidence.resolve(info.getTestMethod().orElseThrow().getName()+".json"),JSON.writeValueAsString(Map.of(
      "scenario",info.getTestMethod().orElseThrow().getName(),"intents",JSON.readTree(rows),"parentPutCalls",store.puts.get(),"parentGetCalls",store.gets.get()))+"\n");
    Files.deleteIfExists(spool);
  }
  static ManagedFileService owner(){return new ManagedFileService(db,JSON,HASHES,null,tx,null,null);}
  Map<String,Object> upload(){return owner().registerDelegatedUpload("tenant-a","alice","file_fixture",key,HASHES.ofBytes(CSV),CSV.length,store,spool);}
  Map<String,Object> intent(){return db.sql("select * from ouf_onboarding.managed_file_upload_intent where idempotency_key=:k").param("k",key).query().singleRow();}
  long assets(){return db.sql("select count(*) from ouf_onboarding.managed_file_asset").query(Long.class).single();}
  void crash(String point)throws Exception{
    Path log=evidence.resolve(key+"-child.log");
    var p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-cp",
      System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),UploadCrashProcess.class.getName(),point,key,spool.toAbsolutePath().toString())
      .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    boolean exited=p.waitFor(45,TimeUnit.SECONDS);if(!exited)p.destroyForcibly();
    assertThat(exited).as("child terminated within deadline").isTrue();assertThat(p.exitValue()).as(Files.readString(log)).isEqualTo(86);
    assertThat(intent()).containsEntry("state","DISPATCHED").containsEntry("asset_id",null);assertThat(assets()).isZero();
  }
  class CountingStore implements ManagedFileStagingStore {
    AtomicInteger puts=new AtomicInteger(),gets=new AtomicInteger();boolean loseReply,changeEpoch;
    CountDownLatch entered,release;
    public void putReserved(String ref,InputStream in,long size,String type)throws IOException{
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThat(db.sql("select * from ouf_onboarding.managed_file_upload_intent where staging_ref=:r").param("r",ref).query().singleRow()).containsEntry("state","DISPATCHED");
      puts.incrementAndGet();
      if(entered!=null){entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IOException("fixture deadline");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}}
      real.putReserved(ref,in,size,type);
      // A separate connection can change the fence while the upload is outside a transaction.
      if(changeEpoch)db.sql("update ouf_onboarding.managed_file_storage_control set epoch=epoch+1").update();
      if(loseReply)throw new IOException("fixture response lost after durable PUT");
    }
    public byte[] get(String ref){assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();gets.incrementAndGet();return real.get(ref);}
  }
  @Test void successfulReplayUsesOneObjectAndRejectsChangedIntent(){
    var first=upload();assertThat(upload()).isEqualTo(first);assertThat(store.puts).hasValue(1);assertThat(store.gets).hasValue(0);assertThat(assets()).isOne();
    assertThatThrownBy(()->owner().registerDelegatedUpload("tenant-a","alice","file_changed",key,HASHES.ofBytes(CSV),CSV.length,store,spool)).hasMessageContaining("different file");
    assertThat(real.get((String)intent().get("staging_ref"))).isEqualTo(CSV);
  }
  @Test void lostReplyReconcilesExactBytesWithFreshOwnerAndNoSecondPut(){
    store.loseReply=true;assertThatThrownBy(this::upload).hasMessageContaining("reconciliation");assertThat(assets()).isZero();
    String ref=(String)intent().get("staging_ref");store.loseReply=false;var result=upload();
    assertThat(intent()).containsEntry("staging_ref",ref).containsEntry("state","ACKED").containsEntry("reconciliation_reads",1);
    assertThat(store.puts).hasValue(1);assertThat(store.gets).hasValue(1);assertThat(result.get("assetId")).isNotNull();assertThat(assets()).isOne();
  }
  @Test void processCrashAfterPutReconcilesWithoutReupload()throws Exception{
    crash("AFTER_PUT");String ref=(String)intent().get("staging_ref");assertThat(real.get(ref)).isEqualTo(CSV);
    upload();assertThat(store.puts).hasValue(0);assertThat(store.gets).hasValue(1);assertThat(assets()).isOne();assertThat(intent()).containsEntry("staging_ref",ref).containsEntry("state","ACKED");
  }
  @Test void processCrashBeforePutRemainsPendingWithoutBlindReplay()throws Exception{
    crash("BEFORE_PUT");assertThatThrownBy(this::upload).isInstanceOf(DomainFailure.class);assertThat(store.puts).hasValue(0);
    assertThat(intent()).containsEntry("state","DISPATCHED").containsEntry("reconciliation_reads",1);assertThat(assets()).isZero();
  }
  @Test void changedGenerationRejectsLateAckAndFurtherStorageAccess(){
    store.changeEpoch=true;assertThatThrownBy(this::upload).hasMessageContaining("generation");assertThat(assets()).isZero();
    assertThat(real.get((String)intent().get("staging_ref"))).isEqualTo(CSV);
    assertThatThrownBy(this::upload).hasMessageContaining("generation");assertThat(store.puts).hasValue(1);assertThat(store.gets).hasValue(0);
    assertThat(intent()).containsEntry("state","DISPATCHED");
  }
  @Test void drainingAndFrozenRejectNewIntentsBeforeS3(){
    for(String phase:List.of("DRAINING","FROZEN")){
      db.sql("update ouf_onboarding.managed_file_storage_control set phase=:p").param("p",phase).update();
      assertThatThrownBy(this::upload).hasMessageContaining("phase");
    }
    assertThat(db.sql("select count(*) from ouf_onboarding.managed_file_upload_intent").query(Long.class).single()).isZero();assertThat(store.puts).hasValue(0);
  }
  @Test void concurrentRetryCannotDispatchWhileOriginalPutIsPending()throws Exception{
    store.entered=new CountDownLatch(1);store.release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
    try{
      var first=pool.submit(this::upload);assertThat(store.entered.await(10,TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(this::upload).isInstanceOf(DomainFailure.class);assertThat(store.puts).hasValue(1);
      store.release.countDown();var ack=first.get(15,TimeUnit.SECONDS);assertThat(upload()).isEqualTo(ack);assertThat(assets()).isOne();
    }finally{store.release.countDown();pool.shutdownNow();}
  }
  @Test void alteredObjectCannotBeAcknowledgedByReconciliation()throws Exception{
    crash("BEFORE_PUT");String ref=(String)intent().get("staging_ref");byte[] wrong=CSV.clone();wrong[wrong.length-1]='x';
    real.putReserved(ref,new ByteArrayInputStream(wrong),wrong.length,"text/csv");
    assertThatThrownBy(this::upload).hasMessageContaining("Stored bytes differ");assertThat(assets()).isZero();assertThat(store.puts).hasValue(0);
  }
  @Test void reconciliationBudgetSurvivesFreshOwnerAndNeverResends()throws Exception{
    crash("BEFORE_PUT");db.sql("update ouf_onboarding.managed_file_upload_intent set reconciliation_reads=127 where idempotency_key=:k").param("k",key).update();
    assertThatThrownBy(this::upload).isInstanceOf(DomainFailure.class);assertThat(intent()).containsEntry("reconciliation_reads",128);
    assertThatThrownBy(this::upload).hasMessageContaining("budget exhausted");assertThat(store.gets).hasValue(1);assertThat(store.puts).hasValue(0);
  }
  @Test void ambientTransactionCannotReachIntentOrS3(){
    assertThatThrownBy(()->tx.execute(s->upload())).hasMessageContaining("ambient transaction");assertThat(store.puts).hasValue(0);assertThat(assets()).isZero();
  }
  @Test void durableIntentCannotBeReboundDeletedOrReopened(){
    upload();for(String update:List.of("set staging_ref='object://other'","set state='DISPATCHED',asset_id=null,acknowledged_at=null","set reconciliation_reads=0"))
      assertThatThrownBy(()->db.sql("update ouf_onboarding.managed_file_upload_intent "+update).update()).hasMessageContaining("immutable");
    assertThatThrownBy(()->db.sql("delete from ouf_onboarding.managed_file_upload_intent").update()).hasMessageContaining("immutable");
  }
  @Test void directUploadDeduplicatesOnlyAnExplicitRequestKey(){
    var first=owner().registerDirectUpload("tenant-a","alice",key,HASHES.ofBytes(CSV),CSV.length,store,spool);
    assertThat(owner().registerDirectUpload("tenant-a","alice",key,HASHES.ofBytes(CSV),CSV.length,store,spool).get("asset_id")).isEqualTo(first.get("asset_id"));
    assertThat(store.puts).hasValue(1);
    var unkeyed1=owner().registerDirectUpload("tenant-a","alice",null,HASHES.ofBytes(CSV),CSV.length,store,spool);
    var unkeyed2=owner().registerDirectUpload("tenant-a","alice",null,HASHES.ofBytes(CSV),CSV.length,store,spool);
    assertThat(unkeyed1.get("asset_id")).isNotEqualTo(unkeyed2.get("asset_id"));assertThat(store.puts).hasValue(3);
  }
}
