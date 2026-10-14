#!/usr/bin/env python3
"""One rollback-backed Onboarding picker cutover or chat-handoff upgrade.

Run from a fetched, pinned source checkout. The live THS secret file is read
only to preserve its existing scope list; values are never printed. The old
container and a restore-tested schema dump remain available after success.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
import time
import types

database = None

ROOT = Path('/etc/ouf/deploy-snapshots')
LIVE = 'ouf-onboarding'
PICKER_ENV = 'SPRING_APPLICATION_JSON'
UPLOAD = 'ouf.managed-source.file.upload'
UPLOAD_URL = 'http://ouf-apisix:9080/api/managed-sources/v1/files'
THS_MOUNT = '/run/secrets/onboarding-ths.yaml'


class Blocked(ValueError):
    pass


def command(args, *, stdin=None, stdout=subprocess.PIPE) -> bytes:
    run = subprocess.run(args, stdin=stdin, stdout=stdout,
                         stderr=subprocess.DEVNULL, check=False)
    if run.returncode:
        raise Blocked('COMMAND_FAILED_' + Path(args[0]).name.upper())
    return run.stdout if stdout == subprocess.PIPE else b''


def load_database(repo: Path, revision: str):
    """Use the matching backup helper from the same pinned source revision."""
    if not re.fullmatch(r'[0-9a-f]{40}', revision):
        raise Blocked('REVISION_REQUIRED')
    safe = ['git', '-c', 'safe.directory=' + str(repo.resolve(strict=True)), '-C', str(repo)]
    source = command([*safe, 'show', revision + ':scripts/r4a_onboarding_db_backup.py']).decode()
    module = types.ModuleType('r4a_onboarding_db_backup')
    exec(compile(source, 'scripts/r4a_onboarding_db_backup.py', 'exec'), module.__dict__)
    return module


def inspect(name: str) -> dict:
    docs = json.loads(command(['docker', 'inspect', name]))
    if len(docs) != 1:
        raise Blocked('DOCKER_INSPECT_UNEXPECTED')
    return docs[0]


def inspect_optional(name: str) -> dict | None:
    if subprocess.run(['docker', 'inspect', name], stdout=subprocess.DEVNULL,
                      stderr=subprocess.DEVNULL, check=False).returncode:
        return None
    return inspect(name)


def scopes_from_ths_config(contents: str) -> tuple[str, list[str]]:
    """Read only the exact ouf-ths registration block from an existing YAML."""
    if contents.lstrip().startswith('{'):
        try:
            registration = json.loads(contents)['spring']['security']['oauth2']['client']['registration']['ouf-ths']
            client = registration['client-id']
            raw_scopes = registration['scope']
            scopes = (raw_scopes if isinstance(raw_scopes, list) else
                      re.split(r'[\s,]+', raw_scopes) if isinstance(raw_scopes, str) else [])
        except (KeyError, TypeError, ValueError):
            raise Blocked('THS_CONFIG_UNRESOLVED') from None
        if (not isinstance(client, str) or not re.fullmatch(r'[A-Za-z0-9._-]+', client) or
                not scopes or any(not isinstance(s, str) or not re.fullmatch(r'[A-Za-z0-9._-]+', s) for s in scopes) or
                len(scopes) != len(set(scopes))):
            raise Blocked('THS_CLIENT_OR_SCOPE_UNRESOLVED')
        return client, scopes
    block_indent = None
    values = {}
    for raw in contents.splitlines():
        if not raw.strip() or raw.lstrip().startswith('#'):
            continue
        if '\t' in raw[:len(raw) - len(raw.lstrip())]:
            raise Blocked('THS_CONFIG_INDENT_UNEXPECTED')
        indent = len(raw) - len(raw.lstrip(' '))
        entry = raw.strip()
        if block_indent is None:
            if entry == 'ouf-ths:':
                block_indent = indent
            continue
        if indent <= block_indent:
            break
        match = re.fullmatch(r'(client-id|scope):\s*(.+)', entry)
        if match:
            key, value = match.groups()
            if key in values or any(c in value for c in ('#', '$', '{', '}', '[', ']')):
                raise Blocked('THS_CONFIG_VALUE_UNEXPECTED')
            values[key] = value.strip().strip('"\'')
    client = values.get('client-id', '')
    scopes = [part for part in re.split(r'[\s,]+', values.get('scope', '')) if part]
    if (not re.fullmatch(r'[A-Za-z0-9._-]+', client) or not scopes or
            len(scopes) != len(set(scopes)) or any(not re.fullmatch(r'[A-Za-z0-9._-]+', s) for s in scopes)):
        raise Blocked('THS_CLIENT_OR_SCOPE_UNRESOLVED')
    return client, scopes


def picker_overlay(scopes: list[str]) -> str:
    requested = [*scopes, *([UPLOAD] if UPLOAD not in scopes else [])]
    return json.dumps({
        'ouf': {'managed-file-picker': {'gateway-upload-url': UPLOAD_URL}},
        'spring': {'security': {'oauth2': {'client': {'registration': {
            'ouf-ths': {'scope': requested},
        }}}}},
    }, separators=(',', ':'))


def preflight(repo: Path, revision: str, upgrade: bool = False) -> tuple[dict, str, list[str], str]:
    if os.geteuid() != 0 or not re.fullmatch(r'[0-9a-f]{40}', revision):
        raise Blocked('ROOT_OR_REVISION_REQUIRED')
    meta = ROOT.lstat()
    if not stat.S_ISDIR(meta.st_mode) or meta.st_uid != 0 or stat.S_IMODE(meta.st_mode) != 0o700:
        raise Blocked('SNAPSHOT_DIRECTORY_UNSAFE')
    old = inspect(LIVE)
    base = (old['Config'].get('Labels') or {}).get('org.opencontainers.image.revision', '')
    host = old['HostConfig']
    if (not old['State']['Running'] or not re.fullmatch(r'[0-9a-f]{40}', base) or
            old['Config'].get('User') != '10003:10003' or
            host['NetworkMode'] != 'ouf-backend' or
            host['RestartPolicy']['Name'] != 'unless-stopped' or
            any(host.get(k) for k in ('PortBindings', 'ReadonlyRootfs', 'Privileged',
                                       'CapAdd', 'CapDrop', 'SecurityOpt', 'Tmpfs',
                                       'Memory', 'NanoCpus', 'Devices', 'ExtraHosts', 'Dns')) or
            (host.get('LogConfig') or {}).get('Type') != 'json-file' or
            old['Config'].get('Healthcheck') or len(old.get('Mounts') or []) != 5 or
            any(m.get('Type') != 'bind' or m.get('RW') for m in old.get('Mounts') or [])):
        raise Blocked('ONBOARDING_RUNTIME_UNEXPECTED')
    if base == revision:
        raise Blocked('REVISION_ALREADY_LIVE')
    safe = ['git', '-c', 'safe.directory=' + str(repo.resolve(strict=True)), '-C', str(repo)]
    for commit in (base, revision):
        if command([*safe, 'rev-parse', commit + '^{commit}']).decode().strip() != commit:
            raise Blocked('PINNED_COMMIT_MISSING')
    migration_diff = command([*safe, 'diff', '--name-status', base, revision, '--',
                              'src/main/resources/db/migration']).decode().splitlines()
    if migration_diff:
        raise Blocked('DATABASE_MIGRATION_CHANGED')
    config = [m for m in old['Mounts'] if m['Destination'] == THS_MOUNT]
    if len(config) != 1:
        raise Blocked('THS_CONFIG_MOUNT_MISSING')
    client, scopes = scopes_from_ths_config(Path(config[0]['Source']).read_text())
    env = database.env(old)
    if (PICKER_ENV in env) != upgrade:
        raise Blocked('PICKER_ENV_ALREADY_PRESENT')
    backup = 'ouf-onboarding-pre-picker-' + revision[:7]
    if subprocess.run(['docker', 'inspect', backup], stdout=subprocess.DEVNULL,
                      stderr=subprocess.DEVNULL, check=False).returncode == 0:
        raise Blocked('BACKUP_NAME_ALREADY_EXISTS')
    return old, client, scopes, backup


def keycloak_scope(repo: Path, revision: str, client: str, mode: str) -> None:
    """Run the scope helper and its import from the pinned commit, not the checkout."""
    if not re.fullmatch(r'[0-9a-f]{40}', revision):
        raise Blocked('REVISION_REQUIRED')
    safe = ['git', '-c', 'safe.directory=' + str(repo.resolve(strict=True)), '-C', str(repo)]
    with tempfile.TemporaryDirectory(prefix='r4a-picker-kcadm-', dir=ROOT) as folder_name:
        folder = Path(folder_name)
        os.chmod(folder, 0o700)
        for name in ('r4a_keycloak_client_scope_binding.py',
                     'r4a_keycloak_client_scope_catalogue.py'):
            source = command([*safe, 'show', revision + ':scripts/' + name])
            target = folder / name
            fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
            with os.fdopen(fd, 'wb') as stream:
                stream.write(source)
        result = subprocess.run([sys.executable, str(folder / 'r4a_keycloak_client_scope_binding.py'), mode,
                                 '--client', client, '--scope', UPLOAD, '--binding', 'optional'],
                                stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True, check=False)
        if result.returncode:
            match = re.search(r'CLIENT_SCOPE_BINDING_BLOCKED=([A-Z_]+)', result.stderr)
            raise Blocked('THS_CLIENT_SCOPE_' + (match.group(1) if match else 'QUERY_FAILED'))


def image(repo: Path, revision: str) -> tuple[str, str]:
    tag = 'ouf-onboarding:picker-' + revision[:7]
    safe = ['git', '-c', 'safe.directory=' + str(repo.resolve(strict=True)), '-C', str(repo)]
    exported = subprocess.Popen([*safe, 'archive', '--format=tar', revision],
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    try:
        result = subprocess.run(['docker', 'build', '--pull=false', '--quiet',
                                 '--label', 'org.opencontainers.image.revision=' + revision,
                                 '-t', tag, '-'], stdin=exported.stdout,
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
    finally:
        exported.stdout.close()
    if exported.wait() or result.returncode:
        raise Blocked('CANDIDATE_IMAGE_BUILD_FAILED')
    doc = inspect(tag)
    if doc['Config']['Labels'].get('org.opencontainers.image.revision') != revision:
        raise Blocked('CANDIDATE_IMAGE_REVISION_MISMATCH')
    return tag, doc['Id']


def readiness() -> None:
    for _ in range(30):
        live = inspect(LIVE)
        pid = live['State'].get('Pid', 0)
        if not live['State']['Running'] or pid <= 0:
            raise Blocked('NEW_CONTAINER_NOT_RUNNING')
        code = subprocess.run(['nsenter', '-t', str(pid), '-n', 'python3', '-c',
                               'import urllib.request; assert urllib.request.urlopen("http://127.0.0.1:8080/actuator/health/readiness",timeout=3).status==200'],
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
        if code.returncode == 0:
            return
        time.sleep(2)
    raise Blocked('ONBOARDING_READINESS_TIMEOUT')


def rollback(state: dict) -> None:
    old = inspect_optional(state['backup'])
    if old is None:
        raise Blocked('BACKUP_CONTAINER_MISSING')
    if old['Id'] != state['old_id']:
        raise Blocked('BACKUP_CONTAINER_CHANGED')
    new = inspect_optional(LIVE)
    if new is not None:
        if new['Id'] != state['new_id']:
            raise Blocked('LIVE_CONTAINER_CHANGED')
        command(['docker', 'update', '--restart', 'no', LIVE], stdout=subprocess.DEVNULL)
        if new['State']['Running']:
            command(['docker', 'stop', LIVE], stdout=subprocess.DEVNULL)
        command(['docker', 'rm', LIVE], stdout=subprocess.DEVNULL)
    command(['docker', 'rename', state['backup'], LIVE], stdout=subprocess.DEVNULL)
    command(['docker', 'update', '--restart', 'unless-stopped', LIVE], stdout=subprocess.DEVNULL)
    command(['docker', 'start', LIVE], stdout=subprocess.DEVNULL)
    readiness()


def main() -> None:
    global database
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('mode', choices=('plan', 'apply', 'upgrade', 'rollback'))
    p.add_argument('--revision')
    p.add_argument('--repo', type=Path, default=Path('/opt/ouf/onboarding'))
    p.add_argument('--state', type=Path)
    a = p.parse_args()
    if a.mode == 'rollback':
        if not a.state or a.state.parent != ROOT:
            raise Blocked('ROLLBACK_STATE_REQUIRED')
        meta = a.state.lstat()
        if meta.st_uid != 0 or not stat.S_ISREG(meta.st_mode) or stat.S_IMODE(meta.st_mode) != 0o600:
            raise Blocked('ROLLBACK_STATE_UNSAFE')
        rollback(json.loads(a.state.read_text()))
        print('PICKER_ONBOARDING_ROLLBACK=PASS')
        return
    database = load_database(a.repo, a.revision or '')
    upgrade = a.mode == 'upgrade'
    old, client, scopes, backup = preflight(a.repo, a.revision or '', upgrade)
    if not upgrade:
        keycloak_scope(a.repo, a.revision, client, 'plan')
    print('MODE=' + a.mode)
    print('THS_CLIENT_AND_SCOPE_IDENTIFIED=true')
    print('DATABASE_MIGRATIONS_UNCHANGED=true')
    print('ORIGINAL_RUNNING=true')
    if a.mode == 'plan':
        print('NO_WRITES=true')
        return
    tag, image_id = image(a.repo, a.revision)
    db, user = database.parameters(old, database.inspect('ouf-postgres'))
    dump = database.backup(db, user)
    database.restore_probe(dump, user)
    if not upgrade:
        keycloak_scope(a.repo, a.revision, client, 'apply')
    new_env = database.env(old) if upgrade else {**database.env(old), PICKER_ENV: picker_overlay(scopes)}
    folder = Path(tempfile.mkdtemp(prefix='r4a-picker-onboarding-', dir=ROOT))
    os.chmod(folder, 0o700)
    state_path = folder / 'state.json'
    env_path = folder / 'candidate.env'
    fd = os.open(env_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'w') as stream:
        for key, value in new_env.items():
            if not key.isidentifier() or any(c in value for c in '\r\n'):
                raise Blocked('ENV_VALUE_UNSAFE')
            stream.write(key + '=' + value + '\n')
        stream.flush()
        os.fsync(stream.fileno())
    state = {'old_id': old['Id'], 'backup': backup, 'new_id': None, 'db_dump': str(dump),
             'image_id': image_id, 'revision': a.revision}
    fd = os.open(state_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'w') as stream:
        json.dump(state, stream)
        stream.write('\n')
    try:
        if inspect(LIVE)['Id'] != old['Id']:
            raise Blocked('LIVE_CHANGED_BEFORE_SWAP')
        command(['docker', 'update', '--restart', 'no', LIVE], stdout=subprocess.DEVNULL)
        command(['docker', 'stop', LIVE], stdout=subprocess.DEVNULL)
        command(['docker', 'rename', LIVE, backup], stdout=subprocess.DEVNULL)
        create = ['docker', 'create', '--name', LIVE, '--network', 'ouf-backend',
                  '--network-alias', LIVE, '--user', '10003:10003', '--restart', 'no',
                  '--env-file', str(env_path)]
        for mount in old['Mounts']:
            create.extend(['--mount', 'type=bind,source=' + mount['Source'] + ',target=' + mount['Destination'] + ',readonly'])
        state['new_id'] = command([*create, tag]).decode().strip()
        if inspect(LIVE)['Id'] != state['new_id']:
            raise Blocked('CANDIDATE_CONTAINER_ID_MISMATCH')
        with state_path.open('w') as stream:
            json.dump(state, stream)
            stream.write('\n')
            stream.flush()
            os.fsync(stream.fileno())
        command(['docker', 'start', LIVE], stdout=subprocess.DEVNULL)
        readiness()
        current = inspect(LIVE)
        if (current['Image'] != image_id or database.env(current) != new_env or
                {(m['Source'], m['Destination'], m['RW']) for m in current['Mounts']} !=
                {(m['Source'], m['Destination'], m['RW']) for m in old['Mounts']}):
            raise Blocked('NEW_CONTAINER_CONFIG_MISMATCH')
        command(['docker', 'update', '--restart', 'unless-stopped', LIVE], stdout=subprocess.DEVNULL)
        print('PICKER_CHAT_HANDOFF_UPGRADE=PASS' if upgrade else 'PICKER_ONBOARDING_ROLLOUT=PASS')
        print('IMAGE_ID=' + image_id)
        print('DB_DUMP=' + str(dump))
        print('ROLLBACK_STATE=' + str(state_path))
        print('THS_SCOPE_REQUESTED=true; LIVE_HUMAN_LOGIN_PENDING=true')
    except BaseException:
        if inspect_optional(backup) is not None:
            try:
                rollback(state)
                print('AUTO_CONTAINER_ROLLBACK=PASS', file=sys.stderr)
            except BaseException:
                print('AUTO_CONTAINER_ROLLBACK=FAILED', file=sys.stderr)
        else:
            current = inspect_optional(LIVE)
            if current is not None and current['Id'] == old['Id']:
                try:
                    command(['docker', 'update', '--restart', 'unless-stopped', LIVE], stdout=subprocess.DEVNULL)
                    if not current['State']['Running']:
                        command(['docker', 'start', LIVE], stdout=subprocess.DEVNULL)
                        readiness()
                    print('AUTO_CONTAINER_ROLLBACK=PASS', file=sys.stderr)
                except BaseException:
                    print('AUTO_CONTAINER_ROLLBACK=FAILED', file=sys.stderr)
        raise
    finally:
        env_path.unlink(missing_ok=True)


if __name__ == '__main__':
    try:
        main()
    except (Blocked, OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as exc:
        print('PICKER_ONBOARDING_BLOCKED=' + (str(exc) if isinstance(exc, Blocked) else type(exc).__name__), file=sys.stderr)
        raise SystemExit(1)
