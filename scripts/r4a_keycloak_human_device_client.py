#!/usr/bin/env python3
"""Enable Device Authorization Grant on the exact OUF human admin public client."""
from __future__ import annotations

import argparse
import json
import sys

from r4a_keycloak_client_scope_catalogue import ScopeError, get_json, run, validate_name

DEVICE_ATTRIBUTE = "oauth2.device.authorization.grant.enabled"


def device_enabled(client):
    return str((client.get("attributes") or {}).get(DEVICE_ATTRIBUTE)).lower() == "true"


def exact_client(container, realm, client_id):
    rows = get_json(container, "get", "clients", "-r", realm, "-q", f"clientId={client_id}")
    matches = [row for row in rows if isinstance(row, dict) and row.get("clientId") == client_id]
    if len(matches) != 1:
        raise ScopeError("CLIENT_NOT_UNIQUE" if matches else "CLIENT_MISSING")
    uuid = matches[0].get("id")
    if not isinstance(uuid, str) or not uuid:
        raise ScopeError("CLIENT_ID_MISSING")
    client = get_json(container, "get", f"clients/{uuid}", "-r", realm)
    if client.get("id") != uuid or client.get("clientId") != client_id:
        raise ScopeError("CLIENT_IDENTITY_CHANGED")
    if client.get("protocol") != "openid-connect" or client.get("publicClient") is not True or client.get("enabled") is not True:
        raise ScopeError("CLIENT_CONTRACT_MISMATCH")
    return client


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("plan", "apply", "verify"))
    parser.add_argument("--container", default="ouf-keycloak")
    parser.add_argument("--realm", default="ouf")
    parser.add_argument("--client", default="ouf-human-admin")
    args = parser.parse_args()
    validate_name(args.realm, "REALM")
    validate_name(args.client, "CLIENT")
    if args.client != "ouf-human-admin":
        raise ScopeError("UNSUPPORTED_CLIENT")

    client = exact_client(args.container, args.realm, args.client)
    enabled = device_enabled(client)
    print("MODE=" + args.mode)
    print("CLIENT=" + args.client)
    print("DEVICE_FLOW_ENABLED=" + str(enabled).lower())
    if args.mode == "plan":
        print("NO_CHANGES=true")
        return
    if args.mode == "verify" and not enabled:
        raise ScopeError("VERIFY_DEVICE_FLOW_DISABLED")
    if args.mode == "apply" and not enabled:
        updated = dict(client)
        updated["attributes"] = {**(updated.get("attributes") or {}), DEVICE_ATTRIBUTE: "true"}
        run(args.container, "update", f"clients/{client['id']}", "-r", args.realm, "-f", "-",
            input_text=json.dumps(updated, separators=(",", ":")))
    after = exact_client(args.container, args.realm, args.client)
    if not device_enabled(after):
        raise ScopeError("VERIFY_DEVICE_FLOW_DISABLED")
    print("VERIFY=PASS")
    print("SECRETS_PRINTED=false")


if __name__ == "__main__":
    try:
        main()
    except (OSError, KeyError, TypeError, ValueError, ScopeError) as exc:
        code = str(exc) if isinstance(exc, ScopeError) else type(exc).__name__
        print("HUMAN_DEVICE_CLIENT_BLOCKED=" + code, file=sys.stderr)
        raise SystemExit(1)
