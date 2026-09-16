# R1a Authorization integration

Authority: Reality Baseline Package 1.7 / Matrix 1.7, Authorization PET 1.5
§§109.2–3, 109.5–8; relevant module PET retains its current version.

Shared artifact: `it.comune.trieste.ouf:authorization-sdk:1.0.0`.
Canonical source: GioNob/ouf-source-onboarding `authorization-sdk/`, commit
`63f238fa78abe2e6834430af618366ce865977fd`. Consumers mirror the same source
artifact with checksums and verify byte identity against that commit in CI.
Build with `bash scripts/install-authorization-sdk.sh` before Maven; Docker
performs the same bounded installation. Production dependencies exclude the
tests classifier. This avoids a new service or a private package-registry token.

The server authentication adapter must construct the common `TrustedPrincipal`
only after token signature/issuer/audience/claim validation. Old role-only
principals and capability/identity headers cannot establish business authority.
The domain adapter uses `ServletAuthorization` to evaluate a locally verified
bundle, derive allowed capabilities and pin policy version for the request.
Coarse Gateway allow cannot grant a capability denied by this bundle.
The SDK rejects missing/stale/tampered/reference-mismatched bundles; invalid
refresh cannot replace the immutable last-known-good snapshot. New requests
observe new versions, in-flight requests remain pinned within freshness limits.

Startup exact-reference configuration and explicit refresh SPI are documented
in the SDK README. Missing bundle denies protected adapter calls. No fallback
accepts the old trusted-capability attributes. Provision identity adapter and
bundle distribution before deploying protected operations. Automatic governed
ACTIVE polling/revocation distribution and IAM scenario A/B/C are not supplied
by the local-file loader; they remain integration/release gates.

This tranche covers shared types/evaluator, Java consumer adapters, canonical
human boundary and executable consumer tests. It does not certify all HTTP
routes, complete resource/DataAccessLabel/assurance policy, administrative CRUD,
production IAM/SSO, or representative full-path acceptance. R1b retains those
policy/control-plane residuals, and R2/R6 retain runtime/production integration.
Existing domain guards still own resource state and data-label enforcement.
No migration or historical audit row is rewritten.

Onboarding translates canonical HUMAN to its existing HUMAN_USER domain vocabulary at one explicit adapter boundary; historical rows and JSON bundle payloads are unchanged.
