#!/usr/bin/env python3
"""Governed InstallationConfiguration workload update.

Derives exactly one new immutable revision from an existing payload by adding
one workload client binding. It never edits the active projection in place.

plan:
  - reads the source payload file
  - proves that the only candidate change is iam.workloadClients.<key>
  - performs no network call

apply:
  - authenticates the HUMAN installer through Keycloak Device Flow
  - POSTs the new immutable revision through Trusted Human Installation API
  - runs environment validation
  - activates only on PASS
  - exports the ACTIVE projection
  - atomically replaces the local active projection after revision/checksum match

The embedded payload.lifecycle object is legacy/non-authoritative metadata.
This tool preserves it byte-for-byte from the source payload. Authoritative
revision, validation state and checksum come from owner response/evidence.
"""
from __future__ import annotations

import argparse
import base64
import copy
import json
import os
from pathlib import Path
import re
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

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
WORKLOAD_KEY_RE=re.compile(r"^[A-Za-z][A-Za-z0-9._-]{0,63}$")
WORKLOAD_ID_RE=re.compile(r"^[A-Za-z0-9._:-]{1,128}$")


class LifecycleError(RuntimeError):
    pass


def jwt_claims(token:str)->dict:
    try:
        part=token.split(".")[1]
        return json.loads(base64.urlsafe_b64decode(part+"="*(-len(part)%4)))
    except Exception as exc:
        raise LifecycleError("INVALID_ACCESS_TOKEN") from exc


def audience_has(value,wanted):
    return value==wanted or (isinstance(value,list) and wanted in value)


def validate_claims(claims:dict)->None:
    scopes=set(str(claims.get("scope","")).split())
    if claims.get("iss")!=ISSUER:
        raise LifecycleError("UNEXPECTED_ISSUER")
    if not audience_has(claims.get("aud"),AUDIENCE):
        raise LifecycleError("GATEWAY_AUDIENCE_MISSING")
    if claims.get("tenant_id")!="ouf-lab":
        raise LifecycleError("UNEXPECTED_TENANT")
    if claims.get("ouf_actor_type")!="HUMAN":
        raise LifecycleError("HUMAN_REQUIRED")
    missing=sorted(REQUIRED_SCOPES-scopes)
    if missing:
        raise LifecycleError("MISSING_SCOPES:"+",".join(missing))
    if not claims.get("acr"):
        raise LifecycleError("ACR_REQUIRED")


