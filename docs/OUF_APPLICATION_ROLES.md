# Ruoli applicativi OUF: persone e ruoli organizzativi

Estensione richiesta dal committente il 20 settembre 2026 ai PET Authorization
§7 e §20. L'IAM mantiene l'autorità sulle identità e sui ruoli organizzativi.
OUF conserva esclusivamente ruoli applicativi (insiemi di capability e vincoli)
e assegnazioni autorizzative. Non crea account o una directory degli utenti.

## Modello

Un ruolo OUF può avere più assegnazioni nello stesso tenant:
- `subjectId`: persona identificata dal claim `sub`, senza dipendenza da Organizzazione;
- `externalRoleRef`: ruolo organizzativo attestato dall'IAM.

Ogni assegnazione sceglie esattamente uno dei due. Il catalogo specifica l'issuer,
che deve coincidere con `ouf.iam.issuer` e con quello del principal verificato.
Il tenant deriva sempre dall'identità autenticata. Email, nomi visuali e header
arbitrari non sono selettori. Un cambio issuer è una migrazione governata: non
riassociare automaticamente subject omonimi del nuovo IAM.

Ogni ruolo elenca capability dichiarate nel bundle e i relativi vincoli ABAC.
Ogni assegnazione ha inizio e fine validità espliciti. Entrambe le modalità
possono coesistere; più assegnazioni applicabili concorrono ai permessi. Revocare
una sola assegnazione non elimina altre assegnazioni o grant diretti validi.
DENY applicabile mantiene precedenza su ALLOW. Scopes OAuth, actor type, tenant,
risorse, assurance e classificazione restano verificati dai moduli.

`superadmin` non è un ruolo di questo catalogo. Il ruolo OUF ordinario `admin`
può includere `authorization.policy.admin` e le capability conversazionali
`authorization.permissions.read`, `authorization.permissions.propose` e
`authorization.proposal.read`; non acquisisce il potere di sostituire il superadmin.
L'authority protetta segue il bootstrap e il trasferimento separati.

## Chatbot e conferma THS

1. Chiamare `authorization.permissions.read` con `view: "ROLES"`.
2. Preparare il nuovo catalogo completo preservando tutte le voci non interessate.
3. Chiamare `authorization.permissions.propose` con `operation: "REPLACE_ROLES"`,
   `reason` e `roleCatalogue`. Omettere `grantId` e `grant`.
4. Aprire il collegamento THS. La card mostra catalogo precedente e proposto,
   ruoli, capability, vincoli, destinatari e validità.
5. Un umano amministratore conferma nella sessione IAM, con CSRF, hash ed ETag.
   Catalogo, grant compilati, versione policy e audit sono pubblicati nella
   stessa transazione. Il chatbot non può confermare.

Esempio del campo `roleCatalogue` (date e identificatori da verificare):
```json
{
  "issuer": "https://iam.example/realms/comune",
  "roles": [{"roleId":"operatore","displayName":"Operatore OUF",
    "permissions":[{"capabilityId":"ouf.system.status","constraints":null}]}],
  "assignments": [
    {"assignmentId":"persona-giovanni","roleId":"operatore",
     "subjectId":"sub-verificato","externalRoleRef":null,
     "validFrom":"2026-09-20T00:00:00Z","validUntil":"2027-09-20T00:00:00Z"},
    {"assignmentId":"funzionari-it","roleId":"operatore",
     "subjectId":null,"externalRoleRef":"ente:funzionario-informatico",
     "validFrom":"2026-09-20T00:00:00Z","validUntil":"2027-09-20T00:00:00Z"}
  ]
}
```

Per revocare rimuovere l'assegnazione dal catalogo proposto; per cambiare i
permessi del ruolo modificare la sua lista di capability. Non si può eliminare
un ruolo lasciando assegnazioni che lo referenziano. Nessun fallback automatico
in caso di scomparsa di un claim IAM. Prima di dismettere Organizzazione,
assegnare e verificare i ruoli nominali necessari, poi revocare quelli esterni.

## Compatibilità e installazione

V29 aggiunge metadati di authoring e lo snapshot precedente per la revisione.
I runtime ricevono i consueti grant canonici: il wire contract dei bundle non
cambia. Il prefisso `ouf-role:` è riservato ai grant generati; le API generiche
non possono modificarli o cancellarli separatamente dal catalogo.
I grant diretti preesistenti sono preservati, senza migrazione automatica.
Limiti: 100 ruoli, 1000 assegnazioni, 100 permission per ruolo, massimo 200 grant
generati per catalogo e 48.000 byte per proposta. L'intero bundle resta soggetto
ai limiti canonici. Una policy cambiata durante la revisione richiede una nuova
proposta; conferme scadute, duplicate o prive di autorità falliscono.

Aggiornare prima Onboarding, poi il manifest MCP. Conservare backup DB e
configurazione. Dopo l'uso del catalogo, un rollback a un binario privo delle
protezioni dei grant gestiti non è supportato; non eliminare metadati o migrazioni.
Confermare con un test autenticato di lettura, assegnazione THS e revoca. La sola
CI non attesta il deploy sull'Ente.
