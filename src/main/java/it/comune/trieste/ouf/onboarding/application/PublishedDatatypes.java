package it.comune.trieste.ouf.onboarding.application;

import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import java.util.*;
import static it.comune.trieste.ouf.onboarding.application.ValueCompositions.*;

/** Called on a private draft before its configuration hash and HUMAN challenge are created. */
final class PublishedDatatypes {
  private PublishedDatatypes(){}
  static boolean requiresPreparation(Map<?,?> mapping,Map<?,?> extraction){
    if(mapping.containsKey("compositions"))return true;
    if(!ManagedReviewSnapshot.child(extraction,"selection").containsKey("fileProfileId"))return false;
    var legacy=Set.of("IDENTITY","DECIMAL_DOT_V1","DECIMAL_COMMA_V1","DATE_ISO_V1","DATE_DMY_V1");
    return ManagedReviewSnapshot.list(mapping.get("propertyMappings")).stream().map(ManagedReviewSnapshot::object)
        .anyMatch(p->p.containsKey("transformParams") || p.get("transform") instanceof String reader && !legacy.contains(reader));
  }
  static void prepare(Map<String,Object> config,Map<String,String> profiles,DatatypeResolver registry,CanonicalHash hashes){
    var mapping=map(config.get("semanticMapping"));
    var refs=list(mapping.get("semanticRefs"),1,32);
    Map<String,Map<String,Object>> pins=new LinkedHashMap<>();
    Map<String,Object> retainedDigests=new HashMap<>();
    Set<String> versions=new HashSet<>();
    for(Object raw:list(config.get("semanticReferenceBindings"),1,32)){
      var binding=map(raw);
      var pin=new LinkedHashMap<String,Object>();
      for(String key:List.of("semanticId","semanticVersion","revisionId","publicationSetId"))pin.put(key,text(binding,key));
      String id=text(pin,"semanticId");
      require(versions.add(id+"@"+pin.get("semanticVersion")));
      String ref=id+"@"+pin.get("publicationSetId");
      require(pins.put(ref,pin)==null);
      if(binding.containsKey("definitionSha256"))retainedDigests.put(ref,binding.get("definitionSha256"));
    }
    require(versions.size()==refs.size() && versions.containsAll(refs));
    Object expressions=mapping.containsKey("compositions")?mapping.get("compositions"):List.of();
    var combined=new ValueCompositions(expressions,profiles,null);
    if(!combined.typeRefs.isEmpty() || !retainedDigests.isEmpty()){
      // Bindings carry coordinates, not artifact kinds. Classify them from the Registry so even
      // an additional pinned DATATYPE (outside a property expression) carries its definition.
      for(var entry:pins.entrySet()){
        var pin=entry.getValue();
        var actual=registry.resolve(text(pin,"semanticId"),text(pin,"revisionId"),text(pin,"publicationSetId"));
        if(actual==null || !(actual.get("artifact_type") instanceof String) || !pin.get("semanticId").equals(actual.get("semantic_id")) || !pin.get("semanticVersion").equals(actual.get("semantic_version"))
            || !pin.get("publicationSetId").equals(actual.get("publication_set_id")) || !pin.get("revisionId").equals(actual.get("revision_id")))
          throw DatatypeResolver.unresolved();
        if("DATATYPE".equals(actual.get("artifact_type"))){
          verify(pin,actual,hashes);
          if(retainedDigests.containsKey(entry.getKey()) && !Objects.equals(retainedDigests.get(entry.getKey()),pin.get("definitionSha256")))
            throw DatatypeResolver.unresolved();
        }else if(combined.typeRefs.contains(entry.getKey()) || retainedDigests.containsKey(entry.getKey()))throw DatatypeResolver.unresolved();
        if(combined.labels.containsKey(pin.get("semanticId")) && !"PROPERTY".equals(actual.get("artifact_type")))throw DatatypeResolver.unresolved();
      }
      for(var entry:pins.entrySet())
        if(entry.getValue().containsKey("definition") && !combined.typeRefs.contains(entry.getKey()))
          checkClosure(entry.getKey(),pins,new HashSet<>(),new int[]{0});
      combined=new ValueCompositions(expressions,profiles,ref->definition(pins,ref));
    }
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
      reader.parameters(property.get("transformParams"),null);
      require(profiles.containsKey(field) && (profiles.get(field)==null || reader.supports(profiles.get(field))));
      used.add(field);
      require(datatypes.put(text(property,"targetPropertyIri"),ValueReader.DATATYPES.get(reader.output))==null);
    }
    require(used.equals(profiles.keySet()) && datatypes.keySet().equals(targetLabels.keySet()));
    for(var target:combined.labels.entrySet()){
      require(target.getValue().equals(targetLabels.get(target.getKey()))
          && pins.values().stream().anyMatch(pin->target.getKey().equals(pin.get("semanticId"))));
    }
    var runtime=map(map(config.get("extractionProfile")).get("runtime"));
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

  private static Map<?,?> definition(Map<String,Map<String,Object>> pins,String ref){
    var pin=pins.get(ref);
    if(pin==null || !(pin.get("definition") instanceof Map<?,?> definition))throw DatatypeResolver.unresolved();
    return definition;
  }
  private static void checkClosure(String ref,Map<String,Map<String,Object>> pins,Set<String> path,int[] leaves){
    var def=definition(pins,ref);
    String id=text(pins.get(ref),"semanticId");
    require(path.size()<4 && path.add(id));
    for(var component:componentSpecs(def).values()){
      String type=text(component,"type");
      if(ValueReader.DATATYPES.containsKey(type))require(++leaves[0]<=256);
      else checkClosure(type,pins,path,leaves);
    }
    path.remove(id);
  }
  private static void verify(Map<String,Object> pin,Map<String,Object> actual,CanonicalHash hashes){
    if(!"ACTIVE".equals(actual.get("status"))
        || !(actual.get("definition") instanceof Map<?,?> definition)
        || !(actual.get("definitionSha256") instanceof String digest)
        || !hashes.ofDefinition(definition).equals(digest.startsWith("sha256:")?digest:"sha256:"+digest)
        || !(actual.get("label") instanceof Map<?,?> label) || !(label.get("it") instanceof String it) || it.isBlank())
      throw DatatypeResolver.unresolved();
    pin.put("definition",definition);pin.put("definitionSha256",digest);pin.put("label",label);
  }
}
