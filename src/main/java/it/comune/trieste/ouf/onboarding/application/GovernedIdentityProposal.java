package it.comune.trieste.ouf.onboarding.application;

import java.net.URI;
import java.util.*;

/** Structural preactivation check; assertion provenance and UDP execution remain separate gates. */
record GovernedIdentityProposal(Map<String,String> declaredDatatypes, String publication) {
  private static final String RESOLUTION="/executionProposal/udp/resolution";
  private static final String POLICY=RESOLUTION+"/governedIdentity";
  private static final String SIGNALS=POLICY+"/signals";
  private static final Set<String> RESOLUTION_FIELDS=Set.of("strategyId","strategyVersion","policyRef","governedIdentity");
  private static final Set<String> POLICY_FIELDS=Set.of("ref","version","tenantId","canonicalClass","sourceId",
      "maxCandidates","allowAutoNew","signals","sufficientRules");
  private static final Set<String> SIGNAL_FIELDS=Set.of("id","semanticRef","comparator",
      "excludesOnDisagreement","uniqueWithinScope","assertionRef");
  private static final Set<String> RULE_FIELDS=Set.of("id","signalIds","assertionRef");
  private static final Set<String> COMPARATORS=Set.of("CONCEPT","TEXT_V1","DECIMAL_V1","JSON_V1");
  private static final String ERROR="ONB_GOVERNED_IDENTITY_INVALID";

  // Configuration findings only check structure and do not require a managed-file publication.
  static void validate(Map<String,Object> resolution, Object policy, String sourceId,
                       Set<String> targetClasses, Set<String> mapped) {
    new GovernedIdentityProposal(Map.of(),null).validateStructure(resolution,policy,sourceId,targetClasses,mapped);
  }

  void validateExecution(Object raw, Object targets, String sourceId, Set<String> classes, String tenant) {
    if (!(raw instanceof Map<?,?> resolution)) throw invalid("RESOLUTION_SHAPE",RESOLUTION);
    Object rawPolicy=resolution.get("governedIdentity");
    if (rawPolicy instanceof Map<?,?> policy) MappingCategory.validate(targets,policy);
    var policy=validateStructure(resolution,rawPolicy,sourceId,classes,declaredDatatypes.keySet());
    if (!tenant.equals(policy.get("tenantId"))) throw invalid("POLICY_TENANT",POLICY+"/tenantId");
    // Exact pins follow all structural checks, including rules and tenant ownership.
    var signals=(List<?>)policy.get("signals");
    for (int i=0;i<signals.size();i++) {
      var signal=(Map<?,?>)signals.get(i);
      String id=(String)signal.get("id"), path=SIGNALS+"/"+i;
      if (!(id+"@"+publication).equals(signal.get("semanticRef")))
        throw signalFailure("SIGNAL_SEMANTIC_REF",path+"/semanticRef",Set.of(id));
      if (!ValueReader.comparator(declaredDatatypes.get(id)).equals(signal.get("comparator")))
        throw signalFailure("SIGNAL_COMPARATOR",path+"/comparator",Set.of(id));
    }
  }

  private Map<?,?> validateStructure(Map<?,?> resolution, Object raw, String sourceId,
                                    Set<String> targetClasses, Set<String> mapped) {
    if (!resolution.keySet().equals(RESOLUTION_FIELDS)) throw invalid("RESOLUTION_SHAPE",RESOLUTION);
    if (!"GOVERNED_IDENTITY".equals(resolution.get("strategyId"))) throw invalid("RESOLUTION_SHAPE",RESOLUTION+"/strategyId");
    if (blank(resolution.get("strategyVersion"))) throw invalid("RESOLUTION_SHAPE",RESOLUTION+"/strategyVersion");
    if (!(raw instanceof Map<?,?> policy)) throw invalid("POLICY_FIELDS",POLICY);
    var fields=new HashSet<>(policy.keySet());
    fields.removeAll(Set.of("distinctRules","category"));
    if (!fields.equals(POLICY_FIELDS)) throw invalid("POLICY_FIELDS",POLICY);
    if (blank(policy.get("ref"))) throw invalid("POLICY_REFERENCE",POLICY+"/ref");
    if (blank(policy.get("version"))) throw invalid("POLICY_REFERENCE",POLICY+"/version");
    if (blank(policy.get("tenantId"))) throw invalid("POLICY_TENANT",POLICY+"/tenantId");
    if (!Objects.equals(policy.get("ref"),resolution.get("policyRef"))) throw invalid("POLICY_REFERENCE",POLICY+"/ref");
    if (!Objects.equals(policy.get("version"),resolution.get("strategyVersion"))) throw invalid("POLICY_REFERENCE",POLICY+"/version");
    if (!Objects.equals(policy.get("sourceId"),sourceId)) throw invalid("POLICY_SOURCE",POLICY+"/sourceId");
    if (!(policy.get("canonicalClass") instanceof String iri) || !targetClasses.contains(iri))
      throw invalid("POLICY_CLASS",POLICY+"/canonicalClass");
    if (!(policy.get("allowAutoNew") instanceof Boolean)) throw invalid("POLICY_BOUNDS",POLICY+"/allowAutoNew");
    if (!(policy.get("maxCandidates") instanceof Number max) || max.doubleValue()!=max.intValue()
        || max.intValue()<1 || max.intValue()>1000) throw invalid("POLICY_BOUNDS",POLICY+"/maxCandidates");
    var signals=boundedList(policy,"signals",1,ManagedFileLimits.MAX_COLUMNS);
    var sufficient=boundedList(policy,"sufficientRules",1,32);
    Set<String> compared=new HashSet<>();
    for (int i=0;i<signals.size();i++) validateSignal(signals.get(i),SIGNALS+"/"+i,mapped,compared);
    if (!compared.equals(mapped)) {
      var missing=new HashSet<>(mapped);
      missing.removeAll(compared);
      throw signalFailure("SIGNALS_INCOMPLETE",SIGNALS,missing);
    }
    Set<String> ids=new HashSet<>();
    validateRules(sufficient,"sufficientRules",compared,ids);
    if (policy.containsKey("distinctRules"))
      validateRules(boundedList(policy,"distinctRules",0,32),"distinctRules",compared,ids);
    return policy;
  }

