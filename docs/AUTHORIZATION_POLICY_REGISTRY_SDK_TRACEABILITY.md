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
- non-root container build remains green.

Real IAM and deployed multi-service evidence remain EVIDENCE PENDING.
