package it.comune.trieste.ouf.onboarding.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import it.comune.trieste.ouf.authorization.PrincipalContext;
import it.comune.trieste.ouf.onboarding.api.CorrelationFilter;
import it.comune.trieste.ouf.onboarding.api.PermissionDelegationVerifier;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/** Same migrated PostgreSQL database as module-ci. All picker times are controlled. */
@SpringBootTest
class ManagedFileChatHandoffTest {
  private static final Instant START = Instant.parse("2030-03-01T08:00:00Z");
  private static final String ORIGIN = "https://ths.example.invalid/";
  private static final String TENANT = "tenant-a", USER = "human:alice";
  private static final String TABLE = "ouf_onboarding.managed_file_handoff";
  @Autowired ManagedFileService files;
  @Autowired JdbcTemplate db;
  @Autowired TransactionTemplate tx;
  @Autowired WebApplicationContext context;
  @MockitoBean PermissionDelegationVerifier receipts;
  private ManagedFileChatHandoff owner;

  @BeforeEach void setup() {
    db.execute("truncate " + TABLE);
    owner = at(0);
  }

  private ManagedFileChatHandoff at(long seconds) {
    return new ManagedFileChatHandoff(files, db, tx, ORIGIN, 3600, 86400,
        Clock.fixed(START.plusSeconds(seconds), ZoneOffset.UTC));
  }

  private UUID issue() {
    return (UUID) owner.issue(UUID.randomUUID(), TENANT, USER).get("handoffId");
  }

  private UUID asset() {
    return (UUID) files.registerOwned(null, "object://fixture/" + UUID.randomUUID(), "sha256:" + "a".repeat(64),
        "text/csv", 42, USER, "retention://fixture", TENANT).get("asset_id");
  }

