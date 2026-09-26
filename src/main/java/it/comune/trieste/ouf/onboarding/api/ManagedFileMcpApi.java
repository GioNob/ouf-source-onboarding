package it.comune.trieste.ouf.onboarding.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.application.ManagedFileService;
import it.comune.trieste.ouf.onboarding.application.OnboardingService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Owner entry point for an exact Gateway-minted delegated HUMAN receipt. */
@RestController
@RequestMapping("/api/internal/v1/onboarding/managed-file-mcp")
public class ManagedFileMcpApi {
  public record CreateDraft(UUID assetId,UUID profileId,String sourceId,String name,String owner,String targetClassIri,
      List<String> semanticRefs,List<String> sourceObjectKeyFields,List<ManagedFileApi.FieldDecision> fields,String layer){}
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
  @PostMapping("/preview") public Map<String,Object> preview(@RequestBody byte[] raw,HttpServletRequest request){
    String owner=subject(raw,request,"ouf.managed-source.preview");
    JsonNode args;
    try {args=arguments(raw,"assetId","profileId");}
    catch(IllegalArgumentException ex){args=arguments(raw,"assetId","jobId");}
    UUID asset=UUID.fromString(args.get("assetId").asText());
    files.requireOwner(asset,owner);
    if(args.has("jobId")){
      var job=files.profileJob(asset,UUID.fromString(args.get("jobId").asText()));
      Map<String,Object> safe=new LinkedHashMap<>();
      for(String key:new String[]{"jobId","status","resultRef","errorCode"})safe.put(key,job.get(key));
      return safe;
    }
    return files.preview(asset,UUID.fromString(args.get("profileId").asText()));
  }
  @PostMapping("/create") public Map<String,Object> create(@RequestBody byte[] raw,HttpServletRequest request){
    String cap="ouf.managed-source.onboarding.create";
    var delegated=receipts.verifyManagedFile(request.getHeader("X-OUF-Managed-File-Receipt"),request.getRequestURI(),cap,raw);
    try {
      JsonNode envelope=json.readTree(raw),args=envelope.required("Arguments");
      Set<String> allowed=Set.of("assetId","profileId","sourceId","name","owner","targetClassIri","semanticRefs","sourceObjectKeyFields","fields","layer");
      if(!args.isObject()||args.size()>allowed.size())throw new IllegalArgumentException("invalid draft arguments");
      for(var names=args.fieldNames();names.hasNext();)if(!allowed.contains(names.next()))throw new IllegalArgumentException("invalid draft arguments");
      CreateDraft draft=json.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).treeToValue(args,CreateDraft.class);
      if(draft.assetId()==null||draft.profileId()==null||draft.sourceId()==null||draft.sourceId().isBlank()
          ||draft.name()==null||draft.name().isBlank()||draft.owner()==null||draft.owner().isBlank()
          ||draft.targetClassIri()==null||draft.targetClassIri().isBlank()
          ||draft.semanticRefs()==null||draft.semanticRefs().isEmpty()||draft.semanticRefs().size()>32
          ||draft.fields()!=null&&draft.fields().size()>256)throw new IllegalArgumentException("invalid draft arguments");
      var actor=new OnboardingService.Actor(delegated.principal().subjectId(),"HUMAN_USER",Set.of(cap));
      List<ManagedFileService.FieldDecision> fields=draft.fields()==null?List.of():draft.fields().stream()
          .map(f->new ManagedFileService.FieldDecision(f.fieldName(),f.extractionDecision(),f.dataAccessLabel(),f.targetPropertyIri(),f.transform(),f.vocabularyId(),f.vocabularyVersion(),f.valueMapRef())).toList();
      return files.onboardIdempotent(draft.assetId(),draft.profileId(),draft.sourceId(),draft.name(),draft.owner(),draft.targetClassIri(),
          draft.semanticRefs(),draft.sourceObjectKeyFields(),fields,draft.layer(),actor,envelope.required("CorrelationID").asText(),delegated.idempotencyKey());
    }catch(IOException e){throw new IllegalArgumentException("invalid draft arguments",e);}
  }
  @ExceptionHandler(SecurityException.class) ResponseEntity<?> denied(){
    return ResponseEntity.status(403).body(Map.of("code","ONB_MANAGED_FILE_RECEIPT_INVALID"));
  }
}
