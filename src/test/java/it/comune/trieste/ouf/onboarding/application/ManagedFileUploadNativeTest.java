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
import static it.comune.trieste.ouf.onboarding.installation.PlatformAuthorityReconciliation.*;
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
    assertThat(db.sql("select count(*) from ouf_onboa…7672 tokens truncated…Zero();
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
  @Test void killedFileLeaseHolderLeavesDurableUnresolvedLatch()throws Exception{
    var p=operator();recoveryPolicy(p,key,1,true);Path dir=fileAuthorityDirectory();var source=fileAuthority(dir,fenceProof(1,false));var guarded=authority(platformFence(source));
    var d=guarded.prepare(p,request(key));guarded.deliver(d.id(),0);var child=fileProcess(dir,"hold",null,0);
    try{
      long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!Files.exists(dir.resolve("child-ready"))&&child.isAlive()&&System.nanoTime()<until)Thread.sleep(20);
      assertThat(Files.exists(dir.resolve("child-ready"))).isTrue();assertThat(child.isAlive()).isTrue();
      assertThatThrownBy(()->policyRecovery(guarded).beginDrain(epoch())).hasMessageContaining("unavailable or contended");
      child.destroyForcibly();assertThat(child.waitFor(5,TimeUnit.SECONDS)).isTrue();
      assertThat(Files.exists(dir.resolve("lease.active"))).isTrue();
      assertThatThrownBy(()->policyRecovery(guarded).beginDrain(epoch())).hasMessageContaining("Unresolved prior authority lease");
      assertThatThrownBy(()->fileProof(new FilePlatformRecoveryAuthority(dir))).hasMessageContaining("Unresolved prior authority lease");
      assertThatThrownBy(()->new FilePlatformRecoveryAuthority(dir).publish(1,fenceProof(2,true))).hasMessageContaining("Unresolved prior authority lease");
      assertThat(db.sql("select phase from ouf_onboarding.managed_file_storage_control").query(String.class).single()).isEqualTo("ACTIVE");
      fileEvidence("killed-holder",Map.of("ownerDeniedWhileHeld",true,"holderKilled",true,"durableLatchPresent",true,"ownerDeniedAfterDeath",true,"publisherDeniedAfterRestart",true,"noAutomaticRegrant",true));
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
    assertThatThrownBy(()->fileProof(source)).hasMessageContaining("guard replaced");fileEvidence("pending-and-guard",Map.of("pendingNeverUsedAsAuthority",true,"replacementPublished",true,"staleGuardRejected",true));
  }
  @Test void fileAuthorityRejectsExpiryAmbientPublicationAndInsecurePermissions()throws Exception{
    var p=operator();recoveryPolicy(p,key,1,true);Path dir=fileAuthorityDirectory();var first=fenceProof(1,false);var source=fileAuthority(dir,first);
    assertThatThrownBy(()->tx.executeWithoutResult(s->source.publish(1,fenceProof(2,true)))).hasMessageContaining("no owner transaction");
    var expired=new StorageRecoveryAuthorityFence.Proof(first.installation(),first.tenant(),first.bundleId(),first.bundleVersion(),2,Instant.now().minusSeconds(20),Instant.now().minusSeconds(1),true);
    assertThatThrownBy(()->source.publish(1,expired)).hasMessageContaining("expired or future");assertThat(fileProof(source)).isEqualTo(first);
    Files.setPosixFilePermissions(dir.resolve("current.json"),java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw-rw-"));assertThatThrownBy(()->fileProof(source)).hasMessageContaining("regular authority file");
    fileEvidence("invalid-publication",Map.of("expiredRejected",true,"ambientTransactionRejected",true,"insecurePermissionsRejected",true,"generationUnchanged",true));
  }
  @Test void unknownTransactionCompletionKeepsDurableAuthorityLatch()throws Exception{
    var p=operator();recoveryPolicy(p,key,1,true);Path dir=fileAuthorityDirectory();var source=fileAuthority(dir,fenceProof(1,false));var fence=platformFence(source);
    tx.executeWithoutResult(s->{
      var previous=TransactionSynchronizationManager.getSynchronizations();fence.lockCurrent();
      // Fault injection of Spring's uncertain completion notification; no real DB failure claimed.
      var added=new ArrayList<>(TransactionSynchronizationManager.getSynchronizations());added.removeAll(previous);assertThat(added).hasSize(1);
      added.getFirst().afterCompletion(TransactionSynchronization.STATUS_UNKNOWN);
    });
    assertThat(Files.exists(dir.resolve("lease.active"))).isTrue();
    assertThatThrownBy(()->fileProof(new FilePlatformRecoveryAuthority(dir))).hasMessageContaining("Unresolved prior authority lease");
    fileEvidence("unknown-completion",Map.of("unknownCompletionInjected",true,"durableLatchPresent",true,"restartStillDenied",true,"automaticClearAbsent",true));
  }
  PlatformStorageRecoveryAuthorityFence.Binding fileBinding(long generation){return new PlatformStorageRecoveryAuthorityFence.Binding("lab-installation","platform-tenant",generation);}
  void unresolvedRollback(FilePlatformRecoveryAuthority source){
    var fence=platformFence(source);
    tx.executeWithoutResult(s->{
      db.sql("select phase from ouf_onboarding.managed_file_storage_control where singleton=true for update").query(String.class).single();
      var before=TransactionSynchronizationManager.getSynchronizations();fence.lockCurrent();
      db.sql("update ouf_onboarding.managed_file_storage_control set phase='DRAINING'").update();
      var added=new ArrayList<>(TransactionSynchronizationManager.getSynchronizations());added.removeAll(before);
      assertThat(added).hasSize(1);added.getFirst().afterCompletion(TransactionSynchronization.STATUS_UNKNOWN);s.setRollbackOnly();
    });
    assertThat(db.sql("select phase from ouf_onboarding.managed_file_storage_control").query(String.class).single()).isEqualTo("ACTIVE");
  }
  Request resolution(FilePlatformRecoveryAuthority source){
    var o=source.inspect(fileBinding(1));assertThat(o.state()).isEqualTo(State.RECONCILE_REQUIRED);
    return new Request(UUID.randomUUID(),o.leaseId(),o.leaseSha256(),1,"lab/operator","OWNER_OUTCOME_VERIFIED","lab/decision/"+key,fenceProof(2,true));
  }
  Path resolutionFile(Path dir,Request r)throws Exception{Path p=dir.resolve("request-"+r.commandId()+".json");Files.writeString(p,JSON.writeValueAsString(r));return p;}
  Gate constantEvidence(Evidence proof){return request->new Lease(){public Evidence current(){return proof;}public void close(){}};}
  Evidence resolutionEvidence(Request r){return new Evidence(FilePlatformRecoveryAuthority.reconciliationFingerprint(r),"lab/database","lab/outcome",Outcome.ROLLED_BACK,Instant.now(),Instant.now().plusSeconds(30));}
  @Test void authorityObservationIsReadOnlyBoundedAndNeverTreatsContentionAsReady()throws Exception{
    var p=operator();recoveryPolicy(p,key,1,true);var dir=fileAuthorityDirectory();var source=fileAuthority(dir,fenceProof(1,false));
    byte[] original=Files.readAllBytes(dir.resolve("current.json"));
    assertThat(source.inspect(fileBinding(1)).state()).isEqualTo(State.CURRENT);assertThat(Files.exists(dir.resolve("lease.active"))).isFalse();
    var live=source.tryAcquire(fileBinding(1));
    assertThat(source.inspect(fileBinding(1))).isEqualTo(new Observation(State.CONTENDED,0,null,null));live.abandon();
    var blocked=source.inspect(fileBinding(1));assertThat(blocked.state()).isEqualTo(State.RECONCILE_REQUIRED);
    assertThat(new FilePlatformRecoveryAuthority(dir).inspect(fileBinding(1))).isEqualTo(blocked);
    assertThat(source.inspect(new PlatformStorageRecoveryAuthorityFence.Binding("other","platform-tenant",1))).isEqualTo(new Observation(State.FENCED,0,null,null));
    assertThat(fileChild(dir,"inspect",null,0)).isZero();assertThat(Files.readAllBytes(dir.resolve("current.json"))).isEqualTo(original);
    byte[] marker=Files.readAllBytes(dir.resolve("lease.active"));Files.writeString(dir.resolve("lease.active"),"bad");
    assertThat(source.inspect(fileBinding(1))).isEqualTo(new Observation(State.UNAVAILABLE,0,null,null));Files.write(dir.resolve("lease.active"),marker);
    assertThat(source.inspect(fileBinding(1))).isEqualTo(blocked);
    fileEvidence("observation",Map.of("readOnly",true,"contendedNotReady",true,"stableIncidentAcrossRestart",true,"wrongBindingRedacted",true,"malformedUnavailable",true));
  }
  @Test void reconciliationRejectsMissingStaleBorrowedAndUnrevokedEvidence()throws Exception{
    var p=operator();recoveryPolicy(p,key,1,true);var dir=fileAuthorityDirectory();var source=fileAuthority(dir,fenceProof(1,false));unresolvedRollback(source);var r=resolution(source);var good=resolutionEvidence(r);
    byte[] before=Files.readAllBytes(dir.resolve("current.json"));
    assertThatThrownBy(()->source.reconcile(r,null)).hasMessageContaining("gate unavailable");
    assertThatThrownBy(()->source.reconcile(r,x->null)).hasMessageContaining("unresolved or contended");
    var bad=Arrays.asList((Evidence)null,new Evidence("0".repeat(64),good.databaseIdentity(),good.ownerEvidenceRef(),good.outcome(),good.verifiedAt(),good.validUntil()),
      new Evidence(good.requestSha256(),good.databaseIdentity(),good.ownerEvidenceRef(),good.outcome(),Instant.now().minusSeconds(20),Instant.now().minusSeconds(1)),
      new Evidence(good.requestSha256(),good.databaseIdentity(),good.ownerEvidenceRef(),null,good.verifiedAt(),good.validUntil()),
      new Evidence(good.requestSha256(),good.databaseIdentity(),good.ownerEvidenceRef(),good.outcome(),good.verifiedAt(),Instant.now().plusSeconds(360)));
    for(var proof:bad)assertThatThrownBy(()->source.reconcile(r,constantEvidence(proof))).hasMessageContaining("Fresh exact owner");
    var active=new Request(r.commandId(),r.leaseId(),r.leaseSha256(),1,r.actorRef(),r.reasonCode(),r.decisionRef(),fenceProof(2,false));
    assertThatThrownBy(()->source.reconcile(active,constantEvidence(good))).hasMessageContaining("revoked next generation");
    var wrong=new Request(r.commandId(),UUID.randomUUID(),r.leaseSha256(),1,r.actorRef(),r.reasonCode(),r.decisionRef(),r.revokedNext());
    assertThatThrownBy(()->source.reconcile(wrong,constantEvidence(good))).hasMessageContaining("lease differs");
    assertThatThrownBy(()->tx.executeWithoutResult(s->source.reconcile(r,constantEvidence(good)))).hasMessageContaining("no owner transaction");
    assertThat(Files.readAllBytes(dir.resolve("current.json"))).isEqualTo(before);assertThat(source.inspect(fileBinding(1)).state()).isEqualTo(State.RECONCILE_REQUIRED);
    fileEvidence("resolution-denials",Map.of("missingGateDenied",true,"staleOrBorrowedEvidenceDenied",true,"activeGenerationDenied",true,"wrongLeaseDenied",true,"ambientTransactionDenied",true,"stateUnchanged",true));
  }
  @Test void unresolvedOwnerCommitBlocksReconciliationUntilActualPostgresCompletion()throws Exception{
    var p=operator();recoveryPolicy(p,key,1,true);var dir=fileAuthorityDirectory();var source=fileAuthority(dir,fenceProof(1,false));var fence=platformFence(source);var auth=authority(fence);
    var d=auth.prepare(p,request(key));auth.deliver(d.id(),0);
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var delegate=new PolicyStorageRecoveryGate(db,auth,runtime,null);
    var synchronization=new java.util.concurrent.atomic.AtomicReference<TransactionSynchronization>();
    var gate=new StorageRecoveryGate(){
      public Evidence lock(Action a,long e,UUID id){
        var before=TransactionSynchronizationManager.getSynchronizations();var result=delegate.lock(a,e,id);
        var added=new ArrayList<>(TransactionSynchronizationManager.getSynchronizations());added.removeAll(before);assertThat(added).hasSize(1);synchronization.set(added.getFirst());return result;
      }
      public void record(Evidence e,Action a,UUID id,String result){
        delegate.record(e,a,id,result);
        synchronization.get().afterCompletion(TransactionSynchronization.STATUS_UNKNOWN);
        entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("fixture deadline");}catch(InterruptedException ex){throw new IllegalStateException(ex);}
      }
    };
    var owner=new ManagedFileStorageRecovery(db,tx,owner(),HASHES,gate,runtime);var pool=Executors.newSingleThreadExecutor();
    try{
      long e=epoch();var future=pool.submit(()->owner.beginDrain(e));assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();var r=resolution(source);
      assertThatThrownBy(()->source.reconcile(r,FileAuthorityReconciliationLab.gate(Outcome.COMMITTED,"DRAINING"))).hasMessageContaining("unresolved or contended");
      assertThat(source.inspect(fileBinding(1)).state()).isEqualTo(State.RECONCILE_REQUIRED);release.countDown();future.get(5,TimeUnit.SECONDS);
      var receipt=source.reconcile(r,FileAuthorityReconciliationLab.gate(Outcome.COMMITTED,"DRAINING"));assertThat(receipt.evidence().outcome()).isEqualTo(Outcome.COMMITTED);
      assertThat(source.inspect(fileBinding(1)).state()).isEqualTo(State.REVOKED);
      assertThat(db.sql("select outcome from ouf_onboarding.storage_recovery_action_audit").query(String.class).list()).containsExactly("DRAINED");
      assertThatThrownBy(()->policyRecovery(auth).freeze(e)).hasMessageContaining("admission generation revoked or differs");
      fileEvidence("resolution-late-commit",Map.of("unknownInjected",true,"actualPostgresLockDeniedEarlyResolution",true,"committedOutcomePreserved",true,"revokedAfterResolution",true,"oldWorkerDenied",true));
    }finally{release.countDown();pool.shutdownNow();}
  }
  @Test void resolvedRollbackRequiresSeparateFreshAdmissionAndPreservesExhaustedBudget()throws Exception{
    var p=operator();recoveryPolicy(p,key,1,true);var dir=fileAuthorityDirectory();var source=fileAuthority(dir,fenceProof(1,false));var old=authority(platformFence(source));
    var delivered=old.prepare(p,request(key));old.deliver(delivered.id(),0);store.loseReply=true;assertThatThrownBy(this::upload).hasMessageContaining("reconciliation");
    db.sql("update ouf_onboarding.managed_file_upload_intent set reconciliation_reads=128").update();var before=intent();unresolvedRollback(source);var r=resolution(source);
    var receipt=source.reconcile(r,FileAuthorityReconciliationLab.gate(Outcome.ROLLED_BACK,"ACTIVE"));assertThat(receipt.evidence().outcome()).isEqualTo(Outcome.ROLLED_BACK);
    assertThat(source.inspect(fileBinding(1)).state()).isEqualTo(State.REVOKED);assertThatThrownBy(()->policyRecovery(old).beginDrain(epoch())).hasMessageContaining("admission generation revoked or differs");
    source.publish(2,fenceProof(3,false));assertThat(source.inspect(fileBinding(1)).state()).isEqualTo(State.FENCED);
    assertThatThrownBy(()->old.prepare(p,request(key+"-old"))).hasMessageContaining("admission generation revoked or differs");
    var fresh=authority(new PlatformStorageRecoveryAuthorityFence(fileBinding(3),source));
    assertThatThrownBy(()->fresh.deliver(delivered.id(),1)).hasMessageContaining("authority generation differs");
    var decision=fresh.prepare(p,request(key+"-fresh"));fresh.deliver(decision.id(),1);policyRecovery(fresh).beginDrain(epoch());
    assertThatThrownBy(()->policyRecovery(fresh).reconcile((UUID)before.get("intent_id"),epoch(),store)).hasMessageContaining("budget exhausted");
    assertThat(intent()).isEqualTo(before);assertThat(store.gets).hasValue(0);assertThat(assets()).isZero();
    fileEvidence("resolution-governed-resume",Map.of("actualRollbackVerified",true,"separateGeneration3AdmissionRequired",true,"oldWorkerAndDecisionDenied",true,"freshDrainSucceeded",true,"budgetStill128",true,"getCalls",0));
  }
  @Test void reconciliationSurvivesProcessHaltBeforeClearAndLostResponse()throws Exception{
    var p=operator();recoveryPolicy(p,key,1,true);
    for(String mode:List.of("reconcile-halt-before-clear","reconcile-halt")){
      var dir=fileAuthorityDirectory();var source=fileAuthority(dir,fenceProof(1,false));unresolvedRollback(source);var r=resolution(source);var path=resolutionFile(dir,r);
      assertThat(fileChild(dir,mode,path,0)).isEqualTo(mode.endsWith("before-clear")?87:86);
      assertThat(source.inspect(fileBinding(1)).state()).isEqualTo(mode.endsWith("before-clear")?State.RECONCILE_REQUIRED:State.REVOKED);
      if(mode.endsWith("before-clear"))assertThatThrownBy(()->source.publish(2,fenceProof(3,false))).hasMessageContaining("Unresolved prior");
      byte[] receipt=Files.readAllBytes(dir.resolve("resolution-"+r.commandId()+".json"));
      assertThat(fileChild(dir,"reconcile",path,0)).isZero();assertThat(source.inspect(fileBinding(1)).state()).isEqualTo(State.REVOKED);
      assertThat(Files.readAllBytes(dir.resolve("resolution-"+r.commandId()+".json"))).isEqualTo(receipt);
      assertThat(source.reconcile(r,null).request()).isEqualTo(r);assertThat(fileProof(source)).isEqualTo(r.revokedNext());
    }
    fileEvidence("resolution-crash",Map.of("haltBeforeLatchClear87",true,"lostReplyAfterCompletion86",true,"exactReplayAcrossJvm",true,"receiptUnchanged",true,"deadlineUnchanged",true,"noAutomaticAdmission",true));
  }
  @Test void competingReconciliationCommandsHaveOneWinner()throws Exception{
    var p=operator();recoveryPolicy(p,key,1,true);var dir=fileAuthorityDirectory();var source=fileAuthority(dir,fenceProof(1,false));unresolvedRollback(source);var a=resolution(source);
    var b=new Request(UUID.randomUUID(),a.leaseId(),a.leaseSha256(),1,a.actorRef(),a.reasonCode(),a.decisionRef(),a.revokedNext());
    var one=fileProcess(dir,"reconcile",resolutionFile(dir,a),0);var two=fileProcess(dir,"reconcile",resolutionFile(dir,b),0);
    try{assertThat(one.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(two.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(List.of(one.exitValue(),two.exitValue())).containsExactlyInAnyOrder(0,3);}
    finally{if(one.isAlive())one.destroyForcibly();if(two.isAlive())two.destroyForcibly();}
    try(var files=Files.list(dir)){assertThat(files.filter(f->f.getFileName().toString().startsWith("resolution-")).count()).isOne();}
    assertThat(source.inspect(fileBinding(1)).state()).isEqualTo(State.REVOKED);
    fileEvidence("resolution-concurrent",Map.of("successes",1,"denials",1,"receipts",1,"generation",2));
  }
  @Test void completedResolutionCannotBeChangedOrClearAnotherLease()throws Exception{
    var p=operator();recoveryPolicy(p,key,1,true);var dir=fileAuthorityDirectory();var source=fileAuthority(dir,fenceProof(1,false));unresolvedRollback(source);var r=resolution(source);
    var receipt=source.reconcile(r,FileAuthorityReconciliationLab.gate(Outcome.ROLLED_BACK,"ACTIVE"));assertThat(source.reconcile(r,null)).isEqualTo(receipt);
    var changed=new Request(r.commandId(),r.leaseId(),r.leaseSha256(),1,r.actorRef(),"CHANGED",r.decisionRef(),r.revokedNext());
    assertThatThrownBy(()->source.reconcile(changed,null)).hasMessageContaining("different intent");
    source.tryAcquire(fileBinding(2)).abandon();var next=source.inspect(fileBinding(2));
    assertThatThrownBy(()->source.reconcile(r,FileAuthorityReconciliationLab.gate(Outcome.ROLLED_BACK,"ACTIVE"))).hasMessageContaining("lease differs");assertThat(source.inspect(fileBinding(2))).isEqualTo(next);
    fileEvidence("resolution-stale-command",Map.of("exactReplay",true,"changedIntentDenied",true,"newLeaseNotCleared",true));
  }
  @Test void reconciliationCapacityAndCorruptReceiptKeepTheOwnerPaused()throws Exception{
    var p=operator();recoveryPolicy(p,key,1,true);var dir=fileAuthorityDirectory();var source=fileAuthority(dir,fenceProof(1,false));unresolvedRollback(source);var r=resolution(source);
    for(int i=0;i<128;i++)Files.writeString(dir.resolve("resolution-reserved-"+i+".json"),"fixture capacity");
    assertThatThrownBy(()->source.reconcile(r,FileAuthorityReconciliationLab.gate(Outcome.ROLLED_BACK,"ACTIVE"))).hasMessageContaining("capacity exhausted");assertThat(source.inspect(fileBinding(1)).state()).isEqualTo(State.RECONCILE_REQUIRED);
    var other=fileAuthorityDirectory();var second=fileAuthority(other,fenceProof(1,false));unresolvedRollback(second);var request=resolution(second);
    assertThat(fileChild(other,"reconcile-halt-before-clear",resolutionFile(other,request),0)).isEqualTo(87);
    Files.writeString(other.resolve("resolution-"+request.commandId()+".json"),"{}");
    assertThatThrownBy(()->second.reconcile(request,FileAuthorityReconciliationLab.gate(Outcome.ROLLED_BACK,"ACTIVE"))).isInstanceOf(IllegalStateException.class);
    assertThat(second.inspect(fileBinding(1)).state()).isEqualTo(State.RECONCILE_REQUIRED);
    fileEvidence("resolution-evidence-retention",Map.of("capacity128Blocks",true,"noReceiptDeletion",true,"corruptReceiptBlocks",true,"latchRetained",true));
  }
  @Test void reconciliationRechecksEvidenceAfterRevokedPublicationBeforeClearingLatch()throws Exception{
    var p=operator();recoveryPolicy(p,key,1,true);var dir=fileAuthorityDirectory();var source=fileAuthority(dir,fenceProof(1,false));unresolvedRollback(source);var r=resolution(source);
    var realGate=FileAuthorityReconciliationLab.gate(Outcome.ROLLED_BACK,"ACTIVE");
    Gate expires=request->{var held=realGate.tryAcquire(request);return new Lease(){int calls;public Evidence current(){var e=held.current();return ++calls==1?e:new Evidence(e.requestSha256(),e.databaseIdentity(),e.ownerEvidenceRef(),e.outcome(),Instant.now().minusSeconds(20),Instant.now().minusSeconds(1));}public void close(){held.close();}};};
    assertThatThrownBy(()->source.reconcile(r,expires)).hasMessageContaining("Fresh exact owner");
    assertThat(source.inspect(fileBinding(1)).state()).isEqualTo(State.RECONCILE_REQUIRED);assertThat(source.inspect(fileBinding(1)).generation()).isEqualTo(2);
    source.reconcile(r,realGate);assertThat(source.inspect(fileBinding(1)).state()).isEqualTo(State.REVOKED);
    fileEvidence("resolution-final-check",Map.of("expiredFinalEvidenceDenied",true,"revokedStatePersisted",true,"latchRetained",true,"freshReconciliationCompleted",true));
  }
}
