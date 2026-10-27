const {test}=require('node:test');
const assert=require('node:assert/strict');
const http=require('node:http');
const fs=require('node:fs');
const path=require('node:path');
const {chromium}=require('playwright');

async function surface(run){
  const root=path.resolve('src/main/resources/ths');
  const server=http.createServer((req,res)=>{const part=req.url.split('?')[0];const name=part.endsWith('/recovery.js')?'profile-recovery.js':part.endsWith('/recovery.css')?'profile-recovery.css':'profile-recovery.html';
    res.setHeader('Content-Type',name.endsWith('.js')?'text/javascript':name.endsWith('.css')?'text/css':'text/html');res.setHeader('Content-Security-Policy',"default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");res.end(fs.readFileSync(path.join(root,name)));});
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));const browser=await chromium.launch({headless:true});
  const asset='00000000-0000-4000-8000-000000000001',job='00000000-0000-4000-8000-000000000002';
  try{await run(await browser.newPage(),`http://127.0.0.1:${server.address().port}/trusted-human/managed-files/recovery/?asset=${asset}&job=${job}`,asset,job);}
  finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
}
function initial(asset,job){return{assetId:asset,jobId:job,status:'FAILED',cumulativeAttempts:5,expectedVersion:0,expectedGeneration:0,inputBindingHash:'sha256:'+'a'.repeat(64),reviewHash:'sha256:'+'b'.repeat(64),contentHash:'sha256:'+'c'.repeat(64),sizeBytes:13903,uploadedAt:'2026-10-26T10:00:00Z',lastFailureCategory:'OBJECT_READ_FORBIDDEN',budgetAvailableToGrant:true,canResume:false,canCancel:true,history:[]};}

test('native profiling review separates budget and resume, retains five and monitors one job with no hidden decisions',async()=>surface(async(page,url,asset,job)=>{
  await page.clock.install();let card=initial(asset,job),posts=[],reads=0,errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.route('**/recovery/api/**',async route=>{const request=route.request();
    if(request.method()==='GET'){reads++;if(card.status==='READY')card={...card,status:'SUCCEEDED',cumulativeAttempts:6,sessionUsedAttempts:1,sessionState:'COMPLETED',canResume:false,canCancel:false};await route.fulfill({json:{card,csrfHeader:'X-CSRF-TOKEN',csrfToken:'fixture-csrf'}});return;}
    posts.push({path:new URL(request.url()).pathname,body:request.postDataJSON(),csrf:request.headers()['x-csrf-token']});
    if(request.url().endsWith('/budget'))card={...card,expectedVersion:1,expectedGeneration:1,budgetAvailableToGrant:false,canResume:true,sessionId:asset,sessionState:'ACTIVE',sessionUsedAttempts:0,sessionBaseAttempts:5,sessionExpiresAt:'2999-01-01T00:00:00Z',decisionRef:job};
    else card={...card,status:'READY',expectedVersion:2,expectedGeneration:2,canResume:false};await route.fulfill({json:card});
  });
  await page.goto(url);await page.locator('#review').waitFor();assert.equal(posts.length,0);assert.equal(reads,1);
  assert.equal(await page.locator('#budget').isDisabled(),true);await page.locator('#reason').fill('Dependency permission repaired and input verified');await page.locator('#confirmed').check();await page.locator('#budget').click();
  await page.getByRole('status').filter({hasText:'Budget concesso'}).waitFor();assert.equal(posts.length,1);assert.equal(card.cumulativeAttempts,5);assert.equal(card.status,'FAILED');
  assert.equal(posts[0].body.maximumAttempts,3);assert.equal(posts[0].body.maximumElapsedSeconds,900);assert.equal(posts[0].csrf,'fixture-csrf');assert.equal(await page.locator('#resume').isDisabled(),true);
  await page.locator('#confirmed').check();await page.locator('#resume').click();await page.getByRole('status').filter({hasText:'Recupero avviato'}).waitFor();assert.equal(posts.length,2);
  assert.deepEqual(posts[1].body,{expectedVersion:1,expectedGeneration:1,inputBindingHash:'sha256:'+'a'.repeat(64),sessionId:asset});
  await page.clock.fastForward(6100);await page.getByRole('status').filter({hasText:'Analisi completata sullo stesso job'}).waitFor();assert.equal(card.cumulativeAttempts,6);assert.equal(posts.length,2);
  await page.clock.fastForward(120000);assert.equal(reads,2);assert.deepEqual(errors,[]);
  fs.mkdirSync('target/browser-evidence',{recursive:true});await page.screenshot({path:'target/browser-evidence/profile-recovery-complete.png',fullPage:true});
}));

test('lost budget response survives reload and reconciles exact command without extending or automatically resuming',async()=>surface(async(page,url,asset,job)=>{
  let card=initial(asset,job),posts=[],lost=true;await page.route('**/recovery/api/**',async route=>{
    const request=route.request();if(request.method()==='GET'){await route.fulfill({json:{card,csrfHeader:'X-CSRF-TOKEN',csrfToken:'fixture'}});return;}
    posts.push(request.postDataJSON());card={...card,expectedVersion:1,expectedGeneration:1,budgetAvailableToGrant:false,canResume:true,sessionId:asset,sessionState:'ACTIVE',sessionExpiresAt:'2999-01-01T00:00:00Z',sessionUsedAttempts:0,sessionBaseAttempts:5,decisionRef:job};
    if(lost){lost=false;await route.abort('failed');}else await route.fulfill({json:card});
  });
  await page.goto(url);await page.locator('#review').waitFor();await page.locator('#reason').fill('Dependency permission repaired and input verified');await page.locator('#confirmed').check();await page.locator('#budget').click();
  await page.getByRole('status').filter({hasText:'Esito non confermato'}).waitFor();assert.equal(posts.length,1);
  await page.reload();await page.locator('#review').waitFor();assert.equal(posts.length,1);assert.equal(await page.locator('#resume').isDisabled(),true);
  await page.locator('#reconcile').click();await page.getByRole('status').filter({hasText:'Budget concesso'}).waitFor();assert.equal(posts.length,2);assert.deepEqual(posts[0],posts[1]);assert.equal(card.cumulativeAttempts,5);assert.equal(card.status,'FAILED');
  assert.equal(await page.locator('#budget').isDisabled(),true);assert.equal(await page.locator('#resume').isDisabled(),true);
  await page.locator('#confirmed').check();assert.equal(await page.locator('#resume').isEnabled(),true);
}));

test('deny hides decisions and server labels are text, with no automatic POST after refresh',async()=>surface(async(page,url,asset,job)=>{
  let deny=true,posts=0;await page.route('**/recovery/api/**',async route=>{if(route.request().method()==='POST')posts++;
    if(deny)await route.fulfill({status:403,json:{code:'DENIED'}});else await route.fulfill({json:{card:{...initial(asset,job),uploadedAt:'<img src=x onerror=alert(1)>'},csrfHeader:'X-CSRF-TOKEN',csrfToken:'fixture'}});});
  await page.goto(url);await page.getByRole('status').filter({hasText:'Accesso non autorizzato'}).waitFor();assert.equal(await page.locator('#review').isHidden(),true);assert.equal(posts,0);
  deny=false;await page.locator('#refresh').click();await page.locator('#review').waitFor();assert.equal(await page.locator('#facts img').count(),0);assert.equal(posts,0);
}));
