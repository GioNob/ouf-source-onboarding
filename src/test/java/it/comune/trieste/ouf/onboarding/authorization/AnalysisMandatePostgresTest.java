package it.comune.trieste.ouf.onboarding.authorization;

import static it.comune.trieste.ouf.onboarding.authorization.AnalysisMandate.*;
import static it.comune.trieste.ouf.onboarding.authorization.AnalysisMandate.Error.CURRENT_RIGHTS_UNAVAILABLE;
import static it.comune.trieste.ouf.onboarding.authorization.AnalysisMandate.Error.RIGHTS_EVIDENCE_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.PolicyBundle;
import it.comune.trieste.ouf.onboarding.authorization.AnalysisMandateRights.Evidence;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "ouf.authorization.analysis-mandate.enabled=true",
    "ouf.authorization.analysis-mandate.installation=installation-test",
    "ouf.authorization.analysis-mandate.issuer=issuer:test",
    "ouf.authorization.analysis-mandate.mcp-workload=mcp-test",
    "ouf.authorization.analysis-mandate.ths-client=ths-test"
})
@AutoConfigureMockMvc
class AnalysisMandatePostgresTest {
  private static final String PREFIX = "/api/internal/v1/authorization/analysis-mandates/";
  private static final String TABLE = "ouf_authorization.analysis_mandate";
  private static final String CERTIFICATE = "jakarta.servlet.request.X509Certificate";
  private static final byte[] GATEWAY_CERT = {1, 2, 3};
  private static final String EPOCH = "a".repeat(64);
  private static final String ANALYSIS = "b".repeat(64);
  private static final List<String> SCOPE = List.of("ouf.semantic.discovery", "ouf.semantic.search");

  @DynamicPropertySource
  static void configuration(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", () -> System.getenv("OUF_ONB_DB_URL"));
    properties.add("spring.datasource.username", () -> System.getenv("OUF_ONB_DB_USER"));
    properties.add("spring.datasource.password", () -> System.getenv("OUF_ONB_DB_PASSWORD"));
    properties.add("ouf.authorization.analysis-mandate.gateway-cert-sha256",
        () -> new CanonicalHash(new ObjectMapper()).ofBytes(GATEWAY_CERT).substring(7));
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcClient db;
  @Autowired AuthorizationPolicyRegistry policies;
  @MockitoBean AnalysisMandateRights rights;
  private X509Certificate gateway;
  private String bundleId;
  private String bundleHash;

  @BeforeEach
  void setup() throws Exception {
    gateway = certificate(GATEWAY_CERT);
    db.sql("truncate " + TABLE + ", ouf_authorization.analysis_mandate_audit").update();
    bundleId = "analysis-mandate-test-" + random();
    bundleHash = publish(1);
    when(rights.fetch(any(), anyLong(), anyLong(), eq("issue"), isNull(), isNull()))
        .thenAnswer(call -> current(true, EPOCH, bundleHash, "f"));
    when(rights.fetch(any(), anyLong(), anyLong(), eq("dispatch"), any(), isNull()))
        .thenAnswer(call -> current(true, EPOCH, bundleHash, "f"));
    when(rights.fetch(any(), anyLong(), anyLong(), eq("revoke"), isNull(), anyString()))
        .thenAnswer(call -> revoker("owner-test", "plan-client", false));
    when(rights.fetchClock()).thenAnswer(call -> evidence(json.createObjectNode()));
  }

  @Test
  void concurrentRevokesCommitOnceAndStaleVersionFails() throws Exception {
    View mandate = issue();
    var ready = new CountDownLatch(2);
    when(rights.fetch(any(), anyLong(), anyLong(), eq("revoke"), isNull(), anyString())).thenAnswer(call -> {
      ready.countDown();
      await(ready);
      return revoker("owner-test", "plan-client", false);
    });
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = workers.submit(() -> revoke(mandate));
      var second = workers.submit(() -> revoke(mandate));
      var responses = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
      assertThat(responses.stream().map(Reply::status)).containsExactlyInAnyOrder(200, 409);
      assertThat(responses.stream().filter(reply -> reply.status() == 409).map(Reply::code))
          .containsExactly("MANDATE_VERSION_CHANGED");
    }
    assertThat(read(mandate).state()).isEqualTo("REVOKED");
    assertThat(read(mandate).version()).isEqualTo(2);
    assertThat(audit("REVOKED")).isEqualTo(1);
    assertThat(audit("DENIED")).isEqualTo(1);
  }

