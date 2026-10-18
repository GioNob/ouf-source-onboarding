package it.comune.trieste.ouf.onboarding.application;

import it.comune.trieste.ouf.authorization.*;
import it.comune.trieste.ouf.onboarding.authorization.AuthorizationPolicyRegistry;
import it.comune.trieste.ouf.onboarding.domain.*;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Revises only explicitly selected property rules on the SAME source. Never publishes or acquires. */
@Service
public class SourceRevisionService {
  public static final String READ="ouf.onboarding.revision.read", PREPARE="ouf.onboarding.revision.prepare";
  public static final String EFFECT="NEW_PUBLICATION_MAY_START_ACQUISITION";
  private final JdbcClient db; private final OnboardingService onboarding;
  private final ManagedOnboardingReviewService reviews; private final ManagedFileService files;
  private final CanonicalHash hashes; private final AuthorizationPolicyRegistry policy;
  public record Change(String propertyIri,String sourceField,Map<String,Object> reconciliation){}
  public record Prepare(String sourceId,UUID parentVersionId,long expectedVersion,String configurationHash,
      String commandKey,String publicationEffect,List<Change> changes){}
  public SourceRevisionService(JdbcClient db,OnboardingService onboarding,ManagedOnboardingReviewService reviews,
      ManagedFileService files,CanonicalHash hashes,AuthorizationPolicyRegistry policy){
    this.db=db;this.onboarding=onboarding;this.reviews=reviews;this.files=files;this.hashes=hashes;this.policy=policy;
  }
  private static DomainFailure fail(HttpStatus status,String code){return new DomainFailure(status,code,code);}
  private void authorize(PrincipalContext p,String cap,String operation,String source,UUID version){
    if(source==null||!source.matches("[A-Za-z0-9_.:-]{1,160}")||version==null)
      throw fail(HttpStatus.BAD_REQUEST,"ONB_REVISION_IDENTITY_INVALID");
    if(p==null||p.actorType()!=PrincipalContext.ActorType.HUMAN||!p.scopes().contains(cap)
        ||!policy.authorizeActive(p,new ResourceContext("source",source,p.tenantId(),null,
            Map.of("onboardingVersionId",version.toString())),cap,operation,Instant.now()).allowed())
      throw fail(HttpStatus.FORBIDDEN,"ONB_REVISION_AUTHORIZATION_DENIED");
    var metadata=(Map<?,?>)onboarding.source(source).get("metadata");
    if(metadata.get("managedFileAssetId") instanceof String asset)files.requireOwner(UUID.fromString(asset),p.subjectId());
    var owners=db.sql("select tenant_id from ouf_onboarding.managed_onboarding_review where onboarding_version_id=:v")
        .param("v",version).query(String.class).list();
    if(!owners.isEmpty()&&!owners.getFirst().equals(p.tenantId()))throw fail(HttpStatus.FORBIDDEN,"ONB_REVISION_AUTHORIZATION_DENIED");
  }
  @Transactional public Map<String,Object> read(PrincipalContext p,String source,UUID version){
    authorize(p,READ,"READ",source,version);
    var current=onboarding.version(source,version);
    var retained=db.sql("select * from ouf_onboarding.source_property_revision where onboarding_version_id=:v")
        .param("v",version).query().listOfRows();
    if(!retained.isEmpty()){
      var row=retained.getFirst();
      if(!Objects.equals(row.get("tenant_id"),p.tenantId())||!Objects.equals(row.get("subject_id"),p.subjectId()))
        throw fail(HttpStatus.FORBIDDEN,"ONB_REVISION_AUTHORIZATION_DENIED");
      return receipt(current,row);
    }
    var out=new LinkedHashMap<String,Object>();
    out.put("sourceId",source);out.put("onboardingVersionId",version);out.put("state",current.get("state"));
    out.put("lockVersion",current.get("lock_version"));out.put("configurationHash",current.get("configuration_hash"));
    out.put("propertyRules",properties(configuration(current)));
    out.put("publicationEffects",effects());return out;
  }
  @Transactional public Map<String,Object> prepare(PrincipalContext p,Prepare r,String correlation){
    if(r==null)throw fail(HttpStatus.BAD_REQUEST,"ONB_REVISION_REQUEST_INVALID");
    authorize(p,PREPARE,"COMMAND",r.sourceId(),r.parentVersionId());
    if(r.commandKey()==null||!r.commandKey().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        ||r.expectedVersion()<0||r.expectedVersion()>9007199254740991L
        ||r.configurationHash()==null||!r.configurationHash().matches("sha256:[a-f0-9]{64}")
        ||!EFFECT.equals(r.publicationEffect())||r.changes()==null||r.changes().isEmpty()||r.changes().size()>256)
      throw fail(HttpStatus.BAD_REQUEST,"ONB_REVISION_REQUEST_INVALID");
    // One command across sources, followed by the same source fence used by clone/publication.
    lock("revision-command:"+hashes.of(List.of(p.tenantId(),p.subjectId(),r.commandKey())));
    String fingerprint=hashes.of(r);
    var retained=db.sql("select * from ouf_onboarding.source_property_revision where tenant_id=:t and subject_id=:s and command_key=:k")
        .param("t",p.tenantId()).param("s",p.subjectId()).param("k",r.commandKey()).query().listOfRows();
    if(!retained.isEmpty()){
      var row=retained.getFirst();if(!Objects.equals(row.get("request_hash"),fingerprint))throw fail(HttpStatus.CONFLICT,"ONB_REVISION_COMMAND_CONFLICT");
      return receipt(onboarding.version(r.sourceId(),(UUID)row.get("onboarding_version_id")),row);
    }
    lock(r.sourceId());
    db.sql("select source_id from ouf_onboarding.source where source_id=:s for update").param("s",r.sourceId()).query(String.class).single();
    var parent=onboarding.version(r.sourceId(),r.parentVersionId());
    if(!"ACTIVE".equals(parent.get("state"))||((Number)parent.get("lock_version")).longValue()!=r.expectedVersion()
        ||!r.configurationHash().equals(parent.get("configuration_hash"))
        ||!r.configurationHash().equals(hashes.of(parent.get("configuration"))))
      throw fail(HttpStatus.PRECONDITION_FAILED,"ONB_REVISION_PARENT_DRIFT");
    if(db.sql("select count(*) from ouf_onboarding.onboarding_version where source_id=:s and state in ('DRAFT','IN_REVIEW','APPROVED')")
        .param("s",r.sourceId()).query(Long.class).single()!=0)
      throw fail(HttpStatus.CONFLICT,"ONB_REVISION_OPEN_VERSION_EXISTS");
    var proposed=revise(configuration(parent),r.changes());
    var actor=new OnboardingService.Actor(p.subjectId(),"HUMAN_USER",Set.of(PREPARE));
    var draft=onboarding.cloneVersion(r.sourceId(),r.parentVersionId(),actor,correlation);
    UUID version=(UUID)draft.get("onboarding_version_id");
    revisionMetadata(proposed,((Number)draft.get("version")).intValue());
    draft=onboarding.patchVersion(r.sourceId(),version,((Number)draft.get("lock_version")).longValue(),proposed,actor,correlation);
    var frozen=onboarding.submit(r.sourceId(),version,((Number)draft.get("lock_version")).longValue(),actor,correlation);
    // Uses the existing native THS/compatibility/activation machinery, not a new approval authority.
    var review=reviews.registerReview(p,r.sourceId(),version,"revision:"+UUID.randomUUID(),fingerprint,frozen,actor,correlation);
    db.sql("insert into ouf_onboarding.source_property_revision(tenant_id,subject_id,command_key,request_hash,source_id,parent_version_id,parent_configuration_hash,onboarding_version_id,publication_effect) values(:t,:s,:k,:h,:source,:parent,:ph,:v,:effect)")
        .param("t",p.tenantId()).param("s",p.subjectId()).param("k",r.commandKey()).param("h",fingerprint)
        .param("source",r.sourceId()).param("parent",r.parentVersionId()).param("ph",r.configurationHash())
        .param("v",version).param("effect",r.publicationEffect()).update();
    return receipt(frozen,Map.of("parent_version_id",r.parentVersionId(),"parent_configuration_hash",r.configurationHash(),"onboarding_version_id",version));
  }
  /** Lost-response reconciliation is keyed by the original command, never by an unknown new version ID. */
  @Transactional public Map<String,Object> readCommand(PrincipalContext p,String source,UUID parent,String key){
    authorize(p,READ,"READ",source,parent);
    if(key==null||!key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))throw fail(HttpStatus.BAD_REQUEST,"ONB_REVISION_REQUEST_INVALID");
    var rows=db.sql("select * from ouf_onboarding.source_property_revision where tenant_id=:t and subject_id=:u and command_key=:k and source_id=:s and parent_version_id=:p")
        .param("t",p.tenantId()).param("u",p.subjectId()).param("k",key).param("s",source).param("p",parent).query().listOfRows();
    if(rows.isEmpty())return Map.of("sourceId",source,"parentVersionId",parent,"commandKey",key,"commandState","ABSENT","newCommandAuthorized",false);
    var row=rows.getFirst();return receipt(onboarding.version(source,(UUID)row.get("onboarding_version_id")),row);
  }
  private void lock(String value){db.sql("select 1 from (select pg_advisory_xact_lock(hashtextextended(:s,0))) locked").param("s",value).query(Integer.class).single();}
  private Map<String,Object> receipt(Map<String,Object> version,Map<String,Object> revision){
    var row=db.sql("select * from ouf_onboarding.managed_onboarding_review where onboarding_version_id=:v")
        .param("v",version.get("onboarding_version_id")).query().singleRow();
    var out=reviews.receipt(version,row);out.put("parentVersionId",revision.get("parent_version_id"));
    out.put("parentConfigurationHash",revision.get("parent_configuration_hash"));out.put("publicationEffects",effects());return out;
  }
  public static Map<String,Object> effects(){return Map.of("effect",EFFECT,"preparationPublishes",false,
      "preparationAcquires",false,"historicalJobsReplayed",false,"humanActivationRequired",true,
      "description","Attivare questa revisione pubblica una nuova configurazione della stessa fonte. Lo scheduler può avviare una nuova acquisizione secondo la configurazione conservata, anche per un file già acquisito. La preparazione non pubblica, non acquisisce e non ripete decisioni o job storici.");}
  @SuppressWarnings("unchecked") private static Map<String,Object> configuration(Map<String,Object> version){return (Map<String,Object>)version.get("configuration");}
  private static void revisionMetadata(Map<String,Object> configuration,int number){
    var bundle=object(configuration,"bundle");var extraction=object(configuration,"extractionProfile");
    String oldRef="onboarding://extraction-profiles/"+extraction.get("profileId")+"/versions/"+extraction.get("version");
    if(bundle.containsKey("extractionProfileRef")){
      if(!oldRef.equals(bundle.get("extractionProfileRef")))throw fail(HttpStatus.UNPROCESSABLE_ENTITY,"ONB_REVISION_EXTERNAL_PROFILE_REFERENCE");
      bundle.put("extractionProfileRef","onboarding://extraction-profiles/"+extraction.get("profileId")+"/versions/"+number);
    }
    bundle.put("bundleVersion",String.valueOf(number));extraction.put("version",String.valueOf(number));
  }
  @SuppressWarnings("unchecked") private static Map<String,Object> object(Map<String,Object> parent,String key){
    if(!(parent.get(key) instanceof Map<?,?>))throw fail(HttpStatus.UNPROCESSABLE_ENTITY,"ONB_REVISION_PROFILE_REQUIRED");
    return (Map<String,Object>)parent.get(key);
  }
  @SuppressWarnings("unchecked") private static List<Map<String,Object>> properties(Map<String,Object> configuration){
    var profile=object(object(object(object(configuration,"extractionProfile"),"runtime"),"udp"),"materialization");
    if(!(profile.get("properties") instanceof List<?> list)||list.size()>256||list.stream().anyMatch(x->!(x instanceof Map<?,?>)))
      throw fail(HttpStatus.UNPROCESSABLE_ENTITY,"ONB_REVISION_PROFILE_REQUIRED");
    return (List<Map<String,Object>>)(List<?>)list;
  }
  /** Deep copy: every field except the selected reconciliation objects remains byte-for-byte JSON equivalent. */
  public static Map<String,Object> revise(Map<String,Object> original,List<Change> changes){
    var copy=deepCopy(original);var properties=properties(copy);var seen=new HashSet<List<String>>();boolean changed=false;
    for(var change:changes){
      if(change==null||change.propertyIri()==null||change.propertyIri().isBlank()||change.sourceField()==null||change.sourceField().isBlank()
          ||change.reconciliation()==null||change.reconciliation().isEmpty()||!seen.add(List.of(change.propertyIri(),change.sourceField())))
        throw fail(HttpStatus.BAD_REQUEST,"ONB_REVISION_PROPERTY_INVALID");
      var matches=properties.stream().filter(x->change.propertyIri().equals(x.get("propertyIri"))&&change.sourceField().equals(x.get("sourceField"))).toList();
      if(matches.size()!=1)throw fail(HttpStatus.UNPROCESSABLE_ENTITY,"ONB_REVISION_PROPERTY_NOT_UNIQUE");
      var property=matches.getFirst();changed|=!Objects.equals(property.get("reconciliation"),change.reconciliation());
      property.put("reconciliation",deepCopy(change.reconciliation()));
    }
    if(!changed)throw fail(HttpStatus.CONFLICT,"ONB_REVISION_NO_CHANGE");
    return copy;
  }
  @SuppressWarnings("unchecked") private static <T>T deepCopy(T value){
    if(value instanceof Map<?,?> map){var out=new LinkedHashMap<String,Object>();map.forEach((k,v)->out.put((String)k,deepCopy(v)));return (T)out;}
    if(value instanceof List<?> list){var out=new ArrayList<>();list.forEach(v->out.add(deepCopy(v)));return (T)out;}
    return value;
  }
}
