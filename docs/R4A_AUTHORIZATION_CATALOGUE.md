# R4a Authorization: catalogo e bundle attivo

Checkpoint 24 settembre 2026. I PET v1.7 sono normativi: Authorization v1.5 richiede capability e grant versionati; MCP v1.4 riserva conferma e pubblicazione alla Trusted Human Surface; Onboarding/THS v1.6 vieta conferme tramite tool MCP. La route APISIX e lo scope Keycloak `urban.object.search` non aggiornano il PolicyBundle Authorization.

Il tentativo di proporre un grant HUMAN temporaneo tramite `authorization.permissions.propose` è stato rifiutato con `ONB_BAD_REQUEST: invalid grant reference`. Il validatore rifiuta riferimenti a capability assenti dal bundle ACTIVE; nessuna proposta è stata creata. La policy letta è `ouf-lab-authorization:14`. Questo errore è compatibile con una capability registrata ma non inserita nel bundle oppure con una capability non registrata: verificare entrambi prima di agire.

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

1. Se `SEARCH_REGISTERED=false`, registrare il descriptor immutabile tramite endpoint trusted HUMAN `POST /api/trusted-human/v1/authorization/capabilities`, owner `udp`, descriptor `{"capabilityId":"urban.object.search","operation":"SEARCH","requiredScope":"urban.object.search","allowedActors":["HUMAN"]}`. Il client HUMAN deve possedere `authorization.policy.admin` e la scrittura deve passare dalla validazione CSRF della THS. Verificare con GET che owner e descriptor coincidano; non fare registrazioni duplicate.
2. Se `SEARCH_IN_ACTIVE_BUNDLE=false`, creare una nuova bozza della policy ACTIVE conservando *tutte* le altre capability e grant, aggiungere solo il descriptor già registrato, incrementare monotonicamente la versione, eseguire preview/simulate e pubblicare unicamente con sessione e conferma trusted HUMAN secondo il PET. Il servizio vieta grant che citano capability non presenti nel bundle; non aggirare il controllo.
3. Solo dopo refresh del bundle nei consumer, proporre un grant limitato a tenant `ouf-lab`, subject HUMAN `giovanni-chatgpt` e intervallo breve, con `resourceType=capability`; la proposta dura 15 minuti e richiede conferma separata nel Trusted Approval Workspace. Per risultati su oggetti UDP occorrono eventuali grant specifici `resourceType=object` per oggetti di test, con policy di proprietà minimizzate. La lettura `authorization.permissions.read` restituisce grant configurati, non decisioni effettive.

Questa guida registra il gate ma **non dichiara già automatizzato** l'export sicuro del bundle ACTIVE, la creazione della bozza e la preview tramite THS. Sono residui di R-INSTALL. Non riutilizzare le credenziali di un altro modulo, non pubblicare token e non attivare il tool MCP fino al collaudo positivo e negativo.
