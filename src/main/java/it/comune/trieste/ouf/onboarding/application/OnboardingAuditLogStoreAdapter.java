package it.comune.trieste.ouf.onboarding.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Types;
import java.util.*;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class OnboardingAuditLogStoreAdapter implements ProtectedLogStoreAdapter {
  private final JdbcClient db;private final ObjectMapper json;
  public OnboardingAuditLogStoreAdapter(JdbcClient db,ObjectMapper json){this.db=db;this.json=json;}
  public List<Map<String,Object>> search(Query q){
    if(q.service()!=null&&!"ouf-source-onboarding".equals(q.service()))return List.of();if(q.severity()!=null&&!"INFO".equals(q.severity()))return List.of();
    return db.sql("select audit_event_id,source_id,onboarding_version_id,correlation_id,actor_subject,actor_type,event_type,payload_summary::text payload_summary,created_at from ouf_onboarding.audit_event where created_at>=:f and created_at<:t and (:c is null or correlation_id=:c) and (:e is null or event_type=:e) order by created_at desc,audit_event_id limit :l")
      .param("f",q.from()).param("t",q.to()).param("c",nullable(q.correlationId())).param("e",nullable(q.eventType())).param("l",q.limit()).query().listOfRows().stream().map(this::normalize).toList();
  }
  public Optional<Map<String,Object>> read(String logRef){UUID id=parse(logRef);if(id==null)return Optional.empty();return db.sql("select audit_event_id,source_id,onboarding_version_id,correlation_id,actor_subject,actor_type,event_type,payload_summary::text payload_summary,created_at from ouf_onboarding.audit_event where audit_event_id=:i").param("i",id).query().listOfRows().stream().findFirst().map(this::normalize);}
  private Map<String,Object> normalize(Map<String,Object> row){try{Map<String,Object> out=new LinkedHashMap<>();out.put("logRef","audit:"+row.get("audit_event_id"));out.put("timestamp",row.get("created_at"));out.put("service","ouf-source-onboarding");out.put("severity","INFO");out.put("correlationId",row.get("correlation_id"));out.put("eventType",row.get("event_type"));out.put("sourceId",row.get("source_id"));out.put("onboardingVersionId",row.get("onboarding_version_id"));out.put("actorSubject",row.get("actor_subject"));out.put("actorType",row.get("actor_type"));out.put("attributes",json.readValue(String.valueOf(row.get("payload_summary")),Object.class));return out;}catch(Exception e){throw new IllegalStateException("audit log decode failed",e);}}
  private static UUID parse(String ref){try{return ref!=null&&ref.startsWith("audit:")?UUID.fromString(ref.substring(6)):null;}catch(IllegalArgumentException e){return null;}}
  private static SqlParameterValue nullable(String value){return new SqlParameterValue(Types.VARCHAR,value);}
}
