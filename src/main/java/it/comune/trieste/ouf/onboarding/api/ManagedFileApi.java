package it.comune.trieste.ouf.onboarding.api;

import com.fasterxml.jackson.databind.JsonNode;
import it.comune.trieste.ouf.onboarding.application.SourceUnitProfiles;
import it.comune.trieste.ouf.onboarding.application.MappingCategory;
import it.comune.trieste.ouf.onboarding.application.ManagedFileExecutionProposal.SemanticBinding;
import it.comune.trieste.ouf.onboarding.application.ManagedFileService;
import it.comune.trieste.ouf.authorization.ServletAuthorization;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/onboarding/v1/managed-files")
public class ManagedFileApi {
  private final ManagedFileService service; private final TrustedActorResolver actors; public ManagedFileApi(ManagedFileService service,TrustedActorResolver actors){this.service=service;this.actors=actors;}
  public record RegisterRequest(String sourceId,@NotBlank String stagingRef,@Pattern(regexp="^sha256:[0-9a-f]{64}$") String contentHash,@NotBlank String mediaType,@Positive long sizeBytes,@NotBlank String retentionRef){}
  public record FieldDecision(@NotBlank String fieldName,@NotBlank String extractionDecision,@NotBlank String dataAccessLabel,String targetPropertyIri,String transform,String vocabularyId,String vocabularyVersion,String valueMapRef,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Map<String,Object> transformParams){
    public FieldDecision(String fieldName,String extractionDecision,String dataAccessLabel,String targetPropertyIri,String transform,String vocabularyId,String vocabularyVersion,String valueMapRef){
      this(fieldName,extractionDecision,dataAccessLabel,targetPropertyIri,transform,vocabularyId,vocabularyVersion,valueMapRef,null);
    }
  }
  public record OnboardRequest(@NotBlank String sourceId,@NotBlank String name,@NotBlank String owner,@NotBlank String targetClassIri,@NotEmpty List<String> semanticRefs,List<String> sourceObjectKeyFields,List<FieldDecision> fields,String layer,JsonNode category,List<Map<String,Object>> compositions,List<SemanticBinding> semanticBindings,SourceUnitProfiles.Reference unitProfileRef){}
  @PostMapping ResponseEntity<Map<String,Object>> register(@Valid @RequestBody RegisterRequest request,HttpServletRequest servletRequest){var actor=actors.requireHuman(servletRequest,"ouf.managed-source.file.upload");var principal=ServletAuthorization.principal(servletRequest).context();if(!actor.subject().equals(principal.subjectId()))throw new SecurityException("Authenticated upload owner differs");var out=service.registerOwned(request.sourceId(),request.stagingRef(),request.contentHash(),request.mediaType(),request.sizeBytes(),actor.subject(),request.retentionRef(),principal.tenantId());return ResponseEntity.created(URI.create("/api/onboarding/v1/managed-files/"+out.get("asset_id"))).body(out);}
  @GetMapping("/{assetId}") public Map<String,Object> asset(@PathVariable UUID assetId,HttpServletRequest request){var actor=actors.requireHuman(request,"ouf.managed-source.preview");service.requireOwner(assetId,actor.subject());var row=service.asset(assetId);return Map.of("assetId",row.get("asset_id"),"status",row.get("status"),"mediaType",row.get("media_type"),"sizeBytes",row.get("size_bytes"));}
  public ResponseEntity<Map<String,Object>> requestProfile(UUID assetId,String idempotencyKey,HttpServletRequest servletRequest){return requestProfile(assetId,idempotencyKey,null,servletRequest);}
  @PostMapping("/{assetId}/profile") public ResponseEntity<Map<String,Object>> requestProfile(@PathVariable UUID assetId,@RequestHeader("Idempotency-Key") String idempotencyKey,@RequestBody(required=false) byte[] body,HttpServletRequest servletRequest){var actor=actors.requireHuman(servletRequest,"ouf.managed-source.file.profile");service.requireOwner(assetId,actor.subject());var out=service.requestProfile(assetId,idempotencyKey,profileOptions(body));return ResponseEntity.accepted().location(URI.create("/api/onboarding/v1/managed-files/"+assetId+"/profile-jobs/"+out.get("job_id"))).body(out);}
  @GetMapping("/{assetId}/profile-jobs/{jobId}") Map<String,Object> profileJob(@PathVariable UUID assetId,@PathVariable UUID jobId,HttpServletRequest request){var actor=actors.requireHuman(request,"ouf.managed-source.preview");service.requireOwner(assetId,actor.subject());return service.profileJob(assetId,jobId);}
  @GetMapping("/{assetId}/profiles/{profileId}") Map<String,Object> profile(@PathVariable UUID assetId,@PathVariable UUID profileId,HttpServletRequest request){var actor=actors.requireHuman(request,"ouf.managed-source.preview");service.requireOwner(assetId,actor.subject());return service.profileWithUnitSummary(assetId,profileId);}
  @GetMapping("/{assetId}/profiles/{profileId}/preview") public Map<String,Object> preview(@PathVariable UUID assetId,@PathVariable UUID profileId,HttpServletRequest request){var actor=actors.requireHuman(request,"ouf.managed-source.preview");service.requireOwner(assetId,actor.subject());return service.preview(assetId,profileId);}
  @GetMapping("/{assetId}/preview")
  public Map<String,Object> unitView(@PathVariable UUID assetId,@RequestParam UUID profileId,@RequestParam String view,
      @RequestParam(required=false) String unitRef,@RequestParam(required=false) Integer offset,
      @RequestParam(required=false) Integer limit,@RequestParam(required=false) String distinctValuesCursor,HttpServletRequest request) {
    var actor=actors.requireHuman(request,"ouf.managed-source.preview");
    var tenant=ServletAuthorization.principal(request).context().tenantId();
    return service.unitView(assetId,profileId,tenant,actor.subject(),view,unitRef,offset,limit,distinctValuesCursor);
  }

