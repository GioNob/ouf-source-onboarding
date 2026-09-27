# R4a first-party CSV picker: coordinated lab rollout

The PET Gateway v1.5 T25/T28/Table 81 and Onboarding v1.6 section 92 require
the one `ouf.managed-source.file.upload` capability to carry a bounded file
through the Gateway into the existing Onboarding staging asset. The picker is
a web entry to that capability. It uses the existing `ouf-ths` HUMAN session;
MCP sends no attachment URL and never downloads the file. A user selects the
local CSV in OUF and receives an Asset ID for the existing profiling flow.

The two release programs below perform preflight and rollback internally. Run
them only on the lab VPS with the existing Docker network, APISIX route
materialization directory and Keycloak `kcadm` session. Do not paste client
secrets, access tokens, CSV contents or Docker environment values into chat.

## Stage 1: Onboarding and `ouf-ths`

Pinned Onboarding revision:
`e1d5d59cbb07c5f7b6e4b73c2e1f57c24b6c3300`.

From `/opt/ouf/onboarding`, fetch that branch as `oufadmin`, then run the
standalone pinned script as root. Fetch writes only to the checkout; `apply`
performs the checks, scope binding, image build, verified DB backup and
container cutover in one program. No separate plan/verify sequence is needed.

```bash
set -o pipefail
git fetch origin codex/r4a-authorization-catalogue
git show e1d5d59cbb07c5f7b6e4b73c2e1f57c24b6c3300:scripts/r4a_picker_onboarding_rollout.py | sudo python3 - apply --revision e1d5d59cbb07c5f7b6e4b73c2e1f57c24b6c3300
```

Expected: `PICKER_ONBOARDING_ROLLOUT=PASS`, `ROLLBACK_STATE=...` and
`LIVE_HUMAN_LOGIN_PENDING=true`. The script loads its database helper from
the same pinned revision. If Keycloak's administrative session has expired,
it stops before changing the container; renew that session using the existing
Keycloak operator procedure and retry. A failed container start restores the
old container; the root-owned DB dump and stopped original are retained.

## Stage 2: Gateway UI route and the same MCP upload tool

Pinned Gateway revision:
`50eaffae9ce16064ca0c1d69aa13ea50169b772f`.
Pinned MCP revision: `d2e57cd15cd0fc210ef6ca62d66b9e839060e1a8`.

Fetch Gateway and MCP as `oufadmin`. The private materialization directory
from the earlier R4a route installation must contain `runtime.json`,
`mcp-routes.json` and `upload-route.json`. The coordinated script verifies
the live HUMAN upload route, creates the picker route, switches MCP to picker
mode, and restores its route/container snapshots if activation fails.

```bash
set -o pipefail
git -C /opt/ouf/gateway fetch origin codex/r4a-managed-mcp-installer-fix
git -C /opt/ouf/mcp fetch origin codex/r4a-managed-file-rollout
cd /opt/ouf/mcp
git show d2e57cd15cd0fc210ef6ca62d66b9e839060e1a8:scripts/r4a_attachment_rollout.py | sudo python3 - --mcp-commit d2e57cd15cd0fc210ef6ca62d66b9e839060e1a8 --mode picker --picker-url https://api.ouf-lab.it/trusted-human/managed-files/ --onboarding-revision e1d5d59cbb07c5f7b6e4b73c2e1f57c24b6c3300 --materialization /etc/ouf/deploy-snapshots/r4a-mcp-routes-vqa3yS
```

Expected: `MANAGED_ATTACHMENT_ROLLOUT=PASS`, `MODE=PICKER`,
`PICKER_ROLLBACK_STATE=...` and `UPLOAD_LIVE_TEST_PENDING=true`.
A passing installer still creates no asset.
Do not repeat it after a pass unless handling an explicit rollback or update.

## Already active picker: restore the missing OIDC login route

The first lab picker rollout completed with the UI route but without the
`/oauth2/authorization/ouf-ths` and `/login/oauth2/code/ouf-ths` routes.
Its browser session later expired and APISIX returned `404 Route Not Found`
for the login URL. Do not repeat the MCP/Onboarding cutover. Install only the
missing login/callback route with the pinned Gateway repair; it snapshots both
route IDs, checks the existing picker, verifies a login redirect and restores
its snapshot on failure. It does not rotate credentials or swap containers.

```bash
cd /opt/ouf/gateway || exit 1
git fetch origin codex/r4a-managed-mcp-installer-fix
set -o pipefail
git show 50eaffae9ce16064ca0c1d69aa13ea50169b772f:ops/apisix/repair_managed_file_picker_login.py | sudo python3 - --revision 50eaffae9ce16064ca0c1d69aa13ea50169b772f --materialization /etc/ouf/deploy-snapshots/r4a-mcp-routes-vqa3yS
```

Require `OIDC_LOGIN_ROUTE_ACTIVE=true` and `PICKER_LOGIN_ROUTE=PASS`. Keep
`BACKUP=...` alongside the existing picker rollback state. Refresh the
browser OUF session through the authorization URL, then retry the upload.
A future coordinated rollout from the pinned revisions above installs all
three picker/authorization/callback paths in one snapshot.

## One live CSV test

From ChatGPT, call the existing `source.file.upload` tool and open the OUF
picker URL it returns. Sign in as `ouf-admin`, select a small local CSV and
press **Carica file**. The browser page must show an Asset ID after HTTP 201.
Give that ID to the chat to invoke the existing file profile, preview and
onboarding-create tools. The DRAFT approval, Semantic/Registry alignment,
Ingestion and UDP smoke are subsequent steps; an Asset ID alone does not
claim their completion. Repeat separately with an oversized CSV and wrong
media type before declaring the Gateway T28 release gate closed. The route
snapshots and old containers stay available for rollback.

If the live test fails, use the printed `PICKER_ROLLBACK_STATE` with the same
pinned MCP script and `--rollback-state` to restore MCP and the picker route in
one operation. If Onboarding also needs restoration, use its printed
`ROLLBACK_STATE` with the same pinned Onboarding script in `rollback` mode.
Both rollback commands require the Git objects already fetched in the two
checkouts and preserve the DB dump. No automatic database restore is attempted.
