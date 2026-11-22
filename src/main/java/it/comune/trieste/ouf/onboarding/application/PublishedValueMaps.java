package it.comune.trieste.ouf.onboarding.application;

import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import java.util.*;
import static it.comune.trieste.ouf.onboarding.application.DatatypeResolver.unresolvedValueMap;
import static it.comune.trieste.ouf.onboarding.application.ValueCompositions.*;

final class PublishedValueMaps {
  private static final int MAX_DEFINITION_BYTES=786_432;
  static final int MAX_BUNDLE_BYTES=1_048_576;
  private PublishedValueMaps(){}

  static String coordinate(String ref,Map<String,Map<String,Object>> pins){
    if(pins.containsKey(ref))return ref;
    // Legacy mapping aliases may name the artifact alone, when the binding is unique.
    var matches=pins.entrySet().stream().filter(e->ref.equals(e.getValue().get("semanticId"))).toList();
    if(matches.size()!=1)throw unresolvedValueMap();
    return matches.getFirst().getKey();
  }

  static String vocabularyId(String ref){
    int at=ref.lastIndexOf('@');
    require(at>0 && ref.substring(at+1).matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"));
    return iri(ref.substring(0,at));
  }

  static int verify(String ref,Map<String,Object> pin,Map<String,Object> actual,CanonicalHash hashes){
    try{
      require("VALUE_MAP".equals(actual.get("artifact_type")) && "ACTIVE".equals(actual.get("status")));
      var definition=map(actual.get("definition"));
      keys(definition,Set.of("targetVocabulary","entries"),Set.of("labels"));
      String vocabulary=text(definition,"targetVocabulary");
      vocabularyId(vocabulary);
      if(definition.containsKey("labels")){
        var labels=map(definition.get("labels"));
        require(Set.of("it","en").containsAll(labels.keySet()) && labels.values().stream().allMatch(String.class::isInstance));
      }
      String previous=null;
      for(Object raw:list(definition.get("entries"),1,2000)){
        var entry=map(raw);
        keys(entry,Set.of("code","concept"),Set.of());
        if(!(entry.get("code") instanceof String code))throw unresolvedValueMap();
        require(!code.isEmpty() && code.codePointCount(0,code.length())<=128 && code.equals(CellWhitespace.trim(code))
            && code.chars().noneMatch(Character::isISOControl)
            && (previous==null || previous.compareTo(code)<0));
        String concept=iri(text(entry,"concept"));
        require(concept.codePointCount(0,concept.length())<=512);
        previous=code;
      }
      String digest=text(actual,"definitionSha256");
      byte[] canonical=hashes.definitionBytes(definition);
      require(canonical.length<=MAX_DEFINITION_BYTES && hashes.ofBytes(canonical).substring(7).equals(digest));
      pin.put("valueMapRef",ref);
      pin.put("targetVocabulary",vocabulary);
      pin.put("definition",definition);
      pin.put("definitionSha256",digest);
      if(actual.get("label") instanceof Map<?,?> label && label.values().stream().allMatch(String.class::isInstance))
        pin.put("label",label);
      return canonical.length;
    }catch(IllegalArgumentException invalid){throw unresolvedValueMap();}
  }
}
