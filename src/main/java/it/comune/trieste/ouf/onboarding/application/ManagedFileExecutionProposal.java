package it.comune.trieste.ouf.onboarding.application;

import java.net.URI;
import java.util.*;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import org.springframework.http.HttpStatus;
import static it.comune.trieste.ouf.onboarding.application.ManagedReviewSnapshot.child;

/** Pure preparation of an explicitly typed CSV execution profile. It issues no approval or runtime authority. */
public final class ManagedFileExecutionProposal {
  static String datatype(String transform){return ValueReader.DATATYPES.get(ValueReader.named(transform).output);}
  public record ReviewTerm(String iri,String labelIt,String descriptionIt,String license) {}
  public record SemanticBinding(String semanticId,String semanticVersion,String revisionId,String publicationSetId,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
      List<ReviewTerm> reviewTerms) {
    public SemanticBinding(String id,String version,String revision,String set){this(id,version,revision,set,null);}
  }
  @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  public record Limits(Integer maxRows,Integer maxColumns,Integer maxCellChars) {}
  public record Proposal(List<SemanticBinding> semanticBindings,String semanticPublicationSetRef,
                         Map<String,Object> udp,
                         @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                         Limits limits) {}
  private ManagedFileExecutionProposal() {}

  static List<String> missingSections(Map<?,?> config) {
    var runtime=child(child(config,"extractionProfile"),"runtime");
    var execution=child(runtime,"execution");
    var udp=child(runtime,"udp");
    var resolution=child(udp,"resolution");
    var missing=new ArrayList<String>();
    if (!hasRetainedBindings(config)) missing.add("semanticBindings");
    if (!(execution.get("semanticPublicationSetRef") instanceof String ref) || ref.isBlank())
      missing.add("semanticPublicationSetRef");
    if (resolution.isEmpty()) missing.add("udp.resolution");
    if (child(udp,"materialization").isEmpty()) missing.add("udp.materialization");
    // Predecessor exact-key execution profiles predate the governed parser-limit contract.
    boolean legacy="EXACT_SOURCE_KEY".equals(resolution.get("strategyId")) && "1".equals(execution.get("adapterRuntimeVersion"))
        && ("managed-csv".equals(execution.get("adapterId")) || "managed-tabular-v1".equals(execution.get("adapterId")));
    if (!validLimit(execution,"maxRows",1_000_000,legacy) || !validLimit(execution,"maxColumns",10_000,legacy)
        || !validLimit(execution,"maxCellChars",1_000_000,legacy))
      missing.add("limits");
    return List.copyOf(missing);
  }

  private static boolean hasRetainedBindings(Map<?,?> config){
    if(!(config.get("semanticReferenceBindings") instanceof List<?> pins) || pins.isEmpty())return false;
    if(!(child(config,"semanticMapping").get("semanticRefs") instanceof List<?> expected) || pins.size()!=expected.size())return false;
    var coordinates=new HashSet<String>();
    for(var pin:pins){
      if(!(pin instanceof Map<?,?> binding))return false;
      for(var key:List.of("semanticId","semanticVersion","revisionId","publicationSetId"))
        if(!(binding.get(key) instanceof String text) || text.isBlank())return false;
      coordinates.add(binding.get("semanticId")+"@"+binding.get("semanticVersion"));
    }
    return coordinates.size()==pins.size() && coordinates.equals(new HashSet<>(expected));
  }

  private static boolean validLimit(Map<?,?> execution,String key,int max,boolean optional){
    return optional && !execution.containsKey(key) || inRange(execution.get(key),1,max);
  }

