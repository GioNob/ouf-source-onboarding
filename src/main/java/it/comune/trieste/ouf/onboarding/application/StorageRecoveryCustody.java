package it.comune.trieste.ouf.onboarding.application;

import java.util.UUID;

/** Trusted platform custody port. Same owner transaction; no network I/O under locks.
 * Implementations must lock current writer-custody evidence until commit. No production
 * verifier is installed by this change. An authority decision alone is not custody.
 */
interface StorageRecoveryCustody {
  record Proof(String reference,UUID runtimeSessionId,boolean quiescent) {}
  Proof lock(String installation,UUID runtimeSessionId,long epoch);
}
