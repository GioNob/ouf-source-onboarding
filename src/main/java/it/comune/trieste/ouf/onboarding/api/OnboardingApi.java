package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.onboarding.application.OnboardingService;
import it.comune.trieste.ouf.onboarding.application.RuntimeProjectionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/onboarding/v1")
public class OnboardingApi {
  private static final String CONFIG_WRITE="ouf.onboarding.configuration.write";
  private static final String INGESTION_ATTEST="ouf.ingestion.configuration.attest";
  private final OnboardingService service; private final RuntimeProjectionService projections; private final TrustedActorResolver actors; public OnboardingApi(OnboardingService service,RuntimeProjectionService projections,TrustedActorResolver actors){this.service=service;this.projections=projections;this.actors=actors;}
  public record SourceRequest(@NotBlank String sourceId,@NotBlank String name,@NotBlank String sourceKind,@NotBlank String acquisitionMode,@NotBlank String owner,Map<String,Object> metadata){}
  public record VersionRequest(Map<String,Object> configuration){}
  public record SourcePatch(String name,String owner,String status,Map<String,Object> metadata){}
  public record CompatibilityRequest(boolean compatible,@NotBlank String detail){}

  @PostMapping("/sources") ResponseEntity<Map<String,Object>> createSource(@Valid @RequestBody SourceRequest r,@RequestHeader HttpHeaders h,HttpServletRequest request){var out=service.createSource(r.sourceId(),r.name(),r.sourceKind(),r.acquisitionMode(),r.owner(),r.metadata(),actors.requireHuman(request,CONFIG_WRITE),correlation(h));return ResponseEntity.created(URI.create("/api/onboarding/v1/sources/"+r.sourceId())).eTag(etag("source",out.get("lock_version"))).body(out);}
  @GetMapping("/sources") List<Map<String,Object>> sources(@RequestParam(required=false) String after,@RequestParam(defaultValue="50") @Min(1) @Max(200) int limit){return service.sources(after,limit);}
  @GetMapping("/sources/{sourceId}") ResponseEntity<Map<String,Object>> source(@PathVariable String sourceId){var out=service.source(sourceId);return ResponseEntity.ok().eTag(etag("source",out.get("lock_version"))).body(out);}
  @PatchMapping("/sources/{sourceId}") ResponseEntity<Map<String,Object>> source(@PathVariable String sourceId,@RequestBody SourcePatch r,@RequestHeader(HttpHeaders.IF_MATCH) String match,@RequestHeader HttpHeaders h,HttpServletRequest request){var out=service.updateSource(sourceId,sourceLock(match),r.name(),r.owner(),r.status(),r.metadata(),actors.requireHuman(request,CONFIG_WRITE),correlation(h));return ResponseEntity.ok().eTag(etag("source",out.get("lock_version"))).body(out);}
  @PostMapping("/sources/{sourceId}/onboarding-versions") ResponseEntity<Map<String,Object>> createVersion(@PathVariable String sourceId,@RequestBody VersionRequest r,@RequestHeader HttpHeaders h,HttpServletRequest request){var out=service.createVersion(sourceId,r.configuration()==null?Map.of():r.configuration(),actors.requireHuman(request,CONFIG_WRITE),correlation(h));return ResponseEntity.created(URI.create("/api/onboarding/v1/sources/"+sourceId+"/onboarding-versions/"+out.get("onboarding_version_id"))).eTag(etag("ov",out.get("lock_version"))).body(out);}
  @GetMapping("/sources/{sourceId}/onboarding-versions/{versionId}") ResponseEntity<Map<String,Object>> version(@PathVariable String sourceId,@PathVariable UUID versionId){var out=service.version(sourceId,versionId);return ResponseEntity.ok().eTag(etag("ov",out.get("lock_version"))).body(out);}
  @PostMapping("/sources/{sourceId}/onboarding-versions/{versionId}/clone") ResponseEntity<Map<String,Object>> clone(@PathVariable String sourceId,@PathVariable UUID versionId,@RequestHeader HttpHeaders h,HttpServletRequest request){var out=service.cloneVersion(sourceId,versionId,actors.requireHuman(request,CONFIG_WRITE),correlation(h));return ResponseEntity.created(URI.create("/api/onboarding/v1/sources/"+sourceId+"/onboarding-versions/"+out.get("onboarding_version_id"))).eTag(etag("ov",out.get("lock_version"))).body(out);}
  @GetMapping("/sources/{sourceId}/onboarding-versions/{versionId}/diff") Map<String,Object> diff(@PathVariable String sourceId,@PathVariable UUID versionId){return service.diff(sourceId,versionId);}
  @PostMapping("/sources/{sourceId}/onboarding-versions/{versionId}/extraction-profile/build") Map<String,Object> buildExtraction(@PathVariable String sourceId,@PathVariable UUID versionId){return service.buildExtractionProfile(sourceId,versionId);}
  @PutMapping("/sources/{sourceId}/onboarding-versions/{versionId}") ResponseEntity<Map<String,Object>> patch(@PathVariable String sourceId,@PathVariable UUID versionId,@RequestBody VersionRequest r,@RequestHeader(HttpHeaders.IF_MATCH) String match,@RequestHeader HttpHeaders h,HttpServletRequest request){var out=service.patchVersion(sourceId,versionId,lock(match),r.configuration(),actors.requireHuman(request,CONFIG_WRITE),correlation(h));return ResponseEntity.ok().eTag(etag("ov",out.get("lock_version"))).body(out);}
  @PostMapping("/sources/{sourceId}/onboarding-versions/{versionId}/validate") Map<String,Object> validate(@PathVariable String sourceId,@PathVariable UUID versionId,@RequestHeader HttpHeaders h,HttpServletRequest request){return service.validate(sourceId,versionId,actors.requireHuman(request,CONFIG_WRITE),correlation(h));}
  @PostMapping("/sources/{sourceId}/onboarding-versions/{versionId}/submit") ResponseEntity<Map<String,Object>> submit(@PathVariable String sourceId,@PathVariable UUID versionId,@RequestHeader(HttpHeaders.IF_MATCH) String match,@RequestHeader HttpHeaders h,HttpServletRequest request){var out=service.submit(sourceId,versionId,lock(match),actors.requireHuman(request,CONFIG_WRITE),correlation(h));return ResponseEntity.ok().eTag(etag("ov",out.get("lock_version"))).body(out);}
  @PostMapping("/sources/{sourceId}/onboarding-versions/{versionId}/approval-challenges") ResponseEntity<Map<String,Object>> challenge(@PathVariable String sourceId,@PathVariable UUID versionId,@RequestHeader HttpHeaders h,HttpServletRequest request){return ResponseEntity.status(HttpStatus.CREATED).body(service.createChallenge(sourceId,versionId,actors.requireHuman(request,CONFIG_WRITE),correlation(h)));}
  @PostMapping("/sources/{sourceId}/onboarding-versions/{versionId}/approval-challenges/{challengeId}/confirm") ResponseEntity<Map<String,Object>> confirm(@PathVariable String sourceId,@PathVariable UUID versionId,@PathVariable UUID challengeId,@RequestHeader HttpHeaders h,HttpServletRequest request){var out=service.confirm(sourceId,versionId,challengeId,actors.requireHuman(request,CONFIG_WRITE),correlation(h),actors.authenticationContextRef(request));return ResponseEntity.ok().eTag(etag("ov",out.get("lock_version"))).body(out);}
  @PostMapping("/sources/{sourceId}/onboarding-versions/{versionId}/activate") Map<String,Object> activate(@PathVariable String sourceId,@PathVariable UUID versionId,@RequestHeader HttpHeaders h,HttpServletRequest request){return service.activate(sourceId,versionId,actors.requireHuman(request,CONFIG_WRITE),correlation(h));}
  @PostMapping("/sources/{sourceId}/onboarding-versions/{versionId}/compatibility/ingestion-runtime") ResponseEntity<Map<String,Object>> attestCompatibility(@PathVariable String sourceId,@PathVariable UUID versionId,@Valid @RequestBody CompatibilityRequest body,@RequestHeader HttpHeaders h,HttpServletRequest request){return ResponseEntity.status(HttpStatus.CREATED).body(service.attestIngestionCompatibility(sourceId,versionId,body.compatible(),body.detail(),actors.requireService(request,INGESTION_ATTEST),correlation(h)));}
  @GetMapping("/runtime/sources/{sourceId}/active-bundle") Map<String,Object> active(@PathVariable String sourceId){return service.activeBundle(sourceId);}
  @GetMapping("/runtime/sources/{sourceId}/bundle-history") List<Map<String,Object>> history(@PathVariable String sourceId){return service.bundleHistory(sourceId);}
  @GetMapping("/runtime/sources/{sourceId}/bundles/{versionId}") Map<String,Object> historical(@PathVariable String sourceId,@PathVariable UUID versionId){return service.historicalBundle(sourceId,versionId);}
  @GetMapping("/runtime/sources/{sourceId}/publications/{publicationId}/gateway-projections") List<Map<String,Object>> projections(@PathVariable String sourceId,@PathVariable UUID publicationId){return projections.projections(sourceId,publicationId);}

  private static String correlation(HttpHeaders h){return Optional.ofNullable(h.getFirst("X-Correlation-ID")).filter(x->!x.isBlank()).orElseGet(()->UUID.randomUUID().toString());}
  private static String etag(String kind,Object lock){return "W/\""+kind+":"+lock+"\"";}
  private static long lock(String value){try{return Long.parseLong(value.replace("W/\"ov:","").replace("\"",""));}catch(Exception e){throw new IllegalArgumentException("invalid If-Match");}}
  private static long sourceLock(String value){try{return Long.parseLong(value.replace("W/\"source:","").replace("\"",""));}catch(Exception e){throw new IllegalArgumentException("invalid If-Match");}}
}
