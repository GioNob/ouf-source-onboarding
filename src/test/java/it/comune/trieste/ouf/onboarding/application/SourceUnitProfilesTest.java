package it.comune.trieste.ouf.onboarding.application;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import java.io.ByteArrayOutputStream;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class SourceUnitProfilesTest {
  final ObjectMapper json=new ObjectMapper();
  final CanonicalHash hashes=new CanonicalHash(json);
  final UUID id=UUID.randomUUID(),asset=UUID.randomUUID();

  private Map<String,Object> retained(byte[] bytes,String media) throws Exception {
    var p=new ManagedFileProfiler().profile(bytes,media);
    return Map.of("profile_id",id,"asset_id",asset,"version",2,"format",p.format(),
        "metadata",json.readValue(json.writeValueAsBytes(p.metadata()),Map.class),"inferred_schema",json.readValue(json.writeValueAsBytes(p.columns()),List.class),
        "candidate_keys",p.candidateKeys(),"redacted_sample",p.sample());
  }

  @Test void csvViewFromHistoricalJsonHasStableHashIndependentOfSamplingAndDistinctCounts() throws Exception {
    var file=retained("id;email\n1;a\n2;b\n".getBytes(),"text/csv");
    var views=new SourceUnitProfiles(file,hashes);
    var page=views.profile("table:0",0,1);
    assertThat(page).containsEntry("schema","ouf.source-unit-profile.v1").containsEntry("recordModel","ONE_ROW_ONE_SOURCE_OBJECT")
        .containsEntry("nextOffset",1).containsEntry("columnCount",2).containsEntry("hasMore",true);
    var different=retained("id;email\n1;a\n2;b\n3;c\n".getBytes(),"text/csv");
    assertThat(new SourceUnitProfiles(different,hashes).profile("table:0",1,1)).containsEntry("schemaHash",page.get("schemaHash"))
        .containsEntry("sample",List.of(Map.of("email","[REDACTED]"),Map.of("email","[REDACTED]"),Map.of("email","[REDACTED]")));
    assertThat(views.units(0,1)).containsEntry("units",List.of(Map.of("name","table","unitRef","table:0","unitKind","TABLE",
        "recordCount",2,"columnCount",2,"hasGeometry",false)));
    var ref=new SourceUnitProfiles.Reference(id,2,"table:0",(String)page.get("schemaHash"));
    assertThat(views.verify(ref).ref()).isEqualTo("table:0");
    assertThatThrownBy(()->views.verify(new SourceUnitProfiles.Reference(id,2,"table:0","sha256:"+"0".repeat(64)))).hasMessage("ONB_UNIT_SCHEMA_CHANGED");
    assertThatThrownBy(()->views.verify(new SourceUnitProfiles.Reference(id,3,"table:0",ref.schemaHash()))).hasMessage("ONB_UNIT_PROFILE_CHANGED");
    assertThatThrownBy(()->views.verify(new SourceUnitProfiles.Reference(id,2,"table:1",ref.schemaHash()))).hasMessage("ONB_PROFILE_UNIT_NOT_FOUND");
  }

  @Test void twoSheetsHaveIndependentNativeEvidenceAndFixedReadParameters() throws Exception {
    byte[] bytes;
    try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
      var first=book.createSheet("first");first.createRow(0).createCell(0).setCellValue("code");
      first.createRow(1).createCell(0).setCellValue("001");
      var second=book.createSheet("second");var header=second.createRow(2);
      header.createCell(0).setCellValue("value");header.createCell(1).setCellValue("active");
      var row=second.createRow(3);row.createCell(0).setCellValue(12);row.createCell(1).setCellValue(true);
      book.write(out);bytes=out.toByteArray();
    }
    String media="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    var views=new SourceUnitProfiles(retained(bytes,media),hashes);
    assertThat(views.units(0,1)).containsEntry("nextOffset",1);
    assertThat(views.units(1,1)).containsEntry("units",List.of(Map.of("unitRef","sheet:1","unitKind","SHEET","name","second",
        "recordCount",1,"columnCount",2,"hasGeometry",false)));
    var second=views.profile("sheet:1",0,1);
    assertThat(second).containsEntry("readParameters",Map.of("xlsx",Map.of("sheet","second","headerRow",3)))
        .containsEntry("columns",List.of(Map.of("name","value","nativeType","NUMERIC","inferredType","LONG","nullable",false,"distinctValues",1,"role","ATTRIBUTE")));
    assertThat(new SourceUnitProfiles(retained(bytes,media),hashes).profile("sheet:1",1,1)).containsEntry("schemaHash",second.get("schemaHash"));
    assertThat(views.profile("sheet:0",0,1).get("schemaHash")).isNotEqualTo(second.get("schemaHash"));
  }

  @Test @SuppressWarnings("unchecked") void bothFeatureLayersRetainCrsDeclaredKeysAndHistoricalEvidence() throws Exception {
    byte[] bytes;
    try(var input=getClass().getResourceAsStream("/geopackage/cameras.gpkg")) {bytes=input.readAllBytes();}
    var file=retained(bytes,"application/geopackage+sqlite3");
    var views=new SourceUnitProfiles(file,hashes);
    assertThat(views.units(0,2).get("units")).asList().hasSize(2);
    var camera=views.profile("layer:1",0,128);
    assertThat(camera).containsEntry("unitName","cameras").containsEntry("recordModel","ONE_FEATURE_ONE_SOURCE_OBJECT")
        .containsEntry("readParameters",Map.of("geopackage",Map.of("layer","cameras")));
    var geometry=(Map<String,Object>)camera.get("geometry");
    assertThat(geometry).containsEntry("column","geom").containsEntry("type","POINT").containsEntry("dimensions",2)
        .containsEntry("srsId",4326).containsEntry("sourceCrs","EPSG:4326");
    assertThat(((Map<?,?>)camera.get("keys")).get("declared")).isEqualTo(List.of(List.of("fid")));
    var columns=(List<Map<String,Object>>)camera.get("columns");
    assertThat(columns).anySatisfy(c->assertThat(c).containsEntry("name","fid").containsEntry("nativeType","INTEGER"));
    assertThat((List<Map<String,Object>>)camera.get("sample")).allSatisfy(row->assertThat(row).containsEntry("geom","[GEOMETRY]"));
    assertThat(views.profile("layer:0",0,1)).containsEntry("unitName","cabinets");
    assertThat(new SourceUnitProfiles(file,hashes).profile("layer:1",1,1)).containsEntry("schemaHash",camera.get("schemaHash"));
    var layers=(List<Map<String,Object>>)((Map<?,?>)file.get("metadata")).get("layers");
    layers.forEach(layer->layer.remove("nativeColumns"));
    var historical=new SourceUnitProfiles(file,hashes).profile("layer:1",0,128);
    assertThat(historical.get("geometry")).isEqualTo(geometry);
    assertThat((List<Map<String,Object>>)historical.get("columns")).allSatisfy(c->assertThat(c).containsEntry("nativeType","UNKNOWN"));
  }
}
