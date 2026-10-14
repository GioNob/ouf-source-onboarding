#!/usr/bin/env python3
"""Reconcile one exact Keycloak client-scope binding without touching other scopes."""
from __future__ import annotations

import argparse
import sys

from r4a_keycloak_client_scope_catalogue import ScopeError, exact_scope, get_json, run, validate_name

KINDS = {"default": "default-client-scopes", "optional": "optional-client-scopes"}


def exact_client(container, realm, client_id):
    rows = get_json(container, "get", "clients", "-r", realm, "-q", f"clientId={client_id}")
    matches = [row for row in rows if isinstance(row, dict) and row.get("clientId") == client_id]
    if len(matches) != 1:
        raise ScopeError("CLIENT_NOT_UNIQUE" if matches else "CLIENT_MISSING")
    if not isinstance(matches[0].get("id"), str) or not matches[0]["id"]:
        raise ScopeError("CLIENT_ID_MISSING")
    return matches[0]["id"]


def bindings(container, realm, client_uuid):
    result = {}
    for kind, path in KINDS.items():
        rows = get_json(container, "get", f"clients/{client_uuid}/{path}", "-r", realm)
        result[kind] = {row["id"] for row in rows if isinstance(row, dict) and isinstance(row.get("id"), str)}
    return result


def state(bound, scope_id, kind):
    other = "optional" if kind == "default" else "default"
    if scope_id in bound[other]:
        raise ScopeError("CONFLICTING_BINDING:" + other.upper())
    return "BOUND" if scope_id in bound[kind] else "MISSING"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("plan", "apply", "verify"))
    parser.add_argument("--container", default="ouf-keycloak")
    parser.add_argument("--realm", default="ouf")
    parser.add_argument("--client", required=True)
    parser.add_argument("--scope", required=True)
    parser.add_argument("--binding", required=True, choices=tuple(KINDS))
    args = parser.parse_args()
    for label, value in (("REALM", args.realm), ("CLIENT", args.client), ("SCOPE", args.scope)):
        validate_name(value, label)
    scope = exact_scope(args.container, args.realm, args.scope)
    if scope is None or not isinstance(scope.get("id"), str) or not scope["id"]:
        raise ScopeError("SCOPE_MISSING")
    client_uuid = exact_client(args.container, args.realm, args.client)
    current = state(bindings(args.container, args.realm, client_uuid), scope["id"], args.binding)
    print("MODE=" + args.mode)
    print("CLIENT=" + args.client)
    print("SCOPE=" + args.scope)
    print("BINDING=" + args.binding.upper())
    print("STATE=" + current)
    if args.mode == "plan":
        print("NO_CHANGES=true")
        return
    if args.mode == "verify" and current != "BOUND":
        raise ScopeError("VERIFY_BINDING_MISSING")
    if args.mode == "apply" and current == "MISSING":
        run(args.container, "update", f"clients/{client_uuid}/{KINDS[args.binding]}/{scope['id']}", "-r", args.realm)
    if state(bindings(args.container, args.realm, client_uuid), scope["id"], args.binding) != "BOUND":
        raise ScopeError("VERIFY_BINDING_MISSING")
    print("VERIFY=PASS")
    print("SECRETS_PRINTED=false")


if __name__ == "__main__":
    try:
        main()
    except (OSError, KeyError, TypeError, ValueError, ScopeError) as exc:
        code = str(exc) if isinstance(exc, ScopeError) else type(exc).__name__
        print("CLIENT_SCOPE_BINDING_BLOCKED=" + code, file=sys.stderr)
        raise SystemExit(1)
