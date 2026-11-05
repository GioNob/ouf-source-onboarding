package it.comune.trieste.ouf.onboarding.application;

import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.util.Map;
import java.util.UUID;
import java.time.Duration;
import java.time.OffsetDateTime;
import static it.comune.trieste.ouf.onboarding.application.StorageRecoveryGate.Action.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Owner-local cut protocol. No endpoint, scheduler, credential revocation or reopen authority.
 * Unavailable without a trusted gate adapter; evidence validation does not itself stop writers.
 */
final class ManagedFileStorageRecovery {
  private final JdbcClient db;
  private final TransactionTemplate tx;
  private final ManagedFileService files;
  private final CanonicalHash hashes;
  private final StorageRecoveryGate gate;

  ManagedFileStorageRecovery(JdbcClient db, TransactionTemplate tx, ManagedFileService files, CanonicalHash hashes) {
    this(db,tx,files,hashes,null);
  }

  ManagedFileStorageRecovery(JdbcClient db, TransactionTemplate tx, ManagedFileService files, CanonicalHash hashes, StorageRecoveryGate gate) {
    this.db=db; this.tx=tx; this.files=files; this.hashes=hashes; this.gate=gate;
  }

  void beginDrain(long expectedEpoch) {
    noAmbientTransaction();
    tx.executeWithoutResult(status -> {
      var control=control(true);
      requireEpoch(control,expectedEpoch);
      var evidence=authorize(DRAIN,expectedEpoch,null);
      if("DRAINING".equals(control.get("phase"))){gate.record(evidence,DRAIN,null,"REPLAY");return;}
      requirePhase(control,"ACTIVE");
      db.sql("update ouf_onboarding.managed_file_storage_control set phase='DRAINING' where singleton=true").update();
      gate.record(evidence,DRAIN,null,"DRAINED");
    });
  }

  Map<String,Object> reconcile(UUID intentId, long expectedEpoch, ManagedFileStagingStore store) {
    noAmbientTransaction();
    // A failed GET consumes the existing durable budget too. No clock/budget reset on recovery.
    var reservation=tx.execute(status -> {
      var control=control(false); requireEpoch(control,expectedEpoch); requirePhase(control,"DRAINING");
      var evidence=authorize(RECONCILE,expectedEpoch,intentId);
      var current=intent(intentId);
      if(((Number)current.get("epoch")).longValue()!=expectedEpoch)throw blocked("Intent generation differs");
      if("ACKED".equals(current.get("state"))){gate.record(evidence,RECONCILE,intentId,"REPLAY");return new Reservation(current,evidence);}
      int claimed=db.sql("update ouf_onboarding.managed_file_upload_intent set reconciliation_reads=reconciliation_reads+1 where intent_id=:i and reconciliation_reads<128 and clock_timestamp()<reconcile_until")
        .param("i",intentId).update();
      if(claimed!=1)throw blocked("Upload reconciliation budget exhausted; intervention required");
      gate.record(evidence,RECONCILE,intentId,"READ_RESERVED");
      return new Reservation(current,evidence);
    });
    var row=reservation.row();
    if("ACKED".equals(row.get("state")))return files.asset((UUID)row.get("asset_id"));
    byte[] bytes=store.get((String)row.get("staging_ref"));
    if(bytes.length!=((Number)row.get("size_bytes")).longValue()||!hashes.ofBytes(bytes).equals(row.get("content_hash")))
      throw blocked("Stored bytes differ; cut blocked");
    return tx.execute(status -> {
      var control=control(false); requireEpoch(control,expectedEpoch); requirePhase(control,"DRAINING");
      var evidence=authorize(RECONCILE,expectedEpoch,intentId);
      if(!evidence.equals(reservation.evidence()))throw blocked("Recovery authority or quiescence binding changed during storage read");
      var current=intent(intentId);
      if("ACKED".equals(current.get("state"))){gate.record(evidence,RECONCILE,intentId,"REPLAY");return files.asset((UUID)current.get("asset_id"));}
      if(!db.sql("select clock_timestamp()<reconcile_until from ouf_onboarding.managed_file_upload_intent where intent_id=:i")
          .param("i",intentId).query(Boolean.class).single())throw blocked("Upload reconciliation budget expired during storage read");
      var asset=files.registerOwned(null,(String)current.get("staging_ref"),(String)current.get("content_hash"),"text/csv",
        ((Number)current.get("size_bytes")).longValue(),(String)current.get("subject_id"),"retention://managed-files/30d",(String)current.get("tenant_id"));
      if("DELEGATED".equals(current.get("upload_kind")))db.sql("insert into ouf_onboarding.managed_file_upload_attempt(tenant_id,subject_id,file_id,idempotency_key,content_hash,size_bytes,asset_id) values(:t,:s,:f,:k,:h,:z,:a)")
        .param("t",current.get("tenant_id")).param("s",current.get("subject_id")).param("f",current.get("file_id"))
        .param("k",current.get("idempotency_key")).param("h",current.get("content_hash"))
        .param("z",current.get("size_bytes")).param("a",asset.get("asset_id")).update();
      db.sql("update ouf_onboarding.managed_file_upload_intent set state='ACKED',asset_id=:a,acknowledged_at=clock_timestamp() where intent_id=:i")
        .param("a",asset.get("asset_id")).param("i",intentId).update();
      gate.record(evidence,RECONCILE,intentId,"ACKED");
      return files.asset((UUID)asset.get("asset_id"));
    });
  }

