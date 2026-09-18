# R3a.1e — governed active InstallationProjection export

Normative basis:
- OUF Reality Baseline Package v1.7;
- Cross-Module Alignment Matrix v1.7;
- Source Onboarding / Configuration / THS PET;
- R3a.1b immutable InstallationConfiguration lifecycle;
- R3a.1c runtime projections;
- R3a.1d Gateway/Caddy projection consumption.

## Goal

Expose the ACTIVE InstallationProjection through a governed Trusted Human operation so installation automation can materialize the exact active revision without manually reconstructing configuration.

## Human role

Platform installer / bootstrap administrator.

The operation is HUMAN-only and requires capability:

`installation.configuration.export`

Canonical descriptor:
- owner: `installation`;
- operation: `READ`;
- required scope: `installation.configuration.export`;
- allowed actor: `HUMAN`.

The descriptor is versioned in `AuthorizationCapabilities`. Registration in the Authorization capability registry is an explicit bootstrap/deployment action; the runtime does not silently auto-register it.

It is explicitly outside the MCP surface.

## Endpoint

`GET /api/trusted-human/v1/installations/{installationId}/projection`

Inputs:
- installationId;
- mandatory `X-Correlation-ID`;
- optional `revision` active-revision precondition.

Behavior:
- no ACTIVE revision -> 404;
- requested revision differs from ACTIVE -> 409;
- non-HUMAN or missing capability -> 403;
- missing correlation id -> 400;
- success -> exact projection for the ACTIVE revision only.

Response includes:
- `X-OUF-Installation-Revision`;
- `X-OUF-Installation-Checksum`;
- `Cache-Control: no-store`;
- attachment filename containing installationId and revision.

## Secret boundary

The export may contain secret references such as:
- `MCP_OIDC_CLIENT_SECRET_FILE`;
- `MCP_FINGERPRINT_KEY_FILE`.

It must never contain:
- passwords;
- client-secret values;
- bearer/access tokens;
- private key material.

## Audit

Migration V23 adds append-only `installation_projection_export_event`.

Each successful export records:
- export UUID;
- installationId;
- active revision;
- configuration checksum;
- HUMAN subject;
- correlation id;
- timestamp.

Export audit rows cannot be updated or deleted.

## Verification

Automated tests cover:
- ACTIVE-only export;
- explicit non-active revision rejection;
- no-active rejection;
- HUMAN + capability boundary;
- SERVICE rejection even with capability;
- missing capability rejection;
- correlation requirement;
- no-store/checksum/revision response headers;
- append-only export audit;
- secret references present while secret values are absent;
- Trusted Human OpenAPI surface remains excluded from MCP.

## Deployment boundary

This increment does not yet claim live Netcup acceptance.

After merge, deployment proceeds by:
1. deploying Source Onboarding with V21–V23;
2. creating/validating the lab InstallationConfiguration revision;
3. producing environment PASS;
4. activating it;
5. registering the canonical `installation.configuration.export` descriptor with owner `installation`;
6. publishing a policy/grant that grants the installer HUMAN `installation.configuration.export`;
7. exporting the ACTIVE projection through this endpoint;
8. atomically materializing it as the installation projection consumed by Gateway/Caddy/MCP.
