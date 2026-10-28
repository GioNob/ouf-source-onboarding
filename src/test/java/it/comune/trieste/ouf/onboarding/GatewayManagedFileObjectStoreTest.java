package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import it.comune.trieste.ouf.onboarding.application.GatewayManagedFileObjectStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import it.comune.trieste.ouf.onboarding.application.ManagedFileObjectReadFailure;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpResponse;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;

class GatewayManagedFileObjectStoreTest {
  @Test void forbiddenStopsBeforeReadingBodyAndReturnsTypedPermanentEvidence(@TempDir Path tmp)throws Exception{
    Path token=tmp.resolve("token");Files.writeString(token,"synthetic");var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
    var reads=new AtomicInteger();var body=new java.io.InputStream(){public int read(){reads.incrementAndGet();throw new AssertionError("forbidden body must not be read");}};
    server.expect(requestTo("https://gateway.test/internal/object-storage/v1/content?ref=object://staging/a.csv"))
        .andRespond(request->new MockClientHttpResponse(body,HttpStatus.FORBIDDEN));
    var store=new GatewayManagedFileObjectStore(builder,"https://gateway.test","/internal/object-storage/v1/content",token);
    assertThatThrownBy(()->store.read("object://staging/a.csv",1)).isInstanceOfSatisfying(ManagedFileObjectReadFailure.class,f->{
      assertThat(f.retryable()).isFalse();assertThat(f.getMessage()).isEqualTo("Gateway object read failed with status 403");});
    assertThat(reads).hasValue(0);server.verify();
  }
  @Test void transientHttpIsExplicitAndRetryAfterIsBoundedAndNotLeaked(){
    Instant now=Instant.parse("2026-10-27T00:00:00Z");
    for(int status:new int[]{429,502,503,504})assertThat(ManagedFileObjectReadFailure.http(status,"120",now).retryAfter()).isEqualTo(Duration.ofSeconds(120));
    assertThat(ManagedFileObjectReadFailure.http(503,"Tue, 27 Oct 2026 00:03:00 GMT",now).retryAfter()).isEqualTo(Duration.ofMinutes(3));
    for(String header:new String[]{"PRIVATE_TOKEN_CANARY","999999"}){
      var failure=ManagedFileObjectReadFailure.http(503,header,now);assertThat(failure.retryable()).isFalse();assertThat(failure.getMessage()).doesNotContain(header);
    }
    for(int status:new int[]{400,401,403,404,409,422,500})assertThat(ManagedFileObjectReadFailure.http(status,null,now).retryable()).isFalse();
  }
  @Test void readsStagedContentOnlyThroughTheConfiguredGatewayRoute(@TempDir Path tmp)throws Exception{Path token=tmp.resolve("token");Files.writeString(token,"first-token\n");RestClient.Builder builder=RestClient.builder();MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();byte[] expected="id,name\n1,A\n".getBytes();server.expect(requestTo("https://gateway.test/internal/object-storage/v1/content?ref=object://staging/a.csv")).andExpect(header("Authorization","Bearer first-token")).andRespond(withSuccess(expected,MediaType.APPLICATION_OCTET_STREAM));var store=new GatewayManagedFileObjectStore(builder,"https://gateway.test","/internal/object-storage/v1/content",token);assertThat(store.read("object://staging/a.csv",expected.length)).isEqualTo(expected);server.verify();}

  @Test void rejectsResponsesLargerThanTheRegisteredObject(@TempDir Path tmp)throws Exception{Path token=tmp.resolve("token");Files.writeString(token,"token");RestClient.Builder builder=RestClient.builder();MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();server.expect(requestTo("https://gateway.test/internal/object-storage/v1/content?ref=object://staging/a.csv")).andRespond(withSuccess("too-large".getBytes(),MediaType.APPLICATION_OCTET_STREAM));var store=new GatewayManagedFileObjectStore(builder,"https://gateway.test","/internal/object-storage/v1/content",token);assertThatThrownBy(()->store.read("object://staging/a.csv",3)).hasMessageContaining("registered size");server.verify();}

  @Test void readsRenewedTokenForEachRequest(@TempDir Path tmp)throws Exception{Path token=tmp.resolve("token");Files.writeString(token,"before");RestClient.Builder builder=RestClient.builder();MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();String uri="https://gateway.test/internal/object-storage/v1/content?ref=object://staging/a.csv";server.expect(requestTo(uri)).andExpect(header("Authorization","Bearer before")).andRespond(withSuccess("x".getBytes(),MediaType.APPLICATION_OCTET_STREAM));server.expect(requestTo(uri)).andExpect(header("Authorization","Bearer after")).andRespond(withSuccess("x".getBytes(),MediaType.APPLICATION_OCTET_STREAM));var store=new GatewayManagedFileObjectStore(builder,"https://gateway.test","/internal/object-storage/v1/content",token);assertThat(store.read("object://staging/a.csv",1)).hasSize(1);Files.writeString(token,"after");assertThat(store.read("object://staging/a.csv",1)).hasSize(1);server.verify();}
}
