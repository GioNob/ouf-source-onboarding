package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.onboarding.application.ManagedFileService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/onboarding/v1/managed-files")
public class ManagedFileApi {
  private final ManagedFileService service; public ManagedFileApi(ManagedFileService service){this.service=service;}
  public record RegisterRequest(String sourceId,@NotBlank String stagingRef,@Pattern(regexp="^sha256:[0-9a-f]{64}$") String contentHash,@NotBlank String mediaType,@Positive long sizeBytes,@NotBlank String retentionRef){}
  public record OnboardRequest(@NotBlank String sourceId,@NotBlank String name,@NotBlank String owner,@NotBlank String targetClassIri,@NotEmpty List<String> semanticRefs){}
  public record ClaimRequest(@NotBlank String worker,@Min(10) @Max(3600) int leaseSeconds){}
  public record HeartbeatRequest(@NotBlank String worker,@Min(10) @Max(3600) int leaseSeconds){}
  public record CompleteRequest(@NotBlank String worker,@NotBlank String outputRef,@PositiveOrZero long ingestedRows){}
  public record FailRequest(@NotBlank String worker,@NotBlank String errorCode,@NotBlank String detail,@PositiveOrZero long retryAfterSeconds){}
  @PostMapping ResponseEntity<Map<String,Object>> register(@Valid @RequestBody RegisterRequest request,@RequestHeader("X-OUF-Subject") String subject){var out=service.register(request.sourceId(),request.stagingRef(),request.contentHash(),request.mediaType(),request.sizeBytes(),subject,request.retentionRef());return ResponseEntity.created(URI.create("/api/onboarding/v1/managed-files/"+out.get("asset_id"))).body(out);}
  @GetMapping("/{assetId}") Map<String,Object> asset(@PathVariable UUID assetId){return service.asset(assetId);}
  @GetMapping("/{assetId}/profiles/{profileId}") Map<String,Object> profile(@PathVariable UUID assetId,@PathVariable UUID profileId){return service.fileProfile(assetId,profileId);}
  @PostMapping("/{assetId}/profiles/{profileId}/onboard") ResponseEntity<Map<String,Object>> onboard(@PathVariable UUID assetId,@PathVariable UUID profileId,@Valid @RequestBody OnboardRequest request,@RequestHeader HttpHeaders headers){var actor=new it.comune.trieste.ouf.onboarding.application.OnboardingService.Actor(Objects.requireNonNull(headers.getFirst("X-OUF-Subject")),Objects.requireNonNull(headers.getFirst("X-OUF-Actor-Type")));var out=service.onboard(assetId,profileId,request.sourceId(),request.name(),request.owner(),request.targetClassIri(),request.semanticRefs(),actor,Optional.ofNullable(headers.getFirst("X-Correlation-ID")).orElseGet(()->UUID.randomUUID().toString()));return ResponseEntity.status(HttpStatus.CREATED).body(out);}
  @PostMapping("/initial-ingestions/claim") ResponseEntity<Map<String,Object>> claim(@Valid @RequestBody ClaimRequest request){return service.claimIngestion(request.worker(),Duration.ofSeconds(request.leaseSeconds()),Instant.now()).map(ResponseEntity::ok).orElseGet(()->ResponseEntity.noContent().build());}
  @PostMapping("/initial-ingestions/{ingestionId}/heartbeat") Map<String,Object> heartbeat(@PathVariable UUID ingestionId,@Valid @RequestBody HeartbeatRequest request){return service.heartbeatIngestion(ingestionId,request.worker(),Duration.ofSeconds(request.leaseSeconds()),Instant.now());}
  @PostMapping("/initial-ingestions/{ingestionId}/complete") Map<String,Object> complete(@PathVariable UUID ingestionId,@Valid @RequestBody CompleteRequest request){return service.completeIngestion(ingestionId,request.worker(),request.outputRef(),request.ingestedRows());}
  @PostMapping("/initial-ingestions/{ingestionId}/fail") Map<String,Object> fail(@PathVariable UUID ingestionId,@Valid @RequestBody FailRequest request){return service.failIngestion(ingestionId,request.worker(),request.errorCode(),request.detail(),Duration.ofSeconds(request.retryAfterSeconds()));}
  @GetMapping("/initial-ingestions/{ingestionId}") Map<String,Object> ingestion(@PathVariable UUID ingestionId){return service.ingestion(ingestionId);}
}
