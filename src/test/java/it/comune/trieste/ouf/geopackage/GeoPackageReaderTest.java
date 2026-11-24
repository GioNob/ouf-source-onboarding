package it.comune.trieste.ouf.geopackage;

import static org.assertj.core.api.Assertions.*;
import java.nio.*;
import org.junit.jupiter.api.Test;

class GeoPackageReaderTest {
  @Test void inventoriesEveryLayerAndPreservesCrsAndNullableAttributes()throws Exception{
    try(var reader=new GeoPackageReader(getClass().getResourceAsStream("/geopackage/cameras.gpkg").readAllBytes())){
      var layers=reader.layers();assertThat(layers).hasSize(2);
      var cameras=layers.stream().filter(l->l.name().equals("cameras")).findFirst().orElseThrow();
      assertThat(cameras.crs()).isEqualTo("EPSG:4326");assertThat(cameras.primaryKey()).isEqualTo("fid");
      var rows=reader.features(cameras,100,1000);assertThat(rows).hasSize(2);assertThat(rows.get(1)).containsEntry("cabinet",null);
      assertThat(String.valueOf(rows.get(0).get("geom"))).contains("13.77","45.65","EPSG:4326");
      assertThatThrownBy(()->reader.features(cameras,1,1000)).hasMessage("GPKG_INVALID_OR_UNSUPPORTED");
    }
  }
  @Test void validatesBothByteOrdersAndRejectsWrongSridDimensionsExtensionsAndTrailingData(){
    for(var order:new ByteOrder[]{ByteOrder.LITTLE_ENDIAN,ByteOrder.BIG_ENDIAN}){
      byte flag=(byte)(order==ByteOrder.LITTLE_ENDIAN?1:0);
      var b=ByteBuffer.allocate(29).order(order).put((byte)'G').put((byte)'P').put((byte)0).put(flag).putInt(4326).put(flag).putInt(1).putDouble(13).putDouble(45);
      byte[] bytes=b.array();assertThat(GeoPackageReader.geometry(bytes,4326)).containsEntry("type","Point");
      assertThatThrownBy(()->GeoPackageReader.geometry(bytes,6708)).isInstanceOf(IllegalArgumentException.class);
      byte[] extra=java.util.Arrays.copyOf(bytes,30);assertThatThrownBy(()->GeoPackageReader.geometry(extra,4326)).isInstanceOf(IllegalArgumentException.class);
      for(int dimensions:new int[]{1001,2001,3001}){
        b.putInt(9,dimensions);
        assertThatThrownBy(()->GeoPackageReader.geometry(bytes,4326)).isInstanceOf(IllegalArgumentException.class);
      }
      b.putInt(9,1);bytes[3]|=32;assertThatThrownBy(()->GeoPackageReader.geometry(bytes,4326)).isInstanceOf(IllegalArgumentException.class);
    }
  }
  @Test void d084_32_geometryCoordinatesUseEcmascriptRoundTripNumbers()throws Exception{
    var json=new com.fasterxml.jackson.databind.ObjectMapper();
    var cases=java.util.Map.of(
        -0.0,"0",Double.MIN_VALUE,"5e-324",Math.nextUp(Double.MIN_VALUE),"1e-323",
        Double.MAX_VALUE,"1.7976931348623157e+308",1e-6,"0.000001",1e-7,"1e-7",
        1e20,"100000000000000000000",1e23,"1e+23");
    for(var entry:cases.entrySet()){
      var coordinate=new GeometryCoordinate(entry.getKey());
      String canonical=json.writeValueAsString(coordinate);
      assertThat(canonical).isEqualTo(entry.getValue());
      assertThat(Double.parseDouble(canonical)).isEqualTo(entry.getKey()==0?0.0:entry.getKey());
    }
    double x=Math.nextUp(13.77),y=Math.nextDown(45.65);
    byte[] point=ByteBuffer.allocate(29).order(ByteOrder.LITTLE_ENDIAN)
        .put((byte)'G').put((byte)'P').put((byte)0).put((byte)1).putInt(4326)
        .put((byte)1).putInt(1).putDouble(x).putDouble(y).array();
    var position=json.readTree(json.writeValueAsBytes(GeoPackageReader.geometry(point,4326))).path("coordinates");
    assertThat(position.get(0).asDouble()).isEqualTo(x);
    assertThat(position.get(1).asDouble()).isEqualTo(y);
  }
  @Test void rejectsNonSqliteAndTruncatedWkb(){assertThatThrownBy(()->new GeoPackageReader(new byte[120])).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->GeoPackageReader.geometry(new byte[8],4326)).isInstanceOf(IllegalArgumentException.class);}
}
