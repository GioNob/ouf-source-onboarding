package it.comune.trieste.ouf.onboarding.application;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

class ValueTypesRevision2Test {
  private final ObjectMapper json=new ObjectMapper();
  private final CanonicalHash hashes=new CanonicalHash(json);
  private static final String SET="00000000-0000-4000-8000-000000000002", REV="00000000-0000-4000-8000-000000000001";
  private static final String MAP="urn:test:map", OTHER_MAP="urn:test:second-map", VOCABULARY="urn:test:vocabulary@"+SET;

  @Test void d084_26_sharedWhitespaceVectorsReachTheCsvProfiler()throws Exception{
    var vectors=json.readTree(Files.readAllBytes(Path.of("contracts/ouf-value-types-v1.json"))).path("whitespace").path("vectors");
    // Profiling keeps lexical codes after trim. Ingestion applies each configured reader.
    var expected=List.of("12.50","-0.0","+007","\u001c12","\ufeff12","Via Roma","","","A1","A1");
    assertThat(vectors.size()).isEqualTo(expected.size());
    for(int i=0;i<vectors.size();i++){
      String cell=vectors.get(i).path("cell").asText(),trimmed=expected.get(i);
      var profile=csv("code\n\""+cell+"\"\n");
      assertThat(profile.distinctCellValues().get("").get("code")).isEqualTo(trimmed.isEmpty()?List.of():List.of(trimmed));
      assertThat(profile.columns().getFirst().nullable()).isEqualTo(trimmed.isEmpty());
      assertThat(profile.sample().getFirst()).containsEntry("code",cell);
    }
  }

  @Test void d084_27_valueMapCodeAndConceptBoundsFollowRevisionTwo(){
    for(String code:List.of("\u00a0A1","A1\u202f","A\u0085B","A\u009fB","\u001cA","x".repeat(129),"")){
      assertMapRejected(definition(code,"urn:test:concept"));
    }
    String code="\ufeffA"+"𐀀".repeat(126);
    var accepted=definition(code,"urn:"+"x".repeat(508));
    assertThat(hashes.ofDefinition(accepted)).isEqualTo(hashes.of(accepted));
    assertThat(new String(hashes.definitionBytes(accepted),StandardCharsets.UTF_8)).contains(code);
    assertThat(PublishedValueMaps.verify(MAP+"@"+SET,new LinkedHashMap<>(),resolve(MAP,accepted),hashes))
        .isEqualTo(hashes.definitionBytes(accepted).length);
    assertMapRejected(definition(code,"urn:"+"x".repeat(509)));
  }

