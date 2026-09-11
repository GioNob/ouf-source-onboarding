package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import it.comune.trieste.ouf.onboarding.application.OnboardingService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class OnboardingWorkflowRuntimeTest {
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->required("OUF_ONB_DB_URL"));r.add("spring.datasource.username",()->required("OUF_ONB_DB_USER"));r.add("spring.datasource.password",()->required("OUF_ONB_DB_PASSWORD"));}
  @Autowired OnboardingService service; @Autowired JdbcClient db;
  OnboardingService.Actor human=new OnboardingService.Actor("human:test","HUMAN_USER");
  OnboardingService.Actor agent=new OnboardingService.Actor("agent:test","AI_AGENT");
  @BeforeEach void clean(){db.sql("truncate table ouf_onboarding.audit_event,ouf_onboarding.approval_decision,ouf_onboarding.approval_challenge,ouf_onboarding.published_configuration,ouf_onboarding.onboarding_version,ouf_onboarding.source restart identity cascade").update();}

  @Test void fullHumanGovernedActivationIsAtomicAndAudited(){
    service.createSource("suap-test","SUAP test","EXTERNAL_API","PULL","Comune di Trieste",Map.of("openData",true),human,"c-1");
    var draft=service.createVersion("suap-test",validConfiguration("suap-test",1),agent,"c-2");
    UUID version=(UUID)draft.get("onboarding_version_id");
    var review=service.submit("suap-test",version,0,agent,"c-3");
    assertThat(review.get("state")).isEqualTo("IN_REVIEW");assertThat(review.get("configuration_hash").toString()).startsWith("sha256:");
    var challenge=service.createChallenge("suap-test",version,agent,"c-4");
    assertThatThrownBy(()->service.confirm("suap-test",version,(UUID)challenge.get("challenge_id"),agent,"c-5","acr:agent")).hasMessageContaining("HUMAN_USER");
    var approved=service.confirm("suap-test",version,(UUID)challenge.get("challenge_id"),human,"c-6","acr:mfa");assertThat(approved.get("state")).isEqualTo("APPROVED");
    var publication=service.activate("suap-test",version,human,"c-7");assertThat(publication.get("checksum").toString()).startsWith("sha256:");
    assertThat(service.version("suap-test",version).get("state")).isEqualTo("ACTIVE");
    assertThat(db.sql("select count(*) from ouf_onboarding.published_configuration where source_id='suap-test' and active").query(Long.class).single()).isEqualTo(1);
    assertThat(db.sql("select event_type from ouf_onboarding.audit_event order by created_at").query(String.class).list()).containsExactly("SOURCE_CREATED","ONBOARDING_VERSION_CREATED","VERSION_VALIDATED","VERSION_SUBMITTED","APPROVAL_CHALLENGE_CREATED","VERSION_APPROVED","VERSION_ACTIVATED");
  }

  @Test void staleEtagAndChallengeReplayFailClosed(){
    service.createSource("s","Source","EXTERNAL_API","PULL","owner",Map.of(),human,"c");var draft=service.createVersion("s",validConfiguration("s",1),human,"c");UUID id=(UUID)draft.get("onboarding_version_id");
    service.patchVersion("s",id,0,validConfiguration("s",2),human,"c");assertThatThrownBy(()->service.patchVersion("s",id,0,validConfiguration("s",3),human,"c")).hasMessageContaining("Stale ETag");
    service.submit("s",id,1,human,"c");var challenge=service.createChallenge("s",id,human,"c");UUID cid=(UUID)challenge.get("challenge_id");service.confirm("s",id,cid,human,"c","acr:mfa");
    assertThatThrownBy(()->service.confirm("s",id,cid,human,"c","acr:mfa")).hasMessageContaining("already consumed");
  }

  @Test void secondActivationSupersedesFirstWithoutMutatingItsBundle(){
    service.createSource("s","Source","INTERNAL_MANAGED","MANAGED","OUF",Map.of(),human,"c");UUID first=approve("s",Map.of("revision",1));var original=service.activate("s",first,human,"c");String checksum=original.get("checksum").toString();
    UUID second=approve("s",Map.of("revision",2));service.activate("s",second,human,"c");
    assertThat(service.version("s",first).get("state")).isEqualTo("SUPERSEDED");assertThat(service.version("s",second).get("state")).isEqualTo("ACTIVE");
    assertThat(db.sql("select checksum from ouf_onboarding.published_configuration where onboarding_version_id=:v").param("v",first).query(String.class).single()).isEqualTo(checksum);
  }
  @Test void appendOnlyEvidenceRejectsMutation(){service.createSource("s","Source","EXTERNAL_API","PULL","owner",Map.of(),human,"c");UUID id=approve("s",Map.of("a",1));assertThatThrownBy(()->db.sql("update ouf_onboarding.approval_decision set actor_subject='forged' where onboarding_version_id=:v").param("v",id).update()).hasStackTraceContaining("approval_decision is append-only");}
  @Test void validationFindingsArePersistedAndBlockSubmit(){service.createSource("s","Source","EXTERNAL_API","PULL","owner",Map.of(),human,"c");var draft=service.createVersion("s",Map.of(),human,"c");UUID id=(UUID)draft.get("onboarding_version_id");var validation=service.validate("s",id,human,"validation-correlation");assertThat(validation.get("result")).isEqualTo("FAIL");assertThat((Long)validation.get("errorCount")).isGreaterThan(0);assertThatThrownBy(()->service.submit("s",id,0,human,"c")).hasMessageContaining("blocking validation errors");assertThat(db.sql("select count(*) from ouf_onboarding.validation_run where onboarding_version_id=:v").param("v",id).query(Long.class).single()).isEqualTo(1);}
  @Test void validationEvidenceIsAppendOnly(){service.createSource("s","Source","EXTERNAL_API","PULL","owner",Map.of(),human,"c");var draft=service.createVersion("s",validConfiguration("s",1),human,"c");UUID id=(UUID)draft.get("onboarding_version_id");service.validate("s",id,human,"c");assertThatThrownBy(()->db.sql("delete from ouf_onboarding.validation_run where onboarding_version_id=:v").param("v",id).update()).hasStackTraceContaining("validation_run is append-only");}
  private UUID approve(String source,Map<String,Object> config){var v=service.createVersion(source,validConfiguration(source,config.getOrDefault("revision",1)),human,"c");UUID id=(UUID)v.get("onboarding_version_id");service.submit(source,id,0,human,"c");var ch=service.createChallenge(source,id,human,"c");service.confirm(source,id,(UUID)ch.get("challenge_id"),human,"c","acr:mfa");return id;}
  private static Map<String,Object> validConfiguration(String source,Object revision){return Map.of(
    "syncProfile",Map.of("bootstrap","FULL_SNAPSHOT","incremental","CHANGE_TOKEN","pageSize",500),
    "extractionProfile",Map.of("profileId","ep-"+source,"version",String.valueOf(revision),"sourceId",source,"selection",Map.of("types",List.of("TYPE")),"projection",Map.of("TYPE",List.of("id")),"sync",Map.of("bootstrap","FULL_SNAPSHOT","incremental","CHANGE_TOKEN"),"runtime",Map.of("credentialRef","secret://"+source)),
    "semanticMapping",Map.of("mappingId","sm-"+source,"sourceType",Map.of("sourceId",source,"typeCode","TYPE"),"targetClasses",List.of(Map.of("classIri","https://example.org/Type")),"semanticRefs",List.of("core@1")),
    "bundle",Map.of("bundleId","bundle-"+source,"bundleVersion","1.0."+revision,"environment","test","source",Map.of("sourceId",source,"sourceKind","EXTERNAL_API","acquisitionMode","PULL"),"createdFromOnboardingVersion","pending","effectiveFrom","2026-01-01T00:00:00Z","checksum","sha256:pending","status","APPROVED","changeRepresentationProfile",Map.of("mode","FULL_SNAPSHOT"))
  );}
  private static String required(String name){String value=System.getenv(name);if(value==null)throw new IllegalStateException(name+" required");return value;}
}
