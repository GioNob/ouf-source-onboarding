package it.comune.trieste.ouf.onboarding.application;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class GovernedIdentityProposalTest {
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
    policy.put("sufficientRules",List.of(Map.of("id","bad-rule","signalIds",List.of("urn:unknown"),
        "assertionRef","assertion://bad")));
    assertThatThrownBy(()->GovernedIdentityProposal.validate(resolution,policy,"source-a",
        Set.of("urn:Place"),Set.of("urn:name","urn:address")))
        .hasMessage("ONB_GOVERNED_IDENTITY_INVALID");
  }
}
