package it.comune.trieste.ouf.onboarding.application.xlsx;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import java.util.zip.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.CsvSource;

class XlsxUnitReaderTest {
  private static final XlsxArchive.Limits LIMITS = XlsxArchive.Limits.defaults();
  private static final XlsxUnitReader READER = new XlsxUnitReader();

  static Stream<Arguments> vectors() throws IOException {
    try (var in = XlsxUnitReaderTest.class.getResourceAsStream("/contracts/xlsx-cell-text-v1.json")) {
      var contract = new ObjectMapper().readTree(in);
      assertThat(contract.required("version").textValue()).isEqualTo("1.1.0");
      assertThat(contract.required("cells").size()).isEqualTo(47);
      assertThat(contract.required("sheets").size()).isEqualTo(11);
      var seen = new HashSet<String>();
      var cases = new ArrayList<Arguments>();
      for (String group : List.of("cells", "sheets")) {
        assertThat(contract.required(group).size()).isPositive();
        for (var vector : contract.get(group)) {
          String id = vector.required("id").textValue();
          assertThat(seen.add(id)).as(id).isTrue();
          cases.add(Arguments.of(id, group, vector, contract.required("defaults")));
        }
      }
      return cases.stream();
    }
  }

  @ParameterizedTest(name="{0}") @MethodSource("vectors")
  void contractVector(String id, String group, JsonNode vector, JsonNode defaults) throws Exception {
    String system = vector.path("dateSystem").asText(defaults.required("dateSystem").textValue());
    String tz = vector.has("timeZone") ? vector.get("timeZone").textValue() : defaults.required("timeZone").textValue();
    Map<String,Integer> declared = vector.has("headerRow") ? Map.of("sheet:0",vector.get("headerRow").intValue()) : Map.of();
    var options = new XlsxUnitReader.Options(tz == null ? null : ZoneId.of(tz), declared, LIMITS);
    try (var book = new XSSFWorkbook()) {
      var props = book.getCTWorkbook().getWorkbookPr();
      if (props == null) props = book.getCTWorkbook().addNewWorkbookPr();
      props.setDate1904(system.equals("1904"));
      var sheet = book.createSheet(" Unit ");
      if (group.equals("cells")) {
        sheet.createRow(0).createCell(0).setCellValue("v");
        set(sheet.createRow(1).createCell(0), vector.required("cell"));
      } else {
        vector.required("rows").fields().forEachRemaining(entry -> {
          var row = sheet.createRow(Integer.parseInt(entry.getKey()) - 1);
          for (var value : entry.getValue()) row.createCell(row.getPhysicalNumberOfCells()).setCellValue(value.textValue());
        });
        vector.path("merged").forEach(r -> sheet.addMergedRegion(CellRangeAddress.valueOf(r.textValue())));
        if (vector.path("hidden").asBoolean()) {
          book.createSheet("Visible");
          book.setSheetHidden(0,true);
        }
      }
      var result = READER.read(bytes(book), options);
      assertThat(result.dateSystem()).isEqualTo(system);
      var unit = result.sheets().getFirst();
      assertThat(unit.name()).isEqualTo(" Unit ");
      var expected = vector.required("expect");
      if (expected.has("reject")) {
        assertThat(unit.status()).isEqualTo(XlsxUnitReader.Status.REJECTED);
        assertThat(unit.rejection().code()).isEqualTo(expected.get("reject").textValue());
        if (expected.has("at")) assertThat(unit.rejection().at()).isEqualTo(expected.get("at").textValue());
      } else {
        assertThat(unit.status()).isEqualTo(XlsxUnitReader.Status.READY);
        if (group.equals("cells")) {
          String text = expected.required("text").textValue();
          if (text.isEmpty()) {
            assertThat(unit.rows()).isEmpty();
            // Keep that row observable in a second workbook image with a populated neighbouring cell.
            sheet.getRow(0).createCell(1).setCellValue("keep");
            sheet.getRow(1).createCell(1).setCellValue("x");
            unit = READER.read(bytes(book), options).sheets().getFirst();
          }
          assertThat(unit.rows().getFirst().values().getFirst()).isEqualTo(text);
        } else {
          assertThat(unit.headerRow()).isEqualTo(expected.required("headerRow").intValue());
          assertThat(unit.recordCount()).isEqualTo(expected.required("recordCount").intValue());
          assertThat(unit.hidden()).isEqualTo(expected.path("hidden").asBoolean());
        }
      }
    }
  }

