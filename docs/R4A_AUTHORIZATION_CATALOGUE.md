# R4a Authorization: catalogo e bundle attivo

Checkpoint 24 settembre 2026. I PET v1.7 sono normativi: Authorization v1.5 richiede capability e grant versionati; MCP v1.4 riserva conferma e pubblicazione alla Trusted Human Surface; Onboarding/THS v1.6 vieta conferme tramite tool MCP. La route APISIX e lo scope Keycloak `urban.object.search` non aggiornano il PolicyBundle Authorization.

Il tentativo di proporre un grant HUMAN temporaneo tramite `authorization.permissions.propose` è stato rifiutato con `ONB_BAD_REQUEST: invalid grant reference`. Il validatore rifiuta riferimenti a capability assenti dal bundle ACTIVE; nessuna proposta è stata creata. La policy letta è `ouf-lab-authorization:14`. Il preflight sul server ha confermato `SEARCH_REGISTERED=false` e `SEARCH_IN_ACTIVE_BUNDLE=false`, senza modificare la policy.

## Diagnosi in sola lettura

**Terminale SSH sul server come `oufadmin`**, non PowerShell. Lo script è committato in questo repository, non emette credenziali, payload o inspect. Richiede `sudo` per leggere Docker e per `psql` locale nel container. Verificare lo SHA del commit fissato:

```bash
git -C /opt/ouf/onboarding fetch origin codex/r4a-authorization-catalogue
git -C /opt/ouf/onboarding merge-base --is-ancestor 66dfc3adec21515b6f4ef705cee61e1454056b9c FETCH_HEAD
set -o pipefail
git -C /opt/ouf/onboarding show d381bcce72eb0eadd907bd3b32e5e5409b6f15fc:scripts/r4a_authorization_catalogue_preflight.py | sudo python3 -
```

Output consentito: `ACTIVE_POLICY_REF`, `SEARCH_REGISTERED`, `SEARCH_IN_ACTIVE_BUNDLE` e `NO_POLICY_CHANGED=true`. I primi tentativi si sono fermati a `DATABASE_QUERY` e `SCHEMA_CHECK`: il database inizialmente scelto dal container PostgreSQL non conteneva tutte le tabelle Authorization. La versione corrente ricava il nome del database dal binding `OUF_ONB_DB_URL` del container Onboarding, senza stamparlo. In caso di `BLOCKED`, usare soltanto `STAGE` per la diagnosi e non dedurre lo stato di registrazione. Nessun INSERT o UPDATE SQL fa parte della procedura.

## Passi successivi, dipendenti dalla diagnosi

1. Se `SEARCH_REGISTERED=false`, registrare il descriptor immutabile tramite endpoint trusted HUMAN `POST /api/trusted-human/v1/authorization/capabilities`, owner `udp`, descriptor `{"capabilityId":"urban.object.search","operation":"SEARCH","requiredScope":"urban.object.search","allowedActors":["HUMAN"]}`. Il corpo HTTP deve essere `{ "ownerRef": "udp", "descriptor": { ... } }`. Il client HUMAN deve possedere `authorization.policy.admin` oppure avere autorità superadmin; `TrustedWriteProof` richiede bearer stateless validato dal filtro. L'endpoint protetto non usa sessione cookie né CSRF Spring; non chiamare questa scrittura tramite MCP. Verificare con GET che owner e descriptor coincidano; non fare registrazioni duplicate.
2. Se `SEARCH_IN_ACTIVE_BUNDLE=false`, creare una nuova bozza della policy ACTIVE conservando *tutte* le altre capability e grant, aggiungere solo il descriptor già registrato, incrementare monotonicamente la versione, eseguire preview/simulate e pubblicare unicamente con sessione e conferma trusted HUMAN secondo il PET. Il servizio vieta grant che citano capability non presenti nel bundle; non aggirare il controllo.
3. Solo dopo refresh del bundle nei consumer, proporre un grant limitato a tenant `ouf-lab`, subject HUMAN `giovanni-chatgpt` e intervallo breve, con `resourceType=capability`; la proposta dura 15 minuti e richiede conferma separata nel Trusted Approval Workspace. Per risultati su oggetti UDP occorrono eventuali grant specifici `resourceType=object` per oggetti di test, con policy di proprietà minimizzate. La lettura `authorization.permissions.read` restituisce grant configurati, non decisioni effettive.

Questa guida registra il gate ma **non dichiara già automatizzato** l'export sicuro del bundle ACTIVE, la creazione della bozza e la preview tramite THS. Sono residui di R-INSTALL. Non riutilizzare le credenziali di un altro modulo, non pubblicare token e non attivare il tool MCP fino al collaudo positivo e negativo.

## Registrazione in serie, ripetibile

Manifest versionato: `catalogue/r4a-udp-search.json`. Il comando `scripts/r4a_register_capabilities.py` valida tutti i descriptor e gli owner, confronta il catalogo prima di scrivere, rifiuta collisioni semantiche e registra solo le capability assenti. Ogni POST rimane una scrittura separata con audit dell'attore HUMAN; una mancata riuscita non è una transazione globale: rilanciare `--check` e quindi `--apply` per riprendere, verificando gli eventuali conflitti. Nessuna esecuzione pubblica automaticamente un PolicyBundle o concede un grant.

