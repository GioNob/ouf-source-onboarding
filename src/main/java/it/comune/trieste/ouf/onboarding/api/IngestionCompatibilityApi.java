package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.onboarding.application.ManagedOnboardingReviewService;
import it.comune.trieste.ouf.authorization.ServletAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/internal/v1/onboarding/compatibility/ingestion-runtime")
public class IngestionCompatibilityApi {
  private final ManagedOnboardingReviewService reviews;

  public IngestionCompatibilityApi(ManagedOnboardingReviewService reviews){
    this.reviews=reviews;
  }

  public record Request(@NotBlank String sourceId,UUID onboardingVersionId,@NotBlank String configurationHash,boolean compatible,@NotBlank String detail){}

  @PostMapping
  ResponseEntity<Map<String,Object>> attest(@Valid @RequestBody Request body,@RequestHeader HttpHeaders headers,HttpServletRequest request){
    if(body.onboardingVersionId()==null)throw new IllegalArgumentException("onboardingVersionId required");
    var out=reviews.ingestionCompatibilityAttest(ServletAuthorization.principal(request).context(),body.sourceId(),body.onboardingVersionId(),body.configurationHash(),body.compatible(),body.detail(),correlation(headers));
    return ResponseEntity.status(HttpStatus.CREATED).body(out);
  }

  @GetMapping public Map<String,Object> read(@RequestParam String sourceId,@RequestParam UUID onboardingVersionId,@RequestParam String configurationHash,HttpServletRequest request){
    return reviews.ingestionCompatibilityRead(ServletAuthorization.principal(request).context(),sourceId,onboardingVersionId,configurationHash);
  }

  private static String correlation(HttpHeaders headers){
    String value=headers.getFirst("X-Correlation-ID");
    return value==null||value.isBlank()?UUID.randomUUID().toString():value;
  }
}
