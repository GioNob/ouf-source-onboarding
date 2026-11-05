package it.comune.trieste.ouf.onboarding.application;

import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Owner-local cut protocol. No endpoint, scheduler, credential revocation or reopen authority.
 * The caller must quiesce every storage writer before reconciliation/cold copy.
 */
final class ManagedFileStorageRecovery {
  private final JdbcClient db;
  private final TransactionTemplate tx;
  private final ManagedFileService files;
  private final CanonicalHash hashes;

  ManagedFileStorageRecovery(JdbcClient db, TransactionTemplate tx, ManagedFileService files, CanonicalHash hashes) {
    this.db=db; this.tx=tx; this.files=files; this.hashes=hashes;
  }

  void beginDrain(long expectedEpoch) {
    noAmbientTransaction();
    tx.executeWithoutResult(status -> {
      var control=control(true);
      requireEpoch(control,expectedEpoch);
      if("DRAINING".equals(control.get("phase")))return;
      requirePhase(control,"ACTIVE");
      db.sql("update ouf_onboarding.managed_file_storage_control set phase='DRAINING' where singleton=true").update();
    });
  }

  Map<String,Object> reconcile(UUID intentId, long expectedEpoch, ManagedFileStagingStore store) {
    noAmbientTransaction();
    // A failed GET consumes the existing durable budget too. No clock/budget reset on recovery.
    var row=tx.execute(status -> {
      var control=control(false); requireEpoch(control,expectedEpoch); requirePhase(control,"DRAINING");
      var current=intent(intentId);
      if(((Number)current.get("epoch")).longValue()!=expectedEpoch)throw blocked("Intent generation differs");
      if("ACKED".equals(current.get("state")))return current;
      int claimed=db.sql("update ouf_onboarding.managed_file_upload_intent set reconciliation_reads=reconciliation_reads+1 where intent_id=:i and reconciliation_reads<128 and clock_timestamp()<reconcile_until")
        .param("i",intentId).update();
      if(claimed!=1)throw blocked("Upload reconciliation budget exhausted; intervention required");
      return current;
    });
    if("ACKED".equals(row.get("state")))return files.asset((UUID)row.get("asset_id"));
    byte[] bytes=store.get((String)row.get("staging_ref"));
    if(bytes.length!=((Number)row.get("size_bytes")).longValue()||!hashes.ofBytes(bytes).equals(row.get("content_hash")))
      throw blocked("Stored bytes differ; cut blocked");
    return tx.execute(status -> {
      var control=control(false); requireEpoch(control,expectedEpoch); requirePhase(control,"DRAINING");
      var current=intent(intentId);
      if("ACKED".equals(current.get("state")))return files.asset((UUID)current.get("asset_id"));
      var asset=files.registerOwned(null,(String)current.get("staging_ref"),(String)current.get("content_hash"),"text/csv",
        ((Number)current.get("size_bytes")).longValue(),(String)current.get("subject_id"),"retention://managed-files/30d",(String)current.get("tenant_id"));
      if("DELEGATED".equals(current.get("upload_kind")))db.sql("insert into ouf_onboarding.managed_file_upload_attempt(tenant_id,subject_id,file_id,idempotency_key,content_hash,size_bytes,asset_id) values(:t,:s,:f,:k,:h,:z,:a)")
        .param("t",current.get("tenant_id")).param("s",current.get("subject_id")).param("f",current.get("file_id"))
        .param("k",current.get("idempotency_key")).param("h",current.get("content_hash"))
        .param("z",current.get("size_bytes")).param("a",asset.get("asset_id")).update();
      db.sql("update ouf_onboarding.managed_file_upload_intent set state='ACKED',asset_id=:a,acknowledged_at=clock_timestamp() where intent_id=:i")
        .param("a",asset.get("asset_id")).param("i",intentId).update();
      return asset;
    });
  }

  void freeze(long expectedEpoch) {
    noAmbientTransaction();
    tx.executeWithoutResult(status -> {
      var control=control(true); requireEpoch(control,expectedEpoch);
      if("FROZEN".equals(control.get("phase")))return;
      requirePhase(control,"DRAINING");
      if(db.sql("select count(*) from ouf_onboarding.managed_file_upload_intent where state<>'ACKED'").query(Long.class).single()!=0)
        throw blocked("Pending upload intents block the cut");
      db.sql("update ouf_onboarding.managed_file_storage_control set phase='FROZEN' where singleton=true").update();
    });
  }

  private Map<String,Object> intent(UUID id) {
    return db.sql("select * from ouf_onboarding.managed_file_upload_intent where intent_id=:i for update")
      .param("i",id).query().singleRow();
  }
  private Map<String,Object> control(boolean exclusive) {
    return db.sql("select epoch,phase from ouf_onboarding.managed_file_storage_control where singleton=true for "+(exclusive?"update":"share")).query().singleRow();
  }
  private static void requireEpoch(Map<String,Object> control,long epoch) {
    if(((Number)control.get("epoch")).longValue()!=epoch)throw blocked("Storage generation changed");
  }
  private static void requirePhase(Map<String,Object> control,String phase) {
    if(!phase.equals(control.get("phase")))throw blocked("Storage phase differs");
  }
  private static void noAmbientTransaction() {
    if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Recovery requires no ambient transaction");
  }
  private static DomainFailure blocked(String message) {
    return new DomainFailure(HttpStatus.CONFLICT,"ONB_UPLOAD_RECOVERY_BLOCKED",message);
  }
}
