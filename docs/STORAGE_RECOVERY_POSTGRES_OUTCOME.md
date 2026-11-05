# PostgreSQL transaction identity and ambiguous COMMIT resolution

This candidate closes the transaction-outcome oracle gap for an admitted PostgreSQL 17
primary on the same server incarnation. It does not install an operator endpoint, HUMAN
approval adapter, runtime grant, new database privilege or automatic recovery scheduler.

## Two implemented steps

1. Bind the durable authority marker to the exact owner's top-level xid8 and admitted
   database identity before returning a lease.
2. Resolve an uncertain completion from PostgreSQL's transaction status, then use the existing
   receipt/revoked-generation/latch-clear protocol. Native tests disrupt real TCP COMMIT traffic.

## Bound store and owner capture

`provisionBound` creates an empty store with a private, checksummed `owner.database` admission
before publishing current authority. Admission records system identifier, database OID and
postmaster start timestamp. The current-state schema is `ouf.platform-storage-authority.pg.v2`;
removing admission metadata cannot downgrade it to the old unbound store. There is no implicit
legacy migration or restart rebind. Legacy stores/tests remain separate component coverage.

`PostgresRecoveryTransactions` requires the exact DataSource resource already bound to the
active Spring owner transaction. A missing/other DataSource or autocommit/read-only connection
denies. It obtains `pg_current_xact_id()` on that same connection and checks the pinned database,
primary/server version and synchronous_commit=on. It never closes the Spring-owned connection.
The private marker includes schema, UUID, authority generation, database identity, xid8 and a
checksum; the complete marker digest still binds the reconciliation command. Marker size is
bounded at 2048 bytes. Missing, malformed or changed identity denies rather than guessing.

The identity observation helper supplies facts for a separately authorized admission; it does
not admit a worker. An old admission is rejected after a PostgreSQL restart, even if the cluster
system identifier and database OID are unchanged. This deliberately requires a future governed
rebind/startup procedure; the code does not infer continuity across restore or failover.

## Resolver and approval boundary

`PostgresRecoveryOutcomeResolver.prepare` reads the exact pending marker under a short guard,
releases that guard, verifies an already-authenticated exact command ApprovalLease, and queries
the admitted PostgreSQL instance on a separate connection. No owner transaction or file guard
is held during that preflight. Statement and socket limits are two seconds; the injected
DataSource must separately have a finite pool/login acquisition timeout. There is no retry.
Administrative identity functions require deployment-owned privileges; none is granted here.

Only `committed` or `aborted` yields an opaque Prepared proof. `in progress` (including prepared
transactions), missing/old discarded status, unknown values, SQL errors or another database/server
incarnation deny. The caller never supplies the outcome, and business rows such as ACTIVE or
DRAINING are not used to infer it. The short proof lasts at most 30 seconds and never beyond
the authenticated approval's finite window (maximum five minutes).

The file provider accepts only this resolver's privately constructed Prepared type for a bound
marker. Under its guard it rechecks the exact marker/request/transaction and current approval,
without additional SQL. A terminal xid cannot later commit again; the file guard excludes other
authority holders until the next revoked generation is durable. Approval revocation exclusion
must remain held until close. A database restart after preflight cannot revive this old admission:
the resulting authority is revoked, and later owner capture still checks the original incarnation.

Receipt replay, saturation, checksums, private permissions and crash behavior retain the existing
contract. Resolution does not publish an active grant, resume traffic, compensate business effects
or reset budgets. A separate authorized generation/admission/decision is required for later work.
The platform still must implement the authenticated ApprovalLease integration; test approvals are
synthetic. The transaction outcome is obtained from real PostgreSQL, not a fixture oracle.

## Native qualification cases

- Compare the marker xid8 with the owner transaction's own xid8; reopen and verify identity;
  reject missing/other DataSource, old generic gate and deleted admission metadata.
- Forward COMMIT to the server, observe its CommandComplete, drop the response and close the
  socket. The actual owner receives a Spring commit failure and leaves the marker. A fresh JVM
  resolves the persisted xid as COMMITTED, preserves DRAINED and fences the old worker.
- Drop COMMIT before forwarding, producing a real connection error and server abort. Resolve
  ROLLED_BACK, preserve ACTIVE/no audit effect, then prove separate fresh admission can drain.
- Close the client while retaining a delayed COMMIT/server transaction. Resolution denies while
  the xid is in progress, then succeeds only after the proxy forwards COMMIT and the server finishes.
- Real PREPARE TRANSACTION stays unresolved until explicit COMMIT PREPARED or ROLLBACK PREPARED;
  the resolver derives the respective terminal outcome. Fixture PostgreSQL alone enables ten
  prepared transactions for this test; this is not a production configuration change.
- Reject a different database on the same cluster and invalid/expired exact command approval.
- Restart the real disposable PostgreSQL container; the old marker remains and the old server
  admission cannot resolve or admit work on the new postmaster.
- Recheck approval after preflight and reject an older prepared proof against a newer lease.

The loopback proxy uses PostgreSQL v3 simple-query mode, disables SSL/GSS only for the disposable
fixture, and never logs authentication or data frames. It causes actual JDBC socket failures;
these new fault cases do not inject Spring UNKNOWN notifications. The previous UNKNOWN-injection
tests remain clearly separate. The aborted-connection test may poll outside all guards solely to
wait for the disposable server to observe EOF; production code contains no such retry loop.

## Limits and sources

Qualification is PG17, local POSIX/ext4 and cooperating processes. No TLS interruption, host
power-loss survival, failover/rebind, automatic receipt retention, status-history truncation drill,
privileged tampering, database/store rollback continuity or multi-host authority is claimed. The
physical common-cut regression still uses its earlier sequential driver. Gateway/THS approval,
least-privilege deployment binding, operational projection, writer custody, full-owner budget
reconstruction, UDP/Ingestion and traffic reopening remain open. MinIO/source-lock are unchanged.

PostgreSQL 17 primary references:

- [Transaction IDs and status functions](https://www.postgresql.org/docs/17/functions-info.html#FUNCTIONS-PG-SNAPSHOT): top-level xid8 and retained terminal status.
- [Control data functions](https://www.postgresql.org/docs/17/functions-info.html#FUNCTIONS-CONTROLDATA): system identifier; the same page defines postmaster start time.
- [initdb options](https://www.postgresql.org/docs/17/app-initdb.html): fixture-only `--set=max_prepared_transactions=10`.

These references describe the database contract; executable receipts qualify only the cases above.
