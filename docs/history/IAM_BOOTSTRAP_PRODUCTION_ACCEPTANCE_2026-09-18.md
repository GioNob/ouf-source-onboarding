# IAM bootstrap and production acceptance — 2026-09-18

Normative baseline: OUF Reality Baseline Package v1.7; Cross-Module Alignment Matrix v1.7; Source Onboarding / Configuration / THS PET v1.6; Authorization / Access Control PET v1.5.

This record captures the complete laboratory/production acceptance sequence executed on the Netcup OUF environment on 2026-09-18. It records successful gates and intermediate failures. It does not amend PET requirements.

## Starting checkpoint

- Source Onboarding R2f was already merged and deployed as `ouf-onboarding:0f1467f`.
- Database schema was Flyway V20.
- `ouf_authorization.bootstrap_latch` existed with `completed=false`.
- No capability registration, policy draft, policy bundle or ACTIVE pointer existed.
- IAM code was present but production was intentionally running with `ouf.iam.enabled=false`.
- Rollback container `ouf-onboarding-rollback-7f051e69` was retained.

## Repository increments in this acceptance

### PR #18 — canonical Authorization bootstrap descriptors

Merge commit: `8c444cb3ae52ef78093a0d1763358bb19278b774`.

Purpose:
- pin `authorization.policy.admin` as `EXECUTE`, scope `authorization.policy.admin`, allowed actor `HUMAN`;
- pin `authorization.bundle.read` as `READ`, scope `authorization.bundle.read`, allowed actor `SERVICE`;
- document bootstrap capability registration semantics.

CI:
- Source Onboarding module CI — run `35313470808` — SUCCESS.
- R2f trusted review browser — run `35313470898` — SUCCESS.

### PR #19 — break first-bundle distribution bootstrap cycle

Merge commit: `26986fe09c236a3aabcfcc3a32283128c087b7d0`.

Observed defect:
- authenticated MCP SERVICE with `authorization.bundle.read` received `403 NO_POLICY_BUNDLE` before first ACTIVE;
- root cause: `AuthorizationBundleApi` delegated to `TrustedActorResolver`, which required `ServletAuthorization.resolve()`; that in turn required an already-installed local policy runtime;
- therefore the endpoint needed a bundle in order to authorize reading the first bundle.

Fix:
- bundle distribution now authorizes from the server-bound trusted IAM principal;
- actor must be `SERVICE`;
- token scope must contain `authorization.bundle.read`;
- if no ACTIVE bundle exists, the endpoint reaches the registry and returns `503 AUTH_POLICY_BUNDLE_UNAVAILABLE`.

CI:
- Source Onboarding module CI — run `35318850814` — SUCCESS.
- R2f trusted review browser — run `35318850826` — SUCCESS.

## Keycloak IAM configuration

Realm: `ouf`.
Issuer: `https://auth.ouf-lab.it/realms/ouf`.

HUMAN client:
- clientId: `ouf-human-admin`;
- public client;
- Device Authorization Grant enabled;
- standard flow disabled;
- direct access grants disabled;
- default persistent admin scope: `authorization.policy.admin`;
- temporary `authorization.bootstrap` scope used only during bootstrap and removed after acceptance;
- hardcoded `tenant_id=ouf-lab`;
- hardcoded `ouf_actor_type=HUMAN`;
- audience mapper includes `ouf-api-gateway`;
- HUMAN identity uses Keycloak standard `sub` because the runtime intentionally falls back from optional `ouf_subject` to `sub`.

HUMAN account:
- username: `ouf-admin`;
- Keycloak user id / JWT subject used by policy grant: `b93d8cf6-cd14-4ee6-91d7-84cd76c4f500`;
- password managed only by Keycloak; no password/MFA duplicated in OUF.