  private void validateSignal(Object item, String path, Set<String> mapped, Set<String> compared) {
    if (!(item instanceof Map<?,?> signal) || !signal.keySet().equals(SIGNAL_FIELDS)) throw invalid("SIGNAL_SHAPE",path);
    if (!(signal.get("id") instanceof String id) || id.isBlank()) throw invalid("SIGNAL_SHAPE",path+"/id");
    var property=Set.of(id);
    if (blank(signal.get("semanticRef"))) throw signalFailure("SIGNAL_SEMANTIC_REF",path+"/semanticRef",property);
    if (!mapped.contains(id)) throw signalFailure("SIGNAL_NOT_MAPPED",path+"/id",property);
    if (!((String)signal.get("semanticRef")).matches("[^@\\s]+@[^@\\s]+"))
      throw signalFailure("SIGNAL_SEMANTIC_REF",path+"/semanticRef",property);
    if (blank(signal.get("assertionRef"))) throw invalid("SIGNAL_ASSERTION_REF",path+"/assertionRef");
    if (!(signal.get("comparator") instanceof String comparator) || !COMPARATORS.contains(comparator))
      throw signalFailure("SIGNAL_COMPARATOR",path+"/comparator",property);
    if (!Boolean.FALSE.equals(signal.get("uniqueWithinScope")))
      throw signalFailure("SIGNAL_FLAGS",path+"/uniqueWithinScope",property);
    if (!Boolean.FALSE.equals(signal.get("excludesOnDisagreement")))
      throw signalFailure("SIGNAL_FLAGS",path+"/excludesOnDisagreement",property);
    if (!compared.add(id)) throw signalFailure("SIGNAL_DUPLICATE",path+"/id",property);
  }

  private static void validateRules(List<?> rules, String field, Set<String> compared, Set<String> ids) {
    boolean distinct=field.equals("distinctRules");
    for (int i=0;i<rules.size();i++) {
      String path=POLICY+"/"+field+"/"+i;
      if (!(rules.get(i) instanceof Map<?,?> rule) || !rule.keySet().equals(RULE_FIELDS)) throw invalid("RULE_SHAPE",path);
      if (!(rule.get("id") instanceof String id) || id.isBlank()) throw invalid("RULE_SHAPE",path+"/id");
      if (!ids.add(id)) throw ruleFailure("RULE_DUPLICATE",path+"/id",id);
      if (blank(rule.get("assertionRef"))) throw invalid("RULE_SHAPE",path+"/assertionRef");
      if (!(rule.get("signalIds") instanceof List<?> members) || members.size()<(distinct?2:1) || members.size()>32
          || new HashSet<>(members).size()!=members.size() || !compared.containsAll(members))
        throw ruleFailure(distinct?"DISTINCT_RULE_MEMBERS":"RULE_MEMBERS",path+"/signalIds",id);
    }
  }

  private static List<?> boundedList(Map<?,?> policy, String field, int min, int max) {
    if (!(policy.get(field) instanceof List<?> list) || list.size()<min || list.size()>max)
      throw invalid("POLICY_BOUNDS",POLICY+"/"+field);
    return list;
  }

  private static boolean blank(Object value) { return !(value instanceof String s) || s.isBlank(); }

  private static ReviewValidationFailure invalid(String constraint, String path) {
    return new ReviewValidationFailure(ERROR,Map.of("constraint",constraint,"path",path));
  }

  private static ReviewValidationFailure ruleFailure(String constraint, String path, String id) {
    // Rule IDs are caller-supplied. Do not echo prose placed in an ID field.
    boolean token=id.codePoints().noneMatch(c->Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c));
    var safeIds=token ? List.of(id) : List.of();
    return new ReviewValidationFailure(ERROR,Map.of("constraint",constraint,"path",path,"ruleIds",safeIds));
  }

  private ReviewValidationFailure signalFailure(String constraint, String path, Set<String> involved) {
    var iris=involved.stream().filter(GovernedIdentityProposal::absoluteIri).sorted().limit(64).toList();
    var properties=new LinkedHashMap<String,Object>();
    properties.put("constraint",constraint);
    properties.put("path",path);
    properties.put("propertyIris",iris);
    properties.put("propertyCount",involved.size());
    if (Set.of("SIGNALS_INCOMPLETE","SIGNAL_COMPARATOR","SIGNAL_SEMANTIC_REF").contains(constraint)) {
      var expected=iris.stream().filter(declaredDatatypes::containsKey)
          .map(id->Map.of("id",id,"semanticRef",id+"@"+publication,
              "comparator",ValueReader.comparator(declaredDatatypes.get(id)))).toList();
      properties.put("expectedSignals",expected);
    }
    return new ReviewValidationFailure(ERROR,properties);
  }

  private static boolean absoluteIri(String id) {
    try { return URI.create(id).isAbsolute(); }
    catch (IllegalArgumentException malformed) { return false; }
  }
}
