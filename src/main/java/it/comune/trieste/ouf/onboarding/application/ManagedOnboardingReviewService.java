package it.comune.trieste.ouf.onboarding.application;

import it.comune.trieste.ouf.authorization.*;
import it.comune.trieste.ouf.onboarding.authorization.AuthorizationPolicyRegistry;
import it.comune.trieste.ouf.onboarding.domain.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Prepares one immutable source review. A delegated request cannot approve or activate it. */
@Service
public class ManagedOnboardingReviewService {
  public static final String PREPARE="ouf.managed-source.onboarding.review.prepare";
  public static final String READ="ouf.managed-source.onboarding.read";
  private final JdbcClient db;
  private final OnboardingService onboarding;
  private final ManagedFileService files;
  private final CanonicalHash hashes;
  private final AuthorizationPolicyRegistry policy;
  private final String publicOrigin;
  public record Prepare(String sourceId,UUID onboardingVersionId,long expectedVersion,
      String commandKey,ManagedFileExecutionProposal.Proposal executionProposal,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
      String candidateAction) {
    public Prepare(String sourceId,UUID version,long expectedVersion,String key,ManagedFileExecutionProposal.Proposal proposal){
      this(sourceId,version,expectedVersion,key,proposal,null);
    }
  }
  public ManagedOnboardingReviewService(JdbcClient db,OnboardingService onboarding,
      ManagedFileService files,CanonicalHash hashes,AuthorizationPolicyRegistry policy,
      @Value("${ouf.authorization.ths.public-origin:}") String publicOrigin){
    this.db=db;this.onboarding=onboarding;this.files=files;this.hashes=hashes;
    this.policy=policy;this.publicOrigin=publicOrigin;
  }
  private static DomainFailure fail(HttpStatus status,String code){return new DomainFailure(status,code,code);}
  private static void identity(String source,UUID version){
    if(source==null||!source.matches("[A-Za-z0-9_.:-]{1,160}")||version==null)
      throw fail(HttpStatus.BAD_REQUEST,"ONB_REVIEW_IDENTITY_INVALID");
  }
  private void authorize(PrincipalContext principal,String capability,String operation,String source,UUID version){
    identity(source,version);
    if(principal==null||principal.actorType()!=PrincipalContext.ActorType.HUMAN
        ||!principal.scopes().contains(capability))throw fail(HttpStatus.FORBIDDEN,"ONB_REVIEW_HUMAN_DELEGATION_REQUIRED");
    var resource=new ResourceContext("source",source,principal.tenantId(),null,
        Map.of("onboardingVersionId",version.toString()));
    if(!policy.authorizeActive(principal,resource,capability,operation,Instant.now()).allowed())
      throw fail(HttpStatus.FORBIDDEN,"ONB_REVIEW_AUTHORIZATION_DENIED");
  }
  @SuppressWarnings("unchecked") private Map<String,Object> ownedVersion(PrincipalContext p,String source,UUID version){
    var current=onboarding.version(source,version);
    var metadata=(Map<String,Object>)onboarding.source(source).get("metadata");
    if(metadata.containsKey("managedTenantId")&&!Objects.equals(metadata.get("managedTenantId"),p.tenantId()))
      throw fail(HttpStatus.FORBIDDEN,"ONB_REVIEW_AUTHORIZATION_DENIED");
    Object raw=metadata.get("managedFileAssetId");
    if(!(raw instanceof String value))throw fail(HttpStatus.CONFLICT,"ONB_REVIEW_MANAGED_SOURCE_REQUIRED");
    files.requireOwner(UUID.fromString(value),p.subjectId());
    return current;
  }
  private String url(UUID challenge){
    URI origin;
    try{origin=URI.create(publicOrigin);}catch(Exception e){throw fail(HttpStatus.SERVICE_UNAVAILABLE,"ONB_REVIEW_THS_ORIGIN_REQUIRED");}
    if(!"https".equals(origin.getScheme())||origin.getHost()==null||origin.getRawUserInfo()!=null
        ||origin.getRawQuery()!=null||origin.getRawFragment()!=null
        ||!(origin.getPath().isEmpty()||"/".equals(origin.getPath())))
      throw fail(HttpStatus.SERVICE_UNAVAILABLE,"ONB_REVIEW_THS_ORIGIN_REQUIRED");
    return publicOrigin.replaceAll("/+$","")+"/trusted-human/onboarding/?challenge="+challenge;
  }
  @Transactional public Map<String,Object> prepare(PrincipalContext p,Prepare request,String correlation){
    if(request==null)throw fail(HttpStatus.BAD_REQUEST,"ONB_REVIEW_REQUEST_INVALID");
    authorize(p,PREPARE,"COMMAND",request.sourceId(),request.onboardingVersionId());
    if(request.commandKey()==null||!request.commandKey().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        ||request.expectedVersion()<0||request.expectedVersion()>9007199254740991L)
      throw fail(HttpStatus.BAD_REQUEST,"ONB_REVIEW_REQUEST_INVALID");
    url(request.onboardingVersionId()); // Validate delivery configuration before any mutation.
    String fingerprint=hashes.of(request);
    if(request.candidateAction()!=null && (!Set.of("WITHDRAW","RESTART_DRAFT").contains(request.candidateAction())
        ||request.executionProposal()!=null))throw fail(HttpStatus.BAD_REQUEST,"ONB_REVIEW_REQUEST_INVALID");
    // Both command journals share one key namespace, including across source versions.
    db.sql("select 1 from (select pg_advisory_xact_lock(hashtextextended(:k,0))) locked")
        .param("k","review-command:"+hashes.of(List.of(p.tenantId(),p.subjectId(),request.commandKey()))).query(Integer.class).single();
    db.sql("select 1 from (select pg_advisory_xact_lock(hashtextextended(:v,0))) locked")
        .param("v",request.onboardingVersionId().toString()).query(Integer.class).single();
    var current=ownedVersion(p,request.sourceId(),request.onboardingVersionId());
    var past=db.sql("select request_hash,result_id from ouf_onboarding.managed_candidate_recovery where tenant_id=:t and subject_id=:s and command_key=:k")
        .param("t",p.tenantId()).param("s",p.subjectId()).param("k",request.commandKey()).query().listOfRows();
    if(!past.isEmpty()){
      if(!fingerprint.equals(past.getFirst().get("request_hash")))throw fail(HttpStatus.CONFLICT,"ONB_REVIEW_COMMAND_CONFLICT");
      UUID result=(UUID)past.getFirst().get("result_id");
      authorize(p,PREPARE,"COMMAND",request.sourceId(),result);
      requireCandidateOwner(p,request.sourceId(),request.onboardingVersionId());
      var resultReview=db.sql("select * from ouf_onboarding.managed_onboarding_review where onboarding_version_id=:v")
          .param("v",result).query().listOfRows();
      if(!resultReview.isEmpty()&&(!p.tenantId().equals(resultReview.getFirst().get("tenant_id"))
          ||!p.subjectId().equals(resultReview.getFirst().get("subject_id"))))throw fail(HttpStatus.FORBIDDEN,"ONB_REVIEW_AUTHORIZATION_DENIED");
      return receipt(ownedVersion(p,request.sourceId(),result),resultReview.isEmpty()?null:resultReview.getFirst());
    }
    var retained=db.sql("select * from ouf_onboarding.managed_onboarding_review where onboarding_version_id=:v or (tenant_id=:t and subject_id=:s and command_key=:k) for update")
        .param("v",request.onboardingVersionId()).param("t",p.tenantId()).param("s",p.subjectId())
        .param("k",request.commandKey()).query().listOfRows();
    if(!retained.isEmpty()){
      if(retained.size()!=1||!retained.getFirst().get("onboarding_version_id").equals(request.onboardingVersionId())
          ||!retained.getFirst().get("tenant_id").equals(p.tenantId())
          ||!retained.getFirst().get("subject_id").equals(p.subjectId()))
        throw fail(HttpStatus.CONFLICT,"ONB_REVIEW_COMMAND_CONFLICT");
      var review=retained.getFirst();
      if(review.get("command_key").equals(request.commandKey())){
        if(!review.get("request_hash").equals(fingerprint))throw fail(HttpStatus.CONFLICT,"ONB_REVIEW_COMMAND_CONFLICT");
        return receipt(ownedVersion(p,request.sourceId(),request.onboardingVersionId()),review);
      }
    }
    if(request.candidateAction()!=null||!retained.isEmpty())
      return recoverCandidate(p,request,fingerprint,retained.isEmpty()?null:retained.getFirst(),correlation);
    if(!"DRAFT".equals(current.get("state"))
        ||((Number)current.get("lock_version")).longValue()!=request.expectedVersion())
      throw fail(HttpStatus.PRECONDITION_FAILED,"ONB_REVIEW_STALE_VERSION");
    var actor=new OnboardingService.Actor(p.subjectId(),"HUMAN_USER",Set.of(PREPARE));
    long version=request.expectedVersion();
    if(request.executionProposal()!=null){
      @SuppressWarnings("unchecked") var configuration=(Map<String,Object>)current.get("configuration");
      @SuppressWarnings("unchecked") var metadata=(Map<String,Object>)onboarding.source(request.sourceId()).get("metadata");
      var selection=ManagedReviewSnapshot.child(ManagedReviewSnapshot.child(configuration,"extractionProfile"),"selection");
      UUID selected=UUID.fromString((String)selection.get("assetId"));
      if(!selected.toString().equals(metadata.get("managedFileAssetId"))){
        files.requireTenantOwner(selected,p.tenantId(),p.subjectId());
        boolean sameRefusedInput=db.sql("""
            select exists(select 1 from ouf_onboarding.source_property_revision r
              join ouf_onboarding.file_profile f on f.profile_id=:profile
              join ouf_onboarding.onboarding_version anchor on anchor.onboarding_version_id=r.parent_version_id
              join ouf_onboarding.onboarding_version parent on parent.onboarding_version_id=r.onboarding_version_id
              where parent.onboarding_version_id=:parent and parent.state='REJECTED' and r.source_id=:source
                and r.tenant_id=:tenant and r.subject_id=:subject and f.asset_id=:asset
                and parent.configuration #>> '{extractionProfile,selection,fileProfileId}'=cast(:profile as text)
                and (r.replacement_profile_id=:profile or (r.replacement_profile_id is null
                  and anchor.configuration #>> '{extractionProfile,selection,fileProfileId}'=cast(:profile as text))))
            """).param("parent",current.get("parent_version_id"),java.sql.Types.OTHER).param("source",request.sourceId())
            .param("tenant",p.tenantId()).param("subject",p.subjectId()).param("asset",selected)
            .param("profile",UUID.fromString((String)selection.get("fileProfileId"))).query(Boolean.class).single();
        if(!sameRefusedInput)throw fail(HttpStatus.CONFLICT,"ONB_REVISION_ASSET_SOURCE_CONFLICT");
      }
      var asset=files.asset(selected);
      var complete=ManagedFileExecutionProposal.compile(configuration,asset,p.tenantId(),request.executionProposal());
      current=onboarding.patchVersion(request.sourceId(),request.onboardingVersionId(),version,complete,actor,correlation);
      version=((Number)current.get("lock_version")).longValue();
    }
    current=onboarding.submit(request.sourceId(),request.onboardingVersionId(),version,actor,correlation);
    var challenge=onboarding.createChallenge(request.sourceId(),request.onboardingVersionId(),actor,correlation);
    db.sql("insert into ouf_onboarding.managed_onboarding_review(tenant_id,subject_id,command_key,request_hash,source_id,onboarding_version_id,challenge_id,configuration_hash) values(:t,:s,:k,:r,:source,:v,:c,:h)")
        .param("t",p.tenantId()).param("s",p.subjectId()).param("k",request.commandKey()).param("r",fingerprint)
        .param("source",request.sourceId()).param("v",request.onboardingVersionId())
        .param("c",challenge.get("challenge_id")).param("h",current.get("configuration_hash")).update();
    if(current.get("parent_version_id") instanceof UUID parent){
      // Carry the original activation fence through a refusal, even across several redrafts.
      db.sql("""
          insert into ouf_onboarding.source_property_revision(tenant_id,subject_id,command_key,request_hash,source_id,
            parent_version_id,parent_configuration_hash,onboarding_version_id,publication_effect,replacement_profile_id,parent_state_at_prepare)
          select r.tenant_id,r.subject_id,:key,:hash,r.source_id,coalesce(base.parent_version_id,r.onboarding_version_id),
            coalesce(base.parent_configuration_hash,r.configuration_hash),:v,'NEW_PUBLICATION_MAY_START_ACQUISITION',
            cast(parent.configuration #>> '{extractionProfile,selection,fileProfileId}' as uuid),
            coalesce(base.parent_state_at_prepare,'REJECTED')
          from ouf_onboarding.managed_onboarding_review r
          join ouf_onboarding.onboarding_version parent using(onboarding_version_id,source_id)
          join ouf_onboarding.approval_decision d on d.challenge_id=r.challenge_id and d.decision='REJECT'
            and d.target_hash=r.configuration_hash
          left join ouf_onboarding.source_property_revision base on base.onboarding_version_id=r.onboarding_version_id and base.source_id=r.source_id
          where r.onboarding_version_id=:parent and r.source_id=:source and parent.state='REJECTED'
            and r.tenant_id=:tenant and r.subject_id=:subject
            and not exists(select 1 from ouf_onboarding.managed_candidate_recovery w where w.result_id=:v and w.operation='RESTART_DRAFT')
          """).param("key","redraft:"+request.onboardingVersionId()).param("hash",fingerprint)
          .param("v",request.onboardingVersionId()).param("parent",parent).param("source",request.sourceId())
          .param("tenant",p.tenantId()).param("subject",p.subjectId()).update();
    }
    freezeSnapshot(current);
    return receipt(current,Map.of("challenge_id",challenge.get("challenge_id"),"configuration_hash",current.get("configuration_hash")));
  }

