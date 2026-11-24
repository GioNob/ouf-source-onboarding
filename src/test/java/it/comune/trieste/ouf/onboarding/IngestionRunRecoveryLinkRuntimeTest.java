package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.api.IngestionRunRecoveryLinkApi;
import it.comune.trieste.ouf.onboarding.api.PermissionDelegationVerifier;
import it.comune.trieste.ouf.onboarding.api.ProblemHandler;
import it.comune.trieste.ouf.onboarding.application.ManagedFileService;
import it.comune.trieste.ouf.onboarding.application.OnboardingService;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class IngestionRunRecoveryLinkRuntimeTest {
  private static final String PATH = "/api/internal/v1/onboarding/ths-links/ingestion-run-recovery";
  private static final String CAPABILITY = "ouf.onboarding.ths-links.ingestion-run-recovery";
  private static final String SOURCE = "managed-recovery-fixture";
  private static final String SUBJECT = "human:alice";
  private static final String RUN = "123e4567-e89b-42d3-a456-426614174000";
  private static final String ORIGIN = "https://ths.example.invalid";
  private static final Duration TTL = Duration.ofMinutes(2);
  private final byte[] key = "e".repeat(64).getBytes(StandardCharsets.US_ASCII);

  @TempDir Path directory;
  @Autowired ObjectMapper json;
  @Autowired CanonicalHash hashes;
  @Autowired ManagedFileService files;
  @Autowired OnboardingService onboarding;
  @Autowired JdbcClient db;
  private PermissionDelegationVerifier verifier;

  @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", () -> System.getenv("OUF_ONB_DB_URL"));
    properties.add("spring.datasource.username", () -> System.getenv("OUF_ONB_DB_USER"));
    properties.add("spring.datasource.password", () -> System.getenv("OUF_ONB_DB_PASSWORD"));
  }

  @BeforeEach void fixture() throws Exception {
    var keyFile = Files.write(directory.resolve("fixture-key"), key);
    verifier = new PermissionDelegationVerifier(json, keyFile.toString(), "https://iam.example/realm", "ouf-gateway", "ouf-mcp-server");
    byte[] csv = "code\n123\n".getBytes(StandardCharsets.UTF_8);
    var asset = files.registerOwned(null, "object://fixture/recovery.csv", hashes.ofBytes(csv), "text/csv",
        csv.length, SUBJECT, "retention://fixture", "tenant-a");
    var actor = new OnboardingService.Actor(SUBJECT, "HUMAN_USER");
    var metadata = Map.<String,Object>of("managedFileAssetId", asset.get("asset_id").toString(), "managedTenantId", "tenant-a");
    onboarding.createSource(SOURCE, "Fixture", "INTERNAL_MANAGED", "MANAGED", "Fixture", metadata, actor, "fixture");
    db.sql("update ouf_onboarding.managed_file_asset set source_id=:source where asset_id=:asset")
        .param("source", SOURCE).param("asset", asset.get("asset_id")).update();
    onboarding.createSource("external-recovery-fixture", "External fixture", "EXTERNAL_FILE", "PULL", "Fixture", metadata, actor, "fixture");
    onboarding.createSource("unbound-recovery-fixture", "Unbound fixture", "INTERNAL_MANAGED", "MANAGED", "Fixture", metadata, actor, "fixture");
  }

  @Test void d085_40g_02_returnsConfiguredHttpsLinkAndPresentationExpiry() throws Exception {
    Instant before = Instant.now();
    byte[] body = call(http(ORIGIN + "/"), request(SOURCE, RUN, 7), "tenant-a", SUBJECT)
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
        .andReturn().getResponse().getContentAsByteArray();
    var result = json.readTree(body);
    assertThat(result.size()).isEqualTo(2);
    assertThat(result.path("reviewUrl").asText()).isEqualTo(ORIGIN + "/trusted-human/ingestion-run-recovery/?runId=" + RUN + "&expectedVersion=7");
    assertThat(Instant.parse(result.path("expiresAt").asText())).isBetween(before.plus(TTL), Instant.now().plus(TTL));
  }

  @Test void d085_40g_03_rejectsWrongIdentityUnmanagedSourceAndDifferentReceipt() throws Exception {
    var http = http(ORIGIN);
    byte[] body = request(SOURCE, RUN, 7);
    call(http, body, "tenant-b", SUBJECT).andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ONB_THS_LINK_SOURCE_SCOPE_UNPROVEN"));
    call(http, body, "tenant-a", "human:bob").andExpect(status().isForbidden());
    call(http, request("external-recovery-fixture", RUN, 7), "tenant-a", SUBJECT).andExpect(status().isForbidden());
    call(http, request("unbound-recovery-fixture", RUN, 7), "tenant-a", SUBJECT).andExpect(status().isForbidden());
    call(http, body, "tenant-a", "").andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ONB_MANAGED_FILE_RECEIPT_INVALID"));

    String previewReceipt = ManagedFileDelegationVerifierTest.sign(json, key, PATH,
        "ouf.managed-source.preview", body, "tenant-a", SUBJECT);
    http.perform(post(PATH).header("X-OUF-Managed-File-Receipt", previewReceipt)
        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
    String receipt = ManagedFileDelegationVerifierTest.sign(json, key, PATH, CAPABILITY, body, "tenant-a", SUBJECT);
    http.perform(post(PATH).header("X-OUF-Managed-File-Receipt", receipt)
        .contentType(MediaType.APPLICATION_JSON).content(request(SOURCE, RUN, 8))).andExpect(status().isForbidden());
  }

  @Test void d085_40g_04_requiresOriginAndCanonicalRunSelector() throws Exception {
    for (String origin : List.of("", ORIGIN + "/path")) {
      call(http(origin), request(SOURCE, RUN, 7), "tenant-a", SUBJECT)
          .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("ONB_THS_LINK_ORIGIN_REQUIRED"))
          .andExpect(jsonPath("$.reviewUrl").doesNotExist());
    }
    var http = http(ORIGIN);
    for (byte[] body : List.of(request(SOURCE, RUN.toUpperCase(java.util.Locale.ROOT), 7), request(SOURCE, "1-1-1-1-1", 7),
        request(SOURCE, RUN, -1), request(SOURCE, RUN, 7.5), request(SOURCE, RUN, 9007199254740992L))) {
      call(http, body, "tenant-a", SUBJECT).andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("ONB_THS_LINK_ARGUMENTS_INVALID"));
    }
  }

  private MockMvc http(String origin) {
    return MockMvcBuilders.standaloneSetup(new IngestionRunRecoveryLinkApi(verifier, files, json, origin, TTL))
        .setControllerAdvice(new ProblemHandler()).build();
  }

  private byte[] request(String source, String run, Number version) throws Exception {
    return json.writeValueAsBytes(Map.of("sourceId", source, "runId", run, "expectedVersion", version));
  }

  private ResultActions call(MockMvc http, byte[] body, String tenant, String subject) throws Exception {
    String receipt = ManagedFileDelegationVerifierTest.sign(json, key, PATH, CAPABILITY, body, tenant, subject);
    return http.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
        .header("X-OUF-Managed-File-Receipt", receipt).content(body));
  }
}
