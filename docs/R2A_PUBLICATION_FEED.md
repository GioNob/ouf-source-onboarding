# R2a — protected publication feed

PET authority: Onboarding 1.6 §37/§109.17, Ingestion 1.3 §5; Reality Baseline v1.7.

`GET /api/onboarding/v1/runtime/publications?after=&limit=20` returns a bounded,
source-key ordered page of current immutable published bundles. `nextAfter=""`
ends a sweep; Ingestion restarts a fresh sweep to discover changes behind the old
cursor. A source disabled after publication remains visible with sourceStatus
DISABLED so the consumer can stop new admission. Draft/review/approved-only
versions are not emitted. An ACTIVE lookup is available at
`GET /api/onboarding/v1/runtime/publications/{sourceId}/active`.

Both routes require a trusted SERVICE principal and
`ouf.onboarding.configuration.read` evaluated against `published-configuration`,
module=ONBOARDING, sourceRef/source resource ID for individual publications.
The collection additionally requires a catalog-level grant. Configure
`ouf.runtime-publications.tenant-id` to the platform catalog owner namespace and
provision its workload grants through Authorization. Missing configuration denies.
This is a platform control-plane catalog, not a per-data-tenant SQL filter.
No request header or user-supplied capability set grants access.

Envelope: publicationId, publicationSequence (owner version sequence), sourceId,
onboardingVersionId, checksum, sourceStatus, tenantId and bundle. The bundle's own
bundleVersion may be semantic text; it is not substituted for publicationSequence.
Exact semanticReferenceBindings supplied in approved configuration are included
in the compiler's checksum-covered output. A scheduled syncProfile must include
explicit bounded operationalPolicy and additive retryBackoffSeconds. Frozen
schema files remain unchanged; their existing syncProfile extension mechanism
permits the additive backoff field.

`RuntimePublicationBoundaryTest` checks anonymous/coarse-forged requests, HUMAN,
and wrong/missing namespace denials before DB access and missing operational policy.
`ActivationPublisherFixture` is test-classpath-only: it profiles a real CSV and
publishes file/PULL bundles through the governed service lifecycle. The pinned
consumer workflow exercises the feed over HTTP with production Ingestion scheduling.
Real IAM, Gateway and Semantic acceptance remains separate from this laboratory
fixture. Onboarding never takes back scheduler or ingestion execution ownership.
