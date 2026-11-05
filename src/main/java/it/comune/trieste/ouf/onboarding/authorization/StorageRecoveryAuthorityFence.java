package it.comune.trieste.ouf.onboarding.authorization;

import java.time.Instant;

/** Trusted current-authority verifier, independent of the owner/Authorization restore set.
 * Must validate current installation/policy generation and hold its revocation fence until
 * the calling transaction commits. No remote I/O or cached allow decisions under SQL locks.
 * No production implementation is installed. Restored rows are not evidence for this port.
 */
public interface StorageRecoveryAuthorityFence {
  record Proof(String installation,String tenant,String bundleId,long bundleVersion,long generation,
               Instant issuedAt,Instant expiresAt,boolean revoked) {}
  Proof lockCurrent();
}
