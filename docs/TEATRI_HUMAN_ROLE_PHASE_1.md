# Ruolo HUMAN Teatri

Il manifest [teatri-human-role-phase-1.json](../catalogue/teatri-human-role-phase-1.json)
contiene un ruolo e la sua assegnazione. È un'aggiunta al catalogo del tenant
`ouf-lab`. I cinque grant nominativi e il ruolo amministrativo esistente
restano nel bundle. La data iniziale coincide con quella dei nominativi e
l'assegnazione scade il 15 settembre 2036 alle 07:13:50.968730 UTC.

## Preparazione e anteprima

1. Leggere il catalogo con `authorization.permissions.read` e argomenti
   `{"view":"ROLES"}`. Leggere il bundle attivo dall'owner Authorization.
   Confrontare i cinque grant di riferimento con il manifest di rinnovo
   [teatri-human-grant-renewal-20261109.json](../catalogue/teatri-human-grant-renewal-20261109.json).
   Verificare tenant, soggetto, `constraints: null` e finestra di validità.
2. Copiare la fotografia corrente del catalogo mantenendo l'issuer, i ruoli e le
   assegnazioni. Aggiungere il solo `role` a `roles` e la sola `assignment` ad
   `assignments`. Se un ID coincide con un elemento diverso, fermarsi e
   rivedere il diff. Preparare `authorization.permissions.propose` con
   `operation: REPLACE_ROLES`, `roleCatalogue` uguale alla fotografia estesa,
   una motivazione e una nuova chiave di idempotenza.
3. Aprire la scheda THS restituita e rivedere `beforeRoles`, `afterRoles`,
   `baseActiveRef`, revisione e `proposedHash`. Il diff del bundle compilato
   atteso è l'aggiunta dei soli cinque grant `ouf-role:`, uno per capability.
   Gli ID si ottengono con il prefisso tenant prodotto da `OufRoleCatalogue`
   e lo SHA-256 UTF-8 di `assignmentId + "\n" + capabilityId`. Ciascuno ha lo
   stesso tenant e soggetto del nominativo, le date dell'assegnazione e
   `constraints` con `effect: ALLOW`, collezioni vuote e selettori null.
   Le capability, i nominativi e gli altri grant devono coincidere con il
   bundle attivo. Il `proposedHash` della scheda impegna insieme il bundle
   compilato e il catalogo.

## Simulazione dell'owner

`POST /api/trusted-human/v1/authorization/policies/{id}:simulate` legge un
draft amministrativo. La proposta THS non è un draft amministrativo e il
registro rifiuta di aggiungere i suoi nuovi ID `ouf-role:` tramite l'API
generica prima della conferma del catalogo.

1. Preparare un draft di simulazione con `POST /api/trusted-human/v1/authorization/policies`
   a partire dallo stesso bundle attivo, con versione successiva e tutti i
   grant esistenti. Aggiungere i cinque grant compilati attesi usando **solo
   per la simulazione** ID univoci fuori dal prefisso `ouf-role:`. Mantenere
   gli altri campi identici alla proiezione del catalogo. Il valutatore non
   usa l'ID del grant per decidere.
2. Chiamare `POST /policies/{id}:preview` e `POST /policies/{id}:simulate` sotto
   lo stesso prefisso, usando l'ETag del draft in `If-Match`. Verificare che il
   diff aggiunga solo i cinque grant di simulazione, che la base sia quella
   della proposta THS e che ogni risposta si riferisca allo stesso hash del
   draft. Simulare almeno i cinque controlli HUMAN e la readiness di
   Ingestion con `resourceType: source` e `onboardingVersionId`. Provare anche
   un altro soggetto, un attore SERVICE e le capability estranee al ruolo.
   Vedere [AUTHORIZATION_REVIEW.md](AUTHORIZATION_REVIEW.md) per il corpo della
   richiesta.
3. L'owner usa l'ora corrente. L'equivalenza dopo il 31 dicembre si verifica
   nel test Java a data fissa. L'hash del draft di simulazione e il
   `proposedHash` THS identificano due oggetti diversi. Abbandonare il draft
   con `DELETE /policies/{id}` e il relativo `If-Match` dopo la simulazione.

## Conferma e verifica successive

La conferma HUMAN resta una decisione separata. Rileggere la scheda THS e
verificare che revisione, `proposedHash` e base coincidano con quelli rivisti.
Confermare solo quella proposta con `If-Match` sulla revisione e
`expectedHash` pari al `proposedHash`. La conferma pubblica la policy.

Dopo la conferma, confrontare `finalPolicyRef` con la versione attiva
dell'owner. Verificare il diff dei grant, incluso che i cinque nominativi
abbiano ancora la scadenza originale. Rilevare lo stesso bundle nelle decisioni
di Onboarding per la review gestita e di Ingestion per la readiness `source`
dello stesso HUMAN. Una risposta positiva con i nominativi ancora validi
richiede anche il controllo della versione caricata. La sola risposta positiva
non prova che il ruolo sia disponibile.
