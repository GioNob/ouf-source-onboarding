package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.onboarding.application.SourceUnitProfiles;
import it.comune.trieste.ouf.onboarding.application.MappingCategory;
import it.comune.trieste.ouf.onboarding.application.ManagedFileExecutionProposal.SemanticBinding;
import it.comune.trieste.ouf.authorization.PrincipalContext;
import it.comune.trieste.ouf.onboarding.application.ManagedMcpObservation;
import static it.comune.trieste.ouf.onboarding.application.ManagedMcpObservation.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.application.ManagedFileService;
import it.comune.trieste.ouf.onboarding.application.ManagedFileLimits;
import it.comune.trieste.ouf.onboarding.application.ManagedFileChatHandoff;
import it.comune.trieste.ouf.onboarding.application.OnboardingService;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Owner entry point for an exact Gateway-minted delegated HUMAN receipt. */
@RestController
@RequestMapping("/api/internal/v1/onboarding/managed-file-mcp")
public class ManagedFileMcpApi {
  public record CreateDraft(UUID assetId,UUID profileId,String sourceId,String name,String owner,String targetClassIri,
      List<String> semanticRefs,List<String> sourceObjectKeyFields,List<ManagedFileApi.FieldDecision> fields,String layer,JsonNode category,List<Map<String,Object>> compositions,
      List<SemanticBinding> semanticBindings,SourceUnitProfiles.Reference unitProfileRef){}
  private final PermissionDelegationVerifier receipts;
  private final ManagedFileService files;
  private final ManagedFileChatHandoff handoffs;
  private final ObjectMapper json;
  public ManagedFileMcpApi(PermissionDelegationVerifier receipts,ManagedFileService files,ObjectMapper json,
      ManagedFileChatHandoff handoffs){
    this.receipts=receipts;this.files=files;this.json=json;this.handoffs=handoffs;
  }
  /** readOptions = {xlsx:{headerRows:{"sheet:n":row}}}; anything else is rejected before the owner. */
  static Map<String,Integer> headerRows(JsonNode options){
    if(options==null)return null;
    JsonNode xlsx=options.get("xlsx");
    if(!options.isObject()||options.size()!=1||xlsx==null||!xlsx.isObject()||xlsx.size()!=1||!xlsx.path("headerRows").isObject())
      throw new IllegalArgumentException("invalid managed-file arguments");
    var rows=new java.util.LinkedHashMap<String,Integer>();
    xlsx.get("headerRows").fields().forEachRemaining(e->{
      if(!e.getValue().isInt())throw new IllegalArgumentException("invalid managed-file arguments");
      rows.put(e.getKey(),e.getValue().intValue());});
    return rows;
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
  private PrincipalContext pickerPrincipal(byte[] raw,HttpServletRequest request){
    return receipts.verifyManagedFile(request.getHeader("X-OUF-Managed-File-Receipt"),
        request.getRequestURI(),"ouf.managed-source.file.upload",raw).principal();
  }
  @PostMapping("/handoff-issue") public Map<String,Object> issue(@RequestBody byte[] raw,HttpServletRequest request){
    var principal=pickerPrincipal(raw,request);
    String key=arguments(raw,"commandKey").get("commandKey").asText();
    UUID commandKey=UUID.fromString(key);
    if(!commandKey.toString().equalsIgnoreCase(key))throw new IllegalArgumentException("UUID commandKey required");
    return handoffs.issue(commandKey,principal.tenantId(),principal.subjectId());
  }
  @PostMapping("/handoff") public Map<String,Object> handoff(@RequestBody byte[] raw,HttpServletRequest request){
    var principal=pickerPrincipal(raw,request);
    UUID id=UUID.fromString(arguments(raw,"handoffId").get("handoffId").asText());
    return ManagedMcpObservation.handoff(handoffs.result(id,principal.tenantId(),principal.subjectId()));
  }
  @PostMapping("/profile") public ResponseEntity<Map<String,Object>> profile(@RequestBody byte[] raw,HttpServletRequest request){
    String cap="ouf.managed-source.file.profile";
    var delegated=receipts.verifyManagedFile(request.getHeader("X-OUF-Managed-File-Receipt"),request.getRequestURI(),cap,raw);
    JsonNode args;
    try{args=json.readTree(raw).required("Arguments");}catch(IOException e){throw new IllegalArgumentException("invalid managed-file arguments",e);}
    // Closed arguments: assetId, plus readOptions.xlsx.headerRows (contract ouf.extraction-unit.v1 §8.2).
    if(!args.isObject()||!args.hasNonNull("assetId")||!args.get("assetId").isTextual()
        ||args.size()!=(args.has("readOptions")?2:1))throw new IllegalArgumentException("invalid managed-file arguments");
    UUID asset=UUID.fromString(args.get("assetId").asText());
    files.requireOwner(asset,delegated.principal().subjectId());
    var job=files.requestProfile(asset,delegated.idempotencyKey(),headerRows(args.get("readOptions")));
    return ResponseEntity.accepted().body(ManagedMcpObservation.profile(Map.of("jobId",job.get("jobId"),"status",job.get("status"))));
  }
  @PostMapping("/preview") public Map<String,Object> preview(@RequestBody byte[] raw,HttpServletRequest request){
    var caller=receipts.verifyManagedFile(request.getHeader("X-OUF-Managed-File-Receipt"),request.getRequestURI(),"ouf.managed-source.preview",raw);
    String owner=caller.principal().subjectId();
    String tenant=caller.principal().tenantId();
    try{
      JsonNode selection=json.readTree(raw).required("Arguments");
      String view=selection.path("view").textValue();
      if ("UNITS".equals(view) || "UNIT".equals(view)) {
        var allowed="UNITS".equals(view)?Set.of("view","assetId","profileId","offset","limit")
            :Set.of("view","assetId","profileId","unitRef","offset","limit","distinctValuesCursor");
        for(var names=selection.fieldNames();names.hasNext();)if(!allowed.contains(names.next()))throw new IllegalArgumentException("Invalid unit argument");
        UUID profile=canonicalId(selection.required("profileId"));
        UUID asset=selection.has("assetId")?canonicalId(selection.get("assetId")):null;
        String ref=selection.path("unitRef").textValue(), continuation=selection.path("distinctValuesCursor").textValue();
        if ("UNIT".equals(view) && (ref==null || !ref.matches("(table|sheet|layer|collection):(0|[1-9][0-9]{0,3})"))
            || selection.has("distinctValuesCursor") && (continuation==null || continuation.isEmpty()))
          throw new IllegalArgumentException("Invalid unit selection");
        var page=files.unitView(asset,profile,tenant,owner,view,ref,selection.has("offset")?pageInteger(selection,"offset",0):null,
            selection.has("limit")?pageInteger(selection,"limit",0):null,continuation);
        return ManagedMcpObservation.preview(page,State.PROFILE_COLUMNS,Reason.COLUMN_PAGE);
      }
      boolean columnsView="COLUMNS".equals(selection.path("view").textValue());
      boolean exactProfile=selection.path("assetId").isTextual()&&selection.path("profileId").isTextual();
      String cursor=selection.path("distinctValuesCursor").textValue();
      if(selection.has("distinctValuesCursor")&&(!columnsView||!exactProfile||cursor==null||cursor.isEmpty()||selection.size()!=4))
        throw new DomainFailure(HttpStatus.BAD_REQUEST,"ONB_DISTINCT_VALUES_CURSOR_INVALID","Invalid distinct values cursor query");
      if(columnsView){
        Set<String> allowed=Set.of("view","assetId","profileId","offset","limit","layer","distinctValuesCursor");
        for(var keys=selection.fieldNames();keys.hasNext();)if(!allowed.contains(keys.next()))throw new IllegalArgumentException("invalid column page query");
        if(!exactProfile)throw new IllegalArgumentException("exact asset and profile required");
        String layer=selection.path("layer").textValue();
        if(selection.has("layer")&&(layer==null||layer.isEmpty()))throw new IllegalArgumentException("exact profile layer required");
        int offset=pageInteger(selection,"offset",0),limit=pageInteger(selection,"limit",ManagedFileLimits.COLUMN_PAGE_SIZE);
        UUID asset=UUID.fromString(selection.get("assetId").asText()),profile=UUID.fromString(selection.get("profileId").asText());
        return ManagedMcpObservation.preview(files.previewColumns(asset,profile,tenant,owner,layer,offset,limit,cursor),
            State.PROFILE_COLUMNS,Reason.COLUMN_PAGE);
      }
      if(selection.has("view")){
        if(!selection.path("view").isTextual()||!"ASSETS".equals(selection.path("view").asText())||selection.size()>3)throw new IllegalArgumentException("invalid owner asset query");
        for(var keys=selection.fieldNames();keys.hasNext();)if(!Set.of("view","limit","afterAssetId").contains(keys.next()))throw new IllegalArgumentException("invalid owner asset query");
        int limit=20;
        if(selection.has("limit")){if(!selection.get("limit").isIntegralNumber()||!selection.get("limit").canConvertToInt())throw new IllegalArgumentException("invalid owner asset limit");limit=selection.get("limit").intValue();}
        if(limit<1||limit>50)throw new IllegalArgumentException("invalid owner asset limit");
        UUID after=null;
        if(selection.has("afterAssetId")){
          if(!selection.get("afterAssetId").isTextual())throw new IllegalArgumentException("invalid owner asset cursor");
          after=UUID.fromString(selection.get("afterAssetId").asText());
          if(!after.toString().equals(selection.get("afterAssetId").asText()))throw new IllegalArgumentException("invalid owner asset cursor");
        }
        return with(files.ownedAssets(tenant,owner,limit,after), State.ASSETS, Reason.ASSET_LIST, Action.READ_AGAIN);
      }
    }catch(IOException e){throw new IllegalArgumentException("invalid owner asset query",e);}
    JsonNode args;
    try {args=arguments(raw,"assetId","profileId");}
    catch(IllegalArgumentException ex){args=arguments(raw,"assetId","jobId");}
    UUID asset=UUID.fromString(args.get("assetId").asText());
    files.requireOwner(asset,owner);
    if(args.has("jobId")){
      files.requireTenantOwner(asset,tenant,owner);
      var job=files.profileJob(asset,UUID.fromString(args.get("jobId").asText()));
      Map<String,Object> safe=new LinkedHashMap<>();
      for(String key:new String[]{"jobId","status","resultRef","errorCode"})safe.put(key,job.get(key));
      if(job.get("errorCode")!=null)safe.put("diagnosticCategory",profileFailureCategory(job.get("last_error")));
      if("SUCCEEDED".equals(job.get("status")) && job.get("resultRef") instanceof UUID profileId)
        return ManagedMcpObservation.profile(files.previewProfile(asset,profileId,tenant,owner,safe));
      return ManagedMcpObservation.profile(safe);
    }
    return ManagedMcpObservation.preview(files.previewProfile(asset,UUID.fromString(args.get("profileId").asText()),tenant,owner),
        State.PROFILE_AVAILABLE,Reason.PROFILE_READY);
  }
  private static UUID canonicalId(JsonNode value) {
    if (!value.isTextual()) throw new IllegalArgumentException("Expected UUID");
    UUID id=UUID.fromString(value.textValue());
    if(!id.toString().equals(value.textValue()))throw new IllegalArgumentException("Expected canonical UUID");
    return id;
  }
  private static int pageInteger(JsonNode selection,String key,int fallback){
    if(!selection.has(key))return fallback;
    JsonNode value=selection.get(key);
    if(!value.isIntegralNumber()||!value.canConvertToInt())throw new IllegalArgumentException("invalid column page bounds");
    return value.intValue();
  }
  @PostMapping("/create") public Map<String,Object> create(@RequestBody byte[] raw,HttpServletRequest request){
    if(raw.length>ManagedFileLimits.MAX_MAPPING_ENVELOPE_BYTES)throw new IllegalArgumentException("managed mapping envelope too large");
    String cap=PermissionDelegationVerifier.MANAGED_CREATE_CAPABILITY;
    var delegated=receipts.verifyManagedFile(request.getHeader("X-OUF-Managed-File-Receipt"),request.getRequestURI(),cap,raw);
    try {
      JsonNode envelope=json.readTree(raw),args=envelope.required("Arguments");
      Set<String> allowed=Set.of("assetId","profileId","sourceId","name","owner","targetClassIri","semanticRefs","sourceObjectKeyFields","fields","layer","category","compositions","semanticBindings","unitProfileRef");
      if(!args.isObject()||args.size()>allowed.size())throw new IllegalArgumentException("invalid draft arguments");
      for(var names=args.fieldNames();names.hasNext();)if(!allowed.contains(names.next()))throw new IllegalArgumentException("invalid draft arguments");
      if(args.has("unitProfileRef")&&!args.get("unitProfileRef").isObject())throw new IllegalArgumentException("Invalid unit profile reference");
      CreateDraft draft=json.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).treeToValue(args,CreateDraft.class);
      if(draft.unitProfileRef()==null&&(draft.assetId()==null||draft.profileId()==null)||draft.sourceId()==null||draft.sourceId().isBlank()
          ||draft.name()==null||draft.name().isBlank()||draft.owner()==null||draft.owner().isBlank()
          ||draft.targetClassIri()==null||draft.targetClassIri().isBlank()
          ||draft.semanticRefs()==null||draft.semanticRefs().isEmpty()
          ||draft.sourceObjectKeyFields()==null||draft.fields()==null||draft.fields().isEmpty()||draft.fields().size()>ManagedFileLimits.MAX_COLUMNS)
          throw new IllegalArgumentException("explicit field and identity decisions required");
      var actor=new OnboardingService.Actor(delegated.principal().subjectId(),"HUMAN_USER",Set.of(cap));
      List<ManagedFileService.FieldDecision> fields=draft.fields().stream()
          .map(f->new ManagedFileService.FieldDecision(f.fieldName(),f.extractionDecision(),f.dataAccessLabel(),f.targetPropertyIri(),f.transform(),f.vocabularyId(),f.vocabularyVersion(),f.valueMapRef(),f.transformParams())).toList();
      return ManagedMcpObservation.created(files.onboardIdempotent(draft.assetId(),draft.profileId(),draft.sourceId(),draft.name(),draft.owner(),draft.targetClassIri(),
          draft.semanticRefs(),draft.sourceObjectKeyFields(),fields,draft.layer(),actor,envelope.required("CorrelationID").asText(),delegated.idempotencyKey(),delegated.principal().tenantId(),MappingCategory.fromRequest(draft.category()),draft.compositions(),draft.semanticBindings(),delegated.principal(),draft.unitProfileRef()));
    }catch(IOException e){throw new IllegalArgumentException("invalid draft arguments",e);}
  }

  /** Fixed categories over retained owner evidence; never release exception text or guess unknowns. */
  static String profileFailureCategory(Object retained) {
    if(!(retained instanceof String detail)||detail.length()>512)return "UNCLASSIFIED";
    return switch(detail) {
      case "CSV must be valid UTF-8 text","CSV must be UTF-8 text" -> "CSV_ENCODING_INVALID";
      case "headers must be non-empty and unique" -> "CSV_HEADERS_INVALID";
      case "invalid CSV","CSV row exceeds declared headers" -> "CSV_SYNTAX_INVALID";
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
