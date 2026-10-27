#!/usr/bin/env python3
"""Stage bytes of an existing qualified CI artifact; no build, target access or runtime authority."""
import gzip,hashlib,importlib.util,io,json,os,re,shutil,signal,stat,sys,tarfile,zipfile,zlib
from pathlib import Path

SOURCE='6ea5242f711761a384fa1b23667971601bc2c38b'
TESTED='e42c398af3ab6989a956925445c478f8eb9f2446'
IMAGE='sha256:24b89e0430513b441453467a57cca983fcef2dbb6733fe29dc0fa34b1be67463'
ZIP_SHA='afc1da28317b06845c8a37d81c529e931cb3d11415f13b9842fb3633a0e8d3d0'
ZIP_BYTES=212905775
GZIP_SHA='e3868e3e6582150da2d94e3f4c467ef5140a54bbad2a69aa6a4306b43938c3c9'
GZIP_BYTES=212904147
CUSTODY_SHA='5d0fc0edb1ebd9386326eadbbee08f2cf0a9fd1e122940c0661b5ac5e3655ed8'
PART_BYTES=25165824
class Stop(RuntimeError):pass
def require(value,code):
    if not value:raise Stop(code)
def encoded(value):return json.dumps(value,sort_keys=True,separators=(',',':'),allow_nan=False).encode()
def file_hash(path):
    h=hashlib.sha256()
    with path.open('rb')as stream:
        for block in iter(lambda:stream.read(1048576),b''):h.update(block)
    return h.hexdigest()
def split(source,folder,size=PART_BYTES):
    rows=[]
    with source.open('rb')as stream:
        while block:=stream.read(size):
            name='candidate.zip.part%02d'%len(rows);path=folder/name
            with path.open('xb')as out:out.write(block)
            rows.append({'name':name,'bytes':len(block),'sha256':hashlib.sha256(block).hexdigest()})
    return rows
def checksum(raw):
    value=0
    for line in raw.decode('utf-8-sig').splitlines():value=zlib.crc32(line.encode(),value)
    return value if value<2**31 else value-2**32
def jar_facts(path,baseline):
    with zipfile.ZipFile(path)as jar:
        rows=[r for r in jar.infolist()if r.filename.startswith('BOOT-INF/classes/db/migration/')and r.filename.endswith('.sql')]
        require(len(rows)==36 and len({r.filename for r in rows})==36,'EXACT_36_MIGRATION_RESOURCES_REQUIRED')
        facts=[]
        for row in rows:
            match=re.fullmatch(r'BOOT-INF/classes/db/migration/V([1-9][0-9]*)__[A-Za-z0-9_]+\.sql',row.filename)
            require(match and 0<row.file_size<=131072,'BOUNDED_SQL_RESOURCE_REQUIRED');raw=jar.read(row)
            facts.append({'version':match[1],'script':row.filename.split('/')[-1],'sha256':hashlib.sha256(raw).hexdigest(),'flywayChecksum':checksum(raw)})
        facts.sort(key=lambda r:int(r['version']))
        require([r['version']for r in facts]==[str(i)for i in range(1,37)],'MIGRATION_VERSION_SET_REQUIRED')
        require(facts[:35]==baseline,'ALL_35_RETAINED_MIGRATIONS_MUST_MATCH')
        require(facts[-1]['script']=='V36__managed_profile_recovery_sessions.sql','EXACT_V36_RESOURCE_REQUIRED')
        yaml=jar.read('BOOT-INF/classes/application.yml');require(len(yaml)<=131072,'BOUNDED_APP_YAML_REQUIRED')
    return {'jarSha256':file_hash(path),'jarBytes':path.stat().st_size,'applicationYamlSha256':hashlib.sha256(yaml).hexdigest(),'migrations':facts}
