package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import it.comune.trieste.ouf.authorization.PrincipalContext;
import it.comune.trieste.ouf.onboarding.application.*;
import it.comune.trieste.ouf.onboarding.authorization.AuthorizationPolicyRegistry;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Task-62f, added in review (DN-0): the real XLSX v1 profile reaches the compiled unit proposal and the THS card,
 * and a failed operation of a multi-operation workbook keeps the HUMAN recovery (contract ouf.extraction-unit.v1 §8.1, §11).
 * Authorization allow is a TEST fixture, never a deployment grant.
 */
@SpringBootTest(properties="ouf.authorization.ths.public-origin=https://review.example")
class XlsxUnitReviewRuntimeTest {
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
    r.add("spring.datasource.url",()->System.getenv("OUF_ONB_DB_URL"));
    r.add("spring.datasource.username",()->System.getenv("OUF_ONB_DB_USER"));
    r.add("spring.datasource.password",()->System.getenv("OUF_ONB_DB_PASSWORD"));
    r.add("ouf.onboarding.source-time-zone",()->"Europe/Rome");
  }
  @Autowired ManagedOnboardingReviewService reviews; @Autowired ManagedFileService files;
  @Autowired OnboardingService onboarding; @Autowired CanonicalHash hashes; @Autowired JdbcClient db;
  @MockitoBean(answers=Answers.RETURNS_DEEP_STUBS) AuthorizationPolicyRegistry policy;
  @MockitoBean UdpIdentityActivationVerifier finalUdp;
  @MockitoBean DatatypeResolver registryReader;
  static final String XLSX="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
  static final String TENANT="test-tenant",SUBJECT="test-human";

  @BeforeEach void clean(){
    db.sql("truncate ouf_onboarding.source,ouf_onboarding.managed_file_asset,ouf_onboarding.file_profile_job restart identity cascade").update();
    when(policy.authorizeActive(any(),any(),anyString(),anyString(),any()).allowed()).thenReturn(true);
  }

  private UUID register(byte[] bytes){
    return (UUID)files.registerOwned(null,"object://fixture/edicole.xlsx",hashes.ofBytes(bytes),XLSX,bytes.length,SUBJECT,"retention://test",TENANT).get("asset_id");
  }
  private Map<String,Object> run(UUID job,byte[] bytes){
    var lease=files.claimProfile("test",Duration.ofMinutes(2),Instant.now()).orElseThrow();
    assertThat(lease.get("job_id")).isEqualTo(job);
    return files.completeProfile(job,"test",((Number)lease.get("generation")).longValue(),bytes,"object://samples/fixture");
  }

  @Test @SuppressWarnings("unchecked") void f62f_11_realSheetReachesCompiledProposalAndThsCard() throws Exception {
    byte[] bytes=XlsxProfileOperationRuntimeTest.workbook();
    String source="xlsx-review";
    UUID asset=register(bytes);
    UUID profile=(UUID)run((UUID)files.requestProfile(asset,"k1",null).get("jobId"),bytes).get("resultRef");
    var unit=files.unitView(null,profile,TENANT,SUBJECT,"UNIT","sheet:0",0,128,null);
    var pin=new SourceUnitProfiles.Reference(profile,((Number)unit.get("profileVersion")).intValue(),"sheet:0",(String)unit.get("schemaHash"));
    String semanticId="https://example.org/onto/Place",property="urn:fixture:name";
    var adopted=new ManagedFileExecutionProposal.SemanticBinding(semanticId,"1.0.0",UUID.randomUUID().toString(),UUID.randomUUID().toString());
    var fields=List.of(new ManagedFileService.FieldDecision("codice","INCLUDE","OPEN",property,"IDENTITY",null,null,null),
        new ManagedFileService.FieldDecision("aperta il","EXCLUDE","OPEN",null,null,null,null,null));
    var draft=files.onboardIdempotent(null,null,source,"Edicole","Mapper",semanticId,List.of(semanticId+"@"+adopted.semanticVersion()),
        List.of("codice"),fields,null,new OnboardingService.Actor(SUBJECT,"HUMAN_USER"),"create","create:xlsx",TENANT,null,null,null,null,pin);
    UUID version=(UUID)draft.get("onboardingVersionId");
    var signal=Map.of("id",property,"semanticRef",property+"@"+adopted.publicationSetId(),"comparator","TEXT_V1",
        "excludesOnDisagreement",false,"uniqueWithinScope",false,"assertionRef","urn:fixture:signal");
    var rule=Map.of("id","name-rule","signalIds",List.of(property),"assertionRef","urn:fixture:rule");
    var identity=Map.of("ref","urn:fixture:identity","version","1","tenantId",TENANT,"sourceId",source,
        "canonicalClass",semanticId,"allowAutoNew",false,"maxCandidates",10,"signals",List.of(signal),"sufficientRules",List.of(rule));
    var materialized=Map.of("sourceField",property,"propertyIri",property,"datatype","http://www.w3.org/2001/XMLSchema#string",
        "accessLabel","OPEN","authorityOrder",List.of(source));
    var udp=new LinkedHashMap<String,Object>(Map.of("resolution",Map.of("strategyId","GOVERNED_IDENTITY","strategyVersion","1",
        "policyRef","urn:fixture:identity","governedIdentity",identity),"materialization",Map.of("policyRef","urn:fixture:authority",
        "properties",List.of(materialized),"bitemporalProperties",List.of(),"checkpointInterval",1)));
    var proposal=new ManagedFileExecutionProposal.Proposal(List.of(adopted),adopted.publicationSetId(),udp,
        new ManagedFileExecutionProposal.Limits(100,2,1000),"ouf.managed-unit-execution-proposal.v1");
    var mapper=new PrincipalContext(SUBJECT,TENANT,PrincipalContext.ActorType.HUMAN,"test-mcp","test-acr","https://issuer.example","test-audience",
        Set.of(ManagedOnboardingReviewService.PREPARE,ManagedOnboardingReviewService.READ,"ouf.managed-source.onboarding.create","ouf.onboarding.configuration.write"),
        new PrincipalContext.IdentityClaims(Set.of(),"test-acr",Set.of(),null));
    var review=reviews.prepare(mapper,new ManagedOnboardingReviewService.Prepare(source,version,0,"review:xlsx",proposal,null,pin),"prepare");
    assertThat(review).containsEntry("state","IN_REVIEW");
    var config=(Map<String,Object>)onboarding.version(source,version).get("configuration");
    var runtime=(Map<String,Object>)((Map<String,Object>)config.get("extractionProfile")).get("runtime");
    var execution=(Map<String,Object>)runtime.get("execution");
    // Compiled unit proposal (§11): readParameters.xlsx is the binding seat, identical in runtime and in execution.
    assertThat(execution).containsEntry("adapterId","managed-xlsx-v1").containsEntry("acquisitionMode","INTERNAL_MANAGED_XLSX")
        .containsEntry("adapterProfileRef","adapter://managed-xlsx/1").containsEntry("readParameters",runtime.get("readParameters"))
        .doesNotContainKeys("csvDelimiter","sheet","headerRow");
    assertThat(runtime.get("readParameters")).isEqualTo(unit.get("readParameters"));
    // D-081: the THS card shows the worksheet, its header row, date system, zone and cell rule.
    var card=reviews.humanCard(mapper,UUID.fromString(String.valueOf(review.get("challengeId"))));
    var file=(Map<String,Object>)((Map<String,Object>)card.get("reviewSnapshot")).get("file");
    assertThat(file).containsEntry("format","XLSX").containsEntry("rows",3).containsEntry("columns",2);
    assertThat((String)file.get("unitDescription")).contains("Foglio «Edicole»").contains("riga 1").contains("sistema 1900")
        .contains("Europe/Rome").contains("ouf.xlsx-cell-text.v1");
  }

  @Test void f62f_12_failedOperationOfMultiOperationWorkbookIsRecoveredByHuman() throws Exception {
    byte[] bytes=XlsxProfileOperationRuntimeTest.workbook();
    UUID asset=register(bytes);
    run((UUID)files.requestProfile(asset,"k1",null).get("jobId"),bytes);
    UUID job=(UUID)files.requestProfile(asset,"k2",Map.of("sheet:3",3)).get("jobId");
    var lease=files.claimProfile("test",Duration.ofMinutes(2),Instant.now()).orElseThrow();
    files.failProfile(job,"test",((Number)lease.get("generation")).longValue(),"ONB_FILE_PROFILE_FAILED","Gateway object read failed with status 403",Duration.ZERO,false);
    assertThat(files.asset(asset)).containsEntry("status","QUARANTINED");
    var principal=new PrincipalContext(SUBJECT,TENANT,PrincipalContext.ActorType.HUMAN,null,"fixture-auth","https://iam.test","ouf",Set.of());
    var actor=new OnboardingService.Actor(SUBJECT,"HUMAN_USER",Set.of("ouf.managed-source.file.profile","ouf.onboarding.quarantine.release"));
    var card=files.profileRecoveryCard(asset,job,principal,actor);
    // The budget is per operation: the other operation of the same workbook does not block the recovery (V48).
    var budget=files.grantProfileBudget(asset,job,principal,actor,new ManagedProfileJobs.BudgetDecision(((Number)card.get("expectedVersion")).longValue(),
        ((Number)card.get("expectedGeneration")).longValue(),(String)card.get("inputBindingHash"),(String)card.get("reviewHash"),"grant-two",
        "Dependency permission repaired; inspect the same immutable input",3,900));
    files.resumeProfile(asset,job,principal,actor,new ManagedProfileJobs.Resume(((Number)budget.get("expectedVersion")).longValue(),
        ((Number)budget.get("expectedGeneration")).longValue(),(String)budget.get("inputBindingHash"),(UUID)budget.get("sessionId")));
    run(job,bytes);
    assertThat(files.profileJob(asset,job)).containsEntry("state","SUCCEEDED");
    assertThat(files.asset(asset)).containsEntry("status","PROFILED");
  }
}
