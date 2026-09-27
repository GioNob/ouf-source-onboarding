#!/usr/bin/env python3
"""Idempotently reconcile one Keycloak OIDC client scope for OUF.

Modes:
  plan   - read-only comparison
  apply  - create or reconcile the exact scope contract
  verify - read-only acceptance

The helper uses the already-authenticated kcadm session inside the running
Keycloak container. It never prints credentials, tokens or secrets.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys

KC="/opt/keycloak/bin/kcadm.sh"
REQUIRED_ATTRIBUTES={
    "include.in.token.scope":"true",
    "display.on.consent.screen":"false",
}

class ScopeError(RuntimeError): pass

def run(container,*args,input_text=None):
    p=subprocess.run(
        ["docker","exec","-i",container,KC,*args],
        input=input_text,text=True,capture_output=True
    )
    if p.returncode:
        msg=(p.stderr or p.stdout).strip()
        if "Session has expired" in msg:
            raise ScopeError("KCADM_SESSION_EXPIRED")
        raise ScopeError("KCADM_COMMAND_FAILED")
    return p.stdout

def get_json(container,*args):
    try:
        return json.loads(run(container,*args))
    except json.JSONDecodeError as exc:
        raise ScopeError("KCADM_INVALID_JSON") from exc

def validate_name(value,label):
    if not re.fullmatch(r"[A-Za-z0-9._:-]+",value):
        raise ScopeError("INVALID_"+label)

def exact_scope(container,realm,name):
    rows=get_json(container,"get","client-scopes","-r",realm)
    matches=[x for x in rows if isinstance(x,dict) and x.get("name")==name]
    if len(matches)>1:
        raise ScopeError("SCOPE_NOT_UNIQUE")
    return matches[0] if matches else None

def desired(name):
    return {
        "name":name,
        "protocol":"openid-connect",
        "attributes":dict(REQUIRED_ATTRIBUTES),
    }

def drift(scope):
    if scope is None:
        return ["SCOPE_MISSING"]
    problems=[]
    if scope.get("protocol")!="openid-connect":
        problems.append("PROTOCOL")
    attrs=scope.get("attributes") or {}
    for key,value in REQUIRED_ATTRIBUTES.items():
        if str(attrs.get(key)).lower()!=value:
            problems.append("ATTRIBUTE:"+key)
    return problems

def reconcile(container,realm,name):
    current=exact_scope(container,realm,name)
    if current is None:
        run(container,"create","client-scopes","-r",realm,"-f","-",input_text=json.dumps(desired(name),separators=(",",":")))
        return
    if not drift(current):
        return
    scope_id=current.get("id")
    if not isinstance(scope_id,str) or not scope_id:
        raise ScopeError("SCOPE_ID_MISSING")
    updated=dict(current)
    updated["name"]=name
    updated["protocol"]="openid-connect"
    attrs=dict(updated.get("attributes") or {})
    attrs.update(REQUIRED_ATTRIBUTES)
    updated["attributes"]=attrs
    run(container,"update",f"client-scopes/{scope_id}","-r",realm,"-f","-",input_text=json.dumps(updated,separators=(",",":")))

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("mode",choices=("plan","apply","verify"))
    p.add_argument("--container",default="ouf-keycloak")
    p.add_argument("--realm",default="ouf")
    p.add_argument("--scope",required=True)
    a=p.parse_args()
    validate_name(a.realm,"REALM")
    validate_name(a.scope,"SCOPE")

    before=exact_scope(a.container,a.realm,a.scope)
    before_drift=drift(before)
    print("MODE="+a.mode)
    print("SCOPE="+a.scope)
    print("EXISTS="+str(before is not None).lower())
    print("DRIFT="+(",".join(before_drift) if before_drift else "NONE"))

    if a.mode=="plan":
        print("NO_CHANGES=true")
        return
    if a.mode=="apply" and before_drift:
        reconcile(a.container,a.realm,a.scope)

    after=exact_scope(a.container,a.realm,a.scope)
    remaining=drift(after)
    if remaining:
        raise ScopeError("VERIFY_DRIFT:"+",".join(remaining))
    print("VERIFY=PASS")
    print("SECRETS_PRINTED=false")

if __name__=="__main__":
    try: main()
    except (OSError,KeyError,TypeError,ValueError,ScopeError) as exc:
        code=str(exc) if isinstance(exc,ScopeError) else type(exc).__name__
        print("CLIENT_SCOPE_CATALOGUE_BLOCKED="+code,file=sys.stderr)
        raise SystemExit(1)
