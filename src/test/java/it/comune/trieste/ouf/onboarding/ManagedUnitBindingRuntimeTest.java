package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.doReturn;
import it.comune.trieste.ouf.onboarding.application.*;
import it.comune.trieste.ouf.onboarding.domain.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest
class ManagedUnitBindingRuntimeTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry props) {
    props.add("spring.datasource.url",()->System.getenv("OUF_ONB_DB_URL"));
    props.add("spring.datasource.username",()->System.getenv("OUF_ONB_DB_USER"));
    props.add("spring.datasource.password",()->System.getenv("OUF_ONB_DB_PASSWORD"));
  }
  @Autowired ManagedFileService files;
  @Autowired OnboardingService onboarding;
  @Autowired CanonicalHash hashes;
  @Autowired JdbcClient db;
  @Autowired ApplicationContext context;
  @MockitoSpyBean ManagedFileProfiler profiler;
  static final String TENANT="fixture-tenant", SUBJECT="human:test", XLSX="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
  final OnboardingService.Actor actor=new OnboardingService.Actor(SUBJECT,"HUMAN_USER");

  @BeforeEach void resetFixtures() {
    db.sql("truncate ouf_onboarding.source,ouf_onboarding.managed_file_asset,ouf_onboarding.file_profile_job cascade").update();
  }

  private UUID register(byte[] bytes,String media) {
    return (UUID)files.registerOwned(null,"object://staging/"+UUID.randomUUID(),hashes.ofBytes(bytes),media,
        bytes.length,SUBJECT,"retention://fixture",TENANT).get("asset_id");
  }

  private Map<String,Object> create(ManagedFileService service,UUID asset,UUID profile,SourceUnitProfiles.Reference pin,String source,String key) {
    return service.onboardIdempotent(asset,profile,source,"Fixture","owner","urn:fixture:class",List.of("core@1"),
        List.of("id"),List.of(new ManagedFileService.FieldDecision("id","INCLUDE","OPEN","urn:fixture:id","IDENTITY",null,null,null)),
        null,actor,"fixture",key,TENANT,null,null,null,null,pin);
  }

  private SourceUnitProfiles.Reference pin(UUID profile,String ref) {
    var page=files.unitView(null,profile,TENANT,SUBJECT,"UNIT",ref,0,1,null);
    return new SourceUnitProfiles.Reference(profile,((Number)page.get("profileVersion")).intValue(),ref,(String)page.get("schemaHash"));
  }

  private String firstSource(UUID asset) {
    return db.sql("select source_id from ouf_onboarding.managed_file_asset where asset_id=:a")
        .param("a",asset).query(String.class).single();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
  void f1_07_csvCrossReplayAndPredecessorWrites(boolean referenceFirst) {
    byte[] bytes="id\n1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    UUID asset=UUID.fromString("00000000-0000-4000-8000-000000000011");
    UUID profile=UUID.fromString("00000000-0000-4000-8000-000000000012");
    db.sql("""
        insert into ouf_onboarding.managed_file_asset(asset_id,staging_ref,content_hash,media_type,size_bytes,uploaded_by,retention_ref,tenant_id,status)
        values(:a,'object://staging/f1.csv',:h,'text/csv',:size,:owner,'retention://fixture',:tenant,'PROFILED')
        """).param("a",asset).param("h",hashes.ofBytes(bytes)).param("size",bytes.length).param("owner",SUBJECT).param("tenant",TENANT).update();
    db.sql("""
        insert into ouf_onboarding.file_profile(profile_id,asset_id,version,format,metadata,inferred_schema,candidate_keys,sample_ref,redacted_sample)
        values(:p,:a,1,'CSV','{"delimiter":",","encoding":"UTF-8","headerRow":1,"rows":1,"columns":1}',
        '[{"name":"id","dataType":"LONG","nullable":false,"distinctValues":1}]','["id"]','object://samples/f1','[{"id":"1"}]')
        """).param("a",asset).param("p",profile).update();
    var pin=pin(profile,"table:0");
    var created=create(files,referenceFirst?null:asset,referenceFirst?null:profile,referenceFirst?pin:null,"fixture-csv","first");
    assertThat(create(files,referenceFirst?asset:null,referenceFirst?profile:null,referenceFirst?null:pin,"fixture-csv","first")).isEqualTo(created);
    var config=(Map<?,?>)onboarding.version("fixture-csv",(UUID)created.get("onboardingVersionId")).get("configuration");
    assertThat(((Map<?,?>)((Map<?,?>)config.get("extractionProfile")).get("selection")).get("unitProfileRef")).isNull();
    assertThat(hashes.of(config)).isEqualTo("sha256:98f9f32ccbd928d51b1de91cfb1e960e46095042284ca5b53b80901f50ed423c");
    assertThat(hashes.of(config.get("bundle"))).isEqualTo("sha256:1dda54268ef6cb86a1baab49b47afb6528837f72970127936b8024654114949a");
    assertThat(firstSource(asset)).isEqualTo("fixture-csv");
    assertThat(db.sql("""
        select v.onboarding_version_id from ouf_onboarding.onboarding_version v
        join ouf_onboarding.managed_file_asset a using(source_id)
        where v.source_id=:source and a.asset_id=:asset and v.state='DRAFT'
        """).param("source","fixture-csv").param("asset",asset).query(UUID.class).single()).isEqualTo(created.get("onboardingVersionId"));
    assertThat(db.sql("select count(*) from ouf_onboarding.managed_file_unit_binding where asset_id=:a").param("a",asset).query(Integer.class).single()).isOne();

    // After rollback the predecessor only writes the asset column.
    UUID later=register(bytes,"text/csv");
    db.sql("update ouf_onboarding.managed_file_asset set source_id=:s where asset_id=:a and source_id is null")
        .param("s","fixture-csv").param("a",later).update();
    assertThat(db.sql("select unit_ref from ouf_onboarding.managed_file_effective_binding where asset_id=:a and source_id=:s")
        .param("a",later).param("s","fixture-csv").query(String.class).single()).isEqualTo("table:0");
    var missing=UUID.randomUUID();
    assertCode(()->files.resolve(null,null,null,new SourceUnitProfiles.Reference(missing,1,"table:0",pin.schemaHash()),TENANT,SUBJECT),"ONB_FILE_PROFILE_NOT_FOUND");
    assertCode(()->files.resolve(null,null,null,pin,"other",SUBJECT),"ONB_FILE_PROFILE_NOT_FOUND");
    assertCode(()->files.resolve(later,null,null,pin,TENANT,SUBJECT),"ONB_UNIT_SELECTION_CHANGED");
  }

  private ManagedFileProfiler.Profile workbook(boolean rejected) {
    var sheets=new ArrayList<Map<String,Object>>();
    for(int index=0;index<2;index++){
      if(rejected)sheets.add(Map.of("sheet","Notes"+index,"sheetIndex",index,"status","REJECTED",
          "rejection",Map.of("code","XLSX_MERGED_CELLS","at","A1:F1")));
      else sheets.add(Map.of("sheet","Data"+index,"sheetIndex",index,"status","READY","headerRow",1,"rows",1,
          "columns",List.of(new ManagedFileProfiler.ColumnProfile("id","STRING",false,1)),
          "nativeTypes",Map.of("id","STRING"),"candidateKeys",List.of("id")));
    }
    return new ManagedFileProfiler.Profile("XLSX",Map.of("reader","ouf.xlsx-cell-text.v1","dateSystem","1900",
        "workbookSheets",2,"sheets",sheets),List.of(),List.of(),List.of(),Map.of());
  }
  @Test void f1_08_twoUnitsHaveDistinctCustodyOnlyWhenEnabled() {
    byte[] bytes={1,2,3};
    UUID asset=register(bytes,XLSX);
    doReturn(workbook(false)).when(profiler).profile(bytes,XLSX);
    UUID profile=(UUID)files.profile(asset,bytes,"object://samples/fixture").get("profile_id");
    var first=pin(profile,"sheet:0");var second=pin(profile,"sheet:1");
    var created=create(files,null,null,first,"fixture-one","first");
    assertThat(create(files,null,null,first,"fixture-one","first")).isEqualTo(created);
    assertCode(()->create(files,null,null,second,"fixture-two","other"),"ONB_UNIT_ASSET_ALREADY_BOUND");
    ManagedFileService enabled=context.getAutowireCapableBeanFactory().createBean(ManagedFileService.class);
    ReflectionTestUtils.setField(enabled,"multipleSourcesPerAsset",true);
    create(enabled,null,null,second,"fixture-two","other");
    assertCode(()->create(enabled,null,null,second,"fixture-three","again"),"ONB_UNIT_ALREADY_BOUND");
    enabled.requireManagedSourceOwner("fixture-two",TENANT,SUBJECT);
    assertThat(firstSource(asset)).isEqualTo("fixture-one");
    assertThat(db.sql("select source_id from ouf_onboarding.managed_file_unit_binding where asset_id=:a order by unit_ref")
        .param("a",asset).query(String.class).list()).containsExactly("fixture-one","fixture-two");
  }

  @Test void f1_09_completedJobRetainsAllRejectedSheets() {
    byte[] bytes={4,5,6};
    UUID asset=register(bytes,XLSX);
    doReturn(workbook(true)).when(profiler).profile(bytes,XLSX);
    var job=files.requestProfile(asset,"fixture");
    var lease=files.claimProfile("test",Duration.ofMinutes(2),Instant.now()).orElseThrow();
    var done=files.completeProfile((UUID)job.get("jobId"),"test",((Number)lease.get("generation")).longValue(),bytes,"object://samples/fixture");
    var summary=Map.of("unitCount",2,"readyUnits",0);
    assertThat(done).containsEntry("status","SUCCEEDED").containsEntry("unitSummary",summary);
    UUID id=(UUID)done.get("resultRef");
    assertThat(files.unitView(null,id,TENANT,SUBJECT,"UNITS",null,null,null,null)).containsEntry("unitSummary",summary);
    assertThat(files.profileWithUnitSummary(asset,id)).containsEntry("unitSummary",summary);
  }

  @Test void f1_10_legacySheetRequiresReprofiling() {
    byte[] bytes={7,8,9};
    UUID asset=register(bytes,XLSX);
    var old=new ManagedFileProfiler.Profile("XLSX",Map.of("sheet","Data","workbookSheets",1,"headerRow",1,"rows",1),
        List.of(new ManagedFileProfiler.ColumnProfile("id","STRING",false,1)),List.of("id"),List.of(),Map.of());
    doReturn(old).when(profiler).profile(bytes,XLSX);
    UUID profile=(UUID)files.profile(asset,bytes,"object://samples/legacy").get("profile_id");
    var reference=pin(profile,"sheet:0");
    assertCode(()->files.resolve(null,null,null,reference,TENANT,SUBJECT),"ONB_UNIT_PROFILE_OUTDATED");
    assertCode(()->files.resolve(UUID.randomUUID(),null,null,reference,TENANT,SUBJECT),"ONB_UNIT_SELECTION_CHANGED");
  }

  private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable work,String code) {
    assertThatExceptionOfType(DomainFailure.class).isThrownBy(work).satisfies(f->assertThat(f.code()).isEqualTo(code));
  }
}
