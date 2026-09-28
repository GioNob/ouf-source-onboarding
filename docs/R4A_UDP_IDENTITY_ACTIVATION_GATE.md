# R4a: gate dell'identità canonica UDP

La fonte è generica: CSV, altri file e verticali via API percorrono Ingestion,
che produce handoff per oggetto con `canonicalPayload` e provenienza. UDP non
riceve il file né decide in base all'estensione. Il profilo di risoluzione
pubblicato governa il confronto degli oggetti canonici.
L'eventuale `sourceObjectId` serve al lineage e all'idempotenza della fonte;
non è assunto come identificatore stabile dell'oggetto urbano condiviso.

Onboarding valida una proposta `governedIdentity` soltanto in DRAFT. Esige
scope di tenant, classe e fonte, versione semantica e comparatori, insieme
esatto di tutte le proprietà canoniche *possibili* nella mappatura, e una regola
che confronti i campi effettivamente esposti da ciascun oggetto. L'oggetto
con più campi può essere quello in ingresso o quello già presente in UDP:
tutti i campi di quello con meno proprietà devono corrispondere. Un singolo campo non è dichiarato
univoco; coordinate e indirizzi discordanti sono indizi per la revisione, non
esclusioni automatiche. La proposta resta inattiva: UDP non esegue ancora
questo profilo nel worker pubblicato. Anche `weighted` resta bloccato con
`ONB_UDP_IDENTITY_RUNTIME_UNAVAILABLE`, senza modificare la storia delle
approvazioni. Per ora il runtime accetta solo il profilo legacy completo.

La fixture JSON è identica in Onboarding e UDP e verifica il contratto di
trasferimento della configurazione; non attesta che esistano un indice completo,
una policy approvata o un runtime pubblicato. Prima dell'attivazione servono
la verifica della compatibilità sul bundle congelato, copertura dei candidati,
comparazione delle strutture canoniche non scalari e il flusso THS con elenco
integrale degli issue, proposte modificabili e conferma atomica esclusivamente
umana. Nessun risultato R-SMOKE o R-INSTALL segue da questa gate.
