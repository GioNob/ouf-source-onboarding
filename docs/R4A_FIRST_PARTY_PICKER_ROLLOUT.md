# R4a first-party file picker: lab rollout record

> **Current status, 27 September 2026.** The rollout commands below are
> historical checkpoints, not a sequence to repeat. Picker and handoff
> rollouts reported PASS; a HUMAN upload produced asset
> `8ec8ae90-808a-4d9e-907c-d56de119e376` and profile
> `4462692b-9c85-446b-b6fd-779f01eab64d`. Semantic publication passed.
> The Onboarding DRAFT remains inactive and R-SMOKE is open.
> The widget handoff across MCP clients has a simulated portability test,
> not a live second-host acceptance. See the
> [cross-module handoff](https://github.com/GioNob/ouf-semantic-registry/blob/codex/r4a-smoke-semantic-inventory/docs/handoffs/OUF_HANDOFF_2026-09-27_R4A.md).
>
> A user asks to import a **file**, not specifically a CSV. Onboarding
> determines format after selection. The presently observed lab upload was
> CSV; support for other PET formats is a separate implementation/acceptance
> gate. Do not use `cinema` as a record or canonical identity key.

## Historical rollout checkpoints

The PET Gateway v1.5 T25/T28/Table 81 and Onboarding v1.6 section 92 require
the one `ouf.managed-source.file.upload` capability to carry a bounded file
through the Gateway into the existing Onboarding staging asset. The picker is
a web entry to that capability. It uses the existing `ouf-ths` HUMAN session;
MCP sends no attachment URL and never downloads the file. A user selects a local file in OUF; the Asset ID remains a fallback if the
host does not deliver the automatic chat handoff.

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
`f5d7b0d5580ad1c602d436035c3b9dd7cfec14dd`.
Pinned MCP revision: `208e5dd0259eb54c0d0ee8c516cc87c14a7ed6ca`.

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
git show 208e5dd0259eb54c0d0ee8c516cc87c14a7ed6ca:scripts/r4a_attachment_rollout.py | sudo python3 - --mcp-commit 208e5dd0259eb54c0d0ee8c516cc87c14a7ed6ca --mode picker --picker-url https://api.ouf-lab.it/trusted-human/managed-files/ --onboarding-revision e1d5d59cbb07c5f7b6e4b73c2e1f57c24b6c3300 --materialization /etc/ouf/deploy-snapshots/r4a-mcp-routes-vqa3yS
```

Expected: `MANAGED_ATTACHMENT_ROLLOUT=PASS`, `MODE=PICKER`,
`PICKER_ROLLBACK_STATE=...` and `UPLOAD_LIVE_TEST_PENDING=true`.
A passing installer still creates no asset.
Do not repeat it after a pass unless handling an explicit rollback or update.

## Original CSV test procedure (completed for the recorded asset)

From ChatGPT, call the existing `source.file.upload` tool and open the OUF
picker URL it returns. Sign in as `ouf-admin`, select a small local CSV and
press **Carica file**. The browser page must show an Asset ID after HTTP 201.
The early test copied the Asset ID into chat. This is historical behavior;
the normal UX should carry the result through the status/handoff capability. The DRAFT approval, Semantic/Registry alignment,
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

## Chat return design and historical rollout (subsequently deployed)

The original picker required copying the Asset ID. The follow-up uses the
same `source.file.upload` tool and the same governed browser upload route.
The tool creates a random handoff ID and displays a ChatGPT widget. OUF binds
the ID to the staged asset and its HUMAN owner for at most 30 minutes in
bounded, ephemeral Onboarding memory. The widget asks MCP for that result via
an exact Gateway route and posts the returned Asset ID to the conversation.
No CSV bytes or browser session tokens enter MCP. A process restart or closed
widget can lose the automatic return; the picker retains the visible Asset ID
as a fallback. Profiling is still a separate authorized capability.

At that checkpoint, after candidate CI, the pinned MCP wrapper
`scripts/r4a_picker_chat_handoff_rollout.py` upgrades the already installed
picker in one run. It uses the existing Onboarding upgrade script, builds and
installs the fourth internal MCP route with a private route snapshot, then
swaps MCP in picker mode. The Onboarding database schema and policy bundle do
not change. A failed stage rolls back the steps it completed; it does not
delete the previously uploaded asset. Only a real upload from the ChatGPT
widget can prove the automatic chat return.

Pinned candidate commits: Onboarding
`4b552ffb1a685a38f1387a20e655da9e4d0500a4`, Gateway
`a226090e7cf1b5e411e9fa422664fa9b4b05746f`, MCP
`bdfce3baec8aaa4bcb9ba6b039aa3b25b023d57a`. On the lab VPS, after
those commits' CI succeeds, paste this single block as `oufadmin`:

```bash
cd /opt/ouf/mcp || exit 1
set -euo pipefail
git -C /opt/ouf/onboarding fetch origin codex/r4a-authorization-catalogue
git -C /opt/ouf/gateway fetch origin codex/r4a-managed-mcp-installer-fix
git fetch origin codex/r4a-managed-file-rollout
git show bdfce3baec8aaa4bcb9ba6b039aa3b25b023d57a:scripts/r4a_picker_chat_handoff_rollout.py | sudo python3 - --mcp-commit bdfce3baec8aaa4bcb9ba6b039aa3b25b023d57a
```

The script verifies all three pinned Git objects before mutation. Success is
`PICKER_CHAT_HANDOFF_ROLLOUT=PASS` with the three retained rollback references
and `LIVE_CHAT_WIDGET_TEST_PENDING=true`. A passing script still does not
prove ChatGPT's follow-up bridge. Test from a fresh `source.file.upload` call,
choose a small local CSV in the OUF picker, return to its ChatGPT widget, and
verify that the conversation receives the Asset ID without typing it.

The earlier asset `55ce7fd2-1893-4d3c-b95d-9c8106c1200a` had an authorization
failure at that checkpoint. Later scoped bindings and the successful profile
for asset `8ec8ae90-808a-4d9e-907c-d56de119e376` supersede that diagnosis.
They do not prove preview, DRAFT activation, Ingestion or UDP for this asset.
