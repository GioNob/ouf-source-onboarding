package it.comune.trieste.ouf.managed;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShapefileReaderTest {
  @Test void readsPointAndPreservesDbfIdentifier() throws Exception {
    var files=fixture();
    try(var reader=new ShapefileReader(zip(files))){
      var layer=reader.layers().getFirst();assertEquals("EPSG:4326",layer.crs());
      assertEquals("001",layer.rows().getFirst().get("CODE"));
      var envelope=(Map<?,?>)layer.rows().getFirst().get(layer.geometryColumn());
      assertEquals("Point",((Map<?,?>)envelope.get("geoJson")).get("type"));
    }
  }
  @Test void preservesPolygonInteriorRing() throws Exception {
    var files=fixture();double[][] points={{0,0},{0,4},{4,4},{4,0},{0,0},{1,1},{3,1},{3,3},{1,3},{1,1}};
    var shp=ByteBuffer.allocate(320);header(shp,5);shp.order(ByteOrder.BIG_ENDIAN).position(100);shp.putInt(1).putInt(106);shp.order(ByteOrder.LITTLE_ENDIAN).putInt(5).putDouble(0).putDouble(0).putDouble(4).putDouble(4).putInt(2).putInt(10).putInt(0).putInt(5);
    for(var point:points)shp.putDouble(point[0]).putDouble(point[1]);
    var shx=ByteBuffer.allocate(108);header(shx,5);shx.order(ByteOrder.BIG_ENDIAN).position(100);shx.putInt(50).putInt(106);files.put("assets.shp",shp.array());files.put("assets.shx",shx.array());
    try(var reader=new ShapefileReader(zip(files))){
      var layer=reader.layers().getFirst();var envelope=(Map<?,?>)layer.rows().getFirst().get(layer.geometryColumn());var geo=(Map<?,?>)envelope.get("geoJson");
      var coordinates=(List<?>)geo.get("coordinates");var rings="MultiPolygon".equals(geo.get("type"))?(List<?>)coordinates.getFirst():coordinates;
      assertEquals(2,rings.size());assertEquals(5,((List<?>)rings.get(1)).size());
    }
  }
  @Test void rejectsMissingComponentsAndTraversal() throws Exception {
    var files=fixture();files.remove("assets.prj");assertThrows(IllegalArgumentException.class,()->new ShapefileReader(zip(files)));
    assertThrows(IllegalArgumentException.class,()->new ShapefileReader(zip(Map.of("../assets.shp",new byte[1]))));
  }
  @Test void rejectsIndexMismatchBeforeEmittingFeatures() throws Exception {
    var files=fixture();ByteBuffer.wrap(files.get("assets.shx")).putInt(100,60);
    assertThrows(IllegalArgumentException.class,()->new ShapefileReader(zip(files)));
  }
  static Map<String,byte[]> fixture(){
    var shp=ByteBuffer.allocate(128);header(shp,1);shp.order(ByteOrder.BIG_ENDIAN).position(100);shp.putInt(1).putInt(10);shp.order(ByteOrder.LITTLE_ENDIAN).putInt(1).putDouble(13.77).putDouble(45.65);
    var shx=ByteBuffer.allocate(108);header(shx,1);shx.order(ByteOrder.BIG_ENDIAN).position(100);shx.putInt(50).putInt(10);
    var dbf=ByteBuffer.allocate(70).order(ByteOrder.LITTLE_ENDIAN);dbf.put(0,(byte)3);dbf.putInt(4,1);dbf.putShort(8,(short)65);dbf.putShort(10,(short)4);dbf.position(32);dbf.put("CODE".getBytes(StandardCharsets.US_ASCII));dbf.put(43,(byte)'C');dbf.put(48,(byte)3);dbf.put(64,(byte)13);dbf.position(65);dbf.put((byte)' ').put("001".getBytes(StandardCharsets.US_ASCII)).put((byte)26);
    String wkt="GEOGCS[\"WGS 84\",DATUM[\"WGS_1984\",SPHEROID[\"WGS 84\",6378137,298.257223563]],PRIMEM[\"Greenwich\",0],UNIT[\"degree\",0.0174532925199433],AUTHORITY[\"EPSG\",\"4326\"]]";
    var files=new LinkedHashMap<String,byte[]>();files.put("assets.shp",shp.array());files.put("assets.shx",shx.array());files.put("assets.dbf",dbf.array());files.put("assets.prj",wkt.getBytes(StandardCharsets.UTF_8));files.put("assets.cpg","UTF-8".getBytes(StandardCharsets.US_ASCII));return files;
  }
  private static void header(ByteBuffer b,int type){b.order(ByteOrder.BIG_ENDIAN).putInt(0,9994).putInt(24,b.capacity()/2);b.order(ByteOrder.LITTLE_ENDIAN).putInt(28,1000).putInt(32,type);b.putDouble(36,13.77).putDouble(44,45.65).putDouble(52,13.77).putDouble(60,45.65);}
  static byte[] zip(Map<String,byte[]> files)throws IOException{var bytes=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(bytes)){for(var e:files.entrySet()){zip.putNextEntry(new ZipEntry(e.getKey()));zip.write(e.getValue());zip.closeEntry();}}return bytes.toByteArray();}
}