  private void requireCandidateOwner(PrincipalContext p,String source,UUID version){
    var metadata=(Map<?,?>)onboarding.source(source).get("metadata");
    if(!(metadata.get("managedFileAssetId") instanceof String asset)
        ||(metadata.containsKey("managedTenantId")&&!p.tenantId().equals(metadata.get("managedTenantId"))))
      throw fail(HttpStatus.FORBIDDEN,"ONB_REVIEW_AUTHORIZATION_DENIED");
    files.requireTenantOwner(UUID.fromString(asset),p.tenantId(),p.subjectId());
    db.sql("""
        select f.asset_id from ouf_onboarding.source_property_revision r
        join ouf_onboarding.file_profile f on f.profile_id=r.replacement_profile_id
        where r.onboarding_version_id=:v
        """).param("v",version).query(UUID.class).optional()
        .ifPresent(id->files.requireTenantOwner(id,p.tenantId(),p.subjectId()));
  }

  private Map<String,Object> recoverCandidate(PrincipalContext p,Prepare request,String fingerprint,
      Map<String,Object> review,String correlation){
    String source=request.sourceId();
    UUID version=request.onboardingVersionId();
    String operation=request.candidateAction()==null?"RENEW_CHALLENGE":request.candidateAction();
    if("RENEW_CHALLENGE".equals(operation)&&request.executionProposal()!=null)
      throw fail(HttpStatus.CONFLICT,"ONB_REVIEW_COMMAND_CONFLICT");

    db.sql("select 1 from (select pg_advisory_xact_lock(hashtextextended(:s,0))) locked")
        .param("s",source).query(Integer.class).single();
    db.sql("select onboarding_version_id from ouf_onboarding.onboarding_version where onboarding_version_id=:v for update")
        .param("v",version).query(UUID.class).single();
    var current=ownedVersion(p,source,version);
    requireCandidateOwner(p,source,version);
    String hash=hashes.of(current.get("configuration"));
    if((current.get("configuration_hash")!=null&&!hash.equals(current.get("configuration_hash")))
        ||(review!=null&&!hash.equals(review.get("configuration_hash"))))
      throw fail(HttpStatus.CONFLICT,"ONB_REVIEW_CONFIGURATION_DRIFT");
    var actor=new OnboardingService.Actor(p.subjectId(),"HUMAN_USER",Set.of(PREPARE));
    UUID old=review==null?null:(UUID)review.get("challenge_id"),issued=null,result=version;
    if("RENEW_CHALLENGE".equals(operation)){
      if(!"IN_REVIEW".equals(current.get("state"))||old==null||
          db.sql("""
              update ouf_onboarding.approval_challenge set status='EXPIRED'
              where challenge_id=:c and onboarding_version_id=:v and configuration_hash=:h
                and status in ('CREATED','EXPIRED') and expires_at<=clock_timestamp()
                and not exists(select 1 from ouf_onboarding.approval_decision where onboarding_version_id=:v)
              """).param("c",old).param("v",version).param("h",hash).update()!=1)
        throw fail(HttpStatus.CONFLICT,"ONB_REVIEW_COMMAND_CONFLICT");
    }
    if(((Number)current.get("lock_version")).longValue()!=request.expectedVersion())
      throw fail(HttpStatus.PRECONDITION_FAILED,"ONB_REVIEW_STALE_VERSION");
    switch(operation){
      case "RENEW_CHALLENGE" -> {
        issued=(UUID)onboarding.createChallenge(source,version,actor,correlation).get("challenge_id");
        db.sql("update ouf_onboarding.managed_onboarding_review set challenge_id=:new where challenge_id=:old")
            .param("new",issued).param("old",old).update();
        db.sql("update ouf_onboarding.onboarding_version set lock_version=lock_version+1 where onboarding_version_id=:v")
            .param("v",version).update();
        review=new LinkedHashMap<>(review);review.put("challenge_id",issued);
      }
      case "WITHDRAW" -> {
        if(!Set.of("DRAFT","IN_REVIEW","APPROVED").contains(current.get("state"))||
            db.sql("select exists(select 1 from ouf_onboarding.published_configuration where onboarding_version_id=:v)")
                .param("v",version).query(Boolean.class).single())
          throw fail(HttpStatus.CONFLICT,"ONB_CANDIDATE_WITHDRAW_NOT_ALLOWED");
        db.sql("update ouf_onboarding.onboarding_version set state='REJECTED',configuration_hash=:h,frozen_at=coalesce(frozen_at,clock_timestamp()),lock_version=lock_version+1,updated_at=clock_timestamp() where onboarding_version_id=:v")
            .param("v",version).param("h",hash).update();
        db.sql("update ouf_onboarding.approval_challenge set status='INVALIDATED' where onboarding_version_id=:v and status='CREATED'")
            .param("v",version).update();
        db.sql("update ouf_onboarding.managed_onboarding_review set readiness_generation=readiness_generation+1,readiness_lease_until=null where onboarding_version_id=:v")
            .param("v",version).update();
      }
      case "RESTART_DRAFT" -> {
        // Only the latest never-published lineage can restart. Active parents use revision.prepare.
        boolean eligible=db.sql("""
            select not exists(select 1 from ouf_onboarding.published_configuration where source_id=:s)
              and not exists(select 1 from ouf_onboarding.onboarding_version where source_id=:s
                and (version>:n or state in ('DRAFT','IN_REVIEW','APPROVED','ACTIVE','REVOKED')))
              and not exists(select 1 from ouf_onboarding.source_property_revision where onboarding_version_id=:v)
            """).param("s",source).param("n",current.get("version")).param("v",version).query(Boolean.class).single();
        if(!eligible||!"REJECTED".equals(current.get("state")))throw fail(HttpStatus.CONFLICT,"ONB_CANDIDATE_RESTART_NOT_ALLOWED");
        var draft=onboarding.cloneVersion(source,version,actor,correlation);
        result=(UUID)draft.get("onboarding_version_id");
        @SuppressWarnings("unchecked") var configuration=(Map<String,Object>)draft.get("configuration");
        int number=((Number)draft.get("version")).intValue();
        SourceRevisionService.revisionMetadata(configuration,number);
        if(configuration.get("extractionProfile") instanceof Map<?,?> extraction
            &&extraction.get("runtime") instanceof Map<?,?> runtime&&runtime.get("execution") instanceof Map<?,?> raw){
          @SuppressWarnings("unchecked") var execution=(Map<String,Object>)raw;
          if(source.equals(execution.get("sourceSchemaId")))execution.put("sourceSchemaVersion",String.valueOf(number));
        }
        onboarding.patchVersion(source,result,((Number)draft.get("lock_version")).longValue(),configuration,actor,correlation);
        review=null;
      }
      default -> throw fail(HttpStatus.BAD_REQUEST,"ONB_REVIEW_REQUEST_INVALID");
    }
    db.sql("""
        insert into ouf_onboarding.managed_candidate_recovery
          (tenant_id,subject_id,command_key,request_hash,operation,source_id,candidate_id,result_id,previous_challenge,issued_challenge)
        values(:t,:u,:k,:h,:op,:s,:v,:result,:old,:issued)
        """).param("t",p.tenantId()).param("u",p.subjectId()).param("k",request.commandKey()).param("h",fingerprint)
        .param("op",operation).param("s",source).param("v",version).param("result",result)
        .param("old",old,java.sql.Types.OTHER).param("issued",issued,java.sql.Types.OTHER).update();
    return receipt(onboarding.version(source,result),review);
  }

