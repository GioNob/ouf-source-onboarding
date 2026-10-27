package it.comune.trieste.ouf.onboarding.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.*;

class ManagedProfileTransportTest {
  @Test void realTransportDoesNotReplay503OrFollowRedirectAndWorkerBeanIsPresent(@TempDir Path root)throws Exception{
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var count=new AtomicInteger();var status=new java.util.concurrent.atomic.AtomicInteger(503);
    server.createContext("/content",exchange->{count.incrementAndGet();assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer fixture-only");exchange.getResponseHeaders().set("Retry-After","0");exchange.getResponseHeaders().set("Location","/unexpected");exchange.sendResponseHeaders(status.get(),-1);exchange.close();});
    server.createContext("/unexpected",exchange->{count.addAndGet(100);exchange.sendResponseHeaders(200,-1);exchange.close();});server.start();
    Path token=root.resolve("token");Files.writeString(token,"fixture-only");
    try{new ApplicationContextRunner().withUserConfiguration(ManagedFileReadTransport.class).withBean(RestClient.Builder.class,RestClient::builder).withBean(ManagedFileService.class,()->mock(ManagedFileService.class))
        .withPropertyValues("ouf.onboarding.object-store.gateway-base-url=http://127.0.0.1:"+server.getAddress().getPort(),"ouf.onboarding.object-store.read-path=/content","ouf.onboarding.object-store.token-file="+token)
        .run(context->{assertThat(context).hasSingleBean(ManagedFileProfileWorker.class);var store=context.getBean(GatewayManagedFileObjectStore.class);
          assertThatThrownBy(()->store.read("object://fixture",1)).isInstanceOfSatisfying(ManagedFileObjectReadFailure.class,f->assertThat(f.retryable()).isTrue());assertThat(count).hasValue(1);
          status.set(302);assertThatThrownBy(()->store.read("object://fixture",1)).hasMessageContaining("302");assertThat(count).hasValue(2);
        });
    }finally{server.stop(0);}
  }
  @Test void networkClassificationIsTypedAndCannotTrustPrivateExceptionText(){
    for(var cause:new java.io.IOException[]{new SocketTimeoutException("PRIVATE_CANARY"),new ConnectException("PRIVATE_CANARY")}){
      var failure=ManagedFileObjectReadFailure.network(new ResourceAccessException("token=PRIVATE_CANARY",cause));assertThat(failure.retryable()).isTrue();assertThat(failure.getMessage()).doesNotContain("PRIVATE_CANARY");}
    for(var cause:new java.io.IOException[]{new UnknownHostException("PRIVATE_HOST"),new javax.net.ssl.SSLException("PRIVATE_TLS"),new java.io.IOException("SocketTimeoutException PRIVATE")})
      assertThat(ManagedFileObjectReadFailure.network(new ResourceAccessException("PRIVATE",cause)).retryable()).isFalse();
  }
}
