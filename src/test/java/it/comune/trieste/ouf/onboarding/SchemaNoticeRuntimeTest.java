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
class SchemaNoticeRuntimeTest {
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->required("OUF_ONB_DB_URL"));r.add("spring.datasource.username",()->required("OUF_ONB_DB_USER"));r.add("spring.datasource.password",()->required("OUF_ONB_DB_PASSWORD"));}
  @Autowired SourceRevisionService revisions; @Autowired OnboardingService onboarding;
  @Autowired SchemaSurveillanceService surveillance;
  @Autowired SchemaNoticeReader notices;
  @Autowired ManagedOnboardingReviewService reviews; @Autowired CanonicalHash hashes; @Autowired JdbcClient db;
  @MockitoBean(answers=Answers.RETURNS_DEEP_STUBS) AuthorizationPolicyRegistry policy;
  private final OnboardingService.Actor human=new OnboardingService.Actor("test-human","HUMAN_USER");
  private PrincipalContext principal(String tenant,String subject){return new PrincipalContext(subject,tenant,PrincipalContext.ActorType.HUMAN,"test-mcp","test-acr","https://issuer.example","test-audience",Set.of(SourceRevisionService.PREPARE,SourceRevisionService.READ,"ouf.onboarding.configuration.write"),new PrincipalContext.IdentityClaims(Set.of(),"test-acr",Set.of(),null));}
  @BeforeEach void clean(){db.sql("truncate ouf_onboarding.source,ouf_onboarding.managed_file_asset restart identity cascade").update();when(policy.authorizeActive(any(),any(),anyString(),anyString(),any()).allowed()).thenReturn(true);}
  private PrincipalContext noticeViewer(String subject) {
    return new PrincipalContext(subject,"tenant",PrincipalContext.ActorType.HUMAN,null,"test-auth","issuer","aud",
        Set.of(SchemaNoticeReader.READ));
  }
  private static final java.time.Instant OBSERVED=java.time.Instant.parse("2026-11-21T09:00:00Z");
  private SchemaObservation opening(String key) {
    var fields=List.of(Map.of("name","id","type","string","nullable",false),
        Map.of("name","new","type","integer","nullable",true));
    return new SchemaObservation(key,"OPEN","schema://s/1",
        Map.of("schemaVersion","2","fieldsSha256",hashes.of(fields),"fields",fields),
        List.of(new SchemaObservation.Difference("new","ADDED")),"checkpoint:1",1L,OBSERVED,null);
  }
  private SchemaObservation event(String key,String state,long polls) {
    return new SchemaObservation(key,state,null,null,null,null,polls,OBSERVED.plusSeconds(polls),null);
  }
  @Test void concurrentOpeningMonotoneUpdatesAndTerminalReplay() throws Exception {
    active();
    var open=opening("incident-1");
    try(var executor=Executors.newFixedThreadPool(4)) {
      var start=new CountDownLatch(1);
      var tasks=new ArrayList<Future<Map<String,Object>>>();
      for(int i=0;i<4;i++) tasks.add(executor.submit(()->{start.await();return surveillance.observe("t","s",open);}));
      start.countDown();
      var receipt=tasks.getFirst().get(15,TimeUnit.SECONDS);
      for(var task:tasks) assertThat(task.get(15,TimeUnit.SECONDS)).isEqualTo(receipt);
    }
    assertThat(surveillance.issues("t","s")).hasSize(1);
    var updated=surveillance.observe("t","s",event("incident-1","UPDATE",12));
    assertThat(updated).containsEntry("observation_polls",12L);
    assertThat(surveillance.observe("t","s",event("incident-1","UPDATE",12))).isEqualTo(updated);
    assertThat(surveillance.observe("t","s",event("incident-1","UPDATE",2))).isEqualTo(updated);
    var closed=surveillance.observe("t","s",event("incident-1","RESOLVED",13));
    assertThat(closed).containsEntry("state","RESOLVED").containsEntry("resolution_reason","SCHEMA_RETURNED");
    assertThat(surveillance.observe("t","s",open)).isEqualTo(closed);
    assertThat(surveillance.observe("t","s",event("incident-1","UPDATE",14))).isEqualTo(closed);
    assertThat(surveillance.observe("t","s",event("incident-1","RESOLVED",13))).isEqualTo(closed);
    assertThat(surveillance.observe("other","s",open).get("issue_id")).isNotEqualTo(closed.get("issue_id"));
  }
  @Test void noticePageFiltersBeforeLookaheadAndRechecksCurrentPolicy() {
    active();
    onboarding.createSource("private","Other source","EXTERNAL_API","PULL","owner",Map.of(),human,"fixture");
    var p=noticeViewer("reviewer");
    var cap=new AuthorizationPolicy.CapabilityDescriptor(SourceRevisionService.PREPARE,"COMMAND",
        SourceRevisionService.PREPARE,Set.of(PrincipalContext.ActorType.HUMAN));
    var scope=new AuthorizationPolicy.GrantConstraints("ALLOW",null,"source","s",Map.of(),Set.of(),Set.of(),null,Set.of(),null);
    var grant=new AuthorizationPolicy.Grant("g",cap.capabilityId(),p.tenantId(),p.subjectId(),null,null,
        java.time.Instant.EPOCH,java.time.Instant.parse("2099-01-01T00:00:00Z"),scope);
    var read=new AuthorizationPolicy.CapabilityDescriptor(SchemaNoticeReader.READ,"READ",
        SchemaNoticeReader.READ,Set.of(PrincipalContext.ActorType.HUMAN));
    var allow=new AuthorizationPolicy.PolicyBundle("test",1,OBSERVED,List.of(cap,read),List.of(grant));
    when(policy.active()).thenReturn(Optional.of(new AuthorizationPolicyRegistry.ActiveBundle("test",1,OBSERVED)));
    when(policy.load("test",1)).thenReturn(allow);
    for(int index=0;index<65;index++) surveillance.observe("tenant","private",opening("hidden-"+index));
    surveillance.observe("elsewhere","s",opening("foreign"));
    surveillance.observe("tenant","s",opening("one"));
    var first=notices.read(p,"OPEN",1,null);
    assertThat(first.notices()).singleElement().satisfies(n->assertThat(n.sourceId()).isEqualTo("s"));
    assertThat(first.hasMore()).isFalse();assertThat(first.next()).isNull();
    surveillance.observe("tenant","s",opening("two"));
    first=notices.read(p,"OPEN",1,null);
    assertThat(first.hasMore()).isTrue();
    var second=notices.read(p,"OPEN",1,first.next());
    assertThat(second.notices()).hasSize(1);assertThat(second.notices().getFirst().noticeId()).isNotEqualTo(first.next());
    assertThat(second.hasMore()).isFalse();
    var empty=new SchemaNoticeReader.Page(List.of(),null,false);
    assertThat(notices.read(noticeViewer("other"),"OPEN",1,null)).isEqualTo(empty);
    assertThatThrownBy(()->notices.read(principal("tenant","reviewer"),"OPEN",1,null)).hasMessageContaining("read scope");
    when(policy.active()).thenReturn(Optional.of(new AuthorizationPolicyRegistry.ActiveBundle("test",2,OBSERVED)));
    when(policy.load("test",2)).thenReturn(new AuthorizationPolicy.PolicyBundle("test",2,OBSERVED,List.of(cap,read),List.of()));
    assertThat(notices.read(p,"OPEN",1,first.next())).isEqualTo(empty);
  }
  @Test void onlyTheFrozenCorrectionCanPublishAndCloseItsNotice() {
    onboarding.createSource("s","API","EXTERNAL_API","PULL","owner",Map.of(),human,"fixture");
    var config=SourceRevisionRuntimeTest.fixture();
    var extraction=new LinkedHashMap<>(section(config,"extractionProfile"));
    var runtime=new LinkedHashMap<>(section(extraction,"runtime"));
    var udp=new LinkedHashMap<>(section(runtime,"udp"));
    var material=new LinkedHashMap<>(section(udp,"materialization"));
    material.put("properties",List.of("urn:id","urn:name").stream().map(iri->Map.of("propertyIri",iri,
        "sourceField",iri,"datatype","http://www.w3.org/2001/XMLSchema#string","accessLabel","RESTRICTED",
        "authorityOrder",List.of("s"))).toList());
    udp.put("materialization",material);
    runtime.put("udp",udp);
    runtime.put("execution",Map.of("sourceSchemaRef","schema://s/1","sourceSchemaVersion","1"));
    extraction.put("projection",Map.of("T",List.of("id","obsolete")));
    extraction.put("sourceUnitProfile",Map.of("origin",Map.of("collection","fixture"),
        "readParameters",Map.of("api",Map.of("schemaVersion","1","fieldsSha256","prior","mode","CURSOR"))));
    var mapping=new LinkedHashMap<>(section(config,"semanticMapping"));
    mapping.put("propertyMappings",List.of(Map.of("sourceField","id","targetPropertyIri","urn:id","transform","IDENTITY"),
        Map.of("sourceField","obsolete","targetPropertyIri","urn:name","transform","IDENTITY")));
    config.put("semanticMapping",mapping);
    extraction.put("runtime",runtime);config.put("extractionProfile",extraction);
    config.put("sourceObjectIdentityPolicy",Map.of("strategy","NATIVE_KEY","sourceFields",List.of("id"),
        "normalizationRuleRef","normalization://native-key/1"));
    UUID parent=(UUID)onboarding.createVersion("s",config,human,"fixture").get("onboarding_version_id");
    String parentHash=hashes.of(configuration(parent));
    db.sql("update ouf_onboarding.onboarding_version set state='ACTIVE',configuration_hash=:h,frozen_at=transaction_timestamp() where onboarding_version_id=:v")
        .param("h",parentHash).param("v",parent).update();
    var open=opening("incident");
    var mixed=new SchemaObservation(open.incidentKey(),open.state(),open.expectedSchemaRef(),open.observedSchema(),
        List.of(new SchemaObservation.Difference("obsolete","REMOVED"),new SchemaObservation.Difference("id","TYPE_CHANGED"),
            new SchemaObservation.Difference("new","ADDED")),open.frozenCheckpointRef(),1L,OBSERVED,null);
    var notice=surveillance.observe("tenant","s",mixed);
    String ref=(String)notice.get("observed_schema_ref");
    var p=principal("tenant","reviewer");
    var cmd=new SourceRevisionService.Prepare("s",parent,0,parentHash,"correction",SourceRevisionService.EFFECT,null,null,null,ref);
    assertThatThrownBy(()->revisions.prepare(principal("other","reviewer"),cmd,"wrong")).hasMessageContaining("No matching");
    var receipt=revisions.prepare(p,cmd,"prepare");
    UUID version=(UUID)receipt.get("onboardingVersionId"),challenge=(UUID)receipt.get("challengeId");
    assertThat(revisions.prepare(p,cmd,"repeat")).isEqualTo(receipt);
    assertThatThrownBy(()->onboarding.activate("s",parent,human,"unrelated")).hasMessageContaining("schema drift");
    String hash=(String)receipt.get("configurationHash");
    reviews.humanAction(p,challenge,"confirm",new ManagedOnboardingReviewService.Decision(hash,2),"test-auth");
    assertThatThrownBy(()->onboarding.activate("s",version,human,"early")).hasMessageContaining("Ingestion Runtime");
    assertThat(surveillance.issues("tenant","s").getFirst()).containsEntry("state","OPEN");
    onboarding.attestIngestionCompatibility("s",version,true,"fixture",
        new OnboardingService.Actor("test-runtime","SERVICE",Set.of("ouf.ingestion.configuration.attest")),"attest");
    surveillance.observe("tenant","s",opening("different"));
    assertThatThrownBy(()->onboarding.activate("s",version,human,"other-incident")).hasMessageContaining("schema drift");
    surveillance.observe("tenant","s",event("different","RESOLVED",2));
    reviews.humanAction(p,challenge,"activate",new ManagedOnboardingReviewService.Decision(hash,3),"test-auth");
    assertThat(onboarding.version("s",version)).containsEntry("state","ACTIVE");
    var approved=section(section(section(configuration(version),"extractionProfile"),"runtime"),"execution");
    var revised=section(configuration(version),"extractionProfile");
    assertThat(section(revised,"projection")).containsEntry("T",List.of("id"));
    assertThat(section(section(section(revised,"sourceUnitProfile"),"readParameters"),"api"))
        .containsEntry("schemaVersion","2").containsEntry("mode","CURSOR");
    assertThat(approved).containsEntry("sourceSchemaVersion","2").containsEntry("sourceSchemaRef",ref);
    assertThat(section(approved,"sourceSchema")).containsEntry("fieldsSha256",opening("incident").observedSchema().get("fieldsSha256"));
    var closed=surveillance.observe("tenant","s",event("incident","RESOLVED",20));
    assertThat(closed).containsEntry("resolution_reason","REVISION_ACTIVATED").containsEntry("state","RESOLVED");
    assertThat(db.sql("select count(*) from ouf_onboarding.published_configuration where active").query(Long.class).single()).isOne();
  }
  @Test void additiveManagedReportRetainsHistoricalGate() {
    onboarding.createSource("managed","File","INTERNAL_MANAGED","MANAGED","owner",Map.of(),human,"fixture");
    surveillance.report("managed","schema:2",null,"hash","ADDITIVE",Map.of());
    surveillance.assertActivationAllowed("managed",null);
  }

  @Test void sameObservedRefInAnUnlinkedApprovedVersionCannotCloseAnAdditivePullNotice() {
    UUID parent=active();
    var issue=surveillance.observe("tenant","s",opening("additive"));
    var config=configuration(parent);
    section(config,"bundle").put("schemaRef",issue.get("observed_schema_ref"));
    UUID version=(UUID)onboarding.createVersion("s",config,human,"draft").get("onboarding_version_id");
    onboarding.submit("s",version,0,human,"submit");
    UUID challenge=(UUID)onboarding.createChallenge("s",version,human,"review").get("challenge_id");
    onboarding.confirm("s",version,challenge,human,"confirm","test-auth");
    onboarding.attestIngestionCompatibility("s",version,true,"fixture",
        new OnboardingService.Actor("runtime","SERVICE",Set.of("ouf.ingestion.configuration.attest")),"attest");
    assertThatThrownBy(()->onboarding.activate("s",version,human,"activate")).hasMessageContaining("schema drift");
    assertThat(onboarding.version("s",version)).containsEntry("state","APPROVED");
    assertThat(surveillance.issues("tenant","s").getFirst()).containsEntry("state","OPEN");
  }

  private UUID active(){
    onboarding.createSource("s","Fixture API","EXTERNAL_API","PULL","Test owner",Map.of(),human,"c");
    var config=SourceRevisionRuntimeTest.fixture();UUID version=(UUID)onboarding.createVersion("s",config,human,"c").get("onboarding_version_id");
    db.sql("update ouf_onboarding.onboarding_version set state='ACTIVE',configuration_hash=:h,frozen_at=transaction_timestamp() where onboarding_version_id=:v").param("h",hashes.of(config)).param("v",version).update();return version;
  }
  @SuppressWarnings("unchecked") private Map<String,Object> configuration(UUID version){return (Map<String,Object>)onboarding.version("s",version).get("configuration");}
  @SuppressWarnings("unchecked") private static Map<String,Object> section(Map<String,Object> map,String key){
    return (Map<String,Object>)map.get(key);
  }
  private static String required(String name){return Objects.requireNonNull(System.getenv(name),name+" required");}
}
