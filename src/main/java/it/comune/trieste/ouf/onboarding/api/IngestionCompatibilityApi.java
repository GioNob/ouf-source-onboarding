package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.onboarding.application.OnboardingService;
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
  private static final String CAPABILITY="ouf.ingestion.configuration.attest";
  private final OnboardingService service;
  private final TrustedActorResolver actors;

  public IngestionCompatibilityApi(OnboardingService service,TrustedActorResolver actors){
    this.service=service;
    this.actors=actors;
  }

  public record Request(@NotBlank String sourceId,UUID onboardingVersionId,boolean compatible,@NotBlank String detail){}

  @PostMapping
  ResponseEntity<Map<String,Object>> attest(@Valid @RequestBody Request body,@RequestHeader HttpHeaders headers,HttpServletRequest request){
    if(body.onboardingVersionId()==null)throw new IllegalArgumentException("onboardingVersionId required");
    var actor=actors.requireService(request,CAPABILITY);
    var out=service.attestIngestionCompatibility(body.sourceId(),body.onboardingVersionId(),body.compatible(),body.detail(),actor,correlation(headers));
    return ResponseEntity.status(HttpStatus.CREATED).body(out);
  }

  private static String correlation(HttpHeaders headers){
    String value=headers.getFirst("X-Correlation-ID");
    return value==null||value.isBlank()?UUID.randomUUID().toString():value;
  }
}
