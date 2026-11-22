package it.comune.trieste.ouf.onboarding.application;

import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Durable admission and verification evidence for managed-value-map-preparation.v1. */
@Component
final class ManagedValueMapPreparation {
  static final String UNAVAILABLE = "ONB_DATATYPE_LOOKUP_UNAVAILABLE";
  private static final String EXHAUSTED = "ONB_VALUE_MAP_RETRY_EXHAUSTED";
  private static final String WHERE_KEY = " where tenant_id=:tenant and subject_id=:subject and command_key=:key";
  private static final String TABLE = "ouf_onboarding.managed_value_map_preparation";
  private static final String FENCE = " and phase='CHECKING' and attempts=:attempt and lease_until>clock_timestamp()";
  private static final int[] BACKOFF = {5,15,60,300,900};
  private final JdbcClient db;

  record Key(String tenant, String subject, String command) {}
  record Claim(Key key, int attempt) {}
  record Admission(Claim claim, DomainFailure failure) {}

  ManagedValueMapPreparation(JdbcClient db) { this.db=db; }

  void checkKey(Key key, String fingerprint) {
    var rows=keyed("select request_hash,phase,last_status,last_code from "+TABLE+WHERE_KEY,key).query().listOfRows();
    if(rows.isEmpty())return;
    var row=rows.getFirst();
    if(!fingerprint.equals(row.get("request_hash")))throw conflict();
    if("CANCELLED".equals(row.get("phase")))throw cancelled();
    if("BLOCKED".equals(row.get("phase")) || "EXHAUSTED".equals(row.get("phase")))throw retainedFailure(row);
  }

  /** Caller holds the review-command and draft advisory locks, for admission only. */
  Admission claim(Key key, UUID draft, String fingerprint, String selection) {
    if(db.sql("select exists(select 1 from "+TABLE+" where onboarding_version_id=:draft and lease_until>statement_timestamp())")
        .param("draft",draft).query(Boolean.class).single())
      throw temporary("CHECKING",5);
    keyed("""
        insert into ouf_onboarding.managed_value_map_preparation
          (tenant_id,subject_id,command_key,request_hash,onboarding_version_id,selection_hash)
        values(:tenant,:subject,:key,:hash,:draft,:selection) on conflict do nothing
        """,key).param("hash",fingerprint).param("draft",draft).param("selection",selection).update();
    var row=db.sql("""
        select tenant_id,subject_id,command_key,phase,last_status,last_code,attempts,deadline<=clock_timestamp() expired,
          ceil(extract(epoch from next_at-clock_timestamp()))::int wait_seconds
        from ouf_onboarding.managed_value_map_preparation
        where onboarding_version_id=:draft and selection_hash=:selection for update
        """).param("draft",draft).param("selection",selection).query().singleRow();
    if(!key.tenant().equals(row.get("tenant_id")) || !key.subject().equals(row.get("subject_id")))throw conflict();
    if("BLOCKED".equals(row.get("phase")) || "EXHAUSTED".equals(row.get("phase")))throw retainedFailure(row);
    if(!key.command().equals(row.get("command_key")) || "COMPLETED".equals(row.get("phase")))throw conflict();
    int attempts=(int)row.get("attempts");
    if(attempts>=128 || Boolean.TRUE.equals(row.get("expired"))){
      keyed("update "+TABLE+" set phase='EXHAUSTED',lease_until=null,next_at=null,last_code=:code,last_status=409"+WHERE_KEY,key)
          .param("code",EXHAUSTED).update();
      return new Admission(null,exhausted());
    }
    if(row.get("wait_seconds") instanceof Integer wait && wait>0)throw temporary("RETRY_WAIT",wait);
    // Serialize the short admission decision across replicas. The lease represents the in-flight read.
    boolean admission=db.sql("select pg_try_advisory_xact_lock(hashtextextended('managed-value-map-registry',0))")
        .query(Boolean.class).single();
    if(!admission)throw temporary("CAPACITY_WAIT",5);
    boolean full=db.sql("select count(*)>=2 or count(*) filter (where tenant_id=:tenant)>=1 from "+TABLE+
        " where lease_until>statement_timestamp()").param("tenant",key.tenant()).query(Boolean.class).single();
    if(full)throw temporary("CAPACITY_WAIT",5);
    keyed("""
        update ouf_onboarding.managed_value_map_preparation
        set phase='CHECKING',attempts=attempts+1,next_at=null,last_code=null,last_status=null,
            lease_until=least(deadline,clock_timestamp()+interval '180 seconds')
        """+WHERE_KEY,key).update();
    return new Admission(new Claim(key,attempts+1),null);
  }

  /** The first digest for a pin wins. The fresh Registry response is still verified on every attempt. */
  void observe(Claim claim, String ref, String digest) {
    String retained=fenced("""
        update ouf_onboarding.managed_value_map_preparation
        set digests=jsonb_build_object(cast(:ref as text),cast(:digest as text)) || digests
        """,claim," returning digests ->> cast(:ref as text)")
        .param("ref",ref).param("digest",digest).query(String.class).optional().orElseThrow(ManagedValueMapPreparation::leaseLost);
    if(!digest.equals(retained))throw DatatypeResolver.unresolvedValueMap();
  }

