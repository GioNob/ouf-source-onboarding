> **Policy di piattaforma vigente:** [Resilienza, quarantena, incidenti e alert — v3](https://github.com/GioNob/ouf-deploy/blob/main/docs/OUF_RESILIENCE_POLICY.md). Obbligatoria per sviluppo e revisione; [adozione e prove](https://github.com/GioNob/ouf-deploy/blob/main/docs/OUF_RESILIENCE_ADOPTION.md).

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
- a governed UDP identity activation gate that rechecks the exact frozen configuration hash and current indexed coverage through Gateway;
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

For governed canonical identity, prepare the UDP index once with the approved
frozen configuration before activation. Set `OUF_ONB_UDP_IDENTITY_GATEWAY_URL`
and `OUF_ONB_UDP_IDENTITY_TOKEN_FILE` for the Onboarding SERVICE call; absent
configuration or stale UDP coverage blocks activation. See
[`docs/R4A_UDP_IDENTITY_ACTIVATION_GATE.md`](docs/R4A_UDP_IDENTITY_ACTIVATION_GATE.md).

Authentication, principal normalization, tenant/resource authorization and policy decisions are owned by the OUF Authorization module. Onboarding consumes only the trusted server principal, roles, authentication-context reference and `ouf.authorizedCapabilities` request context produced by that integration; it does not validate JWTs or own IAM policy. Domain guards remain fail-closed, including the dedicated `ouf.ingestion.configuration.attest` capability required for compatibility attestation.

The machine-readable MCP-facing surface is defined in `openapi/onboarding-v1.yaml`; the non-MCP trusted-human contract is `openapi/ths-v1.yaml`. Protected log operations require HUMAN_USER plus Authorization-owned capabilities and a trusted authorization context on every request. The built-in typed adapter exposes the module's append-only audit stream; production environments add the shared log-store adapter behind the same interface. To run the in-process profiling worker, configure `ouf.onboarding.object-store.gateway-base-url`; the adapter reads the opaque `object://` reference through the Gateway internal object-storage route and applies the registered size and content-hash checks before profiling. The worker remains disabled when that route is not configured.

It does not claim ownership of geospatial runtime ingestion, Authorization Policy Registry, the Ingestion Runtime implementation, the shared log store, or a production browser shell. Production IAM/session/CSRF/CSP/step-up controls and concrete Gateway/log-store/object-store routes remain environment bindings owned by Authorization, Gateway and platform operations.

### Superadmin OUF e bootstrap

Il bootstrap associa un ruolo organizzativo IAM, issuer e tenant all'autorità
protetta di superadmin OUF. Gli admin ordinari non possono modificarla.
Il superadmin può trasferirla con conferma di un titolare del ruolo destinatario.
Configurazione, adozione sulle installazioni esistenti e contratto sono nella
[guida di bootstrap e trasferimento](docs/OUF_ADMIN_BOOTSTRAP.md).

### Revisione dei permessi prima della pubblicazione

La [guida di consultazione e simulazione](docs/AUTHORIZATION_REVIEW.md) descrive
lookup dei grant nominali/per ruolo, diff di una bozza e confronto delle decisioni
ACTIVE/bozza. Sono operazioni del canale umano amministrativo: non attivano policy
e non costituiscono ancora tool chatbot o una directory utenti IAM.

## Chatbot e THS per i permessi

Configurazione IAM, sessione, chiavi, ciclo proposta/conferma e collaudo: [guida operativa](docs/PERMISSION_PROPOSALS.md).

Il rinnovo nominale usa `scripts/r4a_human_grant_lifecycle.py --operation renew`
per HUMAN e `scripts/r4a_service_grant_expiry_renewal.py` per SERVICE. Entrambi
usano il Device Flow dell'amministratore HUMAN indicato da `--subject-id` e le
fasi `plan`, `draft` (con preview owner), `preview`, `publish`, `verify`.
Il manifest SERVICE (`--grants-file`) indica `grantId`, `capabilityId` e
`servicePrincipalId`. Sono ammessi i grant del tenant `ouf-lab` con soggetto e
organizzazione nulli e capability che ammette SERVICE. Il rinnovo modifica solo
`validUntil` di grant già scaduti. Ogni rinnovo usa un file di stato privato.
Il publish richiede `--confirm-publish` e l'hash completo `sha256:…` della
preview approvata in `--reviewed-draft-hash`. In caso di esito ambiguo lo stato
conserva la fase per la riconciliazione con l'owner.

### Difetti emersi nell'E2E Teatri

- Una challenge scaduta non si può rinnovare.
- Il refresh del token THS richiede una correzione.
- Sul VPS sono stati rilevati oltre 150 container fermi.
- I grant HUMAN e SERVICE coinvolti avevano scadenze fisse il 25–26 ottobre, senza avvisi.
- La risoluzione delle capability usa un contesto senza etichette. I grant di ruolo con `allowedDataLabels` o `allowedDetailLevels` non vuoti vengono scartati. Va definito il contratto tra `OufRoleCatalogue` e `GrantConstraints.matches()`.
- Il corpo del 403 viene nascosto al chiamante.
- Nelle verifiche non sono emersi eventi di login in Keycloak.
- Gli strumenti MCP di acquisizione e di stato e storico Ingestion restituiscono `authorization denied`.
- Corretto nella PR #55 il controllo dell'hash di preview che rifiutava il prefisso `sha256:`.
- Il ricaricamento della policy non è visibile nei log.
- Il `503 ONB_PERSISTENCE_FAILURE` non compare nei log di Onboarding.
- Il connettore MCP non espone il clone di una versione di Onboarding.
- Il 403 di readiness non compare nei log di Ingestion.
- Un 403 di una dipendenza porta la review in `BLOCKED` senza ripresa. Dopo il ripristino dell'accesso serve clonare e riapprovare.
- Nei log manca un evento di conclusione del run.
- `ouf-udp` non registra nei log la ricezione degli handoff.

