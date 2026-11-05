# Durable current authority on one protected POSIX host

The [PostgreSQL outcome binding](STORAGE_RECOVERY_POSTGRES_OUTCOME.md) adds an explicit bound
store mode and a real xid8 resolver. The legacy/unbound cases below remain component coverage.
Authenticated platform approval and operational wiring are still absent.

`FilePlatformRecoveryAuthority` implements the existing platform lease port using a
protected local directory outside the owner/Authorization backup and restore set. It is
explicitly constructed, not a Spring bean, HTTP/MCP endpoint, policy issuer or installed
runtime grant. The caller must supply already-approved platform proofs; Gateway/THS
authentication and Authorization policy evaluation remain with their existing owners.

## Persistence and exclusion

The directory must be canonical, private (0700), and on an admitted local POSIX filesystem
type: ext4, xfs, tmpfs or overlay. Guard/state files are regular, owner-matching and0600;
symbolic links and replaced guard identity are denied. The qualified runner filesystem is
recorded by the native receipt. Filesystem type checks do not substitute for production
mount/host qualification; NFS/CIFS, shared multi-host mounts and Windows are not admitted.

One-time provisioning requires an empty directory and creates the permanent guard with
CREATE_NEW. Incomplete provisioning is left blocked for explicit reconciliation. Ordinary
construction, reads and publication never recreate missing current authority. The guard
must never be removed/replaced while any process may use the store. Restoring this external
directory itself, malicious privileged modification and guard replacement coordinated across
new processes are outside the proof; protected path custody is a deployment requirement.

Readers and publishers acquire the **same exclusive OS file lock with tryLock**, with no
wait/retry loop. A recovery transaction owns one lease until after commit/rollback through
`PlatformStorageRecoveryAuthorityFence`. Another JVM cannot publish revocation while that
lease is held. Recovery-control operations serialize per store; nested independent transactions
may be denied as contended. This deliberate control-plane tradeoff avoids unsafe overlapping
JVM locks; it is not a shared read lock or a distributed lease.

Before returning any read lease, the provider forces a private `lease.active` latch and its
parent directory. Known transaction commit/rollback clears and forces the latch before
releasing the OS lock. Process death or an UNKNOWN completion leaves it in place. Thus a
released OS lock cannot by itself authorize another process while a previously sent PostgreSQL
COMMIT may still be unresolved. Readers and publishers reject any existing latch after restart.
No automatic timeout, PID check, new generation or reprovision clears it. The internal governed
reconciliation protocol below requires a separately trusted owner-outcome/exclusion gate;
its operational gate implementation is not installed. This favors a durable pause over a
possible late commit crossing revocation.

Current state is a versioned, SHA256-checked JSON envelope of at most4096bytes. Shape, types,
duplicate/trailing fields, installation/tenant references, generation and finite time window
are validated; no cached ALLOW fallback exists. Missing, corrupted, expired or unavailable
state denies. Local file operations have bounded payloads; lock acquisition does not wait,
but this does not promise a hard latency bound under a stalled kernel/filesystem.

Publication is outside owner transactions. It requires expected generation and exactly the
next generation, preserves installation/tenant and accepts an identical retry of the exact
proof without extending its expiry. A conflicting old generation is rejected. The already-
approved proof is written to a single private pending file, forced to disk, atomically renamed
to current.json, and the parent directory forced. Pending bytes never authorize; an interrupted
pending file can be overwritten only under the permanent guard. Missing atomic-move/fsync
support fails; an uncertain publication requires reconciliation of the exact proof.

This is a current-state projection, not a replacement for the platform's approval/revocation
audit. Later generations may reflect a separately approved new authorization; the provider
does not decide whether to issue it. Old worker admission and decisions remain generation-
bound. Process death releases the OS lock but retains the unresolved latch; it does not renew
a decision, grant or budget. The adapter invokes `abandon` on UNKNOWN completion; providers
without this operation retain their exclusion and fail rather than treating UNKNOWN as rollback.

## Native qualification

Nine added cases use PostgreSQL and distinct real JVMs on the Linux runner:

- Reopen/read after process restart, durable revocation and exact idempotent publication.
- A child publisher is denied inside the actual owner's post-audit/pre-commit interval;
  the same publication succeeds after commit and the old worker is denied afterwards.
- A child holds the guard; the owner is denied. After the child is forcibly killed, the
  durable unresolved latch continues blocking owner and publisher even after provider restart.
- Publisher halts with exit86 after durable publication but before a response. A reopened
  provider reconciles the exact result. Logical Authorization replay restores DELIVERED,
  but fresh prepare, redelivery and owner use remain denied, with the same postmaster.
