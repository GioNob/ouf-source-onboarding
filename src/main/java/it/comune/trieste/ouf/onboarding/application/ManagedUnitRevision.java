package it.comune.trieste.ouf.onboarding.application;

import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import org.springframework.http.HttpStatus;
import java.util.*;

final class ManagedUnitRevision {
  private ManagedUnitRevision() {}

  /** Replace the bytes of a selected sheet/layer only while its approved interpretation and key still hold. */
  static void replace(Map<String,Object> config,Map<String,Object> base,Map<String,Object> oldProfile,
      SourceUnitProfiles.Unit from,Map<String,Object> asset,SourceUnitProfiles.Unit to,SourceUnitProfiles.Reference pin,
      int version,List<ManagedCsvRevision.NewColumn> newColumns) {
    if (!from.ready() || !to.ready() || !from.kind().equals(to.kind()) || !from.readParameters().equals(to.readParameters())
        || !Objects.equals(from.geometry(false),to.geometry(false)) || !from.declared().equals(to.declared()))
      throw fail(HttpStatus.CONFLICT,"ONB_UNIT_SCHEMA_CHANGED");
    var extraction=object(config,"extractionProfile");
    var runtime=object(extraction,"runtime");
    var execution=object(runtime,"execution");
    var selection=object(extraction,"selection");
    var bundle=object(config,"bundle");
    var oldId=oldProfile.get("profile_id").toString();
    String schema="profile://managed-files/"+base.get("asset_id")+"/profiles/"+oldId;
    if (!oldId.equals(runtime.get("fileProfileId")) || !oldId.equals(selection.get("fileProfileId"))
        || !base.get("asset_id").toString().equals(runtime.get("assetId")) || !schema.equals(execution.get("sourceSchemaRef"))
        || !schema.equals(bundle.get("schemaRef")) || !base.get("content_hash").equals(runtime.get("contentHash"))
        || !base.get("staging_ref").equals(runtime.get("stagingRef"))
        || !Objects.toString(base.get("size_bytes")).equals(Objects.toString(execution.get("expectedSize")))
        || !Objects.equals(asset.get("media_type"),base.get("media_type"))
        || !(from.layer()?"managed-geopackage-v1":"managed-xlsx-v1").equals(execution.get("adapterId"))
        || !from.readParameters().equals(execution.get("readParameters")) || !from.ref().equals(execution.get("unitRef")))
      throw fail(HttpStatus.CONFLICT,"ONB_UNIT_SELECTION_CHANGED");

    List<?> keys=(List<?>)object(config,"sourceObjectIdentityPolicy").get("sourceFields");
    var before=index(from);
    var after=index(to);
    var projected=(List<?>)object(extraction,"projection").get("FILE");
    if (keys==null || keys.isEmpty() || !projected.containsAll(keys) || new HashSet<>(keys).size()!=keys.size()
        || keys.stream().anyMatch(k->!before.containsKey(k) || !after.containsKey(k)
            || !Boolean.FALSE.equals(before.get(k).get("nullable")) || !Boolean.FALSE.equals(after.get(k).get("nullable"))
            || !"ATTRIBUTE".equals(after.get(k).get("role")))
        || keys.stream().noneMatch(k->from.candidates().contains(k) || from.layer() && k.equals(from.evidence().get("primaryKey"))))
      throw fail(HttpStatus.UNPROCESSABLE_ENTITY,"ONB_REVISION_STABLE_UNIT_KEY_REQUIRED");
    var proven=keys.stream().filter(k->to.candidates().contains(k) || to.layer() && k.equals(to.evidence().get("primaryKey")))
        .findFirst().orElseThrow(()->fail(HttpStatus.UNPROCESSABLE_ENTITY,"ONB_REVISION_STABLE_UNIT_KEY_REQUIRED"));
    for(Object name:projected){
      var was=before.get(name);
      var next=after.get(name);
      if(was==null || next==null || !List.of("name","role","nativeType","dataType").stream().allMatch(k->was.get(k).equals(next.get(k))))
        throw fail(HttpStatus.CONFLICT,"ONB_UNIT_SCHEMA_CHANGED");
    }
    long rows=((Number)to.evidence().get(to.layer()?"featureCount":"rows")).longValue();
    if (!(execution.get("maxRows") instanceof Number maxRows) || rows>maxRows.longValue()
        || !(execution.get("maxColumns") instanceof Number maxColumns) || after.size()>maxColumns.longValue())
      throw fail(HttpStatus.UNPROCESSABLE_ENTITY,"ONB_REVISION_EXECUTION_LIMIT_EXCEEDED");

    var decisions=newColumns==null?List.<ManagedCsvRevision.NewColumn>of():newColumns;
    var unique=new ArrayList<>(to.candidates());
    unique.add((String)proven);
    ManagedCsvRevision.checkColumns(types(before),types(after),Map.of(),(String)proven,unique,decisions);
    var additions=decisions.stream().filter(d->"MAP".equals(d.decision())).toList();
    if(!additions.isEmpty())ManagedCsvRevision.extendMappings(config,additions);
    if(config.get("excludedFieldReview") instanceof Map<?,?> excluded && oldId.equals(excluded.get("profileId")))
      config.put("excludedFieldReview",Map.of("profileId",pin.profileId().toString(),"labels",excluded.get("labels")));
    selection.put("unitProfileRef",pin.asMap());
    runtime.put("unitRef",to.ref());
    execution.put("unitRef",to.ref());
    bindAsset(selection,runtime,execution,bundle,asset,pin.profileId().toString(),version);
  }

  private static Map<String,Map<String,Object>> index(SourceUnitProfiles.Unit unit){
    var columns=new LinkedHashMap<String,Map<String,Object>>();
    for(var col:unit.structuralColumns())columns.put((String)col.get("name"),col);
    return columns;
  }

  private static Map<String,String> types(Map<String,Map<String,Object>> columns){
    var result=new LinkedHashMap<String,String>();
    columns.forEach((k,v)->result.put(k,ManagedFileService.readerInputType((String)v.get("dataType"))));
    return result;
  }

  static void bindAsset(Map<String,Object> selection,Map<String,Object> runtime,Map<String,Object> execution,
      Map<String,Object> bundle,Map<String,Object> asset,String profileId,int number) {
    String id=asset.get("asset_id").toString();
    String schema="profile://managed-files/"+id+"/profiles/"+profileId;
    selection.put("assetId",id);selection.put("fileProfileId",profileId);
    runtime.put("assetId",id);runtime.put("fileProfileId",profileId);
    runtime.put("stagingRef",asset.get("staging_ref"));runtime.put("contentHash",asset.get("content_hash"));
    execution.put("expectedSize",asset.get("size_bytes"));execution.put("sourceSchemaRef",schema);
    execution.put("sourceSchemaVersion",String.valueOf(number));bundle.put("schemaRef",schema);
  }

  @SuppressWarnings("unchecked")
  private static Map<String,Object> object(Map<String,Object> parent,String key) {
    if(!(parent.get(key) instanceof Map<?,?> map))throw fail(HttpStatus.CONFLICT,"ONB_UNIT_SELECTION_CHANGED");
    return (Map<String,Object>)map;
  }
  private static DomainFailure fail(HttpStatus status,String code) {return new DomainFailure(status,code,code);}
}