Verified HUMAN token claims:
- `iss=https://auth.ouf-lab.it/realms/ouf`;
- `aud` contains `ouf-api-gateway`;
- `sub=b93d8cf6-cd14-4ee6-91d7-84cd76c4f500`;
- `tenant_id=ouf-lab`;
- `ouf_actor_type=HUMAN`;
- `acr=1`;
- bootstrap token contained `authorization.bootstrap` and `authorization.policy.admin`;
- `azp=ouf-human-admin`.

MCP workload:
- clientId: `ouf-mcp-server`;
- `authorization.bundle.read` added to default scopes;
- existing `urban.object.related_search` retained;
- added hardcoded `ouf_actor_type=SERVICE` mapper.

Verified MCP token claims:
- `aud` contains `ouf-api-gateway`;
- `client_id=ouf-mcp-server`;
- `azp=ouf-mcp-server`;
- `ouf_subject=workload:ouf-mcp-server`;
- `tenant_id=ouf-lab`;
- `ouf_actor_type=SERVICE`;
- `acr=1`;
- scopes contain `urban.object.related_search` and `authorization.bundle.read`.

## Canonical capability registration

Registered only through `/api/trusted-human/v1/authorization/capabilities`; no manual database INSERT/UPDATE was used.

1. `authorization.policy.admin`
   - owner: `authorization`
   - operation: `EXECUTE`
   - requiredScope: `authorization.policy.admin`
   - allowedActors: `HUMAN`

2. `authorization.bundle.read`
   - owner: `authorization`
   - operation: `READ`
   - requiredScope: `authorization.bundle.read`
   - allowedActors: `SERVICE`

3. `urban.object.related_search`
   - owner: `udp`
   - operation: `SEARCH`
   - requiredScope: `urban.object.related_search`
   - allowedActors: `SERVICE`, `AI_AGENT`

Post-registration DB count: 3.

## First real PolicyBundle

Draft id: `b20763cb-db4c-4ad2-8c26-c42ca4b6b959`.

Bundle:
- bundleId: `ouf-lab-authorization`;
- version: 1;
- baseActiveRef: `NONE`.

Grants:
- `grant-authorization-policy-admin-human`
  - capability: `authorization.policy.admin`
  - tenant: `ouf-lab`
  - subjectId: `b93d8cf6-cd14-4ee6-91d7-84cd76c4f500`
- `grant-authorization-bundle-read-mcp`
  - capability: `authorization.bundle.read`
  - tenant: `ouf-lab`
  - servicePrincipalId: `ouf-mcp-server`
- `grant-urban-object-related-search-mcp`
  - capability: `urban.object.related_search`
  - tenant: `ouf-lab`
  - servicePrincipalId: `ouf-mcp-server`

Pre-publish state:
- draft revision 0, state DRAFT;
- bootstrap latch false;
- ACTIVE count 0.

Pre-publish SERVICE acceptance after PR #19:
- authenticated SERVICE + `authorization.bundle.read` returned `503 AUTH_POLICY_BUNDLE_UNAVAILABLE`, proving authentication/scope acceptance with no ACTIVE bundle.

Publish:
- POST `/api/trusted-human/v1/authorization/policies/b20763cb-db4c-4ad2-8c26-c42ca4b6b959:publish`
- `If-Match: "0"`
- result: HTTP 200, ETag `"1"`.

Post-publish:
- draft revision 1, state PUBLISHED;
- bootstrap latch `completed=true`;
- completed_by: `b93d8cf6-cd14-4ee6-91d7-84cd76c4f500`;
- ACTIVE: `ouf-lab-authorization:1`;
- HUMAN admin GET after bootstrap: HTTP 200 through `authorization.policy.admin`;
- SERVICE bundle-reader after publish: HTTP 200.

The bootstrap latch is one-way and was not reopened.

## Production deployment

New production image:
- `ouf-onboarding:26986fe`.

Candidate acceptance before switch:
- health: 200;
- SERVICE bundle-reader: 200.

Production switch:
- previous active container preserved as `ouf-onboarding-rollback-0f1467f-4a40acd3287b`;
- historical rollback `ouf-onboarding-rollback-7f051e69` also retained;
- production `ouf-onboarding` now runs `ouf-onboarding:26986fe` with IAM enabled.

