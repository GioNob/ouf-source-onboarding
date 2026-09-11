package it.comune.trieste.ouf.onboarding.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SchemaSurveillanceService {
  private final JdbcClient db;private final ObjectMapper json;
  public SchemaSurveillanceService(JdbcClient db,ObjectMapper json){this.db=db;this.json=json;}
  @Transactional public Map<String,Object> report(String sourceId,String schemaRef,String previous,String observed,String classification,Map<String,Object> detail){if(!Set.of("UNCHANGED","ADDITIVE","BREAKING","UNKNOWN").contains(classification))throw invalid("Unsupported drift classification");UUID id=UUID.randomUUID();db.sql("insert into ouf_onboarding.schema_surveillance_issue(issue_id,source_id,observed_schema_ref,previous_fingerprint,observed_fingerprint,classification,detail) values(:i,:s,:r,:p,:o,:c,cast(:d as jsonb))").param("i",id).param("s",sourceId).param("r",schemaRef).param("p",previous).param("o",observed).param("c",classification).param("d",write(detail)).update();return issue(id);}
  public List<Map<String,Object>> issues(String sourceId){return db.sql("select issue_id,source_id,observed_schema_ref,previous_fingerprint,observed_fingerprint,classification,detail::text detail,state,created_at,resolved_at from ouf_onboarding.schema_surveillance_issue where source_id=:s order by created_at desc").param("s",sourceId).query().listOfRows().stream().map(this::decode).toList();}
  @Transactional public Map<String,Object> resolve(String sourceId,UUID issueId){int changed=db.sql("update ouf_onboarding.schema_surveillance_issue set state='RESOLVED',resolved_at=transaction_timestamp() where source_id=:s and issue_id=:i and state<>'RESOLVED'").param("s",sourceId).param("i",issueId).update();if(changed!=1)throw new DomainFailure(HttpStatus.CONFLICT,"ONB_SCHEMA_ISSUE_NOT_OPEN","Schema issue is absent or already resolved");return issue(issueId);}
  public void assertActivationAllowed(String sourceId){boolean blocked=db.sql("select exists(select 1 from ouf_onboarding.schema_surveillance_issue where source_id=:s and state<>'RESOLVED' and classification in ('BREAKING','UNKNOWN'))").param("s",sourceId).query(Boolean.class).single();if(blocked)throw new DomainFailure(HttpStatus.CONFLICT,"ONB_SCHEMA_DRIFT_BLOCKS_ACTIVATION","Unresolved breaking or unknown schema drift blocks activation");}
  private Map<String,Object> issue(UUID id){return decode(db.sql("select issue_id,source_id,observed_schema_ref,previous_fingerprint,observed_fingerprint,classification,detail::text detail,state,created_at,resolved_at from ouf_onboarding.schema_surveillance_issue where issue_id=:i").param("i",id).query().singleRow());}
  private String write(Object v){try{return json.writeValueAsString(v==null?Map.of():v);}catch(Exception e){throw invalid("Invalid detail JSON");}}
  private Map<String,Object> decode(Map<String,Object> row){try{Map<String,Object> out=new LinkedHashMap<>(row);out.put("detail",json.readValue(String.valueOf(row.get("detail")),Object.class));return out;}catch(Exception e){throw new IllegalStateException(e);}}
  private static DomainFailure invalid(String m){return new DomainFailure(HttpStatus.UNPROCESSABLE_ENTITY,"ONB_SCHEMA_SURVEILLANCE_INVALID",m);}
}
