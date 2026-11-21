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
  @Test void selectedSheetAndLayerCompileForTheVerifiedIngestionAdapters() {
    for(boolean layer:List.of(false,true)) {
      var c=config();var a=asset();var p=proposal();
      var runtime=object(object(c,"extractionProfile"),"runtime");
      runtime.remove("csvDelimiter");
      var parameters=layer?Map.of("geopackage",Map.of("layer","buildings")):Map.of("xlsx",Map.of("sheet","south","headerRow",2));
      runtime.put("readParameters",parameters);
      if(layer) {
        a.put("media_type","application/geopackage+sqlite3");runtime.put("recordModel","ONE_FEATURE_ONE_SOURCE_OBJECT");
        runtime.put("layer","buildings");runtime.put("geometryColumn","cap");runtime.put("sourceCrs","EPSG:4326");
        p.udp().put("spatial",Map.of("policyRef","policy://geometry","relationships",List.of(),"geometry",
            Map.of("sourceField",CAP,"expectedSourceCrs","EPSG:4326","canonicalSrid",4326,"accessLabel","RESTRICTED",
                "normalizationVersion","1","crsPolicy",Map.of("sourceAxisOrder","XY","mismatchAction","REJECT"))));
      } else {
        a.put("media_type","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");runtime.put("format","XLSX");
      }
      var compiled=ManagedFileExecutionProposal.compile(c,a,"fixture-tenant",p);
      var execution=object(object(object(compiled,"extractionProfile"),"runtime"),"execution");
      assertThat(execution).containsEntry("adapterId",layer?"managed-geopackage-v1":"managed-tabular-v1")
          .containsEntry("acquisitionMode",layer?"INTERNAL_MANAGED_GEOPACKAGE":"INTERNAL_MANAGED_XLSX")
          .containsEntry("readParameters",parameters).doesNotContainKey("csvDelimiter");
      if(layer)assertThat(execution).containsEntry("geometryColumn","cap").containsEntry("layer","buildings").containsEntry("sourceCrs","EPSG:4326");
      else assertThat(execution).containsEntry("sheet","south").containsEntry("headerRow",2);
      var evidence=layer?Map.<String,Object>of("layer","buildings","featureCount",1,"geometryColumn","cap","sourceCrs","EPSG:4326")
          :Map.<String,Object>of("sheet","south","rows",1,"headerRow",2);
      var unit=new SourceUnitProfiles.Unit(layer?"layer:0":"sheet:1",layer?"LAYER":"SHEET",layer?"buildings":"south",
          evidence,List.of(),List.of("nome_teatro"),List.of());
      String hash="sha256:"+"1".repeat(64);
      var previous=new SourceUnitProfiles.Reference(UUID.fromString(PROFILE),1,unit.ref(),hash);
      var replacement=new SourceUnitProfiles.Reference(UUID.randomUUID(),1,unit.ref(),hash);
      var bytes=new LinkedHashMap<>(a);bytes.put("asset_id",UUID.randomUUID());bytes.put("content_hash","sha256:new-bytes");
      var changed=new SourceUnitProfiles.Reference(replacement.profileId(),1,unit.ref(),"sha256:"+"2".repeat(64));
      assertThatThrownBy(()->ManagedUnitRevision.replace(compiled,a,bytes,previous,changed,unit,unit,false,2)).hasMessage("ONB_UNIT_SCHEMA_CHANGED");
      ManagedUnitRevision.replace(compiled,a,bytes,previous,replacement,unit,unit,false,2);
      assertThat(execution).containsEntry("sourceSchemaVersion","2").containsEntry("readParameters",parameters)
          .containsEntry("sourceSchemaRef","profile://managed-files/"+bytes.get("asset_id")+"/profiles/"+replacement.profileId());
    }
  }

  @Test void historicalCsvProposalIsExact() {
    var expected=config();
    var runtime=object(object(expected,"extractionProfile"),"runtime");
    var p=proposal();
    runtime.put("udp",p.udp());
    runtime.put("execution",Map.ofEntries(
        Map.entry("acquisitionMode","INTERNAL_MANAGED_CSV"),Map.entry("adapterId","managed-tabular-v1"),
        Map.entry("adapterRuntimeVersion","1.0.0"),Map.entry("sourceSchemaRef","profile://managed-files/"+ASSET+"/profiles/"+PROFILE),
        Map.entry("sourceSchemaId",SOURCE),Map.entry("sourceSchemaVersion","1"),Map.entry("semanticPublicationSetRef",PUBLICATION),
        Map.entry("adapterProfileRef","adapter://managed-tabular/1"),Map.entry("observationPolicy","ACQUISITION_TIME"),
        Map.entry("expectedSize",321L),Map.entry("maxRows",100),Map.entry("maxColumns",20),Map.entry("maxCellChars",1000),
        Map.entry("mappingRefs",List.of("sm-"+SOURCE)),Map.entry("authorityPolicyRef","urn:fixture:authority-policy")));
    expected.put("semanticReferenceBindings",List.of(Map.of("semanticId",ONTOLOGY,"semanticVersion","1",
        "revisionId",REVISION,"publicationSetId",PUBLICATION)));
    var compiled=compile(config(),p);
    assertThat(compiled).isEqualTo(expected);
    assertThat(new it.comune.trieste.ouf.onboarding.domain.CanonicalHash(json).of(compiled))
        .isEqualTo("sha256:32ea369b8a8bb514432abaee595285475dedcef063469e67cb957fb4587df83b");
  }

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
  @Test @SuppressWarnings("unchecked") void categoryMustAgreeAcrossMappingAndGovernedPolicy(){
    var config=config();var proposal=proposal();
    var mapping=object(config,"semanticMapping");
    var targets=(List<Map<String,Object>>)mapping.get("targetClasses");
    var target=targets.getFirst();
    var policy=object(object(proposal.udp(),"resolution"),"governedIdentity");
    var category=Map.of("schemeIri","urn:example:scheme","conceptIri","urn:example:concept");
    target.put("category",category);
    var compiled=compile(config,proposal);
    assertThat(object(compiled,"semanticMapping")).isEqualTo(mapping);
    policy.put("category",category);
    compiled=compile(config,proposal);
    var validator=new ConfigurationValidator();
    assertThat(validator.validate(SOURCE,compiled).findings()).extracting(ConfigurationValidator.Finding::code)
        .contains("ONB_GOVERNED_IDENTITY_PROPOSAL").doesNotContain("ONB_MAPPING_CATEGORY_CONFLICT","ONB_MAPPING_CATEGORY_INVALID");
    var absent=new LinkedHashMap<>(target);absent.remove("category");
    targets.add(absent);
    assertCategoryError(config,proposal,"ONB_MAPPING_CATEGORY_CONFLICT");
    targets.removeLast();
    target.remove("category");
    assertCategoryError(config,proposal,"ONB_MAPPING_CATEGORY_CONFLICT");
    target.put("category",Map.of("schemeIri","urn:example:other","conceptIri","urn:example:concept"));
    assertCategoryError(config,proposal,"ONB_MAPPING_CATEGORY_CONFLICT");
    for(Object invalid:Arrays.asList(null,Map.of(),Map.of("schemeIri","urn:example:scheme"),
        Map.of("schemeIri","relative","conceptIri","urn:example:concept"),
        Map.of("schemeIri","urn:example:scheme","conceptIri",""),
        Map.of("schemeIri","urn:example:scheme","conceptIri","urn:example:concept","label","extra"))){
      target.put("category",invalid);
      assertCategoryError(config,proposal,"ONB_MAPPING_CATEGORY_INVALID");
    }
  }

  private void assertCategoryError(Map<String,Object> config,ManagedFileExecutionProposal.Proposal proposal,String code){
    assertThatThrownBy(()->compile(config,proposal)).isInstanceOfSatisfying(
        it.comune.trieste.ouf.onboarding.domain.DomainFailure.class,failure->assertThat(failure.code()).isEqualTo(code));
    object(object(config,"extractionProfile"),"runtime").put("udp",proposal.udp());
    assertThat(new ConfigurationValidator().validate(SOURCE,config).findings())
        .extracting(ConfigurationValidator.Finding::code).contains(code);
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

  @Test void onlyExplicitVersionedConversionsCanDeclareNumericOrDateProperties(){
    for(String rule:List.of("DECIMAL_DOT_V1","DECIMAL_COMMA_V1","DATE_ISO_V1","DATE_DMY_V1")){
      var c=config();var p=proposal();
      var mappings=(List<Map<String,Object>>)object(c,"semanticMapping").get("propertyMappings");
      mappings.get(1).put("transform",rule);
      var props=(List<Map<String,Object>>)object(p.udp(),"materialization").get("properties");
      String datatype="http://www.w3.org/2001/XMLSchema#"+(rule.startsWith("DECIMAL")?"decimal":"date");
      props.get(1).put("datatype",datatype);
      var policy=object(object(p.udp(),"resolution"),"governedIdentity");
      ((List<Map<String,Object>>)policy.get("signals")).get(1).put("comparator",rule.startsWith("DECIMAL")?"DECIMAL_V1":"TEXT_V1");
      var compiled=compile(c,p);
      assertThat(compiled.get("semanticMapping")).isEqualTo(c.get("semanticMapping"));
      var runtime=object(object(compiled,"extractionProfile"),"runtime");
      var actual=(List<Map<String,Object>>)object(object(runtime,"udp"),"materialization").get("properties");
      assertThat(actual.get(1)).containsEntry("datatype",datatype);
      assertThat(compiled).doesNotContainKeys("runtimeAuthorityIssued","approvedBy","compatibilityAttestation");
      props.get(1).put("datatype","http://www.w3.org/2001/XMLSchema#string");
      assertThatThrownBy(()->compile(c,p)).hasMessage("ONB_MANAGED_EXECUTION_PROPOSAL_INVALID");
    }
  }

  @Test void inferredOrUnversionedConversionNamesAreNotAccepted(){
    for(String rule:List.of("AUTO","DECIMAL","DATE","DATE_DMY_V2")){
      var c=config();
      ((List<Map<String,Object>>)object(c,"semanticMapping").get("propertyMappings")).get(1).put("transform",rule);
      assertThatThrownBy(()->compile(c,proposal())).hasMessage("ONB_MANAGED_EXECUTION_PROPOSAL_INVALID");
    }
  }

  @Test void mappedRevisionExtendsGovernedComparisonOnThePinnedPublication(){
    var compiled=compile(config(),proposal());
    var previous=asset();previous.put("media_type","text/csv");
    var next=new LinkedHashMap<>(previous);next.put("asset_id",UUID.randomUUID());
    var schema=List.of(Map.of("name","nome_teatro","dataType","STRING"),Map.of("name","cap","dataType","STRING"));
    var before=Map.<String,Object>of("profile_id",PROFILE,"format","CSV","metadata",Map.of("rows",1,"delimiter",";"),"inferred_schema",schema);
    var extended=new ArrayList<>(schema);
    extended.add(Map.of("name","fee","dataType","LONG"));
    var after=new LinkedHashMap<>(before);
    after.put("profile_id",UUID.randomUUID());after.put("inferred_schema",extended);after.put("candidate_keys",List.of("nome_teatro"));
    var rule=Map.<String,Object>of("policyRef","urn:ouf:policy:linked-object-property-reconciliation:v1",
        "cardinality","SINGLE","normalizerRef","EXACT_TYPED","normalizerVersion",1,"validity","UNBOUNDED");
    var comparison=new ManagedCsvRevision.Comparison("DECIMAL_V1","urn:fixture:fee-assertion");
    var m=new ManagedCsvRevision.Mapping("urn:fixture:fee","DECIMAL_DOT_V1",rule,null,null,comparison);
    var decision=new ManagedCsvRevision.NewColumn("fee","MAP",m);
    var missing=new ManagedCsvRevision.Mapping(m.propertyIri(),m.transform(),rule,null,null,null);
    assertThatThrownBy(()->ManagedCsvRevision.replace(compiled,previous,before,next,after,2,
        List.of(new ManagedCsvRevision.NewColumn("fee","MAP",missing))))
        .isInstanceOfSatisfying(ManagedCsvRevision.Incompatible.class,e->assertThat(e.diagnostics())
            .extracting(ManagedCsvRevision.Finding::code).containsExactly("INVALID_NEW_COLUMN_COMPARISON"));
    ManagedCsvRevision.replace(compiled,previous,before,next,after,2,List.of(decision));
    var runtime=object(object(compiled,"extractionProfile"),"runtime");
    var resolution=object(object(runtime,"udp"),"resolution");
    var policy=object(resolution,"governedIdentity");
    GovernedIdentityProposal.validate(resolution,policy,SOURCE,Set.of(CLASS),Set.of(NAME,CAP,m.propertyIri()));
    assertThat(((List<?>)policy.get("signals")).getLast()).isEqualTo(Map.of("id",m.propertyIri(),
        "semanticRef",m.propertyIri()+"@"+PUBLICATION,"comparator",comparison.comparator(),"assertionRef",comparison.assertionRef(),
        "uniqueWithinScope",false,"excludesOnDisagreement",false));
    assertThat(policy.get("sufficientRules")).isEqualTo(object(object(proposal().udp(),"resolution"),"governedIdentity").get("sufficientRules"));
  }

  @Test void wideExecutionPreservesAllPropertiesAndRejectsThe1025th() {
    for(int count:List.of(300,400,1024,1025)){
      var c=config();var p=proposal();
      var fields=new ArrayList<String>();var mappings=new ArrayList<Map<String,Object>>();
      var labels=new ArrayList<Map<String,Object>>();var properties=new ArrayList<Map<String,Object>>();
      for(int n=0;n<count;n++){
        String field=n==0?"nome_teatro":n==1?"cap":"campo_"+n;
        String iri=n==0?NAME:n==1?CAP:"urn:fixture:property:"+n;
        fields.add(field);
        mappings.add(Map.of("sourceField",field,"targetPropertyIri",iri,"transform","IDENTITY"));
        labels.add(Map.of("scope","PROPERTY","target",iri,"label","RESTRICTED"));
        properties.add(Map.of("sourceField",iri,"propertyIri",iri,"datatype","http://www.w3.org/2001/XMLSchema#string","accessLabel","RESTRICTED","authorityOrder",List.of(SOURCE)));
      }
      object(object(c,"extractionProfile"),"projection").put("FILE",fields);
      object(c,"semanticMapping").put("propertyMappings",mappings);c.put("dataAccessPolicies",labels);
      object(p.udp(),"materialization").put("properties",properties);
      var signals=new ArrayList<Map<String,Object>>();
      for(var property:properties){
        String iri=(String)property.get("propertyIri");
        signals.add(Map.of("id",iri,"semanticRef",iri+"@"+PUBLICATION,"comparator","TEXT_V1","excludesOnDisagreement",false,"uniqueWithinScope",false,"assertionRef","urn:fixture:reviewed-assertion"));
      }
      object(object(p.udp(),"resolution"),"governedIdentity").put("signals",signals);
      var wide=new ManagedFileExecutionProposal.Proposal(p.semanticBindings(),PUBLICATION,p.udp(),new ManagedFileExecutionProposal.Limits(100,count,1000));
      if(count>1024){assertThatThrownBy(()->compile(c,wide)).hasMessage("ONB_MANAGED_EXECUTION_PROPOSAL_INVALID");continue;}
      var compiled=compile(c,wide);var runtime=object(object(compiled,"extractionProfile"),"runtime");
      assertThat((List<?>)object(object(runtime,"udp"),"materialization").get("properties")).hasSize(count);
      assertThat(object(runtime,"execution")).containsEntry("maxColumns",count);
      assertThat(compiled.get("dataAccessPolicies")).isEqualTo(labels);
    }
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
