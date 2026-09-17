package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import it.comune.trieste.ouf.onboarding.application.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class SemanticGapRuntimeTest {
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->required("OUF_ONB_DB_URL"));r.add("spring.datasource.username",()->required("OUF_ONB_DB_USER"));r.add("spring.datasource.password",()->required("OUF_ONB_DB_PASSWORD"));}
  @Autowired OnboardingService onboarding;@Autowired SemanticGapService gaps;@Autowired JdbcClient db;
  OnboardingService.Actor human=new OnboardingService.Actor("human:test","HUMAN_USER");OnboardingService.Actor registry=new OnboardingService.Actor("service:semantic","SERVICE",Set.of("ouf.semantic.discovery.result.report"));
  @BeforeEach void clean(){db.sql("truncate table ouf_onboarding.audit_event,ouf_onboarding.source restart identity cascade").update();}
  @Test void candidateSelectionDoesNotInventSemanticAuthority(){onboarding.createSource("s","Source","EXTERNAL_API","PULL","owner",Map.of(),human,"c");UUID version=(UUID)onboarding.createVersion("s",Map.of(),human,"c").get("onboarding_version_id");Map<String,Object> gap=gaps.create("s",version,"Permit","status","Missing controlled concept");UUID gapId=(UUID)gap.get("gap_id");gaps.requestSearch("s",version,gapId,"semantic-discovery://request/1");assertThatThrownBy(()->gaps.recordCandidates("s",version,gapId,List.of(Map.of("semanticRef","vocab@1","label","Candidate")),human)).hasMessageContaining("Semantic Registry");Map<String,Object> withCandidates=gaps.recordCandidates("s",version,gapId,List.of(Map.of("semanticRef","vocab@1","label","Candidate","status","CANDIDATE")),registry);UUID candidate=(UUID)((Map<?,?>)((List<?>)withCandidates.get("candidates")).get(0)).get("candidate_id");assertThat(gaps.select("s",version,gapId,candidate)).containsEntry("state","SELECTED");}
  @Test void adoptedCandidateResolvesOnlyAfterRegistryReportsPublication(){
    onboarding.createSource("s","Source","EXTERNAL_API","PULL","owner",Map.of(),human,"c");
    UUID version=(UUID)onboarding.createVersion("s",Map.of(),human,"c").get("onboarding_version_id");
    UUID gap=(UUID)gaps.create("s",version,"Permit","status","Missing concept").get("gap_id");
    gaps.requestSearch("s",version,gap,"semantic-discovery://request/2");
    var result=gaps.recordCandidates("s",version,gap,List.of(Map.of("semanticRef","vocab@1","status","ADOPTED")),registry);
    UUID candidate=(UUID)((Map<?,?>)((List<?>)result.get("candidates")).getFirst()).get("candidate_id");
    assertThat(gaps.select("s",version,gap,candidate)).containsEntry("state","SELECTED");
    assertThat(gaps.recordCandidates("s",version,gap,List.of(Map.of("semanticRef","vocab@1","status","PUBLISHED")),registry)).containsEntry("state","RESOLVED");
    assertThat((List<?>)gaps.gap("s",version,gap).get("candidates")).hasSize(1);
  }
  private static String required(String name){String value=System.getenv(name);if(value==null)throw new IllegalStateException(name+" required");return value;}
}
