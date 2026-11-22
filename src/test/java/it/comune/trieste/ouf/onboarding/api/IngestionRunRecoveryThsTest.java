package it.comune.trieste.ouf.onboarding.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class IngestionRunRecoveryThsTest {
  @Test void task50bH01ReadsAndFencesHumanCommandsThroughTheGateway()throws Exception{
    var fixture=new ManagedProfileRecoveryThsTest();
    var json=new ObjectMapper();
    var run=UUID.randomUUID();
    var state=new AtomicReference<>(Map.of("run_id",run.toString(),"control_version",7,"state","PAUSED","lake_next_action","ABORT_REQUIRED"));
    var requests=java.util.Collections.synchronizedList(new ArrayList<List<String>>());
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/",exchange->{
      try(exchange){
        requests.add(List.of(exchange.getRequestMethod(),exchange.getRequestURI().toString(),
            exchange.getRequestHeaders().getFirst("Authorization"),
            new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)));
        byte[] bytes=json.writeValueAsBytes(state.get());
        exchange.sendResponseHeaders(200,bytes.length);
        exchange.getResponseBody().write(bytes);
      }
    });
    server.start();
    try{
      var api=new IngestionRunRecoveryThs(fixture.session(),json,"http://127.0.0.1:"+server.getAddress().getPort());
      var request=fixture.request();
      assertThat(api.card(run,7,request).getBody().toString()).contains("linkCurrent=true","fixture-csrf")
          .doesNotContain("fixture-private-token");
      assertThat(api.decide(run,6,"abort",request).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
      assertThat(api.decide(run,7,"resume",request).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
      assertThat(api.decide(run,7,"abort",request).getStatusCode()).isEqualTo(HttpStatus.OK);
      String path="/api/trusted-human/v1/ingestion/runs/"+run;
      assertThat(requests.stream().filter(r->r.getFirst().equals("POST"))).containsExactly(
          List.of("POST",path+"/abort","Bearer fixture-private-token","{\"expectedVersion\":7}"));
      assertThat(requests.stream().filter(r->r.getFirst().equals("GET"))).hasSize(4)
          .allSatisfy(r->assertThat(r.get(1)).isEqualTo(path));
    }finally{
      fixture.clear();
      server.stop(0);
    }
  }

  @Test void task50bH02RejectsRequestsWithoutAValidatedHumanSession(){
    var clients=mock(OAuth2AuthorizedClientService.class);
    var api=new IngestionRunRecoveryThs(clients,new ObjectMapper(),"https://gateway.example");
    assertThatThrownBy(()->api.decide(UUID.randomUUID(),1,"abort",new MockHttpServletRequest()))
        .hasMessageContaining("THS_SESSION_REQUIRED");
    verifyNoInteractions(clients);
  }

  @Test void task50bH03MissingCsrfStopsTheCommandBeforeTheController()throws Exception{
    var clients=mock(OAuth2AuthorizedClientService.class);
    var api=new IngestionRunRecoveryThs(clients,new ObjectMapper(),"https://gateway.example");
    var http=MockMvcBuilders.standaloneSetup(api)
        .addFilters(new CsrfFilter(new HttpSessionCsrfTokenRepository())).build();
    http.perform(post("/trusted-human/ingestion-run-recovery/api/"+UUID.randomUUID()+"/7/abort")
        .requestAttr("ouf.permissionThsSession",true)).andExpect(status().isForbidden());
    verifyNoInteractions(clients);
  }
}
