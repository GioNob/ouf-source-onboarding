#!/usr/bin/env python3
"""Reconcile Onboarding runtime-publications tenant from Installation projection.

The reconciler edits only ouf.runtime-publications.tenant-id in the JSON/YAML
configuration file. It supports plan/apply/verify, preserves all unrelated
content semantically, preserves file uid/gid/mode, writes atomically, and keeps
a root-readable backup on apply. Secret values are never printed.
"""
from __future__ import annotations
import argparse, json, os, shutil, stat, sys, tempfile
from pathlib import Path

ENV_KEY="OUF_RUNTIME_PUBLICATIONS_TENANT_ID"

def load_json(path: Path):
    value=json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value,dict):
        raise ValueError("JSON object required: "+str(path))
    return value

def projected_tenant(projection):
    try:
        value=projection["onboarding"]["environment"][ENV_KEY]
    except (KeyError,TypeError):
        raise ValueError("PROJECTION_ONBOARDING_TENANT_MISSING") from None
    if not isinstance(value,str) or not value.strip():
        raise ValueError("PROJECTION_ONBOARDING_TENANT_INVALID")
    return value.strip()

def current_tenant(config):
    ouf=config.get("ouf")
    if not isinstance(ouf,dict): return None
    runtime=ouf.get("runtime-publications")
    if not isinstance(runtime,dict): return None
    value=runtime.get("tenant-id")
    return value if isinstance(value,str) else None

def candidate(config,tenant):
    out=json.loads(json.dumps(config))
    ouf=out.setdefault("ouf",{})
    if not isinstance(ouf,dict): raise ValueError("CONFIG_OUF_NOT_OBJECT")
    runtime=ouf.setdefault("runtime-publications",{})
    if not isinstance(runtime,dict): raise ValueError("CONFIG_RUNTIME_PUBLICATIONS_NOT_OBJECT")
    runtime["tenant-id"]=tenant
    return out

def atomic_write(path: Path,value,st):
    fd,tmp=tempfile.mkstemp(prefix=path.name+".tmp.",dir=str(path.parent))
    try:
        with os.fdopen(fd,"w",encoding="utf-8") as f:
            json.dump(value,f,indent=2,sort_keys=True)
            f.write("\n")
            f.flush()
            os.fsync(f.fileno())
        os.chown(tmp,st.st_uid,st.st_gid)
        os.chmod(tmp,stat.S_IMODE(st.st_mode))
        os.replace(tmp,path)
    finally:
        if os.path.exists(tmp): os.unlink(tmp)

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--projection",type=Path,required=True)
    p.add_argument("--config",type=Path,required=True)
    p.add_argument("--backup",type=Path)
    modes=p.add_mutually_exclusive_group(required=True)
    modes.add_argument("--plan",action="store_true")
    modes.add_argument("--apply",action="store_true")
    modes.add_argument("--verify",action="store_true")
    a=p.parse_args()

    projection=load_json(a.projection)
    tenant=projected_tenant(projection)
    config=load_json(a.config)
    current=current_tenant(config)
    drift=current!=tenant

    print("TARGET_SOURCE=installation-projection")
    print("TARGET_TENANT="+tenant)
    print("CURRENT_PRESENT="+str(current is not None).lower())
    print("DRIFT="+str(drift).lower())

    if a.plan:
        print("NO_WRITES=true")
        return
    if a.verify:
        if drift: raise ValueError("VERIFY_DRIFT")
        print("VERIFY=PASS")
        print("NO_WRITES=true")
        return

    if not drift:
        print("NO_CHANGE=true")
        print("VERIFY=PASS")
        return

    st=a.config.stat()
    backup=a.backup or a.config.with_name(a.config.name+".bak")
    if backup.exists():
        raise ValueError("BACKUP_ALREADY_EXISTS="+str(backup))
    shutil.copyfile(a.config,backup)
    os.chown(backup,0,0)
    os.chmod(backup,0o400)

    updated=candidate(config,tenant)
    atomic_write(a.config,updated,st)

    check=load_json(a.config)
    if current_tenant(check)!=tenant:
        raise RuntimeError("POST_WRITE_VERIFY_FAILED")

    print("BACKUP="+str(backup))
    print("APPLIED=true")
    print("VERIFY=PASS")

if __name__=="__main__":
    try: main()
    except (OSError,ValueError,RuntimeError,json.JSONDecodeError) as exc:
        print("ONBOARDING_RUNTIME_CONFIG_BLOCKED="+str(exc),file=sys.stderr)
        raise SystemExit(1) from None
