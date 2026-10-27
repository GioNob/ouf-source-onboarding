package it.comune.trieste.ouf.onboarding.application;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/** Typed evidence from the read-only Gateway call, never inferred from arbitrary exception text. */
public final class ManagedFileObjectReadFailure extends IllegalStateException {
  private final boolean retryable;
  private final Duration retryAfter;
  private ManagedFileObjectReadFailure(String message,boolean retryable,Duration retryAfter){
    super(message);this.retryable=retryable;this.retryAfter=retryAfter;
  }
  public boolean retryable(){return retryable;}
  public Duration retryAfter(){return retryAfter;}
  public static ManagedFileObjectReadFailure http(int status,String header,Instant now){
    boolean retry=Set.of(429,502,503,504).contains(status);
    Duration after=Duration.ZERO;
    if(retry&&header!=null){
      try{
        if(header.length()>128)throw new IllegalArgumentException();
        after=header.matches("[0-9]{1,6}")?Duration.ofSeconds(Long.parseLong(header)):
            Duration.between(now,ZonedDateTime.parse(header,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
        if(after.isNegative())after=Duration.ZERO;
        // The legacy owner profile has no authority to wait beyond the policy horizon.
        if(after.compareTo(Duration.ofHours(24))>0)throw new IllegalArgumentException();
      }catch(RuntimeException invalid){return new ManagedFileObjectReadFailure("Gateway object read retry policy invalid",false,Duration.ZERO);}
    }
    return new ManagedFileObjectReadFailure("Gateway object read failed with status "+status,retry,after);
  }
}
