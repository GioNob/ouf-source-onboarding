# Contratto di integrazione della THS di recupero Ingestion

Ingestion emette il `reviewUrl` HTTPS dalla configurazione governata dell’origine pubblica THS. Il percorso è
`/trusted-human/ingestion-run-recovery/?runId={runId}&expectedVersion={control_version}`.
Usare l’UUID canonico e la versione di controllo decimale osservata nella stessa proiezione autorizzata del run. Emettere il link per un run in pausa con una decisione HUMAN disponibile secondo i predicati di Ingestion. Il task-50c è il punto di emissione. Il task-50 trasferisce il link nel proprio `next.human.reviewUrl` con `decisionKind: RESUME_OR_ABORT`.

Il link identifica run e versione. L’accesso richiede la sessione OAuth THS, il grant per il run e la decisione autorizzata da Ingestion. Una versione superata apre una vista informativa e richiede un nuovo link per decidere. Il link rimane legato alla versione finché Ingestion non la cambia. Se la capability MCP richiede una scadenza per l’attesa, Ingestion deve fornire una finestra di decisione governata. La scadenza dell’ultimo budget del worker ha una semantica distinta.

## Rotte da esporre nel Gateway

| Metodo | Percorso pubblico | Destinazione |
|---|---|---|
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
