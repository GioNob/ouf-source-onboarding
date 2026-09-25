#!/usr/bin/env python3
"""Governed InstallationConfiguration service-binding update.

Derives one immutable revision from ACTIVE by merging declared
networking.serviceBindings. The tool proves no other payload field changes,
validates the environment, activates only on PASS, exports the ACTIVE
projection and atomically replaces the local projection.
"""
from __future__ import annotations
import argparse, base64, copy, json, os, re, tempfile, time, urllib.error, urllib.parse, urllib.request, uuid
from pathlib import Path

ISSUER="https://auth.ouf-lab.it/realms/ouf"
API_BASE="https://api.ouf-lab.it"
CLIENT_ID="ouf-human-admin"
AUDIENCE="ouf-api-gateway"
REQUIRED_SCOPES={
    "installation.configuration.read",
    "installation.configuration.write",
    "installation.configuration.activate",
    "installation.configuration.export",
}
NAME_RE=re.compile(r"^[A-Za-z0-9._-]{1,128}$")

class LifecycleError(RuntimeError): pass

def jwt_claims(token):
    part=token.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(part+"="*(-len(part)%4)))

def audience_has(value,wanted):
    return value==wanted or (isinstance(value,list) and wanted in value)

def validate_claims(c):
    scopes=set(str(c.get("scope","")).split())
    if c.get("iss")!=ISSUER: raise LifecycleError("UNEXPECTED_ISSUER")
    if not audience_has(c.get("aud"),AUDIENCE): raise LifecycleError("GATEWAY_AUDIENCE_MISSING")
    if c.get("tenant_id")!="ouf-lab": raise LifecycleError("UNEXPECTED_TENANT")
    if c.get("ouf_actor_type")!="HUMAN": raise LifecycleError("HUMAN_REQUIRED")
    missing=sorted(REQUIRED_SCOPES-scopes)
    if missing: raise LifecycleError("MISSING_SCOPES:"+",".join(missing))
    if not c.get("acr"): raise LifecycleError("ACR_REQUIRED")

def post_form(url,values):
    req=urllib.request.Request(url,data=urllib.parse.urlencode(values).encode("ascii"),
        headers={"Content-Type":"application/x-www-form-urlencoded"},method="POST")
    try:
        with urllib.request.urlopen(req,timeout=20) as r:return r.status,json.load(r)
    except urllib.error.HTTPError as e:
        try:return e.code,json.load(e)
        except Exception:return e.code,{}

def device_login():
    with urllib.request.urlopen(ISSUER+"/.well-known/openid-configuration",timeout=20) as r:md=json.load(r)
    scope="openid "+" ".join(sorted(REQUIRED_SCOPES))
    status,start=post_form(md["device_authorization_endpoint"],{"client_id":CLIENT_ID,"scope":scope})
    if status!=200: raise LifecycleError("DEVICE_AUTHORIZATION_FAILED")
    print("OPEN_IN_BROWSER="+start["verification_uri"],flush=True)
    print("ENTER_DEVICE_CODE="+start["user_code"],flush=True)
    interval=max(5,min(30,int(start.get("interval",5))))
    deadline=time.monotonic()+min(600,int(start["expires_in"]))
    while time.monotonic()<deadline:
        time.sleep(interval)
        status,result=post_form(md["token_endpoint"],{
            "client_id":CLIENT_ID,
            "grant_type":"urn:ietf:params:oauth:grant-type:device_code",
            "device_code":start["device_code"]})
        if status==200:
            token=result.get("access_token")
            if not token: raise LifecycleError("TOKEN_MISSING")
            validate_claims(jwt_claims(token))
            return token
        if result.get("error")=="slow_down": interval=min(30,interval+5)
        elif result.get("error")!="authorization_pending": raise LifecycleError("DEVICE_LOGIN_NOT_COMPLETED")
    raise LifecycleError("DEVICE_LOGIN_EXPIRED")

def http_json(method,path,token,body=None,correlation=None):
    h={"Authorization":"Bearer "+token,"Accept":"application/json"}
    if correlation:h["X-Correlation-ID"]=correlation
    data=None
    if body is not None:
        h["Content-Type"]="application/json";data=json.dumps(body,separators=(",",":")).encode()
    req=urllib.request.Request(API_BASE+path,data=data,headers=h,method=method)
    try:
        with urllib.request.urlopen(req,timeout=30) as r:
            raw=r.read(5*1024*1024+1)
            if len(raw)>5*1024*1024: raise LifecycleError("HTTP_RESPONSE_TOO_LARGE")
            return r.status,(json.loads(raw) if raw else {}),dict(r.headers.items())
    except urllib.error.HTTPError as e:
        try:v=json.loads(e.read())
        except Exception:v={}
        return e.code,v,dict(e.headers.items())

def derive(source,bindings):
    out=copy.deepcopy(source)
    networking=out.get("networking")
    if not isinstance(networking,dict): raise LifecycleError("SOURCE_NETWORKING_INVALID")
    current=networking.get("serviceBindings")
    if current is None: current={}
    if not isinstance(current,dict): raise LifecycleError("SOURCE_SERVICE_BINDINGS_INVALID")
    merged=dict(current)
    for k,v in bindings.items():
        if k in merged and merged[k]!=v: raise LifecycleError("SERVICE_BINDING_CONFLICT="+k)
        merged[k]=v
    networking["serviceBindings"]=dict(sorted(merged.items()))
    return out

