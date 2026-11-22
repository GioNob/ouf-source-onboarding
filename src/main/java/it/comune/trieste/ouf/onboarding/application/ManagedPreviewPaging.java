package it.comune.trieste.ouf.onboarding.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
final class ManagedPreviewPaging {
  // Reserve room inside the owner budget for the bounded workflow observation fields.
  private static final int OBSERVATION_BYTES = 512;
  private static final int FLAT = -1;

  private record Schema(List<?> columns, List<?> layers, String hash) {
    List<?> columns(int layer) {
      return layer == FLAT ? columns : items(object(layers.get(layer)).get("columns"));
    }

    String layerName(int layer) {
      return layer == FLAT ? ManagedFileProfiler.FLAT_PROFILE : (String) object(layers.get(layer)).get("layer");
    }
  }

  private record Column(Map<String,Object> metadata, List<String> values, int layer, int index) {}

  private final ObjectMapper json;
  private final CanonicalHash hashes;
  private final DistinctValuesCursors cursors;
  private final int budget;

  ManagedPreviewPaging(ObjectMapper json, CanonicalHash hashes, DistinctValuesCursors cursors,
      @Value("${ouf.onboarding.preview.max-response-bytes:786432}") int maxResponseBytes) {
    if (maxResponseBytes < 8192 || maxResponseBytes > 786432)
      throw new IllegalArgumentException("Preview response budget must be between 8192 and 786432 bytes");
    this.json = json;
    this.hashes = hashes;
    this.cursors = cursors;
    this.budget = maxResponseBytes - OBSERVATION_BYTES;
  }

  Map<String,Object> profile(Map<String,Object> preview, Map<String,Object> job,
      Map<String,Map<String,List<String>>> values, String tenant, String subject) {
    var schema = schema(preview);
    var scope = scope(preview, schema, tenant, subject);
    long deadline = cursors.deadline();
    var out = new LinkedHashMap<>(job == null ? preview : job);
    var projected = new ArrayList<Column>();
    var columns = initialColumns(schema, values, FLAT, scope, deadline, projected);
    out.put("columns", columns);
    out.put("schemaHash", schema.hash());
    if (job == null && !schema.layers().isEmpty()) {
      var metadata = new LinkedHashMap<>(object(preview.get("metadata")));
      var layers = new ArrayList<Map<String,Object>>();
      for (int i = 0; i < schema.layers().size(); i++) {
        var layer = new LinkedHashMap<>(object(schema.layers().get(i)));
        layer.put("columns", initialColumns(schema, values, i, scope, deadline, projected));
        layers.add(layer);
      }
      metadata.put("layers", layers);
      out.put("metadata", metadata);
    }
    int remaining = budget - bytes(out);
    if (remaining < 0) {
      trimSamples(out);
      remaining = budget - bytes(out);
    }
    if (remaining < 0) return compactProfile(out, schema, values, scope, deadline);

    // A compact JSON object's byte count changes by the serialized difference of its replaced child.
    // Keep the other columns' metadata and continuation slots reserved before assigning any spare bytes.
    for (Column column : projected) {
      var first = valueSlice(column, scope, 0, 1, deadline);
      int extra = bytes(first) - bytes(column.metadata());
      if (extra <= remaining) {
        replaceProjection(column, first);
        remaining -= extra;
      }
    }
    for (Column column : projected) {
      int reserved = bytes(column.metadata());
      var fitted = fitValues(column, scope, 0, deadline, reserved + remaining, false);
      replaceProjection(column, fitted);
      remaining += reserved - bytes(fitted);
    }
    return checked(out);
  }

  private static void replaceProjection(Column column, Map<String,Object> fitted) {
    column.metadata().putAll(fitted);
    if (!fitted.containsKey("distinctValuesCursor")) column.metadata().remove("distinctValuesCursor");
  }

