#!/usr/bin/env python3
"""Create a private Onboarding schema dump and prove it restores to scratch DB.

Does not stop production or write to its database. Scratch DB is removed on
success and failure. Keeps the dump for deployment rollback; never prints SQL,
Docker environment values or database content.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import secrets
import stat
import subprocess
import sys
from urllib.parse import urlsplit

DIRECTORY = Path('/etc/ouf/deploy-snapshots')
SCHEMA = 'ouf_onboarding'


def inspect(name: str) -> dict:
    run = subprocess.run(['docker', 'inspect', name], stdout=subprocess.PIPE,
                         stderr=subprocess.DEVNULL, check=False)
    if run.returncode:
        raise ValueError('CONTAINER_INSPECT_FAILED')
    result = json.loads(run.stdout)
    if len(result) != 1:
        raise ValueError('CONTAINER_INSPECT_UNEXPECTED')
    return result[0]


def env(doc: dict) -> dict[str, str]:
    result = {}
    for line in doc['Config'].get('Env') or []:
        key, sep, value = line.partition('=')
        if not sep or key in result:
            raise ValueError('CONTAINER_ENV_INVALID')
        result[key] = value
    return result


def parameters(app: dict, pg: dict) -> tuple[str, str]:
    if not app['State']['Running'] or not pg['State']['Running']:
        raise ValueError('CONTAINER_NOT_RUNNING')
    app_env = env(app)
    pg_env = env(pg)
    url = app_env.get('OUF_ONB_DB_URL', '')
    if not url.startswith('jdbc:postgresql://'):
        raise ValueError('DATABASE_URL_UNEXPECTED')
    parsed = urlsplit(url.removeprefix('jdbc:'))
    if parsed.scheme != 'postgresql' or parsed.path != '/' + SCHEMA or parsed.username or parsed.password:
        raise ValueError('DATABASE_TARGET_UNEXPECTED')
    dbuser = pg_env.get('POSTGRES_USER', '')
    if not dbuser or not dbuser.replace('_', '').isalnum() or len(dbuser) > 63:
        raise ValueError('POSTGRES_USER_UNEXPECTED')
    return SCHEMA, dbuser


def command(*args: str, stdin=None, stdout=None) -> None:
    run = subprocess.run(['docker', 'exec', '-i', 'ouf-postgres', *args],
                         stdin=stdin, stdout=stdout or subprocess.DEVNULL,
                         stderr=subprocess.DEVNULL, check=False)
    if run.returncode:
        raise ValueError('POSTGRES_COMMAND_FAILED')


def backup(name: str, user: str) -> Path:
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    destination = DIRECTORY / f'r4a-onboarding-{stamp}-{secrets.token_hex(4)}.dump'
    descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        with os.fdopen(descriptor, 'wb') as stream:
            command('pg_dump', '-U', user, '-d', name, '-Fc', '-n', SCHEMA, stdout=stream)
            stream.flush()
            os.fsync(stream.fileno())
    except BaseException:
        destination.unlink(missing_ok=True)
        raise
    if destination.stat().st_size < 1024:
        destination.unlink(missing_ok=True)
        raise ValueError('DATABASE_DUMP_TOO_SMALL')
    return destination


def restore_probe(archive: Path, user: str) -> None:
    scratch = 'ouf_r4a_restore_' + secrets.token_hex(5)
    with archive.open('rb') as stream:
        command('pg_restore', '--list', stdin=stream)
    command('createdb', '-U', user, scratch)
    try:
        with archive.open('rb') as stream:
            command('pg_restore', '-U', user, '--no-owner', '--no-acl', '-d', scratch, stdin=stream)
        sql = f"select case when count(*) > 0 then 1 else 0 end from pg_class c join pg_namespace n on c.relnamespace=n.oid where n.nspname='{SCHEMA}' and c.relkind='r';"
        result = subprocess.run(['docker', 'exec', '-i', 'ouf-postgres', 'psql', '-U', user, '-d', scratch,
                                 '-Atc', sql], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=False)
        if result.returncode or result.stdout.strip() != b'1':
            raise ValueError('RESTORE_SCHEMA_EMPTY')
    finally:
        command('dropdb', '-U', user, scratch)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('plan', 'apply'))
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise ValueError('ROOT_REQUIRED')
    st = DIRECTORY.lstat()
    if not stat.S_ISDIR(st.st_mode) or st.st_uid != 0 or stat.S_IMODE(st.st_mode) != 0o700:
        raise ValueError('BACKUP_DIRECTORY_UNSAFE')
    app = inspect('ouf-onboarding')
    pg = inspect('ouf-postgres')
    db, user = parameters(app, pg)
    print('MODE=' + args.mode)
    print('DATABASE_TARGET_MATCH=true')
    print('PRODUCTION_CONTAINERS_RUNNING=true')
    if args.mode == 'plan':
        print('NO_WRITES=true')
        return
    archive = backup(db, user)
    try:
        restore_probe(archive, user)
    except BaseException:
        # Preserve the dump for diagnosis and recovery if the restore fails.
        print('DUMP_SAVED=' + str(archive))
        raise
    print('DUMP_SAVED=' + str(archive))
    print('SCHEMA_RESTORE_TO_SCRATCH=PASS')
    print('PRODUCTION_DB_UNCHANGED=true')
    print('SECRETS_PRINTED=false')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as exc:
        print('DB_BACKUP_BLOCKED=' + (str(exc) if isinstance(exc, ValueError) else type(exc).__name__), file=sys.stderr)
        raise SystemExit(1)
