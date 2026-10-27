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
  /** Only timeouts/connect refusal of this bounded read are transient; TLS/DNS/unknown IO are not. */
  public static ManagedFileObjectReadFailure network(org.springframework.web.client.ResourceAccessException failure){
    Throwable cause=failure;var seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable,Boolean>());
    boolean known=false;
    for(int depth=0;cause!=null&&depth<8&&seen.add(cause);depth++,cause=cause.getCause()){
      if(cause instanceof javax.net.ssl.SSLException||cause instanceof java.net.UnknownHostException)
        return new ManagedFileObjectReadFailure("Gateway object read transport requires reconciliation",false,Duration.ZERO);
      if(cause instanceof java.net.SocketTimeoutException||cause instanceof java.net.http.HttpTimeoutException||cause instanceof java.net.ConnectException)known=true;
    }
    return new ManagedFileObjectReadFailure(known?"Gateway object read temporary transport failure":"Gateway object read transport requires reconciliation",known,Duration.ZERO);
  }
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
