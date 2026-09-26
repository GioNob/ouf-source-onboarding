#!/usr/bin/env python3
"""Prepare an inert R4a Onboarding container with the existing private runtime env.

Only `prepare` writes: it creates a stopped Docker container. It never starts,
stops, renames or removes an existing container. Neither secrets nor the
snapshot are copied into logs or command arguments.
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

SNAPSHOT = Path('/etc/ouf/deploy-snapshots/r4a-before-staging.docker-inspect.json')
IMAGE = 'ouf-onboarding:r4a-e0509e8'
REVISION = 'e0509e8d48146d20d2134eb27c8b1a40be6c9141'
IMAGE_ID = 'sha256:8ca287241c4dd7753fe23a300c1b5764aab9485021efd626f5db6fee8356f920'
CANDIDATE = 'ouf-onboarding-r4a-candidate'
EXTRA_MOUNTS = (
    ('/etc/ouf/secrets/onboarding-minio-access-key', '/run/secrets/onboarding-minio-access-key'),
    ('/etc/ouf/secrets/onboarding-minio-secret-key', '/run/secrets/onboarding-minio-secret-key'),
    ('/run/ouf-onboarding-auth', '/run/ouf-onboarding-auth'),
)
EXTRA_ENV = {
    'OUF_ONBOARDING_STAGING_ENDPOINT': 'http://ouf-minio:9000',
    'OUF_ONBOARDING_STAGING_BUCKET': 'ouf-managed-files',
    'OUF_ONBOARDING_STAGING_ACCESS_KEY_FILE': EXTRA_MOUNTS[0][1],
    'OUF_ONBOARDING_STAGING_SECRET_KEY_FILE': EXTRA_MOUNTS[1][1],
    'OUF_ONBOARDING_OBJECT_STORE_GATEWAY_BASE_URL': 'http://ouf-apisix:9080',
    'OUF_ONBOARDING_OBJECT_STORE_TOKEN_FILE': '/run/ouf-onboarding-auth/token',
}
EXPECTED_ORIGINAL_MOUNTS = {
    '/run/secrets/authorization-owner-key': '/opt/ouf/secrets/authorization-owner-key',
    '/run/secrets/onboarding-ths.yaml': '/opt/ouf/secrets/onboarding-ths.yaml',
}


def docker(*args: str, missing_ok: bool = False) -> list[dict] | None:
    result = subprocess.run(['docker', 'inspect', *args], stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL, check=False)
    if result.returncode:
        if missing_ok:
            return None
        raise ValueError('DOCKER_INSPECT_FAILED')
    docs = json.loads(result.stdout)
    if not isinstance(docs, list) or len(docs) != len(args):
        raise ValueError('DOCKER_INSPECT_UNEXPECTED')
    return docs


def private(path: Path, kind: int, uid: int, gid: int, mode: int) -> None:
    meta = path.lstat()
    if (stat.S_IFMT(meta.st_mode), meta.st_uid, meta.st_gid, stat.S_IMODE(meta.st_mode)) != (kind, uid, gid, mode):
        raise ValueError('PRIVATE_PATH_UNSAFE')


def mounts(doc: dict) -> dict[str, dict]:
    return {m['Destination']: m for m in doc.get('Mounts') or []}


def env(doc: dict) -> dict[str, str]:
    values = {}
    for item in doc['Config'].get('Env') or []:
        key, sep, value = item.partition('=')
        if not sep or not key.isidentifier() or '\n' in item or '\r' in item or key in values:
            raise ValueError('ENV_CANNOT_BE_SAFELY_REPLAYED')
        values[key] = value
    return values


def preflight() -> tuple[dict, dict[str, str], bool]:
    if os.geteuid() != 0:
        raise ValueError('ROOT_REQUIRED')
    private(SNAPSHOT.parent, stat.S_IFDIR, 0, 0, 0o700)
    private(SNAPSHOT, stat.S_IFREG, 0, 0, 0o600)
    previous = json.loads(SNAPSHOT.read_bytes())
    saved = next((d for d in previous if d['Name'] == '/ouf-onboarding'), None)
    if saved is None:
        raise ValueError('SNAPSHOT_CONTAINER_MISSING')
    live = docker('ouf-onboarding')[0]
    if saved['Id'] != live['Id'] or not live['State']['Running']:
        raise ValueError('ORIGINAL_CHANGED_OR_STOPPED')
    image = docker(IMAGE)[0]
    if image['Id'] != IMAGE_ID or image['Config'].get('Labels', {}).get('org.opencontainers.image.revision') != REVISION:
        raise ValueError('CANDIDATE_IMAGE_MISMATCH')
    if (live['Config'].get('User') != '10003:10003' or
            image['Config'].get('User') != '10003:10003' or
            image['Config'].get('Entrypoint') != live['Config'].get('Entrypoint') or
            image['Config'].get('Cmd') != live['Config'].get('Cmd') or
            image['Config'].get('WorkingDir') != live['Config'].get('WorkingDir')):
        raise ValueError('IMAGE_STARTUP_DRIFT')
    host = live['HostConfig']
    if (host['NetworkMode'] != 'ouf-backend' or
            any(host.get(k) for k in ('ReadonlyRootfs', 'Privileged', 'CapAdd', 'CapDrop',
                                       'SecurityOpt', 'Tmpfs', 'Memory', 'NanoCpus', 'Devices',
                                       'ExtraHosts', 'Dns', 'PortBindings')) or
            live['Config'].get('Healthcheck') or live['Config'].get('Volumes')):
        raise ValueError('HOST_CONFIG_NEEDS_REVIEW')
    existing = mounts(live)
    if (set(existing) != set(EXPECTED_ORIGINAL_MOUNTS) or
            any(m['Source'] != EXPECTED_ORIGINAL_MOUNTS[dst] or m['RW'] or m['Type'] != 'bind'
                for dst, m in existing.items())):
        raise ValueError('ORIGINAL_MOUNTS_NEED_REVIEW')
    private(Path('/etc/ouf/secrets'), stat.S_IFDIR, 0, 0, 0o700)
    private(Path(EXTRA_MOUNTS[0][0]), stat.S_IFREG, 0, 10003, 0o440)
    private(Path(EXTRA_MOUNTS[1][0]), stat.S_IFREG, 0, 10003, 0o440)
    private(Path(EXTRA_MOUNTS[2][0]), stat.S_IFDIR, 0, 10003, 0o750)
    private(Path(EXTRA_MOUNTS[2][0]) / 'token', stat.S_IFREG, 0, 10003, 0o440)
    original_env = env(live)
    if set(original_env) & set(EXTRA_ENV):
        raise ValueError('STAGING_ENV_ALREADY_PRESENT')
    return live, original_env, docker(CANDIDATE, missing_ok=True) is not None


def verify(candidate: dict, original: dict, original_env: dict[str, str]) -> None:
    if (candidate['Name'] != '/' + CANDIDATE or candidate['State']['Status'] != 'created' or
            candidate['Image'] != IMAGE_ID or candidate['Config']['User'] != '10003:10003' or
            candidate['HostConfig']['NetworkMode'] != 'ouf-backend' or
            candidate['HostConfig']['RestartPolicy']['Name'] != 'no'):
        raise ValueError('CANDIDATE_CONFIGURATION_MISMATCH')
    expected_mounts = {**EXPECTED_ORIGINAL_MOUNTS, **{dst: src for src, dst in EXTRA_MOUNTS}}
    current_mounts = mounts(candidate)
    if (set(current_mounts) != set(expected_mounts) or
            any(m['Source'] != expected_mounts[dst] or m['RW'] or m['Type'] != 'bind'
                for dst, m in current_mounts.items())):
        raise ValueError('CANDIDATE_MOUNTS_MISMATCH')
    if env(candidate) != {**original_env, **EXTRA_ENV}:
        raise ValueError('CANDIDATE_ENV_MISMATCH')
    if docker('ouf-onboarding')[0]['Id'] != original['Id']:
        raise ValueError('ORIGINAL_CHANGED')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('plan', 'prepare', 'verify'))
    args = parser.parse_args()
    original, original_env, exists = preflight()
    print('MODE=' + args.mode)
    print('IMAGE_REVISION_MATCH=true')
    print('ORIGINAL_CONTAINER_UNCHANGED=true')
    print('CANDIDATE_EXISTS=' + str(exists).lower())
    if args.mode == 'plan':
        print('NO_WRITES=true')
        return
    if args.mode == 'prepare' and not exists:
        # Docker --env-file reads these values without exposing them in argv.
        # The private file is immediately deleted, including on Docker error.
        fd, name = tempfile.mkstemp(prefix='.r4a-candidate-env-', dir=SNAPSHOT.parent)
        try:
            with os.fdopen(fd, 'w', encoding='utf-8') as stream:
                for key, value in {**original_env, **EXTRA_ENV}.items():
                    stream.write(key + '=' + value + '\n')
                stream.flush()
                os.fsync(stream.fileno())
            opts = ['docker', 'create', '--name', CANDIDATE, '--network', 'ouf-backend',
                    '--user', '10003:10003', '--restart', 'no', '--env-file', name]
            for dst, src in EXPECTED_ORIGINAL_MOUNTS.items():
                opts += ['--mount', f'type=bind,source={src},target={dst},readonly']
            for src, dst in EXTRA_MOUNTS:
                opts += ['--mount', f'type=bind,source={src},target={dst},readonly']
            result = subprocess.run(opts + [IMAGE], stdout=subprocess.DEVNULL,
                                    stderr=subprocess.DEVNULL, check=False)
            if result.returncode:
                raise ValueError('CANDIDATE_CREATE_FAILED')
        finally:
            Path(name).unlink(missing_ok=True)
    candidate = docker(CANDIDATE, missing_ok=True)
    if candidate is None:
        raise ValueError('CANDIDATE_NOT_FOUND')
    verify(candidate[0], original, original_env)
    print('CANDIDATE_STOPPED=true')
    print('CANDIDATE_CONFIG_VERIFIED=true')
    print('ORIGINAL_CONTAINER_RUNNING=true')
    print('SECRET_VALUES_NOT_PRINTED=true')
    print('NO_START_OR_SWAP=true')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as exc:
        print('CANDIDATE_BLOCKED=' + (str(exc) if isinstance(exc, ValueError) else type(exc).__name__), file=sys.stderr)
        raise SystemExit(1)
