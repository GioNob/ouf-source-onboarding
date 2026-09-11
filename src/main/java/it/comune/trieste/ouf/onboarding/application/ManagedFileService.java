package it.comune.trieste.ouf.onboarding.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.domain.*;
import java.sql.Types;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ManagedFileService {
  private final JdbcClient db; private final ObjectMapper json; private final CanonicalHash hashes; private final ManagedFileProfiler profiler; private final TransactionTemplate tx;
  public ManagedFileService(JdbcClient db,ObjectMapper json,CanonicalHash hashes,ManagedFileProfiler profiler,TransactionTemplate tx){this.db=db;this.json=json;this.hashes=hashes;this.profiler=profiler;this.tx=tx;}
  public Map<String,Object> register(String sourceId,String stagingRef,String contentHash,String mediaType,long size,String uploadedBy,String retentionRef){
    if(!stagingRef.startsWith("object://"))throw invalid("ONB_STAGING_REF_INVALID","stagingRef must use object://");if(size<1||size>52_428_800)throw invalid("ONB_FILE_SIZE_INVALID","File exceeds the 50 MiB intake limit");UUID id=UUID.randomUUID();
    return tx.execute(status->{db.sql("insert into ouf_onboarding.managed_file_asset(asset_id,source_id,staging_ref,content_hash,media_type,size_bytes,uploaded_by,retention_ref) values(:i,:s,:r,:h,:m,:z,:u,:x) on conflict(staging_ref,content_hash) do nothing").param("i",id).param("s",new SqlParameterValue(Types.VARCHAR,sourceId)).param("r",stagingRef).param("h",contentHash).param("m",mediaType).param("z",size).param("u",uploadedBy).param("x",retentionRef).update();return db.sql("select asset_id,source_id,staging_ref,content_hash,media_type,size_bytes,uploaded_by,retention_ref,status,created_at from ouf_onboarding.managed_file_asset where staging_ref=:r and content_hash=:h").param("r",stagingRef).param("h",contentHash).query().singleRow();});
  }
  public Map<String,Object> profile(UUID assetId,byte[] bytes,String sampleRef){
    Map<String,Object> asset=asset(assetId);if(((Number)asset.get("size_bytes")).longValue()!=bytes.length)throw invalid("ONB_FILE_SIZE_MISMATCH","Staged size does not match downloaded content");String actual=hashes.ofBytes(bytes);if(!actual.equals(asset.get("content_hash")))throw invalid("ONB_FILE_HASH_MISMATCH","Staged content hash does not match downloaded content");ManagedFileProfiler.Profile result=profiler.profile(bytes,String.valueOf(asset.get("media_type")));
    return tx.execute(status->{db.sql("select 1 as acquired from pg_advisory_xact_lock(hashtextextended(:i,0))").param("i",assetId.toString()).query(Integer.class).single();int version=db.sql("select coalesce(max(version),0)+1 from ouf_onboarding.file_profile where asset_id=:a").param("a",assetId).query(Integer.class).single();UUID profileId=UUID.randomUUID();db.sql("insert into ouf_onboarding.file_profile(profile_id,asset_id,version,format,metadata,inferred_schema,candidate_keys,sample_ref) values(:i,:a,:v,:f,cast(:m as jsonb),cast(:s as jsonb),cast(:k as jsonb),:r)").param("i",profileId).param("a",assetId).param("v",version).param("f",result.format()).param("m",write(result.metadata())).param("s",write(result.columns())).param("k",write(result.candidateKeys())).param("r",sampleRef).update();db.sql("update ouf_onboarding.managed_file_asset set status='PROFILED' where asset_id=:a and status in ('STAGED','PROFILED')").param("a",assetId).update();return fileProfile(assetId,profileId);});
  }
  public Map<String,Object> asset(UUID id){return db.sql("select asset_id,source_id,staging_ref,content_hash,media_type,size_bytes,uploaded_by,retention_ref,status,created_at from ouf_onboarding.managed_file_asset where asset_id=:i").param("i",id).query().listOfRows().stream().findFirst().orElseThrow(()->new DomainFailure(HttpStatus.NOT_FOUND,"ONB_FILE_ASSET_NOT_FOUND","Managed file asset not found"));}
  public Map<String,Object> fileProfile(UUID asset,UUID profile){return jsonRow(db.sql("select profile_id,asset_id,version,format,metadata::text metadata,inferred_schema::text inferred_schema,candidate_keys::text candidate_keys,sample_ref,created_at from ouf_onboarding.file_profile where asset_id=:a and profile_id=:p").param("a",asset).param("p",profile).query().listOfRows().stream().findFirst().orElseThrow(()->new DomainFailure(HttpStatus.NOT_FOUND,"ONB_FILE_PROFILE_NOT_FOUND","File profile not found")),"metadata","inferred_schema","candidate_keys");}
  private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException(e);}}
  private Map<String,Object> jsonRow(Map<String,Object> row,String...fields){try{Map<String,Object> out=new LinkedHashMap<>(row);for(String f:fields)if(out.get(f) instanceof String s)out.put(f,json.readValue(s,Object.class));return out;}catch(Exception e){throw new IllegalStateException(e);}}
  private static DomainFailure invalid(String code,String message){return new DomainFailure(HttpStatus.UNPROCESSABLE_ENTITY,code,message);}
}
