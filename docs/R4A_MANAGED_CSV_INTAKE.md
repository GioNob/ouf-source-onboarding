# R4a — intake governato del CSV reale

Stato: codice candidato; CI e bootstrap lab sono gate separati. Il CSV allegato
`cinema_trieste(1).csv` ha 509 byte, UTF-8 con BOM, due colonne (`cinema`,
`indirizzo`), otto righe e SHA-256
`a07c2dcdc21aa9a23fb5585a69d52031dc08010d251bf39bfa67c8e0962c6e1a`.
Non pubblicare il file nel repository. Preservare i byte originali, compreso
il BOM, durante il trasferimento sul VPS e confrontare l'hash prima dell'upload.

**Gate architetturali aperti:** lo smoke qui sotto esercita route Gateway con
un client HTTP HUMAN, non il plugin MCP. Il binding candidato per profile e
preview è versionato nei PR MCP e Gateway e usa una ricevuta HUMAN firmata
verificata dall'owner, ma non è attivo sul lab. I tool per upload tramite
attachment governato, creazione onboarding e ingestion non sono ancora
pubblicati nel plugin live. Non abilitare un tool
che accetti byte/base64 o URL di storage dall'agente come scorciatoia: il
contratto T25 richiede un attachment flow con identità HUMAN verificata. La
pubblicazione Semantic/THS, ingestion, UDP e search non sono ancora dimostrate.

L'endpoint Onboarding usa una copia temporanea su disco con digest incrementale
e limite di 10 MiB, senza tenere l'intero upload in heap. Il 26/09/2026 la
sonda isolata sul runtime APISIX del lab ha osservato i primi byte all'upstream
prima del termine dell'invio (`true`), risposta 204 e rimozione della route
temporanea (`true`), sul commit Gateway
`04d9aa924e84db77b1e9135efef21b10158bc999`. Le route reali HUMAN e MCP
risultavano entrambe assenti (HTTP 404) nell'inventario Admin API successivo.
Restano da provare streaming e limiti attraverso la route prodotto fino
all'owner, inclusi 413, 415, hash errato, assenza di asset parziale e rollback.
Non attivare queste route in produzione con tale gate aperto.

Gli hostname `ouf-onboarding`, `ouf-apisix` e `ouf-minio` sotto sono binding
DNS privati del lab nella stessa rete Docker; non rappresentano il contratto
per un Gateway su macchina/rete diversa. Quel deployment richiede endpoint
approvati per installazione, TLS con verifica del certificato, firewall/egress
e prove negative di accesso diretto agli owner. Un cambio di dominio pubblico
usa la proiezione dell'installazione: nessun hostname del lab è requisito PET.

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
   Onboarding verifica anche che il subject HUMAN proprietario dell'asset
   corrisponda a chi invoca profile, status, preview e create-onboarding;
   possedere uno scope non concede accesso ai file di altri utenti.

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
- `scripts/r4a_keycloak_workload_secret_file.py plan/apply/verify` salva il
  secret corrente del solo client `ouf-onboarding` in
  `/etc/ouf/secrets/onboarding-client-secret` con permessi 0600, senza
  stamparlo, ruotarlo o sovrascrivere un file esistente. `plan` verifica
  `/etc/ouf` root-owned senza scrivere; `apply` crea la directory dedicata
  `/etc/ouf/secrets` root-owned 0700 solo se assente. Non modificare
  `/opt/ouf/secrets`, directory condivisa nel lab e di proprietà `oufadmin`.
  `apply` richiede root e la sessione `kcadm` valida; se il file esiste ma il
  token renewal fallisce, fermarsi e riconciliare il secret tramite la
  procedura IAM;
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

### Stato lab al 26/09/2026

Il bootstrap IAM è verificato: le cinque capability sono registrate, la
PolicyBundle ha raggiunto `ouf-lab-authorization:27` (due grant SERVICE in
:25, quattro grant HUMAN in :26 e altri sei grant HUMAN per `ouf-admin` in
:27, con preview add-only e verifica dei grant precedenti) e il verificatore del
token `ouf-onboarding` ha restituito `WORKLOAD_TOKEN_ACCEPTANCE=PASS` per
issuer, client, audience, attore SERVICE, tenant, scope e scadenza. Il check
locale dei claim non sostituisce la verifica della firma nel Gateway.

