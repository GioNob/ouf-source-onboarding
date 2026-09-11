package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import it.comune.trieste.ouf.onboarding.application.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties="ouf.onboarding.log-export-worker.poll-delay-ms=3600000")
class ProtectedLogRuntimeTest {
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->required("OUF_ONB_DB_URL"));r.add("spring.datasource.username",()->required("OUF_ONB_DB_USER"));r.add("spring.datasource.password",()->required("OUF_ONB_DB_PASSWORD"));}
  @Autowired OnboardingService onboarding;@Autowired ProtectedLogService logs;@Autowired JdbcClient db;
  OnboardingService.Actor human=new OnboardingService.Actor("human:operator","HUMAN_USER",Set.of("ouf.ths.log.read","ouf.ths.log.read.detail","ouf.ths.log.aggregate","ouf.ths.log.correlate","ouf.ths.log.export","ouf.ths.log.export.read","ouf.ths.log.download"));
  @BeforeEach void clean(){db.sql("truncate table ouf_onboarding.protected_log_access_audit,ouf_onboarding.protected_log_export_job,ouf_onboarding.audit_event,ouf_onboarding.source restart identity cascade").update();}
  @Test void searchIsTypedRedactedCappedAndAudited(){onboarding.createSource("s","Source","EXTERNAL_API","PULL","owner",Map.of(),human,"corr-1");var q=query("corr-1");Map<String,Object> result=logs.search(q,human,"authz:decision:1","request-1");assertThat((List<?>)result.get("items")).hasSize(1);Map<?,?> item=(Map<?,?>)((List<?>)result.get("items")).get(0);assertThat(item.get("actorSubject")).isEqualTo("[REDACTED]");assertThat(item.get("correlationId")).isEqualTo("corr-1");assertThat(db.sql("select operation||':'||outcome from ouf_onboarding.protected_log_access_audit").query(String.class).single()).isEqualTo("SEARCH:ALLOWED");}
  @Test void aiAndMissingCapabilityFailClosedAndAreAudited(){var ai=new OnboardingService.Actor("agent:test","AI_AGENT",Set.of("ouf.ths.log.read"));assertThatThrownBy(()->logs.search(query(null),ai,"authz:decision:2","request-2")).hasMessageContaining("HUMAN_USER");assertThatThrownBy(()->logs.search(query(null),new OnboardingService.Actor("human:no-grant","HUMAN_USER"),"authz:decision:3","request-3")).hasMessageContaining("did not grant");assertThat(db.sql("select count(*) from ouf_onboarding.protected_log_access_audit where outcome='DENIED'").query(Long.class).single()).isEqualTo(2);}
  @Test void exportHasPersistentLeaseLifecycleChecksumExpiryAndGovernedDownload(){onboarding.createSource("s","Source","EXTERNAL_API","PULL","owner",Map.of(),human,"corr-export");Map<String,Object> created=logs.createExport(query("corr-export"),human,"authz:create","request-create");UUID id=(UUID)created.get("export_id");Map<String,Object> job=logs.claimExport("test-worker",Duration.ofMinutes(2),Instant.now()).orElseThrow();logs.completeExport(job,"test-worker",Duration.ofMinutes(10));Map<String,Object> manifest=logs.export(id,"incident review",human,"authz:read","request-read");assertThat(manifest).containsEntry("state","SUCCEEDED");assertThat(String.valueOf(manifest.get("checksum"))).startsWith("sha256:");assertThat(manifest).doesNotContainKeys("artifact","authorization_context_ref","request_scope");assertThat(new String(logs.download(id,"incident evidence",human,"authz:fresh-download","request-download"))).contains("SOURCE_CREATED");assertThat(db.sql("select count(*) from ouf_onboarding.protected_log_access_audit where operation='DOWNLOAD' and outcome='ALLOWED'").query(Long.class).single()).isEqualTo(1);}
  @Test void secretsAreAlwaysRemovedEvenWithPiiGrant(){ProtectedLogRedactionService redaction=new ProtectedLogRedactionService();Map<String,Object> out=redaction.redact(Map.of("name","Mario","password","bad","nested",Map.of("access_token","bad","ok","yes")),true);assertThat(out).containsEntry("name","Mario").doesNotContainKey("password");assertThat((Map<?,?>)out.get("nested")).containsEntry("ok","yes").doesNotContainKey("access_token");}
  private static ProtectedLogService.Search query(String correlation){OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC);return new ProtectedLogService.Search(now.minusHours(1),now.plusMinutes(1),"ouf-source-onboarding","INFO",correlation,null,50,null,"incident investigation");}
  private static String required(String name){String value=System.getenv(name);if(value==null)throw new IllegalStateException(name+" required");return value;}
}
