package it.comune.trieste.ouf.onboarding;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

final class ManagedIntakeFixtures {
  static byte[] workbook() throws IOException {
    try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
      var sheet = book.createSheet("Test");
      sheet.createRow(0).createCell(0).setCellValue("id");
      sheet.createRow(1).createCell(0).setCellValue(1);
      book.write(out);
      return out.toByteArray();
    }
  }
}