def prepare(source,destination,deploy):
    require(source.is_file()and source.stat().st_size==ZIP_BYTES and file_hash(source)==ZIP_SHA,'EXACT_QUALIFIED_ZIP_REQUIRED')
    require(not destination.exists(),'DELIVERY_DIRECTORY_CONSUMED');destination.mkdir(mode=0o700)
    work=destination/'work';work.mkdir(mode=0o700);image=destination/'image';image.mkdir(mode=0o700)
    with zipfile.ZipFile(source)as zipped:
        rows=zipped.infolist();require(len(rows)==3 and{r.filename for r in rows}=={'image.tar.gz','manifest.json','SHA256SUMS'},'EXACT_CI_MEMBER_SET_REQUIRED')
        for row in rows:
            require(not row.is_dir()and stat.S_IFMT(row.external_attr>>16)in(0,stat.S_IFREG),'REGULAR_CI_MEMBERS_REQUIRED')
            require(0<row.file_size<=(GZIP_BYTES if row.filename=='image.tar.gz'else 131072),'BOUNDED_CI_MEMBER_REQUIRED')
            with zipped.open(row)as src,(work/row.filename).open('xb')as out:shutil.copyfileobj(src,out,1048576)
    require(file_hash(work/'image.tar.gz')==GZIP_SHA and(work/'SHA256SUMS').read_text()==GZIP_SHA+'  image.tar.gz\n','EXACT_IMAGE_ARCHIVE_REQUIRED')
    manifest=json.loads((work/'manifest.json').read_bytes())
    require(manifest['schema']=='ouf.onboarding-tested-image.v1'and manifest['reviewedSourceCommit']==SOURCE and manifest['testedCommit']==TESTED
        and manifest['imageId']==IMAGE and manifest['imageRevisionLabel']==TESTED and manifest['workflowRunId']=='40533881204'
        and manifest['sourceSchemaMigration']=='V36__managed_profile_recovery_sessions.sql'and manifest['archiveBytes']==GZIP_BYTES
        and manifest['archiveSha256']==GZIP_SHA and manifest['targetApplyAuthorized']is False and manifest['humanBudgetGranted']is False
        and manifest['releaseAcceptance']is False,'EXACT_QUALIFIED_SOURCE_MANIFEST_REQUIRED')
    helper=deploy/'tools/acquisition_read_native/image_custody.py';require(file_hash(helper)==CUSTODY_SHA,'PINNED_PURE_CUSTODY_HELPER_REQUIRED')
    spec=importlib.util.spec_from_file_location('pure_image_custody',helper);custody=importlib.util.module_from_spec(spec);spec.loader.exec_module(custody)
    expanded=work/'expanded.tar'
    with gzip.open(work/'image.tar.gz','rb')as zipped,expanded.open('xb')as out:
        total=0
        while block:=zipped.read(1048576):
            total+=len(block);require(total<=600*1024*1024,'BOUNDED_EXPANDED_IMAGE_REQUIRED');out.write(block)
    expanded_sha=file_hash(expanded)
    with expanded.open('rb')as original:
        audit=custody.archive(original,expanded_sha,IMAGE,TESTED)
        require(audit['imageConfig'].get('User')=='10003:10003'and audit['imageConfig'].get('Entrypoint')==['java','-jar','/app/application.jar'],'EXACT_OWNER_LAUNCH_REQUIRED')
        with(work/'native.tar').open('x+b')as native:derived=custody.tagless_archive(original,native,audit,containerd=True)
    audit['nativeIdentity']=custody.native_identity(audit)
    application=work/'application.jar';found=0
    with tarfile.open(expanded,'r:')as outer:
        for name in audit['layerMembers']:
            with outer.extractfile(name)as stream:
                with tarfile.open(fileobj=stream,mode='r|*')as layer:
                    for member in layer:
                        require(member.name not in('app/.wh.application.jar','app/.wh..wh..opq'),'NO_AMBIGUOUS_APPLICATION_WHITEOUT')
                        if member.name not in('app/application.jar','./app/application.jar'):continue
                        found+=1;require(found==1 and member.isfile()and 0<member.size<=268435456,'ONE_BOUNDED_APPLICATION_JAR_REQUIRED')
                        with layer.extractfile(member)as src,application.open('xb')as out:shutil.copyfileobj(src,out,1048576)
    require(found==1,'PACKAGED_APPLICATION_REQUIRED')
    baseline=json.loads((deploy/'releases/onboarding-diagnostic-migration-fence-20261027.json').read_bytes());facts=jar_facts(application,baseline)
    parts=split(source,image)
    receipt={'schema':'ouf.profile-recovery-ci-image-delivery.v1','reviewedSourceCommit':SOURCE,'testedCommit':TESTED,'artifactId':12747244692,'workflowRunId':40533881204,
        'artifactZipSha256':ZIP_SHA,'artifactZipBytes':ZIP_BYTES,'imageArchiveSha256':GZIP_SHA,'imageArchiveBytes':GZIP_BYTES,'configurationImageId':IMAGE,
        'expandedArchiveSha256':expanded_sha,'expandedArchiveBytes':total,'nativeImageId':custody.runtime_image_id(audit),
        'derivedArchiveSha256':derived['derivedArchiveSha256'],'nativeArchiveBytes':derived['archiveBytes'],'custodyHelperSha256':CUSTODY_SHA,
        'application':facts,'parts':parts,'sourceManifest':manifest,'imageRebuilt':False,'imageImported':False,'imageRelabelled':False,
        'targetApplyAuthorized':False,'runtimeAuthorityIssued':False,'humanBudgetGranted':False,'applicationE2EProven':False,'releaseAcceptance':False}
    (image/'delivery.json').write_bytes(encoded(receipt));(image/'SHA256SUMS').write_text(''.join(p['sha256']+'  '+p['name']+'\n'for p in parts))
    shutil.rmtree(work)
    return receipt
def main():
    try:
        require(len(sys.argv)==4,'CLOSED_DELIVERY_CLI_REQUIRED');signal.signal(signal.SIGALRM,lambda *_:require(False,'FINITE_STAGE_DEADLINE'));signal.alarm(300)
        result=prepare(Path(sys.argv[1]),Path(sys.argv[2]),Path(sys.argv[3]));print(json.dumps({'status':'QUALIFIED_EXISTING_IMAGE_STAGED','nativeImageId':result['nativeImageId'],'parts':len(result['parts']),'expandedArchiveSha256':result['expandedArchiveSha256'],'targetApplyAuthorized':False}),flush=True);return 0
    except Exception as e:
        reason=str(e)if type(e)is Stop and re.fullmatch('[A-Z0-9_]{1,120}',str(e))else'IMAGE_STAGE_UNPROVEN_RECONCILE'
        print(json.dumps({'status':'STOP_RECONCILE','reasonCode':reason,'automaticReplayAllowed':False,'targetApplyAuthorized':False}),flush=True);return 2
    finally:signal.alarm(0)
if __name__=='__main__':raise SystemExit(main())
