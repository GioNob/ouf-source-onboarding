# Nuovo file CSV su una sorgente attiva

## Contratto owner per MCP

Estensione di `source.onboarding.revision.prepare`. Il trasporto owner è `POST /api/internal/v1/onboarding/source-revision-mcp/prepare`, con corpo JSON, receipt `X-OUF-Source-Revision-Receipt` legata a corpo, metodo, path e capability. Serve un delegato HUMAN con scope e grant attivo `ouf.onboarding.revision.prepare` per la sorgente e la versione padre. Il tenant e il soggetto provengono dalla delega verificata. Il cambio di file richiede anche la prova di titolarità degli asset in Onboarding. Il catalogo usa il permesso di revisione esistente.

Prima della preparazione, leggere il padre con `POST /api/internal/v1/onboarding/source-revision-mcp/read`, capability `ouf.onboarding.revision.read` e `Arguments` contenente `sourceId` e `onboardingVersionId`. Conservare `configurationHash`, `lockVersion` ed ETag. Il nuovo file deve essere già disponibile tramite l'intake gestito. Scegliere l'UUID del profilo immutabile dall'esito della profilazione o dall'anteprima.

Esempio di corpo con identificativi illustrativi. La richiesta porta anche `If-Match: W/"ov:7"`, nello stesso formato del GET nativo della versione. Il gateway può ricostruirlo dal valore intero firmato di `expectedVersion`.

```json
{
  "Arguments": {
    "sourceId": "source-example",
    "parentVersionId": "00000000-0000-4000-8000-000000000001",
    "expectedVersion": 7,
    "configurationHash": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "commandKey": "second-upload-1",
    "publicationEffect": "NEW_PUBLICATION_MAY_START_ACQUISITION",
    "assetRef": {
      "assetId": "00000000-0000-4000-8000-000000000002",
      "profileId": "00000000-0000-4000-8000-000000000003"
    },
    "newColumns": [
      {"column": "note", "decision": "EXCLUDE"},
      {
        "column": "prezzo",
        "decision": "MAP",
        "mapping": {
          "propertyIri": "urn:example:prezzo",
          "transform": "DECIMAL_DOT_V1",
          "reconciliation": {
            "policyRef": "urn:ouf:policy:linked-object-property-reconciliation:v1",
            "cardinality": "SINGLE",
            "normalizerRef": "EXACT_TYPED",
            "normalizerVersion": 1,
            "validity": "UNBOUNDED"
          }
        }
      }
    ]
  }
}
```

Gli argomenti sono chiusi. Si specifica esattamente uno tra `assetRef` e il preesistente `changes`. Entrambi gli UUID di `assetRef` sono obbligatori. Il file deve essere diverso dall'asset del padre, in stato `PROFILED`, dello stesso tenant e soggetto, libero oppure già associato alla stessa sorgente. Un upload libero viene associato alla sorgente nella transazione di preparazione. Dopo una preparazione riuscita il legame di custodia resta valido anche in caso di rifiuto HUMAN. Il profilo deve appartenere esattamente al file indicato. Per gli asset storici si applicano le prove di titolarità già accettate dal servizio.

## Compatibilità e diagnostica

Il percorso copre il CSV gestito con adapter `managed-tabular-v1`, mapping di proprietà esplicito e chiave `NATIVE_KEY` singola. La chiave attiva deve essere presente, univoca e valorizzata nel nuovo profilo. La proiezione e il mapping devono coprire gli stessi nomi. Il confronto usa i nomi esatti dei due profili, comprese le colonne già escluse, e tollera il riordino del CSV. Una colonna rimossa blocca la preparazione. Per le colonne già mappate servono lo stesso tipo inferito e una trasformazione compatibile. Una variazione di tipo su una colonna già esclusa non influenza la proiezione.

### Campi da aggiungere al server MCP (PR #87)

Nel ramo `assetRef` aggiungere ad `Arguments` l'array `newColumns`, allo stesso livello di `assetRef`. Il ramo `changes` conserva il contratto precedente. Il client può omettere l'array se i profili non presentano aggiunte. Per ogni nome presente solo nel nuovo profilo deve inviare una voce. Il limite è di 1.024 voci e l'intero corpo firmato deve rientrare nei 65.536 byte del trasporto owner.

