package it.comune.trieste.ouf.onboarding.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.application.ManagedFileService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Owner entry point for an exact Gateway-minted delegated HUMAN receipt. */
@RestController
@RequestMapping("/api/internal/v1/onboarding/managed-file-mcp")
public class ManagedFileMcpApi {
  private final PermissionDelegationVerifier receipts;
  private final ManagedFileService files;
  private final ObjectMapper json;
  public ManagedFileMcpApi(PermissionDelegationVerifier receipts,ManagedFileService files,ObjectMapper json){
    this.receipts=receipts;this.files=files;this.json=json;
  }
  private JsonNode arguments(byte[] raw,String...keys){
    try {
      JsonNode envelope=json.readTree(raw);
      JsonNode args=envelope.required("Arguments");
      if(!args.isObject()||args.size()!=keys.length)throw new IllegalArgumentException("invalid managed-file arguments");
      for(String key:keys)if(!args.hasNonNull(key)||!args.get(key).isTextual())throw new IllegalArgumentException("invalid managed-file arguments");
      return args;
    }catch(IOException e){throw new IllegalArgumentException("invalid managed-file arguments",e);}
  }
  private String subject(byte[] raw,HttpServletRequest request,String capability){
    return receipts.verifyManagedFile(request.getHeader("X-OUF-Managed-File-Receipt"),
        request.getRequestURI(),capability,raw).principal().subjectId();
  }
  @PostMapping("/profile") public ResponseEntity<Map<String,Object>> profile(@RequestBody byte[] raw,HttpServletRequest request){
    String cap="ouf.managed-source.file.profile";
    var delegated=receipts.verifyManagedFile(request.getHeader("X-OUF-Managed-File-Receipt"),request.getRequestURI(),cap,raw);
    UUID asset=UUID.fromString(arguments(raw,"assetId").get("assetId").asText());
    files.requireOwner(asset,delegated.principal().subjectId());
    var job=files.requestProfile(asset,delegated.idempotencyKey());
    return ResponseEntity.accepted().body(Map.of("jobId",job.get("jobId"),"status",job.get("status")));
  }
  @PostMapping("/status") public Map<String,Object> status(@RequestBody byte[] raw,HttpServletRequest request){
    String owner=subject(raw,request,"ouf.managed-source.preview");
    JsonNode args=arguments(raw,"assetId","jobId");
    UUID asset=UUID.fromString(args.get("assetId").asText());
    files.requireOwner(asset,owner);
    var job=files.profileJob(asset,UUID.fromString(args.get("jobId").asText()));
    Map<String,Object> safe=new LinkedHashMap<>();
    for(String key:new String[]{"jobId","status","resultRef","errorCode"})safe.put(key,job.get(key));
    return safe;
  }
  @PostMapping("/preview") public Map<String,Object> preview(@RequestBody byte[] raw,HttpServletRequest request){
    String owner=subject(raw,request,"ouf.managed-source.preview");
    JsonNode args=arguments(raw,"assetId","profileId");
    UUID asset=UUID.fromString(args.get("assetId").asText());
    files.requireOwner(asset,owner);
    return files.preview(asset,UUID.fromString(args.get("profileId").asText()));
  }
  @ExceptionHandler(SecurityException.class) ResponseEntity<?> denied(){
    return ResponseEntity.status(403).body(Map.of("code","ONB_MANAGED_FILE_RECEIPT_INVALID"));
  }
}
