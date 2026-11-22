package it.comune.trieste.ouf.onboarding.application;

import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.util.*;
import static it.comune.trieste.ouf.onboarding.application.ValueCompositions.*;

/** Called on a private draft before its configuration hash and HUMAN challenge are created. */
final class PublishedDatatypes {
  private PublishedDatatypes(){}
  static boolean hasPinnedEvidence(Map<?,?> pin){
    return pin.containsKey("definition") || pin.containsKey("definitionSha256")
        || pin.containsKey("valueMapRef") || pin.containsKey("vocabularyRef") || pin.containsKey("targetVocabulary");
  }
  static boolean requiresPreparation(Map<?,?> mapping,Map<?,?> extraction,Object bindings){
    if(mapping.containsKey("compositions"))return true;
    if(!ManagedReviewSnapshot.child(extraction,"selection").containsKey("fileProfileId"))return false;
    if(ManagedReviewSnapshot.list(bindings).stream().map(ManagedReviewSnapshot::object)
        .anyMatch(PublishedDatatypes::hasPinnedEvidence))return true;
    var legacy=Set.of("IDENTITY","DECIMAL_DOT_V1","DECIMAL_COMMA_V1","DATE_ISO_V1","DATE_DMY_V1");
    return ManagedReviewSnapshot.list(mapping.get("propertyMappings")).stream().map(ManagedReviewSnapshot::object)
        .anyMatch(p->p.containsKey("transformParams") || p.get("transform") instanceof String reader && !legacy.contains(reader));
  }
  static void prepare(Map<String,Object> config,Map<String,String> profiles,DatatypeResolver registry,CanonicalHash hashes){
    var mapping=map(config.get("semanticMapping"));
    var refs=list(mapping.get("semanticRefs"),1,32);
    Map<String,Map<String,Object>> pins=new LinkedHashMap<>();
    Map<String,Object> retainedVocabularies=new HashMap<>();
    Map<String,Object> retainedTargets=new HashMap<>();
    Map<String,Object> retainedDigests=new HashMap<>();
    Map<String,Object> retainedDefinitions=new HashMap<>();
    Set<String> versions=new HashSet<>();
    for(Object raw:list(config.get("semanticReferenceBindings"),1,32)){
      var binding=map(raw);
      var pin=new LinkedHashMap<String,Object>();
      for(String key:List.of("semanticId","semanticVersion","revisionId","publicationSetId"))pin.put(key,text(binding,key));
      String id=text(pin,"semanticId");
      require(versions.add(id+"@"+pin.get("semanticVersion")));
      String ref=id+"@"+pin.get("publicationSetId");
      require(pins.put(ref,pin)==null);
      if(binding.containsKey("valueMapRef") || binding.containsKey("vocabularyRef") || binding.containsKey("targetVocabulary")){
        if(!ref.equals(binding.get("valueMapRef"))
            || (!binding.containsKey("vocabularyRef") && !binding.containsKey("targetVocabulary")))
          throw DatatypeResolver.unresolvedValueMap();
        if(binding.containsKey("vocabularyRef"))retainedVocabularies.put(ref,binding.get("vocabularyRef"));
        if(binding.containsKey("targetVocabulary"))retainedTargets.put(ref,binding.get("targetVocabulary"));
      }
      if(binding.containsKey("definitionSha256"))retainedDigests.put(ref,binding.get("definitionSha256"));
      if(binding.containsKey("definition"))retainedDefinitions.put(ref,binding.get("definition"));
    }
    require(versions.size()==refs.size() && versions.containsAll(refs));
    Object expressions=mapping.containsKey("compositions")?mapping.get("compositions"):List.of();
    var combined=new ValueCompositions(expressions,profiles,null);
    var mapRefs=new LinkedHashSet<>(combined.valueMapRefs);
    for(Object raw:list(mapping.get("propertyMappings"),0,ManagedFileLimits.MAX_COLUMNS)){
      var property=map(raw);
      var reader=ValueReader.named(text(property,"transform"));
      var params=reader.mappingParameters(property);
      if(reader==ValueReader.VALUE_MAP_V1)mapRefs.add((String)params.get("valueMapRef"));
    }
    Map<String,String> mapCoordinates=new HashMap<>();
    for(String ref:mapRefs)mapCoordinates.put(ref,PublishedValueMaps.coordinate(ref,pins));
    var mapPins=new HashSet<>(mapCoordinates.values());
    mapPins.addAll(retainedVocabularies.keySet());
    mapPins.addAll(retainedTargets.keySet());
    var runtime=map(map(config.get("extractionProfile")).get("runtime"));
    Object executionSet=ManagedReviewSnapshot.child(runtime,"execution").get("semanticPublicationSetRef");
    // Bindings carry coordinates, not artifact kinds. Classify them from the Registry so even
    // an additional pinned DATATYPE (outside a property expression) carries its definition.
    for(var entry:pins.entrySet()){
      var pin=entry.getValue();
      String ref=entry.getKey();
      boolean expectedMap=mapPins.contains(ref);
      var actual=resolve(registry,pin,expectedMap);
      String kind=text(actual,"artifact_type");
      boolean valueMap="VALUE_MAP".equals(kind);
      if(expectedMap || valueMap){
        PublishedValueMaps.verify(ref,pin,actual,hashes);
        String vocabulary=text(pin,"targetVocabulary");
        if((retainedTargets.containsKey(ref) && !vocabulary.equals(retainedTargets.get(ref)))
            || (retainedVocabularies.containsKey(ref) && !PublishedValueMaps.vocabularyId(vocabulary).equals(retainedVocabularies.get(ref))))
          throw DatatypeResolver.unresolvedValueMap();
      }else if("DATATYPE".equals(kind)){
        verify(pin,actual,hashes);
      }else if(retainedDigests.containsKey(ref))throw DatatypeResolver.unresolved();
      if(combined.typeRefs.contains(ref) && !"DATATYPE".equals(kind))throw DatatypeResolver.unresolved();
      if("CONCEPT".equals(kind) && actual.get("definition") instanceof Map<?,?> concept && concept.containsKey("vocabularyRef")){
        iri(text(concept,"vocabularyRef"));
        pin.put("definition",concept);
      }
      if((retainedDefinitions.containsKey(ref) && !Objects.equals(retainedDefinitions.get(ref),pin.get("definition")))
          || (retainedDigests.containsKey(ref) && !Objects.equals(retainedDigests.get(ref),pin.get("definitionSha256"))))
        throw valueMap?DatatypeResolver.unresolvedValueMap():DatatypeResolver.unresolved();
      if(combined.labels.containsKey(pin.get("semanticId")) && !"PROPERTY".equals(kind))throw DatatypeResolver.unresolved();
    }
    var mappedTypes=new HashSet<>(combined.typeRefs);
    for(var entry:pins.entrySet())
      if(entry.getValue().containsKey("definitionSha256") && !entry.getValue().containsKey("valueMapRef"))
        checkClosure(entry.getKey(),pins,new HashSet<>(),new int[]{0},mappedTypes);
    if(executionSet!=null)for(var entry:pins.entrySet()){
      String ref=entry.getKey();
      if(!executionSet.equals(entry.getValue().get("publicationSetId")) && !mappedTypes.contains(ref) && !mapPins.contains(ref))
        throw DatatypeResolver.unresolved();
    }
    combined=new ValueCompositions(expressions,profiles,ref->definition(pins,ref),
        ref->PublishedValueMaps.vocabularyId(text(pins.get(mapCoordinates.get(ref)),"targetVocabulary")));
    Map<String,String> targetLabels=new HashMap<>();
    for(Object raw:list(config.get("dataAccessPolicies"),1,ManagedFileLimits.MAX_COLUMNS)){
      var policy=map(raw);
      require(LABELS.contains(text(policy,"label")) && "PROPERTY".equals(policy.get("scope")) && targetLabels.put(text(policy,"target"),text(policy,"label"))==null);
    }
    var used=new HashSet<>(combined.used);
    var datatypes=new HashMap<>(combined.datatypes);
    for(Object raw:list(mapping.get("propertyMappings"),0,ManagedFileLimits.MAX_COLUMNS)){
      var property=map(raw);String field=text(property,"sourceField");
      var reader=ValueReader.named(text(property,"transform"));
      require(profiles.containsKey(field) && (profiles.get(field)==null || reader.supports(profiles.get(field))));
      used.add(field);
      require(datatypes.put(text(property,"targetPropertyIri"),ValueReader.DATATYPES.get(reader.output))==null);
    }
    require(used.equals(profiles.keySet()) && datatypes.keySet().equals(targetLabels.keySet()));
    for(var target:combined.labels.entrySet()){
      require(target.getValue().equals(targetLabels.get(target.getKey()))
          && pins.values().stream().anyMatch(pin->target.getKey().equals(pin.get("semanticId"))));
    }

    if(runtime.containsKey("udp")){
      var udp=map(runtime.get("udp"));
      var materialized=new HashSet<String>();
      for(var raw:list(map(udp.get("materialization")).get("properties"),1,ManagedFileLimits.MAX_COLUMNS)){
        var property=map(raw);String id=text(property,"propertyIri");
        require(materialized.add(id) && text(property,"datatype").equals(datatypes.get(id))
            && id.equals(property.get("sourceField")) && text(property,"accessLabel").equals(targetLabels.get(id)));
      }
      require(materialized.equals(datatypes.keySet()));
      var resolution=map(udp.get("resolution"));
      if(resolution.containsKey("governedIdentity"))for(var raw:list(map(resolution.get("governedIdentity")).get("signals"),1,ManagedFileLimits.MAX_COLUMNS)){
        var signal=map(raw);String datatype=datatypes.get(text(signal,"id"));
        require(datatype!=null && ValueReader.comparator(datatype).equals(signal.get("comparator")));
      }
    }
    config.put("semanticReferenceBindings",List.copyOf(pins.values()));
  }

