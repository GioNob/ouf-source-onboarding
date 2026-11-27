package it.comune.trieste.ouf.onboarding.application.xlsx;

import static org.assertj.core.api.Assertions.*;
import static it.comune.trieste.ouf.onboarding.application.ManagedFileContentInspector.*;
import it.comune.trieste.ouf.onboarding.application.ManagedFileContentInspector;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;

class ManagedFileContentInspectorTest {
  static final String PREFIX = "ouf.onboarding.files.";

  @Test void csvLeavesEncodingAndNulToTheProfiler() {
    var inspector = new ManagedFileContentInspector(new MockEnvironment());
    var csv = inspector.requireFormat(CSV);
    inspector.verify(csv, new byte[]{0, (byte)0xff, 13, 10});
    for (byte[] signature : List.of(HexFormat.of().parseHex("d0cf11e0a1b11ae1"), new byte[]{80,75,3,4},
        "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII))) {
      mismatch(() -> inspector.verify(csv, signature));
      mismatch(() -> inspector.verify(inspector.requireFormat(XLSX), signature));
    }
    mismatch(() -> inspector.verify(inspector.requireFormat(XLSX), "id\n1\n".getBytes(StandardCharsets.UTF_8)));
  }

  @Test void sqliteRequiresOptInAndBigEndianApplicationId() {
    var defaults = new ManagedFileContentInspector(new MockEnvironment());
    assertThatThrownBy(() -> defaults.requireFormat(GPKG)).isInstanceOfSatisfying(DomainFailure.class,
        e -> {assertThat(e.status().value()).isEqualTo(415); assertThat(e.code()).isEqualTo("ONB_FILE_MEDIA_TYPE_UNSUPPORTED");});
    var inspector = new ManagedFileContentInspector(new MockEnvironment().withProperty(PREFIX+"media-types",GPKG));
    var format = inspector.requireFormat(GPKG);
    byte[] header = new byte[100];
    ByteBuffer.wrap(header).put("SQLite format 3\0".getBytes(StandardCharsets.US_ASCII)).putInt(68,0x47504b47);
    inspector.verify(format,header);
    header[71] = 0;
    mismatch(() -> inspector.verify(format,header));
  }

  @Test void macroPartAndRenamedMacroWorkbookAreRejected() throws Exception {
    try (var book = new XSSFWorkbook()) {
      book.createSheet();
      byte[] original = XlsxUnitReaderTest.bytes(book);
      var inspector = new ManagedFileContentInspector(new MockEnvironment());
      var format = inspector.requireFormat(XLSX);
      inspector.verify(format,original);
      mismatch(() -> inspector.verify(format,withPart(original,"xl/vbaProject.bin",new byte[]{0})));
      var macro = XlsxUnitReaderTest.changePart(original,"[Content_Types].xml",xml ->
          xml.replace("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml",
              "application/vnd.ms-excel.sheet.macroEnabled.main+xml"));
      mismatch(() -> inspector.verify(format,macro));
      mismatch(() -> inspector.verify(inspector.requireFormat(CSV), original));
    }
  }

  @ParameterizedTest @CsvSource({"max-entry-bytes,8192","max-uncompressed-bytes,15000","min-inflate-ratio,0.02","max-entries,1","max-sheets,1"})
  void boundedArchiveReportsTheSpecificLimit(String name, String value) throws Exception {
    var env = new MockEnvironment().withProperty(PREFIX+"xlsx."+name,value);
    // Isolate each limit. Entries and total stay small enough to run in a unit test.
    if (!name.equals("min-inflate-ratio")) env.withProperty(PREFIX+"xlsx.min-inflate-ratio","0.00001");
    var inspector = new ManagedFileContentInspector(env);
    try (var book = new XSSFWorkbook()) {
      book.createSheet("one");
      book.createSheet("two");
      var image = withPart(XlsxUnitReaderTest.bytes(book),"synthetic-padding",new byte[12000]);
      assertThatThrownBy(() -> inspector.verify(inspector.requireFormat(XLSX),image))
          .isInstanceOfSatisfying(DomainFailure.class, e -> {
            assertThat(e.status().value()).isEqualTo(422);
            assertThat(e.code()).isEqualTo("ONB_FILE_LIMIT_EXCEEDED");
            assertThat(e.properties()).containsEntry("limit","xlsx."+name);
          });
    } finally {
      new ManagedFileContentInspector(new MockEnvironment());
    }
  }

  @ParameterizedTest @CsvSource({"media-types,application/pdf","xlsx.max-uncompressed-bytes,268435457",
      "xlsx.max-entry-bytes,0","xlsx.max-entries,10001","xlsx.min-inflate-ratio,NaN","xlsx.max-sheets,65",
      "xlsx.max-rows,10001","xlsx.max-columns,1025","csv.max-bytes,10485761","gpkg.max-bytes,-1"})
  void rejectsInvalidInstallationPolicy(String key, String value) {
    assertThatThrownBy(() -> new ManagedFileContentInspector(new MockEnvironment().withProperty(PREFIX+key,value)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test void rejectsInvalidZoneAndExposesEffectiveFileLimit() {
    assertThatThrownBy(() -> new ManagedFileContentInspector(new MockEnvironment()
        .withProperty("ouf.onboarding.source-time-zone","+02:00"))).isInstanceOf(IllegalArgumentException.class);
    var inspector = new ManagedFileContentInspector(new MockEnvironment().withProperty(PREFIX+"csv.max-bytes","4")
        .withProperty("ouf.onboarding.source-time-zone","Europe/Rome"));
    assertThat(inspector.sourceTimeZone().getId()).isEqualTo("Europe/Rome");
    var format = inspector.requireFormat(CSV);
    inspector.verify(format,new byte[4]);
    assertThatThrownBy(() -> inspector.verify(format,new byte[5])).isInstanceOfSatisfying(DomainFailure.class,
        e -> assertThat(e.properties()).containsEntry("limit","csv.max-bytes"));
  }

  private static void mismatch(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call).isInstanceOfSatisfying(DomainFailure.class, e -> {
      assertThat(e.status().value()).isEqualTo(422);
      assertThat(e.code()).isEqualTo("ONB_FILE_MEDIA_TYPE_MISMATCH");
    });
  }

  private static byte[] withPart(byte[] image, String part, byte[] data) throws IOException {
    var target = new ByteArrayOutputStream();
    try (var input = new ZipInputStream(new ByteArrayInputStream(image)); var output = new ZipOutputStream(target)) {
      for (ZipEntry entry; (entry = input.getNextEntry()) != null;) {
        output.putNextEntry(new ZipEntry(entry.getName()));
        input.transferTo(output);
        output.closeEntry();
      }
      output.putNextEntry(new ZipEntry(part));
      output.write(data);
      output.closeEntry();
    }
    return target.toByteArray();
  }
}
