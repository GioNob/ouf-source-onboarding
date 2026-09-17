package it.comune.trieste.ouf.managed;

import com.healthmarketscience.jackcess.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Read-only local tables; catalog constraints are evidence, not semantic decisions. */
public final class AccessReader implements AutoCloseable {
  public static final int MAX_BYTES = 10 * 1024 * 1024, MAX_TABLES = 64, MAX_ROWS = 10000, MAX_COLUMNS = 256;
  private final Path path;
  private Database database;
  public record ColumnInfo(String name, String type) {}
  public record TableInfo(String name, List<ColumnInfo> columns, List<List<String>> uniqueKeys, List<String> primaryKey) {}
  public AccessReader(byte[] bytes) {
    if (bytes.length == 0 || bytes.length > MAX_BYTES) throw invalid("SIZE");
    try {
      path = Files.createTempFile("ouf-access-", ".db");
    } catch (IOException e) { throw invalid("TEMP_STORAGE"); }
    try {
      Files.write(path, bytes);
      database = new DatabaseBuilder(path).setReadOnly(true).open();
      database.setEvaluateExpressions(false);
      database.setDateTimeType(DateTimeType.LOCAL_DATE_TIME);
      database.setLinkResolver((db, name) -> { throw new IOException("ACCESS_LINKED_TABLE_DISABLED"); });
      if (database.getTableNames().size() > MAX_TABLES) throw invalid("TABLE_LIMIT");
      // Inspect metadata before any getTable/relationship call can resolve links.
      for (String name : database.getTableNames()) {
        if (database.getTableMetaData(name).isLinked()) throw invalid("LINKED_TABLE_DISABLED");
      }
      if (database.getDatabasePassword() != null && !database.getDatabasePassword().isEmpty()) throw invalid("PROTECTED_DATABASE");
    } catch (Exception e) { close(); throw invalid("INVALID_OR_UNSUPPORTED"); }
  }
  public List<TableInfo> tables() {
    try {
      var result = new ArrayList<TableInfo>();
      for (String name : new TreeSet<>(database.getTableNames())) {
        Table table = database.getTable(name);
        if (table.getColumnCount() < 1 || table.getColumnCount() > MAX_COLUMNS || table.getRowCount() > MAX_ROWS) throw invalid("TABLE_LIMIT");
        var columns = table.getColumns().stream().map(c -> new ColumnInfo(c.getName(), c.getType().name())).toList();
        var keys = new ArrayList<List<String>>(); List<String> primary = List.of();
        for (Index index : table.getIndexes()) {
          List<String> fields = index.getColumns().stream().map(Index.Column::getName).toList();
          if (index.isUnique()) keys.add(fields);
          if (index.isPrimaryKey()) primary = fields;
        }
        result.add(new TableInfo(name, columns, List.copyOf(keys), primary));
      }
      return List.copyOf(result);
    } catch (IOException e) { throw invalid("CATALOG_INVALID"); }
  }
  public List<Map<String,Object>> relationships() {
    try {
      var relationships = database.getRelationships();
      if (relationships.size() > 512) throw invalid("RELATIONSHIP_LIMIT");
      return relationships.stream().map(r -> Map.<String,Object>of(
          "name", r.getName(), "fromTable", r.getFromTable().getName(), "toTable", r.getToTable().getName(),
          "fromColumns", r.getFromColumns().stream().map(Column::getName).toList(),
          "toColumns", r.getToColumns().stream().map(Column::getName).toList(),
          "referentialIntegrity", r.hasReferentialIntegrity(), "proposalStatus", "PENDING_HUMAN_REVIEW")).toList();
    } catch (IOException e) { throw invalid("RELATIONSHIP_INVALID"); }
  }
  public List<Map<String,Object>> rows(String tableName, Collection<String> projection) {
    try {
      TableInfo info = tables().stream().filter(t -> t.name().equals(tableName)).findFirst().orElseThrow(() -> invalid("TABLE_MISSING"));
      if (projection.isEmpty() || !info.columns().stream().map(ColumnInfo::name).toList().containsAll(projection)) throw invalid("PROJECTION_INVALID");
      Set<String> supported = Set.of("BOOLEAN", "BYTE", "INT", "LONG", "MONEY", "FLOAT", "DOUBLE", "SHORT_DATE_TIME", "TEXT", "MEMO", "GUID", "NUMERIC", "BIG_INT", "EXT_DATE_TIME");
      for (var column : info.columns()) if (projection.contains(column.name()) && !supported.contains(column.type())) throw invalid("COLUMN_TYPE_UNSUPPORTED");
      var rows = new ArrayList<Map<String,Object>>();
      var cursor = database.getTable(tableName).getDefaultCursor();
      Map<String,Object> row;
      while ((row = cursor.getNextRow(projection)) != null) {
        if (rows.size() >= MAX_ROWS) throw invalid("ROW_LIMIT");
        var out = new LinkedHashMap<String,Object>();
        for (String column : projection) {
          Object value = row.get(column);
          if (value instanceof java.time.temporal.TemporalAccessor) value = value.toString();
          if (value != null && !(value instanceof String || value instanceof Number || value instanceof Boolean)) throw invalid("VALUE_TYPE_UNSUPPORTED");
          if (value instanceof String s && s.length() > 65536) throw invalid("CELL_LIMIT");
          if (value instanceof Double d && !Double.isFinite(d) || value instanceof Float f && !Float.isFinite(f)) throw invalid("NON_FINITE_NUMBER");
          out.put(column, value);
        }
        rows.add(Collections.unmodifiableMap(out));
      }
      return List.copyOf(rows);
    } catch (IOException e) { throw invalid("DATA_INVALID"); }
  }
  public void close() {
    try { if (database != null) database.close(); } catch (IOException ignored) { }
    try { Files.deleteIfExists(path); } catch (IOException e) { throw invalid("TEMP_CLEANUP_FAILED"); }
  }
  private static IllegalArgumentException invalid(String reason) { return new IllegalArgumentException("ACCESS_" + reason); }
}
