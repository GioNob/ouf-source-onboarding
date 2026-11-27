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

## Attributi di risorsa delle capability (D-065, D-071, D-081)

| Voce | Registrazione |
|---|---|
| Owner e versione | Source Onboarding, `catalogue/resource-attributes-capabilities.json`, formato 1. Il rilascio include lo stesso file nel classpath dell’immagine. |
| Contenuto | Per ogni capability dichiarata, attributi che l’owner può fornire in almeno uno dei percorsi di decisione. Una lista vuota esclude entrambi gli attributi controllati. Una chiave assente indica una capability non verificabile. |
| Validazione | All’avvio sono controllati versione, struttura, tipi e duplicati. Alla proposta, `allowedDataLabels` richiede `dataAccessLabel` e `allowedDetailLevels` richiede `detailLevel` quando le rispettive liste contengono valori. |
| Acquisizione e ripristino | Conservare la revisione del repository e il digest dell’immagine. Ripristinare l’immagine corrispondente insieme allo snapshot di Authorization. Il catalogo contiene solo dati di rilascio. |
| Verifica | Confrontare il file nel JAR con quello della revisione ripristinata. `PermissionConstraintCatalogueTest` verifica copertura dei descrittori di rilascio, confronto per ruolo, attributi supportati e limite delle violazioni. |

La dichiarazione Semantic usa liste vuote per le capability di ricerca, discovery, proposta e revisione. Il controllo riguarda gli attributi di accesso. Il solo attributo tecnico `module` di Semantic non soddisfa alcuno dei due vincoli. Le capability esterne prive di evidenza restano senza dichiarazione.

Per Onboarding la provenienza è la seguente.

| Capability o gruppo | Attributi e percorso owner |
|---|---|
| `ouf.managed-source.onboarding.read`, `ouf.managed-source.onboarding.review.prepare`, `ouf.onboarding.configuration.write` | `onboardingVersionId` in `ManagedOnboardingReviewService.authorize`. |
| `ouf.onboarding.revision.read`, `ouf.onboarding.revision.prepare` | `onboardingVersionId` in `SourceRevisionService.permitted`. |
| `ouf.onboarding.configuration.read`, `ouf.ingestion.configuration.attest` | `module` e `onboardingVersionId` in `ManagedOnboardingReviewService` sulle configurazioni pubblicate. Per la lettura, `RuntimePublicationApi.require` fornisce anche `sourceRef` quando la fonte è specificata. Il descrittore di attestazione ha owner Onboarding in `catalogue/r4a-onboarding-lifecycle.json`. |
| `ouf.managed-source.file.profile`, `ouf.onboarding.quarantine.release` | `module`, `assetRef`, `jobRef`, `detailLevel` nei percorsi di recovery di `ManagedFileService.profileRecoveryResource` e `ManagedFileOwnershipRecovery.resource`. |
| `ouf.ths.log.*` elencate | `module` e `detailLevel` in `ProtectedLogApi.protectedActor`. La risorsa associata alla richiesta alimenta anche `ServletAuthorization.resolve` per i permessi accessori `pii.read`, `export.read.any` e `download.any` controllati in `ProtectedLogService`. |
| `authorization.permissions.*`, `authorization.proposal.read`, `authorization.policy.admin` | Mappa vuota in `PermissionProposalService.delegated`, `AuthorizationAdminService` e `AuthorizationReviewService.authorized`. Bootstrap e lettura bundle controllano principal e scope in `BootstrapAdministrator` e `AuthorizationBundleApi`. |
| `ouf.onboarding.ths-links.*` | `IngestionRunRecoveryLinkApi.verifyDelegation` verifica la ricevuta per la capability senza aggiungere attributi di risorsa. |
| Altre voci locali con lista vuota | Operazioni fonte, discovery, mapping e file dichiarate in `openapi/onboarding-v1.yaml`, lettura staging in `ManagedFileStagingApi`, quarantena in `ManagedFileQuarantineTrustedHumanApi` e configurazione installazione in `InstallationBootstrapApi` e `InstallationProjectionExportApi`. Il percorso servlet usa la mappa vuota di `ServletAuthorization.resolve` in assenza di una risorsa esplicita. |

La ricevuta, lo status e la scheda usano la proposta e la fotografia dei ruoli della sua transazione. Per una proposta diretta precedente alla fotografia dei ruoli, gli avvisi sui grant compilati conservano il loro `grantId`. Una proposta pendente precedente al rilascio viene ricontrollata prima della pubblicazione. La scheda consente di rifiutarla se richiede correzioni.

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

Row and column ceilings use the selected CSV table, worksheet or feature layer. For CSV, the cell measurement counts UTF-16 units in raw data fields. Earlier CSV profiles, XLSX worksheets and feature layers have no comparable cell measurement. Their cell ceiling is the installation maximum. The approved value and its basis are recorded in the THS snapshot.

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

## Managed file extraction units (D-065, D-080)

