# Consultazione e simulazione dei permessi OUF

## Scopo e autorità normativa

Questo incremento prepara il dominio owner per l'amministrazione conversazionale:
permette all'amministratore umano di consultare i grant configurati e verificare
l'effetto di una bozza prima della pubblicazione. Non introduce ancora tool MCP,
una directory utenti IAM, una scheda browser o la conferma di proposte da chatbot.

Riferimenti: PET Authorization v1.5 §§11–14 e §24; PET MCP v1.4 §§6.1, 83–84;
roadmap `ouf-pet-gap-register-v1.7.json` AUT-03/04 e MCP-01/02. Gli endpoint
amministrativi restano human-only e non MCP-eligible. Il successivo canale delegato
richiederà capability e contratto separati, delega verificabile e una conferma
umana legata alla proposta mostrata; non dovrà chiamare queste API fingendo HUMAN.

Dipende dal bootstrap e dal trasferimento del superadmin descritti in
[OUF_ADMIN_BOOTSTRAP.md](OUF_ADMIN_BOOTSTRAP.md), PR #28. Nessuna migrazione DB
aggiuntiva. I token human-bound e il ruolo IAM rimangono quelli configurati nella
guida; non aggiungere privilegi IAM o scope al client chatbot per usare queste API.

## Autorizzazione e consistenza

Tutti gli endpoint richiedono principal HUMAN verificato, scope
`authorization.policy.admin` e autorità amministrativa OUF attuale, oppure
l'associazione superadmin protetta. L'owner rivaluta la policy ACTIVE sotto lo
stesso lock transazionale usato da pubblicazione e trasferimento superadmin:
un precedente ALLOW del filtro non basta dopo una revoca. Le POST richiedono
anche la prova di scrittura trusted già prevista dal boundary.

I risultati sono `Cache-Control: no-store`. Le operazioni non modificano grant,
bozze, associazione superadmin o policy ACTIVE. Producono eventi amministrativi
append-only `REVIEW_GRANTS`, `PREVIEW_POLICY`, `SIMULATE_POLICY`, attribuiti al
chiamante reale. L'eventuale decisione di accesso dell'admin ordinario è anch'essa
registrata; l'identità ipotetica non genera decisioni di enforcement né entra
nell'audit come chiamante. Non vengono registrati bearer o payload degli scenari.

## Grant configurati

`GET /api/trusted-human/v1/authorization/access`

Usare esattamente uno tra `subjectId` e `externalRoleRef`; `limit` è 1–200,
default 100. Sono mostrati solo i grant del tenant verificato del chiamante.
Il risultato include `policyRef`, `policyHash`, `grants`, `nextAfter` e il campo
`meaning=CONFIGURED_GRANTS_NOT_EFFECTIVE_PERMISSIONS`.

- Per soggetto vengono restituiti i grant nominali, anche scaduti o DENY.
- Per ruolo vengono restituiti i grant che menzionano quel ruolo, inclusi gli
  eventuali vincoli ulteriori. Se il ruolo coincide con il superadmin del tenant,
  `protectedRole` mostra separatamente issuer, ruolo e revisione dell'associazione.
- Nessun risultato non significa che il soggetto non esista o non abbia accessi:
  non viene interrogato l'IAM e non vengono dedotti ruoli, gruppi o grant service.
- Per proseguire passare `after=nextAfter` insieme al medesimo `policyRef`.
  Un cambio di ACTIVE produce 409 e richiede di ricominciare; nessuna pagina
  combina silenziosamente versioni diverse. `after` senza `policyRef` produce 428.

Esempio di percorso, con subject URL-encoded quando necessario:

```text
/api/trusted-human/v1/authorization/access?subjectId=177fd705-b57f-4f9e-a23c-af9d1f3f1f75&limit=100
```

Il ruolo superadmin non rende impliciti tutti i permessi di dominio. La sua
autorità protegge l'amministrazione delle policy; gli altri owner continuano
ad applicare i propri evaluator e vincoli.

## Anteprima della bozza

`POST /api/trusted-human/v1/authorization/policies/{id}:preview`

