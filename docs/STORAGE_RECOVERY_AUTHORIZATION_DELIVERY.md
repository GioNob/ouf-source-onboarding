# Storage recovery decision delivery — same-database integration

See [platform lease binding](STORAGE_RECOVERY_PLATFORM_LEASE.md) for the explicit current-authority
adapter, transaction completion lifecycle and scoped logical Authorization rollback qualification.

`StorageRecoveryAuthorization` connects the existing Authorization policy registry to
the private storage recovery owner. `PolicyStorageRecoveryGate` consumes its delivered
decision inside the owner transaction. Neither class is a Spring bean or an endpoint;
no production wiring, capability registration or grant is performed automatically.

## Distinct capability contract

The candidate descriptor is `installation.storage.recovery`, operation `EXECUTE`, same
required scope, HUMAN only. It is deliberately absent from bootstrap and installation
configuration descriptor lists. The active bundle must declare this exact descriptor;
a matching name with weaker scope/actor semantics is denied. The server constructs resource
type `installation-storage-recovery`, trusted installation ID and platform tenant, with
attributes `storageOwner=onboarding`, `recoveryAction` and `detailLevel=SECURITY_SENSITIVE`.
The existing SDK evaluates the actual active bundle and scoped grants. Existing per-file
profiling/configuration permissions do not authorize this resource.

An explicitly trusted HUMAN principal and authentication no older than15minutes are required.
The requested decision deadline is finite, at most15minutes from preparation, and cannot
exceed that authentication freshness window. Every owner use reevaluates the original
principal against the current pinned policy under lock. Gateway/THS authentication/session
validation remains a required integration boundary; this service does not parse tokens or
accept arbitrary JSON as trusted identity.

## Durable preparation, delivery and revocation

MigrationV40 creates initially empty decision/audit tables and a singleton projection at
version0 with no decision. It inserts no capabilities or grants. A prepared command binds
trusted installation/tenant, HUMAN context, worker runtime, storage epoch, actions and
absolute deadline. Canonical command hashes sort set-valued identity/actions to survive
process serialization order. The same command and binding returns the same decision and
original expiry; changed input under that key is refused.

Delivery locks the projection, requires the expected version and current epoch/policy,
then atomically marks the decision DELIVERED, advances the projection and writes audit.
Repeating delivery after a lost response returns the same version when that exact decision
is still current. It cannot publish an older or revoked decision. Replacement revokes the
previous decision permanently. One of two concurrent deliveries with the same expected
version wins; the other receives a stale-version conflict. Conflicts are not blind-retried.

Explicit revocation requires a fresh HUMAN decision for recoveryAction=REVOKE. It durably
revokes the requested decision and clears its current projection atomically. Repetition
does not reopen it or increment versions again. SQL guards prevent rebinding/deleting
decision history, regressing projection versions or altering audit rows.

## Atomic policy and owner enforcement

This implementation is scoped to the existing **same PostgreSQL database** deployment of
Authorization and the owner. Lock order is storage control → shared policy activation lock
741093 → projection → decision → custody/intent. The registry's existing activation uses
the exclusive741093 lock, so policy activation conflicts with a consuming owner transaction
until it commits. Explicit revocation conflicts through projection/decision row locks.

There is no remote I/O under these locks. GET remains outside transactions. After GET,
the owner checks the active policy generation, decision/revocation, runtime/installation,
epoch, custody and expiry again. A policy change or revocation during GET blocks publication.
The gate writes owner action audit in the same transaction as ACK/assets/receipt. Existing
12s transaction,2s lock,5s statement limits and final expiry check remain in force. The original
128-read/24-hour intent budget is never reset by preparing or delivering another decision.

`StorageRecoveryCustody` is a separate mandatory trusted verifier for reconcile/freeze.
The tests use locked fixture custody evidence; a production all-writer verifier is still
absent. A real policy decision without custody can drain but cannot reconcile/freeze.

## Qualification and remaining boundaries

Native PostgreSQL/SeaweedFS tests exercise actual registry evaluation, command replay after
lost responses, delivery CAS and concurrency, immutable history, explicit and policy-based
revocation during GET, policy activation exclusion, freshness/actor checks, descriptor drift,
missing custody and unchanged exhausted upload budgets. No test grants are deployed.

The physical restore regression now uses populated V40 decisions, delivered projection,
delivery/action audit and actual policy registry rows. Two fresh JVM sessions prepare and
deliver distinct decisions; the second supersedes the first. The named physical cut contains
projection version 2 and the second decision DELIVERED. After the cut, explicit revocation
clears that projection at version 3, a policy version 2 removes its grant, and the independent
current-authority fixture advances to generation 2/revoked. Restoring PostgreSQL returns
the old allowed policy and DELIVERED decision, while the external current fence stays revoked.
Direct fresh preparation, redelivery, drain and freeze are rejected. Withholding the external
fence also rejects preparation/redelivery. Owner, policy and all included audit rows remain
exactly equal to their frozen snapshots after those attempts and storage verification.

`StorageRecoveryAuthorityFence` is mandatory for preparation, delivery and owner consumption.
The default constructor has no verifier and denies. The trusted port binds installation,
tenant, active policy, independent generation and a finite window; a decision cannot outlive
the fence supplied at preparation. V41 pins that generation in immutable decision history
and command hashes. Existing V40 rows receive generation 0, which is deliberately unusable.
A generation change rejects old decisions even with the same local policy. The owner gate
checks the current fence again after writing its audit, before transaction completion.

No production implementation of this independent port is installed. Its implementation must
hold revocation exclusion through the consuming transaction's commit without remote I/O
under owner locks. Native component tests use synthetic proofs; the physical test uses a
bounded local file outside all backup/restore directories and sequential revocation. This
qualifies physical restore behavior, not distributed concurrent revocation or logical rollback.
The independent readback verifies populated state, exact restored audit preservation and
post-cut policy/decision revocation, including semantic corruptions with rehashed manifests.

Distributed policy delivery, remote revocation acknowledgement, authenticated Gateway/THS
wiring, approved capability registration, denial audit, complete writer custody, logical
rollback fencing, UDP/Ingestion, mixed rollout and governed reopening remain open. The
implementation makes no network-distributed linearizability or target acceptance claim.
