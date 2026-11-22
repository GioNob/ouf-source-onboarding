# Prova CI del ritorno al predecessore

Per il ricontrollo delle dipendenze il predecessore designato in esercizio è `b3f33586dd3b1bb427838f2fa1596b1bc58a55b3` (PR #69), con schema V43. D-080 richiede un esito verde del job `predecessor-return` per la coppia di revisioni prima di qualificare il rilascio `rollbackCompatible`. Una modifica successiva del candidato o del predecessore richiede una nuova prova.

Il workflow gira su push e pull request. Con `workflow_dispatch` il parametro `predecessor` accetta uno SHA. Il candidato è il commit effettivamente estratto come HEAD dal runner, incluso il merge di prova nelle pull request. Il job costruisce dai due alberi Git, controlla le etichette OCI contro gli SHA completi e registra SHA e ID immutabili delle immagini nel log. Lo scenario verifica l’uguaglianza della cronologia Flyway V43 nelle tre fasi.

## Dati e verifiche

| Fase | Scritture e osservazioni |
|---|---|
| Predecessore su PostgreSQL 17 vuoto | Flyway arriva a V43. Il worker profila due piccoli CSV. La prima fonte viene approvata e una richiesta di attivazione via THS incontra un 422 di Ingestion. La seconda fonte resta in bozza. |
| Candidato sul medesimo database | La scheda autenticata della prima fonte abilita `resume`. Il comando THS con CSRF registra la ripresa, conserva tentativi e scadenza e avanza la generazione. Per la seconda fonte, il candidato prepara la bozza, congela lo snapshot, riceve `changes` dalla THS e crea una versione figlia con classificazione rivista. |
| Ritorno | Si riavvia il container originale e la readiness deve dare HTTP 200 e `UP`. Il predecessore legge dalla THS la prima review ancora approvata e in `PAUSED`, con il budget e la generazione salvati dal candidato. Legge anche il challenge cancellato della seconda fonte e la bozza figlia. Ripete il comando di creazione della figlia, prepara la review e ripete la preparazione con la stessa chiave. |

Il ricontrollo riusa lo stato `PAUSED`, la generazione e l’audit esistenti. La colonna `readiness_last_code` conserva il codice 422, già scritto dal predecessore. La seconda fonte verifica la conservazione dei dati V43 di decisione, riscontro e snapshot e delle chiavi idempotenti di creazione e revisione.

## Readiness ed evidenza

Il job mantiene lo stesso database, la stessa configurazione e lo stesso container nelle due esecuzioni del predecessore. Una differenza nella cronologia Flyway, un timeout della readiness o una verifica funzionale fallita interrompe il job. In caso di rifiuto Flyway, riportare gli SHA e la fase fallita in **Domande aperte** e sospendere la qualificazione.

Le operazioni di onboarding attraversano le API delle immagini. PostgreSQL riceve direttamente soltanto la policy di identità della fixture e le interrogazioni di controllo. Identità OIDC, chiavi di firma, deleghe, contenuto dei CSV e sessioni sono locali al runner. La prova copre i binari costruiti dai commit dichiarati. Per confrontarli con un'immagine distribuita occorre anche il relativo digest di distribuzione.