  /** Internal owner composition; called in the revision transaction after full validation/submission. */
  Map<String,Object> registerReview(PrincipalContext p,String source,UUID version,String key,String fingerprint,
      Map<String,Object> frozen,OnboardingService.Actor actor,String correlation){
    url(version);
    var challenge=onboarding.createChallenge(source,version,actor,correlation);
    db.sql("insert into ouf_onboarding.managed_onboarding_review(tenant_id,subject_id,command_key,request_hash,source_id,onboarding_version_id,challenge_id,configuration_hash) values(:t,:s,:k,:r,:source,:v,:c,:h)")
        .param("t",p.tenantId()).param("s",p.subjectId()).param("k",key).param("r",fingerprint)
        .param("source",source).param("v",version).param("c",challenge.get("challenge_id")).param("h",frozen.get("configuration_hash")).update();
    freezeSnapshot(frozen);
    return receipt(frozen,Map.of("challenge_id",challenge.get("challenge_id"),"configuration_hash",frozen.get("configuration_hash")));
  }


  private Map<?,?> buildSnapshot(Map<String,Object> version){
    var config=ManagedReviewSnapshot.object(version.get("configuration"));
    var extraction=ManagedReviewSnapshot.child(config,"extractionProfile");
    var selection=ManagedReviewSnapshot.child(extraction,"selection");
    var runtime=ManagedReviewSnapshot.child(extraction,"runtime");
    if(!selection.containsKey("assetId")&&!selection.containsKey("fileProfileId"))return Map.of(); // Non-file revision.
    UUID assetId=UUID.fromString((String)selection.get("assetId")),profileId=UUID.fromString((String)selection.get("fileProfileId"));
    var asset=files.asset(assetId);
    if(!Objects.equals(asset.get("source_id"),version.get("source_id"))||!Objects.equals(asset.get("content_hash"),runtime.get("contentHash")))
      throw fail(HttpStatus.CONFLICT,"ONB_REVIEW_CONFIGURATION_DRIFT");
    var profile=files.selectedProfile(files.fileProfile(assetId,profileId),(String)runtime.get("layer"));
    var source=onboarding.source((String)version.get("source_id"));
    return ManagedReviewSnapshot.build(config,asset,profile,source.get("owner_ref"),source.get("name"));
  }
  private void freezeSnapshot(Map<String,Object> version){
    db.sql("insert into ouf_onboarding.managed_review_snapshot values(:v,cast(:snapshot as jsonb))")
        .param("v",version.get("onboarding_version_id")).param("snapshot",onboarding.write(buildSnapshot(version))).update();
  }
  private Map<?,?> snapshot(Map<String,Object> version){
    return db.sql("select snapshot::text from ouf_onboarding.managed_review_snapshot where onboarding_version_id=:v")
        .param("v",version.get("onboarding_version_id")).query(String.class).optional()
        .<Map<?,?>>map(value->onboarding.retainedJson(value,Map.class)).orElseGet(()->buildSnapshot(version));
  }
  private List<?> feedback(Object version){
    return db.sql("select review_feedback::text from ouf_onboarding.approval_decision where onboarding_version_id=:v and review_outcome='CHANGES_REQUESTED'")
        .param("v",version).query(String.class).optional().map(value->onboarding.retainedJson(value,List.class)).orElse(List.of());
  }

