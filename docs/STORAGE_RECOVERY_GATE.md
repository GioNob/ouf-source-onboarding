# Storage recovery authority boundary

This change prepares and exercises an internal adapter boundary; it does not install a
production authorization adapter, grant authority, expose an endpoint, or revoke writers.
The four-argument `ManagedFileStorageRecovery` constructor now denies every operation.
Only an explicitly supplied trusted `StorageRecoveryGate` can admit the internal protocol.

## Contract basis

The frozen Cross-Module Alignment Matrix v1.7 assigns policy decisions to Authorization,
fine-grained enforcement to backend owners, and protected human operations to THS.
`docs/PET_TRACEABILITY.md` delegates production backup/restore to platform operations.
Managed profile recovery capabilities and installation configuration capabilities do not
confer authority over the global storage singleton. No existing capability is repurposed.

## Internal boundary

The adapter must lock current durable authority and custody evidence in the **same short
PostgreSQL transaction** as the owner transition. Revocation and evidence changes must
conflict with this lock. The order is storage control, gate evidence, upload intent.
The adapter must not perform remote I/O or return a cached allow decision under those locks.
Before production wiring, the platform must define the policy resource, grant issuance,
trusted principal/THS and Gateway path, durable revocation, custody scope covering every
writer, audit storage and restart/restore revalidation. None is supplied by a Java caller.

The owner validates the fixed global scope `onboarding:managed-file-storage`, action,
epoch, non-revoked decision, evidence references and a positive finite authority window
of at most 24 hours, against the database clock. Drain may precede quiescence; reconcile
and freeze require current quiescence evidence. The evidence record is an internal
adapter result, never a client-supplied proof or a signature verifier.

Reconciliation checks and locks the evidence before reserving the existing durable
128-read/24-hour intent budget. It persists READ_RESERVED, commits, and performs GET
without a transaction. Before ACK it locks and revalidates the evidence and requires
the **entire binding to equal the reservation binding**. Revocation, expiry, replacement
decision/session, changed custody or epoch block late publication. A new legitimate
attempt consumes the same intent budget and does not repeat PUT.

The adapter persists successful transition/replay audit entries in the same transaction;
an audit failure rolls back the transition. A failed GET leaves its READ_RESERVED entry
and budget consumption durable. Denied-command audit and production incident handling
remain platform integration requirements. There is no reopen method; fresh authority
alone does not change FROZEN to ACTIVE or bump the owner epoch.

## Synthetic qualification

`StorageRecoveryLabGate` exists only under `src/test`: synthetic PostgreSQL tables,
shared row locks and transaction-bound audit. It is not a migration, grant API or Spring
bean. Native tests cover missing authority, scope/action/generation/window mismatch,
unproven custody, revocation while GET is paused, replacement authority, custody loss,
expiry during I/O, audit rollback, concurrency and blocked reopening.

The physical fixture starts its synthetic authority without quiescence, and marks it
quiescent only after the existing orchestration has stopped all four fixture storage
roles, restricted the same S3 identity to Read/List, restarted them and verified three
actual PUT denials. The fixture authority and audit tables are included in the named
PostgreSQL recovery snapshot and verified by independent artifact readback. This is
evidence for the fixture identity and owner only, not proof of all deployment writers.

UDP/Ingestion, mixed rollout, target identity lifecycle, governed reopening, operational
binding and representative RPO/RTO remain open. MinIO/source-lock, release gates,
real-object counts and target permissions are unchanged.
