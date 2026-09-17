package it.comune.trieste.ouf.onboarding.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SemanticGapService {
  private final JdbcClient db;private final ObjectMapper json;
  public SemanticGapService(JdbcClient db,ObjectMapper json){this.db=db;this.json=json;}
  @Transactional public Map<String,Object> create(String sourceId,UUID versionId,String typeCode,String fieldPath,String description){requireDraft(sourceId,versionId);UUID id=UUID.randomUUID();db.sql("insert into ouf_onboarding.semantic_gap(gap_id,source_id,onboarding_version_id,type_code,field_path,description) values(:i,:s,:v,:t,:f,:d)").param("i",id).param("s",sourceId).param("v",versionId).param("t",typeCode).param("f",new org.springframework.jdbc.core.SqlParameterValue(java.sql.Types.VARCHAR,fieldPath)).param("d",description).update();return gap(sourceId,versionId,id);}
  @Transactional public Map<String,Object> requestSearch(String sourceId,UUID versionId,UUID gapId,String requestRef){requireDraft(sourceId,versionId);int changed=db.sql("update ouf_onboarding.semantic_gap set state='SEARCH_REQUESTED',discovery_request_ref=:r,updated_at=transaction_timestamp() where source_id=:s and onboarding_version_id=:v and gap_id=:g and state='OPEN'").param("r",requestRef).param("s",sourceId).param("v",versionId).param("g",gapId).update();if(changed!=1)throw conflict("Semantic gap is not OPEN");return gap(sourceId,versionId,gapId);}
  @Transactional public Map<String,Object> recordCandidates(String sourceId,UUID versionId,UUID gapId,List<Map<String,Object>> candidates,OnboardingService.Actor actor){if(!"SERVICE".equals(actor.type())||!actor.capabilities().contains("ouf.semantic.discovery.result.report"))throw new DomainFailure(HttpStatus.FORBIDDEN,"ONB_SEMANTIC_RESULT_AUTHORITY_REQUIRED","Semantic result reporting requires the Authorization-owned Semantic Registry capability");requireDraft(sourceId,versionId);Map<String,Object> current=gap(sourceId,versionId,gapId);if(!Set.of("SEARCH_REQUESTED","CANDIDATES_AVAILABLE","SELECTED").contains(current.get("state")))throw conflict("Semantic gap is not waiting for candidates");for(Map<String,Object> candidate:candidates){String ref=String.valueOf(candidate.get("semanticRef"));if(ref.isBlank()||!ref.contains("@"))throw invalid("Candidate semanticRef must pin id@version");db.sql("insert into ouf_onboarding.semantic_candidate(candidate_id,gap_id,semantic_ref,label,status,evidence) values(:i,:g,:r,:l,:s,cast(:e as jsonb)) on conflict(gap_id,semantic_ref) do update set status=excluded.status,evidence=case when excluded.evidence='{}'::jsonb then semantic_candidate.evidence else excluded.evidence end,label=excluded.label").param("i",UUID.randomUUID()).param("g",gapId).param("r",ref).param("l",String.valueOf(candidate.getOrDefault("label",ref))).param("s",String.valueOf(candidate.getOrDefault("status","CANDIDATE"))).param("e",write(candidate.getOrDefault("evidence",Map.of()))).update();}db.sql("update ouf_onboarding.semantic_gap set state=case when selected_candidate_id is null then 'CANDIDATES_AVAILABLE' when exists(select 1 from ouf_onboarding.semantic_candidate c where c.candidate_id=selected_candidate_id and c.status='PUBLISHED') then 'RESOLVED' else 'SELECTED' end,updated_at=transaction_timestamp() where gap_id=:g").param("g",gapId).update();return gap(sourceId,versionId,gapId);}
  @Transactional public Map<String,Object> select(String sourceId,UUID versionId,UUID gapId,UUID candidateId){requireDraft(sourceId,versionId);int changed=db.sql("update ouf_onboarding.semantic_gap set state=case when c.status='PUBLISHED' then 'RESOLVED' else 'SELECTED' end,selected_candidate_id=c.candidate_id,updated_at=transaction_timestamp() from ouf_onboarding.semantic_candidate c where semantic_gap.gap_id=:g and semantic_gap.source_id=:s and semantic_gap.onboarding_version_id=:v and semantic_gap.state='CANDIDATES_AVAILABLE' and c.gap_id=semantic_gap.gap_id and c.candidate_id=:c").param("g",gapId).param("s",sourceId).param("v",versionId).param("c",candidateId).update();if(changed!=1)throw conflict("Candidate does not belong to an eligible semantic gap");return gap(sourceId,versionId,gapId);}
  public List<Map<String,Object>> list(String sourceId,UUID versionId){return db.sql("select gap_id,source_id,onboarding_version_id,type_code,field_path,description,state,discovery_request_ref,selected_candidate_id,source_evidence::text source_evidence,created_at,updated_at from ouf_onboarding.semantic_gap where source_id=:s and onboarding_version_id=:v order by created_at").param("s",sourceId).param("v",versionId).query().listOfRows();}
  public Map<String,Object> gap(String sourceId,UUID versionId,UUID id){Map<String,Object> out=new LinkedHashMap<>(db.sql("select gap_id,source_id,onboarding_version_id,type_code,field_path,description,state,discovery_request_ref,selected_candidate_id,source_evidence::text source_evidence,created_at,updated_at from ouf_onboarding.semantic_gap where source_id=:s and onboarding_version_id=:v and gap_id=:g").param("s",sourceId).param("v",versionId).param("g",id).query().listOfRows().stream().findFirst().orElseThrow(()->new DomainFailure(HttpStatus.NOT_FOUND,"ONB_SEMANTIC_GAP_NOT_FOUND","Semantic gap not found")));out.put("source_evidence",readMap(String.valueOf(out.get("source_evidence"))));out.put("candidates",db.sql("select candidate_id,semantic_ref,label,status,evidence::text evidence,created_at from ouf_onboarding.semantic_candidate where gap_id=:g order by created_at").param("g",id).query().listOfRows().stream().map(this::decode).toList());return out;}
  /** Creates proposals from the pinned, profiled schema; never accepts caller-supplied FK evidence. */
  @Transactional public List<Map<String,Object>> proposeAccessSchema(String sourceId,UUID versionId,OnboardingService.Actor actor){
    if(!actor.capabilities().contains("ouf.source-onboarding.semantic-gap.create"))throw new DomainFailure(HttpStatus.FORBIDDEN,"ONB_SEMANTIC_PROPOSAL_DENIED","Semantic gap creation capability required");
    requireDraft(sourceId,versionId);
    var config=db.sql("select configuration->'sourceSchemaEvidence' as evidence from ouf_onboarding.onboarding_version where source_id=:s and onboarding_version_id=:v").param("s",sourceId).param("v",versionId).query(String.class).single();
    Map<String,Object> evidence=readMap(config);
    String ref=String.valueOf(evidence.get("fileProfileRef"));
    java.util.regex.Matcher refMatch=java.util.regex.Pattern.compile("profile://managed-files/([0-9a-fA-F-]{36})/profiles/([0-9a-fA-F-]{36})").matcher(ref);
    if(!refMatch.matches())throw invalid("Pinned Access file profile required");
    UUID asset=UUID.fromString(refMatch.group(1)),profile=UUID.fromString(refMatch.group(2));
    var stored=db.sql("select p.metadata::text metadata,a.content_hash from ouf_onboarding.file_profile p join ouf_onboarding.managed_file_asset a on a.asset_id=p.asset_id where p.asset_id=:a and p.profile_id=:p and p.format='ACCESS' and (a.source_id=:s or exists(select 1 from ouf_onboarding.source owned where owned.source_id=:s and owned.metadata->>'managedFileAssetId'=a.asset_id::text))").param("a",asset).param("p",profile).param("s",sourceId).query().listOfRows().stream().findFirst().orElseThrow(()->invalid("Access profile does not belong to this source"));
    if(!Objects.equals(stored.get("content_hash"),evidence.get("contentHash")))throw invalid("Access profile content hash differs from draft");
    Map<String,Object> metadata=readMap(String.valueOf(stored.get("metadata")));
    String selected=String.valueOf(evidence.get("selectedTable"));
    @SuppressWarnings("unchecked") var hints=(List<Map<String,Object>>)metadata.getOrDefault("semanticHints",List.of());
    var result=new ArrayList<Map<String,Object>>();
    for(var hint:hints){
      boolean relation="RELATIONSHIP".equals(hint.get("kind"));
      if(!(relation?(selected.equals(hint.get("referencingTable"))||selected.equals(hint.get("referencedTable"))):selected.equals(hint.get("table"))))continue;
      var provenance=Map.of("fileProfileRef",ref,"contentHash",stored.get("content_hash"),"selectedTable",selected,"hint",hint,"authority","PROPOSAL_ONLY");
      String hash=new it.comune.trieste.ouf.onboarding.domain.CanonicalHash(json).of(provenance);
      UUID id=UUID.randomUUID();
      String description=relation?"Review Access relationship "+hint.get("name")+": select published predicate, domain, range and direction":"Review Access table "+selected+": select published class and properties";
      db.sql("insert into ouf_onboarding.semantic_gap(gap_id,source_id,onboarding_version_id,type_code,field_path,description,source_evidence,source_evidence_hash) values(:i,:s,:v,'FILE',:field,:description,cast(:e as jsonb),:hash) on conflict do nothing")
        .param("i",id).param("s",sourceId).param("v",versionId).param("field",relation?String.valueOf(hint.get("name")):selected).param("description",description).param("e",write(provenance)).param("hash",hash).update();
      UUID existing=db.sql("select gap_id from ouf_onboarding.semantic_gap where onboarding_version_id=:v and source_evidence_hash=:h").param("v",versionId).param("h",hash).query(UUID.class).single();
      result.add(gap(sourceId,versionId,existing));
    }
    return result;
  }
  private Map<String,Object> readMap(String value){try{return json.readValue(value,new com.fasterxml.jackson.core.type.TypeReference<>(){});}catch(Exception e){throw invalid("Pinned Access schema evidence required");}}
  private void requireDraft(String source,UUID version){String state=db.sql("select state from ouf_onboarding.onboarding_version where source_id=:s and onboarding_version_id=:v for update").param("s",source).param("v",version).query(String.class).optional().orElseThrow(()->new DomainFailure(HttpStatus.NOT_FOUND,"ONB_NOT_FOUND","version not found"));if(!"DRAFT".equals(state))throw invalid("Semantic gaps may change only on DRAFT versions");}
  private Map<String,Object> decode(Map<String,Object> row){try{Map<String,Object> out=new LinkedHashMap<>(row);out.put("evidence",json.readValue(String.valueOf(row.get("evidence")),Object.class));return out;}catch(Exception e){throw new IllegalStateException(e);}}
  private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw invalid("Invalid candidate evidence");}}
  private static DomainFailure invalid(String m){return new DomainFailure(HttpStatus.UNPROCESSABLE_ENTITY,"ONB_SEMANTIC_GAP_INVALID",m);}
  private static DomainFailure conflict(String m){return new DomainFailure(HttpStatus.CONFLICT,"ONB_SEMANTIC_GAP_CONFLICT",m);}
}
