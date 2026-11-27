# Managed CSV execution proposal

`ManagedFileExecutionProposal.compile` completes an owner-retained managed CSV DRAFT for human review. The caller supplies exact published semantic bindings, one selected publication set, an explicit governed UDP resolution/materialization policy. The caller may restrict the parser limits. The owner derives source, immutable asset, profile, staging/hash/size, mapping and adapter references. Neither configuration state nor runtime authority changes in this pure compiler.

Preparation accepts `TEXT_NFC_TRIM_V1` on profiled `STRING` columns. Its materialization datatype is `xsd:string` and its governed identity comparator is `TEXT_V1`. The owner verifies the projected types and looks up the published pins before freezing this configuration. Execution and compatibility attestation remain separate lifecycle steps.

### Acquisition limits

Onboarding reads the selected profile. Rows count data records and columns count the complete header, including excluded fields. For CSV, the optional integer `metadata.maxCellChars` measures the longest decoded data cell before trimming, in UTF-16 units. The profiler skips the UTF-8 BOM and uses RFC 4180 with the selected delimiter. Header-only files and files with only empty data cells have a measured maximum cell length of zero. Historical profiles without this key and XLSX profiles have no cell measurement.

For each dimension, the ceiling is `min(installationMaximum, max(1, observed + minHeadroom, ceil(observed × (100 + marginPercent) / 100)))`. An unmeasured cell uses the installation maximum. Both `executionProposal.limits` and its individual integer fields are optional. An omitted field takes the ceiling. A supplied field takes the smaller of the request and the ceiling. Only the effective numbers enter the execution configuration.

For example, `"limits": {"maxRows": 100}` requests a row limit and leaves columns and cell length at their derived ceilings. OpenAPI references the same closed `limits` object as the CSV JSON contract. It accepts only `maxRows`, `maxColumns` and `maxCellChars`.

When replacing a file for a `SHEET` or `LAYER`, the approved parent execution must already contain `maxRows` and `maxColumns`. A missing bound returns `ONB_REVISION_EXECUTION_LIMIT_EXCEEDED`. Historical CSV revisions can still derive missing bounds from the replacement profile.

HTTP 422 identifies the first failed dimension in row, column, cell order. A measured count above installation capacity returns `ONB_ACQUISITION_LIMIT_INSTALLATION_EXCEEDED` with `limit`, `observed` and `installationMaximum`. A requested limit below the measurement returns `ONB_ACQUISITION_LIMIT_BELOW_PROFILE` with `limit`, `observed` and `ceiling`. Invalid shapes or values outside the absolute integer ranges (1–1,000,000 rows or cell units, 1–10,000 columns) return `ONB_MANAGED_EXECUTION_PROPOSAL_INVALID`.

### Draft reader checks and recovery

`source.onboarding.create` accepts optional `semanticBindings` in execution proposal format. With bindings, the owner checks the projected input types and runs the same published-reference verifier used at preparation. A successful draft retains the coordinates. Preparation verifies the pins supplied for that review. An exact create replay uses the original command receipt.

Without bindings the draft can proceed. `deferredReaderChecks` lists the field name and transform of each Registry-dependent reader in field-name and transform order. The property is omitted when the list is empty.

A 422 `ONB_READER_UNSUPPORTED` from create carries a `fields` array. Each entry gives `fieldName`, the requested `transform` and `allowedTransforms`. For an unresolved published binding these alternatives work with the field profile and preserve the output datatype without a Registry-dependent reader. An empty list means the caller must revise the bindings or the mapping. For example, a profiled string using `TEXT_NFC_TRIM_V1` can use `IDENTITY` with the same string materialization and comparator. A rejected create leaves the asset and command key available.

A 422 `ONB_DATATYPE_UNRESOLVED` from managed review preparation carries the same entries, the first entry at the top level, and `resolutions`. For `REPLACE_DRAFT`, send create again with the same source ID, asset, profile, name and owner, corrected field decisions and a fresh create key. Then prepare the **returned version** at its returned lock version. The replacement transaction supersedes the previous draft and preserves its receipt. This route requires the latest owner-created draft of an unpublished source. The native `UPDATE_DRAFT` route uses the full version configuration and its current ETag with the HUMAN configuration-write capability. `CORRECT_EXECUTION_PROPOSAL` means to resubmit preparation with corrected publication coordinates. Revision preparation can return `REVISE_CHANGES` to amend the requested revision against the same parent.

