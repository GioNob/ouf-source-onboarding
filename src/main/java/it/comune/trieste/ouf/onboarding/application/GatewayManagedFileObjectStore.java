package it.comune.trieste.ouf.onboarding.application;

import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(name="ouf.onboarding.object-store.gateway-base-url")
public class GatewayManagedFileObjectStore implements ManagedFileObjectStore {
  private static final long ABSOLUTE_MAX_BYTES=52_428_800;
  private final RestClient client;private final String readPath;

  public GatewayManagedFileObjectStore(RestClient.Builder builder,@Value("${ouf.onboarding.object-store.gateway-base-url}") String baseUrl,@Value("${ouf.onboarding.object-store.read-path:/internal/object-storage/v1/content}") String readPath){this.client=builder.baseUrl(baseUrl).build();this.readPath=readPath;}

  @Override public byte[] read(String stagingRef,long expectedSize){
    if(stagingRef==null||!stagingRef.startsWith("object://"))throw new IllegalArgumentException("stagingRef must use object://");if(expectedSize<1||expectedSize>ABSOLUTE_MAX_BYTES)throw new IllegalArgumentException("expected object size is outside intake limits");
    return client.get().uri(builder->builder.path(readPath).queryParam("ref",stagingRef).build()).exchange((request,response)->readBounded(response.getStatusCode(),response.getHeaders().getContentLength(),response.getBody(),expectedSize));
  }

  private static byte[] readBounded(HttpStatusCode status,long contentLength,java.io.InputStream body,long expectedSize) throws IOException {
    if(!status.is2xxSuccessful())throw new IllegalStateException("Gateway object read failed with status "+status.value());if(contentLength>expectedSize)throw new IllegalArgumentException("Gateway object exceeds the registered size");byte[] bytes=body.readNBytes(Math.toIntExact(expectedSize)+1);if(bytes.length>expectedSize)throw new IllegalArgumentException("Gateway object exceeds the registered size");return bytes;
  }
}
