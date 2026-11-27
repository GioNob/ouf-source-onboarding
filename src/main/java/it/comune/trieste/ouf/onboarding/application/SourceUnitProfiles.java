package it.comune.trieste.ouf.onboarding.application;

import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import org.springframework.http.HttpStatus;
import java.util.*;

/** Views of the immutable JSON retained by the file-profile owner. No object-store reads. */
public final class SourceUnitProfiles {
  public record Reference(UUID profileId,int profileVersion,String unitRef,String schemaHash) {
    @com.fasterxml.jackson.annotation.JsonCreator(mode=com.fasterxml.jackson.annotation.JsonCreator.Mode.DELEGATING)
    public static Reference parse(com.fasterxml.jackson.databind.JsonNode node) {
      if(!node.isObject() || node.size()!=4 || !node.path("profileId").isTextual()
          || !node.path("profileVersion").isIntegralNumber() || !node.path("profileVersion").canConvertToInt()
          || !node.path("unitRef").isTextual() || !node.path("schemaHash").isTextual())
        throw new IllegalArgumentException("Invalid source unit reference");
      String id=node.get("profileId").textValue();
      UUID profile=UUID.fromString(id);
      if(!profile.toString().equals(id))throw new IllegalArgumentException("Invalid profile ID");
      return new Reference(profile,node.get("profileVersion").intValue(),node.get("unitRef").textValue(),node.get("schemaHash").textValue());
    }
    Map<String,Object> asMap() {
      return Map.of("profileId",profileId.toString(),"profileVersion",profileVersion,"unitRef",unitRef,"schemaHash",schemaHash);
    }
    public Reference {
      if(profileId==null || profileVersion<1 || unitRef==null || !unitRef.matches("(table|sheet|layer|collection):(0|[1-9][0-9]{0,3})") || schemaHash==null
          || !schemaHash.matches("sha256:[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid source unit reference");
    }
  }
  static final String XLSX_READER = "ouf.xlsx-cell-text.v1";

  record Unit(String ref, String kind, String name, Map<String,Object> evidence,
              List<Map<String,Object>> columns, List<String> candidates, Map<String,Object> readParameters) {
    boolean layer() { return "LAYER".equals(kind); }
    boolean ready() { return "READY".equals(evidence.getOrDefault("status", "READY")); }
    Map<String,Object> rejection() {
      var saved=object(evidence.get("rejection"));
      var rejection=new LinkedHashMap<String,Object>();
      rejection.put("code",saved.get("code"));
      if(saved.containsKey("at"))rejection.put("at",saved.get("at"));
      return rejection;
    }

    String recordModel() { return layer() ? "ONE_FEATURE_ONE_SOURCE_OBJECT" : "ONE_ROW_ONE_SOURCE_OBJECT"; }

    Map<String,Object> geometry(boolean withExtent) {
      if (!layer()) return null;
      var geometry = new LinkedHashMap<String,Object>();
      geometry.put("column", evidence.get("geometryColumn"));
      geometry.put("geometryType", evidence.get("geometryType"));
      geometry.put("dimensions", 2);
      geometry.put("srsId", evidence.get("srsId"));
      geometry.put("sourceCrs", evidence.get("sourceCrs"));
      if (withExtent && evidence.containsKey("extent")) geometry.put("extent", evidence.get("extent"));
      return geometry;
    }

    List<?> declared() { return layer() ? List.of(List.of(evidence.get("primaryKey"))) : List.of(); }

    Map<String,Object> summary() {
      var out = new LinkedHashMap<String,Object>();
      out.put("unitRef", ref);
      out.put("unitKind", kind);
      out.put("name", name);
      out.put("hidden", evidence.getOrDefault("hidden", false));
      out.put("status", ready() ? "READY" : "REJECTED");
      if (!ready()) out.put("rejection", rejection());
      out.put("recordCount", ready() ? evidence.get(layer() ? "featureCount" : "rows") : null);
      out.put("columnCount", ready() ? columns.size() : null);
      out.put("hasGeometry", layer());
      return out;
    }

    List<Map<String,Object>> structuralColumns() {
      var nativeTypes = new HashMap<>(object(evidence.get("nativeTypes")));
      for (var column : objects(evidence.get("nativeColumns"))) nativeTypes.put((String)column.get("name"),column.get("type"));
      var structural = new ArrayList<Map<String,Object>>();
      for (var column : columns) {
        String name = (String)column.get("name");
        boolean geometry = name.equals(evidence.get("geometryColumn"));
        structural.add(Map.of("ordinal",structural.size(), "name",name, "dataType",column.get("dataType"),
            "nullable",column.get("nullable"), "role",geometry ? "GEOMETRY" : "ATTRIBUTE",
            "nativeType","TABLE".equals(kind) ? "TEXT" : geometry ? evidence.get("geometryType") : nativeTypes.getOrDefault(name, "UNKNOWN")));
      }
      return structural;
    }

    Map<String,Object> structure() {
      var schema = new LinkedHashMap<String,Object>();
      if ("TABLE".equals(kind) && !List.of(",",";").contains(Objects.toString(evidence.get("delimiter"),"")))
        throw error(HttpStatus.UNPROCESSABLE_ENTITY,"ONB_FILE_CSV_DIALECT_INVALID");
      schema.put("unitKind",kind);
      schema.put("recordModel",recordModel());
      schema.put("columns",structuralColumns());
      schema.put("keysDeclared",declared());
      schema.put("geometry",geometry(false));
      schema.put("readParameters",readParameters);
      return schema;
    }
  }