  @Test
  void admitWithPreviouslyFetchedRightsAndEveryLaterAdmitFailAfterRevocationCommit() throws Exception {
    View mandate = issue();
    assertThat(call("admit", admit(mandate), gateway, null).status()).isEqualTo(200);
    var observed = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    when(rights.fetch(any(), anyLong(), anyLong(), eq("dispatch"), any(), isNull())).thenAnswer(call -> {
      Evidence evidence = current(true, EPOCH, bundleHash, "f");
      observed.countDown();
      await(resume);
      return evidence;
    });
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var dispatch = workers.submit(() -> call("admit", admit(mandate), gateway, null));
      try {
        await(observed);
        assertThat(revoke(mandate).status()).isEqualTo(200);
      } finally {
        resume.countDown();
      }
      assertThat(dispatch.get(10, TimeUnit.SECONDS).code()).isEqualTo("MANDATE_REVOKED");
      var later = workers.invokeAll(java.util.stream.IntStream.range(0, 8)
          .<java.util.concurrent.Callable<Reply>>mapToObj(i -> () -> call("admit", admit(mandate), gateway, null))
          .toList(), 10, TimeUnit.SECONDS);
      for (var attempt : later) assertThat(attempt.get().code()).isEqualTo("MANDATE_REVOKED");
    }
    assertThat(audit("ADMITTED")).isEqualTo(1);
    assertThat(audit("DENIED")).isEqualTo(9);
  }

  @Test
  void lostIssueResponseReconcilesWithoutProofEvenAfterRevocation() throws Exception {
    var command = command(proof());
    var committed = view(call("issue", command, gateway, null));
    var replay = json.valueToTree(command);
    var replayBody = (com.fasterxml.jackson.databind.node.ObjectNode) replay;
    replayBody.remove("proof");
    assertThat(view(call("issue", replay, gateway, null))).isEqualTo(committed);
    assertThat(revoke(committed).status()).isEqualTo(200);
    assertThat(view(call("issue", replay, gateway, null))).isEqualTo(read(committed));
    replayBody.put("policyEpoch", "7".repeat(64));
    assertThat(call("issue", replay, gateway, null).code()).isEqualTo("ISSUE_KEY_CONFLICT");
    verify(rights, times(1)).fetch(any(), anyLong(), anyLong(), eq("issue"), isNull(), isNull());
    assertThat(audit("ISSUED")).isEqualTo(1);
  }

  @Test
  void mcpWorkloadCertificateIsRejectedOnIssueAndAdmit() throws Exception {
    var mcp = certificate(new byte[] {4, 5, 6});
    var command = command(proof());
    assertThat(call("issue", command, mcp, null).code()).isEqualTo("GATEWAY_WORKLOAD_DENIED");
    assertThat(call("issue", command, null, null).code()).isEqualTo("GATEWAY_AUTHENTICATION_REQUIRED");
    verifyNoInteractions(rights);
    var mandate = view(call("issue", command, gateway, null));
    assertThat(call("admit", admit(mandate), mcp, null).code()).isEqualTo("GATEWAY_WORKLOAD_DENIED");
    verify(rights, never()).fetch(any(), anyLong(), anyLong(), eq("dispatch"), any(), isNull());
    assertThat(audit("ADMITTED")).isZero();
  }

  @Test
  void deniesInactivePrincipalAndGrantOrEpochDrift() throws Exception {
    var mandate = issue();
    when(rights.fetch(any(), anyLong(), anyLong(), eq("dispatch"), any(), isNull()))
        .thenAnswer(call -> current(false, EPOCH, bundleHash, "f"))
        .thenAnswer(call -> current(true, "7".repeat(64), bundleHash, "f"))
        .thenAnswer(call -> current(true, EPOCH, bundleHash, "e"));
    assertThat(call("admit", admit(mandate), gateway, null).code()).isEqualTo("PRINCIPAL_INACTIVE");
    assertThat(call("admit", admit(mandate), gateway, null).code()).isEqualTo("POLICY_EPOCH_CHANGED");
    assertThat(call("admit", admit(mandate), gateway, null).code()).isEqualTo("RIGHTS_CHANGED");
    assertThat(audit("ADMITTED")).isZero();
    assertThat(audit("DENIED")).isEqualTo(3);
  }

  @Test
  void changedActivePolicyDeniesWhetherRightsReportsOldOrNewBundle() throws Exception {
    var mandate = issue();
    String newBundleHash = publish(2);
    assertThat(call("admit", admit(mandate), gateway, null).code()).isEqualTo("POLICY_CHANGED");
    bundleHash = newBundleHash;
    assertThat(call("admit", admit(mandate), gateway, null).code()).isEqualTo("POLICY_CHANGED");
    assertThat(audit("ADMITTED")).isZero();
  }

  @Test
  void rightsOutageFailsClosedAndRevocationSurvivesRecovery() throws Exception {
    var mandate = issue();
    var dispatch = admit(mandate);
    when(rights.fetch(any(), anyLong(), anyLong(), eq("dispatch"), any(), isNull()))
        .thenThrow(new Failure(CURRENT_RIGHTS_UNAVAILABLE, "15"))
        .thenAnswer(call -> current(true, EPOCH, bundleHash, "f"));
    var unavailable = call("admit", dispatch, gateway, null);
    assertThat(unavailable.code()).isEqualTo("CURRENT_RIGHTS_UNAVAILABLE");
    assertThat(unavailable.retryAfter()).isEqualTo("15");
    assertThat(revoke(mandate).status()).isEqualTo(200);
    assertThat(call("admit", dispatch, gateway, null).code()).isEqualTo("MANDATE_REVOKED");
    verify(rights, times(1)).fetch(any(), anyLong(), anyLong(), eq("dispatch"), any(), isNull());
    assertThat(audit("ADMITTED")).isZero();
    assertThat(audit("DENIED")).isEqualTo(2);
  }

  @Test
  void deadlineMaterializesExpiryAndDenialTogether() throws Exception {
    var mandate = issue();
    db.sql("""
        update ouf_authorization.analysis_mandate
        set issued_at_ms=issued_at_ms-86410000, expires_at_ms=expires_at_ms-86410000
        where mandate_ref=:ref
        """)
        .param("ref", id(mandate.mandateRef())).update();
    assertThat(call("admit", admit(mandate), gateway, null).code()).isEqualTo("MANDATE_EXPIRED");
    assertThat(read(mandate).state()).isEqualTo("EXPIRED");
    assertThat(read(mandate).version()).isEqualTo(2);
    assertThat(audit("EXPIRED")).isEqualTo(1);
    assertThat(audit("DENIED")).isEqualTo(1);
    assertThat(audit("ADMITTED")).isZero();
  }

  @Test
  void unverifiedForwardClockDoesNotExpireTheMandate() throws Exception {
    var mandate = issue();
    long deadline = db.sql("""
        update ouf_authorization.analysis_mandate
        set issued_at_ms=issued_at_ms-86410000, expires_at_ms=expires_at_ms-86410000
        where mandate_ref=:ref returning expires_at_ms
        """).param("ref", id(mandate.mandateRef())).query(Long.class).single();
    when(rights.fetch(any(), anyLong(), anyLong(), eq("dispatch"), any(), isNull())).thenAnswer(call -> {
      var current = current(true, EPOCH, bundleHash, "f");
      // The authority still sees a valid mandate while PostgreSQL sees a passed deadline.
      return new Evidence(current.value(), deadline - 60_000, deadline - 30_000,
          current.started(), current.received());
    });

    var denied = call("admit", admit(mandate), gateway, null);
    assertThat(denied.status()).isEqualTo(503);
    assertThat(denied.code()).isEqualTo("CLOCK_UNVERIFIED");
    assertThat(db.sql("""
        select state='ACTIVE' and version=1 and terminal_reason is null
        from ouf_authorization.analysis_mandate where mandate_ref=:ref
        """).param("ref", id(mandate.mandateRef())).query(Boolean.class).single()).isTrue();
    assertThat(audit("EXPIRED")).isZero();
    assertThat(audit("ADMITTED")).isZero();
  }

  @Test
  void activeMandateDoesNotRequireClockEvidence() throws Exception {
    when(rights.fetchClock()).thenThrow(new Failure(RIGHTS_EVIDENCE_INVALID));
    var command = command(proof());
    var mandate = view(call("issue", command, gateway, null));
    ObjectNode replay = json.valueToTree(command);
    replay.remove("proof");
    var expiry = new Expire(mandate.mandateRef(), mandate.generation(), mandate.version(), ANALYSIS,
        "ABSOLUTE_DEADLINE", random());

    assertThat(read(mandate)).isEqualTo(mandate);
    assertThat(view(call("issue", replay, gateway, null))).isEqualTo(mandate);
    var premature = call("expire", expiry, gateway, null);
    assertThat(premature.status()).isEqualTo(409);
    assertThat(premature.code()).isEqualTo("DEADLINE_NOT_REACHED");
    verify(rights, never()).fetchClock();
  }

  @ParameterizedTest
  @ValueSource(strings = {"read", "issue", "expire"})
  void deadlineRequiresVerifiedClock(String action) throws Exception {
    var command = command(proof());
    var mandate = view(call("issue", command, gateway, null));
    ObjectNode replay = json.valueToTree(command);
    replay.remove("proof");
    Object body = switch (action) {
      case "read" -> new Read(mandate.mandateRef(), mandate.generation());
      case "issue" -> replay;
      case "expire" -> new Expire(mandate.mandateRef(), mandate.generation(), mandate.version(), ANALYSIS,
          "ABSOLUTE_DEADLINE", random());
      default -> throw new IllegalArgumentException(action);
    };
    long deadline = db.sql("""
        update ouf_authorization.analysis_mandate
        set issued_at_ms=issued_at_ms-86410000, expires_at_ms=expires_at_ms-86410000
        where mandate_ref=:ref returning expires_at_ms
        """).param("ref", id(mandate.mandateRef())).query(Long.class).single();
    when(rights.fetchClock()).thenAnswer(call -> {
      long tick = System.nanoTime();
      return new Evidence(json.createObjectNode(), deadline - 60_000, deadline - 30_000, tick, tick);
    }).thenAnswer(call -> evidence(json.createObjectNode()));

    var denied = call(action, body, gateway, null);
    assertThat(denied.status()).isEqualTo(503);
    assertThat(denied.code()).isEqualTo("CLOCK_UNVERIFIED");
    assertThat(db.sql("""
        select state='ACTIVE' and version=1 and terminal_reason is null
        from ouf_authorization.analysis_mandate where mandate_ref=:ref
        """).param("ref", id(mandate.mandateRef())).query(Boolean.class).single()).isTrue();
    assertThat(audit("EXPIRED")).isZero();

    var expired = view(call(action, body, gateway, null));
    assertThat(expired.state()).isEqualTo("EXPIRED");
    assertThat(expired.version()).isEqualTo(2);
    assertThat(view(call(action, body, gateway, null))).isEqualTo(expired);
    assertThat(audit("EXPIRED")).isEqualTo(1);
    verify(rights, times(2)).fetchClock();
  }

  @ParameterizedTest
  @CsvSource({"FIRST_ROUND_COMPLETE, false", "CLOSED, false", "CANCELLED, false", "CLOSED, true"})
  void closingRevokedMandatePreventsRenewalWithFreshHumanProof(String reason, boolean renewedBeforeClosure)
      throws Exception {
    var command = command(proof());
    var first = view(call("issue", command, gateway, null));
    var revoked = view(revoke(first));
    assertThat(revoked.state()).isEqualTo("REVOKED");
    var latest = revoked;
    if (renewedBeforeClosure) {
      var second = view(call("issue", renewal(command, revoked), gateway, null));
      latest = view(revoke(second));
    }
    var expiry = new Expire(first.mandateRef(), first.generation(), first.version(), ANALYSIS, reason, random());
    assertThat(view(call("expire", expiry, gateway, null))).isEqualTo(revoked);
    assertThat(view(call("expire", expiry, gateway, null))).isEqualTo(revoked);

    var denied = call("issue", renewal(command, latest), gateway, null);
    assertThat(denied.status()).isEqualTo(409);
    assertThat(denied.code()).isEqualTo("MANDATE_NOT_RENEWABLE");
    assertThat(audit("ISSUED")).isEqualTo(latest.generation());
    assertThat(db.sql("select reason from ouf_authorization.analysis_mandate_audit where outcome='EXPIRED'")
        .query(String.class).single()).isEqualTo(reason);
  }

  @Test
  void renewalRequiresFreshAuthenticationAndCompletionIsTerminal() throws Exception {
    var command = command(proof());
    var first = view(call("issue", command, gateway, null));
    assertThat(revoke(first).status()).isEqualTo(200);
    var renewal = json.valueToTree(command);
    var body = (com.fasterxml.jackson.databind.node.ObjectNode) renewal;
    body.put("previousMandateRef", first.mandateRef()).put("expectedVersion", 2)
        .put("generation", 2).put("issueKey", random());
    assertThat(call("issue", body, gateway, null).code()).isEqualTo("REAUTHENTICATION_REQUIRED");
    long afterIssue = first.expiresAtMs() - TTL_MS + 1;
    long observed = Math.max(System.currentTimeMillis(), afterIssue);
    body.set("proof", json.valueToTree(new Proof("HUMAN", "BATCH_V1", "FIRST_ROUND",
        afterIssue, observed, observed + 10_000)));
    var second = view(call("issue", body, gateway, null));
    assertThat(second.generation()).isEqualTo(2);
    assertThat(second.mandateRef()).isNotEqualTo(first.mandateRef());
    var expiry = new Expire(second.mandateRef(), 2, 1, ANALYSIS, "FIRST_ROUND_COMPLETE", random());
    assertThat(view(call("expire", expiry, gateway, null)).state()).isEqualTo("EXPIRED");
    assertThat(view(call("expire", expiry, gateway, null)).version()).isEqualTo(2);
    body.put("previousMandateRef", second.mandateRef()).put("generation", 3).put("issueKey", random());
    assertThat(call("issue", body, gateway, null).code()).isEqualTo("MANDATE_NOT_RENEWABLE");
    assertThat(audit("ISSUED")).isEqualTo(2);
  }

  @Test
  void operatorMustHaveTheConfiguredThsClientAndAuthority() throws Exception {
    var mandate = issue();
    when(rights.fetch(any(), anyLong(), anyLong(), eq("revoke"), isNull(), anyString()))
        .thenAnswer(call -> revoker("other-human", "plan-client", true))
        .thenAnswer(call -> revoker("other-human", "ths-test", false))
        .thenAnswer(call -> revoker("authorized-operator", "ths-test", true));
    assertThat(revoke(mandate).code()).isEqualTo("REVOCATION_DENIED");
    assertThat(revoke(mandate).code()).isEqualTo("REVOCATION_DENIED");
    assertThat(revoke(mandate).status()).isEqualTo(200);
    assertThat(db.sql("select actor_id from ouf_authorization.analysis_mandate_audit where outcome='REVOKED'")
        .query(String.class).single()).isEqualTo("authorized-operator");
  }

  private String publish(long version) {
    var bundle = new PolicyBundle(bundleId, version, Instant.now(), List.of(), List.of());
    var published = policies.publish(bundle, "policy-test");
    policies.activate(published.bundleId(), published.version());
    return published.contentHash().substring(7);
  }

  private Evidence current(boolean active, String epoch, String policyBundle, String grant) {
    var value = json.createObjectNode().put("active", active).put("allowed", true)
        .put("policyEpoch", epoch).put("policyBundleHash", policyBundle).put("decisionRef", "decision-test")
        .put("acr", "mfa").put("scope", String.join(" ", SCOPE));
    value.putArray("roles").add("human");
    var grants = value.putObject("grants");
    OPERATIONS.forEach(operation -> grants.put(operation, grant.repeat(64)));
    return evidence(value);
  }

  private Evidence revoker(String subject, String client, boolean operatorAuthorized) {
    return evidence(json.createObjectNode().put("active", true).put("allowed", true).put("actorType", "HUMAN")
        .put("issuer", "issuer:test").put("tenant", "tenant-test").put("subject", subject).put("client", client)
        .put("operatorAuthorized", operatorAuthorized));
  }

  private static Evidence evidence(JsonNode value) {
    long now = System.currentTimeMillis();
    long tick = System.nanoTime();
    return new Evidence(value, now, now + 30_000, tick, tick);
  }

  private View issue() throws Exception { return view(call("issue", command(proof()), gateway, null)); }

  private static Proof proof() {
    long now = System.currentTimeMillis();
    return new Proof("HUMAN", "BATCH_V1", "FIRST_ROUND", now - 10_000, now, now + 30_000);
  }

  private ObjectNode renewal(Issue command, View previous) {
    ObjectNode body = json.valueToTree(command);
    body.put("previousMandateRef", previous.mandateRef()).put("expectedVersion", previous.version())
        .put("generation", previous.generation() + 1).put("issueKey", random());
    long afterIssue = previous.expiresAtMs() - TTL_MS + 1;
    long observed = Math.max(System.currentTimeMillis(), afterIssue);
    body.set("proof", json.valueToTree(new Proof("HUMAN", "BATCH_V1", "FIRST_ROUND",
        afterIssue, observed, observed + 10_000)));
    return body;
  }

  private static Issue command(Proof proof) {
    String key = random();
    return new Issue(ANALYSIS, random(), random(), "c".repeat(64), "issuer:test", "tenant-test", "owner-test",
        "plan-client", "mcp-test", random(), key, key, EPOCH, SCOPE, 1, 0L, null, proof);
  }

  private static Admit admit(View mandate) {
    return new Admit(mandate.mandateRef(), mandate.generation(), 1, ANALYSIS, "mcp-test", EPOCH,
        "semantic.search.batch", "d".repeat(64), random(), random(), null);
  }

  private Reply revoke(View mandate) throws Exception {
    return call("revoke", new Revoke(mandate.mandateRef(), mandate.generation(), 1, random()), gateway,
        "Bearer test");
  }

  private View read(View mandate) throws Exception {
    return view(call("read", new Read(mandate.mandateRef(), mandate.generation()), gateway, null));
  }

  private View view(Reply reply) throws Exception {
    assertThat(reply.status()).withFailMessage("Unexpected response: %s", reply.value()).isEqualTo(200);
    return json.treeToValue(reply.value(), View.class);
  }

  private Reply call(String action, Object body, X509Certificate peer, String bearer) throws Exception {
    var request = post(PREFIX + action).secure(true).contentType("application/json")
        .content(json.writeValueAsBytes(body));
    if (peer != null) request.requestAttr(CERTIFICATE, new X509Certificate[] {peer});
    if (bearer != null) request.header("Authorization", bearer);
    var response = mvc.perform(request).andReturn().getResponse();
    return new Reply(response.getStatus(), json.readTree(response.getContentAsByteArray()),
        response.getHeader("Retry-After"));
  }

  private long audit(String outcome) {
    return db.sql("select count(*) from ouf_authorization.analysis_mandate_audit where outcome=:outcome")
        .param("outcome", outcome).query(Long.class).single();
  }

  private static X509Certificate certificate(byte[] encoded) throws Exception {
    var certificate = mock(X509Certificate.class);
    when(certificate.getEncoded()).thenReturn(encoded);
    return certificate;
  }

  private static String random() { return UUID.randomUUID().toString(); }

  private static void await(CountDownLatch latch) throws InterruptedException {
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
  }

  private record Reply(int status, JsonNode value, String retryAfter) {
    String code() { return value.path("code").asText(); }
  }
}
