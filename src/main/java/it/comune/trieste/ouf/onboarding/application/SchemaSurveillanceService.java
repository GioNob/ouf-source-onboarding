package it.comune.trieste.ouf.onboarding.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.domain.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SchemaSurveillanceService {
  private final JdbcClient db;
  private final ObjectMapper json;
  private final CanonicalHash hashes;
  public SchemaSurveillanceService(JdbcClient db,ObjectMapper json,CanonicalHash hashes) {
    this.db=db; this.json=json; this.hashes=hashes;
  }

  private static final String SELECT = """
      select issue_id,source_id,observed_schema_ref,previous_fingerprint,observed_fingerprint,
             classification,detail::text detail,state,created_at,resolved_at,incident_key,
             last_observed_at,observation_polls,resolution_reason
      from ouf_onboarding.schema_surveillance_issue
      """;

  // Activation takes this same row lock before checking issues and publishing.
  private void lockSource(String source) {
    if(db.sql("select source_id from ouf_onboarding.source where source_id=:s for update")
        .param("s",source).query(String.class).optional().isEmpty())
      throw new DomainFailure(HttpStatus.NOT_FOUND,"ONB_SOURCE_NOT_FOUND","Source not found");
  }

  @Transactional public Map<String,Object> observe(String tenant,String source,SchemaObservation event) {
    event.validate(hashes);
    lockSource(source);
    var existing=find(tenant,source,event.incidentKey());
    if ("OPEN".equals(event.state())) {
      if (existing.isPresent()) {
        UUID id=existing.get();
        boolean same=db.sql("""
            select expected_schema_ref=:expected and observed_schema=cast(:schema as jsonb)
              and differences=cast(:diff as jsonb) and frozen_checkpoint_ref=:checkpoint
            from ouf_onboarding.schema_surveillance_issue where issue_id=:id
            """).param("id",id).param("expected",event.expectedSchemaRef()).param("schema",write(event.observedSchema()))
            .param("diff",write(event.differences())).param("checkpoint",event.frozenCheckpointRef())
            .query(Boolean.class).optional().orElse(false);
        if (!same) throw conflict("Incident key already identifies different evidence");
        return issue(id);
      }
      UUID id=UUID.randomUUID();
      String classification=event.differences().stream().allMatch(d->"ADDED".equals(d.change()))?"ADDITIVE":"BREAKING";
      db.sql("""
          insert into ouf_onboarding.schema_surveillance_issue
            (tenant_id,source_id,incident_key,issue_id,observed_schema_ref,observed_fingerprint,classification,
             expected_schema_ref,observed_schema,differences,frozen_checkpoint_ref,observation_polls,created_at,last_observed_at)
          values (:t,:s,:k,:id,:ref,:hash,:class,:expected,cast(:schema as jsonb),cast(:diff as jsonb),
                  :checkpoint,:polls,:at,:at)
          """).param("t",tenant).param("s",source).param("k",event.incidentKey()).param("id",id)
          .param("ref","onboarding:schema-observation:"+id).param("hash",event.observedSchema().get("fieldsSha256"))
          .param("class",classification).param("expected",event.expectedSchemaRef()).param("schema",write(event.observedSchema()))
          .param("diff",write(event.differences())).param("checkpoint",event.frozenCheckpointRef())
          .param("polls",event.observationPolls()).param("at",Timestamp.from(event.observedAt())).update();
      return issue(id);
    }
    UUID id=existing.orElseThrow(()->conflict("OPEN must precede an update or resolution"));
    var current=issue(id);
    if(current.get("observation_polls")==null) throw conflict("Legacy report has no v1 opening evidence");
    if("RESOLVED".equals(current.get("state"))) return current;
    boolean resolved="RESOLVED".equals(event.state());
    String reason=Objects.requireNonNullElse(event.reason(),"SCHEMA_RETURNED");
    db.sql("""
        update ouf_onboarding.schema_surveillance_issue set
          observation_polls=greatest(observation_polls,:polls),last_observed_at=greatest(last_observed_at,:at),
          state=case when :close then 'RESOLVED' else state end,
          resolved_at=case when :close then transaction_timestamp() else resolved_at end,
          resolution_reason=case when :close then :reason else resolution_reason end
        where issue_id=:id and state='OPEN' and observed_schema is not null
        """).param("id",id).param("polls",event.observationPolls()).param("at",Timestamp.from(event.observedAt()))
        .param("close",resolved).param("reason",reason).update();
    return issue(id);
  }

  private Optional<UUID> find(String tenant,String source,String key) {
    return db.sql("select issue_id from ouf_onboarding.schema_surveillance_issue where tenant_id=:t and source_id=:s and incident_key=:k")
        .param("t",tenant).param("s",source).param("k",key).query(UUID.class).optional();
  }

  @Transactional public Map<String,Object> report(String source,String ref,String previous,String observed,String classification,Map<String,Object> detail) {
    return legacy(null,source,ref,previous,observed,classification,detail);
  }

  @Transactional public Map<String,Object> legacy(String tenant,String source,String ref,String previous,String observed,String classification,Map<String,Object> detail) {
    if (ref==null || ref.isBlank() || observed==null || observed.isBlank() || classification==null
        || !Set.of("UNCHANGED","ADDITIVE","BREAKING","UNKNOWN").contains(classification))
      throw invalid("Invalid historical schema report");
    if (detail==null) detail=Map.of();
    if (detail.containsKey("tenant") && !Objects.equals(tenant,detail.get("tenant"))
        || detail.containsKey("sourceId") && !source.equals(detail.get("sourceId"))) throw invalid("Report scope mismatch");
    String key=detail.get("idempotencyKey") instanceof String k?k:null;
    if (key!=null && (key.isBlank() || key.length()>512 || tenant==null)) throw invalid("Invalid incident key");
    lockSource(source);
    if(key!=null) {
      var existing=find(tenant,source,key);
      if(existing.isPresent()) {
        var row=issue(existing.get());
        if (!Objects.equals(row.get("observed_schema_ref"),ref) || !Objects.equals(row.get("previous_fingerprint"),previous)
            || !Objects.equals(row.get("observed_fingerprint"),observed) || !classification.equals(row.get("classification"))
            || !detail.equals(row.get("detail"))) throw conflict("Incident key already identifies different evidence");
        return row;
      }
    }
    UUID id=UUID.randomUUID();
    db.sql("""
        insert into ouf_onboarding.schema_surveillance_issue
          (tenant_id,source_id,incident_key,issue_id,observed_schema_ref,previous_fingerprint,observed_fingerprint,classification,detail)
        values(:t,:s,:k,:id,:ref,:previous,:observed,:class,cast(:detail as jsonb))
        """).param("t",tenant).param("s",source).param("k",key).param("id",id).param("ref",ref)
        .param("previous",previous).param("observed",observed).param("class",classification).param("detail",write(detail)).update();
    return issue(id);
  }

  public List<Map<String,Object>> issues(String tenant,String source) {
    return db.sql(SELECT+" where tenant_id=:t and source_id=:s order by created_at desc")
        .param("t",tenant).param("s",source).query().listOfRows().stream().map(this::decode).toList();
  }
  @Transactional public Map<String,Object> resolve(String source,UUID id) { return resolve(null,source,id); }
  @Transactional public Map<String,Object> resolve(String tenant,String source,UUID id) {
    int changed=db.sql("""
        update ouf_onboarding.schema_surveillance_issue set state='RESOLVED',resolved_at=transaction_timestamp()
        where source_id=:s and issue_id=:id and observed_schema is null and state<>'RESOLVED'
          and (tenant_id is null or tenant_id=:tenant)
        """).param("s",source).param("id",id).param("tenant",tenant).update();
    if(changed!=1) throw conflict("No historical issue eligible for resolution");
    return issue(id);
  }

  public void assertActivationAllowed(String source,UUID correction) {
    boolean blocked=db.sql("""
        select exists(select 1 from ouf_onboarding.schema_surveillance_issue i join ouf_onboarding.source s using(source_id)
          where i.source_id=:s and i.state<>'RESOLVED' and i.issue_id is distinct from cast(:id as uuid)
          and (i.classification in ('BREAKING','UNKNOWN') or s.acquisition_mode='PULL' and (
            i.classification='ADDITIVE' or i.previous_fingerprint<>i.observed_fingerprint
            or (jsonb_typeof(i.detail->'differences')='array' and i.detail->'differences'<>'[]'::jsonb))))
        """).param("id",correction).param("s",source).query(Boolean.class).single();
    if(blocked) throw new DomainFailure(HttpStatus.CONFLICT,"ONB_SCHEMA_DRIFT_BLOCKS_ACTIVATION","Unresolved schema drift blocks activation");
  }

  public void closeActivated(String source,UUID correction) {
    int changed=db.sql("""
        update ouf_onboarding.schema_surveillance_issue set state='RESOLVED',resolution_reason='REVISION_ACTIVATED',
          resolved_at=transaction_timestamp() where issue_id=:id and source_id=:s and state='OPEN'
        """).param("id",correction).param("s",source).update();
    if(changed!=1) throw conflict("Corrective evidence is no longer open");
  }

  private Map<String,Object> issue(UUID id) {
    return decode(db.sql(SELECT+" where issue_id=:id").param("id",id).query().singleRow());
  }
  private String write(Object value) {
    try { return json.writeValueAsString(value); } catch(Exception e) { throw invalid("Invalid evidence"); }
  }
  private Map<String,Object> decode(Map<String,Object> row) {
    var out=new LinkedHashMap<>(row);
    try { out.put("detail",json.readValue((String)row.get("detail"),Map.class)); } catch(Exception e) { throw new IllegalStateException(e); }
    return out;
  }
  static DomainFailure invalid(String message) {
    return new DomainFailure(HttpStatus.UNPROCESSABLE_ENTITY,"ONB_SCHEMA_SURVEILLANCE_INVALID",message);
  }
  static DomainFailure conflict(String message) {
    return new DomainFailure(HttpStatus.CONFLICT,"ONB_SCHEMA_INCIDENT_CONFLICT",message);
  }
}
