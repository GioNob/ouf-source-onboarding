package it.comune.trieste.ouf.onboarding.application;

import java.util.*;

/** Profile compatibility is a coarse filter. Ingestion validates each lexical cell. */
enum ValueReader {
  IDENTITY("string"), TEXT_NFC_TRIM_V1("string"),
  INTEGER_V1("integer","LONG","STRING"),
  INTEGER_GROUP_DOT_V1("integer","LONG","DECIMAL","STRING"),
  DECIMAL_DOT_V1("decimal","LONG","DECIMAL"), DECIMAL_COMMA_V1("decimal","LONG","DECIMAL"),
  DECIMAL_GROUP_DOT_COMMA_V1("decimal","LONG","DECIMAL","STRING"),
  DECIMAL_GROUP_COMMA_DOT_V1("decimal","LONG","DECIMAL","STRING"),
  BOOLEAN_IT_V1("boolean","BOOLEAN","LONG","STRING"),
  DATE_ISO_V1("date","DATE"), DATE_DMY_V1("date","STRING"), DATE_DMY_DASH_V1("date","STRING"),
  DATETIME_ISO_OFFSET_V1("datetime","STRING"), DATETIME_LOCAL_ROME_V1("datetime","STRING"),
  GEOJSON_V1("geometry","STRING"), WKT_V1("geometry","STRING"), VALUE_MAP_V1("concept");

  final String output;
  private final Set<String> profiles;
  ValueReader(String output,String... profiles){this.output=output;this.profiles=Set.of(profiles);}
  boolean supports(String profile){
    if(profile==null)return false;
    // Existing managed geometry mappings preserve the adapter's native value via IDENTITY.
    if("GEOMETRY".equals(profile))return this==IDENTITY;
    return profiles.isEmpty()?Set.of("STRING","LONG","DECIMAL","BOOLEAN","DATE").contains(profile):profiles.contains(profile);
  }
  static ValueReader named(String id){
    try{return valueOf(id);}catch(IllegalArgumentException|NullPointerException e){throw invalid();}
  }
  static IllegalArgumentException invalid(){return new IllegalArgumentException("ONB_VALUE_READER_INVALID");}
  Map<String,Object> mappingParameters(Map<?,?> property){
    Object alias=property.get("valueMapRef");
    if(alias!=null && !(alias instanceof String))throw invalid();
    return parameters(property.get("transformParams"),(String)alias);
  }
  Map<String,Object> parameters(Object raw,String legacyMap){
    if(raw!=null && !(raw instanceof Map<?,?>))throw invalid();
    Map<?,?> params=raw==null?Map.of():(Map<?,?>)raw;
    if(this==VALUE_MAP_V1){
      Object ref=params.get("valueMapRef");
      if(!Set.of("valueMapRef").containsAll(params.keySet()) || (params.containsKey("valueMapRef") && !(ref instanceof String))
          || (legacyMap!=null && ref!=null && !legacyMap.equals(ref)))throw invalid();
      if(ref==null)ref=legacyMap;
      if(!(ref instanceof String s)||s.isBlank())throw invalid();
      return Map.of("valueMapRef",ref);
    }
    if(this==WKT_V1){
      if(!params.keySet().equals(Set.of("crs")) || !(params.get("crs") instanceof String crs)
          || !crs.matches("EPSG:[0-9]+"))throw invalid();
      return Map.of("crs",crs);
    }
    if(!params.isEmpty())throw invalid();
    return Map.of();
  }
  static final Map<String,String> DATATYPES=Map.of(
      "string","http://www.w3.org/2001/XMLSchema#string","integer","http://www.w3.org/2001/XMLSchema#integer",
      "decimal","http://www.w3.org/2001/XMLSchema#decimal","boolean","http://www.w3.org/2001/XMLSchema#boolean",
      "date","http://www.w3.org/2001/XMLSchema#date","datetime","http://www.w3.org/2001/XMLSchema#dateTime",
      "geometry","http://www.opengis.net/ont/geosparql#geoJSONLiteral","concept","http://www.w3.org/2001/XMLSchema#anyURI");
  static String comparator(String datatype){
    if(datatype.equals(DATATYPES.get("decimal"))||datatype.equals(DATATYPES.get("integer")))return "DECIMAL_V1";
    if(datatype.equals(DATATYPES.get("concept")))return "CONCEPT";
    if(datatype.equals(DATATYPES.get("geometry"))||!DATATYPES.containsValue(datatype))return "JSON_V1";
    return "TEXT_V1";
  }
}
