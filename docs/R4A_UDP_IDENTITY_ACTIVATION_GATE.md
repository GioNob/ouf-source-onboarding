# R4a: UDP identity compatibility gate

Onboarding may validate, review and approve a `resolution.weighted` proposal.
The current UDP published runtime rejects that profile with
`UDP_WEIGHTED_RUNTIME_UNAVAILABLE`. A future `resolution.governedIdentity`
profile is likewise unavailable in UDP. Accordingly, activation fails with
`ONB_UDP_IDENTITY_RUNTIME_UNAVAILABLE` before publishing a bundle or changing
the active version, even when a human has approved and Ingestion has attested
compatibility. This gate does not change the proposal or its approval history.
For a configured resolution, activation currently accepts only the complete
six-field legacy profile. Unrecognized fields in the resolution configuration
and incomplete profiles also fail closed rather than relying on a runtime fallback.

This gate concerns the published configuration consumed by UDP. Onboarding
does not send the uploaded file to UDP: Ingestion reads the staged source,
produces per-record handoffs with a mapped `canonicalPayload` and provenance,
and UDP resolves or creates the canonical Urban Object identity.

Onboarding now checks the structure of a `governedIdentity` proposal while it
is a DRAFT. It requires exact policy ref/version, tenant/class/source scope,
bounded candidate count, explicit source-scoped creation choice, signal
property IRIs tied to semantic and materialization mappings, pinned semantic
reference syntax and typed comparators,
assertion references for uniqueness or exclusion, and sufficient rules with
an asserted unique signal. The proposal remains inactive even if structurally
valid. The assertion reference is not a proof that the asserted uniqueness is
true: a future publication check must resolve its approval, scope,
cardinality and validity. Temporal, relation and spatial identity comparators
are not accepted by this initial scalar proposal check.

The gate is temporary until UDP implements the general governed identity
policy and Onboarding can verify the exact published contract and UDP runtime
compatibility for the frozen configuration. An Ingestion compatibility
attestation alone does not establish UDP compatibility. The cinema DRAFT must
remain inactive; no R-SMOKE or R-INSTALL result follows from this change.

Next: define the immutable policy with exact semantic publication bindings,
typed comparators, justified uniqueness/sufficiency and exclusion assertions,
scope and cardinality, temporal/spatial applicability, bounded indexed candidate
coverage, and durable HUMAN review. The weighted score may rank or explain
candidates but cannot authorize MATCH on its own. The future activation check
must pin a compatible UDP implementation to the approved configuration hash.
