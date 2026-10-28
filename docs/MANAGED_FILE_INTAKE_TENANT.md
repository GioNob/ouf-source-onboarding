# Durable tenant ownership at managed-file intake

Native CSV staging and native asset registration previously stored only `uploaded_by`. Delegated upload also retained a tenant-scoped upload receipt; a reviewed source could supply a later tenant/subject proof. An unprofiled native file had neither, so the same user could upload a file and then fail the durable ownership check used by recovery and ASSETS.

V37 adds nullable `managed_file_asset.tenant_id`. Native staging, native registration and delegated intake write the tenant from the authenticated principal in the asset transaction. Tenant and uploader cannot be changed by later updates. Repeating the same object registration reconciles its original identity; a different tenant, subject or unbound legacy identity produces a conflict and does not adopt the object.

The existing tenant-owner predicate accepts the new explicit binding. For legacy NULL rows it retains the existing durable upload/review proof paths, including the Teatri reviewed-source path. A conflicting receipt cannot override an explicit asset tenant. ASSETS and exact-job recovery reuse the same predicate. Recovery card reads do not grant budgets or queue jobs.

Existing rows are not backfilled, reassigned, reuploaded or retried. A legacy denial requires read-only reconciliation of the exact asset, authenticated identity and original receipts. Deployment of V37 alone is not a repair of historical missing provenance. This source change does not grant permissions, reset cumulative attempts, resume a job or activate a source.

Qualification uses real PostgreSQL17 for persistence through a new service instance, FAILED5/card/zero budget preservation, tenant/subject isolation, conflicting receipts, legacy denial, immutable owner binding, identical registration and a concurrent competing-tenant registration. Native boundary tests verify use of the trusted principal. Target deployment and application E2E remain separate gates.