def post_form(url:str,values:dict)->tuple[int,dict]:
    req=urllib.request.Request(
        url,
        data=urllib.parse.urlencode(values).encode("ascii"),
        headers={"Content-Type":"application/x-www-form-urlencoded"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req,timeout=20) as response:
            return response.status,json.load(response)
    except urllib.error.HTTPError as exc:
        try:return exc.code,json.load(exc)
        except Exception:return exc.code,{}


def device_login()->str:
    with urllib.request.urlopen(ISSUER+"/.well-known/openid-configuration",timeout=20) as response:
        metadata=json.load(response)
    if metadata.get("issuer")!=ISSUER:
        raise LifecycleError("OIDC_DISCOVERY_ISSUER_MISMATCH")
    device=metadata.get("device_authorization_endpoint")
    token_endpoint=metadata.get("token_endpoint")
    if not all(isinstance(x,str) and x.startswith(ISSUER+"/protocol/openid-connect/") for x in (device,token_endpoint)):
        raise LifecycleError("OIDC_ENDPOINT_INVALID")
    scope="openid "+" ".join(sorted(REQUIRED_SCOPES))
    status,start=post_form(device,{"client_id":CLIENT_ID,"scope":scope})
    if status!=200 or not all(start.get(k) for k in ("device_code","user_code","verification_uri","expires_in")):
        raise LifecycleError("DEVICE_AUTHORIZATION_FAILED")
    print("OPEN_IN_BROWSER="+str(start["verification_uri"]),flush=True)
    print("ENTER_DEVICE_CODE="+str(start["user_code"]),flush=True)
    interval=max(5,min(30,int(start.get("interval",5))))
    deadline=time.monotonic()+min(600,int(start["expires_in"]))
    while time.monotonic()<deadline:
        time.sleep(interval)
        status,result=post_form(token_endpoint,{
            "client_id":CLIENT_ID,
            "grant_type":"urn:ietf:params:oauth:grant-type:device_code",
            "device_code":start["device_code"],
        })
        if status==200:
            token=result.get("access_token")
            if not isinstance(token,str) or not token:
                raise LifecycleError("TOKEN_MISSING")
            validate_claims(jwt_claims(token))
            return token
        error=result.get("error")
        if error=="slow_down":
            interval=min(30,interval+5)
        elif error!="authorization_pending":
            raise LifecycleError("DEVICE_LOGIN_NOT_COMPLETED")
    raise LifecycleError("DEVICE_LOGIN_EXPIRED")


def http_json(method:str,path:str,token:str,body=None,correlation=None)->tuple[int,dict,dict]:
    headers={"Authorization":"Bearer "+token,"Accept":"application/json"}
    data=None
    if correlation:
        headers["X-Correlation-ID"]=correlation
    if body is not None:
        headers["Content-Type"]="application/json"
        data=json.dumps(body,separators=(",",":")).encode("utf-8")
    req=urllib.request.Request(API_BASE+path,data=data,headers=headers,method=method)
    try:
        with urllib.request.urlopen(req,timeout=30) as response:
            raw=response.read(5*1024*1024+1)
            if len(raw)>5*1024*1024:
                raise LifecycleError("HTTP_RESPONSE_TOO_LARGE")
            value=json.loads(raw) if raw else {}
            return response.status,value,dict(response.headers.items())
    except urllib.error.HTTPError as exc:
        try:value=json.loads(exc.read())
        except Exception:value={}
        return exc.code,value,dict(exc.headers.items())


def derive(source:dict,key:str,client_id:str)->dict:
    if not isinstance(source,dict):
        raise LifecycleError("SOURCE_PAYLOAD_INVALID")
    candidate=copy.deepcopy(source)
    iam=candidate.get("iam")
    if not isinstance(iam,dict):
        raise LifecycleError("SOURCE_IAM_INVALID")
    workloads=iam.get("workloadClients")
    if not isinstance(workloads,dict):
        raise LifecycleError("SOURCE_WORKLOADS_INVALID")
    if key in workloads and workloads[key]!=client_id:
        raise LifecycleError("WORKLOAD_KEY_CONFLICT")
    workloads[key]=client_id
    return candidate


def verify_only_change(source:dict,candidate:dict,key:str,client_id:str)->None:
    expected=copy.deepcopy(source)
    expected["iam"]["workloadClients"][key]=client_id
    if candidate!=expected:
        raise LifecycleError("CANDIDATE_CONTAINS_EXTRA_CHANGES")
    if candidate.get("lifecycle")!=source.get("lifecycle"):
        raise LifecycleError("LEGACY_LIFECYCLE_CHANGED")


def atomic_write(path:Path,value:dict)->None:
    path=path.resolve()
    path.parent.mkdir(parents=True,exist_ok=True)
    fd,tmp=tempfile.mkstemp(prefix="."+path.name+"-",dir=path.parent)
    try:
        with os.fdopen(fd,"w",encoding="utf-8") as stream:
            os.fchmod(stream.fileno(),0o600)
            json.dump(value,stream,indent=2,sort_keys=True)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(tmp,path)
    finally:
        Path(tmp).unlink(missing_ok=True)


def main()->None:
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("mode",choices=("plan","apply"))
    p.add_argument("--source-payload",type=Path,required=True)
    p.add_argument("--workload-key",required=True)
    p.add_argument("--client-id",required=True)
    p.add_argument("--expected-source-revision",type=int,required=True)
    p.add_argument("--installation-id",required=True)
    p.add_argument("--projection-output",type=Path)
    p.add_argument("--device-login",action="store_true")
    a=p.parse_args()

    if not WORKLOAD_KEY_RE.fullmatch(a.workload_key):
        raise LifecycleError("INVALID_WORKLOAD_KEY")
    if not WORKLOAD_ID_RE.fullmatch(a.client_id):
        raise LifecycleError("INVALID_CLIENT_ID")
    source=json.loads(a.source_payload.read_text(encoding="utf-8"))
    if source.get("installationId")!=a.installation_id:
        raise LifecycleError("INSTALLATION_ID_MISMATCH")
    candidate=derive(source,a.workload_key,a.client_id)
    verify_only_change(source,candidate,a.workload_key,a.client_id)

    print("MODE="+a.mode)
    print("INSTALLATION_ID="+a.installation_id)
    print("SOURCE_REVISION="+str(a.expected_source_revision))
    print("WORKLOAD_BINDING="+a.workload_key+"="+a.client_id)
    print("ONLY_DECLARED_CHANGE=true")
    print("LEGACY_LIFECYCLE_PRESERVED=true")

    if a.mode=="plan":
        print("NO_NETWORK_CALL=true")
        print("NO_REVISION_CREATED=true")
        return

    if not a.device_login:
        raise LifecycleError("DEVICE_LOGIN_REQUIRED")
    if a.projection_output is None:
        raise LifecycleError("PROJECTION_OUTPUT_REQUIRED")

    token=device_login()
    corr="installation-workload-"+str(uuid.uuid4())

    status,active,_=http_json(
        "GET",f"/api/trusted-human/v1/installations/{a.installation_id}/active",
        token,correlation=corr)
    if status!=200 or active.get("revision")!=a.expected_source_revision:
        raise LifecycleError("ACTIVE_SOURCE_REVISION_CHANGED")

    status,created,_=http_json(
        "POST",f"/api/trusted-human/v1/installations/{a.installation_id}/revisions",
        token,body=candidate,correlation=corr)
    if status!=201:
        raise LifecycleError("REVISION_CREATE_HTTP_"+str(status))
    new_revision=created.get("revision")
    if not isinstance(new_revision,int) or new_revision<=a.expected_source_revision:
        raise LifecycleError("REVISION_CREATE_INVALID")
    if created.get("validationState")!="VALIDATED":
        raise LifecycleError("REVISION_NOT_VALIDATED")

    status,validation,_=http_json(
        "POST",
        f"/api/trusted-human/v1/installations/{a.installation_id}/revisions/{new_revision}:validate-environment",
        token,correlation=corr)
    if status!=200:
        raise LifecycleError("ENVIRONMENT_VALIDATION_HTTP_"+str(status))
    if validation.get("overallStatus")!="PASS":
        raise LifecycleError("ENVIRONMENT_VALIDATION_NOT_PASS")

    status,activated,_=http_json(
        "POST",
        f"/api/trusted-human/v1/installations/{a.installation_id}/revisions/{new_revision}:activate",
        token,correlation=corr)
    if status!=200 or activated.get("revision")!=new_revision:
        raise LifecycleError("ACTIVATION_FAILED")

    status,projection,headers=http_json(
        "GET",
        f"/api/trusted-human/v1/installations/{a.installation_id}/projection?revision={new_revision}",
        token,correlation=corr)
    if status!=200:
        raise LifecycleError("PROJECTION_EXPORT_HTTP_"+str(status))
    if projection.get("revision")!=new_revision:
        raise LifecycleError("PROJECTION_REVISION_MISMATCH")
    checksum=projection.get("checksum")
    if not isinstance(checksum,str) or not checksum:
        raise LifecycleError("PROJECTION_CHECKSUM_MISSING")
    if headers.get("X-OUF-Installation-Revision") not in (None,str(new_revision)):
        raise LifecycleError("PROJECTION_HEADER_REVISION_MISMATCH")
    if headers.get("X-OUF-Installation-Checksum") not in (None,checksum):
        raise LifecycleError("PROJECTION_HEADER_CHECKSUM_MISMATCH")

    workloads=((projection.get("iam") or {}).get("workloadClients") or {})
    if workloads.get(a.workload_key)!=a.client_id:
        raise LifecycleError("PROJECTION_WORKLOAD_MISSING")

    atomic_write(a.projection_output,projection)
    print("NEW_REVISION="+str(new_revision))
    print("ENVIRONMENT_VALIDATION=PASS")
    print("ACTIVATION=PASS")
    print("PROJECTION_EXPORT=PASS")
    print("ACTIVE_PROJECTION_REPLACED=true")
    print("ACCESS_TOKEN_PRINTED=false")


if __name__=="__main__":
    try:
        main()
    except (OSError,ValueError,KeyError,TypeError,json.JSONDecodeError,LifecycleError) as exc:
        print("INSTALLATION_WORKLOAD_UPDATE_BLOCKED="+str(exc),file=__import__("sys").stderr)
        raise SystemExit(1) from None
