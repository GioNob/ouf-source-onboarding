#!/usr/bin/env python3
"""Exact previously qualified OCI33, disposable loopback-only native owner fixture."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import time

root=Path('.pairwise/storage-proof').resolve()
spec=importlib.util.spec_from_file_location('upload_labs',root/'scripts/run-seaweedfs-452-generation33-labs.py')
labs=importlib.util.module_from_spec(spec);spec.loader.exec_module(labs)
bound=labs.binding_api.load(root,'c2b30130b078a86ca440351980c3e745d4132e256591ec05fe98a9bdfc9058f8')
cold=labs.configured_harness('cold',bound)
work=Path(os.environ['RUNNER_TEMP'])/'upload-native-storage';work.mkdir()
proof=Path('target/upload-native');proof.mkdir(parents=True,exist_ok=True)
archive=work/'image.zip';expected=bound['lock']['producerArtifact']
subprocess.run(['curl','--fail','--silent','--show-error','--location','--proto','=https','--tlsv1.2',
    '--retry','2','--connect-timeout','15','--max-time','180','--max-filesize',str(expected['bytes']),
    '-H','Authorization: Bearer '+os.environ['GH_TOKEN'],'-H','Accept: application/vnd.github+json',
    'https://api.github.com/repos/GioNob/ouf-deploy/actions/artifacts/'+str(expected['id'])+'/zip','-o',str(archive)],check=True)
assert archive.stat().st_size==expected['bytes']
with archive.open('rb') as stream:assert hashlib.file_digest(stream,'sha256').hexdigest()==expected['digest'].removeprefix('sha256:')
(proof/'exact-image.json').write_text(json.dumps(cold.prepare_image(archive,work),indent=2)+'\n')
config=work/'config';config.mkdir();config.chmod(0o755)
(config/'s3.json').write_text(json.dumps({'identities':[{'name':'upload-fixture','credentials':[
    {'accessKey':'upload-fixture','secretKey':'upload-fixture-only-secret'}],'actions':['Admin','Read','Write','List','Tagging']}]}))
roles={
  'master':['master','-ip=127.0.0.1','-ip.bind=127.0.0.1','-port=19333','-port.grpc=29333','-mdir=/data','-peers=none','-telemetry=false','-volumeSizeLimitMB=32','-defaultReplication=000'],
  'volume':['volume','-ip=127.0.0.1','-ip.bind=127.0.0.1','-port=18080','-port.grpc=28080','-master=127.0.0.1:19333','-dir=/data','-max=24','-preStopSeconds=1'],
  'filer':['filer','-ip=127.0.0.1','-ip.bind=127.0.0.1','-port=18888','-port.grpc=28888','-master=127.0.0.1:19333','-defaultStoreDir=/data'],
  's3':['s3','-ip=127.0.0.1','-ip.bind=127.0.0.1','-port=18333','-port.iceberg=0','-port.lance=0','-filer=127.0.0.1:18888','-config=/config/s3.json']}
names=[]
for role,args in roles.items():
    data=work/role;data.mkdir();data.chmod(0o777)
    name='ouf-upload-native-'+role;names.append(name)
    subprocess.run(['docker','run','-d','--name',name,'--network','host','--user','65532:65532','--read-only',
        '--cap-drop','ALL','--security-opt','no-new-privileges','--memory','512m','--pids-limit','128',
        '--tmpfs','/tmp:rw,noexec,nosuid,size=67108864,mode=1777',
        '--mount','type=bind,src='+str(data)+',dst=/data',
        '--mount','type=bind,src='+str(config)+',dst=/config,readonly',cold.IMAGE_ID,'-logtostderr=true',*args],check=True)
deadline=time.monotonic()+90
while True:
    log=subprocess.check_output(['docker','logs','ouf-upload-native-filer'],stderr=subprocess.STDOUT,text=True,timeout=10)
    if cold.ready_lock_ring(log):break
    if time.monotonic()>deadline:raise RuntimeError('filer lock ring readiness deadline')
    time.sleep(1)
(proof/'containers.json').write_bytes(subprocess.check_output(['docker','inspect',*names]))
print('Exact OCI33 ready on runner loopback; no target operation.')
