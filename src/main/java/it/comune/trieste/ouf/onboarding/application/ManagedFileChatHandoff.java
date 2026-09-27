package it.comune.trieste.ouf.onboarding.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** A bounded, ephemeral pointer to an already persisted managed-file asset. */
@Service
public class ManagedFileChatHandoff {
  private static final Duration TTL = Duration.ofMinutes(30);
  private static final int LIMIT = 4096;
  private record Receipt(UUID assetId, String subject, Instant expiry) {}
  private final ConcurrentHashMap<UUID, Receipt> pending = new ConcurrentHashMap<>();
  private final ManagedFileService files;

  public ManagedFileChatHandoff(ManagedFileService files) { this.files = files; }

  public void complete(UUID handoffId, UUID assetId, String subject) {
    files.requireOwner(assetId, subject);
    Instant now = Instant.now();
    pending.entrySet().removeIf(e -> !e.getValue().expiry().isAfter(now));
    if (pending.size() >= LIMIT) throw new IllegalStateException("too many pending handoffs");
    Receipt receipt = new Receipt(assetId, subject, now.plus(TTL));
    Receipt previous = pending.putIfAbsent(handoffId, receipt);
    if (previous != null && (!previous.assetId().equals(assetId) || !previous.subject().equals(subject)
        || !previous.expiry().isAfter(now))) throw new SecurityException("handoff already used");
  }

  public Map<String, Object> result(UUID handoffId, String subject) {
    Receipt receipt = pending.get(handoffId);
    if (receipt == null) return Map.of("status", "PENDING");
    if (!receipt.expiry().isAfter(Instant.now())) {
      pending.remove(handoffId, receipt);
      return Map.of("status", "PENDING");
    }
    if (!receipt.subject().equals(subject)) throw new SecurityException("handoff owner mismatch");
    files.requireOwner(receipt.assetId(), subject);
    return Map.of("status", "STAGED", "assetId", receipt.assetId());
  }
}