  private static void set(XSSFCell cell, JsonNode spec) {
    switch (spec.required("type").textValue()) {
      case "FORMULA" -> {
        cell.setCellFormula(spec.required("formula").textValue());
        var cached = spec.required("cached");
        if (!cached.isNull()) set(cell, cached);
      }
      case "NUMERIC" -> cell.setCellValue(spec.required("value").doubleValue());
      case "BOOLEAN" -> cell.setCellValue(spec.required("value").booleanValue());
      case "STRING" -> cell.setCellValue(spec.required("value").textValue());
      case "ERROR" -> cell.setCellErrorValue(FormulaError.forString(spec.required("value").textValue()).getCode());
      case "BLANK" -> cell.setBlank();
      default -> throw new AssertionError(spec);
    }
    if (spec.has("numberFormat")) {
      var book = cell.getSheet().getWorkbook();
      var style = book.createCellStyle();
      style.setDataFormat(book.createDataFormat().getFormat(spec.get("numberFormat").textValue()));
      cell.setCellStyle(style);
    }
  }

  static byte[] bytes(XSSFWorkbook book) throws IOException {
    var out = new ByteArrayOutputStream();
    book.write(out);
    return out.toByteArray();
  }

  static byte[] changePart(byte[] bytes, String part, UnaryOperator<String> edit) throws IOException {
    var target = new ByteArrayOutputStream();
    boolean found = false;
    try (var input = new ZipInputStream(new ByteArrayInputStream(bytes)); var output = new ZipOutputStream(target)) {
      for (ZipEntry e; (e = input.getNextEntry()) != null;) {
        output.putNextEntry(new ZipEntry(e.getName()));
        if (e.getName().equals(part)) {
          var xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
          String changed = edit.apply(xml);
          assertThat(changed).isNotEqualTo(xml);
          output.write(changed.getBytes(StandardCharsets.UTF_8));
          found = true;
        } else input.transferTo(output);
        output.closeEntry();
      }
    }
    assertThat(found).isTrue();
    return target.toByteArray();
  }

  @ParameterizedTest @CsvSource(value={"n|<v/>|invalid","n|<v>bad</v>|invalid","b|<v>2</v>|invalid","n|<v>0</v>|0"},delimiter='|')
  void checksSerializedFormulaCache(String type, String value, String expected) throws Exception {
    try (var book = new XSSFWorkbook()) {
      var sheet = book.createSheet();
      sheet.createRow(0).createCell(0).setCellValue("v");
      var cell = sheet.createRow(1).createCell(0);
      cell.setCellFormula("1+1");
      cell.setCellValue(2);
      var changed = changePart(bytes(book), "xl/worksheets/sheet1.xml", xml ->
          xml.replaceFirst("<c r=\"A2\"[^>]*>.*?</c>", "<c r=\"A2\" t=\"" + type + "\"><f>1+1</f>" + value + "</c>"));
      var unit = read(changed, LIMITS).getFirst();
      if (expected.equals("invalid")) assertThat(unit.rejection().code()).isEqualTo("XLSX_FORMULA_WITHOUT_CACHED_VALUE");
      else assertThat(unit.rows().getFirst().values()).containsExactly(expected);
    }
  }

  private static List<XlsxUnitReader.SheetUnit> read(byte[] bytes, XlsxArchive.Limits limits) throws IOException {
    return READER.read(bytes, new XlsxUnitReader.Options(null, Map.of(), limits)).sheets();
  }

