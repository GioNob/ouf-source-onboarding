package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import it.comune.trieste.ouf.authorization.*;
import it.comune.trieste.ouf.onboarding.application.*;
import it.comune.trieste.ouf.onboarding.authorization.AuthorizationPolicyRegistry;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real PostgreSQL/transactions. Initial ACTIVE source and policy allow are explicit TEST fixtures. */
@SpringBootTest(properties="ouf.authorization.ths.public-origin=https://review.example")
class SourceRevisionRuntimeTest {
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->required("OUF_ONB_DB_URL"));r.add("spring.datasource.username",()->required("OUF_ONB_DB_USER"));r.add("spring.datasource.password",()->required("OUF_ONB_DB_PASSWORD"));}
  @Autowired SourceRevisionService revisions; @Autowired OnboardingService onboarding;
  @Autowired ManagedOnboardingReviewService reviews; @Autowired CanonicalHash hashes; @Autowired JdbcClient db;
  @MockitoBean(answers=Answers.RETURNS_DEEP_STUBS) AuthorizationPolicyRegistry policy;
  private final OnboardingService.Actor human=new OnboardingService.Actor("test-human","HUMAN_USER");
  private PrincipalContext principal(String tenant,String subject){return new PrincipalContext(subject,tenant,PrincipalContext.ActorType.HUMAN,"test-mcp","test-acr","https://issuer.example","test-audience",Set.of(SourceRevisionService.PREPARE,SourceRevisionService.READ,"ouf.onboarding.configuration.write"),new PrincipalContext.IdentityClaims(Set.of(),"test-acr",Set.of(),null));}
  @BeforeEach void clean(){db.sql("truncate ouf_onboarding.source,ouf_onboarding.managed_file_asset restart identity cascade").update();when(policy.authorizeActive(any(),any(),anyString(),anyString(),any()).allowed()).thenReturn(true);}
  @Test void apiSourceRetainsIdentityConfigurationAndNativeApprovalWithoutPublishing(){
    var parent=active();var p=principal("test-tenant","test-human");var command=command(parent,"revision-one",rule());
    var before=onboarding.version("s",parent);var first=revisions.prepare(p,command,"c");UUID next=(UUID)first.get("onboardingVersionId");
    assertThat(first).containsEntry("state","IN_REVIEW").containsEntry("humanApprovalRequired",true).containsEntry("parentVersionId",parent);
    assertThat(first.get("publicationEffects")).isEqualTo(SourceRevisionService.effects());
    assertThat(revisions.prepare(p,command,"lost-response")).isEqualTo(first);
    assertThat(revisions.read(p,"s",next)).isEqualTo(first);
    assertThat(onboarding.version("s",parent)).isEqualTo(before);
    var config=configuration(next);assertThat(config.get("syncProfile")).isEqualTo(configuration(parent).get("syncProfile"));
    assertThat(config.get("semanticMapping")).isEqualTo(configuration(parent).get("semanticMapping"));
    assertThat(((Map<?,?>)config.get("bundle")).get("bundleVersion")).isEqualTo("2");
    assertThat(((Map<?,?>)config.get("extractionProfile")).get("version")).isEqualTo("2");
    assertThat(revisions.read(p,"s",parent)).containsKey("propertyRules").doesNotContainKeys("configuration","credentialRef");
    var card=reviews.humanCard(p,(UUID)first.get("challengeId"));assertThat(card).containsEntry("publicationEffects",SourceRevisionService.effects());
    assertThat(db.sql("select count(*) from ouf_onboarding.source").query(Long.class).single()).isOne();
    assertThat(db.sql("select count(*) from ouf_onboarding.approval_challenge").query(Long.class).single()).isOne();
    assertThat(db.sql("select count(*) from ouf_onboarding.approval_decision").query(Long.class).single()).isZero();
    assertThat(db.sql("select count(*) from ouf_onboarding.published_configuration").query(Long.class).single()).isZero();
  }
  @Test void concurrentLostResponseRetriesCreateExactlyOneVersionAndChallenge()throws Exception{
    var parent=active();var p=principal("test-tenant","test-human");var command=command(parent,"concurrent",rule());
    try(var executor=Executors.newFixedThreadPool(2)){
      var start=new CountDownLatch(1);
      var a=executor.submit(()->{start.await();return revisions.prepare(p,command,"a");});
      var b=executor.submit(()->{start.await();return revisions.prepare(p,command,"b");});start.countDown();
      assertThat(a.get(15,TimeUnit.SECONDS)).isEqualTo(b.get(15,TimeUnit.SECONDS));
    }
    assertThat(db.sql("select count(*) from ouf_onboarding.onboarding_version").query(Long.class).single()).isEqualTo(2);
    assertThat(db.sql("select count(*) from ouf_onboarding.approval_challenge").query(Long.class).single()).isOne();
  }
  @Test void changedIntentDriftAndOtherReviewCannotCreateAnotherClone(){
    UUID parent=active();var p=principal("test-tenant","test-human");var command=command(parent,"key",rule());
    var first=revisions.prepare(p,command,"c");
    var changed=new LinkedHashMap<>(rule());changed.put("cardinality","MULTI");
    assertThatThrownBy(()->revisions.prepare(p,command(parent,"key",changed),"c")).hasMessage("ONB_REVISION_COMMAND_CONFLICT");
    assertThatThrownBy(()->revisions.prepare(p,command(parent,"different",rule()),"c")).hasMessage("ONB_REVISION_OPEN_VERSION_EXISTS");
    UUID next=(UUID)first.get("onboardingVersionId");
    assertThatThrownBy(()->revisions.read(principal("other-tenant","test-human"),"s",next)).hasMessage("ONB_REVISION_AUTHORIZATION_DENIED");
    assertThatThrownBy(()->revisions.read(principal("test-tenant","other-human"),"s",next)).hasMessage("ONB_REVISION_AUTHORIZATION_DENIED");
    when(policy.authorizeActive(any(),any(),anyString(),anyString(),any()).allowed()).thenReturn(false);
    assertThatThrownBy(()->revisions.prepare(p,command,"c")).hasMessage("ONB_REVISION_AUTHORIZATION_DENIED");
    assertThat(db.sql("select count(*) from ouf_onboarding.onboarding_version").query(Long.class).single()).isEqualTo(2);
  }
  @Test void invalidPolicyRollsBackCloneChallengeAndCommandJournal(){
    UUID parent=active();var p=principal("test-tenant","test-human");var bad=new LinkedHashMap<>(rule());bad.put("deleteMissing",true);
    assertThatThrownBy(()->revisions.prepare(p,command(parent,"invalid",bad),"c")).hasMessageContaining("blocking validation");
    assertThat(db.sql("select count(*) from ouf_onboarding.onboarding_version").query(Long.class).single()).isOne();
    assertThat(db.sql("select count(*) from ouf_onboarding.source_property_revision").query(Long.class).single()).isZero();
    assertThat(db.sql("select count(*) from ouf_onboarding.approval_challenge").query(Long.class).single()).isZero();
  }
  @Test void staleParentAndUnacknowledgedPublicationEffectFailBeforeClone(){
    UUID parent=active();var p=principal("test-tenant","test-human");var base=command(parent,"key",rule());
    var stale=new SourceRevisionService.Prepare("s",parent,1,base.configurationHash(),"key",SourceRevisionService.EFFECT,base.changes());
    assertThatThrownBy(()->revisions.prepare(p,stale,"c")).hasMessage("ONB_REVISION_PARENT_DRIFT");
    var unaware=new SourceRevisionService.Prepare("s",parent,0,base.configurationHash(),"key","POLICY_ONLY_NO_INGESTION",base.changes());
    assertThatThrownBy(()->revisions.prepare(p,unaware,"c")).hasMessage("ONB_REVISION_REQUEST_INVALID");
    assertThat(db.sql("select count(*) from ouf_onboarding.onboarding_version").query(Long.class).single()).isOne();
  }
  @Test void nativeActivationRechecksTheParentEvenAfterAValidHumanDecision(){
    UUID parent=active();var p=principal("test-tenant","test-human");var first=revisions.prepare(p,command(parent,"key",rule()),"c");
    UUID next=(UUID)first.get("onboardingVersionId"),challenge=(UUID)first.get("challengeId");String hash=first.get("configurationHash").toString();
    reviews.humanAction(p,challenge,"confirm",new ManagedOnboardingReviewService.Decision(hash,2),"test-auth");
    db.sql("update ouf_onboarding.onboarding_version set state='SUPERSEDED',lock_version=lock_version+1 where onboarding_version_id=:v").param("v",parent).update();
    assertThatThrownBy(()->onboarding.activate("s",next,human,"c")).hasMessage("ONB_REVISION_PARENT_DRIFT");
    assertThat(db.sql("select count(*) from ouf_onboarding.published_configuration").query(Long.class).single()).isZero();
    // A read/exact replay reconciles the existing command; it does not clone or renew the expired review.
    assertThat(revisions.prepare(p,command(parent,"key",rule()),"reconcile")).containsEntry("onboardingVersionId",next);
  }
  private SourceRevisionService.Prepare command(UUID parent,String key,Map<String,Object> rule){return new SourceRevisionService.Prepare("s",parent,0,String.valueOf(onboarding.version("s",parent).get("configuration_hash")),key,SourceRevisionService.EFFECT,List.of(new SourceRevisionService.Change("urn:name","urn:name",rule)));}
  static Map<String,Object> rule(){return Map.of("policyRef","urn:ouf:policy:linked-object-property-reconciliation:v1","cardinality","SINGLE","normalizerRef","EXACT_TYPED","normalizerVersion",1,"validity","SOURCE_INTERVAL");}
  @SuppressWarnings("unchecked") private Map<String,Object> configuration(UUID version){return (Map<String,Object>)onboarding.version("s",version).get("configuration");}
  private UUID active(){
    onboarding.createSource("s","Fixture API","EXTERNAL_API","PULL","Test owner",Map.of(),human,"c");
    var config=fixture();UUID version=(UUID)onboarding.createVersion("s",config,human,"c").get("onboarding_version_id");
    db.sql("update ouf_onboarding.onboarding_version set state='ACTIVE',configuration_hash=:h,frozen_at=transaction_timestamp() where onboarding_version_id=:v").param("h",hashes.of(config)).param("v",version).update();return version;
  }
  static Map<String,Object> fixture(){
    var config=new LinkedHashMap<String,Object>();config.put("syncProfile",Map.of("bootstrap","FULL_SNAPSHOT","incremental","NONE"));
    var property=new LinkedHashMap<String,Object>(Map.of("propertyIri","urn:name","sourceField","urn:name","datatype","http://www.w3.org/2001/XMLSchema#string","accessLabel","RESTRICTED","authorityOrder",List.of("s")));property.put("reconciliation",null);
    config.put("extractionProfile",Map.of("profileId","ep","version","1","sourceId","s","selection",Map.of(),"projection",Map.of(),"sync",Map.of(),"runtime",Map.of("credentialRef","secret://test-reference","udp",Map.of("materialization",Map.of("policyRef","test-policy","properties",List.of(property))))));
    config.put("semanticMapping",Map.of("mappingId","sm","sourceType",Map.of("sourceId","s","typeCode","T"),"targetClasses",List.of(Map.of("ontologyId","core","ontologyVersion","1","classIri","urn:T")),"semanticRefs",List.of("core@1")));
    config.put("bundle",Map.of("bundleId","b","bundleVersion","1","environment","test","source",Map.of("sourceId","s","sourceKind","EXTERNAL_API","acquisitionMode","PULL"),"bindingRef","gateway://test","createdFromOnboardingVersion","pending","effectiveFrom","2026-01-01T00:00:00Z","checksum","sha256:x","status","APPROVED"));
    config.put("unrelatedExtension",Map.of("keep",List.of("first","second")));return config;
  }
  private static String required(String name){return Objects.requireNonNull(System.getenv(name),name+" required");}
}
