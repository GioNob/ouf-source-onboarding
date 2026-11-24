package it.comune.trieste.ouf.onboarding.application.xlsx;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;

/** Deterministic ouf.xlsx-cell-text.v1 reader. Row ordinals and sheetIndex are physical POI indices. */
public final class XlsxUnitReader {
  public static final String RULE = "ouf.xlsx-cell-text.v1";
  public record Options(ZoneId zone, Map<String,Integer> headerRows, XlsxArchive.Limits limits) {
    public Options {
      headerRows = Map.copyOf(headerRows);
      Objects.requireNonNull(limits);
      headerRows.forEach((unit, row) -> {
        if (!unit.matches("sheet:(0|[1-9][0-9]{0,3})") || row < 1 || row > 1048576)
          throw new IllegalArgumentException("Invalid declared header row");
      });
    }
  }
  public enum Status { READY, REJECTED }
  public enum NativeType { EMPTY, STRING, BOOLEAN, NUMERIC, DATE, TIME, DATETIME, MIXED }
  public record Rejection(String code, String at) {}
  public record DataRow(int ordinal, List<String> values) {}
  public record SheetUnit(String unitRef, int sheetIndex, String name, boolean hidden, Status status,
                          Rejection rejection, Integer headerRow, List<String> headers, List<DataRow> rows,
                          List<NativeType> nativeTypes) {
    public int recordCount() { return rows.size(); }
  }
  public record Result(String dateSystem, List<SheetUnit> sheets) {}
  private record Value(String text, NativeType type) {}
  private static final Value EMPTY = new Value("", NativeType.EMPTY);
  private static final Pattern HEADER_WHITESPACE = Pattern.compile("^\\p{IsWhite_Space}+|\\p{IsWhite_Space}+$");
  private static final Pattern TOKENS = Pattern.compile("\"[^\"]*\"|\\\\.|\\[[^\\]]*\\]|am/pm|y+|d+|m+|h+|s+", Pattern.CASE_INSENSITIVE);
  private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX", Locale.ROOT);
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

