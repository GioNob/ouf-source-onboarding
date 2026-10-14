# R4a temporary grant proposal planner

`scripts/r4a_temporary_grant_proposal.py` prepares one bounded nominal grant
without publishing policy changes.

The planner is intentionally proposal-only. It reads the current configured
grants for one subject through the trusted-HUMAN review endpoint, refuses
incomplete pagination, refuses an already-active equivalent ALLOW grant, and
materializes a 0600 request file for `authorization.permissions.propose`.
The request still has to travel through the governed MCP/Gateway proposal path.
Confirmation and publication remain exclusively on the Trusted Human Surface.

It never writes Authorization tables directly, never confirms a proposal and
never stores bearer tokens in the repository.

## Inputs

Required:
- `--subject-id`
- `--capability-id`
- `--grant-id`
- `--reason`

Defaults:
- tenant `ouf-lab`
- resource type `capability`
- duration 60 minutes

Duration is bounded to 5..1440 minutes.

Provide exactly one source for the preflight:
- `--admin-token-file`: 0600 bearer file owned by the caller; the script calls
  `GET /api/trusted-human/v1/authorization/access`.
- `--access-json`: previously captured complete access response for offline
  planning/tests.

The access page must have `nextAfter=null`; the helper refuses to infer absence
from a partial page.

## Example: R4a urban object search acceptance

```bash
python3 scripts/r4a_temporary_grant_proposal.py \
  --subject-id 177fd705-b57f-4f9e-a23c-af9d1f3f1f75 \
  --capability-id urban.object.search \
  --grant-id r4a-urban-object-search-giovanni-chatgpt \
  --reason "R4a bounded urban.object.search acceptance" \
  --duration-minutes 60 \
  --admin-token-file /run/ouf-admin-device-XXXX/access.token \
  --output /tmp/r4a-urban-object-search-grant.request.json
```

A successful plan reports the base `POLICY_REF`, grant validity and
`POLICY_NOT_CHANGED=true`. With `--output`, the generated file is mode 0600
and contains:
- tool name `authorization.permissions.propose`;
- exactly one UPSERT change;
- the complete nominal grant;
- the expected base policy reference.

The generated request is not an approval credential and is not sent directly to
Onboarding. Submission must use the MCP proposal capability so Gateway creates
and binds the owner receipt. The returned proposal card must then be reviewed
and confirmed in the THS. ACTIVE must remain unchanged until that confirmation.

## Safety invariants

- no DB INSERT/UPDATE;
- no direct call to the internal owner `/permissions/propose` endpoint;
- no automatic THS confirmation;
- no implicit role or IAM lookup;
- no duplicate active equivalent grant;
- no incomplete pagination;
- grant resource type and validity are explicit;
- request file is local 0600 state only.
