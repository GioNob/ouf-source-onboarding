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
      if(!profile.toString().equalsIgnoreCase(id))throw new IllegalArgumentException("Invalid profile ID");
      return new Reference(profile,node.get("profileVersion").intValue(),node.get("unitRef").textValue(),node.get("schemaHash").textValue());
    }
    Map<String,Object> asMap() {
      return Map.of("profileId",profileId.toString(),"profileVersion",profileVersion,"unitRef",unitRef,"schemaHash",schemaHash);
    }
    public Reference {
      if(profileId==null || profileVersion<1 || unitRef==null || unitRef.isBlank() || schemaHash==null
          || !schemaHash.matches("sha256:[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid source unit reference");
    }
  }
  record Unit(String ref,String kind,String name,Map<String,Object> evidence,List<Map<String,Object>> columns,
              List<String> candidates,List<Map<String,Object>> sample) {
    boolean layer() {return "LAYER".equals(kind);}
    String recordModel() {return layer()?"ONE_FEATURE_ONE_SOURCE_OBJECT":"ONE_ROW_ONE_SOURCE_OBJECT";}
    Map<String,Object> readParameters() {
      return switch(kind) {
        case "TABLE" -> Map.of("csv",Map.of("delimiter",evidence.get("delimiter"),"encoding",evidence.get("encoding")));
        case "SHEET" -> Map.of("xlsx",Map.of("sheet",name,"headerRow",evidence.get("headerRow")));
        case "LAYER" -> Map.of("geopackage",Map.of("layer",name));
        default -> throw new IllegalStateException();
      };
    }
    Map<String,Object> geometry() {
      return layer()?Map.of("column",evidence.get("geometryColumn"),"type",evidence.get("geometryType"),
          "dimensions",2,"srsId",evidence.get("srsId"),"sourceCrs",evidence.get("sourceCrs")):null;
    }
    Map<String,Object> summary() {
      var out=new LinkedHashMap<String,Object>();
      out.put("unitRef",ref);out.put("unitKind",kind);out.put("name",name);
      out.put("recordCount",evidence.get(layer()?"featureCount":"rows"));
      out.put("columnCount",columns==null?null:columns.size());out.put("hasGeometry",layer());
      return out;
    }
  }

  private final Map<String,Object> file;
  private final CanonicalHash hashes;
  private final List<Unit> units;
  SourceUnitProfiles(Map<String,Object> file,CanonicalHash hashes) {
    this.file=file;this.hashes=hashes;this.units=inventory();
  }

  @SuppressWarnings("unchecked")
  private List<Unit> inventory() {
    var meta=(Map<String,Object>)file.get("metadata");
    var units=new ArrayList<Unit>();
    switch((String)file.get("format")) {
      case "CSV" -> units.add(top("table:0","TABLE","table",meta));
      case "XLSX" -> {
        units.add(top("sheet:0","SHEET",(String)meta.get("sheet"),meta));
        var sheets=(List<Map<String,Object>>)meta.getOrDefault("sheets",List.of());
        for(int i=1;i<sheets.size();i++)units.add(nested("sheet:"+i,"SHEET","sheet",sheets.get(i)));
      }
      case "GEOPACKAGE" -> {
        var layers=(List<Map<String,Object>>)meta.get("layers");
        for(int i=0;i<layers.size();i++)units.add(nested("layer:"+i,"LAYER","layer",layers.get(i)));
      }
      default -> throw error(HttpStatus.UNPROCESSABLE_ENTITY,"ONB_UNIT_FORMAT_UNSUPPORTED");
    }
    return units;
  }
  @SuppressWarnings("unchecked")
  private Unit top(String ref,String kind,String name,Map<String,Object> meta) {
    return new Unit(ref,kind,name,meta,(List<Map<String,Object>>)file.get("inferred_schema"),
        (List<String>)file.get("candidate_keys"),(List<Map<String,Object>>)file.get("redacted_sample"));
  }
  @SuppressWarnings("unchecked")
  private static Unit nested(String ref,String kind,String nameKey,Map<String,Object> item) {
    return new Unit(ref,kind,(String)item.get(nameKey),item,(List<Map<String,Object>>)item.get("columns"),
        (List<String>)item.get("candidateKeys"),(List<Map<String,Object>>)item.get("sample"));
  }
  private static DomainFailure error(HttpStatus status,String code) {return new DomainFailure(status,code,code);}
  private static int pageEnd(int count,int offset,int limit) {
    if(offset<0 || (offset!=0 && offset>=count) || limit<1 || limit>ManagedFileLimits.COLUMN_PAGE_SIZE)
      throw error(HttpStatus.UNPROCESSABLE_ENTITY,"ONB_PROFILE_PAGE_INVALID");
    return Math.min(count,offset+limit);
  }
  private Map<String,Object> page(int end,int size) {
    var out=new LinkedHashMap<String,Object>();
    out.put("profileId",file.get("profile_id"));out.put("profileVersion",file.get("version"));
    out.put("nextOffset",end<size?end:null);out.put("hasMore",end<size);
    return out;
  }
  Map<String,Object> units(int offset,int limit) {
    int end=pageEnd(units.size(),offset,limit);
    var out=page(end,units.size());out.put("assetId",file.get("asset_id"));
    if("XLSX".equals(file.get("format"))) {
      var metadata=(Map<?,?>)file.get("metadata");
      out.put("inventoryComplete",Objects.equals(metadata.get("workbookSheets"),units.size()));
    }
    out.put("units",units.subList(offset,end).stream().map(Unit::summary).toList());
    return out;
  }
  Unit find(String ref) {
    Unit unit=units.stream().filter(u->u.ref().equals(ref)).findFirst()
        .orElseThrow(()->error(HttpStatus.NOT_FOUND,"ONB_PROFILE_UNIT_NOT_FOUND"));
    if(unit.columns()==null)throw error(HttpStatus.UNPROCESSABLE_ENTITY,"ONB_UNIT_SCHEMA_UNAVAILABLE");
    if(unit.columns().size()>ManagedFileLimits.MAX_COLUMNS)throw error(HttpStatus.UNPROCESSABLE_ENTITY,"ONB_UNIT_COLUMN_LIMIT");
    return unit;
  }
  Reference defaultReference(String layer) {
    var unit=layer==null?units.getFirst():units.stream().filter(u->u.layer() && u.name().equals(layer)).findFirst()
        .orElseThrow(()->error(HttpStatus.NOT_FOUND,"ONB_PROFILE_UNIT_NOT_FOUND"));
    return reference(unit.ref());
  }
  Reference reference(String ref) {
    Unit unit=find(ref);
    return new Reference(UUID.fromString(file.get("profile_id").toString()),((Number)file.get("version")).intValue(),
        ref,hashes.of(schema(unit)));
  }
  Unit verify(Reference reference) {
    if(!Objects.equals(file.get("profile_id").toString(),Objects.toString(reference.profileId(),null))
        || ((Number)file.get("version")).intValue()!=reference.profileVersion())
      throw error(HttpStatus.CONFLICT,"ONB_UNIT_PROFILE_CHANGED");
    Unit unit=find(reference.unitRef());
    if(!hashes.of(schema(unit)).equals(reference.schemaHash()))throw error(HttpStatus.CONFLICT,"ONB_UNIT_SCHEMA_CHANGED");
    return unit;
  }
  @SuppressWarnings("unchecked")
  private Map<String,Object> schema(Unit unit) {
    var evidence=unit.evidence();
    var nativeTypes=new HashMap<>((Map<String,String>)evidence.getOrDefault("nativeTypes",Map.of()));
    var declaredNullable=new HashMap<String,Boolean>();
    for(var col:(List<Map<String,Object>>)evidence.getOrDefault("nativeColumns",List.of())) {
      nativeTypes.put((String)col.get("name"),(String)col.get("type"));
      declaredNullable.put((String)col.get("name"),!Boolean.TRUE.equals(col.get("primaryKey")) && Boolean.TRUE.equals(col.get("nullable")));
    }
    List<Map<String,Object>> columns=new ArrayList<>();
    for(var retained:unit.columns()) {
      String name=(String)retained.get("name");
      boolean geometry=name.equals(evidence.get("geometryColumn"));
      var column=new LinkedHashMap<String,Object>();
      column.put("name",name);
      column.put("nativeType",nativeTypes.getOrDefault(name,"TABLE".equals(unit.kind())?"TEXT":"UNKNOWN"));
      column.put("inferredType",retained.get("dataType"));
      column.put("nullable",declaredNullable.containsKey(name)?declaredNullable.get(name):retained.get("nullable"));
      column.put("role",geometry?"GEOMETRY":"ATTRIBUTE");
      columns.add(column);
    }
    var schema=new LinkedHashMap<String,Object>();
    schema.put("unitKind",unit.kind());schema.put("columns",columns);
    schema.put("keys",Map.of("declared",unit.layer()?List.of(List.of(evidence.get("primaryKey"))):List.of(),
        "candidates",unit.candidates().stream().map(List::of).toList()));
    schema.put("geometry",unit.geometry());schema.put("readParameters",unit.readParameters());
    return schema;
  }

  @SuppressWarnings("unchecked")
  Map<String,Object> profile(String ref,int offset,int limit) {
    Unit unit=find(ref);
    int size=unit.columns().size(),end=pageEnd(size,offset,limit);
    var schema=schema(unit);
    var out=page(end,size);
    out.putAll(schema);out.put("schema","ouf.source-unit-profile.v1");out.put("schemaHash",hashes.of(schema));
    out.put("sourceKind","FILE");out.put("origin",Map.of("assetId",file.get("asset_id")));
    out.put("unitRef",ref);out.put("unitName",unit.name());out.put("recordModel",unit.recordModel());
    out.put("columnCount",size);
    var columns=(List<Map<String,Object>>)schema.get("columns");
    var visible=new ArrayList<Map<String,Object>>();
    for(int i=offset;i<end;i++) {
      var column=new LinkedHashMap<>(columns.get(i));
      column.put("distinctValues",unit.columns().get(i).get("distinctValues"));
      visible.add(column);
    }
    out.put("columns",visible);
    out.put("sample",unit.sample().stream().limit(5).map(row->{
      var values=new LinkedHashMap<String,Object>();
      for(var column:visible) {
        String name=(String)column.get("name");
        values.put(name,"GEOMETRY".equals(column.get("role"))?"[GEOMETRY]":row.get(name));
      }
      return values;
    }).toList());
    return out;
  }
}
