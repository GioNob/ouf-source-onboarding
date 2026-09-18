# R3a.1c — environment validation and runtime projections

Normative basis:
- OUF Reality Baseline Package v1.7;
- Cross-Module Alignment Matrix v1.7;
- Source Onboarding / Configuration / THS PET environment-binding responsibilities;
- R3a.1a InstallationConfiguration contract;
- R3a.1b immutable revision lifecycle;
- Gateway and MCP deployment/security contracts.

## Goal

Turn a syntactically valid InstallationConfiguration into:
1. environment validation evidence based on real network probes; and
2. deterministic non-secret runtime projections for Caddy, Gateway and MCP.

## Environment validation evidence

Migration V22 adds append-only `installation_environment_validation` evidence.

The default validator performs from the deployed Source Onboarding network context:
- DNS resolution for IAM and API hosts;
- HTTPS/OIDC discovery;
- exact OIDC issuer match;
- exact configured token endpoint match against OIDC discovery;
- HTTPS reachability of the public API hostname (2xx–4xx accepted because an undefined root route may legitimately be 404);
- TCP reachability of PostgreSQL `service://host:port`;
- TCP reachability of S3-compatible object storage when configured.

Every run is persisted as PASS/FAIL plus structured findings.

The latest environment validation result governs activation:
- no environment result -> activation denied;
- latest FAIL -> activation denied;
- latest PASS -> activation may proceed if all R3a.1b lifecycle gates also pass.

Validation evidence is append-only.

## Runtime projections

`InstallationRuntimeProjectionService` generates projections from a VALIDATED revision.

### Caddy projection

Contains:
- backend network;
- edge network;
- issuer hostname;
- API hostname;
- OIDC discovery URL.

No organization-specific hostname exists as a product default.

### Gateway projection

Contains:
- issuer URL;
- required audience;
- public API base URL;
- internal Gateway service reference.

### MCP projection

Non-secret environment coordinates:
- `MCP_OIDC_TOKEN_ENDPOINT`;
- `MCP_OIDC_CLIENT_ID`;
- `MCP_GATEWAY_ENDPOINT`;
- `MCP_AUTHORIZATION_BUNDLE_ENDPOINT`;
- `MCP_GATEWAY_RECOVERY_ENDPOINT`.

Secret references only:
- `MCP_OIDC_CLIENT_SECRET_FILE`;
- `MCP_FINGERPRINT_KEY_FILE`.

The MCP workload client id is installation configuration at `iam.workloadClients.mcpServer`; it is not hardcoded by the projection service.

## Product protocol paths vs environment coordinates

The OIDC token endpoint is installation data and must match the value published by OIDC discovery.

The following paths are OUF protocol/API contracts and therefore may be fixed by the product:
- `/internal/capabilities/v1/execute`;
- `/internal/capabilities/v1/authorization/policy-bundle/active`;
- `/internal/capabilities/v1/recovery`.

Hostnames, domains, IPs, network names, realm coordinates and workload client ids are installation data.

## Laboratory example

The versioned lab example projects:
- issuer: `https://auth.ouf-lab.it/realms/ouf`;
- API base: `https://api.ouf-lab.it`;
- backend network: `ouf-backend`;
- edge network: `ouf-edge`;
- MCP workload client: `ouf-mcp-server`;
- MCP secret file references under `/opt/ouf/secrets/`.

These values are evidence for the Netcup laboratory only.

## Verification

Automated tests cover:
- activation blocked until environment PASS exists;
- latest FAIL supersedes an earlier PASS for activation gating;
- validation evidence is append-only;
- lab example projects exact Caddy/Gateway/MCP coordinates;
- projection contains secret references, never secret values.

## Explicit deferrals

R3a.1c does not yet claim:
- trusted-human bootstrap API/wizard;
- automatic application of projections to Caddy/APISIX/MCP containers;
- Gateway materialization of the generic MCP execution endpoint;
- Gateway-mediated PolicyBundle and recovery endpoint deployment;
- enterprise CA installation;
- deployed laboratory environment PASS.

Those are subsequent increments/deployment acceptance gates.
