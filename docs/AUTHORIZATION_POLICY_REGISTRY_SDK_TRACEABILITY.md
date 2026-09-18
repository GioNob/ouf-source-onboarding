# Authorization Policy Registry and SDK traceability

Baseline: OUF Reality Baseline Package v1.7, Authorization / Access Control PET v1.5, Cross-Module Alignment Matrix v1.7.

## Increment scope

- Authorization remains a logical bounded context inside Source Onboarding & Configuration; no synchronous central Authorization service is introduced on the request critical path.
- `AuthorizationPolicyRegistry` persists immutable policy bundles, supports idempotent publication, atomically moves the active pointer, and exposes a transactional publish-and-activate operation.
- Reusing an existing `(bundle_id, version)` with different content fails closed.
- `authorizeActive` resolves one exact active bundle, evaluates the scenario-neutral policy, and persists decision evidence pinned to that exact bundle/version in the same application transaction.
- Authorization decisions expose a stable `decisionRef`; no credential/token material is persisted.
- `contracts/authorization/authorization-sdk-v1.json` is the shared consumer contract for local/cached authorization evaluation. It carries PrincipalContext, ResourceContext, capability and operation and is deliberately IAM-product-neutral.

## Runtime invariants

1. Published bundles are immutable.
2. Activation changes only the singleton active pointer; historical bundles remain readable.
3. Publication is idempotent only for byte/semantic-equivalent content under the same id/version.
4. Evaluation is default-deny and tenant-bound.
5. Every persisted decision references the exact bundle id/version used for evaluation.
6. No consumer is required to make a synchronous network call to Source Onboarding for every authorization decision.

## Canonical platform Authorization capabilities

`AuthorizationCapabilities` is the executable catalogue for platform-owned capabilities that must be stable during IAM/bootstrap integration. The capability registration owner is `authorization`.

- `authorization.policy.admin`: operation `EXECUTE`, required scope `authorization.policy.admin`, allowed actor `HUMAN`.
- `authorization.bundle.read`: operation `READ`, required scope `authorization.bundle.read`, allowed actor `SERVICE`.

The first real policy bundle must preserve an applicable HUMAN grant for `authorization.policy.admin` before the bootstrap latch is closed. Workloads that retrieve the active bundle through `GET /api/internal/v1/authorization/policy-bundle/active` require an applicable SERVICE grant for `authorization.bundle.read`. Capability descriptors must be registered through the trusted-human administration API before a bundle containing them can be published; direct database insertion is not part of the supported bootstrap path.

## A/B/C external integration gate

Still EVIDENCE PENDING until the external IAM path is selected/deployed:

- issuer/JWKS authority;
- audience and realm/perimeter;
- claim mapping/directory lookup;
- machine identity provisioning;
- workload credential rotation;
- revocation propagation.

These details adapt into `PrincipalContext`; they do not alter the evaluator or bundle model.

## Pairwise consequence

The existing MCP `AuthorizationClient` is an earlier integration stub that performs a synchronous HTTP call. It MUST NOT become the final Authorization architecture. The next pairwise increment must replace that critical-path assumption with local/cached bundle evaluation (or an equivalent shared SDK adapter) while preserving `AuthorizationDecisionRef` propagation into admission, Gateway calls and audit evidence.

Gateway remains responsible for coarse-grained edge enforcement; owner services/MCP use the shared Authorization semantics for fine-grained enforcement.

## Evidence

Repository CI must demonstrate:

- Flyway V15 migration succeeds on PostgreSQL 17;
- publish/activate/evaluate/audit path passes integration tests;
- published bundle mutation is rejected;
- same-version different-content publication fails closed;
- canonical platform Authorization capability descriptors remain pinned by tests;
- non-root container build remains green.

Real IAM and deployed multi-service evidence remain EVIDENCE PENDING.

## Production IAM acceptance history

The real Keycloak bootstrap, first ACTIVE PolicyBundle, production IAM activation, restart acceptance, intermediate failures and rollback state are recorded in [docs/history/IAM_BOOTSTRAP_PRODUCTION_ACCEPTANCE_2026-09-18.md](history/IAM_BOOTSTRAP_PRODUCTION_ACCEPTANCE_2026-09-18.md).
