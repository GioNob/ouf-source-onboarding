# Authenticated request approval and restricted PostgreSQL resolver

This candidate adds an explicitly constructed server adapter, not a controller,
Spring bean, runtime grant or operational admission. The existing outcome resolver
remains the only producer of a terminal xid proof. The earlier synthetic approval
fixture remains available for isolated protocol tests.

## Exact HUMAN request

`StorageRecoveryHumanApproval.approve` requires POST, the existing server write
proof and the actual `TrustedJwtAuthenticationToken` produced by OUF IAM. It
compares the trusted request principal with that token and the configured
issuer/audience/installation/tenant. Headers and capability attributes cannot
create approval. The adapter checks bearer issue/expiry, HUMAN actor, absence of
a service identity, authentication age below five minutes and the fresh local
policy. It derives actor and decision references itself. The caller supplies only
command UUID, lease UUID/digest/generation, reason and the exact revoked-next proof.

The new capability is `installation.storage.authority.reconcile`, EXECUTE, HUMAN
only, same required scope. It is not registered or granted by this change. The
resource is `installation-storage-authority`, with configured installation and
tenant, SECURITY_SENSITIVE detail, RECONCILE_AUTHORITY action, reason and the exact
request fingerprint. The shared policy evaluator applies scope, grants, DENY and
assurance constraints; no private evaluator is introduced.

An approval lasts at most 30 seconds and is capped by bearer expiry and HUMAN
freshness. It retains immutable verified identity, policy and command facts, not
the raw token or HTTP request. It is never persisted as a reusable authority. Each
attempt requires a fresh authenticated request; an already completed receipt is
historical readback, not a renewed approval or admission. Successful reconciliation
persists the server-derived actor, reason and policy decision in the existing
checksummed receipt. Failed requests do not introduce a new durable audit subsystem.

## Local revocation boundary

Before final mutation, current approval checks the exact active snapshot and
re-evaluates time-sensitive policy. A policy swap after SQL preflight therefore
denies resolution with the owner latch retained. Under the short file guard,
Prepared calls ApprovalLease.seal(), which obtains the SDK's nonwaiting
`tryLease(expectedSnapshot)` publication fence. It performs no SQL/network I/O.
The SDK refresh path cannot acknowledge a policy swap while that lease is held:
it returns POLICY_PUBLICATION_CONTENDED. After close it can publish the revocation.
The policy fence is acquired only for the final file mutation, never over SQL
preflight or connection acquisition. Existing legacy ApprovalLease implementations
retain a default seal operation; the authenticated adapter supplies the real fence.

This serializes **local JVM** publication. It does not prove distributed revocation
delivery, IdP session revocation, a fresh remote ACTIVE pointer, or cross-process
policy authority. Current SDK staleness/distribution limits remain release gates.
Every caller must close Approved/Prepared. A leaked publication lease can deny
future refresh until close/process exit; its current() expires at 30 seconds and
does not unlock an in-flight mutation automatically. No waiting publication queue
or implicit retry is added. Other consumers retain their pinned SDK sources.

## Finite resolver connection profile

`RecoveryPostgresDataSource.open` creates a separate caller-owned Hikari pool:
one connection, zero minimum idle, 1,000ms acquisition, 500ms validation, asynchronous
initial connection, read-only connections; pgJDBC connect 2s, login 3s, socket 2s,
cancel-signal 1s. Server options set statement 2s, lock 1s and idle-in-transaction
5s. The existing resolver also enforces statement/network limits. Hikari may retry
physical connection creation internally while a caller waits, but each caller's
pool wait is finite; there is no recovery-operation retry or budget renewal.

Only one literal IPv4 host and explicit database/port are accepted: no URL query
override, DNS wait or multi-host fallback. TLS mode is explicitly verify-full or
disabled; qualification uses disabled TLS on disposable loopback. TLS certificates,
remote networking, DNS names, IPv6 and failover need separate qualification. The
factory never replaces the exact Spring-bound owner DataSource, provisions roles
or issues grants. Close the pool at its owning component's shutdown.

The CI-only resolver role has LOGIN and no superuser/createdb/createrole/replication/
bypassrls, no application-schema privileges and only the additional EXECUTE grant
on pg_catalog.pg_control_system(). PostgreSQL's normal PUBLIC catalogue/function
and database defaults remain; this is a minimal **additional-grant** profile for
the qualified baseline, not a complete production privilege audit. No pg_monitor
membership or broad table grant is used. Grant removal must deny resolution and
retain the latch; identity restoration is a fixture action, not automatic repair.

## Qualification scope

Five adapter tests use disposable RSA signatures with the real Nimbus decoder,
issuer/audience validators, OUF converter and bearer bridge. They cover forged
signature/issuer/audience, expired bearer, missing write proof, SERVICE/AI_AGENT,
tenant/scope/assurance/freshness denial, exact binding, revocation ordering and
close/deadline limits. This exercises those components directly, not a deployed
HTTP route, remote IdP, browser confirmation or MCP-to-Gateway end-to-end flow.

Six added native cases cover authenticated reconciliation and receipt binding,
revocation after preflight, restricted-role rollback with authenticated approval,
privilege revocation and committed outcome, pool exhaustion and a real TCP peer
that accepts without replying. Existing real COMMIT faults, prepared transactions,
restart denial and physical regression remain distinct. Two additional SDK tests
cover concurrent publication exclusion and deadline/close behavior.

The operational endpoint, confirmed HUMAN UI, capability registration/grant,
current distributed policy delivery, installation wiring, operational SQL grants,
store custody and governed restart/restore rebind remain open. No MinIO/source-lock
change, target operation, deployment or MiniPET credit is implied.

Primary references: [pgJDBC connection parameters](https://jdbc.postgresql.org/documentation/use/),
[Hikari configuration](https://github.com/brettwooldridge/HikariCP#configuration-knobs-baby),
[PostgreSQL17 control/status functions](https://www.postgresql.org/docs/17/functions-info.html).
