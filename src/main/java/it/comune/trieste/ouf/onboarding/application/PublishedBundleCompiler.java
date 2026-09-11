package it.comune.trieste.ouf.onboarding.application;

import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class PublishedBundleCompiler {
  private final CanonicalHashAdapter hashes;
  public PublishedBundleCompiler(it.comune.trieste.ouf.onboarding.domain.CanonicalHash hashes){this.hashes=new CanonicalHashAdapter(hashes);}

  @SuppressWarnings("unchecked")
  public Map<String,Object> compile(String sourceId,UUID versionId,Object versionNumber,String configurationHash,Map<String,Object> configuration){
    Map<String,Object> template=object(configuration,"bundle");Map<String,Object> source=object(template,"source");
    require(sourceId.equals(source.get("sourceId")),"bundle sourceId does not match the aggregate");
    Map<String,Object> extraction=object(configuration,"extractionProfile");Map<String,Object> semantic=object(configuration,"semanticMapping");
    Map<String,Object> out=new LinkedHashMap<>();
    out.put("bundleId",text(template,"bundleId"));out.put("bundleVersion",String.valueOf(template.getOrDefault("bundleVersion",versionNumber)));
    out.put("environment",text(template,"environment"));out.put("source",Map.copyOf(source));
    copy(template,out,"sourceDefinitionRef","bindingRef","sourceRuntimeProfileRef");
    out.put("extractionProfileRef",Objects.toString(template.get("extractionProfileRef"),"onboarding://extraction-profiles/"+text(extraction,"profileId")+"/versions/"+extraction.get("version")));
    out.put("extractionProfile",extraction);out.put("syncProfile",object(configuration,"syncProfile"));
    copy(configuration,out,"sourceObjectIdentityPolicy","temporalMapping","changeRepresentationProfile","deltaPatchContract","relationshipMappings","authorityPolicies","dataAccessPolicies");
    List<String> semanticRefs=listOfStrings(semantic.get("semanticRefs"));out.put("semanticRefs",semanticRefs);out.put("semanticMapping",semantic);
    Map<String,Object> sourceType=object(semantic,"sourceType");String typeCode=text(sourceType,"typeCode");
    String schemaRef=Objects.toString(template.get("schemaRef"),"onboarding://sources/"+sourceId+"/schemas/"+typeCode);
    out.put("objectTypes",List.of(Map.of("typeCode",typeCode,"schemaRef",schemaRef,"semanticMappingRef","onboarding://semantic-mappings/"+text(semantic,"mappingId"))));
    out.put("createdFromOnboardingVersion",versionId.toString());out.put("effectiveFrom",Instant.now().toString());out.put("activatedAt",Instant.now().toString());
    out.put("configurationHash",configurationHash);out.put("status","ACTIVE");
    out.put("checksum",hashes.of(out));validate(out);return Collections.unmodifiableMap(out);
  }

  public void validate(Map<String,Object> bundle){
    for(String key:List.of("bundleId","bundleVersion","environment","createdFromOnboardingVersion","effectiveFrom","checksum","status"))text(bundle,key);
    require("ACTIVE".equals(bundle.get("status")),"published bundle status must be ACTIVE");Map<String,Object> source=object(bundle,"source");
    for(String key:List.of("sourceId","sourceKind","acquisitionMode"))text(source,key);
    require(Set.of("EXTERNAL_API","OGC_SERVICE","INTERNAL_MANAGED").contains(source.get("sourceKind")),"unsupported frozen sourceKind");
    require(Set.of("PULL","MANAGED").contains(source.get("acquisitionMode")),"unsupported acquisitionMode");
    if("PULL".equals(source.get("acquisitionMode")))require(bundle.get("bindingRef") instanceof String&&!String.valueOf(bundle.get("bindingRef")).isBlank(),"PULL bundle requires bindingRef");
    require(bundle.get("extractionProfileRef") instanceof String,"bundle requires extractionProfileRef");
    Object types=bundle.get("objectTypes");require(types instanceof List<?> l&&!l.isEmpty(),"bundle requires objectTypes");
  }

  private static void copy(Map<String,Object> from,Map<String,Object> to,String...keys){for(String key:keys)if(from.containsKey(key))to.put(key,from.get(key));}
  @SuppressWarnings("unchecked") private static Map<String,Object> object(Map<String,Object> parent,String key){Object value=parent.get(key);if(value instanceof Map<?,?> raw){Map<String,Object> out=new LinkedHashMap<>();raw.forEach((k,v)->out.put(String.valueOf(k),v));return out;}throw invalid("required object missing: "+key);}
  private static String text(Map<String,Object> map,String key){Object value=map.get(key);if(value instanceof String s&&!s.isBlank())return s;throw invalid("required text missing: "+key);}
  private static List<String> listOfStrings(Object value){if(!(value instanceof List<?> list)||list.isEmpty())throw invalid("semanticRefs must not be empty");return list.stream().map(String::valueOf).toList();}
  private static void require(boolean condition,String message){if(!condition)throw invalid(message);}
  private static DomainFailure invalid(String message){return new DomainFailure(HttpStatus.UNPROCESSABLE_ENTITY,"ONB_BUNDLE_CONTRACT_INVALID",message);}
  private record CanonicalHashAdapter(it.comune.trieste.ouf.onboarding.domain.CanonicalHash delegate){String of(Object value){return delegate.of(value);}}
}