  public static Map<String,Object> compile(Map<String,Object> existing,Map<String,Object> asset,
                                           Map<String,Object> profile,String tenant,Proposal proposal,AcquisitionLimits installation) {
    require(existing!=null && asset!=null && proposal!=null && tenant!=null && !tenant.isBlank());
    Map<String,Object> config=copy(existing),extraction=object(config,"extractionProfile"),runtime=object(extraction,"runtime");
    Map<String,Object> semantic=object(config,"semanticMapping"),source=object(semantic,"sourceType"),bundle=object(config,"bundle");
    String sourceId=text(source,"sourceId"),assetId=text(runtime,"assetId"),profileId=text(runtime,"fileProfileId");
    require("FILE".equals(source.get("typeCode")) && sourceId.equals(extraction.get("sourceId"))
        && sourceId.equals(object(bundle,"source").get("sourceId"))
        && "INTERNAL_MANAGED".equals(object(bundle,"source").get("sourceKind"))
        && "MANAGED".equals(object(bundle,"source").get("acquisitionMode"))
        && "MANAGED".equals(runtime.get("mode")) && "ONE_ROW_ONE_SOURCE_OBJECT".equals(runtime.get("recordModel")));
    require(!runtime.containsKey("format") && !runtime.containsKey("layer")
        && Set.of(",",";").contains(text(runtime,"csvDelimiter")));
    require(assetId.equals(String.valueOf(asset.get("asset_id")))
        && sourceId.equals(asset.get("source_id"))
        && text(runtime,"stagingRef").equals(asset.get("staging_ref"))
        && text(runtime,"contentHash").equals(asset.get("content_hash"))
        && assetId.equals(object(extraction,"selection").get("assetId"))
        && profileId.equals(object(extraction,"selection").get("fileProfileId")));
    require(text(bundle,"schemaRef").equals("profile://managed-files/"+assetId+"/profiles/"+profileId));
    long size=integer(asset.get("size_bytes"),1,10_485_760);
    List<?> fields=list(object(extraction,"projection").get("FILE"),1,ManagedFileLimits.MAX_COLUMNS);
    Limits requested=proposal.limits();
    if(requested!=null){
      if(requested.maxRows()!=null)integer(requested.maxRows(),1,1_000_000);
      if(requested.maxColumns()!=null)integer(requested.maxColumns(),1,10_000);
      if(requested.maxCellChars()!=null)integer(requested.maxCellChars(),1,1_000_000);
    }
    Limits limits=installation.resolve(profile,requested);
    require(fields.size()<=limits.maxColumns() && new HashSet<>(fields).size()==fields.size());
    Set<String> mapped=new LinkedHashSet<>(),mappedFields=new LinkedHashSet<>(),classes=new LinkedHashSet<>();
    Map<String,String> declaredDatatypes=new HashMap<>();
    for(Object raw:list(semantic.get("targetClasses"),1,32))classes.add(iri(text(map(raw),"classIri")));
    var inputs=new HashMap<String,String>();
    fields.forEach(field->inputs.put(string(field),null));
    var combined=new ValueCompositions(semantic.getOrDefault("compositions",List.of()),inputs,null);
    var valueMapRefs=new HashSet<>(combined.valueMapRefs);
    mapped.addAll(combined.datatypes.keySet());declaredDatatypes.putAll(combined.datatypes);
    for(Object raw:list(semantic.get("propertyMappings"),0,ManagedFileLimits.MAX_COLUMNS)){
      Map<String,Object> mapping=map(raw);String field=text(mapping,"sourceField");
      require(fields.contains(field) && mappedFields.add(field) && Arrays.stream(ValueReader.values()).anyMatch(r->r.name().equals(mapping.get("transform")))
          && mapped.add(iri(text(mapping,"targetPropertyIri"))));
      var reader=ValueReader.named(text(mapping,"transform"));
      var params=reader.mappingParameters(mapping);
      if(reader==ValueReader.VALUE_MAP_V1)valueMapRefs.add((String)params.get("valueMapRef"));
      declaredDatatypes.put(text(mapping,"targetPropertyIri"),datatype(text(mapping,"transform")));
    }
    mappedFields.addAll(combined.used);
    require(mappedFields.equals(new HashSet<>(fields)));
    require(!semantic.containsKey("vocabularyMappings") || list(semantic.get("vocabularyMappings"),0,0).isEmpty());
    require(!config.containsKey("relationshipMappings") || list(config.get("relationshipMappings"),0,0).isEmpty());
    String publication=uuid(proposal.semanticPublicationSetRef());
    bind(config,semantic,proposal.semanticBindings(),publication,classes,mapped,combined.typeRefs,combined.labels.keySet(),valueMapRefs);
    Map<String,Object> udp=copy(proposal.udp());keys(udp,"resolution","materialization");
    new GovernedIdentityProposal(declaredDatatypes,publication)
        .validateExecution(udp.get("resolution"),semantic.get("targetClasses"),sourceId,classes,tenant);
    Map<String,Object> materialization=object(udp,"materialization");
    keys(materialization,"policyRef","properties","bitemporalProperties","checkpointInterval");
    text(materialization,"policyRef");integer(materialization.get("checkpointInterval"),1,1_000_000);
    list(materialization.get("bitemporalProperties"),0,0);
    Map<String,String> labels=new HashMap<>();
    for(Object raw:list(config.get("dataAccessPolicies"),1,ManagedFileLimits.MAX_COLUMNS)){
      Map<String,Object> label=map(raw);require("PROPERTY".equals(label.get("scope")));
      require(labels.put(text(label,"target"),text(label,"label"))==null);
    }
    require(labels.entrySet().containsAll(combined.labels.entrySet()) && labels.keySet().equals(mapped));Set<String> properties=new HashSet<>();
    for(Object raw:list(materialization.get("properties"),1,ManagedFileLimits.MAX_COLUMNS)){
      Map<String,Object> property=map(raw);keys(property,"sourceField","propertyIri","datatype","accessLabel","authorityOrder");
      String iri=text(property,"propertyIri");
      require(mapped.contains(iri) && properties.add(iri) && iri.equals(property.get("sourceField"))
          && declaredDatatypes.get(iri).equals(property.get("datatype")) && labels.get(iri).equals(property.get("accessLabel")));
      Set<String> authorities=new HashSet<>();
      for(Object authority:list(property.get("authorityOrder"),0,32))require(authorities.add(string(authority)));
    }
    require(properties.equals(mapped));
    Map<String,Object> execution=new LinkedHashMap<>();
    execution.put("acquisitionMode","INTERNAL_MANAGED_CSV");execution.put("adapterId","managed-tabular-v1");
    execution.put("adapterRuntimeVersion","1.0.0");execution.put("sourceSchemaRef",text(bundle,"schemaRef"));
    execution.put("sourceSchemaId",sourceId);execution.put("sourceSchemaVersion",text(extraction,"version"));
    execution.put("semanticPublicationSetRef",publication);execution.put("adapterProfileRef","adapter://managed-tabular/1");
    execution.put("observationPolicy","ACQUISITION_TIME");execution.put("expectedSize",size);
    execution.put("maxRows",limits.maxRows());execution.put("maxColumns",limits.maxColumns());execution.put("maxCellChars",limits.maxCellChars());
    execution.put("mappingRefs",List.of(text(semantic,"mappingId")));execution.put("authorityPolicyRef",text(materialization,"policyRef"));
    runtime.put("execution",execution);runtime.put("udp",udp);
    return config;
  }
  static void bind(Map<String,Object> config,Map<String,Object> semantic,List<SemanticBinding> supplied,String publication,
      Set<String> classes,Set<String> mapped,Set<String> typeRefs,Set<String> compositionTargets,Set<String> valueMapRefs){
    List<?> expectedRefs=list(semantic.get("semanticRefs"),1,Integer.MAX_VALUE);
    require(new HashSet<>(expectedRefs).size()==expectedRefs.size());
    if(supplied==null)supplied=List.of();
    var proposedRefs=new HashSet<String>();
    for(var pin:supplied){
      require(pin!=null);
      proposedRefs.add(atom(pin.semanticId())+"@"+atom(pin.semanticVersion()));
    }
    if(!proposedRefs.equals(new HashSet<>(expectedRefs)) || proposedRefs.size()!=supplied.size()){
      var ref=expectedRefs.stream().map(ManagedFileExecutionProposal::string)
          .filter(expected->!proposedRefs.contains(expected)).findFirst().orElse(string(expectedRefs.getFirst()));
      throw new DomainFailure(HttpStatus.UNPROCESSABLE_ENTITY,"ONB_SEMANTIC_REF_VERSION_MISMATCH",
          "Proposal bindings must match the retained semantic references",Map.of("semanticRef",ref));
    }
    var descriptions=new LinkedHashMap<String,Object>();
    List<Map<String,Object>> bindings=new ArrayList<>();
    for(SemanticBinding binding:supplied){
      String id=binding.semanticId(),version=binding.semanticVersion();
      String set=uuid(binding.publicationSetId());
      String coordinate=id+"@"+set;
      // A nested type can be part of the published definition without an item builder.
      // Preparation verifies the artifact kind of additional pins from other sets.
      require(publication==null || publication.equals(set) || !typeRefs.isEmpty()
          || valueMapRefs.contains(coordinate) || valueMapRefs.contains(id));
      var pinned=new LinkedHashMap<String,Object>(Map.of("semanticId",id,"semanticVersion",version,
          "revisionId",uuid(binding.revisionId()),"publicationSetId",set));
      if(binding.reviewTerms()!=null){
        require(binding.reviewTerms().size()<=classes.size()+mapped.size());
        for(var term:binding.reviewTerms()){
          require(term!=null && (classes.contains(term.iri())||mapped.contains(term.iri())) && !descriptions.containsKey(term.iri()));
          for(String value:Arrays.asList(term.labelIt(),term.descriptionIt(),term.license()))
            require(value==null || (value.length()<=2048 && value.chars().noneMatch(Character::isISOControl)));
          descriptions.put(term.iri(),Map.of("term",term,"binding",pinned));
        }
      }
      bindings.add(pinned);
    }
    for(String target:compositionTargets)require(bindings.stream().anyMatch(b->target.equals(b.get("semanticId"))));
    config.remove("semanticReviewTerms");
    if(!descriptions.isEmpty())config.put("semanticReviewTerms",descriptions);
    for(Object raw:list(semantic.get("targetClasses"),1,32)){
      Map<String,Object> target=map(raw);require(proposedRefs.contains(text(target,"ontologyId")+"@"+text(target,"ontologyVersion")));
    }
    config.put("semanticReferenceBindings",bindings);
  }
  private static String atom(String value){require(value!=null && !value.isBlank() && value.length()<=2048 && value.chars().noneMatch(c->Character.isWhitespace(c)||c=='@'));return value;}
  private static String uuid(String value){try{require(value!=null && UUID.fromString(value).toString().equals(value));return value;}catch(RuntimeException e){throw invalid();}}
  private static String iri(String value){try{require(URI.create(value).isAbsolute());return value;}catch(RuntimeException e){throw invalid();}}
  private static String string(Object value){require(value instanceof String && !((String)value).isBlank() && ((String)value).length()<=2048);return (String)value;}
  private static String text(Map<String,Object> value,String key){return string(value.get(key));}
  private static boolean inRange(Object value,long minimum,long maximum){
    return value instanceof Number n && n.doubleValue()==n.longValue() && n.longValue()>=minimum && n.longValue()<=maximum;
  }
  private static long integer(Object value,long minimum,long maximum){require(inRange(value,minimum,maximum));return ((Number)value).longValue();}
  private static List<?> list(Object value,int minimum,int maximum){require(value instanceof List<?>);List<?> result=(List<?>)value;require(result.size()>=minimum && result.size()<=maximum);return result;}
  @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){require(value instanceof Map<?,?>);return (Map<String,Object>)value;}
  private static Map<String,Object> object(Map<String,Object> value,String key){return map(value.get(key));}
  private static void keys(Map<String,Object> value,String... keys){require(value.keySet().equals(Set.of(keys)));}
  private static Map<String,Object> copy(Map<String,Object> value){require(value!=null);Map<String,Object> out=new LinkedHashMap<>();value.forEach((key,item)->out.put(key,copyValue(item)));return out;}
  private static Object copyValue(Object value){if(value instanceof Map<?,?>)return copy(map(value));if(value instanceof List<?> items)return new ArrayList<>(items.stream().map(ManagedFileExecutionProposal::copyValue).toList());return value;}
  private static void require(boolean condition){if(!condition)throw invalid();}
  private static ReviewValidationFailure invalid(){return new ReviewValidationFailure("ONB_MANAGED_EXECUTION_PROPOSAL_INVALID");}
}
