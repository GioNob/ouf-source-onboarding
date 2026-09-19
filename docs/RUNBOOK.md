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

## Installazione IAM, ChatGPT e policy MCP — checkpoint 19 settembre 2026

Seguire la [guida operativa Keycloak / ChatGPT / MCP](https://github.com/GioNob/ouf-mcp-server/blob/fix/constrained-policy-cache-roundtrip/docs/INSTALLAZIONE_KEYCLOAK_CHATGPT.md) per client,
scope, audience, attributi utente, Device Flow amministrativo dal server,
registrazione capability, draft, ETag, pubblicazione e rollback monotono.
Il documento indica sempre browser del PC oppure terminale SSH del server.

Il [verbale bootstrap IAM](history/IAM_BOOTSTRAP_PRODUCTION_ACCEPTANCE_2026-09-18.md)
resta l'evidenza storica della prima attivazione, non lo stato finale della
successiva prova ChatGPT. In tale prova la policy v4 è stata pubblicata ma
rifiutata dalla cache MCP; l'operatore ha pubblicato v5 ripristinando il contenuto
v3. La registrazione di `ouf.system.status` non è cancellata dal rollback.

La [PR MCP #27](https://github.com/GioNob/ouf-mcp-server/pull/27) corregge il
round-trip dei vincoli opzionali. Al checkpoint non è stata distribuita.
Il dettaglio `PUBLIC_OPERATIONAL` rimane un blocco di accettazione distinto:
non allargare grant o rimuovere vincoli per aggirarlo. La guida contiene
la matrice dei risultati effettivi e le verifiche ancora da eseguire.

Il collegamento punta al branch della PR finché non è mergiata; non implica
che il contenuto sia già su main o in produzione.
