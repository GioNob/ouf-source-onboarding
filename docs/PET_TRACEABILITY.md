# PET v1.5 implementation traceability

Status is evidence-based: **IMPLEMENTED** requires executable code plus tests or a CI-verifiable contract; **DELEGATED** identifies the external owner and the local integration boundary; **ENVIRONMENT BINDING** means the adapter/contract exists but production coordinates and credentials cannot be committed here.

| PET area | Status | Implementation evidence | Verification |
|---|---|---|---|
| Source registry, list/get/update and optimistic locking | IMPLEMENTED | `OnboardingService`, `OnboardingApi` | `OnboardingWorkflowRuntimeTest` |
| Immutable version lifecycle, clone, diff, validation and activation | IMPLEMENTED | `OnboardingService`, V1/V2 | workflow/runtime tests |
| Human challenge bound to frozen hash | IMPLEMENTED | `TrustedHumanApi`, approval tables | workflow and THS contract tests |
| THS normative prefix and MCP separation | IMPLEMENTED | `openapi/ths-v1.yaml`, `/api/trusted-human/v1` | `TrustedHumanSurfaceContractTest` |
| Protected log search/read/filter | IMPLEMENTED | `ProtectedLogService`, typed `ProtectedLogStoreAdapter` | `ProtectedLogRuntimeTest` |
| Protected log aggregate/correlate | IMPLEMENTED | deterministic whitelists in `ProtectedLogService` | runtime tests and OpenAPI |
| Server-side minimization and secret removal | IMPLEMENTED | `ProtectedLogRedactionService` | nested redaction test |
| Protected access audit including denied access | IMPLEMENTED | V11 append-only access audit | runtime tests |
| Asynchronous export, lease recovery, retry, checksum, expiry and governed download | IMPLEMENTED | V11, `ProtectedLogExportWorker` | runtime lifecycle test |
| Shared platform log store | ENVIRONMENT BINDING | typed `ProtectedLogStoreAdapter`; built-in onboarding-audit adapter | production adapter conformance required |
| Production browser THS shell | DELEGATED | backend/OpenAPI is complete | front-end deployment acceptance |
| IAM, tenant/resource authorization and policy decisions | DELEGATED — Authorization | trusted principal, capabilities and authorization-context consumer | integration/E2E suite |
| Gateway token validation, rate limiting and governed routing | DELEGATED — Urban API Gateway | opaque route/object references | integration/E2E suite |
| Managed CSV/XLSX staging, profiling and one-row/one-object configuration | IMPLEMENTED | V5/V7–V9, managed-file services | managed-file tests |
| Managed-file intake quarantine and remediation | IMPLEMENTED | V13, `ManagedFileService`, human-only quarantine API | runtime and THS contract tests |
| Finite HUMAN profiling recovery, same job and persistent generation fencing | CANDIDATE — qualification pending | V36, `ManagedProfileJobs`, native owner API and session/CSRF THS adapter | `ManagedProfileBudgetRuntimeTest`, `ProfileBudgetMigrationUpgradeTest`, `ManagedProfileRecoveryApiTest`, `ManagedProfileRecoveryThsTest`, browser profile-recovery suite; deployment, native network and final cohort remain unproven |\n| One-time file ingestion and recurring PULL execution | DELEGATED — Ingestion Runtime | immutable ACTIVE bundle and compatibility attestation | cross-module E2E |
| Runtime projections for Gateway | IMPLEMENTED | V10, `RuntimeProjectionService` | workflow projection test |
| Schema surveillance and activation gate | IMPLEMENTED | V10, `SchemaSurveillanceService` | blocking/resolution test |
| Semantic gap and candidate selection | IMPLEMENTED | V12, `SemanticGapService` | `SemanticGapRuntimeTest` |
| Semantic adoption/publication authority | DELEGATED — Semantic Model/Registry | service capability-gated candidate callback | cross-module E2E |
| Schema-only object-type inspection and field extraction/access classification | IMPLEMENTED | V14, `DiscoveryService`, `MappingService`, managed-file field decisions | discovery, mapping and managed-file runtime tests |
| Ontology and controlled-vocabulary assignment | IMPLEMENTED | pinned type semantic refs plus property-level vocabulary id/version/value-map references | mapping and managed-file runtime tests; Registry lookup remains cross-module |
| Relationship resolution policy including `QUARANTINE_RELATION` | IMPLEMENTED (CONTROL PLANE) | V4 draft persistence, mapping list API and publication in immutable bundle | `MappingRuntimeTest` |
| Relationship-instance quarantine for `NO_MATCH`/`MULTIPLE_MATCHES` | DELEGATED — Object Resolution/Ingestion Runtime | Onboarding publishes `onNoMatch`/`onMultipleMatches`; it never sees or resolves runtime instances | cross-module E2E required |
| rc3 frozen contract compatibility | IMPLEMENTED | pinned contract copies and SHA manifest | `verify-contract-freeze.sh` in CI |
| Metrics and health probes | IMPLEMENTED | Actuator/Prometheus and `OnboardingMetrics` | application context and CI package |
| Production backup/restore, SLO alerting and NetworkPolicy | DELEGATED — platform operations | module exposes health/metrics | deployment acceptance/runbook exercise |

