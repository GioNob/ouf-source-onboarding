const {test}=require('node:test');
const assert=require('node:assert/strict');
const http=require('node:http');
const fs=require('node:fs');
const path=require('node:path');
const {chromium}=require('playwright');

test('trusted review UI compares geometry and scalar values, submits exact evidence, and handles denial/staleness',async()=>{
  const root=path.resolve('src/main/resources/ths');
  const server=http.createServer((req,res)=>{
    const route=req.url.split('?')[0];
    const file={'/trusted-human/r2f':'r2f.html','/trusted-human/r2f/style.css':'r2f.css','/trusted-human/r2f/review.js':'r2f.js'}[route];
    if(!file){res.writeHead(404).end();return;}
    res.setHeader('Content-Type',file.endsWith('.js')?'text/javascript':file.endsWith('.css')?'text/css':'text/html');
    res.setHeader('Content-Security-Policy',"default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
    res.end(fs.readFileSync(path.join(root,file)));
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const browser=await chromium.launch({headless:true});
  try{
    const base=`http://127.0.0.1:${server.address().port}`,id='00000000-0000-0000-0000-000000000001';
    const page=await browser.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));
    const poly=(size)=>({type:'Polygon',coordinates:[[[0,0],[size,0],[size,size],[0,size],[0,0]]]});
    const review={issueId:id,objectId:id,state:'OPEN',policyRef:'policy://authority/1',propertyIri:'geometry',csrfToken:'test-session-token',current:{revisionId:'old',sourceId:'registry',geometry:poly(1),provenance:{source:'registry'}},candidate:{revisionId:'new',sourceId:'survey',geometry:poly(2),provenance:{source:'survey'}}};
    let sent;
    await page.route('**/api/udp/**',async route=>{
      if(route.request().method()==='POST'){sent={body:route.request().postDataJSON(),csrf:route.request().headers()['x-ouf-csrf']};await route.fulfill({json:{decisionId:id}});}
      else await route.fulfill({json:review});
    });
    await page.goto(`${base}/trusted-human/r2f?kind=geometry&id=${id}`);
    await page.locator('#submit:not([disabled])').waitFor();assert.equal(await page.locator('#map path').count(),2);
    await page.getByRole('button',{name:'Ingrandisci',exact:true}).click();assert.notEqual(await page.locator('#map').getAttribute('viewBox'),'0 0 800 420');
    await page.locator('input[value="new"]').check();await page.locator('#reason').fill('Verificato sul rilievo');await page.locator('#submit').click();
    await page.getByRole('status').filter({hasText:'Decisione registrata'}).waitFor();
    assert.deepEqual(sent,{body:{expectedCurrentRevision:'old',chosenRevision:'new',reason:'Verificato sul rilievo'},csrf:'test-session-token'});
    assert.equal(await page.locator('#submit').isDisabled(),true);
    await page.unroute('**/api/udp/**');
    await page.route('**/api/udp/**',route=>route.fulfill({status:403,json:{code:'DENIED'}}));
    await page.goto(`${base}/trusted-human/r2f?kind=geometry&id=${id}`);await page.getByRole('status').filter({hasText:'Non hai i permessi'}).waitFor();assert.equal(await page.locator('#decision').isHidden(),true);
    await page.unroute('**/api/udp/**');
    const scalar={conflictId:id,objectId:id,state:'OPEN',currentRevision:'canonical-old',propertyIri:'name',policyRef:'policy://authority/1',csrfToken:'scalar-token',candidates:[{contributionId:'one',sourceId:'registry',value:'<img src=x onerror=alert(1)>',provenance:{source:'registry'}},{contributionId:'two',sourceId:'survey',value:'Second',provenance:{source:'survey'}}]};
    await page.route('**/api/udp/**',route=>route.fulfill(route.request().method()==='POST'?{status:409,json:{code:'STALE'}}:{json:scalar}));
    await page.goto(`${base}/trusted-human/r2f?kind=property&id=${id}`);await page.locator('#submit:not([disabled])').waitFor();assert.equal(await page.locator('#choices img').count(),0);
    await page.locator('input[value="two"]').check();await page.locator('#reason').fill('Conferma');await page.locator('#submit').click();await page.getByRole('status').filter({hasText:'I contributi sono cambiati'}).waitFor();
    assert.equal(await page.locator('#map-section').isHidden(),true);assert.deepEqual(errors,[]);
    fs.mkdirSync('target/browser-evidence',{recursive:true});await page.screenshot({path:'target/browser-evidence/property-stale.png',fullPage:true});
  }finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
});
