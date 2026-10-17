const {test}=require('node:test');
const assert=require('node:assert/strict');
const http=require('node:http');
const fs=require('node:fs');
const path=require('node:path');
const {chromium}=require('playwright');

test('Resolution compares actual incoming and selected candidate rows, keeps JSON and submits only explicit choices',async()=>{
 const root=path.resolve('src/main/resources/ths');
 const server=http.createServer((req,res)=>{
  const route=req.url.split('?')[0];
  const name=route.endsWith('/review.js')?'resolution.js':route.endsWith('/review.css')?'resolution.css':route.endsWith('/')?'resolution.html':null;
  if(!name){res.writeHead(404).end();return;}
  res.setHeader('Content-Type',name.endsWith('.js')?'text/javascript':name.endsWith('.css')?'text/css':'text/html');
  res.setHeader('Content-Security-Policy',"default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
  res.end(fs.readFileSync(path.join(root,name)));
 });
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));let browser;
 try {browser=await chromium.launch({headless:true});} catch(error) {await new Promise(resolve=>server.close(resolve));throw error;}
 let page;
 try{
  const base=`http://127.0.0.1:${server.address().port}`,a='00000000-0000-4000-8000-000000000001',b='00000000-0000-4000-8000-000000000002';
  page=await browser.newPage({viewport:{width:1440,height:1000}});const posts=[],errors=[];page.on('pageerror',e=>errors.push(e.message));
  const fields={'urn:test:nome':'Teatro in ingresso','urn:test:indirizzo':'Via Roma 1','urn:test:posti':0,'urn:test:note':null,'urn:test:html':'<img src=x onerror="window.injected=true">'};
  const issue={issueId:a,reviewHash:'sha256:'+'a'.repeat(64),version:0,reasonCode:'UNRESOLVED_IDENTITY',candidateRefs:[a,b],suggestedTargetUrbanObjectId:a,
   evidence:[{complete:true,outcome:'REVIEW_REQUIRED',coverageRef:'indexed-snapshot://fixture'}],
   incomingObject:{reference:'handoff://fixture',sourceId:'teatri-test',sourceObjectId:'riga-2',available:true,fields},
   candidateObjects:{[a]:{reference:a,revision:1,available:true,fields:{'urn:test:nome':'Teatro candidato A','urn:test:indirizzo':'Via Roma 9','urn:test:posti':120}},
    [b]:{reference:b,revision:2,available:true,fields:{'urn:test:nome':'Teatro candidato B','urn:test:indirizzo':'Via Roma 1'}}}};
  const later={...structuredClone(issue),issueId:b,reviewHash:'sha256:'+'b'.repeat(64),incomingObject:{...structuredClone(issue.incomingObject),sourceObjectId:'riga-3',fields:{'urn:test:nome':'Teatro da approfondire'}}};
  let fail=false;
  await page.route('**/trusted-human/resolution/api/**',async route=>{
   const req=route.request();
   if(req.method()==='POST'){posts.push(req.postDataJSON());await route.fulfill({status:409,json:{code:'UDP_REVIEW_PACKAGE_STALE'}});}
   else await route.fulfill(fail?{status:403,json:{detail:'denied'}}:{json:{package:{snapshotHash:'sha256:fixture',issues:[issue,later]},csrfHeader:'X-CSRF-TOKEN',csrfToken:'fixture-csrf'}});
  });
  await page.goto(`${base}/trusted-human/resolution/`);await page.waitForFunction(()=>document.querySelector('article.case-card')||document.getElementById('status').classList.contains('error'));
  console.log('RESOLUTION_STATUS',await page.locator('#status').textContent(),'ERRORS',errors);
  assert.equal(await page.locator('#status').evaluate(e=>e.classList.contains('error')),false,await page.locator('#status').textContent());
  await page.locator('article.case-card').first().waitFor();
  assert.equal(posts.length,0);assert.equal(await page.locator('table.object-comparison thead tr').count(),2);assert.equal(await page.locator('table.object-comparison tbody tr').count(),4);
  assert.match(await page.locator('tbody tr').first().textContent(),/Teatro in ingresso/);assert.match(await page.locator('.candidate-row').first().textContent(),/Teatro candidato A/);
  assert.equal(await page.locator('img').count(),0);assert.equal(await page.evaluate(()=>window.injected),undefined);
  assert.equal(await page.locator('tbody tr').first().locator('td').allTextContents().then(x=>x.includes('0')),true);
  assert.equal(await page.locator('tbody tr').first().locator('td').allTextContents().then(x=>x.includes('null')),true);
  await page.locator('.target').first().selectOption(b);assert.match(await page.locator('.candidate-row').first().textContent(),/Teatro candidato B/);assert.equal(posts.length,0);
  await page.locator('.technical-details summary').first().click();assert.match(await page.locator('pre').first().textContent(),/incomingObject/);
  await page.locator('.action').first().selectOption('CREATE_NEW');
  assert.match(await page.locator('.decision-effect').first().textContent(),/oggetto distinto/);assert.match(await page.locator('.candidate-row').first().textContent(),/solo confronto/);
  fs.mkdirSync('target/browser-evidence',{recursive:true});await page.locator('.technical-details summary').first().click();await page.screenshot({path:'target/browser-evidence/resolution-comparison-desktop.png',fullPage:true});
  await page.setViewportSize({width:390,height:844});await page.screenshot({path:'target/browser-evidence/resolution-comparison-mobile.png',fullPage:true});
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth),true);
  await page.locator('.defer').nth(1).check();
  assert.match(await page.locator('#selection-status').textContent(),/1 casi da confermare · 1 lasciati in quarantena/);
  await page.locator('#confirm').click();await page.locator('#status').filter({hasText:'Conferma respinta'}).waitFor();
  assert.equal(posts.length,1);assert.deepEqual(posts[0],{snapshotHash:'sha256:fixture',choices:[{issueId:a,expectedVersion:0,action:'CREATE_NEW',expectedReviewHash:'sha256:'+'a'.repeat(64),targetUrbanObjectId:null,reason:''}]});
  assert.match(await page.locator('#status').textContent(),/UDP_REVIEW_PACKAGE_STALE/);
  assert.equal(await page.locator('#confirm').isDisabled(),true);
  const properties = {
   'urn:test:nome': {available:true,currentValue:'Teatro candidato A',incomingValue:'Teatro in ingresso',proposedValue:'Teatro candidato A',status:'CONFLICT',conflict:true,rule:{authorityOrder:[],reconciliation:{cardinality:'SINGLE'}},contributions:[{sourceId:'layer-live',accessLabel:'RESTRICTED',valueJson:'"<img src=x onerror=alert(1)>"',provenance:{sourceIdentity:{sourceObjectId:'feature-2'}}}]},
   'urn:test:indirizzo': {available:true,proposedValue:'Via Roma 1',status:'RECONCILED',rule:{authorityOrder:['registry-api','file'],reconciliation:{cardinality:'SINGLE'}},contributions:[]},
   'urn:test:posti': {available:true,proposedValue:0,status:'ADDED',rule:{reconciliation:{cardinality:'SINGLE'}},contributions:[]}
  };
  issue.propertyPreviews = {[a]:{available:true,properties}};
  await page.locator('#refresh').click();await page.locator('.result-row').waitFor();
  assert.match(await page.locator('.result-row').textContent(),/Risultato previsto del collegamento/);
  assert.match(await page.locator('.property-conflict').textContent(),/Conflitto: il valore corrente resta invariato/);
  assert.equal(await page.locator('.result-row td').allTextContents().then(values=>values.some(v=>v.startsWith('0'))),true);
  await page.locator('.property-evidence summary').first().click();
  assert.match(await page.locator('.property-explanation').first().textContent(),/layer-live.*feature-2/);
  assert.equal(await page.locator('img').count(),0);
  assert.equal(posts.length,1);
  await page.screenshot({path:'target/browser-evidence/property-reconciliation-mobile.png',fullPage:true});
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth),true);
  await page.setViewportSize({width:1440,height:1000});
  await page.screenshot({path:'target/browser-evidence/property-reconciliation-desktop.png',fullPage:true});
  await page.locator('.target').first().selectOption(b);assert.equal(await page.locator('.result-row').count(),0);
  assert.match(await page.locator('.property-explanation').first().textContent(),/Anteprima delle proprietà non disponibile/);
  await page.locator('.target').first().selectOption(a);await page.locator('.action').first().selectOption('CREATE_NEW');assert.equal(await page.locator('.result-row').count(),0);
  issue.candidateObjects[b]={reference:b,available:false,fields:{}};await page.locator('#refresh').click();await page.locator('.target').first().selectOption(b);
  await page.getByText('Dati del candidato non disponibili: la riga non indica un oggetto vuoto.').waitFor();
  fail=true;await page.locator('#refresh').click();await page.locator('#status').filter({hasText:'HTTP 403'}).waitFor();assert.equal(await page.locator('article.case-card').count(),0);
  assert.equal(posts.length,1);assert.deepEqual(errors,[]);
 }catch(error){fs.mkdirSync('target/browser-evidence',{recursive:true});await page.screenshot({path:'target/browser-evidence/resolution-failure.png',fullPage:true});throw error;}finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
});

