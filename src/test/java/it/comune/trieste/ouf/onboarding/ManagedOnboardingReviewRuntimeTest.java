package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import it.comune.trieste.ouf.authorization.*;
import it.comune.trieste.ouf.onboarding.application.*;
import it.comune.trieste.ouf.onboarding.authorization.AuthorizationPolicyRegistry;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real lifecycle/PG transactions; authorization allow/deny is a TEST fixture, never a deployment grant. */
@SpringBootTest(properties="ouf.authorization.ths.public-origin=https://review.example")
class ManagedOnboardingReviewRuntimeTest {
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->required("OUF_ONB_DB_URL"));r.add("spring.datasource.username",()->required("OUF_ONB_DB_USER"));r.add("spring.datasource.password",()->required("OUF_ONB_DB_PASSWORD"));}
  @Autowired ManagedOnboardingReviewService reviews; @Autowired ManagedFileService files;
  @Autowired OnboardingService onboarding; @Autowired CanonicalHash hashes; @Autowired JdbcClient db;
  @MockBean(answer=Answers.RETURNS_DEEP_STUBS) AuthorizationPolicyRegistry policy;
  private final OnboardingService.Actor human=new OnboardingService.Actor("test-human","HUMAN_USER");
  private PrincipalContext principal(String tenant,String subject){return new PrincipalContext(subject,tenant,PrincipalContext.ActorType.HUMAN,"test-mcp","test-acr","https://issuer.example","test-audience",Set.of(ManagedOnboardingReviewService.PREPARE,ManagedOnboardingReviewService.READ,"ouf.onboarding.configuration.write"),new PrincipalContext.IdentityClaims(Set.of(),"test-acr",Set.of(),null));}
  @BeforeEach void clean(){db.sql("truncate ouf_onboarding.source,ouf_onboarding.managed_file_asset restart identity cascade").update();when(policy.authorizeActive(any(),any(),anyString(),anyString(),any()).allowed()).thenReturn(true);}
  @Test void preparationRetryAndReadPreserveOneFrozenChallengeAndHumanApprovalRemainsSeparate(){
    UUID version=draft();var p=principal("test-tenant","test-human");
    var command=new ManagedOnboardingReviewService.Prepare("review-test",version,1,"prepare:one",null);
    var prepared=reviews.prepare(p,command,"test-correlation");
    assertThat(prepared).containsEntry("state","IN_REVIEW").containsEntry("humanApprovalRequired",true).containsEntry("ingestionCompatibilityObserved",false);
    assertThat(prepared.get("reviewUrl").toString()).startsWith("https://review.example/trusted-human/onboarding/?challenge=");
    assertThat(prepared).doesNotContainKeys("configuration","credentialRef","stagingRef");
    assertThat(reviews.prepare(p,command,"retry-after-lost-response")).isEqualTo(prepared);
    assertThat(reviews.read(p,"review-test",version)).isEqualTo(prepared);
    assertThat(db.sql("select count(*) from ouf_onboarding.approval_challenge").query(Long.class).single()).isOne();
    assertThat(db.sql("select count(*) from ouf_onboarding.approval_decision").query(Long.class).single()).isZero();
    assertThatThrownBy(()->reviews.prepare(p,new ManagedOnboardingReviewService.Prepare("review-test",version,1,"changed-key",null),"c")).hasMessage("ONB_REVIEW_COMMAND_CONFLICT");
    UUID challenge=(UUID)prepared.get("challengeId");String hash=prepared.get("configurationHash").toString();
    assertThat(reviews.humanCard(p,challenge)).containsKey("configuration");
    assertThatThrownBy(()->reviews.humanAction(p,challenge,"confirm",new ManagedOnboardingReviewService.Decision(hash,1),"test-auth")).hasMessage("ONB_REVIEW_STALE_CARD");
    var approved=reviews.humanAction(p,challenge,"confirm",new ManagedOnboardingReviewService.Decision(hash,2),"test-auth");
    assertThat(approved).containsEntry("state","APPROVED").containsEntry("humanApprovalRequired",false);
    assertThatThrownBy(()->reviews.humanAction(p,challenge,"activate",new ManagedOnboardingReviewService.Decision(hash,3),"test-auth")).hasMessageContaining("Ingestion Runtime");
    assertThat(db.sql("select count(*) from ouf_onboarding.published_configuration").query(Long.class).single()).isZero();
    assertThat(reviews.read(p,"review-test",version)).containsEntry("state","APPROVED");
    when(policy.authorizeActive(any(),any(),anyString(),anyString(),any()).allowed()).thenReturn(false);
    assertThatThrownBy(()->reviews.read(p,"review-test",version)).hasMessage("ONB_REVIEW_AUTHORIZATION_DENIED");
  }
  @Test void assetOwnershipAndTenantCannotBeChangedByAReviewRetry(){
    UUID version=draft();var command=new ManagedOnboardingReviewService.Prepare("review-test",version,1,"prepare:one",null);
    assertThatThrownBy(()->reviews.prepare(principal("test-tenant","other-human"),command,"c")).hasMessageContaining("not owned");
    reviews.prepare(principal("test-tenant","test-human"),command,"c");
    assertThatThrownBy(()->reviews.prepare(principal("other-tenant","test-human"),command,"c")).hasMessage("ONB_REVIEW_COMMAND_CONFLICT");
  }
  @SuppressWarnings("unchecked") private UUID draft(){
    byte[] csv="id,name\n1,Test\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    UUID asset=(UUID)files.register(null,"object://fixture/review.csv",hashes.ofBytes(csv),"text/csv",csv.length,"test-human","retention://test").get("asset_id");
    UUID profile=(UUID)files.profile(asset,csv,"object://fixture/review-sample.json").get("profile_id");
    var draft=files.onboard(asset,profile,"review-test","Test CSV","Test owner","urn:test:Object",List.of("test@1"),List.of("id"),human,"c");
    UUID version=(UUID)draft.get("onboarding_version_id");
    var config=(Map<String,Object>)onboarding.version("review-test",version).get("configuration");
    var runtime=(Map<String,Object>)((Map<String,Object>)config.get("extractionProfile")).get("runtime");
    runtime.put("execution",Map.of("acquisitionMode","INTERNAL_MANAGED_CSV","adapterId","managed-csv","adapterRuntimeVersion","1","sourceSchemaRef","test-schema","sourceSchemaId","test-schema","sourceSchemaVersion","1","semanticPublicationSetRef","test-set","adapterProfileRef","test-profile","observationPolicy","CONTENT_HASH"));
    runtime.put("udp",Map.of("resolution",Map.of("strategyId","EXACT_SOURCE_KEY"),"materialization",Map.of("policyRef","test-policy")));
    config.put("semanticReferenceBindings",List.of(Map.of("semanticId","test","semanticVersion","1","revisionId","test-revision","publicationSetId","test-set")));
    onboarding.patchVersion("review-test",version,0,config,human,"test-complete-configuration");return version;
  }
  private static String required(String name){return Objects.requireNonNull(System.getenv(name),name+" required");}
}
