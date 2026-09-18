# R3a.1g — governed candidate InstallationProjection export

Normative basis:
- OUF Reality Baseline Package v1.7;
- Cross-Module Alignment Matrix v1.7;
- Source Onboarding / Configuration / THS PET;
- R3a.1b immutable InstallationConfiguration lifecycle;
- R3a.1c environment validation and deterministic projections;
- R3a.1e ACTIVE projection export;
- R3a.1f Trusted Human Installation Bootstrap API.

## Goal

Close the bootstrap dependency cycle in which environment validation needs internal DNS / reverse-proxy aliases derived from the InstallationConfiguration before the first revision can become ACTIVE.

A VALIDATED immutable revision may therefore export a governed **candidate projection** for validation bootstrap only.

## Endpoint

`GET /api/trusted-human/v1/installations/{installationId}/revisions/{revision}/projection`

Requirements:
- actor HUMAN;
- capability `installation.configuration.export`;
- mandatory `X-Correlation-ID`;
- target revision exists and is `VALIDATED`.

Response:
- deterministic projection derived from the exact immutable revision;
- `Cache-Control: no-store`;
- `X-OUF-Installation-Revision`;
- `X-OUF-Installation-Checksum`;
- `X-OUF-Projection-Purpose: CANDIDATE`.

## Candidate boundary

A candidate projection:
- is not ACTIVE;
- does not create or mutate the ACTIVE pointer;
- may be used only to prepare prerequisites required by real environment validation;
- must not be consumed as canonical runtime state by application modules;
- is tied to installationId, revision and checksum;
- is audited append-only with export purpose `CANDIDATE`.

## Audit

Migration V25 extends `installation_projection_export_event` with `export_purpose`.

Allowed values:
- `ACTIVE`;
- `CANDIDATE`.

Existing rows become `ACTIVE` through DDL default during migration; the default is then removed so new exports must declare their purpose explicitly.

## Bootstrap sequence

1. create immutable revision;
2. revision becomes `VALIDATED`;
3. export candidate projection;
4. apply only validation prerequisites, e.g. internal reverse-proxy aliases;
5. run real environment validation;
6. latest result must be `PASS`;
7. activate the same revision;
8. export ACTIVE projection;
9. verify ACTIVE revision/checksum equal the candidate revision/checksum used for validation;
10. materialize the ACTIVE projection as canonical runtime configuration.

## Verification

Automated tests cover:
- candidate export before any ACTIVE pointer exists;
- revision/checksum preservation;
- append-only candidate audit;
- candidate purpose response header;
- HUMAN + export capability boundary;
- Trusted Human contract remains outside MCP.

## Netcup laboratory evidence

Revision 1 of `ouf-lab-netcup-01` produced environment FAIL because `api.ouf-lab.it` was not resolvable from the backend network while IAM/OIDC/PostgreSQL/object storage checks passed.

R3a.1g exists specifically to let the immutable revision 1 projection configure the internal Caddy alias required for the next validation attempt without falsely declaring revision 1 ACTIVE first.
