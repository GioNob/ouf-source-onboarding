package it.comune.trieste.ouf.onboarding.application;

import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One dispatch per durable intent; subsequent calls can only reconcile bytes or read an ACK. */
final class ManagedFileUploadCoordinator {
  private final JdbcClient db;
  private final TransactionTemplate tx;
  private final ManagedFileService files;
  private final CanonicalHash hashes;
  ManagedFileUploadCoordinator(JdbcClient db,TransactionTemplate tx,ManagedFileService files,CanonicalHash hashes){
    this.db=db;this.tx=tx;this.files=files;this.hashes=hashes;
  }
  private record Reservation(Map<String,Object> row,boolean dispatch,Map<String,Object> completed){}

  Map<String,Object> upload(String kind,String tenant,String subject,String fileId,String key,String hash,long size,
      ManagedFileStagingStore store,Path spool){
    // Even a caller-owned transaction would retain locks during S3 I/O.
    if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Upload requires no ambient transaction");
    if(tenant==null||tenant.isBlank()||!tenant.equals(tenant.strip())||tenant.length()>256
        ||subject==null||subject.isBlank()||subject.length()>256||fileId==null||!fileId.matches("file_[A-Za-z0-9_-]{1,128}")
        ||key==null||!key.matches("[A-Za-z0-9_.:-]{1,128}")||hash==null||!hash.matches("sha256:[0-9a-f]{64}")
        ||size<1||size>10L*1024*1024)throw failure("ONB_FILE_UPLOAD_INVALID","Invalid upload metadata");
    Reservation reserved=tx.execute(status->{
      // Same lock identity as pre-V39 delegated uploads; old completed receipts remain authoritative.
      db.sql("select 1 from pg_advisory_xact_lock(hashtextextended(:i,0))")
        .param("i",tenant.length()+":"+tenant+subject.length()+":"+subject+key).query(Integer.class).single();
      if("DELEGATED".equals(kind)){
        var prior=db.sql("select * from ouf_onboarding.managed_file_upload_attempt where tenant_id=:t and subject_id=:s and idempotency_key=:k")
          .param("t",tenant).param("s",subject).param("k",key).query().listOfRows();
        if(!prior.isEmpty()){binding(prior.getFirst(),fileId,hash,size);return new Reservation(null,false,files.asset((UUID)prior.getFirst().get("asset_id")));}
      }
      var control=control();
      var rows=db.sql("select * from ouf_onboarding.managed_file_upload_intent where upload_kind=:q and tenant_id=:t and subject_id=:s and idempotency_key=:k for update")
        .param("q",kind).param("t",tenant).param("s",subject).param("k",key).query().listOfRows();
      if(!rows.isEmpty()){
        var row=rows.getFirst();binding(row,fileId,hash,size);
        if("ACKED".equals(row.get("state")))return new Reservation(row,false,files.asset((UUID)row.get("asset_id")));
        fence(control,((Number)row.get("epoch")).longValue());
        int claimed=db.sql("update ouf_onboarding.managed_file_upload_intent set reconciliation_reads=reconciliation_reads+1 where intent_id=:i and reconciliation_reads<128 and clock_timestamp()<reconcile_until")
          .param("i",row.get("intent_id")).update();
        if(claimed!=1)throw failure("ONB_UPLOAD_RECONCILIATION_EXHAUSTED","Upload reconciliation budget exhausted; intervention required");
        return new Reservation(row,false,null);
      }
      long epoch=((Number)control.get("epoch")).longValue();fence(control,epoch);
      UUID id=UUID.randomUUID();String ref="object://managed-files/"+id;
      db.sql("insert into ouf_onboarding.managed_file_upload_intent(intent_id,upload_kind,tenant_id,subject_id,file_id,idempotency_key,content_hash,size_bytes,staging_ref,epoch,state) values(:i,:q,:t,:s,:f,:k,:h,:z,:r,:e,'DISPATCHED')")
        .param("i",id).param("q",kind).param("t",tenant).param("s",subject).param("f",fileId).param("k",key).param("h",hash).param("z",size).param("r",ref).param("e",epoch).update();
      return new Reservation(Map.of("intent_id",id,"staging_ref",ref,"epoch",epoch),true,null);
    });
    if(reserved.completed()!=null)return reserved.completed();
    var row=reserved.row();String ref=(String)row.get("staging_ref");
    if(reserved.dispatch()){
      // This committed reservation is never reset, including if opening the spool or PUT fails.
      try(var input=Files.newInputStream(spool)){store.putReserved(ref,input,size,"text/csv");}
      catch(IOException e){throw failure("ONB_UPLOAD_RECONCILIATION_REQUIRED","Upload outcome requires reconciliation");}
    }else{
      byte[] existing=store.get(ref); // No second PUT, even for missing/denied objects.
      if(existing.length!=size||!hash.equals(hashes.ofBytes(existing)))
        throw failure("ONB_UPLOAD_RECONCILIATION_MISMATCH","Stored bytes differ; reconciliation blocked");
    }
    return tx.execute(status->{
      fence(control(),((Number)row.get("epoch")).longValue());
      var current=db.sql("select * from ouf_onboarding.managed_file_upload_intent where intent_id=:i for update")
        .param("i",row.get("intent_id")).query().singleRow();
      if("ACKED".equals(current.get("state")))return files.asset((UUID)current.get("asset_id"));
      var asset=files.registerOwned(null,ref,hash,"text/csv",size,subject,"retention://managed-files/30d",tenant);
      if("DELEGATED".equals(kind))db.sql("insert into ouf_onboarding.managed_file_upload_attempt(tenant_id,subject_id,file_id,idempotency_key,content_hash,size_bytes,asset_id) values(:t,:s,:f,:k,:h,:z,:a)")
        .param("t",tenant).param("s",subject).param("f",fileId).param("k",key).param("h",hash).param("z",size).param("a",asset.get("asset_id")).update();
      db.sql("update ouf_onboarding.managed_file_upload_intent set state='ACKED',asset_id=:a,acknowledged_at=clock_timestamp() where intent_id=:i")
        .param("a",asset.get("asset_id")).param("i",row.get("intent_id")).update();
      return asset;
    });
  }
  private Map<String,Object> control(){
    return db.sql("select epoch,phase from ouf_onboarding.managed_file_storage_control where singleton=true for share").query().singleRow();
  }
  private static void fence(Map<String,Object> control,long epoch){
    if(!"ACTIVE".equals(control.get("phase"))||((Number)control.get("epoch")).longValue()!=epoch)
      throw failure("ONB_UPLOAD_STORAGE_FENCED","Storage generation or phase changed; upload remains unacknowledged");
  }
  private static void binding(Map<String,Object> row,String file,String hash,long size){
    if(!file.equals(row.get("file_id"))||!hash.equals(row.get("content_hash"))||size!=((Number)row.get("size_bytes")).longValue())
      throw failure("ONB_FILE_UPLOAD_IDEMPOTENCY_CONFLICT","Idempotency key used for different file content or identity");
  }
  private static DomainFailure failure(String code,String message){return new DomainFailure(HttpStatus.CONFLICT,code,message);}
}
