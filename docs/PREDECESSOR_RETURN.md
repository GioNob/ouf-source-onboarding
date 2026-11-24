# Prova CI del ritorno al predecessore

Per la V43 il predecessore designato in esercizio è `79672b55aefa2ef43c777e9bba904e0c48663f75`, con schema V42. D-080 richiede un esito verde del job `predecessor-return` per la coppia di revisioni prima di qualificare il rilascio `rollbackCompatible`. Una modifica successiva del candidato o del predecessore richiede una nuova prova.

Il workflow gira su push e pull request. Con `workflow_dispatch` il parametro `predecessor` accetta uno SHA. Il candidato è il commit effettivamente estratto come HEAD dal runner, incluso il merge di prova nelle pull request. Il job costruisce dai due alberi Git, controlla le etichette OCI contro gli SHA completi e registra SHA e ID immutabili delle immagini nel log. Lo scenario corrente verifica espressamente il passaggio da V42 a V43.

## Dati e verifiche

| Fase | Scritture e osservazioni |
|---|---|
| Predecessore su PostgreSQL 17 vuoto | Flyway arriva a V42. Il worker profila due piccoli CSV. Il servizio crea una fonte gestita con versione in review e challenge, e una seconda fonte con bozza pronta per il candidato. |
| Candidato sul medesimo database | Flyway aggiunge V43 e conserva la storia precedente. Il candidato prepara la seconda bozza e congela lo snapshot. Una sessione THS invia `changes` con CSRF e riscontro sul campo `unused`. Il candidato crea la versione figlia con classificazione rivista, conservando asset e profilo. Il job legge da PostgreSQL decisione, riscontro e snapshot. |
| Ritorno | Il candidato si ferma e Docker riavvia il container originale. La readiness deve dare HTTP 200 e `UP`. Il predecessore legge la versione V42, il rifiuto e la nuova bozza. Una nuova sessione THS deve ricevere la pagina HTML e la scheda autenticata del challenge cancellato, con hash e stato corretti. Il predecessore ripete il comando di creazione del candidato, prepara una nuova review sulla bozza figlia e ripete la preparazione con la stessa chiave. Il job controlla identità, conteggi e conservazione dei dati V43. |

La V43 aggiunge `managed_file_asset.display_name`, le colonne nullable `approval_decision.review_outcome` e `review_feedback` e la tabella append-only `managed_review_snapshot`. Il candidato scrive lo snapshot alla preparazione e il nuovo esito alla richiesta esplicita di modifiche. Il predecessore continua a usare `onboarding_version.state = 'REJECTED'`, `approval_decision.decision = 'REJECT'` e `approval_challenge.status = 'CANCELLED'`. Le chiavi della review e del comando di creazione devono restare leggibili da entrambe le revisioni.

## Readiness ed evidenza

L'immagine V42 usa il proprio Flyway con la validazione all'avvio e il comportamento predefinito per le migrazioni future (`*:future`). Il job mantiene lo stesso database, la stessa configurazione e lo stesso container nelle due esecuzioni del predecessore. Un rifiuto di V43, un timeout della readiness o una verifica funzionale fallita interrompe il job. In caso di rifiuto Flyway, riportare gli SHA e la fase fallita in **Domande aperte** e sospendere la qualificazione.

Le operazioni di onboarding attraversano le API delle immagini. PostgreSQL riceve direttamente soltanto la policy di identità della fixture e le interrogazioni di controllo. Identità OIDC, chiavi di firma, deleghe, contenuto dei CSV e sessioni sono locali al runner. La prova copre i binari costruiti dai commit dichiarati. Per confrontarli con un'immagine distribuita occorre anche il relativo digest di distribuzione.
