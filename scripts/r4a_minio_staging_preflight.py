#!/usr/bin/env python3
"""Read-only MinIO staging prerequisites; never print Docker env values."""
from __future__ import annotations

import json
import shutil
import subprocess
import sys


def docker(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(['docker', *args], capture_output=True, text=True, check=False)


def main() -> None:
    result = docker('inspect', 'ouf-minio')
    if result.returncode:
        raise ValueError('MINIO_INSPECT_FAILED')
    values = json.loads(result.stdout)
    if len(values) != 1 or values[0].get('Name') != '/ouf-minio':
        raise ValueError('MINIO_IDENTITY_MISMATCH')
    doc = values[0]
    env_names = {entry.split('=', 1)[0] for entry in doc['Config']['Env']}
    mounts = {entry['Destination'] for entry in doc['Mounts']}
    images = docker('images', '--format', '{{.Repository}}:{{.Tag}}')
    if images.returncode:
        raise ValueError('DOCKER_IMAGE_INVENTORY_FAILED')
    mc_images = sorted(image for image in images.stdout.splitlines()
                       if image.startswith(('quay.io/minio/mc:', 'minio/mc:')))
    in_container = docker('exec', 'ouf-minio', 'mc', '--version')
    print('MINIO_RUNNING=' + str(doc['State']['Running']).lower())
    print('BACKEND_NETWORK=' + str('ouf-backend' in doc['NetworkSettings']['Networks']).lower())
    print('DATA_MOUNTED=' + str('/data' in mounts).lower())
    print('ADMIN_PASSWORD_FILE_MOUNTED=' + str('/run/secrets/minio_password' in mounts).lower())
    print('ROOT_USER_SOURCE=' + (','.join(sorted(env_names & {
        'MINIO_ROOT_USER', 'MINIO_ROOT_USER_FILE', 'MINIO_ACCESS_KEY'})) or 'NONE'))
    print('ROOT_PASSWORD_SOURCE=' + (','.join(sorted(env_names & {
        'MINIO_ROOT_PASSWORD_FILE', 'MINIO_ROOT_PASSWORD', 'MINIO_SECRET_KEY'})) or 'NONE'))
    print('HOST_MC_AVAILABLE=' + str(shutil.which('mc') is not None).lower())
    print('CONTAINER_MC_AVAILABLE=' + str(in_container.returncode == 0).lower())
    print('CACHED_MC_IMAGES=' + (','.join(mc_images) or 'NONE'))
    print('NO_WRITES=true')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as exc:
        code = str(exc) if isinstance(exc, ValueError) else type(exc).__name__
        print('MINIO_PREFLIGHT_BLOCKED=' + code, file=sys.stderr)
        raise SystemExit(1)