  /** Resolve a current reference from owner state, not chatbot memory. */
  // The owner read also persists the authorization decision in this transaction.
  @Transactional @SuppressWarnings("unchecked")
  public Map<String,Object> readLatest(PrincipalContext p,String source){
    if(source==null||!source.matches("[A-Za-z0-9_.:-]{1,160}"))throw fail(HttpStatus.BAD_REQUEST,"ONB_REVIEW_ARGUMENTS_INVALID");
    var rows=db.sql("select onboarding_version_id from ouf_onboarding.onboarding_version where source_id=:s order by version desc limit 1")
      .param("s",source).query(UUID.class).list();
    // No distinct not-found response before owner authorization.
    if(rows.isEmpty())throw fail(HttpStatus.FORBIDDEN,"ONB_REVIEW_AUTHORIZATION_DENIED");
    UUID version=rows.getFirst();authorize(p,READ,"READ",source,version);
    var metadata=(Map<String,Object>)onboarding.source(source).get("metadata");
    boolean proven=metadata.containsKey("managedTenantId")
      ?Objects.equals(metadata.get("managedTenantId"),p.tenantId())
      :db.sql("select 1 from ouf_onboarding.managed_onboarding_review where source_id=:source and tenant_id=:tenant and subject_id=:subject limit 1")
        .param("source",source).param("tenant",p.tenantId()).param("subject",p.subjectId()).query(Integer.class).optional().isPresent();
    if(!proven)throw fail(HttpStatus.FORBIDDEN,"ONB_REVIEW_OWNER_SCOPE_UNPROVEN");
    return read(p,source,version);
  }

