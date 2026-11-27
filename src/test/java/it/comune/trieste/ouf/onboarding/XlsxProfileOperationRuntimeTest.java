package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;

import it.comune.trieste.ouf.onboarding.application.*;
import it.comune.trieste.ouf.onboarding.domain.*;
import java.io.ByteArrayOutputStream;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

/** Task-62f: XLSX v1 profiles and profiling operations (contract ouf.extraction-unit.v1 §4, §5, §8.1). */
@SpringBootTest
class XlsxProfileOperationRuntimeTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry props) {
    props.add("spring.datasource.url",()->System.getenv("OUF_ONB_DB_URL"));
    props.add("spring.datasource.username",()->System.getenv("OUF_ONB_DB_USER"));
    props.add("spring.datasource.password",()->System.getenv("OUF_ONB_DB_PASSWORD"));
    props.add("ouf.onboarding.source-time-zone",()->"Europe/Rome");
  }
  @Autowired ManagedFileService files;
  @Autowired CanonicalHash hashes;
  @Autowired JdbcClient db;
  @Autowired OnboardingService onboarding;
  static final String TENANT="fixture-tenant", SUBJECT="human:test";
  static final String XLSX="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

  @BeforeEach void resetFixtures() {
    db.sql("truncate ouf_onboarding.source,ouf_onboarding.managed_file_asset,ouf_onboarding.file_profile_job cascade").update();
  }

  /** Valid sheet; merged cells in the data; formula without cached value; a title above a two-column header. */
  static byte[] workbook() throws Exception {
    try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
      var ready = book.createSheet("Edicole");
      var h = ready.createRow(0); h.createCell(0).setCellValue("codice"); h.createCell(1).setCellValue("aperta il");
      var dates = book.createCellStyle(); dates.setDataFormat(book.createDataFormat().getFormat("yyyy-mm-dd hh:mm"));
      for (int i = 1; i <= 3; i++) {
        var r = ready.createRow(i); r.createCell(0).setCellValue("E" + i);
        var c = r.createCell(1); c.setCellValue(46350.4375 + i); c.setCellStyle(dates);
      }
      var merged = book.createSheet("Note");
      var m = merged.createRow(0); m.createCell(0).setCellValue("a"); m.createCell(1).setCellValue("b");
      var d = merged.createRow(1); d.createCell(0).setCellValue("x"); d.createCell(1).setCellValue("y");
      merged.addMergedRegion(new CellRangeAddress(1, 1, 0, 1));
      var formulas = book.createSheet("Calcoli");
      var f = formulas.createRow(0); f.createCell(0).setCellValue("totale");
      formulas.createRow(1).createCell(0).setCellFormula("1+1");
      var titled = book.createSheet("Con titolo");
      titled.createRow(0).createCell(0).setCellValue("Elenco edicole");
      var th = titled.createRow(2); th.createCell(0).setCellValue("id"); th.createCell(1).setCellValue("nome");
      var tr = titled.createRow(3); tr.createCell(0).setCellValue("A1"); tr.createCell(1).setCellValue("Alfa");
      book.write(out);
      return out.toByteArray();
    }
  }

  private UUID register(byte[] bytes,String media) {
    return (UUID)files.registerOwned(null,"object://staging/"+UUID.randomUUID(),hashes.ofBytes(bytes),media,
        bytes.length,SUBJECT,"retention://fixture",TENANT).get("asset_id");
  }

  private Map<String,Object> run(Map<String,Object> job,byte[] bytes) {
    var lease=files.claimProfile("test",Duration.ofMinutes(2),Instant.now()).orElseThrow();
    assertThat(lease.get("job_id")).isEqualTo(job.get("jobId"));
    return files.completeProfile((UUID)job.get("jobId"),"test",((Number)lease.get("generation")).longValue(),bytes,"object://samples/fixture");
  }

  @Test void f62f_01_workbookBecomesUnitsWithStableRejections() throws Exception {
    byte[] bytes=workbook();
    UUID asset=register(bytes,XLSX);
    var done=run(files.requestProfile(asset,"k1",null),bytes);
    assertThat(done).containsEntry("status","SUCCEEDED");
    assertThat(done.get("unitSummary")).isEqualTo(Map.of("unitCount",4,"readyUnits",1,"singleReadyUnitRef","sheet:0"));
    UUID profile=(UUID)done.get("resultRef");
    var inventory=files.unitView(null,profile,TENANT,SUBJECT,"UNITS",null,null,null,null);
    @SuppressWarnings("unchecked") var units=(List<Map<String,Object>>)inventory.get("units");
    assertThat(units).extracting(u->u.get("status")).containsExactly("READY","REJECTED","REJECTED","REJECTED");
    assertThat(units.get(1)).extracting(u->((Map<?,?>)u.get("rejection")).get("code")).isEqualTo("XLSX_MERGED_CELLS");
    assertThat(units.get(2)).extracting(u->((Map<?,?>)u.get("rejection")).get("code")).isEqualTo("XLSX_FORMULA_WITHOUT_CACHED_VALUE");
    // Default header = first non-empty row: under the title, column B is data outside the header.
    assertThat(units.get(3)).extracting(u->((Map<?,?>)u.get("rejection")).get("code")).isEqualTo("XLSX_DATA_OUTSIDE_HEADER");
    assertThat(inventory).containsEntry("inventoryComplete",true);
    var unit=files.unitView(null,profile,TENANT,SUBJECT,"UNIT","sheet:0",0,128,null);
    assertThat(unit).containsEntry("unitKind","SHEET").containsEntry("status","READY").containsEntry("recordCount",3);
    @SuppressWarnings("unchecked") var columns=(List<Map<String,Object>>)unit.get("columns");
    assertThat(columns.get(1)).containsEntry("nativeType","DATETIME").containsEntry("dataType","DATETIME").containsEntry("baseType","datetime");
    @SuppressWarnings("unchecked") var xlsx=(Map<String,Object>)((Map<String,Object>)unit.get("readParameters")).get("xlsx");
    assertThat(xlsx).containsEntry("sheet","Edicole").containsEntry("sheetIndex",0).containsEntry("headerRow",1)
        .containsEntry("dateSystem","1900").containsEntry("timeZone","Europe/Rome").containsEntry("cellText","ouf.xlsx-cell-text.v1");
  }

  @Test void f62f_02_sameOperationSameJobDifferentOptionsNewProfile() throws Exception {
    byte[] bytes=workbook();
    UUID asset=register(bytes,XLSX);
    var first=files.requestProfile(asset,"k1",null);
    assertThat(files.requestProfile(asset,"k2",null)).containsEntry("jobId",first.get("jobId"));
    assertThat(files.requestProfile(asset,"k1",null)).containsEntry("jobId",first.get("jobId"));
    var done=run(first,bytes);
    var declared=files.requestProfile(asset,"k3",Map.of("sheet:3",3));
    assertThat(declared.get("jobId")).isNotEqualTo(first.get("jobId"));
    assertThatThrownBy(()->files.requestProfile(asset,"k3",Map.of("sheet:3",2)))
        .isInstanceOf(DomainFailure.class).hasMessageContaining("different profiling operation");
    var second=run(declared,bytes);
    assertThat(second.get("resultRef")).isNotEqualTo(done.get("resultRef"));
    UUID profile=(UUID)second.get("resultRef");
    var unit=files.unitView(null,profile,TENANT,SUBJECT,"UNIT","sheet:3",0,128,null);
    assertThat(unit).containsEntry("status","READY").containsEntry("recordCount",1);
    assertThat(((Number)unit.get("profileVersion")).intValue()).isEqualTo(2);
  }

  @Test void f62f_03_concurrentRequestsShareOneOperation() throws Exception {
    byte[] bytes=workbook();
    UUID asset=register(bytes,XLSX);
    var pool=Executors.newFixedThreadPool(2);
    try {
      var a=pool.submit(()->files.requestProfile(asset,"ka",Map.of("sheet:0",1)));
      var b=pool.submit(()->files.requestProfile(asset,"kb",Map.of("sheet:0",1)));
      assertThat(a.get(30,TimeUnit.SECONDS).get("jobId")).isEqualTo(b.get(30,TimeUnit.SECONDS).get("jobId"));
    } finally { pool.shutdownNow(); }
    assertThat(db.sql("select count(*) from ouf_onboarding.file_profile_job where asset_id=:a").param("a",asset).query(Long.class).single()).isEqualTo(1L);
  }

  @Test void f62f_04_readOptionsAreXlsxOnlyAndCsvIsUnchanged() {
    byte[] csv="id\n1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    UUID asset=register(csv,"text/csv");
    assertThatThrownBy(()->files.requestProfile(asset,"k1",Map.of("sheet:0",1)))
        .isInstanceOf(DomainFailure.class).hasMessageContaining("XLSX");
    var job=files.requestProfile(asset,"k1",null);
    assertThat(job).doesNotContainKeys("operation_key","read_options");
    assertThat(db.sql("select operation_key is null and read_options is null from ouf_onboarding.file_profile_job where asset_id=:a")
        .param("a",asset).query(Boolean.class).single()).isTrue();
  }

  @Test void f62f_05_invalidDeclarationsAreRejectedBeforeAnyJob() throws Exception {
    UUID asset=register(workbook(),XLSX);
    assertThatThrownBy(()->files.requestProfile(asset,"k1",Map.of("table:0",1))).isInstanceOf(DomainFailure.class);
    assertThatThrownBy(()->files.requestProfile(asset,"k1",Map.of("sheet:0",0))).isInstanceOf(DomainFailure.class);
    assertThatThrownBy(()->files.requestProfile(asset,"k1",Map.of())).isInstanceOf(DomainFailure.class);
    assertThat(db.sql("select count(*) from ouf_onboarding.file_profile_job where asset_id=:a").param("a",asset).query(Long.class).single()).isZero();
  }
  @Test void f62f_06_hiddenSheetsAndUndeclaredZone() throws Exception {
    byte[] bytes;
    try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
      var hidden = book.createSheet("Nascosto");
      hidden.createRow(0).createCell(0).setCellValue("id"); hidden.createRow(1).createCell(0).setCellValue("A");
      book.setSheetHidden(0, true);
      var timed = book.createSheet("Orari");
      timed.createRow(0).createCell(0).setCellValue("quando");
      var style = book.createCellStyle(); style.setDataFormat(book.createDataFormat().getFormat("yyyy-mm-dd hh:mm"));
      var cell = timed.createRow(1).createCell(0); cell.setCellValue(46350.5); cell.setCellStyle(style);
      book.write(out); bytes = out.toByteArray();
    }
    // An installation without a declared zone: only the worksheet with date and time is rejected (§8.4).
    var profile = new ManagedFileProfiler().profile(bytes, XLSX);
    @SuppressWarnings("unchecked") var sheets = (List<Map<String,Object>>)profile.metadata().get("sheets");
    assertThat(sheets.get(0)).containsEntry("hidden", true).containsEntry("status", "READY");
    assertThat(sheets.get(1)).containsEntry("status", "REJECTED");
    assertThat(((Map<?,?>)sheets.get(1).get("rejection")).get("code")).isEqualTo("XLSX_TIME_ZONE_UNDECLARED");
    assertThat(profile.metadata()).doesNotContainKey("timeZone");
  }

  @Test void f62f_07_workbookWithoutReadySheetsIsPersisted() throws Exception {
    byte[] bytes;
    try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
      var sheet = book.createSheet("Errori");
      sheet.createRow(0).createCell(0).setCellValue("totale");
      sheet.createRow(1).createCell(0).setCellFormula("1+1");
      book.write(out); bytes = out.toByteArray();
    }
    UUID asset = register(bytes, XLSX);
    var done = run(files.requestProfile(asset, "k1", null), bytes);
    assertThat(done).containsEntry("status", "SUCCEEDED");
    assertThat(done.get("unitSummary")).isEqualTo(Map.of("unitCount", 1, "readyUnits", 0));
    var inventory = files.unitView(null, (UUID)done.get("resultRef"), TENANT, SUBJECT, "UNITS", null, null, null, null);
    assertThat(inventory.get("units")).asList().hasSize(1);
  }

  @Test void f62f_08_historicalXlsxIsReprofiledAsANewOperation() throws Exception {
    byte[] bytes = workbook();
    UUID asset = register(bytes, XLSX);
    // A profile written by the predecessor's legacy reader (sheet 0 only, no reader declaration). Profiles are immutable.
    db.sql("""
        insert into ouf_onboarding.file_profile(profile_id,asset_id,version,format,metadata,inferred_schema,candidate_keys,sample_ref,redacted_sample)
        values(gen_random_uuid(),:a,1,'XLSX','{"sheet":"Edicole","workbookSheets":4,"headerRow":1,"rows":3,"columns":2,"formulas":"REJECTED"}',
        '[{"name":"codice","dataType":"STRING","nullable":false,"distinctValues":3}]','["codice"]','object://samples/legacy','[]')
        """).param("a", asset).update();
    var fresh = files.requestProfile(asset, "v1", null);
    var done = run(fresh, bytes);
    UUID profile = (UUID)done.get("resultRef");
    var inventory = files.unitView(null, profile, TENANT, SUBJECT, "UNITS", null, null, null, null);
    assertThat(inventory).containsEntry("inventoryComplete", true).containsEntry("readerVersion", "ouf.xlsx-cell-text.v1");
    assertThat(((Number)inventory.get("profileVersion")).intValue()).isEqualTo(2);
  }

  @Test void f62f_09_operationsPerAssetAreFinite() throws Exception {
    UUID asset = register(workbook(), XLSX);
    Object previous = ReflectionTestUtils.getField(files, "maxProfileOperations");
    ReflectionTestUtils.setField(files, "maxProfileOperations", 2);
    try {
      files.requestProfile(asset, "k1", null);
      files.requestProfile(asset, "k2", Map.of("sheet:3", 3));
      assertThatThrownBy(() -> files.requestProfile(asset, "k3", Map.of("sheet:3", 2)))
          .isInstanceOf(DomainFailure.class).hasMessageContaining("profiling operations");
      assertThat(files.requestProfile(asset, "k4", Map.of("sheet:3", 3))).isNotNull();
    } finally {
      ReflectionTestUtils.setField(files, "maxProfileOperations", previous);
    }
  }

  @Test @SuppressWarnings("unchecked") void f62f_10_draftFreezesTheReadParametersOfTheRealUnit() throws Exception {
    byte[] bytes = workbook();
    UUID asset = register(bytes, XLSX);
    UUID profile = (UUID)run(files.requestProfile(asset, "k1", null), bytes).get("resultRef");
    var unit = files.unitView(null, profile, TENANT, SUBJECT, "UNIT", "sheet:0", 0, 128, null);
    var pin = new SourceUnitProfiles.Reference(profile, ((Number)unit.get("profileVersion")).intValue(), "sheet:0", (String)unit.get("schemaHash"));
    var actor = new OnboardingService.Actor(SUBJECT, "HUMAN_USER");
    var draft = files.onboardIdempotent(null, null, "edicole-xlsx", "Edicole", "owner", "urn:fixture:class", List.of("core@1"),
        List.of("codice"), List.of(new ManagedFileService.FieldDecision("codice", "INCLUDE", "OPEN", "urn:fixture:codice", "IDENTITY", null, null, null),
            new ManagedFileService.FieldDecision("aperta il", "EXCLUDE", "OPEN", null, null, null, null, null)),
        null, actor, "fixture", "create-1", TENANT, null, null, null, null, pin);
    var version = onboarding.version("edicole-xlsx", (UUID)draft.get("onboardingVersionId"));
    var runtime = (Map<String,Object>)((Map<String,Object>)((Map<String,Object>)version.get("configuration")).get("extractionProfile")).get("runtime");
    // The DRAFT carries exactly what the compiler turns into managed-xlsx-v1 (f1_06): unit, kind and read parameters.
    assertThat(runtime).containsEntry("unitRef", "sheet:0").containsEntry("unitKind", "SHEET")
        .containsEntry("recordModel", "ONE_ROW_ONE_SOURCE_OBJECT").containsEntry("readParameters", unit.get("readParameters"))
        .doesNotContainKey("csvDelimiter");
  }
}
