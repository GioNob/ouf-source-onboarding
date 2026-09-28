# R4a: gate dell'identità canonica UDP

La fonte è generica: CSV, altri file e verticali via API percorrono Ingestion,
che produce handoff per oggetto con `canonicalPayload` e provenienza. UDP non
riceve il file né decide in base all'estensione. Il profilo di risoluzione
pubblicato governa il confronto degli oggetti canonici.
La `sourceObjectIdentity` tecnica serve al lineage e all'idempotenza della
fonte; un binding ACTIVE valido preserva la continuità dell'osservazione
sorgente, ma non identifica da solo l'oggetto urbano condiviso fra fonti.

Onboarding valida una proposta `governedIdentity` soltanto in DRAFT. Esige
scope di tenant, classe e fonte, versione semantica e comparatori, insieme
esatto di tutte le proprietà canoniche *possibili* nella mappatura, e una regola
che confronti i campi effettivamente esposti da ciascun oggetto. L'oggetto
con più campi può essere quello in ingresso o quello già presente in UDP:
tutti i campi di quello con meno proprietà devono corrispondere e i campi
comuni devono soddisfare una regola sufficiente approvata dalla policy. Un
singolo campo non è dichiarato univoco per default; una coordinata o un
indirizzo discordante mentre altri campi coincidono richiede revisione. Se
tutti i campi comuni confrontabili sono diversi, anche quando uno dei due
oggetti espone altri campi, sono distinti secondo questa strategia;
`allowAutoNew` è la dichiarazione source-scoped per creare automaticamente;
un campo non confrontabile o assente non prova questa distinzione.
La proposta resta inattiva finché il preflight non ha ricostruito e attestato
la copertura per l'hash esatto della configurazione congelata. UDP ha collegato
il profilo al worker pubblicato e verifica la copertura indicizzata prima di
una decisione automatica. Onboarding interroga l'attestazione corrente via
Gateway all'attivazione, usando una credenziale SERVICE con la capability
`ouf.udp.identity.attestation.read`. Una risposta assente, non valida o
difforme blocca l'attivazione. Anche `weighted` resta bloccato con
`ONB_UDP_IDENTITY_RUNTIME_UNAVAILABLE`.

La fixture JSON è identica in Onboarding e UDP e verifica il contratto di
trasferimento della configurazione; non attesta che esistano un indice completo,
una policy approvata o un runtime pubblicato. Prima dell'attivazione servono
la verifica della compatibilità sul bundle congelato, un indice inverso dei
valori canonici con backfill e copertura attestata. La ricerca deve partire da
tutte le proprietà esposte, prendere l'unione dei candidati indicizzati e
confrontare solo questi: la scansione di tutti gli oggetti della classe è stata
rimossa e, senza indice completo, UDP restituisce copertura non verificata.
UDP ha preparato gli indici dei valori, il catalogo delle forme distinte dei
campi e la ricerca delle sole forme disgiunte; un backfill esplicito copre
anche classi con campi variabili, ma nessun processo di attestazione di
produzione lo invoca automaticamente: un amministratore HUMAN con
`urban.identity.preflight` deve richiamare
`POST /api/udp/v1/governance/identity/preflight` con `sourceId`,
`configurationHash` e l'esatta `configuration` congelata. Il worker aggiorna i token per l'oggetto che
materializza nella stessa transazione; le altre mutazioni invalidano
l'attestazione. `JSON_V1` confronta strutture JSON canoniche con chiavi
ordinate, ordine delle liste conservato e numeri normalizzati. Servono la
verifica di ogni tipo canonico e policy concreta e la verifica live del
pacchetto di review. UDP prepara elenco, proposte e conferma atomica. La THS
espone `/trusted-human/resolution/` soltanto quando è configurato
`ouf.authorization.ths.enabled=true` e
`ouf.resolution.ths.gateway-base-url=https://<gateway>`. Il suo backend
inoltra GET/POST all'owner UDP attraverso Gateway con il token della sessione
OIDC HUMAN, che non è consegnato al browser JavaScript. Spring CSRF protegge
la conferma; hash e versioni sono ricontrollati da UDP. Configurare e verificare
la route Gateway con capability `resolution.issue.read` e
`resolution.match.approve`, la sessione IAM, TLS e la card nel lab prima del
deploy. La THS offre `CREATE_NEW` per le issue governed con copertura
completa: dopo la conferma del pacchetto, il worker crea e materializza
l'oggetto nella stessa transazione solo se la copertura è ancora valida.
La proiezione autorizzata MCP prepara la tabella nel chatbot;
anche il backfill tenant delle issue storiche senza candidati richiede
riconciliazione. Nessun risultato R-SMOKE o R-INSTALL segue da questa gate.

## Collegamento dell'installazione

Prima del preflight registrare entrambe le capability nel catalogo di
Authorization e pubblicare le route Gateway di questa PR; concedere
`urban.identity.preflight` a `ouf-admin` e
`ouf.udp.identity.attestation.read` soltanto all'identità SERVICE di
Onboarding. Configurare `OUF_ONB_UDP_IDENTITY_GATEWAY_URL` con la base URL
del Gateway e `OUF_ONB_UDP_IDENTITY_TOKEN_FILE` con il percorso di un token
workload ruotabile, senza inserirlo nel bundle o nei log. Il preflight è una
scansione una tantum del perimetro tenant/classe sotto lock: la route attuale
ha timeout di 60 secondi, quindi per perimetri più grandi serve un job
asincrono prima di dichiarare pronta l'attivazione. Una mutazione concorrente
o successiva rende l'attestazione non valida; rieseguire il preflight. Il
controllo live tra Onboarding e UDP e lo smoke completo restano da eseguire
nel lab con IAM, Gateway e credenziali reali.
