package it.comune.trieste.ouf.onboarding.installation;

import it.comune.trieste.ouf.onboarding.authorization.StorageRecoveryAuthorityFence.Proof;
import java.time.Instant;
import java.util.UUID;

/** Internal recovery port, NOT an authorization issuer or an operator endpoint.
 * A production gate must authenticate the decision and reconcile the exact owner outcome
 * against the admitted database, excluding all old owner transactions through close.
 * A PID, elapsed time, unlocked file or restored database row is not such evidence.
 */
public final class PlatformAuthorityReconciliation {
  private PlatformAuthorityReconciliation() {}
  public enum State { CURRENT, CONTENDED, RECONCILE_REQUIRED, REVOKED, EXPIRED, FENCED, UNAVAILABLE }
  /** Safe diagnostic projection only. CURRENT does not admit work or prove readiness. */
  public record Observation(State state,long generation,UUID leaseId,String leaseSha256) {}
  public enum Outcome { COMMITTED, ROLLED_BACK }
  public record Request(UUID commandId,UUID leaseId,String leaseSha256,long expectedGeneration,
                        String actorRef,String reasonCode,String decisionRef,Proof revokedNext) {}
  public record Evidence(String requestSha256,String databaseIdentity,String ownerEvidenceRef,
                         Outcome outcome,Instant verifiedAt,Instant validUntil) {}
  public record Receipt(String schema,Request request,Evidence evidence,Instant recordedAt) {}
  public interface Gate {
    /** Bounded nonwaiting acquisition. Null denies. No retry, network wait or sleep under
     * the store guard. Any unavailable, unresolved, restored or ambiguous owner denies.
     * Must prevent late commits, authorize this exact request and hold exclusion to close.
     */
    Lease tryAcquire(Request request);
  }
  public interface Lease extends AutoCloseable {
    /** Fresh check of authority AND held owner exclusion, including just before latch removal. */
    Evidence current();
    @Override void close();
  }
}