## D-089 · Managed file picker

The picker tests in `ManagedFileChatHandoffTest` use the module CI PostgreSQL database and a controlled clock for issuance, decisions and reclamation. D089-09 uses the signed-delegation fixture in `ManagedFileDelegationVerifierTest`. Runtime qualification requires a successful CI run.

| PET ID | Observable behavior |
|---|---|
| D089-01 | Two concurrent issuers receive the same UUIDv4, URL, times and version. Scope conflicts fail, and replay after a decision returns the original issuance. |
| D089-02 | A staged asset or a rejection becomes visible on the next read. Identical decisions keep version 2. Different assets, codes and outcome kinds conflict. |
| D089-03 | Second 3599 remains pending. At 3600 both a reader and a late writer observe durable expiry, with one version increment. |
| D089-04 | Reading, checking an upload, completing or rejecting another subject's or tenant's ID returns 409 `ONB_HANDOFF_UNKNOWN`. An owned receipt also requires ownership of the asset being staged. Denied writes preserve the pending version. |
| D089-05 | A pre-deadline writer and a deadline reader on separate PostgreSQL connections agree on the sole committed terminal outcome. |
| D089-06 | Recreating the picker context preserves the original issue acknowledgement, a pending deadline and the code and version of an issued terminal receipt. The predecessor-return workflow also restarts the entire candidate image during a wait. |
| D089-07 | A rejection is readable through the last second of retention. Retirement clears the code and preserves the command acknowledgement. Reads, upload checks and both decisions then return 409 `ONB_HANDOFF_UNKNOWN`. |
| D089-08 | Invalid duration settings prevent service construction. An installation-specific shorter deadline survives a change in configuration. |
| D089-09 | `ManagedFileDelegationVerifierTest` exercises the HTTP issue route with a valid signed receipt and checks missing or wrong-path delegation and invalid command UUIDs. |
| D089-10 | MockMvc compares the serialized HTTP 409 problem body byte for byte for missing, foreign-tenant, foreign-subject, retired and residual legacy IDs, using a different correlation header on each read. All return `ONB_HANDOFF_UNKNOWN` and the MCP owner-error observation. |
| D089-11 | Pre-stream validation, completion with an owned asset and rejection of an unissued ID return 409 `ONB_HANDOFF_UNKNOWN`. The handoff table stays empty. |
| D089-12 | A staged V46 legacy row inserted as predecessor data is unknown on read, upload validation, replay of the same asset and rejection. Its asset and version survive through the last second of retention. Reclamation deletes it at the boundary. |

The D-080 workflow includes `82aabaa`. It compares the full candidate handoff row and Flyway history through V46 after the predecessor has resumed reads and writes. MCP tranche renewal, admission, and the end-to-end latency measurement belong to the MCP and cross-module PET.

## Release boundary

The repository can verify module-local behavior and contracts. It cannot truthfully mark environment-bound integrations complete without Authorization, Gateway, Semantic Registry, Ingestion Runtime, shared log/object storage and a deployed browser client. Those items are not silently omitted: they are explicit release gates owned by the named systems.