V47 stores each unit’s source in `ouf_onboarding.managed_file_unit_binding`, keyed by `(asset_id, unit_ref)`. The DRAFT transaction also retains the first source in `managed_file_asset.source_id`. The `managed_file_effective_binding` view resolves predecessor writes from retained selections.

`ouf.onboarding.units.multiple-sources-per-asset` (`OUF_ONB_UNITS_MULTIPLE_SOURCES_PER_ASSET`) defaults to `false`. Enable it through the approved installation profile only once the rollback predecessor reads unit bindings.
## XLSX profiling operations (D-065, D-080, task-62f)

| Store | Content | Secrets | Capture | Restore |
|---|---|---|---|---|
| `ouf_onboarding.file_profile_job.operation_key`, `read_options` (V48) | For XLSX jobs only: `xlsx-v1:` plus the canonical SHA-256 of the effective options, and the options themselves (`reader`, declared `headerRows`, installation `timeZone` at request time). Unique per asset and operation. NULL for CSV, GeoPackage and predecessor jobs | No | Database backup of the `ouf_onboarding` schema | Restored with the schema; the worker profiles a job with its frozen options, never the current installation zone |

`ouf.onboarding.files.xlsx.max-profile-operations` (`OUF_ONBOARDING_FILES_XLSX_MAX_PROFILE_OPERATIONS`, default 8, range 1–64) bounds the XLSX operations per asset: each one is a new job with its own budget, and the profile worker serves one job at a time. Beyond the bound the request returns `422 ONB_PROFILE_OPERATION_LIMIT`; a replay of an existing operation is still served.

The predecessor ignores both columns. On an XLSX asset with more than one operation it stops with `ONB_PROFILE_HISTORY_RECONCILIATION_REQUIRED` (closed failure); CSV and GeoPackage keep one historical operation per asset. A pending job stopped this way quarantines the asset; after the return, the HUMAN profiling recovery grants a budget per operation and resumes it. If the predecessor completes a pending XLSX operation with its historical reader, that operation keeps the historical profile (`inventoryComplete=false`, not activatable): profile again with different options or upload the file again.

## Managed file intake and XLSX lexical reader (D-065)

Source Onboarding validates these installation settings at startup. Capture and restore the effective values with the installation profile. Keep media types and file sizes aligned with the Gateway. All entries are non-secret.

| Property | Environment | Default | Accepted values or scope |
|---|---|---|---|
| `ouf.onboarding.files.media-types` | OUF_ONBOARDING_FILES_MEDIA_TYPES | text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet | Comma-separated supported formats. Add application/geopackage+sqlite3 for GPKG. |
| `ouf.onboarding.files.csv.max-bytes` | OUF_ONBOARDING_FILES_CSV_MAX_BYTES | 10485760 | 1–10485760 bytes |
| `ouf.onboarding.files.xlsx.max-bytes` | OUF_ONBOARDING_FILES_XLSX_MAX_BYTES | 10485760 | 1–10485760 bytes |
| `ouf.onboarding.files.gpkg.max-bytes` | OUF_ONBOARDING_FILES_GPKG_MAX_BYTES | 10485760 | 1–10485760 bytes |
| `ouf.onboarding.files.xlsx.max-uncompressed-bytes` | OUF_ONBOARDING_FILES_XLSX_MAX_UNCOMPRESSED_BYTES | 104857600 | 1–268435456 bytes across all entries |
| `ouf.onboarding.files.xlsx.max-entry-bytes` | OUF_ONBOARDING_FILES_XLSX_MAX_ENTRY_BYTES | 67108864 | 1–268435456 bytes in one entry |
| `ouf.onboarding.files.xlsx.min-inflate-ratio` | OUF_ONBOARDING_FILES_XLSX_MIN_INFLATE_RATIO | 0.01 | Positive compressed / decompressed ratio, up to 1 |
| `ouf.onboarding.files.xlsx.max-entries` | OUF_ONBOARDING_FILES_XLSX_MAX_ENTRIES | 10000 | 1–10000 ZIP entries |
| `ouf.onboarding.files.xlsx.max-sheets` | OUF_ONBOARDING_FILES_XLSX_MAX_SHEETS | 64 | 1–64 worksheet units |
| `ouf.onboarding.files.xlsx.max-rows` | OUF_ONBOARDING_FILES_XLSX_MAX_ROWS | 10000 | 1–10000 nonempty data rows per worksheet |
| `ouf.onboarding.files.xlsx.max-columns` | OUF_ONBOARDING_FILES_XLSX_MAX_COLUMNS | 1024 | 1–1024 columns per worksheet |
| `ouf.onboarding.source-time-zone` | OUF_ONBOARDING_SOURCE_TIME_ZONE | Unset | JVM IANA zone ID. A worksheet with DATETIME needs a declared zone. |

After restore, verify the picker formats, upload a synthetic file at the configured limit, then read the date and DST vectors with the declared zone.
