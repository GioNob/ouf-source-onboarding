package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import it.comune.trieste.ouf.authorization.*;
import it.comune.trieste.ouf.onboarding.application.*;
import it.comune.trieste.ouf.onboarding.authorization.AuthorizationPolicyRegistry;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real PostgreSQL, proxied service and audited authorization registry; no policy mock. */
@SpringBootTest(properties="ouf.authorization.ths.public-origin=https://review.example")
class IngestionConfigurationAuditRuntimeTest {
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
    r.add("spring.datasource.url",()->required("OUF_ONB_DB_URL"));
    r.add("spring.datasource.username",()->required("OUF_ONB_DB_USER"));
    r.add("spring.datasource.password",()->required("OUF_ONB_DB_PASSWORD"));
  }
  @Autowired ManagedOnboardingReviewService reviews;
  @Autowired ManagedFileService files;
  @Autowired OnboardingService onboarding;
  @Autowired CanonicalHash hashes;
  @Autowired JdbcClient db;
  @Autowired AuthorizationPolicyRegistry policy;
  @Autowired PlatformTransactionManager transactions;
  private final OnboardingService.Actor human=new OnboardingService.Actor("test-human","HUMAN_USER");
  @BeforeEach void clean(){
    db.sql("truncate ouf_onboarding.source,ouf_onboarding.managed_file_asset,ouf_authorization.authorization_decision_audit,ouf_authorization.active_policy_bundle,ouf_authorization.policy_bundle restart identity cascade").update();
  }
  private PrincipalContext principal(String subject,PrincipalContext.ActorType type,String client,Set<String> scopes){
    return new PrincipalContext(subject,"test-tenant",type,client,"test-acr","https://issuer.example","test-audience",scopes,
      new PrincipalContext.IdentityClaims(Set.of(),"test-acr",Set.of(),null));
  }
  @Test void approvedWorkloadReadCommitsAuthorizationAuditWithoutChangingApprovalOrConfiguration(){
    Instant now=Instant.now();
    String prepare=ManagedOnboardingReviewService.PREPARE,read="ouf.onboarding.configuration.read";
    policy.publishAndActivate(new AuthorizationPolicy.PolicyBundle("read-audit-regression",1,now,
      List.of(new AuthorizationPolicy.CapabilityDescriptor(prepare,"COMMAND",prepare,Set.of(PrincipalContext.ActorType.HUMAN)),
              new AuthorizationPolicy.CapabilityDescriptor(read,"READ",read,Set.of(PrincipalContext.ActorType.SERVICE))),
      List.of(new AuthorizationPolicy.Grant("prepare",prepare,"test-tenant","test-human",null,null,now.minusSeconds(60),now.plusSeconds(3600)),
              new AuthorizationPolicy.Grant("read",read,"test-tenant","service-user","test-ingestion",null,now.minusSeconds(60),now.plusSeconds(3600),
                new AuthorizationPolicy.GrantConstraints("ALLOW",null,"published-configuration","review-test",Map.of("module","ONBOARDING"),Set.of(),Set.of(),null,Set.of(),null)))),"fixture-policy-admin");
    UUID version=draft();
    var actor=principal("test-human",PrincipalContext.ActorType.HUMAN,"test-mcp",Set.of(prepare));
    var prepared=reviews.prepare(actor,new ManagedOnboardingReviewService.Prepare("review-test",version,1,"prepare:one",null),"fixture");
    UUID challenge=(UUID)prepared.get("challengeId");String hash=prepared.get("configurationHash").toString();
    onboarding.confirm("review-test",version,challenge,human,"fixture","test-auth");
    var service=principal("service-user",PrincipalContext.ActorType.SERVICE,"test-ingestion",Set.of(read));
    var active=policy.load("read-audit-regression",1);
    assertThat(AuthorizationPolicy.evaluate(active,service,
      new ResourceContext("source","review-test","test-tenant",null,Map.of("onboardingVersionId",version.toString())),
      read,"READ",Instant.now()).decisionCode()).isEqualTo("NO_APPLICABLE_GRANT");
    assertThat(AuthorizationPolicy.evaluate(active,service,
      new ResourceContext("published-configuration","review-test","test-tenant",null,Map.of("module","OTHER")),
      read,"READ",Instant.now()).allowed()).isFalse();
    assertThat(AuthorizationPolicy.evaluate(active,service,
      new ResourceContext("published-configuration","another-source","test-tenant",null,Map.of("module","ONBOARDING")),
      read,"READ",Instant.now()).allowed()).isFalse();
    var before=onboarding.version("review-test",version);
    long auditBefore=policy.decisionAuditCount("test-tenant");

    // Reproduce the rejected INSERT under the original read-only boundary.
    var readOnly=new TransactionTemplate(transactions);readOnly.setReadOnly(true);
    Throwable failure=catchThrowable(()->readOnly.execute(status->reviews.ingestionConfiguration(service,"review-test",version,hash)));
    assertThat(failure).isNotNull();
    SQLException sql=null;
    for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof SQLException problem){sql=problem;break;}
    assertThat(sql!=null).isTrue();assertThat(sql.getSQLState()).isEqualTo("25006");
    assertThat(policy.decisionAuditCount("test-tenant")).isEqualTo(auditBefore);

    assertThat(reviews.ingestionConfiguration(service,"review-test",version,hash))
      .containsEntry("state","APPROVED").containsEntry("configuration_hash",hash);
    assertThat(policy.decisionAuditCount("test-tenant")).isEqualTo(auditBefore+1);
    assertThat(onboarding.version("review-test",version)).isEqualTo(before);
    assertThat(onboarding.approvalCard(challenge)).containsEntry("status","CONFIRMED");
    assertThat(db.sql("select count(*) from ouf_onboarding.approval_decision").query(Long.class).single()).isOne();
    assertThat(db.sql("select count(*) from ouf_onboarding.consumer_compatibility_attestation").query(Long.class).single()).isZero();
  }
  private UUID draft(){
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
