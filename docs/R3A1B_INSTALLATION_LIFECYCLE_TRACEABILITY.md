# R3a.1b — InstallationConfiguration persistence and lifecycle

Normative basis:
- OUF Reality Baseline Package v1.7;
- Cross-Module Alignment Matrix v1.7;
- Source Onboarding / Configuration / THS PET environment-binding responsibilities;
- R3a.1a InstallationConfiguration v1 contract.

## Goal

Persist installation configuration revisions as immutable evidence and make activation, supersession, rollback and revocation explicit lifecycle events.

## Human role

Platform installer / bootstrap administrator.

R3a.1b is still backend/runtime infrastructure. The human-facing bootstrap wizard/API is a later increment.

## Persistence model

Schema: `ouf_installation`.

Tables:
- `installation_configuration_revision`: immutable payload, checksum, validation result/findings, creator and timestamp;
- `installation_configuration_lifecycle_event`: append-only VALIDATED / ACTIVATED / SUPERSEDED / REVOKED / ROLLBACK_ACTIVATED events;
- `installation_configuration_active`: current active revision pointer per installation.

The revision payload itself is never mutated after insertion.

## Validation state

Each attempted revision is persisted as either:
- `VALIDATED`; or
- `REJECTED`.

Rejected attempts are retained as evidence with findings and cannot be activated.

Current runtime validation proves only the bounded R3a.1b gate:
- installation and tenant identity present;
- IAM issuer is HTTPS;
- public Gateway base URL is HTTPS;
- secret references are present;
- credential-like scalar values are rejected.

Full DNS/TLS/IAM/DB/storage reachability validation belongs to R3a.1c+ and is not claimed here.

## Checksum

The service recursively canonicalizes JSON object-key order and stores SHA-256 of the canonical payload.

## Activation and rollback

Activation is serialized per installation with PostgreSQL advisory transaction locking.

When another revision is active:
- the old revision receives `SUPERSEDED`;
- the target receives `ACTIVATED`.

Activating an older validated revision after a newer one emits `ROLLBACK_ACTIVATED`.

A revoked revision cannot be activated again.

## Verification

`InstallationConfigurationLifecycleRuntimeTest` verifies:
- monotonic revision allocation;
- SHA-256 checksum changes with payload;
- DB-level immutability of revisions;
- rejected configuration persistence and non-activation;
- activation and supersession;
- rollback to a previous revision;
- revocation;
- append-only lifecycle history.

## Explicit deferrals

Not claimed by R3a.1b:
- trusted-human bootstrap API;
- browser wizard;
- full infrastructure validation engine;
- per-module runtime projections;
- automatic Caddy/APISIX/Keycloak application;
- unattended bootstrap;
- multi-node HA control-plane acceptance.


## CI history

Initial PR #22 Source Onboarding CI run `35329328093` failed during test compilation before runtime execution. Root causes:
- the test fixture used `Map.of(...)` with more key/value pairs than Java supports;
- `ObjectNode` was referenced without an explicit import.

The branch was corrected without changing lifecycle behavior, validation semantics or acceptance thresholds.
