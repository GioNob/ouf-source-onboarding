package it.comune.trieste.ouf.onboarding.application;

import java.time.Duration;
import java.util.Set;

/** Fail closed on permanent and unknown failures. Existing finite job budget is not renewed. */
public final class ManagedProfileFailurePolicy {
  private ManagedProfileFailurePolicy(){}
  public record Decision(boolean retryable,String retainedDetail,Duration delay){}
  private static final Set<String> SAFE_INPUT_DETAILS=Set.of(
      "stagingRef must use object://","expected object size is outside intake limits",
      "Gateway object exceeds the registered size","Staged size does not match downloaded content",
      "Staged content hash does not match downloaded content","Onboarding workload token is invalid",
      "Onboarding workload token is unavailable","CSV must be valid UTF-8 text","CSV must be UTF-8 text",
      "headers must be non-empty and unique","invalid CSV","row limit exceeded","column limit exceeded",
      "file size outside profiling limits","unsupported media type");
  public static Decision classify(Exception failure,Duration legacyDelay){
    if(legacyDelay==null||legacyDelay.isNegative())throw new IllegalArgumentException("ONB_RETRY_DELAY_INVALID");
    if(failure instanceof ManagedFileObjectReadFailure read){
      return new Decision(read.retryable(),read.getMessage(),read.retryable()&&read.retryAfter().compareTo(legacyDelay)>0?read.retryAfter():legacyDelay);
    }
    // A parser/hash/credential failure is not transient. Neither is an ambiguous persistence result.
    String detail=SAFE_INPUT_DETAILS.contains(String.valueOf(failure.getMessage()))?failure.getMessage():"Profile failure requires owner reconciliation";
    return new Decision(false,detail,Duration.ZERO);
  }
}
