package it.comune.trieste.ouf.onboarding.application;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class GovernedIdentityProposalTest {
  private static final String ROOT="/executionProposal/udp/resolution", POLICY=ROOT+"/governedIdentity";
  private static final String NAME="urn:fixture:officialName", LAT="urn:fixture:latitude", LON="urn:fixture:longitude";
  private static final String SET="00000000-0000-4000-8000-000000000002";
  private static final String PRIVATE="private CSV cell text";
  private final Map<String,String> types=new HashMap<>(Map.of(NAME,ValueReader.DATATYPES.get("string"),
      LAT,ValueReader.DATATYPES.get("decimal"),LON,ValueReader.DATATYPES.get("decimal")));
  private final Map<String,Object> first=new HashMap<>(Map.of("id",NAME,"semanticRef",NAME+"@"+SET,"comparator","TEXT_V1",
      "assertionRef","urn:fixture:assertion","uniqueWithinScope",false,"excludesOnDisagreement",false));
  private final Map<String,Object> rule=new HashMap<>(Map.of("id","by-name","signalIds",List.of(NAME),"assertionRef","urn:fixture:rule"));
  private final List<Map<String,Object>> signals=new ArrayList<>(List.of(first));
  private final Map<String,Object> policy=new HashMap<>(Map.of("ref","urn:fixture:policy","version","1","sourceId","source",
      "tenantId","tenant","canonicalClass","urn:Place","allowAutoNew",false,"maxCandidates",5,"signals",signals,
      "sufficientRules",List.of(rule)));
  private final Map<String,Object> resolution=new HashMap<>(Map.of("strategyId","GOVERNED_IDENTITY","strategyVersion","1",
      "policyRef","urn:fixture:policy","governedIdentity",policy));

  GovernedIdentityProposalTest() {
    for (String id:List.of(LON,LAT)) {
      var signal=new HashMap<>(first);
      signal.put("id",id);
      signal.put("semanticRef",id+"@"+SET);
      signal.put("comparator","DECIMAL_V1");
      signals.add(signal);
    }
  }

  private void validateExecution() {
    new GovernedIdentityProposal(types,SET).validateExecution(resolution,List.of(),"source",Set.of("urn:Place"),"tenant");
  }

  private static Arguments violation(String constraint,String path,Consumer<GovernedIdentityProposalTest> change) {
    return Arguments.of(constraint,path,change);
  }

  static Stream<Arguments> violations() {
    return Stream.of(
        violation("RESOLUTION_SHAPE",ROOT,t->t.resolution.put(PRIVATE,true)),
        violation("RESOLUTION_SHAPE",ROOT+"/strategyId",t->t.resolution.put("strategyId",PRIVATE)),
        violation("POLICY_FIELDS",POLICY,t->t.policy.remove("signals")),
        violation("POLICY_FIELDS",POLICY,t->t.policy.put(PRIVATE,true)),
        violation("POLICY_REFERENCE",POLICY+"/ref",t->t.policy.put("ref",PRIVATE)),
        violation("POLICY_REFERENCE",POLICY+"/version",t->t.policy.put("version","2")),
        violation("POLICY_SOURCE",POLICY+"/sourceId",t->t.policy.put("sourceId",PRIVATE)),
        violation("POLICY_TENANT",POLICY+"/tenantId",t->t.policy.put("tenantId"," ")),
        violation("POLICY_TENANT",POLICY+"/tenantId",t->t.policy.put("tenantId",PRIVATE)),
        violation("POLICY_CLASS",POLICY+"/canonicalClass",t->t.policy.put("canonicalClass",PRIVATE)),
        violation("POLICY_BOUNDS",POLICY+"/allowAutoNew",t->t.policy.put("allowAutoNew",1)),
        violation("POLICY_BOUNDS",POLICY+"/maxCandidates",t->t.policy.put("maxCandidates",1001)),
        violation("POLICY_BOUNDS",POLICY+"/signals",t->t.signals.clear()),
        violation("POLICY_BOUNDS",POLICY+"/signals",t->t.policy.put("signals",Collections.nCopies(1025,t.first))),
        violation("POLICY_BOUNDS",POLICY+"/sufficientRules",t->t.policy.put("sufficientRules",List.of())),
        violation("POLICY_BOUNDS",POLICY+"/distinctRules",t->t.policy.put("distinctRules",Collections.nCopies(33,t.rule))),
        violation("SIGNAL_SHAPE",POLICY+"/signals/0",t->t.first.remove("assertionRef")),
        violation("SIGNAL_NOT_MAPPED",POLICY+"/signals/0/id",t->t.first.put("id","urn:fixture:unknown")),
        violation("SIGNAL_DUPLICATE",POLICY+"/signals/3/id",t->t.signals.add(t.first)),
        violation("SIGNAL_FLAGS",POLICY+"/signals/0/uniqueWithinScope",t->t.first.put("uniqueWithinScope",true)),
        violation("SIGNAL_FLAGS",POLICY+"/signals/0/excludesOnDisagreement",t->t.first.put("excludesOnDisagreement",true)),
        violation("SIGNAL_COMPARATOR",POLICY+"/signals/0/comparator",t->t.first.put("comparator",PRIVATE)),
        violation("SIGNAL_COMPARATOR",POLICY+"/signals/0/comparator",t->t.first.put("comparator","DECIMAL_V1")),
        violation("SIGNAL_SEMANTIC_REF",POLICY+"/signals/0/semanticRef",t->t.first.put("semanticRef",PRIVATE)),
        violation("SIGNAL_SEMANTIC_REF",POLICY+"/signals/0/semanticRef",t->t.first.put("semanticRef",NAME+"@other")),
        violation("SIGNAL_ASSERTION_REF",POLICY+"/signals/0/assertionRef",t->t.first.put("assertionRef","")),
        violation("SIGNALS_INCOMPLETE",POLICY+"/signals",t->t.policy.put("signals",List.of(t.first))),
        violation("RULE_SHAPE",POLICY+"/sufficientRules/0",t->t.rule.remove("id")),
        violation("RULE_DUPLICATE",POLICY+"/distinctRules/0/id",t->t.policy.put("distinctRules",List.of(t.rule))),
        violation("RULE_MEMBERS",POLICY+"/sufficientRules/0/signalIds",t->t.rule.put("signalIds",List.of(PRIVATE))),
        violation("RULE_MEMBERS",POLICY+"/sufficientRules/0/signalIds",t->t.rule.put("signalIds",List.of(NAME,NAME))),
        violation("DISTINCT_RULE_MEMBERS",POLICY+"/distinctRules/0/signalIds",t->t.policy.put("distinctRules",
            List.of(Map.of("id","different","signalIds",List.of(NAME),"assertionRef","urn:fixture:rule"))))
    );
  }

  @ParameterizedTest(name="{0} at {1}") @MethodSource("violations")
  void task59bConstraintAndPath(String constraint,String path,Consumer<GovernedIdentityProposalTest> change) {
    change.accept(this);
    var failure=assertThrows(ReviewValidationFailure.class,this::validateExecution);
    assertThat(failure).hasMessage("ONB_GOVERNED_IDENTITY_INVALID");
    assertThat(failure.status().value()).isEqualTo(422);
    assertThat(failure.code()).isEqualTo("ONB_GOVERNED_IDENTITY_INVALID");
    var expected=new HashMap<String,Object>(Map.of("constraint",constraint,"path",path));
    switch (constraint) {
      case "SIGNAL_NOT_MAPPED","SIGNAL_DUPLICATE","SIGNAL_FLAGS","SIGNAL_COMPARATOR","SIGNAL_SEMANTIC_REF" -> {
        expected.put("propertyCount",1);
        expected.put("propertyIris",List.of(constraint.equals("SIGNAL_NOT_MAPPED")?"urn:fixture:unknown":NAME));
        if (constraint.equals("SIGNAL_COMPARATOR") || constraint.equals("SIGNAL_SEMANTIC_REF"))
          expected.put("expectedSignals",List.of(expectedSignal(NAME,"TEXT_V1")));
      }
      case "SIGNALS_INCOMPLETE" -> {
        expected.put("propertyCount",2);
        expected.put("propertyIris",List.of(LAT,LON));
        expected.put("expectedSignals",List.of(expectedSignal(LAT,"DECIMAL_V1"),expectedSignal(LON,"DECIMAL_V1")));
      }
      case "RULE_DUPLICATE","RULE_MEMBERS" -> expected.put("ruleIds",List.of("by-name"));
      case "DISTINCT_RULE_MEMBERS" -> expected.put("ruleIds",List.of("different"));
    }
    assertThat(failure.properties()).isEqualTo(expected);
  }

  private static Map<String,String> expectedSignal(String id,String comparator) {
    return Map.of("id",id,"semanticRef",id+"@"+SET,"comparator",comparator);
  }

  @Test void task59bMissingSignalsAreSortedBeforeTruncationAndCountedInFull() {
    for (int n=80;n>=1;n--) types.put("urn:fixture:p"+n,ValueReader.DATATYPES.get("decimal"));
    var ids=types.keySet().stream().filter(id->id.startsWith("urn:fixture:p")).sorted().limit(64).toList();
    assertThatThrownBy(this::validateExecution).isInstanceOfSatisfying(ReviewValidationFailure.class,
        failure->assertThat(failure.properties()).isEqualTo(Map.of("constraint","SIGNALS_INCOMPLETE","path",POLICY+"/signals",
            "propertyCount",80,"propertyIris",ids,"expectedSignals",ids.stream().map(id->expectedSignal(id,"DECIMAL_V1")).toList())));
  }

  @Test void task59bStructuralDefectsPrecedeTenantMismatchAndExactPins() {
    first.put("semanticRef",NAME+"@wrong");
    policy.put("tenantId","other");
    rule.put("signalIds",List.of());
    assertThatThrownBy(this::validateExecution).isInstanceOfSatisfying(ReviewValidationFailure.class,
        failure->assertThat(failure.properties()).containsEntry("constraint","RULE_MEMBERS"));
    rule.put("signalIds",List.of(NAME));
    assertThatThrownBy(this::validateExecution).isInstanceOfSatisfying(ReviewValidationFailure.class,
        failure->assertThat(failure.properties()).containsEntry("constraint","POLICY_TENANT"));
  }

  @Test void task59bPlainTextInIdentifierFieldsStaysOutOfDiagnostics() {
    first.put("id",PRIVATE);
    var property=assertThrows(ReviewValidationFailure.class,this::validateExecution);
    assertThat(property.properties()).isEqualTo(Map.of("constraint","SIGNAL_NOT_MAPPED","path",POLICY+"/signals/0/id",
        "propertyCount",1,"propertyIris",List.of()));
    first.put("id",NAME);
    rule.put("id",PRIVATE);
    rule.put("signalIds",List.of());
    var badRule=assertThrows(ReviewValidationFailure.class,this::validateExecution);
    assertThat(badRule.properties()).isEqualTo(Map.of("constraint","RULE_MEMBERS","path",POLICY+"/sufficientRules/0/signalIds",
        "ruleIds",List.of()));
  }

  @Test void explicitSufficientRuleMayRequireAProperSubsetOfMappedFields() {
    var signalA=Map.of("id","urn:name","semanticRef","urn:name@set-1","comparator","TEXT_V1",
        "uniqueWithinScope",false,"excludesOnDisagreement",false,"assertionRef","assertion://name");
    var signalB=Map.of("id","urn:address","semanticRef","urn:address@set-1","comparator","TEXT_V1",
        "uniqueWithinScope",false,"excludesOnDisagreement",false,"assertionRef","assertion://address");
    Map<String,Object> policy=new LinkedHashMap<>();
    policy.put("ref","identity://policy/1");policy.put("version","1");
    policy.put("tenantId","tenant-a");policy.put("canonicalClass","urn:Place");
    policy.put("sourceId","source-a");policy.put("maxCandidates",10);
    policy.put("allowAutoNew",true);policy.put("signals",List.of(signalA,signalB));
    policy.put("sufficientRules",List.of(Map.of("id","name-rule","signalIds",List.of("urn:name"),
        "assertionRef","assertion://name-sufficient")));
    var resolution=Map.<String,Object>of("strategyId","GOVERNED_IDENTITY","strategyVersion","1",
        "policyRef","identity://policy/1","governedIdentity",policy);
    assertThatCode(()->GovernedIdentityProposal.validate(resolution,policy,"source-a",
        Set.of("urn:Place"),Set.of("urn:name","urn:address"))).doesNotThrowAnyException();
    policy.put("distinctRules",List.of(Map.of("id","different-name-and-address","signalIds",List.of("urn:name","urn:address"),"assertionRef","assertion://distinct")));
    assertThatCode(()->GovernedIdentityProposal.validate(resolution,policy,"source-a",Set.of("urn:Place"),Set.of("urn:name","urn:address"))).doesNotThrowAnyException();
    policy.put("distinctRules",List.of(Map.of("id","weak","signalIds",List.of("urn:name"),"assertionRef","assertion://weak")));
    assertThatThrownBy(()->GovernedIdentityProposal.validate(resolution,policy,"source-a",Set.of("urn:Place"),Set.of("urn:name","urn:address"))).hasMessage("ONB_GOVERNED_IDENTITY_INVALID");
    policy.remove("distinctRules");
    policy.put("sufficientRules",List.of(Map.of("id","bad-rule","signalIds",List.of("urn:unknown"),
        "assertionRef","assertion://bad")));
    assertThatThrownBy(()->GovernedIdentityProposal.validate(resolution,policy,"source-a",
        Set.of("urn:Place"),Set.of("urn:name","urn:address")))
        .hasMessage("ONB_GOVERNED_IDENTITY_INVALID");
  }
}

