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
