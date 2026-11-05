#!/usr/bin/env python3
"""Independent byte/semantic readback; no native restore claimed by this verifier."""
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import subprocess
import sys
import tarfile
import tempfile
import zipfile

def sha(data):return hashlib.sha256(data).hexdigest()
def require(value,code):
    if not value:raise ValueError(code)

def verify(archive,root,commit):
    require(subprocess.check_output(['git','-C',str(root),'rev-parse','HEAD'],text=True).strip()==commit,'EXACT_CHECKOUT')
    with zipfile.ZipFile(archive) as z:
        infos=z.infolist();require(len(infos)==len({i.filename for i in infos}) and sum(i.file_size for i in infos)<512*1024*1024,'ZIP_BOUNDS')
        require(all(not i.is_dir() and PurePosixPath(i.filename).name==i.filename for i in infos),'FLAT_REGULAR_ZIP')
        data={i.filename:z.read(i) for i in infos}
    manifest={}
    for line in data['SHA256SUMS'].decode().splitlines():
        digest,name=line.split('  ',1);require(name not in manifest,'DUPLICATE_CHECKSUM');manifest[name]=digest
    require(set(manifest)==set(data)-{'SHA256SUMS'},'COMPLETE_CHECKSUM_COVERAGE')
    for name,digest in manifest.items():require(sha(data[name])==digest,'CHECKSUM:'+name)
    def j(name):return json.loads(data[name+'.json'])
    bindings=j('source-bindings')
    paths=[root/'pom.xml',root/'.github/workflows/upload-common-cut.yml',*(root/'scripts').glob('*upload*.py')]
    paths+=list((root/'src/main').rglob('*.*'))+list((root/'src/test/java').rglob('*.java'))
    require(set(bindings)=={str(p.relative_to(root)) for p in paths if p.is_file()},'EXACT_SOURCE_BINDING_SET')
    image=j('exact-image')
    require(image['manifestDigest']=='sha256:5bfda2ef577b3c33216b82fb3eb1bed3ad76842ed13fc0a114ef94084b20da4d'
            and image['configDigest']=='sha256:3b972a3ca20f98b73554f0772a2df4f1a5ef4441298299a443f0e605f10ec9b6'
            and image['binarySha256']=='c301b4f487f17f4d9b8dfe1b26885ec4ea6141e1ace73b8dfaadff70bd714dfc','EXACT_OCI33')
    for name,digest in bindings.items():
        path=PurePosixPath(name);require(not path.is_absolute() and '..' not in path.parts,'SAFE_SOURCE_PATH')
        require(sha((root/name).read_bytes())==digest,'SOURCE:'+name)
    require(j('run')['commit']==commit and j('run')['storageProofCommit']=='99594c878a3130a353531841e91c53c38741dbd0','PRODUCER_BINDINGS')
    receipt=j('receipt');require(receipt['status']=='PASS' and receipt['targetOperations']==0 and receipt['releaseAdmitted'] is False,'TERMINAL_PASS')
    for field in ('namedPhysicalRecovery','actualOwnerTablesPreserved','pendingReconciledWithoutPut','staleApplicationWriterBlocked','staleS3PutDenied','missingWalBlocksS3','freshRuntimeAttestationRequired','revokedAuthorityRestoreBlocked','currentAuthorityOutsideOwnerBackup','liveWorkerDatabaseRestoreBlocked'):
        require(receipt[field] is True,field)
    cut=j('common-cut');require(cut['schema']=='ouf.onboarding-common-cut.v1' and cut['targetName']=='ouf_onboarding_upload_frozen_v39','NAMED_CUT')
    require(cut['sourceStopped'] and cut['storageStopped'] and cut['systemIdentifier']==receipt['systemIdentifier'],'SOURCE_CUT_IDENTITY')
    require(cut['frozenOwnerSha256']==sha(data['frozen-owner.json']),'FROZEN_CUT_BINDING')
    backup_count=0
    for component in ('base','wal','cold'):
        require(cut['components'][component]==sha(data[component+'-inventory.json']),'COMPONENT_BINDING')
        inventory=j(component+'-inventory');actual={};names=set();total=0
        with tarfile.open(fileobj=io.BytesIO(data[component+'.tar.gz'])) as tar:
            for member in tar:
                path=PurePosixPath(member.name);require(not path.is_absolute() and '..' not in path.parts and path.parts[0]==component,'SAFE_TAR_PATH')
                require(member.name not in names and (member.isdir() or member.isfile()),'REGULAR_UNIQUE_TAR');names.add(member.name)
                if member.isdir():continue
                total+=member.size;require(total<=1024*1024*1024,'TAR_BOUND')
                payload=tar.extractfile(member).read();require(len(payload)==member.size,'TAR_SIZE')
                actual[str(path.relative_to(component))]={'bytes':len(payload),'sha256':sha(payload)}
        require(actual==inventory,'EVERY_BACKUP_BYTE:'+component);backup_count+=len(actual)
    require(backup_count==receipt['backupFiles'],'BACKUP_COUNT')
    segment=cut['targetWalSegment'];require(segment in j('wal-inventory') and 'pg_wal/'+segment not in j('base-inventory'),'POST_BASE_REQUIRED_WAL')
    frozen=j('frozen-owner');draining=j('draining-owner');base=j('base-owner')
    require(frozen['storage_recovery_authority_projection']==[{'singleton':True,'version':0,'decision_id':None}]
            and not frozen['storage_recovery_action_audit']
            and not frozen['ouf_authorization.storage_recovery_decision']
            and not frozen['ouf_authorization.storage_recovery_delivery_audit'],'V40_NO_IMPLICIT_AUTHORITY')
    gate=frozen['storage_recovery_lab_gate'];audit=frozen['storage_recovery_lab_audit']
    require(len(gate)==1 and gate[0]['scope']=='onboarding:managed-file-storage'
            and gate[0]['epoch']==1 and gate[0]['quiescent'] is True and gate[0]['revoked'] is False
            and gate[0]['decision_ref']=='lab-only/decision' and gate[0]['quiescence_ref']=='lab-only/quiescence','LAB_GATE_BINDING')
    require(base['storage_recovery_lab_gate'][0]['quiescent'] is False
            and draining['storage_recovery_lab_gate'][0]['quiescent'] is False,'QUIESCENCE_AFTER_NATIVE_DENIALS')
    require([a['outcome'] for a in sorted(audit,key=lambda a:a['id'])]==['DRAINED','READ_RESERVED','ACKED','FROZEN']
            and all(a['session_id']==gate[0]['session_id'] and a['decision_ref']==gate[0]['decision_ref']
                    and a['quiescence_ref']==gate[0]['quiescence_ref'] for a in audit),'ATOMIC_LAB_AUDIT')
    restart=j('authority-restart');restored=j('authority-restore');live=j('authority-after-cut')
    require(live=={'installationRef':'lab-installation','decisionRef':'lab-only/decision','epoch':1,'revision':2,'revoked':True},'CURRENT_REVOKED_AUTHORITY')
    require(receipt['currentAuthoritySha256']==sha(data['authority-after-cut.json'])
            and restored['currentAuthoritySha256']=='sha256:'+receipt['currentAuthoritySha256'],'CURRENT_AUTHORITY_BYTES')
    require(gate[0]['installation_ref']=='lab-installation'
            and restart['oldRuntime']==draining['storage_recovery_lab_gate'][0]['runtime_session_id']
            and restart['currentRuntime']==gate[0]['runtime_session_id']==gate[0]['quiescence_runtime_session_id']
            and restart['oldRuntime']!=restart['currentRuntime'],'FRESH_RUNTIME_BINDING')
    require(all(restart[k] is True for k in ('oldAuthorityRejected','unprovenCustodyRejected','freshCustodyAfterNativeDenials'))
            and restart['readsBeforeFreshEvidence']==restart['readsAfterRejectedAttempts']==0,'RESTART_BLOCKED_BEFORE_BUDGET')
    require(restored['restoredRuntime']==gate[0]['runtime_session_id'] and restored['currentRuntime']!=restored['restoredRuntime']
            and restored['restoredProjectionRevoked'] is False and restored['currentDecisionRevoked'] is True
            and restored['currentRevision']==2
            and all(restored[k] is True for k in ('replayRejected','freshAttestationRejected','missingAuthorityRejected')),'RESTORE_AUTHORITY_REJECTED')
    revoked=j('revoked-after-cut-owner');expected_revoked=json.loads(json.dumps(frozen))
    expected_revoked['storage_recovery_lab_gate'][0]['revoked']=True
    require(revoked==expected_revoked,'REVOCATION_AFTER_NAMED_CUT')
    ordered=sorted(audit,key=lambda a:a['id'])
    require(ordered[0]['runtime_session_id']==restart['oldRuntime'] and ordered[0]['quiescence_runtime_session_id'] is None
            and all(a['runtime_session_id']==a['quiescence_runtime_session_id']==restart['currentRuntime'] for a in ordered[1:]),'AUDIT_RUNTIME_BINDING')
    worker=j('authority-live-runtime')
    require(worker['originalDatabaseStartedAt']!=worker['restoredDatabaseStartedAt'] and worker['liveWorkerRejected'] is True,'LIVE_WORKER_DATABASE_BINDING')
    require(not base['managed_file_upload_intent'] and not base['managed_file_asset'],'BASE_PRECEDES_INTENTS')
    require(frozen==j('restored-owner')==j('restored-after-s3'),'EXACT_OWNER_AFTER_RESTORE')
    require(frozen['managed_file_storage_control']==[{'singleton':True,'epoch':1,'phase':'FROZEN'}],'PERSISTENT_FROZEN_CONTROL')
    require(draining['managed_file_storage_control']==[{'singleton':True,'epoch':1,'phase':'DRAINING'}],'DRAIN_CONTROL')
    intents=frozen['managed_file_upload_intent'];before={r['intent_id']:r for r in draining['managed_file_upload_intent']}
    assets={r['asset_id']:r for r in frozen['managed_file_asset']};attempts={r['asset_id']:r for r in frozen['managed_file_upload_attempt']}
    require(len(intents)==len(assets)==len(attempts)==2,'EXACT_TWO_OWNER_OBJECTS')
    require(sorted(r['state'] for r in before.values())==['ACKED','DISPATCHED'],'REAL_PENDING_BEFORE_RECOVERY')
    for row in intents:
        require(row['state']=='ACKED' and row['epoch']==1,'ALL_INTENTS_ACKED')
        previous=before[row['intent_id']]
        changed={'state','asset_id','acknowledged_at','reconciliation_reads'}
        require({k:v for k,v in row.items() if k not in changed}=={k:v for k,v in previous.items() if k not in changed},'IMMUTABLE_INTENT')
        if previous['state']=='ACKED':require(row==previous,'ORIGINAL_ACK_UNCHANGED')
        else:require(row['reconciliation_reads']==previous['reconciliation_reads']+1,'DURABLE_READ_BUDGET')
        asset=assets[row['asset_id']];attempt=attempts[row['asset_id']]
        for key in ('content_hash','size_bytes','tenant_id'):require(row[key]==asset[key]==attempt[key],'OWNER_BINDING:'+key)
        require(all(row[k]==attempt[k] for k in ('file_id','idempotency_key')),'RECEIPT_BINDING')
        require(asset['staging_ref']==row['staging_ref'] and asset['uploaded_by']==row['subject_id']==attempt['subject_id'],'STABLE_OBJECT_OWNER')
    objects=j('objects-source');require(objects==j('objects-restored') and len(objects)==2,'S3_PARITY')
    for row in intents:
        obj=objects['managed-files/'+row['intent_id']]
        require(obj=={'sha256':row['content_hash'],'bytes':row['size_bytes'],'contentType':'text/csv'},'BODY_METADATA_BINDING')
    for stage in ('source','restored'):
        denials=[json.loads(line) for line in data['denials-'+stage+'.jsonl'].splitlines()]
        require(len(denials)==3 and {d['key'] for d in denials}==set(objects)|{'stale-new-object'} and all(d['code']=='AccessDenied' for d in denials),'NATIVE_WRITE_DENIALS')
    require(j('missing-wal')['status']=='EXPECTED_FAILURE' and j('missing-wal')['restoredS3Started']==0 and j('missing-wal')['state']['ExitCode']!=0,'NEGATIVE_WAL_GATE')
    require(b'recovery ended before configured recovery target was reached' in data['missing-wal.log'],'NATIVE_MISSING_WAL_LOG')
    require(b'recovery stopping at restore point' in data['ouf-upload-cut-pg-stopped.log'] and b'ouf_onboarding_upload_frozen_v39' in data['ouf-upload-cut-pg-stopped.log'],'NAMED_TARGET_NATIVE_LOG')
    require(j('db-before-s3')=={'status':'PASS','restoredS3Started':0,'systemIdentifier':cut['systemIdentifier']},'DB_GATE_BEFORE_S3')
    commands=[json.loads(line) for line in data['commands.jsonl'].splitlines()]
    phases=[c['args'][-1] for c in commands if c['args'][0]=='java' and c['returncode']==0]
    require(phases==['init','seed-drain','reconcile-freeze','revoke-after-cut','watch-live-runtime','verify-db','verify-s3'],'FRESH_JVM_PHASES')
    states=[state for group in j('stops') for state in group.values()]
    require(len(states)==14 and all(s['Status']=='exited' and s['ExitCode']==0 and not s['OOMKilled'] for s in states),'CLEAN_NATIVE_STOPS')
    require(sum(v['bytes'] for v in objects.values())==receipt['objectBytes'],'OBJECT_BYTE_COUNT')
    return {'status':'PASS','commit':commit,'archiveSha256':sha(Path(archive).read_bytes()),'members':len(data),
            'checksums':len(manifest),'sources':len(bindings),'backupFiles':backup_count,'objects':len(objects),
            'independentNativeRestore':False,'targetOperations':0}

