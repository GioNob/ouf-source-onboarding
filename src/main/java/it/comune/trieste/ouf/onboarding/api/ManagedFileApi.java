package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.onboarding.application.ManagedFileService;
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
  public record FieldDecision(@NotBlank String fieldName,@NotBlank String extractionDecision,@NotBlank String dataAccessLabel,String targetPropertyIri,String transform,String vocabularyId,String vocabularyVersion,String valueMapRef){}
  public record OnboardRequest(@NotBlank String sourceId,@NotBlank String name,@NotBlank String owner,@NotBlank String targetClassIri,@NotEmpty List<String> semanticRefs,List<String> sourceObjectKeyFields,List<FieldDecision> fields,String layer){}
  @PostMapping ResponseEntity<Map<String,Object>> register(@Valid @RequestBody RegisterRequest request,HttpServletRequest servletRequest){var actor=actors.requireHuman(servletRequest,"ouf.managed-source.file.upload");var out=service.register(request.sourceId(),request.stagingRef(),request.contentHash(),request.mediaType(),request.sizeBytes(),actor.subject(),request.retentionRef());return ResponseEntity.created(URI.create("/api/onboarding/v1/managed-files/"+out.get("asset_id"))).body(out);}
  @GetMapping("/{assetId}") Map<String,Object> asset(@PathVariable UUID assetId,HttpServletRequest request){actors.requireHuman(request,"ouf.managed-source.preview");return service.asset(assetId);}
  @PostMapping("/{assetId}/profile") public ResponseEntity<Map<String,Object>> requestProfile(@PathVariable UUID assetId,@RequestHeader("Idempotency-Key") String idempotencyKey,HttpServletRequest servletRequest){actors.requireHuman(servletRequest,"ouf.managed-source.file.profile");var out=service.requestProfile(assetId,idempotencyKey);return ResponseEntity.accepted().location(URI.create("/api/onboarding/v1/managed-files/"+assetId+"/profile-jobs/"+out.get("job_id"))).body(out);}
  @GetMapping("/{assetId}/profile-jobs/{jobId}") Map<String,Object> profileJob(@PathVariable UUID assetId,@PathVariable UUID jobId,HttpServletRequest request){actors.requireHuman(request,"ouf.managed-source.preview");return service.profileJob(assetId,jobId);}
  @GetMapping("/{assetId}/profiles/{profileId}") Map<String,Object> profile(@PathVariable UUID assetId,@PathVariable UUID profileId,HttpServletRequest request){actors.requireHuman(request,"ouf.managed-source.preview");return service.fileProfile(assetId,profileId);}
  @GetMapping("/{assetId}/profiles/{profileId}/preview") public Map<String,Object> preview(@PathVariable UUID assetId,@PathVariable UUID profileId,HttpServletRequest request){actors.requireHuman(request,"ouf.managed-source.preview");return service.preview(assetId,profileId);}
  @PostMapping("/{assetId}/create-onboarding") ResponseEntity<Map<String,Object>> onboard(@PathVariable UUID assetId,@RequestParam UUID profileId,@Valid @RequestBody OnboardRequest request,@RequestHeader HttpHeaders headers,HttpServletRequest servletRequest){List<ManagedFileService.FieldDecision> fields=request.fields()==null?List.of():request.fields().stream().map(f->new ManagedFileService.FieldDecision(f.fieldName(),f.extractionDecision(),f.dataAccessLabel(),f.targetPropertyIri(),f.transform(),f.vocabularyId(),f.vocabularyVersion(),f.valueMapRef())).toList();var out=service.onboard(assetId,profileId,request.sourceId(),request.name(),request.owner(),request.targetClassIri(),request.semanticRefs(),request.sourceObjectKeyFields(),fields,request.layer(),actors.requireHuman(servletRequest,"ouf.managed-source.onboarding.create"),Optional.ofNullable(headers.getFirst("X-Correlation-ID")).orElseGet(()->UUID.randomUUID().toString()));return ResponseEntity.status(HttpStatus.CREATED).body(out);}
}
