#!/usr/bin/env python3
"""Swap verified R4a candidate into the live name, with container rollback.

Requires a tested private DB dump and an empty managed-file bucket. Never
restores the database automatically or deletes the stopped original container.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone

ROOT = Path('/etc/ouf/deploy-snapshots')
SNAPSHOT = ROOT / 'r4a-before-staging.docker-inspect.json'
STATE = ROOT / 'r4a-onboarding-rollout.json'
OLD = 'ouf-onboarding-pre-r4a-e6b7647'
LIVE = 'ouf-onboarding'
SMOKE = 'ouf-onboarding-r4a-smoke'
CANDIDATE = 'ouf-onboarding-r4a-candidate'
IMAGE = 'ouf-onboarding:r4a-e6b7647'
REVISION = 'e6b7647abb983db5cae1365730b891a4dc46797e'


def docker(*args: str, allow_missing: bool = False) -> bytes | None:
    result = subprocess.run(['docker', *args], stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL, check=False)
    if result.returncode:
        if allow_missing:
            return None
        raise ValueError('DOCKER_' + args[0].upper().replace('-', '_') + '_FAILED')
    return result.stdout


def inspect(name: str, allow_missing: bool = False) -> dict | None:
    raw = docker('inspect', name, allow_missing=allow_missing)
    return json.loads(raw)[0] if raw is not None else None


def private(path: Path, kind: int, mode: int) -> None:
    meta = path.lstat()
    if meta.st_uid != 0 or stat.S_IFMT(meta.st_mode) != kind or stat.S_IMODE(meta.st_mode) != mode:
        raise ValueError('PRIVATE_PATH_UNSAFE')


def environment(doc: dict) -> dict[str, str]:
    values = {}
    for line in doc['Config'].get('Env') or []:
        key, sep, val = line.partition('=')
        if not sep or not key.isidentifier() or '\r' in line or '\n' in line or key in values:
            raise ValueError('CANDIDATE_ENV_UNSAFE')
        values[key] = val
    return values


def preflight(archive: Path) -> tuple[dict, dict, dict, str]:
    if os.geteuid() != 0:
        raise ValueError('ROOT_REQUIRED')
    private(ROOT, stat.S_IFDIR, 0o700)
    private(SNAPSHOT, stat.S_IFREG, 0o600)
    if archive.parent != ROOT or archive.is_symlink():
        raise ValueError('BACKUP_PATH_UNEXPECTED')
    private(archive, stat.S_IFREG, 0o600)
    if archive.stat().st_size < 1024:
        raise ValueError('BACKUP_TOO_SMALL')
    if STATE.exists() or inspect(OLD, allow_missing=True):
        raise ValueError('ROLLOUT_ALREADY_PRESENT')
    previous = {d['Name'].removeprefix('/'): d for d in json.loads(SNAPSHOT.read_bytes())}
    live = inspect(LIVE)
    smoke = inspect(SMOKE)
    candidate = inspect(CANDIDATE)
    image = inspect(IMAGE)
    image_id = image['Id']
    if (not image_id.startswith('sha256:') or
            image['Config'].get('Labels', {}).get('org.opencontainers.image.revision') != REVISION):
        raise ValueError('IMAGE_REVISION_MISMATCH')
    if (live['Id'] != previous[LIVE]['Id'] or smoke['Id'] != previous[SMOKE]['Id'] or
            not live['State']['Running'] or not smoke['State']['Running'] or
            candidate['Image'] != image_id or candidate['State']['Status'] != 'created'):
        raise ValueError('RUNTIME_STATE_CHANGED')
    if live['HostConfig']['RestartPolicy']['Name'] != 'unless-stopped' or smoke['HostConfig']['RestartPolicy']['Name'] != 'no':
        raise ValueError('RESTART_POLICY_UNEXPECTED')
    expected = {m['Destination']: (m['Source'], m['RW']) for m in candidate['Mounts']}
    if (len(expected) != 5 or any(rw for _, rw in expected.values()) or
            candidate['HostConfig']['NetworkMode'] != 'ouf-backend' or
            candidate['HostConfig']['RestartPolicy']['Name'] != 'no' or
            candidate['Config']['User'] != '10003:10003'):
        raise ValueError('CANDIDATE_CONFIG_CHANGED')
    env = environment(candidate)
    if not env.get('OUF_ONBOARDING_STAGING_BUCKET') == 'ouf-managed-files':
        raise ValueError('STAGING_ENV_MISSING')
    return live, smoke, candidate, image_id


def create_from(candidate: dict, image_id: str) -> None:
    fd, path = tempfile.mkstemp(prefix='.r4a-rollout-env-', dir=ROOT)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as stream:
            for key, value in environment(candidate).items():
                stream.write(key + '=' + value + '\n')
            stream.flush()
            os.fsync(stream.fileno())
        args = ['create', '--name', LIVE, '--network', 'ouf-backend',
                '--network-alias', LIVE,
                '--user', '10003:10003', '--restart', 'no', '--env-file', path]
        for mount in candidate['Mounts']:
            args += ['--mount', f"type=bind,source={mount['Source']},target={mount['Destination']},readonly"]
        docker(*args, image_id)
    finally:
        Path(path).unlink(missing_ok=True)
    current = inspect(LIVE)
    if (current['Image'] != image_id or environment(current) != environment(candidate) or
            {m['Destination']: (m['Source'], m['RW']) for m in current['Mounts']} !=
            {m['Destination']: (m['Source'], m['RW']) for m in candidate['Mounts']}):
        raise ValueError('NEW_CONTAINER_CONFIG_MISMATCH')


def readiness(name: str) -> None:
    for _ in range(30):
        doc = inspect(name)
        if not doc['State']['Running']:
            raise ValueError('CONTAINER_NOT_RUNNING')
        pid = doc['State']['Pid']
        if not isinstance(pid, int) or pid <= 0:
            raise ValueError('CONTAINER_PID_INVALID')
        result = subprocess.run(['nsenter', '-t', str(pid), '-n', 'python3', '-c',
                                 'import urllib.request; r=urllib.request.urlopen("http://127.0.0.1:8080/actuator/health/readiness",timeout=3); assert r.status==200'],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
        if result.returncode == 0:
            return
        time.sleep(2)
    raise ValueError('READINESS_TIMEOUT')


def write_state(old: dict, smoke: dict, archive: Path) -> None:
    fd = os.open(STATE, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'w') as stream:
        json.dump({'old_id': old['Id'], 'smoke_id': smoke['Id'], 'backup_name': OLD,
                   'db_dump': str(archive), 'phase': 'prepared'}, stream)
        stream.write('\n')
        stream.flush()
        os.fsync(stream.fileno())


def save_failed_logs() -> None:
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    path = ROOT / ('r4a-onboarding-failed-' + stamp + '.log')
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'wb') as stream:
        subprocess.run(['docker', 'logs', '--tail', '120', LIVE], stdout=stream,
                       stderr=stream, check=False)
        stream.flush()
        os.fsync(stream.fileno())
    # Logs can contain application data: expose only their private path.
    print('FAILED_CONTAINER_LOG_SAVED=' + str(path), file=sys.stderr)


def restore_old(old_id: str, smoke_id: str) -> None:
    new = inspect(LIVE, allow_missing=True)
    if new and new['Id'] != old_id:
        try:
            save_failed_logs()
        except (OSError, ValueError):
            print('FAILED_CONTAINER_LOG_SAVE_FAILED=true', file=sys.stderr)
        docker('update', '--restart', 'no', LIVE)
        if new['State']['Running']:
            docker('stop', LIVE)
        docker('rm', LIVE)
    backup = inspect(OLD, allow_missing=True)
    if backup:
        if backup['Id'] != old_id:
            raise ValueError('ROLLBACK_ID_MISMATCH')
        docker('rename', OLD, LIVE)
    restored = inspect(LIVE)
    if restored['Id'] != old_id:
        raise ValueError('ROLLBACK_ORIGINAL_MISSING')
    docker('update', '--restart', 'unless-stopped', LIVE)
    if not restored['State']['Running']:
        docker('start', LIVE)
    readiness(LIVE)
    smoke = inspect(SMOKE)
    if smoke['Id'] != smoke_id:
        raise ValueError('ROLLBACK_SMOKE_MISMATCH')
    if not smoke['State']['Running']:
        docker('start', SMOKE)
    readiness(SMOKE)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('plan', 'apply', 'rollback'))
    parser.add_argument('--db-dump', type=Path)
    args = parser.parse_args()
    if args.mode == 'rollback':
        private(STATE, stat.S_IFREG, 0o600)
        state = json.loads(STATE.read_text())
        restore_old(state['old_id'], state['smoke_id'])
        print('CONTAINER_ROLLBACK=PASS')
        print('DB_RESTORE_NOT_PERFORMED=true')
        return
    if args.db_dump is None:
        raise ValueError('DB_DUMP_REQUIRED')
    old, smoke, candidate, image_id = preflight(args.db_dump)
    print('MODE=' + args.mode)
    print('ORIGINAL_AND_SMOKE_RUNNING=true')
    print('CANDIDATE_READY_TO_SWAP=true')
    print('DB_BACKUP_PRESENT=true')
    if args.mode == 'plan':
        print('NO_WRITES=true')
        return
    write_state(old, smoke, args.db_dump)
    try:
        docker('stop', SMOKE)
        docker('update', '--restart', 'no', LIVE)
        docker('stop', LIVE)
        docker('rename', LIVE, OLD)
        create_from(candidate, image_id)
        docker('start', LIVE)
        readiness(LIVE)
        new = inspect(LIVE)
        aliases = new['NetworkSettings']['Networks']['ouf-backend'].get('Aliases') or []
        if LIVE not in aliases:
            raise ValueError('NETWORK_ALIAS_MISSING')
        docker('update', '--restart', 'unless-stopped', LIVE)
        print('LIVE_IMAGE_MATCH=' + str(new['Image'] == image_id).lower())
        print('LIVE_READINESS=PASS')
        print('ORIGINAL_ROLLBACK_CONTAINER=' + OLD)
        print('SMOKE_CONTAINER_STOPPED=true')
        print('DB_RESTORE_NOT_PERFORMED=true')
    except BaseException:
        try:
            restore_old(old['Id'], smoke['Id'])
            print('AUTO_CONTAINER_ROLLBACK=PASS', file=sys.stderr)
        except BaseException:
            print('AUTO_CONTAINER_ROLLBACK=FAILED_MANUAL_RECOVERY_REQUIRED', file=sys.stderr)
        raise


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as exc:
        print('ROLLOUT_BLOCKED=' + (str(exc) if isinstance(exc, ValueError) else type(exc).__name__), file=sys.stderr)
        raise SystemExit(1)
