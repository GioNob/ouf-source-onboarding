package it.comune.trieste.ouf.onboarding.application;

import it.comune.trieste.ouf.onboarding.domain.*;
import java.sql.Types;
import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Owner-local profiling transitions; reuse the finite session model of managed readiness. */
@Component
public class ManagedProfileJobs {
  public record BudgetDecision(long expectedVersion,long expectedGeneration,String inputBindingHash,
      String reviewHash,String commandKey,String reason,int maximumAttempts,int maximumElapsedSeconds){}
  public record Resume(long expectedVersion,long expectedGeneration,String inputBindingHash,UUID sessionId){}
  public record Cancel(long expectedVersion,long expectedGeneration,String inputBindingHash,String commandKey,String reason){}
  @FunctionalInterface public interface Quarantine {void apply(UUID asset,UUID job,String code,String detail);}
  private final JdbcClient db;private final TransactionTemplate tx;private final CanonicalHash hashes;
  public ManagedProfileJobs(JdbcClient db,TransactionTemplate tx,CanonicalHash hashes){this.db=db;this.tx=tx;this.hashes=hashes;}
  private static DomainFailure denied(String code){return new DomainFailure(HttpStatus.CONFLICT,code,code);}
  private static void require(boolean valid,String code){if(!valid)throw denied(code);}
  private static long number(Map<String,Object> row,String name){return((Number)row.get(name)).longValue();}
  private static Instant instant(Object value){if(value instanceof OffsetDateTime d)return d.toInstant();if(value instanceof java.sql.Timestamp t)return t.toInstant();if(value instanceof Instant i)return i;throw denied("ONB_PROFILE_TIME_UNPROVEN");}
  private Instant now(){return db.sql("select clock_timestamp()").query(OffsetDateTime.class).single().toInstant();}
  private Map<String,Object> row(UUID asset,UUID job,boolean lock){return db.sql("select * from ouf_onboarding.file_profile_job where asset_id=:a and job_id=:j"+(lock?" for update":""))
      .param("a",asset).param("j",job).query().listOfRows().stream().findFirst().orElseThrow(()->denied("ONB_FILE_PROFILE_JOB_NOT_FOUND"));}
  private Map<String,Object> asset(UUID id){return db.sql("select * from ouf_onboarding.managed_file_asset where asset_id=:a").param("a",id).query().singleRow();}
  private String binding(Map<String,Object> a){return hashes.of(List.of("managed-profile-input.v1",a.get("asset_id").toString(),
      a.get("staging_ref"),a.get("content_hash"),a.get("media_type"),((Number)a.get("size_bytes")).longValue(),a.get("uploaded_by")));}
  private Map<String,Object> session(Map<String,Object> job){return job.get("recovery_session_id")==null?Map.of():db.sql("select * from ouf_onboarding.managed_profile_budget_session where session_id=:s and job_id=:j")
      .param("s",job.get("recovery_session_id")).param("j",job.get("job_id")).query().singleRow();}
  private Map<String,Object> quarantine(UUID asset,UUID job){return db.sql("select * from ouf_onboarding.managed_file_quarantine where asset_id=:a and profile_job_id=:j order by created_at desc,quarantine_id desc limit 1")
      .param("a",asset).param("j",job).query().listOfRows().stream().findFirst().orElse(Map.of());}
  private String reviewHash(Map<String,Object> j,Map<String,Object> a,Map<String,Object> q){return hashes.of(Arrays.asList(
      "managed-profile-review.v1",j.get("job_id").toString(),j.get("state"),number(j,"attempts"),number(j,"generation"),number(j,"lock_version"),
      j.get("recovery_session_id")==null?null:j.get("recovery_session_id").toString(),j.get("error_code"),hashes.of(j.get("last_error")),
      binding(a),a.get("status"),q.get("quarantine_id")==null?null:q.get("quarantine_id").toString(),q.get("state")));}
  private long base(Map<String,Object> s){return s.isEmpty()?0:number(s,"base_attempts");}
  private int maximum(Map<String,Object> s){return s.isEmpty()?5:3;}
  private Instant deadline(Map<String,Object> j,Map<String,Object> s){return s.isEmpty()?instant(j.get("created_at")).plusSeconds(900):instant(s.get("expires_at"));}
  private boolean budgetAvailable(Map<String,Object> j,Map<String,Object> s,Instant n){return number(j,"attempts")-base(s)<maximum(s)
      &&n.isBefore(deadline(j,s))&&(s.isEmpty()||"ACTIVE".equals(s.get("state"))&&s.get("activated_at")!=null);}
  private void event(UUID job,String type,String code,String subject,String tenant,String key,String requestHash,UUID decision){event(job,type,code,subject,tenant,key,requestHash,decision,null);}
  private void event(UUID job,String type,String code,String subject,String tenant,String key,String requestHash,UUID decision,String reason){
    db.sql("""
        insert into ouf_onboarding.managed_profile_event(event_id,job_id,job_version,generation,cumulative_attempts,
          session_id,event_type,safe_code,actor_subject,tenant_id,command_key,request_hash,decision_ref,reason)
        select gen_random_uuid(),job_id,lock_version,generation,attempts,recovery_session_id,:type,:code,:subject,:tenant,:key,:hash,:decision,:reason
        from ouf_onboarding.file_profile_job where job_id=:j
        """).param("type",type).param("code",code).param("subject",new SqlParameterValue(Types.VARCHAR,subject))
        .param("tenant",new SqlParameterValue(Types.VARCHAR,tenant)).param("key",new SqlParameterValue(Types.VARCHAR,key))
        .param("hash",new SqlParameterValue(Types.VARCHAR,requestHash)).param("decision",new SqlParameterValue(Types.OTHER,decision)).param("reason",new SqlParameterValue(Types.VARCHAR,reason)).param("j",job).update();
  }
  private void event(UUID job,String type,String code){event(job,type,code,null,null,null,null,null);}
  private void terminal(Map<String,Object> j,String code,String detail,String kind,Quarantine quarantine){
    db.sql("update ouf_onboarding.file_profile_job set state='FAILED',claimed_by=null,lease_until=null,error_code=:c,last_error=:d,failure_class=:f,completed_at=clock_timestamp(),updated_at=clock_timestamp(),generation=generation+1,lock_version=lock_version+1 where job_id=:j")
        .param("c",code).param("d",detail).param("f",kind).param("j",j.get("job_id")).update();
    db.sql("update ouf_onboarding.managed_profile_budget_session set state=:state where session_id=:s and state='ACTIVE'")
        .param("state","EXHAUSTED".equals(kind)?"EXHAUSTED":"BLOCKED").param("s",new SqlParameterValue(Types.OTHER,j.get("recovery_session_id"))).update();
    quarantine.apply((UUID)j.get("asset_id"),(UUID)j.get("job_id"),code,detail);event((UUID)j.get("job_id"),"BLOCKED",code);
  }
  /** Only reserve a claim here. The remote read happens after this transaction releases every lock. */
  public Optional<Map<String,Object>> claim(String worker,Duration lease,Quarantine quarantine){return tx.execute(status->{
    require(worker!=null&&!worker.isBlank()&&lease!=null&&lease.compareTo(Duration.ofSeconds(10))>=0&&lease.compareTo(Duration.ofMinutes(2))<=0,"ONB_PROFILE_LEASE_INVALID");
    // A deliberately narrow, gradual probe profile: at most one live Gateway read across this owner.
    db.sql("select 1 from pg_advisory_xact_lock(hashtextextended('managed-profile-claim.v1',0))").query(Integer.class).single();
    Instant n=now();if(db.sql("select exists(select 1 from ouf_onboarding.file_profile_job where state='RUNNING' and lease_until>clock_timestamp())").query(Boolean.class).single())return Optional.empty();
    var candidates=db.sql("""
        select j.* from ouf_onboarding.file_profile_job j where
          ((j.state in ('READY','RETRY_WAIT') and j.next_attempt_at<=clock_timestamp()) or
           (j.state='RUNNING' and j.lease_until<=clock_timestamp()))
        order by j.next_attempt_at,j.created_at,j.job_id for update skip locked limit 32
        """).query().listOfRows();
    for(var j:candidates){
      UUID a=(UUID)j.get("asset_id"),id=(UUID)j.get("job_id");var input=asset(a);var s=session(j);
      if(db.sql("select count(*) from ouf_onboarding.file_profile_job where asset_id=:a").param("a",a).query(Long.class).single()!=1){terminal(j,"ONB_PROFILE_HISTORY_RECONCILIATION_REQUIRED","Multiple retained profile jobs require reconciliation","UNKNOWN",quarantine);continue;}
      if(!Set.of("STAGED","PROFILED").contains(input.get("status"))){terminal(j,"ONB_PROFILE_CUSTODY_CHANGED","Asset is not admitted for profiling","CUSTODY_CHANGED",quarantine);continue;}
      if(j.get("input_binding_hash")==null&&number(j,"attempts")>0||j.get("input_binding_hash")!=null&&!binding(input).equals(j.get("input_binding_hash"))){
        terminal(j,"ONB_PROFILE_INPUT_RECONCILIATION_REQUIRED","Retained input binding requires reconciliation","CUSTODY_CHANGED",quarantine);continue;}
      if(!budgetAvailable(j,s,n)){terminal(j,"ONB_PROFILE_BUDGET_EXHAUSTED","Finite profiling budget exhausted","EXHAUSTED",quarantine);continue;}
      Instant end=n.plus(lease);if(end.isAfter(deadline(j,s)))end=deadline(j,s);
      var claimed=db.sql("""
          update ouf_onboarding.file_profile_job set state='RUNNING',claimed_by=:w,lease_until=:end,
            attempts=attempts+1,generation=generation+1,lock_version=lock_version+1,input_binding_hash=:binding,
            error_code=null,last_error=null,failure_class=null,completed_at=null,updated_at=clock_timestamp()
          where job_id=:j returning *
          """).param("w",worker).param("end",OffsetDateTime.ofInstant(end,ZoneOffset.UTC)).param("binding",binding(input)).param("j",id).query().singleRow();
      event(id,"CLAIMED","ONB_PROFILE_ATTEMPT_RESERVED");return Optional.of(claimed);
    }
    return Optional.empty();
  });}
  private Map<String,Object> ownedClaim(UUID job,String worker,long generation){
    var j=db.sql("select * from ouf_onboarding.file_profile_job where job_id=:j for update").param("j",job).query().singleRow();
    require("RUNNING".equals(j.get("state"))&&Objects.equals(worker,j.get("claimed_by"))&&number(j,"generation")==generation
        &&j.get("lease_until")!=null&&now().isBefore(instant(j.get("lease_until"))),"ONB_PROFILE_CLAIM_FENCED");
    var input=asset((UUID)j.get("asset_id"));require(Set.of("STAGED","PROFILED").contains(input.get("status"))&&binding(input).equals(j.get("input_binding_hash")),"ONB_PROFILE_INPUT_RECONCILIATION_REQUIRED");
    var s=session(j);require(now().isBefore(deadline(j,s))&&(s.isEmpty()||"ACTIVE".equals(s.get("state"))),"ONB_PROFILE_BUDGET_EXHAUSTED");return j;
  }
  public Map<String,Object> complete(UUID job,String worker,long generation,Supplier<Map<String,Object>> create){return tx.execute(status->{
    var j=ownedClaim(job,worker,generation);var profile=create.get();
    // Parsing and profile persistence share this transaction; no remote I/O is performed inside it.
    ownedClaim(job,worker,generation);
    db.sql("update ouf_onboarding.file_profile_job set state='SUCCEEDED',profile_id=:p,claimed_by=null,lease_until=null,completed_at=clock_timestamp(),updated_at=clock_timestamp(),lock_version=lock_version+1 where job_id=:j")
        .param("p",profile.get("profile_id")).param("j",job).update();
    db.sql("update ouf_onboarding.managed_profile_budget_session set state='COMPLETED' where session_id=:s and state='ACTIVE'")
        .param("s",new SqlParameterValue(Types.OTHER,j.get("recovery_session_id"))).update();event(job,"COMPLETED","ONB_PROFILE_SUCCEEDED");return profile;
  });}
  public void fail(UUID job,String worker,long generation,String detail,Duration retryAfter,boolean transientFailure,Quarantine quarantine){tx.executeWithoutResult(status->{
    require(retryAfter!=null&&!retryAfter.isNegative()&&retryAfter.compareTo(Duration.ofHours(24))<=0,"ONB_RETRY_DELAY_INVALID");
    var j=ownedClaim(job,worker,generation);var s=session(j);Instant n=now();long used=number(j,"attempts")-base(s);
    if(!transientFailure){terminal(j,"ONB_FILE_PROFILE_FAILED",detail,"Profile failure requires owner reconciliation".equals(detail)?"UNKNOWN":"PERMANENT",quarantine);return;}
    int base=switch((int)used){case 1->5;case 2->15;case 3->60;case 4->300;default->900;};
    long delay=Math.max(retryAfter.toSeconds(),ThreadLocalRandom.current().nextInt(base*4/5,base*6/5+1));
    if(used>=maximum(s)||!n.plusSeconds(delay).isBefore(deadline(j,s))){terminal(j,"ONB_PROFILE_BUDGET_EXHAUSTED",detail,"EXHAUSTED",quarantine);return;}
    db.sql("update ouf_onboarding.file_profile_job set state='RETRY_WAIT',claimed_by=null,lease_until=null,next_attempt_at=:next,error_code='ONB_FILE_PROFILE_FAILED',last_error=:d,failure_class='TRANSIENT',updated_at=clock_timestamp(),lock_version=lock_version+1 where job_id=:j")
        .param("next",OffsetDateTime.ofInstant(n.plusSeconds(delay),ZoneOffset.UTC)).param("d",detail).param("j",job).update();event(job,"RETRY_WAIT","ONB_PROFILE_TRANSIENT_READ");
  });}
  private boolean eligible(Map<String,Object> j,Map<String,Object> q){
    return "FAILED".equals(j.get("state"))&&!q.isEmpty()&&Set.of("OPEN","RELEASED").contains(q.get("state"))
        &&("EXHAUSTED".equals(j.get("failure_class"))||Set.of("Gateway object read failed with status 401","Gateway object read failed with status 403").contains(String.valueOf(j.get("last_error"))));
  }
  public Map<String,Object> card(UUID assetId,UUID job){var j=row(assetId,job,false);var a=asset(assetId);var q=quarantine(assetId,job);var s=session(j);Instant n=now();
    var out=new LinkedHashMap<String,Object>();out.put("assetId",assetId);out.put("jobId",job);out.put("status",j.get("state"));out.put("cumulativeAttempts",j.get("attempts"));
    out.put("expectedVersion",j.get("lock_version"));out.put("expectedGeneration",j.get("generation"));out.put("inputBindingHash",binding(a));out.put("reviewHash",reviewHash(j,a,q));
    out.put("contentHash",a.get("content_hash"));out.put("sizeBytes",a.get("size_bytes"));out.put("mediaType",a.get("media_type"));out.put("uploadedAt",a.get("created_at").toString());
    out.put("profile","managed-profile-recovery.v1");out.put("maximumAttempts",3);out.put("maximumElapsedSeconds",900);
    out.put("budgetAvailableToGrant",eligible(j,q)&&(s.isEmpty()||!"ACTIVE".equals(s.get("state"))||!n.isBefore(instant(s.get("expires_at")))));
    out.put("canResume",!s.isEmpty()&&"ACTIVE".equals(s.get("state"))&&s.get("activated_at")==null&&n.isBefore(instant(s.get("expires_at")))&&eligible(j,q));
    out.put("canCancel",!Set.of("SUCCEEDED","CANCELLED").contains(j.get("state")));
    if(!s.isEmpty()){out.put("sessionId",s.get("session_id"));out.put("sessionState",s.get("state"));out.put("sessionExpiresAt",s.get("expires_at").toString());out.put("sessionBaseAttempts",s.get("base_attempts"));out.put("decisionRef",s.get("decision_ref"));out.put("sessionUsedAttempts",number(j,"attempts")-base(s));}
    out.put("history",db.sql("select job_version,generation,cumulative_attempts,event_type,safe_code,created_at from ouf_onboarding.managed_profile_event where job_id=:j order by job_version desc limit 16").param("j",job).query().listOfRows());
    out.put("historyTruncated",db.sql("select count(*)>16 from ouf_onboarding.managed_profile_event where job_id=:j").param("j",job).query(Boolean.class).single());return out;
  }
  private void commandLock(String tenant,String subject,String key){db.sql("select 1 from pg_advisory_xact_lock(hashtextextended(:scope,0))").param("scope","managed-profile-command.v1:"+tenant.length()+":"+tenant+subject.length()+":"+subject+key).query(Integer.class).single();}
  private static void decisionValues(long version,long generation,String binding,String key,String reason){require(version>=0&&version<=9007199254740991L&&generation>=0&&generation<=9007199254740991L
      &&binding!=null&&binding.matches("sha256:[a-f0-9]{64}")&&key!=null&&key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
      &&reason!=null&&reason.strip().length()>=10&&reason.length()<=500&&reason.chars().noneMatch(Character::isISOControl),"ONB_PROFILE_DECISION_INVALID");}
  private void expected(Map<String,Object> j,Map<String,Object> a,long version,long generation,String hash){require(number(j,"lock_version")==version&&number(j,"generation")==generation&&binding(a).equals(hash),"ONB_PROFILE_STALE_REVIEW");}
  public Map<String,Object> grant(UUID assetId,UUID job,String tenant,String subject,BudgetDecision d){return tx.execute(status->{
    require(d!=null,"ONB_PROFILE_DECISION_INVALID");decisionValues(d.expectedVersion(),d.expectedGeneration(),d.inputBindingHash(),d.commandKey(),d.reason());
    require(d.maximumAttempts()==3&&d.maximumElapsedSeconds()==900,"ONB_PROFILE_DECISION_INVALID");
    commandLock(tenant,subject,d.commandKey());var j=row(assetId,job,true);var a=asset(assetId);String fingerprint=hashes.of(d);
    var prior=db.sql("select * from ouf_onboarding.managed_profile_budget_session where tenant_id=:t and subject_id=:s and command_key=:k")
        .param("t",tenant).param("s",subject).param("k",d.commandKey()).query().listOfRows().stream().findFirst();
    if(prior.isPresent()){require(job.equals(prior.get().get("job_id"))&&fingerprint.equals(prior.get().get("request_hash")),"ONB_PROFILE_COMMAND_CONFLICT");var receipt=new LinkedHashMap<>(card(assetId,job));receipt.put("reconciledSessionId",prior.get().get("session_id"));return receipt;}
    expected(j,a,d.expectedVersion(),d.expectedGeneration(),d.inputBindingHash());require(j.get("input_binding_hash")==null||binding(a).equals(j.get("input_binding_hash")),"ONB_PROFILE_INPUT_RECONCILIATION_REQUIRED");var q=quarantine(assetId,job);var s=session(j);
    require(reviewHash(j,a,q).equals(d.reviewHash()),"ONB_PROFILE_STALE_REVIEW");
    require(eligible(j,q)&&(s.isEmpty()||!"ACTIVE".equals(s.get("state"))||!now().isBefore(instant(s.get("expires_at"))))&&number(j,"attempts")<=Integer.MAX_VALUE-3,"ONB_PROFILE_RECOVERY_RECONCILIATION_REQUIRED");
    require(db.sql("select count(*) from ouf_onboarding.file_profile_job where asset_id=:a").param("a",assetId).query(Long.class).single()==1,"ONB_PROFILE_HISTORY_RECONCILIATION_REQUIRED");
    if(!s.isEmpty()&&"ACTIVE".equals(s.get("state")))db.sql("update ouf_onboarding.managed_profile_budget_session set state='EXHAUSTED' where session_id=:s and state='ACTIVE'").param("s",s.get("session_id")).update();
    UUID id=UUID.randomUUID(),decision=UUID.randomUUID();
    db.sql("""
        insert into ouf_onboarding.managed_profile_budget_session(session_id,job_id,asset_id,tenant_id,subject_id,command_key,
          request_hash,input_binding_hash,decision_ref,reason,profile,base_attempts,prior_job,prior_session_id,prior_quarantine_id,
          issued_job_version,issued_generation,maximum_attempts,maximum_elapsed_seconds,issued_at,expires_at,state)
        select :id,j.job_id,j.asset_id,:tenant,:subject,:key,:hash,:binding,:decision,:reason,'managed-profile-recovery.v1',
          j.attempts,to_jsonb(j),j.recovery_session_id,:quarantine,j.lock_version+1,j.generation+1,3,900,n.now,n.now+interval '900 seconds','ACTIVE'
        from ouf_onboarding.file_profile_job j cross join(select clock_timestamp() now)n where j.job_id=:j
        """).param("id",id).param("tenant",tenant).param("subject",subject).param("key",d.commandKey()).param("hash",fingerprint).param("binding",d.inputBindingHash())
        .param("decision",decision).param("reason",d.reason().strip()).param("quarantine",q.get("quarantine_id")).param("j",job).update();
    // Grant only: retain FAILED, attempts, timestamps and the original failure. No job is queued here.
    db.sql("update ouf_onboarding.file_profile_job set recovery_session_id=:s,input_binding_hash=:binding,generation=generation+1,lock_version=lock_version+1 where job_id=:j")
        .param("s",id).param("binding",d.inputBindingHash()).param("j",job).update();event(job,"BUDGET_GRANTED","ONB_PROFILE_HUMAN_BUDGET",subject,tenant,null,null,decision);return card(assetId,job);
  });}
  public Map<String,Object> resume(UUID assetId,UUID job,String subject,String tenant,Resume d,Runnable release){return tx.execute(status->{
    require(d!=null&&d.sessionId()!=null,"ONB_PROFILE_DECISION_INVALID");var j=row(assetId,job,true);var a=asset(assetId);var s=session(j);String fingerprint=hashes.of(d);
    require(!s.isEmpty()&&d.sessionId().equals(s.get("session_id"))&&subject.equals(s.get("subject_id"))&&tenant.equals(s.get("tenant_id"))&&binding(a).equals(d.inputBindingHash())&&binding(a).equals(s.get("input_binding_hash"))&&binding(a).equals(j.get("input_binding_hash")),"ONB_PROFILE_SESSION_MISMATCH");
    if(s.get("resume_request_hash")!=null){require(fingerprint.equals(s.get("resume_request_hash")),"ONB_PROFILE_COMMAND_CONFLICT");return card(assetId,job);}
    expected(j,a,d.expectedVersion(),d.expectedGeneration(),d.inputBindingHash());
    require("ACTIVE".equals(s.get("state"))&&now().isBefore(instant(s.get("expires_at")))&&eligible(j,quarantine(assetId,job)),"ONB_PROFILE_RECOVERY_RECONCILIATION_REQUIRED");
    release.run();db.sql("update ouf_onboarding.managed_profile_budget_session set activated_at=clock_timestamp(),resume_request_hash=:hash where session_id=:s and state='ACTIVE'")
        .param("hash",fingerprint).param("s",d.sessionId()).update();
    db.sql("update ouf_onboarding.file_profile_job set state='READY',completed_at=null,claimed_by=null,lease_until=null,next_attempt_at=clock_timestamp(),generation=generation+1,lock_version=lock_version+1,updated_at=clock_timestamp() where job_id=:j")
        .param("j",job).update();event(job,"RESUMED","ONB_PROFILE_HUMAN_RESUME",subject,tenant,null,null,(UUID)s.get("decision_ref"));return card(assetId,job);
  });}
  public Map<String,Object> cancel(UUID assetId,UUID job,String tenant,String subject,Cancel d){return tx.execute(status->{
    require(d!=null,"ONB_PROFILE_DECISION_INVALID");decisionValues(d.expectedVersion(),d.expectedGeneration(),d.inputBindingHash(),d.commandKey(),d.reason());
    commandLock(tenant,subject,d.commandKey());var j=row(assetId,job,true);String fingerprint=hashes.of(d);
    var retained=db.sql("select job_id,request_hash from ouf_onboarding.managed_profile_event where tenant_id=:t and actor_subject=:s and command_key=:k")
        .param("t",tenant).param("s",subject).param("k",d.commandKey()).query().listOfRows().stream().findFirst();
    if(retained.isPresent()){require(job.equals(retained.get().get("job_id"))&&fingerprint.equals(retained.get().get("request_hash")),"ONB_PROFILE_COMMAND_CONFLICT");return card(assetId,job);}
    expected(j,asset(assetId),d.expectedVersion(),d.expectedGeneration(),d.inputBindingHash());require(!Set.of("SUCCEEDED","CANCELLED").contains(j.get("state")),"ONB_PROFILE_CANCEL_CONFLICT");
    db.sql("update ouf_onboarding.file_profile_job set state='CANCELLED',failure_class='CANCELLED',claimed_by=null,lease_until=null,generation=generation+1,lock_version=lock_version+1,updated_at=clock_timestamp() where job_id=:j").param("j",job).update();
    db.sql("update ouf_onboarding.managed_profile_budget_session set state='CANCELLED' where session_id=:s and state='ACTIVE'")
        .param("s",new SqlParameterValue(Types.OTHER,j.get("recovery_session_id"))).update();event(job,"CANCELLED","ONB_PROFILE_HUMAN_CANCEL",subject,tenant,d.commandKey(),fingerprint,UUID.randomUUID(),d.reason().strip());return card(assetId,job);
  });}
}
