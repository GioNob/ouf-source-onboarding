package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import it.comune.trieste.ouf.onboarding.application.GatewayManagedFileObjectStore;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GatewayManagedFileObjectStoreTest {
  @Test void readsStagedContentOnlyThroughTheConfiguredGatewayRoute(){RestClient.Builder builder=RestClient.builder();MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();byte[] expected="id,name\n1,A\n".getBytes();server.expect(requestTo("https://gateway.test/internal/object-storage/v1/content?ref=object://staging/a.csv")).andRespond(withSuccess(expected,MediaType.APPLICATION_OCTET_STREAM));var store=new GatewayManagedFileObjectStore(builder,"https://gateway.test","/internal/object-storage/v1/content");assertThat(store.read("object://staging/a.csv",expected.length)).isEqualTo(expected);server.verify();}

  @Test void rejectsResponsesLargerThanTheRegisteredObject(){RestClient.Builder builder=RestClient.builder();MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();server.expect(requestTo("https://gateway.test/internal/object-storage/v1/content?ref=object://staging/a.csv")).andRespond(withSuccess("too-large".getBytes(),MediaType.APPLICATION_OCTET_STREAM));var store=new GatewayManagedFileObjectStore(builder,"https://gateway.test","/internal/object-storage/v1/content");assertThatThrownBy(()->store.read("object://staging/a.csv",3)).hasMessageContaining("registered size");server.verify();}
}
