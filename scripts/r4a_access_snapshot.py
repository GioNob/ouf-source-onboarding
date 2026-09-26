#!/usr/bin/env python3
"""Fetch one subject's current Authorization access view through Trusted HUMAN API.

Authenticates with ouf-human-admin Device Flow, validates required token context,
and writes the access response atomically as a 0600 file. The access token is
kept only in memory and is never printed.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

ISSUER="https://auth.ouf-lab.it/realms/ouf"
CLIENT_ID="ouf-human-admin"
BASE="https://api.ouf-lab.it/api/trusted-human/v1/authorization"
REQUIRED_SCOPES={"authorization.permissions.read"}


class AccessError(RuntimeError):
    pass


def jwt_claims(token):
    try:
        part=token.split(".")[1]
        return json.loads(base64.urlsafe_b64decode(part+"="*(-len(part)%4)))
    except Exception as exc:
        raise AccessError("INVALID_ACCESS_TOKEN") from exc


def audience_has(value,wanted):
    return value==wanted or (isinstance(value,list) and wanted in value)


def validate_claims(claims):
    scopes=set(str(claims.get("scope","")).split())
    if claims.get("iss")!=ISSUER:
        raise AccessError("UNEXPECTED_ISSUER")
    if not audience_has(claims.get("aud"),"ouf-api-gateway"):
        raise AccessError("GATEWAY_AUDIENCE_MISSING")
    if claims.get("tenant_id")!="ouf-lab":
        raise AccessError("UNEXPECTED_TENANT")
    if claims.get("ouf_actor_type")!="HUMAN":
        raise AccessError("HUMAN_REQUIRED")
    missing=sorted(REQUIRED_SCOPES-scopes)
    if missing:
        raise AccessError("MISSING_SCOPES:"+",".join(missing))
    if not claims.get("acr"):
        raise AccessError("ACR_REQUIRED")


def post_form(url,values):
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


def device_login():
    with urllib.request.urlopen(ISSUER+"/.well-known/openid-configuration",timeout=20) as response:
        metadata=json.load(response)
    if metadata.get("issuer")!=ISSUER:
        raise AccessError("OIDC_DISCOVERY_ISSUER_MISMATCH")
    device=metadata.get("device_authorization_endpoint")
    token_endpoint=metadata.get("token_endpoint")
    if not all(isinstance(x,str) and x.startswith(ISSUER+"/protocol/openid-connect/") for x in (device,token_endpoint)):
        raise AccessError("OIDC_ENDPOINT_INVALID")
    scope="openid "+" ".join(sorted(REQUIRED_SCOPES))
    status,start=post_form(device,{"client_id":CLIENT_ID,"scope":scope})
    if status!=200 or not all(start.get(k) for k in ("device_code","user_code","verification_uri","expires_in")):
        raise AccessError("DEVICE_AUTHORIZATION_FAILED")
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
                raise AccessError("TOKEN_MISSING")
            validate_claims(jwt_claims(token))
            return token
        error=result.get("error")
        if error=="slow_down":
            interval=min(30,interval+5)
        elif error!="authorization_pending":
            raise AccessError("DEVICE_LOGIN_NOT_COMPLETED")
    raise AccessError("DEVICE_LOGIN_EXPIRED")


def fetch_access(token,subject_id):
    query=urllib.parse.urlencode({"subjectId":subject_id,"limit":200})
    req=urllib.request.Request(
        BASE+"/access?"+query,
        headers={"Authorization":"Bearer "+token,"Accept":"application/json"},
        method="GET",
    )
    try:
        with urllib.request.urlopen(req,timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as exc:
        raise AccessError("ACCESS_HTTP_"+str(exc.code)) from None


def validate_access(value,subject_id):
    if not isinstance(value,dict) or not isinstance(value.get("grants"),list):
        raise AccessError("ACCESS_RESPONSE_INVALID")
    if value.get("nextAfter") is not None:
        raise AccessError("ACCESS_PAGINATION_INCOMPLETE")
    if any(g.get("subjectId")!=subject_id for g in value["grants"]):
        raise AccessError("ACCESS_SUBJECT_MISMATCH")
    if not value.get("policyRef"):
        raise AccessError("POLICY_REF_MISSING")
    return value


def atomic_write(path,value):
    path=path.resolve()
    path.parent.mkdir(parents=True,exist_ok=True)
    fd,tmp=tempfile.mkstemp(prefix="."+path.name+"-",dir=path.parent)
    try:
        with os.fdopen(fd,"w",encoding="utf-8") as out:
            os.fchmod(out.fileno(),0o600)
            json.dump(value,out,indent=2,sort_keys=True)
            out.write("\n")
            out.flush()
            os.fsync(out.fileno())
        os.replace(tmp,path)
    finally:
        Path(tmp).unlink(missing_ok=True)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--subject-id",required=True)
    p.add_argument("--output",type=Path,required=True)
    p.add_argument("--device-login",action="store_true")
    a=p.parse_args()
    if not a.device_login:
        raise AccessError("DEVICE_LOGIN_REQUIRED")
    token=device_login()
    value=validate_access(fetch_access(token,a.subject_id),a.subject_id)
    atomic_write(a.output,value)
    print("POLICY_REF="+str(value.get("policyRef")))
    print("GRANT_COUNT="+str(len(value["grants"])))
    print("NEXT_AFTER="+str(value.get("nextAfter")))
    print("ACCESS_FILE="+str(a.output))
    print("ACCESS_FILE_MODE=0600")
    print("ACCESS_TOKEN_PRINTED=false")


if __name__=="__main__":
    try:main()
    except (OSError,ValueError,TypeError,json.JSONDecodeError,AccessError) as exc:
        print("ACCESS_SNAPSHOT_BLOCKED="+str(exc),file=__import__("sys").stderr)
        raise SystemExit(1) from None
