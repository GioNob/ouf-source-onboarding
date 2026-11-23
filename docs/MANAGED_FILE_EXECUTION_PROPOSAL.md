# Managed CSV execution proposal

`ManagedFileExecutionProposal.compile` completes an owner-retained managed CSV DRAFT for human review. The caller supplies exact published semantic bindings, one selected publication set, an explicit governed UDP resolution/materialization policy and finite parser limits. The owner derives source, immutable asset, profile, staging/hash/size, mapping and adapter references. Neither configuration state nor runtime authority changes in this pure compiler.

Preparation accepts `TEXT_NFC_TRIM_V1` on profiled `STRING` columns. Its materialization datatype is `xsd:string` and its governed identity comparator is `TEXT_V1`. The owner verifies the projected types and looks up the published pins before freezing this configuration. Execution and compatibility attestation remain separate lifecycle steps.

Draft responses list missing proposal sections in this order.

| Proposal section | Evidence in the retained configuration |
|---|---|
| `semanticBindings` | Pinned entries in `semanticReferenceBindings` |
| `semanticPublicationSetRef` | Selected set in `extractionProfile.runtime.execution` |
| `udp.resolution` | Resolution policy in `extractionProfile.runtime.udp` |
| `udp.materialization` | Materialization policy in the same UDP object |
| `limits` | Finite `maxRows`, `maxColumns` and `maxCellChars` in execution |

The retained version-1 exact-key CSV profiles used before governed proposals can prepare with their historical limits. The list reports missing inputs. Compilation still verifies the complete submitted proposal against the asset, profile, owner and mapping.

Every `create.semanticRefs` entry uses `semanticId@semanticVersion`. A UUID suffix returns HTTP 422 with `ONB_SEMANTIC_REF_FORM_INVALID` and the zero-based `index`. Supply the publication set UUID in the separate proposal fields. A binding set that differs from the retained references returns HTTP 422 with `ONB_SEMANTIC_REF_VERSION_MISMATCH` and `semanticRef`. It identifies the first missing retained coordinate, or the first retained coordinate if all are covered and the proposal contains surplus bindings. The draft can be prepared again with corrected bindings.

An imported ontology binding can contain standard external class/property IRIs and local source-preserving properties. This does not require one revision per term: the exact imported RDF snapshot must actually contain the required assertions. Preparation checks the retained semantic reference set against every supplied binding and requires the shared publication set; registry resolution, semantic content validation, frozen compatibility attestation, HUMAN/THS review and current UDP identity-coverage activation gates remain mandatory owner lifecycle checks.

For a discovery adoption, use the revision published after Semantic HUMAN approval. Pass its `semanticId@semanticVersion` to `source.onboarding.create`. In `source.onboarding.review.prepare`, supply all four fields of that publication in `executionProposal.semanticBindings` and its PublicationSet in `semanticPublicationSetRef`. The frozen configuration retains them in `semanticReferenceBindings`. To read the adopted RDF again after ACTIVE changes, use the retained `semanticId`, `revisionId` and `publicationSetId` with `semantic.get` under the current read grant.

Legacy owner proposals may still carry `reviewTerms`. The browser ignores that metadata, including in frozen snapshots and revision comparisons. The onboarding THS reads `published-terms` through the Gateway with the current human session, using the revision IDs from `semanticReferenceBindings`. A published class or property supplies its own Italian label and provenance when its IRI, type and publication binding match. An ontology-level label cannot identify an individual term inside an RDF import.

An incomplete or unavailable lookup leaves the card readable. Confirmation requires a fresh complete lookup for the frozen configuration and version. Rejection and change requests remain available. A verified term with a missing Italian description or license carries a warning. Reloading the card repeats the read under the current session.

Integration dependency (task-33a): the onboarding adapter expects a JSON object with a `terms` array of active revision records and a `missing` array of revision IDs. The task-33a handoff reports that the Gateway route excludes `published-terms`. The browser tests use fixtures. Verification against the registry requires the published endpoint and its Gateway route.

The UDP proposal must compare every mapped canonical property, retain source/class/tenant scope and exact publication references, and preserve each owner access label. The compiler supplies no uniqueness assertion, merge approval or authority ranking: these are explicit caller-proposed, human-reviewed fields. Sufficient rules and authority order are retained for that review. No data is read, no provider request made, no token issued and no transaction or retry state created.

The JSON contract is [managed-file-execution-proposal.schema.json](contracts/managed-file-execution-proposal.schema.json). Unknown fields in request DTOs must be rejected at the owner API as well as MCP schema validation. Compiler tests cover owner-input drift, missing/duplicate/wrong semantic pins, access escalation, omitted mapped properties, false typed materialization, publication-scoped identity and immutable preparation inputs. These tests are not evidence of a deployed end-to-end run.