def verify_only_change(source,candidate,bindings):
    expected=derive(source,bindings)
    if candidate!=expected: raise LifecycleError("CANDIDATE_CONTAINS_EXTRA_CHANGES")

def atomic_write(path,value):
    path=path.resolve();path.parent.mkdir(parents=True,exist_ok=True)
    fd,tmp=tempfile.mkstemp(prefix="."+path.name+"-",dir=path.parent)
    try:
        with os.fdopen(fd,"w") as f:
            os.fchmod(f.fileno(),0o600)
            json.dump(value,f,indent=2,sort_keys=True);f.write("\n");f.flush();os.fsync(f.fileno())
        os.replace(tmp,path)
    finally:
        Path(tmp).unlink(missing_ok=True)

def parse_bindings(values):
    result={}
    for raw in values:
        if "=" not in raw: raise LifecycleError("INVALID_BINDING")
        k,v=raw.split("=",1)
        if not NAME_RE.fullmatch(k) or not NAME_RE.fullmatch(v): raise LifecycleError("INVALID_BINDING")
        if k in result and result[k]!=v: raise LifecycleError("DUPLICATE_BINDING="+k)
        result[k]=v
    if not result: raise LifecycleError("NO_BINDINGS")
    return result

def main():
    p=argparse.ArgumentParser()
    p.add_argument("mode",choices=("plan","apply"))
    p.add_argument("--installation-id",required=True)
    p.add_argument("--expected-source-revision",type=int,required=True)
    p.add_argument("--binding",action="append",default=[])
    p.add_argument("--projection-output",type=Path)
    p.add_argument("--device-login",action="store_true")
    a=p.parse_args()
    bindings=parse_bindings(a.binding)

    print("MODE="+a.mode)
    print("INSTALLATION_ID="+a.installation_id)
    print("SOURCE_REVISION="+str(a.expected_source_revision))
    print("SERVICE_BINDINGS="+",".join(f"{k}={bindings[k]}" for k in sorted(bindings)))

    if a.mode=="plan":
        print("ONLY_DECLARED_CHANGE=true")
        print("NO_NETWORK_CALL=true")
        print("NO_REVISION_CREATED=true")
        return

    if not a.device_login: raise LifecycleError("DEVICE_LOGIN_REQUIRED")
    if a.projection_output is None: raise LifecycleError("PROJECTION_OUTPUT_REQUIRED")
    token=device_login();corr="installation-services-"+str(uuid.uuid4())

    status,active,_=http_json("GET",f"/api/trusted-human/v1/installations/{a.installation_id}/active",token,correlation=corr)
    if status!=200 or active.get("revision")!=a.expected_source_revision:
        raise LifecycleError("ACTIVE_SOURCE_REVISION_CHANGED")
    source=active.get("payload")
    if not isinstance(source,dict): raise LifecycleError("ACTIVE_PAYLOAD_MISSING")

    candidate=derive(source,bindings);verify_only_change(source,candidate,bindings)

    status,created,_=http_json("POST",f"/api/trusted-human/v1/installations/{a.installation_id}/revisions",token,body=candidate,correlation=corr)
    if status!=201: raise LifecycleError("REVISION_CREATE_HTTP_"+str(status))
    rev=created.get("revision")
    if not isinstance(rev,int) or rev<=a.expected_source_revision: raise LifecycleError("REVISION_CREATE_INVALID")
    if created.get("validationState")!="VALIDATED": raise LifecycleError("REVISION_NOT_VALIDATED")

    status,validation,_=http_json("POST",f"/api/trusted-human/v1/installations/{a.installation_id}/revisions/{rev}:validate-environment",token,correlation=corr)
    if status!=200 or validation.get("overallStatus")!="PASS": raise LifecycleError("ENVIRONMENT_VALIDATION_NOT_PASS")

    status,activated,_=http_json("POST",f"/api/trusted-human/v1/installations/{a.installation_id}/revisions/{rev}:activate",token,correlation=corr)
    if status!=200 or activated.get("revision")!=rev: raise LifecycleError("ACTIVATION_FAILED")

    status,projection,headers=http_json("GET",f"/api/trusted-human/v1/installations/{a.installation_id}/projection?revision={rev}",token,correlation=corr)
    if status!=200 or projection.get("revision")!=rev: raise LifecycleError("PROJECTION_EXPORT_FAILED")
    projected=((projection.get("services") or {}).get("bindings") or {})
    for k,v in bindings.items():
        if projected.get(k)!=v: raise LifecycleError("PROJECTION_BINDING_MISSING="+k)

    atomic_write(a.projection_output,projection)
    print("NEW_REVISION="+str(rev))
    print("ENVIRONMENT_VALIDATION=PASS")
    print("ACTIVATION=PASS")
    print("PROJECTION_EXPORT=PASS")
    print("ACTIVE_PROJECTION_REPLACED=true")

if __name__=="__main__":
    try: main()
    except (OSError,ValueError,KeyError,TypeError,json.JSONDecodeError,LifecycleError) as exc:
        print("INSTALLATION_SERVICE_BINDING_UPDATE_BLOCKED="+str(exc),file=__import__("sys").stderr)
        raise SystemExit(1) from None
