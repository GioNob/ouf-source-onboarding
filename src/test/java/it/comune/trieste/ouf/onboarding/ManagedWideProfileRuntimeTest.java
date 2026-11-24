package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.authorization.PrincipalContext;
import it.comune.trieste.ouf.onboarding.api.PermissionDelegationVerifier;
import it.comune.trieste.ouf.onboarding.application.*;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties="ouf.onboarding.preview.max-response-bytes=786432")
@AutoConfigureMockMvc(addFilters=false)
class ManagedWideProfileRuntimeTest {
  private static final String PREVIEW="/api/internal/v1/onboarding/managed-file-mcp/preview";
  private static final String PREVIEW_CAP="ouf.managed-source.preview";
  private static final int PREVIEW_BUDGET=786432;
  @Autowired MockMvc http;
  @Autowired ObjectMapper json;
  @MockitoBean PermissionDelegationVerifier previewReceipts;

  @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url",()->required("OUF_ONB_DB_URL"));r.add("spring.datasource.username",()->required("OUF_ONB_DB_USER"));r.add("spring.datasource.password",()->required("OUF_ONB_DB_PASSWORD"));
  }
  @Test void d084_30_profileValuesSurvivePersistenceAndRequireTenantOwnership()throws Exception{
    var json=new com.fasterxml.jackson.databind.ObjectMapper();
    String tenant="tenant-a",subject="human:profile-owner";
    byte[] bytes="code;nome;empty\n B;first-private; \nA ;second-private;\nB;last-private;\n"
        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    UUID asset=(UUID)files.registerOwned(null,"object://staging/d084.csv",hashes.ofBytes(bytes),"text/csv",
        bytes.length,subject,"retention://30d",tenant).get("asset_id");
    UUID job=(UUID)files.requestProfile(asset,"d084-profile").get("jobId");
    var claim=files.claimProfile("d084-worker",java.time.Duration.ofSeconds(90),java.time.Instant.now()).orElseThrow();
    var completed=files.completeProfile(job,"d084-worker",((Number)claim.get("generation")).longValue(),bytes,"profile://samples/d084");
    UUID profile=(UUID)completed.get("resultRef");
    assertThat(completed).containsEntry("status","SUCCEEDED");

    var principal=new it.comune.trieste.ouf.authorization.PrincipalContext(subject,tenant,
        it.comune.trieste.ouf.authorization.PrincipalContext.ActorType.HUMAN,"mcp","1","issuer","aud",
        Set.of("ouf.managed-source.preview"),
        new it.comune.trieste.ouf.authorization.PrincipalContext.IdentityClaims(Set.of(),"1",Set.of(),null));
    var receipts=org.mockito.Mockito.mock(it.comune.trieste.ouf.onboarding.api.PermissionDelegationVerifier.class);
    String path="/api/internal/v1/onboarding/managed-file-mcp/preview";
    var request=new org.springframework.mock.web.MockHttpServletRequest("POST",path);
    byte[] selector=json.writeValueAsBytes(Map.of("Arguments",Map.of("assetId",asset,"profileId",profile)));
    org.mockito.Mockito.when(receipts.verifyManagedFile(null,path,"ouf.managed-source.preview",selector))
        .thenReturn(new it.comune.trieste.ouf.onboarding.api.PermissionDelegationVerifier.Delegated(principal,"preview"));
    var api=new it.comune.trieste.ouf.onboarding.api.ManagedFileMcpApi(receipts,files,json,null);
    var result=json.valueToTree(api.preview(selector,request));
    assertThat(result.path("columns").get(0).path("distinctValues")).isEqualTo(json.valueToTree(List.of("A","B")));
    byte[] jobSelector=json.writeValueAsBytes(Map.of("Arguments",Map.of("assetId",asset,"jobId",job)));
    org.mockito.Mockito.when(receipts.verifyManagedFile(null,path,"ouf.managed-source.preview",jobSelector))
        .thenReturn(new it.comune.trieste.ouf.onboarding.api.PermissionDelegationVerifier.Delegated(principal,"profile-result"));
    assertThat(json.valueToTree(api.preview(jobSelector,request)).path("columns")).isEqualTo(result.path("columns"));
    assertThat(result.path("columns").get(1).path("distinctCount").asInt()).isEqualTo(3);
    assertThat(result.path("columns").get(1).has("distinctValues")).isFalse();
    assertThat(result.path("columns").get(2).path("distinctValues").isArray()).isTrue();
    assertThat(result.toString()).doesNotContain("first-private","second-private","last-private","distinct_cell_values");
    assertThat(json.valueToTree(files.previewColumns(asset,profile,tenant,subject,null,0,1,null)).path("columns").get(0).path("distinctValues"))
        .isEqualTo(json.valueToTree(List.of("A","B")));
    assertThat(json.valueToTree(files.previewProfile(asset,profile,"tenant-b",subject)).path("columns").get(0)
        .has("distinctValues")).isFalse();
    assertThatThrownBy(()->files.requireTenantOwner(asset,"tenant-b",subject)).hasMessageContaining("scope");
    assertThatThrownBy(()->files.requireOwner(asset,"human:other")).hasMessageContaining("owned");

    // A writer using the predecessor's column set can still append a profile after V44.
    UUID legacyProfile=UUID.randomUUID();
    assertThat(db.sql("""
        insert into ouf_onboarding.file_profile
          (profile_id,asset_id,version,format,metadata,inferred_schema,candidate_keys,sample_ref,redacted_sample)
        select :legacy,asset_id,version+1,format,metadata,inferred_schema,candidate_keys,sample_ref,redacted_sample
        from ouf_onboarding.file_profile where profile_id=:current
        """).param("legacy",legacyProfile).param("current",profile).update()).isEqualTo(1);
    byte[] legacySelector=json.writeValueAsBytes(Map.of("Arguments",Map.of("assetId",asset,"profileId",legacyProfile)));
    org.mockito.Mockito.when(receipts.verifyManagedFile(null,path,"ouf.managed-source.preview",legacySelector))
        .thenReturn(new it.comune.trieste.ouf.onboarding.api.PermissionDelegationVerifier.Delegated(principal,"preview"));
    var retained=json.valueToTree(api.preview(legacySelector,request)).path("columns").get(0);
    assertThat(retained.has("distinctValues")).isFalse();
    assertThat(retained.path("distinctCount").asInt()).isEqualTo(2);
  }
  @Test void d084_35_wideProfilesReturnCompleteByteBoundColumnPages()throws Exception{
    var csv=new StringBuilder("a;b;c;d;e\n");
    for(int i=1000;i<3000;i++){
      String value="p".repeat(124)+i;
      csv.append(String.join(";",Collections.nCopies(5,value))).append('\n');
    }
    byte[] bytes=csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String subject="human:wide",tenant="tenant-wide";
    UUID asset=(UUID)files.registerOwned(null,"object://staging/d084-wide.csv",hashes.ofBytes(bytes),"text/csv",
        bytes.length,subject,"retention://30d",tenant).get("asset_id");
    UUID profile=(UUID)files.profile(asset,bytes,"profile://samples/d084-wide").get("profile_id");
    var full=bounded(preview(tenant,subject,Map.of("assetId",asset,"profileId",profile)));
    assertThat(full.path("columns").size()).isEqualTo(5);
    assertThat(full.path("allowedActions").toString()).contains("READ_DISTINCT_VALUES");
    var columns=new ArrayList<String>();
    Integer offset=0;
    do {
      var page=bounded(preview(tenant,subject,Map.of("view","COLUMNS","assetId",asset,"profileId",profile,"offset",offset)));
      assertThat(page.path("offset").asInt()).isEqualTo(offset);
      assertThat(page.path("columns").size()).isBetween(1,2);
      assertThat(page.path("schemaHash")).isEqualTo(full.path("schemaHash"));
      for(var column:page.path("columns")){
        columns.add(column.path("name").asText());
        assertThat(column.path("distinctValuesComplete").asBoolean()).isTrue();
        assertThat(column.path("distinctValues").size()).isEqualTo(2_000);
        assertThat(column.path("distinctValues").get(1999).asText()).isEqualTo("p".repeat(124)+"2999");
      }
      if (page.path("hasMore").asBoolean()) {
        int next=page.path("nextOffset").asInt();
        assertThat(next).isEqualTo(offset+page.path("columns").size()).isLessThan(5);
        offset=next;
      } else {
        assertThat(page.path("nextOffset").isNull()).isTrue();
        offset=null;
      }
    }while(offset!=null);
    assertThat(columns).containsExactly("a","b","c","d","e");
  }
  @Test void d085_40g_01_httpPreviewContinuesEscapedValuesAndChecksCursorScope()throws Exception{
    var expected=IntStream.range(1000,3000).mapToObj(i->"X"+"\u0001".repeat(120)+"\\\"\uD83D\uDE00"+i).toList();
    assertThat(json.writeValueAsBytes(expected).length).isGreaterThan(PREVIEW_BUDGET);
    byte[] csv=("code,other\n"+String.join("\n",expected.stream().map(v->"\""+v.replace("\"","\"\"")+"\",z").toList())+"\n")
        .getBytes(StandardCharsets.UTF_8);
    String tenant="tenant-a",subject="human:large-column";
    UUID asset=(UUID)files.registerOwned(null,"object://fixture/escaped.csv",hashes.ofBytes(csv),"text/csv",
        csv.length,subject,"retention://30d",tenant).get("asset_id");
    UUID job=(UUID)files.requestProfile(asset,"escaped-job").get("jobId");
    var claim=files.claimProfile("escaped-worker",java.time.Duration.ofSeconds(90),java.time.Instant.now()).orElseThrow();
    UUID profile=(UUID)files.completeProfile(job,"escaped-worker",((Number)claim.get("generation")).longValue(),csv,
        "profile://samples/escaped").get("resultRef");
    var profileBody=bounded(preview(tenant,subject,Map.of("assetId",asset,"profileId",profile)));
    var jobBody=bounded(preview(tenant,subject,Map.of("assetId",asset,"jobId",job)));
    var pageBody=bounded(preview(tenant,subject,Map.of("view","COLUMNS","assetId",asset,"profileId",profile)));
    assertThat(profileBody.path("assetId").asText()).isEqualTo(asset.toString());
    assertThat(profileBody.path("profileId").asText()).isEqualTo(profile.toString());
    assertThat(profileBody.path("profileVersion").asInt()).isEqualTo(1);
    assertThat(profileBody.path("format").asText()).isEqualTo("CSV");
    assertThat(profileBody.path("metadata").path("rows").asInt()).isEqualTo(expected.size());
    assertThat(profileBody.path("candidateKeys").toString()).isEqualTo("[\"code\"]");
    assertThat(profileBody.path("identityProposal").path("strategy").asText()).isEqualTo("NATIVE_KEY");
    assertThat(profileBody.path("recordModel").asText()).isEqualTo("ONE_ROW_ONE_SOURCE_OBJECT");
    assertThat(profileBody.path("proposalStatus").asText()).isEqualTo("PENDING_HUMAN_REVIEW");
    assertThat(profileBody.path("redactedSample").size()).isEqualTo(5);
    assertThat(jobBody.path("jobId").asText()).isEqualTo(job.toString());
    assertThat(jobBody.path("resultRef").asText()).isEqualTo(profile.toString());
    assertThat(jobBody.path("errorCode").isNull()).isTrue();
    assertThat(pageBody.path("nextOffset").asInt()).isEqualTo(1);
    assertThat(pageBody.path("hasMore").asBoolean()).isTrue();

    for (var initial : List.of(profileBody,jobBody,pageBody)) {
      assertThat(initial.path("allowedActions").toString()).contains("READ_DISTINCT_VALUES");
      var slice=initial.path("columns").get(0);
      assertThat(slice.path("distinctValuesComplete").booleanValue()).isFalse();
      var all=new ArrayList<String>();
      for(int step=0;step<4;step++) {
        assertThat(slice.path("name").asText()).isEqualTo("code");
        assertThat(slice.path("distinctCount").asInt()).isEqualTo(expected.size());
        assertThat(slice.path("dataType").asText()).isEqualTo("STRING");
        slice.path("distinctValues").forEach(value->all.add(value.asText()));
        if(slice.path("distinctValuesComplete").booleanValue())break;
        var next=bounded(preview(tenant,subject,Map.of("view","COLUMNS","assetId",asset,"profileId",profile,
            "distinctValuesCursor",slice.path("distinctValuesCursor").asText())));
        assertThat(next.path("offset").asInt()).isZero();
        assertThat(next.path("nextOffset").isNull()).isTrue();
        assertThat(next.path("workflowState").asText()).isEqualTo("PROFILE_COLUMNS");
        assertThat(next.path("reasonCode").asText()).isEqualTo("COLUMN_PAGE");
        slice=next.path("columns").get(0);
        if(slice.path("distinctValuesComplete").booleanValue())
          assertThat(next.path("allowedActions").toString()).isEqualTo("[\"READ_AGAIN\"]");
      }
      assertThat(slice.path("distinctValuesComplete").booleanValue()).isTrue();
      assertThat(slice.has("distinctValuesCursor")).isFalse();
      assertThat(all).containsExactlyElementsOf(expected);
    }

    String cursor=pageBody.path("columns").get(0).path("distinctValuesCursor").asText();
    UUID otherProfile=(UUID)files.profile(asset,csv,"profile://samples/other").get("profile_id");
    for(var args:List.of(
        Map.of("view","COLUMNS","assetId",asset,"profileId",profile,"distinctValuesCursor",(cursor.startsWith("A")?"B":"A")+cursor.substring(1)),
        Map.of("view","COLUMNS","assetId",asset,"profileId",otherProfile,"distinctValuesCursor",cursor),
        Map.of("view","COLUMNS","assetId",asset,"profileId",profile,"distinctValuesCursor",cursor,"offset",0),
        Map.of("view","COLUMNS","assetId",asset,"profileId",profile,"distinctValuesCursor",123))) {
      preview(tenant,subject,args).andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("ONB_DISTINCT_VALUES_CURSOR_INVALID"));
    }
    preview(tenant,subject,Map.of("assetId",asset,"profileId",profile,"distinctValuesCursor",cursor))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ONB_DISTINCT_VALUES_CURSOR_INVALID"));
    preview("tenant-b",subject,Map.of("view","COLUMNS","assetId",asset,"profileId",profile,"distinctValuesCursor",cursor))
        .andExpect(status().isForbidden());
  }

  private ResultActions preview(String tenant,String subject,Map<String,?> args)throws Exception{
    var principal=new PrincipalContext(subject,tenant,PrincipalContext.ActorType.HUMAN,"mcp","1","issuer","aud",Set.of(PREVIEW_CAP));
    when(previewReceipts.verifyManagedFile(eq("fixture"),eq(PREVIEW),eq(PREVIEW_CAP),any(byte[].class)))
        .thenReturn(new PermissionDelegationVerifier.Delegated(principal,"preview"));
    return http.perform(post(PREVIEW).contentType(MediaType.APPLICATION_JSON)
        .header("X-OUF-Managed-File-Receipt","fixture").content(json.writeValueAsBytes(Map.of("Arguments",args))));
  }

  private JsonNode bounded(ResultActions result)throws Exception{
    byte[] body=result.andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    assertThat(body.length).isLessThanOrEqualTo(PREVIEW_BUDGET);
    return json.readTree(body);
  }
  @Autowired ManagedFileService files; @Autowired OnboardingService onboarding; @Autowired CanonicalHash hashes; @Autowired JdbcClient db;
  @BeforeEach void clean() {
    db.sql("truncate table ouf_onboarding.audit_event,ouf_onboarding.consumer_compatibility_attestation,ouf_onboarding.approval_decision,ouf_onboarding.approval_challenge,ouf_onboarding.published_configuration,ouf_onboarding.file_profile_job,ouf_onboarding.onboarding_version,ouf_onboarding.file_profile,ouf_onboarding.managed_file_asset,ouf_onboarding.source restart identity cascade").update();
  }
  @Test @SuppressWarnings("unchecked") void immutablePagesAndOneDraftContainAll1024Properties() {
    byte[] csv=ManagedWideProfileTest.csv(1024,3,"value");
    UUID asset=(UUID)files.registerOwned(null,"object://staging/wide.csv",hashes.ofBytes(csv),"text/csv",csv.length,"human:wide","retention://30d","tenant-wide").get("asset_id");
    UUID profile=(UUID)files.profile(asset,csv,"profile://samples/wide").get("profile_id");
    var names=new LinkedHashSet<String>();String schemaHash=null;
    for(int offset=0;offset<1024;offset+=128) {
      var page=files.previewColumns(asset,profile,"tenant-wide","human:wide",null,offset,128,null);
      assertThat(page).containsEntry("columnCount",1024).containsEntry("offset",offset).containsEntry("hasMore",offset+128<1024);
      assertThat(page).doesNotContainKeys("redactedSample","staging_ref","sample_ref");
      if(schemaHash==null)schemaHash=(String)page.get("schemaHash");else assertThat(page.get("schemaHash")).isEqualTo(schemaHash);
      for(var column:(List<Map<String,Object>>)page.get("columns"))assertThat(names.add((String)column.get("name"))).isTrue();
    }
    assertThat(names).hasSize(1024).contains("campo_0","campo_1023");
    assertThatThrownBy(()->files.previewColumns(asset,profile,"tenant-wide","human:wide",null,1024,128,null)).hasMessageContaining("Invalid");
    assertThatThrownBy(()->files.previewColumns(asset,profile,"tenant-wide","human:wide",null,0,129,null)).hasMessageContaining("Invalid");
    var fields=names.stream().map(n->new ManagedFileService.FieldDecision(n,"INCLUDE","OPEN","https://example.org/"+n,"IDENTITY",null,null,null)).toList();
    var human=new OnboardingService.Actor("human:wide","HUMAN_USER");
    var draft=files.onboardIdempotent(asset,profile,"wide-test","Synthetic 1024-column object","Synthetic",
        "https://example.org/Entity",List.of("synthetic@1"),List.of(),fields,null,human,"wide-1","wide-create-1");
    var replay=files.onboardIdempotent(asset,profile,"wide-test","Synthetic 1024-column object","Synthetic",
        "https://example.org/Entity",List.of("synthetic@1"),List.of(),fields,null,human,"wide-2","wide-create-1");
    assertThat(replay).isEqualTo(draft);
    var stored=onboarding.version("wide-test",(UUID)draft.get("onboardingVersionId"));
    var configuration=(Map<String,Object>)stored.get("configuration");
    var mapping=(Map<String,Object>)configuration.get("semanticMapping");
    assertThat((List<?>)mapping.get("propertyMappings")).hasSize(1024);
    assertThat(db.sql("select count(*) from ouf_onboarding.onboarding_version where source_id='wide-test'").query(Long.class).single()).isEqualTo(1L);
  }
  private static String required(String name) {String value=System.getenv(name);if(value==null)throw new IllegalStateException(name);return value;}
}
