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
      String commandKey,ManagedFileExecutionProposal.Proposal executionProposal) {}
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
    // Lock one logical source version, not the shared policy or a whole-tenant workflow.
    db.sql("select 1 from (select pg_advisory_xact_lock(hashtextextended(:v,0))) locked")
        .param("v",request.onboardingVersionId().toString()).query(Integer.class).single();
    var current=ownedVersion(p,request.sourceId(),request.onboardingVersionId());
    var retained=db.sql("select * from ouf_onboarding.managed_onboarding_review where onboarding_version_id=:v or (tenant_id=:t and subject_id=:s and command_key=:k)")
        .param("v",request.onboardingVersionId()).param("t",p.tenantId()).param("s",p.subjectId())
        .param("k",request.commandKey()).query().listOfRows();
    if(!retained.isEmpty()){
      if(retained.size()!=1||!retained.getFirst().get("request_hash").equals(fingerprint)
          ||!retained.getFirst().get("tenant_id").equals(p.tenantId())
          ||!retained.getFirst().get("subject_id").equals(p.subjectId()))
        throw fail(HttpStatus.CONFLICT,"ONB_REVIEW_COMMAND_CONFLICT");
      return receipt(current,retained.getFirst());
    }
    if(!"DRAFT".equals(current.get("state"))
        ||((Number)current.get("lock_version")).longValue()!=request.expectedVersion())
      throw fail(HttpStatus.PRECONDITION_FAILED,"ONB_REVIEW_STALE_VERSION");
    var actor=new OnboardingService.Actor(p.subjectId(),"HUMAN_USER",Set.of(PREPARE));
    long version=request.expectedVersion();
    if(request.executionProposal()!=null){
      @SuppressWarnings("unchecked") var configuration=(Map<String,Object>)current.get("configuration");
      @SuppressWarnings("unchecked") var metadata=(Map<String,Object>)onboarding.source(request.sourceId()).get("metadata");
      var asset=files.asset(UUID.fromString(String.valueOf(metadata.get("managedFileAssetId"))));
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
    return receipt(current,Map.of("challenge_id",challenge.get("challenge_id"),"configuration_hash",current.get("configuration_hash")));
  }
  @Transactional public Map<String,Object> read(PrincipalContext p,String source,UUID version){
    authorize(p,READ,"READ",source,version);
    var current=ownedVersion(p,source,version);
    var retained=db.sql("select * from ouf_onboarding.managed_onboarding_review where onboarding_version_id=:v")
        .param("v",version).query().listOfRows();
    if(!retained.isEmpty()&&(!Objects.equals(retained.getFirst().get("tenant_id"),p.tenantId())||!Objects.equals(retained.getFirst().get("subject_id"),p.subjectId())))throw fail(HttpStatus.FORBIDDEN,"ONB_REVIEW_AUTHORIZATION_DENIED");
    return receipt(current,retained.isEmpty()?null:retained.getFirst());
  }
  private Map<String,Object> receipt(Map<String,Object> current,Map<String,Object> review){
    var result=new LinkedHashMap<String,Object>();
    result.put("sourceId",current.get("source_id"));result.put("onboardingVersionId",current.get("onboarding_version_id"));
    result.put("state",current.get("state"));result.put("lockVersion",current.get("lock_version"));
    result.put("configurationHash",current.get("configuration_hash"));
    result.put("humanApprovalRequired",!Set.of("APPROVED","ACTIVE","SUPERSEDED").contains(current.get("state")));
    if(review!=null){
      if(!Objects.equals(review.get("configuration_hash"),current.get("configuration_hash")))
        throw fail(HttpStatus.CONFLICT,"ONB_REVIEW_CONFIGURATION_DRIFT");
      UUID id=(UUID)review.get("challenge_id");var card=onboarding.approvalCard(id);
      result.put("challengeId",id);result.put("challengeStatus",card.get("status"));
      result.put("expiresAt",card.get("expires_at"));result.put("reviewUrl",url(id));
      result.put("challengeExpired",db.sql("select expires_at<=clock_timestamp() from ouf_onboarding.approval_challenge where challenge_id=:c").param("c",id).query(Boolean.class).single());
    }
    var publications=db.sql("select publication_id,checksum,active from ouf_onboarding.published_configuration where onboarding_version_id=:v order by created_at desc limit 1")
        .param("v",current.get("onboarding_version_id")).query().listOfRows();
    if(!publications.isEmpty())result.put("publication",publications.getFirst());
    result.put("ingestionCompatibilityObserved",db.sql("select coalesce((select compatible from ouf_onboarding.consumer_compatibility_attestation where onboarding_version_id=:v and consumer='INGESTION_RUNTIME' and configuration_hash=:h order by created_at desc,attestation_id desc limit 1),false)")
        .param("v",current.get("onboarding_version_id")).param("h",current.get("configuration_hash")).query(Boolean.class).single());
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
    card.put("progress",receipt(version,review));return card;
  }
  public record Decision(String configurationHash,long expectedVersion){}
  @Transactional public Map<String,Object> humanAction(PrincipalContext p,UUID challenge,String action,Decision decision,String authentication){
    if(!Set.of("confirm","reject","activate").contains(action)||decision==null)
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
    else if("reject".equals(action))onboarding.reject(source,id,challenge,actor,correlation,authentication);
    else {
      if(!"CONFIRMED".equals(onboarding.approvalCard(challenge).get("status")))
        throw fail(HttpStatus.CONFLICT,"ONB_REVIEW_APPROVAL_REQUIRED");
      onboarding.activate(source,id,actor,correlation);
    }
    return receipt(onboarding.version(source,id),review);
  }
}
