# Registro della configurazione di Source Onboarding

## Attesa umana del selettore dei file (D-065, D-089)

| Parametro | Proprietà Spring | Variabile d'installazione | Default | Valori ammessi |
|---|---|---|---:|---|
| `waitMaxSeconds` | `ouf.onboarding.wait-max-seconds` | `OUF_ONB_WAIT_MAX_SECONDS` | 3600 s | Intero da 1 a 3600 |
| `handoffRetentionSeconds` | `ouf.onboarding.handoff-retention-seconds` | `OUF_ONB_HANDOFF_RETENTION_SECONDS` | 86400 s | Intero positivo a 32 bit |

Onboarding valida entrambi i valori all'avvio. Il primo fissa `waitUntil` per ciascuna nuova emissione. Il secondo stabilisce da quando è consentita la pulizia di una ricevuta conclusa, misurando il tempo da `completedAt`. Il link del selettore usa `ouf.authorization.ths.public-origin`, l'origine HTTPS delle challenge.

Acquisire e ripristinare i due parametri e l'origine con la configurazione dell'installazione. Sono dati privi di segreti. Includere la tabella `ouf_onboarding.managed_file_handoff` nel backup coerente dello schema e della cronologia Flyway. Dopo il ripristino, verificare con un'identità autorizzata che chiave di comando, versione e scadenza di una ricevuta campione corrispondano alla cattura. La configurazione ripristinata si applica alle nuove emissioni.

## Registro delle capability di Authorization

| Campo | Valore |
|---|---|
| Identificativo e versione | `authorization.capability-registration/v1` |
| Componente responsabile | Authorization in Source Onboarding |
| Archivio e classe | Tabella PostgreSQL `ouf_authorization.capability_registration`. Configurazione, tutte le righe. |
| Contenuto | Chiave primaria `capability_id`, `owner_ref`, descrittore JSON con ID, operazione, scope e attori. `registered_by` e `registered_at` conservano la provenienza e il momento della registrazione. Il trigger V16 garantisce l'immutabilità. |
| Segreti | No. La cattura è privata perché può contenere identificativi degli amministratori. |
| Cattura | Includere l'intero schema `ouf_authorization` nel backup PostgreSQL coerente del database Onboarding (`pg_dump` in formato custom), insieme alla cronologia Flyway. Conservare privatamente dump, checksum e distinta ordinata per chiave con conteggio e impronta dei contenuti, inclusa la provenienza. Includere le registrazioni amministrative e quelle di rilascio. |
| Ripristino e dipendenze | Predisporre ruoli PostgreSQL e database di destinazione vuoto. Con l'applicazione ferma, ripristinare lo snapshot completo con `pg_restore --single-transaction`, nell'ordine delle dipendenze del dump. Registro, bundle, puntatore ACTIVE, ruoli, audit e cronologia Flyway devono provenire dalla stessa cattura. Avviare l'artefatto di rilascio corrispondente dopo il controllo dei dati. |
| Prova di ripristino | Su un'istanza isolata confrontare conteggio, chiavi, owner, descrittori e provenienza con la distinta privata. Verificare il riferimento e l'hash di ACTIVE, la corrispondenza di ogni suo descrittore col registro e il secondo avvio senza inserimenti. La prova su un secondo host resta da eseguire. |

### Ricerca semantica a lotti (D-090, tranche T1)

Il rilascio registra `ouf.semantic.search.batch` per l'owner `semantic` con operazione `READ`, scope `ouf.semantic.search` e attore `HUMAN`. La provenienza della nuova riga è `release:catalogue/r4a-semantic-human-capabilities.json`. Si applicano lo schema V16 e la procedura di cattura e ripristino della tabella descritta sopra. Il descrittore e il grant di catalogo sono dati di rilascio comuni a tutte le installazioni.

Per abilitare il lotto nel ruolo `admin`, la proposta `REPLACE_ROLES` conserva la ricerca singola e aggiunge il permesso per il lotto. La THS mostra il descrittore aggiunto e il ruolo proposto. La conferma umana del diff pubblica il bundle e aggiorna ACTIVE nella stessa transazione (D-071).

Tracciabilità PET (D-082), D-090 T1. Nel job CI `java21-postgresql17`, `PermissionProposalRuntimeTest.releaseRegistersOnlyNewDescriptorsAndSecondStartupIsReadOnly` verifica la provenienza e il secondo avvio in sola lettura. `PermissionProposalRuntimeTest.batchSearchEntersExistingAdminRoleOnlyAfterThsConfirmation` verifica il diff del solo lotto, la conservazione di ACTIVE durante la proposta e la pubblicazione dopo conferma THS.