  public Result read(byte[] bytes, Options options) throws IOException {
    XlsxArchive.verify(bytes, options.limits());
    try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
      boolean epoch = workbook.isDate1904();
      var units = new ArrayList<SheetUnit>();
      for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
        var sheet = workbook.getSheetAt(i);
        if (sheet instanceof XSSFChartSheet) continue;
        if (units.size() >= options.limits().maxSheets()) throw XlsxArchive.limit("xlsx.max-sheets");
        String ref = "sheet:" + units.size();
        boolean hidden = workbook.isSheetHidden(i) || workbook.isSheetVeryHidden(i);
        Integer header = options.headerRows().get(ref);
        try {
          if (header == null) header = firstRow(sheet, epoch, options.zone());
          for (var range : sheet.getMergedRegions()) {
            if (range.getLastRow() >= header - 1) throw reject("XLSX_MERGED_CELLS", range.formatAsString());
          }
          var names = headers(sheet, header, epoch, options);
          var types = new ArrayList<>(Collections.nCopies(names.size(), NativeType.EMPTY));
          var rows = new ArrayList<DataRow>();
          for (Row row : sheet) {
            if (row.getRowNum() < header) continue;
            List<String> values = null;
            for (Cell cell : row) {
              Value value = lexical((XSSFCell) cell, epoch, options.zone());
              if (value.text().isEmpty()) continue;
              int column = cell.getColumnIndex();
              if (column >= names.size()) throw reject("XLSX_DATA_OUTSIDE_HEADER", cell.getAddress().formatAsString());
              if (values == null) {
                if (rows.size() == options.limits().maxRows()) throw reject("XLSX_ROW_LIMIT", cell.getAddress().formatAsString());
                values = new ArrayList<>(Collections.nCopies(names.size(), ""));
              }
              values.set(column, value.text());
              var before = types.get(column);
              types.set(column, before == NativeType.EMPTY || before == value.type() ? value.type() : NativeType.MIXED);
            }
            if (values != null) rows.add(new DataRow(row.getRowNum(), List.copyOf(values)));
          }
          units.add(new SheetUnit(ref, i, sheet.getSheetName(), hidden, Status.READY, null, header, names,
              List.copyOf(rows), List.copyOf(types)));
        } catch (Rejected e) {
          units.add(new SheetUnit(ref, i, sheet.getSheetName(), hidden, Status.REJECTED, e.reason, header,
              List.of(), List.of(), List.of()));
        }
      }
      return new Result(epoch ? "1904" : "1900", List.copyOf(units));
    }
  }

  private static int firstRow(XSSFSheet sheet, boolean epoch, ZoneId zone) {
    for (Row row : sheet) {
      for (Cell cell : row) {
        if (!lexical((XSSFCell) cell, epoch, zone).text().isEmpty()) return row.getRowNum() + 1;
      }
    }
    throw reject("XLSX_EMPTY_SHEET", null);
  }

  private static List<String> headers(XSSFSheet sheet, int number, boolean epoch, Options options) {
    var row = sheet.getRow(number - 1);
    var names = new ArrayList<String>();
    var unique = new HashSet<String>();
    if (row != null) {
      for (Cell cell : row) {
        var raw = lexical((XSSFCell) cell, epoch, options.zone()).text();
        if (raw.isEmpty()) continue;
        int col = cell.getColumnIndex();
        String at = cell.getAddress().formatAsString();
        if (col >= options.limits().maxColumns()) throw reject("XLSX_COLUMN_LIMIT", at);
        String name = HEADER_WHITESPACE.matcher(raw).replaceAll("");
        if (col != names.size() || name.isEmpty() || name.codePointCount(0,name.length()) > 256 || !unique.add(name))
          throw reject("XLSX_HEADER_INVALID", at);
        names.add(name);
      }
    }
    if (names.isEmpty()) {
      // A declared blank header differs from a wholly empty worksheet. Preamble formulas are ignored.
      for (Row candidate : sheet) {
        for (Cell cell : candidate) {
          if (cell.getCellType() != CellType.BLANK && (cell.getCellType() != CellType.STRING || !cell.getStringCellValue().isEmpty()))
            throw reject("XLSX_HEADER_INVALID", "A" + number);
        }
      }
      throw reject("XLSX_EMPTY_SHEET", null);
    }
    return List.copyOf(names);
  }

  private static Value lexical(XSSFCell cell, boolean epoch, ZoneId zone) {
    CellType type = cell.getCellType();
    String at = cell.getAddress().formatAsString();
    boolean formula = type == CellType.FORMULA;
    if (formula) {
      if (!cell.getCTCell().isSetV()) throw reject("XLSX_FORMULA_WITHOUT_CACHED_VALUE", at);
      type = cell.getCachedFormulaResultType();
      // POI may report BLANK for an empty numeric cache. A string cache may legitimately be empty.
      String raw = cell.getRawValue();
      if (type == CellType.BLANK || type == CellType.NUMERIC && (raw == null || raw.isEmpty())
          || type == CellType.BOOLEAN && !"0".equals(raw) && !"1".equals(raw))
        throw reject("XLSX_FORMULA_WITHOUT_CACHED_VALUE", at);
    }
    return switch (type) {
      case BLANK, _NONE -> EMPTY;
      case STRING -> new Value(cell.getStringCellValue(), NativeType.STRING);
      case BOOLEAN -> new Value(Boolean.toString(cell.getBooleanCellValue()), NativeType.BOOLEAN);
      case NUMERIC -> {
        double value;
        try {
          value = formula ? new BigDecimal(cell.getRawValue()).doubleValue() : cell.getNumericCellValue();
          if (!Double.isFinite(value)) throw new NumberFormatException();
        } catch (NumberFormatException e) {
          throw reject(formula ? "XLSX_FORMULA_WITHOUT_CACHED_VALUE" : "XLSX_CELL_ERROR", at);
        }
        yield number(value, cell.getCellStyle().getDataFormatString(), epoch, zone, at);
      }
      default -> throw reject("XLSX_CELL_ERROR", at);
    };
  }

  private static Value number(double v, String format, boolean epoch, ZoneId zone, String at) {
    NativeType type = formatType(format);
    if (type == NativeType.NUMERIC)
      return new Value(new BigDecimal(Double.toString(v)).stripTrailingZeros().toPlainString(), type);
    if (v < 0 || v >= (epoch ? 2957004 : 2958466) || type != NativeType.TIME && !epoch && v < 61)
      throw reject("XLSX_DATE_OUT_OF_RANGE", at);
    LocalDateTime local = DateUtil.getLocalDateTime(type == NativeType.TIME ? v - Math.floor(v) : v, epoch, true);
    if (local == null || local.getYear() > 9999) throw reject("XLSX_DATE_OUT_OF_RANGE", at);
    if (type == NativeType.TIME) return new Value(TIME.format(local), type);
    if (type == NativeType.DATE) return new Value(local.toLocalDate().toString(), type);
    if (zone == null) throw reject("XLSX_TIME_ZONE_UNDECLARED", at);
    return new Value(DATETIME.format(ZonedDateTime.ofLocal(local, zone, null)), type);
  }

  private static NativeType formatType(String format) {
    var matcher = TOKENS.matcher(format == null ? "" : format);
    var tokens = new ArrayList<Character>();
    while (matcher.find()) {
      String token = matcher.group().toLowerCase(Locale.ROOT);
      char first = token.charAt(0);
      if (first == '[' && token.matches("\\[(h+|m+|s+)\\]")) return NativeType.NUMERIC;
      if (first != '[' && first != '"' && first != '\\') tokens.add(first);
    }
    boolean date = false, time = false;
    for (int i = 0; i < tokens.size(); i++) {
      char token = tokens.get(i);
      boolean minute = token == 'm' && (i > 0 && tokens.get(i-1) == 'h' || i+1 < tokens.size() && tokens.get(i+1) == 's');
      if (token == 'd' || token == 'y' || token == 'm' && !minute) date = true;
      else time = true;
    }
    if (date) return time ? NativeType.DATETIME : NativeType.DATE;
    return time ? NativeType.TIME : NativeType.NUMERIC;
  }

  private static Rejected reject(String code, String at) { return new Rejected(new Rejection(code, at)); }
  private static final class Rejected extends RuntimeException {
    final Rejection reason;
    Rejected(Rejection reason) { super(reason.code()); this.reason = reason; }
  }
}
