#!/usr/bin/env python3
"""Plan/reconcile the exact Semantic HUMAN scopes on ouf-human-admin in one run."""
import argparse
import sys

from r4a_keycloak_client_scope_catalogue import (
    ScopeError, drift, exact_scope, reconcile, validate_name,
)
from r4a_keycloak_client_scope_binding import (
    KINDS, bindings, exact_client, run, state,
)

SCOPES = (
    'ouf.semantic.propose', 'ouf.semantic.review.prepare',
    'ouf.semantic.approval.request', 'ouf.semantic.review',
    'ouf.semantic.approve', 'ouf.semantic.publish',
    'ouf.semantic.search',
)
CLIENT = 'ouf-human-admin'
REALM = 'ouf'


def inspect(container):
    client_id = exact_client(container, REALM, CLIENT)
    current = bindings(container, REALM, client_id)
    catalogue = {name: exact_scope(container, REALM, name) for name in SCOPES}
    problems = {name: drift(catalogue[name]) for name in SCOPES}
    missing_binding = []
    for name, scope in catalogue.items():
        if scope is not None:
            if state(current, scope['id'], 'optional') == 'MISSING':
                missing_binding.append(name)
    return client_id, catalogue, problems, missing_binding


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('mode', choices=('plan', 'apply', 'verify'))
    p.add_argument('--container', default='ouf-keycloak')
    a = p.parse_args()
    validate_name(a.container, 'CONTAINER')
    client, catalogue, problems, missing = inspect(a.container)
    print('MODE=' + a.mode + ' CLIENT=' + CLIENT)
    print('CATALOGUE_DRIFT=' + (','.join(name for name in SCOPES if problems[name]) or 'NONE'))
    print('MISSING_OPTIONAL_BINDINGS=' + (','.join(missing) or 'NONE'))
    if a.mode == 'plan':
        print('NO_WRITES=true')
        return
    if a.mode == 'apply':
        for name in SCOPES:
            if problems[name]:
                reconcile(a.container, REALM, name)
        client, catalogue, problems, missing = inspect(a.container)
        if any(problems.values()):
            raise ScopeError('CATALOGUE_RECONCILE_FAILED')
        for name in missing:
            run(a.container, 'update',
                f'clients/{client}/{KINDS["optional"]}/{catalogue[name]["id"]}', '-r', REALM)
    _, _, problems, missing = inspect(a.container)
    if any(problems.values()) or missing:
        raise ScopeError('SEMANTIC_SCOPE_VERIFY_FAILED')
    print('VERIFY=PASS SCOPES=' + str(len(SCOPES)) + ' SECRETS_PRINTED=false')


if __name__ == '__main__':
    try:
        main()
    except (OSError, KeyError, TypeError, ValueError, ScopeError) as exc:
        code = str(exc) if isinstance(exc, ScopeError) else type(exc).__name__
        print('SEMANTIC_HUMAN_SCOPES_BLOCKED=' + code, file=sys.stderr)
        raise SystemExit(1)
