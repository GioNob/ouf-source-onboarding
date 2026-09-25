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
