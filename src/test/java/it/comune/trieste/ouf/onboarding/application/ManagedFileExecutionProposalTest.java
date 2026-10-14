package it.comune.trieste.ouf.onboarding.application;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;

class ManagedFileExecutionProposalTest {
  private static final String ASSET="6609b245-86ed-4315-8ce0-73f2a8555bf3",PROFILE="7984c39c-7396-4248-ab0a-f2efc390b49c";
  private static final String REVISION="00000000-0000-4000-8000-000000000001",PUBLICATION="00000000-0000-4000-8000-000000000002";
  private static final String SOURCE="fixture-source",CLASS="https://schema.org/PerformingArtsTheater",NAME="https://schema.org/name",CAP="urn:fixture:sourcePostalCode",ONTOLOGY="urn:fixture:imported-ontology";
  private final ObjectMapper json=new ObjectMapper();
  @Test void importedOntologyCanContainExternalStandardTermsWithoutChangingOwnerIdentityOrProjection(){
    var existing=config();var before=clone(existing);var compiled=compile(existing,proposal());
    assertThat(existing).isEqualTo(before);
    for(String key:List.of("sourceObjectIdentityPolicy","semanticMapping","dataAccessPolicies","bundle","syncProfile","changeRepresentationProfile"))assertThat(compiled.get(key)).isEqualTo(existing.get(key));
    var extraction=object(compiled,"extractionProfile");
    assertThat(extraction.get("selection")).isEqualTo(object(existing,"extractionProfile").get("selection"));
    assertThat(extraction.get("projection")).isEqualTo(object(existing,"extractionProfile").get("projection"));
    var runtime=object(extraction,"runtime");
    assertThat(runtime).containsEntry("stagingRef","staging://fixture/source.csv").containsEntry("contentHash","sha256:fixture");
    assertThat(object(runtime,"execution")).containsEntry("acquisitionMode","INTERNAL_MANAGED_CSV").containsEntry("expectedSize",321L).containsEntry("semanticPublicationSetRef",PUBLICATION);
    assertThat(compiled).doesNotContainKeys("runtimeAuthorityIssued","compatibilityAttestation","approvedBy");
    assertThat((List<?>)compiled.get("semanticReferenceBindings")).hasSize(1);
  }
  @Test void assetOrTenantDriftCannotBeCompiled(){
    for(String key:List.of("asset_id","source_id","staging_ref","content_hash")){
      var changed=asset();changed.put(key,"changed");
      assertThatThrownBy(()->ManagedFileExecutionProposal.compile(config(),changed,"fixture-tenant",proposal())).hasMessage("ONB_MANAGED_EXECUTION_PROPOSAL_INVALID");
    }
    assertThatThrownBy(()->ManagedFileExecutionProposal.compile(config(),asset(),"other-tenant",proposal())).hasMessage("ONB_MANAGED_EXECUTION_PROPOSAL_INVALID");
  }
  @Test void everySemanticReferenceMustHaveOneExactBindingInTheSelectedSet(){
    var correct=proposal();
    for(var binding:List.of(new ManagedFileExecutionProposal.SemanticBinding(ONTOLOGY,"2",REVISION,PUBLICATION),
        new ManagedFileExecutionProposal.SemanticBinding(ONTOLOGY,"1","not-a-revision",PUBLICATION),
        new ManagedFileExecutionProposal.SemanticBinding(ONTOLOGY,"1",REVISION,REVISION))){
      var bad=new ManagedFileExecutionProposal.Proposal(List.of(binding),PUBLICATION,correct.udp(),correct.limits());
      assertThatThrownBy(()->compile(config(),bad)).hasMessage("ONB_MANAGED_EXECUTION_PROPOSAL_INVALID");
    }
    var bad=new ManagedFileExecutionProposal.Proposal(List.of(correct.semanticBindings().getFirst(),correct.semanticBindings().getFirst()),PUBLICATION,correct.udp(),correct.limits());
    assertThatThrownBy(()->compile(config(),bad)).hasMessage("ONB_MANAGED_EXECUTION_PROPOSAL_INVALID");
  }
  @Test void proposalCannotRelabelDataDropCanonicalPropertiesOrPretendCsvTypesAreConverted(){
    for(String change:List.of("label","datatype","drop","unknown")){
      var p=proposal();var properties=(List<Map<String,Object>>)object(p.udp(),"materialization").get("properties");
      if(change.equals("label"))properties.getFirst().put("accessLabel","OPEN");
      if(change.equals("datatype"))properties.getFirst().put("datatype","http://www.w3.org/2001/XMLSchema#integer");
      if(change.equals("drop"))properties.removeLast();
      if(change.equals("unknown"))p.udp().put("spatial",Map.of());
      assertThatThrownBy(()->compile(config(),p)).hasMessage("ONB_MANAGED_EXECUTION_PROPOSAL_INVALID");
    }
  }
  @Test void identityRuleAndSignalMustRemainScopedToAllOwnerPropertiesAndExactPublication(){
    var p=proposal();var policy=object(object(p.udp(),"resolution"),"governedIdentity");
    ((Map<String,Object>)((List<?>)policy.get("signals")).getFirst()).put("semanticRef",NAME+"@"+REVISION);
    assertThatThrownBy(()->compile(config(),p)).hasMessage("ONB_MANAGED_EXECUTION_PROPOSAL_INVALID");
    var other=proposal();object(object(other.udp(),"resolution"),"governedIdentity").put("sourceId","other-source");
    assertThatThrownBy(()->compile(config(),other)).hasMessage("ONB_GOVERNED_IDENTITY_INVALID");
  }
  @Test void boundedParserAndKnownDialectAreRequiredWithoutRereadingAssetBytes(){
    var p=proposal();var bad=new ManagedFileExecutionProposal.Proposal(p.semanticBindings(),PUBLICATION,p.udp(),new ManagedFileExecutionProposal.Limits(1_000_001,20,1000));
    assertThatThrownBy(()->compile(config(),bad)).hasMessage("ONB_MANAGED_EXECUTION_PROPOSAL_INVALID");
    var c=config();object(object(c,"extractionProfile"),"runtime").remove("csvDelimiter");
    assertThatThrownBy(()->compile(c,p)).hasMessage("ONB_MANAGED_EXECUTION_PROPOSAL_INVALID");
  }
  @Test void compiledProposalCopiesNestedPolicySoCallerCannotModifyReviewAfterCompilation(){
    var p=proposal();var compiled=compile(config(),p);p.udp().clear();
    assertThat(object(object(object(compiled,"extractionProfile"),"runtime"),"udp")).containsKeys("resolution","materialization");
  }
  private Map<String,Object> compile(Map<String,Object> c,ManagedFileExecutionProposal.Proposal p){return ManagedFileExecutionProposal.compile(c,asset(),"fixture-tenant",p);}
  private Map<String,Object> asset(){return new LinkedHashMap<>(Map.of("asset_id",UUID.fromString(ASSET),"source_id",SOURCE,"staging_ref","staging://fixture/source.csv","content_hash","sha256:fixture","size_bytes",321L));}
  private ManagedFileExecutionProposal.Proposal proposal(){
    var signals=new ArrayList<Map<String,Object>>();var props=new ArrayList<Map<String,Object>>();
    for(String iri:List.of(NAME,CAP)){
      signals.add(new LinkedHashMap<>(Map.of("id",iri,"semanticRef",iri+"@"+PUBLICATION,"comparator","TEXT_V1","excludesOnDisagreement",false,"uniqueWithinScope",false,"assertionRef","urn:fixture:reviewed-assertion")));
      props.add(new LinkedHashMap<>(Map.of("sourceField",iri,"propertyIri",iri,"datatype","http://www.w3.org/2001/XMLSchema#string","accessLabel","RESTRICTED","authorityOrder",List.of(SOURCE))));
    }
    var policy=new LinkedHashMap<String,Object>(Map.of("ref","urn:fixture:identity-policy","version","1","tenantId","fixture-tenant","canonicalClass",CLASS,"sourceId",SOURCE,"maxCandidates",10,"allowAutoNew",true,"signals",signals,"sufficientRules",List.of(Map.of("id","reviewed-pair","signalIds",List.of(NAME,CAP),"assertionRef","urn:fixture:rule-assertion"))));
    var udp=new LinkedHashMap<String,Object>(Map.of("resolution",new LinkedHashMap<>(Map.of("strategyId","GOVERNED_IDENTITY","strategyVersion","1","policyRef","urn:fixture:identity-policy","governedIdentity",policy)),"materialization",new LinkedHashMap<>(Map.of("policyRef","urn:fixture:authority-policy","properties",props,"bitemporalProperties",List.of(),"checkpointInterval",1))));
    return new ManagedFileExecutionProposal.Proposal(List.of(new ManagedFileExecutionProposal.SemanticBinding(ONTOLOGY,"1",REVISION,PUBLICATION)),PUBLICATION,udp,new ManagedFileExecutionProposal.Limits(100,20,1000));
  }
  private Map<String,Object> config(){
    Map<String,Object> runtime=Map.of("mode","MANAGED","recordModel","ONE_ROW_ONE_SOURCE_OBJECT","rowIdentityBasis","APPROVED_SOURCE_FIELDS","assetId",ASSET,"fileProfileId",PROFILE,"stagingRef","staging://fixture/source.csv","contentHash","sha256:fixture","csvDelimiter",";");
    var semantic=Map.of("mappingId","sm-"+SOURCE,"sourceType",Map.of("sourceId",SOURCE,"typeCode","FILE"),"targetClasses",List.of(Map.of("classIri",CLASS,"ontologyId",ONTOLOGY,"ontologyVersion","1")),"propertyMappings",List.of(Map.of("sourceField","nome_teatro","targetPropertyIri",NAME,"transform","IDENTITY"),Map.of("sourceField","cap","targetPropertyIri",CAP,"transform","IDENTITY")),"semanticRefs",List.of(ONTOLOGY+"@1"),"vocabularyMappings",List.of());
    return clone(Map.of("sourceObjectIdentityPolicy",Map.of("strategy","NATIVE_KEY","sourceFields",List.of("nome_teatro")),"syncProfile",Map.of("incremental","NONE"),"changeRepresentationProfile",Map.of("mode","FULL_SNAPSHOT"),"semanticMapping",semantic,"dataAccessPolicies",List.of(Map.of("scope","PROPERTY","target",NAME,"label","RESTRICTED"),Map.of("scope","PROPERTY","target",CAP,"label","RESTRICTED")),"extractionProfile",Map.of("sourceId",SOURCE,"version","1","selection",Map.of("assetId",ASSET,"fileProfileId",PROFILE),"projection",Map.of("FILE",List.of("nome_teatro","cap")),"runtime",runtime),"bundle",Map.of("source",Map.of("sourceId",SOURCE,"sourceKind","INTERNAL_MANAGED","acquisitionMode","MANAGED"),"schemaRef","profile://managed-files/"+ASSET+"/profiles/"+PROFILE)));
  }
  @SuppressWarnings("unchecked") private Map<String,Object> clone(Map<String,Object> c){return json.convertValue(c,Map.class);}
  @SuppressWarnings("unchecked") private static Map<String,Object> object(Map<String,Object> m,String key){return (Map<String,Object>)m.get(key);}
}
