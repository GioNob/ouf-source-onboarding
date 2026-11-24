package it.comune.trieste.ouf.onboarding.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Request-scoped join of the frozen mapping to exact, active Registry revisions. */
final class PublishedReviewTerms {
  private final Map<String,String> required=new LinkedHashMap<>();
  private final Map<String,JsonNode> pins=new LinkedHashMap<>();
  private final Set<String> ambiguous=new HashSet<>();
  private final ObjectNode verified;
  private boolean valid=true;
  private boolean registryRead;

  PublishedReviewTerms(ObjectNode card){
    verified=card.objectNode();
    var config=card.path("configuration");
    var mapping=config.path("semanticMapping");
    requireMapping(mapping.path("targetClasses"),"classIri","CLASS");
    requireMapping(mapping.path("propertyMappings"),"targetPropertyIri","PROPERTY");
    if(mapping.has("compositions"))requireMapping(mapping.path("compositions"),"targetPropertyIri","PROPERTY");
    var bindings=config.path("semanticReferenceBindings");
    if(!bindings.isArray()){valid=false;return;}
    var pinnedIris=new HashSet<String>();
    for(var binding:bindings){
      var id=text(binding,"revisionId");
      if(!id.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
          ||text(binding,"semanticId").isBlank()||text(binding,"semanticVersion").isBlank()
          ||text(binding,"publicationSetId").isBlank()){valid=false;return;}
      var prior=pins.putIfAbsent(id,binding);
      if(pins.size()>64){valid=false;return;}
      if(prior!=null&&!prior.equals(binding)){valid=false;return;}
      if(prior==null&&!pinnedIris.add(text(binding,"semanticId")))ambiguous.add(text(binding,"semanticId"));
    }
  }

  private void requireMapping(JsonNode rows,String key,String kind){
    if(!rows.isArray()){valid=false;return;}
    for(var row:rows){
      String iri=text(row,key);
      String other=required.put(iri,kind);
      if(iri.isBlank()||other!=null&&!other.equals(kind))valid=false;
    }
  }

  boolean canLookUp(){return valid&&!pins.isEmpty()&&!required.isEmpty();}
  String revisionIds(){return String.join(",",pins.keySet());}

  void read(JsonNode response){
    if(response==null||!response.path("terms").isArray()||!response.path("missing").isArray())return;
    registryRead=true;
    Set<String> missing=new HashSet<>(),seen=new HashSet<>();
    for(var id:response.get("missing"))missing.add(id.textValue());
    for(var term:response.get("terms")){
      String revision=text(term,"revisionId"),iri=text(term,"semanticId");
      var pin=pins.get(revision);
      // Revisions are immutable and publication sets are cumulative: a later publication moves an
      // unchanged revision into a newer set, so the pinned set is not compared with the current one.
      if(pin==null||missing.contains(revision)||ambiguous.contains(iri)||!iri.equals(text(pin,"semanticId"))
          ||!text(pin,"semanticVersion").equals(text(term,"semanticVersion"))
          ||!"ACTIVE".equals(text(term,"status"))||!text(term,"artifactType").equals(required.get(iri)))continue;
      // Even for a unique binding, the response must identify just one term.
      if(!seen.add(iri)){verified.remove(iri);continue;}
      if(text(term.path("label"),"it").isBlank())continue;
      var entry=verified.putObject(iri);
      for(String key:List.of("artifactType","revisionId","semanticVersion","publicationSetId","license","licenseWarning"))
        entry.put(key,text(term,key));
      entry.put("labelIt",text(term.path("label"),"it"));
      entry.put("descriptionIt",text(term.path("description"),"it"));
      var publisher=text(term,"publisher");
      entry.put("publisher",publisher.isBlank()?text(term,"authority_ref"):publisher);
    }
  }

  boolean attachTo(ObjectNode card){
    boolean complete=valid&&verified.size()==required.size();
    card.set("verifiedTerms",verified);
    card.put("termVerification",complete?"COMPLETE":canLookUp()&&!registryRead?"UNAVAILABLE":"INCOMPLETE");
    return complete;
  }

  private static String text(JsonNode node,String field){
    var value=node.path(field).textValue();
    return value==null||value.isBlank()?"":value;
  }
}
