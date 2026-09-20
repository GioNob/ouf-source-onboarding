# Permessi tramite chatbot e conferma umana

L'admin OUF può consultare le abilitazioni e preparare una concessione/revoca tramite chatbot. La proposta non modifica ACTIVE. La THS di Onboarding mostra la modifica e la pubblica soltanto dopo una conferma esplicita dell'admin autenticato. Il chatbot non riceve il bearer umano e non dispone di strumenti di conferma.

Questa implementazione completa il percorso applicativo, non certifica una configurazione IAM live né la chiusura automatica dei gap PET AUT/MCP. Leggere anche [bootstrap e superadmin](OUF_ADMIN_BOOTSTRAP.md) e [ispezione/simulazione](AUTHORIZATION_REVIEW.md).

## Autorità e permessi

IAM attesta identità e ruoli organizzativi; OUF associa tali ruoli alle capability. Il superadmin protetto resta associato a issuer, tenant e ruolo tramite bootstrap/handover. Non viene creato un ruolo amministrativo nel Keycloak esterno. Le proposte cambiano un solo grant ordinario: possono concedere/revocare `authorization.policy.admin` a un soggetto nominale, ma non modificano l'associazione protetta del superadmin.

Registrare attraverso l'API amministrativa esistente questi descriptor, owner `authorization`, allowedActors `[HUMAN]`, requiredScope uguale al capabilityId:

| capabilityId | operation | Effetto |
|---|---|---|
| authorization.permissions.read | READ | Grant configurati del tenant, filtro soggetto/ruolo e paginazione |
| authorization.permissions.propose | COMMAND | Proposta UPSERT/REVOKE di un singolo grant |
| authorization.proposal.read | READ | Ricevuta/stato della propria proposta |

Concedere esplicitamente questi permessi ai soggetti o ruoli incaricati e pubblicare il bundle tramite amministrazione governata. Nessuna concessione automatica a tutti gli utenti. Anche un superadmin che usa MCP necessita dei grant/scope degli strumenti: la conferma THS rivaluta separatamente la sua autorità protetta o il grant amministrativo corrente. `permissions.read` espone grant configurati, non una lista utenti IAM né una prova di accesso effettivo. La simulazione resta nell'API amministrativa di review.

Per un grant basato sul ruolo usare `constraints.externalRoleRef` con il valore canonico attestato da IAM; non inventare il ruolo dal testo della chat. Per un grant nominale usare il subject ID IAM, non email/username. Tenant, validità, capability e vincoli restano parte della policy validata dal dominio.

## Keycloak / IAM

Sul client chatbot assegnare i tre client scope sopra, con `Include in token scope` attivo. Aggiungerli come Default soltanto a quel client, oppure Optional e richiederli esplicitamente nel flusso OAuth. Conservare `mcp.connect`, audience Gateway e mapper di identità/tenant/ruoli già configurati. Lo scope da solo non concede il grant OUF.

Creare un client OIDC separato e riservato al backend THS, ad esempio `ouf-authorization-ths`:

- Client authentication ON; Standard flow ON; Direct access grants e implicit flow OFF.
- Require PKCE ON, metodo S256. Il backend THS abilita esplicitamente PKCE anche per il client riservato: il secret autentica l'applicazione, il verifier lega il codice alla richiesta originaria. Il verifier resta nella sessione server e non nel redirect al browser.
- Valid redirect URI esatta: `https://HOST_OUF/login/oauth2/code/ouf-ths`, senza wildcard.
- Scope `openid` e `authorization.policy.admin`; access token con audience Gateway, `tenant_id`, `ouf_actor_type=HUMAN`, `acr`, `externalRoleRefs` canonici. I mapper e il ruolo organizzativo provengono dall'IAM dell'Ente.
- Il client secret rimane sul server Onboarding. Non inserirlo nel plugin chatbot o in JavaScript.

Il nome della registration Spring è **ouf-ths**, indipendentemente dal client-id IAM. L'utente apre il link di approvazione: il backend avvia il login e conserva l'access token nella sessione lato server. Nel browser resta solo il cookie di sessione. Una sessione IAM SSO esistente può evitare un nuovo inserimento delle credenziali; non si presume una nuova MFA a ogni click.

