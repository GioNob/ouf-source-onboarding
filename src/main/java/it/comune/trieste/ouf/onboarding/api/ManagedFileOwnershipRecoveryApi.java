package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.authorization.ServletAuthorization;
import it.comune.trieste.ouf.onboarding.application.ManagedFileOwnershipRecovery;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Separate original-uploader HUMAN decision; existing exact-job recovery permissions remain mandatory. */
@RestController
@RequestMapping("/api/onboarding/v1/managed-files/{assetId}/profile-jobs/{jobId}/recovery/ownership")
public class ManagedFileOwnershipRecoveryApi {
  private final ManagedFileOwnershipRecovery ownership;private final TrustedActorResolver actors;
  public ManagedFileOwnershipRecoveryApi(ManagedFileOwnershipRecovery ownership,TrustedActorResolver actors){this.ownership=ownership;this.actors=actors;}
  private void bind(UUID asset,UUID job,HttpServletRequest r){r.setAttribute(ServletAuthorization.RESOURCE,ownership.resource(asset,job,ServletAuthorization.principal(r).context()));}
  @GetMapping public ResponseEntity<Map<String,Object>> card(@PathVariable UUID assetId,@PathVariable UUID jobId,HttpServletRequest r){
    bind(assetId,jobId,r);var actor=actors.requireHuman(r,"ouf.managed-source.file.profile");actors.requireHuman(r,"ouf.onboarding.quarantine.release");
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ownership.preview(assetId,jobId,ServletAuthorization.principal(r).context(),actor));
  }
  @PostMapping public ResponseEntity<Map<String,Object>> decide(@PathVariable UUID assetId,@PathVariable UUID jobId,@RequestBody ManagedFileOwnershipRecovery.Decision d,HttpServletRequest r){
    TrustedWriteProof.require(r);bind(assetId,jobId,r);var actor=actors.requireHuman(r,"ouf.managed-source.file.profile");actors.requireHuman(r,"ouf.onboarding.quarantine.release");
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ownership.reconcile(assetId,jobId,ServletAuthorization.principal(r).context(),actor,d));
  }
}
