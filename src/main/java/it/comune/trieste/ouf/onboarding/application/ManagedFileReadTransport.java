package it.comune.trieste.ouf.onboarding.application;

import java.nio.file.Path;
import org.apache.hc.client5.http.config.*;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** No redirects or automatic transport re-execution. Only the owner schedules retries. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="ouf.onboarding.object-store.gateway-base-url")
public class ManagedFileReadTransport {
  @Bean(destroyMethod="destroy") HttpComponentsClientHttpRequestFactory managedProfileReadFactory(){
    var connections=PoolingHttpClientConnectionManagerBuilder.create().setMaxConnTotal(1).setMaxConnPerRoute(1)
        .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(5)).setSocketTimeout(Timeout.ofSeconds(30)).build()).build();
    var client=HttpClients.custom().setConnectionManager(connections).disableAutomaticRetries().disableRedirectHandling()
        .disableCookieManagement().disableContentCompression().setDefaultRequestConfig(RequestConfig.custom()
          .setConnectionRequestTimeout(Timeout.ofSeconds(5)).setResponseTimeout(Timeout.ofSeconds(30)).build()).build();
    return new HttpComponentsClientHttpRequestFactory(client);
  }
  @Bean GatewayManagedFileObjectStore gatewayManagedFileObjectStore(RestClient.Builder builder,
      @Qualifier("managedProfileReadFactory") HttpComponentsClientHttpRequestFactory factory,
      @Value("${ouf.onboarding.object-store.gateway-base-url}") String base,
      @Value("${ouf.onboarding.object-store.read-path:/internal/object-storage/v1/content}") String path,
      @Value("${ouf.onboarding.object-store.token-file}") Path token){
    return new GatewayManagedFileObjectStore(builder.requestFactory(factory),base,path,token);
  }
}
