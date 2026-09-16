# Authorization scenario-neutral core traceability

Baseline: OUF Reality Baseline Package v1.7, Authorization / Access Control PET v1.5, Cross-Module Alignment Matrix v1.7.

## Scope implemented in this increment

- Authorization is implemented as a logical bounded context inside the Source Onboarding & Configuration deployable; no central per-request Authorization microservice is introduced.
- `PrincipalContext` is independent of IAM scenario A/B/C and carries subject, tenant, actor type, optional service principal, authentication-context reference, issuer, audience and scopes.
- `ResourceContext` carries tenant, organization and resource attributes without coupling to an IAM product.
- Capability descriptors define operation, required scope and allowed actor types.
- Grants are tenant-bound, time-bound and may be subject- or service-principal-bound and organization-scoped.
- Evaluation is default-deny. Cross-tenant requests, undeclared capabilities, disallowed actors, missing scopes and absent grants are denied with stable decision codes.
- Every decision is pinned to an immutable policy-bundle id/version and exposes a deterministic decision reference.
- Flyway V15 introduces an immutable `ouf_authorization.policy_bundle`, an active pointer and append-only decision-audit storage.

## Scenario A/B/C integration gate

The following remain external-integration decisions and MUST NOT be hard-coded by this bounded context:

- issuer and JWKS authority;
- token audience and realm/perimeter selection;
- claim-to-PrincipalContext mapping and optional directory lookup;
- service-account/client provisioning;
- workload credential/key rotation;
- revocation propagation and machine-identity lifecycle.

These items remain EVIDENCE PENDING until the A/B/C authority is selected and deployed.

## Security invariants

- default deny;
- tenant mismatch fails closed before grant evaluation;
- no IAM-product-specific code in the core evaluator;
- policy bundles are immutable after publication; activation is an independent pointer change;
- authorization decision evidence references the exact bundle version;
- no raw credential or token material is persisted by this schema.

## Next increment

Persist/publish policy bundles through a governed repository API and expose a shared SDK contract for consumer modules. Pairwise tests with MCP and Gateway must then validate the same capability/scope vocabulary without introducing a synchronous central-authorization critical path.
