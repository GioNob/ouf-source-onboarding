package it.comune.trieste.ouf.onboarding.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.authorization.PrincipalContext;
import org.springframework.context.annotation.Lazy;
import it.comune.trieste.ouf.onboarding.application.ManagedFileExecutionProposal.SemanticBinding;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
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
  private static final java.util.regex.Pattern UUID_TEXT=java.util.regex.Pattern.compile(
      "(?i)[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}");

  static UUID retainedFileId(Object value){
    if(value instanceof String id && UUID_TEXT.matcher(id).matches())return UUID.fromString(id);
    throw conflict("ONB_REVIEW_CONFIGURATION_DRIFT","Invalid retained managed file reference");
  }

  private static Map<String,Object> managedMetadata(UUID asset,UUID profile,Object hash,String tenant){
    var result=new LinkedHashMap<String,Object>();result.put("managedFileAssetId",asset.toString());
    result.put("fileProfileId",profile.toString());result.put("contentHash",hash);
    if(tenant!=null){if(tenant.isBlank()||tenant.length()>256)throw invalid("ONB_FILE_TENANT_INVALID","Explicit authenticated tenant required");result.put("managedTenantId",tenant);}
    return result;
  }
  private static final String TENANT_OWNER_PREDICATE="""
      a.uploaded_by=:subject and (a.tenant_id=:tenant or (a.tenant_id is null and (
        exists(select 1 from ouf_onboarding.managed_file_owner_reconciliation o where o.asset_id=a.asset_id
          and o.tenant_id=:tenant and o.subject_id=:subject and o.content_hash=a.content_hash and o.size_bytes=a.size_bytes)
        or (not exists(select 1 from ouf_onboarding.managed_file_owner_reconciliation o where o.asset_id=a.asset_id) and (
        exists(select 1 from ouf_onboarding.managed_file_upload_attempt u where u.asset_id=a.asset_id and u.tenant_id=:tenant and u.subject_id=:subject)
        or exists(select 1 from ouf_onboarding.source s join ouf_onboarding.managed_onboarding_review r on r.source_id=s.source_id
          where s.metadata->>'managedFileAssetId'=a.asset_id::text and r.tenant_id=:tenant and r.subject_id=:subject
          and (not(jsonb_exists(s.metadata,'managedTenantId')) or s.metadata->>'managedTenantId'=:tenant)))))))
      """;
  public void requireTenantOwner(UUID asset,String tenant,String subject){
    if(!tenantOwnerProven(asset,tenant,subject))
      throw new DomainFailure(HttpStatus.FORBIDDEN,"ONB_FILE_OWNER_SCOPE_UNPROVEN","Managed file owner scope is not proven");
  }
  public boolean tenantOwnerProven(UUID asset,String tenant,String subject){
    if(tenant==null||tenant.isBlank()||subject==null||subject.isBlank())throw invalid("ONB_FILE_TENANT_INVALID","Explicit authenticated owner required");
    return db.sql("select 1 from ouf_onboarding.managed_file_asset a where a.asset_id=:asset and "+TENANT_OWNER_PREDICATE)
        .param("asset",asset).param("tenant",tenant).param("subject",subject).query(Integer.class).optional().isPresent();
  }
  public void requireManagedSourceOwner(String sourceId,String tenant,String subject){
    boolean proven=db.sql("""
        select 1 from ouf_onboarding.source s
        join ouf_onboarding.managed_file_asset a on a.asset_id=case
          when s.metadata->>'managedFileAssetId' ~ '^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$'
          then (s.metadata->>'managedFileAssetId')::uuid end
        where s.source_id=:source and exists(select 1 from ouf_onboarding.managed_file_effective_binding b where b.asset_id=a.asset_id and b.source_id=s.source_id)
          and s.source_kind='INTERNAL_MANAGED' and s.acquisition_mode='MANAGED'
          and (not(jsonb_exists(s.metadata,'managedTenantId')) or s.metadata->>'managedTenantId'=:tenant)
          and
        """+TENANT_OWNER_PREDICATE)
        .param("source",sourceId).param("tenant",tenant).param("subject",subject).query(Integer.class).optional().isPresent();
    if(!proven)throw new DomainFailure(HttpStatus.FORBIDDEN,"ONB_THS_LINK_SOURCE_SCOPE_UNPROVEN",
        "Managed source owner scope is not proven");
  }
  /** Only durable tenant+subject proofs; legacy unbound assets are not inferred. */
  public Map<String,Object> ownedAssets(String tenant,String subject,int limit,UUID after){
    if(tenant==null||tenant.isBlank()||subject==null||subject.isBlank()||limit<1||limit>50)throw invalid("ONB_FILE_ASSET_QUERY_INVALID","Bounded authenticated owner query required");
    if(after!=null)requireTenantOwner(after,tenant,subject);
    var rows=db.sql("""
        select a.asset_id,a.created_at,a.status,a.media_type,a.size_bytes,f.profile_id,
          j.job_id,j.state profile_job_state,j.error_code profile_job_error_code,
          visible.source_id
        from ouf_onboarding.managed_file_asset a
        left join lateral(
          select b.source_id from ouf_onboarding.managed_file_effective_binding b join ouf_onboarding.source s using(source_id)
          where b.asset_id=a.asset_id and (s.metadata->>'managedTenantId'=:tenant or
            (not(jsonb_exists(s.metadata,'managedTenantId')) and exists(select 1 from ouf_onboarding.managed_onboarding_review r
              where r.source_id=s.source_id and r.tenant_id=:tenant and r.subject_id=:subject)))
          order by (b.source_id=a.source_id) desc,b.source_id limit 1
        ) visible on true
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

  public record FieldDecision(String fieldName,String extractionDecision,String dataAccessLabel,String targetPropertyIri,String transform,String vocabularyId,String vocabularyVersion,String valueMapRef,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Map<String,Object> transformParams){
    public FieldDecision(String fieldName,String extractionDecision,String dataAccessLabel,String targetPropertyIri,String transform,String vocabularyId,String vocabularyVersion,String valueMapRef){
      this(fieldName,extractionDecision,dataAccessLabel,targetPropertyIri,transform,vocabularyId,vocabularyVersion,valueMapRef,null);
    }
  }
  private static final long MAX_PROFILE_BYTES=10L*1024*1024;
  private final JdbcClient db; private final ObjectMapper json; private final CanonicalHash hashes; private final ManagedFileProfiler profiler; private final TransactionTemplate tx; private final OnboardingService onboarding; private final ManagedProfileJobs profileJobs;
  private final ManagedPreviewPaging previewPaging;
  private final ManagedOnboardingReviewService reviews;
  private final ManagedValueMapPreparation valueMaps;
  public ManagedFileService(JdbcClient db,ObjectMapper json,CanonicalHash hashes,ManagedFileProfiler profiler,TransactionTemplate tx,OnboardingService onboarding,ManagedProfileJobs profileJobs,ManagedPreviewPaging previewPaging,@Lazy ManagedOnboardingReviewService reviews,ManagedValueMapPreparation valueMaps){this.reviews=reviews;this.valueMaps=valueMaps;this.db=db;this.json=json;this.hashes=hashes;this.profiler=profiler;this.tx=tx;this.onboarding=onboarding;this.profileJobs=profileJobs;this.previewPaging=previewPaging;}
  public Map<String,Object> register(String sourceId,String stagingRef,String contentHash,String mediaType,long size,String uploadedBy,String retentionRef){
    return registerBound(sourceId,stagingRef,contentHash,mediaType,size,uploadedBy,retentionRef,null,null);
  }
  /** Persist the authenticated tenant at intake; never infer it from an asset or later login. */
  public Map<String,Object> registerOwned(String sourceId,String stagingRef,String contentHash,String mediaType,long size,String uploadedBy,String retentionRef,String tenant){
    return registerOwned(sourceId,stagingRef,contentHash,mediaType,size,uploadedBy,retentionRef,tenant,null);
  }
  public Map<String,Object> registerOwned(String sourceId,String stagingRef,String contentHash,String mediaType,long size,String uploadedBy,String retentionRef,String tenant,String displayName){
    if(tenant==null||tenant.isBlank()||!tenant.equals(tenant.strip())||tenant.length()>256||uploadedBy==null||uploadedBy.isBlank())
      throw invalid("ONB_FILE_TENANT_INVALID","Explicit authenticated owner required");
    validateFileName(displayName);
    return registerBound(sourceId,stagingRef,contentHash,mediaType,size,uploadedBy,retentionRef,tenant,displayName);
  }
  public static void validateFileName(String displayName){
    if(displayName!=null&&(displayName.isBlank()||displayName.length()>255||displayName.contains("/")||displayName.contains("\\")||displayName.chars().anyMatch(Character::isISOControl)))
      throw invalid("ONB_FILE_NAME_INVALID","Invalid file display name");
  }
  private Map<String,Object> registerBound(String sourceId,String stagingRef,String contentHash,String mediaType,long size,String uploadedBy,String retentionRef,String tenant,String displayName){
    if(!stagingRef.startsWith("object://"))throw invalid("ONB_STAGING_REF_INVALID","stagingRef must use object://");if(size<1||size>MAX_PROFILE_BYTES)throw invalid("ONB_FILE_SIZE_INVALID","File exceeds the 10 MiB deterministic profiling limit");UUID id=UUID.randomUUID();
    return tx.execute(status->{
      db.sql("insert into ouf_onboarding.managed_file_asset(asset_id,source_id,staging_ref,content_hash,media_type,size_bytes,uploaded_by,retention_ref,tenant_id,display_name) values(:i,:s,:r,:h,:m,:z,:u,:x,:t,:name) on conflict(staging_ref,content_hash) do nothing")
        .param("i",id).param("s",new SqlParameterValue(Types.VARCHAR,sourceId)).param("r",stagingRef).param("h",contentHash).param("m",mediaType).param("z",size).param("u",uploadedBy).param("x",retentionRef).param("t",new SqlParameterValue(Types.VARCHAR,tenant)).param("name",displayName,Types.VARCHAR).update();
      var row=db.sql("select asset_id,source_id,staging_ref,content_hash,media_type,size_bytes,uploaded_by,retention_ref,status,created_at,tenant_id from ouf_onboarding.managed_file_asset where staging_ref=:r and content_hash=:h")
        .param("r",stagingRef).param("h",contentHash).query().singleRow();
      if(!Objects.equals(uploadedBy,row.get("uploaded_by"))||!Objects.equals(tenant,row.get("tenant_id")))
        throw conflict("ONB_FILE_OWNER_BINDING_CONFLICT","Existing file owner binding differs; reconciliation required");
      return row;
    });
  }
  /** Serialize an upload attempt across retries before writing to object storage. */
  public Map<String,Object> registerDelegatedUpload(String tenant,String subject,String fileId,String key,String hash,long size,java.util.function.Supplier<String> put){
    return registerDelegatedUpload(tenant,subject,fileId,key,hash,size,ManagedFileContentInspector.CSV,put);
  }
  public Map<String,Object> registerDelegatedUpload(String tenant,String subject,String fileId,String key,String hash,long size,String mediaType,java.util.function.Supplier<String> put){
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
        if(!Objects.equals(mediaType,existing.get("media_type")))
          throw conflict("ONB_FILE_UPLOAD_IDEMPOTENCY_CONFLICT","Idempotency key used for a different media type");
        return Map.<String,Object>of("assetId",row.get("asset_id"),"status",existing.get("status"));
      }
      String ref=put.get();
      var asset=registerOwned(null,ref,hash,mediaType,size,subject,"retention://managed-files/30d",tenant);
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
    var asset=asset(assetId);
    if(((Number)asset.get("size_bytes")).longValue()!=bytes.length)
      throw invalid("ONB_FILE_SIZE_MISMATCH","Staged size does not match downloaded content");
    if(!hashes.ofBytes(bytes).equals(asset.get("content_hash")))
      throw invalid("ONB_FILE_HASH_MISMATCH","Staged content hash does not match downloaded content");
    var result=profiler.profile(bytes,String.valueOf(asset.get("media_type")));
    String distinct=write(result.distinctCellValues());
    return tx.execute(status->{
      db.sql("select 1 as acquired from pg_advisory_xact_lock(hashtextextended(:i,0))")
          .param("i",assetId.toString()).query(Integer.class).single();
      int version=db.sql("select coalesce(max(version),0)+1 from ouf_onboarding.file_profile where asset_id=:a")
          .param("a",assetId).query(Integer.class).single();
      UUID profileId=UUID.randomUUID();
      db.sql("""
          insert into ouf_onboarding.file_profile
            (profile_id,asset_id,version,format,metadata,inferred_schema,candidate_keys,sample_ref,redacted_sample,distinct_cell_values)
          values(:i,:a,:v,:f,cast(:m as jsonb),cast(:s as jsonb),cast(:k as jsonb),:r,cast(:q as jsonb),:d)
          """).param("i",profileId).param("a",assetId).param("v",version).param("f",result.format())
          .param("m",write(result.metadata())).param("s",write(result.columns())).param("k",write(result.candidateKeys()))
          .param("r",sampleRef).param("q",write(result.sample())).param("d",distinct).update();
      db.sql("update ouf_onboarding.managed_file_asset set status='PROFILED' where asset_id=:a and status in ('STAGED','PROFILED')")
          .param("a",assetId).update();
      return fileProfile(assetId,profileId);
    });
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
      db.sql("insert into ouf_onboarding.file_profile_job(job_id,asset_id,idempotency_key,budget_profile) values(:i,:a,:k,'managed-profile-initial.v1')").param("i",jobId).param("a",assetId).param("k",idempotencyKey).update();
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
  /** Control metadata for one retained job, resolved before policy evaluation.
   * No CSV label is invented here: this context describes the operational job only. */
  public it.comune.trieste.ouf.authorization.ResourceContext profileRecoveryResource(UUID asset,UUID job,it.comune.trieste.ouf.authorization.PrincipalContext p){
    if(p==null||p.actorType()!=it.comune.trieste.ouf.authorization.PrincipalContext.ActorType.HUMAN)
      throw new DomainFailure(HttpStatus.FORBIDDEN,"ONB_PROFILE_HUMAN_CAPABILITY_REQUIRED","Explicit HUMAN recovery identity required");
    requireTenantOwner(asset,p.tenantId(),p.subjectId());
    profileJobs.requireRetainedJob(asset,job);
    return new it.comune.trieste.ouf.authorization.ResourceContext("managed-profile-job",job.toString(),p.tenantId(),null,
        Map.of("module","ONBOARDING","assetRef",asset.toString(),"jobRef",job.toString(),"detailLevel","TENANT_OPERATIONAL"));
  }
  public Map<String,Object> profileRecoveryCard(UUID asset,UUID job,it.comune.trieste.ouf.authorization.PrincipalContext p,OnboardingService.Actor actor){
    recoveryActor(p,actor,asset,false);var card=new LinkedHashMap<>(profileJobs.card(asset,job));boolean write=actor.capabilities().contains("ouf.onboarding.quarantine.release");
    card.put("recoveryPermissionAvailable",write);if(!write){card.put("budgetAvailableToGrant",false);card.put("canResume",false);card.put("canCancel",false);}return card;
  }
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
  public Map<String,Object> asset(UUID id){return db.sql("select asset_id,display_name,source_id,staging_ref,content_hash,media_type,size_bytes,uploaded_by,retention_ref,status,created_at from ouf_onboarding.managed_file_asset where asset_id=:i").param("i",id).query().listOfRows().stream().findFirst().orElseThrow(()->new DomainFailure(HttpStatus.NOT_FOUND,"ONB_FILE_ASSET_NOT_FOUND","Managed file asset not found"));}
  public Map<String,Object> fileProfile(UUID asset,UUID profile){return jsonRow(db.sql("select profile_id,asset_id,version,format,metadata::text metadata,inferred_schema::text inferred_schema,candidate_keys::text candidate_keys,sample_ref,redacted_sample::text redacted_sample,created_at from ouf_onboarding.file_profile where asset_id=:a and profile_id=:p").param("a",asset).param("p",profile).query().listOfRows().stream().findFirst().orElseThrow(()->new DomainFailure(HttpStatus.NOT_FOUND,"ONB_FILE_PROFILE_NOT_FOUND","File profile not found")),"metadata","inferred_schema","candidate_keys","redacted_sample");}

  private Map<String,Map<String,List<String>>> distinctCellValues(UUID assetId,UUID profileId){
    var retained=db.sql("select distinct_cell_values from ouf_onboarding.file_profile where asset_id=:a and profile_id=:p and distinct_cell_values is not null")
        .param("a",assetId).param("p",profileId).query(String.class).optional();
    if(retained.isEmpty())return Map.of();
    try{return json.readValue(retained.get(),new TypeReference<>(){});}
    catch(JsonProcessingException failure){throw new IllegalStateException("managed profile distinct values are unreadable");}
  }
  public Map<String,Object> previewProfile(UUID assetId,UUID profileId,String tenant,String subject){
    return previewProfile(assetId,profileId,tenant,subject,null);
  }

  /** Fit the profile against the actual response envelope, including a job summary when supplied. */
  public Map<String,Object> previewProfile(UUID assetId,UUID profileId,String tenant,String subject,Map<String,Object> job){
    var file=fileProfile(assetId,profileId);
    Map<String,Object> retained=preview(assetId,profileId,file);
    // Legacy subject-owned previews may have no tenant proof. Their counts and samples remain available.
    Map<String,Map<String,List<String>>> distinct=tenantOwnerProven(assetId,tenant,subject)?distinctCellValues(assetId,profileId):Map.of();
    var envelope=job==null?retained:new LinkedHashMap<>(job);
    envelope.put("unitSummary",new SourceUnitProfiles(file,hashes).summary());
    return previewPaging.profile(retained,job==null?null:envelope,distinct,tenant,subject);
  }

  /** Immutable pages share the retained schema hash and exclude preview sample rows. */
  public Map<String,Object> previewColumns(UUID assetId,UUID profileId,String tenant,String subject,
      String layer,int offset,int limit,String cursor){
    requireTenantOwner(assetId,tenant,subject);
    if(offset<0||limit<1||limit>ManagedFileLimits.COLUMN_PAGE_SIZE)throw invalid("ONB_PROFILE_PAGE_INVALID","Invalid column page bounds");
    Map<String,Object> retained;
    try{
      retained=preview(assetId,profileId,fileProfile(assetId,profileId));
    }catch(DomainFailure failure){
      if(cursor!=null&&"ONB_FILE_PROFILE_NOT_FOUND".equals(failure.code()))throw DistinctValuesCursors.invalid();
      throw failure;
    }
    return previewPaging.columns(retained,distinctCellValues(assetId,profileId),tenant,subject,layer,offset,limit,cursor);
  }
  @org.springframework.beans.factory.annotation.Value("${ouf.onboarding.units.multiple-sources-per-asset:false}")
  private boolean multipleSourcesPerAsset;

  public record Selection(UUID assetId, UUID profileId, String layer, SourceUnitProfiles.Reference pin) {}

  private UUID visibleProfile(UUID profile,String tenant,String subject) {
    return db.sql("select p.asset_id from ouf_onboarding.file_profile p join ouf_onboarding.managed_file_asset a using(asset_id) where p.profile_id=:profile and "+TENANT_OWNER_PREDICATE)
        .param("profile",profile).param("tenant",tenant).param("subject",subject).query(UUID.class).optional()
        .orElseThrow(()->SourceUnitProfiles.error(HttpStatus.NOT_FOUND,"ONB_FILE_PROFILE_NOT_FOUND"));
  }

  public Selection resolve(UUID asset,UUID profile,String layer,SourceUnitProfiles.Reference pin,String tenant,String subject) {
    if (pin==null) {
      if (asset==null || profile==null) throw invalid("ONB_FILE_PROFILE_REQUIRED","Asset and profile required");
      return new Selection(asset,profile,layer,null);
    }
    UUID actual=visibleProfile(pin.profileId(),tenant,subject);
    var units=new SourceUnitProfiles(fileProfile(actual,pin.profileId()),hashes);
    var unit=units.verify(pin);
    String selected=unit.layer()?unit.name():null;
    if (asset!=null && !asset.equals(actual) || profile!=null && !profile.equals(pin.profileId()) || layer!=null && !layer.equals(selected))
      throw conflict("ONB_UNIT_SELECTION_CHANGED","Unit differs from requested selection");
    if (units.outdated(unit)) throw SourceUnitProfiles.error(HttpStatus.UNPROCESSABLE_ENTITY,"ONB_UNIT_PROFILE_OUTDATED");
    return new Selection(actual,pin.profileId(),selected,"TABLE".equals(unit.kind())?null:pin);
  }

  public Map<String,Object> unitView(UUID asset,UUID profile,String tenant,String subject,String view,String ref,
      Integer offset,Integer limit,String cursor) {
    UUID actual=visibleProfile(profile,tenant,subject);
    if (asset!=null && !asset.equals(actual)) throw conflict("ONB_UNIT_SELECTION_CHANGED","Asset differs from profile");
    var file=fileProfile(actual,profile);
    var units=new SourceUnitProfiles(file,hashes);
    String media=(String)asset(actual).get("media_type");
    if ("UNITS".equals(view) && ref==null && cursor==null)
      return previewPaging.inventory(units.inventory(media,offset==null?0:offset,limit==null?64:limit),offset==null?0:offset);
    if (!"UNIT".equals(view) || ref==null || !ref.matches("(table|sheet|layer|collection):(0|[1-9][0-9]{0,3})") || cursor!=null && (offset!=null || limit!=null))
      throw invalid("ONB_PROFILE_PAGE_INVALID","Invalid unit query");
    var unit=units.find(ref);
    return previewPaging.unit(units.header(unit,media),unit,distinctCellValues(actual,profile),tenant,subject,
        offset==null?0:offset,limit==null?ManagedFileLimits.COLUMN_PAGE_SIZE:limit,cursor);
  }

  public Map<String,Object> profileWithUnitSummary(UUID asset,UUID profile) {
    var file=fileProfile(asset,profile);
    var result=new LinkedHashMap<>(file);
    result.put("unitSummary",new SourceUnitProfiles(file,hashes).summary());
    return result;
  }

  Map<String,Object> evidenceFor(Map<?,?> extraction) {
    var selection=ManagedReviewSnapshot.child(extraction,"selection");
    var runtime=ManagedReviewSnapshot.child(extraction,"runtime");
    var file=fileProfile(retainedFileId(selection.get("assetId")),retainedFileId(selection.get("fileProfileId")));
    var pin=retainedPin(selection);
    var profile=selectedProfile(file,(String)runtime.get("layer"),pin);
    if(pin!=null && (!Objects.equals(runtime.get("unitRef"),profile.get("unitRef"))
        || !Objects.equals(runtime.get("unitKind"),profile.get("unitKind"))
        || !Objects.equals(runtime.get("readParameters"),profile.get("readParameters"))
        || "LAYER".equals(profile.get("unitKind")) && List.of("layer","geometryColumn","sourceCrs").stream()
            .anyMatch(key->!Objects.equals(profile.get(key),runtime.get(key)))))
      throw conflict("ONB_UNIT_SELECTION_CHANGED","Retained unit differs from profile");
    return profile;
  }

  Map<String,Object> selectedProfile(Map<String,Object> profile,String layer,SourceUnitProfiles.Reference pin) {
    if (pin==null && !("GEOPACKAGE".equals(profile.get("format")) && layer!=null)) return selectedProfile(profile,layer);
    var units=new SourceUnitProfiles(profile,hashes);
    SourceUnitProfiles.Unit unit;
    if (pin!=null) unit=units.verify(pin);
    else {
      selectedProfile(profile,layer); // Validate the historical layer selector before deriving its ordinal.
      var layers=(List<?>)((Map<?,?>)profile.get("metadata")).get("layers");
      int index=0;
      while (!layer.equals(((Map<?,?>)layers.get(index)).get("layer"))) index++;
      unit=units.find("layer:"+index);
      pin=new SourceUnitProfiles.Reference((UUID)profile.get("profile_id"),((Number)profile.get("version")).intValue(),unit.ref(),hashes.of(unit.structure()));
    }
    var out=new LinkedHashMap<>(profile);
    out.putAll(unit.evidence());
    out.put("inferred_schema",unit.columns());
    out.put("candidate_keys",unit.candidates());
    out.put("redacted_sample",unit.evidence().getOrDefault("sample",List.of()));
    out.put("unitProfileRef",pin.asMap());
    out.put("unitRef",unit.ref());
    out.put("unitKind",unit.kind());
    out.put("readParameters",unit.readParameters());
    return out;
  }

  SourceUnitProfiles.Reference retainedPin(Map<?,?> selection) {
    return selection.get("unitProfileRef")==null?null:SourceUnitProfiles.Reference.parse(json.valueToTree(selection.get("unitProfileRef")));
  }

  static String unitRef(Map<String,Object> profile) {
    if (profile.get("unitRef") instanceof String ref) return ref;
    return switch ((String)profile.get("format")) { case "CSV" -> "table:0"; case "XLSX" -> "sheet:0"; default -> null; };
  }

  // All mutations call this again with the asset row locked.
  Map<String,Object> scopedAsset(Map<String,Object> asset,String ref,String source,PrincipalContext principal) {
    return checkBinding(asset,ref,source,bound->boundAssetConflict(principal,bound));
  }

  Map<String,Object> checkBinding(Map<String,Object> asset,String ref,String source,
      java.util.function.Function<String,DomainFailure> historicalConflict) {
    var rows=db.sql("select unit_ref,source_id from ouf_onboarding.managed_file_effective_binding where asset_id=:a")
        .param("a",asset.get("asset_id")).query().listOfRows();
    var known=new HashMap<String,String>();
    for(var row:rows){
      String unit=(String)row.get("unit_ref"),bound=(String)row.get("source_id");
      if(asset.get("source_id")==null || known.putIfAbsent(unit,bound)!=null || unit==null && rows.size()>1)
        throw conflict("ONB_UNIT_SELECTION_CHANGED","Conflicting retained custody evidence");
    }
    // An existing binding remains readable when new multi-source admission is switched off.
    var selected=new LinkedHashMap<>(asset);
    if(rows.stream().anyMatch(r->source.equals(r.get("source_id")) && Objects.equals(ref,r.get("unit_ref")))){
      selected.put("source_id",source);
      return selected;
    }
    for (var row:rows) {
      String bound=(String)row.get("source_id");
      boolean same=Objects.equals(ref,row.get("unit_ref"));
      if (same || row.get("unit_ref")==null || ref==null) {
        if (multipleSourcesPerAsset && same && ref!=null) throw conflict("ONB_UNIT_ALREADY_BOUND","Unit already bound");
        throw historicalConflict.apply(bound);
      }
      if (!multipleSourcesPerAsset || source.equals(bound))
        throw conflict("ONB_UNIT_ASSET_ALREADY_BOUND","Another unit of this asset is already bound");
    }
    selected.put("source_id",null);
    return selected;
  }

  String boundSource(UUID asset,String unit) {
    return db.sql("""
        select source_id from ouf_onboarding.managed_file_effective_binding where asset_id=:asset and unit_ref=:unit
        """).param("asset",asset).param("unit",unit).query(String.class).optional().orElse(null);
  }

  void bindUnit(UUID asset,String ref,String source) {
    if (ref!=null) {
      int inserted=db.sql("""
        insert into ouf_onboarding.managed_file_unit_binding(asset_id,unit_ref,source_id) values(:a,:u,:s)
        on conflict(asset_id,unit_ref) do nothing
        """).param("a",asset).param("u",ref).param("s",source).update();
      if(inserted==0 && !source.equals(boundSource(asset,ref)))throw conflict("ONB_UNIT_ALREADY_BOUND","Unit already bound");
    }
    db.sql("update ouf_onboarding.managed_file_asset set source_id=:s where asset_id=:a and source_id is null")
        .param("s",source).param("a",asset).update();
  }

  public Map<String,Object> preview(UUID assetId,UUID profileId){
    var file=fileProfile(assetId,profileId);
    var result=preview(assetId,profileId,file);
    result.put("unitSummary",new SourceUnitProfiles(file,hashes).summary());
    return result;
  }
  private Map<String,Object> preview(UUID assetId,UUID profileId,Map<String,Object> profile){@SuppressWarnings("unchecked") List<String> candidates=(List<String>)profile.get("candidate_keys");@SuppressWarnings("unchecked") List<Map<String,Object>> columns=(List<Map<String,Object>>)profile.get("inferred_schema");Map<String,Object> identity=candidates.isEmpty()?Map.of("strategy","MANAGED_DETERMINISTIC","sourceFields",List.of("$managedRowOrdinal"),"normalizationRuleRef","normalization://managed-file/asset-row-ordinal-v1","stabilityLimit","Identity is unique per row inside the immutable asset and changes if rows are reordered"):Map.of("strategy","NATIVE_KEY","sourceFields",List.of(candidates.get(0)),"normalizationRuleRef","normalization://managed-file/native-key-v1","stabilityLimit","Identity remains stable while the proposed key remains stable");Map<String,Object> out=new LinkedHashMap<>();out.put("assetId",assetId);out.put("profileId",profileId);out.put("profileVersion",profile.get("version"));out.put("format",profile.get("format"));out.put("metadata",profile.get("metadata"));out.put("columns",columns);out.put("candidateKeys",candidates);out.put("redactedSample",profile.get("redacted_sample"));out.put("recordModel","ONE_ROW_ONE_SOURCE_OBJECT");out.put("identityProposal",Set.of("GEOPACKAGE","ACCESS","SHAPEFILE").contains(profile.get("format"))?Map.of("status","LAYER_AND_STABLE_KEYS_REQUIRED"):identity);out.put("proposalStatus","PENDING_HUMAN_REVIEW");return out;}
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
    return onboardIdempotent(assetId,profileId,sourceId,name,owner,targetClassIri,semanticRefs,sourceObjectKeyFields,decisions,layer,actor,correlation,key,tenant,null);
  }
  public Map<String,Object> onboardIdempotent(UUID assetId,UUID profileId,String sourceId,String name,String owner,String targetClassIri,List<String> semanticRefs,List<String> sourceObjectKeyFields,List<FieldDecision> decisions,String layer,OnboardingService.Actor actor,String correlation,String key,String tenant,Map<String,Object> category){
    return onboardIdempotent(assetId,profileId,sourceId,name,owner,targetClassIri,semanticRefs,sourceObjectKeyFields,decisions,layer,actor,correlation,key,tenant,category,null);
  }
  public Map<String,Object> onboardIdempotent(UUID assetId,UUID profileId,String sourceId,String name,String owner,String targetClassIri,List<String> semanticRefs,List<String> sourceObjectKeyFields,List<FieldDecision> decisions,String layer,OnboardingService.Actor actor,String correlation,String key,String tenant,Map<String,Object> category,List<Map<String,Object>> compositions){
    return onboardIdempotent(assetId,profileId,sourceId,name,owner,targetClassIri,semanticRefs,sourceObjectKeyFields,decisions,layer,actor,correlation,key,tenant,category,compositions,null,null);
  }
  public Map<String,Object> onboardIdempotent(UUID assetId,UUID profileId,String sourceId,String name,String owner,String targetClassIri,List<String> semanticRefs,List<String> sourceObjectKeyFields,List<FieldDecision> decisions,String layer,OnboardingService.Actor actor,String correlation,String key,String tenant,Map<String,Object> category,List<Map<String,Object>> compositions,List<SemanticBinding> bindings,PrincipalContext principal){
    return onboardIdempotent(assetId,profileId,sourceId,name,owner,targetClassIri,semanticRefs,sourceObjectKeyFields,decisions,layer,actor,correlation,key,tenant,category,compositions,bindings,principal,null);
  }
  public Map<String,Object> onboardIdempotent(UUID assetId,UUID profileId,String sourceId,String name,String owner,String targetClassIri,List<String> semanticRefs,List<String> sourceObjectKeyFields,List<FieldDecision> decisions,String layer,OnboardingService.Actor actor,String correlation,String key,String tenant,Map<String,Object> category,List<Map<String,Object>> compositions,List<SemanticBinding> bindings,PrincipalContext principal,SourceUnitProfiles.Reference pin){
    var selected=resolve(assetId,profileId,layer,pin,tenant,actor.subject());
    return createIdempotent(selected.assetId(),selected.profileId(),sourceId,name,owner,targetClassIri,semanticRefs,sourceObjectKeyFields,decisions,selected.layer(),actor,correlation,key,tenant,category,compositions,bindings,principal,selected.pin());
  }
  private Map<String,Object> createIdempotent(UUID assetId,UUID profileId,String sourceId,String name,String owner,String targetClassIri,List<String> semanticRefs,List<String> sourceObjectKeyFields,List<FieldDecision> decisions,String layer,OnboardingService.Actor actor,String correlation,String key,String tenant,Map<String,Object> category,List<Map<String,Object>> compositions,List<SemanticBinding> bindings,PrincipalContext principal,SourceUnitProfiles.Reference pin){
    if(key==null||!key.matches("[A-Za-z0-9_.:-]{1,128}"))throw invalid("ONB_IDEMPOTENCY_KEY_INVALID","Invalid onboarding idempotency key");
    List<Object> intent=new ArrayList<>(Arrays.asList(assetId,profileId,sourceId,name,owner,targetClassIri,semanticRefs,sourceObjectKeyFields,decisions,layer));
    if(tenant!=null)intent.add(tenant);
    if(category!=null)intent.add(MappingCategory.declared(Map.of("category",category)));
    if(compositions!=null)intent.add(Map.of("compositions",compositions));
    if(bindings!=null)intent.add(Map.of("semanticBindings",bindings));
    if(pin!=null)intent.add(pin.asMap());
    String fingerprint=hashes.of(intent);
    var input=new DraftInput(assetId,profileId,sourceId,targetClassIri,semanticRefs,sourceObjectKeyFields,decisions,layer,category,compositions,bindings,pin);
    // Two concurrent commands may both observe the same draft before a Registry lookup.
    // Only that observed version can be replaced, even if another draft is current at commit.
    var expected=tenant==null?null:db.sql("""
        select v.onboarding_version_id,v.lock_version from ouf_onboarding.onboarding_version v
        join ouf_onboarding.managed_file_effective_binding a using(source_id)
        where v.source_id=:source and a.asset_id=:asset and v.state='DRAFT'
        order by v.version desc limit 1
        """).param("source",sourceId).param("asset",assetId).query().listOfRows().stream().findFirst().orElse(null);
    CompiledDraft checked=null;
    if(bindings!=null){
      requireOwner(assetId,actor.subject());
      if(tenant!=null)requireTenantOwner(assetId,tenant,actor.subject());
      semanticTarget(targetClassIri,semanticRefs);
      var prior=db.sql("select request_hash,source_id,onboarding_version_id from ouf_onboarding.managed_file_onboarding_attempt where asset_id=:a and subject_id=:s and idempotency_key=:k")
          .param("a",assetId).param("s",actor.subject()).param("k",key).query().listOfRows();
      if(!prior.isEmpty()){
        var replay=replayDraft(assetId,profileId,fingerprint,prior.getFirst());
        if(replay!=null)return replay;
      }
      checked=checkReaders(input,actor,tenant,principal);
    }
    var verified=checked;
    return tx.execute(status->{
      lockDraftInput(sourceId,assetId);
      requireOwner(assetId,actor.subject());
      if(tenant!=null)requireTenantOwner(assetId,tenant,actor.subject());
      semanticTarget(targetClassIri,semanticRefs); // Includes historical create replays.
      db.sql("insert into ouf_onboarding.managed_file_onboarding_attempt(asset_id,subject_id,idempotency_key,request_hash) values(:a,:s,:k,:h) on conflict do nothing")
          .param("a",assetId).param("s",actor.subject()).param("k",key).param("h",fingerprint).update();
      Map<String,Object> attempt=db.sql("select request_hash,source_id,onboarding_version_id from ouf_onboarding.managed_file_onboarding_attempt where asset_id=:a and subject_id=:s and idempotency_key=:k for update")
          .param("a",assetId).param("s",actor.subject()).param("k",key).query().singleRow();
      var replay=replayDraft(assetId,profileId,fingerprint,attempt);
      if(replay!=null)return replay;
      Map<String,Object> draft=writeDraft(input,name,owner,actor,correlation,tenant,principal,true,verified,expected);
      UUID version=(UUID)draft.get("onboarding_version_id");
      db.sql("update ouf_onboarding.managed_file_onboarding_attempt set source_id=:s,onboarding_version_id=:v where asset_id=:a and subject_id=:u and idempotency_key=:k")
          .param("s",sourceId).param("v",version).param("a",assetId).param("u",actor.subject()).param("k",key).update();
      return draftIdentity(assetId,profileId,sourceId,version);
    });
  }
  private Map<String,Object> replayDraft(UUID assetId,UUID profileId,String fingerprint,Map<String,Object> attempt){
    if(!fingerprint.equals(attempt.get("request_hash")))throw conflict("ONB_FILE_IDEMPOTENCY_CONFLICT","Idempotency key used for different onboarding arguments");
    if(attempt.get("onboarding_version_id")!=null)return draftIdentity(assetId,profileId,String.valueOf(attempt.get("source_id")),(UUID)attempt.get("onboarding_version_id"));
    return null;
  }
  private static void addDeferredChecks(Map<String,Object> result,Map<?,?> config){
    if(config.containsKey("semanticReferenceBindings"))return;
    var deferred=PublishedDatatypes.dependentReaders(config);
    if(!deferred.isEmpty())result.put("deferredReaderChecks",deferred);
  }

  private Map<String,Object> draftIdentity(UUID asset,UUID profile,String source,UUID version){
    Map<String,Object> current=onboarding.version(source,version);
    var identity=new LinkedHashMap<String,Object>(Map.of("assetId",asset,"profileId",profile,"sourceId",source,"onboardingVersionId",version,"state",current.get("state"),"lockVersion",current.get("lock_version")));
    if(ManagedMcpObservation.draftState(current)){
      ManagedMcpObservation.describeDraft(identity,current);
      var config=ManagedReviewSnapshot.object(current.get("configuration"));
      addDeferredChecks(identity,config);
    }
    return identity;
  }
  public Map<String,Object> onboard(UUID assetId,UUID profileId,String sourceId,String name,String owner,String targetClassIri,List<String> semanticRefs,List<String> sourceObjectKeyFields,List<FieldDecision> fieldDecisions,String selectedLayer,OnboardingService.Actor actor,String correlation){
    return onboard(assetId,profileId,sourceId,name,owner,targetClassIri,semanticRefs,sourceObjectKeyFields,fieldDecisions,selectedLayer,actor,correlation,null);
  }
  public Map<String,Object> onboard(UUID assetId,UUID profileId,String sourceId,String name,String owner,String targetClassIri,List<String> semanticRefs,List<String> sourceObjectKeyFields,List<FieldDecision> fieldDecisions,String selectedLayer,OnboardingService.Actor actor,String correlation,String tenant){
    return onboard(assetId,profileId,sourceId,name,owner,targetClassIri,semanticRefs,sourceObjectKeyFields,fieldDecisions,selectedLayer,actor,correlation,tenant,null);
  }
  public Map<String,Object> onboard(UUID assetId,UUID profileId,String sourceId,String name,String owner,String targetClassIri,List<String> semanticRefs,List<String> sourceObjectKeyFields,List<FieldDecision> fieldDecisions,String selectedLayer,OnboardingService.Actor actor,String correlation,String tenant,Map<String,Object> category){
    return onboard(assetId,profileId,sourceId,name,owner,targetClassIri,semanticRefs,sourceObjectKeyFields,fieldDecisions,selectedLayer,actor,correlation,tenant,category,null);
  }
  public Map<String,Object> onboard(UUID assetId,UUID profileId,String sourceId,String name,String owner,String targetClassIri,List<String> semanticRefs,List<String> sourceObjectKeyFields,List<FieldDecision> fieldDecisions,String selectedLayer,OnboardingService.Actor actor,String correlation,String tenant,Map<String,Object> category,List<Map<String,Object>> compositions){
    return onboard(assetId,profileId,sourceId,name,owner,targetClassIri,semanticRefs,sourceObjectKeyFields,fieldDecisions,selectedLayer,actor,correlation,tenant,category,compositions,null,null);
  }
  public Map<String,Object> onboard(UUID assetId,UUID profileId,String sourceId,String name,String owner,String targetClassIri,List<String> semanticRefs,List<String> sourceObjectKeyFields,List<FieldDecision> fieldDecisions,String selectedLayer,OnboardingService.Actor actor,String correlation,String tenant,Map<String,Object> category,List<Map<String,Object>> compositions,List<SemanticBinding> bindings,PrincipalContext principal){
    return onboard(assetId,profileId,sourceId,name,owner,targetClassIri,semanticRefs,sourceObjectKeyFields,fieldDecisions,selectedLayer,actor,correlation,tenant,category,compositions,bindings,principal,null);
  }
  public Map<String,Object> onboard(UUID assetId,UUID profileId,String sourceId,String name,String owner,String targetClassIri,List<String> semanticRefs,List<String> sourceObjectKeyFields,List<FieldDecision> fieldDecisions,String selectedLayer,OnboardingService.Actor actor,String correlation,String tenant,Map<String,Object> category,List<Map<String,Object>> compositions,List<SemanticBinding> bindings,PrincipalContext principal,SourceUnitProfiles.Reference pin){
    var selected=resolve(assetId,profileId,selectedLayer,pin,tenant,actor.subject());
    return createManagedDraft(selected.assetId(),selected.profileId(),sourceId,name,owner,targetClassIri,semanticRefs,sourceObjectKeyFields,fieldDecisions,selected.layer(),actor,correlation,tenant,category,compositions,bindings,principal,selected.pin());
  }
  private Map<String,Object> createManagedDraft(UUID assetId,UUID profileId,String sourceId,String name,String owner,String targetClassIri,List<String> semanticRefs,List<String> sourceObjectKeyFields,List<FieldDecision> fieldDecisions,String selectedLayer,OnboardingService.Actor actor,String correlation,String tenant,Map<String,Object> category,List<Map<String,Object>> compositions,List<SemanticBinding> bindings,PrincipalContext principal,SourceUnitProfiles.Reference pin){
    var input=new DraftInput(assetId,profileId,sourceId,targetClassIri,semanticRefs,sourceObjectKeyFields,fieldDecisions,selectedLayer,category,compositions,bindings,pin);
    var checked=bindings==null?null:checkReaders(input,actor,tenant,principal);
    return tx.execute(status->{
      lockDraftInput(sourceId,assetId);
      return writeDraft(input,name,owner,actor,correlation,tenant,principal,false,checked,null);
    });
  }

  private record CompiledDraft(Map<String,Object> configuration,Map<String,String> profiles){}

  private record DraftInput(UUID assetId,UUID profileId,String sourceId,String targetClassIri,List<String> semanticRefs,List<String> sourceObjectKeyFields,List<FieldDecision> fieldDecisions,String selectedLayer,Map<String,Object> category,List<Map<String,Object>> compositions,List<SemanticBinding> bindings,SourceUnitProfiles.Reference pin){
    CompiledDraft compile(Map<String,Object> asset,Map<String,Object> profile){
      var semanticTarget=new LinkedHashMap<String,Object>(semanticTarget(targetClassIri,semanticRefs));
      @SuppressWarnings("unchecked") List<Map<String,Object>> columns=(List<Map<String,Object>>)profile.get("inferred_schema");List<String> allNames=columns.stream().map(c->String.valueOf(c.get("name"))).toList();Map<String,FieldDecision> decisions=new LinkedHashMap<>();if(fieldDecisions!=null)for(FieldDecision d:fieldDecisions){if(!allNames.contains(d.fieldName())||decisions.put(d.fieldName(),d)!=null||!Set.of("INCLUDE","EXCLUDE").contains(d.extractionDecision())||!Set.of("OPEN","ANONYMOUS","PERSONAL","SENSITIVE","RESTRICTED").contains(d.dataAccessLabel()))throw invalid("ONB_FILE_FIELD_DECISION_INVALID","Field decisions must be unique, complete and use supported extraction/access values");}if(!decisions.isEmpty()&&!decisions.keySet().containsAll(allNames))throw invalid("ONB_FILE_FIELD_DECISION_INCOMPLETE","Every discovered field must be classified");List<String> names=decisions.isEmpty()?allNames:allNames.stream().filter(n->"INCLUDE".equals(decisions.get(n).extractionDecision())).toList();if(names.isEmpty())throw invalid("ONB_FILE_PROJECTION_EMPTY","At least one file field must be included");List<String> keys=sourceObjectKeyFields==null?List.of():List.copyOf(sourceObjectKeyFields);if(!names.containsAll(keys))throw invalid("ONB_FILE_IDENTITY_FIELD_INVALID","Every source-object key field must be included in the selected file profile");if(Set.of("GEOPACKAGE","ACCESS","SHAPEFILE").contains(profile.get("format"))&&(keys.isEmpty()||(profile.get("geometryColumn")!=null&&keys.contains(profile.get("geometryColumn")))||decisions.isEmpty()))throw invalid("ONB_GPKG_APPROVAL_REQUIRED","GeoPackage requires approved stable keys and field classifications");List<String> identityFields=keys.isEmpty()?List.of("$managedRowOrdinal"):keys;String identityStrategy=keys.isEmpty()?"MANAGED_DETERMINISTIC":keys.size()==1?"NATIVE_KEY":"COMPOSITE_NATIVE_KEY";Map<String,Object> identity=new LinkedHashMap<>();identity.put("strategy",identityStrategy);identity.put("sourceFields",identityFields);identity.put("normalizationRuleRef",keys.isEmpty()?"normalization://managed-file/asset-row-ordinal-v1":"normalization://managed-file/native-key-v1");identity.put("stabilityLimit",keys.isEmpty()?"Identity is unique per row inside the immutable asset and changes if rows are reordered":"Identity remains stable while the approved key fields remain stable");Map<String,Object> sync=Map.of("bootstrap","FULL_SNAPSHOT","incremental","NONE","pageSize",500);Map<String,Object> runtime=new LinkedHashMap<>();runtime.put("mode","MANAGED");runtime.put("recordModel","ONE_ROW_ONE_SOURCE_OBJECT");runtime.put("rowIdentityBasis",keys.isEmpty()?"ASSET_AND_ROW_ORDINAL":"APPROVED_SOURCE_FIELDS");runtime.put("stagingRef",asset.get("staging_ref"));runtime.put("contentHash",asset.get("content_hash"));runtime.put("assetId",assetId.toString());runtime.put("fileProfileId",profileId.toString());addCsvDialect(runtime,profile);if(Set.of("GEOPACKAGE","SHAPEFILE").contains(profile.get("format"))){runtime.put("recordModel","ONE_FEATURE_ONE_SOURCE_OBJECT");runtime.put("identityNormalization","GPKG_LENGTH_PREFIXED_V1");identity.put("normalizationRuleRef","normalization://managed-file/source-layer-key-v1");for(String key:List.of("layer","geometryColumn","sourceCrs","srsId"))runtime.put(key,profile.get(key));}if("ACCESS".equals(profile.get("format"))){runtime.put("layer",profile.get("layer"));runtime.put("format","ACCESS");runtime.put("identityNormalization","ACCESS_LENGTH_PREFIXED_V1");identity.put("normalizationRuleRef","normalization://managed-file/source-table-key-v1");}if("SHAPEFILE".equals(profile.get("format"))){runtime.put("format","SHAPEFILE");runtime.put("encoding",profile.get("encoding"));runtime.put("identityNormalization","SHP_LENGTH_PREFIXED_V1");}
      var selection=new LinkedHashMap<String,Object>();
      selection.put("assetId",assetId.toString());
      selection.put("fileProfileId",profileId.toString());
      if(profile.containsKey("unitProfileRef")){
        selection.put("unitProfileRef",profile.get("unitProfileRef"));
        for(String k:List.of("unitKind","unitRef","readParameters"))runtime.put(k,profile.get(k));
      }
      if(category!=null)semanticTarget.put("category",MappingCategory.declared(Map.of("category",category)));
      Map<String,String> profiles=new LinkedHashMap<>();
      for(var column:columns)if(names.contains(column.get("name")))
        profiles.put((String)column.get("name"),readerInputType((String)column.get("dataType")));
      var combined=new ValueCompositions(compositions==null?List.of():compositions,profiles,null);
      var pinnedIds=new HashSet<String>();
      for(String ref:semanticRefs){
        if(ref==null)throw invalid("ONB_SEMANTIC_REF_INVALID","Semantic reference missing");
        int at=ref.lastIndexOf('@');
        if(at>0)pinnedIds.add(ref.substring(0,at));
      }
      if(!pinnedIds.containsAll(combined.labels.keySet()) || combined.typeRefs.stream().anyMatch(ref->!pinnedIds.contains(ref.substring(0,ref.lastIndexOf('@')))))
        throw invalid("ONB_COMPOSITION_REFERENCE_UNPINNED","Composition references must be pinned by the version");
      List<Map<String,Object>> propertyMappings=new ArrayList<>(),access=new ArrayList<>(),vocabularies=new ArrayList<>();
      Map<String,String> inputLabels=new LinkedHashMap<>();
      Set<String> targets=new HashSet<>(combined.labels.keySet());
      var invalidReaders=new TreeMap<String,Map<String,Object>>();
      for(var d:decisions.values()){
        if(!"INCLUDE".equals(d.extractionDecision()))continue;
        String transform=d.transform()==null?"IDENTITY":d.transform();
        ValueReader reader;
        Map<String,Object> params;
        try{
          reader=ValueReader.named(transform);
          params=reader.parameters(d.transformParams(),d.valueMapRef());
          if(!reader.supports(profiles.get(d.fieldName())))throw ValueReader.invalid();
        }catch(ReviewValidationFailure invalid){
          if(bindings==null)throw invalid;
          invalidReaders.put(d.fieldName(),PublishedDatatypes.readerIssue(d.fieldName(),transform,profiles.get(d.fieldName())));
          continue;
        }
        if(d.targetPropertyIri()==null){
          if(!combined.used.contains(d.fieldName()))
            throw invalid("ONB_FILE_TARGET_PROPERTY_REQUIRED","A field needs a property or a composition input");
          inputLabels.put(d.fieldName(),d.dataAccessLabel());
        }else{
          String target=ValueCompositions.iri(d.targetPropertyIri());
          if(!targets.add(target))throw invalid("ONB_FILE_DUPLICATE_TARGET","Duplicate target property");
          var mapping=new LinkedHashMap<String,Object>(Map.of("sourceField",d.fieldName(),"targetPropertyIri",target,"transform",reader.name()));
          if(!params.isEmpty())mapping.put("transformParams",params);
          propertyMappings.add(mapping);
          access.add(Map.of("target",target,"label",d.dataAccessLabel(),"scope","PROPERTY"));
        }
        if(d.vocabularyId()!=null && reader!=ValueReader.VALUE_MAP_V1)vocabularies.add(Map.of("sourceField",d.fieldName(),
            "vocabularyId",required(d.vocabularyId(),"ONB_FILE_VOCABULARY_INVALID"),"vocabularyVersion",required(d.vocabularyVersion(),"ONB_FILE_VOCABULARY_INVALID"),
            "valueMapRef",required(d.valueMapRef(),"ONB_FILE_VOCABULARY_INVALID")));
      }
      if(!invalidReaders.isEmpty())throw new DomainFailure(HttpStatus.UNPROCESSABLE_ENTITY,"ONB_READER_UNSUPPORTED",
          "Review the selected field readers",Map.of("fields",List.copyOf(invalidReaders.values())));
      if(decisions.isEmpty()){
        if(!combined.used.isEmpty())throw invalid("ONB_FILE_FIELD_DECISION_INCOMPLETE","Explicit field decisions required");
        allNames.forEach(n->access.add(Map.of("target",n,"label","OPEN","scope","PROPERTY")));
      }
      combined.labels.forEach((iri,label)->access.add(Map.of("target",iri,"label",label,"scope","PROPERTY")));
      Map<String,Object> semantic=new LinkedHashMap<>();semantic.put("mappingId","sm-"+sourceId);semantic.put("sourceType",Map.of("sourceId",sourceId,"typeCode","FILE"));
      semantic.put("targetClasses",List.of(semanticTarget));semantic.put("propertyMappings",propertyMappings);semantic.put("vocabularyMappings",vocabularies);semantic.put("semanticRefs",semanticRefs);
      if(!combined.labels.isEmpty())semantic.put("compositions",compositions);
      Map<String,Object> config=new LinkedHashMap<>();if(!inputLabels.isEmpty())config.put("compositionInputLabels",inputLabels);config.put("syncProfile",sync);config.put("sourceObjectIdentityPolicy",identity);config.put("changeRepresentationProfile",Map.of("mode","FULL_SNAPSHOT"));config.put("extractionProfile",Map.of("profileId","file-"+profileId,"version",String.valueOf(profile.get("version")),"sourceId",sourceId,"selection",selection,"projection",Map.of("FILE",names),"sync",sync,"runtime",runtime));config.put("semanticMapping",semantic);if("ACCESS".equals(profile.get("format")))config.put("sourceSchemaEvidence",Map.of("fileProfileRef","profile://managed-files/"+assetId+"/profiles/"+profileId,"contentHash",asset.get("content_hash"),"selectedTable",profile.get("layer"),"metadata",profile.get("metadata"),"status","EVIDENCE_ONLY"));config.put("dataAccessPolicies",access);config.put("bundle",Map.of("bundleId","bundle-"+sourceId,"bundleVersion","1.0.0","environment","managed","source",Map.of("sourceId",sourceId,"sourceKind","INTERNAL_MANAGED","acquisitionMode","MANAGED"),"schemaRef","profile://managed-files/"+assetId+"/profiles/"+profileId,"createdFromOnboardingVersion","pending","effectiveFrom","1970-01-01T00:00:00Z","checksum","sha256:pending","status","APPROVED","changeRepresentationProfile",Map.of("mode","FULL_SNAPSHOT")));
      var excluded=new LinkedHashMap<String,String>();
      for(var field:decisions.values())if("EXCLUDE".equals(field.extractionDecision()))excluded.put(field.fieldName(),field.dataAccessLabel());
      if(!excluded.isEmpty())config.put("excludedFieldReview",Map.of("profileId",profileId.toString(),"labels",excluded));
      if(bindings!=null){
        ManagedFileExecutionProposal.bind(config,semantic,bindings,null,Set.of(targetClassIri),targets,
            combined.typeRefs,combined.labels.keySet(),combined.valueMapRefs);
      }
      return new CompiledDraft(config,profiles);
    }
  }

  // Lookups can outlast a client request. Verify outside the command transaction, then compare
  // against the immutable profile again under the asset lock before persisting any result.
  private CompiledDraft checkReaders(DraftInput input,OnboardingService.Actor actor,String tenant,PrincipalContext principal){
    requireOwner(input.assetId(),actor.subject());
    if(tenant!=null)requireTenantOwner(input.assetId(),tenant,actor.subject());
    var asset=asset(input.assetId());
    var profile=selectedProfile(fileProfile(input.assetId(),input.profileId()),input.selectedLayer(),input.pin());
    scopedAsset(asset,unitRef(profile),input.sourceId(),principal);
    var compiled=input.compile(asset,profile);
    onboarding.checkDraftReaders(compiled.configuration(),compiled.profiles());
    return compiled;
  }

  private Map<String,Object> writeDraft(DraftInput input,String name,String owner,OnboardingService.Actor actor,
      String correlation,String tenant,PrincipalContext principal,boolean replaceDraft,CompiledDraft checked,Map<String,Object> expected){
    UUID assetId=input.assetId(),profileId=input.profileId();
    String sourceId=input.sourceId();

    if(tenant!=null)requireTenantOwner(assetId,tenant,actor.subject());
    var profile=selectedProfile(fileProfile(assetId,profileId),input.selectedLayer(),input.pin());
    var asset=scopedAsset(asset(assetId),unitRef(profile),sourceId,principal);
    UUID parent=redraftParent(asset,profileId,sourceId,name,owner,tenant,actor);
    UUID replace=replaceDraft && parent==null && tenant!=null && sourceId.equals(asset.get("source_id"))
        ?replaceableDraft(sourceId,assetId,profileId,name,owner,tenant,actor.subject(),true):null;
    if(parent==null && replace==null && multipleSourcesPerAsset && unitRef(profile)!=null && sourceId.equals(asset.get("source_id")))
      throw conflict("ONB_UNIT_ALREADY_BOUND","Unit already bound");
    if(parent==null && replace==null)onboarding.createSource(sourceId,name,"INTERNAL_MANAGED","MANAGED",owner,
        managedMetadata(assetId,profileId,asset.get("content_hash"),tenant),actor,correlation);
    bindUnit(assetId,unitRef(profile),sourceId);
    var compiled=input.compile(asset,profile);
    if(checked!=null && !checked.equals(compiled))
      throw conflict("ONB_REVIEW_CONFIGURATION_DRIFT","Managed file profile changed during reader verification");
    var config=compiled.configuration();
    if(replace!=null){
      var previous=onboarding.version(sourceId,replace);
      if(expected==null || !replace.equals(expected.get("onboarding_version_id"))
          || !previous.get("lock_version").equals(expected.get("lock_version")))
        throw new DomainFailure(HttpStatus.PRECONDITION_FAILED,"ONB_REVIEW_STALE_VERSION","Read the current draft before revising its fields");
      var retained=ManagedReviewSnapshot.object(previous.get("configuration"));
      var intentSections=List.of("semanticMapping","dataAccessPolicies","semanticReferenceBindings","semanticReviewTerms",
          "excludedFieldReview","compositionInputLabels","sourceObjectIdentityPolicy");
      if(intentSections.stream().allMatch(section->json.valueToTree(config.get(section)).equals(json.valueToTree(retained.get(section)))))
        throw multipleSourcesPerAsset && unitRef(profile)!=null?conflict("ONB_UNIT_ALREADY_BOUND","Unit already bound"):OnboardingService.occupiedSource(sourceId);
      onboarding.supersedeDraft(sourceId,previous,actor,correlation);
      valueMaps.cancel(replace);
      parent=previous.get("parent_version_id") instanceof UUID prior?prior:replace;
    }
    var draft=onboarding.createVersion(sourceId,config,parent,actor,correlation);
    var out=new LinkedHashMap<>(draft);
    out.put("managedFileAssetId",assetId);
    out.put("fileProfileId",profileId);
    out.put("runtimeTrigger","ACTIVE_BUNDLE");
    if(!replaceDraft)addDeferredChecks(out,config);
    return out;
  }
  boolean canReplaceDraft(PrincipalContext p,String source,UUID version){
    var row=onboarding.source(source);
    var metadata=ManagedReviewSnapshot.object(row.get("metadata"));
    var config=ManagedReviewSnapshot.object(onboarding.version(source,version).get("configuration"));
    var selection=ManagedReviewSnapshot.child(ManagedReviewSnapshot.child(config,"extractionProfile"),"selection");
    if(!(metadata.get("managedFileAssetId") instanceof String asset) || !asset.equals(selection.get("assetId"))
        || !(selection.get("fileProfileId") instanceof String profile))
      return false;
    var assetId=retainedFileId(asset);
    return tenantOwnerProven(assetId,p.tenantId(),p.subjectId()) && version.equals(replaceableDraft(source,assetId,
        retainedFileId(profile),(String)row.get("name"),(String)row.get("owner_ref"),p.tenantId(),p.subjectId(),false));
  }

  private UUID replaceableDraft(String source,UUID asset,UUID profile,String name,String owner,String tenant,String subject,boolean lock){
    return db.sql("""
        select v.onboarding_version_id from ouf_onboarding.onboarding_version v
        join ouf_onboarding.source s using(source_id)
        where v.source_id=:source and v.state='DRAFT' and s.status<>'DISABLED'
          and s.name=:name and s.owner_ref=:owner and s.metadata->>'managedTenantId'=:tenant
          and s.metadata->>'managedFileAssetId'=:asset
          and exists(select 1 from ouf_onboarding.managed_file_asset a join ouf_onboarding.file_profile f using(asset_id)
            where a.asset_id=cast(:asset as uuid) and f.profile_id=cast(:profile as uuid) and exists(select 1 from ouf_onboarding.managed_file_effective_binding ub where ub.asset_id=a.asset_id and ub.source_id=:source) and a.status='PROFILED')
          and v.configuration #>> '{extractionProfile,selection,assetId}'=:asset
          and v.configuration #>> '{extractionProfile,selection,fileProfileId}'=:profile
          and exists(select 1 from ouf_onboarding.managed_file_onboarding_attempt a
            where a.onboarding_version_id=v.onboarding_version_id and a.subject_id=:subject)
          and not exists(select 1 from ouf_onboarding.onboarding_version other where other.source_id=:source
            and other.onboarding_version_id<>v.onboarding_version_id
            and (other.version>v.version or other.state in ('DRAFT','IN_REVIEW','APPROVED','ACTIVE','REVOKED')))
          and not exists(select 1 from ouf_onboarding.published_configuration where source_id=:source)
        """+(lock?" for update of v":"")).param("source",source).param("asset",asset.toString()).param("profile",profile.toString())
        .param("name",name).param("owner",owner).param("tenant",tenant).param("subject",subject)
        .query(UUID.class).optional().orElse(null);
  }
  private void lockDraftInput(String sourceId,UUID assetId){
    db.sql("select 1 from (select pg_advisory_xact_lock(hashtextextended(:s,0))) locked")
        .param("s",sourceId).query(Integer.class).single();
    db.sql("select asset_id from ouf_onboarding.managed_file_asset where asset_id=:a for update")
        .param("a",assetId).query(UUID.class).single();
  }
  private UUID redraftParent(Map<String,Object> asset,UUID profile,String source,String name,String owner,String tenant,OnboardingService.Actor actor){
    if(tenant==null||!source.equals(asset.get("source_id")))return null;
    if(!"PROFILED".equals(asset.get("status")))throw conflict("ONB_REVISION_ASSET_NOT_PROFILED","File is not eligible for review");
    var metadata=(Map<?,?>)onboarding.source(source).get("metadata");
    if(!(metadata.get("managedFileAssetId") instanceof String origin))return null;
    requireTenantOwner(UUID.fromString(origin),tenant,actor.subject());
    return matchingRedraftParent(asset.get("asset_id").toString(),profile.toString(),source,name,owner,tenant,actor.subject());
  }

  boolean sameFileRedraftAllowed(UUID version,String source,String tenant,String subject){
    var selection=db.sql("""
        select v.configuration #>> '{extractionProfile,selection,assetId}' asset_id,
          v.configuration #>> '{extractionProfile,selection,fileProfileId}' profile_id,
          s.metadata->>'managedFileAssetId' origin_id,s.name,s.owner_ref
        from ouf_onboarding.onboarding_version v join ouf_onboarding.source s using(source_id)
        where v.onboarding_version_id=:v and v.source_id=:s
        """).param("v",version).param("s",source).query().singleRow();
    if(!(selection.get("asset_id") instanceof String selected)||!(selection.get("profile_id") instanceof String profile)
        ||!(selection.get("origin_id") instanceof String origin))return false;
    UUID assetId,originId;
    try{
      assetId=UUID.fromString(selected);
      originId=UUID.fromString(origin);
      UUID.fromString(profile);
    }catch(IllegalArgumentException malformedSelection){return false;}
    boolean profileAvailable=db.sql("""
        select exists(select 1 from ouf_onboarding.managed_file_asset a
          join ouf_onboarding.file_profile f using(asset_id)
          where a.asset_id=:asset and exists(select 1 from ouf_onboarding.managed_file_effective_binding ub where ub.asset_id=a.asset_id and ub.source_id=:source) and a.status='PROFILED' and f.profile_id::text=:profile)
        """).param("asset",assetId).param("source",source).param("profile",profile).query(Boolean.class).single();
    return profileAvailable&&tenantOwnerProven(assetId,tenant,subject)&&tenantOwnerProven(originId,tenant,subject)
        &&version.equals(matchingRedraftParent(selected,profile,source,(String)selection.get("name"),
            (String)selection.get("owner_ref"),tenant,subject));
  }

  private UUID matchingRedraftParent(String asset,String profile,String source,String name,String owner,String tenant,String subject){
    // Preserve the publication base of an existing revision; the refused plan is the new draft's parent.
    return db.sql("""
        select v.onboarding_version_id from ouf_onboarding.onboarding_version v
        join ouf_onboarding.source s using(source_id)
        join ouf_onboarding.managed_onboarding_review r using(onboarding_version_id,source_id)
        left join ouf_onboarding.source_property_revision base on base.onboarding_version_id=v.onboarding_version_id
        left join ouf_onboarding.onboarding_version anchor on anchor.onboarding_version_id=base.parent_version_id
        left join ouf_onboarding.managed_candidate_recovery w on w.candidate_id=v.onboarding_version_id and w.operation='WITHDRAW'
        where v.source_id=:s and v.state='REJECTED' and s.status<>'DISABLED'
          and r.tenant_id=:tenant and r.subject_id=:subject and s.owner_ref=:owner and s.name=:name
          and (s.metadata->>'managedFileAssetId'=:asset or base.replacement_profile_id=cast(:profile as uuid)
            or (base.replacement_profile_id is null and anchor.configuration #>> '{extractionProfile,selection,assetId}'=:asset
              and anchor.configuration #>> '{extractionProfile,selection,fileProfileId}'=:profile))
          and (base.onboarding_version_id is null or (base.tenant_id=:tenant and base.subject_id=:subject
            and anchor.state=base.parent_state_at_prepare and anchor.configuration_hash=base.parent_configuration_hash))
          and (s.metadata->>'managedTenantId' is null or s.metadata->>'managedTenantId'=:tenant)
          and v.configuration #>> '{extractionProfile,selection,assetId}'=:asset
          and v.configuration #>> '{extractionProfile,selection,fileProfileId}'=:profile
          and not exists(select 1 from ouf_onboarding.onboarding_version other where other.source_id=:s
            and (other.version>v.version or other.state in ('DRAFT','IN_REVIEW','APPROVED','REVOKED')
              or (other.state='ACTIVE' and other.onboarding_version_id is distinct from base.parent_version_id)))
          and (base.parent_state_at_prepare='ACTIVE' or not exists(select 1 from ouf_onboarding.published_configuration where source_id=:s))
          and ((w.candidate_id is null and exists(select 1 from ouf_onboarding.approval_decision d
                  where d.challenge_id=r.challenge_id and d.target_hash=v.configuration_hash and d.decision='REJECT'))
            or (w.candidate_id is not null and exists(select 1 from ouf_onboarding.audit_event e
                  where e.onboarding_version_id=v.onboarding_version_id and e.event_type='PUBLICATION_ENVELOPE_WITHDRAWN')))
        """).param("s",source).param("asset",asset).param("profile",profile)
        .param("name",name).param("owner",owner).param("tenant",tenant).param("subject",subject).query(UUID.class).optional().orElse(null);
  }
  @SuppressWarnings("unchecked") Map<String,Object> selectedProfile(Map<String,Object> profile,String layer){
    if(!Set.of("GEOPACKAGE","ACCESS","SHAPEFILE").contains(profile.get("format"))){if(layer!=null)throw invalid("ONB_LAYER_UNEXPECTED","Layer/table applies to multi-table datasets only");return profile;}
    if(layer==null||layer.isBlank())throw invalid("ONB_GPKG_LAYER_REQUIRED","Choose one feature layer or Access table");
    var metadata=(Map<String,Object>)profile.get("metadata");var layers=(List<Map<String,Object>>)metadata.get("layers");
    var selected=layers.stream().filter(l->layer.equals(l.get("layer"))).findFirst().orElseThrow(()->invalid("ONB_GPKG_LAYER_INVALID","Unknown layer or table"));
    var out=new LinkedHashMap<>(profile);out.putAll(selected);out.put("inferred_schema",selected.get("columns"));out.put("candidate_keys",selected.get("candidateKeys"));out.put("redacted_sample",selected.get("sample"));return out;
  }
  Map<String,String> projectedTypes(String sourceId,Map<?,?> extraction){
    var selection=ValueCompositions.map(extraction.get("selection"));
    var runtime=ValueCompositions.map(extraction.get("runtime"));
    UUID assetId=retainedFileId(selection.get("assetId"));
    var asset=asset(assetId);
    var profile=selectedProfile(fileProfile(assetId,retainedFileId(selection.get("fileProfileId"))),(String)runtime.get("layer"),retainedPin(selection));
    ValueCompositions.require(sourceId.equals(scopedAsset(asset,unitRef(profile),sourceId,null).get("source_id")) && Objects.equals(asset.get("content_hash"),runtime.get("contentHash")));
    var projection=ValueCompositions.list(ValueCompositions.map(extraction.get("projection")).get("FILE"),1,ManagedFileLimits.MAX_COLUMNS);
    var names=new HashSet<>(projection);
    var types=new HashMap<String,String>();
    for(Object raw:ValueCompositions.list(profile.get("inferred_schema"),1,ManagedFileLimits.MAX_COLUMNS)){
      var column=ValueCompositions.map(raw);String name=ValueCompositions.text(column,"name");
      if(names.contains(name))types.put(name,readerInputType(ValueCompositions.text(column,"dataType")));
    }
    ValueCompositions.require(projection.size()==names.size() && types.keySet().equals(names));
    return types;
  }
  // The XLSX v1 reader produces offset timestamps as lexical text for the D-084 readers.
  static String readerInputType(String inferredType) {
    return "DATETIME".equals(inferredType)?"STRING":inferredType;
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
  private DomainFailure boundAssetConflict(PrincipalContext principal,String bound){
    return new DomainFailure(HttpStatus.CONFLICT,"ONB_REVISION_ASSET_SOURCE_CONFLICT","Managed file is bound to another source",
        reviews.assetConflictProperties(principal,bound));
  }
  private static OffsetDateTime utc(Instant instant){return OffsetDateTime.ofInstant(instant,ZoneOffset.UTC);}
  private static void requireLease(Duration lease){if(lease.toSeconds()<10||lease.toSeconds()>3600)throw invalid("ONB_LEASE_INVALID","lease must be between PT10S and PT1H");}
  private static Map<String,Object> semanticTarget(String classIri,List<String> refs){
    if(refs==null||refs.isEmpty())throw invalid("ONB_SEMANTIC_REF_INVALID","At least one pinned semantic reference is required");
    for(int index=0;index<refs.size();index++){
      String ref=refs.get(index);
      int at=ref==null?-1:ref.lastIndexOf('@');
      if(at<1||at==ref.length()-1)throw invalid("ONB_SEMANTIC_REF_INVALID","Semantic references must pin id@version");
      if(UUID_TEXT.matcher(ref.substring(at+1)).matches())
        throw new DomainFailure(HttpStatus.UNPROCESSABLE_ENTITY,"ONB_SEMANTIC_REF_FORM_INVALID",
            "Use semanticId@semanticVersion",Map.of("index",index));
    }
    String first=refs.getFirst();
    int at=first.lastIndexOf('@');
    return Map.of("ontologyId",first.substring(0,at),"ontologyVersion",first.substring(at+1),"classIri",classIri);
  }
  private static String required(String value,String code){if(value==null||value.isBlank())throw invalid(code,"Required field configuration is missing");return value;}
  private Map<String,Object> jobView(Map<String,Object> row){Map<String,Object> out=new LinkedHashMap<>(row);if("SUCCEEDED".equals(row.get("state")) && row.get("profile_id") instanceof UUID id)out.put("unitSummary",new SourceUnitProfiles(fileProfile((UUID)row.get("asset_id"),id),hashes).summary());out.put("jobId",row.get("job_id"));out.put("status","READY".equals(row.get("state"))?"QUEUED":row.get("state"));out.put("createdAt",row.get("created_at"));out.put("updatedAt",row.get("updated_at"));out.put("nextRetryAt",row.get("next_attempt_at"));out.put("resultRef",row.get("profile_id"));out.put("errorCode",row.get("error_code"));return out;}
}

