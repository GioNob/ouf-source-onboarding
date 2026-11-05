package it.comune.trieste.ouf.onboarding.application;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import it.comune.trieste.ouf.onboarding.domain.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Separate fixture JVM at each boundary; it calls the actual owner, never fabricates receipts. */
public final class UploadCommonCutProcess {
  static final ObjectMapper JSON=new ObjectMapper();
  static final CanonicalHash HASHES=new CanonicalHash(JSON);
  static final byte[] BODY="id;name\n1;Trieste\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
  static final Path PROOF=Path.of("target/upload-cut");
  static StorageRecoveryRuntime RUNTIME;
  static JdbcClient db; static ManagedFileService owner; static ManagedFileStorageRecovery recovery;
  static MinioClient client; static MinioManagedFileStagingStore store;
  // This file models a CURRENT platform decision, outside every owner backup/restore directory.
  // It is test-only, not a production policy evaluator or a concurrent revocation protocol.
  static Path liveAuthority(){return Path.of(Objects.requireNonNull(System.getenv("OUF_LAB_AUTHORITY_PATH")));}
  static com.fasterxml.jackson.databind.JsonNode currentAuthority()throws Exception{
    Path path=liveAuthority();
    if(!Files.isRegularFile(path)||Files.size(path)>4096)throw new IllegalStateException("Current lab authority unavailable");
    var node=JSON.readTree(path.toFile());
    if(!node.path("revoked").isBoolean() || node.path("revoked").booleanValue()
        || !"lab-installation".equals(node.path("installationRef").asText())
        || !"lab-only/decision".equals(node.path("decisionRef").asText())
        || !node.path("epoch").isIntegralNumber() || node.path("epoch").asLong()!=1
        || !node.path("revision").isIntegralNumber() || node.path("revision").asLong()!=1)
      throw new IllegalStateException("Current lab authority revoked or mismatched");
    return node;
  }
  static void attestCurrentRuntime()throws Exception{
    currentAuthority();
    int changed=db.sql("update ouf_onboarding.storage_recovery_lab_gate set runtime_session_id=:r,quiescent=false,quiescence_runtime_session_id=null where installation_ref=:i and decision_ref='lab-only/decision' and epoch=1 and revoked=false and clock_timestamp()<expires_at")
      .param("r",RUNTIME.sessionId()).param("i",RUNTIME.installationRef()).update();
    assertThat(changed).isOne(); // Existing decision lifetime, session and intent budget are NOT renewed.
  }
  static void snapshot(String name)throws Exception {
    var rows=new TreeMap<String,Object>();
    for(String table:List.of("managed_file_storage_control","managed_file_upload_intent","managed_file_upload_attempt","managed_file_asset","managed_file_owner_reconciliation","storage_recovery_lab_gate","storage_recovery_lab_audit")) {
      String value=db.sql("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text),'[]'::jsonb)::text from ouf_onboarding."+table+" t").query(String.class).single();
      rows.put(table,JSON.readTree(value));
    }
    Files.writeString(PROOF.resolve(name+".json"),JSON.writeValueAsString(rows)+"\n");
  }
  static Map<String,Object> upload(String key,ManagedFileStagingStore target,Path spool){
    return owner.registerDelegatedUpload("tenant-cut","alice","file_cut",key,HASHES.ofBytes(BODY),BODY.length,target,spool);
  }
  static void denied(String key)throws Exception {
    try {
      client.putObject(PutObjectArgs.builder().bucket("upload-fixture").object(key).stream(new ByteArrayInputStream(BODY),BODY.length,-1).contentType("text/csv").build());
      throw new AssertionError("Obsolete writer PUT succeeded");
    } catch(ErrorResponseException ex){assertThat(ex.errorResponse().code()).isEqualTo("AccessDenied");
      Files.writeString(PROOF.resolve("denials-"+System.getenv().getOrDefault("OUF_CUT_STAGE","source")+".jsonl"),JSON.writeValueAsString(Map.of("key",key,"code",ex.errorResponse().code()))+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
  }
  static void verifyObjects()throws Exception {
    var expected=new TreeSet<String>();
    var rows=db.sql("select staging_ref,content_hash,size_bytes from ouf_onboarding.managed_file_upload_intent order by intent_id").query().listOfRows();
    assertThat(rows).hasSize(2);
    var objects=new TreeMap<String,Object>();
    for(var row:rows){
      String ref=(String)row.get("staging_ref"),key="managed-files/"+MinioManagedFileStagingStore.objectId(ref);
      byte[] bytes=store.get(ref);assertThat(bytes).isEqualTo(BODY);assertThat(HASHES.ofBytes(bytes)).isEqualTo(row.get("content_hash"));
      var head=client.statObject(StatObjectArgs.builder().bucket("upload-fixture").object(key).build());
      assertThat(head.size()).isEqualTo(BODY.length);assertThat(head.contentType()).isEqualTo("text/csv");
      expected.add(key);objects.put(key,Map.of("sha256",HASHES.ofBytes(bytes),"bytes",bytes.length,"contentType",head.contentType()));
      denied(key); // Existing object must not be overwritable with the old identity either.
    }
    var actual=new TreeSet<String>();for(var result:client.listObjects(ListObjectsArgs.builder().bucket("upload-fixture").recursive(true).build()))actual.add(result.get().objectName());
    assertThat(actual).isEqualTo(expected);denied("stale-new-object");
    Files.writeString(PROOF.resolve("objects-"+System.getenv().getOrDefault("OUF_CUT_STAGE","source")+".json"),JSON.writeValueAsString(objects)+"\n");
  }
  public static void main(String[] args)throws Exception {
    Files.createDirectories(PROOF);
    var ds=UploadCrashProcess.datasource();db=JdbcClient.create(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
    RUNTIME=StorageRecoveryRuntime.start("lab-installation",db);
    owner=new ManagedFileService(db,JSON,HASHES,null,tx,null,null);recovery=new ManagedFileStorageRecovery(db,tx,owner,HASHES,new StorageRecoveryLabGate(db),RUNTIME);
    client=UploadCrashProcess.client();client.setTimeout(10000,10000,10000);store=new MinioManagedFileStagingStore(client,"upload-fixture");
    switch(args[0]) {
      case "init" -> {
        Flyway.configure().dataSource(ds).schemas("ouf_onboarding").defaultSchema("ouf_onboarding").load().migrate();
        StorageRecoveryLabGate.init(db,1,false,RUNTIME);
        Files.writeString(liveAuthority(),JSON.writeValueAsString(Map.of("installationRef","lab-installation","decisionRef","lab-only/decision","epoch",1,"revision",1,"revoked",false))+"\n");
        db.sql("create table ouf_onboarding.cut_canary(value text not null)").update();db.sql("insert into ouf_onboarding.cut_canary values('base')").update();
        snapshot("base-owner");
      }
      case "seed-drain" -> {
        attestCurrentRuntime();
        client.makeBucket(MakeBucketArgs.builder().bucket("upload-fixture").build());
        Path spool=Files.createTempFile("cut-",".csv");Files.write(spool,BODY);
        try {
          upload("acked",store,spool);
          var late=new ManagedFileStagingStore(){
            public void putReserved(String ref,InputStream input,long size,String type)throws IOException {
              store.putReserved(ref,input,size,type); recovery.beginDrain(1);
            }
            public byte[] get(String ref){throw new AssertionError("Unexpected seed GET");}
          };
          assertThatThrownBy(()->upload("late",late,spool)).hasMessageContaining("phase");
          assertThatThrownBy(()->upload("new-during-drain",store,spool)).hasMessageContaining("phase");
          assertThatThrownBy(()->recovery.freeze(1)).hasMessageContaining("quiescence");
          snapshot("draining-owner");
        } finally {Files.deleteIfExists(spool);}
      }
      case "reconcile-freeze" -> {
        var ids=db.sql("select intent_id from ouf_onboarding.managed_file_upload_intent where state='DISPATCHED'").query(UUID.class).list();
        assertThat(ids).hasSize(1);
        UUID oldRuntime=db.sql("select runtime_session_id from ouf_onboarding.storage_recovery_lab_gate").query(UUID.class).single();
        long before=db.sql("select sum(reconciliation_reads) from ouf_onboarding.managed_file_upload_intent").query(Long.class).single();
        assertThat(oldRuntime).isNotEqualTo(RUNTIME.sessionId());
        assertThatThrownBy(()->recovery.reconcile(ids.getFirst(),1,store)).hasMessageContaining("runtime binding differs");
        attestCurrentRuntime();
        assertThatThrownBy(()->recovery.reconcile(ids.getFirst(),1,store)).hasMessageContaining("quiescence");
        assertThat(db.sql("select sum(reconciliation_reads) from ouf_onboarding.managed_file_upload_intent").query(Long.class).single()).isEqualTo(before);
        verifyObjects();
        // Fresh custody only after physical stop/restriction and three actual PUT denials.
        db.sql("update ouf_onboarding.storage_recovery_lab_gate set quiescent=true,quiescence_runtime_session_id=:r").param("r",RUNTIME.sessionId()).update();
        recovery.reconcile(ids.getFirst(),1,store);recovery.freeze(1);snapshot("frozen-owner");
        Files.writeString(PROOF.resolve("authority-restart.json"),JSON.writeValueAsString(Map.of("oldRuntime",oldRuntime,"currentRuntime",RUNTIME.sessionId(),"oldAuthorityRejected",true,"unprovenCustodyRejected",true,"readsBeforeFreshEvidence",before,"readsAfterRejectedAttempts",before,"freshCustodyAfterNativeDenials",true))+"\n");
        assertThat(db.sql("select count(*) from ouf_onboarding.managed_file_asset").query(Long.class).single()).isEqualTo(2);
      }
      case "revoke-after-cut" -> {
        currentAuthority();
        String revoked=JSON.writeValueAsString(Map.of("installationRef","lab-installation","decisionRef","lab-only/decision","epoch",1,"revision",2,"revoked",true))+"\n";
        Files.writeString(liveAuthority(),revoked);
        assertThat(db.sql("update ouf_onboarding.storage_recovery_lab_gate set revoked=true").update()).isOne();
        assertThat(db.sql("select revoked from ouf_onboarding.storage_recovery_lab_gate").query(Boolean.class).single()).isTrue();
        snapshot("revoked-after-cut-owner");
        Files.writeString(PROOF.resolve("authority-after-cut.json"),revoked);
      }
      case "watch-live-runtime" -> {
        // Keep this JVM alive across destruction of the original DB and physical restoration.
        Path ready=liveAuthority().resolveSibling("watch-ready"),resume=liveAuthority().resolveSibling("watch-resume");
        Files.writeString(ready,RUNTIME.databaseStartedAt().toString());
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(180);
        while(!Files.exists(resume)){
          if(System.nanoTime()>deadline)throw new IllegalStateException("Bounded live-runtime wait exhausted");
          Thread.sleep(100);
        }
        var current=StorageRecoveryRuntime.databaseStartedAt(db);assertThat(current).isNotEqualTo(RUNTIME.databaseStartedAt());
        assertThatThrownBy(()->recovery.freeze(1)).hasMessageContaining("database binding differs");
        Files.writeString(PROOF.resolve("authority-live-runtime.json"),JSON.writeValueAsString(Map.of("originalDatabaseStartedAt",RUNTIME.databaseStartedAt().toString(),"restoredDatabaseStartedAt",current.toString(),"runtimeSession",RUNTIME.sessionId(),"liveWorkerRejected",true))+"\n");
      }
      case "verify-db" -> {
        assertThat(db.sql("select pg_is_in_recovery()").query(Boolean.class).single()).isFalse();
        assertThat(db.sql("select value from ouf_onboarding.cut_canary").query(String.class).single()).isEqualTo("cut");
        assertThat(db.sql("select phase from ouf_onboarding.managed_file_storage_control").query(String.class).single()).isEqualTo("FROZEN");
        var noStorage=new ManagedFileStagingStore(){
          public void putReserved(String r,InputStream i,long z,String t){throw new AssertionError("Frozen owner PUT");}
          public byte[] get(String r){throw new AssertionError("Frozen owner GET");}
        };
        assertThatThrownBy(()->upload("new-after-restore",noStorage,null)).hasMessageContaining("phase");
        assertThatThrownBy(()->recovery.beginDrain(1)).hasMessageContaining("runtime binding differs");
        UUID id=db.sql("select intent_id from ouf_onboarding.managed_file_upload_intent where idempotency_key='late'").query(UUID.class).single();
        assertThatThrownBy(()->recovery.reconcile(id,1,noStorage)).hasMessageContaining("phase");
        assertThatThrownBy(()->recovery.freeze(2)).hasMessageContaining("generation");
        assertThatThrownBy(()->recovery.freeze(1)).hasMessageContaining("runtime binding differs");
        // PITR restored an unrevoked old projection. The current decision was NOT restored.
        assertThat(db.sql("select revoked from ouf_onboarding.storage_recovery_lab_gate").query(Boolean.class).single()).isFalse();
        var live=JSON.readTree(liveAuthority().toFile());assertThat(live.path("revoked").booleanValue()).isTrue();
        assertThat(live.path("revision").asLong()).isEqualTo(2);
        assertThat(live).isEqualTo(JSON.readTree(PROOF.resolve("authority-after-cut.json").toFile()));
        assertThatThrownBy(UploadCommonCutProcess::attestCurrentRuntime).hasMessageContaining("revoked or mismatched");
        Path withheld=liveAuthority().resolveSibling("withheld-live-authority.json");Files.move(liveAuthority(),withheld);
        try{assertThatThrownBy(UploadCommonCutProcess::attestCurrentRuntime).hasMessageContaining("unavailable");}
        finally{Files.move(withheld,liveAuthority());}
        UUID restoredRuntime=db.sql("select runtime_session_id from ouf_onboarding.storage_recovery_lab_gate").query(UUID.class).single();
        assertThat(restoredRuntime).isNotEqualTo(RUNTIME.sessionId());
        Files.writeString(PROOF.resolve("authority-restore.json"),JSON.writeValueAsString(Map.of("restoredRuntime",restoredRuntime,"currentRuntime",RUNTIME.sessionId(),"restoredProjectionRevoked",false,"currentDecisionRevoked",true,"currentRevision",2,"replayRejected",true,"freshAttestationRejected",true,"missingAuthorityRejected",true,"currentAuthoritySha256",HASHES.ofBytes(Files.readAllBytes(liveAuthority()))))+"\n");
        var ack=upload("acked",noStorage,null);var late=upload("late",noStorage,null);
        assertThat(ack.get("assetId")).isNotEqualTo(late.get("assetId"));
        snapshot("restored-owner");
        assertThat(JSON.readTree(PROOF.resolve("restored-owner.json").toFile())).isEqualTo(JSON.readTree(PROOF.resolve("frozen-owner.json").toFile()));
      }
      case "verify-s3" -> { verifyObjects(); snapshot("restored-after-s3");
        assertThat(JSON.readTree(PROOF.resolve("restored-after-s3.json").toFile())).isEqualTo(JSON.readTree(PROOF.resolve("frozen-owner.json").toFile())); }
      default -> throw new IllegalArgumentException("Unknown fixture phase");
    }
    System.out.println("OWNER_COMMON_CUT_PHASE_PASS "+args[0]);System.exit(0);
  }
}
