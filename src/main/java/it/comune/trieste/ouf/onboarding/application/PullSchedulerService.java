package it.comune.trieste.ouf.onboarding.application;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PullSchedulerService {
  private final JdbcClient db; public PullSchedulerService(JdbcClient db){this.db=db;}
  @Transactional public Map<String,Object> materialize(String sourceId,UUID versionId,Duration interval,String timezone,int timeout,int maxAttempts,Instant firstFire){
    if(interval.toSeconds()<60||interval.toSeconds()>2_678_400)throw new IllegalArgumentException("poll interval must be between PT1M and P31D");ZoneId.of(timezone);
    Map<String,Object> source=db.sql("select acquisition_mode,status from ouf_onboarding.source where source_id=:s for update").param("s",sourceId).query().singleRow();
    if(!"PULL".equals(source.get("acquisition_mode")))throw new IllegalArgumentException("only PULL sources may have a pull schedule");
    String state=db.sql("select state from ouf_onboarding.onboarding_version where onboarding_version_id=:v and source_id=:s").param("v",versionId).param("s",sourceId).query(String.class).single();if(!"ACTIVE".equals(state))throw new IllegalArgumentException("schedule requires an ACTIVE onboarding version");
    UUID id=UUID.randomUUID();db.sql("insert into ouf_onboarding.pull_schedule(schedule_id,source_id,onboarding_version_id,interval_seconds,timezone,timeout_seconds,max_attempts,next_fire_at) values(:i,:s,:v,:n,:z,:t,:m,:f) on conflict(source_id) do update set onboarding_version_id=excluded.onboarding_version_id,interval_seconds=excluded.interval_seconds,timezone=excluded.timezone,timeout_seconds=excluded.timeout_seconds,max_attempts=excluded.max_attempts,next_fire_at=excluded.next_fire_at,enabled=true,lock_version=ouf_onboarding.pull_schedule.lock_version+1,updated_at=transaction_timestamp()").param("i",id).param("s",sourceId).param("v",versionId).param("n",interval.toSeconds()).param("z",timezone).param("t",timeout).param("m",maxAttempts).param("f",OffsetDateTime.ofInstant(firstFire,ZoneOffset.UTC)).update();return schedule(sourceId);
  }
  @Transactional public List<Map<String,Object>> emitDue(Instant now,int limit){
    List<Map<String,Object>> due=db.sql("select schedule_id,source_id,onboarding_version_id,next_fire_at,interval_seconds from ouf_onboarding.pull_schedule where enabled and next_fire_at<=:n order by next_fire_at for update skip locked limit :l").param("n",OffsetDateTime.ofInstant(now,ZoneOffset.UTC)).param("l",limit).query().listOfRows();List<Map<String,Object>> out=new ArrayList<>();
    for(Map<String,Object> s:due){UUID id=UUID.randomUUID();db.sql("insert into ouf_onboarding.pull_dispatch(dispatch_id,schedule_id,source_id,onboarding_version_id,due_at) values(:i,:q,:s,:v,:d) on conflict(schedule_id,due_at) do nothing").param("i",id).param("q",s.get("schedule_id")).param("s",s.get("source_id")).param("v",s.get("onboarding_version_id")).param("d",s.get("next_fire_at")).update();db.sql("update ouf_onboarding.pull_schedule set next_fire_at=:n + make_interval(secs=>interval_seconds),lock_version=lock_version+1,updated_at=transaction_timestamp() where schedule_id=:q").param("n",OffsetDateTime.ofInstant(now,ZoneOffset.UTC)).param("q",s.get("schedule_id")).update();out.add(db.sql("select dispatch_id,schedule_id,source_id,onboarding_version_id,due_at,state,attempts,created_at from ouf_onboarding.pull_dispatch where schedule_id=:q and due_at=:d").param("q",s.get("schedule_id")).param("d",s.get("next_fire_at")).query().singleRow());}return out;
  }
  public Map<String,Object> schedule(String sourceId){return db.sql("select schedule_id,source_id,onboarding_version_id,interval_seconds,timezone,timeout_seconds,max_attempts,overlap_policy,enabled,next_fire_at,lock_version from ouf_onboarding.pull_schedule where source_id=:s").param("s",sourceId).query().singleRow();}
}
