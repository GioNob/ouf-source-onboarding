package it.comune.trieste.ouf.onboarding.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.application.ManagedFileService;
import it.comune.trieste.ouf.onboarding.application.ManagedFileChatHandoff;
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
  private final ManagedFileChatHandoff handoffs;
  private final ObjectMapper json;
  public ManagedFileMcpApi(PermissionDelegationVerifier receipts,ManagedFileService files,ObjectMapper json,
      ManagedFileChatHandoff handoffs){
    this.receipts=receipts;this.files=files;this.json=json;this.handoffs=handoffs;
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
  @PostMapping("/handoff") public Map<String,Object> handoff(@RequestBody byte[] raw,HttpServletRequest request){
    String owner=subject(raw,request,"ouf.managed-source.file.upload");
    UUID id=UUID.fromString(arguments(raw,"handoffId").get("handoffId").asText());
    return handoffs.result(id,owner);
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
      if(job.get("errorCode")!=null)safe.put("diagnosticCategory",profileFailureCategory(job.get("last_error")));
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
          ||draft.sourceObjectKeyFields()==null||draft.fields()==null||draft.fields().isEmpty()||draft.fields().size()>256)
          throw new IllegalArgumentException("explicit field and identity decisions required");
      var actor=new OnboardingService.Actor(delegated.principal().subjectId(),"HUMAN_USER",Set.of(cap));
      List<ManagedFileService.FieldDecision> fields=draft.fields().stream()
          .map(f->new ManagedFileService.FieldDecision(f.fieldName(),f.extractionDecision(),f.dataAccessLabel(),f.targetPropertyIri(),f.transform(),f.vocabularyId(),f.vocabularyVersion(),f.valueMapRef())).toList();
      return files.onboardIdempotent(draft.assetId(),draft.profileId(),draft.sourceId(),draft.name(),draft.owner(),draft.targetClassIri(),
          draft.semanticRefs(),draft.sourceObjectKeyFields(),fields,draft.layer(),actor,envelope.required("CorrelationID").asText(),delegated.idempotencyKey());
    }catch(IOException e){throw new IllegalArgumentException("invalid draft arguments",e);}
  }

  /** Fixed categories over retained owner evidence; never release exception text or guess unknowns. */
  static String profileFailureCategory(Object retained) {
    if(!(retained instanceof String detail)||detail.length()>512)return "UNCLASSIFIED";
    return switch(detail) {
      case "CSV must be valid UTF-8 text","CSV must be UTF-8 text" -> "CSV_ENCODING_INVALID";
      case "headers must be non-empty and unique" -> "CSV_HEADERS_INVALID";
      case "invalid CSV" -> "CSV_SYNTAX_INVALID";
      case "row limit exceeded","column limit exceeded","file size outside profiling limits" -> "PROFILE_LIMIT_EXCEEDED";
      case "unsupported media type" -> "MEDIA_TYPE_UNSUPPORTED";
      case "Staged size does not match downloaded content","Gateway object exceeds the registered size","expected object size is outside intake limits" -> "FILE_SIZE_MISMATCH";
      case "Staged content hash does not match downloaded content" -> "FILE_HASH_MISMATCH";
      case "stagingRef must use object://" -> "OBJECT_REFERENCE_INVALID";
      case "Onboarding workload token is invalid","Onboarding workload token is unavailable" -> "WORKLOAD_CREDENTIAL_UNAVAILABLE";
      case "Gateway object read failed with status 401" -> "OBJECT_READ_AUTHENTICATION_REQUIRED";
      case "Gateway object read failed with status 403" -> "OBJECT_READ_FORBIDDEN";
      case "Gateway object read failed with status 404" -> "OBJECT_READ_NOT_FOUND";
      case "Gateway object read failed with status 429","Gateway object read failed with status 502","Gateway object read failed with status 503","Gateway object read failed with status 504" -> "OBJECT_READ_UNAVAILABLE";
      default -> "UNCLASSIFIED";
    };
  }
  @ExceptionHandler(SecurityException.class) ResponseEntity<?> denied(){
    return ResponseEntity.status(403).body(Map.of("code","ONB_MANAGED_FILE_RECEIPT_INVALID"));
  }
}
