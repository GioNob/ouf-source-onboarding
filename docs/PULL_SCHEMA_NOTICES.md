# Avvisi di schema PULL e revisione HUMAN

Il contratto `ouf.schema-notice.v1` estende il POST SERVICE di sorveglianza. Il tenant proviene dal principal autenticato e la sorgente dal path. V41 identifica un incidente con la terna tenant, sorgente e `incidentKey`. La ricevuta HTTP 201 conserva `issue_id` anche su replay. Il GET SERVICE sulla stessa sorgente restituisce le ricevute del tenant per la riconciliazione.

`OPEN` conserva il riferimento atteso, il checkpoint congelato, le differenze per campo e la dichiarazione osservata. L’owner verifica il digest SHA-256 sui descrittori completi ordinati per nome, con le proprietà ordinate per chiave, e assegna `observed_schema_ref`. Il riuso della chiave con prove diverse restituisce 409. `UPDATE` usa il contatore cumulativo del produttore e il suo istante di osservazione. Duplicati o consegne in ritardo preservano i massimi già acquisiti. `RESOLVED` accetta `reason` con valore `SCHEMA_RETURNED` (default) o `REVISION_ACTIVATED`. Lo stato terminale sopravvive ai replay. UPDATE e RESOLVED prima di OPEN restituiscono 409.

## Lettura

Il Gateway inoltra la lettura HUMAN a `GET /api/onboarding/v1/schema-notices`. Il descrittore READ usa lo scope omonimo `ouf.onboarding.schema-notices.read`, allineato al tool MCP. A ogni pagina l’owner consulta la policy attiva e applica alla risorsa source i grant di `ouf.onboarding.revision.prepare`, compresi i vincoli sul padre, le scadenze e i dinieghi espliciti. Il token di sola lettura può così elencare le sorgenti per cui il soggetto ha il grant. La preparazione effettiva verifica anche lo scope COMMAND tramite il servizio di revisione.

Con `state=OPEN` (default) o `RESOLVED`, `limit` da 1 a 100 (default 20) e il cursore opaco `after`, la risposta contiene `notices`, `next` e `hasMore`. Un cursore viene emesso solo dopo aver trovato un ulteriore avviso autorizzato. La THS iniziale di Onboarding è `/trusted-human/onboarding/`. I suoi endpoint di sessione riusano l’owner locale, con CSRF sulla preparazione. La conferma e l’attivazione seguono il percorso THS esistente. Il client THS deve ricevere gli scope di lettura e, per i comandi, quelli delle relative operazioni.

## Preparazione e pubblicazione

Il comando firmato `source.onboarding.revision.prepare` accetta `observedSchemaRef` come terza alternativa a `changes` e `assetRef`. Conserva sourceId, parentVersionId, expectedVersion, configurationHash, commandKey e publicationEffect previsti dal contratto di revisione. L’owner richiede una sorgente EXTERNAL_API/PULL, il padre ACTIVE e la prova aperta dello stesso tenant, con il riferimento allo schema atteso del padre.

La candidata congela la dichiarazione in `extractionProfile.runtime.execution.sourceSchema` e aggiorna la versione e i riferimenti nel bundle. La proiezione conserva i campi ancora presenti. I campi aggiunti rimangono disponibili nello schema e richiedono un mapping per essere pubblicati come proprietà. Una rimozione elimina le corrispondenze obsolete. I tipi osservati entrano nella nuova dichiarazione, mentre le trasformazioni e le regole di riconciliazione conservate passano attraverso validazione, conferma HUMAN e attestazione Ingestion. Il ramo conserva la chiave di identità approvata. Un cambio di identità o la perdita di tutti i mapping richiedono una nuova decisione di mapping. Il ramo gestisce il formato PULL della base e i parametri API inline. La sostituzione di un profilo di unità identificato da hash/versione richiede evidenza del relativo owner.

Il journal immutabile collega la candidata all’UUID dell’avviso. Solo quella candidata può superare quel blocco. Un altro incidente bloccante continua a impedire la pubblicazione. La chiusura con `REVISION_ACTIVATED`, l’aggiornamento del padre, la pubblicazione e le proiezioni sono nella stessa transazione. Ingestion osserva la nuova pubblicazione sul feed esistente.

## Compatibilità con il produttore dello sprint-03

Il payload storico continua a ottenere i campi di ricevuta verificati da `SchemaReviewGatewayPort`. Per i nuovi invii storici l’owner deduplica anche `detail.idempotencyKey` nel tenant autenticato. I report privi di dichiarazione e differenze tipizzate conservano il gate storico, esteso alle differenze additive su PULL. Il percorso v1 di lettura e correzione richiede l’evidenza completa di OPEN.

L’outbox del task-02 deve inviare OPEN, UPDATE cumulativi e RESOLVED con la stessa chiave, esporre le differenze per campo e la dichiarazione completa, trasmettere checkpoint e istanti, e riconciliare la ricevuta v1 con `incident_key` e `issue_id`. Le righe precedenti a V41 prive di tenant verificato rimangono nel gate. Il test tra servizi deve qualificare la consegna, la pubblicazione correttiva e la ripresa dal checkpoint congelato.
