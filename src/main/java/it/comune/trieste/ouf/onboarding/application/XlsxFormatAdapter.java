package it.comune.trieste.ouf.onboarding.application;

import static it.comune.trieste.ouf.onboarding.application.ManagedFileProfiler.*;

import it.comune.trieste.ouf.onboarding.application.xlsx.XlsxArchive;
import it.comune.trieste.ouf.onboarding.application.xlsx.XlsxUnitReader;
import java.io.IOException;
import java.time.ZoneId;
import java.util.*;

/**
 * XLSX profiles with the shared rule ouf.xlsx-cell-text.v1 (contract ouf.extraction-unit.v1 §8).
 * Every worksheet is a unit; a rejected worksheet keeps its stable reason and never blocks the others.
 * The persisted shape is the one read by SourceUnitProfiles: metadata.sheets, values keyed by sheet:n,
 * no flat top-level schema.
 */
final class XlsxFormatAdapter implements ManagedFileFormatAdapter {
  private final ZoneId zone;
  private final XlsxArchive.Limits limits;
  private final XlsxUnitReader reader = new XlsxUnitReader();

  XlsxFormatAdapter(ZoneId zone, XlsxArchive.Limits limits) {
    this.zone = zone;
    this.limits = Objects.requireNonNull(limits);
  }

  /** Profiles with the installation zone and the default header rows. */
  @Override public Profile profile(byte[] bytes) { return read(bytes, zone, Map.of()); }

  /** Profiles with the options frozen by one operation (§8.1): a null zone means no declared zone. */
  @Override public Profile profile(byte[] bytes, ReadOptions options) {
    return read(bytes, options.timeZone(), options.headerRows());
  }

  private Profile read(byte[] bytes, ZoneId effective, Map<String,Integer> headerRows) {
    XlsxUnitReader.Result result;
    try {
      result = reader.read(bytes, new XlsxUnitReader.Options(effective, headerRows, limits));
    } catch (IOException e) {
      throw new IllegalArgumentException("invalid XLSX", e);
    }
    var sheets = new ArrayList<Map<String,Object>>();
    var distinct = new LinkedHashMap<String,Map<String,List<String>>>();
    for (var unit : result.sheets()) {
      var item = new LinkedHashMap<String,Object>();
      item.put("sheetIndex", unit.sheetIndex());
      item.put("sheet", unit.name());
      item.put("hidden", unit.hidden());
      item.put("status", unit.status().name());
      if (unit.headerRow() != null) item.put("headerRow", unit.headerRow());
      if (unit.status() == XlsxUnitReader.Status.REJECTED) {
        var rejection = new LinkedHashMap<String,Object>();
        rejection.put("code", unit.rejection().code());
        if (unit.rejection().at() != null) rejection.put("at", unit.rejection().at());
        item.put("rejection", rejection);
        sheets.add(item);
        continue;
      }
      var rows = unit.rows().stream().map(XlsxUnitReader.DataRow::values).toList();
      var tabular = build("XLSX", Map.of(), unit.headers(), rows);
      var nativeTypes = new LinkedHashMap<String,String>();
      var columns = new ArrayList<ColumnProfile>(tabular.columns().size());
      for (int c = 0; c < tabular.columns().size(); c++) {
        var column = tabular.columns().get(c);
        var type = unit.nativeTypes().get(c);
        nativeTypes.put(column.name(), type.name());
        // DATETIME exists only for this reader: CSV inference stays byte-identical.
        columns.add(type == XlsxUnitReader.NativeType.DATETIME
            ? new ColumnProfile(column.name(), "DATETIME", column.nullable(), column.distinctValues()) : column);
      }
      item.put("rows", unit.recordCount());
      item.put("columns", List.copyOf(columns));
      item.put("nativeTypes", nativeTypes);
      item.put("candidateKeys", tabular.candidateKeys());
      item.put("sample", tabular.sample());
      sheets.add(item);
      distinct.put(unit.unitRef(), tabular.distinctCellValues().get(FLAT_PROFILE));
    }
    var metadata = new LinkedHashMap<String,Object>();
    metadata.put("reader", XlsxUnitReader.RULE);
    metadata.put("workbookSheets", result.sheets().size());
    metadata.put("dateSystem", result.dateSystem());
    if (effective != null) metadata.put("timeZone", effective.getId());
    metadata.put("sheets", List.copyOf(sheets));
    return new Profile("XLSX", metadata, List.of(), List.of(), List.of(), distinct);
  }
}