  @Test void rejectionOfOneUnitNeverDiscardsItsNeighbours() throws Exception {
    try (var book = new XSSFWorkbook()) {
      book.createSheet("ready").createRow(0).createCell(0).setCellValue("v");
      var merged = book.createSheet("merged");
      merged.createRow(0).createCell(0).setCellValue("v");
      merged.addMergedRegion(CellRangeAddress.valueOf("A2:B2"));
      var missing = book.createSheet("missing");
      missing.createRow(0).createCell(0).setCellValue("v");
      missing.createRow(1).createCell(0).setCellFormula("1+1");
      var units = read(bytes(book), LIMITS);
      assertThat(units.get(0).status()).isEqualTo(XlsxUnitReader.Status.READY);
      assertThat(units.get(1).rejection()).isEqualTo(new XlsxUnitReader.Rejection("XLSX_MERGED_CELLS","A2:B2"));
      assertThat(units.get(2).rejection()).isEqualTo(new XlsxUnitReader.Rejection("XLSX_FORMULA_WITHOUT_CACHED_VALUE","A2"));
      book.removeSheetAt(0);
      assertThat(read(bytes(book), LIMITS)).allSatisfy(unit -> assertThat(unit.status()).isEqualTo(XlsxUnitReader.Status.REJECTED));
    }
  }

  @Test void rowAndColumnProtocolBoundariesAndSheetIsolation() throws Exception {
    try (var book = new XSSFWorkbook()) {
      var sheet = book.createSheet("rows");
      sheet.createRow(0).createCell(0).setCellValue("v");
      for (int i = 1; i <= LIMITS.maxRows(); i++) sheet.createRow(i).createCell(0).setCellValue(i);
      var wide = book.createSheet("columns").createRow(0);
      for (int i = 0; i < LIMITS.maxColumns(); i++) wide.createCell(i).setCellValue("c"+i);
      var result = read(bytes(book), LIMITS);
      assertThat(result.getFirst().recordCount()).isEqualTo(10000);
      assertThat(result.get(1).headers()).hasSize(1024);
      sheet.createRow(10001).createCell(0).setCellValue(10001);
      wide.createCell(1024).setCellValue("extra");
      book.createSheet("valid").createRow(0).createCell(0).setCellValue("v");
      result = read(bytes(book), LIMITS);
      assertThat(result.getFirst().rejection().code()).isEqualTo("XLSX_ROW_LIMIT");
      assertThat(result.get(1).rejection().code()).isEqualTo("XLSX_COLUMN_LIMIT");
      assertThat(result.get(2).status()).isEqualTo(XlsxUnitReader.Status.READY);
    }
  }

  @Test void chartSheetIsExcludedFromInventoryAndHeaderOptionsUseInventoryPosition() throws Exception {
    try (var book = new XSSFWorkbook()) {
      book.createSheet("Chart");
      var sheet = book.createSheet(" Data ");
      sheet.createRow(0).createCell(0).setCellValue("title");
      sheet.createRow(2).createCell(0).setCellValue("value");
      sheet.createRow(5).createCell(0).setCellValue(1);
      sheet.createRow(6).createCell(0).setCellValue("one");
      byte[] image = changePart(bytes(book), "[Content_Types].xml", xml ->
          xml.replaceFirst("worksheet\\+xml", "chartsheet+xml"));
      image = changePart(image, "xl/_rels/workbook.xml.rels", xml ->
          xml.replaceFirst("/worksheet\"", "/chartsheet\""));
      image = changePart(image, "xl/worksheets/sheet1.xml", xml ->
          "<chartsheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"/>");
      var result = READER.read(image, new XlsxUnitReader.Options(null,Map.of("sheet:0",3),LIMITS));
      assertThat(result.sheets()).hasSize(1);
      var unit = result.sheets().getFirst();
      assertThat(unit.unitRef()).isEqualTo("sheet:0");
      assertThat(unit.sheetIndex()).isEqualTo(1);
      assertThat(unit.headers()).containsExactly("value");
      assertThat(unit.rows()).extracting(XlsxUnitReader.DataRow::ordinal).containsExactly(5,6);
      assertThat(unit.nativeTypes()).containsExactly(XlsxUnitReader.NativeType.MIXED);
    }
  }
}
