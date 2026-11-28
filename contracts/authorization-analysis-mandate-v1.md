# Authorization private API for D-104 analysis mandates

The Gateway issues a mandate on HUMAN consent for the first round of one `BATCH_V1` analysis. Authorization owns the PostgreSQL registry and the final admission decision. The Gateway owns the MCP-facing `prepare` and work proof, including the phase, fence, arguments, discovery work reference and request UUID. It obtains an admission immediately before each Semantic call, including reads and retries, and creates the existing Semantic owner receipt. A call admitted before a revocation commits can finish and be reconciled.

## Transport and authentication

Send JSON POST to `/api/internal/v1/authorization/analysis-mandates/{action}`. The API is opt-in through `ouf.authorization.analysis-mandate.enabled`. Use mutually authenticated TLS to the Authorization servlet with the installation's client CA and a configured Gateway leaf certificate SHA-256 pin. Authorization takes the peer certificate from the TLS container. All five commands require this Gateway workload. MCP has no access. The configured installation, IAM issuer, MCP workload and THS client are mandatory. The Gateway passes the authenticated MCP workload identity even though it is the TLS caller. Forward the HUMAN bearer in `Authorization: Bearer …` only on `revoke`. The Gateway must enforce the same installation binding at its ingress.

Fields are case-sensitive. Use lowercase canonical UUIDs, SHA-256 values as 64 lowercase hex digits, UTC epoch milliseconds, and integer versions and generations. Identity fields are bounded to 512 printable ASCII characters without spaces, scopes to 4096 characters when space-separated, and the request to 16 KiB. Unknown fields are rejected. The installation is inferred from the Gateway TLS binding. All successful responses use HTTP 200 and the same fields as the Gateway view, namely `mandateRef`, `generation`, `version`, `state` (`ACTIVE`, `REVOKED`, `EXPIRED`), `analysisId` and `expiresAtMs`. The first generation and each new row start at version 1. A terminal transition increments that row's version once. Responses are private and use `Cache-Control: no-store`.

## Commands

| Command | JSON fields | Concurrency and replay |
| --- | --- | --- |
| `issue` | `analysisId`, `assetId`, `profileId`, `schemaHash`, `issuer`, `tenant`, `subject` (HUMAN `sub`), `client`, `workload`, `startRef`, `commandKey`, `issueKey`, `policyEpoch`, `scope` (array), `generation`, `expectedVersion`, optional `previousMandateRef`, optional `proof` | First issue uses generation 1, version 0, no previous reference, and `issueKey == commandKey`. Use UUIDv4 for `startRef`, `commandKey` and `issueKey`. Renewal uses a new issue key, the preceding reference and its current terminal version, and exactly the next generation. |
| `admit` | `mandateRef`, `generation`, `expectedVersion`, `analysisId`, `workload`, `policyEpoch`, `operation`, `argumentsHash`, `attemptId`, `correlationId`, `requestId` for discovery status/candidates only | Compare all bindings and the current row version. Use UUIDv4 for `mandateRef`, `attemptId` and `correlationId`. Every call evaluates afresh and appends a decision. Repeating an attempt ID never retrieves a cached allow. Only a committed 200 authorizes the corresponding dispatch. |
| `revoke` | `mandateRef`, `generation`, `expectedVersion`, `correlationId` | The active HUMAN owner or an active HUMAN operator with current THS mandate-revocation authority in the bound tenant may revoke through the Gateway. A stale version or terminal row returns 409. After an ambiguous response, reconcile with `read`. |
| `expire` | `mandateRef`, `generation`, `expectedVersion`, `analysisId`, `reason`, `correlationId` | An already terminal row returns its current view for the same bindings. An active row requires the expected version. Reasons are `ABSOLUTE_DEADLINE`, `FIRST_ROUND_COMPLETE`, `CLOSED`, `CANCELLED`. For an early expiry the authenticated Gateway asserts the corresponding durable MCP phase after fencing work. |
| `read` | `mandateRef`, `generation` | Gateway-only lookup on the primary for reconciliation. A record past its absolute deadline is materialized as expired, including its audit event. |

`proof` is the Gateway's request-local attestation over the entire `issue` request on the mutually authenticated channel. Send `actorType: "HUMAN"`, `mode: "BATCH_V1"`, `phase: "FIRST_ROUND"`, `authenticatedAtMs` from verified IAM reauthentication evidence, `observedAtMs` and `validUntilMs` from the synchronized Gateway clock. The evidence window is at most 30 s. It attests that the Gateway validated the authenticated plan and the durable MCP analysis, verified profile, start command and policy epoch. For renewal it also attests renewed consent by the same HUMAN holder. Authorization requires the renewed authentication to postdate the prior issue and the prior authentication, and preserves the original analysis, owner, plan binding and workload. Completion, closure and cancellation do not permit renewal.

