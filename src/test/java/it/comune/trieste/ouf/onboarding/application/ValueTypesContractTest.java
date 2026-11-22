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
    assertThat(hashes.ofBytes(bytes)).isEqualTo("sha256:4b90e85ecd1573404612ef0a0ee627feffaa9c189fe4363e82079bd07824c333");
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

  private static Map<String,Object> split(String reader,Object readerParams){
    return constructor("LIST_SPLIT_V1",List.of(input("value","text","IDENTITY")),
        Map.of("separator","|","itemReader",reader,"itemReaderParams",readerParams));
  }

  @Test void d084_12_splitUsesTheDeclaredItemReaderParameters(){
    assertThat(check(split("WKT_V1",Map.of("crs","EPSG:6708"))).datatypes).containsEntry(PROPERTY,"list");
    assertThat(check(split("VALUE_MAP_V1",Map.of("valueMapRef","colours@"+SET))).valueMapRefs).containsExactly("colours@"+SET);
    for(var bad:List.of(split("WKT_V1",Map.of()),split("WKT_V1",Map.of("crs","private")),
        split("VALUE_MAP_V1",Map.of("crs","EPSG:4326")),split("IDENTITY",Map.of("valueMapRef","private"))))
      assertThatThrownBy(()->check(bad)).hasMessage("ONB_VALUE_READER_INVALID");
    assertThatThrownBy(()->check(split("WKT_V1","private"))).hasMessage("ONB_COMPOSITION_INVALID");
  }

  @Test void d084_13_manyItemsAcceptOnlySingleBuildersOfTheDeclaredType(){
    var geo=Map.of("fieldName","text","reader","WKT_V1","transformParams",Map.of("crs","EPSG:4326"));
    var child=Map.of("structured",struct(INNER,List.of(leaf())));
    var specs=Map.of("components",List.of(spec("locations","geometry","MANY",true),spec("children",INNER+"@"+SET,"MANY",false)));
    var children=Map.of("component","children","items",List.of(child,child));
    java.util.function.Function<Object,ValueCompositions> validate=items->new ValueCompositions(
        List.of(top(struct(OUTER,List.of(Map.of("component","locations","items",items),children)))),FIELDS,
        ref->ref.equals(OUTER+"@"+SET)?specs:definition(INNER));
    assertThat(validate.apply(List.of(geo,Map.of("constructor",point()))).used).containsExactlyInAnyOrder("text","lat","lon");
    assertThat(validate.apply(Collections.nCopies(257,geo)).used).contains("text");
    assertThat(validate.apply(List.of()).typeRefs).contains(INNER+"@"+SET);
    for(var bad:List.of(List.of(Map.of("fieldName","text","reader","IDENTITY")),List.of(Map.of("constructor",split())),
        List.of(child),List.of(Map.of("items",List.of(geo))),List.of(Map.of("fieldName","excluded","reader","GEOJSON_V1"))))
      assertThatThrownBy(()->validate.apply(bad)).hasMessage("ONB_COMPOSITION_INVALID");
    assertThatThrownBy(()->check(struct(OUTER,List.of(Map.of("component","inside","items",List.of(child)),
        Map.of("component","where","constructor",point()))))).hasMessage("ONB_COMPOSITION_INVALID");
  }

  @Test void d084_14_newPreparationsRequireEveryDatatypeToBeActive(){
    for(String status:List.of("DEPRECATED","RETIRED"))for(String changed:List.of(OUTER,INNER)){
      var draft=config();
      var original=draft.get("semanticReferenceBindings");
      assertThatThrownBy(()->PublishedDatatypes.prepare(draft,FIELDS,(id,revision,set)->{
        var actual=response(id);
        if(id.equals(changed))actual.put("status",status);
        return actual;
      },hashes)).isInstanceOfSatisfying(DomainFailure.class,e->assertThat(e.code()).isEqualTo("ONB_DATATYPE_UNRESOLVED"));
      assertThat(draft.get("semanticReferenceBindings")).isSameAs(original);
    }
    // Even a constructor-only D-084 proposal can include a separate DATATYPE pin.
    var draft=config();
    var mapping=new LinkedHashMap<String,Object>();
    mapping.put("semanticRefs",List.of(INNER+"@1",PROPERTY+"@1"));
    mapping.put("compositions",List.of(top(split())));
    mapping.put("propertyMappings",List.of());
    draft.put("semanticMapping",mapping);
    draft.put("semanticReferenceBindings",List.of(binding(INNER),binding(PROPERTY)));
    assertThatThrownBy(()->PublishedDatatypes.prepare(draft,Map.of("text","STRING"),(id,revision,set)->{
      var actual=response(id);
      if(id.equals(INNER))actual.put("status","DEPRECATED");
      return actual;
    },hashes)).isInstanceOfSatisfying(DomainFailure.class,e->assertThat(e.code()).isEqualTo("ONB_DATATYPE_UNRESOLVED"));
    var file=Map.of("selection",Map.of("fileProfileId","p"));
    for(String reader:List.of("IDENTITY","DECIMAL_DOT_V1","DECIMAL_COMMA_V1","DATE_ISO_V1","DATE_DMY_V1")){
      var legacy=Map.of("propertyMappings",List.of(Map.of("sourceField","text","transform",reader)));
      assertThat(PublishedDatatypes.requiresPreparation(legacy,file,List.of(binding(PROPERTY)))).isEqualTo(false);
      assertThat(PublishedDatatypes.requiresPreparation(legacy,file,List.of(Map.of("definitionSha256","previous")))).isEqualTo(true);
    }
  }

  @Test void d084_19_emptyManyStillRequiresTheExactNestedPublication(){
    var config=config();
    var mapping=new LinkedHashMap<Object,Object>(ValueCompositions.map(config.get("semanticMapping")));
    mapping.put("compositions",List.of(top(struct(OUTER,List.of(Map.of("component","inside","items",List.of()),
        Map.of("component","where","constructor",point()))))));
    config.put("semanticMapping",mapping);
    config.put("extractionProfile",Map.of("runtime",Map.of("execution",Map.of("semanticPublicationSetRef",SET))));
    var otherSetPin=new LinkedHashMap<>(binding(INNER));
    otherSetPin.put("publicationSetId",REV);
    config.put("semanticReferenceBindings",List.of(binding(OUTER),otherSetPin,binding(PROPERTY)));
    var rootDefinition=Map.of("components",List.of(spec("inside",INNER+"@"+REV,"MANY",false),
        spec("where","geometry","ONE",true)));
    DatatypeResolver resolver=(id,revision,set)->{
      var actual=response(id);
      if(id.equals(OUTER)){
        actual.put("definition",rootDefinition);
        actual.put("definitionSha256",hashes.ofDefinition(rootDefinition).substring(7));
      }else if(id.equals(INNER)){
        actual.put("publication_set_id",REV);
        actual.put("label",Map.of("en","Nested type"));
      }
      return actual;
    };
    var fields=Map.of("lat","DECIMAL","lon","LONG");
    assertThatThrownBy(()->PublishedDatatypes.prepare(config,fields,(id,revision,set)->{
      var actual=resolver.resolve(id,revision,set);
      if(id.equals(INNER))actual.put("status","DEPRECATED");
      return actual;
    },hashes)).isInstanceOfSatisfying(DomainFailure.class,e->assertThat(e.code()).isEqualTo("ONB_DATATYPE_UNRESOLVED"));
    PublishedDatatypes.prepare(config,fields,resolver,hashes);
    var pin=json.valueToTree(config.get("semanticReferenceBindings")).get(1);
    assertThat(pin.path("publicationSetId").asText()).isEqualTo(REV);
    assertThat(pin.path("definition")).isEqualTo(json.valueToTree(definition(INNER)));
  }

  @Test void d084_15_valueMapPinRequiresPublishedEvidenceForDirectAndListReaders(){
    String mapId="urn:test:map",ref=mapId+"@"+SET,vocabulary="urn:test:colours";
    String member="urn:test:red";
    var ids=List.of(PROPERTY,mapId,member);
    for(boolean direct:List.of(false,true)){
      var mapping=new LinkedHashMap<String,Object>(Map.of("semanticRefs",ids.stream().map(id->id+"@1").toList(),
          "mappingId","m","sourceType",Map.of("typeCode","FILE")));
      mapping.put("compositions",direct?List.of():List.of(top(split("VALUE_MAP_V1",Map.of("valueMapRef",ref)))));
      mapping.put("propertyMappings",direct?List.of(Map.of("sourceField","text","targetPropertyIri",PROPERTY,"transform","VALUE_MAP_V1",
          "valueMapRef",mapId)):List.of());
      var config=config();
      config.put("semanticMapping",mapping);
      config.put("semanticReferenceBindings",ids.stream().map(ValueTypesContractTest::binding).toList());
      var profiles=Map.of("text","STRING");
      DatatypeResolver withoutProof=(id,rev,set)->{
        var actual=new LinkedHashMap<String,Object>(Map.of("semantic_id",id,"semantic_version","1","revision_id",rev,
            "publication_set_id",set,"artifact_type",id.equals(member)?"CONCEPT":id.equals(PROPERTY)?"PROPERTY":"RULE"));
        if(id.equals(member))actual.put("definition",Map.of("vocabularyRef",vocabulary));
        return actual;
      };
      assertThatThrownBy(()->PublishedDatatypes.prepare(config,profiles,withoutProof,hashes)).isInstanceOfSatisfying(
          DomainFailure.class,e->assertThat(e.code()).isEqualTo("ONB_VALUE_MAP_UNRESOLVED"));
      // Fixture of the publisher boundary. A CONCEPT's vocabulary alone cannot attest a map.
      var proof=new java.util.concurrent.atomic.AtomicReference<>(new DatatypeResolver.ValueMapPin(mapId,SET,vocabulary));
      DatatypeResolver withProof=new DatatypeResolver(){
        public Map<String,Object> resolve(String id,String rev,String set){return withoutProof.resolve(id,rev,set);}
        public ValueMapPin valueMapPin(Map<String,Object> published){
          assertThat(published).containsEntry("semantic_id",mapId).containsEntry("publication_set_id",SET).containsEntry("revision_id",REV);
          return proof.get();
        }
      };
      PublishedDatatypes.prepare(config,profiles,withProof,hashes);
      var bundle=new PublishedBundleCompiler(hashes).compile("s",UUID.randomUUID(),1,hashes.of(config),config);
      var pin=json.valueToTree(bundle.get("semanticReferenceBindings")).get(1);
      assertThat(pin.path("valueMapRef").asText()).isEqualTo(ref);
      assertThat(pin.path("vocabularyRef").asText()).isEqualTo(vocabulary);
      assertThat(pin.path("revisionId").asText()).isEqualTo(REV);
      assertThat(json.valueToTree(bundle.get("semanticReferenceBindings")).get(2).path("definition").path("vocabularyRef").asText()).isEqualTo(vocabulary);
      Object frozen=config.get("semanticReferenceBindings");
      for(var wrong:List.of(new DatatypeResolver.ValueMapPin("other",SET,vocabulary),
          new DatatypeResolver.ValueMapPin(mapId,REV,vocabulary),new DatatypeResolver.ValueMapPin(mapId,SET,"relative"),
          new DatatypeResolver.ValueMapPin(mapId,SET,"urn:different"))){
        proof.set(wrong);
        assertThatThrownBy(()->PublishedDatatypes.prepare(config,profiles,withProof,hashes)).isInstanceOfSatisfying(
            DomainFailure.class,e->assertThat(e.code()).isEqualTo("ONB_VALUE_MAP_UNRESOLVED"));
        assertThat(config.get("semanticReferenceBindings")).isSameAs(frozen);
      }
      proof.set(new DatatypeResolver.ValueMapPin(mapId,SET,vocabulary));
      var driftedConcept=new DatatypeResolver(){
        public Map<String,Object> resolve(String id,String rev,String set){
          var actual=withoutProof.resolve(id,rev,set);
          if(id.equals(member))actual.put("definition",Map.of("vocabularyRef","urn:test:different-vocabulary"));
          return actual;
        }
        public ValueMapPin valueMapPin(Map<String,Object> published){return withProof.valueMapPin(published);}
      };
      assertThatThrownBy(()->PublishedDatatypes.prepare(config,profiles,driftedConcept,hashes)).isInstanceOfSatisfying(
          DomainFailure.class,e->assertThat(e.code()).isEqualTo("ONB_DATATYPE_UNRESOLVED"));
      assertThat(config.get("semanticReferenceBindings")).isSameAs(frozen);
    }
  }

  @Test void d084_18_definitionConstraintsKeepTheContractJsonTypes(){
    record Bound(String type,String key,Object valid,Object invalid){}
    for(var b:List.of(new Bound("integer","minInclusive","-90",-90),new Bound("decimal","maxInclusive","45.5","45.50"),
        new Bound("date","minInclusive","2024-02-29","2023-02-29"),new Bound("datetime","maxInclusive","2026-01-01T00:00:00Z","2026-01-01T01:00:00+01:00"),
        new Bound("string","maxLength",80,"80"),new Bound("concept","vocabularyRef","urn:colours","relative"))){
      var component=new LinkedHashMap<>(spec("value",b.type(),"ONE",true));
      var definition=Map.of("components",List.of(component));
      component.put("constraints",Map.of(b.key(),b.valid()));
      assertThat(ValueCompositions.componentSpecs(definition)).containsKey("value");
      component.put("constraints",Map.of(b.key(),b.invalid()));
      assertThatThrownBy(()->ValueCompositions.componentSpecs(definition)).hasMessage("ONB_COMPOSITION_INVALID");
    }
  }
}
