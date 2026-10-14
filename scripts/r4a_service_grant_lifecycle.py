#!/usr/bin/env python3
"""Governed lifecycle for adding stable SERVICE grants to the ACTIVE OUF policy.

The manifest contains complete Authorization Grant objects. The workflow is
plan -> draft -> preview -> publish -> verify. Existing capabilities and grants
must remain unchanged; only missing manifest grants may be added. Device Flow
uses ouf-human-admin and keeps bearer tokens in memory only.
"""
from __future__ import annotations
import argparse, hashlib, json, os, stat, sys, time, urllib.error, urllib.parse, urllib.request
from pathlib import Path

ISSUER="https://auth.ouf-lab.it/realms/ouf"
DEFAULT_BASE="https://api.ouf-lab.it/api/trusted-human/v1/authorization"

def canonical(v): return json.dumps(v,sort_keys=True,separators=(",",":"))
def digest(v): return hashlib.sha256(canonical(v).encode()).hexdigest()

def normalize_grant(g):
    if not isinstance(g,dict): return g
    value=dict(g)
    # AuthorizationPolicy.Grant serializes constraints with NON_NULL, so a
    # semantically null constraint may be absent in API responses.
    value.setdefault("constraints",None)
    return value

def manifest(path):
    rows=json.loads(path.read_text())
    if not isinstance(rows,list) or not rows: raise ValueError("manifest must be nonempty array")
    ids=set()
    for g in rows:
        if not isinstance(g,dict): raise ValueError("invalid grant")
        required={"grantId","capabilityId","tenantId","subjectId","servicePrincipalId","organizationId","validFrom","validUntil","constraints"}
        if set(g)!=required: raise ValueError("invalid grant fields")
        if g["grantId"] in ids: raise ValueError("duplicate grantId")
        ids.add(g["grantId"])
        if g["subjectId"] is not None: raise ValueError("SERVICE grant subjectId must be null")
        if not isinstance(g["servicePrincipalId"],str) or not g["servicePrincipalId"]: raise ValueError("servicePrincipalId required")
    return rows

def device_login():
    def post(url,values):
        req=urllib.request.Request(url,data=urllib.parse.urlencode(values).encode(),method="POST",
            headers={"Content-Type":"application/x-www-form-urlencoded"})
        try:
            with urllib.request.urlopen(req,timeout=20) as r:return r.status,json.load(r)
        except urllib.error.HTTPError as e:
            try:return e.code,json.load(e)
            except Exception:return e.code,{}
    with urllib.request.urlopen(ISSUER+"/.well-known/openid-configuration",timeout=20) as r:m=json.load(r)
    status,start=post(m["device_authorization_endpoint"],{"client_id":"ouf-human-admin","scope":"openid authorization.policy.admin"})
    if status!=200: raise RuntimeError("DEVICE_AUTHORIZATION_FAILED")
    print("OPEN_IN_BROWSER="+start["verification_uri"],flush=True)
    print("ENTER_DEVICE_CODE="+start["user_code"],flush=True)
    interval=max(5,min(30,int(start.get("interval",5))))
    deadline=time.monotonic()+min(600,int(start["expires_in"]))
    while time.monotonic()<deadline:
        time.sleep(interval)
        status,result=post(m["token_endpoint"],{"grant_type":"urn:ietf:params:oauth:grant-type:device_code","client_id":"ouf-human-admin","device_code":start["device_code"]})
        if status==200:return result["access_token"]
        if result.get("error")=="slow_down": interval=min(30,interval+5)
        elif result.get("error")!="authorization_pending": raise RuntimeError("DEVICE_LOGIN_NOT_COMPLETED")
    raise RuntimeError("DEVICE_LOGIN_EXPIRED")

def request(base,token,path,method="GET",body=None,etag=None):
    data=None if body is None else json.dumps(body,separators=(",",":")).encode()
    h={"Authorization":"Bearer "+token,"Accept":"application/json"}
    if data is not None:h["Content-Type"]="application/json"
    if etag is not None:h["If-Match"]='"'+str(etag)+'"'
    req=urllib.request.Request(base.rstrip("/")+path,data=data,method=method,headers=h)
    try:
        with urllib.request.urlopen(req,timeout=30) as r:
            raw=r.read(8_000_000)
            return r.status,(json.loads(raw) if raw else None),r.headers
    except urllib.error.HTTPError as e:
        raise RuntimeError("HTTP_"+str(e.code)) from None

def active(base,token):
    return request(base,token,"/policies/active")[1]

def read_state(path):
    st=path.lstat()
    if not stat.S_ISREG(st.st_mode) or stat.S_IMODE(st.st_mode)!=0o600 or st.st_uid!=os.geteuid(): raise ValueError("state file must be 0600 owned by caller")
    return json.loads(path.read_text())

def write_state(path,state):
    fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
    with os.fdopen(fd,"w") as f: json.dump(state,f,indent=2,sort_keys=True);f.write("\n")
    os.chmod(path,0o600)

def plan(active_view,desired):
    p=active_view["policy"]
    byid={g["grantId"]:normalize_grant(g) for g in p["grants"]}
    missing=[]
    for g in desired:
        old=byid.get(g["grantId"])
        if old is None: missing.append(g)
        elif old!=normalize_grant(g): raise ValueError("GRANT_SEMANTIC_CONFLICT="+g["grantId"])
    capids={c["capabilityId"] for c in p["capabilities"]}
    for g in desired:
        if g["capabilityId"] not in capids: raise ValueError("CAPABILITY_NOT_ACTIVE="+g["capabilityId"])
    return p,missing

