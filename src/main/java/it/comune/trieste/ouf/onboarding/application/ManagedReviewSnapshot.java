package it.comune.trieste.ouf.onboarding.application;

import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.util.*;
import org.springframework.http.HttpStatus;

/** Decision targets come from the pinned profile and configuration. Never include samples or staging references. */
final class ManagedReviewSnapshot {
  private ManagedReviewSnapshot() {}
  static Map<?,?> object(Object value){return value instanceof Map<?,?> map?map:Map.of();}
  static List<?> list(Object value){return value instanceof List<?> items?items:List.of();}
  static Map<?,?> child(Map<?,?> map,String key){return object(map.get(key));}

  static Map<String,Object> build(Map<?,?> config,Map<?,?> asset,Map<?,?> profile,Object owner,Object name){
    var extraction=child(config,"extractionProfile");
    var runtime=child(extraction,"runtime");
    var mapping=child(config,"semanticMapping");
    var resolution=child(child(runtime,"udp"),"resolution");
    var policy=child(resolution,"governedIdentity");
    var keys=list(child(config,"sourceObjectIdentityPolicy").get("sourceFields"));
    var projected=new HashSet<>(list(child(extraction,"projection").get("FILE")));
    var excluded=child(config,"excludedFieldReview");
    var labels=new HashMap<Object,Object>();
    for(var raw:list(config.get("dataAccessPolicies"))){
      var access=object(raw);
      if("PROPERTY".equals(access.get("scope")))labels.put(access.get("target"),access.get("label"));
    }
    var materialized=new HashMap<Object,Map<?,?>>();
    for(var raw:list(child(child(runtime,"udp"),"materialization").get("properties"))){
      var item=object(raw);materialized.put(item.get("propertyIri"),item);
    }
    var properties=new HashMap<Object,Map<?,?>>();
    for(var raw:list(mapping.get("propertyMappings"))){
      var row=object(raw);properties.put(row.get("sourceField"),row);
    }
    var semantics=new LinkedHashMap<String,Object>();
    var bindings=list(config.get("semanticReferenceBindings"));
    for(var raw:list(mapping.get("targetClasses"))){
      var target=object(raw);
      addReference(semantics,config,bindings,target.get("classIri"),target.get("ontologyId"),target.get("ontologyVersion"),"CLASS");
    }
    var identity=new LinkedHashMap<String,Object>();
    var signals=new HashSet<Object>();
    identity.put("sourceKey",keys);
    if(!resolution.isEmpty())identity.put("strategy",resolution.get("strategyId"));
    for(var raw:list(policy.get("signals"))){
      var signal=object(raw);signals.add(signal.get("id"));identity.put("signal:"+signal.get("id"),signal);
    }
    for(String group:List.of("sufficientRules","distinctRules"))for(var raw:list(policy.get(group))){
      var rule=object(raw);identity.put(group+":"+rule.get("id"),rule);
    }
    var weighted=child(resolution,"weighted");
    identity.put("allowAutoNew",policy.get("allowAutoNew"));
    identity.put("maxCandidates",weighted.isEmpty()?policy.get("maxCandidates"):weighted.get("maxCandidates"));
    if("EXACT_SOURCE_KEY".equals(resolution.get("strategyId")) && resolution.get("matchProperty") instanceof String key){
      identity.put("exactKey",key);signals.add(key);
    }else if(!weighted.isEmpty()){
      for(var raw:list(weighted.get("signals"))){
        var signal=object(raw);signals.add(signal.get("property"));identity.put("weighted:"+signal.get("property"),signal);
      }
      for(String key:List.of("highThreshold","reviewThreshold","minimumMargin","blockingProperties","blockingDistanceMeters","allowSpatialIdentity"))
        if(weighted.containsKey(key))identity.put(key,weighted.get(key));
    }else if(policy.isEmpty()&&!resolution.isEmpty())identity.put("resolution",resolution);

    var fields=new LinkedHashMap<String,Object>();
    for(var raw:list(profile.get("inferred_schema"))){
      var column=object(raw);String field=(String)column.get("name");
      var property=properties.getOrDefault(field,Map.of());
      Object iri=property.get("targetPropertyIri");
      boolean included=projected.contains(field);
      var row=new LinkedHashMap<String,Object>();
      row.put("included",included);row.put("property",iri);row.put("transform",property.get("transform"));
      if(property.containsKey("transformParams"))row.put("transformParams",property.get("transformParams"));
      row.put("profileType",column.get("dataType"));row.put("sourceKey",keys.contains(field));
      row.put("signal",signals.contains(iri));
      Object label=included?labels.get(iri==null?field:iri):null;
      if(included&&iri==null&&child(config,"compositionInputLabels").containsKey(field))label=child(config,"compositionInputLabels").get(field);
      if(!included&&Objects.equals(excluded.get("profileId"),String.valueOf(profile.get("profile_id"))))
        label=child(excluded,"labels").get(field);
      row.put("access",label);
      var output=materialized.getOrDefault(iri,Map.of());
      for(String key:List.of("reconciliation","authorityOrder"))if(output.get(key)!=null)row.put(key,output.get(key));
      fields.put(field,row);
      if(iri!=null)addReference(semantics,config,bindings,iri,iri,null,"PROPERTY");
    }
    if(!fields.keySet().containsAll(projected)||!projected.containsAll(properties.keySet()))
      throw new DomainFailure(HttpStatus.CONFLICT,
          "ONB_REVIEW_CONFIGURATION_DRIFT","Projection and file profile disagree");
    var limits=new LinkedHashMap<String,Object>();
    for(String key:List.of("maxRows","maxColumns","maxCellChars"))limits.put(key,child(runtime,"execution").get(key));
    var file=new LinkedHashMap<String,Object>();
    file.put("name",asset.get("display_name"));file.put("assetId",asset.get("asset_id"));
    file.put("size",asset.get("size_bytes"));file.put("format",profile.get("format"));
    file.put("rows",child(profile,"metadata").get("rows"));
    if(file.get("rows")==null)file.put("rows",profile.get("rowCount")!=null?profile.get("rowCount"):profile.get("featureCount"));
    file.put("columns",fields.size());
    var snapshot=new LinkedHashMap<String,Object>();
    snapshot.put("sourceName",name);snapshot.put("owner",owner);snapshot.put("file",file);
    // JSONB objects have no insertion order. Keep the original header order separately.
    snapshot.put("bindings",bindings);
    snapshot.put("columnOrder",List.copyOf(fields.keySet()));
    var compositions=new LinkedHashMap<String,Object>();
    for(Object raw:list(mapping.get("compositions"))){
      var item=object(raw);String target=(String)item.get("targetPropertyIri");
      compositions.put(target,item);addReference(semantics,config,bindings,target,target,null,"PROPERTY");
    }
    if(!compositions.isEmpty())snapshot.put("COMPOSITION",compositions);
    var definitions=new LinkedHashMap<String,Object>();
    for(Object raw:bindings){
      var pin=object(raw);
      if(pin.containsKey("definitionSha256"))definitions.put(pin.get("semanticId")+"@"+pin.get("publicationSetId"),pin);
    }
    if(!definitions.isEmpty())snapshot.put("DATATYPE",definitions);
    snapshot.put("FIELD",fields);snapshot.put("IDENTITY",identity);snapshot.put("SEMANTIC",semantics);snapshot.put("LIMITS",limits);
    return snapshot;
  }
  private static void addReference(Map<String,Object> refs,Map<?,?> config,List<?> bindings,Object iri,Object ontology,Object version,String kind){
    if(!(iri instanceof String id))return;
    var description=child(child(config,"semanticReviewTerms"),id);
    var binding=child(description,"binding");
    var term=bindings.contains(binding)?child(description,"term"):Map.of();
    if(!bindings.contains(binding)){
      var matching=bindings.stream().map(ManagedReviewSnapshot::object)
          .filter(b->Objects.equals(b.get("semanticId"),ontology)&&(version==null||Objects.equals(b.get("semanticVersion"),version))).toList();
      binding=matching.size()==1?matching.getFirst():null;
    }
    var ref=new LinkedHashMap<String,Object>();
    ref.put("kind",kind);
    for(String key:List.of("labelIt","descriptionIt","license"))ref.put(key,term.get(key));
    if(binding!=null)for(String key:List.of("semanticId","semanticVersion","revisionId","publicationSetId"))ref.put(key,binding.get(key));
    refs.put(id,ref);
  }
  static List<Map<String,Object>> differences(Map<?,?> before,Map<?,?> after){
    var changes=new ArrayList<Map<String,Object>>();
    for(String section:List.of("FIELD","IDENTITY","SEMANTIC","LIMITS","COMPOSITION","DATATYPE")){
      var prior=child(before,section);var next=child(after,section);
      var targets=new LinkedHashSet<>(prior.keySet().stream().map(Object::toString).toList());
      next.keySet().forEach(key->targets.add(key.toString()));
      for(var target:targets)if(!Objects.equals(prior.get(target),next.get(target))||prior.containsKey(target)!=next.containsKey(target)){
        var change=new LinkedHashMap<String,Object>();change.put("section",section);change.put("target",target);
        change.put("before",prior.get(target));change.put("after",next.get(target));changes.add(change);
      }
    }
    return changes;
  }
}
