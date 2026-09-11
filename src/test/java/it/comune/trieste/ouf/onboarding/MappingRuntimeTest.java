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
class MappingRuntimeTest {
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->required("OUF_ONB_DB_URL"));r.add("spring.datasource.username",()->required("OUF_ONB_DB_USER"));r.add("spring.datasource.password",()->required("OUF_ONB_DB_PASSWORD"));}
  @Autowired OnboardingService onboarding; @Autowired DiscoveryService discovery; @Autowired MappingService mapping; @Autowired JdbcClient db;
  OnboardingService.Actor agent=new OnboardingService.Actor("agent:external","AI_AGENT");
  @BeforeEach void setup(){db.sql("truncate table ouf_onboarding.audit_event,ouf_onboarding.approval_decision,ouf_onboarding.approval_challenge,ouf_onboarding.published_configuration,ouf_onboarding.validation_run,ouf_onboarding.relationship_mapping_draft,ouf_onboarding.field_mapping,ouf_onboarding.mapping_workspace,ouf_onboarding.discovered_field,ouf_onboarding.discovered_state,ouf_onboarding.discovered_type,ouf_onboarding.discovery_run,ouf_onboarding.source_binding,ouf_onboarding.onboarding_version,ouf_onboarding.source restart identity cascade").update();onboarding.createSource("world-bank","World Bank","EXTERNAL_API","PULL","World Bank",Map.of(),agent,"c");discovery.configureBinding("world-bank","population","REST","gateway://world-bank","workload://public",Map.of(),0);UUID run=(UUID)discovery.start("world-bank","population","discovery",Map.of()).get("discovery_run_id");discovery.claim("worker",Duration.ofMinutes(1));discovery.complete(run,"worker",snapshot());mapping.initialize("world-bank","indicator-observation");}
  @Test void discoveryBecomesValidatedDraft(){mapping.configureType("world-bank","indicator-observation","https://example.org/IndicatorObservation",List.of("https://schema.gov.it/example@1"),List.of("PUBLISHED"),0);mapping.configureField("world-bank","indicator-observation","countryiso3code","INCLUDE","https://example.org/country","IDENTITY",0);mapping.configureField("world-bank","indicator-observation","date","INCLUDE","https://example.org/year","IDENTITY",0);mapping.configureField("world-bank","indicator-observation","value","INCLUDE","https://example.org/value","IDENTITY",0);mapping.configureField("world-bank","indicator-observation","indicator.id","EXCLUDE",null,null,0);var draft=mapping.buildDraft("world-bank","indicator-observation",agent,"mapping-build");UUID id=(UUID)draft.get("onboarding_version_id");assertThat(draft.get("state")).isEqualTo("DRAFT");assertThat(onboarding.validate("world-bank",id,agent,"validation").get("result")).isEqualTo("PASS");assertThat(mapping.workspace("world-bank","indicator-observation").get("state")).isEqualTo("COMPLETE");}
  @Test void unclassifiedFieldBlocksDraft(){mapping.configureType("world-bank","indicator-observation","https://example.org/IndicatorObservation",List.of("core@1"),List.of(),0);mapping.configureField("world-bank","indicator-observation","value","INCLUDE","https://example.org/value","IDENTITY",0);assertThatThrownBy(()->mapping.buildDraft("world-bank","indicator-observation",agent,"c")).hasMessageContaining("Every discovered field must be classified");assertThat(db.sql("select count(*) from ouf_onboarding.onboarding_version").query(Long.class).single()).isZero();}
  @Test void mappingUsesOptimisticLocking(){mapping.configureType("world-bank","indicator-observation","https://example.org/One",List.of("core@1"),List.of(),0);assertThatThrownBy(()->mapping.configureType("world-bank","indicator-observation","https://example.org/Two",List.of("core@1"),List.of(),0)).hasMessageContaining("Stale ETag");}
  @Test void includedFieldRequiresTargetProperty(){assertThatThrownBy(()->mapping.configureField("world-bank","indicator-observation","value","INCLUDE",null,"IDENTITY",0)).hasStackTraceContaining("field_mapping_check");}
  private static Map<String,Object> snapshot(){return Map.of("types",List.of(Map.of("typeCode","indicator-observation","fields",List.of(Map.of("name","countryiso3code","dataType","string","nullable",false),Map.of("name","date","dataType","year","nullable",false),Map.of("name","value","dataType","number","nullable",true),Map.of("name","indicator.id","dataType","string","nullable",false)),"states",List.of(Map.of("code","PUBLISHED")))));}
  private static String required(String n){String v=System.getenv(n);if(v==null)throw new IllegalStateException(n+" required");return v;}
}
