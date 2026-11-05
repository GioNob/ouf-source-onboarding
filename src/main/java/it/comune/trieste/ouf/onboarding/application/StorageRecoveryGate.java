package it.comune.trieste.ouf.onboarding.application;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Trusted platform adapter port, not a client DTO or a grant issuer.
 * No production implementation is installed. An adapter must read current durable authority
 * and custody evidence with locks held until the caller's transaction commits, in the same
 * database/transaction as the owner. No network I/O, polling or cached allow decisions here.
 * Revocation and custody changes must conflict with those locks. Lock order: control, gate, intent.
 * Bind current platform attestation to the trusted installation and fresh runtime session.
 * Restored local projections cannot mint/rebind authority; quiescence needs its own current binding.
 */
interface StorageRecoveryGate {
  String SCOPE="onboarding:managed-file-storage";
  enum Action { DRAIN, RECONCILE, FREEZE }
  record Evidence(UUID sessionId, String scope, long epoch, String decisionRef, String quiescenceRef,
                  Instant issuedAt, Instant expiresAt, boolean revoked, boolean quiescent,
                  Set<Action> actions, String installationRef, UUID runtimeSessionId, UUID quiescenceRuntimeSessionId) {
    public Evidence { actions=actions==null?Set.of():Set.copyOf(actions); }
  }
  Evidence lock(Action action, long expectedEpoch, UUID intentId);
  /** Persist the decision/evidence binding and outcome atomically with the owner transition.
   * Failure must roll back the transition. READ_RESERVED survives subsequent I/O failure.
   */
  void record(Evidence evidence, Action action, UUID intentId, String outcome);
}
