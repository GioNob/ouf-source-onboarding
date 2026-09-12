package it.comune.trieste.ouf.onboarding.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DiscoveryService {
  private final JdbcClient db; private final ObjectMapper json; private final CanonicalHash hashes;
  public DiscoveryService(JdbcClient db,ObjectMapper json,CanonicalHash hashes){this.db=db;this.json=json;this.hashes=hashes;}

  @Transactional public Map<String,Object> configureBinding(String sourceId,String bindingId,String protocol,String routeRef,String credentialRef,Map<String,Object> configuration,long expected){
    if(credentialRef!=null&&!credentialRef.matches("^(secret|workload)://.+"))throw failure("ONB_SECRET_REF_INVALID","credentialRef must use secret:// or workload://");
    int updated=db.sql("update ouf_onboarding.source_binding set protocol=:p,route_ref=:r,credential_ref=:c,configuration=cast(:x as jsonb),lock_version=lock_version+1,updated_at=transaction_timestamp() where source_id=:s and binding_id=:b and lock_version=:l")
      .param("p",protocol).param("r",routeRef).param("c",credentialRef).param("x",write(configuration)).param("s",sourceId).param("b",bindingId).param("l",expected).update();
    if(updated==0&&expected==0) db.sql("insert into ouf_onboarding.source_binding(source_id,binding_id,protocol,route_ref,credential_ref,configuration) values(:s,:b,:p,:r,:c,cast(:x as jsonb)) on conflict do nothing")
      .param("s",sourceId).param("b",bindingId).param("p",protocol).param("r",routeRef).param("c",credentialRef).param("x",write(configuration)).update();
    return binding(sourceId,bindingId);
  }
  public Map<String,Object> binding(String sourceId,String bindingId){return jsonRow(db.sql("select source_id,binding_id,protocol,route_ref,credential_ref,configuration::text configuration,lock_version,created_at,updated_at from ouf_onboarding.source_binding where source_id=:s and binding_id=:b").param("s",sourceId).param("b",bindingId).query().listOfRows().stream().findFirst().orElseThrow(()->new DomainFailure(HttpStatus.NOT_FOUND,"ONB_BINDING_NOT_FOUND","Binding not found")),"configuration");}

  @Transactional public Map<String,Object> start(String sourceId,String bindingId,String idempotencyKey,Map<String,Object> options){
    Map<String,Object> safeOptions=options==null?Map.of():options;String mode=String.valueOf(safeOptions.getOrDefault("mode","SCHEMA_ONLY"));if(!Set.of("SCHEMA_ONLY","GOVERNED_PROFILE").contains(mode))throw failure("ONB_DISCOVERY_MODE_INVALID","Unsupported discovery mode");Map<String,Object> binding=binding(sourceId,bindingId);String requestHash=hashes.of(Map.of("binding",binding,"options",safeOptions));UUID run=UUID.randomUUID();
    db.sql("insert into ouf_onboarding.discovery_run(discovery_run_id,source_id,binding_id,idempotency_key,request_hash,status,discovery_mode) values(:i,:s,:b,:k,:h,'READY',:m) on conflict(source_id,idempotency_key) do nothing")
      .param("i",run).param("s",sourceId).param("b",bindingId).param("k",idempotencyKey).param("h",requestHash).param("m",mode).update();
    Map<String,Object> found=db.sql("select discovery_run_id,source_id,binding_id,idempotency_key,request_hash,status,discovery_mode,attempts,max_attempts,claimed_by,lease_until,retry_at,snapshot::text snapshot,error_code,created_at,updated_at,completed_at from ouf_onboarding.discovery_run where source_id=:s and idempotency_key=:k for update").param("s",sourceId).param("k",idempotencyKey).query().singleRow();
    if(!requestHash.equals(found.get("request_hash")))throw new DomainFailure(HttpStatus.CONFLICT,"ONB_DISCOVERY_IDEMPOTENCY_CONFLICT","Idempotency key already binds a different discovery request");return jsonRow(found,"snapshot");
  }

  @Transactional public Optional<Map<String,Object>> claim(String worker,Duration lease){
    List<Map<String,Object>> rows=db.sql("with candidate as (select discovery_run_id from ouf_onboarding.discovery_run where (status='READY' and (retry_at is null or retry_at<=transaction_timestamp())) or (status='RUNNING' and lease_until<transaction_timestamp()) order by created_at for update skip locked limit 1) update ouf_onboarding.discovery_run d set status='RUNNING',attempts=attempts+1,claimed_by=:w,lease_until=transaction_timestamp()+cast(:lease as interval),retry_at=null,updated_at=transaction_timestamp() from candidate c where d.discovery_run_id=c.discovery_run_id and d.attempts<d.max_attempts returning d.discovery_run_id,d.source_id,d.binding_id,d.status,d.attempts,d.claimed_by,d.lease_until")
      .param("w",worker).param("lease",lease.toSeconds()+" seconds").query().listOfRows();return rows.stream().findFirst();
  }

  @Transactional public Map<String,Object> complete(UUID runId,String worker,Map<String,Object> snapshot){
    Map<String,Object> run=db.sql("select discovery_run_id,source_id,status,claimed_by,discovery_mode from ouf_onboarding.discovery_run where discovery_run_id=:i for update").param("i",runId).query().singleRow();
    if(!"RUNNING".equals(run.get("status"))||!worker.equals(run.get("claimed_by")))throw new DomainFailure(HttpStatus.CONFLICT,"ONB_DISCOVERY_CLAIM_LOST","Discovery claim is not owned by this worker");String source=String.valueOf(run.get("source_id"));
    if("SCHEMA_ONLY".equals(run.get("discovery_mode"))&&containsInstanceMaterial(snapshot))throw failure("ONB_SCHEMA_ONLY_INSTANCE_DATA_FORBIDDEN","SCHEMA_ONLY discovery cannot contain samples or instances");
    db.sql("delete from ouf_onboarding.discovered_type where source_id=:s").param("s",source).update();
    Object rawTypes=snapshot.get("types");if(!(rawTypes instanceof List<?> types)||types.isEmpty())throw failure("ONB_DISCOVERY_SNAPSHOT_INVALID","Snapshot must contain at least one type");
    for(Object raw:types){if(!(raw instanceof Map<?,?> type))throw failure("ONB_DISCOVERY_SNAPSHOT_INVALID","Each type must be an object");String code=text(type,"typeCode");db.sql("insert into ouf_onboarding.discovered_type(source_id,type_code,discovery_run_id,label,schema_ref) values(:s,:t,:r,:l,:x)").param("s",source).param("t",code).param("r",runId).param("l",nullable(type.get("label"))).param("x",nullable(type.get("schemaRef"))).update();
      for(Object f:list(type.get("fields"))){Map<?,?> field=(Map<?,?>)f;db.sql("insert into ouf_onboarding.discovered_field(source_id,type_code,field_name,data_type,nullable,discovery_run_id) values(:s,:t,:f,:d,:n,:r)").param("s",source).param("t",code).param("f",text(field,"name")).param("d",text(field,"dataType")).param("n",Boolean.TRUE.equals(field.get("nullable"))).param("r",runId).update();}
      for(Object st:list(type.get("states"))){Map<?,?> state=(Map<?,?>)st;db.sql("insert into ouf_onboarding.discovered_state(source_id,type_code,state_code,label,discovery_run_id) values(:s,:t,:c,:l,:r)").param("s",source).param("t",code).param("c",text(state,"code")).param("l",nullable(state.get("label"))).param("r",runId).update();}}
    db.sql("update ouf_onboarding.discovery_run set status='SUCCEEDED',snapshot=cast(:x as jsonb),claimed_by=null,lease_until=null,completed_at=transaction_timestamp(),updated_at=transaction_timestamp() where discovery_run_id=:i and status='RUNNING' and claimed_by=:w").param("x",write(snapshot)).param("i",runId).param("w",worker).update();return run(runId);
  }
  public Map<String,Object> run(UUID id){return jsonRow(db.sql("select discovery_run_id,source_id,binding_id,idempotency_key,request_hash,status,discovery_mode,attempts,max_attempts,claimed_by,lease_until,retry_at,snapshot::text snapshot,error_code,created_at,updated_at,completed_at from ouf_onboarding.discovery_run where discovery_run_id=:i").param("i",id).query().listOfRows().stream().findFirst().orElseThrow(()->new DomainFailure(HttpStatus.NOT_FOUND,"ONB_DISCOVERY_NOT_FOUND","Discovery run not found")),"snapshot");}
  public List<Map<String,Object>> types(String sourceId){return db.sql("select source_id,type_code,discovery_run_id,label,schema_ref from ouf_onboarding.discovered_type where source_id=:s order by type_code").param("s",sourceId).query().listOfRows();}
  public List<Map<String,Object>> fields(String sourceId,String typeCode){return db.sql("select field_name,data_type,nullable,discovery_run_id from ouf_onboarding.discovered_field where source_id=:s and type_code=:t order by field_name").param("s",sourceId).param("t",typeCode).query().listOfRows();}
  public List<Map<String,Object>> states(String sourceId,String typeCode){return db.sql("select state_code,label,discovery_run_id from ouf_onboarding.discovered_state where source_id=:s and type_code=:t order by state_code").param("s",sourceId).param("t",typeCode).query().listOfRows();}
  private String write(Object value){try{return json.writeValueAsString(value==null?Map.of():value);}catch(Exception e){throw failure("ONB_JSON_INVALID",e.getMessage());}}
  private Map<String,Object> jsonRow(Map<String,Object> row,String field){try{Map<String,Object> out=new LinkedHashMap<>(row);if(out.get(field) instanceof String s)out.put(field,json.readValue(s,Object.class));return out;}catch(Exception e){throw new IllegalStateException(e);}}
  private static String text(Map<?,?> map,String key){Object v=map.get(key);if(!(v instanceof String s)||s.isBlank())throw failure("ONB_DISCOVERY_SNAPSHOT_INVALID",key+" is required");return s;}
  private static String nullable(Object value){return value==null?null:String.valueOf(value);}
  private static List<?> list(Object value){return value instanceof List<?> l?l:List.of();}
  private static boolean containsInstanceMaterial(Object value){if(value instanceof Map<?,?> map){for(var entry:map.entrySet()){String key=String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);if(Set.of("sample","samples","records","instances","rows","features").contains(key))return true;if(containsInstanceMaterial(entry.getValue()))return true;}}else if(value instanceof Collection<?> values)for(Object item:values)if(containsInstanceMaterial(item))return true;return false;}
  private static DomainFailure failure(String code,String message){return new DomainFailure(HttpStatus.UNPROCESSABLE_ENTITY,code,message);}
}
