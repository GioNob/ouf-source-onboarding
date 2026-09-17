package it.comune.trieste.ouf.geopackage;

import java.nio.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Bounded, read-only GeoPackage simple-feature reader. No reprojection or semantic inference. */
public final class GeoPackageReader implements AutoCloseable {
  public record Column(String name,String type,boolean nullable,boolean primaryKey) {}
  public record Layer(String name,String geometryColumn,String geometryType,int srsId,String crs,List<Column> columns,String primaryKey) {}
  private final Path file;
  private Connection db;
  private long deadline=System.nanoTime()+java.time.Duration.ofSeconds(5).toNanos();
  public GeoPackageReader(byte[] bytes) {
    if(bytes.length<100||bytes.length>10*1024*1024||!Arrays.equals(Arrays.copyOf(bytes,16),"SQLite format 3\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))throw invalid();
    try {
      file=Files.createTempFile("ouf-gpkg-",".gpkg");
    }catch(java.io.IOException e){throw invalid();}
    try {
      Files.write(file,bytes);
      var properties=new Properties();properties.setProperty("enable_load_extension","false");
      db=DriverManager.getConnection("jdbc:sqlite:"+file.toUri()+"?mode=ro&immutable=1",properties);
      org.sqlite.ProgressHandler.setHandler(db,1000,new org.sqlite.ProgressHandler(){protected int progress(){return System.nanoTime()>deadline?1:0;}});
      try(var s=db.createStatement()){
        s.execute("PRAGMA trusted_schema=OFF");s.execute("PRAGMA query_only=ON");
        try(var r=s.executeQuery("PRAGMA application_id")){if(!r.next()||r.getInt(1)!=0x47504b47)throw invalid();}
      }
      table("gpkg_contents");table("gpkg_geometry_columns");table("gpkg_spatial_ref_sys");
    }catch(Exception e){close();throw invalid();}
  }
  public List<Layer> layers(){
    deadline=System.nanoTime()+java.time.Duration.ofSeconds(5).toNanos();
    var out=new ArrayList<Layer>();
    try(var s=db.createStatement()){
      s.setQueryTimeout(5);
      try(var r=s.executeQuery("select c.table_name,g.column_name,g.geometry_type_name,g.srs_id,s.organization,s.organization_coordsys_id,g.z,g.m from gpkg_contents c join gpkg_geometry_columns g on g.table_name=c.table_name and g.srs_id=c.srs_id join gpkg_spatial_ref_sys s on s.srs_id=g.srs_id where c.data_type='features' order by c.table_name limit 65")){
        while(r.next()){
          if(out.size()==64)throw invalid();
          String name=r.getString(1),geometry=r.getString(2),type=r.getString(3);int srid=r.getInt(4),epsg=r.getInt(6);
          if(!"EPSG".equalsIgnoreCase(r.getString(5))||epsg<1||epsg>999999||r.getInt(7)!=0||r.getInt(8)!=0||!Set.of("GEOMETRY","POINT","LINESTRING","POLYGON","MULTIPOINT","MULTILINESTRING","MULTIPOLYGON","GEOMETRYCOLLECTION").contains(type))throw invalid();
          table(name);var columns=columns(name);var keys=columns.stream().filter(Column::primaryKey).toList();
          if(keys.size()!=1||!"INTEGER".equalsIgnoreCase(keys.get(0).type())||columns.stream().noneMatch(c->c.name().equals(geometry)))throw invalid();
          if(out.stream().anyMatch(l->l.name().equals(name)))throw invalid();
          out.add(new Layer(name,geometry,type,srid,"EPSG:"+epsg,columns,keys.get(0).name()));
        }
      }
      if(out.isEmpty())throw invalid();return List.copyOf(out);
    }catch(SQLException e){throw invalid();}
  }
  public List<Map<String,Object>> features(Layer layer,int maxRows,int maxCellChars){
    if(maxRows<1||maxRows>10000||maxCellChars<1||maxCellChars>1048576||!layers().contains(layer))throw invalid();
    var rows=new ArrayList<Map<String,Object>>();
    try(var s=db.createStatement()){
      s.setQueryTimeout(5);
      String projection=String.join(",",layer.columns().stream().map(c->quote(c.name())).toList());
      try(var r=s.executeQuery("select "+projection+" from "+quote(layer.name())+" order by "+quote(layer.primaryKey())+" limit "+(maxRows+1))){
        while(r.next()){
          if(rows.size()==maxRows||System.nanoTime()>deadline)throw invalid();var row=new LinkedHashMap<String,Object>();
          for(var c:layer.columns()){
            Object value=r.getObject(c.name());
            if(c.name().equals(layer.geometryColumn())){
              if(!(value instanceof byte[] b))throw invalid();
              var geo=geometry(b,layer.srsId());
              if(!"GEOMETRY".equals(layer.geometryType())&&!layer.geometryType().equals(String.valueOf(geo.get("type")).toUpperCase(Locale.ROOT)))throw invalid();
              value=Map.of("crs",layer.crs(),"geoJson",geo);
            }else if(value instanceof byte[]||value instanceof String str&&str.length()>maxCellChars||value instanceof Double d&&!Double.isFinite(d))throw invalid();
            row.put(c.name(),value);
          }
          rows.add(Collections.unmodifiableMap(row));
        }
      }
      return List.copyOf(rows);
    }catch(SQLException e){throw invalid();}
  }
  private List<Column> columns(String name)throws SQLException{
    var out=new ArrayList<Column>();
    try(var s=db.prepareStatement("select name,type,\"notnull\",pk,hidden from pragma_table_xinfo(?) limit 257")){
      s.setString(1,name);s.setQueryTimeout(5);
      try(var r=s.executeQuery()){while(r.next()){
        if(out.size()==256||r.getInt(5)!=0)throw invalid();
        out.add(new Column(r.getString(1),r.getString(2),r.getInt(3)==0&&r.getInt(4)==0,r.getInt(4)>0));
      }}
    }return List.copyOf(out);
  }
  private void table(String name)throws SQLException{
    quote(name);
    try(var s=db.prepareStatement("select type,sql from sqlite_schema where name=?")){
      s.setString(1,name);s.setQueryTimeout(5);
      try(var r=s.executeQuery()){if(!r.next()||!"table".equals(r.getString(1))||r.getString(2)==null||r.getString(2).toUpperCase(Locale.ROOT).contains("VIRTUAL")||r.next())throw invalid();}
    }
  }
  private static String quote(String name){if(name==null||name.isBlank()||name.length()>256||name.indexOf('\0')>=0)throw invalid();return "\""+name.replace("\"","\"\"")+"\"";}
  public static Map<String,Object> geometry(byte[] bytes,int srsId){
    if(bytes.length<13||bytes.length>1048576)throw invalid();
    try{
      var b=ByteBuffer.wrap(bytes);
      if(b.get()!='G'||b.get()!='P'||b.get()!=0)throw invalid();
      int flags=Byte.toUnsignedInt(b.get()),envelope=(flags>>1)&7;
      if((flags&0xf0)!=0||envelope>1)throw invalid();
      b.order((flags&1)==1?ByteOrder.LITTLE_ENDIAN:ByteOrder.BIG_ENDIAN);
      if(b.getInt()!=srsId)throw invalid();
      if(envelope==1)for(int i=0;i<4;i++)if(!Double.isFinite(b.getDouble()))throw invalid();
      var result=wkb(b,0,new int[]{100000});if(b.hasRemaining())throw invalid();return result;
    }catch(BufferUnderflowException|IndexOutOfBoundsException e){throw invalid();}
  }
  private static Map<String,Object> wkb(ByteBuffer b,int depth,int[] budget){
    if(depth>16||--budget[0]<0)throw invalid();int order=Byte.toUnsignedInt(b.get());if(order>1)throw invalid();
    b.order(order==1?ByteOrder.LITTLE_ENDIAN:ByteOrder.BIG_ENDIAN);int type=b.getInt();
    if(type<1||type>7)throw invalid();
    if(type==1)return Map.of("type","Point","coordinates",point(b,budget));
    if(type==2)return Map.of("type","LineString","coordinates",points(b,budget));
    if(type==3){int n=count(b,budget);var rings=new ArrayList<Object>();for(int i=0;i<n;i++)rings.add(points(b,budget));return Map.of("type","Polygon","coordinates",rings);}
    int n=count(b,budget);var items=new ArrayList<Object>();
    for(int i=0;i<n;i++){var child=wkb(b,depth+1,budget);String expected=switch(type){case 4->"Point";case 5->"LineString";case 6->"Polygon";default->null;};if(expected!=null&&!expected.equals(child.get("type")))throw invalid();items.add(type==7?child:child.get("coordinates"));}
    return Map.of("type",switch(type){case 4->"MultiPoint";case 5->"MultiLineString";case 6->"MultiPolygon";default->"GeometryCollection";},type==7?"geometries":"coordinates",items);
  }
  private static List<Double> point(ByteBuffer b,int[] budget){if(--budget[0]<0)throw invalid();double x=b.getDouble(),y=b.getDouble();if(!Double.isFinite(x)||!Double.isFinite(y))throw invalid();return List.of(x,y);}
  private static List<Object> points(ByteBuffer b,int[] budget){int n=count(b,budget);var points=new ArrayList<Object>();for(int i=0;i<n;i++)points.add(point(b,budget));return points;}
  private static int count(ByteBuffer b,int[] budget){int n=b.getInt();if(n<1||n>budget[0]||n>b.remaining())throw invalid();return n;}
  public void close(){try{if(db!=null)db.close();}catch(SQLException ignored){}finally{try{Files.deleteIfExists(file);}catch(java.io.IOException e){throw new IllegalStateException("GPKG_TEMP_CLEANUP_FAILED");}}}
  private static IllegalArgumentException invalid(){return new IllegalArgumentException("GPKG_INVALID_OR_UNSUPPORTED");}
}