A 409 `ONB_REVISION_ASSET_SOURCE_CONFLICT` discloses `boundSourceId` only after the latest-source read checks succeed. Its ordered `resolutions` list can be empty. `CREATE_REVISION` uses same-source create for a replaceable draft, or revision preparation for an eligible revision parent. `UPLOAD_NEW_FILE` starts a fresh upload under the caller's upload grant.

### Missing proposal sections

Draft responses list missing proposal sections in this order.

| Proposal section | Evidence in the retained configuration |
|---|---|
| `semanticBindings` | Pinned entries in `semanticReferenceBindings` |
| `semanticPublicationSetRef` | Selected set in `extractionProfile.runtime.execution` |
| `udp.resolution` | Resolution policy in `extractionProfile.runtime.udp` |
| `udp.materialization` | Materialization policy in the same UDP object |
| `limits` | Finite `maxRows`, `maxColumns` and `maxCellChars` in execution |

The retained version-1 exact-key CSV profiles used before governed proposals can prepare with their historical limits. The list reports missing execution sections. Onboarding supplies missing parser limits when compiling a proposal. Compilation still verifies the complete submitted proposal against the asset, profile, owner and mapping.

Every `create.semanticRefs` entry uses `semanticId@semanticVersion`. A UUID suffix returns HTTP 422 with `ONB_SEMANTIC_REF_FORM_INVALID` and the zero-based `index`. Supply the publication set UUID in the separate proposal fields. A binding set that differs from the retained references returns HTTP 422 with `ONB_SEMANTIC_REF_VERSION_MISMATCH` and `semanticRef`. It identifies the first missing retained coordinate, or the first retained coordinate if all are covered and the proposal contains surplus bindings. The draft can be prepared again with corrected bindings.

An imported ontology binding can contain standard external class/property IRIs and local source-preserving properties. This does not require one revision per term: the exact imported RDF snapshot must actually contain the required assertions. Preparation checks the retained semantic reference set against every supplied binding and requires the shared publication set; registry resolution, semantic content validation, frozen compatibility attestation, HUMAN/THS review and current UDP identity-coverage activation gates remain mandatory owner lifecycle checks.

For a discovery adoption, use the revision published after Semantic HUMAN approval. Pass its `semanticId@semanticVersion` to `source.onboarding.create`. In `source.onboarding.review.prepare`, supply all four fields of that publication in `executionProposal.semanticBindings` and its PublicationSet in `semanticPublicationSetRef`. The frozen configuration retains them in `semanticReferenceBindings`. To read the adopted RDF again after ACTIVE changes, use the retained `semanticId`, `revisionId` and `publicationSetId` with `semantic.get` under the current read grant.

Legacy owner proposals may still carry `reviewTerms`. The browser ignores that metadata, including in frozen snapshots and revision comparisons. The onboarding THS reads `published-terms` through the Gateway with the current human session, using the revision IDs from `semanticReferenceBindings`. A published class or property supplies its own Italian label and provenance when its IRI, type and publication binding match. An ontology-level label cannot identify an individual term inside an RDF import.

An incomplete or unavailable lookup leaves the card readable. Confirmation requires a fresh complete lookup for the frozen configuration and version. Rejection and change requests remain available. A verified term with a missing Italian description or license carries a warning. Reloading the card repeats the read under the current session.

Integration dependency (task-33a): the onboarding adapter expects a JSON object with a `terms` array of active revision records and a `missing` array of revision IDs. The task-33a handoff reports that the Gateway route excludes `published-terms`. The browser tests use fixtures. Verification against the registry requires the published endpoint and its Gateway route.

