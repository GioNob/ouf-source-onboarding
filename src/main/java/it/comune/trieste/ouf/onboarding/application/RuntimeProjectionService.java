package it.comune.trieste.ouf.onboarding.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class RuntimeProjectionService {
  private final JdbcClient db;private final ObjectMapper json;private final CanonicalHash hashes;
  public RuntimeProjectionService(JdbcClient db,ObjectMapper json,CanonicalHash hashes){this.db=db;this.json=json;this.hashes=hashes;}

  @SuppressWarnings("unchecked") public void publish(UUID publicationId,Map<String,Object> bundle){
    String sourceId=String.valueOf(((Map<String,Object>)bundle.get("source")).get("sourceId"));String environment=String.valueOf(bundle.get("environment"));String version=String.valueOf(bundle.get("bundleVersion"));Map<String,Object> extraction=(Map<String,Object>)bundle.get("extractionProfile");Map<String,Object> runtime=extraction.get("runtime") instanceof Map<?,?> r?copy(r):Map.of();String mode=String.valueOf(runtime.getOrDefault("mode",((Map<String,Object>)bundle.get("source")).get("acquisitionMode")));String endpoint=String.valueOf(runtime.getOrDefault("endpointRef",runtime.getOrDefault("stagingRef",bundle.getOrDefault("bindingRef","onboarding://sources/"+sourceId))));String schemaRef=String.valueOf(((Map<String,Object>)((List<?>)bundle.get("objectTypes")).get(0)).get("schemaRef"));
    Map<String,Object> sourceRuntime=new LinkedHashMap<>();sourceRuntime.put("sourceId",sourceId);sourceRuntime.put("profileVersion",version);sourceRuntime.put("environment",environment);sourceRuntime.put("protocol","MANAGED".equals(mode)?"FILE":"HTTP");sourceRuntime.put("endpointRef",endpoint);if(runtime.get("credentialRef")!=null)sourceRuntime.put("credentialRef",runtime.get("credentialRef"));sourceRuntime.put("sourceSchemaRef",schemaRef);sourceRuntime.put("status","ACTIVE");
    Map<String,Object> schema=Map.of("sourceId",sourceId,"schemaName",schemaRef,"schemaVersion",version,"protocol","MANAGED".equals(mode)?"FILE":"HTTP","schemaFingerprint",bundle.get("configurationHash"));
    Map<String,Object> route=Map.of("routeId","route-"+sourceId+"-"+version,"capabilityId","ouf.source.acquire","sourceId",sourceId,"direction","southbound","extractionProfileRef",bundle.get("extractionProfileRef"),"backendBinding",Map.of("service","ouf-ingestion-runtime","operation","acquire"),"status","ACTIVE");
    persist(publicationId,sourceId,"SOURCE_RUNTIME_PROFILE",sourceRuntime);persist(publicationId,sourceId,"SOURCE_SCHEMA_BINDING",schema);persist(publicationId,sourceId,"ROUTE_BINDING",route);
  }
  public List<Map<String,Object>> projections(String sourceId,UUID publicationId){return db.sql("select projection_id,publication_id,source_id,projection_type,document::text document,checksum,created_at from ouf_onboarding.runtime_projection where source_id=:s and publication_id=:p order by projection_type").param("s",sourceId).param("p",publicationId).query().listOfRows().stream().map(this::decode).toList();}
  private void persist(UUID publicationId,String sourceId,String type,Map<String,Object> document){db.sql("insert into ouf_onboarding.runtime_projection(projection_id,publication_id,source_id,projection_type,document,checksum) values(:i,:p,:s,:t,cast(:d as jsonb),:h)").param("i",UUID.randomUUID()).param("p",publicationId).param("s",sourceId).param("t",type).param("d",write(document)).param("h",hashes.of(document)).update();}
  private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
  private Map<String,Object> decode(Map<String,Object> row){try{Map<String,Object> out=new LinkedHashMap<>(row);out.put("document",json.readValue(String.valueOf(row.get("document")),Object.class));return out;}catch(Exception e){throw new IllegalStateException(e);}}
  private static Map<String,Object> copy(Map<?,?> raw){Map<String,Object> out=new LinkedHashMap<>();raw.forEach((k,v)->out.put(String.valueOf(k),v));return out;}
}