  private static Map<String,Object> resolve(DatatypeResolver registry,Map<String,Object> pin,boolean expectedMap){
    Map<String,Object> actual;
    try{
      actual=registry.resolve(text(pin,"semanticId"),text(pin,"revisionId"),text(pin,"publicationSetId"));
    }catch(DomainFailure failure){
      if(expectedMap && "ONB_DATATYPE_UNRESOLVED".equals(failure.code()))throw DatatypeResolver.unresolvedValueMap();
      throw failure;
    }
    if(actual==null || !(actual.get("artifact_type") instanceof String kind) || kind.isBlank()
        || !pin.get("semanticId").equals(actual.get("semantic_id"))
        || !pin.get("semanticVersion").equals(actual.get("semantic_version"))
        || !pin.get("publicationSetId").equals(actual.get("publication_set_id"))
        || !pin.get("revisionId").equals(actual.get("revision_id")))
      throw expectedMap?DatatypeResolver.unresolvedValueMap():DatatypeResolver.unresolved();
    return actual;
  }

  private static Map<?,?> definition(Map<String,Map<String,Object>> pins,String ref){
    var pin=pins.get(ref);
    if(pin==null || pin.containsKey("valueMapRef") || !pin.containsKey("definitionSha256")
        || !(pin.get("definition") instanceof Map<?,?> definition))throw DatatypeResolver.unresolved();
    return definition;
  }
  private static void checkClosure(String ref,Map<String,Map<String,Object>> pins,Set<String> path,int[] leaves,Set<String> referenced){
    var def=definition(pins,ref);
    String id=text(pins.get(ref),"semanticId");
    require(path.size()<4 && path.add(id));
    for(var component:componentSpecs(def).values()){
      String type=text(component,"type");
      if(ValueReader.DATATYPES.containsKey(type))require(++leaves[0]<=256);
      else{
        referenced.add(type);
        checkClosure(type,pins,path,leaves,referenced);
      }
    }
    path.remove(id);
  }
  private static void verify(Map<String,Object> pin,Map<String,Object> actual,CanonicalHash hashes){
    if(!"ACTIVE".equals(actual.get("status"))
        || !(actual.get("definition") instanceof Map<?,?> definition)
        || !(actual.get("definitionSha256") instanceof String digest)
        || !hashes.ofDefinition(definition).equals(digest.startsWith("sha256:")?digest:"sha256:"+digest)
        || !(actual.get("label") instanceof Map<?,?> label) || !label.values().stream().allMatch(String.class::isInstance))
      throw DatatypeResolver.unresolved();
    pin.put("definition",definition);pin.put("definitionSha256",digest);pin.put("label",label);
  }
}
