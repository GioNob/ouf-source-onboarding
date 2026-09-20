# Superadmin OUF per ruolo IAM: bootstrap e trasferimento

## Decisione e perimetro

Decisione concordata il 20 settembre 2026: l'IAM dell'Ente identifica persone e
ruoli organizzativi; OUF associa un ruolo organizzativo ai poteri di superadmin.
Il riferimento è configurabile per Comune. Non si crea un ruolo `superadmin OUF`
nel Keycloak del fornitore e non si replica una base utenti o password.

Il superadmin può amministrare le policy e quindi nominare/revocare gli admin
ordinari tramite grant OUF. La sua associazione è un'autorità di governo
separata dal PolicyBundle ordinario: un admin non può revocarla tramite grant,
DENY, rimozione dei descriptor o sostituzione della policy. Solo il flusso di
trasferimento può sostituirla. Questo non attribuisce automaticamente capability
operative nei moduli: sui dati e sulle operazioni di dominio restano necessari
le policy, gli scope e i controlli dell'owner.

Questa versione sostituisce la precedente proposta della PR #28 basata su
`admin-subject` e primo grant nominale. Il subject non è più una proprietà di
bootstrap: viene registrato nell'audit come identità della persona che agisce.

PET consultati: Authorization §§3, 6.3–7.2, 11–14, 20.2, 22, 23 e 109;
MCP §§83–84. L'associazione protetta precisa il confine tra policy ordinaria e
autorità di governo: i DENY della policy ordinaria restano validi per il dominio,
ma non possono annullare il diritto del superadmin a governare quella policy.
Questa è una decisione esplicita del committente, non un comportamento implicito
dell'evaluator condiviso. Roadmap AUT-03/04 rimane PARTIAL: la prova modulo non
chiude l'accettazione di tutti i canali e domini.

Sono fuori perimetro custodi, break-glass, nuovi meccanismi di revoca IAM e
amministrazione conversazionale. Disponibilità dell'incarico, disabilitazione e
recupero delle identità restano responsabilità dell'IAM dell'Ente. Un token già
emesso può restare utilizzabile fino alla propria scadenza. La sostituzione del
ruolo nella tabella protetta è invece verificata a ogni operazione amministrativa,
senza attendere il refresh del bundle dei consumer.

## Configurazione per Comune

Configurare Onboarding prima del bootstrap:

```properties
ouf.iam.enabled=true
ouf.iam.issuer=https://iam.example/realms/ente
ouf.iam.audience=ouf-api-gateway
ouf.authorization.bootstrap.admin-issuer=https://iam.example/realms/ente
ouf.authorization.bootstrap.admin-tenant=comune-esempio
ouf.authorization.bootstrap.superadmin-role=ente:installatore
```

Variabili Spring Boot equivalenti per l'associazione:

```text
OUF_AUTHORIZATION_BOOTSTRAP_ADMINISSUER
OUF_AUTHORIZATION_BOOTSTRAP_ADMINTENANT
OUF_AUTHORIZATION_BOOTSTRAP_SUPERADMINROLE
```

L'issuer deve coincidere esattamente con quello autenticato. Il tenant deve
coincidere con `tenant_id`. Il riferimento del ruolo deve essere stabile e
coincidere con un valore attestato nel token; non usare un'etichetta visuale o
un attributo modificabile dall'utente. Il client IAM deve poter ottenere gli
scope tecnici `authorization.bootstrap` per l'inizializzazione e
`authorization.policy.admin` per amministrazione, proposta e accettazione.
Questi scope non rendono da soli superadmin: servono ruolo, issuer e tenant.
L'associazione iniziale può indicare il ruolo dell'installatore, da trasferire
al termine della configurazione a quello individuato dal Comune.

Claim canonico:

```json
{"tenant_id":"comune-esempio","externalRoleRefs":["ente:installatore"]}
```

L'adapter JWT Onboarding usa `externalRoleRefs` (array di stringhe). Per i client
esistenti accetta anche `external_role_refs`; se sono presenti entrambi devono
contenere lo stesso insieme. Claim malformati, duplicati e conflitti sono
respinti. Il profilo è coerente con Gateway/MCP: massimo 32 ruoli, identificativi
ASCII di 1–128 caratteri `[A-Za-z0-9_:./-]`, elenco serializzato massimo 4096
caratteri. Non vengono letti implicitamente `realm_access.roles` o
`resource_access`. Il mapper IAM deve emettere gli identificativi pertinenti.

## Nuova installazione

1. Il titolare del ruolo configurato accede tramite IAM con lo scope bootstrap.
2. Registra le capability necessarie e crea la prima bozza di policy. Non è
   necessario un grant nominale per attribuirsi il superadmin.
3. Pubblica la revisione esatta della bozza tramite `If-Match` sul canale
   trusted-human. OUF ricontrolla il ruolo verificato e persiste l'associazione
   protetta, la prima policy, lo storico e la chiusura del bootstrap nella stessa
   transazione. Un rollback annulla tutte queste modifiche.
4. Usa un token con `authorization.policy.admin` per le operazioni successive.

Il primo ruolo non può essere scelto dal corpo della richiesta HTTP. Le
proprietà iniziali non sono più fonte di autorità dopo l'installazione:
cambiarle o riavviare non sostituisce il ruolo persistito. Il latch non si
riapre. L'associazione non dispone di endpoint DELETE e non può diventare vuota.

## Installazione già inizializzata

