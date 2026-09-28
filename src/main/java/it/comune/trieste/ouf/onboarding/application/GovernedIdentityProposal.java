package it.comune.trieste.ouf.onboarding.application;

import java.util.*;

/** Structural preactivation check; assertion provenance and UDP execution remain separate gates. */
final class GovernedIdentityProposal {
  private static final Set<String> POLICY=Set.of("ref","version","tenantId","canonicalClass","sourceId",
      "maxCandidates","allowAutoNew","signals","sufficientRules");
  private static final Set<String> SIGNAL=Set.of("id","semanticRef","comparator",
      "excludesOnDisagreement","uniqueWithinScope","assertionRef");
  private static final Set<String> RULE=Set.of("id","signalIds","assertionRef");
  private static final Set<String> COMPARATORS=Set.of("CONCEPT","TEXT_V1","DECIMAL_V1");
  private GovernedIdentityProposal() {}

  static void validate(Map<String,Object> resolution, Object raw, String sourceId,
                       Set<String> targetClasses, Set<String> mappedProperties) {
    if (!resolution.keySet().equals(Set.of("strategyId","strategyVersion","policyRef","governedIdentity"))
        || !"GOVERNED_IDENTITY".equals(resolution.get("strategyId"))
        || blank(resolution.get("strategyVersion"))) throw invalid();
    Map<?,?> policy=map(raw, POLICY);
    if (blank(policy.get("ref")) || blank(policy.get("version")) || blank(policy.get("tenantId"))
        || !Objects.equals(policy.get("ref"),resolution.get("policyRef"))
        || !Objects.equals(policy.get("version"),resolution.get("strategyVersion"))
        || !Objects.equals(policy.get("sourceId"),sourceId)
        || !targetClasses.contains(policy.get("canonicalClass"))
        || !(policy.get("allowAutoNew") instanceof Boolean)
        || !(policy.get("maxCandidates") instanceof Number max) || max.doubleValue()!=max.intValue()
        || max.intValue()<1 || max.intValue()>1000) throw invalid();
    List<?> signals=list(policy.get("signals"),1,32),rules=list(policy.get("sufficientRules"),1,1);
    Set<String> compared=new HashSet<>();
    for(Object item:signals){
      Map<?,?> signal=map(item,SIGNAL);
      String id=string(signal.get("id"));
      String ref=string(signal.get("semanticRef"));
      if(!mappedProperties.contains(id) || !ref.matches("[^@\\s]+@[^@\\s]+")
          || blank(signal.get("assertionRef"))
          || !COMPARATORS.contains(signal.get("comparator"))
          || !Boolean.FALSE.equals(signal.get("uniqueWithinScope"))
          || !Boolean.FALSE.equals(signal.get("excludesOnDisagreement"))
          || !compared.add(id))throw invalid();
    }
    if(!compared.equals(mappedProperties))throw invalid();
    Set<String> ids=new HashSet<>();
    for(Object item:rules){
      Map<?,?> rule=map(item,RULE);
      String id=string(rule.get("id"));
      if(!ids.add(id) || blank(rule.get("assertionRef")))throw invalid();
      List<?> members=list(rule.get("signalIds"),1,32);
      if(new HashSet<>(members).size()!=members.size() || !compared.equals(new HashSet<>(members)))throw invalid();
    }
  }
  private static Map<?,?> map(Object value,Set<String> fields){
    if(!(value instanceof Map<?,?> map) || !map.keySet().equals(fields))throw invalid();return map;
  }
  private static List<?> list(Object value,int min,int max){
    if(!(value instanceof List<?> list)||list.size()<min||list.size()>max)throw invalid();return list;
  }
  private static String string(Object value){if(!(value instanceof String s)||s.isBlank())throw invalid();return s;}
  private static boolean blank(Object value){return !(value instanceof String s)||s.isBlank();}
  private static IllegalArgumentException invalid(){return new IllegalArgumentException("ONB_GOVERNED_IDENTITY_INVALID");}
}