| Campo | Contratto |
|---|---|
| `column`, `decision` | Nome esatto della colonna nuova e valore `EXCLUDE` o `MAP`. Per `EXCLUDE` omettere `mapping`. I byte dell'asset conservano la colonna come evidenza. |
| `mapping.propertyIri`, `mapping.transform` | Obbligatori per `MAP`. IRI assoluto e distinto per ogni proprietà. `IDENTITY` conserva la cella lessicale come stringa. `DECIMAL_DOT_V1` e `DECIMAL_COMMA_V1` richiedono `LONG` o `DECIMAL`. `DATE_ISO_V1` richiede `DATE`, `DATE_DMY_V1` richiede `STRING`. Il datatype canonico deriva dalla trasformazione. |
| `mapping.reconciliation` | Oggetto obbligatorio, con le stesse regole di `revision.prepare.changes[].reconciliation`. Il validatore applica alla proprietà compilata i vincoli su normalizzazione, cardinalità, validità, revisione e cancellazione. |
| `mapping.accessLabel`, `mapping.authorityOrder` | Opzionali. La classificazione ammette `OPEN`, `ANONYMOUS`, `PERSONAL`, `SENSITIVE`, `RESTRICTED` (default). La lista ordinata di autorità ha default `[]` e ammette fino a 32 riferimenti distinti. |
| `mapping.comparison` | Obbligatorio soltanto se il padre usa `governedIdentity`, che richiede il confronto di ogni proprietà canonica. Indicare `comparator` (`CONCEPT`, `TEXT_V1`, `DECIMAL_V1` o `JSON_V1`) e `assertionRef`. L'owner lega il segnale all'IRI e al set semantico già fissato. Le regole sufficienti, le regole di distinzione e la chiave della sorgente restano quelle approvate. |

Per il mapping dell'esempio, un padre con identità governata richiede anche `"comparison": {"comparator":"DECIMAL_V1","assertionRef":"urn:example:price-comparison"}` dentro `mapping`. MCP deve raccogliere la decisione per ogni colonna e inoltrare il corpo firmato. Il servizio aggiunge alla proiezione solo le colonne con `MAP`, nell'ordine della richiesta. Nella materializzazione UDP il campo sorgente è l'IRI canonico prodotto dal mapping semantico.

Il cambio usa il delimitatore rilevato dal profiler e conserva i limiti di esecuzione approvati. Controlla il numero di righe e colonne rispetto a tali limiti. La conversione lessicale esatta, compresi i separatori decimali, le date e i limiti dei singoli valori, resta soggetta alla verifica dell'Ingestion Runtime sui dati effettivi. I contratti spaziali, relazionali, di vocabolario, multiformato e le chiavi dipendenti dall'ordine delle righe richiedono qualifica separata.

Su incompatibilità l'owner restituisce HTTP 422 con `code=ONB_REVISION_CSV_INCOMPATIBLE` e `diagnostics[]`. Ogni elemento ha i campi `code`, `column`, `expected`, `actual`. I campi non applicabili possono essere null. Esempio di due differenze restituite nella stessa risposta:

```json
{
  "code": "ONB_REVISION_CSV_INCOMPATIBLE",
  "diagnostics": [
    {"code":"MISSING_MAPPED_COLUMN","column":"amount","expected":"DECIMAL","actual":null},
    {"code":"NEW_COLUMN_DECISION_REQUIRED","column":"total","expected":null,"actual":"STRING"}
  ]
}
```

Gli altri codici diagnostici sono `MISSING_COLUMN`, `TYPE_CHANGED`, `TRANSFORM_TYPE_MISMATCH`, `KEY_NOT_UNIQUE_OR_NULL`, `EXECUTION_LIMIT_EXCEEDED` e `UNSUPPORTED_CSV_CONTRACT`. Le decisioni aggiungono `COLUMN_NOT_NEW`, `DUPLICATE_NEW_COLUMN_DECISION`, `INVALID_NEW_COLUMN_DECISION`, `DUPLICATE_TARGET_PROPERTY` e `INVALID_NEW_COLUMN_COMPARISON`. Una regola incompatibile con la configurazione compilata blocca il submit con HTTP 422 `ONB_VALIDATION_FAILED`. MCP deve preservare la diagnostica e il nome della colonna che richiede una scelta.

## Esito, replay e approvazione

Per il cambio file, aggiungere `newColumns` anche allo schema della ricevuta MCP di prepare/read. La risposta ripropone le scelte registrate per la specifica versione, con un array vuoto per i cambi file storici. La THS le mostra sulla stessa scheda di approvazione HUMAN, inclusi proprietà, trasformazione e regole per le colonne mappate. La migrazione V40 conserva le scelte nel journal append-only insieme al fingerprint del comando.

