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
import java.time.Instant;
import it.comune.trieste.ouf.authorization.*;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.*;
import it.comune.trieste.ouf.onboarding.authorization.*;
import it.comune.trieste.ouf.onboarding.installation.PlatformStorageRecoveryAuthorityFence;
import it.comune.trieste.ouf.onboarding.installation.FilePlatformRecoveryAuthority;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.*;

@EnabledIfEnvironmentVariable(named="OUF_UPLOAD_NATIVE",matches="true")
class ManagedFileUploadNativeTest {
  static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
  static final CanonicalHash HASHES=new CanonicalHash(JSON);
  static final byte[] CSV="\uFEFFid;name\r\n1;Trieste\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
  static JdbcClient db;static TransactionTemplate tx;static MinioClient client;static MinioManagedFileStagingStore real;
  static Path evidence;Path spool;String key;CountingStore store;
  StorageRecoveryRuntime runtime;
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
    runtime=StorageRecoveryRuntime.start("lab-installation",db);StorageRecoveryLabGate.init(db,epoch(),true,runtime);
    db.sql("truncate ouf_authorization.storage_recovery_decision cascade").update();
    db.sql("insert into ouf_onboarding.storage_recovery_authority_projection values(true,0,null)").update();
    spool=Files.createTempFile("ouf-upload-fixture-",".csv");Files.write(spool,CSV);key=UUID.randomUUID().toString();store=new CountingStore();
  }
  @AfterEach void proof(TestInfo info)throws Exception{
    // Synthetic fixture rows only; no credentials, user content or signed URLs.
    String rows=db.sql("select coalesce(json_agg(i order by created_at),'[]'::json)::text from ouf_onboarding.managed_file_upload_intent i").query(String.class).single();
    String audit=db.sql("select coalesce(json_agg(a order by id),'[]'::json)::text from ouf_onboarding.storage_recovery_lab_audit a").query(String.class).single();
    String delivery=db.sql("select coalesce(json_agg(a order by occurred_at,event_id),'[]'::json)::text from ouf_authorization.storage_recovery_delivery_audit a").query(String.class).single();
    String actions=db.sql("select coalesce(json_agg(a order by occurred_at,event_id),'[]'::json)::text from ouf_onboarding.storage_recovery_action_audit a").query(String.class).single();
    Files.writeString(evidence.resolve(info.getTestMethod().orElseThrow().getName()+".json"),JSON.writeValueAsString(Map.of(
      "scenario",info.getTestMethod().orElseThrow().getName(),"intents",JSON.readTree(rows),"syntheticGateAudit",JSON.readTree(audit),"deliveryAudit",JSON.readTree(delivery),"policyOwnerAudit",JSON.readTree(actions),"parentPutCalls",store.puts.get(),"parentGetCalls",store.gets.get()))+"\n");
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
  ManagedFileStorageRecovery recovery(){return recovery(runtime);}
  ManagedFileStorageRecovery recovery(StorageRecoveryRuntime binding){return new ManagedFileStorageRecovery(db,tx,owner(),HASHES,new StorageRecoveryLabGate(db),binding);}
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
      StorageRecoveryLabGate.init(db,e,true,runtime);gateUpdate(change);
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
    var guarded=new ManagedFileStorageRecovery(db,tx,owner(),HASHES,failing,runtime);
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
  @Test void authorityExpiryDuringAuditRollsBackWholePublication(){
    UUID id=pendingRecovery();long e=epoch();gateUpdate("expires_at=clock_timestamp()+interval '3 seconds'");
    var delegate=new StorageRecoveryLabGate(db);var delayed=new AtomicInteger();
    var slow=new StorageRecoveryGate(){
      public Evidence lock(Action a,long epoch,UUID intent){return delegate.lock(a,epoch,intent);}
      public void record(Evidence p,Action a,UUID intent,String outcome){
        delegate.record(p,a,intent,outcome);
        if("ACKED".equals(outcome)){delayed.incrementAndGet();db.sql("select pg_sleep(3)").query().listOfRows();}
      }
    };
    assertThatThrownBy(()->new ManagedFileStorageRecovery(db,tx,owner(),HASHES,slow,runtime).reconcile(id,e,store)).hasMessageContaining("expired before transition completion");
    assertThat(delayed).hasValue(1);assertThat(assets()).isZero();assertThat(intent()).containsEntry("state","DISPATCHED").containsEntry("reconciliation_reads",1);
    assertThat(db.sql("select outcome from ouf_onboarding.storage_recovery_lab_audit order by id").query(String.class).list()).containsExactly("DRAINED","READ_RESERVED");
  }
  @Test void contendedAuthorityLockFailsWithinBoundAndLeavesControlActive()throws Exception{
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
    try{
      var held=pool.submit(()->tx.executeWithoutResult(status->{
        db.sql("select singleton from ouf_onboarding.storage_recovery_lab_gate for update").query(Boolean.class).single();entered.countDown();
        try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("fixture timeout");}catch(InterruptedException ex){throw new RuntimeException(ex);}
      }));
      assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(()->recovery().beginDrain(epoch())).hasMessageContaining("lock timeout");
      release.countDown();held.get(15,TimeUnit.SECONDS);
      assertThat(db.sql("select phase from ouf_onboarding.managed_file_storage_control").query(String.class).single()).isEqualTo("ACTIVE");
    }finally{release.countDown();pool.shutdownNow();}
  }
  @Test void adapterWithoutTrustedRuntimeBindingIsDenied(){
    var unbound=new ManagedFileStorageRecovery(db,tx,owner(),HASHES,new StorageRecoveryLabGate(db));
    assertThatThrownBy(()->unbound.beginDrain(epoch())).hasMessageContaining("runtime binding unavailable");
    assertThat(db.sql("select phase from ouf_onboarding.managed_file_storage_control").query(String.class).single()).isEqualTo("ACTIVE");
  }
  @Test void freshRuntimeCannotReusePersistedAuthorityOrSpendIntentBudget(){
    UUID id=pendingRecovery();var replacement=StorageRecoveryRuntime.start("lab-installation",db);
    assertThat(replacement.sessionId()).isNotEqualTo(runtime.sessionId());
    assertThatThrownBy(()->recovery(replacement).reconcile(id,epoch(),store)).hasMessageContaining("runtime binding differs");
    assertThatThrownBy(()->recovery(replacement).freeze(epoch())).hasMessageContaining("runtime binding differs");
    assertThat(store.gets).hasValue(0);assertThat(assets()).isZero();assertThat(intent()).containsEntry("reconciliation_reads",0);
  }
  @Test void installationBindingIsIndependentOfMatchingRuntimeNonce(){
    UUID id=pendingRecovery();gateUpdate("installation_ref='another-installation'");
    assertThatThrownBy(()->recovery().reconcile(id,epoch(),store)).hasMessageContaining("runtime binding differs");
    assertThat(store.gets).hasValue(0);assertThat(intent()).containsEntry("reconciliation_reads",0);
  }
  void bindRuntimeOnly(StorageRecoveryRuntime binding){
    db.sql("update ouf_onboarding.storage_recovery_lab_gate set runtime_session_id=:r").param("r",binding.sessionId()).update();
  }
  @Test void freshAuthorityBindingDoesNotRenewOldQuiescence(){
    UUID id=pendingRecovery();var replacement=StorageRecoveryRuntime.start("lab-installation",db);bindRuntimeOnly(replacement);
    assertThatThrownBy(()->recovery(replacement).reconcile(id,epoch(),store)).hasMessageContaining("quiescence");
    assertThatThrownBy(()->recovery(replacement).freeze(epoch())).hasMessageContaining("quiescence");
    assertThat(store.gets).hasValue(0);assertThat(intent()).containsEntry("reconciliation_reads",0);
  }
  @Test void reboundAuthorityAndCustodyDoNotResetExhaustedIntentBudget(){
    UUID id=pendingRecovery();db.sql("update ouf_onboarding.managed_file_upload_intent set reconciliation_reads=128").update();
    var replacement=StorageRecoveryRuntime.start("lab-installation",db);bindRuntimeOnly(replacement);
    db.sql("update ouf_onboarding.storage_recovery_lab_gate set quiescence_runtime_session_id=:r").param("r",replacement.sessionId()).update();
    assertThatThrownBy(()->recovery(replacement).reconcile(id,epoch(),store)).hasMessageContaining("budget exhausted");
    assertThat(intent()).containsEntry("reconciliation_reads",128);assertThat(store.gets).hasValue(0);
  }
  @Test void matchingFreshBindingCannotOverrideRevocation(){
    UUID id=pendingRecovery();var replacement=StorageRecoveryRuntime.start("lab-installation",db);bindRuntimeOnly(replacement);
    db.sql("update ouf_onboarding.storage_recovery_lab_gate set revoked=true,quiescence_runtime_session_id=:r").param("r",replacement.sessionId()).update();
    assertThatThrownBy(()->recovery(replacement).reconcile(id,epoch(),store)).hasMessageContaining("authority denied");
    assertThat(intent()).containsEntry("reconciliation_reads",0);assertThat(store.gets).hasValue(0);
  }
  @Test void runtimeBindingChangeDuringGetRejectsLateAck(){
    UUID id=pendingRecovery();long e=epoch();var replacement=StorageRecoveryRuntime.start("lab-installation",db);
    assertThatThrownBy(()->recovery().reconcile(id,e,changedDuringGet(()->bindRuntimeOnly(replacement)))).hasMessageContaining("runtime binding differs");
    assertThat(assets()).isZero();assertThat(intent()).containsEntry("state","DISPATCHED").containsEntry("reconciliation_reads",1);
  }
  AuthorizationPolicyRegistry policyRegistry(){return new AuthorizationPolicyRegistry(db,JSON,HASHES);}
  PrincipalContext operator(){return new PrincipalContext("operator","platform-tenant",PrincipalContext.ActorType.HUMAN,null,"lab/authn","lab/issuer","lab/audience",Set.of(StorageRecoveryAuthorization.CAPABILITY.requiredScope()),new PrincipalContext.IdentityClaims(Set.of(),"lab-mfa",Set.of("mfa"),Instant.now().minusSeconds(1)));}
  // Component fixture only. The physical restore test uses an independent external file.
  StorageRecoveryAuthorityFence.Proof fenceProof(long generation,boolean revoked){
    var active=policyRegistry().active().orElseThrow();
    return new StorageRecoveryAuthorityFence.Proof("lab-installation","platform-tenant",active.bundleId(),active.version(),generation,Instant.now().minusSeconds(1),Instant.now().plusSeconds(600),revoked);
  }
  StorageRecoveryAuthorization authority(StorageRecoveryAuthorityFence fence){return new StorageRecoveryAuthorization(db,tx,JSON,policyRegistry(),HASHES,"lab-installation","platform-tenant",fence);}
  StorageRecoveryAuthorization authority(){return authority(()->fenceProof(1,false));}
  void recoveryPolicy(PrincipalContext p,String bundle,long version,boolean allowed){
    var constraints=new GrantConstraints("ALLOW",null,"installation-storage-recovery","lab-installation",Map.of("storageOwner","onboarding"),Set.of(),Set.of("SECURITY_SENSITIVE"),null,Set.of(),null);
    var grants=allowed?List.of(new Grant("lab-recovery",StorageRecoveryAuthorization.CAPABILITY.capabilityId(),"platform-tenant",p.subjectId(),null,null,Instant.now().minusSeconds(60),Instant.now().plusSeconds(600),constraints)):List.<Grant>of();
    tx.executeWithoutResult(s->policyRegistry().publishAndActivate(new PolicyBundle(bundle,version,Instant.now(),List.of(StorageRecoveryAuthorization.CAPABILITY),grants),"lab-operator"));
  }
  StorageRecoveryAuthorization.Request request(String command){return new StorageRecoveryAuthorization.Request(command,runtime.sessionId(),epoch(),StorageRecoveryAuthorization.ACTIONS,Instant.now().plusSeconds(300));}
  ManagedFileStorageRecovery policyRecovery(){return policyRecovery(authority());}
  ManagedFileStorageRecovery policyRecovery(StorageRecoveryAuthorization authorization){
    StorageRecoveryCustody custody=(installation,session,epoch)->{
      var evidence=new StorageRecoveryLabGate(db).lock(StorageRecoveryGate.Action.RECONCILE,epoch,null);
      return new StorageRecoveryCustody.Proof(evidence.quiescenceRef(),evidence.quiescenceRuntimeSessionId(),evidence.quiescent());
    };
    return new ManagedFileStorageRecovery(db,tx,owner(),HASHES,new PolicyStorageRecoveryGate(db,authorization,runtime,custody),runtime);
  }
  StorageRecoveryAuthorization.Decision delivered(PrincipalContext p,String bundle){recoveryPolicy(p,bundle,1,true);var d=authority().prepare(p,request(key));assertThat(authority().deliver(d.id(),0)).isOne();return d;}
  @Test void storageCapabilityIsDistinctAndDenyByDefault(){
    var p=operator();recoveryPolicy(p,key,1,false);
    assertThatThrownBy(()->authority().prepare(p,request(key))).hasMessageContaining("policy denied");
    assertThat(AuthorizationCapabilities.bootstrapDescriptors()).doesNotContain(StorageRecoveryAuthorization.CAPABILITY);
    assertThat(AuthorizationCapabilities.installationDescriptors()).doesNotContain(StorageRecoveryAuthorization.CAPABILITY);
    assertThat(db.sql("select count(*) from ouf_authorization.storage_recovery_decision").query(Long.class).single()).isZero();
  }
  @Test void staleHumanAndServiceCannotPrepareStorageAuthority(){
    var p=operator();recoveryPolicy(p,key,1,true);
    var stale=new PrincipalContext(p.subjectId(),p.tenantId(),p.actorType(),null,p.authenticationContextRef(),p.issuer(),p.audience(),p.scopes(),new PrincipalContext.IdentityClaims(Set.of(),"lab-mfa",Set.of("mfa"),Instant.now().minusSeconds(1000)));
    var service=new PrincipalContext(p.subjectId(),p.tenantId(),PrincipalContext.ActorType.SERVICE,"lab-service",p.authenticationContextRef(),p.issuer(),p.audience(),p.scopes(),p.claims());
    for(var invalid:List.of(stale,service))assertThatThrownBy(()->authority().prepare(invalid,request(key))).hasMessageContaining("HUMAN");
  }
  @Test void prepareLostResponseReplaysExactDecisionWithoutRenewal(){
    var p=operator();recoveryPolicy(p,key,1,true);var request=request(key);var first=authority().prepare(p,request);
    assertThat(authority().prepare(p,request)).isEqualTo(first);
    assertThatThrownBy(()->authority().prepare(p,new StorageRecoveryAuthorization.Request(key,runtime.sessionId(),epoch(),Set.of("DRAIN"),request.expiresAt()))).hasMessageContaining("different binding");
    assertThat(db.sql("select count(*) from ouf_authorization.storage_recovery_decision").query(Long.class).single()).isOne();
  }
  @Test void deliveredLostResponseReconcilesSameProjectionVersion(){
    var p=operator();var d=delivered(p,key);
    assertThat(authority().deliver(d.id(),0)).isOne();
    assertThat(db.sql("select event from ouf_authorization.storage_recovery_delivery_audit where decision_id=:id order by occurred_at").param("id",d.id()).query(String.class).list()).containsExactly("PREPARED","DELIVERED");
    policyRecovery().beginDrain(epoch());policyRecovery().freeze(epoch());
    assertThat(db.sql("select outcome from ouf_onboarding.storage_recovery_action_audit order by occurred_at").query(String.class).list()).containsExactly("DRAINED","FROZEN");
  }
  @Test void explicitRevocationBlocksLateAckAndOldDelivery(){
    var p=operator();var d=delivered(p,key);store.loseReply=true;assertThatThrownBy(this::upload).hasMessageContaining("reconciliation");
    long e=epoch();policyRecovery().beginDrain(e);UUID id=(UUID)intent().get("intent_id");
    assertThatThrownBy(()->policyRecovery().reconcile(id,e,changedDuringGet(()->assertThat(authority().revoke(p,d.id(),1)).isEqualTo(2)))).hasMessageContaining("No delivered");
    assertThat(assets()).isZero();assertThat(intent()).containsEntry("reconciliation_reads",1);
    assertThat(authority().revoke(p,d.id(),1)).isEqualTo(2);
    assertThatThrownBy(()->authority().deliver(d.id(),2)).hasMessageContaining("revoked");
  }
  @Test void activePolicyRevocationDuringGetBlocksPublication(){
    var p=operator();delivered(p,key);store.loseReply=true;assertThatThrownBy(this::upload).hasMessageContaining("reconciliation");
    long e=epoch();policyRecovery().beginDrain(e);UUID id=(UUID)intent().get("intent_id");
    assertThatThrownBy(()->policyRecovery().reconcile(id,e,changedDuringGet(()->recoveryPolicy(p,key,2,false)))).hasMessageContaining("policy generation changed");
    assertThat(assets()).isZero();assertThat(intent()).containsEntry("state","DISPATCHED").containsEntry("reconciliation_reads",1);
  }
  @Test void policyChangeBeforeDeliveryCannotPublishPreparedDecision(){
    var p=operator();recoveryPolicy(p,key,1,true);var d=authority().prepare(p,request(key));recoveryPolicy(p,key,2,false);
    assertThatThrownBy(()->authority().deliver(d.id(),0)).hasMessageContaining("policy generation changed");
    assertThat(db.sql("select version from ouf_onboarding.storage_recovery_authority_projection").query(Long.class).single()).isZero();
  }
  @Test void concurrentPreparationHasOneDurableCommand()throws Exception{
    var p=operator();recoveryPolicy(p,key,1,true);var request=request(key);var pool=Executors.newFixedThreadPool(2);
    try{var a=pool.submit(()->authority().prepare(p,request));var b=pool.submit(()->authority().prepare(p,request));assertThat(a.get(10,TimeUnit.SECONDS)).isEqualTo(b.get(10,TimeUnit.SECONDS));}
    finally{pool.shutdownNow();}
    assertThat(db.sql("select count(*) from ouf_authorization.storage_recovery_decision").query(Long.class).single()).isOne();
  }
  @Test void staleProjectionCasAndSupersededDecisionCannotOverwrite(){
    var p=operator();var first=delivered(p,key);var second=authority().prepare(p,request(key+"-next"));
    assertThatThrownBy(()->authority().deliver(second.id(),0)).hasMessageContaining("Stale projection");
    assertThat(authority().deliver(second.id(),1)).isEqualTo(2);
    assertThatThrownBy(()->authority().deliver(first.id(),2)).hasMessageContaining("revoked");
    assertThat(db.sql("select decision_id from ouf_onboarding.storage_recovery_authority_projection").query(UUID.class).single()).isEqualTo(second.id());
  }
  @Test void decisionBindingAndHistoryCannotBeEditedOrReopened(){
    var p=operator();var d=delivered(p,key);authority().revoke(p,d.id(),1);
    for(String statement:List.of("update ouf_authorization.storage_recovery_decision set state='PREPARED'","update ouf_authorization.storage_recovery_decision set expires_at=expires_at+interval '1 hour'","delete from ouf_authorization.storage_recovery_decision","update ouf_onboarding.storage_recovery_authority_projection set version=0"))
      assertThatThrownBy(()->db.sql(statement).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
  }
  @Test void policyActivationConflictsWithGateUntilOwnerTransactionCompletes()throws Exception{
    var p=operator();delivered(p,key);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
    try{
      var held=pool.submit(()->tx.executeWithoutResult(s->{authority().lockCurrent(runtime.sessionId(),epoch(),"DRAIN");entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("fixture timeout");}catch(InterruptedException ex){throw new RuntimeException(ex);}}));
      assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(()->tx.executeWithoutResult(s->{db.sql("set local lock_timeout='500ms'").update();policyRegistry().activate(key,1);})).hasMessageContaining("lock timeout");
      release.countDown();held.get(10,TimeUnit.SECONDS);
    }finally{release.countDown();pool.shutdownNow();}
  }
  @Test void noCustodyVerifierCannotFreezeEvenWithRealPolicyDecision(){
    var p=operator();delivered(p,key);
    var guarded=new ManagedFileStorageRecovery(db,tx,owner(),HASHES,new PolicyStorageRecoveryGate(db,authority(),runtime,null),runtime);
    guarded.beginDrain(epoch());assertThatThrownBy(()->guarded.freeze(epoch())).hasMessageContaining("quiescence");
  }
  @Test void sameCapabilityNameCannotBorrowAWeakerDescriptor(){
    var p=operator();var weak=new CapabilityDescriptor(StorageRecoveryAuthorization.CAPABILITY.capabilityId(),"EXECUTE","ouf.managed-source.file.profile",Set.of(PrincipalContext.ActorType.HUMAN));
    tx.executeWithoutResult(s->policyRegistry().publishAndActivate(new PolicyBundle(key,1,Instant.now(),List.of(weak),List.of()),"lab-operator"));
    assertThatThrownBy(()->authority().prepare(p,request(key))).hasMessageContaining("semantics differ");
  }
  @Test void concurrentDeliveriesHaveOneProjectionWinner()throws Exception{
    var p=operator();recoveryPolicy(p,key,1,true);var a=authority().prepare(p,request(key));var b=authority().prepare(p,request(key+"-other"));var pool=Executors.newFixedThreadPool(2);
    java.util.function.Function<UUID,Boolean> deliver=id->{try{assertThat(authority().deliver(id,0)).isOne();return true;}catch(IllegalStateException ex){assertThat(ex).hasMessageContaining("Stale projection");return false;}};
    try{var one=pool.submit(()->deliver.apply(a.id()));var two=pool.submit(()->deliver.apply(b.id()));assertThat(List.of(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);}
    finally{pool.shutdownNow();}
    assertThat(db.sql("select version from ouf_onboarding.storage_recovery_authority_projection").query(Long.class).single()).isOne();
    assertThat(db.sql("select count(*) from ouf_authorization.storage_recovery_delivery_audit where event='DELIVERED'").query(Long.class).single()).isOne();
  }
  @Test void replacementPolicyDecisionDoesNotResetIntentBudget(){
    var p=operator();delivered(p,key);store.loseReply=true;assertThatThrownBy(this::upload).hasMessageContaining("reconciliation");
    long e=epoch();policyRecovery().beginDrain(e);db.sql("update ouf_onboarding.managed_file_upload_intent set reconciliation_reads=128").update();
    var next=authority().prepare(p,request(key+"-replacement"));assertThat(authority().deliver(next.id(),1)).isEqualTo(2);
    assertThatThrownBy(()->policyRecovery().reconcile((UUID)intent().get("intent_id"),e,store)).hasMessageContaining("budget exhausted");
    assertThat(intent()).containsEntry("reconciliation_reads",128);assertThat(store.gets).hasValue(0);assertThat(assets()).isZero();
  }
  @Test void currentFenceIsMandatoryEvenWithAllowedLocalPolicy(){
    var p=operator();recoveryPolicy(p,key,1,true);
    var unbound=new StorageRecoveryAuthorization(db,tx,JSON,policyRegistry(),HASHES,"lab-installation","platform-tenant");
    assertThatThrownBy(()->unbound.prepare(p,request(key))).hasMessageContaining("fence unavailable");
    assertThat(db.sql("select count(*) from ouf_authorization.storage_recovery_decision").query(Long.class).single()).isZero();
  }
  @Test void revokedOrAbsentFenceBlocksFreshPrepareAndRedelivery(){
    var p=operator();var d=delivered(p,key);
    for(StorageRecoveryAuthorityFence fence:List.<StorageRecoveryAuthorityFence>of(()->null,()->fenceProof(2,true))){
      assertThatThrownBy(()->authority(fence).prepare(p,request(key+"-fresh"))).hasMessageContaining("Current platform authority");
      assertThatThrownBy(()->authority(fence).deliver(d.id(),1)).hasMessageContaining("Current platform authority");
    }
    assertThat(db.sql("select version from ouf_onboarding.storage_recovery_authority_projection").query(Long.class).single()).isOne();
  }
  @Test void fenceBindingsAndWindowCannotBeBorrowed(){
    var p=operator();recoveryPolicy(p,key,1,true);var good=fenceProof(1,false);
    var wrong=List.of(
      new StorageRecoveryAuthorityFence.Proof("other",good.tenant(),key,1,1,good.issuedAt(),good.expiresAt(),false),
      new StorageRecoveryAuthorityFence.Proof(good.installation(),"other",key,1,1,good.issuedAt(),good.expiresAt(),false),
      new StorageRecoveryAuthorityFence.Proof(good.installation(),good.tenant(),key,2,1,good.issuedAt(),good.expiresAt(),false),
      new StorageRecoveryAuthorityFence.Proof(good.installation(),good.tenant(),key,1,0,good.issuedAt(),good.expiresAt(),false),
      new StorageRecoveryAuthorityFence.Proof(good.installation(),good.tenant(),key,1,1,Instant.now().minusSeconds(20),Instant.now().minusSeconds(1),false));
    for(var proof:wrong)assertThatThrownBy(()->authority(()->proof).prepare(p,request(key))).hasMessageContaining("Current platform authority");
    var shortProof=new StorageRecoveryAuthorityFence.Proof(good.installation(),good.tenant(),key,1,1,good.issuedAt(),Instant.now().plusSeconds(60),false);
    assertThatThrownBy(()->authority(()->shortProof).prepare(p,request(key))).hasMessageContaining("deadline");
  }
  @Test void independentGenerationCannotReuseOldDecisionWithSamePolicy(){
    var p=operator();var d=delivered(p,key);var renewed=authority(()->fenceProof(2,false));
    assertThatThrownBy(()->renewed.deliver(d.id(),1)).hasMessageContaining("authority generation differs");
    assertThatThrownBy(()->policyRecovery(renewed).beginDrain(epoch())).hasMessageContaining("authority generation differs");
    var next=renewed.prepare(p,request(key+"-fresh"));assertThat(next.authorityGeneration()).isEqualTo(2);
    assertThat(renewed.deliver(next.id(),1)).isEqualTo(2);
    assertThatThrownBy(()->db.sql("update ouf_authorization.storage_recovery_decision set authority_generation=3").update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
  }
  @Test void independentRevocationDuringGetBlocksLateOwnerPublication(){
    var p=operator();delivered(p,key);var revoked=new java.util.concurrent.atomic.AtomicBoolean();
    var guarded=policyRecovery(authority(()->fenceProof(1,revoked.get())));
    store.loseReply=true;assertThatThrownBy(this::upload).hasMessageContaining("reconciliation");
    long e=epoch();guarded.beginDrain(e);UUID id=(UUID)intent().get("intent_id");
    assertThatThrownBy(()->guarded.reconcile(id,e,changedDuringGet(()->revoked.set(true)))).hasMessageContaining("Current platform authority revoked");
    assertThat(assets()).isZero();assertThat(intent()).containsEntry("state","DISPATCHED").containsEntry("reconciliation_reads",1);
  }
  @Test void independentFenceIsRecheckedAfterOwnerAuditBeforeCommit(){
    var p=operator();delivered(p,key);var calls=new AtomicInteger();
    var guarded=policyRecovery(authority(()->fenceProof(1,calls.incrementAndGet()>1)));
    assertThatThrownBy(()->guarded.beginDrain(epoch())).hasMessageContaining("Current platform authority revoked");
    assertThat(db.sql("select phase from ouf_onboarding.managed_file_storage_control").query(String.class).single()).isEqualTo("ACTIVE");
    assertThat(db.sql("select count(*) from ouf_onboarding.storage_recovery_action_audit").query(Long.class).single()).isZero();
  }
  PlatformStorageRecoveryAuthorityFence platformFence(PlatformStorageRecoveryAuthorityFence.CurrentAuthority current){
    return new PlatformStorageRecoveryAuthorityFence(new PlatformStorageRecoveryAuthorityFence.Binding("lab-installation","platform-tenant",1),current);
  }
  @Test void platformFenceRequiresTransactionAndAvailableLease(){
    var missing=platformFence(binding->null);
    assertThatThrownBy(missing::lockCurrent).hasMessageContaining("requires owner transaction");
    assertThatThrownBy(()->tx.execute(s->missing.lockCurrent())).hasMessageContaining("unavailable or contended");
    assertThat(TransactionSynchronizationManager.hasResource(missing)).isFalse();
  }
  @Test void platformLeaseIsReusedAndHeldThroughActualCommit(){
    var p=operator();delivered(p,key);var platform=new PlatformRecoveryAuthorityLab(fenceProof(1,false));var fence=platformFence(platform);
    tx.executeWithoutResult(s->{
      assertThat(fence.lockCurrent()).isSameAs(fence.lockCurrent());assertThat(platform.acquired).hasValue(1);
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
        @Override public void afterCommit(){assertThat(platform.held()).isTrue();assertThat(platform.closed).hasValue(0);}
      });
    });
    assertThat(platform.closed).hasValue(1);assertThat(platform.held()).isFalse();assertThat(TransactionSynchronizationManager.hasResource(fence)).isFalse();
    policyRecovery(authority(fence)).beginDrain(epoch());assertThat(platform.acquired).hasValue(2);assertThat(platform.closed).hasValue(2);
  }
  @Test void platformLeaseIsReleasedOnOwnerRollback(){
    var p=operator();delivered(p,key);var platform=new PlatformRecoveryAuthorityLab(fenceProof(1,false));var fence=platformFence(platform);
    assertThatThrownBy(()->tx.executeWithoutResult(s->{authority(fence).lockCurrent(runtime.sessionId(),epoch(),"DRAIN");throw new IllegalStateException("fixture rollback");})).hasMessageContaining("fixture rollback");
    assertThat(platform.closed).hasValue(1);assertThat(platform.held()).isFalse();assertThat(TransactionSynchronizationManager.hasResource(fence)).isFalse();
    policyRecovery(authority(fence)).beginDrain(epoch());assertThat(platform.closed).hasValue(2);
  }
  @Test void mismatchedPlatformAdmissionClosesLeaseWithoutAuthority(){
    var p=operator();recoveryPolicy(p,key,1,true);
    for(var proof:List.of(fenceProof(2,false),fenceProof(1,true),new StorageRecoveryAuthorityFence.Proof("other","platform-tenant",key,1,1,Instant.now().minusSeconds(1),Instant.now().plusSeconds(600),false))){
      var platform=new PlatformRecoveryAuthorityLab(proof);var fence=platformFence(platform);
      assertThatThrownBy(()->authority(fence).prepare(p,request(key))).hasMessageContaining("admission generation revoked or differs");
      assertThat(platform.closed).hasValue(1);assertThat(platform.held()).isFalse();assertThat(TransactionSynchronizationManager.hasResource(fence)).isFalse();
    }
  }
  @Test void concurrentPlatformRevocationCannotCrossOwnerCommit()throws Exception{
    var p=operator();delivered(p,key);var platform=new PlatformRecoveryAuthorityLab(fenceProof(1,false));var fence=platformFence(platform);
    var next=fenceProof(2,true);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
    try{
      var held=pool.submit(()->tx.executeWithoutResult(s->{authority(fence).lockCurrent(runtime.sessionId(),epoch(),"DRAIN");entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("fixture timeout");}catch(InterruptedException e){throw new RuntimeException(e);}}));
      assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();assertThat(platform.advance(next)).isFalse();
      release.countDown();held.get(10,TimeUnit.SECONDS);assertThat(platform.advance(next)).isTrue();
      assertThatThrownBy(()->policyRecovery(authority(fence)).beginDrain(epoch())).hasMessageContaining("admission generation revoked or differs");
      assertThat(db.sql("select phase from ouf_onboarding.managed_file_storage_control").query(String.class).single()).isEqualTo("ACTIVE");
    }finally{release.countDown();pool.shutdownNow();}
  }
  @Test void platformLeaseSuspendsAndResumesWithNestedIndependentTransaction(){
    var p=operator();recoveryPolicy(p,key,1,true);var platform=new PlatformRecoveryAuthorityLab(fenceProof(1,false));var fence=platformFence(platform);
    var nested=new TransactionTemplate(tx.getTransactionManager());nested.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    tx.executeWithoutResult(s->{
      var outer=fence.lockCurrent();nested.executeWithoutResult(inner->{fence.lockCurrent();assertThat(platform.acquired).hasValue(2);});
      assertThat(platform.closed).hasValue(1);assertThat(fence.lockCurrent()).isSameAs(outer);assertThat(platform.held()).isTrue();
    });
    assertThat(platform.closed).hasValue(2);assertThat(platform.held()).isFalse();assertThat(TransactionSynchronizationManager.hasResource(fence)).isFalse();
  }
  // Privileged fixture-only logical replay, with normal INSERT/immutability guards left enabled.
  // Scope is Authorization tables + delivery/action projection, not whole-owner or cluster restore.
  static final List<String> LOGICAL_TABLES=List.of("ouf_authorization.policy_bundle","ouf_authorization.active_policy_bundle","ouf_authorization.authorization_decision_audit","ouf_authorization.storage_recovery_decision","ouf_onboarding.storage_recovery_authority_projection","ouf_authorization.storage_recovery_delivery_audit","ouf_onboarding.storage_recovery_action_audit");
  Map<String,String> logicalSnapshot(){
    var snapshot=new LinkedHashMap<String,String>();
    for(String table:LOGICAL_TABLES)snapshot.put(table,db.sql("select coalesce(jsonb_agg(to_jsonb(r) order by to_jsonb(r)::text),'[]'::jsonb)::text from "+table+" r").query(String.class).single());
    return snapshot;
  }
  void restoreLogical(Map<String,String> snapshot){
    tx.executeWithoutResult(s->{
      db.sql("truncate "+String.join(",",LOGICAL_TABLES)+" cascade").update();
      for(String table:LOGICAL_TABLES)db.sql("insert into "+table+" select * from jsonb_populate_recordset(null::"+table+",cast(:rows as jsonb))").param("rows",snapshot.get(table)).update();
    });
  }
  void logicalRollbackWithLiveWorker(boolean revoked)throws Exception{
    var p=operator();recoveryPolicy(p,key,1,true);var platform=new PlatformRecoveryAuthorityLab(fenceProof(1,false));var fence=platformFence(platform);var guarded=authority(fence);
    var decision=guarded.prepare(p,request(key));guarded.deliver(decision.id(),0);
    store.loseReply=true;assertThatThrownBy(this::upload).hasMessageContaining("reconciliation");var owner=policyRecovery(guarded);long e=epoch();owner.beginDrain(e);
    db.sql("update ouf_onboarding.managed_file_upload_intent set reconciliation_reads=128").update();
    var before=logicalSnapshot();var originalIntent=new LinkedHashMap<>(intent());var postmaster=StorageRecoveryRuntime.databaseStartedAt(db);UUID session=runtime.sessionId();
    assertThat(guarded.revoke(p,decision.id(),1)).isEqualTo(2);
    if(revoked)recoveryPolicy(p,key,2,false);
    assertThat(platform.advance(fenceProof(2,revoked))).isTrue();
    restoreLogical(before);assertThat(logicalSnapshot()).isEqualTo(before);
    assertThat(StorageRecoveryRuntime.databaseStartedAt(db)).isEqualTo(postmaster);assertThat(runtime.sessionId()).isEqualTo(session);
    assertThat(policyRegistry().active().orElseThrow().version()).isOne();
    assertThat(db.sql("select state from ouf_authorization.storage_recovery_decision").query(String.class).single()).isEqualTo("DELIVERED");
    assertThatThrownBy(()->guarded.prepare(p,request(key+"-after-rollback"))).hasMessageContaining("admission generation revoked or differs");
    assertThatThrownBy(()->guarded.deliver(decision.id(),1)).hasMessageContaining("admission generation revoked or differs");
    assertThatThrownBy(()->owner.beginDrain(e)).hasMessageContaining("admission generation revoked or differs");
    assertThatThrownBy(()->owner.reconcile((UUID)originalIntent.get("intent_id"),e,store)).hasMessageContaining("admission generation revoked or differs");
    assertThatThrownBy(()->owner.freeze(e)).hasMessageContaining("admission generation revoked or differs");
    assertThat(logicalSnapshot()).isEqualTo(before);assertThat(intent()).isEqualTo(originalIntent);assertThat(store.gets).hasValue(0);assertThat(assets()).isZero();assertThat(platform.held()).isFalse();
    Files.writeString(evidence.resolve("logical-rollback-"+revoked+".json"),JSON.writeValueAsString(Map.of("scope","Authorization tables and owner delivery/action projection only","postmasterBefore",postmaster.toString(),"postmasterAfter",StorageRecoveryRuntime.databaseStartedAt(db).toString(),"runtimeSession",session,"externalGeneration",2,"externalRevoked",revoked,"snapshot",before,"snapshotAfterDeniedAttempts",logicalSnapshot(),"reconciliationReads",intent().get("reconciliation_reads"),"getCalls",store.gets.get()))+"\n");
    System.out.println("LOGICAL_ROLLBACK_PASS "+JSON.writeValueAsString(Map.of("externalRevoked",revoked,"postmasterUnchanged",true,"runtimeUnchanged",true,"restoredSnapshotUnchanged",true,"deniedAttempts",5,"reconciliationReads",128,"getCalls",0,"snapshotSha256",HASHES.of(before))));
  }
  @Test void logicalAuthorizationRollbackCannotUndoIndependentRevocationInLiveWorker()throws Exception{logicalRollbackWithLiveWorker(true);}
  @Test void logicalAuthorizationRollbackCannotReuseLiveWorkerAfterGenerationOnlyChange()throws Exception{logicalRollbackWithLiveWorker(false);}
  Path fileAuthorityDirectory()throws Exception{return Files.createTempDirectory("ouf-authority-",java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));}
  FilePlatformRecoveryAuthority fileAuthority(Path directory,StorageRecoveryAuthorityFence.Proof first){FilePlatformRecoveryAuthority.provision(directory,first);return new FilePlatformRecoveryAuthority(directory);}
  StorageRecoveryAuthorityFence.Proof fileProof(FilePlatformRecoveryAuthority source){
    try(var lease=source.tryAcquire(new PlatformStorageRecoveryAuthorityFence.Binding("lab-installation","platform-tenant",1))){assertThat(lease).isNotNull();return lease.proof();}
  }
  Process fileProcess(Path directory,String action,Path proof,long expected)throws Exception{
    return new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),FilePlatformAuthorityProcess.class.getName(),directory.toString(),action,proof==null?"unused":proof.toString(),Long.toString(expected))
      .redirectErrorStream(true).redirectOutput(evidence.resolve("file-platform-"+key+"-"+UUID.randomUUID()+".log").toFile()).start();
  }
  int fileChild(Path directory,String action,Path proof,long expected){
    try{var child=fileProcess(directory,action,proof,expected);if(!child.waitFor(10,TimeUnit.SECONDS)){child.destroyForcibly();child.waitFor(5,TimeUnit.SECONDS);throw new AssertionError("File authority child deadline");}return child.exitValue();}
    catch(Exception ex){throw new IllegalStateException(ex);}
  }
  Path nextFileProof(Path directory,StorageRecoveryAuthorityFence.Proof proof)throws Exception{Path path=directory.resolve("proposal-"+UUID.randomUUID()+".json");Files.writeString(path,JSON.writeValueAsString(proof));return path;}
  void fileEvidence(String scenario,Map<String,Object> facts)throws Exception{
    var receipt=new TreeMap<String,Object>(facts);receipt.put("scenario",scenario);receipt.put("status","PASS");receipt.put("scope","same-host local POSIX filesystem; synthetic authority; no target");
    Files.writeString(evidence.resolve("file-authority-"+scenario+".json"),JSON.writeValueAsString(receipt)+"\n");System.out.println("FILE_PLATFORM_PASS "+JSON.writeValueAsString(receipt));
  }
  @Test void fileAuthorityPersistsRestartAndReconcilesLostPublicationWithoutRenewal()throws Exception{
    var p=operator();recoveryPolicy(p,key,1,true);Path dir=fileAuthorityDirectory();var first=fenceProof(1,false);fileAuthority(dir,first);
    assertThat(fileChild(dir,"read",null,0)).isZero();var next=fenceProof(2,true);var proposal=nextFileProof(dir,next);
    assertThat(fileChild(dir,"publish",proposal,1)).isZero();var reopened=new FilePlatformRecoveryAuthority(dir);assertThat(fileProof(reopened)).isEqualTo(next);
    assertThat(reopened.publish(1,next)).isEqualTo(2);assertThat(fileProof(new FilePlatformRecoveryAuthority(dir)).expiresAt()).isEqualTo(next.expiresAt());
    assertThatThrownBy(()->reopened.publish(1,fenceProof(2,false))).hasMessageContaining("Stale authority generation");
    fileEvidence("restart-idempotency",Map.of("generation",2,"revoked",true,"sameProofAfterRestart",true,"deadlineUnchanged",true,"stalePublicationRejected",true,"filesystem",Files.getFileStore(dir).type()));
  }
  @Test void fileAuthorityRevocationInOtherJvmCannotCrossActualOwnerCommit()throws Exception{
    var p=operator();recoveryPolicy(p,key,1,true);Path dir=fileAuthorityDirectory();var source=fileAuthority(dir,fenceProof(1,false));var guarded=authority(platformFence(source));
    var d=guarded.prepare(p,request(key));guarded.deliver(d.id(),0);Path proposal=nextFileProof(dir,fenceProof(2,true));
    var delegate=new PolicyStorageRecoveryGate(db,guarded,runtime,null);var rejected=new AtomicInteger();
    var gate=new StorageRecoveryGate(){
      public Evidence lock(Action a,long e,UUID id){return delegate.lock(a,e,id);}
      public void record(Evidence proof,Action a,UUID id,String outcome){delegate.record(proof,a,id,outcome);assertThat(fileChild(dir,"publish",proposal,1)).isEqualTo(3);rejected.incrementAndGet();}
    };
    new ManagedFileStorageRecovery(db,tx,owner(),HASHES,gate,runtime).beginDrain(epoch());assertThat(rejected).hasValue(1);
    assertThat(db.sql("select phase from ouf_onboarding.managed_file_storage_control").query(String.class).single()).isEqualTo("DRAINING");
    assertThat(fileChild(dir,"publish",proposal,1)).isZero();assertThatThrownBy(()->policyRecovery(guarded).freeze(epoch())).hasMessageContaining("admission generation revoked or differs");
    fileEvidence("cross-process-commit",Map.of("blockedDuringOwnerCommit",true,"publishedAfterCommit",true,"generation",2,"revoked",true));
  }
  @Test void killedFileLeaseHolderReleasesOsLockWithoutRegranting()throws Exception{
    var p=operator();recoveryPolicy(p,key,1,true);Path dir=fileAuthorityDirectory();var source=fileAuthority(dir,fenceProof(1,false));var guarded=authority(platformFence(source));
    var d=guarded.prepare(p,request(key));guarded.deliver(d.id(),0);var child=fileProcess(dir,"hold",null,0);
    try{
      long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!Files.exists(dir.resolve("child-ready"))&&child.isAlive()&&System.nanoTime()<until)Thread.sleep(20);
      assertThat(Files.exists(dir.resolve("child-ready"))).isTrue();assertThat(child.isAlive()).isTrue();
      assertThatThrownBy(()->policyRecovery(guarded).beginDrain(epoch())).hasMessageContaining("unavailable or contended");
      child.destroyForcibly();assertThat(child.waitFor(5,TimeUnit.SECONDS)).isTrue();
      policyRecovery(guarded).beginDrain(epoch());assertThat(fileProof(new FilePlatformRecoveryAuthority(dir)).generation()).isOne();
      fileEvidence("killed-holder",Map.of("ownerDeniedWhileHeld",true,"holderKilled",true,"lockReleased",true,"generationUnchanged",true,"existingAuthorityOnly",true));
    }finally{if(child.isAlive()){child.destroyForcibly();child.waitFor(5,TimeUnit.SECONDS);}}
  }
  @Test void fileRevocationSurvivesPublisherCrashAndLogicalAuthorizationReplay()throws Exception{
    var p=operator();recoveryPolicy(p,key,1,true);Path dir=fileAuthorityDirectory();var source=fileAuthority(dir,fenceProof(1,false));var guarded=authority(platformFence(source));
    var d=guarded.prepare(p,request(key));guarded.deliver(d.id(),0);var before=logicalSnapshot();var postmaster=StorageRecoveryRuntime.databaseStartedAt(db);
    guarded.revoke(p,d.id(),1);var next=fenceProof(2,true);var proposal=nextFileProof(dir,next);
    assertThat(fileChild(dir,"publish-halt",proposal,1)).isEqualTo(86);var reopened=new FilePlatformRecoveryAuthority(dir);assertThat(fileProof(reopened)).isEqualTo(next);assertThat(reopened.publish(1,next)).isEqualTo(2);
    restoreLogical(before);assertThat(StorageRecoveryRuntime.databaseStartedAt(db)).isEqualTo(postmaster);var restored=authority(platformFence(reopened));
    assertThatThrownBy(()->restored.prepare(p,request(key+"-after"))).hasMessageContaining("admission generation revoked or differs");
    assertThatThrownBy(()->restored.deliver(d.id(),1)).hasMessageContaining("admission generation revoked or differs");
    assertThatThrownBy(()->policyRecovery(restored).beginDrain(epoch())).hasMessageContaining("admission generation revoked or differs");assertThat(logicalSnapshot()).isEqualTo(before);
    fileEvidence("crash-and-logical-replay",Map.of("publisherExit",86,"generation",2,"revoked",true,"exactPublicationReconciled",true,"samePostmaster",true,"restoredSnapshotUnchanged",true,"prepareDeliveryOwnerRejected",true));
  }
  @Test void twoFilePublishersHaveOneExpectedGenerationWinner()throws Exception{
    var p=operator();recoveryPolicy(p,key,1,true);Path dir=fileAuthorityDirectory();fileAuthority(dir,fenceProof(1,false));
    var a=fileProcess(dir,"publish",nextFileProof(dir,fenceProof(2,true)),1);var b=fileProcess(dir,"publish",nextFileProof(dir,fenceProof(2,false)),1);
    try{assertThat(a.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(b.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(List.of(a.exitValue(),b.exitValue())).containsExactlyInAnyOrder(0,3);}
    finally{if(a.isAlive())a.destroyForcibly();if(b.isAlive())b.destroyForcibly();}
    assertThat(fileProof(new FilePlatformRecoveryAuthority(dir)).generation()).isEqualTo(2);fileEvidence("concurrent-publication",Map.of("successes",1,"denials",1,"generation",2));
  }
  @Test void fileAuthorityRejectsCorruptionOversizeMissingStateAndSymlink()throws Exception{
    var p=operator();recoveryPolicy(p,key,1,true);Path dir=fileAuthorityDirectory();var source=fileAuthority(dir,fenceProof(1,false));Path state=dir.resolve("current.json");byte[] original=Files.readAllBytes(state);
    for(String bad:List.of("{}","x".repeat(4097),new String(original,java.nio.charset.StandardCharsets.UTF_8).replace("\"generation\":1","\"generation\":2"))){
      Files.writeString(state,bad);assertThatThrownBy(()->fileProof(source)).isInstanceOf(IllegalStateException.class);
    }
    Files.write(state,original);Path saved=dir.resolve("saved.json");Files.move(state,saved);
    assertThatThrownBy(()->fileProof(source)).hasMessageContaining("state unavailable");assertThatThrownBy(()->source.publish(1,fenceProof(2,true))).hasMessageContaining("state unavailable");
    assertThatThrownBy(()->FilePlatformRecoveryAuthority.provision(dir,fenceProof(1,false))).hasMessageContaining("empty directory");
    Files.createSymbolicLink(state,saved);assertThatThrownBy(()->fileProof(source)).hasMessageContaining("regular authority file");Files.delete(state);Files.move(saved,state);
    assertThat(fileProof(source).generation()).isOne();fileEvidence("invalid-state",Map.of("corruptionRejected",true,"oversizeRejected",true,"missingStateRejected",true,"automaticReprovisionRejected",true,"symlinkRejected",true));
  }
  @Test void fileAuthorityIgnoresUncommittedPendingAndRejectsReplacedGuard()throws Exception{
    var p=operator();recoveryPolicy(p,key,1,true);Path dir=fileAuthorityDirectory();var source=fileAuthority(dir,fenceProof(1,false));
    Files.writeString(dir.resolve("current.pending"),"incomplete");Files.setPosixFilePermissions(dir.resolve("current.pending"),java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    assertThat(fileProof(new FilePlatformRecoveryAuthority(dir)).generation()).isOne();source.publish(1,fenceProof(2,true));assertThat(Files.exists(dir.resolve("current.pending"))).isFalse();
    Files.move(dir.resolve("authority.lock"),dir.resolve("old.lock"));Files.createFile(dir.resolve("authority.lock"),java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
    assertThatThrownBy(()->fileProof(source)).hasMessageContaining("lock unavailable");fileEvidence("pending-and-guard",Map.of("pendingNeverUsedAsAuthority",true,"replacementPublished",true,"staleGuardRejected",true));
  }
  @Test void fileAuthorityRejectsExpiryAmbientPublicationAndInsecurePermissions()throws Exception{
    var p=operator();recoveryPolicy(p,key,1,true);Path dir=fileAuthorityDirectory();var first=fenceProof(1,false);var source=fileAuthority(dir,first);
    assertThatThrownBy(()->tx.executeWithoutResult(s->source.publish(1,fenceProof(2,true)))).hasMessageContaining("no owner transaction");
    var expired=new StorageRecoveryAuthorityFence.Proof(first.installation(),first.tenant(),first.bundleId(),first.bundleVersion(),2,Instant.now().minusSeconds(20),Instant.now().minusSeconds(1),true);
    assertThatThrownBy(()->source.publish(1,expired)).hasMessageContaining("expired or future");assertThat(fileProof(source)).isEqualTo(first);
    Files.setPosixFilePermissions(dir.resolve("current.json"),java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw-rw-"));assertThatThrownBy(()->fileProof(source)).hasMessageContaining("regular authority file");
    fileEvidence("invalid-publication",Map.of("expiredRejected",true,"ambientTransactionRejected",true,"insecurePermissionsRejected",true,"generationUnchanged",true));
  }
}
