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
    evidence=Path.of("target/upload-native");Files.createDirectories(evidence);
    Flyway.configure().dataSource(ds).schemas("ouf_onboarding").defaultSchema("ouf_onboarding").target("38").load().migrate();
    var legacy=owner().registerOwned(null,"object://fixture/pre-v39",HASHES.ofBytes(CSV),"text/csv",CSV.length,"alice","retention://30d","tenant-a");
    db.sql("insert into ouf_onboarding.managed_file_upload_attempt(tenant_id,subject_id,file_id,idempotency_key,content_hash,size_bytes,asset_id) values('tenant-a','alice','file_fixture','pre-v39',:h,:z,:a)")
      .param("h",HASHES.ofBytes(CSV)).param("z",CSV.length).param("a",legacy.get("asset_id")).update();
    String before=db.sql("select row_to_json(u)::text from ouf_onboarding.managed_file_upload_attempt u where idempotency_key='pre-v39'").query(String.class).single();
    Flyway.configure().dataSource(ds).schemas("ouf_onboarding").defaultSchema("ouf_onboarding").load().migrate();
    String after=db.sql("select row_to_json(u)::text from ouf_onboarding.managed_file_upload_attempt u where idempotency_key='pre-v39'").query(String.class).single();
    assertThat(after).isEqualTo(before);
    var replay=owner().registerDelegatedUpload("tenant-a","alice","file_fixture","pre-v39",HASHES.ofBytes(CSV),CSV.length,null,null);
    assertThat(replay.get("assetId")).isEqualTo(legacy.get("asset_id"));
    Files.writeString(evidence.resolve("v38-upgrade.json"),JSON.writeValueAsString(Map.of("before",JSON.readTree(before),"after",JSON.readTree(after),"replayedAsset",replay.get("assetId").toString()))+"\n");
    client=UploadCrashProcess.client();real=new MinioManagedFileStagingStore(client,"upload-fixture");
    if(!client.bucketExists(BucketExistsArgs.builder().bucket("upload-fixture").build()))client.makeBucket(MakeBucketArgs.builder().bucket("upload-fixture").build());
  }
  @BeforeEach void before()throws Exception{
    db.sql("truncate ouf_onboarding.source,ouf_onboarding.managed_file_asset restart identity cascade").update();
    db.sql("update ouf_onboarding.managed_file_storage_control set epoch=epoch+1,phase='ACTIVE'").update();
    StorageRecoveryLabGate.init(db,epoch(),true);
    spool=Files.createTempFile("ouf-upload-fixture-",".csv");Files.write(spool,CSV);key=UUID.randomUUID().toString();store=new CountingStore();
  }
  @AfterEach void proof(TestInfo info)throws Exception{
    // Synthetic fixture rows only; no credentials, user content or signed URLs.
    String rows=db.sql("select coalesce(json_agg(i order by created_at),'[]'::json)::text from ouf_onboarding.managed_file_upload_intent i").query(String.class).single();
    String audit=db.sql("select coalesce(json_agg(a order by id),'[]'::json)::text from ouf_onboarding.storage_recovery_lab_audit a").query(String.class).single();
    Files.writeString(evidence.resolve(info.getTestMethod().orElseThrow().getName()+".json"),JSON.writeValueAsString(Map.of(
      "scenario",info.getTestMethod().orElseThrow().getName(),"intents",JSON.readTree(rows),"syntheticGateAudit",JSON.readTree(audit),"parentPutCalls",store.puts.get(),"parentGetCalls",store.gets.get()))+"\n");
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
  @Test void expiredReconciliationWindowCannotBeRenewedByNewCall(){
    UUID id=UUID.randomUUID();
    db.sql("insert into ouf_onboarding.managed_file_upload_intent(intent_id,upload_kind,tenant_id,subject_id,idempotency_key,file_id,content_hash,size_bytes,staging_ref,epoch,state,created_at,reconcile_until) select :i,'DELEGATED','tenant-a','alice',:k,'file_fixture',:h,:z,:r,epoch,'DISPATCHED',clock_timestamp()-interval '25 hours',clock_timestamp()-interval '1 hour' from ouf_onboarding.managed_file_storage_control")
      .param("i",id).param("k",key).param("h",HASHES.ofBytes(CSV)).param("z",CSV.length).param("r","object://managed-files/"+id).update();
    assertThatThrownBy(this::upload).hasMessageContaining("budget exhausted");assertThat(store.gets).hasValue(0);assertThat(store.puts).hasValue(0);
    assertThatThrownBy(()->db.sql("update ouf_onboarding.managed_file_upload_intent set reconcile_until=clock_timestamp()+interval '1 hour'").update()).hasMessageContaining("immutable");
  }
  @Test void tenantSubjectAndRouteNamespacesDoNotShareAnIntent(){
    var first=upload();
    var tenant=owner().registerDelegatedUpload("tenant-b","alice","file_fixture",key,HASHES.ofBytes(CSV),CSV.length,store,spool);
    var subject=owner().registerDelegatedUpload("tenant-a","bob","file_fixture",key,HASHES.ofBytes(CSV),CSV.length,store,spool);
    var direct=owner().registerDirectUpload("tenant-a","alice",key,HASHES.ofBytes(CSV),CSV.length,store,spool);
    assertThat(Set.of(first.get("assetId"),tenant.get("assetId"),subject.get("assetId"),direct.get("asset_id"))).hasSize(4);assertThat(store.puts).hasValue(4);
  }
  @Test void storageFenceCannotRegressOrBeDeleted(){
    db.sql("update ouf_onboarding.managed_file_storage_control set phase='FROZEN'").update();
    for(String command:List.of("update ouf_onboarding.managed_file_storage_control set epoch=epoch-1","update ouf_onboarding.managed_file_storage_control set phase='ACTIVE'","delete from ouf_onboarding.managed_file_storage_control"))
      assertThatThrownBy(()->db.sql(command).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
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
  ManagedFileStorageRecovery recovery(){return new ManagedFileStorageRecovery(db,tx,owner(),HASHES,new StorageRecoveryLabGate(db));}
  long epoch(){return db.sql("select epoch from ouf_onboarding.managed_file_storage_control").query(Long.class).single();}
  @Test void recoveryDrainReconcilesThenFreezesWithoutAnotherPut(){
    store.loseReply=true;assertThatThrownBy(this::upload).hasMessageContaining("reconciliation");
    long e=epoch();recovery().beginDrain(e);recovery().beginDrain(e);
    assertThatThrownBy(()->recovery().freeze(e)).hasMessageContaining("Pending");
    var result=recovery().reconcile((UUID)intent().get("intent_id"),e,store);
    assertThat(recovery().reconcile((UUID)intent().get("intent_id"),e,store)).isEqualTo(result);
    recovery().freeze(e);recovery().freeze(e);
    assertThat(store.puts).hasValue(1);assertThat(store.gets).hasValue(1);assertThat(assets()).isOne();
    assertThat(upload().get("assetId")).isEqualTo(result.get("asset_id"));
  }
  @Test void missingRecoveryObjectKeepsCutBlockedAndConsumesBudget()throws Exception{
    crash("BEFORE_PUT");long e=epoch();recovery().beginDrain(e);
    assertThatThrownBy(()->recovery().reconcile((UUID)intent().get("intent_id"),e,store)).isInstanceOf(DomainFailure.class);
    assertThatThrownBy(()->recovery().freeze(e)).hasMessageContaining("Pending");
    assertThat(intent()).containsEntry("state","DISPATCHED").containsEntry("reconciliation_reads",1);
    assertThat(store.puts).hasValue(0);assertThat(assets()).isZero();
  }
  @Test void corruptRecoveryObjectCannotAcquireReceipt()throws Exception{
    crash("BEFORE_PUT");String ref=(String)intent().get("staging_ref");byte[] wrong=CSV.clone();wrong[0]='x';
    real.putReserved(ref,new ByteArrayInputStream(wrong),wrong.length,"text/csv");
    long e=epoch();recovery().beginDrain(e);
    assertThatThrownBy(()->recovery().reconcile((UUID)intent().get("intent_id"),e,store)).hasMessageContaining("bytes differ");
    assertThatThrownBy(()->recovery().freeze(e)).hasMessageContaining("Pending");assertThat(assets()).isZero();
  }
  @Test void recoveryGenerationChangeDuringGetRejectsLatePublication(){
    store.loseReply=true;assertThatThrownBy(this::upload).hasMessageContaining("reconciliation");
    long e=epoch();recovery().beginDrain(e);
    var changing=new ManagedFileStagingStore(){
      public void putReserved(String r,InputStream i,long z,String t){throw new AssertionError("recovery PUT");}
      public byte[] get(String ref){byte[] b=store.get(ref);db.sql("update ouf_onboarding.managed_file_storage_control set epoch=epoch+1").update();return b;}
    };
    assertThatThrownBy(()->recovery().reconcile((UUID)intent().get("intent_id"),e,changing)).hasMessageContaining("generation");
    assertThatThrownBy(()->recovery().freeze(e)).hasMessageContaining("generation");assertThat(assets()).isZero();
    assertThatThrownBy(()->recovery().reconcile((UUID)intent().get("intent_id"),epoch(),store)).hasMessageContaining("generation");
    assertThat(store.gets).hasValue(1);
  }
  @Test void recoveryCannotRenewExhaustedBudgetOrEnterAmbientTransaction(){
    store.loseReply=true;assertThatThrownBy(this::upload).hasMessageContaining("reconciliation");
    long e=epoch();recovery().beginDrain(e);
    db.sql("update ouf_onboarding.managed_file_upload_intent set reconciliation_reads=128").update();
    assertThatThrownBy(()->recovery().reconcile((UUID)intent().get("intent_id"),e,store)).hasMessageContaining("budget exhausted");
    assertThatThrownBy(()->tx.execute(s->{recovery().reconcile((UUID)intent().get("intent_id"),e,store);return null;})).hasMessageContaining("ambient transaction");
    assertThat(store.gets).hasValue(0);assertThat(assets()).isZero();
  }
  @Test void concurrentRecoveryCreatesOneReceipt()throws Exception{
    store.loseReply=true;assertThatThrownBy(this::upload).hasMessageContaining("reconciliation");
    long e=epoch();recovery().beginDrain(e);UUID id=(UUID)intent().get("intent_id");
    var barrier=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
    var concurrent=new ManagedFileStagingStore(){public void putReserved(String r,InputStream i,long z,String t){throw new AssertionError("recovery PUT");} public byte[] get(String ref){try{barrier.await(10,TimeUnit.SECONDS);}catch(Exception ex){throw new RuntimeException(ex);}return store.get(ref);}};
    try{
      var a=pool.submit(()->recovery().reconcile(id,e,concurrent));var b=pool.submit(()->recovery().reconcile(id,e,concurrent));
      assertThat(a.get(15,TimeUnit.SECONDS)).isEqualTo(b.get(15,TimeUnit.SECONDS));
      assertThat(assets()).isOne();assertThat(store.puts).hasValue(1);assertThat(store.gets).hasValue(2);recovery().freeze(e);
    }finally{pool.shutdownNow();}
  }
  @Test void drainDuringInFlightPutRejectsAckThenRecoveryUsesSameIntent()throws Exception{
    store.entered=new CountDownLatch(1);store.release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
    long e=epoch();try{
      var future=pool.submit(this::upload);assertThat(store.entered.await(10,TimeUnit.SECONDS)).isTrue();
      recovery().beginDrain(e);store.release.countDown();assertThatThrownBy(()->future.get(15,TimeUnit.SECONDS)).hasCauseInstanceOf(DomainFailure.class);
      assertThat(assets()).isZero();recovery().reconcile((UUID)intent().get("intent_id"),e,store);recovery().freeze(e);
      assertThat(assets()).isOne();assertThat(store.puts).hasValue(1);
    }finally{store.release.countDown();pool.shutdownNow();}
  }
  void deadlineDuringRead(boolean draining){
    UUID id=UUID.randomUUID();
    db.sql("insert into ouf_onboarding.managed_file_upload_intent(intent_id,upload_kind,tenant_id,subject_id,idempotency_key,file_id,content_hash,size_bytes,staging_ref,epoch,state,reconcile_until) select :i,'DELEGATED','tenant-a','alice',:k,'file_fixture',:h,:z,:r,epoch,'DISPATCHED',clock_timestamp()+interval '3 seconds' from ouf_onboarding.managed_file_storage_control")
      .param("i",id).param("k",key).param("h",HASHES.ofBytes(CSV)).param("z",CSV.length).param("r","object://managed-files/"+id).update();
    var slow=new ManagedFileStagingStore(){
      public void putReserved(String r,InputStream in,long z,String t){throw new AssertionError("Unexpected PUT");}
      public byte[] get(String ref){
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        // Wait against the database deadline itself, with a finite test watchdog.
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(db.sql("select clock_timestamp()<reconcile_until from ouf_onboarding.managed_file_upload_intent where intent_id=:i").param("i",id).query(Boolean.class).single()){
          if(System.nanoTime()>deadline)throw new AssertionError("Fixture deadline exceeded");
          try{Thread.sleep(25);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
        }
        return CSV;
      }
    };
    if(draining){long e=epoch();recovery().beginDrain(e);assertThatThrownBy(()->recovery().reconcile(id,e,slow)).hasMessageContaining("expired during");}
    else assertThatThrownBy(()->owner().registerDelegatedUpload("tenant-a","alice","file_fixture",key,HASHES.ofBytes(CSV),CSV.length,slow,spool)).hasMessageContaining("expired during");
    assertThat(assets()).isZero();assertThat(intent()).containsEntry("state","DISPATCHED").containsEntry("reconciliation_reads",1);
  }
  @Test void recoveryDeadlineIncludesTimeSpentInGet(){deadlineDuringRead(true);}
  @Test void uploadDeadlineIncludesTimeSpentInGet(){deadlineDuringRead(false);}

  void gateUpdate(String clause){db.sql("update ouf_onboarding.storage_recovery_lab_gate set "+clause).update();}
  UUID pendingRecovery(){store.loseReply=true;assertThatThrownBy(this::upload).hasMessageContaining("reconciliation");recovery().beginDrain(epoch());return (UUID)intent().get("intent_id");}
  ManagedFileStagingStore changedDuringGet(Runnable change){return new ManagedFileStagingStore(){
    public void putReserved(String r,InputStream i,long z,String t){throw new AssertionError("Recovery PUT");}
    public byte[] get(String ref){byte[] bytes=store.get(ref);change.run();return bytes;}
  };}
  @Test void missingAuthorityAdapterCannotDrainOrConsumeReads(){
    var unavailable=new ManagedFileStorageRecovery(db,tx,owner(),HASHES);
    assertThatThrownBy(()->unavailable.beginDrain(epoch())).hasMessageContaining("adapter unavailable");
    assertThat(db.sql("select phase from ouf_onboarding.managed_file_storage_control").query(String.class).single()).isEqualTo("ACTIVE");
    UUID id=pendingRecovery();assertThatThrownBy(()->unavailable.reconcile(id,epoch(),store)).hasMessageContaining("adapter unavailable");
    assertThat(intent()).containsEntry("reconciliation_reads",0);assertThat(store.gets).hasValue(0);
  }
  @Test void wrongScopeActionGenerationAndInvalidAuthorityWindowsDenyBeforeRead(){
    UUID id=pendingRecovery();long e=epoch();
    for(String change:List.of("scope='tenant-a:profile'","actions='DRAIN'","epoch=epoch+1","revoked=true",
        "issued_at=clock_timestamp()+interval '1 minute'","expires_at=clock_timestamp()-interval '1 second'",
        "expires_at=issued_at+interval '25 hours'","decision_ref=''")){
      StorageRecoveryLabGate.init(db,e,true);gateUpdate(change);
      assertThatThrownBy(()->recovery().reconcile(id,e,store)).isInstanceOf(DomainFailure.class);
    }
    assertThat(intent()).containsEntry("reconciliation_reads",0);assertThat(store.gets).hasValue(0);assertThat(assets()).isZero();
  }
  @Test void unprovenQuiescenceAllowsDrainButBlocksReconcileAndFreeze(){
    gateUpdate("quiescent=false");UUID id=pendingRecovery();
    assertThatThrownBy(()->recovery().reconcile(id,epoch(),store)).hasMessageContaining("quiescence");
    assertThatThrownBy(()->recovery().freeze(epoch())).hasMessageContaining("quiescence");
    gateUpdate("quiescent=true,quiescence_ref=null");
    assertThatThrownBy(()->recovery().reconcile(id,epoch(),store)).hasMessageContaining("quiescence");
    assertThat(store.gets).hasValue(0);assertThat(intent()).containsEntry("reconciliation_reads",0);
  }
  @Test void revocationDuringPausedGetCommitsAndRejectsLateAck()throws Exception{
    UUID id=pendingRecovery();long e=epoch();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
    var paused=changedDuringGet(()->{entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("fixture timeout");}catch(InterruptedException ex){throw new RuntimeException(ex);}});
    try{
      var result=pool.submit(()->recovery().reconcile(id,e,paused));assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
      gateUpdate("revoked=true");release.countDown();
      assertThatThrownBy(()->result.get(15,TimeUnit.SECONDS)).hasCauseInstanceOf(DomainFailure.class);
      assertThatThrownBy(()->recovery().freeze(e)).hasMessageContaining("authority denied");
      assertThat(intent()).containsEntry("state","DISPATCHED").containsEntry("reconciliation_reads",1);assertThat(assets()).isZero();
    }finally{release.countDown();pool.shutdownNow();}
  }
  @Test void replacementAuthorityCannotAdoptAnInFlightRead(){
    UUID id=pendingRecovery();long e=epoch();
    assertThatThrownBy(()->recovery().reconcile(id,e,changedDuringGet(()->gateUpdate("decision_ref='lab-only/new-decision'")))).hasMessageContaining("binding changed");
    assertThat(assets()).isZero();assertThat(intent()).containsEntry("reconciliation_reads",1);
    recovery().reconcile(id,e,store);assertThat(assets()).isOne();assertThat(intent()).containsEntry("reconciliation_reads",2);assertThat(store.puts).hasValue(1);
  }
  @Test void custodyWithdrawalDuringGetRejectsLateAck(){
    UUID id=pendingRecovery();long e=epoch();
    assertThatThrownBy(()->recovery().reconcile(id,e,changedDuringGet(()->gateUpdate("quiescent=false")))).hasMessageContaining("quiescence");
    assertThat(assets()).isZero();assertThat(intent()).containsEntry("state","DISPATCHED").containsEntry("reconciliation_reads",1);
  }
  @Test void authorityExpiryIncludesTimeSpentOutsideTransaction(){
    UUID id=pendingRecovery();long e=epoch();gateUpdate("expires_at=clock_timestamp()+interval '3 seconds'");
    var slow=changedDuringGet(()->{
      long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
      while(db.sql("select clock_timestamp()<expires_at from ouf_onboarding.storage_recovery_lab_gate").query(Boolean.class).single()){
        if(System.nanoTime()>limit)throw new AssertionError("fixture timeout");
        try{Thread.sleep(25);}catch(InterruptedException ex){throw new RuntimeException(ex);}
      }
    });
    assertThatThrownBy(()->recovery().reconcile(id,e,slow)).hasMessageContaining("authority expired");
    assertThat(assets()).isZero();assertThat(intent()).containsEntry("reconciliation_reads",1);
    assertThatThrownBy(()->recovery().reconcile(id,e,store)).hasMessageContaining("authority expired");assertThat(store.gets).hasValue(1);
  }
  @Test void auditFailureRollsBackAckButPreservesConsumedRead(){
    UUID id=pendingRecovery();long e=epoch();var delegate=new StorageRecoveryLabGate(db);
    var failing=new StorageRecoveryGate(){
      public Evidence lock(Action a,long epoch,UUID intent){return delegate.lock(a,epoch,intent);}
      public void record(Evidence p,Action a,UUID intent,String outcome){delegate.record(p,a,intent,outcome);if("ACKED".equals(outcome))throw new IllegalStateException("fixture audit unavailable");}
    };
    var guarded=new ManagedFileStorageRecovery(db,tx,owner(),HASHES,failing);
    assertThatThrownBy(()->guarded.reconcile(id,e,store)).hasMessageContaining("audit unavailable");
    assertThat(assets()).isZero();assertThat(intent()).containsEntry("state","DISPATCHED").containsEntry("reconciliation_reads",1);
    assertThat(db.sql("select outcome from ouf_onboarding.storage_recovery_lab_audit order by id").query(String.class).list()).containsExactly("DRAINED","READ_RESERVED");
    recovery().reconcile(id,e,store);recovery().freeze(e);
    assertThat(db.sql("select outcome from ouf_onboarding.storage_recovery_lab_audit order by id").query(String.class).list()).containsExactly("DRAINED","READ_RESERVED","READ_RESERVED","ACKED","FROZEN");
  }
  @Test void frozenOwnerCannotReopenWithReplayedOrReplacedAuthority(){
    long e=epoch();recovery().beginDrain(e);recovery().freeze(e);gateUpdate("decision_ref='lab-only/fresh-decision'");
    assertThatThrownBy(()->recovery().beginDrain(e)).hasMessageContaining("phase");
    assertThatThrownBy(()->recovery().beginDrain(e+1)).hasMessageContaining("generation");
    gateUpdate("revoked=true");assertThatThrownBy(()->recovery().freeze(e)).hasMessageContaining("authority denied");
    assertThatThrownBy(this::upload).hasMessageContaining("phase");assertThat(store.puts).hasValue(0);
  }
}