  DomainFailure failed(Claim claim, DomainFailure failure) {
    if(UNAVAILABLE.equals(failure.code())){
      int base=BACKOFF[Math.min(claim.attempt()-1,BACKOFF.length-1)];
      int delay=ThreadLocalRandom.current().nextInt(base*4/5,base*6/5+1);
      if(failure.properties().get("retryAfterSeconds") instanceof Number retryAfter)
        delay=Math.max(delay,retryAfter.intValue());
      var row=fenced("""
          update ouf_onboarding.managed_value_map_preparation
          set lease_until=null,
              next_at=least(deadline,statement_timestamp()+make_interval(secs=>:delay)),
              phase=case when attempts>=128 or statement_timestamp()+make_interval(secs=>:delay)>=deadline then 'EXHAUSTED' else 'RETRY_WAIT' end,
              last_code=case when attempts>=128 or statement_timestamp()+make_interval(secs=>:delay)>=deadline then :exhausted else :code end,
              last_status=case when attempts>=128 or statement_timestamp()+make_interval(secs=>:delay)>=deadline then 409 else 503 end
          """,claim," returning phase").param("delay",delay).param("exhausted",EXHAUSTED).param("code",UNAVAILABLE)
          .query(String.class).optional().orElseThrow(ManagedValueMapPreparation::leaseLost);
      return "EXHAUSTED".equals(row)?exhausted():temporary("RETRY_WAIT",delay);
    }
    if(fenced("update "+TABLE+" set phase='BLOCKED',lease_until=null,last_code=:code,last_status=:status",
        claim,"").param("code",failure.code()).param("status",failure.status().value()).update()!=1)throw leaseLost();
    return withState(failure,"BLOCKED");
  }

  void complete(Claim claim) {
    if(fenced("update "+TABLE+" set phase='COMPLETED',lease_until=null",claim,"").update()!=1)throw leaseLost();
  }

  void cancel(UUID draft) {
    db.sql("update "+TABLE+" set phase='CANCELLED',lease_until=null,next_at=null,last_code='ONB_REVIEW_STALE_VERSION',last_status=412"+
        " where onboarding_version_id=:draft and phase in ('CHECKING','RETRY_WAIT')").param("draft",draft).update();
  }

  private JdbcClient.StatementSpec fenced(String sql, Claim claim, String suffix) {
    return keyed(sql+WHERE_KEY+FENCE+suffix,claim.key()).param("attempt",claim.attempt());
  }

  private JdbcClient.StatementSpec keyed(String sql, Key key) {
    return db.sql(sql).param("tenant",key.tenant()).param("subject",key.subject()).param("key",key.command());
  }

  private static DomainFailure retainedFailure(Map<String,Object> row) {
    if("EXHAUSTED".equals(row.get("phase")))return exhausted();
    String code=(String)row.get("last_code");
    return withState("ONB_VALUE_MAP_UNRESOLVED".equals(code)?DatatypeResolver.unresolvedValueMap():
        new DomainFailure(HttpStatus.valueOf((int)row.get("last_status")),code,"Semantic preparation is blocked"),"BLOCKED");
  }

  private static DomainFailure withState(DomainFailure failure, String phase) {
    var properties=new LinkedHashMap<>(failure.properties());
    if(failure.status()!=HttpStatus.PRECONDITION_FAILED && failure.status()!=HttpStatus.FORBIDDEN)
      properties.put("state","DRAFT");
    properties.put("preparationPhase",phase);
    return new DomainFailure(failure.status(),failure.code(),failure.getMessage(),properties);
  }

  static DomainFailure temporary(String phase, int delay) {
    return withState(new DomainFailure(HttpStatus.SERVICE_UNAVAILABLE,UNAVAILABLE,
        "Registry verification is paused. Retry the same request and commandKey after retryAfterSeconds.",
        Map.of("retryAfterSeconds",delay)),phase);
  }

  private static DomainFailure exhausted() {
    return withState(new DomainFailure(HttpStatus.CONFLICT,EXHAUSTED,
        "The semantic verification retry budget is exhausted. Operator intervention is required."),"EXHAUSTED");
  }

  private static DomainFailure conflict() {
    return new DomainFailure(HttpStatus.CONFLICT,"ONB_REVIEW_COMMAND_CONFLICT","Retry the original semantic preparation with its commandKey");
  }

  private static DomainFailure leaseLost() {
    return new DomainFailure(HttpStatus.CONFLICT,"ONB_VALUE_MAP_PREPARATION_LEASE_LOST","Retry the same commandKey to reconcile semantic preparation");
  }

  private static DomainFailure cancelled() {
    return new DomainFailure(HttpStatus.PRECONDITION_FAILED,"ONB_REVIEW_STALE_VERSION","The draft was withdrawn",
        Map.of("state","REJECTED","preparationPhase","CANCELLED"));
  }
}