  Map<String,Object> columns(Map<String,Object> preview, Map<String,Map<String,List<String>>> values,
      String tenant, String subject, String layerName, int offset, int limit, String cursor) {
    var schema = schema(preview);
    var scope = scope(preview, schema, tenant, subject);
    var out = new LinkedHashMap<String,Object>();
    for (String key : List.of("assetId", "profileId", "profileVersion", "format")) out.put(key, preview.get(key));
    out.put("view", "COLUMNS");
    out.put("schemaHash", schema.hash());
    out.put("limitsProfile", ManagedFileLimits.PROFILE);

    if (cursor != null) {
      var position = cursors.verify(cursor, scope);
      int layer = position.layer();
      int index = position.column();
      if (layer < FLAT || layer >= schema.layers().size() || index < 0 || index >= schema.columns(layer).size())
        throw DistinctValuesCursors.invalid();
      var column = column(schema, values, layer, index);
      if (column.values() == null || position.offset() < 0 || position.offset() >= column.values().size())
        throw DistinctValuesCursors.invalid();
      if (layer != FLAT) out.put("layer", schema.layerName(layer));
      var page = pageEnvelope(out, schema.columns(layer).size(), index);
      page.add(fitValues(column, scope, position.offset(), position.expiresAt(), budget - bytes(out), true));
      return checked(out);
    }

    int layer = FLAT;
    if (layerName != null) {
      for (int i = 0; i < schema.layers().size(); i++) {
        if (layerName.equals(schema.layerName(i))) {
          layer = i;
          break;
        }
      }
      if (layer == FLAT) throw invalidPage("Unknown profile layer");
      out.put("layer", layerName);
    } else if (!List.of("CSV", "XLSX").contains(preview.get("format"))) {
      throw new DomainFailure(HttpStatus.BAD_REQUEST, "ONB_PROFILE_PAGE_FORMAT_UNSUPPORTED", "Tabular profile or exact layer required");
    }
    return page(out, schema, values, scope, layer, offset, limit, cursors.deadline());
  }

  private List<Map<String,Object>> initialColumns(Schema schema, Map<String,Map<String,List<String>>> values,
      int layer, DistinctValuesCursors.Scope scope, long deadline, List<Column> projected) {
    var columns = new ArrayList<Map<String,Object>>();
    for (int i = 0; i < schema.columns(layer).size(); i++) {
      var column = column(schema, values, layer, i);
      if (column.values() != null) {
        if (!column.values().isEmpty()) projected.add(column);
        var placeholder = valueSlice(column, scope, 0, 0, deadline);
        column.metadata().putAll(placeholder);
      }
      columns.add(column.metadata());
    }
    return columns;
  }

  private Map<String,Object> compactProfile(Map<String,Object> out, Schema schema, Map<String,Map<String,List<String>>> values,
      DistinctValuesCursors.Scope scope, long deadline) {
    if (out.get("candidateKeys") instanceof List<?> keys && keys.size() > 1) {
      out.put("candidateKeys", keys.subList(0, 1));
      out.put("candidateKeysTruncated", true);
    }
    if (!schema.layers().isEmpty() && out.containsKey("metadata")) {
      var metadata = new LinkedHashMap<>(object(out.get("metadata")));
      var layers = new ArrayList<Map<String,Object>>();
      for (int i = 0; i < schema.layers().size(); i++) {
        layers.add(Map.of("layer", schema.layerName(i), "columns", List.of(), "columnCount", schema.columns(i).size()));
      }
      metadata.put("layers", layers);
      out.put("metadata", metadata);
      out.put("metadataTruncated", true);
      if (bytes(out) > budget) out.put("metadata", Map.of());
    }
    if (schema.columns().isEmpty()) return checked(out);
    return page(out, schema, values, scope, FLAT, 0, ManagedFileLimits.COLUMN_PAGE_SIZE, deadline);
  }

  private Map<String,Object> page(Map<String,Object> out, Schema schema, Map<String,Map<String,List<String>>> values,
      DistinctValuesCursors.Scope scope, int layer, int offset, int limit, long deadline) {
    int count = schema.columns(layer).size();
    if (offset < 0 || offset >= count || count > ManagedFileLimits.MAX_COLUMNS
        || limit < 1 || limit > ManagedFileLimits.COLUMN_PAGE_SIZE) throw invalidPage("Invalid retained profile or page bounds");
    var page = pageEnvelope(out, count, offset);
    int remaining = budget - bytes(out);
    int end = offset;
    for (; end < Math.min(count, offset + limit); end++) {
      var column = column(schema, values, layer, end);
      var full = valueSlice(column, scope, 0, column.values() == null ? 0 : column.values().size(), deadline);
      int size = bytes(full) + (page.isEmpty() ? 0 : 1);
      if (size > remaining) {
        if (page.isEmpty()) {
          page.add(fitValues(column, scope, 0, deadline, remaining, true));
          end++;
        }
        break;
      }
      page.add(full);
      remaining -= size;
    }
    out.put("hasMore", end < count);
    out.put("nextOffset", end < count ? end : null);
    return checked(out);
  }