  @Transactional public Map<String,Object> read(PrincipalContext p,String source,UUID version){
    authorize(p,READ,"READ",source,version);
    var current=ownedVersion(p,source,version);
    var retained=db.sql("select * from ouf_onboarding.managed_onboarding_review where onboarding_version_id=:v")
        .param("v",version).query().listOfRows();
    if(!retained.isEmpty()&&(!Objects.equals(retained.getFirst().get("tenant_id"),p.tenantId())||!Objects.equals(retained.getFirst().get("subject_id"),p.subjectId())))throw fail(HttpStatus.FORBIDDEN,"ONB_REVIEW_AUTHORIZATION_DENIED");
    return receipt(current,retained.isEmpty()?null:retained.getFirst());
  }
  Map<String,Object> receipt(Map<String,Object> current,Map<String,Object> review){
    var result=new LinkedHashMap<String,Object>();
    result.put("sourceId",current.get("source_id"));result.put("onboardingVersionId",current.get("onboarding_version_id"));
    result.put("state",current.get("state"));result.put("lockVersion",current.get("lock_version"));
    result.put("configurationHash",current.get("configuration_hash"));
    if("REJECTED".equals(current.get("state"))&&db.sql("select exists(select 1 from ouf_onboarding.managed_candidate_recovery where candidate_id=:v and operation='WITHDRAW')")
        .param("v",current.get("onboarding_version_id")).query(Boolean.class).single())result.put("candidateDisposition","WITHDRAWN");
    result.put("humanApprovalRequired",!Set.of("APPROVED","ACTIVE","SUPERSEDED").contains(current.get("state")));
    if(review!=null){
      if(!Objects.equals(review.get("configuration_hash"),current.get("configuration_hash")))
        throw fail(HttpStatus.CONFLICT,"ONB_REVIEW_CONFIGURATION_DRIFT");
      UUID id=(UUID)review.get("challenge_id");var card=onboarding.approvalCard(id);
      result.put("challengeId",id);result.put("challengeStatus",card.get("status"));
      result.put("expiresAt",card.get("expires_at"));result.put("reviewUrl",url(id));
      boolean expired=db.sql("select expires_at<=clock_timestamp() from ouf_onboarding.approval_challenge where challenge_id=:c")
          .param("c",id).query(Boolean.class).single();
      result.put("challengeExpired",expired);
      if(expired&&"IN_REVIEW".equals(current.get("state"))&&Set.of("CREATED","EXPIRED").contains(card.get("status")))
        result.put("nextAction",Map.of("tool","source.onboarding.review.prepare","sourceId",current.get("source_id"),
            "onboardingVersionId",current.get("onboarding_version_id"),"expectedVersion",current.get("lock_version"),
            "newCommandKeyRequired",true));
    }
    if("REJECTED".equals(current.get("state"))&&review!=null&&!"WITHDRAWN".equals(result.get("candidateDisposition"))){
      var notes=feedback(current.get("onboarding_version_id"));
      if(!notes.isEmpty())result.put("state","CHANGES_REQUESTED");
      result.put("reviewFeedback",notes);
      var selection=ManagedReviewSnapshot.child(ManagedReviewSnapshot.child(ManagedReviewSnapshot.object(current.get("configuration")),"extractionProfile"),"selection");
      if(selection.containsKey("assetId")&&selection.containsKey("fileProfileId")&&db.sql(
          "select not exists(select 1 from ouf_onboarding.onboarding_version where source_id=:s and version>:n)")
          .param("s",current.get("source_id")).param("n",current.get("version")).query(Boolean.class).single())result.put("nextAction",Map.of("tool","source.onboarding.create","sourceId",current.get("source_id"),
          "assetId",selection.get("assetId"),"profileId",selection.get("fileProfileId"),"newCommandKeyRequired",true,
          "instruction","Prepara una nuova bozza sullo stesso file tenendo conto delle modifiche richieste."));
    }
    var publications=db.sql("select publication_id,checksum,active from ouf_onboarding.published_configuration where onboarding_version_id=:v order by created_at desc limit 1")
        .param("v",current.get("onboarding_version_id")).query().listOfRows();
    if(!publications.isEmpty())result.put("publication",publications.getFirst());
    result.put("ingestionCompatibilityObserved",db.sql("select coalesce((select compatible from ouf_onboarding.consumer_compatibility_attestation where onboarding_version_id=:v and consumer='INGESTION_RUNTIME' and configuration_hash=:h order by created_at desc,attestation_id desc limit 1),false)")
        .param("v",current.get("onboarding_version_id")).param("h",current.get("configuration_hash")).query(Boolean.class).single());
    if(review!=null){
      var budget=budgetState((UUID)review.get("challenge_id"));boolean session=budget.get("readiness_session_id")!=null;
      var value=new LinkedHashMap<String,Object>();
      for(String key:List.of("readiness_started_at","readiness_attempts","readiness_generation","readiness_phase",
          "readiness_lease_until","readiness_next_at","readiness_last_code"))value.put(key,budget.get(key));
      value.put("profile",session?"managed-review-recovery.v1":"managed-review-activation.v1");
      value.put("maximumAttempts",session?budget.get("session_maximum_attempts"):128);
      value.put("maximumElapsedSeconds",session?budget.get("session_maximum_seconds"):86400);
      value.put("cumulativeAttempts",budget.get("readiness_attempts"));
      value.put("sessionAttempts",((Number)budget.get("readiness_attempts")).longValue()
          -(session?((Number)budget.get("session_base_attempts")).longValue():0));
      value.put("sessionId",budget.get("readiness_session_id"));value.put("decisionRef",budget.get("decision_ref"));
      value.put("budgetExpiresAt",budget.get("budget_deadline"));
      value.put("budgetExhausted",Boolean.TRUE.equals(budget.get("budget_exhausted")));
      value.put("counterLineageVerified",Boolean.TRUE.equals(budget.get("budget_lineage_valid")));
      value.put("humanRecheckAllowed","APPROVED".equals(current.get("state"))&&"CONFIRMED".equals(result.get("challengeStatus"))
          &&recheckableBlock(budget)&&Boolean.TRUE.equals(budget.get("budget_lineage_valid"))
          &&!Boolean.TRUE.equals(budget.get("budget_exhausted"))&&!Boolean.TRUE.equals(budget.get("delayed")));
      String reason=renewalCode(budget);
      boolean available="APPROVED".equals(current.get("state"))&&reason!=null;
      value.put("humanBudgetGrantAvailable",available);
      if(available)value.put("reconciledBudgetReason",reason);
      value.put("newBudgetProfile",Map.of("profile","managed-review-recovery.v1","maximumAttempts",3,"maximumElapsedSeconds",900));
      value.put("requiresAuthenticatedHumanResume",true);result.put("activationProgress",value);
    }
    return result;
  }
  private Map<String,Object> humanReview(PrincipalContext p,UUID challenge){
    var rows=db.sql("select * from ouf_onboarding.managed_onboarding_review where challenge_id=:c and tenant_id=:t")
        .param("c",challenge).param("t",p.tenantId()).query().listOfRows();
    if(rows.isEmpty())throw fail(HttpStatus.NOT_FOUND,"ONB_REVIEW_NOT_FOUND");
    var review=rows.getFirst();
    authorize(p,"ouf.onboarding.configuration.write","COMMAND",String.valueOf(review.get("source_id")),(UUID)review.get("onboarding_version_id"));
    return review;
  }
  @Transactional public Map<String,Object> humanCard(PrincipalContext p,UUID challenge){
    var review=humanReview(p,challenge);var card=new LinkedHashMap<>(onboarding.approvalCard(challenge));
    var version=onboarding.version(String.valueOf(review.get("source_id")),(UUID)review.get("onboarding_version_id"));
    card.put("onboardingState",version.get("state"));card.put("lockVersion",version.get("lock_version"));
    var progress=receipt(version,review);
    card.put("progress",progress);card.put("onboardingState",progress.get("state"));
    var snapshot=snapshot(version);
    if(!snapshot.isEmpty())card.put("reviewSnapshot",snapshot);
    if(version.get("parent_version_id") instanceof UUID parentId){
      var parent=onboarding.version((String)version.get("source_id"),parentId);
      // Compare only reviews in the same delegated ownership scope.
      boolean sameScope=db.sql("select exists(select 1 from ouf_onboarding.managed_onboarding_review where onboarding_version_id=:p and tenant_id=:t and subject_id=:s)")
          .param("p",parentId).param("t",review.get("tenant_id")).param("s",review.get("subject_id")).query(Boolean.class).single();
      if(sameScope)card.put("previousReview",Map.of("version",parent.get("version"),"feedback",feedback(parentId),
          "changes",ManagedReviewSnapshot.differences(snapshot(parent),snapshot)));
    }
    var revision=db.sql("""
        select r.parent_version_id,r.parent_configuration_hash,r.publication_effect,p.asset_id,r.replacement_profile_id,r.new_columns
        from ouf_onboarding.source_property_revision r left join ouf_onboarding.file_profile p on p.profile_id=r.replacement_profile_id
        where r.onboarding_version_id=:v
        """).param("v",version.get("onboarding_version_id")).query().listOfRows();
    if(!revision.isEmpty()){
      var base=new LinkedHashMap<>(revision.getFirst());
      Object profile=base.remove("replacement_profile_id"),asset=base.remove("asset_id");
      if(profile!=null){
        card.put("assetRef",Map.of("assetId",asset,"profileId",profile));
        card.put("newColumns",onboarding.newColumnDecisions(base));
      }
      base.remove("new_columns");
      card.put("revisionBase",base);card.put("publicationEffects",SourceRevisionService.effects());
    }
    return card;
  }
  /** Owner-local finite recovery sessions. Granting one never invokes activation. */
  public record BudgetDecision(String configurationHash,long expectedVersion,long expectedGeneration,
      String commandKey,String reason,int maximumAttempts,int maximumElapsedSeconds){}
  private static boolean transientReadinessCode(Object code){
    return code instanceof String value && value.matches("ONB_READINESS_(DEPENDENCY_(408|429|502|503|504|UNAVAILABLE)|CAPACITY_PAUSED|INTERRUPTED)");
  }
  private Map<String,Object> budgetState(UUID challenge){
    return db.sql("""
        select r.*, r.readiness_lease_until>clock_timestamp() leased,
          r.readiness_next_at>clock_timestamp() delayed,
          exists(select 1 from ouf_onboarding.published_configuration pc
            where pc.onboarding_version_id=r.onboarding_version_id) has_publication,
          exists(select 1 from ouf_onboarding.approval_decision d join ouf_onboarding.approval_challenge c
            on c.challenge_id=d.challenge_id where c.challenge_id=r.challenge_id and c.status='CONFIRMED'
            and c.source_id=r.source_id and c.onboarding_version_id=r.onboarding_version_id
            and d.onboarding_version_id=r.onboarding_version_id and d.decision='APPROVE'
            and d.target_hash=r.configuration_hash and c.configuration_hash=r.configuration_hash) confirmed_approval,
          b.base_attempts session_base_attempts,b.maximum_attempts session_maximum_attempts,
          b.maximum_elapsed_seconds session_maximum_seconds,b.issued_at session_issued_at,
          b.expires_at session_expires_at,b.state session_state,b.decision_ref,
          ((r.readiness_session_id is null and r.readiness_attempts between 0 and 128) or (b.session_id is not null
            and r.readiness_attempts>=b.base_attempts and b.configuration_hash=r.configuration_hash)) budget_lineage_valid,
          case when r.readiness_session_id is null
            then r.readiness_started_at+interval '24 hours'
            else b.expires_at end budget_deadline,
          case when r.readiness_session_id is null then
            r.readiness_attempts>=128 or coalesce(r.readiness_started_at+interval '24 hours'<=clock_timestamp()+interval '50 seconds',false)
            else b.session_id is null or b.state<>'ACTIVE' or b.configuration_hash<>r.configuration_hash
              or r.readiness_attempts-b.base_attempts>=b.maximum_attempts
              or b.expires_at<=clock_timestamp()+interval '50 seconds' end budget_exhausted
        from ouf_onboarding.managed_onboarding_review r
        left join ouf_onboarding.managed_readiness_budget_session b on b.session_id=r.readiness_session_id
        where r.challenge_id=:c
        """).param("c",challenge).query().singleRow();
  }
  public static boolean humanRecheckCode(String code){
    // Pre-V42 the final UDP read conflated transport failures and invalid evidence.
    return "ONB_UDP_IDENTITY_PREFLIGHT_REQUIRED".equals(code)
        ||code!=null&&code.matches("ONB_READINESS_DEPENDENCY_4[0-9]{2}")&&!transientReadinessCode(code);
  }
  private static boolean recheckableBlock(Map<String,Object> state){
    return "BLOCKED".equals(state.get("readiness_phase"))&&!Boolean.TRUE.equals(state.get("leased"))
        &&state.get("readiness_started_at")!=null&&((Number)state.get("readiness_attempts")).intValue()>0
        &&((Number)state.get("readiness_generation")).longValue()>0
        &&humanRecheckCode((String)state.get("readiness_last_code"));
  }
  private static String renewalCode(Map<String,Object> state){
    if(!Boolean.TRUE.equals(state.get("budget_lineage_valid"))||!Boolean.TRUE.equals(state.get("budget_exhausted"))
        ||(state.get("readiness_session_id")!=null&&!"ACTIVE".equals(state.get("session_state"))&&!"EXHAUSTED".equals(state.get("session_state")))
        ||Boolean.TRUE.equals(state.get("leased"))||Boolean.TRUE.equals(state.get("delayed"))
        ||!Boolean.TRUE.equals(state.get("confirmed_approval"))||Boolean.TRUE.equals(state.get("has_publication"))
        ||state.get("readiness_started_at")==null||((Number)state.get("readiness_attempts")).longValue()<1
        ||((Number)state.get("readiness_generation")).longValue()<1)return null;
    String phase=(String)state.get("readiness_phase"),code=(String)state.get("readiness_last_code");
    if(Set.of("PAUSED","EXHAUSTED").contains(phase)&&transientReadinessCode(code))return code;
    if(Set.of("BLOCKED","PAUSED","EXHAUSTED").contains(phase)&&humanRecheckCode(code))return code;
    // Publication, projections and ACTIVE commit together. Compatibility is read back by frozen hash
    // before a new probe. An expired in-flight claim can therefore be fenced without replaying it.
    if(code==null&&("EXHAUSTED".equals(phase)||(state.get("readiness_lease_until")!=null
        &&Set.of("CHECKING_COMPATIBILITY","CHECKING_IDENTITY","ACTIVATING").contains(phase))))
      return "ONB_READINESS_INTERRUPTED";
    return null;
  }
  @Transactional public Map<String,Object> grantReadinessBudget(PrincipalContext p,UUID challenge,BudgetDecision decision){
    var review=humanReview(p,challenge);
    if(decision==null||decision.commandKey()==null||!decision.commandKey().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        ||decision.reason()==null||decision.reason().strip().length()<10||decision.reason().length()>500
        ||decision.reason().chars().anyMatch(Character::isISOControl)
        ||decision.maximumAttempts()!=3||decision.maximumElapsedSeconds()!=900
        ||decision.expectedGeneration()<0||decision.expectedGeneration()>9007199254740991L
        ||decision.expectedVersion()<0||decision.expectedVersion()>9007199254740991L)
      throw fail(HttpStatus.BAD_REQUEST,"ONB_READINESS_BUDGET_DECISION_INVALID");
    db.sql("select challenge_id from ouf_onboarding.managed_onboarding_review where challenge_id=:c for update")
        .param("c",challenge).query(UUID.class).single();
    String source=String.valueOf(review.get("source_id"));UUID version=(UUID)review.get("onboarding_version_id");
    db.sql("select 1 from (select pg_advisory_xact_lock(hashtextextended(:s,0))) locked")
        .param("s",source).query(Integer.class).single();
    db.sql("select onboarding_version_id from ouf_onboarding.onboarding_version where onboarding_version_id=:v for update")
        .param("v",version).query(UUID.class).single();
    var current=ownedVersion(p,source,version);
    requireCandidateOwner(p,source,version);
    String hash=String.valueOf(current.get("configuration_hash"));
    if(!Objects.equals(decision.configurationHash(),hash)||!Objects.equals(review.get("configuration_hash"),hash))
      throw fail(HttpStatus.PRECONDITION_FAILED,"ONB_REVIEW_STALE_CARD");
    String fingerprint=hashes.of(decision);
    var retained=db.sql("select * from ouf_onboarding.managed_readiness_budget_session where tenant_id=:t and subject_id=:s and command_key=:k")
        .param("t",p.tenantId()).param("s",p.subjectId()).param("k",decision.commandKey()).query().listOfRows();
    if(!retained.isEmpty()){
      var row=retained.getFirst();
      if(!row.get("challenge_id").equals(challenge)||!row.get("request_hash").equals(fingerprint))
        throw fail(HttpStatus.CONFLICT,"ONB_READINESS_BUDGET_COMMAND_CONFLICT");
      var result=new LinkedHashMap<>(receipt(current,review));
      result.put("reconciledBudgetSessionId",row.get("session_id"));return result;
    }
    var state=budgetState(challenge);
    if(!"APPROVED".equals(current.get("state"))||!"CONFIRMED".equals(onboarding.approvalCard(challenge).get("status")))
      throw fail(HttpStatus.CONFLICT,"ONB_REVIEW_APPROVAL_REQUIRED");
    if(((Number)current.get("lock_version")).longValue()!=decision.expectedVersion()
        ||((Number)state.get("readiness_generation")).longValue()!=decision.expectedGeneration())
      throw fail(HttpStatus.PRECONDITION_FAILED,"ONB_REVIEW_STALE_CARD");
    String reasonCode=renewalCode(state);
    if(reasonCode==null||!hash.equals(hashes.of(current.get("configuration")))
        ||((Number)state.get("readiness_attempts")).longValue()>Integer.MAX_VALUE-3)
      throw fail(HttpStatus.CONFLICT,"ONB_READINESS_BUDGET_RECONCILIATION_REQUIRED");
    // The old session, legacy start, attempts, last code and backoff are never reset.
    db.sql("update ouf_onboarding.managed_readiness_budget_session set state='EXHAUSTED' where challenge_id=:c and state='ACTIVE'")
        .param("c",challenge).update();
    UUID session=UUID.randomUUID(),decisionRef=UUID.randomUUID();
    db.sql("""
        insert into ouf_onboarding.managed_readiness_budget_session
        (session_id,challenge_id,tenant_id,subject_id,command_key,request_hash,configuration_hash,decision_ref,
         reason,profile,base_attempts,prior_generation,prior_started_at,prior_phase,prior_last_code,prior_session_id,
         prior_last_code_absent,maximum_attempts,maximum_elapsed_seconds,issued_at,expires_at,state)
        select :session,r.challenge_id,:tenant,:subject,:key,:fingerprint,r.configuration_hash,:decision,
          :reason,'managed-review-recovery.v1',r.readiness_attempts,r.readiness_generation,r.readiness_started_at,
          r.readiness_phase,:priorCode,r.readiness_session_id,r.readiness_last_code is null,3,900,t.now,t.now+interval '900 seconds','ACTIVE'
        from ouf_onboarding.managed_onboarding_review r cross join (select clock_timestamp() now) t where r.challenge_id=:c
        """).param("session",session).param("tenant",p.tenantId()).param("subject",p.subjectId())
        .param("key",decision.commandKey()).param("fingerprint",fingerprint).param("decision",decisionRef)
        .param("reason",decision.reason().strip()).param("priorCode",reasonCode).param("c",challenge).update();
    // Fence every earlier worker; granting this session does not reserve an attempt.
    db.sql("update ouf_onboarding.managed_onboarding_review set readiness_session_id=:session,readiness_generation=readiness_generation+1,readiness_phase='PAUSED',readiness_lease_until=null,readiness_last_code=:code where challenge_id=:c")
        .param("session",session).param("code",reasonCode).param("c",challenge).update();
    return receipt(current,review);
  }
  public record ActivationClaim(UUID challenge,UUID version,String source,String hash,long lockVersion,long generation,
      Map<String,Object> configuration,Map<String,Object> progress,boolean reconciled,boolean recheck){}
  @Transactional(noRollbackFor=DomainFailure.class)
  @SuppressWarnings("unchecked") public ActivationClaim claimActivation(PrincipalContext p,UUID challenge,Decision decision){
    var review=humanReview(p,challenge);
    db.sql("select challenge_id from ouf_onboarding.managed_onboarding_review where challenge_id=:c for update").param("c",challenge).query(UUID.class).single();
    String source=String.valueOf(review.get("source_id"));UUID version=(UUID)review.get("onboarding_version_id");
    var current=onboarding.version(source,version);String hash=String.valueOf(current.get("configuration_hash"));
    if(decision==null||!Objects.equals(decision.configurationHash(),hash)||!Objects.equals(review.get("configuration_hash"),hash))throw fail(HttpStatus.PRECONDITION_FAILED,"ONB_REVIEW_STALE_CARD");
    if("ACTIVE".equals(current.get("state")))return new ActivationClaim(challenge,version,source,hash,((Number)current.get("lock_version")).longValue(),0,Map.of(),receipt(current,review),true,false);
    if(!"APPROVED".equals(current.get("state"))||!"CONFIRMED".equals(onboarding.approvalCard(challenge).get("status")))throw fail(HttpStatus.CONFLICT,"ONB_REVIEW_APPROVAL_REQUIRED");
    if(((Number)current.get("lock_version")).longValue()!=decision.expectedVersion())throw fail(HttpStatus.PRECONDITION_FAILED,"ONB_REVIEW_STALE_CARD");
    var state=budgetState(challenge);
    if(!Boolean.TRUE.equals(state.get("budget_lineage_valid")))throw fail(HttpStatus.CONFLICT,"ONB_READINESS_COUNTER_LINEAGE_UNPROVEN");
    if(Boolean.TRUE.equals(state.get("leased")))throw fail(HttpStatus.CONFLICT,"ONB_READINESS_IN_PROGRESS");
    if("BLOCKED".equals(state.get("readiness_phase")))throw fail(HttpStatus.CONFLICT,"ONB_READINESS_BLOCKED");
    if(Boolean.TRUE.equals(state.get("budget_exhausted"))){
      db.sql("update ouf_onboarding.managed_onboarding_review set readiness_phase='EXHAUSTED',readiness_lease_until=null where challenge_id=:c").param("c",challenge).update();
      db.sql("update ouf_onboarding.managed_readiness_budget_session set state='EXHAUSTED' where challenge_id=:c and state='ACTIVE'").param("c",challenge).update();
      throw fail(HttpStatus.CONFLICT,"ONB_READINESS_BUDGET_EXHAUSTED");
    }
    if(Boolean.TRUE.equals(state.get("delayed")))throw fail(HttpStatus.TOO_MANY_REQUESTS,"ONB_READINESS_BACKOFF");
    boolean recheck="PAUSED".equals(state.get("readiness_phase"))&&humanRecheckCode((String)state.get("readiness_last_code"));
    long generation=db.sql("update ouf_onboarding.managed_onboarding_review set readiness_started_at=coalesce(readiness_started_at,clock_timestamp()),readiness_attempts=readiness_attempts+1,readiness_generation=readiness_generation+1,readiness_phase='CHECKING_COMPATIBILITY',readiness_lease_until=least(clock_timestamp()+interval '60 seconds',cast(:deadline as timestamptz)),readiness_next_at=null,readiness_last_code=null where challenge_id=:c returning readiness_generation").param("c",challenge).param("deadline",state.get("budget_deadline")).query(Long.class).single();
    return new ActivationClaim(challenge,version,source,hash,decision.expectedVersion(),generation,(Map<String,Object>)current.get("configuration"),receipt(current,review),false,recheck);
  }
  @Transactional public void activationPhase(ActivationClaim claim,String phase){
    if(!Set.of("CHECKING_IDENTITY","ACTIVATING").contains(phase))throw fail(HttpStatus.BAD_REQUEST,"ONB_READINESS_PHASE_INVALID");
    if(db.sql("update ouf_onboarding.managed_onboarding_review set readiness_phase=:p where challenge_id=:c and readiness_generation=:g and readiness_lease_until>clock_timestamp()").param("p",phase).param("c",claim.challenge()).param("g",claim.generation()).update()!=1)
      throw fail(HttpStatus.CONFLICT,"ONB_READINESS_LEASE_LOST");
  }
  @Transactional public Map<String,Object> finishActivation(PrincipalContext p,ActivationClaim claim,String authentication){
    var state=db.sql("select readiness_generation,readiness_lease_until>clock_timestamp() valid from ouf_onboarding.managed_onboarding_review where challenge_id=:c for update").param("c",claim.challenge()).query().singleRow();
    if(((Number)state.get("readiness_generation")).longValue()!=claim.generation()||!Boolean.TRUE.equals(state.get("valid")))throw fail(HttpStatus.CONFLICT,"ONB_READINESS_LEASE_LOST");
    humanAction(p,claim.challenge(),"activate",new Decision(claim.hash(),claim.lockVersion()),authentication);
    if(db.sql("update ouf_onboarding.managed_onboarding_review set readiness_phase='ACTIVE',readiness_lease_until=null,readiness_next_at=null,readiness_last_code=null where challenge_id=:c and readiness_generation=:g and readiness_lease_until>clock_timestamp()").param("c",claim.challenge()).param("g",claim.generation()).update()!=1)
      throw fail(HttpStatus.CONFLICT,"ONB_READINESS_LEASE_LOST");
    db.sql("update ouf_onboarding.managed_readiness_budget_session set state='COMPLETED' where challenge_id=:c and state='ACTIVE'")
        .param("c",claim.challenge()).update();
    return receipt(onboarding.version(claim.source(),claim.version()),humanReview(p,claim.challenge()));
  }
  @Transactional public void activationFailure(ActivationClaim claim,String code,boolean transientFailure){activationFailure(claim,code,transientFailure,0);}
  @Transactional public void activationFailure(ActivationClaim claim,String code,boolean transientFailure,int retryAfterSeconds){
    String safe=code!=null&&code.matches("[A-Z0-9_]{1,100}")?code:"ONB_READINESS_FAILED";
    var counts=db.sql("select readiness_attempts from ouf_onboarding.managed_onboarding_review where challenge_id=:c and readiness_generation=:g").param("c",claim.challenge()).param("g",claim.generation()).query(Integer.class).optional();
    if(counts.isEmpty())return;
    int base=switch(counts.get()){case 1->5;case 2->15;case 3->60;case 4->300;default->900;};
    int delay=Math.max(retryAfterSeconds,java.util.concurrent.ThreadLocalRandom.current().nextInt(Math.max(1,base*4/5),base*6/5+1));
    db.sql("update ouf_onboarding.managed_onboarding_review set readiness_phase=:p,readiness_lease_until=null,readiness_next_at=case when :retry then clock_timestamp()+make_interval(secs=>:delay) else null end,readiness_last_code=:code where challenge_id=:c and readiness_generation=:g and readiness_lease_until>clock_timestamp()")
        .param("p",transientFailure?"PAUSED":"BLOCKED").param("retry",transientFailure).param("delay",delay).param("code",safe).param("c",claim.challenge()).param("g",claim.generation()).update();
  }

