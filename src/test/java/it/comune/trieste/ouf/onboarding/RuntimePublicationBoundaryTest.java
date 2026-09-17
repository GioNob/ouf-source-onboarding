package it.comune.trieste.ouf.onboarding;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.authorization.TestAuthorization;
import it.comune.trieste.ouf.onboarding.api.RuntimePublicationApi;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;

class RuntimePublicationBoundaryTest {
 @Test void anonymousForgedActorHumanAndWrongNamespaceDenyBeforeDatabase()throws Exception{
  for(String tenant:List.of("tenant-a","other","")){
   var db=mock(JdbcClient.class);var http=MockMvcBuilders.standaloneSetup(new RuntimePublicationApi(db,new ObjectMapper(),tenant)).build();
   http.perform(get("/api/onboarding/v1/runtime/publications").header("X-Actor-Type","SERVICE").header("X-Capabilities","ouf.onboarding.configuration.read")).andExpect(status().isForbidden());
   http.perform(get("/api/onboarding/v1/runtime/publications").with(r->{TestAuthorization.bind(r,"human","HUMAN",Set.of("ouf.onboarding.configuration.read"));return r;})).andExpect(status().isForbidden());
   if(!tenant.equals("tenant-a"))http.perform(get("/api/onboarding/v1/runtime/publications").with(r->{TestAuthorization.bind(r,"runtime","SERVICE",Set.of("ouf.onboarding.configuration.read"));return r;})).andExpect(status().isForbidden());
   verifyNoInteractions(db);
  }
 }
 @Test void missingScheduledPolicyCannotPassValidation(){var config=new LinkedHashMap<String,Object>();config.put("syncProfile",Map.of("bootstrap","FULL_SNAPSHOT","incremental","NONE","pollInterval","PT1M"));var result=new it.comune.trieste.ouf.onboarding.application.ConfigurationValidator().validate("s",config);assertThat(result.findings()).extracting(it.comune.trieste.ouf.onboarding.application.ConfigurationValidator.Finding::code).contains("ONB_OPERATIONAL_POLICY_REQUIRED");}
}
