# THS feedback and MCP latency verification

## September 20 incident and correction

The permission confirmation disabled its buttons but displayed the result only above
the long review card. Operators could mistake a committed policy for a stalled request.
The UI now displays progress and the outcome next to the controls, updates the state
and published policy in the context, and moves focus to the outcome. After eight
seconds it explains the wait. This is a feedback threshold, not a backend timeout.

An interrupted request has an **unverified outcome**, not proof of failure. Reload
the review to read its authoritative state before acting again. No automatic POST
retry, duplicate submission, automatic approval or session extension is introduced.
Hash, revision, CSRF, IAM and owner authorization remain unchanged.

Deployment: build and roll out the Onboarding image containing these static assets,
preserving the current environment, secret mounts, UID 10003, networks and rollback
container. No migration or Keycloak change is required for this patch. Reload the
THS tab after rollout. Browser tests cover delayed response, duplicate clicks,
publication, rejection, stale context, network loss and reload of published state.

## Measure before changing timeouts

An observed connector call at 2026-09-20T12:27:38.168Z took 16,728 ms and returned
MCP HEALTHY. Earlier error-path APISIX logs contained a 42 ms POST /mcp (41 ms
upstream). These are different requests and cannot establish the current bottleneck.
Neither a few samples nor the chat's total turn duration establish p95 or an SLO.

On the SSH server, after installing this revision in /opt/ouf/onboarding, run:

```sh
sudo docker logs --since 15m --timestamps ouf-apisix 2>&1 | python3 /opt/ouf/onboarding/ops/summarize_mcp_latency.py
```

The parser emits aggregate request/upstream timings grouped by route class and HTTP
status. It does not emit IPs, query strings, user agents, identities or raw log lines.
Empty groups mean no matching observations, not zero latency. It supports the
existing APISIX combined log format, ignores unrelated refresh traffic and omits
ambiguous multi-upstream timings. HTTP 200 on /mcp can still contain a tool error.

For a clean measurement use a short quiet window with one authorized read, record
the UTC timestamp and connector elapsed time, and match the corresponding request
in server logs locally. Never sum /mcp and internal execute times: the latter is
nested in the former. Fast Gateway total with slow connector elapsed places the
unexplained time outside the logged Gateway interval; it does not distinguish
ChatGPT scheduling, connector processing, network transit or other client work.
Slow Gateway/upstream timings require investigation of IAM, application and DB
spans before altering connection reuse, retry or timeout settings. Do not disable
authorization, bypass Gateway or send bearer tokens through diagnostic chat output.

## Configuration lessons and verified baseline

- THS tenant mapper must emit `tenant_id`, not `tenant-id`; the latter caused 403.
- The `ouf-admin` account needs the trusted `ouf_actor_type=HUMAN` claim.
- Multi-account connector calls require the actual account `link_id`; it is
  connector routing metadata, not a permission argument or IAM claim.
- Policy 8 created operational-viewer without assignments. Policy 9 assigned it
  nominally to giovanni-chatgpt; a real read then returned HEALTHY at PUBLIC_OPERATIONAL.
- Registration, configured grants, effective authorization and role assignment are
  distinct checks; discovery alone does not verify access.

PET alignment: Source Onboarding THS human-only decision, state/hash/ETag and CSRF
requirements, local API latency target excluding remote dependencies; Authorization
109.8/109.12 protection of credentials and separate latency/evaluator evidence.
No policy-engine, IAM, cache-freshness or access-control relaxation is part of this fix.
