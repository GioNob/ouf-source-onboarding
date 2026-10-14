#!/usr/bin/env python3
"""Store an existing Keycloak workload client secret in a root-only file.

plan is read-only and never requests the secret. apply writes a new file only;
verify checks metadata without reading or printing the secret. This script
never rotates a Keycloak credential and never overwrites an existing file.
The dedicated /etc/ouf/secrets directory is created only by apply.
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

KC="/opt/keycloak/bin/kcadm.sh"
SECRET_DIRECTORY=Path("/etc/ouf/secrets")


class SecretError(RuntimeError): pass


def kcadm(container,*args):
    p=subprocess.run(["docker","exec","-i",container,KC,*args],capture_output=True,text=True)
    if p.returncode:
        if "Session has expired" in (p.stderr or p.stdout):raise SecretError("KCADM_SESSION_EXPIRED")
        raise SecretError("KCADM_COMMAND_FAILED")
    try:return json.loads(p.stdout)
    except json.JSONDecodeError as exc:raise SecretError("KCADM_INVALID_JSON") from exc


def exact_client(container,realm,name):
    rows=kcadm(container,"get","clients","-r",realm,"-q","clientId="+name,"--fields","id,clientId")
    matches=[row for row in rows if isinstance(row,dict) and row.get("clientId")==name]
    if len(matches)!=1:raise SecretError("CLIENT_NOT_UNIQUE_OR_MISSING")
    return matches[0]["id"]


def check_directory(directory,create=False,expected_uid=0):
    base=directory.parent
    base_info=base.lstat()
    if not stat.S_ISDIR(base_info.st_mode) or base_info.st_uid!=expected_uid or stat.S_IMODE(base_info.st_mode)&0o022:
        raise SecretError("SECRET_BASE_DIRECTORY_UNSAFE")
    if not directory.exists() and not directory.is_symlink():
        if not create:return False
        directory.mkdir(mode=0o700)
    info=directory.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid!=expected_uid or stat.S_IMODE(info.st_mode)!=0o700:
        raise SecretError("SECRET_DIRECTORY_UNSAFE")
    return True


def check_path(path,create_parent=False):
    if not path.is_absolute() or path.parent!=SECRET_DIRECTORY or path.name!="onboarding-client-secret":
        raise SecretError("OUTPUT_PATH_NOT_ALLOWED")
    if not check_directory(path.parent,create=create_parent):return False,False
    if path.exists():
        info=path.lstat()
        if not stat.S_ISREG(info.st_mode) or info.st_uid!=0 or stat.S_IMODE(info.st_mode)!=0o600 or info.st_size<1 or info.st_size>4096:
            raise SecretError("EXISTING_SECRET_FILE_INVALID")
        return True,True
    if path.is_symlink():raise SecretError("OUTPUT_SYMLINK_DENIED")
    return True,False


def store(path,secret):
    if not isinstance(secret,str) or not secret or len(secret)>4096 or any(ch.isspace() for ch in secret):
        raise SecretError("KEYCLOAK_SECRET_INVALID")
    fd,tmp=tempfile.mkstemp(prefix=".onboarding-client-",dir=path.parent)
    try:
        with os.fdopen(fd,"w") as stream:
            os.fchmod(stream.fileno(),0o600)
            stream.write(secret)
            stream.flush();os.fsync(stream.fileno())
        # Link with O_EXCL semantics so an existing secret is never replaced.
        os.link(tmp,path)
        dfd=os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY)
        try:os.fsync(dfd)
        finally:os.close(dfd)
    finally:Path(tmp).unlink(missing_ok=True)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("mode",choices=("plan","apply","verify"))
    p.add_argument("--client",default="ouf-onboarding")
    p.add_argument("--container",default="ouf-keycloak")
    p.add_argument("--realm",default="ouf")
    p.add_argument("--output",type=Path,default=SECRET_DIRECTORY/"onboarding-client-secret")
    a=p.parse_args()
    if a.client!="ouf-onboarding" or not re.fullmatch(r"[a-z0-9-]+",a.realm):raise SecretError("CLIENT_OR_REALM_INVALID")
    internal=exact_client(a.container,a.realm,a.client)
    directory_exists,exists=check_path(a.output)
    print("CLIENT_EXACT=true SECRET_DIRECTORY_EXISTS="+str(directory_exists).lower()+" SECRET_FILE_EXISTS="+str(exists).lower())
    if a.mode=="plan":print("NO_CHANGES=true SECRET_NOT_READ_OR_PRINTED=true");return
    if os.geteuid()!=0:raise SecretError("ROOT_REQUIRED")
    if a.mode=="apply" and not directory_exists:
        _,exists=check_path(a.output,create_parent=True)
    if a.mode=="apply" and not exists:
        payload=kcadm(a.container,"get",f"clients/{internal}/client-secret","-r",a.realm)
        store(a.output,payload.get("value"))
    if not check_path(a.output)[1]:raise SecretError("SECRET_FILE_MISSING")
    print("VERIFY=PASS SECRET_NOT_PRINTED=true SECRET_NOT_ROTATED=true")


if __name__=="__main__":
    try:main()
    except (OSError,KeyError,TypeError,ValueError,SecretError) as exc:
        code=str(exc) if isinstance(exc,SecretError) else type(exc).__name__
        print("WORKLOAD_SECRET_FILE_BLOCKED="+code,file=sys.stderr)
        raise SystemExit(1)
