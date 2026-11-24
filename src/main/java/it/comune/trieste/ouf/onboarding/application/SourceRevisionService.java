package it.comune.trieste.ouf.onboarding.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import it.comune.trieste.ouf.authorization.*;
import it.comune.trieste.ouf.onboarding.authorization.AuthorizationPolicyRegistry;
import it.comune.trieste.ouf.onboarding.domain.*;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Prepares and freezes one revision of an active source for native HUMAN review. */
@Service
public class SourceRevisionService {
  public static final String READ="ouf.onboarding.revision.read", PREPARE="ouf.onboarding.revision.prepare";
  public static final String EFFECT="NEW_PUBLICATION_MAY_START_ACQUISITION";
  private final JdbcClient db; private final OnboardingService onboarding;
  private final ManagedOnboardingReviewService reviews; private final ManagedFileService files;
  private final CanonicalHash hashes; private final AuthorizationPolicyRegistry policy;
  public record Change(String propertyIri,String sourceField,Map<String,Object> reconciliation,
      @JsonInclude(JsonInclude.Include.NON_NULL) String transform,
      @JsonInclude(JsonInclude.Include.NON_NULL) Map<String,Object> transformParams){
    public Change(String propertyIri,String sourceField,Map<String,Object> reconciliation){this(propertyIri,sourceField,reconciliation,null,null);}
  }
  public record AssetRef(UUID assetId, UUID profileId) {}
  public record Prepare(String sourceId,UUID parentVersionId,long expectedVersion,String configurationHash,
      String commandKey,String publicationEffect,List<Change> changes,
      @JsonInclude(JsonInclude.Include.NON_NULL) AssetRef assetRef,
      @JsonInclude(JsonInclude.Include.NON_NULL) List<ManagedCsvRevision.NewColumn> newColumns,
      @JsonInclude(JsonInclude.Include.NON_NULL) List<Map<String,Object>> compositions){
    public Prepare(String sourceId,UUID parentVersionId,long expectedVersion,String configurationHash,
        String commandKey,String publicationEffect,List<Change> changes,AssetRef assetRef,List<ManagedCsvRevision.NewColumn> newColumns){
      this(sourceId,parentVersionId,expectedVersion,configurationHash,commandKey,publicationEffect,changes,assetRef,newColumns,null);
    }
    // The absent extension must serialize exactly as before for historical command fingerprints.
    public Prepare(String sourceId,UUID parentVersionId,long expectedVersion,String configurationHash,
        String commandKey,String publicationEffect,List<Change> changes){
      this(sourceId,parentVersionId,expectedVersion,configurationHash,commandKey,publicationEffect,changes,null);
    }
    public Prepare(String sourceId,UUID parentVersionId,long expectedVersion,String configurationHash,
        String commandKey,String publicationEffect,List<Change> changes,AssetRef assetRef){
      this(sourceId,parentVersionId,expectedVersion,configurationHash,commandKey,publicationEffect,changes,assetRef,null);
    }
  }
  public SourceRevisionService(JdbcClient db,OnboardingService onboarding,ManagedOnboardingReviewService reviews,
      ManagedFileService files,CanonicalHash hashes,AuthorizationPolicyRegistry policy){
    this.db=db;this.onboarding=onboarding;this.reviews=reviews;this.files=files;this.hashes=hashes;this.policy=policy;
  }
  private static DomainFailure fail(HttpStatus status,String code){return new DomainFailure(status,code,code);}
  static boolean permitted(AuthorizationPolicyRegistry policy,PrincipalContext p,String source,UUID version,String cap,String operation){
    return p!=null && p.actorType()==PrincipalContext.ActorType.HUMAN && p.scopes().contains(cap)
        && policy.authorizeActive(p,new ResourceContext("source",source,p.tenantId(),null,
            Map.of("onboardingVersionId",version.toString())),cap,operation,Instant.now()).allowed();
  }
  private void authorize(PrincipalContext p,String cap,String operation,String source,UUID version){
    if(source==null||!source.matches("[A-Za-z0-9_.:-]{1,160}")||version==null)
      throw fail(HttpStatus.BAD_REQUEST,"ONB_REVISION_IDENTITY_INVALID");
    if(!permitted(policy,p,source,version,cap,operation))
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
    out.put("publicationEffects",effects());return ManagedMcpObservation.version(out, revisionEligible(db,current));
  }
  @Transactional(timeout=8) public Map<String,Object> prepare(PrincipalContext p,Prepare r,String correlation){
    if(r==null)throw fail(HttpStatus.BAD_REQUEST,"ONB_REVISION_REQUEST_INVALID");
    authorize(p,PREPARE,"COMMAND",r.sourceId(),r.parentVersionId());
    if(r.commandKey()==null||!r.commandKey().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        ||r.expectedVersion()<0||r.expectedVersion()>9007199254740991L
        ||r.configurationHash()==null||!r.configurationHash().matches("sha256:[a-f0-9]{64}")
        ||!EFFECT.equals(r.publicationEffect())||(r.changes()!=null && r.assetRef()!=null)
        ||(r.changes()==null && r.assetRef()==null && r.compositions()==null)
        ||(r.changes()!=null&&(r.changes().isEmpty()||r.changes().size()>256))
        ||(r.newColumns()!=null&&(r.assetRef()==null||r.newColumns().size()>ManagedFileLimits.MAX_COLUMNS))
        ||(r.assetRef()!=null&&(r.assetRef().assetId()==null||r.assetRef().profileId()==null)))
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
    if(!revisionParent(db,parent,r.assetRef()!=null)||((Number)parent.get("lock_version")).longValue()!=r.expectedVersion()
        ||!r.configurationHash().equals(parent.get("configuration_hash"))
        ||!r.configurationHash().equals(hashes.of(parent.get("configuration"))))
      throw fail(HttpStatus.PRECONDITION_FAILED,"ONB_REVISION_PARENT_DRIFT");
    if(revisionOpen(db,r.sourceId()))
      throw fail(HttpStatus.CONFLICT,"ONB_REVISION_OPEN_VERSION_EXISTS");
    var actor=new OnboardingService.Actor(p.subjectId(),"HUMAN_USER",Set.of(PREPARE));
    var draft=onboarding.cloneVersion(r.sourceId(),r.parentVersionId(),actor,correlation);
    UUID version=(UUID)draft.get("onboarding_version_id");
    var proposed=configuration(draft);
    int number=((Number)draft.get("version")).intValue();
    if(r.changes()!=null)proposed=revise(proposed,r.changes(),r.compositions()==null);
    if(r.assetRef()!=null)replaceAsset(p,r,proposed,number);
    if(r.compositions()!=null){
      boolean same=Objects.equals(object(proposed,"semanticMapping").getOrDefault("compositions",List.of()),r.compositions());
      if(!same)reviseCompositions(proposed,r.compositions());
      else if(r.assetRef()==null && proposed.equals(configuration(draft)))throw fail(HttpStatus.CONFLICT,"ONB_REVISION_NO_CHANGE");
    }
    if(r.changes()!=null && r.changes().stream().anyMatch(c->c.transform()!=null||c.transformParams()!=null)){
      var profiles=files.projectedTypes(r.sourceId(),object(proposed,"extractionProfile"));
      for(var mapping:rows(object(proposed,"semanticMapping"),"propertyMappings"))
        if(!ManagedCsvRevision.supports((String)mapping.get("transform"),profiles.get(mapping.get("sourceField"))))throw ValueReader.invalid();
    }
    revisionMetadata(proposed,number);
    draft=onboarding.patchVersion(r.sourceId(),version,((Number)draft.get("lock_version")).longValue(),proposed,actor,correlation);
    Map<String,Object> frozen;
    try{
      frozen=onboarding.submit(r.sourceId(),version,((Number)draft.get("lock_version")).longValue(),actor,correlation);
    }catch(DomainFailure failure){
      if(!"ONB_DATATYPE_UNRESOLVED".equals(failure.code()))throw failure;
      var properties=new LinkedHashMap<>(failure.properties());
      properties.put("resolutions",List.of("REVISE_CHANGES"));
      throw new DomainFailure(failure.status(),failure.code(),failure.getMessage(),properties);
    }
    // Uses the existing native THS/compatibility/activation machinery, not a new approval authority.
    reviews.registerReview(p,r.sourceId(),version,"revision:"+UUID.randomUUID(),fingerprint,frozen,actor,correlation);
    var row=db.sql("""
        insert into ouf_onboarding.source_property_revision
          (tenant_id,subject_id,command_key,request_hash,source_id,parent_version_id,parent_configuration_hash,
           onboarding_version_id,publication_effect,replacement_profile_id,new_columns,parent_state_at_prepare)
        values(:t,:s,:k,:h,:source,:parent,:ph,:v,:effect,:profile,cast(:columns as jsonb),:parentState)
        returning parent_version_id,parent_configuration_hash,replacement_profile_id,new_columns
        """)
        .param("t",p.tenantId()).param("s",p.subjectId()).param("k",r.commandKey()).param("h",fingerprint)
        .param("source",r.sourceId()).param("parent",r.parentVersionId()).param("ph",r.configurationHash())
        .param("parentState",parent.get("state"))
        .param("v",version).param("effect",r.publicationEffect())
        .param("columns",r.newColumns()==null?null:onboarding.write(r.newColumns()),java.sql.Types.VARCHAR)
        .param("profile",r.assetRef()==null?null:r.assetRef().profileId(),java.sql.Types.OTHER).query().singleRow();
    return receipt(frozen,row);
  }

