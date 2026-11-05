package it.comune.trieste.ouf.onboarding.application;

import java.util.UUID;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Created by trusted startup, never hydrated from a backup or client request.
 * A fresh instance requires fresh platform attestation; it does not issue authority.
 * Keep one instance for the lifetime of the recovery worker. Do not rotate it to retry.
 */
final class StorageRecoveryRuntime {
  private final String installationRef;
  private final UUID sessionId;
  private final Instant databaseStartedAt;
  private StorageRecoveryRuntime(String installationRef,JdbcClient db) {
    if(installationRef==null || !installationRef.matches("[A-Za-z0-9_.:/-]{1,256}"))
      throw new IllegalArgumentException("Trusted installation reference required");
    this.installationRef=installationRef; this.sessionId=UUID.randomUUID();
    this.databaseStartedAt=databaseStartedAt(db);
  }
  static StorageRecoveryRuntime start(String installationRef,JdbcClient db) { return new StorageRecoveryRuntime(installationRef,db); }
  static Instant databaseStartedAt(JdbcClient db) { return db.sql("select pg_postmaster_start_time()").query(OffsetDateTime.class).single().toInstant(); }
  String installationRef() { return installationRef; }
  UUID sessionId() { return sessionId; }
  Instant databaseStartedAt() { return databaseStartedAt; }
}