L'API GET usa `limit=200&offset=N`; l'implementazione paginata in questo PR va distribuita **prima** della riconciliazione di cataloghi grandi. Non usare lo script contro il vecchio runtime per un catalogo di 200+ voci: il controllo sulle pagine duplicate arresta la procedura. Manifest di altri moduli devono essere ricavati dai rispettivi contratti PET e approvati dal proprietario semantico; non inventare capability a partire dai soli nomi delle route. Le differenze di descriptor richiedono nuovo capabilityId o una decisione versionata, perché la registrazione esistente è immutabile.

Sul PC o sul server, in un checkout aggiornato di questo repository:

```bash
python3 scripts/r4a_register_capabilities.py --manifest catalogue/r4a-udp-search.json
```

Il comando sopra convalida localmente e non effettua richieste di rete. Le modalità `--check` e `--apply` accettano `--device-login` (Device Authorization Grant del client HUMAN `ouf-human-admin`, token solo in memoria) oppure `--token-file` con bearer HUMAN in file regolare di proprietà dell'utente, permessi 0600. Il codice dispositivo mostrato localmente va inserito soltanto nella pagina IAM indicata dallo script; non inviare codice né token in chat. La login Device Grant è documentata nell'accettazione IAM del 18 settembre. **Prima di usare `--apply`, distribuire il runtime Onboarding con GET paginato, completare il CI, collaudare il percorso della route Gateway con `--check` e verificare il preview del piano.** Non incollare il token in terminale condiviso, chat, URL o repository. Il batch non sostituisce draft, preview/simulate e publish tramite canale HUMAN.

Esempio dopo l'attivazione del runtime paginato, nel terminale SSH `oufadmin` sul server in `/opt/ouf/onboarding` (oppure in un checkout locale aggiornato):

```bash
python3 scripts/r4a_register_capabilities.py --manifest catalogue/r4a-udp-search.json --check --device-login
python3 scripts/r4a_register_capabilities.py --manifest catalogue/r4a-udp-search.json --apply --device-login
```

Ogni invocazione Device Grant richiede un accesso HUMAN separato. Lo script stampa URL di verifica e codice dispositivo nel proprio terminale, mai l'access token. La prima invocazione non scrive nulla; la seconda effettua solo POST delle capability mancanti. Un runtime non paginato potrebbe restituire una pagina ripetuta: lo script la rifiuta, senza iniziare le scritture.


## Lifecycle PolicyBundle automatizzato

Il workflow HUMAN non deve più ricostruire a mano il bundle ACTIVE né leggere direttamente il database. L'endpoint
`GET /api/trusted-human/v1/authorization/policies/active` restituisce al policy administrator il bundle ACTIVE completo
con `policyRef` e `contentHash`, con `Cache-Control: no-store`. È HUMAN-only e non è esposto MCP.

Lo script `scripts/r4a_authorization_lifecycle.py` usa esclusivamente la Trusted Human Surface e implementa un workflow
ripetibile e fail-closed:

1. `--plan` (default): legge ACTIVE, confronta il manifest e calcola la versione successiva; nessuna scrittura.
2. `--draft`: clona tutte le capability e tutti i grant, aggiunge soltanto i descriptor mancanti, crea il draft e
   richiede immediatamente `:preview`. Se preview rimuove capability o modifica grant, il comando fallisce.
3. `--preview`: ripete la verifica read-only del draft salvato.
4. `--simulate --scenario <file>`: esegue una simulazione ipotetica owner-side senza produrre decisioni enforceable.
5. `--publish --confirm-publish`: ripete preview, pubblica con ETag e verifica il nuovo ACTIVE; rifiuta cambi di grant o
   del set di capability rispetto al piano.
6. `--verify`: verifica nuovamente ACTIVE e preservazione dei grant.

Le fasi mutanti usano un file di stato locale 0600 che contiene solo identificativi, revisioni e hash, mai bearer token.
Il Device Grant resta HUMAN e ogni invocazione autentica separatamente. Esempio:

```bash
python3 scripts/r4a_authorization_lifecycle.py --manifest catalogue/r4a-udp-search.json --plan --device-login
python3 scripts/r4a_authorization_lifecycle.py --manifest catalogue/r4a-udp-search.json --draft --state-file /tmp/r4a-policy.state --device-login
python3 scripts/r4a_authorization_lifecycle.py --preview --state-file /tmp/r4a-policy.state --device-login
# simulate con uno scenario esplicito prima del publish
python3 scripts/r4a_authorization_lifecycle.py --publish --confirm-publish --state-file /tmp/r4a-policy.state --device-login
python3 scripts/r4a_authorization_lifecycle.py --verify --state-file /tmp/r4a-policy.state --device-login
```

Registrazione del catalogo, publication del PolicyBundle e grant restano azioni distinte. Lo script non concede permessi e
non sostituisce il Trusted Approval Workspace per i grant.
