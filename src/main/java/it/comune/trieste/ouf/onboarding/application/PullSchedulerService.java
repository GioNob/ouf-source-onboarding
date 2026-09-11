package it.comune.trieste.ouf.onboarding.application;

import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PullSchedulerService {
  private final JdbcClient db;

  public PullSchedulerService(JdbcClient db){this.db=db;}

  @Transactional public Map<String,Object> materialize(String sourceId,UUID versionId,Duration interval,String timezone,int timeout,int maxAttempts,Instant firstFire){
    if(interval.toSeconds()<60||interval.toSeconds()>2_678_400)throw new IllegalArgumentException("poll interval must be between PT1M and P31D");ZoneId.of(timezone);
    Map<String,Object> source=db.sql("select acquisition_mode,status from ouf_onboarding.source where source_id=:s for update").param("s",sourceId).query().singleRow();
    if(!"PULL".equals(source.get("acquisition_mode")))throw new IllegalArgumentException("only PULL sources may have a pull schedule");
    String state=db.sql("select state from ouf_onboarding.onboarding_version where onboarding_version_id=:v and source_id=:s").param("v",versionId).param("s",sourceId).query(String.class).single();if(!"ACTIVE".equals(state))throw new IllegalArgumentException("schedule requires an ACTIVE onboarding version");
    UUID id=UUID.randomUUID();db.sql("insert into ouf_onboarding.pull_schedule(schedule_id,source_id,onboarding_version_id,interval_seconds,timezone,timeout_seconds,max_attempts,next_fire_at) values(:i,:s,:v,:n,:z,:t,:m,:f) on conflict(source_id) do update set onboarding_version_id=excluded.onboarding_version_id,interval_seconds=excluded.interval_seconds,timezone=excluded.timezone,timeout_seconds=excluded.timeout_seconds,max_attempts=excluded.max_attempts,next_fire_at=excluded.next_fire_at,enabled=true,lock_version=ouf_onboarding.pull_schedule.lock_version+1,updated_at=transaction_timestamp()")
      .param("i",id).param("s",sourceId).param("v",versionId).param("n",interval.toSeconds()).param("z",timezone).param("t",timeout).param("m",maxAttempts).param("f",utc(firstFire)).update();return schedule(sourceId);
  }

  @Transactional public List<Map<String,Object>> emitDue(Instant now,int limit){
    List<Map<String,Object>> due=db.sql("select schedule_id,source_id,onboarding_version_id,next_fire_at,interval_seconds from ouf_onboarding.pull_schedule where enabled and next_fire_at<=:n order by next_fire_at for update skip locked limit :l").param("n",utc(now)).param("l",limit).query().listOfRows();List<Map<String,Object>> out=new ArrayList<>();
    for(Map<String,Object> s:due){
      long active=db.sql("select count(*) from ouf_onboarding.pull_dispatch where schedule_id=:q and state in ('READY','RUNNING','RETRY_WAIT')").param("q",s.get("schedule_id")).query(Long.class).single();
      if(active==0){UUID id=UUID.randomUUID();db.sql("insert into ouf_onboarding.pull_dispatch(dispatch_id,schedule_id,source_id,onboarding_version_id,due_at,next_attempt_at) values(:i,:q,:s,:v,:d,:d) on conflict(schedule_id,due_at) do nothing").param("i",id).param("q",s.get("schedule_id")).param("s",s.get("source_id")).param("v",s.get("onboarding_version_id")).param("d",s.get("next_fire_at")).update();out.add(dispatch(id));}
      db.sql("update ouf_onboarding.pull_schedule set next_fire_at=:n + make_interval(secs=>interval_seconds),lock_version=lock_version+1,updated_at=transaction_timestamp() where schedule_id=:q").param("n",utc(now)).param("q",s.get("schedule_id")).update();
    }return out;
  }

  @Transactional public Optional<Map<String,Object>> claim(String worker,Duration lease,Instant now){
    requireLease(lease);OffsetDateTime instant=utc(now);
    db.sql("update ouf_onboarding.pull_dispatch d set state='FAILED',claimed_by=null,lease_until=null,error_code='INGESTION_LEASE_EXHAUSTED',last_error='Lease expired after maximum attempts',completed_at=:n from ouf_onboarding.pull_schedule s where d.schedule_id=s.schedule_id and d.state='RUNNING' and d.lease_until<=:n and d.attempts>=s.max_attempts").param("n",instant).update();
    List<Map<String,Object>> claimed=db.sql("with candidate as (select d.dispatch_id from ouf_onboarding.pull_dispatch d join ouf_onboarding.pull_schedule s on s.schedule_id=d.schedule_id where ((d.state in ('READY','RETRY_WAIT') and d.next_attempt_at<=:n) or (d.state='RUNNING' and d.lease_until<=:n)) and d.attempts<s.max_attempts and not exists(select 1 from ouf_onboarding.pull_dispatch a where a.schedule_id=d.schedule_id and a.dispatch_id<>d.dispatch_id and a.state='RUNNING' and a.lease_until>:n) order by d.next_attempt_at,d.created_at for update of d skip locked limit 1) update ouf_onboarding.pull_dispatch d set state='RUNNING',claimed_by=:w,lease_until=:u,attempts=d.attempts+1,error_code=null,last_error=null,completed_at=null where d.dispatch_id=(select dispatch_id from candidate) returning d.*")
      .param("n",instant).param("w",worker).param("u",utc(now.plus(lease))).query().listOfRows();return claimed.stream().findFirst().map(row->{Map<String,Object> out=new LinkedHashMap<>(row);out.put("kind","SCHEDULED_PULL");return out;});
  }

  @Transactional public Map<String,Object> heartbeat(UUID dispatchId,String worker,Duration lease,Instant now){
    requireLease(lease);int changed=db.sql("update ouf_onboarding.pull_dispatch set lease_until=:u where dispatch_id=:i and state='RUNNING' and claimed_by=:w and lease_until>:n").param("u",utc(now.plus(lease))).param("i",dispatchId).param("w",worker).param("n",utc(now)).update();if(changed!=1)throw conflict("ONB_PULL_LEASE_LOST","Pull dispatch lease is not owned by this worker");return dispatch(dispatchId);
  }

  @Transactional public Map<String,Object> complete(UUID dispatchId,String worker,String outputRef){
    int changed=db.sql("update ouf_onboarding.pull_dispatch set state='SUCCEEDED',claimed_by=null,lease_until=null,output_ref=:o,completed_at=transaction_timestamp() where dispatch_id=:i and state='RUNNING' and claimed_by=:w").param("o",outputRef).param("i",dispatchId).param("w",worker).update();if(changed==1)return dispatch(dispatchId);Map<String,Object> current=dispatch(dispatchId);if("SUCCEEDED".equals(current.get("state"))&&Objects.equals(outputRef,current.get("output_ref")))return current;throw conflict("ONB_PULL_DISPATCH_CONFLICT","Pull dispatch is terminal or owned by another worker");
  }

  @Transactional public Map<String,Object> fail(UUID dispatchId,String worker,String errorCode,String detail,Duration retryAfter){
    if(retryAfter.isNegative())throw new IllegalArgumentException("retryAfter must not be negative");int changed=db.sql("update ouf_onboarding.pull_dispatch d set state=case when d.attempts>=s.max_attempts then 'FAILED' else 'RETRY_WAIT' end,claimed_by=null,lease_until=null,next_attempt_at=transaction_timestamp()+make_interval(secs=>:r),error_code=:e,last_error=:d,completed_at=case when d.attempts>=s.max_attempts then transaction_timestamp() else null end from ouf_onboarding.pull_schedule s where d.schedule_id=s.schedule_id and d.dispatch_id=:i and d.state='RUNNING' and d.claimed_by=:w")
      .param("r",retryAfter.toSeconds()).param("e",errorCode).param("d",detail).param("i",dispatchId).param("w",worker).update();if(changed!=1)throw conflict("ONB_PULL_DISPATCH_CONFLICT","Pull dispatch is terminal or owned by another worker");return dispatch(dispatchId);
  }

  public Map<String,Object> schedule(String sourceId){return db.sql("select schedule_id,source_id,onboarding_version_id,interval_seconds,timezone,timeout_seconds,max_attempts,overlap_policy,enabled,next_fire_at,lock_version from ouf_onboarding.pull_schedule where source_id=:s").param("s",sourceId).query().singleRow();}
  public Map<String,Object> dispatch(UUID id){return db.sql("select dispatch_id,schedule_id,source_id,onboarding_version_id,due_at,state,attempts,claimed_by,lease_until,next_attempt_at,error_code,last_error,output_ref,created_at,completed_at from ouf_onboarding.pull_dispatch where dispatch_id=:i").param("i",id).query().singleRow();}
  private static OffsetDateTime utc(Instant instant){return OffsetDateTime.ofInstant(instant,ZoneOffset.UTC);}
  private static void requireLease(Duration lease){if(lease.toSeconds()<10||lease.toSeconds()>3600)throw new IllegalArgumentException("lease must be between PT10S and PT1H");}
  private static DomainFailure conflict(String code,String message){return new DomainFailure(HttpStatus.CONFLICT,code,message);}
}
