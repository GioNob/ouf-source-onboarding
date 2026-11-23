package it.comune.trieste.ouf.onboarding.application;

import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ManagedFileChatHandoff {
  private static final String TABLE = "ouf_onboarding.managed_file_handoff";
  private final ManagedFileService files;
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final String publicOrigin;
  private final int waitSeconds;
  private final int retentionSeconds;

  @Autowired
  public ManagedFileChatHandoff(ManagedFileService files, JdbcTemplate db, TransactionTemplate tx,
      @Value("${ouf.authorization.ths.public-origin:}") String publicOrigin,
      @Value("${ouf.onboarding.wait-max-seconds}") int waitSeconds,
      @Value("${ouf.onboarding.handoff-retention-seconds}") int retentionSeconds) {
    this(files, db, tx, publicOrigin, waitSeconds, retentionSeconds, Clock.systemUTC());
  }

  ManagedFileChatHandoff(ManagedFileService files, JdbcTemplate db, TransactionTemplate tx,
      String publicOrigin, int waitSeconds, int retentionSeconds, Clock clock) {
    if (waitSeconds < 1 || waitSeconds > 3600 || retentionSeconds < 1)
      throw new IllegalArgumentException("waitMaxSeconds must be 1..3600 and handoffRetentionSeconds positive");
    this.files = files;
    this.db = db;
    this.tx = tx;
    this.publicOrigin = publicOrigin;
    this.waitSeconds = waitSeconds;
    this.retentionSeconds = retentionSeconds;
    this.clock = clock;
  }

  private record Receipt(UUID id, Instant issued, Instant until, String state, long version,
                         UUID asset, String code) {
    static Receipt read(ResultSet rs, int row) throws SQLException {
      return new Receipt(rs.getObject("handoff_id", UUID.class), rs.getTimestamp("issued_at").toInstant(),
          rs.getTimestamp("wait_until").toInstant(), rs.getString("state"), rs.getLong("observation_version"),
          rs.getObject("asset_id", UUID.class), rs.getString("code"));
    }
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  public Map<String,Object> issue(UUID commandKey, String tenant, String subject) {
    String base = ThsPublicOrigin.base(publicOrigin);
    reclaim();
    return tx.execute(ignored -> {
      Instant issued = now();
      db.update("insert into " + TABLE + """
           (handoff_id, tenant_id, subject_id, command_key, issued_at, wait_until, state, observation_version, legacy)
           values (?, ?, ?, ?, ?, ?, 'PENDING', 1, false) on conflict (command_key) do nothing
          """, UUID.randomUUID(), tenant, subject, commandKey, Timestamp.from(issued),
          Timestamp.from(issued.plusSeconds(waitSeconds)));
      var rows = db.query("select * from " + TABLE + " where command_key=? and tenant_id=? and subject_id=?",
          Receipt::read, commandKey, tenant, subject);
      if (rows.isEmpty()) throw conflict("ONB_HANDOFF_COMMAND_CONFLICT");
      var receipt = rows.getFirst();
      // This is the original command acknowledgement. Only /handoff observes later transitions.
      return Map.of("handoffId", receipt.id(), "pickerUrl", base + "/trusted-human/managed-files/?handoff=" + receipt.id(),
          "issuedAt", receipt.issued().toString(), "waitUntil", receipt.until().toString(),
          "state", "PENDING", "observationVersion", "1");
    });
  }

  public void complete(UUID id, UUID asset, String tenant, String subject) {
    finish(id, tenant, subject, "STAGED", asset, null);
  }

  /** Reject a stale or foreign URL before streaming to the Gateway. Finish checks again after the upload. */
  public void checkUpload(UUID id, String tenant, String subject) {
    Receipt row = requireIssued(id, tenant, subject, false);
    switch (row.state()) {
      case "PENDING" -> {
        if (!now().isBefore(row.until())) throw conflict("ONB_HANDOFF_EXPIRED");
      }
      case "STAGED" -> { } // Finish reconciles the actual asset ID on replay.
      case "EXPIRED" -> throw conflict("ONB_HANDOFF_EXPIRED");
      default -> throw conflict("ONB_HANDOFF_OUTCOME_CONFLICT");
    }
  }

  public void reject(UUID id, String tenant, String subject, String code) {
    if (!"FORMAT_UNSUPPORTED".equals(code) && !"SIZE_INVALID".equals(code))
      throw new IllegalArgumentException("unsupported file rejection code");
    finish(id, tenant, subject, "REJECTED", null, code);
  }

  private void finish(UUID id, String tenant, String subject, String state, UUID asset, String code) {
    reclaim();
    boolean expired = tx.execute(ignored -> {
      Receipt row = requireIssued(id, tenant, subject, true);
      if ("STAGED".equals(state)) files.requireTenantOwner(asset, tenant, subject);
      Instant decision = now();
      if (expire(row, decision) || "EXPIRED".equals(row.state())) return true;
      if ("PENDING".equals(row.state())) {
        db.update("update " + TABLE + """
             set state=?, asset_id=?, code=?, completed_at=?, observation_version=observation_version+1
             where handoff_id=?
            """, state, asset, code, Timestamp.from(decision), id);
      } else if (!state.equals(row.state()) || !Objects.equals(asset, row.asset()) || !Objects.equals(code, row.code())) {
        throw conflict("ONB_HANDOFF_OUTCOME_CONFLICT");
      }
      return false;
    });
    // Commit the observed expiry even when this request must return a conflict.
    if (expired) throw conflict("ONB_HANDOFF_EXPIRED");
  }

  private Receipt requireIssued(UUID id, String tenant, String subject, boolean lock) {
    var rows = db.query("select * from " + TABLE + " where handoff_id=? and tenant_id=? and subject_id=?"
        + " and not legacy and not receipt_retired" + (lock ? " for update" : ""), Receipt::read, id, tenant, subject);
    if (rows.isEmpty()) throw conflict("ONB_HANDOFF_UNKNOWN");
    return rows.getFirst();
  }

  private boolean expire(Receipt row, Instant decision) {
    if (!"PENDING".equals(row.state()) || decision.isBefore(row.until())) return false;
    db.update("update " + TABLE + """
         set state='EXPIRED', completed_at=wait_until, observation_version=observation_version+1 where handoff_id=?
        """, row.id());
    return true;
  }

  public Map<String,Object> result(UUID id, String tenant, String subject) {
    return tx.execute(ignored -> {
      Receipt row = requireIssued(id, tenant, subject, false);
      boolean expired = false;
      if ("PENDING".equals(row.state()) && !now().isBefore(row.until())) {
        row = requireIssued(id, tenant, subject, true);
        expired = expire(row, now());
      }
      String state = expired ? "EXPIRED" : row.state();
      var result = new LinkedHashMap<String,Object>();
      result.put("status", state);
      result.put("state", state);
      result.put("observationVersion", Long.toString(row.version() + (expired ? 1 : 0)));
      result.put("issuedAt", row.issued().toString());
      result.put("waitUntil", row.until().toString());
      if (row.asset() != null) {
        files.requireTenantOwner(row.asset(), tenant, subject);
        result.put("assetId", row.asset());
      }
      if (row.code() != null) result.put("code", row.code());
      return result;
    });
  }

  // Indexed, bounded maintenance on picker writes. No timer or locks across HTTP requests.
  private void reclaim() {
    Instant instant = now();
    db.update("update " + TABLE + """
         set state='EXPIRED', completed_at=wait_until, observation_version=observation_version+1
         where handoff_id in (select handoff_id from ouf_onboarding.managed_file_handoff
           where state='PENDING' and wait_until<=? order by wait_until limit 64 for update skip locked)
        """, Timestamp.from(instant));
    Timestamp cutoff = Timestamp.from(instant.minusSeconds(retentionSeconds));
    db.update("delete from " + TABLE + """
         where handoff_id in (select handoff_id from ouf_onboarding.managed_file_handoff
           where legacy and not receipt_retired and completed_at<=? order by completed_at limit 64 for update skip locked)
        """, cutoff);
    // Keep the original command acknowledgement available for idempotent issuance.
    db.update("update " + TABLE + """
         set receipt_retired=true, asset_id=null, code=null
         where handoff_id in (select handoff_id from ouf_onboarding.managed_file_handoff
           where not legacy and not receipt_retired and completed_at<=?
           order by completed_at limit 64 for update skip locked)
        """, cutoff);
  }

  private static DomainFailure conflict(String code) {
    return new DomainFailure(HttpStatus.CONFLICT, code, code);
  }
}
