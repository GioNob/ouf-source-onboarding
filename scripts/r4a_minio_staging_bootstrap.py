#!/usr/bin/env python3
"""Provision and check the dedicated managed-file MinIO bucket and identity.

Requires the existing private Docker snapshot. Credentials are generated in
root-owned files on the host and passed to a transient shell in ouf-minio via
stdin, never printed. MinIO admin credentials stay inside that container.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import secrets
import stat
import subprocess
import sys
import uuid

BUCKET = 'ouf-managed-files'
USER = 'ouf-onboarding-staging'
POLICY_NAME = 'ouf-onboarding-managed-files-v1'
DIRECTORY = Path('/etc/ouf/secrets')
ACCESS = DIRECTORY / 'onboarding-minio-access-key'
SECRET = DIRECTORY / 'onboarding-minio-secret-key'
SNAPSHOT = Path('/etc/ouf/deploy-snapshots/r4a-before-staging.docker-inspect.json')
POLICY = {
    'Version': '2012-10-17',
    'Statement': [{'Effect': 'Allow',
                   'Action': ['s3:GetObject', 's3:PutObject', 's3:AbortMultipartUpload',
                              's3:ListMultipartUploadParts'],
                   'Resource': ['arn:aws:s3:::ouf-managed-files/managed-files/*']}],
}

PREFIX = r'''set -eu
umask 077
cfg="$(mktemp -d /tmp/ouf-r4a-mc.XXXXXX)"
trap 'rm -rf "$cfg"' EXIT
if [ -n "${MINIO_ROOT_USER_FILE:-}" ] && [ -r "$MINIO_ROOT_USER_FILE" ]; then
    root_user="$(cat "$MINIO_ROOT_USER_FILE")"
else
    root_user="${MINIO_ROOT_USER:-}"
fi
test -n "$root_user" && test -n "${MINIO_ROOT_PASSWORD_FILE:-}" && test -r "$MINIO_ROOT_PASSWORD_FILE" || exit 31
root_password="$(cat "$MINIO_ROOT_PASSWORD_FILE")"
test -n "$root_password" || exit 32
mc --config-dir "$cfg" alias set r4a http://127.0.0.1:9000 "$root_user" "$root_password" >/dev/null 2>&1 || exit 33
unset root_password
mc --config-dir "$cfg" admin info r4a >/dev/null 2>&1 || exit 34
'''
PROBE = PREFIX + r'''if mc --config-dir "$cfg" stat r4a/ouf-managed-files >/dev/null 2>&1; then echo BUCKET=true; else echo BUCKET=false; fi
if mc --config-dir "$cfg" admin policy info r4a ouf-onboarding-managed-files-v1 >/dev/null 2>&1; then echo POLICY=true; else echo POLICY=false; fi
if mc --config-dir "$cfg" admin user info r4a ouf-onboarding-staging >/dev/null 2>&1; then echo USER=true; else echo USER=false; fi
'''


def run_shell(shell: str) -> str:
    response = subprocess.run(['docker', 'exec', '-i', 'ouf-minio', 'sh', '-s'],
                              input=shell, capture_output=True, text=True, check=False)
    if response.returncode:
        raise ValueError('MINIO_COMMAND_FAILED_' + str(response.returncode))
    return response.stdout


def private_file(path: Path, gid: int, mode: int) -> bool:
    if not path.exists() and not path.is_symlink():
        return False
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_gid != gid or stat.S_IMODE(info.st_mode) != mode:
        raise ValueError('PRIVATE_FILE_UNSAFE')
    return True


def prerequisites() -> bool:
    if os.geteuid() != 0:
        raise ValueError('ROOT_REQUIRED')
    d = DIRECTORY.lstat()
    if not stat.S_ISDIR(d.st_mode) or d.st_uid != 0 or stat.S_IMODE(d.st_mode) != 0o700:
        raise ValueError('SECRET_DIRECTORY_UNSAFE')
    if not private_file(SNAPSHOT, 0, 0o600):
        raise ValueError('DOCKER_SNAPSHOT_REQUIRED')
    access = private_file(ACCESS, 10003, 0o440)
    secret = private_file(SECRET, 10003, 0o440)
    if access != secret:
        raise ValueError('APP_CREDENTIAL_FILES_PARTIAL')
    if access and (ACCESS.read_text().strip() != USER or len(SECRET.read_text().strip()) < 32):
        raise ValueError('APP_CREDENTIAL_FILES_INVALID')
    return access


def probe() -> dict[str, bool]:
    output = run_shell(PROBE)
    rows = dict(line.split('=', 1) for line in output.splitlines() if '=' in line)
    if set(rows) != {'BUCKET', 'POLICY', 'USER'} or not set(rows.values()) <= {'true', 'false'}:
        raise ValueError('MINIO_PROBE_UNEXPECTED')
    return {key: value == 'true' for key, value in rows.items()}


def create_file(path: Path, value: str) -> None:
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        with os.fdopen(fd, 'w', encoding='ascii') as stream:
            stream.write(value + '\n')
            stream.flush()
            os.fsync(stream.fileno())
        os.chown(path, 0, 10003)
        os.chmod(path, 0o440)
    except BaseException:
        raise


def policy_matches() -> bool:
    output = run_shell(PREFIX + r'''mc --config-dir "$cfg" admin policy info r4a ouf-onboarding-managed-files-v1 --policy-file "$cfg/installed.json" >/dev/null 2>&1 || exit 41
cat "$cfg/installed.json"
''')
    try:
        return json.loads(output) == POLICY
    except json.JSONDecodeError as exc:
        raise ValueError('MINIO_POLICY_INVALID_JSON') from exc


def install(existing_files: bool, before: dict[str, bool]) -> None:
    if before['USER'] and not existing_files:
        raise ValueError('EXISTING_USER_WITHOUT_LOCAL_CREDENTIALS')
    if before['POLICY'] and not policy_matches():
        raise ValueError('EXISTING_POLICY_DIFFERS')
    if not existing_files:
        # Persist credentials before the API call, so an interrupted apply can
        # resume without rotating or losing the new MinIO user's password.
        create_file(ACCESS, USER)
        create_file(SECRET, secrets.token_hex(32))
    password = SECRET.read_text(encoding='ascii').strip()
    if not before['BUCKET']:
        run_shell(PREFIX + 'mc --config-dir "$cfg" mb --ignore-existing r4a/ouf-managed-files >/dev/null 2>&1 || exit 51\n')
        print('BUCKET_CREATED=true')
    if not before['POLICY']:
        body = json.dumps(POLICY, separators=(',', ':'), sort_keys=True)
        run_shell(PREFIX + 'cat > "$cfg/policy.json" <<\'OUF_R4A_POLICY\'\n' + body +
                  '\nOUF_R4A_POLICY\nmc --config-dir "$cfg" admin policy create r4a ' +
                  POLICY_NAME + ' "$cfg/policy.json" >/dev/null 2>&1 || exit 52\n')
        print('POLICY_CREATED=true')
    if not before['USER']:
        run_shell('APP_SECRET=' + password + '\n' + PREFIX +
                  'mc --config-dir "$cfg" admin user add r4a ' + USER +
                  ' "$APP_SECRET" >/dev/null 2>&1 || exit 53\n')
        print('USER_CREATED=true')
    # Reattaching the same dedicated policy is idempotent. The credential is
    # never the MinIO root credential; the runtime user cannot administer IAM.
    run_shell(PREFIX + 'mc --config-dir "$cfg" admin policy attach r4a ' +
              POLICY_NAME + ' --user ' + USER + ' >/dev/null 2>&1 || exit 54\n')
    print('POLICY_ATTACHED=true')


def behavioral_check() -> None:
    password = SECRET.read_text(encoding='ascii').strip()
    marker = 'probe-' + uuid.uuid4().hex
    script = ('APP_SECRET=' + password + '\n' + PREFIX + r'''mc --config-dir "$cfg" alias set app http://127.0.0.1:9000 ouf-onboarding-staging "$APP_SECRET" >/dev/null 2>&1 || exit 61
unset APP_SECRET
obj="app/ouf-managed-files/managed-files/''' + marker + r'''"
trap 'mc --config-dir "$cfg" rm "r4a/ouf-managed-files/managed-files/''' + marker + r'''" >/dev/null 2>&1 || true; rm -rf "$cfg"' EXIT
printf 'ouf-r4a-probe' | mc --config-dir "$cfg" pipe "$obj" >/dev/null 2>&1 || exit 62
value="$(mc --config-dir "$cfg" cat "$obj" 2>/dev/null)" || exit 63
test "$value" = ouf-r4a-probe || exit 64
mc --config-dir "$cfg" stat r4a/ouf-udp >/dev/null 2>&1 || exit 65
if printf 'ouf-r4a-probe' | mc --config-dir "$cfg" pipe "app/ouf-udp/managed-files/''' + marker + r'''" >/dev/null 2>&1; then
    mc --config-dir "$cfg" rm "r4a/ouf-udp/managed-files/''' + marker + r'''" >/dev/null 2>&1 || true
    exit 66
fi
echo PREFIX_WRITE_READ_OK=true
echo UDP_WRITE_DENIED=true
''')
    output = run_shell(script)
    if set(output.splitlines()) != {'PREFIX_WRITE_READ_OK=true', 'UDP_WRITE_DENIED=true'}:
        raise ValueError('MINIO_BEHAVIOR_UNEXPECTED')
    print(output, end='')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('plan', 'apply', 'verify'))
    args = parser.parse_args()
    files = prerequisites()
    before = probe()
    print('MODE=' + args.mode)
    for key in ('BUCKET', 'POLICY', 'USER'):
        print(key + '_EXISTS=' + str(before[key]).lower())
    print('APP_CREDENTIAL_FILES=' + ('both' if files else 'none'))
    if args.mode == 'plan':
        print('NO_WRITES=true')
        return
    if args.mode == 'apply':
        install(files, before)
    after = probe()
    if not all(after.values()) or not prerequisites() or not policy_matches():
        raise ValueError('MINIO_STAGING_VERIFY_FAILED')
    behavioral_check()
    print('VERIFY=PASS')
    print('SECRETS_PRINTED=false')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError) as exc:
        code = str(exc) if isinstance(exc, ValueError) else type(exc).__name__
        print('MINIO_STAGING_BOOTSTRAP_BLOCKED=' + code, file=sys.stderr)
        raise SystemExit(1)
