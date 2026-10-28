package it.comune.trieste.ouf.onboarding.application;

import it.comune.trieste.ouf.authorization.PrincipalContext;
import it.comune.trieste.ouf.authorization.ResourceContext;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit native HUMAN repair of missing legacy tenant provenance, never a budget or upload. */
@Service
public class ManagedFileOwnershipRecovery {
  public record Decision(long expectedVersion,long expectedGeneration,String inputBindingHash,String reviewHash,String commandKey,String reason,boolean confirmed){}
  private final JdbcClient db;private final TransactionTemplate tx;private final CanonicalHash hashes;
  private final ManagedFileService files;private final ManagedProfileJobs jobs;
  public ManagedFileOwnershipRecovery(JdbcClient db,TransactionTemplate tx,CanonicalHash hashes,ManagedFileService files,ManagedProfileJobs jobs){this.db=db;this.tx=tx;this.hashes=hashes;this.files=files;this.jobs=jobs;}
  private static void require(boolean condition,String code){if(!condition)throw new DomainFailure(HttpStatus.CONFLICT,code,code);}
  private static long number(Map<String,Object> row,String key){return((Number)row.get(key)).longValue();}
  private void principal(PrincipalContext p){
    if(p==null||p.actorType()!=PrincipalContext.ActorType.HUMAN||p.subjectId()==null||p.subjectId().isBlank()||p.tenantId()==null||p.tenantId().isBlank()||p.tenantId().length()>256)
      throw new DomainFailure(HttpStatus.FORBIDDEN,"ONB_PROFILE_HUMAN_CAPABILITY_REQUIRED","Explicit authenticated HUMAN required");
  }
  private void actor(PrincipalContext p,OnboardingService.Actor a){principal(p);
    if(a==null||!"HUMAN_USER".equals(a.type())||!p.subjectId().equals(a.subject())||!a.capabilities().containsAll(Set.of("ouf.managed-source.file.profile","ouf.onboarding.quarantine.release")))
      throw new DomainFailure(HttpStatus.FORBIDDEN,"ONB_PROFILE_HUMAN_CAPABILITY_REQUIRED","Both current HUMAN recovery capabilities required");
  }
  public ResourceContext resource(UUID asset,UUID job,PrincipalContext p){
    principal(p);files.requireOwner(asset,p.subjectId());jobs.requireRetainedJob(asset,job);
    return new ResourceContext("managed-profile-job",job.toString(),p.tenantId(),null,Map.of("module","ONBOARDING","assetRef",asset.toString(),"jobRef",job.toString(),"detailLevel","TENANT_OPERATIONAL"));
  }
  private Map<String,Object> asset(UUID asset,boolean lock){return db.sql("select * from ouf_onboarding.managed_file_asset where asset_id=:a"+(lock?" for update":"")).param("a",asset).query().singleRow();}
  private Map<String,Object> job(UUID asset,UUID job,boolean lock){return db.sql("select * from ouf_onboarding.file_profile_job where asset_id=:a and job_id=:j"+(lock?" for update":"")).param("a",asset).param("j",job).query().singleRow();}
  private Map<String,Object> receipt(UUID asset){return db.sql("select * from ouf_onboarding.managed_file_owner_reconciliation where asset_id=:a").param("a",asset).query().listOfRows().stream().findFirst().orElse(Map.of());}
  private void eligible(UUID id,Map<String,Object> a,Map<String,Object> j,PrincipalContext p){
    require(p.subjectId().equals(a.get("uploaded_by")),"ONB_FILE_OWNER_IDENTITY_CHANGED");
    require(a.get("tenant_id")==null&&a.get("source_id")==null&&Set.of("STAGED","QUARANTINED").contains(a.get("status"))&&"text/csv".equals(a.get("media_type")),"ONB_FILE_OWNER_LEGACY_RECONCILIATION_BLOCKED");
    require("FAILED".equals(j.get("state"))&&number(j,"attempts")>=1&&j.get("profile_id")==null&&j.get("recovery_session_id")==null,"ONB_FILE_OWNER_JOB_RECONCILIATION_BLOCKED");
    require(j.get("input_binding_hash")==null||jobs.inputBinding(id).equals(j.get("input_binding_hash")),"ONB_FILE_OWNER_INPUT_CHANGED");
    var counts=db.sql("""
        select (select count(*) from ouf_onboarding.managed_file_upload_attempt where asset_id=:a) uploads,
          (select count(*) from ouf_onboarding.source where metadata->>'managedFileAssetId'=cast(:a as text)) sources,
          (select count(*) from ouf_onboarding.file_profile_job where asset_id=:a) jobs,
          (select count(*) from ouf_onboarding.managed_profile_budget_session where asset_id=:a) budgets
        """).param("a",id).query().singleRow();
    require(number(counts,"uploads")==0&&number(counts,"sources")==0&&number(counts,"jobs")==1&&number(counts,"budgets")==0&&receipt(id).isEmpty(),"ONB_FILE_OWNER_EXISTING_PROVENANCE_REQUIRES_RECONCILIATION");
  }
  private String review(UUID asset,UUID job,Map<String,Object> a,Map<String,Object> j,PrincipalContext p){
    return hashes.of(Arrays.asList("managed-file-legacy-owner-review.v1",asset.toString(),job.toString(),p.subjectId(),p.tenantId(),jobs.inputBinding(asset),
        a.get("status"),a.get("created_at").toString(),j.get("state"),number(j,"attempts"),number(j,"generation"),number(j,"lock_version"),j.get("error_code"),hashes.of(j.get("last_error"))));
  }
  public Map<String,Object> preview(UUID asset,UUID job,PrincipalContext p,OnboardingService.Actor actor){
    actor(p,actor);resource(asset,job,p);
    if(files.tenantOwnerProven(asset,p.tenantId(),p.subjectId()))return Map.of("status","OWNER_BINDING_ALREADY_PROVEN","assetId",asset,"jobId",job);
    var a=asset(asset,false);var j=job(asset,job,false);eligible(asset,a,j,p);
    return Map.ofEntries(Map.entry("status","OWNER_BINDING_REVIEW_REQUIRED"),Map.entry("assetId",asset),Map.entry("jobId",job),Map.entry("tenantId",p.tenantId()),Map.entry("subjectId",p.subjectId()),
        Map.entry("contentHash",a.get("content_hash")),Map.entry("sizeBytes",a.get("size_bytes")),Map.entry("uploadedAt",a.get("created_at").toString()),Map.entry("jobStatus",j.get("state")),
        Map.entry("cumulativeAttempts",j.get("attempts")),Map.entry("expectedVersion",j.get("lock_version")),Map.entry("expectedGeneration",j.get("generation")),
        Map.entry("inputBindingHash",jobs.inputBinding(asset)),Map.entry("reviewHash",review(asset,job,a,j,p)),Map.entry("humanBudgetGranted",false),Map.entry("resumed",false));
  }
  private Map<String,Object> result(Map<String,Object> receipt,boolean reconciled){return Map.of("status","OWNER_BINDING_RECONCILED","assetId",receipt.get("asset_id"),"jobId",receipt.get("job_id"),"tenantId",receipt.get("tenant_id"),
      "subjectId",receipt.get("subject_id"),"decisionRef",receipt.get("decision_ref"),"reconciled",reconciled,"humanBudgetGranted",false,"resumed",false);}
  public Map<String,Object> reconcile(UUID asset,UUID job,PrincipalContext p,OnboardingService.Actor actor,Decision d){
    actor(p,actor);resource(asset,job,p);
    require(d!=null&&d.confirmed()&&d.expectedVersion()>=0&&d.expectedVersion()<=9007199254740991L&&d.expectedGeneration()>=0&&d.expectedGeneration()<=9007199254740991L
        &&d.inputBindingHash()!=null&&d.inputBindingHash().matches("sha256:[a-f0-9]{64}")&&d.reviewHash()!=null&&d.reviewHash().matches("sha256:[a-f0-9]{64}")
        &&d.commandKey()!=null&&d.commandKey().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")&&d.reason()!=null&&d.reason().strip().length()>=10&&d.reason().length()<=500&&d.reason().chars().noneMatch(Character::isISOControl),"ONB_FILE_OWNER_DECISION_INVALID");
    var bounded=new TransactionTemplate(tx.getTransactionManager());bounded.setTimeout(12);
    return bounded.execute(status->{
      db.sql("set local lock_timeout='2s'").update();db.sql("set local statement_timeout='5s'").update();
      db.sql("select 1 from pg_advisory_xact_lock(hashtextextended(:k,0))").param("k","managed-file-owner.v1:"+p.tenantId().length()+":"+p.tenantId()+p.subjectId().length()+":"+p.subjectId()+d.commandKey()).query(Integer.class).single();
      // Same lock order as profiling budget admission: job, then immutable input.
      var j=job(asset,job,true);var a=asset(asset,true);String fingerprint=hashes.of(d);
      var command=db.sql("select asset_id,job_id,request_hash from ouf_onboarding.managed_file_owner_reconciliation where tenant_id=:t and subject_id=:s and command_key=:k")
          .param("t",p.tenantId()).param("s",p.subjectId()).param("k",d.commandKey()).query().listOfRows();
      require(command.isEmpty()||asset.equals(command.getFirst().get("asset_id"))&&job.equals(command.getFirst().get("job_id"))&&fingerprint.equals(command.getFirst().get("request_hash")),"ONB_FILE_OWNER_COMMAND_CONFLICT");
      var prior=receipt(asset);
      if(!prior.isEmpty()){
        require(job.equals(prior.get("job_id"))&&p.subjectId().equals(prior.get("subject_id"))&&p.tenantId().equals(prior.get("tenant_id"))&&d.commandKey().equals(prior.get("command_key"))&&fingerprint.equals(prior.get("request_hash")),"ONB_FILE_OWNER_COMMAND_CONFLICT");
        require(jobs.inputBinding(asset).equals(prior.get("input_binding_hash")),"ONB_FILE_OWNER_INPUT_CHANGED");return result(prior,true);
      }
      require(number(j,"lock_version")==d.expectedVersion()&&number(j,"generation")==d.expectedGeneration()&&jobs.inputBinding(asset).equals(d.inputBindingHash())&&review(asset,job,a,j,p).equals(d.reviewHash()),"ONB_FILE_OWNER_STALE_REVIEW");
      eligible(asset,a,j,p);
      db.sql("""
          insert into ouf_onboarding.managed_file_owner_reconciliation(asset_id,job_id,tenant_id,subject_id,content_hash,size_bytes,input_binding_hash,review_hash,command_key,request_hash,decision_ref,reason,prior_job_version,prior_generation,cumulative_attempts)
          values(:a,:j,:t,:s,:h,:z,:b,:r,:k,:f,:d,:why,:v,:g,:n)
          """).param("a",asset).param("j",job).param("t",p.tenantId()).param("s",p.subjectId()).param("h",a.get("content_hash")).param("z",a.get("size_bytes"))
          .param("b",d.inputBindingHash()).param("r",d.reviewHash()).param("k",d.commandKey()).param("f",fingerprint).param("d",UUID.randomUUID()).param("why",d.reason().strip())
          .param("v",d.expectedVersion()).param("g",d.expectedGeneration()).param("n",j.get("attempts")).update();
      // No asset/job/quarantine, upload/source review, budget or event row is rewritten.
      return result(receipt(asset),false);
    });
  }
}
