# Source Onboarding operational runbook

## Failed or abandoned jobs

Inspect `ouf_onboarding_job_backlog` and `ouf_onboarding_failed_jobs`, then the relevant `file_profile_job` or `protected_log_export_job` by correlation/export identifier. A `RUNNING` job with an expired lease is reclaimed with `FOR UPDATE SKIP LOCKED`; after five attempts it becomes `FAILED`. Correct the dependency or input before replaying through the public idempotent command. Never edit job state manually.

## Dependency failure

Gateway/object-store and shared log-store failures must surface as `ONB_DEPENDENCY_FAILURE` with a correlation identifier. Keep the frozen onboarding version unchanged, verify Gateway route health, Authorization context propagation and the opaque object/log reference, then retry within the bounded job policy. Activation remains atomic and must never be partially repaired in the database.

## Database failover and restore

Stop write traffic, verify PostgreSQL recovery/PITR completion, run `flyway validate`, then restore API and worker replicas. Confirm one ACTIVE bundle per source, append-only audit triggers, expired lease recovery and checksum resolution of historical publications before reopening write traffic.

## Schema drift

An unresolved `BREAKING` or `UNKNOWN` surveillance issue blocks activation. Resolve it through a new reviewed onboarding version; do not mutate the ACTIVE bundle or mark an issue resolved directly in SQL.

## Protected log export

Exports are HUMAN_USER-only, purpose-bound and expire. A failed export may be retried by the worker while attempts remain. Never expose `artifact`, storage paths, credentials or Authorization context through status APIs. Investigate every unexpected download using `protected_log_access_audit`.

## OUF superadmin role and handover

Use [OUF_ADMIN_BOOTSTRAP.md](OUF_ADMIN_BOOTSTRAP.md) for initial designation,
one-time adoption on existing installations and role handover. Do not reset the
bootstrap latch, modify ordinary grants to impersonate superadmin, or edit the
protected binding directly. A pending transfer leaves the current role active;
cancel or replace an expired proposal and obtain a fresh target-role confirmation.
The IAM authority remains responsible for identity recovery and role membership.

## Review before authorization changes

Use [AUTHORIZATION_REVIEW.md](AUTHORIZATION_REVIEW.md) to inspect configured grants,
preview an exact draft revision and simulate a hypothetical context. A 409 means
ACTIVE changed: reload/rebase through the governed lifecycle before reviewing
again. A 412 means the draft revision changed. A 413 oversized diff must not be
presented as a complete review. Simulation ALLOW is not an enforcement decision
or proof of the user's real IAM memberships; never forward it as authorization.

## Chatbot e THS per i permessi

Configurazione IAM, sessione, chiavi, ciclo proposta/conferma e collaudo: [guida operativa](PERMISSION_PROPOSALS.md).


## Keycloak client-scope bootstrap per capability OUF

Quando una capability richiede uno scope OAuth omonimo, distinguere due operazioni:
1. creazione/reconciliation del client scope nel realm;
2. binding del client scope al client workload/HUMAN come DEFAULT o OPTIONAL.

Non usare sequenze `kcadm` one-off come procedura definitiva.

Per creare o riallineare un client scope OIDC usare:

`scripts/r4a_keycloak_client_scope_catalogue.py`

Il tool:
- supporta `plan`, `apply`, `verify`;
- usa matching esatto per nome;
- imposta `protocol=openid-connect`;
- imposta `include.in.token.scope=true`;
- imposta `display.on.consent.screen=false`;
- preserva attributi extra;
- non stampa token, password o client secret;
- fallisce esplicitamente se la sessione kcadm è scaduta.

Esempio logico R4a:
- creare/reconciliare `ouf.onboarding.configuration.write`;
- creare/reconciliare `ouf.ingestion.configuration.attest`;
- bindare `ouf.onboarding.configuration.write` a `ouf-human-admin` come OPTIONAL;
- bindare `ouf.ingestion.configuration.attest` a `ouf-ingestion` come DEFAULT;
- emettere token nuovi e verificare il claim `scope`;
- solo dopo eseguire acceptance applicativa.