Production acceptance:
- health: 200;
- protected admin endpoint without bearer token: 401;
- SERVICE bundle-reader: 200;
- restart performed;
- health after restart: 200;
- SERVICE bundle-reader after restart with fresh token: 200;
- HUMAN admin after restart with fresh Device Flow token: 200.

After post-restart HUMAN acceptance:
- `authorization.bootstrap` was removed from the HUMAN client's default scopes;
- `authorization.policy.admin` remains.

Temporary `ouf-onboarding-iam-smoke` container was removed after production acceptance.

## Intermediate failures and resolutions

These are retained as evidence, not hidden.

1. PostgreSQL role assumption
   - attempted SQL role `postgres`;
   - environment actually uses `POSTGRES_USER=ouf_admin`;
   - corrected without changing database configuration.

2. HUMAN custom subject mapper
   - attempted custom user attribute `ouf_subject` did not persist under current Keycloak user-profile behavior;
   - mapper removed;
   - runtime's designed fallback to standard OIDC `sub` used instead.

3. HUMAN password/device flow
   - first temporary-password login failed;
   - credential existed with `UPDATE_PASSWORD`;
   - password was reset directly in Keycloak and required action cleared;
   - Device Flow then succeeded.

4. Token expiry
   - HUMAN and SERVICE access tokens use short TTLs;
   - multiple 401s occurred after long build/test intervals;
   - resolved by minting fresh tokens immediately before protected operations.
   - one failed refresh command redirected into the live token file and emptied it; subsequent token handling used replacement/atomic file patterns.

5. Initial IAM smoke issuer discovery failure
   - `auth.ouf-lab.it` resolved on the host but not inside `ouf-backend`;
   - startup failed with `UnknownHostException`;
   - fixed declaratively in Gateway/Caddy deployment; no permanent per-consumer `--add-host` workaround retained.

6. Temporary env-file permission failures
   - attempts to copy container env into `/tmp` via `sudo tee` failed under the invoking shell/file ownership arrangement;
   - abandoned;
   - smoke/production use canonical `/opt/ouf/secrets/onboarding.env` plus explicit IAM env overrides.

7. Host port publish anomaly on smoke
   - HostConfig contained `127.0.0.1:18080:8080`, but runtime `.NetworkSettings.Ports` was null and no listener existed;
   - unnecessary host exposure was dropped;
   - all smoke acceptance moved to ephemeral curl containers on `ouf-backend`.

8. Bundle-reader bootstrap cycle
   - pre-fix result: 403 `NO_POLICY_BUNDLE`;
   - corrected by PR #19;
   - post-fix pre-ACTIVE result: expected 503;
   - post-publish result: 200.

9. Draft verification query
   - attempted ORDER BY nonexistent `policy_draft.created_at`;
   - only the read-only verification query failed;
   - corrected using actual columns; draft/latch state remained unchanged.

10. First publish attempt
    - HUMAN token had expired;
    - request returned 401 before mutation;
    - DB confirmed draft remained DRAFT, latch false, ACTIVE empty;
    - fresh Device Flow token used for successful publish.

## Final verified state

- IAM production activation: PASS.
- First immutable ACTIVE PolicyBundle: PASS.
- Anti-lockout HUMAN admin grant: PASS.
- SERVICE bundle distribution: PASS.
- MCP related-search grant: present in ACTIVE.
- Bootstrap latch one-way closure: PASS.
- Bootstrap scope removed after restart acceptance: PASS.
- Production restart acceptance: PASS.
- Rollback containers retained: PASS.
- Secrets/passwords not committed or recorded in evidence: PASS.

## Remaining acceptance scope

This record closes the real IAM/bootstrap/first-policy/production-activation gate exercised here. It does not claim complete PET acceptance for all modules, all capabilities, all THS/browser flows, representative load/SLO, disaster recovery, or every roadmap group.
