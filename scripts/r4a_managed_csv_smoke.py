#!/usr/bin/env python3
"""Upload the exact real CSV via Gateway and verify asynchronous profiling.

Interactive Device Flow; the access token and source rows never reach stdout.
There is no automatic retry of the upload after an uncertain HTTP outcome.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
from pathlib import Path
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

SHA="a07c2dcdc21aa9a23fb5585a69d52031dc08010d251bf39bfa67c8e0962c6e1a"
SCOPES={"ouf.managed-source.file.upload","ouf.managed-source.file.profile","ouf.managed-source.preview"}


class SmokeError(RuntimeError): pass


def exact_file(path: Path) -> bytes:
    data=path.read_bytes()
    if len(data)!=509 or hashlib.sha256(data).hexdigest()!=SHA:
        raise SmokeError("CSV_BYTES_OR_SHA_MISMATCH")
    return data


def oidc_post(url,fields):
    request=urllib.request.Request(url,data=urllib.parse.urlencode(fields).encode("ascii"),method="POST",
                                   headers={"Content-Type":"application/x-www-form-urlencoded"})
    try:
        with urllib.request.urlopen(request,timeout=15) as response:return response.status,json.load(response)
    except urllib.error.HTTPError as exc:
        try:return exc.code,json.load(exc)
        except (ValueError,OSError):return exc.code,{}


def device_login(expected_subject, issuer, client, audience):
    with urllib.request.urlopen(issuer+"/.well-known/openid-configuration",timeout=15) as response:
        metadata=json.load(response)
    if metadata.get("issuer")!=issuer:raise SmokeError("ISSUER_MISMATCH")
    device=metadata.get("device_authorization_endpoint")
    endpoint=metadata.get("token_endpoint")
    if not all(isinstance(x,str) and x.startswith(issuer+"/protocol/openid-connect/") for x in (device,endpoint)):
        raise SmokeError("OIDC_ENDPOINT_MISMATCH")
    requested=int(time.time())
    status,start=oidc_post(device,{"client_id":client,"scope":"openid "+" ".join(sorted(SCOPES))})
    verification=start.get("verification_uri","")
    origin=urllib.parse.urlsplit(issuer)
    verify_origin=urllib.parse.urlsplit(verification)
    if (status!=200 or not start.get("device_code") or verify_origin.scheme!="https"
            or verify_origin.netloc!=origin.netloc or verify_origin.username or verify_origin.password):
        raise SmokeError("DEVICE_AUTHORIZATION_FAILED")
    print("OPEN_IN_PC_BROWSER="+start["verification_uri"],flush=True)
    print("ENTER_CODE_ON_PC="+start["user_code"],flush=True)
    print("DO_NOT_PASTE_CODE_OR_TOKEN_IN_CHAT=true",flush=True)
    interval=max(5,min(30,int(start.get("interval",5))))
    deadline=time.monotonic()+min(600,int(start.get("expires_in",600)))
    while time.monotonic()<deadline:
        time.sleep(interval)
        status,response=oidc_post(endpoint,{"client_id":client,"grant_type":"urn:ietf:params:oauth:grant-type:device_code","device_code":start["device_code"]})
        if status==200:
            token=response.get("access_token")
            if not isinstance(token,str) or len(token)>16384:raise SmokeError("TOKEN_INVALID")
            try:
                part=token.split(".")[1]
                claims=json.loads(base64.urlsafe_b64decode(part+"="*(-len(part)%4)))
            except (IndexError,ValueError,UnicodeError) as exc:raise SmokeError("TOKEN_CLAIMS_INVALID") from exc
            audience_claim=claims.get("aud",[])
            if isinstance(audience_claim,str):audience_claim=[audience_claim]
            if (claims.get("iss")!=issuer or claims.get("azp")!=client or claims.get("sub")!=expected_subject
                    or claims.get("ouf_actor_type") not in ("HUMAN","HUMAN_USER")
                    or audience not in audience_claim or not SCOPES.issubset(set(str(claims.get("scope","")).split()))
                    or claims.get("iat",0)<requested-5 or claims.get("exp",0)<=time.time()):
                raise SmokeError("TOKEN_CONTRACT_MISMATCH")
            print("TOKEN_ACCEPTANCE=PASS",flush=True)
            return token
        error=response.get("error")
        if error=="slow_down":interval=min(30,interval+5)
        elif error!="authorization_pending":raise SmokeError("DEVICE_LOGIN_FAILED")
    raise SmokeError("DEVICE_LOGIN_EXPIRED")


def json_request(base,path,token,method="GET",body=None,headers=None):
    request=urllib.request.Request(base+path,data=body,method=method,headers={
        "Authorization":"Bearer "+token,"Accept":"application/json",
        "X-Correlation-ID":str(uuid.uuid4()),**(headers or {})})
    try:
        with urllib.request.urlopen(request,timeout=30) as response:
            raw=response.read(65537)
            if len(raw)>65536:raise SmokeError("RESPONSE_TOO_LARGE")
            return response.status,json.loads(raw)
    except urllib.error.HTTPError as exc:
        # Do not log untrusted response bodies or sensitive request headers.
        raise SmokeError("GATEWAY_HTTP_"+str(exc.code)) from None


def check_preview(preview):
    columns=preview.get("columns",[])
    names=[row.get("name") for row in columns if isinstance(row,dict)]
    sample=preview.get("redactedSample",[])
    if (preview.get("format")!="CSV" or preview.get("metadata",{}).get("rows")!=8
            or names!=["cinema","indirizzo"] or not isinstance(sample,list)
            or not sample or any(row.get("indirizzo")!="[REDACTED]" for row in sample)):
        raise SmokeError("PROFILE_CONTRACT_MISMATCH")


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--csv",type=Path,required=True)
    p.add_argument("--subject-id",required=True,help="Freshly verified exact Keycloak HUMAN subject")
    p.add_argument("--issuer",required=True,help="Approved iam.issuerUrl from installation projection")
    p.add_argument("--gateway-base-url",required=True,help="Approved gateway.publicApiBaseUrl from installation projection")
    p.add_argument("--audience",required=True,help="Approved gateway.requiredAudience from installation projection")
    p.add_argument("--client-id",required=True,help="Approved HUMAN device client for this installation")
    a=p.parse_args()
    issuer=a.issuer.rstrip("/")
    base=a.gateway_base_url.rstrip("/")
    for name,value in (("issuer",issuer),("gateway",base)):
        parsed=urllib.parse.urlsplit(value)
        if (parsed.scheme!="https" or not parsed.netloc or parsed.username or parsed.password
                or parsed.query or parsed.fragment):raise SmokeError("INVALID_INSTALLATION_"+name.upper())
    data=exact_file(a.csv)
    print("CSV_BYTES=509 CSV_SHA256_MATCH=true",flush=True)
    token=device_login(a.subject_id,issuer,a.client_id,a.audience)
    status,asset=json_request(base,"/api/managed-sources/v1/files",token,"POST",data,{
        "Content-Type":"text/csv","X-Content-SHA256":"sha256:"+SHA})
    if status!=201 or asset.get("content_hash")!="sha256:"+SHA or asset.get("size_bytes")!=509:
        raise SmokeError("UPLOAD_CONTRACT_MISMATCH")
    asset_id=str(uuid.UUID(str(asset["asset_id"])))
    if not str(asset.get("staging_ref","")).startswith("object://managed-files/"):
        raise SmokeError("STAGING_REF_INVALID")
    print("UPLOAD=PASS ASSET_ID="+asset_id,flush=True)
    root="/api/onboarding/v1/managed-files/"+asset_id
    status,job=json_request(base,root+"/profile",token,"POST",b"",{"Idempotency-Key":"r4a-cinema-"+SHA})
    if status!=202:raise SmokeError("PROFILE_START_FAILED")
    job_id=str(uuid.UUID(str(job["job_id"])))
    deadline=time.monotonic()+90
    while time.monotonic()<deadline:
        _,state=json_request(base,root+"/profile-jobs/"+job_id,token)
        if state.get("status")=="SUCCEEDED":
            profile_id=str(uuid.UUID(str(state["profile_id"])))
            _,preview=json_request(base,root+"/profiles/"+profile_id+"/preview",token)
            check_preview(preview)
            print("PROFILE=PASS ROWS=8 COLUMNS=cinema,indirizzo REDACTION=PASS",flush=True)
            print("PROFILE_ID="+profile_id,flush=True)
            return
        if state.get("status")=="FAILED":raise SmokeError("PROFILE_JOB_FAILED")
        time.sleep(2)
    raise SmokeError("PROFILE_JOB_TIMEOUT")


if __name__=="__main__":
    try:main()
    except (OSError,ValueError,KeyError,TypeError,SmokeError) as exc:
        code=str(exc) if isinstance(exc,SmokeError) else type(exc).__name__
        print("MANAGED_CSV_SMOKE_BLOCKED="+code,file=sys.stderr)
        raise SystemExit(1)
