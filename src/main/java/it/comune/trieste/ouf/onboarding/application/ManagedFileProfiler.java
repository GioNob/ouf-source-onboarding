package it.comune.trieste.ouf.onboarding.application;

import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ManagedFileProfiler {
  static final int MAX_ROWS=10_000,MAX_COLUMNS=ManagedFileLimits.MAX_COLUMNS; static final long MAX_BYTES=10*1024*1024;
  static final int MAX_DISTINCT_VALUES=2_000,MAX_DISTINCT_VALUE_LENGTH=128;
  static final String FLAT_PROFILE="";
  public record ColumnProfile(String name,String dataType,boolean nullable,long distinctValues) {}
  public record Profile(String format,Map<String,Object> metadata,List<ColumnProfile> columns,List<String> candidateKeys,
      List<Map<String,String>> sample,Map<String,Map<String,List<String>>> distinctCellValues) {}

  /** Effective read options of one profiling operation (contract ouf.extraction-unit.v1 §8.1). */
  public record ReadOptions(java.time.ZoneId timeZone,Map<String,Integer> headerRows) {
    public static final ReadOptions NONE=new ReadOptions(null,Map.of());
    public ReadOptions { headerRows=Map.copyOf(headerRows==null?Map.of():headerRows); }
    boolean declared() { return !headerRows.isEmpty(); }
  }

  private final ManagedFileFormatAdapter csv = new CsvFormatAdapter();
  private final ManagedFileFormatAdapter xlsx;
  private final ManagedFileFormatAdapter geopackage = new GeoPackageFormatAdapter();

  /** Development and test default: no declared zone, protocol default archive limits. */
  public ManagedFileProfiler() { this(null, it.comune.trieste.ouf.onboarding.application.xlsx.XlsxArchive.Limits.defaults()); }
  @org.springframework.beans.factory.annotation.Autowired
  public ManagedFileProfiler(ManagedFileContentInspector inspector) { this(inspector.sourceTimeZone(), inspector.xlsxLimits()); }
  private final java.time.ZoneId zone;
  ManagedFileProfiler(java.time.ZoneId zone, it.comune.trieste.ouf.onboarding.application.xlsx.XlsxArchive.Limits limits) {
    this.zone = zone;
    xlsx = new XlsxFormatAdapter(zone, limits);
  }
  /** Installation zone used when an operation is requested; null when the installation declares none. */
  public java.time.ZoneId sourceTimeZone() { return zone; }

  public Profile profile(byte[] bytes,String mediaType){return profile(bytes,mediaType,null);}
  public Profile profile(byte[] bytes,String mediaType,ReadOptions options){if(options!=null&&options.declared()&&!"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".equals(mediaType))throw new IllegalArgumentException("read options unsupported");if(bytes.length==0||bytes.length>MAX_BYTES)throw new IllegalArgumentException("file size outside profiling limits");return switch(mediaType){case "application/zip"->shapefile(bytes);case "application/x-msaccess","application/vnd.ms-access"->access(bytes);case "application/geopackage+sqlite3"->geopackage.profile(bytes);case "text/csv"->csv.profile(bytes);case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"->options==null?xlsx.profile(bytes):xlsx.profile(bytes,options);default->throw new IllegalArgumentException("unsupported media type");};}
  private Profile shapefile(byte[] bytes){
    try(var reader=new it.comune.trieste.ouf.managed.ShapefileReader(bytes)){
      var layers=new ArrayList<Map<String,Object>>();
      var distinct=new LinkedHashMap<String,Map<String,List<String>>>();
      for(var layer:reader.layers()){
        var rows=layer.rows().stream().map(row->layer.columns().stream().map(h->h.equals(layer.geometryColumn())?"[GEOMETRY]":Objects.toString(row.get(h),"")).toList()).toList();
        var tabular=build("SHAPEFILE",Map.of(),layer.columns(),rows);
        distinct.put(layer.name(),withoutGeometry(tabular,layer.geometryColumn()));
        var columns=tabular.columns().stream().map(c->c.name().equals(layer.geometryColumn())?new ColumnProfile(c.name(),"GEOMETRY",false,0):c).toList();
        var item=new LinkedHashMap<String,Object>();item.put("layer",layer.name());item.put("sourceCrs",layer.crs());item.put("srsId",Integer.parseInt(layer.crs().substring(5)));item.put("encoding",layer.encoding());item.put("geometryColumn",layer.geometryColumn());item.put("columns",columns);
        item.put("candidateKeys",tabular.candidateKeys().stream().filter(k->!k.equals(layer.geometryColumn())).toList());item.put("sample",tabular.sample());item.put("featureCount",rows.size());layers.add(item);
      }
      return new Profile("SHAPEFILE",Map.of("layers",layers,"layerSelection","REQUIRED","dimensions",2,"reader","geotools-35.0"),List.of(),List.of(),List.of(),distinct);
    }
  }
  private Profile access(byte[] bytes){
    try(var reader=new it.comune.trieste.ouf.managed.AccessReader(bytes)){
      var tables=new ArrayList<Map<String,Object>>();
      for(var table:reader.tables()){
        var headers=table.columns().stream().map(it.comune.trieste.ouf.managed.AccessReader.ColumnInfo::name).toList();
        var rows=reader.rows(table.name(),headers);
        var values=rows.stream().map(row->headers.stream().map(h->Objects.toString(row.get(h),"")).toList()).toList();
        var profile=build("ACCESS",Map.of(),headers,values);
        var item=new LinkedHashMap<String,Object>();item.put("layer",table.name());item.put("columns",profile.columns());item.put("sourceColumns",table.columns());
        item.put("candidateKeys",profile.candidateKeys());item.put("primaryKey",table.primaryKey());item.put("uniqueKeys",table.uniqueKeys());item.put("rowCount",rows.size());
        item.put("sample",profile.sample().stream().map(row->{var masked=new LinkedHashMap<String,String>();row.keySet().forEach(k->masked.put(k,"[REDACTED]"));return masked;}).toList());tables.add(item);
      }
      var relationships=reader.relationships();var hints=new ArrayList<Map<String,Object>>();
      for(var table:tables)hints.add(Map.of("kind","CLASS_AND_PROPERTIES","table",table.get("layer"),"sourceColumns",table.get("sourceColumns"),"declaredPrimaryKey",table.get("primaryKey"),"declaredUniqueKeys",table.get("uniqueKeys"),"status","PENDING_HUMAN_REVIEW","nextAction","SEARCH_EXISTING_SEMANTICS_OR_OPEN_GAP"));
      for(var relationship:relationships)hints.add(Map.of("kind","RELATIONSHIP","name",relationship.get("name"),"referencedTable",relationship.get("fromTable"),"referencedColumns",relationship.get("fromColumns"),"referencingTable",relationship.get("toTable"),"referencingColumns",relationship.get("toColumns"),"referentialIntegrity",relationship.get("referentialIntegrity"),"status","PENDING_HUMAN_REVIEW","nextAction","SELECT_SEMANTIC_RELATION_AND_DIRECTION"));
      return new Profile("ACCESS",Map.of("layers",tables,"tableSelection","REQUIRED","relationships",relationships,"semanticHints",hints,"proposalStatus","PENDING_HUMAN_REVIEW","reader","jackcess-5.0.0","expressions","DISABLED","linkedTables","REJECTED"),List.of(),List.of(),List.of(),Map.of());
    }
  }
  static Profile build(String kind,Map<String,Object> metadata,List<String> headers,List<List<String>> rows){
    List<ColumnProfile> profiles=new ArrayList<>();
    List<String> keys=new ArrayList<>();
    Map<String,List<String>> values=new LinkedHashMap<>();
    for(int c=0;c<headers.size();c++){
      Set<String> distinct=new HashSet<>();
      boolean nullable=false;
      String type=null;
      for(List<String> row:rows){
        String value=c<row.size()?CellWhitespace.trim(row.get(c)):"";
        if(value.isEmpty()){nullable=true;continue;}
        distinct.add(value);
        type=merge(type,infer(value));
      }
      String name=headers.get(c);
      profiles.add(new ColumnProfile(name,type==null?"STRING":type,nullable,distinct.size()));
      if(!nullable&&!rows.isEmpty()&&distinct.size()==rows.size())keys.add(name);
      if(!"ACCESS".equals(kind))retainDistinct(values,name,distinct);
    }
    List<Map<String,String>> sample=rows.stream().limit(5).map(row->{
      Map<String,String> item=new LinkedHashMap<>();
      for(int i=0;i<headers.size();i++)item.put(headers.get(i),i<row.size()?redact(headers.get(i),row.get(i)):"");
      return item;
    }).toList();
    return new Profile(kind,metadata,List.copyOf(profiles),List.copyOf(keys),sample,Map.of(FLAT_PROFILE,values));
  }
  static Map<String,List<String>> withoutGeometry(Profile profile,String geometryColumn){
    var result=new LinkedHashMap<>(profile.distinctCellValues().get(FLAT_PROFILE));
    result.remove(geometryColumn);
    return result;
  }
  static void retainDistinct(Map<String,List<String>> result,String column,Set<String> values){
    if(values.size()<=MAX_DISTINCT_VALUES && !redactedColumn(column)
        && values.stream().allMatch(value->value.codePointCount(0,value.length())<=MAX_DISTINCT_VALUE_LENGTH))
      result.put(column,values.stream().sorted().toList());
  }
  static void validateHeaders(List<String> headers){if(headers.isEmpty()||headers.size()>MAX_COLUMNS)throw new IllegalArgumentException("column limit exceeded");Set<String> seen=new HashSet<>();for(String h:headers)if(h==null||CellWhitespace.trim(h).isEmpty()||h.length()>ManagedFileLimits.MAX_COLUMN_NAME_CHARS||!seen.add(h))throw new IllegalArgumentException("headers must be non-empty and unique");}
  static String infer(String value){if(value.matches("-?[0-9]+"))return "LONG";if(value.matches("-?[0-9]+[.,][0-9]+"))return "DECIMAL";if(value.equalsIgnoreCase("true")||value.equalsIgnoreCase("false"))return "BOOLEAN";try{LocalDate.parse(value);return "DATE";}catch(Exception ignored){return "STRING";}}
  static String merge(String current,String next){if(current==null||current.equals(next))return next;if(Set.of(current,next).equals(Set.of("LONG","DECIMAL")))return "DECIMAL";return "STRING";}
  private static boolean redactedColumn(String header){
    String key=header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","");
    return key.matches(".*(name|nome|surname|cognome|email|mail|phone|telefono|mobile|cellulare|address|indirizzo|fiscalcode|codicefiscale|iban|birth|nascita).*");
  }
  static String redact(String header,String value){
    if(redactedColumn(header))return "[REDACTED]";
    if(value==null)return "";
    int end=Math.min(128,value.length());
    if(end<value.length() && Character.isSurrogatePair(value.charAt(end-1),value.charAt(end)))end--;
    return value.substring(0,end);
  }
}
