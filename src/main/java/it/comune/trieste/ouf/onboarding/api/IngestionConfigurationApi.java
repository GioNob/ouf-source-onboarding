package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.authorization.ServletAuthorization;
import it.comune.trieste.ouf.onboarding.application.ManagedOnboardingReviewService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** One exact approved managed configuration, readable only by an authorized workload. */
@RestController
@RequestMapping("/api/internal/v1/onboarding/ingestion-configuration")
public class IngestionConfigurationApi {
  private final ManagedOnboardingReviewService reviews;
  public IngestionConfigurationApi(ManagedOnboardingReviewService reviews){this.reviews=reviews;}
  @GetMapping public Map<String,Object> read(@RequestParam String sourceId,@RequestParam UUID onboardingVersionId,
      @RequestParam String configurationHash,HttpServletRequest request){
    return reviews.ingestionConfiguration(ServletAuthorization.principal(request).context(),sourceId,onboardingVersionId,configurationHash);
  }
}
