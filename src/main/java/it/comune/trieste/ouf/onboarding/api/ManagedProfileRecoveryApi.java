package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.authorization.ServletAuthorization;
import it.comune.trieste.ouf.onboarding.application.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Native HUMAN boundary. No MCP receipt or service identity can grant profiling budgets. */
@RestController
@RequestMapping("/api/onboarding/v1/managed-files/{assetId}/profile-jobs/{jobId}/recovery")
public class ManagedProfileRecoveryApi {
  private final ManagedFileService files;private final TrustedActorResolver actors;
  public ManagedProfileRecoveryApi(ManagedFileService files,TrustedActorResolver actors){this.files=files;this.actors=actors;}
  private void bindResource(UUID assetId,UUID jobId,HttpServletRequest r){
    // The caller cannot select a label or substitute a resource through headers/body.
    var p=ServletAuthorization.principal(r).context();
    r.setAttribute(ServletAuthorization.RESOURCE,files.profileRecoveryResource(assetId,jobId,p));
  }
  @GetMapping public ResponseEntity<Map<String,Object>> card(@PathVariable UUID assetId,@PathVariable UUID jobId,HttpServletRequest r){
    bindResource(assetId,jobId,r);var actor=actors.requireHuman(r,"ouf.managed-source.file.profile");
    return result(files.profileRecoveryCard(assetId,jobId,ServletAuthorization.principal(r).context(),actor));
  }
  @PostMapping("/budget") public ResponseEntity<Map<String,Object>> budget(@PathVariable UUID assetId,@PathVariable UUID jobId,@RequestBody ManagedProfileJobs.BudgetDecision d,HttpServletRequest r){
    TrustedWriteProof.require(r);bindResource(assetId,jobId,r);var actor=actors.requireHuman(r,"ouf.managed-source.file.profile");actors.requireHuman(r,"ouf.onboarding.quarantine.release");
    return result(files.grantProfileBudget(assetId,jobId,ServletAuthorization.principal(r).context(),actor,d));
  }
  @PostMapping("/resume") public ResponseEntity<Map<String,Object>> resume(@PathVariable UUID assetId,@PathVariable UUID jobId,@RequestBody ManagedProfileJobs.Resume d,HttpServletRequest r){
    TrustedWriteProof.require(r);bindResource(assetId,jobId,r);var actor=actors.requireHuman(r,"ouf.managed-source.file.profile");actors.requireHuman(r,"ouf.onboarding.quarantine.release");
    return result(files.resumeProfile(assetId,jobId,ServletAuthorization.principal(r).context(),actor,d));
  }
  @PostMapping("/cancel") public ResponseEntity<Map<String,Object>> cancel(@PathVariable UUID assetId,@PathVariable UUID jobId,@RequestBody ManagedProfileJobs.Cancel d,HttpServletRequest r){
    TrustedWriteProof.require(r);bindResource(assetId,jobId,r);var actor=actors.requireHuman(r,"ouf.managed-source.file.profile");actors.requireHuman(r,"ouf.onboarding.quarantine.release");
    return result(files.cancelProfile(assetId,jobId,ServletAuthorization.principal(r).context(),actor,d));
  }
  private static ResponseEntity<Map<String,Object>> result(Map<String,Object> value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
}
