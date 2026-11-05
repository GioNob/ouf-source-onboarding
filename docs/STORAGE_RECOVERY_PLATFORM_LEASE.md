# Platform binding and live logical Authorization rollback

`PlatformStorageRecoveryAuthorityFence` is an explicitly constructed bridge from trusted
platform worker admission to the existing Authorization recovery service. It is not a bean,
HTTP/MCP endpoint, token verifier, new policy owner or grant issuer.

## Existing owners and boundary

| Existing owner/source | Responsibility | Not accepted as current recovery authority |
| --- | --- | --- |
| InstallationConfigurationService and InstallationRuntimeProjectionService | Installation identity, tenant, validated configuration and projections | Restored ACTIVE pointer or configuration revision alone |
| Gateway / ServletAuthorization | Authenticated principal, audience and routing | Client-supplied identity JSON or arbitrary headers |
| Trusted Human Surface / TrustedWriteProof | Protected HUMAN command and correlation | Development delegation or installation configuration permission |
| AuthorizationPolicyRegistry / StorageRecoveryAuthorization | Exact storage capability, live policy, finite decision, versioned delivery and audit | Old allowed bundle restored from backup alone |
| Platform current-authority/custody integration | Independent generation and revocation exclusion; separate writer quiescence | A cached allow, old backup receipt, or restored database row |

The trusted `Binding(installation, tenant, generation)` belongs to worker admission outside
the owner/Authorization restore set. The adapter compares it to the current platform proof.
An installation configuration revision can legitimately roll back to an older validated
revision; it is therefore **not** this independent, monotonic recovery generation. The
adapter does not derive admission from `projectActive`, request payloads or a restored row.

`CurrentAuthority.tryAcquire` is a trusted, nonblocking local port. Its lease must exclude
revocation/generation changes until closed. Remote authentication and authority verification
must happen outside owner transactions; merely fetching or caching a remote allow does not
implement this contract. There is no production provider installed in this change.

The adapter requires an actual transaction and active synchronization. It acquires one
lease per adapter/transaction, validates installation/tenant/admission generation and reuses
the immutable proof for that transaction. The Authorization service still validates policy,
HUMAN freshness, decision generation and expiry at every use. The adapter holds the lease
through commit, releases it after completion on commit or rollback, and detaches/reattaches
it during transaction suspension/resumption. Invalid proofs are released immediately;
absent/contended acquisition denies without waiting or resetting any owner budget.

Changing the external generation invalidates the old admitted worker, including fresh
preparation and redelivery attempts. A new worker/admission is a separate trusted platform
action, not something the adapter issues automatically. New admission alone does not grant
reopening or repair a rolled-back budget; normal policy/custody and governed recovery remain
required. Revocation, mismatches and missing authority do not cause automatic retry.

## Native qualification scope

Six PostgreSQL scenarios cover missing transaction/lease, reuse through commit, release on
rollback, invalid admission, concurrent revocation exclusion and REQUIRES_NEW suspension.
The test provider uses an in-process read/write lock and separate heap state. This qualifies
the adapter lifecycle and local concurrency contract, not cross-process or distributed leases.

Two more native scenarios retain the **same JVM/runtime and PostgreSQL postmaster**. They
save populated Authorization policy/decision/audit tables and the owner delivery/action
projection, then revoke the delivered decision. One changes policy and revokes external
authority; the other advances only the independent generation while its policy remains
allowed. A privileged test helper TRUNCATEs/reinserts the saved rows with normal INSERT and
immutability triggers enabled. No production rollback path is added or trigger disabled.

After replay, the old policy and DELIVERED decision are present again. Five operations from
the original worker are denied: fresh prepare, redelivery, drain, reconcile and freeze.
Postmaster/session are unchanged, restored snapshots remain exact, the existing exhausted
128-read budget remains 128, no GET occurs and no asset is published. Receipts retain both
snapshots, runtime/postmaster identifiers and external generation/revocation; logs summarize
each successful scenario.

This is a **logical replay of Authorization tables and the owner delivery/action projection**,
not pg_dump/pg_restore of an entire database, arbitrary owner-data rollback, process restart
of the external provider or independent recovery of every audit stream. Owner upload intent
and its budget are deliberately outside the replayed set. The platform must fence/advance
current authority before a governed logical restore; an unfenced rollback that restores all
local data while leaving external generation unchanged is not detected by this contract.

Production Gateway/THS admission, durable independent authority, distributed exclusion,
all-writer custody, full-owner rollback/budget reconstruction, durable denial audit, UDP/
Ingestion, mixed rollout and governed reopening remain open. No target grant or deployment.