  private void replaceAsset(PrincipalContext p,Prepare request,Map<String,Object> config,int number){
    String source=request.sourceId();
    var aggregate=onboarding.source(source);
    var metadata=object(aggregate,"metadata");
    if(!"INTERNAL_MANAGED".equals(aggregate.get("source_kind"))||!"MANAGED".equals(aggregate.get("acquisition_mode"))
        ||!(metadata.get("managedFileAssetId") instanceof String origin)
        ||(metadata.containsKey("managedTenantId")&&!p.tenantId().equals(metadata.get("managedTenantId"))))
      throw fail(HttpStatus.FORBIDDEN,"ONB_REVISION_SOURCE_SCOPE_UNPROVEN");
    files.requireTenantOwner(UUID.fromString(origin),p.tenantId(),p.subjectId());
    var selection=object(object(config,"extractionProfile"),"selection");
    UUID previous,profile;
    try{
      previous=UUID.fromString(String.valueOf(selection.get("assetId")));
      profile=UUID.fromString(String.valueOf(selection.get("fileProfileId")));
    }catch(IllegalArgumentException e){throw fail(HttpStatus.UNPROCESSABLE_ENTITY,"ONB_REVISION_PROFILE_REQUIRED");}
    var ref=request.assetRef();
    if(previous.equals(ref.assetId()))throw fail(HttpStatus.CONFLICT,"ONB_REVISION_NO_CHANGE");
    files.requireTenantOwner(previous,p.tenantId(),p.subjectId());
    var base=files.asset(previous);
    if(!source.equals(base.get("source_id")))throw reviews.assetConflict(p,(String)base.get("source_id"));
    files.requireTenantOwner(ref.assetId(),p.tenantId(),p.subjectId());
    // Serialize custody with initial onboarding and other sources competing for an unattached upload.
    db.sql("select asset_id from ouf_onboarding.managed_file_asset where asset_id=:a for update")
        .param("a",ref.assetId()).query(UUID.class).single();
    var asset=files.asset(ref.assetId());
    if(asset.get("source_id") instanceof String bound && !source.equals(bound))throw reviews.assetConflict(p,bound);
    var other=db.sql("select source_id from ouf_onboarding.source where source_id<>:s and metadata->>'managedFileAssetId'=:id order by source_id limit 2")
        .param("s",source).param("id",ref.assetId().toString()).query(String.class).list();
    if(!other.isEmpty())throw reviews.assetConflict(p,other.size()==1?other.getFirst():null);
    if(!"PROFILED".equals(asset.get("status")))throw fail(HttpStatus.CONFLICT,"ONB_REVISION_ASSET_NOT_PROFILED");
    ManagedCsvRevision.replace(config,base,files.fileProfile(previous,profile),asset,
        files.fileProfile(ref.assetId(),ref.profileId()),number,request.newColumns());
    if(asset.get("source_id")==null)
      db.sql("update ouf_onboarding.managed_file_asset set source_id=:s where asset_id=:a and source_id is null")
          .param("s",source).param("a",ref.assetId()).update();
  }
  private static boolean revisionParent(JdbcClient db,Map<String,Object> parent,boolean replacementAsset) {
    if ("ACTIVE".equals(parent.get("state"))) return true;
    return replacementAsset && "REJECTED".equals(parent.get("state")) && db.sql("""
        select not exists(select 1 from ouf_onboarding.published_configuration where source_id=:s)
          and not exists(select 1 from ouf_onboarding.onboarding_version where source_id=:s
            and (version>:n or state in ('ACTIVE','REVOKED')))
        """).param("s",parent.get("source_id")).param("n",parent.get("version")).query(Boolean.class).single();
  }

