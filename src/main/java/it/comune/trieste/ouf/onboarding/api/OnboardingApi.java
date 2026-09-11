package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.onboarding.application.OnboardingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/onboarding/v1")
public class OnboardingApi {
  private final OnboardingService service; public OnboardingApi(OnboardingService service){this.service=service;}
  public record SourceRequest(@NotBlank String sourceId,@NotBlank String name,@NotBlank String sourceKind,@NotBlank String acquisitionMode,@NotBlank String owner,Map<String,Object> metadata){}
  public record VersionRequest(Map<String,Object> configuration){}

  @PostMapping("/sources") ResponseEntity<Map<String,Object>> createSource(@Valid @RequestBody SourceRequest r,@RequestHeader HttpHeaders h){var out=service.createSource(r.sourceId(),r.name(),r.sourceKind(),r.acquisitionMode(),r.owner(),r.metadata(),actor(h),correlation(h));return ResponseEntity.created(URI.create("/api/onboarding/v1/sources/"+r.sourceId())).eTag(etag("source",out.get("lock_version"))).body(out);}
  @GetMapping("/sources") List<Map<String,Object>> sources(){return service.sources();}
  @GetMapping("/sources/{sourceId}") ResponseEntity<Map<String,Object>> source(@PathVariable String sourceId){var out=service.source(sourceId);return ResponseEntity.ok().eTag(etag("source",out.get("lock_version"))).body(out);}
  @PostMapping("/sources/{sourceId}/onboarding-versions") ResponseEntity<Map<String,Object>> createVersion(@PathVariable String sourceId,@RequestBody VersionRequest r,@RequestHeader HttpHeaders h){var out=service.createVersion(sourceId,r.configuration()==null?Map.of():r.configuration(),actor(h),correlation(h));return ResponseEntity.created(URI.create("/api/onboarding/v1/sources/"+sourceId+"/onboarding-versions/"+out.get("onboarding_version_id"))).eTag(etag("ov",out.get("lock_version"))).body(out);}
  @GetMapping("/sources/{sourceId}/onboarding-versions/{versionId}") ResponseEntity<Map<String,Object>> version(@PathVariable String sourceId,@PathVariable UUID versionId){var out=service.version(sourceId,versionId);return ResponseEntity.ok().eTag(etag("ov",out.get("lock_version"))).body(out);}
  @PutMapping("/sources/{sourceId}/onboarding-versions/{versionId}") ResponseEntity<Map<String,Object>> patch(@PathVariable String sourceId,@PathVariable UUID versionId,@RequestBody VersionRequest r,@RequestHeader(HttpHeaders.IF_MATCH) String match,@RequestHeader HttpHeaders h){var out=service.patchVersion(sourceId,versionId,lock(match),r.configuration(),actor(h),correlation(h));return ResponseEntity.ok().eTag(etag("ov",out.get("lock_version"))).body(out);}
  @PostMapping("/sources/{sourceId}/onboarding-versions/{versionId}/validate") Map<String,Object> validate(@PathVariable String sourceId,@PathVariable UUID versionId,@RequestHeader HttpHeaders h){return service.validate(sourceId,versionId,actor(h),correlation(h));}
  @PostMapping("/sources/{sourceId}/onboarding-versions/{versionId}/submit") ResponseEntity<Map<String,Object>> submit(@PathVariable String sourceId,@PathVariable UUID versionId,@RequestHeader(HttpHeaders.IF_MATCH) String match,@RequestHeader HttpHeaders h){var out=service.submit(sourceId,versionId,lock(match),actor(h),correlation(h));return ResponseEntity.ok().eTag(etag("ov",out.get("lock_version"))).body(out);}
  @PostMapping("/sources/{sourceId}/onboarding-versions/{versionId}/approval-challenges") ResponseEntity<Map<String,Object>> challenge(@PathVariable String sourceId,@PathVariable UUID versionId,@RequestHeader HttpHeaders h){return ResponseEntity.status(HttpStatus.CREATED).body(service.createChallenge(sourceId,versionId,actor(h),correlation(h)));}
  @PostMapping("/sources/{sourceId}/onboarding-versions/{versionId}/approval-challenges/{challengeId}/confirm") ResponseEntity<Map<String,Object>> confirm(@PathVariable String sourceId,@PathVariable UUID versionId,@PathVariable UUID challengeId,@RequestHeader HttpHeaders h){var out=service.confirm(sourceId,versionId,challengeId,actor(h),correlation(h),required(h,"X-OUF-Authentication-Context-Ref"));return ResponseEntity.ok().eTag(etag("ov",out.get("lock_version"))).body(out);}
  @PostMapping("/sources/{sourceId}/onboarding-versions/{versionId}/activate") Map<String,Object> activate(@PathVariable String sourceId,@PathVariable UUID versionId,@RequestHeader HttpHeaders h){return service.activate(sourceId,versionId,actor(h),correlation(h));}
  @GetMapping("/runtime/sources/{sourceId}/active-bundle") Map<String,Object> active(@PathVariable String sourceId){return service.activeBundle(sourceId);}

  private static OnboardingService.Actor actor(HttpHeaders h){return new OnboardingService.Actor(required(h,"X-OUF-Subject"),required(h,"X-OUF-Actor-Type"));}
  private static String correlation(HttpHeaders h){return Optional.ofNullable(h.getFirst("X-Correlation-ID")).filter(x->!x.isBlank()).orElseGet(()->UUID.randomUUID().toString());}
  private static String required(HttpHeaders h,String name){String v=h.getFirst(name);if(v==null||v.isBlank())throw new IllegalArgumentException(name+" required");return v;}
  private static String etag(String kind,Object lock){return "W/\""+kind+":"+lock+"\"";}
  private static long lock(String value){try{return Long.parseLong(value.replace("W/\"ov:","").replace("\"",""));}catch(Exception e){throw new IllegalArgumentException("invalid If-Match");}}
}
