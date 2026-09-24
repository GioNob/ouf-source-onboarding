#!/usr/bin/env python3
"""Safe trusted-HUMAN lifecycle for adding registered capabilities to ACTIVE policy.

Default action is --plan. Mutating phases are explicit and resumable through a
0600 state file. The script never prints bearer tokens or full policy/grant
payloads. It preserves all existing grants and capabilities byte-semantically
at the JSON object level and refuses preview drift before publish.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import stat
import sys
import time
from datetime import datetime, timezone
import urllib.error
import urllib.parse
import urllib.request

ISSUER = "https://auth.ouf-lab.it/realms/ouf"
DEFAULT_BASE = "https://api.ouf-lab.it/api/trusted-human/v1/authorization"


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"))


def digest(value):
    return hashlib.sha256(canonical(value).encode("utf-8")).hexdigest()


def normalize_descriptor(value):
    if not isinstance(value, dict):
        raise ValueError("invalid descriptor")
    required={"capabilityId","operation","requiredScope","allowedActors"}
    if set(value) != required or not isinstance(value.get("allowedActors"), list):
        raise ValueError("invalid descriptor")
    return {**value, "allowedActors": sorted(value["allowedActors"])}


def manifest_descriptors(path):
    rows=json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(rows,list) or not rows:
        raise ValueError("manifest must be a nonempty array")
    result=[]
    ids=set()
    for row in rows:
        if not isinstance(row,dict) or set(row)!={"ownerRef","descriptor"}:
            raise ValueError("invalid manifest row")
        descriptor=normalize_descriptor(row["descriptor"])
        ident=descriptor["capabilityId"]
        if not isinstance(ident,str) or not ident or ident in ids:
            raise ValueError("invalid or duplicate capabilityId")
        ids.add(ident);result.append(descriptor)
    return result


def build_candidate(active_view, desired, now=None):
    if not isinstance(active_view,dict) or not isinstance(active_view.get("policy"),dict):
        raise ValueError("invalid active policy view")
    active=active_view["policy"]
    for key in ("bundleId","version","publishedAt","capabilities","grants"):
        if key not in active:
            raise ValueError("incomplete active policy")
    if not isinstance(active["capabilities"],list) or not isinstance(active["grants"],list):
        raise ValueError("invalid active policy collections")
    existing={}
    for cap in active["capabilities"]:
        norm=normalize_descriptor(cap)
        ident=norm["capabilityId"]
        if ident in existing:
            raise ValueError("duplicate capability in ACTIVE")
        existing[ident]=norm
    missing=[]
    for descriptor in desired:
        ident=descriptor["capabilityId"]
        if ident in existing:
            if existing[ident] != descriptor:
                raise ValueError("ACTIVE_SEMANTIC_CONFLICT="+ident)
        else:
            missing.append(descriptor)
    when=now or datetime.now(timezone.utc).isoformat().replace("+00:00","Z")
    candidate={
        "bundleId":active["bundleId"],
        "version":int(active["version"])+1,
        "publishedAt":when,
        "capabilities":[*active["capabilities"],*missing],
        "grants":active["grants"],
    }
    return candidate,missing


def validate_preview(preview, expected_added):
    if not isinstance(preview,dict):
        raise ValueError("invalid preview")
    added=sorted(normalize_descriptor(x)["capabilityId"] for x in preview.get("addedCapabilities",[]))
    expected=sorted(x["capabilityId"] for x in expected_added)
    if added != expected:
        raise ValueError("PREVIEW_ADDED_CAPABILITIES_MISMATCH")
    if preview.get("removedCapabilities"):
        raise ValueError("PREVIEW_REMOVES_CAPABILITIES")
    if preview.get("grantChanges"):
        raise ValueError("PREVIEW_CHANGES_GRANTS")
    return True


def verify_active(active_view,state):
    policy=active_view.get("policy") if isinstance(active_view,dict) else None
    if not isinstance(policy,dict):
        raise ValueError("invalid active policy view")
    if policy.get("bundleId") != state["bundleId"] or int(policy.get("version",-1)) != state["targetVersion"]:
        raise ValueError("ACTIVE_TARGET_IDENTITY_MISMATCH")
    ids=sorted(normalize_descriptor(x)["capabilityId"] for x in policy.get("capabilities",[]))
    if ids != sorted(state["targetCapabilityIds"]):
        raise ValueError("ACTIVE_CAPABILITY_SET_MISMATCH")
    if digest(policy.get("grants",[])) != state["baselineGrantsHash"]:
        raise ValueError("ACTIVE_GRANTS_CHANGED")
    return True


def device_login():
    def post(url, values):
        req=urllib.request.Request(url,data=urllib.parse.urlencode(values).encode("ascii"),method="POST",
                                   headers={"Content-Type":"application/x-www-form-urlencoded"})
        try:
            with urllib.request.urlopen(req,timeout=20) as response:return response.status,json.load(response)
        except urllib.error.HTTPError as exc:
            try:return exc.code,json.load(exc)
            except (ValueError,UnicodeError):return exc.code,{}
    with urllib.request.urlopen(ISSUER+"/.well-known/openid-configuration",timeout=20) as response:
        metadata=json.load(response)
    if metadata.get("issuer")!=ISSUER:raise ValueError("unexpected OIDC issuer")
    device=metadata.get("device_authorization_endpoint");token_endpoint=metadata.get("token_endpoint")
    if not all(isinstance(x,str) and x.startswith(ISSUER+"/protocol/openid-connect/") for x in (device,token_endpoint)):
        raise ValueError("unexpected OIDC endpoints")
    status,start=post(device,{"client_id":"ouf-human-admin","scope":"openid authorization.policy.admin"})
    if status!=200 or not all(start.get(k) for k in ("device_code","user_code","verification_uri","expires_in")):
        raise RuntimeError("DEVICE_AUTHORIZATION_FAILED")
    print("OPEN_IN_BROWSER="+str(start["verification_uri"]),flush=True)
    print("ENTER_DEVICE_CODE="+str(start["user_code"]),flush=True)
    interval=max(5,min(30,int(start.get("interval",5))))
    deadline=time.monotonic()+min(600,int(start["expires_in"]))
    while time.monotonic()<deadline:
        time.sleep(interval)
        status,result=post(token_endpoint,{"grant_type":"urn:ietf:params:oauth:grant-type:device_code",
                                          "client_id":"ouf-human-admin","device_code":start["device_code"]})
        if status==200:
            token=result.get("access_token")
            if not isinstance(token,str) or not token:raise RuntimeError("TOKEN_MISSING")
            return token
        if result.get("error")=="slow_down":interval=min(30,interval+5)
        elif result.get("error")!="authorization_pending":raise RuntimeError("DEVICE_LOGIN_NOT_COMPLETED")
    raise RuntimeError("DEVICE_LOGIN_EXPIRED")


def request(base,token,path,method="GET",body=None,etag=None):
    data=None if body is None else json.dumps(body,separators=(",",":")).encode()
    headers={"Authorization":"Bearer "+token,"Accept":"application/json"}
    if data is not None:headers["Content-Type"]="application/json"
    if etag is not None:headers["If-Match"]='"'+str(etag)+'"'
    req=urllib.request.Request(base.rstrip("/")+path,data=data,method=method,headers=headers)
    try:
        with urllib.request.urlopen(req,timeout=30) as response:
            raw=response.read(8_000_000)
            value=json.loads(raw) if raw else None
            return response.status,value,response.headers
    except urllib.error.HTTPError as exc:
        raise RuntimeError("HTTP_"+str(exc.code)) from None


def token_from_args(args):
    if args.device_login == (args.token_file is not None):
        raise ValueError("choose exactly one of --device-login and --token-file")
    if args.device_login:return device_login()
    info=args.token_file.lstat()
    if not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode)!=0o600 or info.st_uid!=os.geteuid():
        raise ValueError("token file must be regular, owned by caller, mode 0600")
    token=args.token_file.read_text(encoding="ascii").strip()
    if not token or len(token)>16384:raise ValueError("invalid token file")
    return token


def read_state(path):
    info=path.lstat()
    if not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode)!=0o600 or info.st_uid!=os.geteuid():
        raise ValueError("state file must be regular, owned by caller, mode 0600")
    state=json.loads(path.read_text())
    if not isinstance(state,dict) or not state.get("draftId"):raise ValueError("invalid state file")
    return state


def write_state(path,state):
    path.parent.mkdir(parents=True,exist_ok=True)
    tmp=path.with_name(path.name+".tmp")
    fd=os.open(tmp,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
    with os.fdopen(fd,"w") as out:json.dump(state,out,indent=2,sort_keys=True);out.write("\n")
    os.replace(tmp,path);os.chmod(path,0o600)


def active(base,token):
    _,value,_=request(base,token,"/policies/active")
    return value


def preview(base,token,state):
    _,value,_=request(base,token,f"/policies/{state['draftId']}:preview","POST",etag=state["revision"])
    validate_preview(value,[{"capabilityId":x,"operation":state["addedDescriptors"][x]["operation"],
                             "requiredScope":state["addedDescriptors"][x]["requiredScope"],
                             "allowedActors":state["addedDescriptors"][x]["allowedActors"]}
                            for x in state["addedCapabilityIds"]])
    return value


def main():
    p=argparse.ArgumentParser()
    p.add_argument("--manifest",type=Path)
    p.add_argument("--base-url",default=DEFAULT_BASE)
    p.add_argument("--state-file",type=Path)
    p.add_argument("--scenario",type=Path)
    p.add_argument("--token-file",type=Path)
    p.add_argument("--device-login",action="store_true")
    p.add_argument("--confirm-publish",action="store_true")
    mode=p.add_mutually_exclusive_group()
    mode.add_argument("--plan",action="store_true")
    mode.add_argument("--draft",action="store_true")
    mode.add_argument("--preview",action="store_true")
    mode.add_argument("--simulate",action="store_true")
    mode.add_argument("--publish",action="store_true")
    mode.add_argument("--verify",action="store_true")
    args=p.parse_args()
    if not args.base_url.startswith("https://") or "?" in args.base_url or "#" in args.base_url:
        raise ValueError("HTTPS base URL without query required")
    if not any((args.plan,args.draft,args.preview,args.simulate,args.publish,args.verify)):args.plan=True
    token=token_from_args(args)

    if args.plan or args.draft:
        if args.manifest is None:raise ValueError("--manifest required")
        desired=manifest_descriptors(args.manifest)
        current=active(args.base_url,token)
        candidate,missing=build_candidate(current,desired)
        print("ACTIVE_POLICY_REF="+str(current.get("policyRef")))
        print("ACTIVE_CONTENT_HASH="+str(current.get("contentHash")))
        print(f"ACTIVE_CAPABILITIES={len(current['policy']['capabilities'])} ACTIVE_GRANTS={len(current['policy']['grants'])}")
        print("ADD_CAPABILITIES="+(",".join(x["capabilityId"] for x in missing) if missing else "NONE"))
        print("TARGET_POLICY_REF="+candidate["bundleId"]+":"+str(candidate["version"]))
        print("GRANTS_PRESERVED=true")
        if args.plan:
            print("NO_WRITES=true")
            return
        if not missing:raise ValueError("NO_POLICY_CHANGE_REQUIRED")
        if args.state_file is None:raise ValueError("--state-file required for --draft")
        status,draft,headers=request(args.base_url,token,"/policies","POST",candidate)
        if status!=200 or not isinstance(draft,dict):raise RuntimeError("DRAFT_CREATE_FAILED")
        etag=headers.get("ETag","").strip('"')
        if not etag.isdigit():raise RuntimeError("DRAFT_ETAG_MISSING")
        state={
            "formatVersion":1,"draftId":draft["id"],"revision":int(etag),
            "baseActiveRef":current["policyRef"],"bundleId":candidate["bundleId"],
            "targetVersion":candidate["version"],
            "baselineGrantsHash":digest(current["policy"]["grants"]),
            "baselineCapabilityIds":sorted(x["capabilityId"] for x in current["policy"]["capabilities"]),
            "targetCapabilityIds":sorted(x["capabilityId"] for x in candidate["capabilities"]),
            "addedCapabilityIds":sorted(x["capabilityId"] for x in missing),
            "addedDescriptors":{x["capabilityId"]:normalize_descriptor(x) for x in missing},
            "activeContentHash":current.get("contentHash"),
        }
        value=preview(args.base_url,token,state)
        state["activeHash"]=value.get("activeHash");state["draftHash"]=value.get("draftHash")
        write_state(args.state_file,state)
        print("DRAFT_ID="+state["draftId"])
        print("DRAFT_REVISION="+str(state["revision"]))
        print("PREVIEW_ADDED="+(",".join(state["addedCapabilityIds"]) or "NONE"))
        print("PREVIEW_REMOVED=0 PREVIEW_GRANT_CHANGES=0")
        print("STATE_FILE="+str(args.state_file))
        print("POLICY_NOT_PUBLISHED=true")
        return

    if args.state_file is None:raise ValueError("--state-file required")
    state=read_state(args.state_file)
    if args.preview:
        value=preview(args.base_url,token,state)
        print("PREVIEW_OK=true DRAFT_HASH="+str(value.get("draftHash")))
        print("NO_WRITES=true")
        return
    if args.simulate:
        if args.scenario is None:raise ValueError("--scenario required for --simulate")
        scenario=json.loads(args.scenario.read_text())
        _,value,_=request(args.base_url,token,f"/policies/{state['draftId']}:simulate","POST",scenario,state["revision"])
        print("SIMULATION_CONTEXT="+str(value.get("contextSource")))
        print("SIMULATION_BEFORE="+str((value.get("before") or {}).get("code")))
        print("SIMULATION_AFTER="+str((value.get("after") or {}).get("code")))
        print("SIMULATION_AUTHORITATIVE="+str(value.get("authoritative")).lower())
        print("NO_POLICY_WRITE=true")
        return
    if args.verify:
        current=active(args.base_url,token);verify_active(current,state)
        print("ACTIVE_VERIFIED=true POLICY_REF="+str(current.get("policyRef")))
        print("GRANTS_PRESERVED=true")
        return
    if args.publish:
        if not args.confirm_publish:raise ValueError("--confirm-publish required")
        preview(args.base_url,token,state)
        status,value,headers=request(args.base_url,token,f"/policies/{state['draftId']}:publish","POST",etag=state["revision"])
        if status!=200 or not isinstance(value,dict) or value.get("state")!="PUBLISHED":
            raise RuntimeError("PUBLISH_FAILED")
        current=active(args.base_url,token);verify_active(current,state)
        state["published"]=True;state["publishedPolicyRef"]=current.get("policyRef");state["publishedContentHash"]=current.get("contentHash")
        write_state(args.state_file,state)
        print("PUBLISHED=true POLICY_REF="+str(current.get("policyRef")))
        print("ACTIVE_VERIFIED=true GRANTS_PRESERVED=true")
        return
    raise ValueError("unsupported mode")


if __name__=="__main__":
    try:main()
    except (OSError,UnicodeError,ValueError,RuntimeError,json.JSONDecodeError,KeyError) as exc:
        print("POLICY_LIFECYCLE_BLOCKED="+str(exc),file=sys.stderr)
        raise SystemExit(1) from None
