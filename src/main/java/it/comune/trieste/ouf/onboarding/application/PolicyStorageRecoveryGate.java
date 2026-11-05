package it.comune.trieste.ouf.onboarding.application;

import it.comune.trieste.ouf.onboarding.authorization.StorageRecoveryAuthorization;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Same-database Authorization-to-owner bridge, explicitly constructed, never auto-enabled. */
final class PolicyStorageRecoveryGate implements StorageRecoveryGate {
  private final JdbcClient db;private final StorageRecoveryAuthorization authority;
  private final StorageRecoveryRuntime runtime;private final StorageRecoveryCustody custody;
  PolicyStorageRecoveryGate(JdbcClient db,StorageRecoveryAuthorization authority,StorageRecoveryRuntime runtime,StorageRecoveryCustody custody){
    this.db=db;this.authority=Objects.requireNonNull(authority);this.runtime=Objects.requireNonNull(runtime);this.custody=custody;
  }
  public Evidence lock(Action action,long epoch,UUID intentId){
    var decision=authority.lockCurrent(runtime.sessionId(),epoch,action.name());
    StorageRecoveryCustody.Proof proof=action==Action.DRAIN||custody==null?null:custody.lock(runtime.installationRef(),runtime.sessionId(),epoch);
    return new Evidence(decision.id(),SCOPE,decision.epoch(),"authorization/"+decision.id(),proof==null?null:proof.reference(),decision.issuedAt(),decision.expiresAt(),false,proof!=null&&proof.quiescent(),
      decision.actions().stream().map(Action::valueOf).collect(java.util.stream.Collectors.toSet()),decision.installation(),decision.runtimeSessionId(),proof==null?null:proof.runtimeSessionId());
  }
  public void record(Evidence e,Action action,UUID intentId,String outcome){
    db.sql("insert into ouf_onboarding.storage_recovery_action_audit(event_id,decision_id,action,intent_id,outcome,quiescence_ref,runtime_session_id) values(:e,:d,:a,:i,:o,:q,:r)")
      .param("e",UUID.randomUUID()).param("d",e.sessionId()).param("a",action.name()).param("i",intentId).param("o",outcome).param("q",e.quiescenceRef()).param("r",e.runtimeSessionId()).update();
  }
}
