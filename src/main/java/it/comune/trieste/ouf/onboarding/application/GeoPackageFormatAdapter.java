package it.comune.trieste.ouf.onboarding.application;

import java.util.*;
import it.comune.trieste.ouf.onboarding.application.ManagedFileProfiler.*;
import static it.comune.trieste.ouf.onboarding.application.ManagedFileProfiler.*;

final class GeoPackageFormatAdapter implements ManagedFileFormatAdapter {
  @Override public String mediaType() { return "application/geopackage+sqlite3"; }

  @Override public Profile profile(byte[] bytes){
    try(var reader=new it.comune.trieste.ouf.geopackage.GeoPackageReader(bytes)){
      var layers=new ArrayList<Map<String,Object>>();
      for(var layer:reader.layers()){
        var features=reader.features(layer,MAX_ROWS,1048576);
        var headers=layer.columns().stream().map(it.comune.trieste.ouf.geopackage.GeoPackageReader.Column::name).toList();
        var rows=features.stream().map(row->headers.stream().map(h->h.equals(layer.geometryColumn())?"[GEOMETRY]":Objects.toString(row.get(h),"")).toList()).toList();
        var tabular=build("GEOPACKAGE",Map.of(),headers,rows);
        var columns=tabular.columns().stream().map(c->c.name().equals(layer.geometryColumn())?new ColumnProfile(c.name(),"GEOMETRY",false,0):c).toList();
        var item=new LinkedHashMap<String,Object>();item.put("layer",layer.name());item.put("geometryColumn",layer.geometryColumn());item.put("geometryType",layer.geometryType());item.put("srsId",layer.srsId());item.put("sourceCrs",layer.crs());item.put("primaryKey",layer.primaryKey());item.put("columns",columns);
        item.put("nativeColumns",layer.columns());item.put("candidateKeys",tabular.candidateKeys().stream().filter(k->!k.equals(layer.geometryColumn())).toList());item.put("sample",tabular.sample());item.put("featureCount",features.size());layers.add(item);
      }
      return new Profile("GEOPACKAGE",Map.of("layers",layers,"layerSelection","REQUIRED","recordModel","ONE_FEATURE_ONE_SOURCE_OBJECT","dimensions",2),List.of(),List.of(),List.of());
    }
  }
}
