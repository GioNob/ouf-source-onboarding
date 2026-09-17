package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.CapabilityDescriptor;
import it.comune.trieste.ouf.authorization.PrincipalContext;
import it.comune.trieste.ouf.authorization.ServletAuthorization;
import it.comune.trieste.ouf.authorization.TestAuthorization;
import it.comune.trieste.ouf.onboarding.authorization.AuthorizationRuntimeSynchronizer;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AuthorizationBootstrapServerBindingTest {
  @DynamicPropertySource
  static void db(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> System.getenv("OUF_ONB_DB_URL"));
    r.add("spring.datasource.username", () -> System.getenv("OUF_ONB_DB_USER"));
    r.add("spring.datasource.password", () -> System.getenv("OUF_ONB_DB_PASSWORD"));
  }

  @Autowired MockMvc http;
  @Autowired ObjectMapper json;
  @Autowired JdbcClient db;
  @MockBean AuthorizationRuntimeSynchronizer runtimeSynchronizer;

  @BeforeEach
  void clean() {
    db.sql("truncate ouf_authorization.admin_audit,ouf_authorization.policy_draft,ouf_authorization.capability_registration,ouf_authorization.authorization_decision_audit,ouf_authorization.active_policy_bundle,ouf_authorization.policy_bundle,ouf_authorization.bootstrap_latch cascade").update();
    db.sql("insert into ouf_authorization.bootstrap_latch(singleton_key,completed) values(true,false)").update();
  }

  @Test
  void bootstrapPostWorksWithoutServletPrincipalWhenServerBindingsExist() throws Exception {
    var descriptor = new CapabilityDescriptor(
        "bootstrap.test", "EXECUTE", "bootstrap.test",
        Set.of(PrincipalContext.ActorType.HUMAN));

    http.perform(post("/api/trusted-human/v1/authorization/capabilities")
        .with(request -> {
          assertThat(request.getUserPrincipal()).isNull();
          request.setAttribute(
              ServletAuthorization.TRUSTED_PRINCIPAL,
              TestAuthorization.principal("human:admin", "HUMAN", Set.of("authorization.bootstrap")));
          request.setAttribute("ouf.statelessBearerWriteValidated", Boolean.TRUE);
          return request;
        })
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsBytes(Map.of("ownerRef", "authorization", "descriptor", descriptor))))
        .andExpect(status().isCreated());

    assertThat(db.sql("select count(*) from ouf_authorization.capability_registration where capability_id='bootstrap.test'")
        .query(Long.class).single()).isEqualTo(1);
  }
}
