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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real lifecycle/PG transactions; authorization allow/deny is a TEST fixture, never a deployment grant. */
@SpringBootTest(properties="ouf.authorization.ths.public-origin=https://review.example")
class ManagedOnboardingReviewRuntimeTest {
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->required("OUF_ONB_DB_URL"));r.add("spring.datasource.username",()->required("OUF_ONB_DB_USER"));r.add("spring.datasource.password",()->required("OUF_ONB_DB_PASSWORD"));}
  @Autowired ManagedOnboardingReviewService reviews; @Autowired ManagedFileService files;
  @Autowired OnboardingService onboarding; @Autowired CanonicalHash hashes; @Autowired JdbcClient db;
  @MockitoBean(answers=Answers.RETURNS_DEEP_STUBS) AuthorizationPolicyRegistry policy;
  private final OnboardingService.Actor human=new OnboardingService.Actor("test-human","HUMAN_USER");
  private PrincipalContext principal(String tenant,String subject){return new PrincipalContext(subject,tenant,PrincipalContext.ActorType.HUMAN,"test-mcp","test-acr","https://issuer.example","test-audience",Set.of(ManagedOnboardingReviewService.PREPARE,ManagedOnboardingReviewService.READ,"ouf.onboarding.configuration.write"),new PrincipalContext.IdentityClaims(Set.of(),"test-acr",Set.of(),null));}
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
  @Test void d084_24_valueMapIsFrozenBeforeReviewAndTheCardExposesOnlyItsSummary()throws Exception{
    String source="colours",tenant="test-tenant",cls="urn:fixture:Place",property="urn:fixture:colour",map="urn:fixture:map";
    String set=UUID.randomUUID().toString(),mapSet=UUID.randomUUID().toString(),ref=map+"@"+mapSet;
    String vocabulary="urn:fixture:colours@"+UUID.randomUUID();
    byte[] csv="code\nprivate-fixture-code\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    UUID asset=(UUID)files.registerOwned(null,"object://fixture/codes.csv",hashes.ofBytes(csv),"text/csv",
        csv.length,human.subject(),"retention://test",tenant).get("asset_id");
    UUID profile=(UUID)files.profile(asset,csv,"object://fixture/codes-sample").get("profile_id");
    var fields=List.of(new ManagedFileService.FieldDecision("code","INCLUDE","OPEN",property,"VALUE_MAP_V1",
        null,null,null,Map.of("valueMapRef",ref)));
    var ids=List.of(cls,property,map);
    var draft=files.onboardIdempotent(asset,profile,source,"Colori","Comune",cls,ids.stream().map(id->id+"@1").toList(),
        List.of("code"),fields,null,human,"create","create",tenant);
    UUID version=(UUID)draft.get("onboardingVersionId");
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
    for(var pin:bindings){
      String kind=cls.equals(pin.semanticId())?"CLASS":property.equals(pin.semanticId())?"PROPERTY":"VALUE_MAP";
      var response=new LinkedHashMap<String,Object>(Map.of("semantic_id",pin.semanticId(),"semantic_version","1",
          "revision_id",pin.revisionId(),"publication_set_id",pin.publicationSetId(),"artifact_type",kind,"status","ACTIVE"));
      if("VALUE_MAP".equals(kind)){
        response.put("status","RETIRED");response.put("definition",definition);
        response.put("definitionSha256",hashes.ofDefinition(definition).substring(7));
        mapResponse.putAll(response);
      }
      when(registryReader.resolve(pin.semanticId(),pin.revisionId(),pin.publicationSetId()))
          .thenReturn("VALUE_MAP".equals(kind)?mapResponse:response);
    }
    assertThatThrownBy(()->reviews.prepare(actor,command,"prepare")).isInstanceOfSatisfying(
        it.comune.trieste.ouf.onboarding.domain.DomainFailure.class,e->assertThat(e.code()).isEqualTo("ONB_VALUE_MAP_UNRESOLVED"));
    assertThat(onboarding.version(source,version)).containsEntry("state","DRAFT");
    assertThat(db.sql("select count(*) from ouf_onboarding.approval_challenge where onboarding_version_id=:v")
        .param("v",version).query(Long.class).single()).isZero();
    mapResponse.put("status","ACTIVE");
    var review=reviews.prepare(actor,command,"retry");
    var json=new com.fasterxml.jackson.databind.ObjectMapper();
    var frozen=json.valueToTree(onboarding.version(source,version).get("configuration"));
    assertThat(frozen.path("semanticReferenceBindings").get(2).path("definition")).isEqualTo(json.valueToTree(definition));
    assertThat(hashes.of(onboarding.version(source,version).get("configuration"))).isEqualTo(review.get("configurationHash"));
    var card=json.valueToTree(reviews.humanCard(actor,(UUID)review.get("challengeId")));
    assertThat(card.path("reviewSnapshot").path("FIELD").path("code").path("valueMap")).isEqualTo(json.valueToTree(
        Map.of("valueMapRef",ref,"name","Colori pubblicati","targetVocabulary",vocabulary,"entryCount",1)));
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
  @Test void humanResumeCannotResetExhaustionOrUnknownAmbiguousFailures(){
    UUID version=draft();var p=principal("test-tenant","test-human");
    var prepared=reviews.prepare(p,new ManagedOnboardingReviewService.Prepare("review-test",version,1,"prepare:one",null),"c");
    UUID challenge=(UUID)prepared.get("challengeId");String hash=prepared.get("configurationHash").toString();
    reviews.humanAction(p,challenge,"confirm",new ManagedOnboardingReviewService.Decision(hash,2),"test-auth");
    var claim=reviews.claimActivation(p,challenge,new ManagedOnboardingReviewService.Decision(hash,3));
    reviews.activationFailure(claim,"ONB_READINESS_OUTCOME_UNCONFIRMED",false);
    assertThatThrownBy(()->reviews.resumeActivation(p,challenge,new ManagedOnboardingReviewService.Resume(hash,3,claim.generation(),"ONB_READINESS_OUTCOME_UNCONFIRMED"),"test-auth")).hasMessage("ONB_READINESS_RESUME_NOT_ALLOWED");
    reviews.activationFailure(claim,"ONB_READINESS_DEPENDENCY_403",false);
    assertThat(progress(reviews.read(p,"review-test",version))).containsEntry("readiness_last_code","ONB_READINESS_OUTCOME_UNCONFIRMED");
  }
  @Test void humanResumeOf403CannotResetAnExpiredBudget(){
    var e=exhausted();var p=e.principal();UUID challenge=e.challenge();String hash=e.hash();
    db.sql("update ouf_onboarding.managed_onboarding_review set readiness_started_at=clock_timestamp() where challenge_id=:c").param("c",challenge).update();
    var claim=reviews.claimActivation(p,challenge,new ManagedOnboardingReviewService.Decision(hash,3));
    reviews.activationFailure(claim,"ONB_READINESS_DEPENDENCY_403",false);
    var resume=new ManagedOnboardingReviewService.Resume(hash,3,claim.generation(),"ONB_READINESS_DEPENDENCY_403");
    when(policy.authorizeActive(any(),any(),anyString(),anyString(),any()).allowed()).thenReturn(false);
    assertThatThrownBy(()->reviews.resumeActivation(p,challenge,resume,"test-auth")).hasMessage("ONB_REVIEW_AUTHORIZATION_DENIED");
    when(policy.authorizeActive(any(),any(),anyString(),anyString(),any()).allowed()).thenReturn(true);
    db.sql("update ouf_onboarding.managed_onboarding_review set readiness_attempts=128 where challenge_id=:c").param("c",challenge).update();
    assertThatThrownBy(()->reviews.resumeActivation(p,challenge,resume,"test-auth")).hasMessage("ONB_READINESS_BUDGET_EXHAUSTED");
    db.sql("update ouf_onboarding.managed_onboarding_review set readiness_attempts=1,readiness_started_at=clock_timestamp()-interval '25 hours' where challenge_id=:c").param("c",challenge).update();
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
    var e=exhausted();var old=new ManagedOnboardingReviewService.ActivationClaim(e.challenge(),e.version(),"review-test",e.hash(),3,4,Map.of(),Map.of(),false);
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