OAuth scope e Authorization grant sono gate distinti: la presenza di uno non sostituisce l'altro.


### Keycloak client-scope binding

After the scopes pass catalogue `verify`, use
`scripts/r4a_keycloak_client_scope_binding.py` with `plan`, `apply`, then
`verify` for each exact client/scope pair:

- `--client ouf-human-admin --scope ouf.onboarding.configuration.write --binding optional`
- `--client ouf-ingestion --scope ouf.ingestion.configuration.attest --binding default`

Run the versioned helper from a green commit with the existing interactive
`kcadm` admin session; never put its password in shell arguments or logs.
The helper adds only the requested missing binding. It blocks when the same
scope is already bound in the opposite category; resolve that drift through
review rather than silently moving or removing it. Confirm fresh token scope
claims without printing token material. OAuth scope and Authorization grant
remain separate acceptance gates.


### HUMAN Device Flow gate

For the R4a human token acceptance, `ouf-human-admin` is a public OIDC
client. The live lab client may have Device Authorization Grant disabled.
Use `scripts/r4a_keycloak_human_device_client.py` in `plan`, `apply`,
`verify` order from a green commit. The helper checks the effective Keycloak client attribute
`oauth2.device.authorization.grant.enabled` and changes only that attribute
when missing on the exact client; it preserves
its existing configuration, and fails if its public OIDC identity contract
has changed. Do not enable direct access grants or standard flow to work
around this gate. After verification, request the optional
`ouf.onboarding.configuration.write` scope in a new HUMAN Device Flow,
verify the token claim without printing the token, and separately verify
the governed Authorization grant before any mutating Onboarding call.


### HUMAN token acceptance without exporting bearer

After Device Flow `verify` passes, run
`python3 scripts/r4a_human_token_scope_smoke.py` in a server terminal. Open
the displayed verification URI on the operator PC, enter the displayed
short-lived code there, and authenticate as `ouf-admin`. Do not paste the
code or the terminal's intermediate output into chat. The tool requests the
optional `ouf.onboarding.configuration.write` scope and prints only
boolean checks of issuer, client, HUMAN actor, expected username, Gateway
audience, exact scope, freshness and expiry. It never prints or stores the
access token; it does not grant an OUF Authorization capability. Do not use
this smoke to send the HUMAN bearer to an AI client.


### Named HUMAN grant for Onboarding configuration

Use `scripts/r4a_human_grant_lifecycle.py` for the exact IAM subject ID
resolved from Keycloak username `ouf-admin`. The script requires a fresh
Device Flow token for `authorization.policy.admin`, checks its subject and
HUMAN claim against that ID, and keeps bearer material only in memory.
The add-only lifecycle is `plan → draft → preview → publish → verify` over
the trusted-HUMAN Authorization owner API. It refuses duplicate equivalent
grants, capability absence and unexpected changes in preview; it preserves
all existing capabilities and grants. The default lab validity is
2026-09-25T00:00:00Z through 2026-10-25T00:00:00Z and is explicit in each
invocation. The state file contains policy material and must remain mode
0600. Inspect the plan/preview and obtain the operator's affirmative
publication decision before using `publish --confirm-publish`. An OAuth
scope in a JWT does not substitute for this ACTIVE grant.


### No-write authenticated Onboarding acceptance

Once the HUMAN OAuth scope and Authorization grant are ACTIVE, run
`scripts/r4a_human_token_scope_smoke.py --check-onboarding`. This issues a
fresh HUMAN token in memory and posts a valid version request to a fresh
random source ID that does not exist. The owner must return HTTP 404 with
`ONB_NOT_FOUND`; this proves Gateway reachability and fine-grained owner
Authorization before the source lookup. A Gateway-level 404 or a 401/403
does not pass. The request creates no source, version or publication.
The smoke prints only status and error code, never bearer material.
