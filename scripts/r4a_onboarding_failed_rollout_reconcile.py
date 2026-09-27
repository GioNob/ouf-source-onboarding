#!/usr/bin/env python3
"""Retire only the stopped candidate and state of a rolled-back R4a attempt.

Keeps the failed attempt record and DB dump; never stops, replaces, or
removes production or smoke. Docker inspect values stay in memory.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import stat
import subprocess
import sys

ROOT = Path('/etc/ouf/deploy-snapshots')
STATE = ROOT / 'r4a-onboarding-rollout.json'
ARCHIVE = ROOT / 'r4a-onboarding-rollout-failed-e0509e8.json'
SNAPSHOT = ROOT / 'r4a-before-staging.docker-inspect.json'
OLD = 'ouf-onboarding-pre-r4a-e0509e8'
CANDIDATE = 'ouf-onboarding-r4a-candidate'
FAILED_IMAGE_ID = 'sha256:8ca287241c4dd7753fe23a300c1b5764aab9485021efd626f5db6fee8356f920'


def private(path: Path, kind: int, mode: int) -> None:
    meta = path.lstat()
    if meta.st_uid != 0 or stat.S_IFMT(meta.st_mode) != kind or stat.S_IMODE(meta.st_mode) != mode:
        raise ValueError('PRIVATE_PATH_UNSAFE')


def inspect(name: str, missing_ok: bool = False) -> dict | None:
    call = subprocess.run(['docker', 'inspect', name], stdout=subprocess.PIPE,
                          stderr=subprocess.DEVNULL, check=False)
    if call.returncode:
        if missing_ok:
            return None
        raise ValueError('DOCKER_INSPECT_FAILED')
    docs = json.loads(call.stdout)
    if len(docs) != 1:
        raise ValueError('DOCKER_INSPECT_UNEXPECTED')
    return docs[0]


def preflight() -> bool:
    if os.geteuid() != 0:
        raise ValueError('ROOT_REQUIRED')
    private(ROOT, stat.S_IFDIR, 0o700)
    private(STATE, stat.S_IFREG, 0o600)
    private(SNAPSHOT, stat.S_IFREG, 0o600)
    if ARCHIVE.exists() or ARCHIVE.is_symlink():
        raise ValueError('ARCHIVED_STATE_ALREADY_EXISTS')
    state = json.loads(STATE.read_bytes())
    previous = {d['Name'].removeprefix('/'): d for d in json.loads(SNAPSHOT.read_bytes())}
    live = inspect('ouf-onboarding')
    smoke = inspect('ouf-onboarding-r4a-smoke')
    candidate = inspect(CANDIDATE, missing_ok=True)
    if (live['Id'] != previous['ouf-onboarding']['Id'] or
            smoke['Id'] != previous['ouf-onboarding-r4a-smoke']['Id'] or
            not live['State']['Running'] or not smoke['State']['Running'] or
            state['old_id'] != live['Id'] or state['smoke_id'] != smoke['Id'] or
            state['backup_name'] != OLD or inspect(OLD, missing_ok=True) is not None):
        raise ValueError('ROLLED_BACK_RUNTIME_MISMATCH')
    dump = Path(state['db_dump'])
    if dump.parent != ROOT or dump.is_symlink():
        raise ValueError('BACKUP_PATH_INVALID')
    private(dump, stat.S_IFREG, 0o600)
    if candidate is not None and (candidate['State']['Status'] != 'created' or
                                  candidate['Image'] != FAILED_IMAGE_ID):
        raise ValueError('CANDIDATE_NOT_INERT')
    return candidate is not None


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('plan', 'apply'))
    args = parser.parse_args()
    present = preflight()
    print('MODE=' + args.mode)
    print('ORIGINAL_AND_SMOKE_RUNNING=true')
    print('FAILED_CANDIDATE_STOPPED=' + str(present).lower())
    print('DB_DUMP_PRESERVED=true')
    if args.mode == 'plan':
        print('NO_WRITES=true')
        return
    if present:
        run = subprocess.run(['docker', 'rm', CANDIDATE], stdout=subprocess.DEVNULL,
                             stderr=subprocess.DEVNULL, check=False)
        if run.returncode:
            raise ValueError('CANDIDATE_REMOVE_FAILED')
    os.rename(STATE, ARCHIVE)
    fd = os.open(ROOT, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)
    print('FAILED_CANDIDATE_REMOVED=true')
    print('FAILED_ATTEMPT_ARCHIVED=' + str(ARCHIVE))
    print('ORIGINAL_CONTAINERS_UNCHANGED=true')
    print('NO_DATABASE_CHANGES=true')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as exc:
        print('ROLLOUT_RECONCILE_BLOCKED=' + (str(exc) if isinstance(exc, ValueError) else type(exc).__name__), file=sys.stderr)
        raise SystemExit(1)
