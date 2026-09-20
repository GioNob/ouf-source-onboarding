# OUF Source Onboarding & Configuration

Implementation in progress against `OUF_PET_Source_Onboarding_Configuration_THS_v1_5_Development_Ready`.

This first executable increment contains the PostgreSQL 17 source/version workflow core:

- source registration without secret material;
- immutable version history and optimistic locking;
- DRAFT -> IN_REVIEW -> APPROVED -> ACTIVE;
- short-lived approval challenges bound to the canonical configuration hash;
- HUMAN_USER-only confirmation and activation;
- executable validation engine with typed findings and append-only validation evidence;
- persistent and idempotent technical-discovery queue with lease recovery and materialized source types/fields/states;
- mapping workspace for field classification, eligibility, semantic/property mappings and governed DRAFT generation;
- managed CSV/XLSX asset registration and bounded deterministic profiling without storing file blobs in PostgreSQL;
- asynchronous, idempotent managed-file profiling jobs owned by the Onboarding control plane;
- managed-file DRAFT generation with immutable `assetId`, `fileProfileId`, `stagingRef`, and content hash references in the runtime bundle;
- an `INGESTION-COMPAT` attestation gate that checks consumer acceptance without waiting for data ingestion;
- append-only approval evidence and audit;
- atomic ACTIVE bundle switch with historical bundle retention.
- immutable technical `SourceRuntimeProfile`, `SourceSchemaBinding`, and `RouteBinding` projections for Gateway consumption;
- explicit historical bundle lookup without fallback to the current version;
- schema-surveillance issues with a fail-closed activation gate for BREAKING/UNKNOWN drift;
- strict DELTA_PATCH logical-contract validation;
- a direct HUMAN_USER THS backend surface for exact frozen context, confirm, reject, and activate, explicitly excluded from MCP.
- a distinct `/api/trusted-human/v1` Protected Operations backend for typed log search/read, deterministic aggregate/correlation, server-side redaction, access audit, and governed asynchronous export/download;
- source update/list pagination, version clone/diff, explicit extraction-profile build, and a semantic-gap/candidate workflow that preserves Semantic Registry authority;
- Prometheus operational gauges for active sources, pending reviews, job backlog, and terminal job failures.

Source Onboarding never owns polling, scheduling, runtime leases, ETL, or ingestion outcomes. `pollInterval` is authored here as configuration only. Ingestion Runtime consumes the exact ACTIVE bundle and owns both recurring PULL execution and the one-time ingestion of a managed CSV/XLSX file.

## Conversational managed-file flow

The intended MCP/agent experience is: “I want to add this CSV as an object source; each row is one object.” The agent orchestrates these capabilities:

1. upload the attachment through the Gateway/object-storage capability and obtain `stagingRef`, content hash, media type, size, and retention reference;
2. register the staged asset with `POST /api/onboarding/v1/managed-files`;
3. request asynchronous profiling with `POST /api/onboarding/v1/managed-files/{assetId}/profile` and poll the returned job resource;
4. present inferred columns, types, candidate keys, and the proposed “one row = one object” mapping to the human;
5. create the governed DRAFT with `POST /api/onboarding/v1/managed-files/{assetId}/create-onboarding?profileId=...`;
6. submit, approve, attest `INGESTION-COMPAT`, and activate the immutable bundle;
7. let Ingestion Runtime observe the ACTIVE bundle, read the staged file once, create one canonical object per data row, and persist runtime lineage/watermarks outside this module.

The AI agent may propose and drive the workflow, but approval and activation remain human-only. Request identity is derived from a validated server principal and trusted role mapping; actor identity headers are not accepted.

Authentication, principal normalization, tenant/resource authorization and policy decisions are owned by the OUF Authorization module. Onboarding consumes only the trusted server principal, roles, authentication-context reference and `ouf.authorizedCapabilities` request context produced by that integration; it does not validate JWTs or own IAM policy. Domain guards remain fail-closed, including the dedicated `ouf.ingestion.configuration.attest` capability required for compatibility attestation.

The machine-readable MCP-facing surface is defined in `openapi/onboarding-v1.yaml`; the non-MCP trusted-human contract is `openapi/ths-v1.yaml`. Protected log operations require HUMAN_USER plus Authorization-owned capabilities and a trusted authorization context on every request. The built-in typed adapter exposes the module's append-only audit stream; production environments add the shared log-store adapter behind the same interface. To run the in-process profiling worker, configure `ouf.onboarding.object-store.gateway-base-url`; the adapter reads the opaque `object://` reference through the Gateway internal object-storage route and applies the registered size and content-hash checks before profiling. The worker remains disabled when that route is not configured.

It does not claim ownership of geospatial runtime ingestion, Authorization Policy Registry, the Ingestion Runtime implementation, the shared log store, or a production browser shell. Production IAM/session/CSRF/CSP/step-up controls and concrete Gateway/log-store/object-store routes remain environment bindings owned by Authorization, Gateway and platform operations.

### Superadmin OUF e bootstrap

Il bootstrap associa un ruolo organizzativo IAM, issuer e tenant all'autorità
protetta di superadmin OUF. Gli admin ordinari non possono modificarla.
Il superadmin può trasferirla con conferma di un titolare del ruolo destinatario.
Configurazione, adozione sulle installazioni esistenti e contratto sono nella
[guida di bootstrap e trasferimento](docs/OUF_ADMIN_BOOTSTRAP.md).