I container `ouf-onboarding` e `ouf-onboarding-r4a-smoke` utilizzano
`ouf-onboarding:r4a-5866007`, rete `ouf-backend`, UID/GID `10003:10003` e
montano soltanto `authorization-owner-key` e `onboarding-ths.yaml`. Entrambi
hanno `STAGING_KEYS=NONE`; `ouf-minio` ha il volume dati persistente su
`/data` e il proprio file privato per la password amministrativa. Nessuno
dei tre container reca l'etichetta di un file Compose, e la ricerca di
script di avvio sotto `/opt/ouf` e `/etc/systemd/system` non ha trovato la
definizione corrente. Bucket e credenziali dedicate sono stati verificati
separatamente; le variabili e i mount di staging dei container Onboarding
non sono ancora installati. Il backup dei managed file resta da definire;
il bucket UDP esistente resta separato.

Prima di qualsiasi ricreazione dei container conservare, in un file privato
root-owned sul VPS, il risultato integrale di `docker inspect` dei container
interessati (contiene variabili e potenzialmente secret: non incollarlo in
chat o nei repository), l'image ID e la configurazione dei mount e della
rete. Documentare i controlli di salute e il rollback verso i container
precedenti. Provisionare il bucket `ouf-managed-files` e un'identità MinIO
limitata al prefisso `arn:aws:s3:::ouf-managed-files/managed-files/*`, con
`s3:PutObject` e `s3:GetObject` come operazioni richieste dal codice; la
prova fino a 10 MiB deve accertare anche i permessi necessari al multipart
e alla pulizia di un upload interrotto; provare con tale identità
scrittura e lettura nel prefisso, il rifiuto sull'altro bucket e accesso
anonimo negato. Rendere accessibili i file credenziali solo a root e al
runtime UID/GID 10003, senza stampare valori o ruotare secret esistenti.
Stabilire backup e retention del bucket prima dei primi byte reali. Solo
dopo il bootstrap e una prova di rollback montare i due file nel container
Onboarding e impostare le variabili qui sotto; verificare la salute del
backend prima di installare le route Gateway di prodotto.

