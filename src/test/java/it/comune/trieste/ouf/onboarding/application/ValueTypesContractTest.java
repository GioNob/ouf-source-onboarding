package it.comune.trieste.ouf.onboarding.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ValueTypesContractTest {
  private final ObjectMapper json=new ObjectMapper();
  private final CanonicalHash hashes=new CanonicalHash(json);
  private static final String SET="00000000-0000-4000-8000-000000000002",REV="00000000-0000-4000-8000-000000000001";
  private static final String OUTER="urn:test:outer",INNER="urn:test:inner",PROPERTY="urn:test:property";
  private static final Map<String,String> FIELDS=Map.of("text","STRING","lat","DECIMAL","lon","LONG");

  @Test void d084_01_contractVocabularyAndComparators()throws Exception{
    byte[] bytes=Files.readAllBytes(Path.of("contracts/ouf-value-types-v1.json"));
    assertThat(hashes.ofBytes(bytes)).isEqualTo("sha256:c046646c3030f1bcf4460741bbd09351f9bb501cb054d8ed45ce2e7d98dd4247");
    // Same canonical definition as the Registry publication fixture, including numeric constraints.
    var registryDefinition=Map.of("components",List.of(Map.of("type","string","required",true,"name","via","constraints",Map.of("maxLength",80),"cardinality","ONE")));
    var pretty=new CanonicalHash(json.copy().enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT));
    assertThat(pretty.ofDefinition(registryDefinition)).isEqualTo("sha256:b0183219cb69e6545eca88e988abf861275e7099cbb3b77a9dd715e53bb941f4");
    var contract=json.readTree(bytes);
    assertThat(contract.get("readers").size()).isEqualTo(ValueReader.values().length);
    for(var r:contract.get("readers"))assertThat(ValueReader.named(r.get("id").asText()).output).isEqualTo(r.get("output").asText());
    for(var b:contract.get("baseTypes")){
      String id=b.get("id").asText(),iri=b.get("datatype").asText();
      assertThat(ValueReader.DATATYPES.get(id)).isEqualTo(iri);
      assertThat(ValueReader.comparator(iri)).isEqualTo(contract.path("identityComparators").path(id).asText());
    }
    assertThat(ValueReader.comparator("list")).isEqualTo("JSON_V1");
    assertThat(ValueReader.comparator(OUTER)).isEqualTo("JSON_V1");
  }

  @Test void d084_02_everyReaderHasAnExplicitProfileMatrixAndClosedParameters(){
    var all=Set.of("STRING","BOOLEAN","LONG","DECIMAL","DATE");
    for(ValueReader r:ValueReader.values()){
      Set<String> compatible=switch(r){
        case IDENTITY,TEXT_NFC_TRIM_V1,VALUE_MAP_V1 -> all;
        case DECIMAL_DOT_V1,DECIMAL_COMMA_V1 -> Set.of("LONG","DECIMAL");
        case INTEGER_V1 -> Set.of("LONG","STRING");
        case INTEGER_GROUP_DOT_V1,DECIMAL_GROUP_DOT_COMMA_V1,DECIMAL_GROUP_COMMA_DOT_V1 -> Set.of("STRING","LONG","DECIMAL");
        case BOOLEAN_IT_V1 -> Set.of("BOOLEAN","STRING","LONG");
        case DATE_ISO_V1 -> Set.of("DATE");
        default -> Set.of("STRING");
      };
      for(String type:all)assertThat(ManagedCsvRevision.supports(r.name(),type)).as("%s on %s",r,type).isEqualTo(compatible.contains(type));
      assertThatThrownBy(()->r.parameters(Map.of("other","private-source-cell"),null)).hasMessage("ONB_VALUE_READER_INVALID");
    }
    assertThat(ValueReader.WKT_V1.parameters(Map.of("crs","EPSG:4326"),null)).containsEntry("crs","EPSG:4326");
    assertThatThrownBy(()->ValueReader.WKT_V1.parameters(Map.of("crs","private-source-cell"),null)).hasMessage("ONB_VALUE_READER_INVALID");
    assertThatThrownBy(()->ValueReader.WKT_V1.parameters(null,null)).hasMessage("ONB_VALUE_READER_INVALID");
    assertThatThrownBy(()->ValueReader.VALUE_MAP_V1.parameters(null,null)).hasMessage("ONB_VALUE_READER_INVALID");
    assertThat(ValueReader.VALUE_MAP_V1.parameters(null,"map")).isEqualTo(Map.of("valueMapRef","map"));
    assertThatThrownBy(()->ValueReader.VALUE_MAP_V1.parameters(Map.of("valueMapRef","x"),"y")).hasMessage("ONB_VALUE_READER_INVALID");
  }

  private static Map<String,Object> input(String role,String field,String reader){
    return Map.of("role",role,"fieldName",field,"reader",reader);
  }
  private static Map<String,Object> constructor(String id,List<?> inputs,Map<?,?> params){
    return Map.of("constructorId",id,"inputs",inputs,"params",params);
  }
  private static Map<String,Object> top(Map<String,Object> expression){
    var result=new LinkedHashMap<>(expression);
    result.put("kind",expression.containsKey("typeRef")?"STRUCTURED":"CONSTRUCTOR");
    result.put("targetPropertyIri",PROPERTY);result.put("dataAccessLabel","OPEN");return result;
  }
  private static Map<String,Object> point(){
    return constructor("POINT_FROM_LAT_LON_V1",List.of(input("lat","lat","DECIMAL_DOT_V1"),input("lon","lon","DECIMAL_COMMA_V1")),Map.of());
  }
  private static Map<String,Object> split(){
    return constructor("LIST_SPLIT_V1",List.of(input("value","text","IDENTITY")),Map.of("itemReader","INTEGER_V1","separator",","));
  }
  private static Map<String,Object> struct(String id,List<?> components){return Map.of("typeRef",id+"@"+SET,"components",components);}
  private static Map<String,Object> spec(String name,String type,String cardinality,boolean required){
    return Map.of("name",name,"type",type,"cardinality",cardinality,"required",required);
  }
  private static Map<String,Object> leaf(){return Map.of("component","words","constructor",split());}
  private static Map<String,Object> nested(){return struct(OUTER,List.of(Map.of("component","inside","structured",struct(INNER,List.of(leaf()))),
      Map.of("component","where","constructor",point())));}
  private static Map<String,Object> definition(String id){
    return Map.of("components",id.equals(INNER)?List.of(spec("words","integer","MANY",true)):
        List.of(spec("inside",INNER+"@"+SET,"ONE",false),spec("where","geometry","ONE",true)));
  }
  private ValueCompositions check(Map<String,Object> expression){
    return new ValueCompositions(List.of(top(expression)),FIELDS,ref->definition(ref.split("@")[0]));
  }
  @Test void d084_03_constructorRolesOrderTypesAndParameters(){
    assertThat(check(point()).datatypes.get(PROPERTY)).isEqualTo(ValueReader.DATATYPES.get("geometry"));
    var part=input("part","text","IDENTITY");
    assertThat(check(constructor("TEXT_JOIN_V1",List.of(part,part),Map.of("separator"," "))).datatypes.get(PROPERTY)).isEqualTo(ValueReader.DATATYPES.get("string"));
    assertThat(check(split()).datatypes.get(PROPERTY)).isEqualTo("list");
    for(var bad:List.of(constructor("TEXT_JOIN_V1",List.of(part),Map.of("separator",",")),
        constructor("TEXT_JOIN_V1",List.of(part,part),Map.of("separator","123456789")),
        constructor("LIST_SPLIT_V1",List.of(input("value","text","IDENTITY")),Map.of("separator",",")),
        constructor("POINT_FROM_LAT_LON_V1",List.of(input("lat","lat","DECIMAL_DOT_V1"),input("lat","lon","DECIMAL_DOT_V1")),Map.of()),
        constructor("POINT_FROM_LAT_LON_V1",List.of(input("lat","lat","IDENTITY"),input("lon","lon","DECIMAL_DOT_V1")),Map.of()),
        constructor("TEXT_JOIN_V1",List.of(part,input("part","excluded","IDENTITY")),Map.of("separator"," ")))){
      assertThatThrownBy(()->check(bad)).hasMessage("ONB_COMPOSITION_INVALID");
    }
  }
  @Test void d084_04_exactComponentsCardinalityCyclesAndDepth(){
    assertThat(check(nested()).datatypes.get(PROPERTY)).isEqualTo(OUTER);
    var extra=Map.of("component","extra","fieldName","text","reader","IDENTITY");
    for(var bad:List.of(struct(OUTER,List.of(Map.of("component","where","constructor",point()))),
        struct(INNER,List.of(leaf(),extra)),struct(INNER,List.of(leaf(),leaf())),
        struct(INNER,List.of(Map.of("component","words","fieldName","lon","reader","INTEGER_V1"))),
        struct(INNER,List.of(Map.of("component","words","constructor",point()))),
        struct(OUTER,List.of(Map.of("component","inside","structured",struct(OUTER,List.of(leaf()))))))){
      assertThatThrownBy(()->check(bad)).hasMessage("ONB_COMPOSITION_INVALID");
    }
    Map<String,Object> tree=struct(INNER,List.of(leaf()));
    for(int i=0;i<4;i++)tree=struct("urn:level:"+i,List.of(Map.of("component","child","structured",tree)));
    var deep=top(tree);
    assertThatThrownBy(()->new ValueCompositions(List.of(deep),FIELDS,null)).hasMessage("ONB_COMPOSITION_INVALID");
  }

  @Test void d084_08_componentAndLeafBudgetsApplyToExpandedOccurrences(){
    List<Map<String,Object>> components=new ArrayList<>();
    for(int i=0;i<32;i++)components.add(Map.of("component","f"+i,"fieldName","text","reader","IDENTITY"));
    var inner=struct(INNER,components);
    var branches=new ArrayList<Map<String,Object>>();
    for(int i=0;i<8;i++)branches.add(Map.of("component","b"+i,"structured",inner));
    var expression=top(struct(OUTER,branches));
    assertThat(new ValueCompositions(List.of(expression),FIELDS,null).used).containsExactly("text");
    branches.add(Map.of("component","more","structured",inner));
    assertThatThrownBy(()->new ValueCompositions(List.of(expression),FIELDS,null)).hasMessage("ONB_COMPOSITION_INVALID");
    components.add(Map.of("component","more","fieldName","text","reader","IDENTITY"));
    assertThatThrownBy(()->new ValueCompositions(List.of(top(inner)),FIELDS,null)).hasMessage("ONB_COMPOSITION_INVALID");
  }

  private static Map<String,Object> binding(String id){
    return Map.of("semanticId",id,"semanticVersion","1","revisionId",REV,"publicationSetId",SET);
  }
  private Map<String,Object> response(String id){
    if(id.equals(PROPERTY))return Map.of("semantic_id",id,"semantic_version","1","artifact_type","PROPERTY","status","ACTIVE",
        "revision_id",REV,"publication_set_id",SET);
    var def=definition(id);
    return new LinkedHashMap<>(Map.of("semantic_id",id,"semantic_version","1","artifact_type","DATATYPE","status","ACTIVE",
        "revision_id",REV,"publication_set_id",SET,"definition",def,"definitionSha256",hashes.of(def).substring(7),"label",Map.of("it","Tipo pubblicato")));
  }
  private static Map<String,Object> config(){
    var ids=List.of(OUTER,INNER,PROPERTY);
    var mapping=Map.of("sourceType",Map.of("typeCode","FILE"),"mappingId","m","targetClasses",List.of(Map.of("classIri","urn:class")),
        "compositions",List.of(top(nested())),"propertyMappings",List.of(),"semanticRefs",ids.stream().map(id->id+"@1").toList());
    var bundle=Map.of("bundleId","b","environment","test","source",Map.of("sourceId","s","sourceKind","INTERNAL_MANAGED","acquisitionMode","MANAGED"));
    return new LinkedHashMap<>(Map.of("semanticMapping",mapping,"semanticReferenceBindings",ids.stream().map(ValueTypesContractTest::binding).toList(),
        "dataAccessPolicies",List.of(Map.of("target",PROPERTY,"label","OPEN","scope","PROPERTY")),
        "extractionProfile",Map.of("profileId","e","version","1","runtime",Map.of()),"bundle",bundle,"syncProfile",Map.of()));
  }
  @Test void d084_05_exactResolveResponseAndTransitiveBundle(){
    assertThat(RegistryDatatypeResolver.path(INNER,REV,SET)).isEqualTo("/api/semantic/v1/references:resolve?semanticId=urn%3Atest%3Ainner&revisionId="+REV+"&publicationSetId="+SET);
    var config=config();List<String> calls=new ArrayList<>();
    PublishedDatatypes.prepare(config,FIELDS,(id,revision,set)->{
      assertThat(revision).isEqualTo(REV);assertThat(set).isEqualTo(SET);calls.add(id);return response(id);
    },hashes);
    assertThat(calls).containsExactly(OUTER,INNER,PROPERTY);
    var bundle=new PublishedBundleCompiler(hashes).compile("s",UUID.randomUUID(),1,hashes.of(config),config);
    var pins=json.valueToTree(bundle.get("semanticReferenceBindings"));
    for(int i=0;i<2;i++){
      var pin=pins.get(i);var def=definition(pin.get("semanticId").asText());
      assertThat(pin.get("definition")).isEqualTo(json.valueToTree(def));
      assertThat(pin.get("definitionSha256").asText()).isEqualTo(hashes.of(def).substring(7));
    }
  }
  @Test void d084_10_registryHttpContractUsesWorkloadReadAndExactHistoricalCoordinates()throws Exception{
    var builder=org.springframework.web.client.RestClient.builder();
    var server=org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
    var uri="https://gateway.test"+RegistryDatatypeResolver.path(INNER,REV,SET);
    server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(uri))
        .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(org.springframework.http.HttpMethod.GET))
        .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header("Authorization","Bearer fixture"))
        .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
            json.writeValueAsString(response(INNER)),org.springframework.http.MediaType.APPLICATION_JSON));
    server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(uri))
        .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(org.springframework.http.HttpStatus.NOT_FOUND));
    server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(uri))
        .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After","15"));
    var client=new RegistryDatatypeResolver(json,"https://gateway.test",builder,()->"fixture");
    assertThat(client.resolve(INNER,REV,SET)).isEqualTo(response(INNER));
    assertThatThrownBy(()->client.resolve(INNER,REV,SET)).isInstanceOfSatisfying(DomainFailure.class,
        e->assertThat(e.code()).isEqualTo("ONB_DATATYPE_UNRESOLVED"));
    assertThatThrownBy(()->client.resolve(INNER,REV,SET)).isInstanceOfSatisfying(DomainFailure.class,e->{
      assertThat(e.code()).isEqualTo("ONB_DATATYPE_LOOKUP_UNAVAILABLE");
      assertThat(e.properties()).containsEntry("retryAfterSeconds",15);
    });
    server.verify();
  }
  @Test void d084_06_unresolvedOrTamperedNestedDefinitionFailsBeforeFreezing(){
    for(String key:List.of("definition","definitionSha256","semantic_version","revision_id","status","artifact_type","publication_set_id")){
      var config=config();var original=config.get("semanticReferenceBindings");
      assertThatThrownBy(()->PublishedDatatypes.prepare(config,FIELDS,(id,rev,set)->{
        var result=response(id);if(id.equals(INNER))result.put(key,"private-source-cell");return result;
      },hashes)).isInstanceOfSatisfying(DomainFailure.class,e->assertThat(e.code()).isEqualTo("ONB_DATATYPE_UNRESOLVED"));
      assertThat(config.get("semanticReferenceBindings")).isSameAs(original);
    }
    assertThatThrownBy(()->PublishedDatatypes.prepare(config(),FIELDS,(id,rev,set)->null,hashes))
        .isInstanceOfSatisfying(DomainFailure.class,e->assertThat(e.code()).isEqualTo("ONB_DATATYPE_UNRESOLVED"));
  }
}