Inviare `If-Match: "<revisione>"`. La bozza deve appartenere al tenant del
chiamante, essere DRAFT e basarsi ancora sulla stessa ACTIVE. La risposta riporta
revisione, riferimento ACTIVE, hash della policy attiva e della bozza, capability
aggiunte/rimosse e grant nominali/per ruolo aggiunti, rimossi o modificati con
valori `before`/`after`. I grant di altri tenant non sono esposti nel diff.

Il diff contiene al massimo 200 cambiamenti totali; oltre il limite la richiesta
fallisce con 413, senza troncare una revisione che potrebbe sembrare completa.
Una revisione diversa produce 412; una ACTIVE cambiata produce 409; una bozza
inesistente o di altro tenant produce 404. L'anteprima è `authoritative=false`:
non costituisce un'approvazione né una credenziale e non pubblica nulla.

## Simulazione

`POST /api/trusted-human/v1/authorization/policies/{id}:simulate`

Usa lo stesso `If-Match` e gli stessi controlli di consistenza dell'anteprima.
Il corpo ha struttura rigorosa; campi sconosciuti sono rifiutati:

```json
{
  "hypotheticalPrincipal": {
    "subjectId": "persona-da-verificare",
    "tenantId": "ouf-lab",
    "actorType": "HUMAN",
    "servicePrincipalId": null,
    "authenticationContextRef": "simulation",
    "issuer": "https://auth.ouf-lab.it/realms/ouf",
    "audience": "ouf-api-gateway",
    "scopes": ["operations.status.read"],
    "claims": {
      "externalRoleRefs": ["funzionario-informatico"],
      "acr": "1",
      "amr": [],
      "authenticatedAt": null
    }
  },
  "resource": {
    "resourceType": "capability",
    "resourceId": null,
    "tenantId": "ouf-lab",
    "organizationId": null,
    "attributes": {"detailLevel": "PUBLIC_OPERATIONAL"}
  },
  "capabilityId": "ouf.system.status",
  "operation": "READ"
}
```

L'evaluator condiviso valuta ACTIVE e bozza allo stesso istante del server.
La risposta `before`/`after` comprende allowed, codice e dettaglio consentito,
con `contextSource=HYPOTHETICAL_NOT_IAM_VERIFIED` e `authoritative=false`.
Non vengono emessi `decisionRef` utilizzabili come prova di autorizzazione.
Tenant del soggetto e della risorsa devono coincidere con quello del chiamante.
Ruoli e scope dello scenario non sostituiscono mai l'identità autenticata.

La simulazione riguarda i grant del bundle, non l'associazione superadmin separata.
Non prova l'esistenza del soggetto, i suoi ruoli reali, l'accesso a ogni risorsa,
l'enforcement aggiuntivo dell'owner o la riuscita di una chiamata MCP.
I limiti includono 32 ruoli, 128 scope, 64 attributi risorsa e 5 MiB del boundary;
restano in vigore i limiti di cardinalità del bundle.

## Rilascio e verifica

1. Integrare prima la dipendenza PR #28 e usare il runbook di deploy Onboarding,
   conservando backup e rollback. Nessuna reinstallazione IAM richiesta.
2. Configurare le rotte sul Gateway secondo il contratto OpenAPI; non esporle
   come route M2M o nel manifest MCP. Questo incremento non installa rotte live.
3. Accedere sul canale umano con autorità amministrativa e verificare lookup
   nominale e per ruolo nello stesso tenant.
4. Creare una bozza attraverso il lifecycle esistente, chiamare preview e
   simulate e verificare che la versione ACTIVE non sia cambiata.
5. Verificare negativi di scope/ruolo, tenant, ETag e ACTIVE cambiata. I token
   reali non devono essere incollati in chat o nei report.

`AuthorizationReviewRuntimeTest` esercita queste invarianti con PostgreSQL e
MockMvc; l'identità del boundary è un fixture trusted. La prova IAM/Gateway
reale e il futuro flusso chatbot/THS sono gate distinti e ancora da eseguire.
