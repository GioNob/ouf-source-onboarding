package it.comune.trieste.ouf.onboarding.application;

import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ConfigurationValidator {
  public record Finding(String severity,String code,String path,String message) {}
  public record Result(List<Finding> findings) {
    public long errors(){return findings.stream().filter(f->"ERROR".equals(f.severity())).count();}
    public long warnings(){return findings.stream().filter(f->"WARNING".equals(f.severity())).count();}
    public boolean valid(){return errors()==0;}
  }

  public Result validate(String sourceId, Map<String,Object> configuration){
    List<Finding> out=new ArrayList<>();
    requireObject(configuration,"syncProfile",out); requireObject(configuration,"extractionProfile",out);
    requireObject(configuration,"semanticMapping",out); requireObject(configuration,"bundle",out);
    object(configuration,"syncProfile").ifPresent(sync->{
      requireEnum(sync,"bootstrap",Set.of("FULL_SNAPSHOT"),"/syncProfile/bootstrap",out);
      requireEnum(sync,"incremental",Set.of("CHANGE_TOKEN","LAST_MODIFIED","MONOTONIC_ID","SNAPSHOT_DIFF","NONE"),"/syncProfile/incremental",out);
      Object page=sync.get("pageSize"); if(page instanceof Number n && (n.longValue()<1||n.longValue()>10000)) error(out,"ONB_PAGE_SIZE_INVALID","/syncProfile/pageSize","pageSize must be between 1 and 10000");
      if(sync.get("pollInterval") instanceof String value)try{long seconds=java.time.Duration.parse(value).toSeconds();if(seconds<60||seconds>2_678_400)error(out,"ONB_POLL_INTERVAL_INVALID","/syncProfile/pollInterval","pollInterval must be between PT1M and P31D");}catch(Exception e){error(out,"ONB_POLL_INTERVAL_INVALID","/syncProfile/pollInterval","pollInterval must be an ISO-8601 duration");}
      if(sync.get("pollInterval")!=null)validateScheduledPolicy(sync,out);
    });
    object(configuration,"extractionProfile").ifPresent(ex->{
      requireText(ex,"profileId","/extractionProfile/profileId",out); requireText(ex,"version","/extractionProfile/version",out); requireText(ex,"sourceId","/extractionProfile/sourceId",out);
      requireObject(ex,"selection","/extractionProfile/selection",out); requireObject(ex,"projection","/extractionProfile/projection",out); requireObject(ex,"sync","/extractionProfile/sync",out);
      if(ex.get("sourceId") instanceof String s && !sourceId.equals(s)) error(out,"ONB_SOURCE_BINDING_MISMATCH","/extractionProfile/sourceId","sourceId does not match the onboarding source");
      object(ex,"runtime").ifPresent(runtime->{Object credential=runtime.get("credentialRef");if(credential instanceof String s&&!(s.startsWith("secret://")||s.startsWith("workload://")))error(out,"ONB_SECRET_REF_INVALID","/extractionProfile/runtime/credentialRef","credentialRef must use secret:// or workload:// and never contain a plaintext credential");});
    });
    object(configuration,"semanticMapping").ifPresent(mapping->{
      requireText(mapping,"mappingId","/semanticMapping/mappingId",out); requireObject(mapping,"sourceType","/semanticMapping/sourceType",out);
      requireNonEmptyList(mapping,"targetClasses","/semanticMapping/targetClasses",out); requireNonEmptyList(mapping,"semanticRefs","/semanticMapping/semanticRefs",out);
      if(mapping.get("targetClasses") instanceof List<?> targets)for(int i=0;i<targets.size();i++){Object target=targets.get(i);if(!(target instanceof Map<?,?> raw)){error(out,"ONB_SEMANTIC_TARGET_INVALID","/semanticMapping/targetClasses/"+i,"Target class must be an object");continue;}Map<String,Object> item=new LinkedHashMap<>();raw.forEach((k,v)->item.put(String.valueOf(k),v));for(String key:List.of("ontologyId","ontologyVersion","classIri"))requireText(item,key,"/semanticMapping/targetClasses/"+i+"/"+key,out);}
      if(mapping.get("semanticRefs") instanceof List<?> refs)for(int i=0;i<refs.size();i++)if(!(refs.get(i) instanceof String s)||!s.matches("[^@\\s]+@[^@\\s]+"))error(out,"ONB_SEMANTIC_REF_INVALID","/semanticMapping/semanticRefs/"+i,"Semantic references must pin id@version");
      object(mapping,"sourceType").ifPresent(type->{if(type.get("sourceId") instanceof String s&&!sourceId.equals(s))error(out,"ONB_SOURCE_BINDING_MISMATCH","/semanticMapping/sourceType/sourceId","sourceId does not match the onboarding source");requireText(type,"typeCode","/semanticMapping/sourceType/typeCode",out);});
    });
    object(configuration,"bundle").ifPresent(bundle->{
      for(String key:List.of("bundleId","bundleVersion","environment","createdFromOnboardingVersion","effectiveFrom","checksum","status"))requireText(bundle,key,"/bundle/"+key,out);
      requireEnum(bundle,"status",Set.of("APPROVED","ACTIVE","SUPERSEDED","REVOKED"),"/bundle/status",out); requireObject(bundle,"source","/bundle/source",out);
      object(bundle,"source").ifPresent(source->{requireText(source,"sourceId","/bundle/source/sourceId",out);requireText(source,"sourceKind","/bundle/source/sourceKind",out);requireText(source,"acquisitionMode","/bundle/source/acquisitionMode",out);if(source.get("sourceId") instanceof String s&&!sourceId.equals(s))error(out,"ONB_SOURCE_BINDING_MISMATCH","/bundle/source/sourceId","sourceId does not match the onboarding source");if("PULL".equals(source.get("acquisitionMode")))requireText(bundle,"bindingRef","/bundle/bindingRef",out);});
      object(bundle,"changeRepresentationProfile").ifPresent(change->{if("DELTA_PATCH".equals(change.get("mode"))&&object(bundle,"deltaPatchContract").isEmpty())error(out,"ONB_DELTA_CONTRACT_REQUIRED","/bundle/deltaPatchContract","DELTA_PATCH requires a compiled deltaPatchContract");});
    });
    Optional<Map<String,Object>> topLevelChange=object(configuration,"changeRepresentationProfile");Optional<Map<String,Object>> change=topLevelChange.isPresent()?topLevelChange:object(configuration,"bundle").flatMap(b->object(b,"changeRepresentationProfile"));change.ifPresent(profile->{
      requireEnum(profile,"mode",Set.of("FULL_SNAPSHOT","DELTA_PATCH","PROPERTY_EVENTS"),"/changeRepresentationProfile/mode",out);String mode=String.valueOf(profile.get("mode"));
      if("PROPERTY_EVENTS".equals(mode))requireText(profile,"eventContractRef","/changeRepresentationProfile/eventContractRef",out);
      if("DELTA_PATCH".equals(mode)){Optional<Map<String,Object>> delta=object(configuration,"deltaPatchContract");if(delta.isEmpty())delta=object(configuration,"bundle").flatMap(b->object(b,"deltaPatchContract"));if(delta.isEmpty())error(out,"ONB_DELTA_CONTRACT_REQUIRED","/deltaPatchContract","DELTA_PATCH requires a compiled logical contract");else validateDelta(delta.get(),out);}
    });
    validateGeoPackageAndRelationships(configuration,out);
    spatial(configuration).ifPresent(profile->validateSpatial(profile,out));
    if(out.isEmpty()) out.add(new Finding("INFO","ONB_CONFIGURATION_VALID","/","Configuration satisfies the executable onboarding validation profile"));
    return new Result(List.copyOf(out));
  }

  private static void validateGeoPackageAndRelationships(Map<String,Object> configuration,List<Finding> out){
    var runtime=object(configuration,"extractionProfile").flatMap(ex->object(ex,"runtime")).orElse(Map.of());
    var execution=object(runtime,"execution").orElse(Map.of());var udp=object(runtime,"udp").orElse(Map.of());
    if("INTERNAL_MANAGED_ACCESS".equals(execution.get("acquisitionMode"))){
      String path="/extractionProfile/runtime";
      if(!"managed-access-v1".equals(execution.get("adapterId"))||!(runtime.get("layer") instanceof String table)||table.isBlank()||!Objects.equals(runtime.get("layer"),execution.get("layer")))error(out,"ONB_ACCESS_PROFILE_MISMATCH",path,"Access execution must retain its approved table and adapter");
      Object keys=object(configuration,"sourceObjectIdentityPolicy").orElse(Map.of()).get("sourceFields");
      if(!(keys instanceof List<?> list)||list.isEmpty()||list.contains("$managedRowOrdinal"))error(out,"ONB_ACCESS_STABLE_KEY_REQUIRED",path,"Access requires approved stable key fields");
    }
    if(Set.of("INTERNAL_MANAGED_GEOPACKAGE","INTERNAL_MANAGED_SHAPEFILE").contains(Objects.toString(execution.get("acquisitionMode"),""))){
      String path="/extractionProfile/runtime";
      for(String key:List.of("layer","sourceCrs","geometryColumn"))if(!(runtime.get(key) instanceof String value)||value.isBlank()||!Objects.equals(value,execution.get(key)))error(out,"ONB_GPKG_PROFILE_MISMATCH",path+"/"+key,"Execution must retain the approved GeoPackage layer, CRS and geometry column");
      var keys=object(configuration,"sourceObjectIdentityPolicy").orElse(Map.of()).get("sourceFields");
      if(!(keys instanceof List<?> list)||list.isEmpty()||list.contains("$managedRowOrdinal")||list.contains(runtime.get("geometryColumn")))error(out,"ONB_GPKG_STABLE_KEY_REQUIRED","/sourceObjectIdentityPolicy","GeoPackage requires explicit stable feature keys");
      var geometry=object(udp,"spatial").flatMap(sp->object(sp,"geometry")).orElse(Map.of());
      if(!Objects.equals(runtime.get("sourceCrs"),geometry.get("expectedSourceCrs"))||geometry.isEmpty())error(out,"ONB_GPKG_CRS_POLICY_REQUIRED",path+"/udp/spatial","GeoPackage requires its approved source CRS and governed spatial profile");
    }
    if(udp.containsKey("relationships")){
      var profile=object(udp,"relationships").orElse(Map.of());String path="/extractionProfile/runtime/udp/relationships";requireText(profile,"policyRef",path+"/policyRef",out);
      if(!(profile.get("relationships") instanceof List<?> rules)||rules.isEmpty()||rules.size()>64){error(out,"ONB_RELATION_RULES_REQUIRED",path,"Require 1..64 governed relationship rules");return;}
      var unique=new HashSet<Object>();
      for(Object raw:rules){if(!(raw instanceof Map<?,?> rule)){error(out,"ONB_RELATION_RULE_INVALID",path,"Relationship must be an object");continue;}
        if(!unique.add(rule.get("relationIri"))||!"CANONICAL_KEY".equals(rule.get("resolutionStrategy"))||!"QUARANTINE_RELATION".equals(rule.get("onNoMatch")))error(out,"ONB_RELATION_RULE_INVALID",path,"R2e requires unique relations, explicit key matching and quarantine on no match");
        for(String key:List.of("sourceField","relationIri","targetCanonicalType","targetPropertyIri","accessLabel"))if(!(rule.get(key) instanceof String value)||value.isBlank())error(out,"ONB_RELATION_RULE_INVALID",path+"/"+key,"Required relationship field missing");
        boolean label=configuration.get("dataAccessPolicies") instanceof List<?> policies&&policies.stream().anyMatch(p->p instanceof Map<?,?> policy&&"RELATIONSHIP".equals(policy.get("scope"))&&Objects.equals(rule.get("relationIri"),policy.get("target"))&&Objects.equals(rule.get("accessLabel"),policy.get("label")));
        if(!label)error(out,"ONB_RELATION_LABEL_REQUIRED",path,"Relationship label must be approved explicitly");
      }
    }
  }

  public static Optional<Map<String,Object>> spatial(Map<String,Object> configuration){
    return object(configuration,"extractionProfile").flatMap(ex->object(ex,"runtime")).flatMap(r->object(r,"udp")).filter(u->u.containsKey("spatial")).map(u->object(u,"spatial").orElse(Map.of()));
  }
  private static void validateSpatial(Map<String,Object> profile,List<Finding> out){
    String path="/extractionProfile/runtime/udp/spatial";
    requireText(profile,"policyRef",path+"/policyRef",out);
    if(!(profile.get("relationships") instanceof List<?> relations)||!relations.isEmpty())error(out,"ONB_SPATIAL_RELATION_POLICY_UNSUPPORTED",path+"/relationships","R2d accepts geometry only; relationship binding belongs to R2e");
    var geometry=object(profile,"geometry");if(geometry.isEmpty()){error(out,"ONB_CRS_PROFILE_REQUIRED",path+"/geometry","Explicit geometry and CRS profile required");return;}
    var g=geometry.get();for(String key:List.of("sourceField","normalizationVersion","accessLabel"))requireText(g,key,path+"/geometry/"+key,out);
    Object crs=g.get("expectedSourceCrs"),srid=g.get("canonicalSrid");
    if(!(crs instanceof String c)||!c.matches("EPSG:[1-9][0-9]{0,5}")||!(srid instanceof Number n)||n.doubleValue()!=n.intValue()||n.intValue()<1||n.intValue()>999999){error(out,"ONB_CRS_UNKNOWN",path+"/geometry","Known EPSG source CRS and explicit positive municipal SRID required");return;}
    int source=Integer.parseInt(String.valueOf(crs).substring(5)),target=((Number)srid).intValue();
    var decision=object(g,"crsPolicy");if(decision.isEmpty()){error(out,"ONB_CRS_DECISION_REQUIRED",path+"/geometry/crsPolicy","Declare XY or YX axes and the human choice CONVERT or REJECT");return;}
    var policy=decision.get();requireEnum(policy,"sourceAxisOrder",Set.of("XY","YX"),path+"/geometry/crsPolicy/sourceAxisOrder",out);requireEnum(policy,"mismatchAction",Set.of("CONVERT","REJECT"),path+"/geometry/crsPolicy/mismatchAction",out);
    if(source!=target&&"CONVERT".equals(policy.get("mismatchAction")))validateOperation(policy,"sourceOperation",source,target,path,out);
    if(target!=4326&&!(source!=target&&"REJECT".equals(policy.get("mismatchAction"))))validateOperation(policy,"servingOperation",target,4326,path,out);
    out.add(new Finding("INFO","ONB_CRS_HUMAN_DECISION",path,"Source "+crs+", axes "+policy.get("sourceAxisOrder")+", municipal EPSG:"+target+", mismatch "+policy.get("mismatchAction")+". Approval pins this choice, both operations, accuracy and resources for subsequent runs."));
  }
  private static void validateOperation(Map<String,Object> policy,String key,int source,int target,String path,List<Finding> out){
    String p=path+"/geometry/crsPolicy/"+key;var candidate=object(policy,key);if(candidate.isEmpty()){error(out,"ONB_CRS_OPERATION_REQUIRED",p,"Conversion requires an explicit operation, accuracy statement, area and control points");return;}
    var op=candidate.get();for(String field:List.of("operationId","pipeline","projVersion","accuracyStatement"))requireText(op,field,p+"/"+field,out);
    if(!(op.get("sourceSrid") instanceof Number a)||a.doubleValue()!=source||!(op.get("targetSrid") instanceof Number b)||b.doubleValue()!=target)error(out,"ONB_CRS_OPERATION_MISMATCH",p,"Operation must bind the declared source and destination CRS");
    Object accuracy=op.get("accuracyMeters");if(accuracy!=null&&(!(accuracy instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()<0))error(out,"ONB_CRS_ACCURACY_INVALID",p+"/accuracyMeters","Accuracy is a non-negative number in metres or null for explicitly unknown accuracy");
    if(!(op.get("sourceBounds") instanceof List<?> bounds)||bounds.size()!=4||bounds.stream().anyMatch(v->!(v instanceof Number n)||!Double.isFinite(n.doubleValue())))error(out,"ONB_CRS_AREA_REQUIRED",p+"/sourceBounds","Four finite XY source bounds are required");
    if(!(op.get("controlPoints") instanceof List<?> points)||points.size()<2||points.size()>20)error(out,"ONB_CRS_CONTROLS_REQUIRED",p+"/controlPoints","Two to twenty independent control points required; tolerance uses target CRS units");
    if(op.get("sourceBounds") instanceof List<?> bounds&&bounds.size()==4&&bounds.stream().allMatch(v->v instanceof Number n&&Double.isFinite(n.doubleValue()))){if(((Number)bounds.get(0)).doubleValue()>=((Number)bounds.get(2)).doubleValue()||((Number)bounds.get(1)).doubleValue()>=((Number)bounds.get(3)).doubleValue())error(out,"ONB_CRS_AREA_INVALID",p+"/sourceBounds","Bounds must be ordered xmin,ymin,xmax,ymax in normalized XY source units");}
    if(op.get("controlPoints") instanceof List<?> points)for(Object value:points){if(!(value instanceof Map<?,?> point)||List.of("sourceX","sourceY","targetX","targetY","tolerance").stream().anyMatch(k->!(point.get(k) instanceof Number n)||!Double.isFinite(n.doubleValue()))||((Number)point.get("tolerance")).doubleValue()<=0)error(out,"ONB_CRS_CONTROLS_INVALID",p+"/controlPoints","Each control requires finite coordinates and a positive target-unit tolerance");}
    if(!(op.get("pipeline") instanceof String pipeline)||pipeline.length()>4096||!pipeline.startsWith("+proj=pipeline ")||pipeline.contains("@")||pipeline.contains("+init=")||pipeline.contains("null")||pipeline.contains("http:" )||pipeline.contains("https:"))error(out,"ONB_CRS_PIPELINE_INVALID",p+"/pipeline","Explicit bounded PROJ pipeline required; optional grids and fallback are forbidden");
    var grids=object(op,"requiredGrids");if(grids.isEmpty())error(out,"ONB_CRS_GRID_INVENTORY_REQUIRED",p+"/requiredGrids","Explicit grid filename to SHA256 map required, including an empty map when none is needed");
    else if(!grids.get().isEmpty()){
      if(!(op.get("resourceVersion") instanceof String version)||!version.matches("sha256:[a-f0-9]{64}"))error(out,"ONB_CRS_RESOURCE_VERSION_REQUIRED",p+"/resourceVersion","Pin the SHA256 of the licensed deployment grid manifest");
      for(var entry:grids.get().entrySet())if(!entry.getKey().matches("[A-Za-z0-9_-]+\\.(tif|gsb|gtx)")||!(entry.getValue() instanceof String hash)||!hash.matches("[a-f0-9]{64}"))error(out,"ONB_CRS_GRID_INVENTORY_INVALID",p+"/requiredGrids","Each grid needs a safe filename and a SHA256 checksum");
    }
    out.add(new Finding("INFO","ONB_CRS_OPERATION_ACCURACY",p,"Operation "+op.get("operationId")+": "+op.get("accuracyStatement")+"; accuracy metres="+op.get("accuracyMeters")+"; PROJ="+op.get("projVersion")+"; resources="+op.get("resourceVersion")));
  }

  private static void validateScheduledPolicy(Map<String,Object> sync,List<Finding> out){
    var policy=object(sync,"operationalPolicy");if(policy.isEmpty()){error(out,"ONB_OPERATIONAL_POLICY_REQUIRED","/syncProfile/operationalPolicy","Scheduled sources require an explicit bounded operational policy");return;}
    var p=policy.orElseThrow();try{java.time.ZoneId.of(String.valueOf(p.get("timeZone")));}catch(Exception e){error(out,"ONB_TIMEZONE_INVALID","/syncProfile/operationalPolicy/timeZone","Explicit IANA timezone required");}
    for(String key:List.of("misfireToleranceSeconds","sourceTimeoutSeconds","maxRetryAttempts","maxRetryElapsedSeconds","incidentDedupWindowSeconds","operationalRetentionDays")){
      int min=key.equals("maxRetryAttempts")?0:key.equals("operationalRetentionDays")?30:1;int max=key.equals("sourceTimeoutSeconds")?300:key.equals("maxRetryAttempts")?100:key.equals("operationalRetentionDays")?3650:86400;
      Object value=p.get(key);if(!(value instanceof Number n)||n.doubleValue()!=n.longValue()||n.longValue()<min||n.longValue()>max)error(out,"ONB_OPERATIONAL_POLICY_INVALID","/syncProfile/operationalPolicy/"+key,"Explicit bounded integer required");
    }
    requireEnum(p,"operationalVisibilityClass",Set.of("PUBLIC_OPERATIONAL","TENANT_OPERATIONAL","RESTRICTED_OPERATIONAL","SECURITY_SENSITIVE"),"/syncProfile/operationalPolicy/operationalVisibilityClass",out);
    Object backoff=sync.get("retryBackoffSeconds");if(!(backoff instanceof Number n)||n.doubleValue()!=n.longValue()||n.longValue()<1||n.longValue()>86400)error(out,"ONB_RETRY_BACKOFF_REQUIRED","/syncProfile/retryBackoffSeconds","Explicit bounded retry backoff required");
  }
  private static Optional<Map<String,Object>> object(Map<String,Object> parent,String key){Object value=parent.get(key);if(value instanceof Map<?,?> raw){Map<String,Object> copy=new LinkedHashMap<>();raw.forEach((k,v)->copy.put(String.valueOf(k),v));return Optional.of(copy);}return Optional.empty();}
  private static void requireObject(Map<String,Object> parent,String key,List<Finding> out){requireObject(parent,key,"/"+key,out);}
  private static void requireObject(Map<String,Object> parent,String key,String path,List<Finding> out){if(object(parent,key).isEmpty())error(out,"ONB_REQUIRED_OBJECT_MISSING",path,"Required object is missing");}
  private static void requireText(Map<String,Object> parent,String key,String path,List<Finding> out){Object value=parent.get(key);if(!(value instanceof String s)||s.isBlank())error(out,"ONB_REQUIRED_VALUE_MISSING",path,"Required non-blank value is missing");}
  private static void requireEnum(Map<String,Object> parent,String key,Set<String> allowed,String path,List<Finding> out){Object value=parent.get(key);if(!(value instanceof String s)||!allowed.contains(s))error(out,"ONB_VALUE_NOT_ALLOWED",path,"Value must be one of "+allowed);}
  private static void requireNonEmptyList(Map<String,Object> parent,String key,String path,List<Finding> out){Object value=parent.get(key);if(!(value instanceof List<?> list)||list.isEmpty())error(out,"ONB_REQUIRED_ARRAY_EMPTY",path,"Required array must contain at least one item");}
  private static void error(List<Finding> out,String code,String path,String message){out.add(new Finding("ERROR",code,path,message));}
  private static void validateDelta(Map<String,Object> delta,List<Finding> out){requireEnum(delta,"mode",Set.of("DELTA_PATCH"),"/deltaPatchContract/mode",out);requireText(delta,"baseReference","/deltaPatchContract/baseReference",out);Object operations=delta.get("operations");if(!(operations instanceof List<?> ops)||ops.isEmpty()||ops.stream().anyMatch(v->!Set.of("SET","REMOVE").contains(String.valueOf(v))))error(out,"ONB_DELTA_OPERATIONS_INVALID","/deltaPatchContract/operations","Operations must be a non-empty subset of SET and REMOVE");Object bindings=delta.get("propertyBindings");if(!(bindings instanceof List<?> items)||items.isEmpty())error(out,"ONB_DELTA_BINDINGS_REQUIRED","/deltaPatchContract/propertyBindings","At least one direct property binding is required");else for(int i=0;i<items.size();i++){if(!(items.get(i) instanceof Map<?,?> raw)){error(out,"ONB_DELTA_BINDING_INVALID","/deltaPatchContract/propertyBindings/"+i,"Binding must be an object");continue;}Map<String,Object> item=new LinkedHashMap<>();raw.forEach((k,v)->item.put(String.valueOf(k),v));for(String key:List.of("sourcePath","targetProperty","changeSection"))requireText(item,key,"/deltaPatchContract/propertyBindings/"+i+"/"+key,out);}}
}