  @Test void d089_01_concurrentIssueReturnsOriginalReceiptAndBindsCommandToPrincipal() throws Exception {
    UUID key = UUID.randomUUID();
    var start = new CyclicBarrier(2);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var first = workers.submit(() -> {
        start.await(5, TimeUnit.SECONDS);
        return owner.issue(key, TENANT, USER);
      });
      start.await(5, TimeUnit.SECONDS);
      var issued = owner.issue(key, TENANT, USER);
      assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(issued);
      UUID id = (UUID) issued.get("handoffId");
      assertThat(id.version()).isEqualTo(4);
      assertThat(issued).containsEntry("state", "PENDING").containsEntry("observationVersion", "1")
          .containsEntry("issuedAt", START.toString()).containsEntry("waitUntil", START.plusSeconds(3600).toString())
          .containsEntry("pickerUrl", ORIGIN + "trusted-human/managed-files/?handoff=" + id);
      owner.reject(id, TENANT, USER, "FORMAT_UNSUPPORTED");
      assertThat(at(50).issue(key, TENANT, USER)).isEqualTo(issued);
      assertThatThrownBy(() -> owner.issue(key, TENANT, "other")).hasMessage("ONB_HANDOFF_COMMAND_CONFLICT");
      assertThatThrownBy(() -> owner.issue(key, "other", USER)).hasMessage("ONB_HANDOFF_COMMAND_CONFLICT");
      assertThat(db.queryForObject("select count(*) from " + TABLE, Long.class)).isEqualTo(1L);
    }
  }

  @Test void d089_02_terminalDecisionsAreVersionedOnceAndRejectConflictingReplays() {
    UUID id = issue(), asset = asset(), other = asset();
    assertThat(owner.result(id, TENANT, USER)).containsEntry("state", "PENDING").containsEntry("observationVersion", "1");
    at(3599).complete(id, asset, TENANT, USER);
    var staged = at(3600).result(id, TENANT, USER);
    assertThat(staged).containsEntry("assetId", asset).containsEntry("state", "STAGED").containsEntry("observationVersion", "2");
    at(4000).complete(id, asset, TENANT, USER);
    assertThat(at(4000).result(id, TENANT, USER)).isEqualTo(staged);
    assertThatThrownBy(() -> at(4000).complete(id, other, TENANT, USER)).hasMessage("ONB_HANDOFF_OUTCOME_CONFLICT");
    assertThatThrownBy(() -> at(4000).reject(id, TENANT, USER, "SIZE_INVALID")).hasMessage("ONB_HANDOFF_OUTCOME_CONFLICT");

    UUID rejected = issue();
    owner.reject(rejected, TENANT, USER, "SIZE_INVALID");
    owner.reject(rejected, TENANT, USER, "SIZE_INVALID");
    assertThat(owner.result(rejected, TENANT, USER)).containsEntry("code", "SIZE_INVALID")
        .containsEntry("state", "REJECTED").containsEntry("observationVersion", "2").doesNotContainKey("assetId");
    assertThatThrownBy(() -> owner.reject(rejected, TENANT, USER, "FORMAT_UNSUPPORTED")).hasMessage("ONB_HANDOFF_OUTCOME_CONFLICT");
    assertThatThrownBy(() -> owner.complete(rejected, asset, TENANT, USER)).hasMessage("ONB_HANDOFF_OUTCOME_CONFLICT");
  }

  @Test void d089_03_exactDeadlineClosesBothReadAndWritePaths() {
    UUID first = issue(), second = issue(), asset = asset();
    at(3599).checkUpload(first, TENANT, USER);
    assertThatThrownBy(() -> at(3600).checkUpload(first, TENANT, USER)).hasMessage("ONB_HANDOFF_EXPIRED");
    assertThat(at(3599).result(first, TENANT, USER)).containsEntry("state", "PENDING");
    var expired = at(3600).result(first, TENANT, USER);
    assertThat(expired).containsEntry("status", "EXPIRED").containsEntry("state", "EXPIRED")
        .containsEntry("observationVersion", "2").containsEntry("waitUntil", START.plusSeconds(3600).toString());
    assertThatThrownBy(() -> at(3600).complete(second, asset, TENANT, USER)).hasMessage("ONB_HANDOFF_EXPIRED");
    assertThatThrownBy(() -> at(3601).reject(first, TENANT, USER, "SIZE_INVALID")).hasMessage("ONB_HANDOFF_EXPIRED");
    assertThat(at(3605).result(second, TENANT, USER)).isEqualTo(expired);
  }

  private void assertUnknown(ThrowingCallable operation) {
    assertThatThrownBy(operation).isInstanceOfSatisfying(DomainFailure.class, failure -> {
      assertThat(failure.status()).isEqualTo(HttpStatus.CONFLICT);
      assertThat(failure.code()).isEqualTo("ONB_HANDOFF_UNKNOWN");
    });
  }

  @Test void d089_04_foreignTenantAndSubjectCannotUseOrChangeTheReceipt() {
    UUID issued = issue(), asset = asset();
    assertUnknown(() -> owner.result(issued, "other", USER));
    assertUnknown(() -> owner.checkUpload(issued, "other", USER));
    assertUnknown(() -> owner.reject(issued, "other", USER, "SIZE_INVALID"));
    assertUnknown(() -> owner.complete(issued, asset, "other", USER));
    assertUnknown(() -> owner.result(issued, TENANT, "other"));
    assertUnknown(() -> owner.checkUpload(issued, TENANT, "other"));
    assertUnknown(() -> owner.reject(issued, TENANT, "other", "SIZE_INVALID"));
    assertUnknown(() -> owner.complete(issued, asset, TENANT, "other"));
    UUID foreignAsset = (UUID) files.registerOwned(null, "object://fixture/" + UUID.randomUUID(),
        "sha256:" + "b".repeat(64), "text/csv", 42, USER, "retention://fixture", "other").get("asset_id");
    assertUnknown(() -> owner.complete(issued, foreignAsset, "other", USER));
    assertThatThrownBy(() -> owner.complete(issued, foreignAsset, TENANT, USER)).isInstanceOfSatisfying(
        DomainFailure.class, failure -> assertThat(failure.code()).isEqualTo("ONB_FILE_OWNER_SCOPE_UNPROVEN"));
    assertThat(owner.result(issued, TENANT, USER)).containsEntry("state", "PENDING").containsEntry("observationVersion", "1");
  }

  @Test void d089_05_competingDeadlineAndCompletionPublishOneTerminalVersion() throws Exception {
    UUID id = issue(), asset = asset();
    var start = new CyclicBarrier(2);
    var closing = at(3600);
    var completing = at(3599);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var finish = workers.submit(() -> {
        start.await(5, TimeUnit.SECONDS);
        try {
          completing.complete(id, asset, TENANT, USER);
          return "STAGED";
        } catch (DomainFailure failure) {
          assertThat(failure.code()).isEqualTo("ONB_HANDOFF_EXPIRED");
          return "EXPIRED";
        }
      });
      start.await(5, TimeUnit.SECONDS);
      var observed = closing.result(id, TENANT, USER);
      assertThat(observed).containsEntry("state", finish.get(10, TimeUnit.SECONDS)).containsEntry("observationVersion", "2");
      assertThat(at(7200).result(id, TENANT, USER)).isEqualTo(observed);
    }
  }

  private GenericApplicationContext pickerContext(long seconds) {
    var context = new GenericApplicationContext();
    context.registerBean(ManagedFileChatHandoff.class, () -> at(seconds));
    context.refresh();
    return context;
  }

  @Test void d089_06_restartPreservesPendingAndTerminalReceipts() {
    UUID key = UUID.randomUUID(), rejected = issue();
    Map<String,Object> issued;
    try (var first = pickerContext(0)) {
      var before = first.getBean(ManagedFileChatHandoff.class);
      issued = before.issue(key, TENANT, USER);
      before.reject(rejected, TENANT, USER, "SIZE_INVALID");
    }
    try (var second = pickerContext(2700)) {
      var after = second.getBean(ManagedFileChatHandoff.class);
      assertThat(after.issue(key, TENANT, USER)).isEqualTo(issued);
      assertThat(after.result((UUID) issued.get("handoffId"), TENANT, USER))
          .containsEntry("waitUntil", issued.get("waitUntil")).containsEntry("state", "PENDING");
      assertThat(after.result(rejected, TENANT, USER)).containsEntry("code", "SIZE_INVALID")
          .containsEntry("state", "REJECTED").containsEntry("observationVersion", "2");
    }
  }

  @Test void d089_07_retentionRunsFromCompletionAndCannotReopenAnIssuedLink() {
    UUID key = UUID.randomUUID(), asset = asset();
    var issued = owner.issue(key, TENANT, USER);
    UUID id = (UUID) issued.get("handoffId");
    at(100).reject(id, TENANT, USER, "SIZE_INVALID");
    var lastSecond = at(100 + 86400 - 1);
    lastSecond.issue(UUID.randomUUID(), TENANT, USER);
    assertThat(lastSecond.result(id, TENANT, USER)).containsEntry("state", "REJECTED");
    var after = at(100 + 86400);
    assertThat(after.issue(key, TENANT, USER)).isEqualTo(issued);
    assertUnknown(() -> after.result(id, TENANT, USER));
    assertUnknown(() -> after.checkUpload(id, TENANT, USER));
    assertUnknown(() -> after.complete(id, asset, TENANT, USER));
    assertUnknown(() -> after.reject(id, TENANT, USER, "SIZE_INVALID"));
    assertThat(db.queryForObject("select code is null and receipt_retired from " + TABLE + " where handoff_id=?",
        Boolean.class, id)).isTrue();
  }

  @Test void d089_08_configurationIsBoundedAndADifferentWaitIsPersisted() {
    for (int value : new int[]{0, -1, 3601}) {
      assertThatThrownBy(() -> new ManagedFileChatHandoff(files, db, tx, ORIGIN, value, 1))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> new ManagedFileChatHandoff(files, db, tx, ORIGIN, 60, 0))
        .isInstanceOf(IllegalArgumentException.class);
    var shorter = new ManagedFileChatHandoff(files, db, tx, ORIGIN, 15, 1, Clock.fixed(START, ZoneOffset.UTC));
    var issued = shorter.issue(UUID.randomUUID(), TENANT, USER);
    assertThat(issued).containsEntry("waitUntil", START.plusSeconds(15).toString());
    assertThat(at(15).result((UUID) issued.get("handoffId"), TENANT, USER)).containsEntry("state", "EXPIRED");
  }

  @Test void d089_10_unavailableReceiptsReturnByteIdenticalHttpProblems() throws Exception {
    UUID retired = issue();
    owner.reject(retired, TENANT, USER, "SIZE_INVALID");
    var after = at(86400);
    UUID foreignTenant = (UUID) after.issue(UUID.randomUUID(), "other", USER).get("handoffId");
    UUID foreignSubject = (UUID) after.issue(UUID.randomUUID(), TENANT, "other").get("handoffId");
    UUID owned = (UUID) after.issue(UUID.randomUUID(), TENANT, USER).get("handoffId");
    after.reject(owned, TENANT, USER, "FORMAT_UNSUPPORTED");
    UUID legacy = insertLegacyStaged(asset());

    var mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new CorrelationFilter()).build();
    readHandoff(mvc, owned).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("REJECTED"))
        .andExpect(jsonPath("$.legacy").doesNotExist());
    byte[] missing = readUnknownBody(mvc, UUID.randomUUID());
    for (UUID id : new UUID[]{foreignTenant, foreignSubject, retired, legacy}) {
      assertThat(readUnknownBody(mvc, id)).isEqualTo(missing);
    }
  }

  private byte[] readUnknownBody(MockMvc mvc, UUID id) throws Exception {
    return readHandoff(mvc, id).andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("ONB_HANDOFF_UNKNOWN")).andExpect(jsonPath("$.workflowState").value("ERROR"))
        .andExpect(jsonPath("$.reasonCode").value("OWNER_ERROR")).andExpect(jsonPath("$.allowedActions[0]").value("NONE"))
        .andExpect(jsonPath("$.legacy").doesNotExist()).andReturn().getResponse().getContentAsByteArray();
  }

  private ResultActions readHandoff(MockMvc mvc, UUID id) throws Exception {
    String path = "/api/internal/v1/onboarding/managed-file-mcp/handoff";
    String capability = "ouf.managed-source.file.upload";
    byte[] body = """
        {"Arguments":{"handoffId":"%s"}}
        """.formatted(id).getBytes(StandardCharsets.UTF_8);
    var principal = new PrincipalContext(USER, TENANT, PrincipalContext.ActorType.HUMAN, null,
        "fixture-auth", "fixture-issuer", "fixture-audience", Set.of(capability));
    when(receipts.verifyManagedFile("fixture", path, capability, body))
        .thenReturn(new PermissionDelegationVerifier.Delegated(principal, "fixture"));
    String correlation = UUID.randomUUID().toString();
    return mvc.perform(post(path).header("X-OUF-Managed-File-Receipt", "fixture")
            .header("X-Correlation-ID", correlation).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(header().string("X-Correlation-ID", correlation));
  }

  @Test void d089_11_unissuedUploadAndBothDecisionsLeaveNoReceipt() {
    UUID missing = UUID.randomUUID(), asset = asset();
    assertUnknown(() -> owner.checkUpload(missing, TENANT, USER));
    assertUnknown(() -> owner.complete(missing, asset, TENANT, USER));
    assertUnknown(() -> owner.reject(missing, TENANT, USER, "SIZE_INVALID"));
    assertThat(db.queryForObject("select count(*) from " + TABLE, Long.class)).isZero();
  }

  private UUID insertLegacyStaged(UUID asset) {
    UUID legacy = UUID.randomUUID();
    db.update("insert into " + TABLE + """
         (handoff_id, tenant_id, subject_id, issued_at, state, observation_version, asset_id, completed_at, legacy)
         values (?, ?, ?, ?, 'STAGED', 1, ?, ?, true)
        """, legacy, TENANT, USER, Timestamp.from(START), asset, Timestamp.from(START));
    return legacy;
  }

  @Test void d089_12_residualReceiptCannotBeReplayedAndIsReclaimedAtRetention() {
    UUID asset = asset(), legacy = insertLegacyStaged(asset);
    assertUnknown(() -> owner.result(legacy, TENANT, USER));
    assertUnknown(() -> owner.checkUpload(legacy, TENANT, USER));
    assertUnknown(() -> owner.complete(legacy, asset, TENANT, USER));
    assertUnknown(() -> owner.reject(legacy, TENANT, USER, "SIZE_INVALID"));
    at(86399).issue(UUID.randomUUID(), TENANT, USER);
    assertThat(db.queryForObject("select asset_id from " + TABLE + " where handoff_id=? and legacy and observation_version=1",
        UUID.class, legacy)).isEqualTo(asset);
    at(86400).issue(UUID.randomUUID(), TENANT, USER);
    assertThat(db.queryForObject("select count(*) from " + TABLE + " where handoff_id=?", Long.class, legacy)).isZero();
  }
}
