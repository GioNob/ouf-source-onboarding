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
      if(sync.get("timezone") instanceof String value)try{java.time.ZoneId.of(value);}catch(Exception e){error(out,"ONB_TIMEZONE_INVALID","/syncProfile/timezone","timezone must be an IANA zone identifier");}
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
      object(mapping,"sourceType").ifPresent(type->{if(type.get("sourceId") instanceof String s&&!sourceId.equals(s))error(out,"ONB_SOURCE_BINDING_MISMATCH","/semanticMapping/sourceType/sourceId","sourceId does not match the onboarding source");requireText(type,"typeCode","/semanticMapping/sourceType/typeCode",out);});
    });
    object(configuration,"bundle").ifPresent(bundle->{
      for(String key:List.of("bundleId","bundleVersion","environment","createdFromOnboardingVersion","effectiveFrom","checksum","status"))requireText(bundle,key,"/bundle/"+key,out);
      requireEnum(bundle,"status",Set.of("APPROVED","ACTIVE","SUPERSEDED","REVOKED"),"/bundle/status",out); requireObject(bundle,"source","/bundle/source",out);
      object(bundle,"source").ifPresent(source->{requireText(source,"sourceId","/bundle/source/sourceId",out);requireText(source,"sourceKind","/bundle/source/sourceKind",out);requireText(source,"acquisitionMode","/bundle/source/acquisitionMode",out);if(source.get("sourceId") instanceof String s&&!sourceId.equals(s))error(out,"ONB_SOURCE_BINDING_MISMATCH","/bundle/source/sourceId","sourceId does not match the onboarding source");});
      object(bundle,"changeRepresentationProfile").ifPresent(change->{if("DELTA_PATCH".equals(change.get("mode"))&&object(bundle,"deltaPatchContract").isEmpty())error(out,"ONB_DELTA_CONTRACT_REQUIRED","/bundle/deltaPatchContract","DELTA_PATCH requires a compiled deltaPatchContract");});
    });
    if(out.isEmpty()) out.add(new Finding("INFO","ONB_CONFIGURATION_VALID","/","Configuration satisfies the executable onboarding validation profile"));
    return new Result(List.copyOf(out));
  }

  private static Optional<Map<String,Object>> object(Map<String,Object> parent,String key){Object value=parent.get(key);if(value instanceof Map<?,?> raw){Map<String,Object> copy=new LinkedHashMap<>();raw.forEach((k,v)->copy.put(String.valueOf(k),v));return Optional.of(copy);}return Optional.empty();}
  private static void requireObject(Map<String,Object> parent,String key,List<Finding> out){requireObject(parent,key,"/"+key,out);}
  private static void requireObject(Map<String,Object> parent,String key,String path,List<Finding> out){if(object(parent,key).isEmpty())error(out,"ONB_REQUIRED_OBJECT_MISSING",path,"Required object is missing");}
  private static void requireText(Map<String,Object> parent,String key,String path,List<Finding> out){Object value=parent.get(key);if(!(value instanceof String s)||s.isBlank())error(out,"ONB_REQUIRED_VALUE_MISSING",path,"Required non-blank value is missing");}
  private static void requireEnum(Map<String,Object> parent,String key,Set<String> allowed,String path,List<Finding> out){Object value=parent.get(key);if(!(value instanceof String s)||!allowed.contains(s))error(out,"ONB_VALUE_NOT_ALLOWED",path,"Value must be one of "+allowed);}
  private static void requireNonEmptyList(Map<String,Object> parent,String key,String path,List<Finding> out){Object value=parent.get(key);if(!(value instanceof List<?> list)||list.isEmpty())error(out,"ONB_REQUIRED_ARRAY_EMPTY",path,"Required array must contain at least one item");}
  private static void error(List<Finding> out,String code,String path,String message){out.add(new Finding("ERROR",code,path,message));}
}