Lo script `scripts/r4a_stage_deploy_snapshot.py` implementa `plan`, `apply`
e `verify` per la fotografia privata e immutabile dei tre container. Richiede
root e salva `docker inspect` integrale in
`/etc/ouf/deploy-snapshots/r4a-before-staging.docker-inspect.json` con
directory 0700 e file 0600. `plan` e `verify` non scrivono; `apply` non
sovrascrive un file esistente e fallisce se lo snapshot non coincide con i
container ancora attivi. Lo snapshot non sostituisce il backup di database
e oggetti né ricrea automaticamente un container.
Nel lab il `plan` ha trovato tre container e nessuno snapshot; `apply` ha
salvato un file corrispondente allo stato live e `verify` ne ha riletto lo
stesso SHA-256
`fbdc127f1edf990ec3603662143e6f8742913a27678571e6dfe1fc7281704772`.
`scripts/r4a_minio_staging_preflight.py` raccoglie ora in sola lettura rete,
mount, sorgenti delle credenziali espresse come **nomi di variabile**, client
`mc` disponibili e immagini client già presenti: non stampa i valori degli
env Docker. Usare il risultato per pinning/versioning del client MinIO prima
di creare bucket o account.
Sul lab il preflight ha confermato MinIO attivo su `ouf-backend`, volume dati e
password amministrativa montati, client `mc` disponibile **dentro** il
container (nessun client host né immagine aggiuntiva necessaria). Lo script
`scripts/r4a_minio_staging_plan.py` usa quel client per verificare in sola
lettura se bucket `ouf-managed-files`, utente `ouf-onboarding-staging` e
policy `ouf-onboarding-managed-files-v1` esistono già. Legge i file admin
soltanto nel container e cancella la configurazione temporanea `mc` alla
fine; stampa booleani e non il contenuto delle credenziali. Non procedere
all'apply se l'utente esiste senza i due file credenziali privati associati.
Il piano sul lab ha dato `ADMIN_ALIAS_OK=true`, i tre oggetti assenti e
`APP_CREDENTIAL_FILES=none`. Da un commit verde usare
`scripts/r4a_minio_staging_bootstrap.py plan → apply → verify`: richiede lo
snapshot privato già verificato, genera file root:10003 0440 in
`/etc/ouf/secrets/onboarding-minio-{access,secret}-key`, crea un utente
MinIO distinto dal root, un bucket separato e la policy versionata sul solo
prefisso `ouf-managed-files/managed-files/*`. L'utente può `GetObject`,
`PutObject`, abortire/listare parti multipart per la pulizia, ma non può
modificare la IAM MinIO né cancellare oggetti. L'apply non sovrascrive file
esistenti e blocca utente preesistente senza credenziali associate; i file
vengono persistiti prima di creare l'utente per consentire un retry senza
rotazione. `verify` prova scrittura/lettura con l'identità applicativa,
nega una scrittura sul bucket UDP e pulisce l'oggetto di prova con il root;
non carica il CSV reale. La prova non equivale a backup, versione/retention
approvata né a verifica del multipart a 10 MiB, che restano gate prima
dell'upload reale.
Sul lab, dopo il bootstrap e il controllo della policy MinIO indipendente
dall'ordine delle azioni restituito dal server, `verify` ha confermato:
`BUCKET_EXISTS=true`, `POLICY_EXISTS=true`, `USER_EXISTS=true`,
`APP_CREDENTIAL_FILES=both`, `POLICY_EXACT=true`,
`PREFIX_WRITE_READ_OK=true`, `UDP_WRITE_DENIED=true`, `VERIFY=PASS` e
`SECRETS_PRINTED=false`. La prima invocazione `apply` aveva creato le
risorse ma era terminata con `MINIO_STAGING_VERIFY_FAILED` per il confronto
sensibile all'ordine delle azioni; la successiva verifica con il confronto
corretto è passata senza rotazione delle credenziali. La prova anonima,
il multipart a 10 MiB, backup e retention richiedono ancora verifica.

Per la richiesta che `ouf-admin` possa usare tutte le capability HUMAN
attive, `scripts/r4a_admin_all_human_manifest.py --subject-id
<SUB_VERIFICATO>` pianifica, senza scrivere, i grant mancanti sulla policy
attiva via Device Flow. `--manifest-out <FILE_NUOVO>` scrive in modo esclusivo
e privato il manifest dei soli grant mancanti; usare poi il workflow HUMAN
esistente `plan` → `draft` → `preview` → `publish` → `verify` con quel
manifest e verificare il subject del token. Il tool riporta gli scope HUMAN
richiesti, da verificare o legare separatamente in Keycloak: un grant non
aggiunge scope a un token. Le capability SERVICE-only continuano ad avere
grant ai workload, perché il backend respinge un attore HUMAN per quei
descriptor anche se il suo subject avesse un grant. Restano validi i vincoli
di owner sui singoli asset e la scadenza dei grant HUMAN già pubblicati.
Sul lab :27, `ouf-admin` dispone di 19 grant HUMAN validi; le sei aggiunte
scadono il 26/09/2027, mentre le scadenze dei 13 grant precedenti vanno
seguite separatamente. Il batch di verifica e binding degli scope Keycloak
è `scripts/r4a_admin_human_scope_bindings.py plan/apply/verify`: controlla
15 scope HUMAN del catalogo :27, usa il client esatto `ouf-human-admin` e
aggiunge solo eventuali binding OPTIONAL mancanti. Non crea client scope,
non modifica binding esistenti e non attribuisce scope SERVICE. Richiede la
sessione `kcadm` valida nel container Keycloak. Richiedere nel flusso OIDC
gli scope OPTIONAL pertinenti alla singola operazione: il solo binding non
li inserisce automaticamente in ogni access token.
Sul lab il batch ha restituito `MODE=plan`, 13 binding presenti e due
mancanti (`operations.status.read`, `urban.object.search`); `MODE=apply` ha
aggiunto i due e verificato `BOUND_SCOPES=15`, `MISSING_CATALOGUE=NONE`,
`MISSING_BINDINGS=NONE`, `VERIFY=PASS`, senza stampare secret. La modalità
`token` dello stesso script
richiede i 15 scope via Device Flow e stampa solo booleani per issuer,
subject, client, tenant, attore HUMAN, audience, scadenza e scope; il controllo
locale dei claim non sostituisce la convalida della firma al Gateway. Sul lab
ha restituito tutti i booleani `true` e
`ADMIN_HUMAN_TOKEN_ACCEPTANCE=PASS`, senza stampare token o secret. I gate
staging e route di prodotto restano separati.

