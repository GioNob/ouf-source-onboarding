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
  static JdbcClient db; static ManagedFileService owner; static ManagedFileStorageRecovery recovery;
  static MinioClient client; static MinioManagedFileStagingStore store;
  static void snapshot(String name)throws Exception {
    var rows=new TreeMap<String,Object>();
    for(String table:List.of("managed_file_storage_control","managed_file_upload_intent","managed_file_upload_attempt","managed_file_asset","managed_file_owner_reconciliation")) {
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
    owner=new ManagedFileService(db,JSON,HASHES,null,tx,null,null);recovery=new ManagedFileStorageRecovery(db,tx,owner,HASHES);
    client=UploadCrashProcess.client();client.setTimeout(10000,10000,10000);store=new MinioManagedFileStagingStore(client,"upload-fixture");
    switch(args[0]) {
      case "init" -> {
        Flyway.configure().dataSource(ds).schemas("ouf_onboarding").defaultSchema("ouf_onboarding").load().migrate();
        db.sql("create table ouf_onboarding.cut_canary(value text not null)").update();db.sql("insert into ouf_onboarding.cut_canary values('base')").update();
        snapshot("base-owner");
      }
      case "seed-drain" -> {
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
          assertThatThrownBy(()->recovery.freeze(1)).hasMessageContaining("Pending");
          snapshot("draining-owner");
        } finally {Files.deleteIfExists(spool);}
      }
      case "reconcile-freeze" -> {
        verifyObjects();
        var ids=db.sql("select intent_id from ouf_onboarding.managed_file_upload_intent where state='DISPATCHED'").query(UUID.class).list();
        assertThat(ids).hasSize(1);recovery.reconcile(ids.getFirst(),1,store);recovery.freeze(1);snapshot("frozen-owner");
        assertThat(db.sql("select count(*) from ouf_onboarding.managed_file_asset").query(Long.class).single()).isEqualTo(2);
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
        assertThatThrownBy(()->recovery.beginDrain(1)).hasMessageContaining("phase");
        UUID id=db.sql("select intent_id from ouf_onboarding.managed_file_upload_intent where idempotency_key='late'").query(UUID.class).single();
        assertThatThrownBy(()->recovery.reconcile(id,1,noStorage)).hasMessageContaining("phase");
        assertThatThrownBy(()->recovery.freeze(2)).hasMessageContaining("generation");
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
