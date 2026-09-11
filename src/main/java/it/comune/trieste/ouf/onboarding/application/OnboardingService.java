package it.comune.trieste.ouf.onboarding.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OnboardingService {
  private final JdbcClient db; private final ObjectMapper json; private final CanonicalHash hashes; private final Duration challengeTtl;
  public OnboardingService(JdbcClient db,ObjectMapper json,CanonicalHash hashes,@Value("${ouf.onboarding.approval-challenge-ttl:PT10M}") Duration challengeTtl){this.db=db;this.json=json;this.hashes=hashes;this.challengeTtl=challengeTtl;}

  @Transactional public Map<String,Object> createSource(String id,String name,String kind,String mode,String owner,Map<String,Object> metadata,Actor actor,String correlation){
    if("INTERNAL_MANAGED".equals(kind)&&!"MANAGED".equals(mode))throw bad("ONB_SOURCE_MODE_INVALID","INTERNAL_MANAGED requires MANAGED acquisition");
    db.sql("insert into ouf_onboarding.source(source_id,name,source_kind,acquisition_mode,owner_ref,metadata) values(:i,:n,:k,:m,:o,cast(:x as jsonb))")
      .param("i",id).param("n",name).param("k",kind).param("m",mode).param("o",owner).param("x",write(metadata)).update();
    audit(id,null,correlation,actor,"SOURCE_CREATED",Map.of("sourceKind",kind)); return source(id);
  }
  public Map<String,Object> source(String id){return jsonRow(db.sql("select source_id,name,source_kind,acquisition_mode,status,owner_ref,metadata::text metadata,lock_version,created_at,updated_at from ouf_onboarding.source where source_id=:i").param("i",id).query().listOfRows().stream().findFirst().orElseThrow(()->missing("source")),"metadata");}
  public List<Map<String,Object>> sources(){return db.sql("select source_id,name,source_kind,acquisition_mode,status,owner_ref,metadata::text metadata,lock_version,created_at,updated_at from ouf_onboarding.source order by source_id").query().listOfRows().stream().map(x->jsonRow(x,"metadata")).toList();}

  @Transactional public Map<String,Object> createVersion(String sourceId,Map<String,Object> configuration,Actor actor,String correlation){
    source(sourceId);db.sql("select 1 from (select pg_advisory_xact_lock(hashtextextended(:s,0))) locked").param("s",sourceId).query(Integer.class).single();UUID id=UUID.randomUUID();
    int version=db.sql("select coalesce(max(version),0)+1 from ouf_onboarding.onboarding_version where source_id=:s").param("s",sourceId).query(Integer.class).single();
    db.sql("insert into ouf_onboarding.onboarding_version(onboarding_version_id,source_id,version,state,configuration) values(:i,:s,:v,'DRAFT',cast(:c as jsonb))")
      .param("i",id).param("s",sourceId).param("v",version).param("c",write(configuration)).update();
    audit(sourceId,id,correlation,actor,"ONBOARDING_VERSION_CREATED",Map.of("version",version)); return version(sourceId,id);
  }
  public Map<String,Object> version(String sourceId,UUID id){return jsonRow(db.sql("select onboarding_version_id,source_id,version,state,lock_version,configuration::text configuration,configuration_hash,frozen_at,created_at,updated_at from ouf_onboarding.onboarding_version where source_id=:s and onboarding_version_id=:i").param("s",sourceId).param("i",id).query().listOfRows().stream().findFirst().orElseThrow(()->missing("version")),"configuration");}

  @Transactional public Map<String,Object> patchVersion(String sourceId,UUID id,long expected,Map<String,Object> configuration,Actor actor,String correlation){
    int rows=db.sql("update ouf_onboarding.onboarding_version set configuration=cast(:c as jsonb),lock_version=lock_version+1,updated_at=transaction_timestamp() where source_id=:s and onboarding_version_id=:i and state='DRAFT' and lock_version=:l")
      .param("c",write(configuration)).param("s",sourceId).param("i",id).param("l",expected).update();
    if(rows!=1)throw precondition(); audit(sourceId,id,correlation,actor,"DRAFT_UPDATED",Map.of("expectedLockVersion",expected)); return version(sourceId,id);
  }

  @Transactional public Map<String,Object> submit(String sourceId,UUID id,long expected,Actor actor,String correlation){
    Map<String,Object> current=version(sourceId,id);String hash=hashes.of(current.get("configuration"));
    int rows=db.sql("update ouf_onboarding.onboarding_version set state='IN_REVIEW',configuration_hash=:h,frozen_at=transaction_timestamp(),lock_version=lock_version+1,updated_at=transaction_timestamp() where source_id=:s and onboarding_version_id=:i and state='DRAFT' and lock_version=:l")
      .param("h",hash).param("s",sourceId).param("i",id).param("l",expected).update();
    if(rows!=1)throw precondition();audit(sourceId,id,correlation,actor,"VERSION_SUBMITTED",Map.of("configurationHash",hash));return version(sourceId,id);
  }

  @Transactional public Map<String,Object> createChallenge(String sourceId,UUID versionId,Actor actor,String correlation){
    Map<String,Object> v=version(sourceId,versionId);if(!"IN_REVIEW".equals(v.get("state")))throw invalid("Version must be IN_REVIEW");
    UUID id=UUID.randomUUID();Instant expires=Instant.now().plus(challengeTtl);
    db.sql("insert into ouf_onboarding.approval_challenge(challenge_id,onboarding_version_id,source_id,configuration_hash,status,expires_at) values(:c,:v,:s,:h,'CREATED',:e)")
      .param("c",id).param("v",versionId).param("s",sourceId).param("h",v.get("configuration_hash")).param("e",expires).update();
    audit(sourceId,versionId,correlation,actor,"APPROVAL_CHALLENGE_CREATED",Map.of("challengeId",id.toString()));
    return db.sql("select challenge_id,onboarding_version_id,source_id,configuration_hash,status,expires_at,created_at from ouf_onboarding.approval_challenge where challenge_id=:i").param("i",id).query().singleRow();
  }

  @Transactional public Map<String,Object> confirm(String sourceId,UUID versionId,UUID challengeId,Actor actor,String correlation,String authenticationContextRef){
    if(!"HUMAN_USER".equals(actor.type()))throw new DomainFailure(HttpStatus.FORBIDDEN,"ONB_HUMAN_APPROVAL_REQUIRED","Approval requires a HUMAN_USER identity");
    Map<String,Object> v=version(sourceId,versionId);
    int challenge=db.sql("update ouf_onboarding.approval_challenge set status='CONFIRMED',confirmed_at=transaction_timestamp() where challenge_id=:c and onboarding_version_id=:v and source_id=:s and status='CREATED' and expires_at>transaction_timestamp() and configuration_hash=:h")
      .param("c",challengeId).param("v",versionId).param("s",sourceId).param("h",v.get("configuration_hash")).update();
    if(challenge!=1)throw new DomainFailure(HttpStatus.CONFLICT,"ONB_APPROVAL_CHALLENGE_STALE","Challenge expired, stale, or already consumed");
    int changed=db.sql("update ouf_onboarding.onboarding_version set state='APPROVED',lock_version=lock_version+1,updated_at=transaction_timestamp() where onboarding_version_id=:v and source_id=:s and state='IN_REVIEW'")
      .param("v",versionId).param("s",sourceId).update();if(changed!=1)throw invalid("Version must be IN_REVIEW");
    db.sql("insert into ouf_onboarding.approval_decision(decision_id,challenge_id,onboarding_version_id,actor_subject,decision,target_hash,authentication_context_ref) values(:d,:c,:v,:a,'APPROVE',:h,:x)")
      .param("d",UUID.randomUUID()).param("c",challengeId).param("v",versionId).param("a",actor.subject()).param("h",v.get("configuration_hash")).param("x",authenticationContextRef).update();
    audit(sourceId,versionId,correlation,actor,"VERSION_APPROVED",Map.of("challengeId",challengeId.toString()));return version(sourceId,versionId);
  }

  @Transactional public Map<String,Object> activate(String sourceId,UUID versionId,Actor actor,String correlation){
    if(!"HUMAN_USER".equals(actor.type()))throw new DomainFailure(HttpStatus.FORBIDDEN,"ONB_HUMAN_ACTIVATION_REQUIRED","Activation requires a HUMAN_USER identity");
    Map<String,Object> v=version(sourceId,versionId);if(!"APPROVED".equals(v.get("state")))throw invalid("Version must be APPROVED");
    Map<String,Object> bundle=new LinkedHashMap<>();bundle.put("bundleVersion",v.get("version"));bundle.put("source",source(sourceId));bundle.put("configuration",v.get("configuration"));bundle.put("effectiveFrom",Instant.now().toString());String checksum=hashes.of(bundle);bundle.put("checksum",checksum);
    db.sql("update ouf_onboarding.published_configuration set active=false where source_id=:s and active=true").param("s",sourceId).update();
    db.sql("update ouf_onboarding.onboarding_version set state='SUPERSEDED',lock_version=lock_version+1,updated_at=transaction_timestamp() where source_id=:s and state='ACTIVE'").param("s",sourceId).update();
    db.sql("insert into ouf_onboarding.published_configuration(publication_id,source_id,onboarding_version_id,bundle_version,bundle,checksum,active,effective_from) values(:p,:s,:v,:n,cast(:b as jsonb),:h,true,transaction_timestamp())")
      .param("p",UUID.randomUUID()).param("s",sourceId).param("v",versionId).param("n",v.get("version")).param("b",write(bundle)).param("h",checksum).update();
    db.sql("update ouf_onboarding.onboarding_version set state='ACTIVE',lock_version=lock_version+1,updated_at=transaction_timestamp() where onboarding_version_id=:v and state='APPROVED'").param("v",versionId).update();
    audit(sourceId,versionId,correlation,actor,"VERSION_ACTIVATED",Map.of("checksum",checksum));return activeBundle(sourceId);
  }
  public Map<String,Object> activeBundle(String sourceId){return jsonRow(db.sql("select publication_id,source_id,onboarding_version_id,bundle_version,bundle::text bundle,checksum,effective_from from ouf_onboarding.published_configuration where source_id=:s and active=true").param("s",sourceId).query().listOfRows().stream().findFirst().orElseThrow(()->missing("active bundle")),"bundle");}

  private void audit(String source,UUID version,String correlation,Actor actor,String event,Object payload){db.sql("insert into ouf_onboarding.audit_event(audit_event_id,source_id,onboarding_version_id,correlation_id,actor_subject,actor_type,event_type,payload_summary) values(:i,:s,:v,:c,:a,:t,:e,cast(:p as jsonb))").param("i",UUID.randomUUID()).param("s",source).param("v",version).param("c",correlation).param("a",actor.subject()).param("t",actor.type()).param("e",event).param("p",write(payload)).update();}
  private String write(Object value){try{return json.writeValueAsString(value==null?Map.of():value);}catch(Exception e){throw bad("ONB_JSON_INVALID",e.getMessage());}}
  @SuppressWarnings("unchecked") private Map<String,Object> jsonRow(Map<String,Object> row,String field){try{Map<String,Object> out=new LinkedHashMap<>(row);Object value=out.get(field);if(value instanceof String s)out.put(field,json.readValue(s,Object.class));return out;}catch(Exception e){throw new IllegalStateException("database JSON decode failure",e);}}
  private static DomainFailure missing(String what){return new DomainFailure(HttpStatus.NOT_FOUND,"ONB_NOT_FOUND",what+" not found");}
  private static DomainFailure invalid(String message){return new DomainFailure(HttpStatus.UNPROCESSABLE_ENTITY,"ONB_INVALID_STATE_TRANSITION",message);}
  private static DomainFailure bad(String code,String message){return new DomainFailure(HttpStatus.BAD_REQUEST,code,message);}
  private static DomainFailure precondition(){return new DomainFailure(HttpStatus.PRECONDITION_FAILED,"ONB_ETAG_MISMATCH","Stale ETag or immutable version");}
  public record Actor(String subject,String type){}
}