Solo dopo il bootstrap, nel deploy governato di Onboarding impostare:

```text
OUF_ONBOARDING_STAGING_ENDPOINT=http://ouf-minio:9000
OUF_ONBOARDING_STAGING_BUCKET=ouf-managed-files
OUF_ONBOARDING_STAGING_ACCESS_KEY_FILE=/run/secrets/onboarding-minio-access-key
OUF_ONBOARDING_STAGING_SECRET_KEY_FILE=/run/secrets/onboarding-minio-secret-key
OUF_ONBOARDING_OBJECT_STORE_GATEWAY_BASE_URL=http://ouf-apisix:9080
OUF_ONBOARDING_OBJECT_STORE_TOKEN_FILE=/run/ouf-onboarding-auth/token
```

Montare in sola lettura i due file credenziali e la **directory**
`/run/ouf-onboarding-auth` nel container UID/GID 10003:10003. Il refresher
del token sostituisce atomicamente il file `token` tramite `os.replace`:
montare direttamente il file fisserebbe il vecchio inode e impedirebbe al
container di osservare i rinnovi. Verificare la lettura nel container e
il rinnovo prima di usare la route SERVICE. Il tag immagine attuale
`ouf-onboarding:r4a-5866007` e i container esistenti non includono ancora
la configurazione e i mount di staging; predisporre un candidato con la
versione che contiene il codice R4a, senza utilizzare lo snapshot `docker
inspect` come script eseguibile o stampare le variabili originali.
`scripts/r4a_onboarding_staging_preflight.py` controlla in sola lettura lo
snapshot, i metadati dei file privati, la directory del token e la presenza
dei mount e delle chiavi di configurazione nel container live; non stampa
valori delle variabili, credenziali né il contenuto dello snapshot. Prima del
deploy sono attesi `ORIGINAL_CONTAINERS_PRESENT=true`,
`MINIO_CREDENTIAL_FILES_SAFE=true`, `TOKEN_DIRECTORY_SAFE=true`,
`ONBOARDING_STAGING_ENV_KEYS_PRESENT=false` e
`ONBOARDING_STAGING_MOUNTS_PRESENT=false`.
Il preflight sul lab ha confermato lo snapshot SHA-256 sopra, container
originali presenti, directory private e file MinIO/token sicuri, rete e
utente runtime corretti, nessun env o mount di staging presente e nessuna
scrittura. La build dal tree Git `e0509e8d48146d20d2134eb27c8b1a40be6c9141`
ha prodotto l'immagine candidata `ouf-onboarding:r4a-e0509e8`, image ID
`sha256:8ca287241c4dd7753fe23a300c1b5764aab9485021efd626f5db6fee8356f920`;
la label revision coincide. I workflow CI del precedente commit funzionale
`834574f34e1034d275068baf5b54108b08ad5fc6` sono verdi. La presenza
dell'immagine non dimostra salute dell'applicazione né funzionalità di staging.
`scripts/r4a_onboarding_staging_candidate.py plan/prepare/verify` confronta
immagine e label con l'ID attestato, container attivo con lo snapshot, utente,
rete, entrypoint, mount ed env. Blocca impostazioni host inattese; `prepare`
crea solo `ouf-onboarding-r4a-candidate` nello stato Docker `created`, con
le due credenziali e la **directory** del token montate in sola lettura. Copia
le variabili originali dal container attivo in un file temporaneo privato per
`docker create --env-file` e lo elimina immediatamente; non stampa valori
sensibili né avvia o arresta container. `verify` confronta configurazione e
mount in memoria. Il candidato fermo non è ancora un deploy, né una prova
di salute: prima di avviarlo accertare backup recuperabile di DB e oggetti,
retention approvata, rollback verso il container originale e readiness HTTP.
`scripts/r4a_onboarding_db_backup.py plan/apply` usa `pg_dump -Fc` sul solo
schema `ouf_onboarding` del database esatto configurato nel container live,
salva il dump con permessi 0600 sotto `/etc/ouf/deploy-snapshots` e prova
`pg_restore` in un database temporaneo del medesimo PostgreSQL, che poi
elimina. Non stampa password o contenuto del dump e non cambia il database
Onboarding attivo. Conservare il dump e verificare il ripristino prima di
avviare il candidato: eventuali migrazioni Flyway richiedono un rollback
consapevole di database e immagine. Questa prova del DB non costituisce
backup del bucket `ouf-managed-files`; definire un backup degli oggetti
separato e retention approvata prima di caricare file reali.
Sul lab il backup ha prodotto
`/etc/ouf/deploy-snapshots/r4a-onboarding-20260926T125405Z-d709d63d.dump`:
`DATABASE_TARGET_MATCH=true`, `SCHEMA_RESTORE_TO_SCRATCH=PASS` e
`PRODUCTION_DB_UNCHANGED=true`. Il candidato fermo è stato creato dal commit
`15280572ba60076460cde2aee3d24d603aadc691`:
`CANDIDATE_CONFIG_VERIFIED=true`, `ORIGINAL_CONTAINER_RUNNING=true`,
`NO_START_OR_SWAP=true`.
`scripts/r4a_onboarding_rollout_gate.py --db-dump <DUMP_PRIVATO>` ricontrolla
prima dello scambio l'ID del container originale, l'immagine del candidato,
l'archivio `pg_restore`, la versione Flyway live e se l'intero bucket
`ouf-managed-files` è vuoto. Il client MinIO usa una alias
temporanea che viene rimossa; stampa solo booleani e numero di versione.
Un bucket vuoto oggi non sostituisce un backup periodico degli oggetti dopo
i primi upload.
La prima esecuzione si è fermata con `CHECK_COMMAND_FAILED` senza mutazioni:
la diagnostica generica non localizzava il comando fallito. La revisione
successiva distingue `FLYWAY_QUERY_FAILED` da
`MINIO_BUCKET_QUERY_FAILED` e controlla l'intero bucket esistente, evitando
di richiedere il listing di un prefisso che potrebbe non esistere.

Il generico `ops/policy_token/install_policy_token_workload.py` nel repo
Gateway installa, dopo `--apply`, un timer di rinnovo per `ouf-onboarding`
con `--required-scope ouf.internal.object-storage.read`, il secret client
in un file privato e `--runtime-gid 10003`. Verificare claim di client,
audience, scope, attore, scadenza e tenant senza stampare il token.
Lo script `scripts/r4a_verify_onboarding_workload_token.py --tenant-id <TENANT>`
legge la projection e `/run/ouf-onboarding-auth/token`, controlla metadati
del file e claim, stampa solo booleani e fallisce se uno manca. È un controllo
locale dei claim, non una verifica della firma JWT; il token deve provenire
dal refresher HTTPS governato. Sul lab il tenant atteso è `ouf-lab`.

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
  <PERCORSO_CSV_PRIVATO> --subject-id <SUBJECT_IAM_VERIFICATO>
  --issuer <iam.issuerUrl> --gateway-base-url <gateway.publicApiBaseUrl>
  --audience <gateway.requiredAudience> --client-id <CLIENT_HUMAN_VERIFICATO>`.
  I quattro binding derivano dalla proiezione approvata dell'Ente. Aprire solo
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