  private final Map<String,Object> file;
  private final CanonicalHash hashes;
  private final List<Unit> units = new ArrayList<>();
  private final String reader;

  SourceUnitProfiles(Map<String,Object> file, CanonicalHash hashes) {
    this.file = file;
    this.hashes = hashes;
    var meta = object(file.get("metadata"));
    reader = (String)meta.get("reader");
    switch ((String)file.get("format")) {
      case "CSV" -> {
        var csv=new LinkedHashMap<String,Object>();
        csv.put("encoding",meta.getOrDefault("encoding","UTF-8"));
        csv.put("headerRow",meta.getOrDefault("headerRow",1));
        csv.put("delimiter",meta.get("delimiter"));
        units.add(new Unit("table:0","TABLE","table",meta,objects(file.get("inferred_schema")),
            strings(file.get("candidate_keys")),Map.of("csv",csv)));
      }
      case "XLSX" -> {
        if (!XLSX_READER.equals(reader)) {
          units.add(new Unit("sheet:0","SHEET",(String)meta.get("sheet"),meta,objects(file.get("inferred_schema")),
              strings(file.get("candidate_keys")),Map.of("xlsx",Map.of("sheet",meta.get("sheet"),"sheetIndex",0,"headerRow",meta.get("headerRow")))));
        } else for (var sheet : objects(meta.get("sheets"))) {
          var parameters = new LinkedHashMap<String,Object>();
          for (String key : List.of("sheet","sheetIndex","headerRow")) if (sheet.containsKey(key)) parameters.put(key,sheet.get(key));
          for (String key : List.of("dateSystem","timeZone")) if (meta.containsKey(key)) parameters.put(key,meta.get(key));
          parameters.put("cellText",reader);
          units.add(new Unit("sheet:"+units.size(),"SHEET",(String)sheet.get("sheet"),sheet,objects(sheet.get("columns")),
              strings(sheet.get("candidateKeys")),Map.of("xlsx",parameters)));
        }
      }
      case "GEOPACKAGE" -> {
        for (var layer : objects(meta.get("layers"))) units.add(new Unit("layer:"+units.size(),"LAYER",(String)layer.get("layer"),layer,
            objects(layer.get("columns")),strings(layer.get("candidateKeys")),Map.of("geopackage",Map.of(
                "layer",layer.get("layer"),"geometryColumn",layer.get("geometryColumn"),"sourceCrs",layer.get("sourceCrs")))));
      }
      default -> { }
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String,Object> object(Object raw) { return raw instanceof Map<?,?> map ? (Map<String,Object>)map : Map.of(); }
  @SuppressWarnings("unchecked")
  private static List<Map<String,Object>> objects(Object raw) { return raw instanceof List<?> rows ? (List<Map<String,Object>>)rows : List.of(); }
  @SuppressWarnings("unchecked")
  private static List<String> strings(Object raw) { return raw instanceof List<?> rows ? (List<String>)rows : List.of(); }

  static DomainFailure error(HttpStatus status,String code) { return new DomainFailure(status,code,code); }

  private void supported() {
    if (!Set.of("CSV","XLSX","GEOPACKAGE").contains(file.get("format")))
      throw error(HttpStatus.UNPROCESSABLE_ENTITY,"ONB_UNIT_FORMAT_UNSUPPORTED");
  }

  Unit find(String ref) {
    supported();
    return units.stream().filter(u -> u.ref().equals(ref)).findFirst()
        .orElseThrow(() -> error(HttpStatus.NOT_FOUND,"ONB_PROFILE_UNIT_NOT_FOUND"));
  }

  Unit verify(Reference reference) {
    if (!reference.profileId().equals(file.get("profile_id")) || ((Number)file.get("version")).intValue() != reference.profileVersion())
      throw error(HttpStatus.CONFLICT,"ONB_UNIT_PROFILE_CHANGED");
    var unit = find(reference.unitRef());
    if (!unit.ready()) throw new DomainFailure(HttpStatus.UNPROCESSABLE_ENTITY,"ONB_UNIT_REJECTED","Unit was rejected",
        Map.of("rejection",unit.rejection()));
    if (!hashes.of(unit.structure()).equals(reference.schemaHash())) throw error(HttpStatus.CONFLICT,"ONB_UNIT_SCHEMA_CHANGED");
    return unit;
  }

  boolean outdated(Unit unit) { return "SHEET".equals(unit.kind()) && !XLSX_READER.equals(reader); }

  Map<String,Object> summary() {
    var ready = units.stream().filter(Unit::ready).toList();
    var out = new LinkedHashMap<String,Object>();
    out.put("unitCount",units.size());
    out.put("readyUnits",ready.size());
    if (ready.size()==1) out.put("singleReadyUnitRef",ready.getFirst().ref());
    return out;
  }

  private Map<String,Object> envelope(String schema,String media) {
    var out = new LinkedHashMap<String,Object>();
    out.put("schema",schema);
    out.put("sourceKind","FILE");
    out.put("origin",Map.of("assetId",file.get("asset_id"),"mediaType",media));
    out.put("profileId",file.get("profile_id"));
    out.put("profileVersion",file.get("version"));
    return out;
  }

  Map<String,Object> inventory(String media,int offset,int limit) {
    supported();
    if (offset<0 || offset>63 || offset>0 && offset>=units.size() || limit<1 || limit>64)
      throw error(HttpStatus.BAD_REQUEST,"ONB_PROFILE_PAGE_INVALID");
    int end = Math.min(units.size(),offset+limit);
    var out = envelope("ouf.source-unit-inventory.v1",media);
    out.put("readerVersion",reader);
    out.put("inventoryComplete",!"XLSX".equals(file.get("format")) || XLSX_READER.equals(reader));
    out.put("units",units.subList(offset,end).stream().map(Unit::summary).toList());
    out.put("unitSummary",summary());
    out.put("nextOffset",end<units.size()?end:null);
    out.put("hasMore",end<units.size());
    return out;
  }

  Map<String,Object> header(Unit unit,String media) {
    var out = envelope("ouf.source-unit-profile.v1",media);
    out.putAll(unit.summary());
    out.remove("hidden");
    out.remove("hasGeometry");
    out.put("unitName",out.remove("name"));
    out.put("view","UNIT");
    out.put("recordModel",unit.recordModel());
    out.put("limitsProfile",ManagedFileLimits.PROFILE);
    out.put("schemaHash",hashes.of(unit.structure()));
    out.put("columnsHash",hashes.of(unit.columns()));
    out.put("keys",Map.of("declared",unit.declared(),"candidates",unit.candidates().stream().map(List::of).toList()));
    out.put("geometry",unit.geometry(true));
    out.put("readParameters",unit.readParameters());
    return out;
  }
}
