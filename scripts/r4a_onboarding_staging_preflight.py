#!/usr/bin/env python3
"""Read-only preflight for the R4a Onboarding staging container deployment.

Docker inspect and credential values remain in process memory. Output contains
only boolean facts and a checksum of the private, pre-deploy snapshot.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import stat
import subprocess
import sys

SNAPSHOT = Path('/etc/ouf/deploy-snapshots/r4a-before-staging.docker-inspect.json')
SECRET_DIR = Path('/etc/ouf/secrets')
TOKEN_DIR = Path('/run/ouf-onboarding-auth')
NAMES = ('ouf-onboarding', 'ouf-onboarding-r4a-smoke', 'ouf-minio')
STAGING_ENV = ('OUF_ONBOARDING_STAGING_ENDPOINT', 'OUF_ONBOARDING_STAGING_BUCKET',
               'OUF_ONBOARDING_STAGING_ACCESS_KEY_FILE', 'OUF_ONBOARDING_STAGING_SECRET_KEY_FILE',
               'OUF_ONBOARDING_OBJECT_STORE_GATEWAY_BASE_URL',
               'OUF_ONBOARDING_OBJECT_STORE_TOKEN_FILE')


def private(path: Path, kind: int, owner: int, group: int, mode: int) -> bool:
    try:
        meta = path.lstat()
    except FileNotFoundError:
        return False
    if (stat.S_IFMT(meta.st_mode), meta.st_uid, meta.st_gid, stat.S_IMODE(meta.st_mode)) != (kind, owner, group, mode):
        raise ValueError('PRIVATE_PATH_UNSAFE')
    return True


def inspect() -> list[dict]:
    run = subprocess.run(['docker', 'inspect', *NAMES], stdout=subprocess.PIPE,
                         stderr=subprocess.DEVNULL, check=False)
    if run.returncode:
        raise ValueError('DOCKER_INSPECT_FAILED')
    docs = json.loads(run.stdout)
    if not isinstance(docs, list) or {d['Name'].removeprefix('/') for d in docs} != set(NAMES):
        raise ValueError('DOCKER_INSPECT_UNEXPECTED')
    return docs


def main() -> None:
    snapshot_ok = private(SNAPSHOT, stat.S_IFREG, 0, 0, 0o600)
    if not snapshot_ok:
        raise ValueError('SNAPSHOT_MISSING')
    raw = SNAPSHOT.read_bytes()
    saved = json.loads(raw)
    if {d['Name'].removeprefix('/') for d in saved} != set(NAMES):
        raise ValueError('SNAPSHOT_UNEXPECTED')
    live = inspect()
    current = {d['Name']: d for d in live}
    original = {d['Name']: d for d in saved}
    unchanged = all(current[name]['Id'] == original[name]['Id'] for name in current)
    secrets_ok = private(SECRET_DIR, stat.S_IFDIR, 0, 0, 0o700)
    access_ok = private(SECRET_DIR / 'onboarding-minio-access-key', stat.S_IFREG, 0, 10003, 0o440)
    secret_ok = private(SECRET_DIR / 'onboarding-minio-secret-key', stat.S_IFREG, 0, 10003, 0o440)
    token_dir_ok = private(TOKEN_DIR, stat.S_IFDIR, 0, 10003, 0o750)
    token_ok = private(TOKEN_DIR / 'token', stat.S_IFREG, 0, 10003, 0o440)
    app = current['/ouf-onboarding']
    env_keys = {item.split('=', 1)[0] for item in app['Config'].get('Env') or []}
    mounts = {m['Destination'] for m in app.get('Mounts') or []}
    print('SNAPSHOT_SHA256=' + hashlib.sha256(raw).hexdigest())
    print('ORIGINAL_CONTAINERS_PRESENT=' + str(unchanged).lower())
    print('SECRET_DIRECTORY_SAFE=' + str(secrets_ok).lower())
    print('MINIO_CREDENTIAL_FILES_SAFE=' + str(access_ok and secret_ok).lower())
    print('TOKEN_DIRECTORY_SAFE=' + str(token_dir_ok and token_ok).lower())
    print('ONBOARDING_NETWORK_OK=' + str('ouf-backend' in (app['NetworkSettings'].get('Networks') or {})).lower())
    print('ONBOARDING_RUNTIME_USER_OK=' + str(app['Config'].get('User') == '10003:10003').lower())
    print('ONBOARDING_STAGING_ENV_KEYS_PRESENT=' + str(set(STAGING_ENV) <= env_keys).lower())
    print('ONBOARDING_STAGING_MOUNTS_PRESENT=' + str({
        '/run/secrets/onboarding-minio-access-key',
        '/run/secrets/onboarding-minio-secret-key',
        '/run/ouf-onboarding-auth',
    } <= mounts).lower())
    print('NO_WRITES=true')
    print('SECRET_VALUES_NOT_READ_OR_PRINTED=true')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as exc:
        print('STAGING_PREFLIGHT_BLOCKED=' + (str(exc) if isinstance(exc, ValueError) else type(exc).__name__), file=sys.stderr)
        raise SystemExit(1)