## Configurazione Onboarding

### Correzione del profilo iniziale e ordine di attivazione

L'immagine `ouf-onboarding:72ccc2c` inizialmente preparata non abilita esplicitamente PKCE per il client riservato. Non attivare quella versione come profilo conforme al PET Authorization §6.1 (Authorization Code + PKCE). Preparare l'immagine contenente la correzione e poi imporre PKCE S256 sul client Keycloak, prima del collaudo umano. L'indicazione precedente di lasciare Require PKCE OFF è superata. Client ID, secret, callback e mapper rimangono validi.

Per il laboratorio sono già stati confermati: secret autenticato da Keycloak, file `/opt/ouf/secrets/onboarding-ths.yaml` e chiave owner leggibili da UID/GID 10003:10003. La verifica di introspezione con token fittizio deve restituire HTTP 200 e `active:false`: verifica le credenziali del client, non il login umano o i grant OUF. HTTP 401 non autorizza a procedere; correggere il valore e verificarlo prima di sostituire il file. Non stampare né inviare secret in chat.

Il file YAML può essere scritto come JSON (sottoinsieme YAML); montarlo read-only e caricarlo con `SPRING_CONFIG_ADDITIONAL_LOCATION=file:/run/secrets/onboarding-ths.yaml`. Conservare le variabili IAM/database già presenti nel container. Il solo file non modifica il servizio in esecuzione. Prima dello switch conservare backup di configurazione e database; mantenere il container precedente per rollback applicativo senza cancellare migrazioni, audit o proposte.

Controllo PKCE: avviare il login e verificare `code_challenge_method=S256` e un `code_challenge` nel redirect IAM, senza `code_verifier` o secret. Completare il login con PKCE imposto su Keycloak. Il test runtime verifica il redirect della catena reale, la correlazione SHA-256 col verifier della sessione e il suo invio nella richiesta token; non sostituisce il collaudo con IAM reale.

Esempio di configurazione applicativa, sostituire host/issuer/client e usare il secret manager dell'installazione per `THS_CLIENT_SECRET`:

```yaml
ouf:
  iam:
    enabled: true
    issuer: https://IAM/realms/ENTE
    audience: ouf-api-gateway
  authorization:
    delegation:
      key-file: /run/secrets/authorization-owner-key
      workload: ouf-mcp-server
    ths:
      enabled: true
      public-origin: https://HOST_OUF
spring:
  security:
    oauth2:
      client:
        registration:
          ouf-ths:
            provider: ente
            client-id: ouf-authorization-ths
            client-secret: ${THS_CLIENT_SECRET}
            authorization-grant-type: authorization_code
            redirect-uri: https://HOST_OUF/login/oauth2/code/ouf-ths
            scope: openid,authorization.policy.admin
        provider:
          ente:
            issuer-uri: https://IAM/realms/ENTE
server:
  servlet:
    session:
      timeout: 10m
      cookie:
        secure: true
        http-only: true
        same-site: lax
```

`public-origin` accetta solo un'origine HTTPS senza credenziali, query o percorso. Il collegamento restituito non è fornito dal chatbot. URI di callback assoluta e origine configurata evitano di fidarsi di Host/Forwarded non verificati. La catena THS richiede la sessione OAuth2; bearer, ricevuta di delega e header simulati non autorizzano conferme. Al momento la sessione usa il token ottenuto al login senza refresh automatico: alla scadenza riaprire «Accedi nuovamente con IAM». Con repliche multiple serve una strategia di sessione condivisa o affinità prima dell'abilitazione; la sessione in memoria non sopravvive al riavvio.

La chiave owner è distinta dalla chiave di delega MCP: 64 caratteri ASCII esadecimali casuali, condivisi solo APISIX/Onboarding. File read-only leggibile da UID/GID **10003:10003**, non dal container MCP. Gateway conserva il valore in una variabile autorizzata `nginx_config.envs`; YAML APISIX leggibile da UID/GID **636:636**. Verificare i permessi nel container candidato prima dello switch. Vedere il runbook Gateway `docs/PERMISSION_PROPOSALS_DEPLOYMENT.md` per materializzazione, route esatte e rollback.