  private static List<Map<String,Object>> pageEnvelope(Map<String,Object> out, int count, int offset) {
    var page = new ArrayList<Map<String,Object>>();
    out.put("columns", page);
    out.put("columnCount", count);
    out.put("offset", offset);
    // false and null reserve at least as many bytes as true and a column index (at most 1024).
    out.put("hasMore", false);
    out.put("nextOffset", null);
    return page;
  }

  private Map<String,Object> fitValues(Column column, DistinctValuesCursors.Scope scope,
      int start, long deadline, int available, boolean requireProgress) {
    int end = column.values() == null ? 0 : column.values().size();
    var full = valueSlice(column, scope, start, end, deadline);
    if (bytes(full) <= available) return full;
    int min = requireProgress ? start + 1 : start;
    int max = end - 1;
    Map<String,Object> best = null;
    while (min <= max) {
      int middle = (min + max) >>> 1;
      var candidate = valueSlice(column, scope, start, middle, deadline);
      if (bytes(candidate) <= available) {
        best = candidate;
        min = middle + 1;
      } else max = middle - 1;
    }
    if (best == null) throw tooLarge();
    return best;
  }

  private Map<String,Object> valueSlice(Column column, DistinctValuesCursors.Scope scope,
      int start, int end, long deadline) {
    var result = new LinkedHashMap<>(column.metadata());
    if (column.values() != null) {
      boolean complete = end == column.values().size();
      result.put("distinctValues", column.values().subList(start, end));
      result.put("distinctValuesComplete", complete);
      if (complete) result.remove("distinctValuesCursor");
      else result.put("distinctValuesCursor", cursors.issue(scope,
          new DistinctValuesCursors.Position(column.layer(), column.index(), end, deadline)));
    }
    return result;
  }

  private static Column column(Schema schema, Map<String,Map<String,List<String>>> values, int layer, int index) {
    var metadata = new LinkedHashMap<>(object(schema.columns(layer).get(index)));
    metadata.put("distinctCount", metadata.remove("distinctValues"));
    var retained = values.getOrDefault(schema.layerName(layer), Map.of());
    return new Column(metadata, retained.get(metadata.get("name")), layer, index);
  }

  private Schema schema(Map<String,Object> preview) {
    var columns = items(preview.get("columns"));
    var layers = items(object(preview.get("metadata")).get("layers"));
    String hash = layers.isEmpty() ? hashes.of(columns) : hashes.of(List.of(columns, layers.stream()
        .map(ManagedPreviewPaging::object).map(layer -> List.of(layer.get("layer"), layer.get("columns"))).toList()));
    return new Schema(columns, layers, hash);
  }

  private static DistinctValuesCursors.Scope scope(Map<String,Object> preview, Schema schema, String tenant, String subject) {
    return new DistinctValuesCursors.Scope(tenant, subject, (UUID) preview.get("assetId"), (UUID) preview.get("profileId"), schema.hash());
  }

  private static void trimSamples(Map<String,Object> out) {
    boolean truncated = false;
    if (!items(out.get("redactedSample")).isEmpty()) {
      out.put("redactedSample", List.of());
      truncated = true;
    }
    for (Object raw : items(object(out.get("metadata")).get("layers"))) {
      var layer = object(raw);
      if (!items(layer.get("sample")).isEmpty()) {
        layer.put("sample", List.of());
        truncated = true;
      }
    }
    if (truncated) out.put("redactedSampleTruncated", true);
  }

  @SuppressWarnings("unchecked")
  private static Map<String,Object> object(Object value) {
    return value instanceof Map<?,?> map ? (Map<String,Object>) map : Map.of();
  }

  private static List<?> items(Object value) {
    return value instanceof List<?> list ? list : List.of();
  }

  private int bytes(Object value) {
    try {
      return json.writeValueAsBytes(value).length;
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("Managed profile serialization failed", failure);
    }
  }

  private Map<String,Object> checked(Map<String,Object> out) {
    if (bytes(out) > budget) throw tooLarge();
    return out;
  }

  private static DomainFailure invalidPage(String message) {
    return new DomainFailure(HttpStatus.BAD_REQUEST, "ONB_PROFILE_PAGE_INVALID", message);
  }

  private static DomainFailure tooLarge() {
    return new DomainFailure(HttpStatus.UNPROCESSABLE_ENTITY, "ONB_PROFILE_PREVIEW_BUDGET_EXCEEDED",
        "Managed profile metadata exceeds the preview response budget");
  }
}
