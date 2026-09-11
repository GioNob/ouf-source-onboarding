package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import it.comune.trieste.ouf.onboarding.application.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class DiscoveryRuntimeTest {
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->required("OUF_ONB_DB_URL"));r.add("spring.datasource.username",()->required("OUF_ONB_DB_USER"));r.add("spring.datasource.password",()->required("OUF_ONB_DB_PASSWORD"));}
  @Autowired OnboardingService onboarding; @Autowired DiscoveryService discovery; @Autowired JdbcClient db;
  OnboardingService.Actor human=new OnboardingService.Actor("human:test","HUMAN_USER");
  @BeforeEach void clean(){db.sql("truncate table ouf_onboarding.audit_event,ouf_onboarding.approval_decision,ouf_onboarding.approval_challenge,ouf_onboarding.published_configuration,ouf_onboarding.validation_run,ouf_onboarding.discovered_field,ouf_onboarding.discovered_state,ouf_onboarding.discovered_type,ouf_onboarding.discovery_run,ouf_onboarding.source_binding,ouf_onboarding.onboarding_version,ouf_onboarding.source restart identity cascade").update();onboarding.createSource("world-bank","World Bank Italy population","EXTERNAL_API","PULL","World Bank",Map.of("openData",true),human,"c");discovery.configureBinding("world-bank","population","REST","gateway://southbound/world-bank","workload://public-anonymous",Map.of("mediaType","application/json"),0);}

  @Test void startIsIdempotentAndPayloadConflictFailsClosed(){var first=discovery.start("world-bank","population","key-1",Map.of("indicator","SP.POP.TOTL"));var replay=discovery.start("world-bank","population","key-1",Map.of("indicator","SP.POP.TOTL"));assertThat(replay.get("discovery_run_id")).isEqualTo(first.get("discovery_run_id"));assertThatThrownBy(()->discovery.start("world-bank","population","key-1",Map.of("indicator","NY.GDP.MKTP.CD"))).hasMessageContaining("different discovery request");}
  @Test void oneWorkerClaimsAndMaterializesSnapshot(){UUID id=(UUID)discovery.start("world-bank","population","key-1",Map.of()).get("discovery_run_id");var claim=discovery.claim("worker-1",Duration.ofSeconds(30)).orElseThrow();assertThat(claim.get("discovery_run_id")).isEqualTo(id);assertThat(discovery.claim("worker-2",Duration.ofSeconds(30))).isEmpty();discovery.complete(id,"worker-1",snapshot());assertThat(discovery.run(id).get("status")).isEqualTo("SUCCEEDED");assertThat(discovery.types("world-bank")).extracting(x->x.get("type_code")).containsExactly("indicator-observation");assertThat(discovery.fields("world-bank","indicator-observation")).hasSize(4);assertThat(discovery.states("world-bank","indicator-observation")).extracting(x->x.get("state_code")).containsExactly("PUBLISHED");}
  @Test void expiredLeaseIsRecoveredWithoutDuplicateRun(){UUID id=(UUID)discovery.start("world-bank","population","key-1",Map.of()).get("discovery_run_id");discovery.claim("crashed",Duration.ZERO).orElseThrow();var recovered=discovery.claim("recovery",Duration.ofSeconds(30)).orElseThrow();assertThat(recovered.get("discovery_run_id")).isEqualTo(id);assertThat(recovered.get("claimed_by")).isEqualTo("recovery");assertThat(((Number)recovered.get("attempts")).intValue()).isEqualTo(2);}
  @Test void plaintextCredentialIsRejected(){assertThatThrownBy(()->discovery.configureBinding("world-bank","bad","REST","gateway://x","password=secret",Map.of(),0)).hasMessageContaining("secret:// or workload://");}
  private static Map<String,Object> snapshot(){return Map.of("types",List.of(Map.of("typeCode","indicator-observation","label","World Bank indicator observation","schemaRef","world-bank-v2","fields",List.of(Map.of("name","countryiso3code","dataType","string","nullable",false),Map.of("name","date","dataType","year","nullable",false),Map.of("name","value","dataType","number","nullable",true),Map.of("name","indicator.id","dataType","string","nullable",false)),"states",List.of(Map.of("code","PUBLISHED","label","Published observation")))));}
  private static String required(String name){String value=System.getenv(name);if(value==null)throw new IllegalStateException(name+" required");return value;}
}
