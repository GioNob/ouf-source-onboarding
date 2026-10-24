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
    var progress=db.sql("select readiness_started_at,readiness_attempts,readiness_generation,readiness_phase,readiness_lease_until,readiness_next_at,readiness_last_code from ouf_onboarding.managed_onboarding_review where onboarding_version_id=:v")
        .param("v",current.get("onboarding_version_id")).query().listOfRows();
    if(!progress.isEmpty()){
      var value=new LinkedHashMap<String,Object>(progress.getFirst());
      var budget=budgetState((UUID)review.get("challenge_id"));boolean session=budget.get("readiness_session_id")!=null;
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
      value.put("humanBudgetGrantAvailable","APPROVED".equals(current.get("state"))&&renewalAvailable(budget));
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
    card.put("progress",receipt(version,review));return card;
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
  private static boolean renewalAvailable(Map<String,Object> state){
    return Boolean.TRUE.equals(state.get("budget_lineage_valid")) && Boolean.TRUE.equals(state.get("budget_exhausted"))
        && !Boolean.TRUE.equals(state.get("leased")) && !Boolean.TRUE.equals(state.get("delayed"))
        && Set.of("PAUSED","EXHAUSTED").contains(state.get("readiness_phase"))
        && transientReadinessCode(state.get("readiness_last_code"));
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
    db.sql("select onboarding_version_id from ouf_onboarding.onboarding_version where onboarding_version_id=:v for update")
        .param("v",version).query(UUID.class).single();
    var current=ownedVersion(p,source,version);
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
    if(!renewalAvailable(state)||((Number)state.get("readiness_attempts")).longValue()>Integer.MAX_VALUE-3
        ||db.sql("select exists(select 1 from ouf_onboarding.published_configuration where onboarding_version_id=:v)")
            .param("v",version).query(Boolean.class).single())
      throw fail(HttpStatus.CONFLICT,"ONB_READINESS_BUDGET_RECONCILIATION_REQUIRED");
    // The old session, legacy start, attempts, last code and backoff are never reset.
    db.sql("update ouf_onboarding.managed_readiness_budget_session set state='EXHAUSTED' where challenge_id=:c and state='ACTIVE'")
        .param("c",challenge).update();
    UUID session=UUID.randomUUID(),decisionRef=UUID.randomUUID();
    db.sql("""
        insert into ouf_onboarding.managed_readiness_budget_session
        (session_id,challenge_id,tenant_id,subject_id,command_key,request_hash,configuration_hash,decision_ref,
         reason,profile,base_attempts,prior_generation,prior_started_at,prior_phase,prior_last_code,prior_session_id,
         maximum_attempts,maximum_elapsed_seconds,issued_at,expires_at,state)
        select :session,r.challenge_id,:tenant,:subject,:key,:fingerprint,r.configuration_hash,:decision,
          :reason,'managed-review-recovery.v1',r.readiness_attempts,r.readiness_generation,r.readiness_started_at,
          r.readiness_phase,r.readiness_last_code,r.readiness_session_id,3,900,t.now,t.now+interval '900 seconds','ACTIVE'
        from ouf_onboarding.managed_onboarding_review r cross join (select clock_timestamp() now) t where r.challenge_id=:c
        """).param("session",session).param("tenant",p.tenantId()).param("subject",p.subjectId())
        .param("key",decision.commandKey()).param("fingerprint",fingerprint).param("decision",decisionRef)
        .param("reason",decision.reason().strip()).param("c",challenge).update();
    // Fence every earlier worker; granting this session does not reserve an attempt.
    db.sql("update ouf_onboarding.managed_onboarding_review set readiness_session_id=:session,readiness_generation=readiness_generation+1,readiness_phase='PAUSED',readiness_lease_until=null where challenge_id=:c")
        .param("session",session).param("c",challenge).update();
    return receipt(current,review);
  }
  public record ActivationClaim(UUID challenge,UUID version,String source,String hash,long lockVersion,long generation,
      Map<String,Object> configuration,Map<String,Object> progress,boolean reconciled){}
  @Transactional(noRollbackFor=DomainFailure.class)
  @SuppressWarnings("unchecked") public ActivationClaim claimActivation(PrincipalContext p,UUID challenge,Decision decision){
    var review=humanReview(p,challenge);
    db.sql("select challenge_id from ouf_onboarding.managed_onboarding_review where challenge_id=:c for update").param("c",challenge).query(UUID.class).single();
    String source=String.valueOf(review.get("source_id"));UUID version=(UUID)review.get("onboarding_version_id");
    var current=onboarding.version(source,version);String hash=String.valueOf(current.get("configuration_hash"));
    if(decision==null||!Objects.equals(decision.configurationHash(),hash)||!Objects.equals(review.get("configuration_hash"),hash))throw fail(HttpStatus.PRECONDITION_FAILED,"ONB_REVIEW_STALE_CARD");
    if("ACTIVE".equals(current.get("state")))return new ActivationClaim(challenge,version,source,hash,((Number)current.get("lock_version")).longValue(),0,Map.of(),receipt(current,review),true);
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
    long generation=db.sql("update ouf_onboarding.managed_onboarding_review set readiness_started_at=coalesce(readiness_started_at,clock_timestamp()),readiness_attempts=readiness_attempts+1,readiness_generation=readiness_generation+1,readiness_phase='CHECKING_COMPATIBILITY',readiness_lease_until=least(clock_timestamp()+interval '60 seconds',cast(:deadline as timestamptz)),readiness_next_at=null,readiness_last_code=null where challenge_id=:c returning readiness_generation").param("c",challenge).param("deadline",state.get("budget_deadline")).query(Long.class).single();
    return new ActivationClaim(challenge,version,source,hash,decision.expectedVersion(),generation,(Map<String,Object>)current.get("configuration"),receipt(current,review),false);
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
    db.sql("update ouf_onboarding.managed_onboarding_review set readiness_phase=:p,readiness_lease_until=null,readiness_next_at=case when :retry then clock_timestamp()+make_interval(secs=>:delay) else null end,readiness_last_code=:code where challenge_id=:c and readiness_generation=:g and readiness_phase<>'ACTIVE'")
        .param("p",transientFailure?"PAUSED":"BLOCKED").param("retry",transientFailure).param("delay",delay).param("code",safe).param("c",claim.challenge()).param("g",claim.generation()).update();
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
