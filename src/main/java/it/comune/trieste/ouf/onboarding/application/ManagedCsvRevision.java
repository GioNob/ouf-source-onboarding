package it.comune.trieste.ouf.onboarding.application;

import java.util.*;

/** Rebinds the owner-compiled CSV configuration on a private draft after checking both pinned profiles. */
public final class ManagedCsvRevision {
  private ManagedCsvRevision() {}

  public record Finding(String code,String column,String expected,String actual) {}
  public static final class Incompatible extends RuntimeException {
    private static final long serialVersionUID=1L;
    private final List<Finding> diagnostics;
    Incompatible(List<Finding> diagnostics){
      super("ONB_REVISION_CSV_INCOMPATIBLE");
      this.diagnostics=List.copyOf(diagnostics);
    }
    public List<Finding> diagnostics(){return diagnostics;}
  }

  static void replace(Map<String,Object> config,Map<String,Object> previous,Map<String,Object> oldProfile,
      Map<String,Object> asset,Map<String,Object> profile,int version){
    var extraction=object(config,"extractionProfile");
    var runtime=object(extraction,"runtime");
    var selection=object(extraction,"selection");
    var execution=object(runtime,"execution");
    var bundle=object(config,"bundle");
    var source=object(bundle,"source");
    String sourceId=text(extraction,"sourceId");
    require("INTERNAL_MANAGED".equals(source.get("sourceKind"))&&"MANAGED".equals(source.get("acquisitionMode"))
        &&sourceId.equals(source.get("sourceId"))&&sourceId.equals(execution.get("sourceSchemaId"))
        &&"INTERNAL_MANAGED_CSV".equals(execution.get("acquisitionMode"))
        &&"managed-tabular-v1".equals(execution.get("adapterId"))
        &&"CSV".equals(oldProfile.get("format"))&&"CSV".equals(profile.get("format"))
        &&"text/csv".equals(asset.get("media_type"))&&"text/csv".equals(previous.get("media_type"))
        &&"ONE_ROW_ONE_SOURCE_OBJECT".equals(runtime.get("recordModel"))&&"MANAGED".equals(runtime.get("mode")));
    String oldId=previous.get("asset_id").toString(),oldProfileId=oldProfile.get("profile_id").toString();
    String oldRef=schemaRef(oldId,oldProfileId);
    require(selection.keySet().equals(Set.of("assetId","fileProfileId"))
        &&oldId.equals(selection.get("assetId"))&&oldId.equals(runtime.get("assetId"))
        &&oldProfileId.equals(selection.get("fileProfileId"))&&oldProfileId.equals(runtime.get("fileProfileId"))
        &&oldRef.equals(bundle.get("schemaRef"))&&oldRef.equals(execution.get("sourceSchemaRef"))
        &&previous.get("staging_ref").equals(runtime.get("stagingRef"))
        &&previous.get("content_hash").equals(runtime.get("contentHash"))
        &&number(previous,"size_bytes")==number(execution,"expectedSize")
        &&Objects.equals(runtime.get("csvDelimiter"),object(oldProfile,"metadata").get("delimiter")));

    var projected=strings(object(extraction,"projection").get("FILE"));
    var identity=object(config,"sourceObjectIdentityPolicy");
    var keys=strings(identity.get("sourceFields"));
    require("NATIVE_KEY".equals(identity.get("strategy"))&&keys.size()==1&&projected.containsAll(keys)
        &&"APPROVED_SOURCE_FIELDS".equals(runtime.get("rowIdentityBasis")));
    var semantic=object(config,"semanticMapping");
    var udp=object(runtime,"udp");
    require(!runtime.containsKey("layer")&&!runtime.containsKey("format")&&!runtime.containsKey("endpointRef")
        &&!bundle.containsKey("sourceRuntimeProfileRef")&&!bundle.containsKey("bindingRef")&&!config.containsKey("sourceSchemaEvidence")
        &&!udp.containsKey("spatial")&&!udp.containsKey("relationships")
        &&List.of().equals(config.getOrDefault("relationshipMappings",List.of()))
        &&List.of().equals(semantic.getOrDefault("vocabularyMappings",List.of())));
    var mappings=semantic.get("propertyMappings");
    require(mappings instanceof List<?>);
    var mapped=new LinkedHashMap<String,String>();
    for(var item:(List<?>)mappings){
      require(item instanceof Map<?,?>);
      var mapping=(Map<?,?>)item;
      require(mapped.put(text(mapping,"sourceField"),text(mapping,"transform"))==null);
    }
    require(!mapped.isEmpty()&&mapped.size()==projected.size()&&mapped.keySet().equals(new HashSet<>(projected)));
    var nextColumns=columns(profile);
    checkColumns(columns(oldProfile),nextColumns,mapped,keys.getFirst(),strings(profile.get("candidate_keys")));
    var metadata=object(profile,"metadata");
    String delimiter=text(metadata,"delimiter");
    require(Set.of(",",";").contains(delimiter));
    if(number(metadata,"rows")>number(execution,"maxRows")||nextColumns.size()>number(execution,"maxColumns"))
      throw new Incompatible(List.of(new Finding("EXECUTION_LIMIT_EXCEEDED",null,null,null)));

    String id=asset.get("asset_id").toString(),profileId=profile.get("profile_id").toString();
    String ref=schemaRef(id,profileId);
    selection.put("assetId",id);
    selection.put("fileProfileId",profileId);
    runtime.put("assetId",id);
    runtime.put("fileProfileId",profileId);
    runtime.put("stagingRef",asset.get("staging_ref"));
    runtime.put("contentHash",asset.get("content_hash"));
    runtime.put("csvDelimiter",delimiter);
    execution.put("expectedSize",asset.get("size_bytes"));
    execution.put("sourceSchemaRef",ref);
    execution.put("sourceSchemaVersion",String.valueOf(version));
    bundle.put("schemaRef",ref);
  }

