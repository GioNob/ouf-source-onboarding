package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import it.comune.trieste.ouf.onboarding.application.*;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import java.io.*;
import java.time.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class ManagedFileRuntimeTest {
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->required("OUF_ONB_DB_URL"));r.add("spring.datasource.username",()->required("OUF_ONB_DB_USER"));r.add("spring.datasource.password",()->required("OUF_ONB_DB_PASSWORD"));}
  @Autowired SemanticGapService gaps; @Autowired ManagedFileService files; @Autowired ManagedFileProfiler profiler; @Autowired OnboardingService onboarding; @Autowired CanonicalHash hashes; @Autowired JdbcClient db;
  OnboardingService.Actor human=new OnboardingService.Actor("human:test","HUMAN_USER");OnboardingService.Actor ingestion=new OnboardingService.Actor("service:ingestion","SERVICE",Set.of("ouf.ingestion.configuration.attest"));
  @BeforeEach void clean(){db.sql("truncate table ouf_onboarding.audit_event,ouf_onboarding.consumer_compatibility_attestation,ouf_onboarding.approval_decision,ouf_onboarding.approval_challenge,ouf_onboarding.published_configuration,ouf_onboarding.file_profile_job,ouf_onboarding.onboarding_version,ouf_onboarding.file_profile,ouf_onboarding.managed_file_asset,ouf_onboarding.source restart identity cascade").update();}
  @Test void humanCapabilityDoesNotRevealAnotherUsersStagedAsset(){
    byte[] csv="cinema,indirizzo\nA,Trieste\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    UUID asset=(UUID)files.register(null,"object://staging/owned.csv",hashes.ofBytes(csv),"text/csv",csv.length,"human:alice","retention://30d").get("asset_id");
    files.requireOwner(asset,"human:alice");
    assertThatThrownBy(()->files.requireOwner(asset,"human:bob"))
        .isInstanceOf(it.comune.trieste.ouf.onboarding.domain.DomainFailure.class)
        .hasMessageContaining("not owned");
  }
  @Test void managedFileDraftRetryReturnsOneVersionAndRejectsChangedArguments(){
    byte[] csv="cinema,indirizzo\nA,Trieste\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    UUID asset=(UUID)files.register(null,"object://staging/idempotent.csv",hashes.ofBytes(csv),"text/csv",csv.length,"human:test","retention://30d").get("asset_id");
    UUID profile=(UUID)files.profile(asset,csv,"object://samples/idempotent.json").get("profile_id");
    var first=files.onboardIdempotent(asset,profile,"cinema-test","Cinema","Comune","https://example.org/Cinema",List.of("core@1"),List.of(),List.of(),null,human,"corr-1","create-1");
    var retry=files.onboardIdempotent(asset,profile,"cinema-test","Cinema","Comune","https://example.org/Cinema",List.of("core@1"),List.of(),List.of(),null,human,"corr-2","create-1");
    assertThat(retry).isEqualTo(first).containsEntry("state","DRAFT");
    assertThat(first).doesNotContainKeys("configuration","stagingRef","contentHash");
    assertThat(db.sql("select count(*) from ouf_onboarding.onboarding_version where source_id='cinema-test'").query(Long.class).single()).isEqualTo(1);
    assertThatThrownBy(()->files.onboardIdempotent(asset,profile,"cinema-test","Changed","Comune","https://example.org/Cinema",List.of("core@1"),List.of(),List.of(),null,human,"corr-3","create-1"))
        .hasMessageContaining("different onboarding arguments");
    assertThatThrownBy(()->files.onboardIdempotent(asset,profile,"cinema-test","Cinema","Comune","https://example.org/Cinema",List.of("core@1"),List.of(),List.of(),null,new OnboardingService.Actor("human:other","HUMAN_USER"),"corr-4","create-1"))
        .hasMessageContaining("not owned");
  }
  @Test @SuppressWarnings("unchecked") void accessTableWithoutGeometryCreatesDraftWithCompositeKeysAndRelationshipEvidence(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)throws Exception{
    it.comune.trieste.ouf.pairwise.ManagedFormatsPublisherFixture.main(new String[]{directory.toString()});
    for(String filename:List.of("assets.mdb","assets.accdb")){
      byte[] bytes=java.nio.file.Files.readAllBytes(directory.resolve(filename));
      UUID asset=(UUID)files.register(null,"object://staging/"+filename,hashes.ofBytes(bytes),"application/x-msaccess",bytes.length,"human:test","retention://30d").get("asset_id");
      UUID profile=(UUID)files.profile(asset,bytes,"object://samples/"+filename).get("profile_id");
      var fields=List.of("ID","CODE","NAME","DISTRICT").stream().map(n->new ManagedFileService.FieldDecision(n,"INCLUDE","OPEN","https://example.org/"+n,"IDENTITY",null,null,null)).toList();
      var draft=files.onboard(asset,profile,filename,"Assets","Comune","https://example.org/Asset",List.of("core@1"),List.of("DISTRICT","CODE"),fields,"Assets",human,"r2f");
      var config=(Map<String,Object>)draft.get("configuration");
      assertThat((Map<String,Object>)config.get("sourceObjectIdentityPolicy")).containsEntry("sourceFields",List.of("DISTRICT","CODE"));
      assertThat(String.valueOf(config.get("sourceSchemaEvidence"))).contains("asset_children","PENDING_HUMAN_REVIEW","ASSET_CODE");
      UUID version=(UUID)draft.get("onboarding_version_id");
      assertThatThrownBy(()->gaps.proposeAccessSchema(filename,version,human)).hasMessageContaining("capability");
      var proposer=new OnboardingService.Actor("human:test","HUMAN_USER",Set.of("ouf.source-onboarding.semantic-gap.create"));
      var proposals=gaps.proposeAccessSchema(filename,version,proposer);
      assertThat(proposals).hasSize(2);
      assertThat(gaps.proposeAccessSchema(filename,version,proposer).stream().map(x->x.get("gap_id")).toList()).containsExactlyElementsOf(proposals.stream().map(x->x.get("gap_id")).toList());
      var relation=proposals.stream().filter(x->String.valueOf(x.get("source_evidence")).contains("RELATIONSHIP")).findFirst().orElseThrow();
      assertThat(relation).containsEntry("state","OPEN");
      assertThat(String.valueOf(relation.get("source_evidence"))).contains("DISTRICT", "ASSET_CODE", "PROPOSAL_ONLY", "profile://managed-files/");
      UUID gap=(UUID)relation.get("gap_id");
      gaps.requestSearch(filename,version,gap,"semantic-discovery://access/"+gap);
      var registry=new OnboardingService.Actor("service:semantic","SERVICE",Set.of("ouf.semantic.discovery.result.report"));
      var candidate=gaps.recordCandidates(filename,version,gap,List.of(Map.of("semanticRef","core@1","status","ADOPTED","evidence",Map.of("predicate","https://example.org/belongsTo","domain","Child","range","Asset","direction","REFERENCING_TO_REFERENCED"))),registry);
      UUID candidateId=(UUID)((Map<?,?>)((List<?>)candidate.get("candidates")).getFirst()).get("candidate_id");
      assertThat(gaps.select(filename,version,gap,candidateId)).containsEntry("state","SELECTED");
      assertThat(gaps.recordCandidates(filename,version,gap,List.of(Map.of("semanticRef","core@1","status","PUBLISHED")),registry)).containsEntry("state","RESOLVED");
      assertThat(onboarding.version(filename,version)).containsEntry("state","DRAFT");
      db.sql("update ouf_onboarding.onboarding_version set state='IN_REVIEW',configuration_hash='sha256:test',frozen_at=transaction_timestamp() where onboarding_version_id=:v").param("v",version).update();
      assertThatThrownBy(()->gaps.proposeAccessSchema(filename,version,proposer)).hasMessageContaining("DRAFT");
      assertThat(db.sql("select count(*) from ouf_onboarding.published_configuration").query(Long.class).single()).isZero();
    }
  }
  @Test @SuppressWarnings("unchecked") void geoPackageRegistrationRequiresLayerAndStableKeys()throws Exception{
    byte[] bytes=getClass().getResourceAsStream("/geopackage/cameras.gpkg").readAllBytes();
    UUID asset=(UUID)files.register(null,"object://staging/cameras.gpkg",hashes.ofBytes(bytes),"application/geopackage+sqlite3",bytes.length,"human:test","retention://30d").get("asset_id");
    UUID profile=(UUID)files.profile(asset,bytes,"object://samples/cameras.json").get("profile_id");
    assertThat(String.valueOf(files.preview(asset,profile))).contains("cameras","cabinets","EPSG:4326","LAYER_AND_STABLE_KEYS_REQUIRED");
    var fields=List.of("fid","code","cabinet","geom").stream().map(n->new ManagedFileService.FieldDecision(n,"INCLUDE","OPEN","https://example.org/"+n,"IDENTITY",null,null,null)).toList();
    assertThatThrownBy(()->files.onboard(asset,profile,"gpkg","Cameras","Comune","https://example.org/Camera",List.of("core@1"),List.of("code"),fields,human,"r2e")).hasMessageContaining("Choose one");
    assertThatThrownBy(()->files.onboard(asset,profile,"gpkg","Cameras","Comune","https://example.org/Camera",List.of("core@1"),List.of(),fields,"cameras",human,"r2e")).hasMessageContaining("stable keys");
    var draft=files.onboard(asset,profile,"gpkg","Cameras","Comune","https://example.org/Camera",List.of("core@1"),List.of("code"),fields,"cameras",human,"r2e");
    var config=(Map<String,Object>)draft.get("configuration");var extraction=(Map<String,Object>)config.get("extractionProfile");
    assertThat((Map<String,Object>)extraction.get("runtime")).containsEntry("layer","cameras").containsEntry("sourceCrs","EPSG:4326").containsEntry("recordModel","ONE_FEATURE_ONE_SOURCE_OBJECT");
    assertThat((Map<String,Object>)config.get("sourceObjectIdentityPolicy")).containsEntry("sourceFields",List.of("code"));
  }

  @Test @SuppressWarnings("unchecked") void csvProducesDeterministicSchemaAndCandidateKey(){byte[] csv="id;name;active;observed\n1;Alpha;true;2026-01-01\n2;Beta;false;2026-01-02\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);var asset=files.register(null,"object://staging/a.csv",hashes.ofBytes(csv),"text/csv",csv.length,"human:test","retention://30d");UUID assetId=(UUID)asset.get("asset_id");var profile=files.profile(assetId,csv,"object://samples/a.json");assertThat(profile.get("format")).isEqualTo("CSV");assertThat((List<?>)profile.get("inferred_schema")).hasSize(4);assertThat((List<String>)profile.get("candidate_keys")).contains("id","name","observed");Map<String,Object> preview=files.preview(assetId,(UUID)profile.get("profile_id"));List<Map<String,String>> sample=(List<Map<String,String>>)preview.get("redactedSample");assertThat(sample).hasSize(2);assertThat(sample.get(0)).containsEntry("id","1").containsEntry("name","[REDACTED]");assertThat(preview).doesNotContainKeys("stagingRef","contentHash").containsEntry("recordModel","ONE_ROW_ONE_SOURCE_OBJECT").containsEntry("proposalStatus","PENDING_HUMAN_REVIEW");assertThat(db.sql("select count(*) from information_schema.columns where table_schema='ouf_onboarding' and table_name='managed_file_asset' and data_type='bytea'").query(Long.class).single()).isZero();}
  @Test void xlsxIsProfiledWithoutExecutingFormulas() throws Exception {byte[] bytes;try(var workbook=new XSSFWorkbook();var out=new ByteArrayOutputStream()){var sheet=workbook.createSheet("objects");var header=sheet.createRow(0);header.createCell(0).setCellValue("id");header.createCell(1).setCellValue("value");var row=sheet.createRow(1);row.createCell(0).setCellValue("1");row.createCell(1).setCellValue(42);workbook.write(out);bytes=out.toByteArray();}var p=profiler.profile(bytes,"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");assertThat(p.format()).isEqualTo("XLSX");assertThat(p.columns()).extracting(ManagedFileProfiler.ColumnProfile::name).containsExactly("id","value");}
  @Test void xlsxFormulaIsRejected() throws Exception {byte[] bytes;try(var workbook=new XSSFWorkbook();var out=new ByteArrayOutputStream()){var sheet=workbook.createSheet("objects");sheet.createRow(0).createCell(0).setCellValue("value");sheet.createRow(1).createCell(0).setCellFormula("1+1");workbook.write(out);bytes=out.toByteArray();}byte[] payload=bytes;assertThatThrownBy(()->profiler.profile(payload,"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).hasMessageContaining("formulas are not allowed");}
  @Test void hashMismatchFailsBeforePersistence(){byte[] registered="id,name\n1,A\n".getBytes();byte[] forged="id,name\n2,B\n".getBytes();var asset=files.register(null,"object://staging/a.csv",hashes.ofBytes(registered),"text/csv",forged.length,"human:test","retention://30d");assertThatThrownBy(()->files.profile((UUID)asset.get("asset_id"),forged,"object://sample")).hasMessageContaining("hash");assertThat(db.sql("select count(*) from ouf_onboarding.file_profile").query(Long.class).single()).isZero();}
  @Test void profileIsImmutable(){byte[] csv="id,name\n1,A\n".getBytes();var asset=files.register(null,"object://staging/a.csv",hashes.ofBytes(csv),"text/csv",csv.length,"human:test","retention://30d");var profile=files.profile((UUID)asset.get("asset_id"),csv,"object://sample");assertThatThrownBy(()->db.sql("delete from ouf_onboarding.file_profile where profile_id=:p").param("p",profile.get("profile_id")).update()).hasStackTraceContaining("file_profile is immutable");}
  @Test void profilingRequestIsAsynchronousAndIdempotent(){byte[] csv="id,name\n1,A\n".getBytes();UUID asset=(UUID)files.register(null,"object://staging/job.csv",hashes.ofBytes(csv),"text/csv",csv.length,"agent:test","retention://30d").get("asset_id");var first=files.requestProfile(asset,"mcp-call-1");var replay=files.requestProfile(asset,"mcp-call-1");assertThat(replay.get("job_id")).isEqualTo(first.get("job_id"));var claimed=files.claimProfile("onboarding-profiler",Duration.ofMinutes(1),Instant.now()).orElseThrow();assertThat(claimed.get("staging_ref")).isEqualTo("object://staging/job.csv");var completed=files.completeProfile((UUID)claimed.get("job_id"),"onboarding-profiler",csv,"object://samples/job.json");assertThat(completed.get("state")).isEqualTo("SUCCEEDED");assertThat(completed.get("profile_id")).isNotNull();}
  @Test @SuppressWarnings("unchecked") void activeManagedBundleTriggersOneTimeIngestionOutsideOnboarding(){byte[] csv="id,name\n1,Alpha\n".getBytes();UUID asset=(UUID)files.register(null,"object://staging/managed.csv",hashes.ofBytes(csv),"text/csv",csv.length,"agent:test","retention://30d").get("asset_id");UUID profile=(UUID)files.profile(asset,csv,"object://samples/managed.json").get("profile_id");var draft=files.onboard(asset,profile,"managed-csv","Managed CSV","Comune","https://example.org/Object",List.of("core@1"),List.of("id"),human,"c");UUID version=(UUID)draft.get("onboarding_version_id");assertThat(draft.get("runtimeTrigger")).isEqualTo("ACTIVE_BUNDLE");onboarding.submit("managed-csv",version,0,human,"c");var challenge=onboarding.createChallenge("managed-csv",version,human,"c");onboarding.confirm("managed-csv",version,(UUID)challenge.get("challenge_id"),human,"c","acr:mfa");assertThatThrownBy(()->onboarding.activate("managed-csv",version,human,"c")).hasMessageContaining("Ingestion Runtime");onboarding.attestIngestionCompatibility("managed-csv",version,true,"managed file profile accepted",ingestion,"c");Map<String,Object> publication=onboarding.activate("managed-csv",version,human,"c");Map<String,Object> bundle=(Map<String,Object>)publication.get("bundle"),extraction=(Map<String,Object>)bundle.get("extractionProfile"),runtime=(Map<String,Object>)extraction.get("runtime"),identity=(Map<String,Object>)bundle.get("sourceObjectIdentityPolicy");assertThat(bundle).containsKeys("bundleId","bundleVersion","environment","createdFromOnboardingVersion","effectiveFrom","checksum","status","objectTypes").containsEntry("status","ACTIVE");assertThat(runtime).containsEntry("mode","MANAGED").containsEntry("recordModel","ONE_ROW_ONE_SOURCE_OBJECT").containsEntry("assetId",asset.toString()).containsEntry("fileProfileId",profile.toString()).containsEntry("stagingRef","object://staging/managed.csv");assertThat(identity).containsEntry("strategy","NATIVE_KEY").containsEntry("sourceFields",List.of("id"));assertThat(db.sql("select count(*) from information_schema.tables where table_schema='ouf_onboarding' and table_name in ('managed_file_ingestion','pull_schedule','pull_dispatch')").query(Long.class).single()).isZero();}
  @Test void malformedUtf8IsRejected(){assertThatThrownBy(()->profiler.profile(new byte[]{'i','d','\n',(byte)0xC3,(byte)0x28},"text/csv")).hasMessageContaining("valid UTF-8");}
  @Test @SuppressWarnings("unchecked") void managedFileCanSelectAndClassifyEveryColumn(){byte[] csv="id,name\n1,A\n".getBytes();UUID asset=(UUID)files.register(null,"object://staging/classified.csv",hashes.ofBytes(csv),"text/csv",csv.length,"agent:test","retention://30d").get("asset_id");UUID profile=(UUID)files.profile(asset,csv,"object://samples/classified.json").get("profile_id");List<ManagedFileService.FieldDecision> decisions=List.of(new ManagedFileService.FieldDecision("id","INCLUDE","OPEN","https://example.org/id","IDENTITY","ids","1","semantic://maps/ids@1"),new ManagedFileService.FieldDecision("name","EXCLUDE","PERSONAL",null,null,null,null,null));var draft=files.onboard(asset,profile,"classified-csv","Classified CSV","Comune","https://example.org/Object",List.of("core@1"),List.of("id"),decisions,human,"corr");Map<String,Object> config=(Map<String,Object>)draft.get("configuration"),extraction=(Map<String,Object>)config.get("extractionProfile"),semantic=(Map<String,Object>)config.get("semanticMapping");assertThat(String.valueOf(extraction.get("projection"))).contains("id").doesNotContain("name");assertThat(String.valueOf(config.get("dataAccessPolicies"))).contains("OPEN").doesNotContain("PERSONAL");assertThat(String.valueOf(semantic.get("vocabularyMappings"))).contains("ids","semantic://maps/ids@1");}
  @Test void managedFileQuarantineIsHumanGovernedAndAppendOnly(){byte[] csv="id\n1\n".getBytes();UUID asset=(UUID)files.register(null,"object://staging/quarantine.csv",hashes.ofBytes(csv),"text/csv",csv.length,"agent:test","retention://30d").get("asset_id");UUID q=UUID.randomUUID();db.sql("insert into ouf_onboarding.managed_file_quarantine(quarantine_id,asset_id,reason_code,evidence_ref,safe_detail,correlation_id) values(:q,:a,'ONB_FILE_PROFILE_FAILED',:e,'password=[REDACTED]','corr-q')").param("q",q).param("a",asset).param("e","quarantine://managed-files/"+asset+"/"+q).update();db.sql("update ouf_onboarding.managed_file_asset set status='QUARANTINED' where asset_id=:a").param("a",asset).update();assertThatThrownBy(()->files.resolveQuarantine(q,"RELEASED",human,"authz:none","corr")).hasMessageContaining("Authorization did not grant");var reviewer=new OnboardingService.Actor("human:reviewer","HUMAN_USER",Set.of("ouf.onboarding.quarantine.release"));assertThat(files.resolveQuarantine(q,"RELEASED",reviewer,"authz:q1","corr-q")).containsEntry("state","RELEASED");assertThat(files.asset(asset)).containsEntry("status","STAGED");assertThatThrownBy(()->db.sql("delete from ouf_onboarding.managed_file_quarantine where quarantine_id=:q").param("q",q).update()).hasStackTraceContaining("append-only");assertThat(db.sql("select count(*) from ouf_onboarding.audit_event where event_type='MANAGED_FILE_QUARANTINE_RELEASED'").query(Long.class).single()).isOne();}
  private static String required(String n){String v=System.getenv(n);if(v==null)throw new IllegalStateException(n+" required");return v;}
}
