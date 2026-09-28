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
UDP ha preparato gli indici dei valori e delle forme dei campi, la ricerca
per semi e un backfill esplicito anche per classi con campi variabili, ma
nessun processo di attestazione di
produzione lo invoca ancora; le
mutazioni invalidano le eventuali attestazioni precedenti. Servono inoltre
la comparazione delle strutture canoniche non scalari e il flusso THS con elenco
integrale degli issue, proposte modificabili e conferma atomica esclusivamente
umana. Nessun risultato R-SMOKE o R-INSTALL segue da questa gate.