def negative_probes(archive,root,commit):
    with zipfile.ZipFile(archive) as z:original={n:z.read(n) for n in z.namelist()}
    results=[]
    cases=[('source-bindings.json','EXACT_SOURCE_BINDING_SET',lambda x:x.pop(next(k for k in x if k.endswith('ManagedFileStorageRecovery.java')))),
           ('receipt.json','TERMINAL_PASS',lambda x:x.update(status='BLOCKED')),
           ('common-cut.json','COMPONENT_BINDING',lambda x:x['components'].update(wal='0'*64)),
           ('restored-owner.json','EXACT_OWNER_AFTER_RESTORE',lambda x:x['managed_file_storage_control'][0].update(phase='ACTIVE')),
           ('missing-wal.json','NEGATIVE_WAL_GATE',lambda x:x.update(restoredS3Started=1)),
           ('authority-restart.json','RESTART_BLOCKED_BEFORE_BUDGET',lambda x:x.update(oldAuthorityRejected=False)),
           ('authority-restore.json','RESTORE_AUTHORITY_REJECTED',lambda x:x.update(freshAttestationRejected=False)),
           ('revoked-after-cut-owner.json','REVOCATION_AFTER_NAMED_CUT',lambda x:x['storage_recovery_lab_gate'][0].update(revoked=False)),
           ('authority-live-runtime.json','LIVE_WORKER_DATABASE_BINDING',lambda x:x.update(liveWorkerRejected=False))]
    with tempfile.TemporaryDirectory() as directory:
        for name,expected,change in cases:
            data=dict(original);value=json.loads(data[name]);change(value);data[name]=json.dumps(value).encode()
            # Recompute the outer manifest: these must fail semantic checks, not just hashes.
            data['SHA256SUMS']=''.join(sha(v)+'  '+k+'\n' for k,v in sorted(data.items()) if k!='SHA256SUMS').encode()
            candidate=Path(directory)/'modified.zip'
            with zipfile.ZipFile(candidate,'w',zipfile.ZIP_DEFLATED) as z:
                for k,v in data.items():z.writestr(k,v)
            try:verify(candidate,root,commit)
            except ValueError as error:require(str(error)==expected,'EXPECTED_REJECTION:'+expected);results.append(expected)
            else:raise ValueError('TAMPER_ACCEPTED:'+name)
    return results

if __name__=='__main__':
    archive=Path(sys.argv[1]);root=Path.cwd();commit=sys.argv[2]
    result=verify(archive,root,commit)
    if '--negative-probes' in sys.argv:result['negativeReadbacks']=negative_probes(archive,root,commit)
    print(json.dumps(result,indent=2,sort_keys=True))
