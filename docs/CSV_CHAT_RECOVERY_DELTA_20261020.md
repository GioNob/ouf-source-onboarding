# CSV chat recovery — WP6 owner delta

Existing preview and source-read capabilities/paths are extended with explicit
ASSETS and LATEST read views. No new receipt authority, persistence table, source
approval, login, activation or runtime claim is introduced.

ASSETS returns at most50owned asset references, optional latest profile and a
tenant-proven source reference. SQL filters by authenticated subject and durable
tenant ownership from upload/review records before pagination. UUID ordering and
owned cursors are deterministic. Storage refs, hashes, other owners and unproven
legacy assets are omitted; historicalCoverage discloses the proof boundary.

New delegated create commands bind their fingerprint and source metadata to the
authenticated tenant, not caller arguments. Legacy direct service overloads and
historical source metadata remain unchanged. A changed tenant cannot reuse an
existing command result. No backfill guesses an Ente/tenant for old records.

LATEST selects the latest source version from owner state, checks current READ
authorization, tenant proof and file ownership, and returns the existing safe
receipt. It does not prepare a challenge or approve/activate a version. Unproven
legacy scope requires its existing exact reference and governed reconciliation;
403 is not an empty-history result.

Normative basis: Mini-PET1.1 WP6/R19/R20; Matrix1.7§1; Onboarding PET1.6 ownership
and managed-file boundary; MCP PET1.4§33.4–33.5 catch-up and owner redaction.
CI qualification and MCP candidate wiring remain required. No target deployment
or broad security/PET acceptance is implied by this source patch.