Il fingerprint include l'intero array, il suo ordine e i campi annidati di `mapping`. A parità di chiave, cambiare una decisione, una regola o l'ordine restituisce `409 ONB_REVISION_COMMAND_CONFLICT`. Anche `[]` e l'omissione sono corpi distinti. L'omissione conserva il fingerprint dei comandi precedenti a V40.

HTTP 200 restituisce la ricevuta di revisione e l'ETag della candidata. Comprende `sourceId`, `parentVersionId`, `parentConfigurationHash`, `onboardingVersionId`, `configurationHash`, `lockVersion`, `assetRef`, `diagnostics: []`, `publicationEffects`, `challengeId`, `challengeStatus`, `expiresAt` e `reviewUrl`. Al primo successo `state=IN_REVIEW` e `humanApprovalRequired=true`. Questo è lo stato nativo della candidata congelata. Il padre rimane `ACTIVE` durante la review.

Nella stessa transazione il servizio clona il padre, lega asset e profilo, aggiorna selezione, runtime, hash, dimensione e riferimenti allo schema, valida, congela e apre la challenge. L'ID logico dell'extraction profile conserva la propria storia e avanza di versione. L'asset del caricamento originario nei metadati della sorgente resta una prova di provenienza. Il file della singola versione si legge da `extractionProfile.selection`. Il journal append-only registra profilo sostitutivo, sorgente, padre, candidata, soggetto, tenant e fingerprint. Gli eventi nativi di clone, modifica, validazione, submit, challenge, decisione e attivazione mantengono la correlazione.

La chiave è stabile per tenant e soggetto, lunga 1–128 caratteri e conforme a `[A-Za-z0-9][A-Za-z0-9._:-]{0,127}`. In caso di risposta persa leggere `/read` con `Arguments={sourceId,parentVersionId,commandKey}`. Una ricevuta esistente restituisce la stessa versione e challenge nel loro stato corrente. Un replay esatto usa il corpo, la chiave e l'ETag **originali**. Una risposta `commandState=ABSENT` non autorizza la rotazione della chiave. La firma di trasporto deve essere valida per ogni chiamata. La riconciliazione non rinnova la challenge o il budget di readiness.

Il link conduce alla THS. La conferma HUMAN usa l'hash e il lock della candidata. L'attivazione è un'azione HUMAN separata con `ouf.onboarding.configuration.write`, readiness Ingestion sullo stesso hash e controlli di identità UDP previsti dalla configurazione. Alla pubblicazione l'owner verifica ancora padre, profilo, custodia e stato del file. L'attivazione rende la nuova pubblicazione eleggibile per l'acquisizione. La stabilità degli UUID degli oggetti si verifica in UDP durante l'E2E.

| HTTP | Codici e comportamento MCP |
|---|---|
| 400 / 422 | `ONB_REVISION_REQUEST_INVALID`, `ONB_REVISION_ARGUMENTS_INVALID`, errori di validazione o incompatibilità. Restituire il motivo e la diagnostica. |
| 403 | `ONB_REVISION_RECEIPT_INVALID`, `ONB_REVISION_AUTHORIZATION_DENIED`, `ONB_REVISION_SOURCE_SCOPE_UNPROVEN`, oppure diniego di titolarità del file. L'identità proviene dalla delega. |
| 404 | `ONB_FILE_PROFILE_NOT_FOUND` per un profilo che non appartiene all'asset autorizzato. |
| 409 | `ONB_REVISION_ASSET_NOT_PROFILED`, `ONB_REVISION_ASSET_SOURCE_CONFLICT`, `ONB_REVISION_NO_CHANGE`, `ONB_REVISION_OPEN_VERSION_EXISTS` o `ONB_REVISION_COMMAND_CONFLICT`. In attivazione `ONB_REVISION_ASSET_DRIFT` blocca la pubblicazione. |
| 412 / 428 | `ONB_REVISION_PARENT_DRIFT` per ETag discordante, padre superato, lock o hash obsoleto. `ONB_REVISION_ETAG_REQUIRED` per header assente nel cambio asset. |
| 503 / esito di trasporto incerto | Riconciliare con la chiave originale. Gli errori permanenti o di integrità richiedono intervento. |
