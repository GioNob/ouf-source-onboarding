package it.comune.trieste.ouf.onboarding.application;

import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import org.springframework.http.HttpStatus;
import java.util.*;

final class ManagedUnitRevision {
  private ManagedUnitRevision() {}

  /** Replace the bytes of a selected sheet/layer only while its approved interpretation and key still hold. */
  static void replace(Map<String,Object> config,Map<String,Object> base,Map<String,Object> asset,
      SourceUnitProfiles.Reference oldPin,SourceUnitProfiles.Reference nextPin,SourceUnitProfiles.Unit from,
      SourceUnitProfiles.Unit to,boolean hasColumnChanges,int number) {
    if(!oldPin.schemaHash().equals(nextPin.schemaHash()) || !from.kind().equals(to.kind())
        || !from.readParameters().equals(to.readParameters()) || hasColumnChanges)
      throw fail(HttpStatus.CONFLICT,"ONB_UNIT_SCHEMA_CHANGED");
    var extraction=object(config,"extractionProfile");
    var selection=object(extraction,"selection");
    var runtime=object(extraction,"runtime");
    var execution=object(runtime,"execution");
    var bundle=object(config,"bundle");
    var oldSchema="profile://managed-files/"+base.get("asset_id")+"/profiles/"+oldPin.profileId();
    var media=to.layer()?"application/geopackage+sqlite3":"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    if(!media.equals(base.get("media_type")) || !media.equals(asset.get("media_type"))
        || !oldSchema.equals(bundle.get("schemaRef")) || !oldSchema.equals(execution.get("sourceSchemaRef"))
        || !Objects.equals(base.get("staging_ref"),runtime.get("stagingRef"))
        || !Objects.equals(base.get("content_hash"),runtime.get("contentHash"))
        || !Objects.toString(base.get("size_bytes")).equals(Objects.toString(execution.get("expectedSize")))
        || !(to.layer()?"managed-geopackage-v1":"managed-tabular-v1").equals(execution.get("adapterId"))
        || !(to.layer()?"INTERNAL_MANAGED_GEOPACKAGE":"INTERNAL_MANAGED_XLSX").equals(execution.get("acquisitionMode"))
        || !from.name().equals(execution.get(to.layer()?"layer":"sheet"))
        || execution.containsKey("readParameters") && !from.readParameters().equals(execution.get("readParameters"))
        || !to.layer() && execution.containsKey("headerRow") && !Objects.equals(from.evidence().get("headerRow"),execution.get("headerRow"))
        || to.layer() && (!Objects.equals(from.evidence().get("geometryColumn"),execution.get("geometryColumn"))
            || !Objects.equals(from.evidence().get("sourceCrs"),execution.get("sourceCrs"))))
      throw fail(HttpStatus.CONFLICT,"ONB_UNIT_SELECTION_CHANGED");
    var keys=object(config,"sourceObjectIdentityPolicy").get("sourceFields");
    if(!(keys instanceof List<?> fields) || fields.isEmpty()
        || fields.stream().noneMatch(k->to.candidates().contains(k) || to.layer() && k.equals(to.evidence().get("primaryKey"))))
      throw fail(HttpStatus.UNPROCESSABLE_ENTITY,"ONB_REVISION_STABLE_UNIT_KEY_REQUIRED");
    long rows=((Number)to.evidence().get(to.layer()?"featureCount":"rows")).longValue();
    if(!(execution.get("maxRows") instanceof Number maxRows) || rows>maxRows.longValue()
        || !to.layer() && (!(execution.get("maxColumns") instanceof Number maxColumns) || to.columns().size()>maxColumns.intValue()))
      throw fail(HttpStatus.UNPROCESSABLE_ENTITY,"ONB_REVISION_EXECUTION_LIMIT_EXCEEDED");
    bindAsset(selection,runtime,execution,bundle,asset,nextPin.profileId().toString(),number);
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
