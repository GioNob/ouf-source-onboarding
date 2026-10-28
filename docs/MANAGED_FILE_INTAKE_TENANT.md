# Durable tenant ownership at managed-file intake

Native CSV staging and native asset registration previously stored only `uploaded_by`. Delegated upload also retained a tenant-scoped upload receipt; a reviewed source could supply a later tenant/subject proof. An unprofiled native file had neither, so the same user could upload a file and then fail the durable ownership check used by recovery and ASSETS.

V37 adds nullable `managed_file_asset.tenant_id`. Native staging, native registration and delegated intake write the tenant from the authenticated principal in the asset transaction. Tenant and uploader cannot be changed by later updates. Repeating the same object registration reconciles its original identity; a different tenant, subject or unbound legacy identity produces a conflict and does not adopt the object.

The existing tenant-owner predicate accepts the new explicit binding. For legacy NULL rows it retains the existing durable upload/review proof paths, including the Teatri reviewed-source path. A conflicting receipt cannot override an explicit asset tenant. ASSETS and exact-job recovery reuse the same predicate. Recovery card reads do not grant budgets or queue jobs.

Existing rows are not backfilled, reassigned, reuploaded or retried. A legacy denial requires read-only reconciliation of the exact asset, authenticated identity and original receipts. Deployment of V37 alone is not a repair of historical missing provenance. This source change does not grant permissions, reset cumulative attempts, resume a job or activate a source.

Qualification uses real PostgreSQL17 for persistence through a new service instance, FAILED5/card/zero budget preservation, tenant/subject isolation, conflicting receipts, legacy denial, immutable owner binding, identical registration and a concurrent competing-tenant registration. Native boundary tests verify use of the trusted principal. Target deployment and application E2E remain separate gates.

## Explicit legacy reconciliation before any budget

The retained Farmacie read proved the original uploader matches the expected subject but no upload or source-review tenant receipt exists. Teatri has its existing source-review proof. A generic migration must not invent a Farmacie receipt or copy the Teatri source review.

V38 adds an empty append-only `managed_file_owner_reconciliation` table. The native owner API `/recovery/ownership` uses the existing exact-job recovery resource and requires both current HUMAN profile and quarantine recovery capabilities. It is available only to the original uploader for a tenantless retained failed CSV with one job, no result, no prior budget, no source and no upload provenance. The current authenticated tenant is displayed for explicit confirmation; a client cannot choose a subject or tenant in the decision body. This is a separate owner decision, not a grant or a general ownership transfer.

The server binds the review to principal, immutable input, current job version/generation/attempts and failure. Confirmation uses a12second transaction,2second lock limit,5second statement limit, command locking, job/input row locking and one immutable receipt per asset. Same-command reconciliation after a lost response returns the original decision reference; changed intent or a competing decision is denied. Job, original asset, quarantine, source/upload receipts and all budget/event rows remain unchanged. Reconciled legacy proof is authoritative over contradictory later legacy receipts; native explicit tenant bindings remain authoritative.

The USER-only THS exposes three separate decisions where legacy repair is needed: confirm tenant, grant3attempts/900seconds, then resume. No confirmation triggers the following mutation. It automatically follows an explicit ownership-denied GET with one distinct ownership-review GET, never an automatic POST. Pending commands persist before dispatch, survive reload and reconcile the same body/key. If browser persistence is unavailable, mutation is blocked before dispatch. The existing Gateway HUMAN GET/POST managed-file wildcard and three requested OAuth scopes already cover this path; no new route, scope or temporary permission is introduced.

V37 and V38 do not backfill or bind retained production data during installation. After a reviewed installation, the actual HUMAN session and current policy must authorize the explicit legacy decision. Application E2E and the61Farmacie rows through UDP remain open until verified separately.
