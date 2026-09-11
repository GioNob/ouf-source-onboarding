package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.onboarding.application.ManagedFileService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/onboarding/v1/managed-files")
public class ManagedFileApi {
  private final ManagedFileService service; public ManagedFileApi(ManagedFileService service){this.service=service;}
  public record RegisterRequest(String sourceId,@NotBlank String stagingRef,@Pattern(regexp="^sha256:[0-9a-f]{64}$") String contentHash,@NotBlank String mediaType,@Positive long sizeBytes,@NotBlank String retentionRef){}
  @PostMapping ResponseEntity<Map<String,Object>> register(@Valid @RequestBody RegisterRequest request,@RequestHeader("X-OUF-Subject") String subject){var out=service.register(request.sourceId(),request.stagingRef(),request.contentHash(),request.mediaType(),request.sizeBytes(),subject,request.retentionRef());return ResponseEntity.created(URI.create("/api/onboarding/v1/managed-files/"+out.get("asset_id"))).body(out);}
  @GetMapping("/{assetId}") Map<String,Object> asset(@PathVariable UUID assetId){return service.asset(assetId);}
  @GetMapping("/{assetId}/profiles/{profileId}") Map<String,Object> profile(@PathVariable UUID assetId,@PathVariable UUID profileId){return service.fileProfile(assetId,profileId);}
}
