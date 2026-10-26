"""Publish only the exact already-qualified public CI ZIP as bounded custody chunks."""
import base64,hashlib,json,os,pathlib,tempfile,urllib.request,urllib.error,urllib.parse,zipfile
REPO='GioNob/ouf-source-onboarding'
BRANCH='delivery/profile-diagnostic-image-20261026'
ZIP_SHA='318a6370e4bedb6d17d5d5d8a9bbdf959ee7051196a51a425d334a324afc71a6'
ZIP_BYTES=210887971
ARTIFACT=12668879487
SOURCE='a5ed8b05c264a8221783a6e323ba00117aed891d'
TESTED='692277b1bf5def4a2b5b1ba4fb6770c16b136fb5'
TOKEN=os.environ['GH_TOKEN']
def require(ok,reason):
    if not ok:raise RuntimeError(reason)
def sha(raw):return hashlib.sha256(raw).hexdigest()
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args):return None
opener=urllib.request.build_opener(NoRedirect())
def api(path,body=None):
    require(path.startswith('/repos/'+REPO+'/'),'CLOSED_REPOSITORY')
    data=None if body is None else json.dumps(body,separators=(',',':')).encode()
    req=urllib.request.Request('https://api.github.com'+path,data=data,headers={'Authorization':'Bearer '+TOKEN,'Accept':'application/vnd.github+json','Content-Type':'application/json','User-Agent':'ouf-exact-image-custody'})
    with opener.open(req,timeout=90) as response:return json.load(response)
def download(path):
    req=urllib.request.Request('https://api.github.com/repos/'+REPO+'/actions/artifacts/'+str(ARTIFACT)+'/zip',headers={'Authorization':'Bearer '+TOKEN,'Accept':'application/vnd.github+json'})
    try:opener.open(req,timeout=30);raise RuntimeError('ARTIFACT_REDIRECT_REQUIRED')
    except urllib.error.HTTPError as response:
        require(response.code==302,'EXACT_ARTIFACT_REDIRECT');url=response.headers['Location']
    for attempt in range(3):
        u=urllib.parse.urlsplit(url);host=u.hostname or ''
        require(u.scheme=='https' and not u.username and not u.password and (host.endswith('.blob.core.windows.net') or host=='objects.githubusercontent.com' or host.endswith('.actions.githubusercontent.com')),'CLOSED_STORAGE_REDIRECT')
        try:response=opener.open(urllib.request.Request(url,headers={'User-Agent':'ouf-exact-image-custody'}),timeout=60)
        except urllib.error.HTTPError as response:
            require(response.code in (301,302,303,307,308),'STORAGE_STATUS');url=response.headers['Location'];continue
        h=hashlib.sha256();size=0
        with response,path.open('xb') as out:
            while chunk:=response.read(1048576):
                size+=len(chunk);require(size<=ZIP_BYTES,'ZIP_SIZE_BOUND');h.update(chunk);out.write(chunk)
        require(size==ZIP_BYTES and h.hexdigest()==ZIP_SHA,'EXACT_ARTIFACT_BYTES');return
    raise RuntimeError('REDIRECT_BOUND_EXHAUSTED')
def main():
    ref='/repos/'+REPO+'/git/ref/heads/'+BRANCH
    head=api(ref)['object']['sha']
    compare=api('/repos/'+REPO+'/compare/'+SOURCE+'...'+TESTED)
    require([f['filename'] for f in compare['files']]==['.github/workflows/qualify-v35-native-deploy.yml'],'EXACT_APPLICATION_SOURCE_EQUIVALENCE')
    with tempfile.TemporaryDirectory() as tmp:
        path=pathlib.Path(tmp)/'candidate.zip';download(path)
        with zipfile.ZipFile(path) as z:
            require(set(z.namelist())=={'SHA256SUMS','manifest.json','image.tar.gz'} and len(z.namelist())==3,'EXACT_ZIP_MEMBERS')
            manifest=json.loads(z.read('manifest.json'))
            require(manifest['reviewedSourceCommit']==SOURCE and manifest['testedCommit']==TESTED and manifest['testedTree']=='7c1142043abece26250a1f8c48aea57e9c2e8948' and manifest['workflowRunId']=='40363741161','EXACT_CI_MANIFEST')
            require(manifest['imageId']=='sha256:83c33ec95ac827edc389f7411b30d1ed85aef8bcec30986f5e19605ab7960a94' and manifest['archiveBytes']==210886343 and manifest['archiveSha256']=='54e78b838eee2626c6dad615bd18cea726886174c24758bbd7db92d91653ef18','EXACT_IMAGE_DESCRIPTOR')
            h=hashlib.sha256()
            with z.open('image.tar.gz') as src:
                while block:=src.read(1048576):h.update(block)
            require(h.hexdigest()==manifest['archiveSha256'] and z.read('SHA256SUMS').decode()=='54e78b838eee2626c6dad615bd18cea726886174c24758bbd7db92d91653ef18  image.tar.gz\n','INNER_ARCHIVE_CHECKSUM')
        entries=[];parts=[]
        with path.open('rb') as stream:
            while raw:=stream.read(24*1024*1024):
                name='image/candidate.zip.part%02d'%len(parts)
                blob=api('/repos/'+REPO+'/git/blobs',{'content':base64.b64encode(raw).decode(),'encoding':'base64'})
                entries.append({'path':name,'mode':'100644','type':'blob','sha':blob['sha']});parts.append({'path':name,'bytes':len(raw),'sha256':sha(raw)})
        require(len(parts)==9,'NINE_BOUNDED_PARTS')
        custody={'schema':'ouf.onboarding-diagnostic-public-image-custody.v1','artifactId':ARTIFACT,'workflowRun':40363741161,'zipBytes':ZIP_BYTES,'zipSha256':ZIP_SHA,'reviewedSourceCommit':SOURCE,'testedCommit':TESTED,'testedTree':manifest['testedTree'],'applicationSourceEquivalent':True,'onlySourceDifference':'.github/workflows/qualify-v35-native-deploy.yml','imageManifest':manifest,'parts':parts,'runtimeAuthorityIssued':False,'targetApplyAuthorized':False,'imageRebuilt':False,'imageRelabeled':False}
        old=api('/repos/'+REPO+'/git/commits/'+head)
        entries.append({'path':'image/custody.json','mode':'100644','type':'blob','content':json.dumps(custody,indent=2)+'\n'})
        tree=api('/repos/'+REPO+'/git/trees',{'base_tree':old['tree']['sha'],'tree':entries})
        commit=api('/repos/'+REPO+'/git/commits',{'message':'Retain exact qualified Onboarding diagnostic image bytes','tree':tree['sha'],'parents':[head]})
        require(api(ref)['object']['sha']==head,'DELIVERY_BRANCH_CHANGED')
        req=urllib.request.Request('https://api.github.com/repos/'+REPO+'/git/refs/heads/'+BRANCH,data=json.dumps({'sha':commit['sha'],'force':False}).encode(),method='PATCH',headers={'Authorization':'Bearer '+TOKEN,'Accept':'application/vnd.github+json','Content-Type':'application/json'})
        with opener.open(req,timeout=30) as response:json.load(response)
        print('PUBLISHED_IMAGE_DELIVERY_COMMIT='+commit['sha'])
        print('EXACT_ZIP_SHA256='+ZIP_SHA)
if __name__=='__main__':main()
