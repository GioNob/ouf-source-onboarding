package it.comune.trieste.ouf.onboarding;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.CapabilityDescriptor;
import it.comune.trieste.ouf.authorization.PrincipalContext;
import it.comune.trieste.ouf.onboarding.authorization.AuthorizationRuntimeSynchronizer;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "ouf.iam.enabled=true",
    "ouf.iam.issuer=https://auth.ouf-lab.it/realms/ouf",
    "ouf.iam.audience=ouf-api-gateway",
    "ouf.iam.actor-type-claim=ouf_actor_type"
})
@AutoConfigureMockMvc
class IamAuthorizationBootstrapIntegrationTest {
  @DynamicPropertySource
  static void db(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> System.getenv("OUF_ONB_DB_URL"));
    r.add("spring.datasource.username", () -> System.getenv("OUF_ONB_DB_USER"));
    r.add("spring.datasource.password", () -> System.getenv("OUF_ONB_DB_PASSWORD"));
  }

  @Autowired MockMvc http;
  @Autowired ObjectMapper json;
  @Autowired JdbcClient db;
  @MockBean JwtDecoder jwtDecoder;
  @MockBean AuthorizationRuntimeSynchronizer runtimeSynchronizer;

  @BeforeEach
  void clean() {
    db.sql("truncate ouf_authorization.admin_audit,ouf_authorization.policy_draft,ouf_authorization.capability_registration,ouf_authorization.authorization_decision_audit,ouf_authorization.active_policy_bundle,ouf_authorization.policy_bundle,ouf_authorization.bootstrap_latch cascade").update();
    db.sql("insert into ouf_authorization.bootstrap_latch(singleton_key,completed) values(true,false)").update();
  }

  private Jwt token(String actor, String scope, boolean clientIdentity) {
    var claims = new HashMap<String, Object>();
    claims.put("sub", "HUMAN".equals(actor) ? "human:admin" : "workload:ouf-mcp-server");
    claims.put("ouf_subject", claims.get("sub"));
    claims.put("iss", "https://auth.ouf-lab.it/realms/ouf");
    claims.put("aud", List.of("ouf-api-gateway"));
    claims.put("tenant_id", "ouf-lab");
    claims.put("ouf_actor_type", actor);
    claims.put("acr", "HUMAN".equals(actor) ? "urn:ouf:acr:human" : "client-credentials");
    claims.put("scope", scope);
    if (clientIdentity) claims.put("client_id", "ouf-mcp-server");
    return new Jwt(
        "token",
        Instant.now().minusSeconds(10),
        Instant.now().plusSeconds(300),
        Map.of("alg", "RS256"),
        claims);
  }

  private byte[] registration(String id) throws Exception {
    var descriptor = new CapabilityDescriptor(
        id, "EXECUTE", id, Set.of(PrincipalContext.ActorType.HUMAN));
    return json.writeValueAsBytes(Map.of("ownerRef", "authorization", "descriptor", descriptor));
  }


  @Test
  void installationTrustedHumanSurfaceReturns401WithoutBearerToken() throws Exception {
    http.perform(get("/api/trusted-human/v1/installations/install-a/active"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void protectedBootstrapEndpointReturns401WithoutBearerToken() throws Exception {
    http.perform(post("/api/trusted-human/v1/authorization/capabilities")
        .contentType(MediaType.APPLICATION_JSON)
        .content(registration("iam.no-token")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void humanWithBootstrapScopePassesRealIamChain() throws Exception {
    when(jwtDecoder.decode("human-bootstrap"))
        .thenReturn(token("HUMAN", "authorization.bootstrap", false));

    http.perform(post("/api/trusted-human/v1/authorization/capabilities")
        .header("Authorization", "Bearer human-bootstrap")
        .contentType(MediaType.APPLICATION_JSON)
        .content(registration("iam.human-bootstrap")))
        .andExpect(status().isCreated());
  }

  @Test
  void serviceCannotUseBootstrapEvenWithBootstrapScope() throws Exception {
    when(jwtDecoder.decode("service-bootstrap"))
        .thenReturn(token("SERVICE", "authorization.bootstrap", true));

    http.perform(post("/api/trusted-human/v1/authorization/capabilities")
        .header("Authorization", "Bearer service-bootstrap")
        .contentType(MediaType.APPLICATION_JSON)
        .content(registration("iam.service-bootstrap")))
        .andExpect(status().isForbidden());
  }

  @Test
  void humanWithoutBootstrapScopeIsDenied() throws Exception {
    when(jwtDecoder.decode("human-no-bootstrap"))
        .thenReturn(token("HUMAN", "profile", false));

    http.perform(post("/api/trusted-human/v1/authorization/capabilities")
        .header("Authorization", "Bearer human-no-bootstrap")
        .contentType(MediaType.APPLICATION_JSON)
        .content(registration("iam.human-no-bootstrap")))
        .andExpect(status().isForbidden());
  }

  @Test
  void invalidBearerTokenReturns401() throws Exception {
    when(jwtDecoder.decode("invalid"))
        .thenThrow(new BadJwtException("invalid token"));

    http.perform(post("/api/trusted-human/v1/authorization/capabilities")
        .header("Authorization", "Bearer invalid")
        .contentType(MediaType.APPLICATION_JSON)
        .content(registration("iam.invalid")))
        .andExpect(status().isUnauthorized());
  }
}
