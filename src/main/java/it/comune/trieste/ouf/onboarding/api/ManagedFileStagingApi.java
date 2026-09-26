package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.onboarding.application.ManagedFileService;
import it.comune.trieste.ouf.onboarding.application.ManagedFileStagingStore;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnBean(ManagedFileStagingStore.class)
public class ManagedFileStagingApi {
  private static final int MAX_BYTES=10*1024*1024;
  private final ManagedFileStagingStore storage;
  private final ManagedFileService files;
  private final TrustedActorResolver actors;
  public ManagedFileStagingApi(ManagedFileStagingStore storage,ManagedFileService files,CanonicalHash hashes,TrustedActorResolver actors){
    this.storage=storage;this.files=files;this.actors=actors;
  }

  @PostMapping(path="/api/managed-sources/v1/files",consumes="text/csv")
  public ResponseEntity<Map<String,Object>> upload(HttpServletRequest request,
      @RequestHeader(value="X-Content-SHA256",required=false) String declaredHash) throws IOException {
    var actor=actors.requireHuman(request,"ouf.managed-source.file.upload");
    Path staged=Files.createTempFile("ouf-managed-intake-",".csv");
    try {
      MessageDigest digest=MessageDigest.getInstance("SHA-256");
      long size=0;
      try(var target=Files.newOutputStream(staged);InputStream source=request.getInputStream()) {
        byte[] chunk=new byte[64*1024];
        for(int n;(n=source.read(chunk))!=-1;){
          size+=n;
          if(size>MAX_BYTES)throw new DomainFailure(HttpStatus.PAYLOAD_TOO_LARGE,"ONB_FILE_SIZE_INVALID","File is empty or exceeds 10 MiB");
          digest.update(chunk,0,n);
          target.write(chunk,0,n);
        }
      }
      if(size==0)throw new DomainFailure(HttpStatus.BAD_REQUEST,"ONB_FILE_SIZE_INVALID","File is empty");
      String hash="sha256:"+java.util.HexFormat.of().formatHex(digest.digest());
      if(declaredHash!=null&&!hash.equals(declaredHash))throw new DomainFailure(HttpStatus.UNPROCESSABLE_ENTITY,"ONB_FILE_HASH_MISMATCH","Upload checksum mismatch");
      String ref;
      try(var source=Files.newInputStream(staged)){ref=storage.put(source,size,"text/csv");}
      var asset=files.register(null,ref,hash,"text/csv",size,actor.subject(),"retention://managed-files/30d");
      return ResponseEntity.created(URI.create("/api/onboarding/v1/managed-files/"+asset.get("asset_id"))).body(asset);
    } catch(NoSuchAlgorithmException e){throw new IllegalStateException("SHA-256 unavailable",e);}
      finally {Files.deleteIfExists(staged);}
  }

  @GetMapping(path="/api/internal/v1/onboarding/managed-files/content",produces="application/octet-stream")
  public ResponseEntity<byte[]> read(@RequestParam("ref") String ref,HttpServletRequest request){
    actors.requireService(request,"ouf.object-storage.content.read");
    byte[] content=storage.get(ref);
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(content.length).body(content);
  }
}
