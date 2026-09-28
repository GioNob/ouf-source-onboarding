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
La proposta resta inattiva: UDP non esegue ancora
questo profilo nel worker pubblicato. Anche `weighted` resta bloccato con
`ONB_UDP_IDENTITY_RUNTIME_UNAVAILABLE`, senza modificare la storia delle
approvazioni. Per ora il runtime accetta solo il profilo legacy completo.

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
anche classi con campi variabili, ma
nessun processo di attestazione di
produzione lo invoca ancora; le
mutazioni invalidano le eventuali attestazioni precedenti. Servono inoltre
la comparazione delle strutture canoniche non scalari e la verifica live del
pacchetto di review. UDP prepara elenco, proposte e conferma atomica. La THS
espone `/trusted-human/resolution/` soltanto quando è configurato
`ouf.authorization.ths.enabled=true` e
`ouf.resolution.ths.gateway-base-url=https://<gateway>`. Il suo backend
inoltra GET/POST all'owner UDP attraverso Gateway con il token della sessione
OIDC HUMAN, che non è consegnato al browser JavaScript. Spring CSRF protegge
la conferma; hash e versioni sono ricontrollati da UDP. Configurare e verificare
la route Gateway con capability `resolution.issue.read` e
`resolution.match.approve`, la sessione IAM, TLS e la card nel lab prima del
deploy. La proiezione autorizzata MCP per la tabella chatbot manca ancora;
anche il backfill tenant delle issue storiche senza candidati richiede
riconciliazione. Nessun risultato R-SMOKE o R-INSTALL segue da questa gate.