  @PostMapping("/{assetId}/create-onboarding") ResponseEntity<Map<String,Object>> onboard(@PathVariable UUID assetId,@RequestParam(required=false) UUID profileId,@Valid @RequestBody OnboardRequest request,@RequestHeader HttpHeaders headers,HttpServletRequest servletRequest){var actor=actors.requireHuman(servletRequest,"ouf.managed-source.onboarding.create");if(request.unitProfileRef()==null)service.requireOwner(assetId,actor.subject());List<ManagedFileService.FieldDecision> fields=request.fields()==null?List.of():request.fields().stream().map(f->new ManagedFileService.FieldDecision(f.fieldName(),f.extractionDecision(),f.dataAccessLabel(),f.targetPropertyIri(),f.transform(),f.vocabularyId(),f.vocabularyVersion(),f.valueMapRef(),f.transformParams())).toList();var out=service.onboard(assetId,profileId,request.sourceId(),request.name(),request.owner(),request.targetClassIri(),request.semanticRefs(),request.sourceObjectKeyFields(),fields,request.layer(),actor,Optional.ofNullable(headers.getFirst("X-Correlation-ID")).orElseGet(()->UUID.randomUUID().toString()),ServletAuthorization.principal(servletRequest).context().tenantId(),MappingCategory.fromRequest(request.category()),request.compositions(),request.semanticBindings(),ServletAuthorization.principal(servletRequest).context(),request.unitProfileRef());return ResponseEntity.status(HttpStatus.CREATED).body(out);}

  /** Optional body {readOptions:{xlsx:{headerRows:{...}}}} (contract ouf.extraction-unit.v1 §8.2). */
  private static Map<String,Integer> profileOptions(byte[] raw){
    // Without a body the command is unchanged, whatever Content-Type a historical client sends.
    if(raw==null||new String(raw,java.nio.charset.StandardCharsets.UTF_8).isBlank())return null;
    com.fasterxml.jackson.databind.JsonNode body;
    try{body=new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);}catch(java.io.IOException e){throw new IllegalArgumentException("Profile options must be JSON");}
    if(body==null||body.isNull()||body.isObject()&&body.isEmpty())return null;
    if(!body.isObject()||body.size()!=1||!body.has("readOptions"))throw new IllegalArgumentException("Only readOptions may be supplied");
    return ManagedFileMcpApi.headerRows(body.get("readOptions"));
  }
}

