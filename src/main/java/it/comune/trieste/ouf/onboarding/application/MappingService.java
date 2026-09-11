package it.comune.trieste.ouf.onboarding.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.sql.Types;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MappingService {
  private final JdbcClient db; private final ObjectMapper json; private final OnboardingService onboarding;
  public MappingService(JdbcClient db,ObjectMapper json,OnboardingService onboarding){this.db=db;this.json=json;this.onboarding=onboarding;}

  @Transactional public Map<String,Object> initialize(String sourceId,String typeCode){
    db.sql("insert into ouf_onboarding.mapping_workspace(source_id,type_code) select source_id,type_code from ouf_onboarding.discovered_type where source_id=:s and type_code=:t on conflict do nothing").param("s",sourceId).param("t",typeCode).update();
    db.sql("insert into ouf_onboarding.field_mapping(source_id,type_code,field_name) select source_id,type_code,field_name from ouf_onboarding.discovered_field where source_id=:s and type_code=:t on conflict do nothing").param("s",sourceId).param("t",typeCode).update();
    return workspace(sourceId,typeCode);
  }
  @Transactional public Map<String,Object> configureType(String sourceId,String typeCode,String targetClassIri,List<String> semanticRefs,List<String> eligibleStates,long expected){
    int rows=db.sql("update ouf_onboarding.mapping_workspace set target_class_iri=:i,semantic_refs=cast(:r as jsonb),eligible_states=cast(:e as jsonb),lock_version=lock_version+1,updated_at=transaction_timestamp() where source_id=:s and type_code=:t and state='DRAFT' and lock_version=:l")
      .param("i",targetClassIri).param("r",write(semanticRefs)).param("e",write(eligibleStates)).param("s",sourceId).param("t",typeCode).param("l",expected).update();if(rows!=1)throw stale();return workspace(sourceId,typeCode);
  }
  @Transactional public Map<String,Object> configureField(String sourceId,String typeCode,String fieldName,String classification,String targetPropertyIri,String transform,long expected){
    int rows=db.sql("update ouf_onboarding.field_mapping set classification=:c,target_property_iri=:p,transform=:x,lock_version=lock_version+1,updated_at=transaction_timestamp() where source_id=:s and type_code=:t and field_name=:f and lock_version=:l and exists(select 1 from ouf_onboarding.mapping_workspace w where w.source_id=:s and w.type_code=:t and w.state='DRAFT')")
      .param("c",classification).param("p",new SqlParameterValue(Types.VARCHAR,targetPropertyIri)).param("x",new SqlParameterValue(Types.VARCHAR,transform)).param("s",sourceId).param("t",typeCode).param("f",fieldName).param("l",expected).update();if(rows!=1)throw stale();return field(sourceId,typeCode,fieldName);
  }
  @Transactional public Map<String,Object> addRelationship(String sourceId,String typeCode,String sourceField,String relationIri,String targetClassIri,String strategy,String onNoMatch,String onMultiple,String provenance){
    UUID id=UUID.randomUUID();db.sql("insert into ouf_onboarding.relationship_mapping_draft(relationship_mapping_id,source_id,type_code,source_field,relation_iri,target_class_iri,resolution_strategy,on_no_match,on_multiple_matches,provenance_policy) values(:i,:s,:t,:f,:r,:c,:x,:n,:m,:p)").param("i",id).param("s",sourceId).param("t",typeCode).param("f",sourceField).param("r",relationIri).param("c",targetClassIri).param("x",strategy).param("n",onNoMatch).param("m",onMultiple).param("p",provenance).update();return Map.of("relationshipMappingId",id);
  }
  @Transactional public Map<String,Object> buildDraft(String sourceId,String typeCode,OnboardingService.Actor actor,String correlation){
    Map<String,Object> workspace=db.sql("select source_id,type_code,state,target_class_iri,semantic_refs::text semantic_refs,eligible_states::text eligible_states,lock_version from ouf_onboarding.mapping_workspace where source_id=:s and type_code=:t for update").param("s",sourceId).param("t",typeCode).query().listOfRows().stream().findFirst().orElseThrow(()->missing("Mapping workspace not found"));
    List<Map<String,Object>> fields=db.sql("select field_name,classification,target_property_iri,transform from ouf_onboarding.field_mapping where source_id=:s and type_code=:t order by field_name for update").param("s",sourceId).param("t",typeCode).query().listOfRows();
    if(fields.isEmpty()||fields.stream().anyMatch(f->"UNCLASSIFIED".equals(f.get("classification"))))throw invalid("Every discovered field must be classified");List<Map<String,Object>> included=fields.stream().filter(f->"INCLUDE".equals(f.get("classification"))).toList();if(included.isEmpty())throw invalid("At least one field must be included");
    String target=Objects.toString(workspace.get("target_class_iri"),"");List<String> refs=readList(workspace.get("semantic_refs"));if(target.isBlank()||refs.isEmpty())throw invalid("Target class and semantic references are required");List<String> states=readList(workspace.get("eligible_states"));Map<String,Object> source=onboarding.source(sourceId);
    List<Map<String,Object>> propertyMappings=included.stream().map(f->Map.<String,Object>of("sourceField",f.get("field_name"),"targetPropertyIri",f.get("target_property_iri"),"transform",Objects.toString(f.get("transform"),"IDENTITY"))).toList();List<String> projection=included.stream().map(f->String.valueOf(f.get("field_name"))).toList();
    Map<String,Object> sync=Map.of("bootstrap","FULL_SNAPSHOT","incremental","NONE","pageSize",500);Map<String,Object> config=new LinkedHashMap<>();config.put("syncProfile",sync);
    config.put("extractionProfile",Map.of("profileId","ep-"+sourceId+"-"+typeCode,"version","1","sourceId",sourceId,"selection",Map.of("procedureTypes",List.of(typeCode),"eligibleNativeStates",states),"projection",Map.of(typeCode,projection),"sync",sync,"runtime",Map.of("mode",source.get("acquisition_mode"),"credentialRef","workload://configured-binding")));
    String[] semantic=semanticRef(refs.get(0));config.put("semanticMapping",Map.of("mappingId","sm-"+sourceId+"-"+typeCode,"sourceType",Map.of("sourceId",sourceId,"typeCode",typeCode),"targetClasses",List.of(Map.of("ontologyId",semantic[0],"ontologyVersion",semantic[1],"classIri",target)),"propertyMappings",propertyMappings,"semanticRefs",refs));
    Map<String,Object> bundle=new LinkedHashMap<>();bundle.put("bundleId","bundle-"+sourceId+"-draft");bundle.put("bundleVersion","1.0.0");bundle.put("environment","draft");bundle.put("source",Map.of("sourceId",sourceId,"sourceKind","OGC".equals(source.get("source_kind"))?"OGC_SERVICE":source.get("source_kind"),"acquisitionMode",source.get("acquisition_mode")));if("PULL".equals(source.get("acquisition_mode")))bundle.put("bindingRef","onboarding://sources/"+sourceId+"/bindings/active");bundle.put("createdFromOnboardingVersion","pending");bundle.put("effectiveFrom","1970-01-01T00:00:00Z");bundle.put("checksum","sha256:pending");bundle.put("status","APPROVED");bundle.put("changeRepresentationProfile",Map.of("mode","FULL_SNAPSHOT"));config.put("changeRepresentationProfile",Map.of("mode","FULL_SNAPSHOT"));config.put("bundle",bundle);
    Map<String,Object> draft=onboarding.createVersion(sourceId,config,actor,correlation);db.sql("update ouf_onboarding.mapping_workspace set state='COMPLETE',lock_version=lock_version+1,updated_at=transaction_timestamp() where source_id=:s and type_code=:t and state='DRAFT'").param("s",sourceId).param("t",typeCode).update();return draft;
  }
  public Map<String,Object> workspace(String sourceId,String typeCode){Map<String,Object> w=jsonRow(db.sql("select source_id,type_code,state,target_class_iri,semantic_refs::text semantic_refs,eligible_states::text eligible_states,lock_version,created_at,updated_at from ouf_onboarding.mapping_workspace where source_id=:s and type_code=:t").param("s",sourceId).param("t",typeCode).query().listOfRows().stream().findFirst().orElseThrow(()->missing("Mapping workspace not found")),"semantic_refs","eligible_states");w.put("fields",db.sql("select field_name,classification,target_property_iri,transform,lock_version from ouf_onboarding.field_mapping where source_id=:s and type_code=:t order by field_name").param("s",sourceId).param("t",typeCode).query().listOfRows());return w;}
  private Map<String,Object> field(String s,String t,String f){return db.sql("select field_name,classification,target_property_iri,transform,lock_version from ouf_onboarding.field_mapping where source_id=:s and type_code=:t and field_name=:f").param("s",s).param("t",t).param("f",f).query().singleRow();}
  private String write(Object v){try{return json.writeValueAsString(v==null?List.of():v);}catch(Exception e){throw new IllegalArgumentException(e);}}
  @SuppressWarnings("unchecked") private List<String> readList(Object value){try{return value instanceof String s?json.readValue(s,List.class):(List<String>)value;}catch(Exception e){throw new IllegalStateException(e);}}
  private Map<String,Object> jsonRow(Map<String,Object> row,String...fields){try{Map<String,Object> out=new LinkedHashMap<>(row);for(String f:fields)if(out.get(f) instanceof String s)out.put(f,json.readValue(s,Object.class));return out;}catch(Exception e){throw new IllegalStateException(e);}}
  private static DomainFailure stale(){return new DomainFailure(HttpStatus.PRECONDITION_FAILED,"ONB_ETAG_MISMATCH","Stale ETag or completed workspace");}
  private static DomainFailure invalid(String m){return new DomainFailure(HttpStatus.UNPROCESSABLE_ENTITY,"ONB_MAPPING_INCOMPLETE",m);}
  private static DomainFailure missing(String m){return new DomainFailure(HttpStatus.NOT_FOUND,"ONB_MAPPING_NOT_FOUND",m);}
  private static String[] semanticRef(String ref){int at=ref==null?-1:ref.lastIndexOf('@');if(at<1||at==ref.length()-1)throw invalid("Semantic references must pin id@version");return new String[]{ref.substring(0,at),ref.substring(at+1)};}
}
