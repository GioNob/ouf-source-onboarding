package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.onboarding.application.ManagedFileService;
import it.comune.trieste.ouf.onboarding.application.ManagedFileStagingStore;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Internal streaming owner endpoint; only a Gateway-signed HUMAN delegation can enter. */
@RestController
@ConditionalOnBean(ManagedFileStagingStore.class)
public class ManagedFileDelegatedUploadApi {
  private final PermissionDelegationVerifier receipts;
  private final ManagedFileService files;
  private final ManagedFileStagingStore storage;
  public ManagedFileDelegatedUploadApi(PermissionDelegationVerifier receipts,ManagedFileService files,ManagedFileStagingStore storage){
    this.receipts=receipts;this.files=files;this.storage=storage;
  }
  @PostMapping(path="/api/internal/v1/onboarding/managed-file-mcp/upload",consumes="text/csv")
  public ResponseEntity<Map<String,Object>> upload(HttpServletRequest request) throws IOException {
    var delegated=receipts.verifyManagedUpload(request.getHeader("X-OUF-Managed-File-Receipt"),request.getRequestURI());
    if(request.getContentLengthLong()!=delegated.expectedLength())
      throw new DomainFailure(HttpStatus.UNPROCESSABLE_ENTITY,"ONB_FILE_SIZE_MISMATCH","Declared upload size differs from signed size");
    Path spool=Files.createTempFile("ouf-delegated-intake-",".csv");
    try {
      var digest=MessageDigest.getInstance("SHA-256");long size=0;
      try(var input=request.getInputStream();var output=Files.newOutputStream(spool)){
        byte[] chunk=new byte[64*1024];
        for(int n;(n=input.read(chunk))!=-1;){
          size+=n;
          if(size>delegated.expectedLength())throw new DomainFailure(HttpStatus.UNPROCESSABLE_ENTITY,"ONB_FILE_SIZE_MISMATCH","Upload exceeds signed size");
          digest.update(chunk,0,n);output.write(chunk,0,n);
        }
      }
      if(size!=delegated.expectedLength())throw new DomainFailure(HttpStatus.UNPROCESSABLE_ENTITY,"ONB_FILE_SIZE_MISMATCH","Upload is incomplete");
      if(!delegated.expectedHash().equals("sha256:"+HexFormat.of().formatHex(digest.digest())))
        throw new DomainFailure(HttpStatus.UNPROCESSABLE_ENTITY,"ONB_FILE_HASH_MISMATCH","Upload checksum mismatch");
      final long checkedSize=size;
      var principal=delegated.principal();
      Map<String,Object> asset=files.registerDelegatedUpload(principal.tenantId(),principal.subjectId(),delegated.fileId(),
          delegated.idempotencyKey(),delegated.expectedHash(),checkedSize,()->{
            try(var input=Files.newInputStream(spool)){return storage.put(input,checkedSize,"text/csv");}
            catch(IOException e){throw new DomainFailure(HttpStatus.BAD_GATEWAY,"ONB_STAGING_WRITE_FAILED","Managed file staging write failed");}
          });
      return ResponseEntity.status(HttpStatus.CREATED).body(asset);
    }catch(NoSuchAlgorithmException e){throw new IllegalStateException("SHA-256 unavailable",e);}
    finally{Files.deleteIfExists(spool);}
  }
  @ExceptionHandler(SecurityException.class) ResponseEntity<?> denied(){
    return ResponseEntity.status(403).body(Map.of("code","ONB_MANAGED_FILE_RECEIPT_INVALID"));
  }
}