  private static boolean revisionOpen(JdbcClient db,Object source) {
    return db.sql("select exists(select 1 from ouf_onboarding.onboarding_version where source_id=:s and state in ('DRAFT','IN_REVIEW','APPROVED'))")
        .param("s",source).query(Boolean.class).single();
  }

  static boolean revisionEligible(JdbcClient db,Map<String,Object> parent) {
    return revisionParent(db,parent,true) && !revisionOpen(db,parent.get("source_id"));
  }

  /** Lost-response reconciliation is keyed by the original command, never by an unknown new version ID. */
  @Transactional public Map<String,Object> readCommand(PrincipalContext p,String source,UUID parent,String key){
    authorize(p,READ,"READ",source,parent);
    if(key==null||!key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))throw fail(HttpStatus.BAD_REQUEST,"ONB_REVISION_REQUEST_INVALID");
    var rows=db.sql("select * from ouf_onboarding.source_property_revision where tenant_id=:t and subject_id=:u and command_key=:k and source_id=:s and parent_version_id=:p")
        .param("t",p.tenantId()).param("u",p.subjectId()).param("k",key).param("s",source).param("p",parent).query().listOfRows();
    if(rows.isEmpty())return ManagedMcpObservation.with(
        Map.of("sourceId",source,"parentVersionId",parent,"commandKey",key,"commandState","ABSENT","newCommandAuthorized",false),
        ManagedMcpObservation.State.COMMAND_ABSENT,ManagedMcpObservation.Reason.REVISION_COMMAND_ABSENT);
    var row=rows.getFirst();return receipt(onboarding.version(source,(UUID)row.get("onboarding_version_id")),row);
  }
  private void lock(String value){db.sql("select 1 from (select pg_advisory_xact_lock(hashtextextended(:s,0))) locked").param("s",value).query(Integer.class).single();}
  private Map<String,Object> receipt(Map<String,Object> version,Map<String,Object> revision){
    var row=db.sql("select * from ouf_onboarding.managed_onboarding_review where onboarding_version_id=:v")
        .param("v",version.get("onboarding_version_id")).query().singleRow();
    var out=reviews.receipt(version,row);out.put("parentVersionId",revision.get("parent_version_id"));
    if(revision.get("replacement_profile_id")!=null){
      var selection=object(object(configuration(version),"extractionProfile"),"selection");
      out.put("assetRef",Map.of("assetId",selection.get("assetId"),"profileId",revision.get("replacement_profile_id")));
      out.put("diagnostics",List.of());
      out.put("newColumns",onboarding.newColumnDecisions(revision));
    }
    out.put("parentConfigurationHash",revision.get("parent_configuration_hash"));out.put("publicationEffects",effects());return out;
  }
  public static Map<String,Object> effects(){return Map.of("effect",EFFECT,"preparationPublishes",false,
      "preparationAcquires",false,"historicalJobsReplayed",false,"humanActivationRequired",true,
      "description","Attivare questa revisione pubblica una nuova configurazione della stessa fonte. Lo scheduler può avviare una nuova acquisizione secondo la configurazione conservata, anche per un file già acquisito. La preparazione non pubblica, non acquisisce e non ripete decisioni o job storici.");}
  @SuppressWarnings("unchecked") private static Map<String,Object> configuration(Map<String,Object> version){return (Map<String,Object>)version.get("configuration");}
  static void revisionMetadata(Map<String,Object> configuration,int number){
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
    if(!(profile.get("properties") instanceof List<?> list)||list.size()>ManagedFileLimits.MAX_COLUMNS||list.stream().anyMatch(x->!(x instanceof Map<?,?>)))
      throw fail(HttpStatus.UNPROCESSABLE_ENTITY,"ONB_REVISION_PROFILE_REQUIRED");
    return (List<Map<String,Object>>)(List<?>)list;
  }
  @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Map<String,Object> parent,String key){
    var items=ValueCompositions.list(parent.get(key),0,ManagedFileLimits.MAX_COLUMNS);
    for(Object item:items)ValueCompositions.map(item);
    return (List<Map<String,Object>>)(List<?>)items;
  }
  private static void updateDatatype(Map<String,Object> config,Map<String,Object> property,String datatype){
    property.put("datatype",datatype);
    var resolution=object(object(object(object(config,"extractionProfile"),"runtime"),"udp"),"resolution");
    if(resolution.containsKey("governedIdentity"))
      for(var signal:rows(object(resolution,"governedIdentity"),"signals"))
        if(property.get("propertyIri").equals(signal.get("id")))signal.put("comparator",ValueReader.comparator(datatype));
  }
  // Reuse the pinned destination's authority, reconciliation and identity assertions.
  static void reviseCompositions(Map<String,Object> config,List<Map<String,Object>> compositions){
    var semantic=object(config,"semanticMapping");
    var projection=ValueCompositions.list(object(object(config,"extractionProfile"),"projection").get("FILE"),1,ManagedFileLimits.MAX_COLUMNS);
    var fields=new HashMap<String,String>();projection.forEach(f->fields.put((String)f,null));
    var combined=new ValueCompositions(compositions,fields,null);
    var direct=rows(semantic,"propertyMappings");
    Map<Object,Object> labels=new LinkedHashMap<>(ValueCompositions.map(config.getOrDefault("compositionInputLabels",Map.of())));
    var access=rows(config,"dataAccessPolicies");
    var priorLabels=new HashMap<Object,Object>();
    for(var policy:access)priorLabels.put(policy.get("target"),policy.get("label"));
    for(var row:direct)if(combined.labels.containsKey(row.get("targetPropertyIri")))
      labels.put(row.get("sourceField"),priorLabels.get(row.get("targetPropertyIri")));
    if(!labels.isEmpty())config.put("compositionInputLabels",labels);
    semantic.put("propertyMappings",direct.stream().filter(row->!combined.labels.containsKey(row.get("targetPropertyIri"))).toList());
    semantic.put("compositions",compositions);
    for(var property:properties(config)){
      String id=(String)property.get("propertyIri");
      if(combined.datatypes.containsKey(id)){
        updateDatatype(config,property,combined.datatypes.get(id));property.put("accessLabel",combined.labels.get(id));
      }
    }
    for(var policy:access)
      if(combined.labels.containsKey(policy.get("target")))policy.put("label",combined.labels.get(policy.get("target")));
  }
  public static Map<String,Object> revise(Map<String,Object> original,List<Change> changes){
    return revise(original,changes,true);
  }
  private static Map<String,Object> revise(Map<String,Object> original,List<Change> changes,boolean requireChange){
    var copy=deepCopy(original);var properties=properties(copy);var seen=new HashSet<List<String>>();boolean changed=false;
    for(var change:changes){
      if(change==null||change.propertyIri()==null||change.propertyIri().isBlank()||change.sourceField()==null||change.sourceField().isBlank()
          ||(change.reconciliation()==null && change.transform()==null && change.transformParams()==null)
          ||(change.reconciliation()!=null && change.reconciliation().isEmpty())||!seen.add(List.of(change.propertyIri(),change.sourceField())))
        throw fail(HttpStatus.BAD_REQUEST,"ONB_REVISION_PROPERTY_INVALID");
      var matches=properties.stream().filter(x->change.propertyIri().equals(x.get("propertyIri"))&&change.sourceField().equals(x.get("sourceField"))).toList();
      if(matches.size()!=1)throw fail(HttpStatus.UNPROCESSABLE_ENTITY,"ONB_REVISION_PROPERTY_NOT_UNIQUE");
      var property=matches.getFirst();
      if(change.reconciliation()!=null){
        changed|=!Objects.equals(property.get("reconciliation"),change.reconciliation());
        property.put("reconciliation",deepCopy(change.reconciliation()));
      }
      if(change.transform()!=null||change.transformParams()!=null){
        var mappings=rows(object(copy,"semanticMapping"),"propertyMappings").stream()
            .filter(m->change.propertyIri().equals(m.get("targetPropertyIri"))).toList();
        if(mappings.size()!=1)throw fail(HttpStatus.BAD_REQUEST,"ONB_REVISION_PROPERTY_INVALID");
        var mapping=mappings.getFirst();
        var reader=ValueReader.named(change.transform()==null?(String)mapping.get("transform"):change.transform());
        var params=reader.parameters(change.transformParams(),null);
        changed|=!reader.name().equals(mapping.get("transform"))||!params.equals(mapping.getOrDefault("transformParams",Map.of()));
        mapping.put("transform",reader.name());mapping.remove("transformParams");
        if(!params.isEmpty())mapping.put("transformParams",params);
        updateDatatype(copy,property,ValueReader.DATATYPES.get(reader.output));
      }
    }
    if(requireChange&&!changed)throw fail(HttpStatus.CONFLICT,"ONB_REVISION_NO_CHANGE");
    return copy;
  }
  @SuppressWarnings("unchecked") private static <T>T deepCopy(T value){
    if(value instanceof Map<?,?> map){var out=new LinkedHashMap<String,Object>();map.forEach((k,v)->out.put((String)k,deepCopy(v)));return (T)out;}
    if(value instanceof List<?> list){var out=new ArrayList<>();list.forEach(v->out.add(deepCopy(v)));return (T)out;}
    return value;
  }
}
