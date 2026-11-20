# Registro della configurazione di Source Onboarding

## Registro delle capability di Authorization

| Campo | Valore |
|---|---|
| Identificativo e versione | `authorization.capability-registration/v1` |
| Componente responsabile | Authorization in Source Onboarding |
| Archivio e classe | Tabella PostgreSQL `ouf_authorization.capability_registration`. Configurazione, tutte le righe. |
| Contenuto | Chiave primaria `capability_id`, `owner_ref`, descrittore JSON con ID, operazione, scope e attori. `registered_by` e `registered_at` conservano la provenienza e il momento della registrazione. Il trigger V16 garantisce l'immutabilità. |
| Segreti | No. La cattura è privata perché può contenere identificativi degli amministratori. |
| Cattura | Includere l'intero schema `ouf_authorization` nel backup PostgreSQL coerente del database Onboarding (`pg_dump` in formato custom), insieme alla cronologia Flyway. Conservare privatamente dump, checksum e distinta ordinata per chiave con conteggio e impronta dei contenuti, inclusa la provenienza. Includere le registrazioni amministrative e quelle di rilascio. |
| Ripristino e dipendenze | Predisporre ruoli PostgreSQL e database di destinazione vuoto. Con l'applicazione ferma, ripristinare lo snapshot completo con `pg_restore --single-transaction`, nell'ordine delle dipendenze del dump. Registro, bundle, puntatore ACTIVE, ruoli, audit e cronologia Flyway devono provenire dalla stessa cattura. Avviare l'artefatto di rilascio corrispondente dopo il controllo dei dati. |
| Prova di ripristino | Su un'istanza isolata confrontare conteggio, chiavi, owner, descrittori e provenienza con la distinta privata. Verificare il riferimento e l'hash di ACTIVE, la corrispondenza di ogni suo descrittore col registro e il secondo avvio senza inserimenti. La prova su un secondo host resta da eseguire. |
