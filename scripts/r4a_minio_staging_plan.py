#!/usr/bin/env python3
"""Read-only MinIO staging bucket/user/policy plan with an ephemeral mc alias.

The existing MinIO admin credential is read only inside ouf-minio. The
temporary mc configuration is removed before the command exits. No credential
value, alias listing or raw mc error is printed to the caller.
"""
from __future__ import annotations

import os
from pathlib import Path
import stat
import subprocess
import sys

BUCKET = 'ouf-managed-files'
USER = 'ouf-onboarding-staging'
POLICY = 'ouf-onboarding-managed-files-v1'
SECRET_DIR = Path('/etc/ouf/secrets')
ACCESS_FILE = SECRET_DIR / 'onboarding-minio-access-key'
SECRET_FILE = SECRET_DIR / 'onboarding-minio-secret-key'

SHELL = r'''set -eu
umask 077
cfg="$(mktemp -d /tmp/ouf-r4a-mc.XXXXXX)"
trap 'rm -rf "$cfg"' EXIT
if [ -n "${MINIO_ROOT_USER_FILE:-}" ] && [ -r "$MINIO_ROOT_USER_FILE" ]; then
    root_user="$(cat "$MINIO_ROOT_USER_FILE")"
else
    root_user="${MINIO_ROOT_USER:-}"
fi
test -n "$root_user" || exit 31
test -n "${MINIO_ROOT_PASSWORD_FILE:-}" || exit 32
test -r "$MINIO_ROOT_PASSWORD_FILE" || exit 33
root_password="$(cat "$MINIO_ROOT_PASSWORD_FILE")"
test -n "$root_password" || exit 34
mc --config-dir "$cfg" alias set r4a http://127.0.0.1:9000 "$root_user" "$root_password" >/dev/null 2>&1 || exit 35
unset root_password
mc --config-dir "$cfg" admin info r4a >/dev/null 2>&1 || exit 36
echo ADMIN_ALIAS_OK=true
if mc --config-dir "$cfg" stat r4a/ouf-managed-files >/dev/null 2>&1; then
    echo BUCKET_EXISTS=true
else
    echo BUCKET_EXISTS=false
fi
if mc --config-dir "$cfg" admin user info r4a ouf-onboarding-staging >/dev/null 2>&1; then
    echo USER_EXISTS=true
else
    echo USER_EXISTS=false
fi
if mc --config-dir "$cfg" admin policy info r4a ouf-onboarding-managed-files-v1 >/dev/null 2>&1; then
    echo POLICY_EXISTS=true
else
    echo POLICY_EXISTS=false
fi
'''


def file_status(path: Path) -> str:
    if not path.exists() and not path.is_symlink():
        return 'absent'
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_gid != 10003 or stat.S_IMODE(info.st_mode) != 0o440:
        return 'unsafe'
    return 'present'


def main() -> None:
    if os.geteuid() != 0:
        raise ValueError('ROOT_REQUIRED')
    directory = SECRET_DIR.lstat()
    if not stat.S_ISDIR(directory.st_mode) or directory.st_uid != 0 or stat.S_IMODE(directory.st_mode) != 0o700:
        raise ValueError('SECRET_DIRECTORY_UNSAFE')
    access, secret = file_status(ACCESS_FILE), file_status(SECRET_FILE)
    if 'unsafe' in (access, secret) or (access == 'present') != (secret == 'present'):
        raise ValueError('STAGING_CREDENTIAL_FILES_UNSAFE_OR_PARTIAL')
    result = subprocess.run(['docker', 'exec', '-i', 'ouf-minio', 'sh', '-s'],
                            input=SHELL, capture_output=True, text=True, check=False)
    if result.returncode:
        raise ValueError('MINIO_ADMIN_PROBE_FAILED_' + str(result.returncode))
    rows = dict(row.split('=', 1) for row in result.stdout.splitlines() if '=' in row)
    if set(rows) != {'ADMIN_ALIAS_OK', 'BUCKET_EXISTS', 'USER_EXISTS', 'POLICY_EXISTS'}:
        raise ValueError('MINIO_PROBE_UNEXPECTED_RESPONSE')
    if rows['ADMIN_ALIAS_OK'] != 'true' or any(rows[key] not in ('true', 'false') for key in
                                          ('BUCKET_EXISTS', 'USER_EXISTS', 'POLICY_EXISTS')):
        raise ValueError('MINIO_PROBE_UNEXPECTED_RESPONSE')
    print('MODE=plan')
    for key in ('ADMIN_ALIAS_OK', 'BUCKET_EXISTS', 'USER_EXISTS', 'POLICY_EXISTS'):
        print(key + '=' + rows[key])
    print('APP_CREDENTIAL_FILES=' + ('both' if access == 'present' else 'none'))
    print('NO_PERSISTENT_WRITES=true')
    print('SECRETS_PRINTED=false')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, TypeError) as exc:
        code = str(exc) if isinstance(exc, ValueError) else type(exc).__name__
        print('MINIO_STAGING_PLAN_BLOCKED=' + code, file=sys.stderr)
        raise SystemExit(1)
