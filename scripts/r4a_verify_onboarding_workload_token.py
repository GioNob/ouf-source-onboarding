#!/usr/bin/env python3
"""Inspect the private Onboarding workload JWT without printing its contents.

This checks local claims and file metadata, not the cryptographic signature.
The token must have been obtained by the governed HTTPS refresher.
"""
from __future__ import annotations

import argparse
import base64
import json
from pathlib import Path
import stat
import sys
import time

TOKEN_FILE=Path('/run/ouf-onboarding-auth/token')
PROJECTION=Path('/opt/ouf/installation/active-projection.json')
SCOPE='ouf.internal.object-storage.read'

def inspect(token_file,projection,tenant,expected_uid=0,expected_gid=10003):
    meta=token_file.lstat()
    metadata_ok=(stat.S_ISREG(meta.st_mode) and meta.st_uid==expected_uid
                 and meta.st_gid==expected_gid and stat.S_IMODE(meta.st_mode)==0o440
                 and 0<meta.st_size<=16384)
    if not metadata_ok:raise ValueError('TOKEN_FILE_METADATA')
    token=token_file.read_text(encoding='ascii').strip()
    parts=token.split('.')
    if len(parts)!=3:raise ValueError('TOKEN_FORMAT')
    claims=json.loads(base64.urlsafe_b64decode(parts[1]+'='*(-len(parts[1])%4)))
    if not isinstance(claims,dict):raise ValueError('TOKEN_CLAIMS')
    config=json.loads(projection.read_text())
    issuer=config['gateway']['issuerUrl'].rstrip('/')
    audience=config['gateway']['requiredAudience']
    aud=claims.get('aud',[])
    if isinstance(aud,str):aud=[aud]
    now=time.time()
    return {
        'TOKEN_METADATA_OK':metadata_ok,
        'ISSUER_MATCH':claims.get('iss')==issuer,
        'CLIENT_MATCH':claims.get('azp')=='ouf-onboarding',
        'GATEWAY_AUDIENCE':isinstance(aud,list) and audience in aud,
        'ACTOR_IS_SERVICE':claims.get('ouf_actor_type')=='SERVICE',
        'TENANT_MATCH':claims.get('tenant_id')==tenant,
        'SCOPE_PRESENT':SCOPE in str(claims.get('scope','')).split(),
        'FRESH':isinstance(claims.get('iat'),int) and now-3600<claims['iat']<=now+30,
        'NOT_EXPIRED':isinstance(claims.get('exp'),int) and claims['exp']>now+30,
    }

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tenant-id',required=True)
    args=parser.parse_args()
    if not args.tenant_id or any(c.isspace() for c in args.tenant_id):
        raise ValueError('INVALID_TENANT')
    results=inspect(TOKEN_FILE,PROJECTION,args.tenant_id)
    for name,passed in results.items():print(name+'='+str(passed).lower())
    if not all(results.values()):raise ValueError('TOKEN_CLAIMS_MISMATCH')
    print('WORKLOAD_TOKEN_ACCEPTANCE=PASS')

if __name__=='__main__':
    try:main()
    except (OSError,ValueError,KeyError,TypeError) as exc:
        code=str(exc) if isinstance(exc,ValueError) else type(exc).__name__
        print('WORKLOAD_TOKEN_VERIFY_BLOCKED='+code,file=sys.stderr)
        raise SystemExit(1)
