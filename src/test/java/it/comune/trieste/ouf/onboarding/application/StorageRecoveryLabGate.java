package it.comune.trieste.ouf.onboarding.application;

import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Synthetic PostgreSQL adapter ONLY. These tables and grants have no production migration/API.
 * The native unit fixture controls custody; the physical fixture sets it only after S3 denials.
 */
final class StorageRecoveryLabGate implements StorageRecoveryGate {
  final JdbcClient db;
  StorageRecoveryLabGate(JdbcClient db){this.db=db;}
  static void init(JdbcClient db,long epoch,boolean quiescent){
    db.sql("create table if not exists ouf_onboarding.storage_recovery_lab_gate(singleton boolean primary key check(singleton),session_id uuid not null,scope text not null,epoch bigint not null,decision_ref text not null,quiescence_ref text,issued_at timestamptz not null,expires_at timestamptz not null,revoked boolean not null,quiescent boolean not null,actions text not null)").update();
    db.sql("create table if not exists ouf_onboarding.storage_recovery_lab_audit(id bigint generated always as identity primary key,session_id uuid not null,decision_ref text not null,quiescence_ref text,action text not null,intent_id uuid,outcome text not null)").update();
    db.sql("truncate ouf_onboarding.storage_recovery_lab_gate,ouf_onboarding.storage_recovery_lab_audit restart identity").update();
    db.sql("insert into ouf_onboarding.storage_recovery_lab_gate values(true,:s,:scope,:e,'lab-only/decision','lab-only/quiescence',clock_timestamp(),clock_timestamp()+interval '1 hour',false,:q,'DRAIN,RECONCILE,FREEZE')")
      .param("s",UUID.randomUUID()).param("scope",SCOPE).param("e",epoch).param("q",quiescent).update();
  }
  public Evidence lock(Action action,long epoch,UUID intentId){
    if(!TransactionSynchronizationManager.isActualTransactionActive())throw new AssertionError("Gate outside owner transaction");
    return db.sql("select * from ouf_onboarding.storage_recovery_lab_gate where singleton=true for share")
      .query((rs,n)->new Evidence(rs.getObject("session_id",UUID.class),rs.getString("scope"),rs.getLong("epoch"),
        rs.getString("decision_ref"),rs.getString("quiescence_ref"),rs.getObject("issued_at",OffsetDateTime.class).toInstant(),
        rs.getObject("expires_at",OffsetDateTime.class).toInstant(),rs.getBoolean("revoked"),rs.getBoolean("quiescent"),
        Arrays.stream(rs.getString("actions").split(",")).map(Action::valueOf).collect(java.util.stream.Collectors.toSet())))
      .optional().orElse(null);
  }
  public void record(Evidence e,Action action,UUID intentId,String outcome){
    if(!TransactionSynchronizationManager.isActualTransactionActive())throw new AssertionError("Audit outside owner transaction");
    db.sql("insert into ouf_onboarding.storage_recovery_lab_audit(session_id,decision_ref,quiescence_ref,action,intent_id,outcome) values(:s,:d,:q,:a,:i,:o)")
      .param("s",e.sessionId()).param("d",e.decisionRef()).param("q",e.quiescenceRef()).param("a",action.name()).param("i",intentId).param("o",outcome).update();
  }
}
