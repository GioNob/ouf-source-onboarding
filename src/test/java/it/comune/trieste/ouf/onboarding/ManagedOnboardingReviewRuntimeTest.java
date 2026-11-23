package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.authorization.*;
import it.comune.trieste.ouf.onboarding.api.ManagedOnboardingReviewApi;
import it.comune.trieste.ouf.onboarding.api.PermissionDelegationVerifier;
import it.comune.trieste.ouf.onboarding.api.ProblemHandler;
import it.comune.trieste.ouf.onboarding.application.*;
import it.comune.trieste.ouf.onboarding.authorization.AuthorizationPolicyRegistry;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Real lifecycle/PG transactions; authorization allow/deny is a TEST fixture, never a deployment grant. */
@SpringBootTest(properties="ouf.authorization.ths.public-origin=https://review.example")
class ManagedOnboardingReviewRuntimeTest {
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->required("OUF_ONB_DB_URL"));r.add("spring.datasource.username",()->required("OUF_ONB_DB_USER"));r.add("spring.datasource.password",()->required("OUF_ONB_DB_PASSWORD"));}
  @Autowired ManagedOnboardingReviewService reviews; @Autowired ManagedFileService files;
  @Autowired OnboardingService onboarding; @Autowired CanonicalHash hashes; @Autowired JdbcClient db;
  @MockitoBean(answers=Answers.RETURNS_DEEP_STUBS) AuthorizationPolicyRegistry policy;
  private final OnboardingService.Actor human=new OnboardingService.Actor("test-human","HUMAN_USER");
  private PrincipalContext principal(String tenant,String subject){return new PrincipalContext(subject,tenant,PrincipalContext.ActorType.HUMAN,"test-mcp","test-acr","https://issuer.example","test-audience",Set.of(ManagedOnboardingReviewService.PREPARE,ManagedOnboardingReviewService.READ,"ouf.managed-source.onboarding.create","ouf.onboarding.configuration.write"),new PrincipalContext.IdentityClaims(Set.of(),"test-acr",Set.of(),null));}
  @BeforeEach void clean(){db.sql("truncate ouf_onboarding.source,ouf_onboarding.managed_file_asset restart identity cascade").update();when(policy.authorizeActive(any(),any(),anyString(),anyString(),any()).allowed()).thenReturn(true);}
  @MockitoBean UdpIdentityActivationVerifier finalUdp;
  private record Governed(PrincipalContext mapper,ManagedOnboardingReviewService.Prepare command,Map<String,Object> review){}
  private Governed governed(ManagedFileExecutionProposal.SemanticBinding adopted){
    String semanticId="https://example.org/onto/Place", property="urn:fixture:name";
    String tenant="test-tenant", source="review-test";
    // The proposed policy and mapping pin the same publication.
    byte[] csv="name,unused\nEdicola,1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    UUID asset=(UUID)files.registerOwned(null,"object://fixture/edicole.csv",hashes.ofBytes(csv),"text/csv",
        csv.length,human.subject(),"retention://test",tenant).get("asset_id");
    UUID profile=(UUID)files.profile(asset,csv,"object://fixture/edicole-sample.json").get("profile_id");
    var fields=List.of(new ManagedFileService.FieldDecision("name","INCLUDE","OPEN",property,"IDENTITY",null,null,null),
        new ManagedFileService.FieldDecision("unused","EXCLUDE","OPEN",null,null,null,null,null));
    var refs=List.of(semanticId+"@"+adopted.semanticVersion());
    var draft=files.onboardIdempotent(asset,profile,source,"Edicole","Mapper",semanticId,refs,List.of("name"),
        fields,null,human,"create","create:edicole",tenant);
    UUID version=(UUID)draft.get("onboardingVersionId");
    var signal=Map.of("id",property,"semanticRef",property+"@"+adopted.publicationSetId(),"comparator","TEXT_V1",
        "excludesOnDisagreement",false,"uniqueWithinScope",false,"assertionRef","urn:fixture:signal");
    var rule=Map.of("id","name-rule","signalIds",List.of(property),"assertionRef","urn:fixture:rule");
    var policy=Map.of("ref","urn:fixture:identity","version","1","tenantId",tenant,"sourceId",source,
        "canonicalClass",semanticId,"allowAutoNew",false,"maxCandidates",10,"signals",List.of(signal),"sufficientRules",List.of(rule));
    var materialized=Map.of("sourceField",property,"propertyIri",property,"datatype","http://www.w3.org/2001/XMLSchema#string",
        "accessLabel","OPEN","authorityOrder",List.of(source));
    var udp=Map.<String,Object>of("resolution",Map.of("strategyId","GOVERNED_IDENTITY","strategyVersion","1",
        "policyRef","urn:fixture:identity","governedIdentity",policy),"materialization",Map.of("policyRef","urn:fixture:authority",
        "properties",List.of(materialized),"bitemporalProperties",List.of(),"checkpointInterval",1));
    var proposal=new ManagedFileExecutionProposal.Proposal(List.of(adopted),adopted.publicationSetId(),udp,
        new ManagedFileExecutionProposal.Limits(100,2,1000));
    var mapper=principal(tenant,human.subject());
    var command=new ManagedOnboardingReviewService.Prepare(source,version,0,"review:edicole",proposal);
    return new Governed(mapper,command,reviews.prepare(mapper,command,"prepare"));
  }
  @MockitoBean DatatypeResolver registryReader;
  @Autowired ObjectMapper runtimeJson;

  @Test @SuppressWarnings("unchecked") void task52cRetainedInvalidCompositionReturnsItsDomainCode()throws Exception{
    var version=draft();
    var config=(Map<String,Object>)onboarding.version("review-test",version).get("configuration");
    ((Map<String,Object>)config.get("semanticMapping")).put("compositions",List.of(Map.of("kind","UNKNOWN")));
    onboarding.patchVersion("review-test",version,1,config,human,"test");
    var receipts=mock(PermissionDelegationVerifier.class);
    when(receipts.verifyManagedFile(any(),anyString(),anyString(),any()))
        .thenReturn(new PermissionDelegationVerifier.Delegated(principal("test-tenant",human.subject()),"test"));
    var http=MockMvcBuilders.standaloneSetup(new ManagedOnboardingReviewApi(receipts,reviews,runtimeJson))
        .setControllerAdvice(new ProblemHandler()).build();
    var body=ownerPost(http,"review-prepare",new ManagedOnboardingReviewService.Prepare("review-test",version,2,"bad",null),422);
    assertThat(body.required("code").asText()).isEqualTo("ONB_COMPOSITION_INVALID");
    assertThat(onboarding.version("review-test",version)).containsEntry("state","DRAFT").containsEntry("lock_version",2L);
    assertThat(db.sql("select count(*) from ouf_onboarding.approval_challenge").query(Long.class).single()).isZero();
  }

  private com.fasterxml.jackson.databind.JsonNode ownerPost(org.springframework.test.web.servlet.MockMvc http,
      String route,Object arguments,int status)throws Exception{
    byte[] envelope=runtimeJson.writeValueAsBytes(Map.of("Arguments",arguments,"CorrelationID",UUID.randomUUID().toString()));
    var response=http.perform(post("/api/internal/v1/onboarding/managed-file-mcp/"+route)
        .contentType("application/json").content(envelope)).andExpect(status().is(status)).andReturn().getResponse();
    return runtimeJson.readTree(response.getContentAsByteArray());
  }

  @Test void task52cPoiCsvDeclaresMissingProposalAndReplaysOneChallenge()throws Exception{
    poiCsv(false);
  }

  @Test void task52dReaderChecksAndSameAssetCorrection()throws Exception{
    poiCsv(true);
  }

  @Test void task52dBoundReadersReplayAndRespectSourceVisibility()throws Exception{
    String tenant="fixture-tenant",source="bound-fixture",cls="urn:fixture:Class",cap="ouf.managed-source.onboarding.create";
    var mapper=new PrincipalContext(human.subject(),tenant,PrincipalContext.ActorType.HUMAN,"test-mcp","1",
        "https://issuer.example","test",Set.of(cap,ManagedOnboardingReviewService.READ,"ouf.managed-source.file.upload"),
        new PrincipalContext.IdentityClaims(Set.of(),"1",Set.of(),null));
    byte[] csv="name\nvalue\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    UUID asset=(UUID)files.registerOwned(null,"object://fixture/bound.csv",hashes.ofBytes(csv),"text/csv",csv.length,
        human.subject(),"retention://test",tenant).get("asset_id");
    UUID profile=(UUID)files.profile(asset,csv,"object://fixture/bound-sample").get("profile_id");
    var binding=new ManagedFileExecutionProposal.SemanticBinding(cls,"1",UUID.randomUUID().toString(),UUID.randomUUID().toString());
    when(registryReader.resolve(cls,binding.revisionId(),binding.publicationSetId())).thenReturn(Map.of("semantic_id",cls,
        "semantic_version","1","publication_set_id",binding.publicationSetId(),"revision_id",binding.revisionId(),"artifact_type","CLASS"));
    var receipts=mock(PermissionDelegationVerifier.class);
    when(receipts.verifyManagedFile(any(),anyString(),anyString(),any()))
        .thenReturn(new PermissionDelegationVerifier.Delegated(mapper,"checked"));
    var http=MockMvcBuilders.standaloneSetup(new it.comune.trieste.ouf.onboarding.api.ManagedFileMcpApi(receipts,files,runtimeJson,null))
        .setControllerAdvice(new ProblemHandler()).build();
    var create=new LinkedHashMap<String,Object>(Map.of("assetId",asset,"profileId",profile,"sourceId",source,
        "name","Fixture","owner","Fixture","targetClassIri",cls,"semanticRefs",List.of(cls+"@1"),"sourceObjectKeyFields",List.of("name"),
        "semanticBindings",List.of(binding),"fields",List.of(Map.of("fieldName","name","extractionDecision","INCLUDE",
            "targetPropertyIri","urn:fixture:name","transform","TEXT_NFC_TRIM_V1","dataAccessLabel","OPEN"))));
    var checked=ownerPost(http,"create",create,200);
    assertThat(checked.has("deferredReaderChecks")).isFalse();
    assertThat(ownerPost(http,"create",create,200)).isEqualTo(checked);
    verify(registryReader).resolve(cls,binding.revisionId(),binding.publicationSetId());
    create.remove("semanticBindings");
    assertThat(ownerPost(http,"create",create,409).required("code").asText()).isEqualTo("ONB_FILE_IDEMPOTENCY_CONFLICT");
    when(receipts.verifyManagedFile(any(),anyString(),anyString(),any()))
        .thenReturn(new PermissionDelegationVerifier.Delegated(mapper,"other"));
    create.put("sourceId","other-fixture");
    var visible=ownerPost(http,"create",create,409);
    assertThat(visible.required("code").asText()).isEqualTo("ONB_REVISION_ASSET_SOURCE_CONFLICT");
    assertThat(visible.required("boundSourceId").asText()).isEqualTo(source);
    assertThat(visible.required("resolutions")).isEqualTo(runtimeJson.valueToTree(List.of("CREATE_REVISION","UPLOAD_NEW_FILE")));
    var denied=mock(AuthorizationPolicy.AuthorizationDecision.class);
    when(policy.authorizeActive(any(),any(),eq(ManagedOnboardingReviewService.READ),eq("READ"),any())).thenReturn(denied);
    var hidden=ownerPost(http,"create",create,409);
    assertThat(hidden.required("code").asText()).isEqualTo("ONB_REVISION_ASSET_SOURCE_CONFLICT");
    assertThat(hidden.required("resolutions")).isEqualTo(runtimeJson.valueToTree(List.of("UPLOAD_NEW_FILE")));
    assertThat(hidden.toString()).doesNotContain(source,"object://","boundSourceId");
    when(policy.authorizeActive(any(),any(),eq("ouf.managed-source.file.upload"),eq("COMMAND"),any())).thenReturn(denied);
    assertThat(ownerPost(http,"create",create,409).required("resolutions").size()).isZero();
    assertThat(db.sql("select count(*) from ouf_onboarding.managed_file_onboarding_attempt").query(Long.class).single()).isOne();

    // Interleave a different authorized correction while the first command is resolving a pin.
    create.put("sourceId",source);
    create.put("semanticBindings",List.of(binding));
    doAnswer(call->{
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      var changed=List.of(new ManagedFileService.FieldDecision("name","INCLUDE","OPEN","urn:fixture:name","IDENTITY",null,null,null));
      files.onboardIdempotent(asset,profile,source,"Fixture","Fixture",cls,List.of(cls+"@1"),List.of("name"),
          changed,null,human,"correction","wins",tenant);
      return Map.of("semantic_id",cls,"semantic_version","1","publication_set_id",binding.publicationSetId(),
          "revision_id",binding.revisionId(),"artifact_type","CLASS");
    }).when(registryReader).resolve(cls,binding.revisionId(),binding.publicationSetId());
    var stale=ownerPost(http,"create",create,412);
    assertThat(stale.required("code").asText()).isEqualTo("ONB_REVIEW_STALE_VERSION");
    assertThat(db.sql("select count(*) from ouf_onboarding.onboarding_version where state='DRAFT'").query(Long.class).single()).isOne();
    assertThat(db.sql("select count(*) from ouf_onboarding.managed_file_onboarding_attempt").query(Long.class).single()).isEqualTo(2L);
  }

  private void poiCsv(boolean correctReaders)throws Exception{
    String tenant="test-tenant",source="poi-test",cls="urn:fixture:POI",set=UUID.randomUUID().toString();
    var mapper=principal(tenant,human.subject());
    var receipts=mock(PermissionDelegationVerifier.class);
    when(receipts.verifyManagedFile(any(),anyString(),anyString(),any()))
        .thenReturn(new PermissionDelegationVerifier.Delegated(mapper,"poi-create"));
    var http=MockMvcBuilders.standaloneSetup(new ManagedOnboardingReviewApi(receipts,reviews,runtimeJson),
        new it.comune.trieste.ouf.onboarding.api.ManagedFileMcpApi(receipts,files,runtimeJson,null))
        .setControllerAdvice(new ProblemHandler()).build();
    var columns=new ArrayList<>(List.of("id_redazionale","nome","indirizzo","telefono"));
    for(int i=1;i<=14;i++)columns.add("esclusa_"+i);
    byte[] csv=(String.join(",",columns)+"\n"+String.join(",",Collections.nCopies(18,"dato-sintetico"))+"\n")
        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    UUID asset=(UUID)files.registerOwned(null,"object://fixture/poi.csv",hashes.ofBytes(csv),"text/csv",csv.length,
        human.subject(),"retention://test",tenant).get("asset_id");
    UUID profile=(UUID)files.profile(asset,csv,"object://fixture/poi-sample.json").get("profile_id");
    var fields=new ArrayList<Map<String,Object>>();
    var bindings=new ArrayList<ManagedFileExecutionProposal.SemanticBinding>();
    bindings.add(new ManagedFileExecutionProposal.SemanticBinding(cls,"1",UUID.randomUUID().toString(),set));
    var properties=new ArrayList<Map<String,Object>>();
    var signals=new ArrayList<Map<String,Object>>();
    for(int i=0;i<columns.size();i++){
      String field=columns.get(i);
      if(i>=3){
        fields.add(Map.of("fieldName",field,"extractionDecision","EXCLUDE","dataAccessLabel",i==3?"PERSONAL":"OPEN"));
        continue;
      }
      String iri="urn:fixture:"+List.of("POIID","POIofficialName","fullAddress").get(i);
      fields.add(Map.of("fieldName",field,"extractionDecision","INCLUDE","targetPropertyIri",iri,
          "dataAccessLabel","OPEN","transform","TEXT_NFC_TRIM_V1"));
      bindings.add(new ManagedFileExecutionProposal.SemanticBinding(iri,"1",UUID.randomUUID().toString(),set));
      properties.add(Map.of("propertyIri",iri,"sourceField",iri,"datatype","http://www.w3.org/2001/XMLSchema#string",
          "authorityOrder",List.of(source),"accessLabel","OPEN"));
      signals.add(Map.of("id",iri,"semanticRef",iri+"@"+set,"comparator","TEXT_V1","excludesOnDisagreement",false,
          "uniqueWithinScope",false,"assertionRef","urn:fixture:assertion"));
    }
    var refs=bindings.stream().map(b->b.semanticId()+"@1").toList();
    var create=new LinkedHashMap<String,Object>(Map.of("assetId",asset,"profileId",profile,"sourceId",source,
        "name","Fixture POI","owner","Fixture","targetClassIri",cls,"sourceObjectKeyFields",List.of("id_redazionale"),"fields",fields));
    create.put("semanticRefs",List.of(refs.getFirst(),bindings.get(1).semanticId()+"@"+set));
    var form=ownerPost(http,"create",create,422);
    assertThat(form.path("code").asText()).isEqualTo("ONB_SEMANTIC_REF_FORM_INVALID");
    assertThat(form.required("index").intValue()).isEqualTo(1);
    for(String table:List.of("source","onboarding_version","managed_file_onboarding_attempt"))
      assertThat(db.sql("select count(*) from ouf_onboarding."+table).query(Long.class).single()).isZero();
    create.put("semanticRefs",refs);
    var sortedFields=columns.subList(0,3).stream().sorted().toList();
    var issues=sortedFields.stream().map(field->Map.of("fieldName",field,"transform","TEXT_NFC_TRIM_V1",
        "allowedTransforms",List.of("IDENTITY"))).toList();
    if(correctReaders){
      create.put("semanticBindings",bindings);
      var rejected=ownerPost(http,"create",create,422);
      assertThat(rejected.required("code").asText()).isEqualTo("ONB_READER_UNSUPPORTED");
      assertThat(rejected.required("fields")).isEqualTo(runtimeJson.valueToTree(issues));
      for(String table:List.of("source","onboarding_version","managed_file_onboarding_attempt","approval_challenge"))
        assertThat(db.sql("select count(*) from ouf_onboarding."+table).query(Long.class).single()).isZero();
      assertThat(files.asset(asset).get("source_id")).isNull();
      verify(registryReader).resolve(anyString(),anyString(),eq(set));
      clearInvocations(registryReader);
      create.remove("semanticBindings");
    }
    var draft=ownerPost(http,"create",create,200);
    UUID version=UUID.fromString(draft.required("onboardingVersionId").asText());
    assertThat(draft.required("deferredReaderChecks")).isEqualTo(runtimeJson.valueToTree(sortedFields.stream()
        .map(field->Map.of("fieldName",field,"transform","TEXT_NFC_TRIM_V1")).toList()));
    var stored=runtimeJson.valueToTree(onboarding.version(source,version).get("configuration"));
    assertThat(stored.at("/extractionProfile/projection/FILE")).isEqualTo(runtimeJson.valueToTree(columns.subList(0,3)));
    assertThat(stored.at("/sourceObjectIdentityPolicy/sourceFields/0").asText()).isEqualTo("id_redazionale");
    assertThat(stored.at("/excludedFieldReview/labels").size()).isEqualTo(15);
    assertThat(stored.at("/excludedFieldReview/labels/telefono").asText()).isEqualTo("PERSONAL");
    var read=ownerPost(http,"review-read",Map.of("sourceId",source,"onboardingVersionId",version),200);
    var compact=ownerPost(http,"review-read",Map.of("sourceId",source,"view","LATEST",
        "ifObservationVersion",read.required("observationVersion").asText()),200);
    assertThat(compact.path("unchanged").asBoolean()).isTrue();
    var missing=runtimeJson.valueToTree(List.of("semanticBindings","semanticPublicationSetRef","udp.resolution","udp.materialization","limits"));
    for(var response:List.of(draft,read,compact)){
      assertThat(response.required("reasonCode").asText()).isEqualTo("EXECUTION_PROPOSAL_REQUIRED");
      assertThat(response.required("executionProposalRequired").booleanValue()).isTrue();
      assertThat(response.required("missingSections")).isEqualTo(missing);
      assertThat(response.required("allowedActions").toString()).isEqualTo("[\"PREPARE_REVIEW\"]");
    }
    var prepare=new LinkedHashMap<String,Object>(Map.of("sourceId",source,"onboardingVersionId",version,"expectedVersion",1,"commandKey","empty"));
    assertThat(ownerPost(http,"review-prepare",prepare,412).path("code").asText()).isEqualTo("ONB_REVIEW_STALE_VERSION");
    prepare.put("expectedVersion",0);
    var required=ownerPost(http,"review-prepare",prepare,422);
    assertThat(required.required("code").asText()).isEqualTo("ONB_EXECUTION_PROPOSAL_REQUIRED");
    assertThat(required.required("missingSections")).isEqualTo(missing);
    var rule=Map.of("id","r","signalIds",List.of(signals.getFirst().get("id")),"assertionRef","urn:fixture:rule");
    var identity=Map.of("ref","urn:policy","version","1","sourceId",source,"tenantId",tenant,"canonicalClass",cls,
        "allowAutoNew",false,"maxCandidates",5,"signals",signals,"sufficientRules",List.of(rule));
    var udp=Map.<String,Object>of("resolution",Map.of("strategyId","GOVERNED_IDENTITY","strategyVersion","1","policyRef","urn:policy","governedIdentity",identity),
        "materialization",Map.of("policyRef","urn:authority","checkpointInterval",1,"properties",properties,"bitemporalProperties",List.of()));
    var limits=new ManagedFileExecutionProposal.Limits(100,18,1000);
    var wrong=new ArrayList<>(bindings);
    wrong.set(1,new ManagedFileExecutionProposal.SemanticBinding(bindings.get(1).semanticId(),"2",bindings.get(1).revisionId(),set));
    prepare.put("executionProposal",new ManagedFileExecutionProposal.Proposal(wrong,set,udp,limits));
    prepare.put("commandKey","wrong-version");
    var mismatch=ownerPost(http,"review-prepare",prepare,422);
    assertThat(mismatch.required("code").asText()).isEqualTo("ONB_SEMANTIC_REF_VERSION_MISMATCH");
    assertThat(mismatch.required("semanticRef").asText()).isEqualTo(refs.get(1));
    assertThat(onboarding.version(source,version)).containsEntry("state","DRAFT").containsEntry("lock_version",0L);
    assertThat(db.sql("select count(*) from ouf_onboarding.approval_challenge").query(Long.class).single()).isZero();
    verifyNoInteractions(registryReader);
    prepare.put("executionProposal",new ManagedFileExecutionProposal.Proposal(bindings,set,udp,limits));
    prepare.put("commandKey","prepare-poi");
    if(correctReaders){
      var failed=ownerPost(http,"review-prepare",prepare,422);
      assertThat(failed.required("code").asText()).isEqualTo("ONB_DATATYPE_UNRESOLVED");
      assertThat(failed.required("fields")).isEqualTo(runtimeJson.valueToTree(issues));
      assertThat(failed.required("fieldName").asText()).isEqualTo(sortedFields.getFirst());
      assertThat(failed.required("resolutions")).isEqualTo(runtimeJson.valueToTree(List.of("REPLACE_DRAFT")));
      assertThat(onboarding.version(source,version)).containsEntry("state","DRAFT").containsEntry("lock_version",0L);
      for(int i=0;i<3;i++){
        var corrected=new LinkedHashMap<>(fields.get(i));
        corrected.put("transform","IDENTITY");
        fields.set(i,corrected);
      }
      when(receipts.verifyManagedFile(any(),anyString(),anyString(),any()))
          .thenReturn(new PermissionDelegationVerifier.Delegated(mapper,"poi-corrected"));
      var corrected=ownerPost(http,"create",create,200);
      assertThat(corrected.has("deferredReaderChecks")).isFalse();
      assertThat(ownerPost(http,"create",create,200)).isEqualTo(corrected);
      assertThat(onboarding.version(source,version)).containsEntry("state","SUPERSEDED");
      assertThat(ownerPost(http,"review-prepare",prepare,412).path("code").asText()).isEqualTo("ONB_REVIEW_STALE_VERSION");
      prepare.put("onboardingVersionId",corrected.required("onboardingVersionId").asText());
      prepare.put("commandKey","prepare-corrected");
    }else{
      when(registryReader.resolve(anyString(),anyString(),eq(set))).thenAnswer(call->Map.of("semantic_id",call.getArgument(0),
          "semantic_version","1","publication_set_id",set,"revision_id",call.getArgument(1),
          "artifact_type",cls.equals(call.getArgument(0))?"CLASS":"PROPERTY"));
    }
    var frozen=ownerPost(http,"review-prepare",prepare,200);
    assertThat(frozen.required("state").asText()).isEqualTo("IN_REVIEW");
    assertThat(frozen.required("configurationHash").asText()).matches("sha256:[a-f0-9]{64}");
    var replay=ownerPost(http,"review-prepare",prepare,200);
    assertThat(replay.required("challengeId")).isEqualTo(frozen.required("challengeId"));
    assertThat(replay.required("configurationHash")).isEqualTo(frozen.required("configurationHash"));
    assertThat(db.sql("select count(*) from ouf_onboarding.approval_challenge").query(Long.class).single()).isOne();
    verify(registryReader,times(correctReaders?1:4)).resolve(anyString(),anyString(),eq(set));
  }

  @ParameterizedTest @ValueSource(booleans={false,true})
  void task51e02and03WideEnvelopePublishesOrBlocksBeforeReadiness(boolean oversized)throws Exception{
    String tenant="test-tenant",source="wide-publication",cls="urn:fixture:Place",policyRef="urn:fixture:identity";
    String set=UUID.randomUUID().toString();
    byte[] csv=ManagedWideProfileTest.csv(1024,3,"value");
    UUID asset=(UUID)files.registerOwned(null,"object://fixture/wide-publication.csv",hashes.ofBytes(csv),"text/csv",
        csv.length,human.subject(),"retention://test",tenant).get("asset_id");
    UUID profile=(UUID)files.profile(asset,csv,"object://fixture/wide-sample.json").get("profile_id");
    var fields=new ArrayList<ManagedFileService.FieldDecision>();
    var properties=new ArrayList<Map<String,Object>>();
    var signals=new ArrayList<Map<String,Object>>();
    var bindings=new ArrayList<ManagedFileExecutionProposal.SemanticBinding>();
    bindings.add(new ManagedFileExecutionProposal.SemanticBinding(cls,"1",UUID.randomUUID().toString(),set));
    for(int i=0;i<1024;i++){
      String iri="urn:fixture:property:"+(oversized?"x".repeat(600):"")+i;
      fields.add(new ManagedFileService.FieldDecision("campo_"+i,"INCLUDE","OPEN",iri,"TEXT_NFC_TRIM_V1",null,null,null));
      properties.add(Map.of("sourceField",iri,"propertyIri",iri,"datatype","http://www.w3.org/2001/XMLSchema#string",
          "accessLabel","OPEN","authorityOrder",List.of(source)));
      signals.add(Map.of("id",iri,"semanticRef",iri+"@"+set,"comparator","TEXT_V1",
          "excludesOnDisagreement",false,"uniqueWithinScope",false,"assertionRef","urn:fixture:signal"));
      bindings.add(new ManagedFileExecutionProposal.SemanticBinding(iri,"1",UUID.randomUUID().toString(),set));
    }
    var refs=bindings.stream().map(pin->pin.semanticId()+"@"+pin.semanticVersion()).toList();
    var draft=files.onboardIdempotent(asset,profile,source,"Wide source","Fixture",cls,refs,List.of("campo_0"),
        fields,null,human,"create","wide-create",tenant);
    UUID version=(UUID)draft.get("onboardingVersionId");
    String property=fields.getFirst().targetPropertyIri();
    var rule=Map.of("id","name-rule","signalIds",List.of(property),"assertionRef","urn:fixture:rule");
    var identity=Map.of("ref",policyRef,"version","1","tenantId",tenant,"sourceId",source,"canonicalClass",cls,
        "allowAutoNew",false,"maxCandidates",10,"signals",signals,"sufficientRules",List.of(rule));
    var udp=Map.<String,Object>of("resolution",Map.of("strategyId","GOVERNED_IDENTITY","strategyVersion","1",
        "policyRef",policyRef,"governedIdentity",identity),"materialization",Map.of("policyRef","urn:fixture:authority",
        "properties",properties,"bitemporalProperties",List.of(),"checkpointInterval",1));
    when(registryReader.resolve(anyString(),anyString(),eq(set))).thenAnswer(call->Map.of(
        "semantic_id",call.getArgument(0),"semantic_version","1","publication_set_id",set,
        "revision_id",call.getArgument(1),"artifact_type",cls.equals(call.getArgument(0))?"CLASS":"PROPERTY"));
    var mapper=principal(tenant,human.subject());
    var proposal=new ManagedFileExecutionProposal.Proposal(bindings,set,udp,new ManagedFileExecutionProposal.Limits(100,1024,1000));
    var prepared=reviews.prepare(mapper,new ManagedOnboardingReviewService.Prepare(source,version,0,"wide-review",proposal),"prepare");
    UUID challenge=(UUID)prepared.get("challengeId");
    String hash=(String)prepared.get("configurationHash");
    long lock=((Number)prepared.get("lockVersion")).longValue();
    if(oversized){
      var observed=reviews.read(mapper,source,version);
      assertThat(observed).containsEntry("workflowState",ManagedMcpObservation.State.IN_REVIEW)
          .containsEntry("reasonCode",ManagedMcpObservation.Reason.ONB_PUBLICATION_ENVELOPE_TOO_LARGE)
          .containsEntry("allowedActions",List.of(ManagedMcpObservation.Action.WITHDRAW_CANDIDATE));
      assertThat(runtimeJson.valueToTree(observed).path("nextAction").path("candidateAction").asText()).isEqualTo("WITHDRAW");
      var size=(RuntimePublicationEnvelope.Size)observed.get("publicationEnvelopeBlock");
      assertThat(size.bytes()).isGreaterThan(size.limitBytes());
      assertThat(size.limitBytes()).isEqualTo(2097152);
      assertThat(reviews.humanCard(mapper,challenge)).doesNotContainKeys("configuration","reviewSnapshot");
      assertThatThrownBy(()->reviews.humanAction(mapper,challenge,"confirm",
          new ManagedOnboardingReviewService.Decision(hash,lock),"test-auth"))
          .isInstanceOfSatisfying(DomainFailure.class,failure->assertThat(failure.code()).isEqualTo(RuntimePublicationEnvelope.TOO_LARGE));
      assertThat(onboarding.approvalCard(challenge)).containsEntry("status","CREATED");
      // Reproduce an approval carried over from the predecessor, which did not check envelope size.
      db.sql("update ouf_onboarding.approval_challenge set status='CONFIRMED',confirmed_at=clock_timestamp() where challenge_id=:c")
          .param("c",challenge).update();
      db.sql("update ouf_onboarding.onboarding_version set state='APPROVED',lock_version=lock_version+1 where onboarding_version_id=:v")
          .param("v",version).update();
      assertThatThrownBy(()->reviews.claimActivation(mapper,challenge,new ManagedOnboardingReviewService.Decision(hash,lock+1)))
          .isInstanceOfSatisfying(DomainFailure.class,failure->assertThat(failure.code()).isEqualTo(RuntimePublicationEnvelope.TOO_LARGE));
      assertThatThrownBy(()->onboarding.activate(source,version,human,"activate"))
          .isInstanceOfSatisfying(DomainFailure.class,failure->assertThat(failure.code()).isEqualTo(RuntimePublicationEnvelope.TOO_LARGE));
      var blocked=reviews.read(mapper,source,version);
      assertThat(progress(blocked)).containsEntry("readiness_attempts",0).containsEntry("humanActivationAllowed",false);
      assertThat(onboarding.bundleHistory(source)).isEmpty();
      var withdrawn=reviews.prepare(mapper,new ManagedOnboardingReviewService.Prepare(source,version,lock+1,
          "withdraw-wide",null,"WITHDRAW"),"withdraw");
      assertThat(withdrawn).containsEntry("candidateDisposition","WITHDRAWN")
          .containsEntry("allowedActions",List.of(ManagedMcpObservation.Action.REDRAFT_SAME_FILE,ManagedMcpObservation.Action.CREATE_REVISION));
      assertThat(runtimeJson.valueToTree(withdrawn).path("nextAction").path("tool").asText()).isEqualTo("source.onboarding.create");
      var smallerFields=fields.stream().map(field->new ManagedFileService.FieldDecision(field.fieldName(),"INCLUDE","OPEN",
          "urn:fixture:"+field.fieldName(),"TEXT_NFC_TRIM_V1",null,null,null)).toList();
      var smallerRefs=new ArrayList<String>(List.of(cls+"@1"));
      smallerFields.forEach(field->smallerRefs.add(field.targetPropertyIri()+"@1"));
      var smaller=files.onboardIdempotent(asset,profile,source,"Wide source","Fixture",cls,smallerRefs,List.of("campo_0"),
          smallerFields,null,human,"create","smaller-create",tenant);
      assertThat(onboarding.version(source,(UUID)smaller.get("onboardingVersionId"))).containsEntry("parent_version_id",version);
    }else{
      var approved=reviews.humanAction(mapper,challenge,"confirm",new ManagedOnboardingReviewService.Decision(hash,lock),"test-auth");
      onboarding.attestIngestionCompatibilityBound(source,version,hash,true,"Fixture compatibility",
          new OnboardingService.Actor("test-ingestion","SERVICE",Set.of("ouf.ingestion.configuration.attest")),"attest");
      var claim=reviews.claimActivation(mapper,challenge,new ManagedOnboardingReviewService.Decision(hash,
          ((Number)approved.get("lockVersion")).longValue()));
      assertThat(reviews.finishActivation(mapper,claim,"test-auth")).containsEntry("state","ACTIVE");
      var api=new it.comune.trieste.ouf.onboarding.api.RuntimePublicationApi(db,new RuntimePublicationEnvelope(runtimeJson,"tenant-a",2097152));
      var request=new org.springframework.mock.web.MockHttpServletRequest();
      TestAuthorization.bind(request,"test-runtime","SERVICE",Set.of("ouf.onboarding.configuration.read"));
      byte[] bytes=api.active(source,request);
      assertThat(bytes.length).isLessThan(2097152);
      var envelope=runtimeJson.readTree(bytes);
      var bundle=envelope.path("bundle");
      assertThat(bundle.path("semanticMapping").path("propertyMappings").size()).isEqualTo(1024);
      assertThat(bundle.path("semanticReferenceBindings").size()).isEqualTo(1025);
      String bundleRef=bundle.path("bundleId").asText()+":"+bundle.path("bundleVersion").asText()+":"+envelope.path("checksum").asText();
      assertThat(runtimeJson.readTree(api.resolve(bundleRef,request))).isEqualTo(envelope);
    }
  }

  @Test void task50bV02ConditionalReadDetectsExpiryAndReadinessAtTheSameLockVersion(){
    var fixture=governed(new PublishedSnapshots().publish("https://example.org/onto/Place","1.0.0","Prima"));
    var p=fixture.mapper();
    String source=fixture.command().sourceId();
    UUID version=fixture.command().onboardingVersionId();
    var initial=reviews.readLatest(p,source);
    String validator=(String)initial.get("observationVersion");
    UUID challenge=(UUID)initial.get("challengeId");
    assertThat(initial.get("allowedActions")).isEqualTo(List.of(ManagedMcpObservation.Action.WAIT_HUMAN_REVIEW,
        ManagedMcpObservation.Action.READ_AGAIN));
    assertThat(reviews.readLatest(p,source,validator)).containsEntry("unchanged",true)
        .doesNotContainKeys("reviewUrl","activationProgress");
    assertThatThrownBy(()->reviews.readLatest(principal("other-tenant","test-human"),source,validator))
        .hasMessage("ONB_REVIEW_OWNER_SCOPE_UNPROVEN");
    db.sql("update ouf_onboarding.approval_challenge set expires_at=clock_timestamp()-interval '1 second' where challenge_id=:c")
        .param("c",challenge).update();
    var expired=reviews.read(p,source,version,validator);
    assertThat(expired).isEqualTo(reviews.read(p,source,version)).doesNotContainKey("unchanged")
        .containsEntry("lockVersion",initial.get("lockVersion"))
        .containsEntry("allowedActions",List.of(ManagedMcpObservation.Action.RENEW_REVIEW));
    var renewed=reviews.prepare(p,new ManagedOnboardingReviewService.Prepare(source,version,
        ((Number)expired.get("lockVersion")).longValue(),"new-review",null),"renew");
    var approved=reviews.humanAction(p,(UUID)renewed.get("challengeId"),"confirm",
        new ManagedOnboardingReviewService.Decision((String)renewed.get("configurationHash"),
            ((Number)renewed.get("lockVersion")).longValue()),"test-auth");
    db.sql("""
        update ouf_onboarding.managed_onboarding_review set readiness_phase='BLOCKED',
          readiness_generation=1,readiness_attempts=1,readiness_started_at=clock_timestamp(),
          readiness_last_code='ONB_READINESS_DEPENDENCY_422' where onboarding_version_id=:v
        """).param("v",version).update();
    var blocked=reviews.read(p,source,version,(String)approved.get("observationVersion"));
    assertThat(blocked).containsEntry("lockVersion",approved.get("lockVersion")).doesNotContainKey("unchanged")
        .containsEntry("allowedActions",List.of(ManagedMcpObservation.Action.HUMAN_RECHECK));
    assertThat(reviews.readLatest(p,source,(String)blocked.get("observationVersion"))).containsEntry("unchanged",true);
  }
  @Test @SuppressWarnings("unchecked") void d084_11_failedLookupLeavesDraftThenFreezesPublishedDefinitionBeforeReview(){
    String type="urn:fixture:Postal",nested="urn:fixture:Location",property="urn:fixture:address",cls="urn:fixture:Place",source="typed",tenant="test-tenant";
    String set=UUID.randomUUID().toString(),rev=UUID.randomUUID().toString();
    String nestedSet=UUID.randomUUID().toString(),nestedRev=UUID.randomUUID().toString();
    byte[] csv="name\nEdicola\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    UUID asset=(UUID)files.registerOwned(null,"object://fixture/typed.csv",hashes.ofBytes(csv),"text/csv",csv.length,human.subject(),"retention://test",tenant).get("asset_id");
    UUID profile=(UUID)files.profile(asset,csv,"object://fixture/sample").get("profile_id");
    var comp=Map.<String,Object>of("kind","STRUCTURED","targetPropertyIri",property,"dataAccessLabel","OPEN","typeRef",type+"@"+set,
        "components",List.of(Map.of("component","name","fieldName","name","reader","TEXT_NFC_TRIM_V1"),
            Map.of("component","locations","items",List.of())));
    var draft=files.onboardIdempotent(asset,profile,source,"Sede","Comune",cls,List.of(cls+"@1",property+"@1",type+"@1",nested+"@1"),List.of("name"),
        List.of(new ManagedFileService.FieldDecision("name","INCLUDE","OPEN",null,null,null,null,null)),null,human,"create","create",tenant,null,List.of(comp));
    UUID version=(UUID)draft.get("onboardingVersionId");
    var signal=Map.of("id",property,"semanticRef",property+"@"+set,"comparator","JSON_V1","excludesOnDisagreement",false,"uniqueWithinScope",false,"assertionRef","urn:assertion");
    var identity=Map.of("ref","policy","version","1","tenantId",tenant,"sourceId",source,"canonicalClass",cls,"allowAutoNew",false,"maxCandidates",10,
        "signals",List.of(signal),"sufficientRules",List.of(Map.of("id","rule","signalIds",List.of(property),"assertionRef","urn:rule")));
    var udp=Map.<String,Object>of("resolution",Map.of("strategyId","GOVERNED_IDENTITY","strategyVersion","1","policyRef","policy","governedIdentity",identity),
        "materialization",Map.of("policyRef","policy","properties",List.of(Map.of("sourceField",property,"propertyIri",property,"datatype",type,
            "accessLabel","OPEN","authorityOrder",List.of(source))),"bitemporalProperties",List.of(),"checkpointInterval",1));
    var bindings=List.of(
        new ManagedFileExecutionProposal.SemanticBinding(cls,"1",UUID.randomUUID().toString(),set),
        new ManagedFileExecutionProposal.SemanticBinding(property,"1",UUID.randomUUID().toString(),set),
        new ManagedFileExecutionProposal.SemanticBinding(type,"1",rev,set),
        new ManagedFileExecutionProposal.SemanticBinding(nested,"1",nestedRev,nestedSet));
    var command=new ManagedOnboardingReviewService.Prepare(source,version,0,"typed-review",new ManagedFileExecutionProposal.Proposal(bindings,set,udp,new ManagedFileExecutionProposal.Limits(100,2,100)));
    var principal=principal(tenant,human.subject());
    for(var pin:bindings.subList(0,2))
      when(registryReader.resolve(pin.semanticId(),pin.revisionId(),set)).thenReturn(Map.of("semantic_id",pin.semanticId(),"semantic_version","1",
          "revision_id",pin.revisionId(),"publication_set_id",set,"artifact_type",pin.semanticId().equals(cls)?"CLASS":"PROPERTY","status","ACTIVE"));
    var definition=Map.of("components",List.of(
        Map.of("name","name","type","string","required",true,"cardinality","ONE"),
        Map.of("name","locations","type",nested+"@"+nestedSet,"required",false,"cardinality","MANY")));
    when(registryReader.resolve(type,rev,set)).thenReturn(Map.of("semantic_id",type,"semantic_version","1","revision_id",rev,"publication_set_id",set,
        "artifact_type","DATATYPE","status","ACTIVE","definition",definition,"definitionSha256",hashes.ofDefinition(definition).substring(7),"label",Map.of("it","Recapito")));
    var nestedDefinition=Map.of("components",List.of(Map.of("name","name","type","string","required",true,"cardinality","ONE")));
    var nestedPin=new LinkedHashMap<>(Map.<String,Object>of("semantic_id",nested,"semantic_version","1","revision_id",nestedRev,"publication_set_id",nestedSet,
        "artifact_type","DATATYPE","status","DEPRECATED","definition",nestedDefinition,
        "definitionSha256",hashes.ofDefinition(nestedDefinition).substring(7),"label",Map.of("it","Posizione")));
    when(registryReader.resolve(nested,nestedRev,nestedSet)).thenReturn(nestedPin);
    assertThatThrownBy(()->reviews.prepare(principal,command,"prepare")).isInstanceOfSatisfying(
        it.comune.trieste.ouf.onboarding.domain.DomainFailure.class,e->assertThat(e.code()).isEqualTo("ONB_DATATYPE_UNRESOLVED"));
    assertThat(onboarding.version(source,version)).containsEntry("state","DRAFT");
    assertThat(db.sql("select count(*) from ouf_onboarding.approval_challenge where onboarding_version_id=:v").param("v",version).query(Long.class).single()).isZero();
    nestedPin.put("status","ACTIVE");
    var review=reviews.prepare(principal,command,"retry");
    assertThat(reviews.prepare(principal,command,"replay")).isEqualTo(review);
    verify(registryReader,times(2)).resolve(type,rev,set);
    verify(registryReader,times(2)).resolve(nested,nestedRev,nestedSet);
    var card=reviews.humanCard(principal,(UUID)review.get("challengeId"));
    assertThat(hashes.of(card.get("configuration"))).isEqualTo(review.get("configurationHash"));
    var snapshot=(Map<String,Object>)card.get("reviewSnapshot");
    assertThat(snapshot).containsEntry("COMPOSITION",Map.of(property,comp));
    var datatypes=(Map<?,?>)snapshot.get("DATATYPE");
    assertThat(((Map<?,?>)datatypes.get(type+"@"+set)).get("definition")).isEqualTo(definition);
    assertThat(((Map<?,?>)datatypes.get(nested+"@"+nestedSet)).get("definition")).isEqualTo(nestedDefinition);
  }
  private record ValueMapFixture(PrincipalContext actor,ManagedOnboardingReviewService.Prepare command,
      Map<String,Object> mapResponse,Map<String,Object> propertyResponse,String ref,String vocabulary,
      Function<ManagedFileService,Map<String,Object>> create) {
    UUID version(){return command.onboardingVersionId();}
    ManagedFileExecutionProposal.SemanticBinding mapPin(){return command.executionProposal().semanticBindings().getFirst();}
    ManagedFileExecutionProposal.SemanticBinding propertyPin(){return command.executionProposal().semanticBindings().getLast();}
  }

  private ValueMapFixture valueMapDraft(){
    String source="colours",tenant="test-tenant",cls="urn:fixture:Place",property="urn:fixture:colour",map="urn:fixture:map";
    String set=UUID.randomUUID().toString(),mapSet=UUID.randomUUID().toString(),ref=map+"@"+mapSet;
    String vocabulary="urn:fixture:colours@"+UUID.randomUUID();
    byte[] csv="code\nprivate-fixture-code\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    UUID asset=(UUID)files.registerOwned(null,"object://fixture/codes.csv",hashes.ofBytes(csv),"text/csv",
        csv.length,human.subject(),"retention://test",tenant).get("asset_id");
    UUID profile=(UUID)files.profile(asset,csv,"object://fixture/codes-sample").get("profile_id");
    var fields=List.of(new ManagedFileService.FieldDecision("code","INCLUDE","OPEN",property,"VALUE_MAP_V1",
        null,null,null,Map.of("valueMapRef",ref)));
    // Verify the map before the later property read can fail with a transient response.
    var ids=List.of(map,cls,property);
    Function<ManagedFileService,Map<String,Object>> create=service->service.onboardIdempotent(asset,profile,source,
        "Colori","Comune",cls,ids.stream().map(id->id+"@1").toList(),
        List.of("code"),fields,null,human,"create","create",tenant);
    UUID version=(UUID)create.apply(files).get("onboardingVersionId");
    var signal=Map.of("id",property,"semanticRef",property+"@"+set,"comparator","CONCEPT",
        "excludesOnDisagreement",false,"uniqueWithinScope",false,"assertionRef","urn:assertion");
    var identity=Map.of("ref","policy","version","1","tenantId",tenant,"sourceId",source,"canonicalClass",cls,
        "allowAutoNew",false,"maxCandidates",10,"signals",List.of(signal),
        "sufficientRules",List.of(Map.of("id","rule","signalIds",List.of(property),"assertionRef","urn:rule")));
    var udp=Map.<String,Object>of("resolution",Map.of("strategyId","GOVERNED_IDENTITY","strategyVersion","1",
        "policyRef","policy","governedIdentity",identity),"materialization",Map.of("policyRef","policy","properties",List.of(
            Map.of("sourceField",property,"propertyIri",property,"datatype","http://www.w3.org/2001/XMLSchema#anyURI",
                "accessLabel","OPEN","authorityOrder",List.of(source))),"bitemporalProperties",List.of(),"checkpointInterval",1));
    var bindings=ids.stream().map(id->new ManagedFileExecutionProposal.SemanticBinding(id,"1",
        UUID.randomUUID().toString(),map.equals(id)?mapSet:set)).toList();
    var command=new ManagedOnboardingReviewService.Prepare(source,version,0,"map-review",
        new ManagedFileExecutionProposal.Proposal(bindings,set,udp,new ManagedFileExecutionProposal.Limits(100,2,100)));
    var actor=principal(tenant,human.subject());
    var definition=Map.of("targetVocabulary",vocabulary,"labels",Map.of("it","Colori pubblicati"),
        "entries",List.of(Map.of("code","private-fixture-code","concept","urn:fixture:red")));
    var mapResponse=new LinkedHashMap<String,Object>();
    var propertyResponse=new LinkedHashMap<String,Object>();
    for(var pin:bindings){
      String kind=cls.equals(pin.semanticId())?"CLASS":property.equals(pin.semanticId())?"PROPERTY":"VALUE_MAP";
      var response=new LinkedHashMap<String,Object>(Map.of("semantic_id",pin.semanticId(),"semantic_version","1",
          "revision_id",pin.revisionId(),"publication_set_id",pin.publicationSetId(),"artifact_type",kind,"status","ACTIVE"));
      if("VALUE_MAP".equals(kind)){
        response.put("definition",definition);
        response.put("definitionSha256",hashes.ofDefinition(definition).substring(7));
        mapResponse.putAll(response);
      }
      if("PROPERTY".equals(kind))propertyResponse.putAll(response);
      when(registryReader.resolve(pin.semanticId(),pin.revisionId(),pin.publicationSetId()))
          .thenReturn("VALUE_MAP".equals(kind)?mapResponse:response);
    }
    return new ValueMapFixture(actor,command,mapResponse,propertyResponse,ref,vocabulary,create);
  }

  @Test void d084_24_valueMapIsFrozenBeforeReviewAndTheCardExposesOnlyItsSummary(){
    var fixture=valueMapDraft();
    var review=reviews.prepare(fixture.actor(),fixture.command(),"prepare");
    assertValueMapReview(fixture,review);
  }

  @ParameterizedTest
  @ValueSource(booleans={false,true})
  void d084_37_valueMapPauseResumesExactlyOnceWithOrWithoutRestart(boolean restart){
    var fixture=valueMapDraft();
    pauseValueMap(fixture);
    clearInvocations(registryReader);
    assertThatThrownBy(()->reviews.prepare(fixture.actor(),fixture.command(),"early"))
        .isInstanceOfSatisfying(DomainFailure.class,e->assertThat(e.properties()).containsEntry("preparationPhase","RETRY_WAIT"));
    verifyNoInteractions(registryReader);
    makeValueMapRetryDue(fixture);
    if(restart){
      try(var fresh=freshReviewContext()){
        var reloaded=fresh.getBean(ManagedOnboardingReviewService.class);
        assertThat(reloaded).isNotSameAs(reviews);
        assertThat(fresh.getBean(OnboardingService.class)).isNotSameAs(onboarding);
        resumeValueMap(fixture,reloaded,fresh.getBean(ManagedFileService.class));
      }
    }else resumeValueMap(fixture,reviews,files);
  }

  @ParameterizedTest
  @ValueSource(strings={"RETIRED","DEPRECATED","MISSING","REPLACED","DIGEST_CHANGED","BAD_DIGEST"})
  void d084_38_valueMapRetryRejectsRevocationReplacementAndDigestDrift(String change)throws Exception{
    var fixture=valueMapDraft();
    pauseValueMap(fixture);
    makeValueMapRetryDue(fixture);
    var pin=fixture.mapPin();
    var response=fixture.mapResponse();
    switch(change){
      case "RETIRED","DEPRECATED" -> response.put("status",change);
      case "MISSING" -> doThrow(DatatypeResolver.unresolved()).when(registryReader)
          .resolve(pin.semanticId(),pin.revisionId(),pin.publicationSetId());
      case "REPLACED" -> response.put("revision_id",UUID.randomUUID().toString());
      case "DIGEST_CHANGED" -> {
        var changed=Map.of("targetVocabulary",fixture.vocabulary(),
            "entries",List.of(Map.of("code","private-fixture-code","concept","urn:fixture:blue")));
        response.put("definition",changed);
        response.put("definitionSha256",hashes.ofDefinition(changed).substring(7));
      }
      case "BAD_DIGEST" -> response.put("definitionSha256","0".repeat(64));
      default -> throw new AssertionError(change);
    }
    clearInvocations(registryReader);
    assertValueMapBlocked(fixture,fixture.command());
    verify(registryReader).resolve(pin.semanticId(),pin.revisionId(),pin.publicationSetId());
    clearInvocations(registryReader);
    assertValueMapProblem(fixture);
    var newKey=new ManagedOnboardingReviewService.Prepare(fixture.command().sourceId(),fixture.version(),0,
        "same-selection-new-key",fixture.command().executionProposal());
    assertValueMapBlocked(fixture,newKey);
    verifyNoInteractions(registryReader);
    assertPausedDraft(fixture);
  }

  private void assertValueMapProblem(ValueMapFixture fixture)throws Exception{
    String path="/api/internal/v1/onboarding/managed-file-mcp/review-prepare";
    var json=new ObjectMapper();
    var raw=json.writeValueAsBytes(Map.of("Arguments",fixture.command()));
    var receipts=mock(PermissionDelegationVerifier.class);
    when(receipts.verifyManagedFile(null,path,ManagedOnboardingReviewService.PREPARE,raw))
        .thenReturn(new PermissionDelegationVerifier.Delegated(fixture.actor(),fixture.command().commandKey()));
    var mvc=MockMvcBuilders.standaloneSetup(new ManagedOnboardingReviewApi(receipts,reviews,json))
        .setControllerAdvice(new ProblemHandler()).build();
    mvc.perform(post(path).contentType("application/json").content(raw))
        .andExpect(status().is(422)).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.code").value("ONB_VALUE_MAP_UNRESOLVED"))
        .andExpect(jsonPath("$.state").value("DRAFT"))
        .andExpect(jsonPath("$.preparationPhase").value("BLOCKED"))
        .andExpect(jsonPath("$.detail").value(DatatypeResolver.unresolvedValueMap().getMessage()));
  }

  @ParameterizedTest
  @ValueSource(booleans={false,true})
  void d084_39_valueMapRetryBudgetStaysExhausted(boolean expireTime){
    var fixture=valueMapDraft();
    pauseValueMap(fixture);
    db.sql("update ouf_onboarding.managed_value_map_preparation set "+
        (expireTime?"deadline=clock_timestamp()-interval '1 second'":"attempts=128")+" where onboarding_version_id=:v")
        .param("v",fixture.version()).update();
    clearInvocations(registryReader);
    for(int replay=0;replay<2;replay++){
      assertThatThrownBy(()->reviews.prepare(fixture.actor(),fixture.command(),"exhausted"))
          .isInstanceOfSatisfying(DomainFailure.class,e->{
            assertThat(e.code()).isEqualTo("ONB_VALUE_MAP_RETRY_EXHAUSTED");
            assertThat(e.properties()).containsEntry("state","DRAFT").containsEntry("preparationPhase","EXHAUSTED");
          });
    }
    verifyNoInteractions(registryReader);
    assertPausedDraft(fixture);
  }

  @Test void d084_40_valueMapRetryFencesAConcurrentLateResult()throws Exception{
    var fixture=valueMapDraft();
    pauseValueMap(fixture);
    makeValueMapRetryDue(fixture);
    var reading=new CountDownLatch(1);
    var release=new CountDownLatch(1);
    var property=fixture.propertyPin();
    doAnswer(call->{
      reading.countDown();
      assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();
      return fixture.propertyResponse();
    }).doReturn(fixture.propertyResponse()).when(registryReader)
        .resolve(property.semanticId(),property.revisionId(),property.publicationSetId());
    try(var executor=Executors.newFixedThreadPool(2)){
      var late=executor.submit(()->assertThatThrownBy(()->reviews.prepare(fixture.actor(),fixture.command(),"late"))
          .isInstanceOfSatisfying(DomainFailure.class,e->assertThat(e.code()).isEqualTo("ONB_VALUE_MAP_PREPARATION_LEASE_LOST")));
      try{
        assertThat(reading.await(5,TimeUnit.SECONDS)).isTrue();
        executor.submit(()->assertThatThrownBy(()->reviews.prepare(fixture.actor(),fixture.command(),"concurrent"))
            .isInstanceOfSatisfying(DomainFailure.class,e->assertThat(e.properties()).containsEntry("preparationPhase","CHECKING")))
            .get(3,TimeUnit.SECONDS);
        db.sql("update ouf_onboarding.managed_value_map_preparation set lease_until=clock_timestamp()-interval '1 second' where onboarding_version_id=:v")
            .param("v",fixture.version()).update();
        var review=reviews.prepare(fixture.actor(),fixture.command(),"after-lease");
        assertValueMapReview(fixture,review);
      }finally{release.countDown();}
      late.get(5,TimeUnit.SECONDS);
    }
    assertThat(challengeCount(fixture.version())).isOne();
  }

  @Test void d084_41_withdrawalCancelsPausedValueMapPreparation(){
    var fixture=valueMapDraft();
    pauseValueMap(fixture);
    clearInvocations(registryReader);
    var withdraw=new ManagedOnboardingReviewService.Prepare(fixture.command().sourceId(),fixture.version(),0,
        "withdraw-paused-map",null,"WITHDRAW");
    assertThat(reviews.prepare(fixture.actor(),withdraw,"withdraw")).containsEntry("candidateDisposition","WITHDRAWN");
    assertThatThrownBy(()->reviews.prepare(fixture.actor(),fixture.command(),"retry"))
        .isInstanceOfSatisfying(DomainFailure.class,e->{
          assertThat(e.code()).isEqualTo("ONB_REVIEW_STALE_VERSION");
          assertThat(e.properties()).containsEntry("state","REJECTED").containsEntry("preparationPhase","CANCELLED");
        });
    verifyNoInteractions(registryReader);
    assertThat(challengeCount(fixture.version())).isZero();
  }

  private ConfigurableApplicationContext freshReviewContext(){
    return new SpringApplicationBuilder(OnboardingApplication.class).web(WebApplicationType.SERVLET)
        .properties("server.port=-1","ouf.authorization.ths.public-origin=https://review.example")
        .initializers(context->{
          var beans=(GenericApplicationContext)context;
          beans.registerBean("testRegistry",DatatypeResolver.class,()->registryReader,definition->definition.setPrimary(true));
          beans.registerBean("testPolicy",AuthorizationPolicyRegistry.class,()->policy,definition->definition.setPrimary(true));
        }).run();
  }

  private void pauseValueMap(ValueMapFixture fixture){
    var property=fixture.propertyPin();
    doAnswer(call->{
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      throw new DomainFailure(HttpStatus.SERVICE_UNAVAILABLE,"ONB_DATATYPE_LOOKUP_UNAVAILABLE",
          "Fixture unavailable",Map.of("retryAfterSeconds",90));
    }).doReturn(fixture.propertyResponse()).when(registryReader)
        .resolve(property.semanticId(),property.revisionId(),property.publicationSetId());
    assertThatThrownBy(()->reviews.prepare(fixture.actor(),fixture.command(),"prepare"))
        .isInstanceOfSatisfying(DomainFailure.class,e->{
          assertThat(e.code()).isEqualTo("ONB_DATATYPE_LOOKUP_UNAVAILABLE");
          assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
          assertThat(e.properties()).containsEntry("state","DRAFT").containsEntry("preparationPhase","RETRY_WAIT")
              .containsEntry("retryAfterSeconds",90);
        });
    assertThat(db.sql("""
        select attempts,phase,digests->>cast(:ref as text) digest,lease_until
        from ouf_onboarding.managed_value_map_preparation where onboarding_version_id=:v
        """).param("ref",fixture.ref()).param("v",fixture.version()).query().singleRow())
        .containsEntry("attempts",1).containsEntry("phase","RETRY_WAIT").containsEntry("lease_until",null)
        .containsEntry("digest",fixture.mapResponse().get("definitionSha256"));
    assertPausedDraft(fixture);
  }

  private void makeValueMapRetryDue(ValueMapFixture fixture){
    db.sql("""
        update ouf_onboarding.managed_value_map_preparation
        set next_at=clock_timestamp()-interval '1 second',deadline=deadline-interval '3 hours'
        where onboarding_version_id=:v
        """).param("v",fixture.version()).update();
  }

  private void resumeValueMap(ValueMapFixture fixture,ManagedOnboardingReviewService service,ManagedFileService createService){
    assertThat(fixture.create().apply(createService)).containsEntry("onboardingVersionId",fixture.version())
        .containsEntry("state","DRAFT");
    var review=service.prepare(fixture.actor(),fixture.command(),"retry");
    assertThat(service.prepare(fixture.actor(),fixture.command(),"lost-response")).isEqualTo(review);
    var pin=fixture.mapPin();
    verify(registryReader).resolve(pin.semanticId(),pin.revisionId(),pin.publicationSetId());
    assertThat(db.sql("select attempts,phase from ouf_onboarding.managed_value_map_preparation where onboarding_version_id=:v")
        .param("v",fixture.version()).query().singleRow()).containsEntry("attempts",2).containsEntry("phase","COMPLETED");
    assertThat(challengeCount(fixture.version())).isOne();
    assertValueMapReview(fixture,review);
  }

  private void assertValueMapBlocked(ValueMapFixture fixture,ManagedOnboardingReviewService.Prepare command){
    assertThatThrownBy(()->reviews.prepare(fixture.actor(),command,"retry")).isInstanceOfSatisfying(DomainFailure.class,e->{
      assertThat(e.code()).isEqualTo("ONB_VALUE_MAP_UNRESOLVED");
      assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
      assertThat(e.properties()).containsEntry("state","DRAFT").containsEntry("preparationPhase","BLOCKED");
      assertThat(e).hasMessageContaining("published ACTIVE value map revision").hasMessageContaining("new commandKey");
    });
  }

  private void assertPausedDraft(ValueMapFixture fixture){
    var draft=onboarding.version(fixture.command().sourceId(),fixture.version());
    assertThat(draft).containsEntry("state","DRAFT").containsEntry("lock_version",0L);
    assertThat(((Map<?,?>)draft.get("configuration")).containsKey("semanticReferenceBindings")).isFalse();
    assertThat(challengeCount(fixture.version())).isZero();
    assertThat(versionCount(fixture.command().sourceId())).isOne();
  }

  private long challengeCount(UUID version){
    return db.sql("select count(*) from ouf_onboarding.approval_challenge where onboarding_version_id=:v")
        .param("v",version).query(Long.class).single();
  }

  private long versionCount(String source){
    return db.sql("select count(*) from ouf_onboarding.onboarding_version where source_id=:source")
        .param("source",source).query(Long.class).single();
  }

  private void assertValueMapReview(ValueMapFixture fixture,Map<String,Object> review){
    String source=fixture.command().sourceId();
    UUID version=fixture.version();
    assertThat(review).containsEntry("state","IN_REVIEW").containsEntry("onboardingVersionId",version);
    assertThat(versionCount(source)).isOne();
    var json=new com.fasterxml.jackson.databind.ObjectMapper();
    var frozen=json.valueToTree(onboarding.version(source,version).get("configuration"));
    var embedded=frozen.path("semanticReferenceBindings").get(0);
    assertThat(embedded.path("definition")).isEqualTo(json.valueToTree(fixture.mapResponse().get("definition")));
    assertThat(embedded.path("definitionSha256").asText()).isEqualTo(fixture.mapResponse().get("definitionSha256"));
    assertThat(embedded.path("revisionId").asText()).isEqualTo(fixture.mapPin().revisionId());
    assertThat(hashes.of(onboarding.version(source,version).get("configuration"))).isEqualTo(review.get("configurationHash"));
    var card=json.valueToTree(reviews.humanCard(fixture.actor(),(UUID)review.get("challengeId")));
    assertThat(card.path("reviewSnapshot").path("FIELD").path("code").path("valueMap")).isEqualTo(json.valueToTree(
        Map.of("valueMapRef",fixture.ref(),"name","Colori pubblicati","targetVocabulary",fixture.vocabulary(),"entryCount",1)));
    assertThat(card.toString()).doesNotContain("private-fixture-code");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings={"ACTIVE","DEPRECATED","RETIRED"})
  void d084_17_historicalBundleReturnsFrozenDatatypeWithoutLiveStatusGate(String currentStatus)throws Exception{
    String source="history",id="urn:test:historical",set=UUID.randomUUID().toString(),rev=UUID.randomUUID().toString();
    onboarding.createSource(source,"Storico","INTERNAL_MANAGED","MANAGED","owner",Map.of(),human,"history");
    var draft=onboarding.createVersion(source,Map.of(),human,"history");
    UUID version=(UUID)draft.get("onboarding_version_id");
    var definition=Map.of("components",List.of(Map.of("name","name","type","string","required",true,"cardinality","ONE")));
    var pin=Map.of("semanticId",id,"publicationSetId",set,"revisionId",rev,"semanticVersion","1",
        "definition",definition,"definitionSha256",hashes.ofDefinition(definition).substring(7));
    String map="urn:test:historical-map",ref=map+"@"+set,vocabulary="urn:test:vocabulary@"+set;
    var mapDefinition=Map.of("targetVocabulary",vocabulary,
        "entries",List.of(Map.of("code","historical-code","concept","urn:test:concept")));
    var mapPin=Map.of("semanticId",map,"semanticVersion","1","publicationSetId",set,"valueMapRef",ref,
        "revisionId",rev,"targetVocabulary",vocabulary,"definition",mapDefinition,
        "definitionSha256",hashes.ofDefinition(mapDefinition).substring(7));
    var bundle=Map.of("semanticReferenceBindings",List.of(pin,mapPin));
    db.sql("""
        insert into ouf_onboarding.published_configuration(publication_id,source_id,onboarding_version_id,bundle_version,bundle,checksum,active,effective_from)
        values(:id,:s,:v,1,cast(:b as jsonb),:h,false,transaction_timestamp())
        """).param("id",UUID.randomUUID()).param("s",source).param("v",version)
        .param("b",new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(bundle)).param("h",hashes.of(bundle)).update();
    when(registryReader.resolve(id,rev,set)).thenReturn(Map.of("status",currentStatus));
    when(registryReader.resolve(map,rev,set)).thenReturn(Map.of("status",currentStatus));
    assertThat(onboarding.historicalBundle(source,version)).containsEntry("bundle",bundle).containsEntry("checksum",hashes.of(bundle));
    verifyNoInteractions(registryReader);
  }

  @Test void preparedMappingReadsAdoptedBytesAfterAnotherSemanticPublicationBecomesActive(){
    String semanticId="https://example.org/onto/Place";
    var registry=new PublishedSnapshots();
    var adopted=registry.publish(semanticId,"1.0.0","Prima");
    var originalBytes=registry.snapshots.get(adopted);
    var fixture=governed(adopted);
    var mapper=fixture.mapper();var command=fixture.command();var review=fixture.review();
    var refs=List.of(semanticId+"@"+adopted.semanticVersion());
    registry.publish(semanticId,"1.1.0","Seconda");
    assertThat(registry.snapshots.get(registry.active)).isNotEqualTo(originalBytes);
    assertThat(reviews.prepare(mapper,command,"retry")).isEqualTo(review);
    var card=reviews.humanCard(mapper,(UUID)review.get("challengeId"));
    var config=(Map<?,?>)card.get("configuration");
    assertThat(((Map<?,?>)config.get("semanticMapping")).get("semanticRefs")).isEqualTo(refs);
    var bindings=(List<?>)config.get("semanticReferenceBindings");
    assertThat(bindings).hasSize(1);
    var retained=new com.fasterxml.jackson.databind.ObjectMapper().convertValue(bindings.getFirst(),
        ManagedFileExecutionProposal.SemanticBinding.class);
    assertThat(retained).isEqualTo(adopted);
    assertThat(registry.snapshots.get(retained)).containsExactly(originalBytes);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
  void humanRefusalCanStartOneLinkedDraftOnTheSameFile(boolean changes){
    String type="https://example.org/onto/Place",property="urn:fixture:name";
    var binding=new ManagedFileExecutionProposal.SemanticBinding(type,"1",UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),List.of(new ManagedFileExecutionProposal.ReviewTerm(type,"Luogo","Un luogo",null),
        new ManagedFileExecutionProposal.ReviewTerm(property,"Nome",null,null)));
    var fixture=governed(binding);var p=fixture.mapper();var prepared=fixture.review();
    UUID version=fixture.command().onboardingVersionId(),challenge=(UUID)prepared.get("challengeId");
    String source=fixture.command().sourceId(),hash=(String)prepared.get("configurationHash");
    long expected=((Number)prepared.get("lockVersion")).longValue();
    var snapshot=(Map<?,?>)reviews.humanCard(p,challenge).get("reviewSnapshot");
    assertThat(snapshot.get("columnOrder")).isEqualTo(List.of("name","unused"));
    var file=(Map<?,?>)snapshot.get("file");
    assertThat(file.get("format")).isEqualTo("CSV");assertThat(file.get("columns")).isEqualTo(2);
    assertThat(file.get("rows")).isEqualTo(1);assertThat(snapshot.get("owner")).isEqualTo("Mapper");
    var fields=(Map<?,?>)snapshot.get("FIELD");var unused=(Map<?,?>)fields.get("unused");
    assertThat(unused.get("included")).isEqualTo(false);assertThat(unused.get("access")).isEqualTo("OPEN");
    assertThat(((Map<?,?>)((Map<?,?>)snapshot.get("SEMANTIC")).get(property)).get("labelIt")).isEqualTo("Nome");
    var note=new ManagedOnboardingReviewService.Feedback("FIELD","unused","È personale");
    var decision=new ManagedOnboardingReviewService.Decision(hash,expected,changes?List.of(note):null);
    var invalid=new ManagedOnboardingReviewService.Decision(hash,expected,List.of(new ManagedOnboardingReviewService.Feedback("FIELD","unknown","")));
    assertThatThrownBy(()->reviews.humanAction(p,challenge,"changes",invalid,"test-auth")).hasMessage("ONB_REVIEW_FEEDBACK_INVALID");
    var result=reviews.humanAction(p,challenge,changes?"changes":"reject",decision,"test-auth");
    assertThat(result).containsEntry("state",changes?"CHANGES_REQUESTED":"REJECTED").containsEntry("challengeStatus","CANCELLED");
    var read=reviews.readLatest(p,source);
    assertThat(read.get("allowedActions")).isEqualTo(List.of(ManagedMcpObservation.Action.REDRAFT_SAME_FILE,
        ManagedMcpObservation.Action.CREATE_REVISION));
    assertThat(read.get("reviewFeedback")).isEqualTo(changes?List.of(Map.of("section","FIELD","target","unused","comment","È personale")):List.of());
    assertThatThrownBy(()->reviews.humanAction(p,challenge,"confirm",new ManagedOnboardingReviewService.Decision(hash,expected),"test-auth"))
        .hasMessage("ONB_REVIEW_STALE_CARD");
    var next=(Map<?,?>)read.get("nextAction");assertThat(next.get("tool")).isEqualTo("source.onboarding.create");
    UUID asset=UUID.fromString(next.get("assetId").toString()),profile=UUID.fromString(next.get("profileId").toString());
    var decisions=List.of(new ManagedFileService.FieldDecision("name","INCLUDE","OPEN",property,"IDENTITY",null,null,null),
        new ManagedFileService.FieldDecision("unused","EXCLUDE","PERSONAL",null,null,null,null,null));
    java.util.function.Function<String,Map<String,Object>> create=key->files.onboardIdempotent(asset,profile,source,"Edicole","Mapper",
        type,List.of(type+"@1"),List.of("name"),decisions,null,human,"redraft",key,p.tenantId());
    var draft=create.apply("redraft:1");UUID child=(UUID)draft.get("onboardingVersionId");
    assertThat(reviews.readLatest(p,source,(String)read.get("observationVersion")))
        .containsEntry("onboardingVersionId",child).doesNotContainKey("unchanged");
    assertThat(reviews.read(p,source,version,(String)read.get("observationVersion")))
        .containsEntry("allowedActions",List.of(ManagedMcpObservation.Action.NONE)).doesNotContainKey("unchanged");
    assertThat(onboarding.version(source,child)).containsEntry("parent_version_id",version).containsEntry("version",2);
    assertThat(create.apply("redraft:1")).isEqualTo(draft);
    assertThatThrownBy(()->create.apply("redraft:duplicate")).isInstanceOf(it.comune.trieste.ouf.onboarding.domain.DomainFailure.class);
    var second=reviews.prepare(p,new ManagedOnboardingReviewService.Prepare(source,child,0,"review:2",fixture.command().executionProposal()),"review");
    var card=reviews.humanCard(p,(UUID)second.get("challengeId"));
    var previous=(Map<?,?>)card.get("previousReview");
    assertThat(previous.get("feedback")).isEqualTo(read.get("reviewFeedback"));
    assertThat((List<?>)previous.get("changes")).anyMatch(raw->"unused".equals(((Map<?,?>)raw).get("target")));
    assertThat(onboarding.bundleHistory(source)).isEmpty();
  }

  @Test void finalUdp503PausesThenPublishesOnceOnSameReview(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)throws Exception{
    var adopted=new PublishedSnapshots().publish("https://example.org/onto/Place","1.0.0","Prima");
    var fixture=governed(adopted);
    var p=fixture.mapper();var prepared=fixture.review();
    String hash=(String)prepared.get("configurationHash"),source=fixture.command().sourceId();
    UUID version=fixture.command().onboardingVersionId(),challenge=(UUID)prepared.get("challengeId");
    var approved=reviews.humanAction(p,challenge,"confirm",new ManagedOnboardingReviewService.Decision(hash,
        ((Number)prepared.get("lockVersion")).longValue()),"test-auth");
    var activation=new ManagedOnboardingReviewService.Decision(hash,((Number)approved.get("lockVersion")).longValue());
    onboarding.attestIngestionCompatibilityBound(source,version,hash,true,"Exact fixture compatibility",
        new OnboardingService.Actor("test-ingestion","SERVICE",Set.of("ouf.ingestion.configuration.attest")),"c");
    var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
    var json=new com.fasterxml.jackson.databind.ObjectMapper();
    var finalReads=new java.util.concurrent.atomic.AtomicInteger();
    server.createContext("/api/udp/v1/governance/",exchange->{
      boolean internal=exchange.getRequestURI().getPath().contains("/internal/");
      int status=internal&&finalReads.incrementAndGet()==1?503:200;
      var bytes=json.writeValueAsBytes(Map.of("valid",true,"sourceId",source,"configurationHash",hash,
          "tenantId",p.tenantId(),"canonicalClass",adopted.semanticId(),"policyRef","urn:fixture:identity",
          "policyVersion","1","coverageRef","coverage://fixture"));
      exchange.sendResponseHeaders(status,bytes.length);
      try(var out=exchange.getResponseBody()){out.write(bytes);}
    });
    server.start();
    try{
      String origin="http://127.0.0.1:"+server.getAddress().getPort();
      var token=java.nio.file.Files.writeString(dir.resolve("fixture-token"),"test-workload");
      var verifier=new UdpIdentityActivationVerifier(json,origin,token.toString());
      doAnswer(call->{verifier.requireCurrent(call.getArgument(0),call.getArgument(1),call.getArgument(2));return null;})
          .when(finalUdp).requireCurrent(anyString(),anyString(),anyMap());
      var coordinator=new ManagedActivationCoordinator(reviews,json,origin);
      assertThatThrownBy(()->coordinator.activate(p,challenge,activation,"test-auth","Bearer fixture"))
          .isInstanceOf(it.comune.trieste.ouf.onboarding.domain.DomainFailure.class);
      var retained=progress(reviews.read(p,source,version));
      assertThat(retained).containsEntry("readiness_phase","PAUSED").containsEntry("readiness_last_code","ONB_READINESS_DEPENDENCY_503");
      assertThat(onboarding.bundleHistory(source)).isEmpty();
      Thread.sleep(6100); // First persisted backoff is 4–6 seconds.
      var active=coordinator.activate(p,challenge,activation,"test-auth","Bearer fixture");
      assertThat(active).containsEntry("state","ACTIVE");
      assertThat(coordinator.activate(p,challenge,activation,"test-auth","Bearer fixture").get("publication"))
          .isEqualTo(active.get("publication"));
      assertThat(finalReads.get()).isEqualTo(2);assertThat(onboarding.bundleHistory(source)).hasSize(1);
    }finally{server.stop(0);}
  }

  private static class PublishedSnapshots {
    final Map<ManagedFileExecutionProposal.SemanticBinding,byte[]> snapshots=new HashMap<>();
    ManagedFileExecutionProposal.SemanticBinding active;
    ManagedFileExecutionProposal.SemanticBinding publish(String id,String version,String label){
      active=new ManagedFileExecutionProposal.SemanticBinding(id,version,UUID.randomUUID().toString(),UUID.randomUUID().toString());
      String rdf="<"+id+"> <http://www.w3.org/2000/01/rdf-schema#label> \""+label+"\" .\n";
      snapshots.put(active,rdf.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return active;
    }
  }
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
  @Test void expiredChallengeRenewsOnceUnderConcurrentChatRetries()throws Exception{
    UUID version=draft();var p=principal("test-tenant",human.subject());
    var first=reviews.prepare(p,new ManagedOnboardingReviewService.Prepare("review-test",version,1,"first",null),"c");
    UUID old=(UUID)first.get("challengeId");
    db.sql("update ouf_onboarding.approval_challenge set expires_at=clock_timestamp()-interval '1 second' where challenge_id=:c")
        .param("c",old).update();
    var renew=new ManagedOnboardingReviewService.Prepare("review-test",version,2,"renew",null);
    Map<String,Object> renewed;
    try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
      var start=new java.util.concurrent.CountDownLatch(1);
      var a=pool.submit(()->{start.await();return reviews.prepare(p,renew,"a");});
      var b=pool.submit(()->{start.await();return reviews.prepare(p,renew,"b");});
      start.countDown();renewed=a.get(15,java.util.concurrent.TimeUnit.SECONDS);
      assertThat(b.get(15,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(renewed);
    }
    UUID fresh=(UUID)renewed.get("challengeId");
    assertThat(fresh).isNotEqualTo(old);assertThat(renewed).containsEntry("challengeExpired",false);
    assertThat(onboarding.approvalCard(old)).containsEntry("status","EXPIRED");
    var decision=new ManagedOnboardingReviewService.Decision((String)renewed.get("configurationHash"),3);
    assertThatThrownBy(()->reviews.humanAction(p,old,"confirm",decision,"test-auth")).hasMessage("ONB_REVIEW_NOT_FOUND");
    assertThat(reviews.humanAction(p,fresh,"confirm",decision,"test-auth")).containsEntry("state","APPROVED");
    assertThat(db.sql("select count(*) from ouf_onboarding.approval_challenge").query(Long.class).single()).isEqualTo(2);
    assertThat(db.sql("select count(*) from ouf_onboarding.managed_candidate_recovery").query(Long.class).single()).isOne();
  }

  @Test void rejectedInitialCsvCanStartAnotherDraftAndNeedsAnotherHumanApproval(){
    UUID version=draft();var p=principal("test-tenant",human.subject());
    var first=reviews.prepare(p,new ManagedOnboardingReviewService.Prepare("review-test",version,1,"first",null),"c");
    reviews.humanAction(p,(UUID)first.get("challengeId"),"reject",
        new ManagedOnboardingReviewService.Decision((String)first.get("configurationHash"),2),"test-auth");
    var restart=new ManagedOnboardingReviewService.Prepare("review-test",version,3,"start-again",null,"RESTART_DRAFT");
    var second=reviews.prepare(p,restart,"restart");
    UUID next=(UUID)second.get("onboardingVersionId");
    assertThat(next).isNotEqualTo(version);assertThat(second).containsEntry("state","DRAFT");
    assertThat(reviews.prepare(p,restart,"lost-response")).isEqualTo(second);
    var prepared=reviews.prepare(p,new ManagedOnboardingReviewService.Prepare("review-test",next,
        ((Number)second.get("lockVersion")).longValue(),"review-again",null),"c");
    assertThat(reviews.humanAction(p,(UUID)prepared.get("challengeId"),"confirm",
        new ManagedOnboardingReviewService.Decision((String)prepared.get("configurationHash"),
            ((Number)prepared.get("lockVersion")).longValue()),"test-auth"))
        .containsEntry("state","APPROVED");
    assertThat(onboarding.version("review-test",version)).containsEntry("state","REJECTED");
    assertThat(db.sql("select decision from ouf_onboarding.approval_decision").query(String.class).list()).containsExactlyInAnyOrder("REJECT","APPROVE");
  }

  @Test void assetOwnershipAndTenantCannotBeChangedByAReviewRetry(){
    UUID version=draft();var command=new ManagedOnboardingReviewService.Prepare("review-test",version,1,"prepare:one",null);
    assertThatThrownBy(()->reviews.prepare(principal("test-tenant","other-human"),command,"c")).hasMessageContaining("not owned");
    reviews.prepare(principal("test-tenant","test-human"),command,"c");
    assertThatThrownBy(()->reviews.prepare(principal("other-tenant","test-human"),command,"c")).hasMessage("ONB_REVIEW_COMMAND_CONFLICT");
  }
  @Test void readinessLeaseAndBudgetSurviveRetryAndFenceLateActivation(){
    UUID version=draft();var p=principal("test-tenant","test-human");
    var prepared=reviews.prepare(p,new ManagedOnboardingReviewService.Prepare("review-test",version,1,"prepare:one",null),"c");UUID challenge=(UUID)prepared.get("challengeId");String hash=prepared.get("configurationHash").toString();
    reviews.humanAction(p,challenge,"confirm",new ManagedOnboardingReviewService.Decision(hash,2),"test-auth");
    var decision=new ManagedOnboardingReviewService.Decision(hash,3);var first=reviews.claimActivation(p,challenge,decision);
    assertThatThrownBy(()->reviews.claimActivation(p,challenge,decision)).hasMessage("ONB_READINESS_IN_PROGRESS");
    db.sql("update ouf_onboarding.managed_onboarding_review set readiness_lease_until=clock_timestamp()-interval '1 second' where challenge_id=:c").param("c",challenge).update();
    var second=reviews.claimActivation(p,challenge,decision);assertThat(second.generation()).isGreaterThan(first.generation());
    assertThatThrownBy(()->reviews.finishActivation(p,first,"test-auth")).hasMessage("ONB_READINESS_LEASE_LOST");
    reviews.activationFailure(first,"LATE_FAILURE",false);
    reviews.activationFailure(second,"DEPENDENCY_PAUSED",true,90);
    assertThatThrownBy(()->reviews.claimActivation(p,challenge,decision)).hasMessage("ONB_READINESS_BACKOFF");
    assertThat(db.sql("select readiness_next_at>clock_timestamp()+interval '80 seconds' from ouf_onboarding.managed_onboarding_review where challenge_id=:c").param("c",challenge).query(Boolean.class).single()).isTrue();
    when(policy.authorizeActive(any(),any(),anyString(),anyString(),any()).allowed()).thenReturn(false);
    assertThatThrownBy(()->reviews.claimActivation(p,challenge,decision)).hasMessage("ONB_REVIEW_AUTHORIZATION_DENIED");
    assertThat(db.sql("select readiness_attempts from ouf_onboarding.managed_onboarding_review where challenge_id=:c").param("c",challenge).query(Integer.class).single()).isEqualTo(2);
    when(policy.authorizeActive(any(),any(),anyString(),anyString(),any()).allowed()).thenReturn(true);
    db.sql("update ouf_onboarding.managed_onboarding_review set readiness_started_at=clock_timestamp()-interval '25 hours',readiness_next_at=null where challenge_id=:c").param("c",challenge).update();
    assertThatThrownBy(()->reviews.claimActivation(p,challenge,decision)).hasMessage("ONB_READINESS_BUDGET_EXHAUSTED");
    assertThat(db.sql("select readiness_phase from ouf_onboarding.managed_onboarding_review where challenge_id=:c").param("c",challenge).query(String.class).single()).isEqualTo("EXHAUSTED");
    assertThat(db.sql("select readiness_attempts from ouf_onboarding.managed_onboarding_review where challenge_id=:c").param("c",challenge).query(Integer.class).single()).isEqualTo(2);
    assertThat(db.sql("select count(*) from ouf_onboarding.published_configuration").query(Long.class).single()).isZero();
  }
  @Test void exactCompatibilityAcknowledgmentReconcilesWithoutAnotherEvidenceWrite(){
    UUID version=draft();var p=principal("test-tenant","test-human");var prepared=reviews.prepare(p,new ManagedOnboardingReviewService.Prepare("review-test",version,1,"prepare:one",null),"c");String hash=prepared.get("configurationHash").toString();
    var workload=new OnboardingService.Actor("test-ingestion","SERVICE",Set.of("ouf.ingestion.configuration.attest","ouf.onboarding.configuration.read"));
    assertThat(onboarding.ingestionCompatibility("review-test",version,hash,workload)).containsEntry("status","ABSENT").containsEntry("compatible",false);
    var first=onboarding.attestIngestionCompatibilityBound("review-test",version,hash,true,"Real probe fixture",workload,"c");
    assertThat(onboarding.attestIngestionCompatibilityBound("review-test",version,hash,true,"Ambiguous acknowledgment retry",workload,"c")).isEqualTo(first);
    assertThat(onboarding.ingestionCompatibility("review-test",version,hash,workload)).containsEntry("status","PRESENT").containsEntry("compatible",true);
    assertThatThrownBy(()->onboarding.attestIngestionCompatibilityBound("review-test",version,"sha256:"+"0".repeat(64),true,"drift",workload,"c")).hasMessageContaining("Frozen configuration identity changed");
    assertThat(db.sql("select count(*) from ouf_onboarding.consumer_compatibility_attestation").query(Long.class).single()).isOne();
  }

  private PrincipalContext workload(String tenant,PrincipalContext.ActorType type,Set<String> scopes){
    return new PrincipalContext("test-workload",tenant,type,"test-ingestion","test-acr","https://issuer.example","test-audience",scopes,
        new PrincipalContext.IdentityClaims(Set.of(),"test-acr",Set.of(),null));
  }
  @Test void workloadReadsOnlyAnExactlyApprovedManagedVersionWithinItsTenantAndPolicy(){
    UUID version=draft();var p=principal("test-tenant","test-human");
    var prepared=reviews.prepare(p,new ManagedOnboardingReviewService.Prepare("review-test",version,1,"prepare:one",null),"c");
    UUID challenge=(UUID)prepared.get("challengeId");String hash=prepared.get("configurationHash").toString();
    String read="ouf.onboarding.configuration.read";var service=workload("test-tenant",PrincipalContext.ActorType.SERVICE,Set.of(read));
    assertThatThrownBy(()->reviews.ingestionConfiguration(service,"review-test",version,hash)).hasMessage("ONB_INGESTION_CONFIGURATION_DRIFT");
    reviews.humanAction(p,challenge,"confirm",new ManagedOnboardingReviewService.Decision(hash,2),"test-auth");
    assertThat(reviews.ingestionConfiguration(service,"review-test",version,hash)).containsEntry("state","APPROVED").containsEntry("configuration_hash",hash);
    assertThatThrownBy(()->reviews.ingestionConfiguration(workload("other-tenant",PrincipalContext.ActorType.SERVICE,Set.of(read)),"review-test",version,hash)).hasMessage("ONB_INGESTION_CONFIGURATION_DENIED");
    for(var type:List.of(PrincipalContext.ActorType.HUMAN,PrincipalContext.ActorType.AI_AGENT))
      assertThatThrownBy(()->reviews.ingestionConfiguration(workload("test-tenant",type,Set.of(read)),"review-test",version,hash)).hasMessage("ONB_INGESTION_CONFIGURATION_AUTHORITY_REQUIRED");
    assertThatThrownBy(()->reviews.ingestionConfiguration(workload("test-tenant",PrincipalContext.ActorType.SERVICE,Set.of()),"review-test",version,hash)).hasMessage("ONB_INGESTION_CONFIGURATION_AUTHORITY_REQUIRED");
    assertThatThrownBy(()->reviews.ingestionConfiguration(service,"review-test",version,"sha256:"+"0".repeat(64))).hasMessage("ONB_INGESTION_CONFIGURATION_DRIFT");
    when(policy.authorizeActive(any(),any(),anyString(),anyString(),any()).allowed()).thenReturn(false);
    assertThatThrownBy(()->reviews.ingestionConfiguration(service,"review-test",version,hash)).hasMessage("ONB_INGESTION_CONFIGURATION_DENIED");
    assertThat(db.sql("select count(*) from ouf_onboarding.approval_decision").query(Long.class).single()).isOne();
    assertThat(db.sql("select count(*) from ouf_onboarding.consumer_compatibility_attestation").query(Long.class).single()).isZero();
  }
  @Test void explicitHumanResumeRetainsApprovalBudgetAndFencesTheBlockedGeneration(){
    UUID version=draft();var p=principal("test-tenant","test-human");
    var prepared=reviews.prepare(p,new ManagedOnboardingReviewService.Prepare("review-test",version,1,"prepare:one",null),"c");
    UUID challenge=(UUID)prepared.get("challengeId");String hash=prepared.get("configurationHash").toString();
    var decision=new ManagedOnboardingReviewService.Decision(hash,3);
    reviews.humanAction(p,challenge,"confirm",new ManagedOnboardingReviewService.Decision(hash,2),"test-auth");
    var first=reviews.claimActivation(p,challenge,decision);
    reviews.activationFailure(first,"ONB_READINESS_DEPENDENCY_403",false);
    var before=db.sql("select readiness_started_at,readiness_attempts from ouf_onboarding.managed_onboarding_review where challenge_id=:c").param("c",challenge).query().singleRow();
    assertThatThrownBy(()->reviews.claimActivation(p,challenge,decision)).hasMessage("ONB_READINESS_BLOCKED");
    var resume=new ManagedOnboardingReviewService.Resume(hash,3,first.generation(),"ONB_READINESS_DEPENDENCY_403");
    assertThatThrownBy(()->reviews.resumeActivation(principal("other-tenant","test-human"),challenge,resume,"test-auth")).hasMessage("ONB_REVIEW_NOT_FOUND");
    assertThat(reviews.resumeActivation(p,challenge,resume,"test-auth")).containsEntry("state","APPROVED").containsEntry("challengeStatus","CONFIRMED");
    assertThat(db.sql("select readiness_started_at,readiness_attempts from ouf_onboarding.managed_onboarding_review where challenge_id=:c").param("c",challenge).query().singleRow()).isEqualTo(before);
    assertThatThrownBy(()->reviews.resumeActivation(p,challenge,resume,"test-auth")).hasMessage("ONB_REVIEW_STALE_CARD");
    reviews.activationFailure(first,"LATE_DENIAL",false);
    assertThatThrownBy(()->reviews.finishActivation(p,first,"test-auth")).hasMessage("ONB_READINESS_LEASE_LOST");
    var second=reviews.claimActivation(p,challenge,decision);
    assertThat(second.generation()).isGreaterThan(first.generation());
    assertThat(db.sql("select readiness_attempts from ouf_onboarding.managed_onboarding_review where challenge_id=:c").param("c",challenge).query(Integer.class).single()).isEqualTo(2);
    assertThat(db.sql("select count(*) from ouf_onboarding.audit_event where event_type='READINESS_RESUME_REQUESTED' and onboarding_version_id=:v").param("v",version).query(Long.class).single()).isOne();
    assertThat(db.sql("select count(*) from ouf_onboarding.approval_decision").query(Long.class).single()).isOne();
    assertThat(db.sql("select count(*) from ouf_onboarding.published_configuration").query(Long.class).single()).isZero();
  }
  @Test void dependency422CanBeRecheckedThroughIngestionAndBothUdpGates() throws Exception{
    var fixture=governed(new PublishedSnapshots().publish("https://example.org/onto/Place","1.0.0","Prima"));
    var p=fixture.mapper();var prepared=fixture.review();
    String source=fixture.command().sourceId(),hash=(String)prepared.get("configurationHash");
    UUID version=fixture.command().onboardingVersionId(),challenge=(UUID)prepared.get("challengeId");
    var approved=reviews.humanAction(p,challenge,"confirm",new ManagedOnboardingReviewService.Decision(hash,
        ((Number)prepared.get("lockVersion")).longValue()),"test-auth");
    long lock=((Number)approved.get("lockVersion")).longValue();
    var activation=new ManagedOnboardingReviewService.Decision(hash,lock);
    String code="ONB_READINESS_DEPENDENCY_422";
    var corrected=new java.util.concurrent.atomic.AtomicBoolean();
    List<String> checks=Collections.synchronizedList(new ArrayList<>());
    var json=new com.fasterxml.jackson.databind.ObjectMapper();
    var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/api/trusted-human/v1/ingestion/onboarding-readiness",exchange->{
      checks.add("Ingestion");
      try(exchange){
        if(!corrected.get()){
          exchange.sendResponseHeaders(422,-1);
          return;
        }
        onboarding.attestIngestionCompatibilityBound(source,version,hash,true,"Exact fixture compatibility",
            new OnboardingService.Actor("test-ingestion","SERVICE",Set.of("ouf.ingestion.configuration.attest")),"recheck");
        var bytes=json.writeValueAsBytes(Map.of("status","PASS","attestationSubmitted",true,"sourceId",source,
            "onboardingVersionId",version,"configurationHash",hash));
        exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
      }
    });
    server.createContext("/api/udp/v1/governance/identity/preflight",exchange->{
      checks.add("UDP preflight");
      try(exchange){
        var bytes=json.writeValueAsBytes(Map.of("valid",true,"sourceId",source,"configurationHash",hash));
        exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
      }
    });
    doAnswer(call->{checks.add("UDP final");return null;}).when(finalUdp).requireCurrent(anyString(),anyString(),anyMap());
    server.start();
    try{
      var coordinator=new ManagedActivationCoordinator(reviews,json,"http://127.0.0.1:"+server.getAddress().getPort());
      assertThatThrownBy(()->coordinator.activate(p,challenge,activation,"test-auth","Bearer fixture"))
          .isInstanceOfSatisfying(it.comune.trieste.ouf.onboarding.domain.DomainFailure.class,
              failure->assertThat(failure.code()).isEqualTo(code));
      var blocked=progress(cardProgress(p,challenge));
      assertThat(blocked).containsEntry("readiness_phase","BLOCKED").containsEntry("readiness_last_code",code)
          .containsEntry("humanRecheckAllowed",true).containsEntry("cumulativeAttempts",1);
      assertThatThrownBy(()->reviews.claimActivation(p,challenge,activation)).hasMessage("ONB_READINESS_BLOCKED");
      long generation=((Number)blocked.get("readiness_generation")).longValue();
      var request=new ManagedOnboardingReviewService.Resume(hash,lock,generation,code);
      assertThatThrownBy(()->reviews.resumeActivation(p,challenge,
          new ManagedOnboardingReviewService.Resume(hash,lock,generation-1,code),"test-auth")).hasMessage("ONB_REVIEW_STALE_CARD");
      assertThatThrownBy(()->reviews.resumeActivation(p,challenge,request," ")).hasMessage("ONB_REVIEW_AUTHENTICATION_REQUIRED");
      corrected.set(true);
      var resumed=reviews.resumeActivation(p,challenge,request,"test-auth");
      assertThat(resumed).containsEntry("state","APPROVED").containsEntry("challengeStatus","CONFIRMED");
      assertThat(progress(resumed)).containsEntry("readiness_phase","PAUSED").containsEntry("readiness_generation",generation+1)
          .containsEntry("humanRecheckAllowed",false).containsEntry("budgetExpiresAt",blocked.get("budgetExpiresAt"))
          .containsEntry("counterLineageVerified",true).containsEntry("sessionId",blocked.get("sessionId"))
          .containsEntry("cumulativeAttempts",blocked.get("cumulativeAttempts"));
      assertThat(checks).containsExactly("Ingestion");
      assertThat(onboarding.bundleHistory(source)).isEmpty();
      assertThatThrownBy(()->reviews.resumeActivation(p,challenge,request,"test-auth")).hasMessage("ONB_REVIEW_STALE_CARD");
      assertThat(db.sql("""
          select count(*) from ouf_onboarding.audit_event
          where onboarding_version_id=:v and event_type='READINESS_RESUME_REQUESTED'
            and actor_type='HUMAN_USER' and payload_summary->>'previousCode'=:code
          """).param("v",version).param("code",code).query(Long.class).single()).isOne();
      var active=coordinator.activate(p,challenge,activation,"test-auth","Bearer fixture");
      assertThat(active).containsEntry("state","ACTIVE").containsEntry("ingestionCompatibilityObserved",true);
      assertThat(progress(active)).containsEntry("cumulativeAttempts",2);
      assertThat(checks).containsExactly("Ingestion","Ingestion","UDP preflight","UDP final");
      assertThat(onboarding.bundleHistory(source)).hasSize(1);
    }finally{server.stop(0);}
  }
  @Test void humanResumeCannotResetExhaustionOrUnknownAmbiguousFailures(){
    UUID version=draft();var p=principal("test-tenant","test-human");
    var prepared=reviews.prepare(p,new ManagedOnboardingReviewService.Prepare("review-test",version,1,"prepare:one",null),"c");
    UUID challenge=(UUID)prepared.get("challengeId");String hash=prepared.get("configurationHash").toString();
    reviews.humanAction(p,challenge,"confirm",new ManagedOnboardingReviewService.Decision(hash,2),"test-auth");
    var claim=reviews.claimActivation(p,challenge,new ManagedOnboardingReviewService.Decision(hash,3));
    reviews.activationFailure(claim,"ONB_READINESS_OUTCOME_UNCONFIRMED",false);
    assertThat(progress(cardProgress(p,challenge))).containsEntry("humanRecheckAllowed",false);
    assertThatThrownBy(()->reviews.resumeActivation(p,challenge,new ManagedOnboardingReviewService.Resume(hash,3,claim.generation(),"ONB_READINESS_OUTCOME_UNCONFIRMED"),"test-auth")).hasMessage("ONB_READINESS_RESUME_NOT_ALLOWED");
    reviews.activationFailure(claim,"ONB_READINESS_DEPENDENCY_403",false);
    assertThat(progress(reviews.read(p,"review-test",version))).containsEntry("readiness_last_code","ONB_READINESS_OUTCOME_UNCONFIRMED");
  }
  @Test void humanResumeOf422CannotResetExhaustedAttemptsOrTime(){
    var e=exhausted();var p=e.principal();UUID challenge=e.challenge();String hash=e.hash();
    db.sql("update ouf_onboarding.managed_onboarding_review set readiness_started_at=clock_timestamp() where challenge_id=:c").param("c",challenge).update();
    var claim=reviews.claimActivation(p,challenge,new ManagedOnboardingReviewService.Decision(hash,3));
    reviews.activationFailure(claim,"ONB_READINESS_DEPENDENCY_422",false);
    var resume=new ManagedOnboardingReviewService.Resume(hash,3,claim.generation(),"ONB_READINESS_DEPENDENCY_422");
    when(policy.authorizeActive(any(),any(),anyString(),anyString(),any()).allowed()).thenReturn(false);
    assertThatThrownBy(()->reviews.resumeActivation(p,challenge,resume,"test-auth")).hasMessage("ONB_REVIEW_AUTHORIZATION_DENIED");
    when(policy.authorizeActive(any(),any(),anyString(),anyString(),any()).allowed()).thenReturn(true);
    db.sql("update ouf_onboarding.managed_onboarding_review set readiness_attempts=128 where challenge_id=:c").param("c",challenge).update();
    assertThat(progress(cardProgress(p,challenge))).containsEntry("humanRecheckAllowed",false).containsEntry("humanBudgetGrantAvailable",true);
    assertThatThrownBy(()->reviews.resumeActivation(p,challenge,resume,"test-auth")).hasMessage("ONB_READINESS_BUDGET_EXHAUSTED");
    db.sql("update ouf_onboarding.managed_onboarding_review set readiness_attempts=1,readiness_started_at=clock_timestamp()-interval '25 hours' where challenge_id=:c").param("c",challenge).update();
    assertThat(progress(cardProgress(p,challenge))).containsEntry("humanRecheckAllowed",false).containsEntry("budgetExhausted",true);
    assertThatThrownBy(()->reviews.resumeActivation(p,challenge,resume,"test-auth")).hasMessage("ONB_READINESS_BUDGET_EXHAUSTED");
    assertThat(db.sql("select readiness_phase from ouf_onboarding.managed_onboarding_review where challenge_id=:c").param("c",challenge).query(String.class).single()).isEqualTo("BLOCKED");
  }


  private record Exhausted(UUID challenge,UUID version,String hash,PrincipalContext principal){}
  private Exhausted exhausted(){
    UUID version=draft();var p=principal("test-tenant","test-human");
    var prepared=reviews.prepare(p,new ManagedOnboardingReviewService.Prepare("review-test",version,1,"prepare:finite",null),"c");
    UUID challenge=(UUID)prepared.get("challengeId");String hash=prepared.get("configurationHash").toString();
    reviews.humanAction(p,challenge,"confirm",new ManagedOnboardingReviewService.Decision(hash,2),"test-auth");
    db.sql("update ouf_onboarding.managed_onboarding_review set readiness_started_at=clock_timestamp()-interval '25 hours',readiness_attempts=3,readiness_generation=4,readiness_phase='PAUSED',readiness_last_code='ONB_READINESS_DEPENDENCY_503' where challenge_id=:c").param("c",challenge).update();
    return new Exhausted(challenge,version,hash,p);
  }
  private ManagedOnboardingReviewService.BudgetDecision budget(Exhausted e,String key,long generation){
    return new ManagedOnboardingReviewService.BudgetDecision(e.hash(),3,generation,key,"Dependency path recovered and exact resource verified",3,900);
  }
  @Test void explicit403ResumeUsesTheCurrentFiniteSessionWithoutResettingItsOriginalHistory(){
    var e=exhausted();reviews.grantReadinessBudget(e.principal(),e.challenge(),budget(e,"grant:resume",4));
    var claim=reviews.claimActivation(e.principal(),e.challenge(),new ManagedOnboardingReviewService.Decision(e.hash(),3));
    reviews.activationFailure(claim,"ONB_READINESS_DEPENDENCY_403",false);
    var before=db.sql("select readiness_started_at,readiness_attempts,readiness_session_id from ouf_onboarding.managed_onboarding_review where challenge_id=:c")
        .param("c",e.challenge()).query().singleRow();
    var sessions=db.sql("select to_jsonb(s)::text from ouf_onboarding.managed_readiness_budget_session s").query(String.class).single();
    var resume=new ManagedOnboardingReviewService.Resume(e.hash(),3,claim.generation(),"ONB_READINESS_DEPENDENCY_403");
    when(policy.authorizeActive(any(),any(),anyString(),anyString(),any()).allowed()).thenReturn(false);
    assertThatThrownBy(()->reviews.resumeActivation(e.principal(),e.challenge(),resume,"test-auth")).hasMessage("ONB_REVIEW_AUTHORIZATION_DENIED");
    when(policy.authorizeActive(any(),any(),anyString(),anyString(),any()).allowed()).thenReturn(true);
    reviews.resumeActivation(e.principal(),e.challenge(),resume,"test-auth");
    assertThat(db.sql("select readiness_started_at,readiness_attempts,readiness_session_id from ouf_onboarding.managed_onboarding_review where challenge_id=:c")
        .param("c",e.challenge()).query().singleRow()).isEqualTo(before);
    assertThat(db.sql("select to_jsonb(s)::text from ouf_onboarding.managed_readiness_budget_session s").query(String.class).single()).isEqualTo(sessions);
    assertThatThrownBy(()->reviews.finishActivation(e.principal(),claim,"test-auth")).hasMessage("ONB_READINESS_LEASE_LOST");
    var next=reviews.claimActivation(e.principal(),e.challenge(),new ManagedOnboardingReviewService.Decision(e.hash(),3));
    assertThat(next.generation()).isGreaterThan(claim.generation());
    assertThat(db.sql("select readiness_attempts from ouf_onboarding.managed_onboarding_review where challenge_id=:c").param("c",e.challenge()).query(Integer.class).single()).isEqualTo(5);
    assertThat(db.sql("select count(*) from ouf_onboarding.published_configuration").query(Long.class).single()).isZero();
  }
  @SuppressWarnings("unchecked") private Map<String,Object> progress(Map<String,Object> result){return (Map<String,Object>)result.get("activationProgress");}
  @SuppressWarnings("unchecked") private Map<String,Object> cardProgress(PrincipalContext p,UUID challenge){
    return (Map<String,Object>)reviews.humanCard(p,challenge).get("progress");
  }
  @Test void freshHumanBudgetRetainsCumulativeHistoryAndDoesNotActivate(){
    var e=exhausted();var old=db.sql("select readiness_started_at from ouf_onboarding.managed_onboarding_review where challenge_id=:c").param("c",e.challenge()).query().singleRow();
    assertThat(progress(reviews.read(e.principal(),"review-test",e.version()))).containsEntry("budgetExhausted",true).containsEntry("humanBudgetGrantAvailable",true);
    var grant=reviews.grantReadinessBudget(e.principal(),e.challenge(),budget(e,"grant:one",4));
    assertThat(grant).containsEntry("state","APPROVED").containsEntry("ingestionCompatibilityObserved",false);
    assertThat(progress(grant)).containsEntry("cumulativeAttempts",3).containsEntry("sessionAttempts",0L).containsEntry("maximumAttempts",3).containsEntry("maximumElapsedSeconds",900).containsEntry("budgetExhausted",false);
    assertThat(db.sql("select readiness_started_at from ouf_onboarding.managed_onboarding_review where challenge_id=:c").param("c",e.challenge()).query().singleRow()).isEqualTo(old);
    var history=db.sql("select base_attempts,prior_generation,prior_phase,prior_last_code,reason,subject_id,expires_at-issued_at duration from ouf_onboarding.managed_readiness_budget_session").query().singleRow();
    assertThat(history).containsEntry("base_attempts",3).containsEntry("prior_generation",4L).containsEntry("prior_phase","PAUSED").containsEntry("prior_last_code","ONB_READINESS_DEPENDENCY_503").containsEntry("subject_id","test-human");
    assertThat(db.sql("select count(*) from ouf_onboarding.published_configuration").query(Long.class).single()).isZero();
    var next=reviews.claimActivation(e.principal(),e.challenge(),new ManagedOnboardingReviewService.Decision(e.hash(),3));
    assertThat(progress(next.progress())).containsEntry("cumulativeAttempts",4).containsEntry("sessionAttempts",1L);
    assertThat(next.generation()).isEqualTo(6);
  }
  @Test void explicitActivationAfterNewSessionPublishesOnceAndReconcilesARepeatedCompletion(){
    var e=exhausted();reviews.grantReadinessBudget(e.principal(),e.challenge(),budget(e,"grant:activate",4));
    var workload=new OnboardingService.Actor("test-ingestion","SERVICE",Set.of("ouf.ingestion.configuration.attest","ouf.onboarding.configuration.read"));
    onboarding.attestIngestionCompatibilityBound("review-test",e.version(),e.hash(),true,"Exact fixture compatibility",workload,"session-compatibility");
    var decision=new ManagedOnboardingReviewService.Decision(e.hash(),3);
    var claim=reviews.claimActivation(e.principal(),e.challenge(),decision);
    reviews.activationPhase(claim,"CHECKING_IDENTITY");reviews.activationPhase(claim,"ACTIVATING");
    var completed=reviews.finishActivation(e.principal(),claim,"test-auth");
    assertThat(completed).containsEntry("state","ACTIVE").containsKey("publication");
    assertThat(db.sql("select state from ouf_onboarding.managed_readiness_budget_session").query(String.class).single()).isEqualTo("COMPLETED");
    assertThat(db.sql("select count(*) from ouf_onboarding.published_configuration where onboarding_version_id=:v").param("v",e.version()).query(Long.class).single()).isOne();
    assertThat(reviews.claimActivation(e.principal(),e.challenge(),decision).reconciled()).isTrue();
    assertThat(progress(reviews.read(e.principal(),"review-test",e.version()))).containsEntry("cumulativeAttempts",4);
    assertThat(db.sql("select count(*) from ouf_onboarding.published_configuration").query(Long.class).single()).isOne();
  }
  @Test void lostGrantResponseAndConcurrentHumanCommandsCreateOneSession()throws Exception{
    var e=exhausted();var command=budget(e,"grant:concurrent",4);
    try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
      var start=new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.Callable<Map<String,Object>> call=()->{start.await();return reviews.grantReadinessBudget(e.principal(),e.challenge(),command);};
      var one=pool.submit(call);var two=pool.submit(call);start.countDown();
      assertThat(progress(one.get()).get("sessionId")).isEqualTo(progress(two.get()).get("sessionId"));
    }
    assertThat(db.sql("select count(*) from ouf_onboarding.managed_readiness_budget_session").query(Long.class).single()).isOne();
    assertThatThrownBy(()->reviews.grantReadinessBudget(e.principal(),e.challenge(),budget(e,"another-key",4))).hasMessage("ONB_REVIEW_STALE_CARD");
    var changed=new ManagedOnboardingReviewService.BudgetDecision(e.hash(),3,4,"grant:concurrent","A different declared reason",3,900);
    assertThatThrownBy(()->reviews.grantReadinessBudget(e.principal(),e.challenge(),changed)).hasMessage("ONB_READINESS_BUDGET_COMMAND_CONFLICT");
    assertThat(progress(reviews.read(e.principal(),"review-test",e.version())).get("cumulativeAttempts")).isEqualTo(3);
  }
  @Test void newBudgetFencesOldWorkerAndNeverRefundsAnAttempt(){
    var e=exhausted();var old=new ManagedOnboardingReviewService.ActivationClaim(e.challenge(),e.version(),"review-test",e.hash(),3,4,Map.of(),Map.of(),false,false);
    var grant=reviews.grantReadinessBudget(e.principal(),e.challenge(),budget(e,"grant:fence",4));
    reviews.activationFailure(old,"LATE_FAILURE",false);
    assertThatThrownBy(()->reviews.activationPhase(old,"ACTIVATING")).hasMessage("ONB_READINESS_LEASE_LOST");
    assertThatThrownBy(()->reviews.finishActivation(e.principal(),old,"test-auth")).hasMessage("ONB_READINESS_LEASE_LOST");
    assertThat(progress(reviews.read(e.principal(),"review-test",e.version()))).isEqualTo(progress(grant));
    assertThat(db.sql("select count(*) from ouf_onboarding.published_configuration").query(Long.class).single()).isZero();
  }
  @Test void threeAdditionalAttemptsExhaustSessionAndNextHumanSessionKeepsBothHistories(){
    var e=exhausted();reviews.grantReadinessBudget(e.principal(),e.challenge(),budget(e,"grant:first",4));
    var activation=new ManagedOnboardingReviewService.Decision(e.hash(),3);
    for(int i=0;i<3;i++){
      var claim=reviews.claimActivation(e.principal(),e.challenge(),activation);
      reviews.activationFailure(claim,"ONB_READINESS_DEPENDENCY_503",true);
      db.sql("update ouf_onboarding.managed_onboarding_review set readiness_next_at=null where challenge_id=:c").param("c",e.challenge()).update();
    }
    assertThatThrownBy(()->reviews.claimActivation(e.principal(),e.challenge(),activation)).hasMessage("ONB_READINESS_BUDGET_EXHAUSTED");
    var current=progress(reviews.read(e.principal(),"review-test",e.version()));
    assertThat(current).containsEntry("cumulativeAttempts",6).containsEntry("sessionAttempts",3L);
    long generation=((Number)current.get("readiness_generation")).longValue();
    var next=reviews.grantReadinessBudget(e.principal(),e.challenge(),budget(e,"grant:second",generation));
    assertThat(progress(next)).containsEntry("cumulativeAttempts",6).containsEntry("sessionAttempts",0L);
    assertThat(db.sql("select base_attempts from ouf_onboarding.managed_readiness_budget_session order by issued_at").query(Integer.class).list()).containsExactly(3,6);
    assertThat(db.sql("select count(*) from ouf_onboarding.managed_readiness_budget_session where state='ACTIVE'").query(Long.class).single()).isOne();
    assertThat(progress(reviews.claimActivation(e.principal(),e.challenge(),activation).progress())).containsEntry("cumulativeAttempts",7);
  }
  @Test void cumulativeCountCanPassLegacy128ButCanNeverRegressBelowSessionFloor(){
    var e=exhausted();
    db.sql("update ouf_onboarding.managed_onboarding_review set readiness_attempts=128 where challenge_id=:c").param("c",e.challenge()).update();
    var command=budget(e,"grant:past128",4);reviews.grantReadinessBudget(e.principal(),e.challenge(),command);
    var claim=reviews.claimActivation(e.principal(),e.challenge(),new ManagedOnboardingReviewService.Decision(e.hash(),3));
    assertThat(progress(claim.progress())).containsEntry("cumulativeAttempts",129).containsEntry("sessionAttempts",1L);
    reviews.activationFailure(claim,"ONB_READINESS_DEPENDENCY_503",true);
    db.sql("update ouf_onboarding.managed_onboarding_review set readiness_attempts=127,readiness_next_at=null where challenge_id=:c").param("c",e.challenge()).update();
    assertThatThrownBy(()->reviews.claimActivation(e.principal(),e.challenge(),new ManagedOnboardingReviewService.Decision(e.hash(),3))).hasMessage("ONB_READINESS_COUNTER_LINEAGE_UNPROVEN");
    db.sql("update ouf_onboarding.managed_readiness_budget_session set issued_at=issued_at-interval '1 hour',expires_at=expires_at-interval '1 hour'").update();
    long generation=((Number)progress(reviews.read(e.principal(),"review-test",e.version())).get("readiness_generation")).longValue();
    assertThatThrownBy(()->reviews.grantReadinessBudget(e.principal(),e.challenge(),budget(e,"grant:regressed",generation))).hasMessage("ONB_READINESS_BUDGET_RECONCILIATION_REQUIRED");
    assertThat(db.sql("select count(*) from ouf_onboarding.managed_readiness_budget_session").query(Long.class).single()).isOne();
  }
  @Test void expiredSessionCannotBeExtendedBySameKeyOrRestartedController(){
    var e=exhausted();var key=budget(e,"grant:expired",4);reviews.grantReadinessBudget(e.principal(),e.challenge(),key);
    db.sql("update ouf_onboarding.managed_readiness_budget_session set issued_at=issued_at-interval '1 hour',expires_at=expires_at-interval '1 hour'").update();
    assertThatThrownBy(()->reviews.claimActivation(e.principal(),e.challenge(),new ManagedOnboardingReviewService.Decision(e.hash(),3))).hasMessage("ONB_READINESS_BUDGET_EXHAUSTED");
    var reconciled=reviews.grantReadinessBudget(e.principal(),e.challenge(),key);
    assertThat(progress(reconciled)).containsEntry("budgetExhausted",true).containsEntry("cumulativeAttempts",3);
    assertThat(db.sql("select count(*) from ouf_onboarding.managed_readiness_budget_session").query(Long.class).single()).isOne();
  }
  @Test void crashedLastClaimReconcilesBeforeHumanRenewalAndLateWorkerCannotPublish(){
    var e=exhausted();var p=e.principal();UUID c=e.challenge();
    db.sql("update ouf_onboarding.managed_onboarding_review set readiness_started_at=clock_timestamp(),readiness_attempts=127,readiness_generation=127 where challenge_id=:c").param("c",c).update();
    var decision=new ManagedOnboardingReviewService.Decision(e.hash(),3);
    var lost=reviews.claimActivation(p,c,decision);
    assertThat(progress(reviews.read(p,"review-test",e.version()))).containsEntry("humanBudgetGrantAvailable",false);
    db.sql("update ouf_onboarding.managed_onboarding_review set readiness_lease_until=clock_timestamp()-interval '1 second' where challenge_id=:c").param("c",c).update();
    assertThat(progress(reviews.read(p,"review-test",e.version())))
        .containsEntry("reconciledBudgetReason","ONB_READINESS_INTERRUPTED");
    var grant=reviews.grantReadinessBudget(p,c,budget(e,"after-crash",lost.generation()));
    assertThat(progress(grant)).containsEntry("sessionAttempts",0L).containsEntry("cumulativeAttempts",128);
    assertThatThrownBy(()->reviews.finishActivation(p,lost,"test-auth")).hasMessage("ONB_READINESS_LEASE_LOST");
    var fresh=reviews.claimActivation(p,c,decision);
    onboarding.attestIngestionCompatibilityBound("review-test",e.version(),e.hash(),true,"Current fixture",
        new OnboardingService.Actor("test-ingestion","SERVICE",Set.of("ouf.ingestion.configuration.attest")),"fixture");
    assertThat(reviews.finishActivation(p,fresh,"test-auth")).containsEntry("state","ACTIVE");
    assertThat(reviews.claimActivation(p,c,decision).reconciled()).isTrue();
    assertThat(onboarding.bundleHistory("review-test")).hasSize(1);
  }
  @Test void expired403AllowsFreshHumanBudgetAndRechecksDependency(){
    var e=exhausted();
    db.sql("update ouf_onboarding.managed_onboarding_review set readiness_phase='BLOCKED',readiness_last_code='ONB_READINESS_DEPENDENCY_403' where challenge_id=:c")
        .param("c",e.challenge()).update();
    var grant=reviews.grantReadinessBudget(e.principal(),e.challenge(),budget(e,"after-403",4));
    assertThat(progress(grant)).containsEntry("sessionAttempts",0L);
    var fresh=reviews.claimActivation(e.principal(),e.challenge(),new ManagedOnboardingReviewService.Decision(e.hash(),3));
    reviews.activationFailure(fresh,"ONB_READINESS_DEPENDENCY_403",false);
    assertThat(progress(reviews.read(e.principal(),"review-test",e.version()))).containsEntry("readiness_phase","BLOCKED");
    assertThat(onboarding.bundleHistory("review-test")).isEmpty();
    reviews.resumeActivation(e.principal(),e.challenge(),new ManagedOnboardingReviewService.Resume(e.hash(),3,
        fresh.generation(),"ONB_READINESS_DEPENDENCY_403"),"test-auth");
    onboarding.attestIngestionCompatibilityBound("review-test",e.version(),e.hash(),true,"Current fixture",
        new OnboardingService.Actor("test-ingestion","SERVICE",Set.of("ouf.ingestion.configuration.attest")),"c");
    var retry=reviews.claimActivation(e.principal(),e.challenge(),new ManagedOnboardingReviewService.Decision(e.hash(),3));
    assertThat(reviews.finishActivation(e.principal(),retry,"test-auth")).containsEntry("state","ACTIVE");
    assertThat(onboarding.bundleHistory("review-test")).hasSize(1);
  }
  @Test void ambiguousPublicationBlocksRenewalOfLegacyInterruptedClaim(){
    var e=exhausted();
    db.sql("update ouf_onboarding.managed_onboarding_review set readiness_phase='EXHAUSTED',readiness_last_code=null where challenge_id=:c")
        .param("c",e.challenge()).update();
    assertThat(progress(reviews.read(e.principal(),"review-test",e.version()))).containsEntry("humanBudgetGrantAvailable",true);
    db.sql("insert into ouf_onboarding.published_configuration(publication_id,source_id,onboarding_version_id,bundle_version,bundle,checksum,active,effective_from) values(:id,'review-test',:v,1,'{}',:h,false,clock_timestamp())")
        .param("id",UUID.randomUUID()).param("v",e.version()).param("h",e.hash()).update();
    assertThat(progress(reviews.read(e.principal(),"review-test",e.version()))).containsEntry("humanBudgetGrantAvailable",false);
    assertThatThrownBy(()->reviews.grantReadinessBudget(e.principal(),e.challenge(),budget(e,"ambiguous",4)))
        .hasMessage("ONB_READINESS_BUDGET_RECONCILIATION_REQUIRED");
  }
  @Test void renewalNeverOverridesUnknownOutcomeLeaseBackoffRevocationOrHash(){
    var e=exhausted();
    for(String phase:List.of("BLOCKED","ACTIVATING","CHECKING_COMPATIBILITY")){
      db.sql("update ouf_onboarding.managed_onboarding_review set readiness_phase=:p where challenge_id=:c").param("p",phase).param("c",e.challenge()).update();
      assertThatThrownBy(()->reviews.grantReadinessBudget(e.principal(),e.challenge(),budget(e,"grant:blocked",4))).hasMessage("ONB_READINESS_BUDGET_RECONCILIATION_REQUIRED");
    }
    db.sql("update ouf_onboarding.managed_onboarding_review set readiness_phase='PAUSED',readiness_last_code='ONB_READINESS_OUTCOME_UNCONFIRMED' where challenge_id=:c").param("c",e.challenge()).update();
    assertThatThrownBy(()->reviews.grantReadinessBudget(e.principal(),e.challenge(),budget(e,"grant:unknown",4))).hasMessage("ONB_READINESS_BUDGET_RECONCILIATION_REQUIRED");
    db.sql("update ouf_onboarding.managed_onboarding_review set readiness_last_code='ONB_READINESS_DEPENDENCY_503',readiness_lease_until=clock_timestamp()+interval '1 minute' where challenge_id=:c").param("c",e.challenge()).update();
    assertThatThrownBy(()->reviews.grantReadinessBudget(e.principal(),e.challenge(),budget(e,"grant:leased",4))).hasMessage("ONB_READINESS_BUDGET_RECONCILIATION_REQUIRED");
    db.sql("update ouf_onboarding.managed_onboarding_review set readiness_lease_until=null,readiness_next_at=clock_timestamp()+interval '1 hour' where challenge_id=:c").param("c",e.challenge()).update();
    assertThatThrownBy(()->reviews.grantReadinessBudget(e.principal(),e.challenge(),budget(e,"grant:delayed",4))).hasMessage("ONB_READINESS_BUDGET_RECONCILIATION_REQUIRED");
    when(policy.authorizeActive(any(),any(),anyString(),anyString(),any()).allowed()).thenReturn(false);
    assertThatThrownBy(()->reviews.grantReadinessBudget(e.principal(),e.challenge(),budget(e,"grant:revoked",4))).hasMessage("ONB_REVIEW_AUTHORIZATION_DENIED");
    when(policy.authorizeActive(any(),any(),anyString(),anyString(),any()).allowed()).thenReturn(true);
    var wrong=new ManagedOnboardingReviewService.BudgetDecision("sha256:"+"0".repeat(64),3,4,"grant:hash","Do not adopt changed configuration",3,900);
    assertThatThrownBy(()->reviews.grantReadinessBudget(e.principal(),e.challenge(),wrong)).hasMessage("ONB_REVIEW_STALE_CARD");
    assertThat(db.sql("select count(*) from ouf_onboarding.managed_readiness_budget_session").query(Long.class).single()).isZero();
  }
  @SuppressWarnings("unchecked") @Test void latestReadRecoversTheOwnerVersionWithoutNewChallengeOrApproval(){
    UUID first=draft();
    db.sql("update ouf_onboarding.source set metadata=metadata||cast(:m as jsonb) where source_id='review-test'").param("m","{\"managedTenantId\":\"test-tenant\"}").update();
    var config=(Map<String,Object>)onboarding.version("review-test",first).get("configuration");
    var next=onboarding.createVersion("review-test",config,human,"new-explicit-draft");UUID second=(UUID)next.get("onboarding_version_id");
    var recovered=reviews.readLatest(principal("test-tenant","test-human"),"review-test");
    assertThat(recovered).containsEntry("onboardingVersionId",second).containsEntry("state","DRAFT");
    assertThat(recovered).containsEntry("reasonCode",ManagedMcpObservation.Reason.DRAFT_READY)
        .containsEntry("executionProposalRequired",false).containsEntry("missingSections",List.of());
    assertThat(recovered).doesNotContainKeys("configuration","stagingRef","credentialRef");
    assertThat(db.sql("select count(*) from ouf_onboarding.approval_challenge").query(Long.class).single()).isZero();
    assertThat(db.sql("select count(*) from ouf_onboarding.approval_decision").query(Long.class).single()).isZero();
    assertThatThrownBy(()->reviews.readLatest(principal("other-tenant","test-human"),"review-test")).hasMessage("ONB_REVIEW_OWNER_SCOPE_UNPROVEN");
  }
  @Test void latestReadCannotInferLegacyTenantOrBypassCurrentAuthorization(){
    draft();var p=principal("test-tenant","test-human");
    assertThatThrownBy(()->reviews.readLatest(p,"review-test")).hasMessage("ONB_REVIEW_OWNER_SCOPE_UNPROVEN");
    assertThatThrownBy(()->reviews.readLatest(p,"unknown-source")).hasMessage("ONB_REVIEW_AUTHORIZATION_DENIED");
    db.sql("update ouf_onboarding.source set metadata=metadata||cast(:m as jsonb) where source_id='review-test'").param("m","{\"managedTenantId\":\"test-tenant\"}").update();
    when(policy.authorizeActive(any(),any(),anyString(),anyString(),any()).allowed()).thenReturn(false);
    assertThatThrownBy(()->reviews.readLatest(p,"review-test")).hasMessage("ONB_REVIEW_AUTHORIZATION_DENIED");
    assertThat(db.sql("select count(*) from ouf_onboarding.managed_onboarding_review").query(Long.class).single()).isZero();
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
    // Declared legacy fixture contract, not the target GOVERNED_IDENTITY profile.
    runtime.put("udp",Map.of("resolution",Map.of("strategyId","EXACT_SOURCE_KEY","strategyVersion","1",
        "policyRef","test-resolution-policy","canonicalType","urn:test:Object",
        "canonicalKeyProperty","urn:test:id","matchProperty","urn:test:id"),"materialization",Map.of("policyRef","test-policy")));
    config.put("semanticReferenceBindings",List.of(Map.of("semanticId","test","semanticVersion","1","revisionId","test-revision","publicationSetId","test-set")));
    onboarding.patchVersion("review-test",version,0,config,human,"test-complete-configuration");return version;
  }
  private static String required(String name){return Objects.requireNonNull(System.getenv(name),name+" required");}
}

