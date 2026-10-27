package it.comune.trieste.ouf.onboarding.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

public class GatewayManagedFileObjectStore implements ManagedFileObjectStore {
  private static final long ABSOLUTE_MAX_BYTES=10L*1024*1024;
  private final RestClient client;private final String readPath;private final Path tokenFile;

  public GatewayManagedFileObjectStore(RestClient.Builder builder,@Value("${ouf.onboarding.object-store.gateway-base-url}") String baseUrl,@Value("${ouf.onboarding.object-store.read-path:/internal/object-storage/v1/content}") String readPath,@Value("${ouf.onboarding.object-store.token-file}") Path tokenFile){this.client=builder.baseUrl(baseUrl).build();this.readPath=readPath;this.tokenFile=tokenFile;}

  @Override public byte[] read(String stagingRef,long expectedSize){
    if(stagingRef==null||!stagingRef.startsWith("object://"))throw new IllegalArgumentException("stagingRef must use object://");if(expectedSize<1||expectedSize>ABSOLUTE_MAX_BYTES)throw new IllegalArgumentException("expected object size is outside intake limits");
    long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(40);
    try{return client.get().uri(builder->builder.path(readPath).queryParam("ref",stagingRef).build()).headers(headers->headers.setBearerAuth(token())).exchange((request,response)->{
      if(!response.getStatusCode().is2xxSuccessful())throw ManagedFileObjectReadFailure.http(response.getStatusCode().value(),response.getHeaders().getFirst("Retry-After"),java.time.Instant.now());
      return readBounded(response.getStatusCode(),response.getHeaders().getContentLength(),response.getBody(),expectedSize,deadline);
    });}catch(org.springframework.web.client.ResourceAccessException failure){throw ManagedFileObjectReadFailure.network(failure);}
  }

  private String token(){try{String value=Files.readString(tokenFile).strip();if(value.isEmpty()||value.contains("\n"))throw new IllegalStateException("Onboarding workload token is invalid");return value;}catch(IOException e){throw new IllegalStateException("Onboarding workload token is unavailable",e);}}

  private static byte[] readBounded(HttpStatusCode status,long contentLength,java.io.InputStream body,long expectedSize,long deadline) throws IOException {
    if(contentLength>expectedSize)throw new IllegalArgumentException("Gateway object exceeds the registered size");var bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[32768];
    for(;;){if(System.nanoTime()-deadline>=0)throw new java.net.SocketTimeoutException("Bounded managed read deadline");
      int count=body.read(buffer,0,(int)Math.min(buffer.length,expectedSize+1-bytes.size()));
      if(System.nanoTime()-deadline>=0)throw new java.net.SocketTimeoutException("Bounded managed read deadline");
      if(count<0)break;if(count==0)continue;bytes.write(buffer,0,count);
      if(bytes.size()>expectedSize)throw new IllegalArgumentException("Gateway object exceeds the registered size");}
    return bytes.toByteArray();
  }
}