## Contratto e ciclo di vita

Il Gateway valida l'envelope chiuso e firma i byte originali, percorso, capability, identità, ruoli, scope e chiave di idempotenza con una ricevuta di massimo 30 secondi. L'owner verifica firma, issuer, audience e workload prima di leggere `Arguments`. Ricevute non valide falliscono chiuse. I claim AMR/auth_time non sono ancora trasportati da questo percorso delegato: policy che li richiedono non devono essere considerate soddisfatte.

Una proposta dura **15 minuti**, è immutabile, cambia un grant e fissa hash e riferimento di ACTIVE. UPSERT contiene il grant completo; REVOKE contiene il grantId e nessun grant. La motivazione è obbligatoria. Stessa chiave di idempotenza e stesso contenuto restituiscono la stessa ricevuta; un contenuto diverso produce conflitto. Nessun aggiornamento in-place della proposta.

La THS mostra soggetto/ruolo, tenant, capability, validità, prima/dopo e tutti i vincoli. Checkbox e pulsante inviano hash esatto, revision in `If-Match` e CSRF della sessione. La conferma rivaluta i permessi dell'admin, scadenza e ACTIVE, poi crea/pubblica il draft e finalizza la proposta nella stessa transazione. Conferme concorrenti hanno un solo vincitore. Il rifiuto non pubblica nulla. Se ACTIVE cambia, creare una nuova proposta: non applicare automaticamente una modifica a una policy diversa.

Proponente e approvatore possono essere la stessa persona se possiede entrambe le autorizzazioni. Non viene introdotto un vincolo generalizzato di doppio operatore. Lo stato è leggibile dal proponente con la capability dedicata; un admin del tenant vede la scheda THS. Audit conserva identità e riferimenti; i token non fanno parte della proposta.

## Rilascio e collaudo

1. Aggiornare Onboarding, applicare V27 e configurare IAM/chiave/sessione; mantenere backend privato.
2. Aggiornare Gateway, aggiungere chiave owner alla configurazione Nginx e installare le route generate dal materializzatore permission-proposals. Conservare backup e container precedenti.
3. Aggiornare MCP con i tre strumenti e aggiornare discovery del plugin.
4. Registrare descriptor e concedere solo i grant di lettura/proposta necessari. Accedere con l'identità prevista, controllando il subject.
5. Proporre una modifica di prova; verificare PENDING e ACTIVE invariata. Aprire THS, confrontare i campi, confermare. Verificare PUBLISHED, nuova versione e grant effettivo. Provare anche rifiuto, scadenza/ACTIVE cambiata e assenza di scope.

La CI esegue PostgreSQL reale, rollback/concorrenza/scadenza, CSRF e sessione OAuth2 simulata, interoperabilità Lua/JVM e browser Playwright. Il Gateway esegue anche APISIX reale con JWT/JWKS di test. Questo non sostituisce lo smoke test autenticato dell'installazione con IAM reale. Rollback applicativo conservando V27 e audit; eventuale revoca di un grant già pubblicato richiede una nuova operazione governata.

## Superadmin senza modulo Organizzazione

L'autorità protetta può essere designata tramite ruolo IAM o persona (`issuer`,
`tenant`, `sub`). Il login THS nominale non richiede `externalRoleRefs`; quel claim
serve quando la designazione è organizzativa. I permessi ordinari continuano a
seguire il ciclo proposta, card, conferma e pubblicazione. Vedere
[bootstrap e trasferimento](OUF_ADMIN_BOOTSTRAP.md).

## Ruoli OUF ordinari

La proposta `REPLACE_ROLES` gestisce ruoli OUF e relative assegnazioni nominali
o organizzative. Lettura con `view: ROLES`; revisione e conferma restano nella
stessa THS. Vedere [modello, esempio e limiti](OUF_APPLICATION_ROLES.md).
