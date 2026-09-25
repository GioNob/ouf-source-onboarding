package it.comune.trieste.ouf.onboarding.application;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.charset.*;
import java.time.LocalDate;
import java.util.*;
import org.apache.commons.csv.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

@Component
public class ManagedFileProfiler {
  static final int MAX_ROWS=10_000,MAX_COLUMNS=256; static final long MAX_BYTES=10*1024*1024;
  public record ColumnProfile(String name,String dataType,boolean nullable,long distinctValues) {}
  public record Profile(String format,Map<String,Object> metadata,List<ColumnProfile> columns,List<String> candidateKeys,List<Map<String,String>> sample) {}

  public Profile profile(byte[] bytes,String mediaType){if(bytes.length==0||bytes.length>MAX_BYTES)throw new IllegalArgumentException("file size outside profiling limits");return switch(mediaType){case "application/zip"->shapefile(bytes);case "application/x-msaccess","application/vnd.ms-access"->access(bytes);case "application/geopackage+sqlite3"->geopackage(bytes);case "text/csv"->csv(bytes);case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"->xlsx(bytes);default->throw new IllegalArgumentException("unsupported media type");};}
  private Profile shapefile(byte[] bytes){
    try(var reader=new it.comune.trieste.ouf.managed.ShapefileReader(bytes)){
      var layers=new ArrayList<Map<String,Object>>();
      for(var layer:reader.layers()){
        var rows=layer.rows().stream().map(row->layer.columns().stream().map(h->h.equals(layer.geometryColumn())?"[GEOMETRY]":Objects.toString(row.get(h),"")).toList()).toList();
        var tabular=build("SHAPEFILE",Map.of(),layer.columns(),rows);
        var columns=tabular.columns().stream().map(c->c.name().equals(layer.geometryColumn())?new ColumnProfile(c.name(),"GEOMETRY",false,0):c).toList();
        var item=new LinkedHashMap<String,Object>();item.put("layer",layer.name());item.put("sourceCrs",layer.crs());item.put("srsId",Integer.parseInt(layer.crs().substring(5)));item.put("encoding",layer.encoding());item.put("geometryColumn",layer.geometryColumn());item.put("columns",columns);
        item.put("candidateKeys",tabular.candidateKeys().stream().filter(k->!k.equals(layer.geometryColumn())).toList());item.put("sample",tabular.sample());item.put("featureCount",rows.size());layers.add(item);
      }
      return new Profile("SHAPEFILE",Map.of("layers",layers,"layerSelection","REQUIRED","dimensions",2,"reader","geotools-35.0"),List.of(),List.of(),List.of());
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
      return new Profile("ACCESS",Map.of("layers",tables,"tableSelection","REQUIRED","relationships",relationships,"semanticHints",hints,"proposalStatus","PENDING_HUMAN_REVIEW","reader","jackcess-5.0.0","expressions","DISABLED","linkedTables","REJECTED"),List.of(),List.of(),List.of());
    }
  }
  private Profile geopackage(byte[] bytes){
    try(var reader=new it.comune.trieste.ouf.geopackage.GeoPackageReader(bytes)){
      var layers=new ArrayList<Map<String,Object>>();
      for(var layer:reader.layers()){
        var features=reader.features(layer,MAX_ROWS,1048576);
        var headers=layer.columns().stream().map(it.comune.trieste.ouf.geopackage.GeoPackageReader.Column::name).toList();
        var rows=features.stream().map(row->headers.stream().map(h->h.equals(layer.geometryColumn())?"[GEOMETRY]":Objects.toString(row.get(h),"")).toList()).toList();
        var tabular=build("GEOPACKAGE",Map.of(),headers,rows);
        var columns=tabular.columns().stream().map(c->c.name().equals(layer.geometryColumn())?new ColumnProfile(c.name(),"GEOMETRY",false,0):c).toList();
        var item=new LinkedHashMap<String,Object>();item.put("layer",layer.name());item.put("geometryColumn",layer.geometryColumn());item.put("geometryType",layer.geometryType());item.put("srsId",layer.srsId());item.put("sourceCrs",layer.crs());item.put("primaryKey",layer.primaryKey());item.put("columns",columns);item.put("candidateKeys",tabular.candidateKeys().stream().filter(k->!k.equals(layer.geometryColumn())).toList());item.put("sample",tabular.sample());item.put("featureCount",features.size());layers.add(item);
      }
      return new Profile("GEOPACKAGE",Map.of("layers",layers,"layerSelection","REQUIRED","recordModel","ONE_FEATURE_ONE_SOURCE_OBJECT","dimensions",2),List.of(),List.of(),List.of());
    }
  }
  private Profile csv(byte[] bytes){String text;try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();}catch(CharacterCodingException e){throw new IllegalArgumentException("CSV must be valid UTF-8 text",e);}if(text.startsWith("\uFEFF"))text=text.substring(1);if(text.indexOf('\u0000')>=0)throw new IllegalArgumentException("CSV must be UTF-8 text");char delimiter=delimiter(text);CSVFormat format=CSVFormat.DEFAULT.builder().setDelimiter(delimiter).setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(true).get();try(CSVParser parser=CSVParser.parse(text,format)){List<String> headers=parser.getHeaderNames();validateHeaders(headers);List<List<String>> rows=new ArrayList<>();for(CSVRecord record:parser){if(rows.size()>=MAX_ROWS)throw new IllegalArgumentException("row limit exceeded");List<String> row=new ArrayList<>();for(int i=0;i<headers.size();i++)row.add(record.isSet(i)?record.get(i):"");rows.add(row);}return build("CSV",Map.of("encoding","UTF-8","delimiter",String.valueOf(delimiter),"headerRow",1,"rows",rows.size(),"columns",headers.size()),headers,rows);}catch(IOException e){throw new IllegalArgumentException("invalid CSV",e);}}
  private Profile xlsx(byte[] bytes){try(Workbook workbook=new XSSFWorkbook(new ByteArrayInputStream(bytes))){if(workbook.getNumberOfSheets()==0)throw new IllegalArgumentException("workbook has no sheets");Sheet sheet=workbook.getSheetAt(0);Row header=sheet.getRow(sheet.getFirstRowNum());if(header==null)throw new IllegalArgumentException("sheet has no header");int columns=header.getLastCellNum();if(columns<1||columns>MAX_COLUMNS)throw new IllegalArgumentException("column limit exceeded");List<String> headers=new ArrayList<>();for(int i=0;i<columns;i++)headers.add(cellText(header.getCell(i),true));validateHeaders(headers);List<List<String>> rows=new ArrayList<>();for(int r=header.getRowNum()+1;r<=sheet.getLastRowNum();r++){if(rows.size()>=MAX_ROWS)throw new IllegalArgumentException("row limit exceeded");Row row=sheet.getRow(r);if(row==null)continue;List<String> values=new ArrayList<>();for(int c=0;c<columns;c++)values.add(cellText(row.getCell(c),false));rows.add(values);}return build("XLSX",Map.of("workbookSheets",workbook.getNumberOfSheets(),"sheet",sheet.getSheetName(),"headerRow",header.getRowNum()+1,"rows",rows.size(),"columns",columns,"formulas","REJECTED"),headers,rows);}catch(IOException e){throw new IllegalArgumentException("invalid XLSX",e);}}
  private Profile build(String kind,Map<String,Object> metadata,List<String> headers,List<List<String>> rows){List<ColumnProfile> profiles=new ArrayList<>();List<String> keys=new ArrayList<>();for(int c=0;c<headers.size();c++){Set<String> distinct=new HashSet<>();boolean nullable=false;String type=null;for(List<String> row:rows){String value=c<row.size()?row.get(c).trim():"";if(value.isEmpty()){nullable=true;continue;}distinct.add(value);type=merge(type,infer(value));}String resolved=type==null?"STRING":type;profiles.add(new ColumnProfile(headers.get(c),resolved,nullable,distinct.size()));if(!nullable&&!rows.isEmpty()&&distinct.size()==rows.size())keys.add(headers.get(c));}List<Map<String,String>> sample=rows.stream().limit(5).map(row->{Map<String,String> item=new LinkedHashMap<>();for(int i=0;i<headers.size();i++)item.put(headers.get(i),i<row.size()?redact(headers.get(i),row.get(i)):"");return item;}).toList();return new Profile(kind,metadata,List.copyOf(profiles),List.copyOf(keys),sample);}
  private static void validateHeaders(List<String> headers){if(headers.isEmpty()||headers.size()>MAX_COLUMNS)throw new IllegalArgumentException("column limit exceeded");Set<String> seen=new HashSet<>();for(String h:headers)if(h==null||h.isBlank()||!seen.add(h))throw new IllegalArgumentException("headers must be non-empty and unique");}
  private static char delimiter(String text){String first=text.lines().findFirst().orElse("");long comma=first.chars().filter(c->c==',').count(),semicolon=first.chars().filter(c->c==';').count();return semicolon>comma?';':',';}
  private static String cellText(Cell cell,boolean header){if(cell==null)return "";if(cell.getCellType()==CellType.FORMULA)throw new IllegalArgumentException("XLSX formulas are not allowed");DataFormatter formatter=new DataFormatter(Locale.ROOT);String value=formatter.formatCellValue(cell);return header?value.trim():value;}
  private static String infer(String value){if(value.matches("-?[0-9]+"))return "LONG";if(value.matches("-?[0-9]+[.,][0-9]+"))return "DECIMAL";if(value.equalsIgnoreCase("true")||value.equalsIgnoreCase("false"))return "BOOLEAN";try{LocalDate.parse(value);return "DATE";}catch(Exception ignored){return "STRING";}}
  private static String merge(String current,String next){if(current==null||current.equals(next))return next;if(Set.of(current,next).equals(Set.of("LONG","DECIMAL")))return "DECIMAL";return "STRING";}
  private static String redact(String header,String value){String key=header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","");if(key.matches(".*(name|nome|surname|cognome|email|mail|phone|telefono|mobile|cellulare|address|indirizzo|fiscalcode|codicefiscale|iban|birth|nascita).*"))return "[REDACTED]";String v=value==null?"":value;return v.length()>128?v.substring(0,128):v;}
}
