# Primo amministratore OUF e bootstrap

## Decisione applicativa

L'IAM esterno autentica la persona e attesta i ruoli dell'organizzazione.
OUF mantiene le abilitazioni nominali, le associazioni ruolo organizzativo →
capability e la designazione degli amministratori OUF. Non è necessario creare
un ruolo `admin OUF` nel Keycloak dell'Ente o del fornitore. OUF non crea una
seconda anagrafica di credenziali.

Il primo amministratore è una scelta esplicita dell'installazione durante il
bootstrap, non il primo utente che accede. La scelta è vincolata alla terna
issuer IAM, subject canonico e tenant. Non usare email, username o nome visualizzato
come identificatore del grant. Se l'adapter IAM usa `ouf_subject`, utilizzare
quel subject canonico anziché presumere che coincida sempre con `sub`.

Questa decisione precisa, su indicazione del committente, il modello di bootstrap
PET Authorization §20.2, che descriveva inizialmente ruoli amministrativi EVO:
il ruolo amministrativo OUF è locale alla policy applicativa; i ruoli EVO restano
utilizzabili per le altre associazioni organizzative. Restano invariati default
deny, DENY prevalente, verifica IAM, audit e confine trusted-human.

## Configurazione iniziale

Prima del primo avvio amministrativo, configurare nel servizio Onboarding:

```properties
ouf.authorization.bootstrap.admin-issuer=https://iam.example/realms/ente
ouf.authorization.bootstrap.admin-subject=<subject-canonico-della-persona>
ouf.authorization.bootstrap.admin-tenant=<tenant-installazione>
```

Le equivalenti variabili Spring Boot sono:

```text
OUF_AUTHORIZATION_BOOTSTRAP_ADMINISSUER
OUF_AUTHORIZATION_BOOTSTRAP_ADMINSUBJECT
OUF_AUTHORIZATION_BOOTSTRAP_ADMINTENANT
```

Si tratta di riferimenti di identità, non di password o token. Devono comunque
essere gestiti come configurazione privilegiata dell'installazione. Nessun valore
predefinito viene ricavato dall'utente collegato. Una configurazione incompleta
nega il bootstrap (`AUTH_BOOTSTRAP_ADMIN_NOT_CONFIGURED`).

L'operatore configurato si autentica attraverso l'IAM e presenta un token HUMAN
verificato con lo scope `authorization.bootstrap`. Possedere questo scope non
basta: un altro subject, issuer o tenant è respinto. Il controllo è eseguito sia
alla frontiera HTTP sia dal controller. La pubblicazione ricontrolla la
configurazione e il principal verificato, dentro la transazione serializzata.

## Prima policy

1. Registrare il descrittore della capability `authorization.policy.admin`:
   operazione `EXECUTE`, scope `authorization.policy.admin`, attore `HUMAN`.
2. Creare una bozza della prima policy contenente un grant nominale ALLOW per
   il subject e tenant configurati, con intervallo di validità già attivo.
   Non subordinare questa designazione a un ruolo organizzativo esterno.
3. Includere nella policy le altre abilitazioni iniziali previste
   dall'installazione. Lo scope OAuth non sostituisce questi grant.
4. Pubblicare la revisione esatta attraverso il canale trusted-human e
   `If-Match`. Il controllo verifica che la persona configurata possa esercitare
   la capability di amministrazione nel contesto amministrativo. Le condizioni
   di assurance e i DENY applicabili continuano a valere.
5. La pubblicazione e la chiusura del bootstrap avvengono nella stessa
   transazione; un errore lascia la bozza e il bootstrap aperti. Una policy senza
   il grant iniziale applicabile è respinta con
   `AUTH_BOOTSTRAP_ADMIN_GRANT_REQUIRED`.
6. Per le successive operazioni amministrative ottenere un token con lo scope
   `authorization.policy.admin`: il token bootstrap non viene trasformato in
   un token amministrativo dal controllo della policy.

Dopo la prima pubblicazione il latch persistente chiude il bootstrap.
Cambiare queste proprietà, riavviare il servizio o perdere il puntatore ACTIVE
non lo riapre. Le proprietà non costituiscono un account di emergenza e non
ripristinano grant revocati. Un'installazione già inizializzata continua a usare
la policy ACTIVE; non deve rieseguire il bootstrap per introdurre questa versione.

## Nomine successive e confini dell'incremento

Le successive abilitazioni restano modifiche OUF versionate e auditate tramite
bozza, grant nominale, revisione e pubblicazione trusted-human. Non richiedono
la scrittura di un ruolo amministrativo nel Keycloak esterno. L'accesso completo
alle capability applicative deve essere descritto da un profilo OUF esplicito;
`authorization.policy.admin` permette di amministrare la policy e non introduce
un bypass automatico delle decisioni dei moduli.

Questo incremento implementa la designazione del primo admin e la sua verifica
alla chiusura del bootstrap. L'interfaccia conversazionale per proposte di
nomina/revoca, il profilo completo di amministratore e la protezione dalla revoca
dell'ultimo amministratore nelle policy successive sono incrementi ancora da
completare. La conferma umana e la pubblicazione non diventano strumenti MCP.
Una scadenza dei grant o una disabilitazione nell'IAM richiedono comunque un
percorso operativo di recupero governato: non si cancella il latch.

## Verifica

- `BootstrapAdministratorTest`: identità esatta, configurazione incompleta,
  grant mancante/di altra persona/scaduto/futuro, ruoli organizzativi non
  equivalenti alla nomina, DENY nominale e per ruolo, scope bootstrap obbligatorio.
- `IamAuthorizationBootstrapIntegrationTest`: catena IAM e rifiuto di un'altra
  persona anche con scope bootstrap.
- `AuthorizationAdminRuntimeTest`: prima policy priva del grant respinta senza
  attivazione né chiusura, pubblicazione valida, latch non riapribile e
  pubblicazioni concorrenti.
- `AuthorizationPublishTransactionTest`: refresh solo dopo commit e rollback
  atomico del bootstrap.