  static void checkColumns(Map<String,String> before,Map<String,String> after,Map<String,String> mapped,
      String key,List<String> candidateKeys){
    require(before.keySet().containsAll(mapped.keySet()));
    List<Finding> findings=new ArrayList<>();
    for(var name:before.keySet()){
      String oldType=before.get(name),newType=after.get(name);
      if(newType==null)findings.add(new Finding(mapped.containsKey(name)?"MISSING_MAPPED_COLUMN":"MISSING_COLUMN",name,oldType,null));
      else if(mapped.containsKey(name)&&!oldType.equals(newType))
        findings.add(new Finding("TYPE_CHANGED",name,oldType,newType));
      else if(mapped.containsKey(name)&&!supports(mapped.get(name),newType))
        findings.add(new Finding("TRANSFORM_TYPE_MISMATCH",name,mapped.get(name),newType));
    }
    for(var name:after.keySet())
      if(!before.containsKey(name))findings.add(new Finding("NEW_COLUMN",name,null,after.get(name)));
    if(after.containsKey(key)&&!candidateKeys.contains(key))
      findings.add(new Finding("KEY_NOT_UNIQUE_OR_NULL",key,null,null));
    if(!findings.isEmpty())throw new Incompatible(findings);
  }

  private static boolean supports(String transform,String type){
    return switch(transform){
      case "IDENTITY" -> true; // The adapter preserves lexical CSV cells.
      case "DECIMAL_DOT_V1","DECIMAL_COMMA_V1" -> type.equals("LONG")||type.equals("DECIMAL");
      case "DATE_ISO_V1" -> type.equals("DATE");
      case "DATE_DMY_V1" -> type.equals("STRING"); // Lexical validation belongs to ingestion readiness.
      default -> false;
    };
  }
  private static String schemaRef(String asset,String profile){return "profile://managed-files/"+asset+"/profiles/"+profile;}
  private static Map<String,String> columns(Map<String,Object> profile){
    var schema=profile.get("inferred_schema");
    require(schema instanceof List<?> list&&!list.isEmpty()&&list.size()<=ManagedFileLimits.MAX_COLUMNS);
    var columns=new LinkedHashMap<String,String>();
    for(var entry:(List<?>)schema){
      require(entry instanceof Map<?,?>);
      var column=(Map<?,?>)entry;
      String type=text(column,"dataType");
      require(Set.of("STRING","LONG","DECIMAL","BOOLEAN","DATE").contains(type)&&columns.put(text(column,"name"),type)==null);
    }
    return columns;
  }
  private static long number(Map<?,?> map,String key){
    require(map.get(key) instanceof Number n&&n.longValue()>0);
    return ((Number)map.get(key)).longValue();
  }
  private static String text(Map<?,?> map,String key){
    require(map.get(key) instanceof String s&&!s.isBlank());
    return (String)map.get(key);
  }
  @SuppressWarnings("unchecked") private static List<String> strings(Object raw){
    require(raw instanceof List<?> list&&list.stream().allMatch(s->s instanceof String));
    return (List<String>)raw;
  }
  @SuppressWarnings("unchecked") private static Map<String,Object> object(Map<String,Object> map,String key){
    require(map.get(key) instanceof Map<?,?>);
    return (Map<String,Object>)map.get(key);
  }
  private static void require(boolean ok){
    if(!ok)throw new Incompatible(List.of(new Finding("UNSUPPORTED_CSV_CONTRACT",null,null,null)));
  }
}
