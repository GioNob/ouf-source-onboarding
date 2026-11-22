package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.comune.trieste.ouf.onboarding.api.IngestionRunRecoveryLinkApi;
import it.comune.trieste.ouf.onboarding.api.PermissionDelegationVerifier;
import it.comune.trieste.ouf.onboarding.api.ProblemHandler;
import it.comune.trieste.ouf.onboarding.application.ManagedFileService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SemanticReviewLinkTest {
  private static final String PATH = "/api/internal/v1/onboarding/ths-links/semantic-review";
  private static final String CAPABILITY = "ouf.onboarding.ths-links.semantic-review";
  private static final String CHALLENGE = "123e4567-e89b-42d3-a456-426614174000";
  private static final String ORIGIN = "https://ths.example.invalid";
  private static final String SUBJECT = "human:alice";
  private static final Duration TTL = Duration.ofMinutes(2);
  private final byte[] key = "e".repeat(64).getBytes(StandardCharsets.US_ASCII);
  private final ObjectMapper json = new ObjectMapper();
  private final ManagedFileService files = mock(ManagedFileService.class);
  private PermissionDelegationVerifier verifier;

  @BeforeEach void fixture(@TempDir Path directory) throws Exception {
    Path keyFile = Files.write(directory.resolve("fixture-key"), key);
    verifier = new PermissionDelegationVerifier(json, keyFile.toString(),
        "https://iam.example/realm", "ouf-gateway", "ouf-mcp-server");
  }

  @Test void returnsSemanticReviewLinkAndPresentationExpiry() throws Exception {
    byte[] body = request(CHALLENGE);
    Instant before = Instant.now();
    byte[] response = call(http(ORIGIN + "/"), body, receipt(body, "tenant-a", SUBJECT))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
        .andReturn().getResponse().getContentAsByteArray();

    var result = json.readTree(response);
    assertThat(result.size()).isEqualTo(2);
    assertThat(result.path("reviewUrl").asText()).isEqualTo(ORIGIN + "/trusted-human/semantic/?challenge=" + CHALLENGE);
    assertThat(Instant.parse(result.path("expiresAt").asText())).isBetween(before.plus(TTL), Instant.now().plus(TTL));
    verifyNoInteractions(files);
  }

  @Test void rejectsNonCanonicalUuidAndUnexpectedFields() throws Exception {
    MockMvc http = http(ORIGIN);
    byte[] extraField = json.writeValueAsBytes(Map.of("challengeId", CHALLENGE, "tenant", "tenant-a"));
    byte[] duplicate = ("{\"challengeId\":\"" + CHALLENGE + "\",\"challengeId\":\"" + CHALLENGE + "\"}")
        .getBytes(StandardCharsets.UTF_8);
    for (byte[] body : List.of(request(CHALLENGE.toUpperCase(Locale.ROOT)), request("1-1-1-1-1"),
        request(123), extraField, duplicate)) {
      call(http, body, receipt(body, "tenant-a", SUBJECT)).andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("ONB_THS_LINK_ARGUMENTS_INVALID"));
    }
  }

  @Test void requiresConfiguredHttpsOrigin() throws Exception {
    byte[] body = request(CHALLENGE);
    for (String origin : List.of("", ORIGIN + "/unexpected")) {
      call(http(origin), body, receipt(body, "tenant-a", SUBJECT)).andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.code").value("ONB_THS_LINK_ORIGIN_REQUIRED"))
          .andExpect(jsonPath("$.reviewUrl").doesNotExist());
    }
  }

  @Test void requiresHumanReceiptBoundToTenantSubjectRouteAndBody() throws Exception {
    MockMvc http = http(ORIGIN);
    byte[] body = request(CHALLENGE);
    http.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ONB_MANAGED_FILE_RECEIPT_INVALID"));

    String[] tenantB = receipt(body, "tenant-b", SUBJECT).split("\\.");
    var claims = (ObjectNode) json.readTree(Base64.getUrlDecoder().decode(tenantB[0]));
    claims.put("tenant", "tenant-a");
    String alteredTenant = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(json.writeValueAsBytes(claims)) + "." + tenantB[1];
    String recoveryReceipt = ManagedFileDelegationVerifierTest.sign(json, key,
        "/api/internal/v1/onboarding/ths-links/ingestion-run-recovery", "ouf.onboarding.ths-links.ingestion-run-recovery",
        body, "tenant-a", SUBJECT);
    for (String receipt : List.of(alteredTenant, receipt(body, "", SUBJECT), receipt(body, "tenant-a", ""),
        recoveryReceipt, receipt(request("123e4567-e89b-42d3-a456-426614174001"), "tenant-a", SUBJECT))) {
      call(http, body, receipt).andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("ONB_MANAGED_FILE_RECEIPT_INVALID"))
          .andExpect(jsonPath("$.reviewUrl").doesNotExist());
    }
  }

  private byte[] request(Object challenge) throws Exception {
    return json.writeValueAsBytes(Map.of("challengeId", challenge));
  }

  private String receipt(byte[] body, String tenant, String subject) throws Exception {
    return ManagedFileDelegationVerifierTest.sign(json, key, PATH, CAPABILITY, body, tenant, subject);
  }

  private MockMvc http(String origin) {
    return MockMvcBuilders.standaloneSetup(new IngestionRunRecoveryLinkApi(verifier, files, json, origin, TTL))
        .setControllerAdvice(new ProblemHandler()).build();
  }

  private ResultActions call(MockMvc http, byte[] body, String receipt) throws Exception {
    return http.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
        .header("X-OUF-Managed-File-Receipt", receipt).content(body));
  }
}