Non cancellare il latch o la policy attiva. La migrazione V26 crea le nuove
tabelle senza attribuire automaticamente autorità a qualcuno. Gli admin
esistenti continuano a essere autorizzati dalla policy attiva.

Per adottare una sola volta il modello protetto:

1. Configurare issuer, tenant e ruolo iniziale sul servizio.
2. Un titolare di quel ruolo accede con `authorization.policy.admin`.
3. Lo stesso principal deve essere già autorizzato dalla policy ACTIVE alla
   capability `authorization.policy.admin` nel contesto amministrativo.
4. Eseguire `POST /api/trusted-human/v1/authorization/superadmin:adopt` dal
   canale umano autenticato. La verifica usa direttamente la policy attiva,
   non una capability dichiarata dal chiamante o uno snapshot locale obsoleto.
5. Controllare la risposta con `roleRef` e `revision: 0`. Tentativi successivi
   non sostituiscono l'associazione. Proseguire con il trasferimento ordinario.

Occorrono entrambe le autorità: ruolo configurato e abilitazione amministrativa
preesistente. L'adozione non è una procedura di recupero quando non esistono più
admin. Non usare il token o la configurazione del laboratorio senza verificare
il ruolo effettivamente attestato dall'IAM.

## Trasferimento al ruolo dell'Ente

Tutte le route seguenti sono sotto `/api/trusted-human/v1/authorization` e
richiedono principal HUMAN verificato; le scritture richiedono trusted write
proof. Non sono strumenti MCP. Il contratto completo è
[authorization-admin-v1.yaml](../openapi/authorization-admin-v1.yaml).

| Passo | Operazione | Identità |
|---|---|---|
| Leggere ruolo e revisione | `GET /superadmin` | Titolare del ruolo attuale |
| Proporre il subentro | `POST /superadmin/transfers` | Titolare del ruolo attuale |
| Leggere proposta esatta | `GET /superadmin/transfers/{id}` | Ruolo attuale o destinatario |
| Confermare il subentro | `POST /superadmin/transfers/{id}:accept` | Titolare del ruolo destinatario |
| Annullare prima della conferma | `POST /superadmin/transfers/{id}:cancel` | Titolare del ruolo attuale |

Per proporre, inviare l'ETag del binding (per esempio `If-Match: "0"`) e:

```json
{"targetRoleRef":"ente:direttore-innovazione","reason":"Installazione completata; trasferimento al ruolo competente del Comune"}
```

La proposta restituisce id, issuer, tenant, ruoli sorgente/destinatario,
revisione di base, motivazione, proponente e scadenza di 15 minuti. Il vecchio
ruolo mantiene l'autorità mentre la proposta è pendente. Una sola proposta
pendente per tenant è ammessa; quella scaduta può essere sostituita.

Un titolare del nuovo ruolo accede con il proprio token IAM, legge la proposta
e conferma usando il relativo ETag (`If-Match: "0"`). Il corpo della conferma
non seleziona un ruolo diverso: il destinatario è già fissato dalla proposta.
La stessa persona può confermare se l'IAM le attesta anche il nuovo ruolo;
questo flusso verifica la titolarità, non impone due persone distinte.

La conferma aggiorna ruolo e revisione, consuma la proposta e scrive storico e
audit in una sola transazione serializzata. Il nuovo ruolo diventa autorità di
governo e il vecchio perde quei poteri. Eventuali grant ordinari indipendenti
del vecchio titolare restano invariati: perdere il superadmin non equivale a
revocare tutte le sue abilitazioni nominali o organizzative.

ETag obsoleto: 412; proposta scaduta: 410; proposta già consumata/cancellata o
conflitto: 409; identità, scope o ruolo errati: 403; altro tenant: 404.
La procedura non consente di cambiare issuer o tenant. Un cambio di IAM è una
migrazione distinta. Nessuna chiamata alla directory o all'Admin API Keycloak
è necessaria per scegliere il destinatario: l'esistenza e la titolarità vengono
provate dall'accesso IAM al momento dell'accettazione.

## Protezioni e collaudo

- Gli admin continuano a gestire grant nominali e di ruolo nel proprio tenant.
  Le bozze sono associate al tenant e non possono alterare i grant degli altri.
- Gli endpoint protetti non consultano grant ordinari per attribuire autorità
  di trasferimento; aggiungere un grant o un header `superadmin` non la conferisce.
- Le scritture alle policy ricontrollano l'autorità dentro lo stesso lock usato
  dal trasferimento: una richiesta del vecchio superadmin in attesa non mantiene
  privilegi acquisiti prima del subentro.
- Lo storico delle associazioni è append-only; nessuna cancellazione del binding.
- Audit: installazione, proposta, annullamento e accettazione conservano identità,
  tenant, riferimento di revisione e correlazione. Motivo e ruoli sono nella
  proposta persistita; nessun token o secret viene registrato.
- Test: bootstrap per ruolo, catena bearer HTTP, adozione legacy a doppia
  condizione, dinieghi IAM/tenant/scope/prova, DENY ordinario non neutralizzante,
  cancellazione/scadenza, replay, concorrenza e rollback atomico.

Il test bearer usa un JwtDecoder controllato per isolare il flusso applicativo;
la firma/discovery IAM reali e il percorso Gateway del vostro ambiente vanno
collaudati prima del rilascio. Configurazione OIDC, sessioni e recupero identità
restano nell'IAM esistente. Nessuna nuova garanzia di revoca istantanea.
