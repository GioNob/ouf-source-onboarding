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
  @Autowired ManagedFileService files;
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
    assertThat(revisions.readCommand(p,"s",parent,"revision-one")).isEqualTo(first);
    assertThat(revisions.readCommand(p,"s",parent,"unknown-key")).containsEntry("commandState","ABSENT").containsEntry("newCommandAuthorized",false);
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

  @Test void finiteReadinessBudgetCannotBroadenNonManagedSourceRevisionAuthority(){
    UUID parent=active();var p=principal("test-tenant","test-human");
    var first=revisions.prepare(p,command(parent,"finite-revision",rule()),"c");
    UUID next=(UUID)first.get("onboardingVersionId"),challenge=(UUID)first.get("challengeId");
    String hash=first.get("configurationHash").toString();
    reviews.humanAction(p,challenge,"confirm",new ManagedOnboardingReviewService.Decision(hash,2),"test-auth");
    db.sql("update ouf_onboarding.managed_onboarding_review set readiness_started_at=clock_timestamp()-interval '25 hours',readiness_attempts=3,readiness_generation=4,readiness_phase='PAUSED',readiness_last_code='ONB_READINESS_DEPENDENCY_503' where challenge_id=:c").param("c",challenge).update();
    var journal=db.sql("select to_jsonb(r)::text from ouf_onboarding.source_property_revision r").query(String.class).single();
    var parentBefore=onboarding.version("s",parent);
    assertThatThrownBy(()->reviews.grantReadinessBudget(p,challenge,new ManagedOnboardingReviewService.BudgetDecision(hash,3,4,"finite-revision-budget","Exact dependency recovery verified",3,900)))
        .hasMessage("ONB_REVIEW_MANAGED_SOURCE_REQUIRED");
    assertThat(db.sql("select to_jsonb(r)::text from ouf_onboarding.source_property_revision r").query(String.class).single()).isEqualTo(journal);
    assertThat(onboarding.version("s",parent)).isEqualTo(parentBefore);
    assertThat(revisions.read(p,"s",next)).containsEntry("parentVersionId",parent).containsEntry("publicationEffects",SourceRevisionService.effects());
    assertThat(db.sql("select count(*) from ouf_onboarding.onboarding_version").query(Long.class).single()).isEqualTo(2);
    assertThat(db.sql("select count(*) from ouf_onboarding.approval_decision").query(Long.class).single()).isOne();
    assertThat(db.sql("select count(*) from ouf_onboarding.managed_readiness_budget_session").query(Long.class).single()).isZero();
    assertThat(db.sql("select count(*) from ouf_onboarding.published_configuration").query(Long.class).single()).isZero();
    db.sql("update ouf_onboarding.onboarding_version set state='SUPERSEDED',lock_version=lock_version+1 where onboarding_version_id=:v").param("v",parent).update();
    assertThatThrownBy(()->onboarding.activate("s",next,human,"c")).hasMessage("ONB_REVISION_PARENT_DRIFT");
    assertThat(db.sql("select count(*) from ouf_onboarding.published_configuration").query(Long.class).single()).isZero();
  }

  @Test void replacementReferenceAloneChecksOwnerAndDriftBeforeFreezingTheRevision() {
    var p=principal(UUID.randomUUID().toString(),human.subject());
    var parent=activeCsv(p);
    var next=csv("id;amount\nr1;10\nr2;20\n",p.tenantId(),null,true);
    var legacy=replace(parent,next);
    var page=files.unitView(next.assetId(),next.profileId(),"table:0",0,1);
    var pin=new SourceUnitProfiles.Reference(next.profileId(),1,"table:0",(String)page.get("schemaHash"));
    java.util.function.Function<SourceUnitProfiles.Reference,SourceRevisionService.Prepare> command=ref->
        new SourceRevisionService.Prepare("s",parent,legacy.expectedVersion(),legacy.configurationHash(),
            legacy.commandKey(),legacy.publicationEffect(),null,null,null,ref);
    assertThatThrownBy(()->revisions.prepare(p,command.apply(new SourceUnitProfiles.Reference(
        next.profileId(),1,"table:0","sha256:"+"0".repeat(64))),"drift")).hasMessage("ONB_UNIT_SCHEMA_CHANGED");
    var receipt=revisions.prepare(p,command.apply(pin),"pin");
    var extraction=section(configuration((UUID)receipt.get("onboardingVersionId")),"extractionProfile");
    assertThat(section(extraction,"selection").get("unitProfileRef")).isEqualTo(Map.of("profileId",next.profileId().toString(),
        "profileVersion",1,"unitRef","table:0","schemaHash",pin.schemaHash()));
    assertThat(revisions.prepare(p,command.apply(pin),"replay")).isEqualTo(receipt);
  }

  @Test void csvReplacementPublishesOnlyAfterConfirmationAndAttestation(){
    var p=principal(UUID.randomUUID().toString(),human.subject());
    var parent=activeCsv(p);
    var original=onboarding.version("s",parent);
    var ref=csv("id;amount\nr1;10\nr2;20\n",p.tenantId(),null,true);
    var command=replace(parent,ref);
    var receipt=revisions.prepare(p,command,"prepare");
    var version=(UUID)receipt.get("onboardingVersionId");
    var challenge=(UUID)receipt.get("challengeId");
    var hash=(String)receipt.get("configurationHash");
    assertThat(receipt).containsEntry("sourceId","s").containsEntry("state","IN_REVIEW")
        .containsEntry("parentVersionId",parent).containsEntry("diagnostics",List.of());
    assertThat(onboarding.version("s",parent)).isEqualTo(original);
    assertThat(revisions.prepare(p,command,"repeat")).isEqualTo(receipt);
    assertThat(revisions.readCommand(p,"s",parent,command.commandKey())).isEqualTo(receipt);
    assertThat(reviews.humanCard(p,challenge)).containsEntry("assetRef",Map.of("assetId",ref.assetId(),"profileId",ref.profileId()));
    assertThat(files.asset(ref.assetId())).containsEntry("source_id","s");
    assertThatThrownBy(()->revisions.prepare(p,replace(parent,csv("id,amount\nr3,30\n",p.tenantId(),null,true)),"changed"))
        .hasMessage("ONB_REVISION_COMMAND_CONFLICT");
    var config=configuration(version);
    for(String key:List.of("sourceObjectIdentityPolicy","semanticMapping","dataAccessPolicies","syncProfile"))
      assertThat(config.get(key)).isEqualTo(configuration(parent).get(key));
    var extraction=section(config,"extractionProfile");
    var runtime=section(extraction,"runtime");
    var asset=files.asset(ref.assetId());
    assertThat(section(extraction,"selection")).containsEntry("assetId",ref.assetId().toString())
        .containsEntry("fileProfileId",ref.profileId().toString());
    assertThat(runtime).containsEntry("csvDelimiter",";").containsEntry("stagingRef",asset.get("staging_ref"))
        .containsEntry("contentHash",asset.get("content_hash"));
    String schema="profile://managed-files/"+ref.assetId()+"/profiles/"+ref.profileId();
    assertThat(section(runtime,"execution")).containsEntry("sourceSchemaRef",schema).containsEntry("sourceSchemaVersion","2");
    assertThat(((Number)section(runtime,"execution").get("expectedSize")).longValue()).isEqualTo(asset.get("size_bytes"));
    assertThat(section(config,"bundle")).containsEntry("schemaRef",schema);
    assertThatThrownBy(()->onboarding.activate("s",version,human,"early")).hasMessageContaining("APPROVED");
    reviews.humanAction(p,challenge,"confirm",new ManagedOnboardingReviewService.Decision(hash,2),"test-auth");
    assertThatThrownBy(()->onboarding.activate("s",version,human,"early")).hasMessageContaining("Ingestion Runtime");
    onboarding.attestIngestionCompatibility("s",version,true,"fixture",
        new OnboardingService.Actor("fixture-runtime","SERVICE",Set.of("ouf.ingestion.configuration.attest")),"attest");
    db.sql("update ouf_onboarding.managed_file_asset set status='QUARANTINED' where asset_id=:a").param("a",ref.assetId()).update();
    assertThatThrownBy(()->onboarding.activate("s",version,human,"stale")).hasMessageContaining("no longer eligible");
    assertThat(db.sql("select count(*) from ouf_onboarding.published_configuration").query(Long.class).single()).isZero();
    db.sql("update ouf_onboarding.managed_file_asset set status='PROFILED' where asset_id=:a").param("a",ref.assetId()).update();
    reviews.humanAction(p,challenge,"activate",new ManagedOnboardingReviewService.Decision(hash,3),"test-auth");
    assertThat(onboarding.version("s",version)).containsEntry("state","ACTIVE");
    assertThat(onboarding.version("s",parent)).containsEntry("state","SUPERSEDED");
    assertThat(revisions.prepare(p,command,"after-publication")).containsEntry("onboardingVersionId",version);
    assertThat(db.sql("select count(*) from ouf_onboarding.published_configuration").query(Long.class).single()).isOne();
  }

  @Test void unprovenCustodyStaleParentAndIncompatibleProfileRollBackTheWholeCommand(){
    var p=principal(UUID.randomUUID().toString(),human.subject());
    var parent=activeCsv(p);
    onboarding.createSource("other","Other","INTERNAL_MANAGED","MANAGED","owner",Map.of(),human,"create");
    String good="id,amount\nr2,20\n";
    var foreignSource=csv(good,p.tenantId(),"other",true);
    assertThatThrownBy(()->revisions.prepare(p,replace(parent,foreignSource),"c")).hasMessage("ONB_REVISION_ASSET_SOURCE_CONFLICT");
    var foreignTenant=csv(good,UUID.randomUUID().toString(),null,true);
    assertThatThrownBy(()->revisions.prepare(p,replace(parent,foreignTenant),"c")).hasMessageContaining("owner scope");
    var staged=csv(good,p.tenantId(),null,false);
    assertThatThrownBy(()->revisions.prepare(p,replace(parent,staged),"c")).hasMessage("ONB_REVISION_ASSET_NOT_PROFILED");
    var incompatible=csv("id,amount,other\nr1,text,one\n",p.tenantId(),null,true);
    assertThatThrownBy(()->revisions.prepare(p,replace(parent,incompatible),"c"))
        .isInstanceOfSatisfying(ManagedCsvRevision.Incompatible.class,e->assertThat(e.diagnostics())
            .extracting(ManagedCsvRevision.Finding::code).containsExactly("TYPE_CHANGED","NEW_COLUMN_DECISION_REQUIRED"));
    var missing=csv("id\nr1\n",p.tenantId(),null,true);
    assertThatThrownBy(()->revisions.prepare(p,replace(parent,missing),"c"))
        .isInstanceOfSatisfying(ManagedCsvRevision.Incompatible.class,e->assertThat(e.diagnostics())
            .containsExactly(new ManagedCsvRevision.Finding("MISSING_MAPPED_COLUMN","amount","LONG",null)));
    var unpaired=new SourceRevisionService.AssetRef(missing.assetId(),incompatible.profileId());
    assertThatThrownBy(()->revisions.prepare(p,replace(parent,unpaired),"c")).hasMessageContaining("profile not found");
    var base=replace(parent,csv(good,p.tenantId(),null,true));
    var stale=new SourceRevisionService.Prepare("s",parent,9,base.configurationHash(),base.commandKey(),base.publicationEffect(),null,base.assetRef());
    assertThatThrownBy(()->revisions.prepare(p,stale,"c")).hasMessage("ONB_REVISION_PARENT_DRIFT");
    when(policy.authorizeActive(any(),any(),anyString(),anyString(),any()).allowed()).thenReturn(false);
    assertThatThrownBy(()->revisions.prepare(p,base,"c")).hasMessage("ONB_REVISION_AUTHORIZATION_DENIED");
    assertThat(db.sql("select count(*) from ouf_onboarding.onboarding_version").query(Long.class).single()).isOne();
    assertThat(db.sql("select count(*) from ouf_onboarding.approval_challenge").query(Long.class).single()).isZero();
    assertThat(db.sql("select count(*) from ouf_onboarding.source_property_revision").query(Long.class).single()).isZero();
    assertThat(files.asset(incompatible.assetId()).get("source_id")).isNull();
  }

  @Test void excludedColumnIsRecordedOnlyOnItsRevision(){
    var p=principal(UUID.randomUUID().toString(),human.subject());
    var parent=activeCsv(p);
    var ref=csv("id,amount,notes\nr1,10,private\n",p.tenantId(),null,true);
    var excluded=new ManagedCsvRevision.NewColumn("notes","EXCLUDE",null);
    var first=revisions.prepare(p,replace(parent,ref,List.of(excluded)),"prepare");
    var evidence=List.of(Map.of("column","notes","decision","EXCLUDE"));
    assertThat(first).containsEntry("newColumns",evidence);
    assertThat(reviews.humanCard(p,(UUID)first.get("challengeId"))).containsEntry("newColumns",evidence);
    var config=configuration((UUID)first.get("onboardingVersionId"));
    var before=configuration(parent);
    for(String key:List.of("semanticMapping","dataAccessPolicies","sourceObjectIdentityPolicy"))
      assertThat(config.get(key)).isEqualTo(before.get(key));
    assertThat(section(config,"extractionProfile").get("projection")).isEqualTo(section(before,"extractionProfile").get("projection"));
    assertThat(section(section(config,"extractionProfile"),"runtime").get("udp"))
        .isEqualTo(section(section(before,"extractionProfile"),"runtime").get("udp"));
  }

  @Test void mixedDecisionsFreezeCanonicalPropertiesAndReconcileTheSameReview(){
    var p=principal(UUID.randomUUID().toString(),human.subject());
    var parent=activeCsv(p);
    var ref=csv("id,amount,price,notes\nr1,10,15,private\n",p.tenantId(),null,true);
    var excluded=new ManagedCsvRevision.NewColumn("notes","EXCLUDE",null);
    var mapping=new ManagedCsvRevision.Mapping("urn:price","DECIMAL_DOT_V1",rule(),"PERSONAL",List.of("s"),null);
    var mapped=new ManagedCsvRevision.NewColumn("price","MAP",mapping);
    var command=replace(parent,ref,List.of(excluded,mapped));
    // A string-only normalizer on a decimal must roll back just as in a property revision.
    var invalidRule=new LinkedHashMap<>(rule());
    invalidRule.put("normalizerRef","NFC_TRIM");
    var bad=new ManagedCsvRevision.NewColumn("price","MAP",new ManagedCsvRevision.Mapping("urn:price",
        mapping.transform(),invalidRule,null,null,null));
    assertThatThrownBy(()->revisions.prepare(p,replace(parent,ref,List.of(excluded,bad)),"invalid"))
        .hasMessageContaining("blocking validation");
    assertThat(files.asset(ref.assetId()).get("source_id")).isNull();
    var first=revisions.prepare(p,command,"prepare");
    var version=(UUID)first.get("onboardingVersionId");
    var card=reviews.humanCard(p,(UUID)first.get("challengeId"));
    assertThat(first).containsEntry("state","IN_REVIEW");
    assertThat(card.get("newColumns")).isEqualTo(List.of(Map.of("column","notes","decision","EXCLUDE"),
        Map.of("column","price","decision","MAP","mapping",Map.of("propertyIri","urn:price","transform","DECIMAL_DOT_V1",
            "accessLabel","PERSONAL","authorityOrder",List.of("s"),"reconciliation",rule()))));
    assertThat(revisions.prepare(p,command,"repeat")).isEqualTo(first);
    assertThat(revisions.readCommand(p,"s",parent,command.commandKey())).isEqualTo(first);
    assertThat(revisions.read(p,"s",version)).isEqualTo(first);
    var changed=List.of(excluded,new ManagedCsvRevision.NewColumn("price","EXCLUDE",null));
    assertThatThrownBy(()->revisions.prepare(p,replace(parent,ref,changed),"changed")).hasMessage("ONB_REVISION_COMMAND_CONFLICT");
    assertThat(db.sql("select count(*) from ouf_onboarding.approval_challenge").query(Long.class).single()).isOne();
    var config=configuration(version);
    assertThat(config.get("sourceObjectIdentityPolicy")).isEqualTo(configuration(parent).get("sourceObjectIdentityPolicy"));
    var extraction=section(config,"extractionProfile");
    assertThat(section(extraction,"projection")).containsEntry("FILE",List.of("id","amount","price"));
    assertThat(((List<?>)section(config,"semanticMapping").get("propertyMappings")).getLast())
        .isEqualTo(Map.of("sourceField","price","targetPropertyIri","urn:price","transform","DECIMAL_DOT_V1"));
    assertThat(((List<?>)config.get("dataAccessPolicies")).getLast()).isEqualTo(Map.of("target","urn:price","scope","PROPERTY","label","PERSONAL"));
    var udp=section(section(extraction,"runtime"),"udp");
    var properties=(List<?>)section(udp,"materialization").get("properties");
    assertThat(properties.getLast()).isEqualTo(Map.of("sourceField","urn:price","propertyIri","urn:price",
        "datatype","http://www.w3.org/2001/XMLSchema#decimal","accessLabel","PERSONAL","authorityOrder",List.of("s"),"reconciliation",rule()));
  }

  private SourceRevisionService.AssetRef csv(String content,String tenant,String source,boolean profile){
    byte[] bytes=content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    UUID asset=(UUID)files.registerOwned(source,"object://fixture/"+UUID.randomUUID(),hashes.ofBytes(bytes),"text/csv",
        bytes.length,human.subject(),"retention://fixture",tenant).get("asset_id");
    UUID id=profile?(UUID)files.profile(asset,bytes,"sample://fixture").get("profile_id"):UUID.randomUUID();
    return new SourceRevisionService.AssetRef(asset,id);
  }
  @SuppressWarnings("unchecked") private static Map<String,Object> section(Map<String,Object> map,String key){
    return (Map<String,Object>)map.get(key);
  }
  private UUID activeCsv(PrincipalContext p){
    var ref=csv("id,amount\nr1,10\n",p.tenantId(),null,true);
    var fields=List.of("id","amount").stream().map(n->new ManagedFileService.FieldDecision(n,"INCLUDE","RESTRICTED","urn:"+n,"IDENTITY",null,null,null)).toList();
    var draft=files.onboard(ref.assetId(),ref.profileId(),"s","Fixture","owner","urn:Object",List.of("core@1"),
        List.of("id"),fields,null,human,"create",p.tenantId());
    UUID version=(UUID)draft.get("onboarding_version_id");
    var config=configuration(version);
    var runtime=section(section(config,"extractionProfile"),"runtime");
    var execution=new LinkedHashMap<String,Object>(Map.of("acquisitionMode","INTERNAL_MANAGED_CSV","adapterId","managed-tabular-v1",
        "adapterRuntimeVersion","1","sourceSchemaRef",section(config,"bundle").get("schemaRef"),"sourceSchemaId","s",
        "sourceSchemaVersion","1","semanticPublicationSetRef","fixture-set","adapterProfileRef","fixture","observationPolicy","ACQUISITION_TIME"));
    execution.putAll(Map.of("maxRows",100,"maxColumns",4,"expectedSize",files.asset(ref.assetId()).get("size_bytes")));
    runtime.put("execution",execution);
    runtime.put("udp",Map.of("resolution",Map.of("strategyId","EXACT_SOURCE_KEY","strategyVersion","1",
        "policyRef","fixture","canonicalType","urn:Object","canonicalKeyProperty","urn:id","matchProperty","urn:id"),
        "materialization",Map.of("policyRef","fixture","properties",fields.stream().map(f->Map.of("propertyIri",f.targetPropertyIri(),
            "sourceField",f.targetPropertyIri(),"datatype","http://www.w3.org/2001/XMLSchema#string",
            "accessLabel",f.dataAccessLabel(),"authorityOrder",List.of("s"))).toList())));
    config.put("semanticReferenceBindings",List.of(Map.of("semanticId","core","semanticVersion","1","revisionId","fixture","publicationSetId","fixture-set")));
    onboarding.patchVersion("s",version,0,config,human,"fixture");
    db.sql("update ouf_onboarding.onboarding_version set state='ACTIVE',configuration_hash=:h,frozen_at=transaction_timestamp() where onboarding_version_id=:v")
        .param("h",hashes.of(configuration(version))).param("v",version).update();
    return version;
  }
  private SourceRevisionService.Prepare replace(UUID parent,SourceRevisionService.AssetRef ref){
    return replace(parent,ref,null);
  }
  private SourceRevisionService.Prepare replace(UUID parent,SourceRevisionService.AssetRef ref,List<ManagedCsvRevision.NewColumn> decisions){
    var row=onboarding.version("s",parent);
    return new SourceRevisionService.Prepare("s",parent,((Number)row.get("lock_version")).longValue(),
        (String)row.get("configuration_hash"),"csv-revision",SourceRevisionService.EFFECT,null,ref,decisions);
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

