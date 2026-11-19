package it.comune.trieste.ouf.onboarding.api;

import com.fasterxml.jackson.databind.*;
import it.comune.trieste.ouf.onboarding.application.ManagedOnboardingReviewService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/internal/v1/onboarding/managed-file-mcp")
public class ManagedOnboardingReviewApi {
  private final PermissionDelegationVerifier receipts;
  private final ManagedOnboardingReviewService reviews;
  private final ObjectMapper json;
  public record Read(String sourceId,UUID onboardingVersionId){}
  public ManagedOnboardingReviewApi(PermissionDelegationVerifier receipts,ManagedOnboardingReviewService reviews,ObjectMapper json){
    this.receipts=receipts;this.reviews=reviews;this.json=json.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
  }
  private <T>T arguments(byte[] raw,Class<T> type){
    try{var args=json.readTree(raw).required("Arguments");if(!args.isObject()||!args.path("sourceId").isTextual()||!args.path("onboardingVersionId").isTextual())throw new IllegalArgumentException("ONB_REVIEW_ARGUMENTS_REQUIRED");
      if(type==ManagedOnboardingReviewService.Prepare.class&&(!args.path("commandKey").isTextual()||!args.path("expectedVersion").isIntegralNumber()||!args.path("expectedVersion").canConvertToLong()))throw new IllegalArgumentException("ONB_REVIEW_ARGUMENTS_INVALID");
      return json.treeToValue(args,type);}
    catch(java.io.IOException e){throw new IllegalArgumentException("ONB_REVIEW_ARGUMENTS_INVALID",e);}
  }
  @PostMapping("/review-prepare") public Map<String,Object> prepare(@RequestBody byte[] raw,HttpServletRequest request){
    var caller=receipts.verifyManagedFile(request.getHeader("X-OUF-Managed-File-Receipt"),request.getRequestURI(),ManagedOnboardingReviewService.PREPARE,raw);
    var command=arguments(raw,ManagedOnboardingReviewService.Prepare.class);
    return reviews.prepare(caller.principal(),command,UUID.randomUUID().toString());
  }
  @PostMapping("/review-read") public Map<String,Object> read(@RequestBody byte[] raw,HttpServletRequest request){
    var caller=receipts.verifyManagedFile(request.getHeader("X-OUF-Managed-File-Receipt"),request.getRequestURI(),ManagedOnboardingReviewService.READ,raw);
    try{
      var args=json.readTree(raw).required("Arguments");
      if(args.has("view")){
        if(!args.isObject()||args.size()!=2||!args.path("sourceId").isTextual()||!args.path("view").isTextual()
            ||!"LATEST".equals(args.path("view").asText()))throw new IllegalArgumentException("ONB_REVIEW_ARGUMENTS_INVALID");
        return reviews.readLatest(caller.principal(),args.path("sourceId").asText());
      }
    }catch(java.io.IOException e){throw new IllegalArgumentException("ONB_REVIEW_ARGUMENTS_INVALID",e);}
    var command=arguments(raw,Read.class);return reviews.read(caller.principal(),command.sourceId(),command.onboardingVersionId());
  }
  @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> invalid(){return ResponseEntity.unprocessableEntity().body(Map.of("code","ONB_REVIEW_ARGUMENTS_INVALID"));}
  @ExceptionHandler(SecurityException.class) ResponseEntity<?> denied(){return ResponseEntity.status(403).body(Map.of("code","ONB_MANAGED_FILE_RECEIPT_INVALID"));}
}
