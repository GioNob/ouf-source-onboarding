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

class GatewayManagedFileObjectStoreTest {
  @Test void readsStagedContentOnlyThroughTheConfiguredGatewayRoute(@TempDir Path tmp)throws Exception{Path token=tmp.resolve("token");Files.writeString(token,"first-token\n");RestClient.Builder builder=RestClient.builder();MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();byte[] expected="id,name\n1,A\n".getBytes();server.expect(requestTo("https://gateway.test/internal/object-storage/v1/content?ref=object://staging/a.csv")).andExpect(header("Authorization","Bearer first-token")).andRespond(withSuccess(expected,MediaType.APPLICATION_OCTET_STREAM));var store=new GatewayManagedFileObjectStore(builder,"https://gateway.test","/internal/object-storage/v1/content",token);assertThat(store.read("object://staging/a.csv",expected.length)).isEqualTo(expected);server.verify();}

  @Test void rejectsResponsesLargerThanTheRegisteredObject(@TempDir Path tmp)throws Exception{Path token=tmp.resolve("token");Files.writeString(token,"token");RestClient.Builder builder=RestClient.builder();MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();server.expect(requestTo("https://gateway.test/internal/object-storage/v1/content?ref=object://staging/a.csv")).andRespond(withSuccess("too-large".getBytes(),MediaType.APPLICATION_OCTET_STREAM));var store=new GatewayManagedFileObjectStore(builder,"https://gateway.test","/internal/object-storage/v1/content",token);assertThatThrownBy(()->store.read("object://staging/a.csv",3)).hasMessageContaining("registered size");server.verify();}

  @Test void readsRenewedTokenForEachRequest(@TempDir Path tmp)throws Exception{Path token=tmp.resolve("token");Files.writeString(token,"before");RestClient.Builder builder=RestClient.builder();MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();String uri="https://gateway.test/internal/object-storage/v1/content?ref=object://staging/a.csv";server.expect(requestTo(uri)).andExpect(header("Authorization","Bearer before")).andRespond(withSuccess("x".getBytes(),MediaType.APPLICATION_OCTET_STREAM));server.expect(requestTo(uri)).andExpect(header("Authorization","Bearer after")).andRespond(withSuccess("x".getBytes(),MediaType.APPLICATION_OCTET_STREAM));var store=new GatewayManagedFileObjectStore(builder,"https://gateway.test","/internal/object-storage/v1/content",token);assertThat(store.read("object://staging/a.csv",1)).hasSize(1);Files.writeString(token,"after");assertThat(store.read("object://staging/a.csv",1)).hasSize(1);server.verify();}
}
