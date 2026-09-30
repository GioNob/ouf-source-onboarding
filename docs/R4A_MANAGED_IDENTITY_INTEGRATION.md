# R4a — coesistenza intake managed e gate d'identità

L'inventario VPS del 30 settembre conferma la route GET
`/internal/object-storage/v1/content`, rewrite
`/api/internal/v1/onboarding/managed-files/content`, upstream
`ouf-onboarding:8080`, OIDC e scope `ouf.internal.object-storage.read`.
La revisione live f74c3a9f377b5ce93b5cab298fa24372de1602bc manca del controller
ManagedFileStagingApi: il probe consumer è bloccato da ING_EXECUTION_GATEWAY_404
dopo il preflight Semantic. Nessuna attestazione positiva o attivazione.

La candidata integra i due rami divergenti: intake/picker/delegation da
5d770df635f8328a1e901b2d47bf8cd5bacab6c5 e gate d'identità/PermissionProposal
da f74c3a9f377b5ce93b5cab298fa24372de1602bc. Conserva le due famiglie di
validazione e tutte le superfici HUMAN authorization, managed-files e resolution
nella stessa sessione OIDC/PKCE/CSRF. I due parent sono nel merge commit.

Il vecchio test che attivava un DRAFT managed incompleto è sostituito dalla
regressione fail-closed dell'intake; le prove identity complete e del gate UDP
restano. Aggiunta regressione sulle due validazioni nello stesso DRAFT.
La CI avvia il deployable con staging fittizio e verifica che il GET owner
anonimo restituisca 403 ONB_AUTHORIZATION_DENIED, senza raggiungere MinIO.
Readiness da sola non dimostra l'esposizione del controller.

La capability `ouf.object-storage.content.read` richiede lo scope
`ouf.internal.object-storage.read`: nomi diversi sono intenzionali, come
dichiarato dal descriptor in catalogue/r4a-managed-file-intake.json.
Nessun cambiamento di capability, scope, grant o route è necessario per
questo binding. La decisione effettiva del backend resta da osservare.

Prima del rollout: CI verde sul commit esatto, inventario env/mount live di
staging e delle due identità, backup recuperabile e confronto di ogni
migrazione candidata con Flyway live. Il DB è già a V31, ma i file V30/V31
mancano nel tree f74c3a9…: non dedurre assenza di migrazioni dal solo
confronto git con quella baseline. Questa candidata conserva i file originali
dell'intake; un drift dei checksum o una migrazione pendente deve bloccare.
Preservare env e mount live e il gate UDP durante lo switch. Conservare il
runtime attuale per rollback, senza restore automatico del DB e senza
cancellare oggetti/asset congelati.

Non utilizzare il precedente rollout picker direttamente: il suo controllo
git migrations blocca questo caso e il suo overlay riguarda un'altra fase.
La preparazione specifica della release deve distinguere pianificazione,
build, prova isolata e switch. Nessuno di questi passi è stato eseguito sul
VPS da questa modifica. R-SMOKE/R-INSTALL restano OPEN.
