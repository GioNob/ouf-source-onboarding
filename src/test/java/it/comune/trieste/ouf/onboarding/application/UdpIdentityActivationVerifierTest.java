package it.comune.trieste.ouf.onboarding.application;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UdpIdentityActivationVerifierTest {
  @TempDir Path temp;

  @Test void finalReadClassifiesOnlyExplicitTemporaryStatusesAndRetainsRetryAfter()throws Exception{
    var status=new java.util.concurrent.atomic.AtomicInteger();
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/",exchange->{
      exchange.getResponseHeaders().set("Retry-After","90");
      exchange.sendResponseHeaders(status.get(),-1);exchange.close();
    });
    server.start();
    try{
      var token=Files.writeString(temp.resolve("fixture"),"test-workload");
      var verifier=new UdpIdentityActivationVerifier(new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort(),token.toString());
      for(int code:List.of(408,429,502,503,504)){
        status.set(code);
        assertThatThrownBy(()->verifier.requireCurrent("s","sha256:"+"1".repeat(64),Map.of()))
            .isInstanceOfSatisfying(UdpIdentityActivationVerifier.TransientFailure.class,e->{
              assertThat(e.code()).isEqualTo("ONB_READINESS_DEPENDENCY_"+code);
              assertThat(e.retryAfterSeconds).isEqualTo(90);
            });
      }
      for(int code:List.of(401,403,404,500)){
        status.set(code);
        assertThatThrownBy(()->verifier.requireCurrent("s","sha256:"+"1".repeat(64),Map.of()))
            .isInstanceOfSatisfying(it.comune.trieste.ouf.onboarding.domain.DomainFailure.class,
                e->assertThat(e.code()).isEqualTo("ONB_UDP_IDENTITY_EVIDENCE_UNPROVEN"));
      }
    }finally{server.stop(0);}
  }

  @Test void exactFrozenConfigurationRequiresCurrentUdpAttestation()throws Exception{
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    var valid=new java.util.concurrent.atomic.AtomicBoolean(true);
    server.createContext("/api/udp/v1/governance/internal/identity/preflight",exchange->{
      String body=new ObjectMapper().writeValueAsString(Map.of(
          "valid",valid.get(),"configurationHash","sha256:"+"a".repeat(64),
          "sourceId","registry","tenantId","default","canonicalClass","ouf:Road",
          "policyRef","policy://identity/1","policyVersion","1","coverageRef","coverage://1"));
      byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200,bytes.length);try(var stream=exchange.getResponseBody()){stream.write(bytes);}
    });
    server.start();
    try{
      Path token=temp.resolve("token");Files.writeString(token,"service-token");
      var verifier=new UdpIdentityActivationVerifier(new ObjectMapper(),
          "http://127.0.0.1:"+server.getAddress().getPort(),token.toString());
      Map<String,Object> policy=Map.of("ref","policy://identity/1","version","1",
          "sourceId","registry","tenantId","default","canonicalClass","ouf:Road");
      Map<String,Object> resolution=Map.of("strategyId","GOVERNED_IDENTITY",
          "strategyVersion","1","policyRef","policy://identity/1","governedIdentity",policy);
      Map<String,Object> configuration=Map.of("extractionProfile",Map.of("runtime",Map.of(
          "udp",Map.of("resolution",resolution))));
      ResolutionActivationGate.requireExecutable(configuration,"registry","sha256:"+"a".repeat(64),verifier);
      valid.set(false);
      assertThatThrownBy(()->ResolutionActivationGate.requireExecutable(configuration,
          "registry","sha256:"+"a".repeat(64),verifier))
          .hasMessageContaining("coverage");
      assertThatThrownBy(()->ResolutionActivationGate.requireExecutable(configuration,
          "registry","sha256:"+"b".repeat(64),verifier))
          .hasMessageContaining("coverage");
    }finally{server.stop(0);}
  }
}
