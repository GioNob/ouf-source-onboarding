# R2e — profilazione GeoPackage

Reality Baseline Package v1.7: letti tutti i sette PET e i documenti L0. [Allineamento obbligatorio a ogni sprint](https://github.com/GioNob/ouf-semantic-registry/blob/main/docs/OUF_SPRINT_PET_ALIGNMENT.md).

Il media type è `application/geopackage+sqlite3`. Il profilo elenca in `metadata.layers` i layer feature, la primary key, le colonne, i candidati a chiave, un campione redatto, il numero di feature e i metadati CRS/geometria. Non sceglie un layer né un significato semantico automaticamente.

`POST /api/onboarding/v1/managed-files/{assetId}/create-onboarding?profileId=...` richiede `layer`, `sourceObjectKeyFields` non vuoti e decisioni complete per i campi del layer. La geometria non può essere la chiave. Il draft conserva `ONE_FEATURE_ONE_SOURCE_OBJECT`, layer, CRS, colonna geometrica e normalizzazione `source-layer-key-v1`.

La compilazione del profilo eseguibile deve completare `runtime.execution` e `runtime.udp` prima dell'approvazione. Il validatore richiede coerenza di layer/CRS/colonna fra runtime ed execution e il profilo spaziale governato R2d. Le relazioni hanno policy/label esplicite; strategia, classe e chiave target vengono verificate nuovamente da UDP sul bundle esatto. Conferma umana, hash, attestazione di compatibilità e pubblicazione mantengono il percorso esistente.

Il reader usa SQLite JDBC 3.53.4.0 in sola lettura, senza estensioni, con limiti di dimensione/righe/colonne/coordinate e scadenza VM. Supporto 2D simple features con CRS EPSG dichiarato; nessuna riparazione o conversione nel profiler. Limiti dettagliati nel [modulo Ingestion](https://github.com/GioNob/ouf-ingestion-runtime/blob/main/docs/R2E_GEOPACKAGE.md).

Le prove comprendono parser reale, migrazione del media type/formato, scelta obbligatoria del layer, rifiuto di chiavi assenti e conservazione delle decisioni nel draft. Il percorso fra owner è esercitato dalla CI R2e di Ingestion con identità/Gateway di laboratorio dichiarati. La UI cartografica integrata rimane R4a.