The UDP proposal must compare every mapped canonical property, retain source/class/tenant scope and exact publication references, and preserve each owner access label. The compiler supplies no uniqueness assertion, merge approval or authority ranking: these are explicit caller-proposed, human-reviewed fields. Sufficient rules and authority order are retained for that review. No data is read, no provider request made, no token issued and no transaction or retry state created.

### Governed identity diagnostics

An identity rejection from `POST /api/internal/v1/onboarding/managed-file-mcp/review-prepare` returns HTTP 422 with `code` and fixed `detail` both equal to `ONB_GOVERNED_IDENTITY_INVALID`. The problem reports the first violated `constraint`. Its `path` is a JSON Pointer relative to the arguments, rooted at `/executionProposal/udp/resolution`. Object shape errors point to the object, field errors to the field, and array entries use zero-based indices. Correct the proposal and submit it again, with the same `commandKey` or with the new one supplied by the MCP `next`. The rejected command records nothing and leaves the draft and its lock version available for that correction.

| Constraint | What to correct |
|---|---|
| `RESOLUTION_SHAPE` | Supply the resolution fields, the governed identity strategy and a nonblank strategy version. |
| `POLICY_FIELDS` | Supply the required policy fields and remove unknown fields. |
| `POLICY_REFERENCE` | Match the policy's nonblank `ref` and `version` to `policyRef` and `strategyVersion`. |
| `POLICY_SOURCE`, `POLICY_TENANT`, `POLICY_CLASS` | Use the draft's source, the caller's tenant and a mapped target class. |
| `POLICY_BOUNDS` | Use a boolean `allowAutoNew`, an integer `maxCandidates` from 1 through 1000, 1–1024 signals, 1–32 sufficient rules and up to 32 optional distinct rules. |
| `SIGNAL_SHAPE`, `SIGNAL_ASSERTION_REF` | Supply the signal fields, a nonblank ID and a nonblank assertion reference. |
| `SIGNAL_NOT_MAPPED`, `SIGNAL_DUPLICATE`, `SIGNAL_FLAGS` | Use one signal per mapped property and set both flags to `false`. |
| `SIGNAL_COMPARATOR`, `SIGNAL_SEMANTIC_REF` | Use the declared datatype's comparator and the property ID followed by `@` and the selected publication set. |
| `SIGNALS_INCOMPLETE` | Add a signal for each missing property, even when a sufficient rule uses only a subset. |
| `RULE_SHAPE`, `RULE_DUPLICATE` | Supply the rule fields and a nonblank assertion reference. Give each rule a nonblank ID unique across both rule lists. |
| `RULE_MEMBERS`, `DISTINCT_RULE_MEMBERS` | Use 1–32 distinct declared signal IDs in a sufficient rule, or 2–32 in a distinct rule. |

`SIGNAL_NOT_MAPPED`, `SIGNAL_DUPLICATE`, `SIGNAL_FLAGS`, `SIGNAL_COMPARATOR`, `SIGNAL_SEMANTIC_REF` and `SIGNALS_INCOMPLETE` include `propertyCount` (the full distinct count) and `propertyIris` (up to 64 distinct IRIs in lexical order). Comparator, semantic reference and incomplete coverage errors also include `expectedSignals` in the same order, with `id`, `semanticRef` and `comparator` derived from the mapping and publication. For incomplete coverage these are the missing signals to add. Keep the assertion reference and flags explicit in each completed signal.

`RULE_DUPLICATE`, `RULE_MEMBERS` and `DISTINCT_RULE_MEMBERS` include `ruleIds` (up to 32). The pointer identifies the offending entry even when its identifier is malformed. Diagnostic lists carry identifiers only. Raw cell values, unknown field names, rule members and submitted assertion text are never copied into the problem.

The JSON contract is [managed-file-execution-proposal.schema.json](contracts/managed-file-execution-proposal.schema.json). Unknown fields in request DTOs must be rejected at the owner API as well as MCP schema validation. Compiler tests cover owner-input drift, missing/duplicate/wrong semantic pins, access escalation, omitted mapped properties, false typed materialization, publication-scoped identity and immutable preparation inputs. These tests are not evidence of a deployed end-to-end run.
