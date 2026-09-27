#!/usr/bin/env python3
"""Reconcile only the managed-file default scopes of the OUF ChatGPT OAuth client.

The first-party picker uses its own THS login, but the MCP status/profile
calls use the ouf-chatgpt client. An optional scope bound to ouf-human-admin
does not appear in the ChatGPT token. No token or Keycloak secret is printed.
"""
import argparse
import json
import subprocess
import sys

CLIENT = 'ouf-chatgpt'
REALM = 'ouf'
KC = '/opt/keycloak/bin/kcadm.sh'
SCOPES = (
    'ouf.managed-source.file.upload',
    'ouf.managed-source.file.profile',
    'ouf.managed-source.preview',
    'ouf.managed-source.onboarding.create',
)

class Blocked(RuntimeError):
    pass

def kc(*args):
    result = subprocess.run(['docker', 'exec', '-i', 'ouf-keycloak', KC, *args],
                            capture_output=True, text=True, check=False)
    if result.returncode:
        if 'Session has expired' in result.stderr or 'Session has expired' in result.stdout:
            raise Blocked('KCADM_SESSION_EXPIRED')
        raise Blocked('KCADM_COMMAND_FAILED')
    return result.stdout

def listing(*args):
    try:
        value = json.loads(kc('get', *args, '-r', REALM))
    except ValueError as exc:
        raise Blocked('KCADM_INVALID_JSON') from exc
    if not isinstance(value, list):
        raise Blocked('KCADM_INVALID_LIST')
    return value

def exact(rows, field, value):
    matches = [row for row in rows if isinstance(row, dict) and row.get(field) == value]
    if len(matches) != 1 or not isinstance(matches[0].get('id'), str):
        raise Blocked('NOT_UNIQUE_OR_MISSING_' + value.upper().replace('.', '_').replace('-', '_'))
    return matches[0]

def inspect():
    client = exact(listing('clients', '-q', 'clientId=' + CLIENT), 'clientId', CLIENT)
    catalogue = listing('client-scopes')
    scopes = {name: exact(catalogue, 'name', name) for name in SCOPES}
    for name, scope in scopes.items():
        if scope.get('protocol') != 'openid-connect' or str((scope.get('attributes') or {}).get('include.in.token.scope')).lower() != 'true':
            raise Blocked('SCOPE_CONTRACT_INVALID_' + name.upper().replace('.', '_').replace('-', '_'))
    base = 'clients/' + client['id'] + '/'
    bound = {kind: {row['id'] for row in listing(base + kind + '-client-scopes')
                    if isinstance(row, dict) and isinstance(row.get('id'), str)}
             for kind in ('default', 'optional')}
    if bound['default'] & bound['optional']:
        raise Blocked('CONFLICTING_CLIENT_BINDING')
    state = {name: ('DEFAULT' if scope['id'] in bound['default'] else
                    'OPTIONAL' if scope['id'] in bound['optional'] else 'MISSING')
             for name, scope in scopes.items()}
    return base, scopes, state

def change(base, scope_id, kind, verb):
    kc(verb, base + kind + '-client-scopes/' + scope_id, '-r', REALM)

def reconcile(base, scopes, before):
    done = []
    try:
        for name in SCOPES:
            if before[name] == 'DEFAULT':
                continue
            scope_id = scopes[name]['id']
            if before[name] == 'OPTIONAL':
                change(base, scope_id, 'optional', 'delete')
                done.append((scope_id, 'optional', 'delete'))
            change(base, scope_id, 'default', 'update')
            done.append((scope_id, 'default', 'update'))
        if any(value != 'DEFAULT' for value in inspect()[2].values()):
            raise Blocked('VERIFY_DEFAULT_BINDING_FAILED')
    except (Blocked, OSError):
        rollback_ok = True
        for scope_id, kind, verb in reversed(done):
            try:
                change(base, scope_id, kind, 'update' if verb == 'delete' else 'delete')
            except (Blocked, OSError):
                rollback_ok = False
        raise Blocked('APPLY_FAILED_ROLLBACK_' + ('PASS' if rollback_ok else 'UNVERIFIED'))

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('plan', 'apply', 'verify'))
    args = parser.parse_args()
    base, scopes, before = inspect()
    print('MODE=' + args.mode + ' CLIENT=' + CLIENT)
    for name in SCOPES:
        print('SCOPE=' + name + ' BINDING=' + before[name])
    if args.mode == 'plan':
        print('NO_WRITES=true')
        return
    if args.mode == 'verify' and any(value != 'DEFAULT' for value in before.values()):
        raise Blocked('DEFAULT_BINDING_MISSING')
    if args.mode == 'apply':
        reconcile(base, scopes, before)
    print('VERIFY=PASS SECRETS_PRINTED=false')
    print('OAUTH_RECONNECT_REQUIRED=true')

if __name__ == '__main__':
    try:
        main()
    except (Blocked, OSError, KeyError, TypeError) as exc:
        code = str(exc) if isinstance(exc, Blocked) else type(exc).__name__
        print('CHATGPT_MANAGED_SCOPES_BLOCKED=' + code, file=sys.stderr)
        raise SystemExit(1)
