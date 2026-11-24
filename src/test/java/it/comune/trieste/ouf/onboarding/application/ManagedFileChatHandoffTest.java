package it.comune.trieste.ouf.onboarding.application;

import static org.assertj.core.api.Assertions.*;

import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

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
    assertThat(staged).containsEntry("assetId", asset).containsEntry("state", "STAGED").containsEntry("observationVersion", "2").containsEntry("mediaType", "text/csv");
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

  @Test void d089_04_unknownAndForeignAreIndistinguishableAndLegacyBindsFirstWriter() {
    UUID issued = issue(), legacy = UUID.randomUUID(), asset = asset();
    var masked = Map.of("status", "PENDING", "legacy", true);
    owner.checkUpload(legacy, TENANT, USER);
    assertThatThrownBy(() -> owner.checkUpload(issued, "other", USER)).hasMessage("ONB_HANDOFF_UNAVAILABLE");
    assertThat(ManagedMcpObservation.handoff(owner.result(legacy, TENANT, USER))).isEqualTo(masked);
    assertThat(owner.result(issued, "other", USER)).isEqualTo(masked);
    assertThat(owner.result(issued, TENANT, "other")).isEqualTo(masked);
    assertThatThrownBy(() -> owner.reject(issued, "other", USER, "SIZE_INVALID")).hasMessage("ONB_HANDOFF_UNAVAILABLE");
    assertThatThrownBy(() -> owner.complete(issued, asset, "other", USER)).isInstanceOf(DomainFailure.class);
    UUID foreignAsset = (UUID) files.registerOwned(null, "object://fixture/" + UUID.randomUUID(),
        "sha256:" + "b".repeat(64), "text/csv", 42, USER, "retention://fixture", "other").get("asset_id");
    assertThatThrownBy(() -> owner.complete(issued, foreignAsset, "other", USER)).hasMessage("ONB_HANDOFF_UNAVAILABLE");
    assertThat(owner.result(issued, TENANT, USER)).containsEntry("state", "PENDING").containsEntry("observationVersion", "1");
    owner.complete(legacy, asset, TENANT, USER);
    owner.complete(legacy, asset, TENANT, USER);
    assertThat(at(3700).result(legacy, TENANT, USER)).isEqualTo(Map.of("status", "STAGED", "assetId", asset, "legacy", true));
    assertThat(owner.result(legacy, TENANT, "other")).isEqualTo(masked);
    assertThatThrownBy(() -> owner.reject(legacy, TENANT, "other", "SIZE_INVALID")).hasMessage("ONB_HANDOFF_UNAVAILABLE");
    UUID rejection = UUID.randomUUID();
    owner.reject(rejection, TENANT, USER, "FORMAT_UNSUPPORTED");
    assertThat(at(3700).result(rejection, TENANT, USER))
        .isEqualTo(Map.of("status", "REJECTED", "code", "FORMAT_UNSUPPORTED", "legacy", true));
    assertThat(db.queryForObject("select count(*) from " + TABLE +
        " where legacy and wait_until is null and command_key is null", Long.class)).isEqualTo(2L);
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
    UUID key = UUID.randomUUID(), legacy = UUID.randomUUID();
    Map<String,Object> issued;
    try (var first = pickerContext(0)) {
      var before = first.getBean(ManagedFileChatHandoff.class);
      issued = before.issue(key, TENANT, USER);
      before.reject(legacy, TENANT, USER, "SIZE_INVALID");
    }
    try (var second = pickerContext(2700)) {
      var after = second.getBean(ManagedFileChatHandoff.class);
      assertThat(after.issue(key, TENANT, USER)).isEqualTo(issued);
      assertThat(after.result((UUID) issued.get("handoffId"), TENANT, USER))
          .containsEntry("waitUntil", issued.get("waitUntil")).containsEntry("state", "PENDING");
      assertThat(after.result(legacy, TENANT, USER)).containsEntry("code", "SIZE_INVALID");
    }
  }

  @Test void d089_07_retentionRunsFromCompletionAndCannotReopenAnIssuedLink() {
    UUID key = UUID.randomUUID(), legacy = UUID.randomUUID();
    var issued = owner.issue(key, TENANT, USER);
    UUID id = (UUID) issued.get("handoffId");
    at(100).reject(id, TENANT, USER, "SIZE_INVALID");
    at(100).reject(legacy, TENANT, USER, "SIZE_INVALID");
    var lastSecond = at(100 + 86400 - 1);
    lastSecond.issue(UUID.randomUUID(), TENANT, USER);
    assertThat(lastSecond.result(id, TENANT, USER)).containsEntry("state", "REJECTED");
    assertThat(lastSecond.result(legacy, TENANT, USER)).containsEntry("status", "REJECTED");
    var after = at(100 + 86400);
    assertThat(after.issue(key, TENANT, USER)).isEqualTo(issued);
    assertThat(after.result(id, TENANT, USER)).isEqualTo(Map.of("status", "PENDING", "legacy", true));
    assertThat(after.result(legacy, TENANT, USER)).isEqualTo(after.result(id, TENANT, USER));
    assertThatThrownBy(() -> after.reject(id, TENANT, USER, "SIZE_INVALID")).hasMessage("ONB_HANDOFF_UNAVAILABLE");
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
}
