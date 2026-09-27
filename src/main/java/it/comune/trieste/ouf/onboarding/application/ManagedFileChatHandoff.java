package it.comune.trieste.ouf.onboarding.application;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Short-lived result pointer for the existing upload capability. */
@Service
public class ManagedFileChatHandoff {
  private final JdbcClient db;
  private final ManagedFileService files;

  public ManagedFileChatHandoff(JdbcClient db, ManagedFileService files) {
    this.db = db;
    this.files = files;
  }

  public void complete(UUID handoffId, UUID assetId, String subject) {
    files.requireOwner(assetId, subject);
    int inserted = db.sql("insert into ouf_onboarding.managed_file_chat_handoff(handoff_id,subject_id,asset_id) " +
        "values(:h,:s,:a) on conflict(handoff_id) do nothing")
        .param("h", handoffId).param("s", subject).param("a", assetId).update();
    if (inserted == 0) {
      var existing = db.sql("select asset_id from ouf_onboarding.managed_file_chat_handoff " +
          "where handoff_id=:h and subject_id=:s and asset_id=:a and expires_at>now()")
          .param("h", handoffId).param("s", subject).param("a", assetId).query().listOfRows();
      if (existing.isEmpty()) throw new SecurityException("handoff already used");
    }
  }

  public Map<String, Object> result(UUID handoffId, String subject) {
    var rows = db.sql("select asset_id from ouf_onboarding.managed_file_chat_handoff " +
        "where handoff_id=:h and subject_id=:s and expires_at>now()")
        .param("h", handoffId).param("s", subject).query().listOfRows();
    if (rows.isEmpty()) return Map.of("status", "PENDING");
    UUID assetId = (UUID) rows.get(0).get("asset_id");
    files.requireOwner(assetId, subject);
    return Map.of("status", "STAGED", "assetId", assetId);
  }
}
