# R4a — intake governato del CSV reale

Stato: codice candidato; CI e bootstrap lab sono gate separati. Il CSV allegato
`cinema_trieste(1).csv` ha 509 byte, UTF-8 con BOM, due colonne (`cinema`,
`indirizzo`), otto righe e SHA-256
`a07c2dcdc21aa9a23fb5585a69d52031dc08010d251bf39bfa67c8e0962c6e1a`.
Non pubblicare il file nel repository. Preservare i byte originali, compreso
il BOM, durante il trasferimento sul VPS e confrontare l'hash prima dell'upload.

## Confini e ordine

1. `POST /api/managed-sources/v1/files` è una chiamata HUMAN autenticata dal
   Gateway con scope `ouf.managed-source.file.upload`. Onboarding richiede la
   capability applicativa corrispondente, limita a 10 MiB, calcola SHA-256,
   mette i byte in MinIO e registra `ManagedFileAsset` nel proprio DB.
2. `GET /internal/object-storage/v1/content?ref=object://managed-files/<uuid>`
   è una route M2M Gateway verso il medesimo deployable Onboarding. Il backend
   richiede attore SERVICE e `ouf.object-storage.content.read`. L'oggetto
   rimane in MinIO; la lettura respinge riferimenti estranei e file >10 MiB.
3. Il worker Onboarding usa esclusivamente la route Gateway e un token
   workload `ouf-onboarding` letto da `/run/ouf-onboarding-auth/token` a ogni
   richiesta. Ingestion usa il proprio client e token, con la stessa read
   capability. Non riutilizzare il token Ingestion per il worker Onboarding.
4. Profiling/preview e create-onboarding usano le route HUMAN
   `POST` e `GET /api/onboarding/v1/managed-files/*`. La route POST richiede
   a bordo lo scope `ouf.managed-source.file.profile`; il backend richiede
   inoltre la capability distinta `ouf.managed-source.onboarding.create` per
   create-onboarding. La GET richiede `ouf.managed-source.preview` sia a bordo
   che nel backend. La semantica live non ha ancora
   publication set: DRAFT, review e pubblicazione Semantic precedono ACTIVE.

## Bootstrap lab, prima delle modifiche runtime

Conservare immagine/tag e configurazione `ouf-onboarding` correnti, la
InstallationProjection attiva e la snapshot APISIX. Eseguire i seguenti tool
versionati da commit CI verde in `plan`/`--check` prima di ogni `apply`:

- `scripts/r4a_keycloak_client_scope_catalogue.py` per
  `ouf.managed-source.file.upload`, `ouf.managed-source.file.profile`,
  `ouf.managed-source.preview`, `ouf.managed-source.onboarding.create`
  e `ouf.internal.object-storage.read`;
- `scripts/r4a_keycloak_workload_client.py --container ouf-keycloak
  --client-id ouf-onboarding` per il nuovo client confidenziale con service
  account, audience Gateway e claim SERVICE; non stampare o rigenerare secret;
- `scripts/r4a_keycloak_client_scope_binding.py` per i quattro scope HUMAN
  come OPTIONAL di `ouf-human-admin` e read DEFAULT di `ouf-onboarding`
  e `ouf-ingestion`;
- `scripts/r4a_register_capabilities.py --manifest
  catalogue/r4a-managed-file-intake.json --check --device-login` per
  verificare le cinque capability nell'Authorization Registry;
- `scripts/r4a_service_grant_lifecycle.py` con manifest
  `catalogue/r4a-managed-file-read-grants.json` e
  `scripts/r4a_human_grant_lifecycle.py --grants-file
  catalogue/r4a-managed-file-human-grants.json --subject-id
  <SUBJECT_VERIFICATO>` per pianificare i quattro grant HUMAN in una sola
  draft/preview/publish add-only;
  Applicare/publish solo dopo preview esatta;
  preservare i grant esistenti. Verificare la scadenza prima del deploy.

I secret del client workload e di un account MinIO limitato al bucket
dedicato vanno in file root-owned non stampati, montati leggibili al solo
UID/GID runtime `10003`. Preparare un bucket `ouf-managed-files` con backup,
retention e credenziali ristrette; non usare il bucket UDP o il root account
MinIO per l'applicazione. Non creare direttamente record DB né caricare il
CSV su MinIO aggirando la route HUMAN.

## Configurazione runtime candidata

Solo dopo il bootstrap, nel deploy governato di Onboarding impostare:

```text
OUF_ONBOARDING_STAGING_ENDPOINT=http://ouf-minio:9000
OUF_ONBOARDING_STAGING_BUCKET=ouf-managed-files
OUF_ONBOARDING_STAGING_ACCESS_KEY_FILE=/run/secrets/onboarding-minio-access-key
OUF_ONBOARDING_STAGING_SECRET_KEY_FILE=/run/secrets/onboarding-minio-secret-key
OUF_ONBOARDING_OBJECT_STORE_GATEWAY_BASE_URL=http://ouf-apisix:9080
OUF_ONBOARDING_OBJECT_STORE_TOKEN_FILE=/run/ouf-onboarding-auth/token
```

Il generico `ops/policy_token/install_policy_token_workload.py` nel repo
Gateway installa, dopo `--apply`, un timer di rinnovo per `ouf-onboarding`
con `--required-scope ouf.internal.object-storage.read`, il secret client
in un file privato e `--runtime-gid 10003`. Verificare claim di client,
audience, scope, attore, scadenza e tenant senza stampare il token.

Compilare `ouf-config` dal checkout Gateway verde, applicare la proiezione
attiva e materializzare `onboarding-managed-file-read` col tool
`tools/materialize_apisix_internal_m2m_routes.py` e
`trusted-human-managed-file-upload`,
`trusted-human-managed-file-actions-post` e
`trusted-human-managed-file-preview-get` col materializer HUMAN Onboarding.
Gli installer APISIX creano snapshot private, readback e prova anonima
401/403, con restore automatico in caso di errore. Non installare le route
prima che il backend e le due capability rispondano secondo contratto.

## Accettazione da eseguire sul VPS

- Trasferire il CSV originale dal PC al VPS con un canale autenticato in una
  directory privata di `oufadmin`. Il file della chat non è già presente sul
  VPS. Fare `sha256sum` e verificare 509 byte prima di ogni richiesta.
- Da SSH `oufadmin`, eseguire `scripts/r4a_managed_csv_smoke.py --csv
  <PERCORSO_CSV_PRIVATO> --subject-id <SUBJECT_IAM_VERIFICATO>`. Aprire solo
  sul browser del PC l'URL Device Flow mostrato e inserire il codice sul
  sito IAM; non copiare codice o token in chat. Lo script non riprova
  automaticamente l'upload dopo un esito HTTP incerto.
- `sha256sum` del file trasferito identico all'hash sopra; 509 byte.
- Upload autenticato via Gateway: HTTP 201, `staging_ref` valido,
  `content_hash` `sha256:` identico, `size_bytes=509`; nessun token/file nei log.
- Lettura via route SERVICE con token `ouf-onboarding`: byte identici; token
  assente o scope/client errato respinti. Profiling produce esattamente
  `cinema` e `indirizzo`, otto righe e sample redatto per `indirizzo`.
- Testare preview e proposta semantica prima di qualunque APPROVED/ACTIVE;
  il round trip fino a Ingestion, UDP e search resta OPEN fino a tale prova.

Rollback: ripristinare snapshot delle route APISIX e il tag immagine/config
Onboarding precedenti. Non cancellare il bucket o gli oggetti caricati:
seguiranno la retention governata e la verifica dei riferimenti.
