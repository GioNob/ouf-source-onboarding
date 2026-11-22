package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import it.comune.trieste.ouf.onboarding.application.*;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class ManagedWideProfileRuntimeTest {
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
    assertThat(json.valueToTree(files.previewColumns(asset,profile,0,1)).path("columns").get(0).path("distinctValues"))
        .isEqualTo(json.valueToTree(List.of("A","B")));
    assertThat(json.valueToTree(files.previewProfile(asset,profile,"tenant-b",subject)).path("columns").get(0)
        .has("distinctValues")).isFalse();
    assertThatThrownBy(()->files.requireTenantOwner(asset,"tenant-b",subject)).hasMessageContaining("scope");
    assertThatThrownBy(()->files.requireOwner(asset,"human:other")).hasMessageContaining("owned");

    // Rows written by the predecessor omit the new column, while keeping their exact count.
    db.sql("update ouf_onboarding.file_profile set distinct_cell_values=null where profile_id=:p").param("p",profile).update();
    var retained=json.valueToTree(api.preview(selector,request)).path("columns").get(0);
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
    var json=new com.fasterxml.jackson.databind.ObjectMapper();
    var full=files.previewProfile(asset,profile,tenant,subject);
    assertThat(json.writeValueAsBytes(full).length).isLessThanOrEqualTo(1_048_576);
    var columns=new ArrayList<String>();
    Integer offset=0;
    do {
      var page=files.previewColumns(asset,profile,offset,128);
      byte[] body=json.writeValueAsBytes(page);
      assertThat(body.length).isLessThanOrEqualTo(1_048_576);
      for(var column:json.readTree(body).path("columns")){
        columns.add(column.path("name").asText());
        assertThat(column.path("distinctValues").size()).isEqualTo(2_000);
        assertThat(column.path("distinctValues").get(1999).asText()).isEqualTo("p".repeat(124)+"2999");
      }
      offset=(Integer)page.get("nextOffset");
    }while(offset!=null);
    assertThat(columns).containsExactly("a","b","c","d","e");
  }
  @Autowired ManagedFileService files; @Autowired OnboardingService onboarding; @Autowired CanonicalHash hashes; @Autowired JdbcClient db;
  @BeforeEach void clean() {
    db.sql("truncate table ouf_onboarding.audit_event,ouf_onboarding.consumer_compatibility_attestation,ouf_onboarding.approval_decision,ouf_onboarding.approval_challenge,ouf_onboarding.published_configuration,ouf_onboarding.file_profile_job,ouf_onboarding.onboarding_version,ouf_onboarding.file_profile,ouf_onboarding.managed_file_asset,ouf_onboarding.source restart identity cascade").update();
  }
  @Test @SuppressWarnings("unchecked") void immutablePagesAndOneDraftContainAll1024Properties() {
    byte[] csv=ManagedWideProfileTest.csv(1024,3,"value");
    UUID asset=(UUID)files.register(null,"object://staging/wide.csv",hashes.ofBytes(csv),"text/csv",csv.length,"human:wide","retention://30d").get("asset_id");
    UUID profile=(UUID)files.profile(asset,csv,"profile://samples/wide").get("profile_id");
    var names=new LinkedHashSet<String>();String schemaHash=null;
    for(int offset=0;offset<1024;offset+=128) {
      var page=files.previewColumns(asset,profile,offset,128);
      assertThat(page).containsEntry("columnCount",1024).containsEntry("offset",offset).containsEntry("hasMore",offset+128<1024);
      assertThat(page).doesNotContainKeys("redactedSample","staging_ref","sample_ref");
      if(schemaHash==null)schemaHash=(String)page.get("schemaHash");else assertThat(page.get("schemaHash")).isEqualTo(schemaHash);
      for(var column:(List<Map<String,Object>>)page.get("columns"))assertThat(names.add((String)column.get("name"))).isTrue();
    }
    assertThat(names).hasSize(1024).contains("campo_0","campo_1023");
    assertThatThrownBy(()->files.previewColumns(asset,profile,1024,128)).hasMessageContaining("Invalid");
    assertThatThrownBy(()->files.previewColumns(asset,profile,0,129)).hasMessageContaining("Invalid");
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
