package it.comune.trieste.ouf.onboarding.application;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SourceUnitProfilesTest {
  private final ObjectMapper json=new ObjectMapper();
  private final CanonicalHash hashes=new CanonicalHash(json);
  private final ManagedPreviewPaging paging=new ManagedPreviewPaging(json,hashes,
      new DistinctValuesCursors(json,new byte[32],Clock.fixed(Instant.EPOCH,ZoneOffset.UTC),Duration.ofMinutes(5)),8192);
  private final UUID asset=UUID.randomUUID(),id=UUID.randomUUID();
  private static final String XLSX="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

  private Map<String,Object> file(String format,Map<String,Object> metadata,List<?> columns) {
    return Map.of("format",format,"metadata",metadata,"asset_id",asset,"profile_id",id,
        "version",2,"inferred_schema",columns,"candidate_keys",List.of("id"));
  }
  private Map<String,Object> column(String name,String type,boolean nullable,int count) {
    return Map.of("name",name,"dataType",type,"nullable",nullable,"distinctValues",count);
  }
  private Map<String,Object> sheet(String name,int index) {
    return Map.of("sheet",name,"sheetIndex",index,"headerRow",2,"rows",250,"status","READY","hidden",true,
        "candidateKeys",List.of("id"),"nativeTypes",Map.of("id","STRING"),"columns",List.of(column("id","STRING",false,250),
        column("value","LONG",true,3)));
  }
  private SourceUnitProfiles sheets(List<?> sheets) {
    return new SourceUnitProfiles(file("XLSX",Map.of("reader",SourceUnitProfiles.XLSX_READER,"workbookSheets",sheets.size(),
        "dateSystem","1904","timeZone","Europe/Rome","sheets",sheets),List.of()),hashes);
  }

  @Test void f1_03_csvStructuralAndPersistedDigests() {
    var file=file("CSV",Map.of("encoding","UTF-8","delimiter",";","headerRow",1,"rows",2),
        List.of(column("id","LONG",false,2),column("email","STRING",true,1)));
    var views=new SourceUnitProfiles(file,hashes);
    var unit=views.find("table:0");
    var header=views.header(unit,"text/csv");
    assertEquals("sha256:da3d063f0d8675dce286960c91f2bddbab14f680be997337ea6c512fd4e17729",header.get("columnsHash"));
    assertEquals("sha256:32724417a3d7253e3904d9b0e6a34cacb79f5206007fa232b9e0e31582a66f7e",header.get("schemaHash"));
    assertEquals(Map.of("unitCount",1,"readyUnits",1,"singleReadyUnitRef","table:0"),views.summary());
  }

  @Test void f1_04_sheetPagesAndContinuationsBindTheInventoryPosition() {
    var views=sheets(List.of(sheet("First",1),sheet("Second",3)));
    var unit=views.find("sheet:0");
    var values=java.util.stream.IntStream.range(0,250).mapToObj(i->"x".repeat(100)+i).toList();
    var retained=Map.of("sheet:0",Map.of("id",values),"sheet:1",Map.of("id",values));
    var first=paging.unit(views.header(unit,XLSX),unit,retained,"t","u",0,1,null);
    var second=paging.unit(views.header(unit,XLSX),unit,retained,"t","u",1,1,null);
    assertEquals(first.get("schemaHash"),second.get("schemaHash"));
    assertEquals(first.get("columnsHash"),second.get("columnsHash"));
    assertEquals(Map.of("xlsx",Map.of("sheet","First","sheetIndex",1,"headerRow",2,"dateSystem","1904",
        "timeZone","Europe/Rome","cellText",SourceUnitProfiles.XLSX_READER)),first.get("readParameters"));
    var col=json.valueToTree(first.get("columns")).get(0);
    assertEquals("STRING",col.path("nativeType").asText());
    assertEquals(250,col.path("distinctCount").asInt());
    String cursor=col.required("distinctValuesCursor").asText();
    var next=paging.unit(views.header(unit,XLSX),unit,retained,"t","u",0,128,cursor);
    assertEquals(values.get(col.required("distinctValues").size()),json.valueToTree(next.get("columns")).get(0).required("distinctValues").get(0).asText());
    var other=views.find("sheet:1");
    assertEquals("ONB_DISTINCT_VALUES_CURSOR_INVALID",assertThrows(DomainFailure.class,()->paging.unit(views.header(other,XLSX),
        other,retained,"t","u",0,128,cursor)).code());
    assertFalse(first.containsKey("sample"));
  }

  @Test void f1_12_twoGeoPackageLayersExposeTheirOwnColumnsAndKeys() throws Exception {
    byte[] bytes;
    try(var in=getClass().getResourceAsStream("/geopackage/cameras.gpkg")) {bytes=in.readAllBytes();}
    String media="application/geopackage+sqlite3";
    var raw=new ManagedFileProfiler().profile(bytes,media);
    @SuppressWarnings("unchecked") Map<String,Object> metadata=json.convertValue(raw.metadata(),Map.class);
    var views=new SourceUnitProfiles(file("GEOPACKAGE",metadata,List.of()),hashes);
    assertEquals(Map.of("unitCount",2,"readyUnits",2),views.summary());
    for(int index=0;index<2;index++){
      var unit=views.find("layer:"+index);
      assertEquals(index==0?"cabinets":"cameras",unit.name());
      var head=views.header(unit,media);
      var first=paging.unit(head,unit,raw.distinctCellValues(),"t","u",0,1,null);
      var second=paging.unit(views.header(unit,media),unit,raw.distinctCellValues(),"t","u",1,1,null);
      assertEquals("READY",first.get("status"));
      assertEquals(first.get("schemaHash"),second.get("schemaHash"));
      assertEquals(hashes.of(unit.columns()),first.get("columnsHash"));
      assertEquals("POINT",json.valueToTree(head).required("geometry").required("geometryType").asText());
      assertEquals("fid",json.valueToTree(head).required("keys").required("declared").get(0).get(0).asText());
    }
  }

  @Test void f1_05_rejectionsAndReferencePrecedence() {
    var rejection=Map.of("code","XLSX_MERGED_CELLS","at","A1:F1");
    var rejected=sheets(List.of(Map.of("sheet","Notes","sheetIndex",0,"status","REJECTED","rejection",rejection)));
    assertEquals(Map.of("unitCount",1,"readyUnits",0),rejected.summary());
    assertEquals(true,rejected.inventory(XLSX,0,64).get("inventoryComplete"));
    String hash="sha256:"+"0".repeat(64);
    var error=assertThrows(DomainFailure.class,()->rejected.verify(new SourceUnitProfiles.Reference(id,2,"sheet:0",hash)));
    assertEquals("ONB_UNIT_REJECTED",error.code());
    assertEquals(rejection,error.properties().get("rejection"));
    assertEquals("ONB_UNIT_PROFILE_CHANGED",assertThrows(DomainFailure.class,()->rejected.verify(new SourceUnitProfiles.Reference(id,1,"sheet:5",hash))).code());
    assertEquals("ONB_PROFILE_UNIT_NOT_FOUND",assertThrows(DomainFailure.class,()->rejected.verify(new SourceUnitProfiles.Reference(id,2,"sheet:5",hash))).code());
    var ready=sheets(List.of(sheet("First",0)));
    assertEquals("ONB_UNIT_SCHEMA_CHANGED",assertThrows(DomainFailure.class,()->ready.verify(new SourceUnitProfiles.Reference(id,2,"sheet:0",hash))).code());
    var old=new SourceUnitProfiles(file("XLSX",Map.of("sheet","First","headerRow",1,"workbookSheets",1,"rows",0),List.of()),hashes);
    assertEquals(false,old.inventory(XLSX,0,64).get("inventoryComplete"));
    assertTrue(old.outdated(old.find("sheet:0")));
    var unsupported=new SourceUnitProfiles(file("ACCESS",Map.of(),List.of()),hashes);
    assertEquals("ONB_UNIT_FORMAT_UNSUPPORTED",assertThrows(DomainFailure.class,()->unsupported.inventory("application/x-msaccess",0,64)).code());
  }
}
