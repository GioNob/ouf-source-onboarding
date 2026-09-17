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
class OnboardingWorkflowRuntimeTest {
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->required("OUF_ONB_DB_URL"));r.add("spring.datasource.username",()->required("OUF_ONB_DB_USER"));r.add("spring.datasource.password",()->required("OUF_ONB_DB_PASSWORD"));}
  @Autowired OnboardingService service; @Autowired SchemaSurveillanceService surveillance; @Autowired RuntimeProjectionService projections; @Autowired JdbcClient db;
  OnboardingService.Actor human=new OnboardingService.Actor("human:test","HUMAN_USER");
  OnboardingService.Actor agent=new OnboardingService.Actor("agent:test","AI_AGENT");
  OnboardingService.Actor ingestion=new OnboardingService.Actor("service:ingestion","SERVICE",Set.of("ouf.ingestion.configuration.attest"));
  @BeforeEach void clean(){db.sql("truncate table ouf_onboarding.audit_event,ouf_onboarding.consumer_compatibility_attestation,ouf_onboarding.approval_decision,ouf_onboarding.approval_challenge,ouf_onboarding.published_configuration,ouf_onboarding.onboarding_version,ouf_onboarding.source restart identity cascade").update();}

  @Test void fullHumanGovernedActivationIsAtomicAndAudited(){
    service.createSource("suap-test","SUAP test","EXTERNAL_API","PULL","Comune di Trieste",Map.of("openData",true),human,"c-1");
    var draft=service.createVersion("suap-test",validConfiguration("suap-test",1),agent,"c-2");
    UUID version=(UUID)draft.get("onboarding_version_id");
    var review=service.submit("suap-test",version,0,agent,"c-3");
    assertThat(review.get("state")).isEqualTo("IN_REVIEW");assertThat(review.get("configuration_hash").toString()).startsWith("sha256:");
    var challenge=service.createChallenge("suap-test",version,agent,"c-4");
    assertThatThrownBy(()->service.confirm("suap-test",version,(UUID)challenge.get("challenge_id"),agent,"c-5","acr:agent")).hasMessageContaining("HUMAN_USER");
    var approved=service.confirm("suap-test",version,(UUID)challenge.get("challenge_id"),human,"c-6","acr:mfa");assertThat(approved.get("state")).isEqualTo("APPROVED");
    assertThatThrownBy(()->service.activate("suap-test",version,human,"c-7")).hasMessageContaining("Ingestion Runtime");
    service.attestIngestionCompatibility("suap-test",version,true,"rc3 bundle and extraction profile accepted",ingestion,"c-7");
    var publication=service.activate("suap-test",version,human,"c-7");assertThat(publication.get("checksum").toString()).startsWith("sha256:");
    assertThat(service.version("suap-test",version).get("state")).isEqualTo("ACTIVE");
    assertThat(db.sql("select count(*) from ouf_onboarding.published_configuration where source_id='suap-test' and active").query(Long.class).single()).isEqualTo(1);
    assertThat(db.sql("select event_type from ouf_onboarding.audit_event order by created_at").query(String.class).list()).containsExactly("SOURCE_CREATED","ONBOARDING_VERSION_CREATED","VERSION_VALIDATED","VERSION_SUBMITTED","APPROVAL_CHALLENGE_CREATED","VERSION_APPROVED","INGESTION_COMPAT_ATTESTED","VERSION_ACTIVATED");
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
  @Test void genericServiceCannotAttestIngestionCompatibility(){service.createSource("s","Source","EXTERNAL_API","PULL","owner",Map.of(),human,"c");var draft=service.createVersion("s",validConfiguration("s",1),human,"c");UUID id=(UUID)draft.get("onboarding_version_id");service.submit("s",id,0,human,"c");assertThatThrownBy(()->service.attestIngestionCompatibility("s",id,true,"forged",new OnboardingService.Actor("service:other","SERVICE"),"c")).hasMessageContaining("Authorization-owned");}
  @Test void activationPublishesTechnicalGatewayProjectionsAndHistoricalBundle(){service.createSource("s","Source","EXTERNAL_API","PULL","owner",Map.of(),human,"c");UUID id=approve("s",Map.of("revision",1));Map<String,Object> active=service.activate("s",id,human,"c");UUID publication=(UUID)active.get("publication_id");assertThat(projections.projections("s",publication)).extracting(p->p.get("projection_type")).containsExactly("ROUTE_BINDING","SOURCE_RUNTIME_PROFILE","SOURCE_SCHEMA_BINDING");assertThat(service.historicalBundle("s",id).get("checksum")).isEqualTo(active.get("checksum"));assertThat(service.bundleHistory("s")).hasSize(1);}
  @Test void unresolvedBreakingSchemaDriftBlocksActivation(){service.createSource("s","Source","EXTERNAL_API","PULL","owner",Map.of(),human,"c");UUID id=approve("s",Map.of("revision",1));Map<String,Object> issue=surveillance.report("s","schema://s/2","sha256:old","sha256:new","BREAKING",Map.of("removed",List.of("id")));assertThatThrownBy(()->service.activate("s",id,human,"c")).hasMessageContaining("schema drift");surveillance.resolve("s",(UUID)issue.get("issue_id"));assertThat(service.activate("s",id,human,"c").get("source_id")).isEqualTo("s");}
  @Test void approvalCardShowsExactFrozenContextAndHumanCanReject(){service.createSource("s","Source","EXTERNAL_API","PULL","owner",Map.of(),human,"c");var draft=service.createVersion("s",validConfiguration("s",1),human,"c");UUID id=(UUID)draft.get("onboarding_version_id");service.submit("s",id,0,human,"c");var challenge=service.createChallenge("s",id,agent,"c");UUID challengeId=(UUID)challenge.get("challenge_id");assertThat(service.approvalCard(challengeId)).containsEntry("configuration_hash",service.version("s",id).get("configuration_hash")).containsEntry("trustedApprovalRef","ths://approval-challenges/"+challengeId).containsKey("changedSections");assertThat(service.reject("s",id,challengeId,human,"c","acr:mfa").get("state")).isEqualTo("REJECTED");}
  @Test void sourceUpdateCloneDiffAndExtractionBuildRespectVersioning(){service.createSource("s","Source","EXTERNAL_API","PULL","owner",Map.of("a",1),human,"c");assertThat(service.updateSource("s",0,"Renamed",null,"ENABLED",Map.of("a",2),human,"c")).containsEntry("name","Renamed").containsEntry("lock_version",1L);var first=service.createVersion("s",validConfiguration("s",1),human,"c");UUID firstId=(UUID)first.get("onboarding_version_id");var cloned=service.cloneVersion("s",firstId,human,"c");UUID cloneId=(UUID)cloned.get("onboarding_version_id");assertThat(cloned).containsEntry("state","DRAFT");assertThat(service.diff("s",cloneId).get("changedSections")).isEqualTo(List.of());assertThat(service.buildExtractionProfile("s",cloneId)).containsKeys("extractionProfile","configurationHash");assertThatThrownBy(()->service.updateSource("s",0,"stale",null,null,null,human,"c")).hasMessageContaining("Stale ETag");}
  @Test @SuppressWarnings("unchecked") void crsDecisionAppearsOnHumanCardAndCannotChangeAfterApproval(){
    service.createSource("gis","GIS","EXTERNAL_API","PULL","Comune",Map.of(),human,"crs");
    var config=new LinkedHashMap<>(validConfiguration("gis",1));var extraction=new LinkedHashMap<>((Map<String,Object>)config.get("extractionProfile"));
    var spatial=Map.of("policyRef","crs://municipal/v1","relationships",List.of(),"geometry",Map.of("sourceField","urn:geometry","expectedSourceCrs","EPSG:4326","canonicalSrid",6708,"normalizationVersion","v1","accessLabel","RESTRICTED","crsPolicy",Map.of("sourceAxisOrder","XY","mismatchAction","REJECT")));
    extraction.put("runtime",Map.of("udp",Map.of("spatial",spatial)));config.put("extractionProfile",extraction);
    var draft=service.createVersion("gis",config,agent,"crs");UUID id=(UUID)draft.get("onboarding_version_id");service.submit("gis",id,0,agent,"crs");
    var challenge=service.createChallenge("gis",id,agent,"crs");UUID cid=(UUID)challenge.get("challenge_id");
    assertThat(challenge).containsEntry("spatialDecision",spatial).containsKey("spatialDecisionFindings");assertThat(service.approvalCard(cid)).containsEntry("spatialDecision",spatial).containsKey("spatialDecisionFindings");
    assertThatThrownBy(()->service.confirm("gis",id,cid,agent,"crs","agent")).hasMessageContaining("HUMAN_USER");
    var approved=service.confirm("gis",id,cid,human,"crs","mfa");
    assertThatThrownBy(()->service.patchVersion("gis",id,((Number)approved.get("lock_version")).longValue(),validConfiguration("gis",2),agent,"crs"));
    assertThat(service.approvalCard(cid)).containsEntry("spatialDecision",spatial);
    service.attestIngestionCompatibility("gis",id,true,"reject policy understood",ingestion,"crs");var published=service.activate("gis",id,human,"crs");
    var bundle=(Map<String,Object>)published.get("bundle");var publishedExtraction=(Map<String,Object>)bundle.get("extractionProfile");var runtime=(Map<String,Object>)publishedExtraction.get("runtime");
    assertThat((Map<String,Object>)runtime.get("udp")).containsEntry("spatial",spatial);
  }
  private UUID approve(String source,Map<String,Object> config){var v=service.createVersion(source,validConfiguration(source,config.getOrDefault("revision",1)),human,"c");UUID id=(UUID)v.get("onboarding_version_id");service.submit(source,id,0,human,"c");var ch=service.createChallenge(source,id,human,"c");service.confirm(source,id,(UUID)ch.get("challenge_id"),human,"c","acr:mfa");service.attestIngestionCompatibility(source,id,true,"accepted",ingestion,"c");return id;}
  private static Map<String,Object> validConfiguration(String source,Object revision){return Map.of(
    "syncProfile",Map.of("bootstrap","FULL_SNAPSHOT","incremental","CHANGE_TOKEN","pageSize",500),
    "extractionProfile",Map.of("profileId","ep-"+source,"version",String.valueOf(revision),"sourceId",source,"selection",Map.of("types",List.of("TYPE")),"projection",Map.of("TYPE",List.of("id")),"sync",Map.of("bootstrap","FULL_SNAPSHOT","incremental","CHANGE_TOKEN"),"runtime",Map.of("credentialRef","secret://"+source)),
    "semanticMapping",Map.of("mappingId","sm-"+source,"sourceType",Map.of("sourceId",source,"typeCode","TYPE"),"targetClasses",List.of(Map.of("ontologyId","core","ontologyVersion","1","classIri","https://example.org/Type")),"semanticRefs",List.of("core@1")),
    "bundle",Map.of("bundleId","bundle-"+source,"bundleVersion","1.0."+revision,"environment","test","source",Map.of("sourceId",source,"sourceKind","EXTERNAL_API","acquisitionMode","PULL"),"bindingRef","onboarding://sources/"+source+"/bindings/active","createdFromOnboardingVersion","pending","effectiveFrom","2026-01-01T00:00:00Z","checksum","sha256:pending","status","APPROVED","changeRepresentationProfile",Map.of("mode","FULL_SNAPSHOT"))
  );}
  private static String required(String name){String value=System.getenv(name);if(value==null)throw new IllegalStateException(name+" required");return value;}
}
