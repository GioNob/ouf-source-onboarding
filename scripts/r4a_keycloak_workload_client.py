#!/usr/bin/env python3
"""Idempotent Keycloak workload-client provisioner for OUF lab.

Modes:
  plan   - read-only comparison against canonical workload profile
  apply  - create or reconcile the client, default scopes and protocol mappers
  verify - read-only exact verification

The tool shells out to an already-authenticated kcadm.sh session. It never
reads or prints the generated client secret. The caller is responsible for
persisting the secret through the existing governed secret/runtime mechanism.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys

CANONICAL_SCOPES={
    "acr",
    "authorization.bundle.read",
    "basic",
    "email",
    "profile",
    "roles",
    "service_account",
    "web-origins",
}

EXPECTED_CLIENT={
    "enabled":True,
    "standardFlowEnabled":False,
    "directAccessGrantsEnabled":False,
    "serviceAccountsEnabled":True,
    "publicClient":False,
    "protocol":"openid-connect",
}

MAPPERS={
  "ouf-api-gateway-audience":{
    "protocol":"openid-connect",
    "protocolMapper":"oidc-audience-mapper",
    "config":{
      "included.client.audience":"ouf-api-gateway",
      "id.token.claim":"false",
      "lightweight.claim":"false",
      "introspection.token.claim":"true",
      "access.token.claim":"true",
      "userinfo.token.claim":"false",
    },
  },
  "ouf-service-actor-type":{
    "protocol":"openid-connect",
    "protocolMapper":"oidc-hardcoded-claim-mapper",
    "config":{
      "introspection.token.claim":"true",
      "claim.value":"SERVICE",
      "userinfo.token.claim":"false",
      "id.token.claim":"false",
      "lightweight.claim":"false",
      "access.token.claim":"true",
      "claim.name":"ouf_actor_type",
      "jsonType.label":"String",
      "access.tokenResponse.claim":"false",
    },
  },
  "ouf-lab-tenant":{
    "protocol":"openid-connect",
    "protocolMapper":"oidc-hardcoded-claim-mapper",
    "config":{
      "introspection.token.claim":"true",
      "claim.value":"ouf-lab",
      "userinfo.token.claim":"false",
      "id.token.claim":"false",
      "lightweight.claim":"false",
      "access.token.claim":"true",
      "claim.name":"tenant_id",
      "jsonType.label":"String",
      "access.tokenResponse.claim":"false",
    },
  },
}

class ProvisionError(RuntimeError): pass

def run(kc, args, stdin=None):
    cmd=[*(kc if isinstance(kc,tuple) else (kc,)),*args]
    p=subprocess.run(cmd,input=stdin,text=True,capture_output=True)
    if p.returncode!=0:
        msg=(p.stderr or p.stdout).strip().replace("\n"," | ")
        raise ProvisionError("KCADM_FAILED:"+msg)
    return p.stdout

def jrun(kc,args):
    raw=run(kc,args)
    try:return json.loads(raw) if raw.strip() else None
    except json.JSONDecodeError as e: raise ProvisionError("INVALID_KCADM_JSON") from e

def client_id(kc,realm,client_id):
    rows=jrun(kc,["get","clients","-r",realm,"-q","clientId="+client_id,"--fields","id,clientId"])
    if not isinstance(rows,list): raise ProvisionError("CLIENT_LOOKUP_INVALID")
    if len(rows)>1: raise ProvisionError("DUPLICATE_CLIENT_ID")
    return rows[0]["id"] if rows else None

def scope_map(kc,realm):
    rows=jrun(kc,["get","client-scopes","-r",realm,"--fields","id,name"])
    if not isinstance(rows,list): raise ProvisionError("SCOPE_LOOKUP_INVALID")
    return {x["name"]:x["id"] for x in rows}

def current(kc,realm,internal_id):
    client=jrun(kc,["get",f"clients/{internal_id}","-r",realm])
    scopes=jrun(kc,["get",f"clients/{internal_id}/default-client-scopes","-r",realm,"--fields","id,name"])
    mappers=jrun(kc,["get",f"clients/{internal_id}/protocol-mappers/models","-r",realm])
    return client,scopes,mappers

def normalized_mapper(x):
    return {
      "protocol":x.get("protocol"),
      "protocolMapper":x.get("protocolMapper"),
      "config":{k:str(v).lower() if isinstance(v,bool) else str(v) for k,v in (x.get("config") or {}).items()},
    }

def inspect(kc,realm,client_name):
    iid=client_id(kc,realm,client_name)
    if not iid:
        return {
          "exists":False,
          "clientDrift":True,
          "missingScopes":sorted(CANONICAL_SCOPES),
          "extraScopes":[],
          "mapperDrift":sorted(MAPPERS),
        }
    client,scopes,mappers=current(kc,realm,iid)
    client_drift=any(client.get(k)!=v for k,v in EXPECTED_CLIENT.items()) or client.get("clientId")!=client_name
    have_scopes={x["name"] for x in scopes}
    by_name={x.get("name"):normalized_mapper(x) for x in mappers}
    mapper_drift=[]
    for name,wanted in MAPPERS.items():
        got=by_name.get(name)
        if got!=wanted: mapper_drift.append(name)
    return {
      "exists":True,
      "id":iid,
      "clientDrift":client_drift,
      "missingScopes":sorted(CANONICAL_SCOPES-have_scopes),
      "extraScopes":sorted(have_scopes-CANONICAL_SCOPES),
      "mapperDrift":sorted(mapper_drift),
    }

def reconcile(kc,realm,client_name):
    iid=client_id(kc,realm,client_name)
    payload={**EXPECTED_CLIENT,"clientId":client_name,"clientAuthenticatorType":"client-secret","bearerOnly":False}
    if not iid:
        run(kc,["create","clients","-r",realm,"-s","clientId="+client_name,
                "-s","enabled=true","-s","protocol=openid-connect","-s","publicClient=false",
                "-s","serviceAccountsEnabled=true","-s","standardFlowEnabled=false",
                "-s","directAccessGrantsEnabled=false","-s","clientAuthenticatorType=client-secret"])
        iid=client_id(kc,realm,client_name)
        if not iid: raise ProvisionError("CLIENT_CREATE_NOT_VISIBLE")
    else:
        run(kc,["update",f"clients/{iid}","-r",realm,
                "-s","enabled=true","-s","protocol=openid-connect","-s","publicClient=false",
                "-s","serviceAccountsEnabled=true","-s","standardFlowEnabled=false",
                "-s","directAccessGrantsEnabled=false","-s","clientAuthenticatorType=client-secret"])

    scopes=scope_map(kc,realm)
    missing=CANONICAL_SCOPES-set(x["name"] for x in jrun(kc,["get",f"clients/{iid}/default-client-scopes","-r",realm,"--fields","name"]))
    for name in sorted(missing):
        sid=scopes.get(name)
        if not sid: raise ProvisionError("REQUIRED_SCOPE_NOT_FOUND="+name)
        run(kc,["update",f"clients/{iid}/default-client-scopes/{sid}","-r",realm])

    existing=jrun(kc,["get",f"clients/{iid}/protocol-mappers/models","-r",realm])
    by_name={x.get("name"):x for x in existing}
    for name,wanted in MAPPERS.items():
        body={"name":name,**wanted}
        old=by_name.get(name)
        if old is None:
            run(kc,["create",f"clients/{iid}/protocol-mappers/models","-r",realm,"-f","-"],json.dumps(body))
        elif normalized_mapper(old)!=wanted:
            run(kc,["update",f"clients/{iid}/protocol-mappers/models/{old['id']}","-r",realm,"-f","-"],json.dumps(body))

def main():
    p=argparse.ArgumentParser()
    p.add_argument("mode",choices=("plan","apply","verify"))
    p.add_argument("--kcadm",default="/opt/keycloak/bin/kcadm.sh")
    p.add_argument("--container",default=None,help="Execute kcadm in this Docker container")
    p.add_argument("--realm",default="ouf")
    p.add_argument("--client-id",required=True)
    a=p.parse_args()

    kc=("docker","exec","-i",a.container,a.kcadm) if a.container else a.kcadm
    before=inspect(kc,a.realm,a.client_id)
    drift=(not before["exists"] or before["clientDrift"] or before["missingScopes"] or before["mapperDrift"])
    print("CLIENT_ID="+a.client_id)
    print("EXISTS="+str(before["exists"]).lower())
    print("CLIENT_DRIFT="+str(bool(before["clientDrift"])).lower())
    print("MISSING_SCOPES="+(",".join(before["missingScopes"]) if before["missingScopes"] else "NONE"))
    print("EXTRA_SCOPES="+(",".join(before["extraScopes"]) if before["extraScopes"] else "NONE"))
    print("MAPPER_DRIFT="+(",".join(before["mapperDrift"]) if before["mapperDrift"] else "NONE"))
    print("DRIFT="+str(bool(drift)).lower())

    if a.mode=="plan":
        print("NO_WRITES=true")
        return
    if a.mode=="apply":
        reconcile(kc,a.realm,a.client_id)

    after=inspect(kc,a.realm,a.client_id)
    remaining=(not after["exists"] or after["clientDrift"] or after["missingScopes"] or after["mapperDrift"])
    print("VERIFY="+("PASS" if not remaining else "FAIL"))
    print("SECRET_PRINTED=false")
    if remaining: raise ProvisionError("RECONCILE_INCOMPLETE")

if __name__=="__main__":
    try: main()
    except (OSError,KeyError,TypeError,ValueError,ProvisionError) as e:
        print("WORKLOAD_CLIENT_PROVISION_BLOCKED="+str(e),file=sys.stderr)
        raise SystemExit(1)
