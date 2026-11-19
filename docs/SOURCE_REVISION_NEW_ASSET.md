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
    }
  }
}
```

Gli argomenti sono chiusi. Si specifica esattamente uno tra `assetRef` e il preesistente `changes`. Entrambi gli UUID di `assetRef` sono obbligatori. Il file deve essere diverso dall'asset del padre, in stato `PROFILED`, dello stesso tenant e soggetto, libero oppure già associato alla stessa sorgente. Un upload libero viene associato alla sorgente nella transazione di preparazione. Dopo una preparazione riuscita il legame di custodia resta valido anche in caso di rifiuto HUMAN. Il profilo deve appartenere esattamente al file indicato. Per gli asset storici si applicano le prove di titolarità già accettate dal servizio.

## Compatibilità e diagnostica

Il percorso copre il CSV gestito con adapter `managed-tabular-v1`, mapping di proprietà esplicito e chiave `NATIVE_KEY` singola. La chiave attiva deve essere presente, univoca e valorizzata nel nuovo profilo. La proiezione e il mapping devono coprire gli stessi nomi. Il confronto usa nomi esatti e tollera un ordine differente. Ogni colonna aggiunta o rimossa blocca la preparazione, comprese quelle escluse. Per una colonna mappata si richiede il medesimo tipo inferito e una trasformazione compatibile. Una variazione di tipo su una colonna esclusa non influenza la proiezione.

Il cambio usa il delimitatore rilevato dal profiler e conserva i limiti di esecuzione approvati. Controlla il numero di righe e colonne rispetto a tali limiti. La conversione lessicale esatta, compresi i separatori decimali, le date e i limiti dei singoli valori, resta soggetta alla verifica dell'Ingestion Runtime sui dati effettivi. I contratti spaziali, relazionali, di vocabolario, multiformato e le chiavi dipendenti dall'ordine delle righe richiedono qualifica separata.

Su incompatibilità l'owner restituisce HTTP 422 con `code=ONB_REVISION_CSV_INCOMPATIBLE` e `diagnostics[]`. Ogni elemento ha i campi `code`, `column`, `expected`, `actual`. I campi non applicabili possono essere null. Esempio di due differenze restituite nella stessa risposta:

```json
{
  "code": "ONB_REVISION_CSV_INCOMPATIBLE",
  "diagnostics": [
    {"code":"MISSING_MAPPED_COLUMN","column":"amount","expected":"DECIMAL","actual":null},
    {"code":"NEW_COLUMN","column":"total","expected":null,"actual":"STRING"}
  ]
}
```

Gli altri codici diagnostici sono `MISSING_COLUMN`, `TYPE_CHANGED`, `TRANSFORM_TYPE_MISMATCH`, `KEY_NOT_UNIQUE_OR_NULL`, `EXECUTION_LIMIT_EXCEEDED` e `UNSUPPORTED_CSV_CONTRACT`. MCP deve preservare tutte le differenze. La diagnostica comprende metadati di schema, senza campioni o valori di cella.

## Esito, replay e approvazione

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