## Source mapping and publication (D-065)

| Entry | Owner and contents | Credentials | Capture and restore | Restore check |
|---|---|---|---|---|
| `onboarding.semantic-mapping.v1` | Source Onboarding owns the reviewed mapping in `ouf_onboarding.onboarding_version.configuration`. The version pins source type, target classes, optional controlled category and semantic references. | The category contains two public IRIs. Treat the enclosing configuration snapshot as private. Credential references must keep their original bindings. | Capture the configuration JSON in a consistent database snapshot with source/version IDs, parentage, hashes, approval records and referenced semantic revision/publication IDs. Restore the referenced revisions and the version graph before dependent publications. | Recompute the version hash where present. Resolve the pinned semantic references and compare every target class and its optional category against the captured mapping. |
| `onboarding.published-mapping.v1` | Source Onboarding owns the mapping embedded in `ouf_onboarding.published_configuration.bundle` for UDP consumption. Depends on `onboarding.semantic-mapping.v1`. | Same handling as the reviewed configuration. | Capture the original bundle with its publication row, version link, checksum and lifecycle state in the same snapshot. Restore the captured rows and links. | Verify checksum and version link, then compare the published mapping with the approved version, including category absence. |
| `ouf.onboarding.publication.max-runtime-envelope-bytes` | Source Onboarding installation setting in bytes. Default `2097152` (2 MiB), the lower known runtime read limit. Set at or below the smallest installed Ingestion and UDP publication read capacity, including Gateway limits on those routes. The check counts UTF-8 JSON bytes for the whole runtime envelope, including D-084 pins, embedded definitions, value maps, the longest valid source status and the framing of the single-item discovery page. | No. | Capture and restore the effective setting with the installation configuration, alongside the runtime and Gateway read limits. | Verify that review and activation reject a body over the restored limit and that active and exact reads return the admitted envelope. A blocked candidate can be withdrawn and replaced with a smaller revision. |

## Managed file acquisition limits (D-065)

The following Spring settings are integers under `ouf.onboarding.acquisition-limits.`. All seven are non-secret installation configuration. Capture the effective values with the installation configuration backup. Restore them before starting Onboarding and verify a new review snapshot against a known file. Frozen reviews retain their original basis. The environment overrides below use the prefix `OUF_ONB_ACQUISITION_LIMITS_`.

| Property suffix | Environment suffix | Default | Inclusive range |
|---|---|---:|---:|
| `max-rows` | `MAX_ROWS` | 1000000 | 1–1000000 |
| `max-columns` | `MAX_COLUMNS` | 1024 | 1–10000 |
| `max-cell-chars` | `MAX_CELL_CHARS` | 1000000 | 1–1000000 |
| `margin-percent` | `MARGIN_PERCENT` | 25 | 0–1000 |
| `min-headroom-rows` | `MIN_HEADROOM_ROWS` | 100 | 0–1000000 |
| `min-headroom-columns` | `MIN_HEADROOM_COLUMNS` | 8 | 0–10000 |
| `min-headroom-cell-chars` | `MIN_HEADROOM_CELL_CHARS` | 1024 | 0–1000000 |

## Controlled category contract (D-059)

The local [semantic mapping profile](../contracts/onboarding/semantic-mapping-category-v1.json) extends the frozen RC3 schema. A human selects the canonical class and may declare its category on the target class. For discovery, the optional JSON body of `POST …/mapping/build-draft` accepts `category` for the configured target class. For managed files, pass the same optional field beside `targetClassIri` in the create-onboarding or MCP draft request.

```json
{
  "ontologyId": "example",
  "ontologyVersion": "1",
  "classIri": "urn:example:PointOfInterest",
  "category": {
    "schemeIri": "urn:example:place-types",
    "conceptIri": "urn:example:kiosk"
  }
}
```

Both IRIs are required and absolute. Extra keys, explicit nulls and malformed values produce `ONB_MAPPING_CATEGORY_INVALID`. Repeated target entries for one class must agree, including absence. If `extractionProfile.runtime.udp.resolution.governedIdentity.category` is present, both IRIs must match the target for its canonical class. Conflicts produce `ONB_MAPPING_CATEGORY_CONFLICT`.

Onboarding pins semantic references and, for managed execution, their published revisions and set. These bindings carry no concept-to-scheme membership assertion. The human verifies membership against the published vocabulary when reviewing the class, scheme and concept in THS.

Publication carries the approved pair at `semanticMapping.targetClasses[].category`. UDP scopes identity comparison by tenant, exact canonical class and concept IRI. Omitting the field limits comparison to uncategorized objects of the same class and tenant. Historical mappings need no backfill.