  /** Dedicated workload read: never exposes the HUMAN lifecycle wildcard to SERVICE. */
  // Authorization is audited in the same transaction; domain configuration remains unchanged.
  @Transactional
  public Map<String,Object> ingestionConfiguration(PrincipalContext p,String source,UUID version,String hash){
    identity(source,version);
    String capability="ouf.onboarding.configuration.read";
    if(p==null||p.actorType()!=PrincipalContext.ActorType.SERVICE||!p.scopes().contains(capability))
      throw fail(HttpStatus.FORBIDDEN,"ONB_INGESTION_CONFIGURATION_AUTHORITY_REQUIRED");
    var resource=new ResourceContext("published-configuration",source,p.tenantId(),null,Map.of("module","ONBOARDING","onboardingVersionId",version.toString()));
    if(!policy.authorizeActive(p,resource,capability,"READ",Instant.now()).allowed())
      throw fail(HttpStatus.FORBIDDEN,"ONB_INGESTION_CONFIGURATION_DENIED");
    var rows=db.sql("select tenant_id,challenge_id,configuration_hash from ouf_onboarding.managed_onboarding_review where source_id=:s and onboarding_version_id=:v")
        .param("s",source).param("v",version).query().listOfRows();
    if(rows.size()!=1||!Objects.equals(rows.getFirst().get("tenant_id"),p.tenantId()))
      throw fail(HttpStatus.FORBIDDEN,"ONB_INGESTION_CONFIGURATION_DENIED");
    var current=onboarding.version(source,version);var review=rows.getFirst();
    if(hash==null||!hash.matches("sha256:[a-f0-9]{64}")||!Objects.equals(hash,current.get("configuration_hash"))
        ||!Objects.equals(hash,review.get("configuration_hash"))||!"APPROVED".equals(current.get("state"))
        ||!"CONFIRMED".equals(onboarding.approvalCard((UUID)review.get("challenge_id")).get("status")))
      throw fail(HttpStatus.PRECONDITION_FAILED,"ONB_INGESTION_CONFIGURATION_DRIFT");
    return current;
  }
  /** Exact frozen managed-source read; Authorization evaluates the same owner resource as configuration GET. */
  @Transactional public Map<String,Object> ingestionCompatibilityRead(PrincipalContext p,String source,UUID version,String hash){
    var actor=compatibilityActor(p,source,version,hash,"ouf.onboarding.configuration.read","READ");
    return onboarding.ingestionCompatibility(source,version,hash,actor);
  }

