# Durable current authority on one protected POSIX host

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
bound. Process death releases the OS lock; it does not renew a decision, grant or budget.

## Native qualification

Eight added cases use PostgreSQL and distinct real JVMs on the Linux runner:

- Reopen/read after process restart, durable revocation and exact idempotent publication.
- A child publisher is denied inside the actual owner's post-audit/pre-commit interval;
  the same publication succeeds after commit and the old worker is denied afterwards.
- A child holds the guard; the owner is denied until the child is forcibly killed. The OS
  releases the lock and the original still-valid authority remains unchanged, without regrant.
- Publisher halts with exit86 after durable publication but before a response. A reopened
  provider reconciles the exact result. Logical Authorization replay restores DELIVERED,
  but fresh prepare, redelivery and owner use remain denied, with the same postmaster.
- Two publishers using one expected generation have one winner and one denial.
- Corruption, oversize, absent state, reprovision attempts and symlink state are rejected.
- A synthetic incomplete pending file never authorizes; a stale instance rejects guard replacement.
- Expired proposals, publication within an owner transaction and insecure permissions deny.

Receipts and child logs are archived. The pending-file test is a constructed interrupted
state, not a claimed kill at a precisely instrumented write boundary. JVM halt/kill proves
process-crash recovery, not host power-loss survival. The previous physical common-cut and
independent archive regressions remain separate and still use their sequential file fixture.

## Remaining integration

No operational path is installed. Trusted Gateway/THS admission/publication, protected external
mount and guard custody, platform audit, production startup/recovery tooling, multiple-host
revocation, provider-storage rollback, full-owner budget reconstruction and writer custody
remain open. No permission, admission, merge, target operation or MinIO/source-lock change.
