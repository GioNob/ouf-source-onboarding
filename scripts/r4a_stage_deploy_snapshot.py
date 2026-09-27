#!/usr/bin/env python3
"""Private, immutable Docker configuration snapshot before R4a staging deploy.

The saved inspect JSON can contain credentials. Never print it or copy it to
the repository. This is a configuration snapshot, not a database/object backup.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import sys

NAMES = ('ouf-onboarding', 'ouf-onboarding-r4a-smoke', 'ouf-minio')
PARENT = Path('/etc/ouf')
DIRECTORY = PARENT / 'deploy-snapshots'
TARGET = DIRECTORY / 'r4a-before-staging.docker-inspect.json'


def private_dir(path: Path, owner_uid: int = 0) -> bool:
    if not path.exists() and not path.is_symlink():
        return False
    meta = path.lstat()
    if not stat.S_ISDIR(meta.st_mode) or meta.st_uid != owner_uid or stat.S_IMODE(meta.st_mode) != 0o700:
        raise ValueError('SNAPSHOT_DIRECTORY_UNSAFE')
    return True


def existing(path: Path, owner_uid: int = 0) -> bytes | None:
    if not path.exists() and not path.is_symlink():
        return None
    meta = path.lstat()
    if not stat.S_ISREG(meta.st_mode) or meta.st_uid != owner_uid or stat.S_IMODE(meta.st_mode) != 0o600:
        raise ValueError('SNAPSHOT_FILE_UNSAFE')
    return path.read_bytes()


def inspect_containers() -> bytes:
    result = subprocess.run(['docker', 'inspect', *NAMES], stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL, check=False)
    if result.returncode:
        raise ValueError('DOCKER_INSPECT_FAILED')
    try:
        docs = json.loads(result.stdout)
        if not isinstance(docs, list) or len(docs) != len(NAMES) or {
            doc['Name'].removeprefix('/') for doc in docs
        } != set(NAMES):
            raise ValueError('DOCKER_INSPECT_UNEXPECTED')
    except (TypeError, KeyError, json.JSONDecodeError) as exc:
        raise ValueError('DOCKER_INSPECT_UNEXPECTED') from exc
    return json.dumps(docs, sort_keys=True, separators=(',', ':')).encode() + b'\n'


def execute(mode: str, parent: Path = PARENT, directory: Path = DIRECTORY,
            target: Path = TARGET, owner_uid: int = 0) -> dict[str, str]:
    if os.geteuid() != 0:
        raise ValueError('ROOT_REQUIRED')
    parent_meta = parent.lstat()
    if not stat.S_ISDIR(parent_meta.st_mode) or parent_meta.st_uid != owner_uid:
        raise ValueError('SNAPSHOT_PARENT_UNSAFE')
    if mode == 'apply' and not private_dir(directory, owner_uid):
        directory.mkdir(mode=0o700)
    else:
        private_dir(directory, owner_uid)
    saved = existing(target, owner_uid)
    current = inspect_containers() if mode != 'verify' else None
    if mode == 'verify' and saved is None:
        raise ValueError('SNAPSHOT_MISSING')
    if mode == 'apply' and saved is None:
        fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, 'wb') as stream:
            stream.write(current)
            stream.flush()
            os.fsync(stream.fileno())
        saved = existing(target, owner_uid)
    if saved is not None:
        try:
            docs = json.loads(saved)
            if len(docs) != len(NAMES) or {d['Name'].removeprefix('/') for d in docs} != set(NAMES):
                raise ValueError('SNAPSHOT_INVALID')
        except (TypeError, KeyError, json.JSONDecodeError) as exc:
            raise ValueError('SNAPSHOT_INVALID') from exc
    match = saved == current if current is not None and saved is not None else None
    if mode == 'apply' and not match:
        raise ValueError('SNAPSHOT_DIFFERS_FROM_CURRENT')
    return {
        'MODE': mode,
        'CONTAINERS': str(len(NAMES)),
        'SNAPSHOT_EXISTS': str(saved is not None).lower(),
        'SNAPSHOT_MATCHES_CURRENT': 'unknown' if match is None else str(match).lower(),
        'SNAPSHOT_SHA256': hashlib.sha256(saved).hexdigest() if saved is not None else 'none',
        'NO_WRITES': str(mode != 'apply').lower(),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('plan', 'apply', 'verify'))
    args = parser.parse_args()
    try:
        for key, value in execute(args.mode).items():
            print(f'{key}={value}')
    except (OSError, ValueError) as exc:
        code = str(exc) if isinstance(exc, ValueError) else type(exc).__name__
        print(f'SNAPSHOT_BLOCKED={code}', file=sys.stderr)
        raise SystemExit(1)


if __name__ == '__main__':
    main()
