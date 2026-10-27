package it.comune.trieste.ouf.onboarding.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.domain.*;
import java.sql.Types;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ManagedFileService {

  private static Map<String,Object> managedMetadata(UUID asset,UUID profile,Object hash,String tenant){
    var result=new LinkedHashMap<String,Object>();result.put("managedFileAssetId",asset.toString());
    result.put("fileProfileId",profile.toString());result.put("contentHash",hash);
    if(tenant!=null){if(tenant.isBlank()||tenant.length()>256)throw invalid("ONB_FILE_TENANT_INVALID","Explicit authenticated tenant required");result.put("managedTenantId",tenant);}
    return result;
  }
  private static final String TENANT_OWNER_PREDICATE="""
      a.uploaded_by=:subject and (
        exists(select 1 from ouf_onboarding.managed_file_upload_attempt u where u.asset_id=a.asset_id and u.tenant_id=:tenant and u.subject_id=:subject)
        or exists(select 1 from ouf_onboarding.source s join ouf_onboarding.managed_onboarding_review r on r.source_id=s.source_id
          where s.metadata->>'managedFileAssetId'=a.asset_id::text and r.tenant_id=:tenant and r.subject_id=:subject
          and (not(jsonb_exists(s.metadata,'managedTenantId')) or s.metadata->>'managedTenantId'=:tenant)))
      """;
  public void requireTenantOwner(UUID asset,String tenant,String subject){
    if(tenant==null||tenant.isBlank()||subject==null||subject.isBlank())throw invalid("ONB_FILE_TENANT_INVALID","Explicit authenticated owner required");
    if(db.sql("select 1 from ouf_onboarding.managed_file_asset a where a.asset_id=:asset and "+TENANT_OWNER_PREDICATE)
        .param("asset",asset).param("tenant",tenant).param("subject",subject).query(Integer.class).optional().isEmpty())
      throw new DomainFailure(HttpStatus.FORBIDDEN,"ONB_FILE_OWNER_SCOPE_UNPROVEN","Managed file owner scope is not proven");
  }
  /** Only durable tenant+subject proofs; legacy unbound assets are not inferred. */
  public Map<String,Object> ownedAssets(String tenant,String subject,int limit,UUID after){
    if(tenant==null||tenant.isBlank()||subject==null||subject.isBlank()||limit<1||limit>50)throw invalid("ONB_FILE_ASSET_QUERY_INVALID","Bounded authenticated owner query required");
    if(after!=null)requireTenantOwner(after,tenant,subject);
    var rows=db.sql("""
        select a.asset_id,a.created_at,a.status,a.media_type,a.size_bytes,f.profile_id,
          j.job_id,j.state profile_job_state,j.error_code profile_job_error_code,
          case when s.metadata->>'managedTenantId'=:tenant or
            (not(jsonb_exists(s.metadata,'managedTenantId')) and exists(select 1 from ouf_onboarding.managed_onboarding_review r
              where r.source_id=s.source_id and r.tenant_id=:tenant and r.subject_id=:subject))
            then a.source_id else null end source_id
        from ouf_onboarding.managed_file_asset a
        left join ouf_onboarding.source s on s.source_id=a.source_id
        left join lateral(select profile_id from ouf_onboarding.file_profile p where p.asset_id=a.asset_id order by version desc,profile_id desc limit 1) f on true
        left join lateral(select job_id,state,error_code from ouf_onboarding.file_profile_job p where p.asset_id=a.asset_id order by created_at desc,job_id desc limit 1) j on true
        where
        """+TENANT_OWNER_PREDICATE+" and (cast(:after as uuid) is null or a.asset_id>cast(:after as uuid)) order by a.asset_id limit :limit")
      .param("tenant",tenant).param("subject",subject).param("after",new SqlParameterValue(Types.OTHER,after)).param("limit",limit+1).query().listOfRows();
    boolean more=rows.size()>limit;var items=new ArrayList<Map<String,Object>>();
    for(var row:rows.subList(0,Math.min(rows.size(),limit))){
      var item=new LinkedHashMap<String,Object>();item.put("assetId",row.get("asset_id"));item.put("status",row.get("status"));
      item.put("uploadedAt",row.get("created_at").toString());item.put("mediaType",row.get("media_type"));item.put("sizeBytes",row.get("size_bytes"));
      // Derived only from metadata already authorized for this asset. No filename is inferred.
      item.put("workLabel","CSV · "+item.get("uploadedAt")+" · "+item.get("sizeBytes")+" B");
      if(row.get("job_id")!=null){
        var job=new LinkedHashMap<String,Object>();job.put("jobId",row.get("job_id"));job.put("status","READY".equals(row.get("profile_job_state"))?"QUEUED":row.get("profile_job_state"));
        // Details remain in exact-job preview; do not expose last_error or arbitrary error strings here.
        if(Set.of("ONB_FILE_PROFILE_FAILED","ONB_PROFILE_LEASE_EXHAUSTED").contains(String.valueOf(row.get("profile_job_error_code"))))job.put("errorCode",row.get("profile_job_error_code"));
        item.put("profileJob",job);
      }
      if(row.get("profile_id")!=null)item.put("profileId",row.get("profile_id"));
      if(row.get("source_id")!=null)item.put("sourceId",row.get("source_id"));items.add(item);
    }
    var result=new LinkedHashMap<String,Object>();result.put("items",items);result.put("hasMore",more);
    if(more)result.put("nextAfterAssetId",items.getLast().get("assetId"));
    result.put("scope","VERIFIED_TENANT_OWNER");result.put("historicalCoverage","UPLOAD_OR_REVIEW_RECEIPTS");
    return result;
  }

  public record FieldDecision(String fieldName,String extractionDecision,String dataAccessLabel,String targetPropertyIri,String transform,String vocabularyId,String vocabularyVersion,String valueMapRef){}
  private static final long MAX_PROFILE_BYTES=10L*1024*1024;
  private final JdbcClient db; private final ObjectMapper json; private final CanonicalHash hashes; private final ManagedFileProfiler profiler; private final TransactionTemplate tx; private final OnboardingService onboarding; private final ManagedProfileJobs profileJobs;
  public ManagedFileService(JdbcClient db,ObjectMapper json,CanonicalHash hashes,ManagedFileProfiler profiler,TransactionTemplate tx,OnboardingService onboarding,ManagedProfileJobs profileJobs){this.db=db;this.json=json;this.hashes=hashes;this.profiler=profiler;this.tx=tx;this.onboarding=onboarding;this.profileJobs=profileJobs;}
  public Map<String,Object> register(String sourceId,String stagingRef,String contentHash,String mediaType,long size,String uploadedBy,String retentionRef){
    if(!stagingRef.startsWith("object://"))throw invalid("ONB_STAGING_REF_INVALID","stagingRef must use object://");if(size<1||size>MAX_PROFILE_BYTES)throw invalid("ONB_FILE_SIZE_INVALID","File exceeds the 10 MiB deterministic profiling limit");UUID id=UUID.randomUUID();
    return tx.execute(status->{db.sql("insert into ouf_onboarding.managed_file_asset(asset_id,source_id,staging_ref,content_hash,media_type,size_bytes,uploaded_by,retention_ref) values(:i,:s,:r,:h,:m,:z,:u,:x) on conflict(staging_ref,content_hash) do nothing").param("i",id).param("s",new SqlParameterValue(Types.VARCHAR,sourceId)).param("r",stagingRef).param("h",contentHash).param("m",mediaType).param("z",size).param("u",uploadedBy).param("x",retentionRef).update();return db.sql("select asset_id,source_id,staging_ref,content_hash,media_type,size_bytes,uploaded_by,retention_ref,status,created_at from ouf_onboarding.managed_file_asset where staging_ref=:r and content_hash=:h").param("r",stagingRef).param("h",contentHash).query().singleRow();});
  }
  /** Serialize an upload attempt across retries before writing to object storage. */
  public Map<String,Object> registerDelegatedUpload(String tenant,String subject,String fileId,String key,String hash,long size,java.util.function.Supplier<String> put){
    if(tenant==null||tenant.isBlank()||subject==null||subject.isBlank()||fileId==null||!fileId.matches("file_[A-Za-z0-9_-]{1,128}")
        ||key==null||!key.matches("[A-Za-z0-9_.:-]{1,128}")||hash==null||!hash.matches("sha256:[0-9a-f]{64}")
        ||size<1||size>MAX_PROFILE_BYTES)throw invalid("ONB_FILE_UPLOAD_INVALID","Invalid delegated upload metadata");
    return tx.execute(status->{
      db.sql("select 1 as acquired from pg_advisory_xact_lock(hashtextextended(:i,0))")
          .param("i",tenant.length()+":"+tenant+subject.length()+":"+subject+key).query(Integer.class).single();
      var previous=db.sql("select file_id,content_hash,size_bytes,asset_id from ouf_onboarding.managed_file_upload_attempt where tenant_id=:t and subject_id=:s and idempotency_key=:k for update")
          .param("t",tenant).param("s",subject).param("k",key).query().listOfRows();
      if(!previous.isEmpty()){
        var row=previous.get(0);
        if(!fileId.equals(row.get("file_id"))||!hash.equals(row.get("content_hash"))||size!=((Number)row.get("size_bytes")).longValue())
          throw conflict("ONB_FILE_UPLOAD_IDEMPOTENCY_CONFLICT","Idempotency key used for different file content or identity");
        var existing=asset((UUID)row.get("asset_id"));
        return Map.<String,Object>of("assetId",row.get("asset_id"),"status",existing.get("status"));
      }
      String ref=put.get();
      var asset=register(null,ref,hash,"text/csv",size,subject,"retention://managed-files/30d");
      db.sql("insert into ouf_onboarding.managed_file_upload_attempt(tenant_id,subject_id,file_id,idempotency_key,content_hash,size_bytes,asset_id) values(:t,:s,:f,:k,:h,:z,:a)")
          .param("t",tenant).param("s",subject).param("f",fileId).param("k",key).param("h",hash).param("z",size).param("a",asset.get("asset_id")).update();
      return Map.<String,Object>of("assetId",asset.get("asset_id"),"status","STAGED");
    });
  }
  /** The caller's capability alone does not grant access to every staged file. */
  public void requireOwner(UUID assetId,String subject){
    if(subject==null||subject.isBlank()||!subject.equals(asset(assetId).get("uploaded_by")))
      throw new DomainFailure(HttpStatus.FORBIDDEN,"ONB_FILE_OWNER_DENIED","Managed file is not owned by this subject");
  }
  public Map<String,Object> profile(UUID assetId,byte[] bytes,String sampleRef){
    Map<String,Object> asset=asset(assetId);if(((Number)asset.get("size_bytes")).longValue()!=bytes.length)throw invalid("ONB_FILE_SIZE_MISMATCH","Staged size does not match downloaded content");String actual=hashes.ofBytes(bytes);if(!actual.equals(asset.get("content_hash")))throw invalid("ONB_FILE_HASH_MISMATCH","Staged content hash does not match downloaded content");ManagedFileProfiler.Profile result=profiler.profile(bytes,String.valueOf(asset.get("media_type")));
    return tx.execute(status->{db.sql("select 1 as acquired from pg_advisory_xact_lock(hashtextextended(:i,0))").param("i",assetId.toString()).query(Integer.class).single();int version=db.sql("select coalesce(max(version),0)+1 from ouf_onboarding.file_profile where asset_id=:a").param("a",assetId).query(Integer.class).single();UUID profileId=UUID.randomUUID();db.sql("insert into ouf_onboarding.file_profile(profile_id,asset_id,version,format,metadata,inferred_schema,candidate_keys,sample_ref,redacted_sample) values(:i,:a,:v,:f,cast(:m as jsonb),cast(:s as jsonb),cast(:k as jsonb),:r,cast(:q as jsonb))").param("i",profileId).param("a",assetId).param("v",version).param("f",result.format()).param("m",write(result.metadata())).param("s",write(result.columns())).param("k",write(result.candidateKeys())).param("r",sampleRef).param("q",write(result.sample())).update();db.sql("update ouf_onboarding.managed_file_asset set status='PROFILED' where asset_id=:a and status in ('STAGED','PROFILED')").param("a",assetId).update();return fileProfile(assetId,profileId);});
  }
  public Map<String,Object> requestProfile(UUID assetId,String idempotencyKey){
    asset(assetId);if(idempotencyKey==null||idempotencyKey.isBlank())throw invalid("ONB_IDEMPOTENCY_KEY_REQUIRED","Idempotency-Key must not be blank");
    return tx.execute(status->{
      // The immutable asset is the logical profiling operation. A new chat/key is not a new budget.
      db.sql("select asset_id from ouf_onboarding.managed_file_asset where asset_id=:a for update").param("a",assetId).query(UUID.class).single();
      var exact=db.sql("select * from ouf_onboarding.file_profile_job where asset_id=:a and idempotency_key=:k").param("a",assetId).param("k",idempotencyKey).query().listOfRows();
      if(!exact.isEmpty())return jobView(exact.getFirst());
      var retained=db.sql("select * from ouf_onboarding.file_profile_job where asset_id=:a order by created_at,job_id limit 2").param("a",assetId).query().listOfRows();
      if(retained.size()>1)throw conflict("ONB_PROFILE_HISTORY_RECONCILIATION_REQUIRED","Multiple retained profile jobs require explicit owner reconciliation");
      if(retained.size()==1)return jobView(retained.getFirst());
      UUID jobId=UUID.randomUUID();
      db.sql("insert into ouf_onboarding.file_profile_job(job_id,asset_id,idempotency_key) values(:i,:a,:k)").param("i",jobId).param("a",assetId).param("k",idempotencyKey).update();
      return profileJob(assetId,jobId);
    });
  }
  public Map<String,Object> profileJob(UUID assetId,UUID jobId){
    return jobView(db.sql("select * from ouf_onboarding.file_profile_job where asset_id=:a and job_id=:i").param("a",assetId).param("i",jobId).query().listOfRows().stream().findFirst().orElseThrow(()->new DomainFailure(HttpStatus.NOT_FOUND,"ONB_FILE_PROFILE_JOB_NOT_FOUND","File profile job not found")));
  }
  public Optional<Map<String,Object>> claimProfile(String worker,Duration lease,Instant ignoredOperatorClock){
    return profileJobs.claim(worker,lease,this::profileQuarantine).map(row->{var out=new LinkedHashMap<>(row);out.putAll(asset((UUID)row.get("asset_id")));return out;});
  }
  public Map<String,Object> completeProfile(UUID jobId,String worker,long generation,byte[] bytes,String sampleRef){
    var row=db.sql("select asset_id from ouf_onboarding.file_profile_job where job_id=:j").param("j",jobId).query().singleRow();UUID asset=(UUID)row.get("asset_id");
    profileJobs.complete(jobId,worker,generation,()->profile(asset,bytes,sampleRef));return profileJob(asset,jobId);
  }
  public Map<String,Object> failProfile(UUID jobId,String worker,long generation,String errorCode,String detail,Duration retryAfter,boolean retryable){
    // The only runtime caller supplies the typed failure policy; arbitrary raw exception text is not persisted.
    profileJobs.fail(jobId,worker,generation,safe(detail),retryAfter,retryable,this::profileQuarantine);
    UUID asset=db.sql("select asset_id from ouf_onboarding.file_profile_job where job_id=:j").param("j",jobId).query(UUID.class).single();return profileJob(asset,jobId);
  }
  private void profileQuarantine(UUID asset,UUID job,String code,String detail){quarantine(asset,job,code,detail,"profile-job:"+job);}
  private void recoveryActor(it.comune.trieste.ouf.authorization.PrincipalContext p,OnboardingService.Actor actor,UUID asset,boolean write){
    if(p==null||p.actorType()!=it.comune.trieste.ouf.authorization.PrincipalContext.ActorType.HUMAN||actor==null||!"HUMAN_USER".equals(actor.type())||!p.subjectId().equals(actor.subject())
        ||!actor.capabilities().contains("ouf.managed-source.file.profile")||write&&!actor.capabilities().contains("ouf.onboarding.quarantine.release"))
      throw new DomainFailure(HttpStatus.FORBIDDEN,"ONB_PROFILE_HUMAN_CAPABILITY_REQUIRED","Explicit HUMAN profiling and quarantine recovery authority required");
    requireTenantOwner(asset,p.tenantId(),p.subjectId());
  }
  public Map<String,Object> profileRecoveryCard(UUID asset,UUID job,it.comune.trieste.ouf.authorization.PrincipalContext p,OnboardingService.Actor actor){recoveryActor(p,actor,asset,false);return profileJobs.card(asset,job);}
  public Map<String,Object> grantProfileBudget(UUID asset,UUID job,it.comune.trieste.ouf.authorization.PrincipalContext p,OnboardingService.Actor actor,ManagedProfileJobs.BudgetDecision d){
    recoveryActor(p,actor,asset,true);return profileJobs.grant(asset,job,p.tenantId(),p.subjectId(),d);
  }
  public Map<String,Object> resumeProfile(UUID asset,UUID job,it.comune.trieste.ouf.authorization.PrincipalContext p,OnboardingService.Actor actor,ManagedProfileJobs.Resume d){
    recoveryActor(p,actor,asset,true);return profileJobs.resume(asset,job,p.subjectId(),p.tenantId(),d,()->{
      var q=db.sql("select quarantine_id,state from ouf_onboarding.managed_file_quarantine where asset_id=:a and profile_job_id=:j order by created_at desc,quarantine_id desc limit 1 for update")
          .param("a",asset).param("j",job).query().singleRow();
      if("OPEN".equals(q.get("state")))resolveQuarantine((UUID)q.get("quarantine_id"),"RELEASED",actor,"profile-budget:"+d.sessionId(),"profile-job:"+job);
      else if(!"RELEASED".equals(q.get("state")))throw conflict("ONB_PROFILE_QUARANTINE_REJECTED","Rejected quarantine cannot be resumed");
    });
  }
  public Map<String,Object> cancelProfile(UUID asset,UUID job,it.comune.trieste.ouf.authorization.PrincipalContext p,OnboardingService.Actor actor,ManagedProfileJobs.Cancel d){
    recoveryActor(p,actor,asset,true);return profileJobs.cancel(asset,job,p.tenantId(),p.subjectId(),d);
  }
  public List<Map<String,Object>> quarantines(String state){String selected=state==null?"OPEN":state;if(!Set.of("OPEN","RELEASED","REJECTED").contains(selected))throw invalid("ONB_QUARANTINE_STATE_INVALID","Unsupported quarantine state");return db.sql("select quarantine_id,asset_id,profile_job_id,reason_code,evidence_ref,safe_detail,state,created_at,resolved_at,resolved_by_subject,correlation_id from ouf_onboarding.managed_file_quarantine where state=:s order by created_at desc").param("s",selected).query().listOfRows();}
  public Map<String,Object> quarantine(UUID id){return db.sql("select quarantine_id,asset_id,profile_job_id,reason_code,evidence_ref,safe_detail,state,created_at,resolved_at,resolved_by_subject,correlation_id from ouf_onboarding.managed_file_quarantine where quarantine_id=:i").param("i",id).query().listOfRows().stream().findFirst().orElseThrow(()->new DomainFailure(HttpStatus.NOT_FOUND,"ONB_QUARANTINE_NOT_FOUND","Managed-file quarantine item not found"));}
  public Map<String,Object> resolveQuarantine(UUID id,String decision,OnboardingService.Actor actor,String authorizationContext,String correlation){if(!"HUMAN_USER".equals(actor.type()))throw new DomainFailure(HttpStatus.FORBIDDEN,"THS_HUMAN_USER_REQUIRED","Quarantine remediation requires HUMAN_USER");String capability="RELEASED".equals(decision)?"ouf.onboarding.quarantine.release":"ouf.onboarding.quarantine.reject";if(!actor.capabilities().contains(capability))throw new DomainFailure(HttpStatus.FORBIDDEN,"ONB_QUARANTINE_CAPABILITY_REQUIRED","Authorization did not grant "+capability);return tx.execute(s->{Map<String,Object> q=quarantine(id);int n=db.sql("update ouf_onboarding.managed_file_quarantine set state=:d,resolved_at=transaction_timestamp(),resolved_by_subject=:a,authorization_context_ref=:x where quarantine_id=:i and state='OPEN'").param("d",decision).param("a",actor.subject()).param("x",authorizationContext).param("i",id).update();if(n!=1)throw conflict("ONB_QUARANTINE_NOT_OPEN","Quarantine item is not open");if("RELEASED".equals(decision))db.sql("update ouf_onboarding.managed_file_asset set status='STAGED' where asset_id=:a and status='QUARANTINED'").param("a",q.get("asset_id")).update();db.sql("insert into ouf_onboarding.audit_event(audit_event_id,source_id,onboarding_version_id,correlation_id,actor_subject,actor_type,event_type,payload_summary) values(gen_random_uuid(),(select source_id from ouf_onboarding.managed_file_asset where asset_id=:a),null,:c,:s,'HUMAN_USER',:e,jsonb_build_object('quarantineId',:q,'authorizationContextRef',:x))").param("a",q.get("asset_id")).param("c",correlation).param("s",actor.subject()).param("e","MANAGED_FILE_QUARANTINE_"+decision).param("q",id.toString()).param("x",authorizationContext).update();return quarantine(id);});}
  private void quarantine(UUID asset,UUID job,String code,String detail,String correlation){UUID id=UUID.randomUUID();db.sql("insert into ouf_onboarding.managed_file_quarantine(quarantine_id,asset_id,profile_job_id,reason_code,evidence_ref,safe_detail,correlation_id) values(:i,:a,:j,:c,:e,:d,:r) on conflict do nothing").param("i",id).param("a",asset).param("j",job).param("c",code).param("e","quarantine://managed-files/"+asset+"/"+id).param("d",detail).param("r",correlation).update();db.sql("update ouf_onboarding.managed_file_asset set status='QUARANTINED' where asset_id=:a").param("a",asset).update();}
  private static String safe(String value){if(value==null)return null;String redacted=value.replaceAll("(?i)(password|token|secret|authorization)\\s*[=:]\\s*[^\\s,;]+","$1=[REDACTED]");return redacted.length()>512?redacted.substring(0,512):redacted;}
  public Map<String,Object> asset(UUID id){return db.sql("select asset_id,source_id,staging_ref,content_hash,media_type,size_bytes,uploaded_by,retention_ref,status,created_at from ouf_onboarding.managed_file_asset where asset_id=:i").param("i",id).query().listOfRows().stream().findFirst().orElseThrow(()->new DomainFailure(HttpStatus.NOT_FOUND,"ONB_FILE_ASSET_NOT_FOUND","Managed file asset not found"));}
  public Map<String,Object> fileProfile(UUID asset,UUID profile){return jsonRow(db.sql("select profile_id,asset_id,version,format,metadata::text metadata,inferred_schema::text inferred_schema,candidate_keys::text candidate_keys,sample_ref,redacted_sample::text redacted_sample,created_at from ouf_onboarding.file_profile where asset_id=:a and profile_id=:p").param("a",asset).param("p",profile).query().listOfRows().stream().findFirst().orElseThrow(()->new DomainFailure(HttpStatus.NOT_FOUND,"ONB_FILE_PROFILE_NOT_FOUND","File profile not found")),"metadata","inferred_schema","candidate_keys","redacted_sample");}
  public Map<String,Object> preview(UUID assetId,UUID profileId){Map<String,Object> profile=fileProfile(assetId,profileId);@SuppressWarnings("unchecked") List<String> candidates=(List<String>)profile.get("candidate_keys");@SuppressWarnings("unchecked") List<Map<String,Object>> columns=(List<Map<String,Object>>)profile.get("inferred_schema");Map<String,Object> identity=candidates.isEmpty()?Map.of("strategy","MANAGED_DETERMINISTIC","sourceFields",List.of("$managedRowOrdinal"),"normalizationRuleRef","normalization://managed-file/asset-row-ordinal-v1","stabilityLimit","Identity is unique per row inside the immutable asset and changes if rows are reordered"):Map.of("strategy","NATIVE_KEY","sourceFields",List.of(candidates.get(0)),"normalizationRuleRef","normalization://managed-file/native-key-v1","stabilityLimit","Identity remains stable while the proposed key remains stable");Map<String,Object> out=new LinkedHashMap<>();out.put("assetId",assetId);out.put("profileId",profileId);out.put("profileVersion",profile.get("version"));out.put("format",profile.get("format"));out.put("metadata",profile.get("metadata"));out.put("columns",columns);out.put("candidateKeys",candidates);out.put("redactedSample",profile.get("redacted_sample"));out.put("recordModel","ONE_ROW_ONE_SOURCE_OBJECT");out.put("identityProposal",Set.of("GEOPACKAGE","ACCESS","SHAPEFILE").contains(profile.get("format"))?Map.of("status","LAYER_AND_STABLE_KEYS_REQUIRED"):identity);out.put("proposalStatus","PENDING_HUMAN_REVIEW");return out;}
  public Map<String,Object> onboard(UUID assetId,UUID profileId,String sourceId,String name,String owner,String targetClassIri,List<String> semanticRefs,List<String> sourceObjectKeyFields,OnboardingService.Actor actor,String correlation){
    return onboard(assetId,profileId,sourceId,name,owner,targetClassIri,semanticRefs,sourceObjectKeyFields,List.of(),actor,correlation);
  }
  public Map<String,Object> onboard(UUID assetId,UUID profileId,String sourceId,String name,String owner,String targetClassIri,List<String> semanticRefs,List<String> sourceObjectKeyFields,List<FieldDecision> fieldDecisions,OnboardingService.Actor actor,String correlation){
    return onboard(assetId,profileId,sourceId,name,owner,targetClassIri,semanticRefs,sourceObjectKeyFields,fieldDecisions,null,actor,correlation);
  }
  /** A retry returns the original version identity; changed arguments cannot reuse the key. */
  public Map<String,Object> onboardIdempotent(UUID assetId,UUID profileId,String sourceId,String name,String owner,String targetClassIri,List<String> semanticRefs,List<String> sourceObjectKeyFields,List<FieldDecision> decisions,String layer,OnboardingService.Actor actor,String correlation,String key){
    return onboardIdempotent(assetId,profileId,sourceId,name,owner,targetClassIri,semanticRefs,sourceObjectKeyFields,decisions,layer,actor,correlation,key,null);
  }
  public Map<String,Object> onboardIdempotent(UUID assetId,UUID profileId,String sourceId,String name,String owner,String targetClassIri,List<String> semanticRefs,List<String> sourceObjectKeyFields,List<FieldDecision> decisions,String layer,OnboardingService.Actor actor,String correlation,String key,String tenant){
    if(key==null||!key.matches("[A-Za-z0-9_.:-]{1,128}"))throw invalid("ONB_IDEMPOTENCY_KEY_INVALID","Invalid onboarding idempotency key");
    String fingerprint=hashes.of(tenant==null?Arrays.asList(assetId,profileId,sourceId,name,owner,targetClassIri,semanticRefs,sourceObjectKeyFields,decisions,layer):Arrays.asList(assetId,profileId,sourceId,name,owner,targetClassIri,semanticRefs,sourceObjectKeyFields,decisions,layer,tenant));
    return tx.execute(status->{
      requireOwner(assetId,actor.subject());
      if(tenant!=null)requireTenantOwner(assetId,tenant,actor.subject());
      db.sql("insert into ouf_onboarding.managed_file_onboarding_attempt(asset_id,subject_id,idempotency_key,request_hash) values(:a,:s,:k,:h) on conflict do nothing")
          .param("a",assetId).param("s",actor.subject()).param("k",key).param("h",fingerprint).update();
      Map<String,Object> attempt=db.sql("select request_hash,source_id,onboarding_version_id from ouf_onboarding.managed_file_onboarding_attempt where asset_id=:a and subject_id=:s and idempotency_key=:k for update")
          .param("a",assetId).param("s",actor.subject()).param("k",key).query().singleRow();
      if(!fingerprint.equals(attempt.get("request_hash")))throw conflict("ONB_FILE_IDEMPOTENCY_CONFLICT","Idempotency key used for different onboarding arguments");
      if(attempt.get("onboarding_version_id")!=null)return draftIdentity(assetId,profileId,String.valueOf(attempt.get("source_id")),(UUID)attempt.get("onboarding_version_id"));
      Map<String,Object> draft=onboard(assetId,profileId,sourceId,name,owner,targetClassIri,semanticRefs,sourceObjectKeyFields,decisions,layer,actor,correlation,tenant);
      UUID version=(UUID)draft.get("onboarding_version_id");
      db.sql("update ouf_onboarding.managed_file_onboarding_attempt set source_id=:s,onboarding_version_id=:v where asset_id=:a and subject_id=:u and idempotency_key=:k")
          .param("s",sourceId).param("v",version).param("a",assetId).param("u",actor.subject()).param("k",key).update();
      return draftIdentity(assetId,profileId,sourceId,version);
    });
  }
  private Map<String,Object> draftIdentity(UUID asset,UUID profile,String source,UUID version){
    Map<String,Object> current=onboarding.version(source,version);
    return Map.of("assetId",asset,"profileId",profile,"sourceId",source,"onboardingVersionId",version,"state",current.get("state"));
  }
  public Map<String,Object> onboard(UUID assetId,UUID profileId,String sourceId,String name,String owner,String targetClassIri,List<String> semanticRefs,List<String> sourceObjectKeyFields,List<FieldDecision> fieldDecisions,String selectedLayer,OnboardingService.Actor actor,String correlation){
    return onboard(assetId,profileId,sourceId,name,owner,targetClassIri,semanticRefs,sourceObjectKeyFields,fieldDecisions,selectedLayer,actor,correlation,null);
  }
  public Map<String,Object> onboard(UUID assetId,UUID profileId,String sourceId,String name,String owner,String targetClassIri,List<String> semanticRefs,List<String> sourceObjectKeyFields,List<FieldDecision> fieldDecisions,String selectedLayer,OnboardingService.Actor actor,String correlation,String tenant){
    return tx.execute(status->{Map<String,Object> asset=asset(assetId),profile=selectedProfile(fileProfile(assetId,profileId),selectedLayer);onboarding.createSource(sourceId,name,"INTERNAL_MANAGED","MANAGED",owner,managedMetadata(assetId,profileId,asset.get("content_hash"),tenant),actor,correlation);db.sql("update ouf_onboarding.managed_file_asset set source_id=:s where asset_id=:a and source_id is null").param("s",sourceId).param("a",assetId).update();
      @SuppressWarnings("unchecked") List<Map<String,Object>> columns=(List<Map<String,Object>>)profile.get("inferred_schema");List<String> allNames=columns.stream().map(c->String.valueOf(c.get("name"))).toList();Map<String,FieldDecision> decisions=new LinkedHashMap<>();if(fieldDecisions!=null)for(FieldDecision d:fieldDecisions){if(!allNames.contains(d.fieldName())||decisions.put(d.fieldName(),d)!=null||!Set.of("INCLUDE","EXCLUDE").contains(d.extractionDecision())||!Set.of("OPEN","ANONYMOUS","PERSONAL","SENSITIVE","RESTRICTED").contains(d.dataAccessLabel()))throw invalid("ONB_FILE_FIELD_DECISION_INVALID","Field decisions must be unique, complete and use supported extraction/access values");}if(!decisions.isEmpty()&&!decisions.keySet().containsAll(allNames))throw invalid("ONB_FILE_FIELD_DECISION_INCOMPLETE","Every discovered field must be classified");List<String> names=decisions.isEmpty()?allNames:allNames.stream().filter(n->"INCLUDE".equals(decisions.get(n).extractionDecision())).toList();if(names.isEmpty())throw invalid("ONB_FILE_PROJECTION_EMPTY","At least one file field must be included");List<String> keys=sourceObjectKeyFields==null?List.of():List.copyOf(sourceObjectKeyFields);if(!names.containsAll(keys))throw invalid("ONB_FILE_IDENTITY_FIELD_INVALID","Every source-object key field must be included in the selected file profile");if(Set.of("GEOPACKAGE","ACCESS","SHAPEFILE").contains(profile.get("format"))&&(keys.isEmpty()||(profile.get("geometryColumn")!=null&&keys.contains(profile.get("geometryColumn")))||decisions.isEmpty()))throw invalid("ONB_GPKG_APPROVAL_REQUIRED","GeoPackage requires approved stable keys and field classifications");List<String> identityFields=keys.isEmpty()?List.of("$managedRowOrdinal"):keys;String identityStrategy=keys.isEmpty()?"MANAGED_DETERMINISTIC":keys.size()==1?"NATIVE_KEY":"COMPOSITE_NATIVE_KEY";Map<String,Object> identity=new LinkedHashMap<>();identity.put("strategy",identityStrategy);identity.put("sourceFields",identityFields);identity.put("normalizationRuleRef",keys.isEmpty()?"normalization://managed-file/asset-row-ordinal-v1":"normalization://managed-file/native-key-v1");identity.put("stabilityLimit",keys.isEmpty()?"Identity is unique per row inside the immutable asset and changes if rows are reordered":"Identity remains stable while the approved key fields remain stable");Map<String,Object> sync=Map.of("bootstrap","FULL_SNAPSHOT","incremental","NONE","pageSize",500);Map<String,Object> runtime=new LinkedHashMap<>();runtime.put("mode","MANAGED");runtime.put("recordModel","ONE_ROW_ONE_SOURCE_OBJECT");runtime.put("rowIdentityBasis",keys.isEmpty()?"ASSET_AND_ROW_ORDINAL":"APPROVED_SOURCE_FIELDS");runtime.put("stagingRef",asset.get("staging_ref"));runtime.put("contentHash",asset.get("content_hash"));runtime.put("assetId",assetId.toString());runtime.put("fileProfileId",profileId.toString());addCsvDialect(runtime,profile);if(Set.of("GEOPACKAGE","SHAPEFILE").contains(profile.get("format"))){runtime.put("recordModel","ONE_FEATURE_ONE_SOURCE_OBJECT");runtime.put("identityNormalization","GPKG_LENGTH_PREFIXED_V1");identity.put("normalizationRuleRef","normalization://managed-file/source-layer-key-v1");for(String key:List.of("layer","geometryColumn","sourceCrs","srsId"))runtime.put(key,profile.get(key));}if("ACCESS".equals(profile.get("format"))){runtime.put("layer",profile.get("layer"));runtime.put("format","ACCESS");runtime.put("identityNormalization","ACCESS_LENGTH_PREFIXED_V1");identity.put("normalizationRuleRef","normalization://managed-file/source-table-key-v1");}if("SHAPEFILE".equals(profile.get("format"))){runtime.put("format","SHAPEFILE");runtime.put("encoding",profile.get("encoding"));runtime.put("identityNormalization","SHP_LENGTH_PREFIXED_V1");}Map<String,Object> semanticTarget=semanticTarget(targetClassIri,semanticRefs);List<Map<String,Object>> propertyMappings=decisions.values().stream().filter(d->"INCLUDE".equals(d.extractionDecision())).map(d->Map.<String,Object>of("sourceField",d.fieldName(),"targetPropertyIri",required(d.targetPropertyIri(),"ONB_FILE_TARGET_PROPERTY_REQUIRED"),"transform",d.transform()==null?"IDENTITY":d.transform())).toList();List<Map<String,Object>> vocabularies=decisions.values().stream().filter(d->"INCLUDE".equals(d.extractionDecision())&&d.vocabularyId()!=null).map(d->Map.<String,Object>of("sourceField",d.fieldName(),"vocabularyId",required(d.vocabularyId(),"ONB_FILE_VOCABULARY_INVALID"),"vocabularyVersion",required(d.vocabularyVersion(),"ONB_FILE_VOCABULARY_INVALID"),"valueMapRef",required(d.valueMapRef(),"ONB_FILE_VOCABULARY_INVALID"))).toList();List<Map<String,Object>> access=decisions.isEmpty()?allNames.stream().map(n->Map.<String,Object>of("target",n,"label","OPEN","scope","PROPERTY")).toList():decisions.values().stream().filter(d->"INCLUDE".equals(d.extractionDecision())).map(d->Map.<String,Object>of("target",d.targetPropertyIri(),"label",d.dataAccessLabel(),"scope","PROPERTY")).toList();Map<String,Object> semantic=new LinkedHashMap<>();semantic.put("mappingId","sm-"+sourceId);semantic.put("sourceType",Map.of("sourceId",sourceId,"typeCode","FILE"));semantic.put("targetClasses",List.of(semanticTarget));semantic.put("propertyMappings",propertyMappings);semantic.put("vocabularyMappings",vocabularies);semantic.put("semanticRefs",semanticRefs);Map<String,Object> config=new LinkedHashMap<>();config.put("syncProfile",sync);config.put("sourceObjectIdentityPolicy",identity);config.put("changeRepresentationProfile",Map.of("mode","FULL_SNAPSHOT"));config.put("extractionProfile",Map.of("profileId","file-"+profileId,"version",String.valueOf(profile.get("version")),"sourceId",sourceId,"selection",Map.of("assetId",assetId.toString(),"fileProfileId",profileId.toString()),"projection",Map.of("FILE",names),"sync",sync,"runtime",runtime));config.put("semanticMapping",semantic);if("ACCESS".equals(profile.get("format")))config.put("sourceSchemaEvidence",Map.of("fileProfileRef","profile://managed-files/"+assetId+"/profiles/"+profileId,"contentHash",asset.get("content_hash"),"selectedTable",profile.get("layer"),"metadata",profile.get("metadata"),"status","EVIDENCE_ONLY"));config.put("dataAccessPolicies",access);config.put("bundle",Map.of("bundleId","bundle-"+sourceId,"bundleVersion","1.0.0","environment","managed","source",Map.of("sourceId",sourceId,"sourceKind","INTERNAL_MANAGED","acquisitionMode","MANAGED"),"schemaRef","profile://managed-files/"+assetId+"/profiles/"+profileId,"createdFromOnboardingVersion","pending","effectiveFrom","1970-01-01T00:00:00Z","checksum","sha256:pending","status","APPROVED","changeRepresentationProfile",Map.of("mode","FULL_SNAPSHOT")));Map<String,Object> draft=onboarding.createVersion(sourceId,config,actor,correlation);Map<String,Object> out=new LinkedHashMap<>(draft);out.put("managedFileAssetId",assetId);out.put("fileProfileId",profileId);out.put("runtimeTrigger","ACTIVE_BUNDLE");return out;});
  }
  @SuppressWarnings("unchecked") private Map<String,Object> selectedProfile(Map<String,Object> profile,String layer){
    if(!Set.of("GEOPACKAGE","ACCESS","SHAPEFILE").contains(profile.get("format"))){if(layer!=null)throw invalid("ONB_LAYER_UNEXPECTED","Layer/table applies to multi-table datasets only");return profile;}
    if(layer==null||layer.isBlank())throw invalid("ONB_GPKG_LAYER_REQUIRED","Choose one feature layer or Access table");
    var metadata=(Map<String,Object>)profile.get("metadata");var layers=(List<Map<String,Object>>)metadata.get("layers");
    var selected=layers.stream().filter(l->layer.equals(l.get("layer"))).findFirst().orElseThrow(()->invalid("ONB_GPKG_LAYER_INVALID","Unknown layer or table"));
    var out=new LinkedHashMap<>(profile);out.putAll(selected);out.put("inferred_schema",selected.get("columns"));out.put("candidate_keys",selected.get("candidateKeys"));out.put("redacted_sample",selected.get("sample"));return out;
  }
  private static void addCsvDialect(Map<String,Object> runtime,Map<String,Object> profile){
    if(!"CSV".equals(profile.get("format")))return;
    if(!(profile.get("metadata") instanceof Map<?,?> metadata)
        ||!Set.of(",",";").contains(metadata.get("delimiter")))
      throw invalid("ONB_FILE_CSV_DIALECT_INVALID","CSV delimiter is missing from the approved profile");
    runtime.put("csvDelimiter",metadata.get("delimiter"));
  }
  private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException(e);}}
  private Map<String,Object> jsonRow(Map<String,Object> row,String...fields){try{Map<String,Object> out=new LinkedHashMap<>(row);for(String f:fields)if(out.get(f) instanceof String s)out.put(f,json.readValue(s,Object.class));return out;}catch(Exception e){throw new IllegalStateException(e);}}
  private static DomainFailure invalid(String code,String message){return new DomainFailure(HttpStatus.UNPROCESSABLE_ENTITY,code,message);}
  private static DomainFailure conflict(String code,String message){return new DomainFailure(HttpStatus.CONFLICT,code,message);}
  private static OffsetDateTime utc(Instant instant){return OffsetDateTime.ofInstant(instant,ZoneOffset.UTC);}
  private static void requireLease(Duration lease){if(lease.toSeconds()<10||lease.toSeconds()>3600)throw invalid("ONB_LEASE_INVALID","lease must be between PT10S and PT1H");}
  private static Map<String,Object> semanticTarget(String classIri,List<String> refs){if(refs==null||refs.isEmpty())throw invalid("ONB_SEMANTIC_REF_INVALID","At least one pinned semantic reference is required");String ref=refs.get(0);int at=ref.lastIndexOf('@');if(at<1||at==ref.length()-1)throw invalid("ONB_SEMANTIC_REF_INVALID","Semantic references must pin id@version");return Map.of("ontologyId",ref.substring(0,at),"ontologyVersion",ref.substring(at+1),"classIri",classIri);}
  private static String required(String value,String code){if(value==null||value.isBlank())throw invalid(code,"Required field configuration is missing");return value;}
  private static Map<String,Object> jobView(Map<String,Object> row){Map<String,Object> out=new LinkedHashMap<>(row);out.put("jobId",row.get("job_id"));out.put("status","READY".equals(row.get("state"))?"QUEUED":row.get("state"));out.put("createdAt",row.get("created_at"));out.put("updatedAt",row.get("updated_at"));out.put("nextRetryAt",row.get("next_attempt_at"));out.put("resultRef",row.get("profile_id"));out.put("errorCode",row.get("error_code"));return out;}
}
