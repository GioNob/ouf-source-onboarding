# Finite HUMAN sessions for managed source readiness

An approved managed source can remain paused after its original 128-attempt or
24-hour readiness budget ends. An explicitly authenticated HUMAN can grant a
new `managed-review-recovery.v1` session: at most three additional attempts in
900 seconds. The profile is deliberately narrower than the original window
because it is intended for checking a repaired dependency, not replaying a
whole onboarding. Granting the session does not activate or publish a source.

The existing THS review shows the exact source, version, configuration hash,
retained cumulative count and new limits. A reason and a separate grant click
are required. The actor is taken from the authenticated principal; the owner
issues a decision reference. The HUMAN then separately chooses whether to
verify and activate. This retains the existing configuration approval, source,
asset and identical-operation publication/acquisition identity.

V34 adds an owner-local session journal and a session pointer to the existing
managed review. Legacy start, cumulative attempts, prior generation, last
failure and previous sessions are retained. A grant advances the fencing
generation without reserving an attempt. Each activation claim increments the
same cumulative counter and uses the current session's remaining attempts and
deadline; it keeps the existing 50-second completion margin and 60-second
attempt lease. One active session is enforced by row locking and a partial
unique index. Identical command keys reconcile a lost grant response instead
of extending its deadline; changed intent conflicts.

Only elapsed/exhausted, known transient PAUSED/EXHAUSTED states are eligible.
An active lease, outstanding Retry-After/backoff, unknown outcome, BLOCKED
state, unconfirmed approval, configuration drift, source ownership or current
authorization denial cannot be bypassed by granting a budget. Old workers are
fenced by the new generation. A session cannot undo a publication or start an
acquisition by itself. No MCP grant/activation capability is introduced.

The new operation is `POST .../approval-challenges/{id}/review/budget` through
the current HUMAN Gateway namespace and existing configuration-write policy.
The browser adapter validates the fixed request shape and uses the existing
session/CSRF boundary. The owner reauthorizes and verifies managed-file ownership.
The existing Gateway approval namespace is a wildcard route; no new Gateway
route, IAM permission or endpoint is required by the code change. Installed
Gateway/Onboarding generations still require target verification.

Qualify with real PostgreSQL lifecycle tests for retained history, concurrent
commands, idempotent lost-response reconciliation, stale worker fencing,
attempt/time exhaustion and refused overrides. Browser coverage must show no
automatic grant or activation, including a grant committed by the owner whose
HTTP response is lost. CI qualification is separate from deployment and a
real HUMAN budget decision. V34 is a new schema migration and must use the
existing governed migration/deployment process; do not enable automatic
production migrations to install it.

This implements the owner-local mechanism required by OUF resilience policy
v3 sections14–16 and the repository AGENTS.md. It does not change domain
publication/approval contracts or declare CSV E2E acceptance. Issue50 records
the retained target context; no target-specific IDs or addresses are product
defaults.
