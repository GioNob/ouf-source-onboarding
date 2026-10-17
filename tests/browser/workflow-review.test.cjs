const {test}=require('node:test');
const assert=require('node:assert/strict');
const http=require('node:http');
const fs=require('node:fs');
const path=require('node:path');
const {chromium}=require('playwright');

test('workflow HUMAN UI makes no automatic decisions and resumes exact approval before publication',async()=>{
  const root=path.resolve('src/main/resources/ths');
  const server=http.createServer((req,res)=>{
    const route=req.url.split('?')[0];
    const name=route.endsWith('/review.js')?'workflow-review.js':route.endsWith('/review.css')?'workflow-review.css':route.endsWith('/')?'workflow-review.html':null;
    if(!name){res.writeHead(404).end();return;}
    res.setHeader('Content-Type',name.endsWith('.js')?'text/javascript':name.endsWith('.css')?'text/css':'text/html');
    res.setHeader('Content-Security-Policy',"default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
    res.end(fs.readFileSync(path.join(root,name)));
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));const browser=await chromium.launch({headless:true});
  try{
    const base=`http://127.0.0.1:${server.address().port}`,id='00000000-0000-4000-8000-000000000001',hash='a'.repeat(64);
    const page=await browser.newPage(),errors=[],posts=[];page.on('pageerror',e=>errors.push(e.message));
    let semantic={challenge_id:id,status:'OPEN',lifecycleStatus:'IN_REVIEW',target_content_hash:hash,expires_at:'2999-01-01T00:00:00Z',label_json:{it:'Vocabolario teatri di test'},artifact_type:'ONTOLOGY',definition_json:{datatype:'http://www.w3.org/2001/XMLSchema#string'},interchangeSnapshot:{content:'<urn:test> a <urn:Ontology> .'}};
    await page.route('**/trusted-human/semantic/api/**',async route=>{
      const req=route.request();if(req.method()==='POST'){
        posts.push({path:new URL(req.url()).pathname,body:req.postDataJSON(),csrf:req.headers()['x-csrf-token']});
        if(req.url().endsWith('/decision'))semantic={...semantic,status:'APPROVED',approvalDecisionId:id};
        else semantic={...semantic,lifecycleStatus:'ACTIVE'};
        await route.fulfill({json:{status:'stored'}});
      }else await route.fulfill({json:{card:semantic,csrfHeader:'X-CSRF-TOKEN',csrfToken:'test-csrf'}});
    });
    await page.goto(`${base}/trusted-human/semantic/?challenge=${id}`);await page.locator('article.card').waitFor();
    assert.equal(posts.length,0);assert.equal(await page.locator('#approve').isDisabled(),true);
    await page.locator('article input[type=checkbox]').check();await page.locator('#approve').click();
    await page.getByRole('status').filter({hasText:'1 decisioni registrate'}).waitFor();
    assert.deepEqual(posts[0].body,{decision:'APPROVED',expectedContentHash:hash});assert.equal(posts[0].csrf,'test-csrf');
    assert.equal(posts.length,1);assert.equal(await page.locator('#complete').isDisabled(),true);
    await page.locator('article input[type=checkbox]').check();await page.locator('#complete').click();
    await page.getByText('Stato: ACTIVE',{exact:true}).waitFor();assert.equal(posts.length,2);
    assert.deepEqual(posts[1].body,{approvalDecisionId:id,contentHash:hash});
    await page.reload();await page.locator('article.card').waitFor();assert.equal(posts.length,2);
    let sourcePosts=0;const source={challenge_id:id,status:'CREATED',onboardingState:'IN_REVIEW',lockVersion:2,configuration_hash:'sha256:'+hash,source_id:'test-source',source_name:'Test source',expires_at:'2999-01-01T00:00:00Z',configuration:{semanticMapping:{propertyMappings:[{sourceField:'nome',targetPropertyIri:'urn:name',transform:'IDENTITY'}]}},progress:{ingestionCompatibilityObserved:false}};
    await page.route('**/trusted-human/onboarding/api/**',async route=>{
      if(route.request().method()==='POST' && route.request().url().endsWith('/resume')){
        sourcePosts++;assert.deepEqual(route.request().postDataJSON(),{configurationHash:'sha256:'+hash,expectedVersion:3,expectedGeneration:1,blockedCode:'ONB_READINESS_DEPENDENCY_403'});
        source.progress.activationProgress={...source.progress.activationProgress,readiness_phase:'PAUSED',readiness_generation:2};
        await route.fulfill({json:{state:'APPROVED'}});return;
      }
      if(route.request().method()==='POST'){sourcePosts++;assert.deepEqual(route.request().postDataJSON(),{configurationHash:'sha256:'+hash,expectedVersion:source.lockVersion});await route.fulfill(route.request().url().endsWith('/activate')?{json:{state:'ACTIVE'}}:{status:412,json:{code:'ONB_REVIEW_STALE_CARD'}});}
      else await route.fulfill({json:{card:source,csrfHeader:'X-CSRF-TOKEN',csrfToken:'test-csrf'}});
    });
    await page.goto(`${base}/trusted-human/onboarding/?challenge=${id}`);await page.locator('article.card').waitFor();assert.equal(sourcePosts,0);
    await page.locator('article input[type=checkbox]').check();await page.locator('#approve').click();
    await page.getByRole('status').filter({hasText:'nessun invio viene ripetuto automaticamente'}).waitFor();assert.equal(sourcePosts,1);
    await page.locator('#refresh').click();await page.getByRole('status').filter({hasText:'Esamina le proposte'}).waitFor();assert.equal(sourcePosts,1);assert.deepEqual(errors,[]);
    // An approved source can now request real readiness before activation; no hidden approval or automatic POST.
    source.status='CONFIRMED';source.onboardingState='APPROVED';source.lockVersion=3;
    source.progress={ingestionCompatibilityObserved:false,activationProgress:{readiness_phase:'NOT_STARTED'}};
    await page.reload();await page.locator('article.card').waitFor();
    await page.locator('article input[type=checkbox]').check();
    assert.equal(await page.locator('#complete').isEnabled(),true);
    assert.equal(await page.locator('#approve').isDisabled(),true);
    const priorSourcePosts=sourcePosts;
    await page.locator('#complete').click();
    await page.getByRole('status').filter({hasText:'1 decisioni registrate'}).waitFor();
    assert.equal(sourcePosts,priorSourcePosts+1);
    source.progress.activationProgress={readiness_phase:'PAUSED',readiness_next_at:'2999-01-01T00:00:00Z'};
    await page.reload();await page.locator('article.card').waitFor();await page.locator('article input[type=checkbox]').check();
    assert.equal(await page.locator('#complete').isDisabled(),true);
    assert.equal(sourcePosts,priorSourcePosts+1);
    source.progress.activationProgress={readiness_phase:'BLOCKED',readiness_generation:1,readiness_attempts:1,readiness_started_at:new Date().toISOString(),readiness_last_code:'ONB_READINESS_DEPENDENCY_403'};
    await page.reload();await page.locator('article.card').waitFor();await page.locator('article input[type=checkbox]').check();
    assert.equal(await page.locator('#complete').isDisabled(),true);assert.equal(await page.locator('#resume').isEnabled(),true);
    const beforeResume=sourcePosts;await page.locator('#resume').click();
    await page.getByRole('status').filter({hasText:'Ripresa registrata'}).waitFor();
    assert.equal(sourcePosts,beforeResume+1);assert.equal(source.onboardingState,'APPROVED');
    assert.equal(await page.locator('#complete').isDisabled(),true);
    await page.reload();await page.locator('article.card').waitFor();await page.locator('article input[type=checkbox]').check();
    assert.equal(await page.locator('#complete').isEnabled(),true);assert.equal(await page.locator('#resume').isDisabled(),true);
    assert.equal(sourcePosts,beforeResume+1);
    fs.mkdirSync('target/browser-evidence',{recursive:true});await page.screenshot({path:'target/browser-evidence/workflow-source-review.png',fullPage:true});
  }finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
});