  void freeze(long expectedEpoch) {
    noAmbientTransaction();
    tx.executeWithoutResult(status -> {
      var control=control(true); requireEpoch(control,expectedEpoch);
      var evidence=authorize(FREEZE,expectedEpoch,null);
      if("FROZEN".equals(control.get("phase"))){gate.record(evidence,FREEZE,null,"REPLAY");return;}
      requirePhase(control,"DRAINING");
      if(db.sql("select count(*) from ouf_onboarding.managed_file_upload_intent where state<>'ACKED'").query(Long.class).single()!=0)
        throw blocked("Pending upload intents block the cut");
      db.sql("update ouf_onboarding.managed_file_storage_control set phase='FROZEN' where singleton=true").update();
      gate.record(evidence,FREEZE,null,"FROZEN");
    });
  }

  private record Reservation(Map<String,Object> row, StorageRecoveryGate.Evidence evidence) {}
  private StorageRecoveryGate.Evidence authorize(StorageRecoveryGate.Action action,long epoch,UUID intentId) {
    if(gate==null)throw blocked("Storage recovery authority adapter unavailable");
    var evidence=gate.lock(action,epoch,intentId);
    var now=db.sql("select clock_timestamp()").query(OffsetDateTime.class).single().toInstant();
    if(evidence==null || evidence.sessionId()==null || !StorageRecoveryGate.SCOPE.equals(evidence.scope())
        || evidence.revoked() || !reference(evidence.decisionRef())
        || !evidence.actions().contains(action))throw blocked("Storage recovery authority denied");
    if(evidence.epoch()!=epoch)throw blocked("Storage recovery authority generation differs");
    if(evidence.issuedAt()==null || evidence.expiresAt()==null || evidence.issuedAt().isAfter(now)
        || !now.isBefore(evidence.expiresAt()) || !evidence.issuedAt().isBefore(evidence.expiresAt())
        || Duration.between(evidence.issuedAt(),evidence.expiresAt()).compareTo(Duration.ofHours(24))>0)
      throw blocked("Storage recovery authority expired or invalid duration");
    if(action!=DRAIN && (!evidence.quiescent() || !reference(evidence.quiescenceRef())))
      throw blocked("Current storage quiescence evidence required");
    return evidence;
  }
  private static boolean reference(String value) { return value!=null && value.matches("[A-Za-z0-9_.:/-]{1,256}"); }

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