def preview(base,token,state):
    value=request(base,token,f"/policies/{state['draftId']}:preview","POST",etag=state["revision"])[1]
    if value.get("addedCapabilities") or value.get("removedCapabilities"): raise ValueError("PREVIEW_CAPABILITY_CHANGE")
    changes=value.get("grantChanges") or []
    actual=sorted(x.get("grantId") for x in changes)
    expected=sorted(state["addedGrantIds"])
    if actual!=expected: raise ValueError("PREVIEW_GRANT_CHANGE_MISMATCH")
    for x in changes:
        if x.get("before") is not None or x.get("after") is None: raise ValueError("PREVIEW_NOT_ADD_ONLY")
    return value

def verify(active_view,state):
    p=active_view["policy"]
    if active_view.get("policyRef")!=state["targetPolicyRef"]: raise ValueError("ACTIVE_REF_MISMATCH")
    if digest(p["capabilities"])!=state["capabilitiesHash"]: raise ValueError("CAPABILITIES_CHANGED")
    byid={g["grantId"]:normalize_grant(g) for g in p["grants"]}
    for gid,wanted in state["desiredGrants"].items():
        if byid.get(gid)!=normalize_grant(wanted): raise ValueError("GRANT_NOT_ACTIVE="+gid)
    baseline=[g for g in p["grants"] if g["grantId"] not in state["addedGrantIds"]]
    if digest(baseline)!=state["baselineGrantsHash"]: raise ValueError("EXISTING_GRANTS_CHANGED")

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--manifest",type=Path)
    p.add_argument("--state-file",type=Path)
    p.add_argument("--base-url",default=DEFAULT_BASE)
    p.add_argument("--device-login",action="store_true")
    p.add_argument("--confirm-publish",action="store_true")
    m=p.add_mutually_exclusive_group()
    m.add_argument("--plan",action="store_true");m.add_argument("--draft",action="store_true");m.add_argument("--preview",action="store_true");m.add_argument("--publish",action="store_true");m.add_argument("--verify",action="store_true")
    a=p.parse_args()
    if not a.device_login: raise ValueError("--device-login required")
    token=device_login()
    if a.plan or a.draft:
        if not a.manifest: raise ValueError("--manifest required")
        desired=manifest(a.manifest);cur=active(a.base_url,token);policy,missing=plan(cur,desired)
        print("ACTIVE_POLICY_REF="+cur["policyRef"])
        print("ACTIVE_CAPABILITIES="+str(len(policy["capabilities"]))+" ACTIVE_GRANTS="+str(len(policy["grants"])))
        print("ADD_GRANTS="+(",".join(g["grantId"] for g in missing) if missing else "NONE"))
        print("EXISTING_GRANTS_PRESERVED=true")
        if a.plan:
            print("NO_WRITES=true");return
        if not missing: raise ValueError("NO_POLICY_CHANGE_REQUIRED")
        if not a.state_file: raise ValueError("--state-file required")
        candidate={**policy,"version":int(policy["version"])+1,"grants":[*policy["grants"],*missing]}
        status,draft,headers=request(a.base_url,token,"/policies","POST",candidate)
        etag=headers.get("ETag","").strip('"')
        if status!=200 or not etag.isdigit(): raise RuntimeError("DRAFT_CREATE_FAILED")
        state={
            "draftId":draft["id"],"revision":int(etag),"targetPolicyRef":candidate["bundleId"]+":"+str(candidate["version"]),
            "addedGrantIds":sorted(g["grantId"] for g in missing),"desiredGrants":{g["grantId"]:g for g in missing},
            "capabilitiesHash":digest(policy["capabilities"]),"baselineGrantsHash":digest(policy["grants"])
        }
        value=preview(a.base_url,token,state)
        write_state(a.state_file,state)
        print("DRAFT_ID="+state["draftId"]);print("DRAFT_REVISION="+str(state["revision"]))
        print("PREVIEW_GRANT_ADDS="+",".join(state["addedGrantIds"]))
        print("PREVIEW_CAPABILITY_CHANGES=0")
        print("STATE_FILE="+str(a.state_file));print("POLICY_NOT_PUBLISHED=true");return
    if not a.state_file: raise ValueError("--state-file required")
    state=read_state(a.state_file)
    if a.preview:
        v=preview(a.base_url,token,state);print("PREVIEW_OK=true DRAFT_HASH="+str(v.get("draftHash")));print("NO_WRITES=true");return
    if a.publish:
        if not a.confirm_publish: raise ValueError("--confirm-publish required")
        preview(a.base_url,token,state)
        status,value,_=request(a.base_url,token,f"/policies/{state['draftId']}:publish","POST",etag=state["revision"])
        if status!=200 or value.get("state")!="PUBLISHED": raise RuntimeError("PUBLISH_FAILED")
        cur=active(a.base_url,token);verify(cur,state)
        print("PUBLISHED=true POLICY_REF="+cur["policyRef"]);print("ACTIVE_VERIFIED=true EXISTING_GRANTS_PRESERVED=true");return
    if a.verify:
        cur=active(a.base_url,token);verify(cur,state)
        print("ACTIVE_VERIFIED=true POLICY_REF="+cur["policyRef"]);print("EXISTING_GRANTS_PRESERVED=true");return
    raise ValueError("choose a mode")

if __name__=="__main__":
    try:main()
    except (OSError,ValueError,RuntimeError,KeyError,json.JSONDecodeError) as e:
        print("SERVICE_GRANT_LIFECYCLE_BLOCKED="+str(e),file=sys.stderr);raise SystemExit(1)