The emission key is scoped to installation, issuer, tenant and subject. Replay with identical issue fields, including the expected version, returns the committed reference and its current state even after revocation or expiry. Replay can omit `proof`, and does not recheck current rights or extend the deadline. Reusing the key with changed fields conflicts. Only a new issuance requires fresh proof and current rights. Absolute expiry is 24 h after issuance at most, and the Gateway calls `expire` when the first round ends or the analysis closes or is cancelled.

The registry permits exactly `semantic.search.batch`, `semantic.discovery.request`, `semantic.discovery.status`, and `semantic.discovery.candidates`. Their required scopes are `ouf.semantic.search` for search and `ouf.semantic.discovery` for discovery. Use the Gateway 67a canonical `argumentsHash`. The Gateway remains responsible for verifying the work evidence and only accepts status or candidates for the durable discovery binding of the analysis.

## Current authority

Authorization acquires a fresh decision from the configured current-rights authority over mTLS with hostname verification. Configure `ouf.authorization.analysis-mandate.rights-uri`, `rights-ssl-bundle` (a Spring SSL bundle with client key material and trust anchors), `gateway-cert-sha256`, `installation`, `issuer`, `mcp-workload`, `ths-client`, and `clock-skew-ms` (default 2000, maximum 5000). Point the datasource at the authoritative writer. A deployment must preserve confirmed terminal transitions across failover and fence this path on restore until tombstones are reconciled.

The rights request uses the 67a `ouf.analysis.batch.mandate.v1` JSON and exact-byte SHA-256 `requestHash` with a new UUID nonce. It includes the original plan and identity context, `policyEpoch`, and dispatch metadata where applicable. The action is `issue`, `dispatch` or `revoke`. The authority returns the 67a fields `clockSynchronized`, `observedAtMs`, `validUntilMs`, `active`, `allowed` and on an allow for issue/dispatch `decisionRef`, `policyEpoch`, `acr`, `scope`, `roles` and all four effective grant fingerprints. It must evaluate fresh IAM, current profile and effective grants and include `policyBundleHash`, the active Authorization bundle's content hash used in the evaluation. Revocation instead returns the independently authenticated HUMAN identity and, for a THS operator, `operatorAuthorized` bound to this request and tenant.

Remote evidence is fetched before acquiring registry locks, and freshness is checked again inside the primary transaction. Issue and admit hold a shared lock on the active policy pointer and check the bundle hash. Admission compares the fresh policy epoch, ACR, effective scope, roles and grant fingerprints with the issued snapshot. It also locks the mandate row shared with revocation and expiry, checks the primary clock against the evidence and appends audit before returning. No positive decision is cached. Termination requires durable audit in the same transaction. The audit contains actor, installation, analysis, generation, epoch, recognized operation, correlation, timestamp, outcome and reason. Tokens, receipts, request arguments and intent are excluded.

## Errors and retries

Errors have one JSON field `code`.

| HTTP | Codes |
| --- | --- |
| 400 | `INVALID_REQUEST` |
| 401 | `GATEWAY_AUTHENTICATION_REQUIRED`, `HUMAN_AUTHENTICATION_REQUIRED` |
| 403 | `GATEWAY_WORKLOAD_DENIED`, `MANDATE_BINDING_MISMATCH`, `HUMAN_PROOF_REQUIRED`, `HUMAN_PROOF_INVALID`, `REAUTHENTICATION_REQUIRED`, `OPERATION_DENIED`, `PRINCIPAL_INACTIVE`, `CURRENT_RIGHTS_DENIED`, `RIGHTS_CHANGED`, `POLICY_EPOCH_CHANGED`, `POLICY_CHANGED`, `MANDATE_REVOKED`, `MANDATE_EXPIRED`, `REVOCATION_DENIED` |
| 404 | `MANDATE_NOT_FOUND` |
| 409 | `ISSUE_KEY_CONFLICT`, `MANDATE_VERSION_CHANGED`, `MANDATE_GENERATION_CHANGED`, `MANDATE_NOT_RENEWABLE`, `DEADLINE_NOT_REACHED` |
| 500 | `REGISTRY_FAILURE` |
| 503 | `CURRENT_RIGHTS_UNAVAILABLE`, `RIGHTS_EVIDENCE_INVALID`, `CLOCK_UNVERIFIED`, `REGISTRY_UNAVAILABLE` |

Authorization performs one bounded attempt per command. A failed or ambiguous admission authorizes zero dispatches. The caller preserves the existing durable retry and reconciliation budget, including the same issuance key for an identical issue and a new fresh check before dispatch. Honor `Retry-After` when present. Terminal state, invalid evidence, clock incidents, identity drift, policy drift and unclassified failures pause affected work for governed handling. The Gateway still owns clock monitoring, work checkpointing and retry scheduling.