  /** Authorization audit and deduplicated attestation commit in the same owner transaction. */
  @Transactional public Map<String,Object> ingestionCompatibilityAttest(PrincipalContext p,String source,UUID version,String hash,boolean compatible,String detail,String correlation){
    var actor=compatibilityActor(p,source,version,hash,"ouf.ingestion.configuration.attest","COMMAND");
    return onboarding.attestIngestionCompatibilityBound(source,version,hash,compatible,detail,actor,correlation);
  }

  private OnboardingService.Actor compatibilityActor(PrincipalContext p,String source,UUID version,String hash,String capability,String operation){
    identity(source,version);
    if(p==null||p.actorType()!=PrincipalContext.ActorType.SERVICE||!p.scopes().contains(capability))
      throw fail(HttpStatus.FORBIDDEN,"ONB_COMPATIBILITY_AUTHORITY_REQUIRED");
    var rows=db.sql("select tenant_id,configuration_hash from ouf_onboarding.managed_onboarding_review where source_id=:s and onboarding_version_id=:v")
        .param("s",source).param("v",version).query().listOfRows();
    if(rows.size()!=1||!Objects.equals(rows.getFirst().get("tenant_id"),p.tenantId()))
      throw fail(HttpStatus.FORBIDDEN,"ONB_COMPATIBILITY_AUTHORIZATION_DENIED");
    var current=onboarding.version(source,version);
    if(hash==null||!hash.matches("sha256:[a-f0-9]{64}")||!Objects.equals(hash,current.get("configuration_hash"))
        ||!Objects.equals(hash,rows.getFirst().get("configuration_hash"))
        ||!Set.of("IN_REVIEW","APPROVED","ACTIVE","SUPERSEDED").contains(String.valueOf(current.get("state"))))
      throw fail(HttpStatus.PRECONDITION_FAILED,"ONB_COMPATIBILITY_CONFIGURATION_DRIFT");
    var resource=new ResourceContext("published-configuration",source,p.tenantId(),null,Map.of("module","ONBOARDING","onboardingVersionId",version.toString()));
    if(!policy.authorizeActive(p,resource,capability,operation,Instant.now()).allowed())
      throw fail(HttpStatus.FORBIDDEN,"ONB_COMPATIBILITY_AUTHORIZATION_DENIED");
    return new OnboardingService.Actor(p.subjectId(),"SERVICE",Set.of(capability));
  }

