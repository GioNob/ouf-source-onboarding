# Storage recovery decision delivery — same-database integration

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

The existing physical restore regression includes the new V40 tables in snapshots and
requires them to remain ungranted in that drill. It does **not** yet restore populated V40
decision/projection history; that is a separate next qualification. The previous independent
current-authority/restart drill remains valid and is not replaced by a restored local policy.
Same-database policy evaluation does not make a restored old Authorization bundle current:
production wiring must retain the independent restore-generation/current-authority fence.

Distributed policy delivery, remote revocation acknowledgement, authenticated Gateway/THS
wiring, approved capability registration, denial audit, complete writer custody, logical
rollback fencing, UDP/Ingestion, mixed rollout and governed reopening remain open. The
implementation makes no network-distributed linearizability or target acceptance claim.