  @Test void d084_34_xlsxCellsUseTheSameWhitespaceAndRedaction()throws Exception{
    var vectors=json.readTree(Files.readAllBytes(Path.of("contracts/ouf-value-types-v1.json"))).path("whitespace").path("vectors");
    var cells=new ArrayList<String>();
    for(var vector:vectors){
      String cell=vector.path("cell").asText();
      // The CSV test also covers the literal U+001C, which XML 1.0 cannot contain.
      if(cell.indexOf('\u001c')<0)cells.add(cell);
    }
    try(var workbook=new XSSFWorkbook();var bytes=new ByteArrayOutputStream()){
      var sheet=workbook.createSheet();
      var header=sheet.createRow(0);
      for(int i=0;i<cells.size();i++){
        header.createCell(i).setCellValue("code_"+i);
      }
      header.createCell(cells.size()).setCellValue("nome");
      var row=sheet.createRow(1);
      for(int i=0;i<cells.size();i++)row.createCell(i).setCellValue(cells.get(i));
      row.createCell(cells.size()).setCellValue("private");
      workbook.write(bytes);
      var xlsx=new ManagedFileProfiler().profile(bytes.toByteArray(),"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
      for(int i=0;i<cells.size();i++){
        var csv=csv("code\n\""+cells.get(i)+"\"\n");
        assertThat(xlsx.distinctCellValues().get("sheet:0").get("code_"+i)).isEqualTo(csv.distinctCellValues().get("").get("code"));
      }
      // ouf.xlsx-cell-text.v1 keys values by unit and keeps the sample inside the worksheet (task-62f).
      assertThat(xlsx.distinctCellValues().get("sheet:0")).doesNotContainKey("nome");
      @SuppressWarnings("unchecked") var unit=((java.util.List<java.util.Map<String,Object>>)xlsx.metadata().get("sheets")).getFirst();
      @SuppressWarnings("unchecked") var sample=(java.util.List<java.util.Map<String,String>>)unit.get("sample");
      assertThat(sample.getFirst()).containsEntry("nome","[REDACTED]");
    }
  }

  @Test void d084_28_valueMapCanonicalByteLimitsAreInclusiveAndCountEachPinOnce(){
    int definitionLimit=786_432,bundleLimit=1_048_576;
    var first=sizedDefinition(definitionLimit);
    assertThat(PublishedValueMaps.verify(MAP+"@"+SET,new LinkedHashMap<>(),resolve(MAP,first),hashes))
        .isEqualTo(definitionLimit);
    assertMapRejected(sizedDefinition(definitionLimit+1));
    for(int extra:List.of(0,1)){
      String pin=MAP+"@"+SET;
      var properties=List.of(
          Map.of("sourceField","code","targetPropertyIri","urn:test:p1","transform","VALUE_MAP_V1","valueMapRef",MAP),
          Map.of("sourceField","code","targetPropertyIri","urn:test:p2","transform","VALUE_MAP_V1","transformParams",Map.of("valueMapRef",pin)));
      var mapping=Map.of("semanticRefs",List.of(MAP+"@1",OTHER_MAP+"@1"),"propertyMappings",properties);
      var bindings=List.of(binding(MAP),binding(OTHER_MAP));
      var config=new LinkedHashMap<String,Object>();
      config.put("semanticMapping",mapping);
      config.put("semanticReferenceBindings",bindings);
      config.put("extractionProfile",Map.of("runtime",Map.of()));
      config.put("dataAccessPolicies",List.of(
          Map.of("target","urn:test:p1","label","OPEN","scope","PROPERTY"),
          Map.of("target","urn:test:p2","label","OPEN","scope","PROPERTY")));
      var second=sizedDefinition(bundleLimit-definitionLimit+extra);
      DatatypeResolver registry=(id,rev,set)->resolve(id,id.equals(MAP)?first:second);
      if(extra==0)PublishedDatatypes.prepare(config,Map.of("code","STRING"),registry,hashes);
      else {
        assertThatThrownBy(()->PublishedDatatypes.prepare(config,Map.of("code","STRING"),registry,hashes))
            .isInstanceOfSatisfying(DomainFailure.class,e->assertThat(e.code()).isEqualTo("ONB_VALUE_MAP_LIMIT_EXCEEDED"));
        assertThat(config.get("semanticReferenceBindings")).isSameAs(bindings);
      }
    }
  }

  @Test void d084_29_exactSortedCodesRespectCardinalityCodePointsAndRedaction(){
    var rows=new StringBuilder("code;over;short;wide;nome_cittadino;empty\n");
    String lastGood="x"+"𐀀".repeat(127), tooLong=lastGood+"x";
    for(int i=0;i<=2000;i++)rows.append(" k").append(i%2000).append(" ;k").append(i).append(';')
        .append(lastGood).append(';').append(i==2000?tooLong:"short").append(";redacted; \n");
    var result=csv(rows.toString());
    var values=result.distinctCellValues().get("");
    assertThat(values).containsOnlyKeys("code","short","empty");
    assertThat(values.get("code")).hasSize(2000).isSorted().contains("k0","k1999");
    assertThat(values.get("short")).containsExactly(lastGood);
    assertThat(result.sample().getFirst()).containsEntry("short","x"+"𐀀".repeat(63));
    assertThat(values.get("empty")).isEmpty();
    assertThat(result.columns().get(1).distinctValues()).isEqualTo(2001);
    assertThat(result.columns().get(3).distinctValues()).isEqualTo(2);
    assertThat(result.sample().getFirst()).containsEntry("nome_cittadino","[REDACTED]");
    assertThat(csv("code\nA\na\né\né\n").distinctCellValues().get("").get("code")).containsExactly("A","a","é","é");
  }

  private static ManagedFileProfiler.Profile csv(String data){
    return new ManagedFileProfiler().profile(data.getBytes(StandardCharsets.UTF_8),"text/csv");
  }
  private static Map<String,String> binding(String id){
    return Map.of("semanticId",id,"semanticVersion","1","revisionId",REV,"publicationSetId",SET);
  }
  private static Map<String,Object> definition(String code,String concept){
    return Map.of("targetVocabulary",VOCABULARY,"entries",List.of(Map.of("code",code,"concept",concept)));
  }
  private Map<String,Object> resolve(String id,Map<String,Object> definition){
    return Map.of("semantic_id",id,"semantic_version","1","revision_id",REV,"publication_set_id",SET,
        "artifact_type","VALUE_MAP","status","ACTIVE","definition",definition,"definitionSha256",hashes.ofDefinition(definition).substring(7));
  }
  private void assertMapRejected(Map<String,Object> definition){
    assertThatThrownBy(()->PublishedValueMaps.verify(MAP+"@"+SET,new LinkedHashMap<>(),resolve(MAP,definition),hashes))
        .isInstanceOfSatisfying(DomainFailure.class,e->assertThat(e.code()).isEqualTo("ONB_VALUE_MAP_UNRESOLVED"));
  }
  private Map<String,Object> sizedDefinition(int bytes){
    var entries=IntStream.range(10000,11600)
        .mapToObj(i->new LinkedHashMap<>(Map.of("code","é"+i,"concept","urn:x"))).toList();
    var result=Map.<String,Object>of("targetVocabulary",VOCABULARY,"entries",entries);
    int remaining=bytes-hashes.definitionBytes(result).length;
    for(var entry:entries){
      int padding=Math.min(remaining,507);
      entry.put("concept","urn:x"+"x".repeat(padding));
      remaining-=padding;
    }
    assertThat(remaining).isZero();
    assertThat(hashes.definitionBytes(result).length).isEqualTo(bytes);
    return result;
  }
}
