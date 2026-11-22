package it.comune.trieste.ouf.onboarding;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.authorization.TestAuthorization;
import it.comune.trieste.ouf.onboarding.api.RuntimePublicationApi;
import it.comune.trieste.ouf.onboarding.application.RuntimePublicationEnvelope;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;

class RuntimePublicationBoundaryTest {
 @Test void task51e05ActualJsonBytesIncludeD084EvidenceAndAllowExactLimit()throws Exception{
  var json=new ObjectMapper();var publication=UUID.randomUUID();var version=UUID.randomUUID();
  var bundle=Map.of("semanticReferenceBindings",List.of(
      Map.of("semanticId","urn:datatype","definitionSha256","sha256:fixture","definition",Map.of("name","Comune è città")),
      Map.of("semanticId","urn:value-map","valueMapRef","urn:value-map@1","definition",Map.of("entries",List.of("Città\t\\\"")))));
  String stored=json.writeValueAsString(bundle),source="test-source",checksum="sha256:fixture";
  var codec=new RuntimePublicationEnvelope(json,"tenant-a",2097152);
  var db=mock(JdbcClient.class,RETURNS_DEEP_STUBS);
  List<Map<String,Object>> rows=List.of(Map.of(
      "publication_id",publication,"source_id",source,"onboarding_version_id",version,"bundle_version",1,
      "checksum",checksum,"source_status","REGISTERED","bundle",stored));
  when(db.sql(contains("p.source_id=:source")).param("source",source).query().listOfRows()).thenReturn(rows);
  when(db.sql(contains("p.source_id>:after")).param("after","").param("limit",1).query().listOfRows()).thenReturn(rows);
  var http=MockMvcBuilders.standaloneSetup(new RuntimePublicationApi(db,codec)).build();
  var response=http.perform(get("/api/onboarding/v1/runtime/publications/"+source+"/active").accept("application/json")
      .with(r->{TestAuthorization.bind(r,"runtime","SERVICE",Set.of("ouf.onboarding.configuration.read"));return r;}))
      .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json"))
      .andReturn().getResponse().getContentAsByteArray();
  assertThat(json.readTree(response).path("bundle")).isEqualTo(json.readTree(stored));
  var page=http.perform(get("/api/onboarding/v1/runtime/publications").param("limit","1").accept("application/json")
      .with(r->{TestAuthorization.bind(r,"runtime","SERVICE",Set.of("ouf.onboarding.configuration.read"));return r;}))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
  assertThat(json.readTree(page).path("items").get(0)).isEqualTo(json.readTree(response));
  assertThat(codec.measure(publication,source,version,1,checksum,stored).bytes()).isEqualTo(page.length);
  var exact=new RuntimePublicationEnvelope(json,"tenant-a",page.length);
  exact.measure(publication,source,version,1,checksum,stored).requireFits();
  var below=new RuntimePublicationEnvelope(json,"tenant-a",page.length-1);
  assertThatThrownBy(()->below.measure(publication,source,version,1,checksum,stored).requireFits())
      .isInstanceOfSatisfying(DomainFailure.class,failure->{
        assertThat(failure.code()).isEqualTo(RuntimePublicationEnvelope.TOO_LARGE);
        assertThat(failure.properties()).containsEntry("envelopeBytes",page.length).containsEntry("maxEnvelopeBytes",page.length-1);
      });
 }
 @Test void anonymousForgedActorHumanAndWrongNamespaceDenyBeforeDatabase()throws Exception{
  for(String tenant:List.of("tenant-a","other","")){
   var db=mock(JdbcClient.class);var http=MockMvcBuilders.standaloneSetup(new RuntimePublicationApi(db,new RuntimePublicationEnvelope(new ObjectMapper(),tenant,2097152))).build();
   http.perform(get("/api/onboarding/v1/runtime/publications").header("X-Actor-Type","SERVICE").header("X-Capabilities","ouf.onboarding.configuration.read")).andExpect(status().isForbidden());
   http.perform(get("/api/onboarding/v1/runtime/publications").with(r->{TestAuthorization.bind(r,"human","HUMAN",Set.of("ouf.onboarding.configuration.read"));return r;})).andExpect(status().isForbidden());
   if(!tenant.equals("tenant-a"))http.perform(get("/api/onboarding/v1/runtime/publications").with(r->{TestAuthorization.bind(r,"runtime","SERVICE",Set.of("ouf.onboarding.configuration.read"));return r;})).andExpect(status().isForbidden());
   verifyNoInteractions(db);
  }
 }
 @Test void missingScheduledPolicyCannotPassValidation(){var config=new LinkedHashMap<String,Object>();config.put("syncProfile",Map.of("bootstrap","FULL_SNAPSHOT","incremental","NONE","pollInterval","PT1M"));var result=new it.comune.trieste.ouf.onboarding.application.ConfigurationValidator().validate("s",config);assertThat(result.findings()).extracting(it.comune.trieste.ouf.onboarding.application.ConfigurationValidator.Finding::code).contains("ONB_OPERATIONAL_POLICY_REQUIRED");}
}