  public record Resume(String configurationHash,long expectedVersion,long expectedGeneration,String blockedCode){}
  /** Explicit HUMAN reconciliation only. Retains cumulative attempts and the current finite budget. */
  @Transactional public Map<String,Object> resumeActivation(PrincipalContext p,UUID challenge,Resume request,String authentication){
    var review=humanReview(p,challenge);
    db.sql("select challenge_id from ouf_onboarding.managed_onboarding_review where challenge_id=:c for update")
        .param("c",challenge).query(UUID.class).single();
    var state=budgetState(challenge);
    String source=String.valueOf(review.get("source_id"));UUID version=(UUID)review.get("onboarding_version_id");
    db.sql("select onboarding_version_id from ouf_onboarding.onboarding_version where onboarding_version_id=:v for update").param("v",version).query(UUID.class).single();
    var current=onboarding.version(source,version);
    if(request==null||!Objects.equals(request.configurationHash(),current.get("configuration_hash"))
        ||!Objects.equals(request.configurationHash(),review.get("configuration_hash"))
        ||request.expectedVersion()!=((Number)current.get("lock_version")).longValue()
        ||request.expectedGeneration()!=((Number)state.get("readiness_generation")).longValue()
        ||!Objects.equals(request.blockedCode(),state.get("readiness_last_code")))
      throw fail(HttpStatus.PRECONDITION_FAILED,"ONB_REVIEW_STALE_CARD");
    if(!"APPROVED".equals(current.get("state"))||!"CONFIRMED".equals(onboarding.approvalCard(challenge).get("status")))
      throw fail(HttpStatus.CONFLICT,"ONB_REVIEW_APPROVAL_REQUIRED");
    if(!recheckableBlock(state))
      throw fail(HttpStatus.CONFLICT,"ONB_READINESS_RESUME_NOT_ALLOWED");
    if(!Boolean.TRUE.equals(state.get("budget_lineage_valid")))
      throw fail(HttpStatus.CONFLICT,"ONB_READINESS_COUNTER_LINEAGE_UNPROVEN");
    if(Boolean.TRUE.equals(state.get("budget_exhausted")))
      throw fail(HttpStatus.CONFLICT,"ONB_READINESS_BUDGET_EXHAUSTED");
    if(Boolean.TRUE.equals(state.get("delayed")))throw fail(HttpStatus.TOO_MANY_REQUESTS,"ONB_READINESS_BACKOFF");
    if(authentication==null||authentication.isBlank())throw fail(HttpStatus.FORBIDDEN,"ONB_REVIEW_AUTHENTICATION_REQUIRED");
    db.sql("update ouf_onboarding.managed_onboarding_review set readiness_generation=readiness_generation+1,readiness_phase='PAUSED',readiness_lease_until=null,readiness_next_at=null where challenge_id=:c")
        .param("c",challenge).update();
    onboarding.auditReadinessResume(source,version,new OnboardingService.Actor(p.subjectId(),"HUMAN_USER",Set.of("ouf.onboarding.configuration.write")),
        Map.of("challengeId",challenge.toString(),"previousGeneration",request.expectedGeneration(),"previousCode",request.blockedCode(),
            "attemptsPreserved",state.get("readiness_attempts"),"authenticationContextRef",authentication));
    return receipt(current,review);
  }

  public record Feedback(String section,String target,String comment){}
  public record Decision(String configurationHash,long expectedVersion,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
      List<Feedback> reviewFeedback){
    public Decision(String hash,long version){this(hash,version,null);}
  }
  private String validateFeedback(Map<String,Object> version,List<Feedback> feedback){
    if(feedback==null||feedback.isEmpty()||feedback.size()>128)throw fail(HttpStatus.BAD_REQUEST,"ONB_REVIEW_FEEDBACK_INVALID");
    var snapshot=snapshot(version);var seen=new HashSet<List<String>>();
    for(var note:feedback){
      if(note==null||note.section()==null||!Set.of("FIELD","IDENTITY","SEMANTIC","LIMITS").contains(note.section())
          ||note.target()==null||!ManagedReviewSnapshot.child(snapshot,note.section()).containsKey(note.target())
          ||!seen.add(List.of(note.section(),note.target()))||note.comment()!=null&&(note.comment().length()>500
          ||note.comment().chars().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t')))
        throw fail(HttpStatus.BAD_REQUEST,"ONB_REVIEW_FEEDBACK_INVALID");
    }
    String value=onboarding.write(feedback);
    if(value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>48*1024)throw fail(HttpStatus.BAD_REQUEST,"ONB_REVIEW_FEEDBACK_TOO_LARGE");
    return value;
  }
  @Transactional public Map<String,Object> humanAction(PrincipalContext p,UUID challenge,String action,Decision decision,String authentication){
    if(!Set.of("confirm","reject","changes","activate").contains(action)||decision==null
        ||(!"changes".equals(action)&&decision.reviewFeedback()!=null))
      throw fail(HttpStatus.BAD_REQUEST,"ONB_REVIEW_ACTION_INVALID");
    var review=humanReview(p,challenge);String source=String.valueOf(review.get("source_id"));UUID id=(UUID)review.get("onboarding_version_id");
    db.sql("select onboarding_version_id from ouf_onboarding.onboarding_version where onboarding_version_id=:v for update").param("v",id).query(UUID.class).single();
    var current=onboarding.version(source,id);
    if(!Objects.equals(decision.configurationHash(),current.get("configuration_hash"))
        ||!Objects.equals(review.get("configuration_hash"),current.get("configuration_hash"))
        ||((Number)current.get("lock_version")).longValue()!=decision.expectedVersion())
      throw fail(HttpStatus.PRECONDITION_FAILED,"ONB_REVIEW_STALE_CARD");
    var actor=new OnboardingService.Actor(p.subjectId(),"HUMAN_USER",Set.of("ouf.onboarding.configuration.write"));
    String correlation=UUID.randomUUID().toString();
    if("confirm".equals(action))onboarding.confirm(source,id,challenge,actor,correlation,authentication);
    else if("changes".equals(action))onboarding.closeWithoutApproval(source,id,challenge,actor,correlation,authentication,validateFeedback(current,decision.reviewFeedback()));
    else if("reject".equals(action))onboarding.reject(source,id,challenge,actor,correlation,authentication);
    else {
      if(!"CONFIRMED".equals(onboarding.approvalCard(challenge).get("status")))
        throw fail(HttpStatus.CONFLICT,"ONB_REVIEW_APPROVAL_REQUIRED");
      onboarding.activate(source,id,actor,correlation);
    }
    return receipt(onboarding.version(source,id),review);
  }
}

