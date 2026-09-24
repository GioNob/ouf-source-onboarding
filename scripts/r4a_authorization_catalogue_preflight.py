#!/usr/bin/env python3
"""Read-only R4a Authorization catalogue gate, with no secret output.

Run as root on the OUF host. Never emit Docker inspect, database credentials,
bundle JSON, or grant payloads. No SQL mutations or policy publication.
"""
import json
import os
import subprocess

CAPABILITY = "urban.object.search"
SQL = """
select a.bundle_id, a.version,
       exists(select 1 from ouf_authorization.capability_registration r
              where r.capability_id='urban.object.search'),
       exists(select 1 from jsonb_array_elements(p.bundle_payload->'capabilities') c
              where c->>'capabilityId'='urban.object.search')
from ouf_authorization.active_policy_bundle a
join ouf_authorization.policy_bundle p on p.bundle_id=a.bundle_id and p.version=a.version
where a.singleton_key=true
"""


def main() -> None:
    if os.geteuid() != 0:
        raise SystemExit("R4A_AUTH_PREFLIGHT=BLOCKED; ROOT_REQUIRED=true")
    stage = "DOCKER_INSPECT"
    try:
        result = subprocess.run(
            ["docker", "inspect", "ouf-postgres"], check=True, capture_output=True, text=True
        )
        stage = "INSPECT_PARSE"
        inspected = json.loads(result.stdout)
        if len(inspected) != 1 or not inspected[0]["State"]["Running"]:
            raise ValueError("Postgres is not running")
        stage = "DATABASE_BINDING"
        env = dict(item.split("=", 1) for item in inspected[0]["Config"]["Env"] if "=" in item)
        user = env.get("POSTGRES_USER", "postgres")
        db = env.get("POSTGRES_DB", user)
        if not user or not db:
            raise ValueError("Database binding missing")
        stage = "DATABASE_QUERY"
        query = subprocess.run(
            ["docker", "exec", "--user", "postgres", "ouf-postgres",
             "psql", "-X", "-v", "ON_ERROR_STOP=1", "-A", "-t",
             "-F", "|", "-U", user, "-d", db, "-c", SQL],
            check=True, capture_output=True, text=True,
        )
        stage = "RESULT_PARSE"
        lines = query.stdout.strip().splitlines()
        if len(lines) != 1:
            raise ValueError("Active policy result is ambiguous")
        ref, version, registered, active = lines[0].split("|")
        if not ref or not version.isdecimal() or registered not in ("t", "f") or active not in ("t", "f"):
            raise ValueError("Invalid result")
    except (OSError, KeyError, ValueError, subprocess.SubprocessError, json.JSONDecodeError):
        raise SystemExit(f"R4A_AUTH_PREFLIGHT=BLOCKED; STAGE={stage}; NO_POLICY_CHANGED=true") from None
    print(f"ACTIVE_POLICY_REF={ref}:{version}")
    print(f"SEARCH_REGISTERED={'true' if registered == 't' else 'false'}")
    print(f"SEARCH_IN_ACTIVE_BUNDLE={'true' if active == 't' else 'false'}")
    print("R4A_AUTH_PREFLIGHT=READ_ONLY; NO_POLICY_CHANGED=true")


if __name__ == "__main__":
    main()
