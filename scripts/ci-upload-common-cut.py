#!/usr/bin/env python3
"""Disposable native owner/common-cut drill. No deployment or real credentials."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import time

PROOF=Path('target/upload-cut').resolve()
WORK=Path(os.environ['RUNNER_TEMP'])/'upload-common-cut'
STORAGE=Path(os.environ['RUNNER_TEMP'])/'upload-native-storage'
PG='ouf-upload-cut-pg'
TARGET='ouf_onboarding_upload_frozen_v39'
ROLES=('master','volume','filer','s3')

def run(args, *, timeout=90, check=True, env=None):
    result=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout,env=env)
    with (PROOF/'commands.jsonl').open('a') as f:
        f.write(json.dumps({'args':args,'returncode':result.returncode,
                            'stdout':result.stdout.decode(errors='replace'),'stderr':result.stderr.decode(errors='replace')})+'\n')
    if check and result.returncode:raise RuntimeError('Command failed: '+str(args[:4])+'; see commands.jsonl')
    return result

def docker(*args,**kw):return run(['docker',*map(str,args)],**kw)
def sql(query,name=PG):
    return docker('exec',name,'psql','-X','-qAt','-v','ON_ERROR_STOP=1','-U','ouf_onboarding','-d','ouf_onboarding','-c',query).stdout.decode().strip()
def write(path,value):path.write_text(json.dumps(value,indent=2,sort_keys=True)+'\n')
def digest(path):
    with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def inventory(root):
    return {str(p.relative_to(root)):{'bytes':p.stat().st_size,'sha256':digest(p)} for p in sorted(root.rglob('*')) if p.is_file()}
def java_command(phase,stage='source'):
    env={**os.environ,'OUF_CUT_STAGE':stage,'OUF_LAB_AUTHORITY_PATH':str(WORK/'current-authority.json')}
    cp='target/test-classes:target/classes:'+Path('target/upload-cut-classpath.txt').read_text().strip()
    return ['java','-cp',cp,'it.comune.trieste.ouf.onboarding.application.UploadCommonCutProcess',phase],env
def java(phase,stage='source'):
    args,env=java_command(phase,stage)
    return run(args,env=env,timeout=120)
def ready(name):
    deadline=time.monotonic()+60
    for _ in range(120):
        r=docker('exec',name,'pg_isready','-h','127.0.0.1','-U','ouf_onboarding','-d','ouf_onboarding',check=False,timeout=3)
        if r.returncode==0:return
        if time.monotonic()>=deadline:break
        time.sleep(.5)
    raise RuntimeError('Bounded PostgreSQL readiness exhausted')
def stop(name):
    docker('stop','--time','30',name)
    state=json.loads(docker('inspect',name).stdout)[0]['State']
    assert state['Status']=='exited' and state['ExitCode']==0 and not state['OOMKilled'],state
    log=docker('logs',name);(PROOF/(name+'-stopped.log')).write_bytes(log.stdout+log.stderr)
    docker('rm',name)
    return state
def stop_storage():
    return {role:stop('ouf-upload-native-'+role) for role in reversed(ROLES)}
def start_storage(root,templates):
    for role in ROLES:
        original=templates[role]
        docker('run','-d','--name','ouf-upload-native-'+role,'--network','host','--user','65532:65532','--read-only',
            '--cap-drop','ALL','--security-opt','no-new-privileges','--memory','512m','--pids-limit','128',
            '--tmpfs','/tmp:rw,noexec,nosuid,size=67108864,mode=1777',
            '--mount',f'type=bind,src={root/role},dst=/data',
            '--mount',f'type=bind,src={root/"config"},dst=/config,readonly',original['Image'],*original['Config']['Cmd'])
    deadline=time.monotonic()+90
    while True:
        # Reuse the exact qualified readiness predicate, not a sleep-only startup.
        import importlib.util
        module=Path('.pairwise/storage-proof/scripts/run-seaweedfs-452-cold-recovery.py')
        spec=importlib.util.spec_from_file_location('cut_cold',module);cold=importlib.util.module_from_spec(spec);spec.loader.exec_module(cold)
        result=docker('logs','ouf-upload-native-filer');text=(result.stdout+result.stderr).decode()
        if cold.ready_lock_ring(text):break
        if time.monotonic()>deadline:raise RuntimeError('Bounded filer readiness exhausted')
        time.sleep(1)
def own(path,uid=None):
    run(['sudo','-n','chown','-R',uid or f'{os.getuid()}:{os.getgid()}',str(path)])
def recovery_settings():
    return (f"\nrestore_command = 'cp /archive/%f %p'\nrecovery_target_name = '{TARGET}'\n"
            "recovery_target_action = 'promote'\nhot_standby = off\narchive_mode = off\n")
def start_restore(name,data,wal,image):
    own(wal,'70:70')
    own(data)
    with (data/'postgresql.auto.conf').open('a') as f:
        f.write(recovery_settings())
    (data/'recovery.signal').touch();own(data,'70:70')
    docker('run','-d','--name',name,'-p','127.0.0.1:5432:5432',
           '-v',f'{data}:/var/lib/postgresql/data','-v',f'{wal}:/archive:ro',image)

def main():
    assert os.environ.get('GITHUB_ACTIONS')=='true' and not WORK.exists()
    PROOF.mkdir(parents=True,exist_ok=True);WORK.mkdir();WORK.chmod(0o755)
    export=WORK/'export';export.mkdir();export.chmod(0o777)
    wal=export/'wal';wal.mkdir();wal.chmod(0o777)
    states=[];watch=None;watch_output=None
    receipt={'status':'BLOCKED','targetOperations':0,'releaseAdmitted':False}
    try:
        docker('pull','postgres:17.11-alpine',timeout=180)
        image=json.loads(docker('image','inspect','postgres:17.11-alpine').stdout)[0]
        write(PROOF/'postgres-image.json',image)
        assert docker('run','--rm',image['Id'],'postgres','--version').stdout.decode().strip()=='postgres (PostgreSQL) 17.11'
        docker('run','-d','--name',PG,'-p','127.0.0.1:5432:5432',
          '-e','POSTGRES_DB=ouf_onboarding','-e','POSTGRES_USER=ouf_onboarding','-e','POSTGRES_PASSWORD=ci-only-password',
          '-v',f'{export}:/proof',image['Id'],'-c','archive_mode=on','-c','wal_level=replica',
          '-c','archive_command=test ! -f /proof/wal/%f && cp %p /proof/wal/%f')
        ready(PG);java('init')
        system_id=sql('select system_identifier::text from pg_control_system()')
        docker('exec','-u','postgres','-e','PGPASSWORD=ci-only-password',PG,'pg_basebackup','-h','127.0.0.1',
            '-U','ouf_onboarding','-D','/proof/base','--checkpoint=fast','--wal-method=stream','--manifest-checksums=SHA256',timeout=120)
        docker('exec','-u','postgres',PG,'pg_verifybackup','/proof/base',timeout=120)
        own(export);base_index=inventory(export/'base');write(PROOF/'base-inventory.json',base_index)
        # Restore ownership for continued archiving. Never alter archived bytes.
        own(wal,'70:70');sql('checkpoint; select pg_switch_wal()')
        java('seed-drain')
        templates={r:json.loads(docker('inspect','ouf-upload-native-'+r).stdout)[0] for r in ROLES}
        states.append(stop_storage());own(STORAGE)
        config=STORAGE/'config/s3.json';value=json.loads(config.read_text())
        value['identities'][0]['actions']=['Read','List'];write(config,value)
        for r in ROLES:own(STORAGE/r,'65532:65532')
        start_storage(STORAGE,templates)
        java('reconcile-freeze')
        states.append(stop_storage());own(STORAGE)
        cold=export/'cold';cold.mkdir()
        for r in (*ROLES,'config'):shutil.copytree(STORAGE/r,cold/r)
        cold_index=inventory(cold);write(PROOF/'cold-inventory.json',cold_index)
        sql("update ouf_onboarding.cut_canary set value='cut'")
        target_lsn=sql("select pg_create_restore_point('%s')"%TARGET)
        segment=sql("select pg_walfile_name('%s'::pg_lsn)"%target_lsn)
        assert 'pg_wal/'+segment not in base_index
        # This decision file is outside export/recovered/positive and is never extracted from a backup.
        java('revoke-after-cut');authority_hash=digest(WORK/'current-authority.json')
        watch_args,watch_env=java_command('watch-live-runtime')
        watch_output=(PROOF/'live-runtime-watch.log').open('wb')
        watch=subprocess.Popen(watch_args,stdout=watch_output,stderr=subprocess.STDOUT,env=watch_env)
        deadline=time.monotonic()+30
        while not (WORK/'watch-ready').exists():
            if watch.poll() is not None or time.monotonic()>deadline:raise RuntimeError('Live-runtime watcher did not become ready')
            time.sleep(.1)
        sql("update ouf_onboarding.cut_canary set value='after-cut'; select pg_switch_wal()")
        deadline=time.monotonic()+30
        while not (wal/segment).exists():
            if time.monotonic()>deadline:raise RuntimeError('WAL archive budget exhausted')
            time.sleep(.2)
        assert sql('select value from ouf_onboarding.cut_canary')=='after-cut'
        states.append({PG:stop(PG)});own(export)
        wal_index=inventory(wal);write(PROOF/'wal-inventory.json',wal_index)
        assert inventory(export/'base')==base_index
        cut={'schema':'ouf.onboarding-common-cut.v1','systemIdentifier':system_id,'targetName':TARGET,
             'targetLsn':target_lsn,'targetWalSegment':segment,'sourceStopped':True,'storageStopped':True,
             'frozenOwnerSha256':digest(PROOF/'frozen-owner.json'),
             'components':{n:digest(PROOF/(n+'-inventory.json')) for n in ('base','wal','cold')}}
        write(PROOF/'common-cut.json',cut)
        for name in ('base','wal','cold'):
            with tarfile.open(PROOF/(name+'.tar.gz'),'w:gz') as tar:tar.add(export/name,arcname=name)
        # Recover from exports, not from source data directories.
        withheld=WORK/'withheld-export';export.rename(withheld)
        recovered=WORK/'recovered';recovered.mkdir()
        for name in ('base','wal','cold'):
            with tarfile.open(PROOF/(name+'.tar.gz')) as tar:tar.extractall(recovered,filter='data')
            assert inventory(recovered/name)=={'base':base_index,'wal':wal_index,'cold':cold_index}[name]
        # Missing required WAL must terminate before any restored S3 process is started.
        negative=WORK/'negative';shutil.copytree(recovered/'base',negative)
        badwal=WORK/'badwal';shutil.copytree(recovered/'wal',badwal);(badwal/segment).unlink()
        start_restore(PG,negative,badwal,image['Id'])
        deadline=time.monotonic()+60
        while True:
            state=json.loads(docker('inspect',PG).stdout)[0]['State']
            if not state['Running']:break
            if time.monotonic()>deadline:raise RuntimeError('Missing-WAL recovery did not terminate')
            time.sleep(.5)
        log=docker('logs',PG);(PROOF/'missing-wal.log').write_bytes(log.stdout+log.stderr)
        assert state['ExitCode']!=0 and b'recovery ended before configured recovery target was reached' in log.stdout+log.stderr
        write(PROOF/'missing-wal.json',{'status':'EXPECTED_FAILURE','state':state,'restoredS3Started':0})
        docker('rm',PG)
        positive=WORK/'positive';shutil.copytree(recovered/'base',positive)
        start_restore(PG,positive,recovered/'wal',image['Id']);ready(PG)
        assert sql('select system_identifier::text from pg_control_system()')==system_id
        (WORK/'watch-resume').write_text('restored')
        code=watch.wait(timeout=30);watch_output.close();watch_output=None
        with (PROOF/'commands.jsonl').open('a') as f:
            f.write(json.dumps({'args':watch_args,'returncode':code,'stdout':(PROOF/'live-runtime-watch.log').read_text(),'stderr':''})+'\n')
        assert code==0,'Live worker accepted changed database or watcher failed'
        watch=None
        java('verify-db','restored')
        assert digest(WORK/'current-authority.json')==authority_hash
        write(PROOF/'db-before-s3.json',{'status':'PASS','restoredS3Started':0,'systemIdentifier':system_id})
        restored_storage=WORK/'restored-storage';shutil.copytree(recovered/'cold',restored_storage)
        for r in ROLES:own(restored_storage/r,'65532:65532')
        start_storage(restored_storage,templates);java('verify-s3','restored')
        assert json.loads((PROOF/'objects-source.json').read_text())==json.loads((PROOF/'objects-restored.json').read_text())
        states.append(stop_storage());states.append({PG:stop(PG)})
        assert inventory(withheld/'base')==base_index and inventory(withheld/'wal')==wal_index and inventory(withheld/'cold')==cold_index
        receipt.update(status='PASS',namedPhysicalRecovery=True,actualOwnerTablesPreserved=True,
            pendingReconciledWithoutPut=True,staleApplicationWriterBlocked=True,staleS3PutDenied=True,
            missingWalBlocksS3=True,objects=2,objectBytes=36,cleanStorageStops=12,cleanPostgresStops=2,
            targetName=TARGET,systemIdentifier=system_id,postgresImageId=image['Id'],
            backupFiles=sum(map(len,(base_index,wal_index,cold_index))),
            freshRuntimeAttestationRequired=True,revokedAuthorityRestoreBlocked=True,
            currentAuthorityOutsideOwnerBackup=True,currentAuthoritySha256=authority_hash,
            liveWorkerDatabaseRestoreBlocked=True,
            populatedAuthorizationHistoryRestored=True,restoredPolicyCannotPrepareOrRedeliver=True,
            scope='synthetic Onboarding owner only; no UDP/Ingestion or operational binding')
    finally:
        if watch is not None and watch.poll() is None:
            watch.terminate()
            try:watch.wait(timeout=5)
            except subprocess.TimeoutExpired:watch.kill();watch.wait(timeout=5)
        if watch_output is not None:watch_output.close()
        write(PROOF/'stops.json',states);write(PROOF/'receipt.json',receipt)
        for name in [PG,*('ouf-upload-native-'+r for r in reversed(ROLES))]:
            result=docker('inspect',name,check=False)
            if result.returncode==0:
                log=docker('logs',name,check=False);(PROOF/(name+'-cleanup.log')).write_bytes(log.stdout+log.stderr)
                docker('rm','-f',name,check=False)
    print(json.dumps(receipt))

if __name__=='__main__':main()
