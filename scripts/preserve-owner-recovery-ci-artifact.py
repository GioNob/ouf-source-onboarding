"""One exact existing CI ZIP to a delivery-only Git branch; no rebuild/import/release."""
import hashlib,json,os,subprocess,zipfile
from pathlib import Path

EXPECTED_BRANCH='delivery/managed-file-owner-v38-ci-20261028'
REPO='GioNob/ouf-source-onboarding';RUN=40732932645;ARTIFACT=12836316286
SHA='7719c1045753fe38bbdc498adb8f540c61bad411c25969badf71ec4d3ec5d91b'
def api(path):return json.loads(subprocess.check_output(['gh','api','repos/'+REPO+path]))
def main():
    assert os.environ['GITHUB_REPOSITORY']==REPO and os.environ['GITHUB_REF']=='refs/heads/'+EXPECTED_BRANCH
    run=api('/actions/runs/'+str(RUN));artifact=api('/actions/artifacts/'+str(ARTIFACT))
    assert run['conclusion']=='success'and run['head_sha']=='9d19fad3dd750dd5b9e77253d3e7e82029c1aca7'and run['event']=='pull_request'
    assert artifact['name']=='source-onboarding-governed-image-'+str(RUN)and artifact['size_in_bytes']==213064533 and not artifact['expired']
    assert artifact['digest']=='sha256:'+SHA and artifact['workflow_run']['id']==RUN
    image=Path('image');assert not image.exists();image.mkdir()
    target=Path(os.environ['RUNNER_TEMP'])/'owner-recovery-original-ci.zip'
    with target.open('xb')as output:subprocess.run(['gh','api','repos/'+REPO+'/actions/artifacts/'+str(ARTIFACT)+'/zip'],stdout=output,check=True,timeout=180)
    assert target.stat().st_size==213064533
    h=hashlib.sha256()
    with target.open('rb')as stream:
        while block:=stream.read(1048576):h.update(block)
    assert h.hexdigest()==SHA
    with zipfile.ZipFile(target)as z:
        assert set(z.namelist())=={'manifest.json','image.tar.gz','SHA256SUMS'}
        manifest=json.loads(z.read('manifest.json'))
    assert manifest['reviewedSourceCommit']=='9d19fad3dd750dd5b9e77253d3e7e82029c1aca7'and manifest['testedCommit']=='73498c17596ae155a3ed9ab36acfd6bb69da9846'
    assert manifest['testedTree']=='091a10a53da04ed46759f345e877a69e8db87021'and manifest['sourceSchemaMigration']=='V38__managed_file_legacy_owner_reconciliation.sql'
    assert manifest['archiveSha256']=='eca776c890233c87c9b5e42297683c184424d71f02737b710e839c22eab5903f'
    assert all(manifest[k]is False for k in('targetApplyAuthorized','humanBudgetGranted','releaseAcceptance'))
    parts=[]
    with target.open('rb')as stream:
        while block:=stream.read(25165824):
            name='candidate.zip.part%02d'%len(parts);(image/name).write_bytes(block)
            parts.append({'name':name,'bytes':len(block),'sha256':hashlib.sha256(block).hexdigest()})
    assert len(parts)==9 and sum(p['bytes']for p in parts)==213064533
    receipt={'schema':'ouf.exact-ci-artifact-git-delivery.v1','repository':REPO,'workflowRunId':RUN,'artifactId':ARTIFACT,'artifactZipBytes':213064533,'artifactZipSha256':SHA,'sourceManifest':manifest,'parts':parts,'imageRebuilt':False,'imageImported':False,'releaseAcceptance':False,'targetApplyAuthorized':False}
    (image/'delivery.json').write_text(json.dumps(receipt,sort_keys=True,separators=(',',':'))+'\n')
    subprocess.run(['git','add','--','image'],check=True)
    subprocess.run(['git','-c','user.name=GioNob','-c','user.email=96419716+GioNob@users.noreply.github.com','commit','-m','Preserve exact tested owner recovery V38 CI ZIP without rebuilding'],check=True)
    subprocess.run(['git','push','origin','HEAD:refs/heads/'+EXPECTED_BRANCH],check=True,timeout=180)
    print(json.dumps({'status':'EXACT_V38_CI_ZIP_PRESERVED_NOT_IMPORTED','commit':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),'parts':9,'sha256':SHA,'applicationE2EProven':False}))
if __name__=='__main__':main()
