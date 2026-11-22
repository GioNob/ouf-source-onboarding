# Contratto di integrazione della THS di recupero Ingestion

Onboarding emette `reviewUrl` e `expiresAt` da `POST /api/internal/v1/onboarding/ths-links/ingestion-run-recovery`. Il MCP invia via Gateway il corpo `{sourceId, runId, expectedVersion}`, con UUID in forma canonica minuscola e versione di controllo come intero decimale JSON tra 0 e 9007199254740991. I valori provengono dalla stessa proiezione autorizzata del run. Il Gateway firma il metodo, il percorso, il corpo effettivo e la capability `ouf.onboarding.ths-links.ingestion-run-recovery` con la stessa ricevuta HUMAN delle rotte managed-file (`X-OUF-Managed-File-Receipt`, scopo `managed-file-owner`). Onboarding richiede una prova durevole della proprietà tenant e soggetto della fonte gestita.

Il percorso è `/trusted-human/ingestion-run-recovery/?runId={runId}&expectedVersion={expectedVersion}` sull’origine HTTPS `ouf.authorization.ths.public-origin`, condivisa con le challenge. Con origine mancante o non valida, l’endpoint restituisce `503 ONB_THS_LINK_ORIGIN_REQUIRED`. La durata di presentazione `ouf.onboarding.ths-links.ingestion-run-recovery.presentation-ttl` accetta una durata ISO-8601 positiva ed è `PT10M` per default. `expiresAt` in UTC indica fino a quando il client può presentare il link ricevuto. L’accesso e la decisione richiedono sessione OAuth THS, grant per il run, stato e versione verificati da Ingestion. Una versione superata apre una vista informativa e richiede un nuovo link per decidere.

Ingestion espone `decisionDeadline` in `status` e `history` per ogni run la cui `recoveryAction` richiede un umano. È la fine della finestra di decisione in formato ISO-8601 UTC e alimenta il `deadline` del `WAIT_FOR_HUMAN` del MCP. Per `HUMAN_RESUME_REQUIRED`, il MCP chiede a Onboarding il link da usare in `next.human.reviewUrl` con `decisionKind: RESUME_OR_ABORT`. La durata di presentazione del link e il budget del worker hanno scadenze distinte.

## Rotte da esporre nel Gateway

| Metodo | Percorso | Destinazione |
|---|---|---|
| POST | `/api/internal/v1/onboarding/ths-links/ingestion-run-recovery` | Onboarding, invocazione MCP con ricevuta Gateway HUMAN `ouf.onboarding.ths-links.ingestion-run-recovery` |
| GET | `/trusted-human/ingestion-run-recovery/` | Onboarding, sessione THS |
| GET | `/trusted-human/ingestion-run-recovery/recovery.js`, `/trusted-human/ingestion-run-recovery/recovery.css` | Onboarding, sessione THS |
| GET | `/trusted-human/ingestion-run-recovery/api/{runId}/{expectedVersion}` | Onboarding, sessione THS |
| POST | `/trusted-human/ingestion-run-recovery/api/{runId}/{expectedVersion}/resume`, `/trusted-human/ingestion-run-recovery/api/{runId}/{expectedVersion}/abort` | Onboarding, sessione THS e CSRF |
| GET | `/api/trusted-human/v1/ingestion/runs/{id}` | Ingestion, HUMAN `ingestion.run.read` |
| POST | `/api/trusted-human/v1/ingestion/runs/{id}/resume` | Ingestion, HUMAN `ingestion.run.resume` |
| POST | `/api/trusted-human/v1/ingestion/runs/{id}/abort` | Ingestion, HUMAN `ingestion.run.abort` |

Onboarding usa la base Gateway THS già configurata e inoltra il token HUMAN solo al percorso owner previsto. Prima di inoltrare una decisione rilegge il run, verifica versione e stato `PAUSED`, poi invia `{expectedVersion}`. Ingestion ricontrolla autorizzazione, prerequisiti e versione. `resume` usa il profilo finito già esposto nell’API HUMAN, 128 tentativi entro 24 ore. Dopo un esito incerto la pagina offre una sola rilettura su richiesta dell’utente. Il nuovo tentativo richiede una nuova conferma esplicita.

## Evidenze nella scheda

La GET HUMAN già espone stato, `failure_code`, causa e azione del lake e `recoveryBudget` quando presente. La scheda mostra i tentativi residui e cumulativi e la scadenza dell’ultimo budget.

Per mostrare gli effetti con evidenza, l’owner può aggiungere `effects` alla stessa GET. La proiezione proposta contiene tre contatori facoltativi non negativi. `lakeWritesConfirmed` conta le scritture confermate dal deposito. `handoffsAcknowledged` conta le consegne con conferma. `handoffsUnconfirmed` conta le consegne con esito da verificare. Un campo mancante è informazione indisponibile. Nel checkout Ingestion consultato la GET HUMAN espone la causa del lake ma manca la proiezione dei contatori. La scheda dichiara questa lacuna e mostra ogni contatore disponibile. L’owner deve definire l’unità di conteggio in base alle evidenze persistenti prima dell’integrazione.
