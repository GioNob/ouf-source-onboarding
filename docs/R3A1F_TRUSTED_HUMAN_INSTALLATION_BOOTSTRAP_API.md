# R3a.1f — Trusted Human Installation Bootstrap API

Normative basis:
- OUF Reality Baseline Package v1.7;
- Cross-Module Alignment Matrix v1.7;
- Source Onboarding / Configuration / THS PET;
- R3a.1b immutable installation lifecycle;
- R3a.1c real environment validation and projections;
- R3a.1e governed active projection export.

## Goal

Expose the InstallationConfiguration lifecycle through a governed HUMAN-only Trusted Human Surface so a real deployment can perform:

1. create immutable revision;
2. read revision / ACTIVE pointer;
3. run environment validation;
4. activate;
5. rollback to an older validated revision;
6. revoke.

No SQL or manual file mutation is part of the control flow.

## Capabilities

Canonical owner: `installation`.

- `installation.configuration.read`
  - operation: READ
  - actor: HUMAN
- `installation.configuration.write`
  - operation: EXECUTE
  - actor: HUMAN
- `installation.configuration.activate`
  - operation: EXECUTE
  - actor: HUMAN
- `installation.configuration.export`
  - operation: READ
  - actor: HUMAN

All are versioned in `AuthorizationCapabilities`. Registration in the capability registry and grants in an ACTIVE PolicyBundle remain explicit deployment actions.

## Trusted Human endpoints

Base path:

`/api/trusted-human/v1/installations`

Endpoints:

- `GET /{installationId}/active`
- `GET /{installationId}/revisions/{revision}`
- `POST /{installationId}/revisions`
- `POST /{installationId}/revisions/{revision}:validate-environment`
- `POST /{installationId}/revisions/{revision}:activate`
- `POST /{installationId}/revisions/{revision}:rollback`
- `POST /{installationId}/revisions/{revision}:revoke`

All surfaces are explicitly excluded from MCP.

## Write governance

Every mutating operation requires:
- HUMAN authorization;
- the specific installation capability;
- stateless trusted-write proof;
- non-empty `X-Correlation-ID`.

Create also requires the path installationId to equal the payload installationId.

## Activation and rollback

Activation reuses the R3a.1b lifecycle service and therefore remains fail-closed:
- target revision must be VALIDATED;
- target revision must not be REVOKED;
- latest environment validation must be PASS.

Rollback is explicit at the API boundary:
- an ACTIVE revision must already exist;
- target revision must be older than ACTIVE;
- the lifecycle service emits `ROLLBACK_ACTIVATED`.

## Correlation evidence

Migration V24 persists correlation for:
- every InstallationConfiguration creation attempt, including REJECTED revisions, via `created_correlation_id`;
- every environment validation run via `correlation_id`.

Existing rows are backfilled with deterministic `legacy:` values before NOT NULL is enforced.

Lifecycle activation/supersession/rollback/revoke already store correlation through append-only lifecycle events.

## Verification

Automated tests cover:
- canonical installation capability descriptors;
- HUMAN-only read/write/activate boundaries;
- trusted-write proof;
- correlation requirement;
- path/body installationId consistency;
- explicit rollback semantics;
- correlation persistence for revision creation and environment validation;
- THS/OpenAPI presence and MCP exclusion.

## Laboratory deployment sequence after merge

For the Netcup laboratory:

1. deploy Source Onboarding including V24;
2. register `installation.configuration.read`, `installation.configuration.write`, and `installation.configuration.activate` with owner `installation`;
3. publish a new Authorization PolicyBundle granting those capabilities to the installer HUMAN;
4. create `ouf-lab-netcup-01` revision through THS;
5. run environment validation;
6. activate only after PASS;
7. export ACTIVE projection through R3a.1e;
8. atomically materialize `/opt/ouf/installation/active-projection.json`;
9. continue Gateway/Caddy/MCP deployment from the same projection.