- Two publishers using one expected generation have one winner and one denial.
- Corruption, oversize, absent state, reprovision attempts and symlink state are rejected.
- A synthetic incomplete pending file never authorizes; a stale instance rejects guard replacement.
- Expired proposals, publication within an owner transaction and insecure permissions deny.
- An injected UNKNOWN transaction-completion notification preserves the latch across reopen;
  no actual database/network failure is claimed for that injected case.

Receipts and child logs are archived. The pending-file test is a constructed interrupted
state, not a claimed kill at a precisely instrumented write boundary. JVM halt/kill proves
process-crash recovery, not host power-loss survival. The previous physical common-cut and
independent archive regressions remain separate and still use their sequential file fixture.

## Observation and governed reconciliation

`inspect(binding)` is a bounded read-only projection under the same nonwaiting guard. It
returns CURRENT, CONTENDED, RECONCILE_REQUIRED, REVOKED, EXPIRED, FENCED or UNAVAILABLE.
CURRENT is not readiness, an ALLOW, a traffic lease or proof of valid Authorization policy.
Contention and invalid files never become healthy. Installation/tenant mismatch hides fields;
the unresolved marker UUID and digest correlate one pause across restarts, without exposing
the authority bundle, actor, path or raw errors. Inspection never writes files or claims a lease.
Construction can still fail for an unavailable/unqualified store; operational callers must
project that failure as unavailable rather than healthy. No MCP/HTTP endpoint is installed.

`PlatformAuthorityReconciliation` defines an internal trusted gate contract, not a verifier
of arbitrary operator JSON. Its Gate must authenticate an exact finite decision, establish the
admitted database identity, reconcile the exact lease's COMMITTED or ROLLED_BACK outcome and
exclude every old owner transaction, including a possibly sent COMMIT, until close. Unresolved
or unknown outcomes deny. The gate uses bounded nonwaiting acquisition: no network retries,
sleep or remote operator interaction under the store guard. A production implementation must
also cover restored databases, prepared transactions and custody loss; none is inferred from
a PID, time, an unlocked file or an old receipt. The bound-mode PostgreSQL resolver implements
the transaction-outcome portion; authenticated approval and the operational gate wiring remain open.

The provider checks the full request fingerprint, safe actor/reason/decision references, exact
marker UUID/digest/generation, installation/tenant, and fresh evidence with at most five minutes
validity. It checks the same held evidence again just before completing. Requests must publish
exactly the next generation **revoked**. Even expired revoked proofs can be used to complete an
interrupted safety transition; they never grant authority. A separate fresh publication and
admission are required later, and old decisions still fail generation checks.

Under the guard the sequence is: persist a checksummed resolution receipt using private staging,
fsync and atomic rename; persist the revoked generation; recheck the held gate evidence; delete
and fsync the exact latch. Failure or death before deletion retains the pause. A restarted caller
can reconcile the identical command with fresh exclusion/evidence; database identity, outcome
and owner evidence reference must match the first receipt. After completed latch removal,
an exact retry returns that receipt without a new gate or writes to the authority proof; it is
a historical command readback, not renewed approval. A changed command, later generation or
new unresolved lease is rejected. Receipt/current files and their directory are forced again
when reconciling an uncertain prior persistence result. Incomplete staging never authorizes.

Receipts are private, bounded to 8192 bytes and retained by command UUID. Capacity is 128 per
store; exhaustion blocks a new resolution and deletes nothing. There is no automatic pruning,
budget reset or incident acknowledgement/resolution shortcut. This protocol receipt supports
the external platform audit; it does not replace the authoritative HUMAN decision history.
Malformation, ambiguous filesystem effects or lost custody leave recovery blocked for intervention.

Nine further native cases qualify the internal protocol: read-only/redacted observation;
missing, stale, borrowed or unrevoked evidence denial; a real owner transaction still holding
its PostgreSQL control row after injected UNKNOWN; actual rollback followed by separate new
generation/admission while an exhausted 128-read budget stays exhausted; separate-JVM halt
after revoked publication/before latch removal and after completion/before reply; competing
commands; changed intent and newer-lease protection; receipt capacity/corruption; and final
evidence expiry after publication. Tests use a synthetic trusted decision/outcome oracle and
a real PostgreSQL NOWAIT control-row lock. The oracle knows the fixture transaction outcome;
it is NOT a production database outcome resolver or an actual ambiguous network-COMMIT test.
This is governed control-path resumption, not reopening storage traffic or a target deployment.

## Remaining integration

No operational path is installed. Trusted Gateway/THS admission/publication, protected external
mount and guard custody, authenticated owner-outcome/exclusion gate, platform audit, production startup/recovery tooling, multiple-host
revocation, provider-storage rollback, full-owner budget reconstruction and writer custody
remain open. No permission, admission, merge, target operation or MinIO/source-lock change.
