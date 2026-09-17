package it.comune.trieste.ouf.managed;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.geojson.geom.GeometryJSON;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.Geometry;

/** Bounded static 2D feature intake. No remote resources, styles or expressions are loaded. */
public final class ShapefileReader implements AutoCloseable {
  public record Layer(String name,String crs,String encoding,String geometryColumn,List<String> columns,List<Map<String,Object>> rows) {}
  private final Path directory;
  private final List<Layer> layers = new ArrayList<>();
  private static final ObjectMapper JSON = new ObjectMapper();
  public ShapefileReader(byte[] archive) {
    if(archive.length==0||archive.length>10*1024*1024)throw invalid("ARCHIVE_SIZE");
    try{directory=Files.createTempDirectory("ouf-shapefile-");}catch(IOException e){throw invalid("TEMP_STORAGE");}
    try{
      var files=new TreeMap<String,byte[]>();long total=0;int entries=0;
      try(var zip=new ZipInputStream(new ByteArrayInputStream(archive))){
        ZipEntry entry;
        while((entry=zip.getNextEntry())!=null){
          if(++entries>512)throw invalid("ENTRY_LIMIT");
          String name=entry.getName().toLowerCase(Locale.ROOT);
          if(name.contains("\\")||name.contains(":")||name.startsWith("/")||Arrays.asList(name.split("/")).contains(".."))throw invalid("UNSAFE_PATH");
          if(entry.isDirectory()){if(zip.read()!=-1)throw invalid("DIRECTORY_CONTENT");continue;}
          if(!Path.of(name).normalize().toString().equals(name))throw invalid("UNSAFE_PATH");
          byte[] bytes=zip.readNBytes(10*1024*1024+1);total+=bytes.length;
          if(bytes.length>10*1024*1024||total>30*1024*1024)throw invalid("EXPANDED_SIZE");
          if(files.putIfAbsent(name,bytes)!=null)throw invalid("DUPLICATE_COMPONENT");
        }
      }
      List<String> shapes=files.keySet().stream().filter(n->n.endsWith(".shp")).toList();
      if(shapes.isEmpty()||shapes.size()>64)throw invalid("LAYER_LIMIT");
      for(String name:shapes){
        String stem=name.substring(0,name.length()-4);
        for(String ext:List.of(".shp",".shx",".dbf",".prj",".cpg"))if(!files.containsKey(stem+ext))throw invalid("COMPONENT_REQUIRED");
        validateHeaders(files.get(name),files.get(stem+".shx"),files.get(stem+".dbf"));
        String code=new String(files.get(stem+".cpg"),StandardCharsets.US_ASCII).strip();
        Charset encoding=Charset.forName(switch(code){case "65001"->"UTF-8";case "1252"->"windows-1252";default->code;});
        if(files.get(stem+".prj").length>16384)throw invalid("CRS_SIZE");
        // Only the five data components reach the parser; uploaded indexes/XML/styles are ignored.
        for(String ext:List.of(".shp",".shx",".dbf",".prj",".cpg")){
          Path target=directory.resolve(stem+ext).normalize();if(!target.startsWith(directory))throw invalid("UNSAFE_PATH");
          Files.createDirectories(target.getParent());Files.write(target,files.get(stem+ext));
        }
        var store=new ShapefileDataStore(directory.resolve(name).toUri().toURL());
        try{
          store.setCharset(encoding);store.setTimeZone(TimeZone.getTimeZone("UTC"));store.setIndexed(false);
          var schema=store.getSchema();String crs=CRS.lookupIdentifier(schema.getCoordinateReferenceSystem(),true);
          if(crs==null||!crs.matches("EPSG:[1-9][0-9]{0,5}"))throw invalid("CRS_UNRESOLVED");
          String geometryColumn=schema.getGeometryDescriptor().getLocalName();
          var columns=schema.getAttributeDescriptors().stream().map(d->d.getLocalName()).toList();
          if(columns.size()>256||new HashSet<>(columns).size()!=columns.size())throw invalid("COLUMNS_INVALID");
          var rows=new ArrayList<Map<String,Object>>();
          try(var features=store.getFeatureSource().getFeatures().features()){
            while(features.hasNext()){
              if(rows.size()>=10000)throw invalid("ROW_LIMIT");
              var feature=features.next();var row=new LinkedHashMap<String,Object>();
              for(String column:columns){
                Object value=feature.getAttribute(column);
                if(column.equals(geometryColumn)){
                  if(!(value instanceof Geometry g)||g.isEmpty()||g.getNumPoints()>100000||!g.isValid())throw invalid("GEOMETRY_INVALID");
                  for(var coordinate:g.getCoordinates())if(!Double.isFinite(coordinate.x)||!Double.isFinite(coordinate.y))throw invalid("GEOMETRY_INVALID");
                  var writer=new StringWriter();new GeometryJSON(16).write(g,writer);
                  value=Map.of("crs",crs,"geoJson",JSON.readValue(writer.toString(),new TypeReference<Map<String,Object>>(){}));
                }else if(value instanceof Date date)value=date.toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate().toString();
                else if(value!=null&&!(value instanceof String||value instanceof Number||value instanceof Boolean))throw invalid("ATTRIBUTE_TYPE");
                if(value instanceof String s&&s.length()>65536)throw invalid("CELL_LIMIT");
                if(value instanceof Double d&&!Double.isFinite(d)||value instanceof Float f&&!Float.isFinite(f))throw invalid("NON_FINITE_NUMBER");
                row.put(column,value);
              }
              rows.add(Collections.unmodifiableMap(row));
            }
          }
          layers.add(new Layer(stem,crs,encoding.name(),geometryColumn,columns,List.copyOf(rows)));
        }finally{store.dispose();}
      }
    }catch(Exception e){close();throw invalid("INVALID_OR_UNSUPPORTED");}
  }
  public List<Layer> layers(){return List.copyOf(layers);}
  private static void validateHeaders(byte[] shp,byte[] shx,byte[] dbf){
    if(shp.length<100||shx.length<100||dbf.length<32||(shx.length-100)%8!=0)throw invalid("HEADER_INVALID");
    var s=ByteBuffer.wrap(shp);var x=ByteBuffer.wrap(shx);
    if(s.getInt(0)!=9994||x.getInt(0)!=9994||2L*s.getInt(24)!=shp.length||2L*x.getInt(24)!=shx.length)throw invalid("LENGTH_INVALID");
    s.order(ByteOrder.LITTLE_ENDIAN);x.order(ByteOrder.LITTLE_ENDIAN);
    int shape=s.getInt(32);if(s.getInt(28)!=1000||x.getInt(28)!=1000||shape!=x.getInt(32)||!Set.of(1,3,5,8).contains(shape))throw invalid("ONLY_2D_SUPPORTED");
    int count=(shx.length-100)/8;long dbfRows=Integer.toUnsignedLong(ByteBuffer.wrap(dbf).order(ByteOrder.LITTLE_ENDIAN).getInt(4));
    if(count>10000||count!=dbfRows)throw invalid("RECORD_COUNT_MISMATCH");
    s.order(ByteOrder.BIG_ENDIAN);x.order(ByteOrder.BIG_ENDIAN);long expected=100;
    for(int i=0;i<count;i++){
      long offset=2L*x.getInt(100+i*8),length=2L*x.getInt(104+i*8);
      if(offset!=expected||length<4||offset+8+length>shp.length||s.getInt((int)offset)!=i+1||2L*s.getInt((int)offset+4)!=length)throw invalid("INDEX_MISMATCH");
      if(s.order(ByteOrder.LITTLE_ENDIAN).getInt((int)offset+8)!=shape)throw invalid("RECORD_SHAPE_MISMATCH");s.order(ByteOrder.BIG_ENDIAN);
      expected=offset+8+length;
    }
    if(expected!=shp.length)throw invalid("TRAILING_RECORDS");
  }
  public void close(){try(var paths=Files.walk(directory)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}catch(IOException e){throw invalid("TEMP_CLEANUP_FAILED");}}
  private static IllegalArgumentException invalid(String reason){return new IllegalArgumentException("SHP_"+reason);}
}
