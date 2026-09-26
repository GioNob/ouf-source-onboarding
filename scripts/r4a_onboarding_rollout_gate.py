#!/usr/bin/env python3
"""Read-only rollout gate: DB backup, migration version, and MinIO prefix.

Temporary mc configuration is deleted inside MinIO. Never prints database
rows, object keys, admin credentials, Docker environments or backup contents.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import stat
import subprocess
import sys

DIRECTORY = Path('/etc/ouf/deploy-snapshots')
PREVIOUS = DIRECTORY / 'r4a-before-staging.docker-inspect.json'
IMAGE_ID = 'sha256:8ca287241c4dd7753fe23a300c1b5764aab9485021efd626f5db6fee8356f920'


def run(*cmd: str, input: str | bytes | None = None) -> bytes:
    binary = isinstance(input, bytes)
    result = subprocess.run(cmd, input=input, stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL, text=not binary, check=False)
    if result.returncode:
        raise ValueError('CHECK_COMMAND_FAILED')
    return result.stdout if binary else result.stdout.encode()


def inspect(name: str) -> dict:
    docs = json.loads(run('docker', 'inspect', name))
    if len(docs) != 1:
        raise ValueError('DOCKER_INSPECT_UNEXPECTED')
    return docs[0]


def private(path: Path, kind: int, mode: int) -> None:
    meta = path.lstat()
    if meta.st_uid != 0 or stat.S_IFMT(meta.st_mode) != kind or stat.S_IMODE(meta.st_mode) != mode:
        raise ValueError('PRIVATE_PATH_UNSAFE')


def check_db(archive: Path) -> str:
    private(archive, stat.S_IFREG, 0o600)
    if archive.stat().st_size < 1024:
        raise ValueError('DATABASE_DUMP_TOO_SMALL')
    with archive.open('rb') as stream:
        listed = subprocess.run(['docker', 'exec', '-i', 'ouf-postgres', 'pg_restore', '--list'],
                                stdin=stream, stdout=subprocess.DEVNULL,
                                stderr=subprocess.DEVNULL, check=False)
    if listed.returncode:
        raise ValueError('DATABASE_DUMP_INVALID')
    pg = inspect('ouf-postgres')
    postgres = dict(item.partition('=')[::2] for item in pg['Config']['Env'])
    user = postgres.get('POSTGRES_USER', '')
    if not user or not user.replace('_', '').isalnum():
        raise ValueError('POSTGRES_USER_UNEXPECTED')
    query = "select coalesce(max(version),'0') from ouf_onboarding.flyway_schema_history where success=true;"
    value = run('docker', 'exec', '-i', 'ouf-postgres', 'psql', '-U', user,
                '-d', 'ouf_onboarding', '-Atc', query).decode().strip()
    if not value.isdecimal():
        raise ValueError('FLYWAY_VERSION_INVALID')
    return value


def check_bucket() -> bool:
    shell = r'''set -eu
umask 077
cfg="$(mktemp -d /tmp/ouf-r4a-rollout-mc.XXXXXX)"
trap 'rm -rf "$cfg"' EXIT
if [ -n "${MINIO_ROOT_USER_FILE:-}" ]; then root_user="$(cat "$MINIO_ROOT_USER_FILE")"; else root_user="${MINIO_ROOT_USER:-}"; fi
test -n "$root_user" && test -r "${MINIO_ROOT_PASSWORD_FILE:-}" || exit 31
root_password="$(cat "$MINIO_ROOT_PASSWORD_FILE")"
mc --config-dir "$cfg" alias set r4a http://127.0.0.1:9000 "$root_user" "$root_password" >/dev/null 2>&1 || exit 32
unset root_password root_user
mc --config-dir "$cfg" stat r4a/ouf-managed-files >/dev/null 2>&1 || exit 33
mc --config-dir "$cfg" ls --recursive r4a/ouf-managed-files/managed-files/ > "$cfg/objects" 2>/dev/null || exit 34
if [ -s "$cfg/objects" ]; then echo PREFIX_EMPTY=false; else echo PREFIX_EMPTY=true; fi
'''
    out = run('docker', 'exec', '-i', 'ouf-minio', 'sh', '-s', input=shell).decode().strip()
    if out not in ('PREFIX_EMPTY=true', 'PREFIX_EMPTY=false'):
        raise ValueError('MINIO_PREFIX_CHECK_UNEXPECTED')
    return out.endswith('true')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--db-dump', type=Path, required=True)
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise ValueError('ROOT_REQUIRED')
    private(DIRECTORY, stat.S_IFDIR, 0o700)
    private(PREVIOUS, stat.S_IFREG, 0o600)
    if args.db_dump.parent != DIRECTORY or args.db_dump.is_symlink():
        raise ValueError('DATABASE_DUMP_PATH_UNEXPECTED')
    saved = json.loads(PREVIOUS.read_bytes())
    original_id = next(d['Id'] for d in saved if d['Name'] == '/ouf-onboarding')
    live = inspect('ouf-onboarding')
    candidate = inspect('ouf-onboarding-r4a-candidate')
    if (live['Id'] != original_id or not live['State']['Running'] or
            candidate['State']['Status'] != 'created' or candidate['Image'] != IMAGE_ID):
        raise ValueError('CANDIDATE_OR_ORIGINAL_CHANGED')
    version = check_db(args.db_dump)
    empty = check_bucket()
    print('ORIGINAL_RUNNING=true')
    print('CANDIDATE_STOPPED=true')
    print('BACKUP_ARCHIVE_VALID=true')
    print('FLYWAY_VERSION=' + version)
    print('MINIO_STAGING_PREFIX_EMPTY=' + str(empty).lower())
    print('NO_PERSISTENT_WRITES=true')
    print('SECRETS_PRINTED=false')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, StopIteration, json.JSONDecodeError) as exc:
        print('ROLLOUT_GATE_BLOCKED=' + (str(exc) if isinstance(exc, ValueError) else type(exc).__name__), file=sys.stderr)
        raise SystemExit(1)
