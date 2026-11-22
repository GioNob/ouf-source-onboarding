const {test}=require('node:test');
const assert=require('node:assert/strict');
const http=require('node:http');
const fs=require('node:fs');
const path=require('node:path');
const {chromium}=require('playwright');

test('workflow HUMAN UI makes no automatic decisions and resumes exact approval before publication',async t=>{
  const root=path.resolve('src/main/resources/ths');
  let sessionExpired=false,returnPage;
  const server=http.createServer((req,res)=>{
    const route=req.url.split('?')[0];
    if(req.url.endsWith('&__ths_login')){
      returnPage=req.url.slice(0,-'&__ths_login'.length);
      res.writeHead(302,{Location:'/oauth2/authorization/ouf-ths'}).end();return;
    }
    if(route==='/oauth2/authorization/ouf-ths'){
      sessionExpired=false;res.writeHead(302,{Location:returnPage}).end();return;
    }
    const name=route.endsWith('/session.js')?'session.js':route.endsWith('/review.js')?'workflow-review.js':route.endsWith('/review.css')?'workflow-review.css':route.endsWith('/')?'workflow-review.html':null;
    if(!name){res.writeHead(404).end();return;}
    res.setHeader('Content-Type',name.endsWith('.js')?'text/javascript':name.endsWith('.css')?'text/css':'text/html');
    res.setHeader('Content-Security-Policy',"default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
    res.end(require('./ths-asset.cjs')(name));
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));const browser=await chromium.launch({headless:true});
  try{
    const base=`http://127.0.0.1:${server.address().port}`,id='00000000-0000-4000-8000-000000000001',hash='a'.repeat(64);
    const page=await browser.newPage(),errors=[],posts=[];page.on('pageerror',e=>errors.push(e.message));
    let semantic={challenge_id:id,status:'OPEN',lifecycleStatus:'IN_REVIEW',target_content_hash:hash,expires_at:'2999-01-01T00:00:00Z',label_json:{it:'Luogo di spettacolo',en:'Performance venue'},artifact_type:'CLASS',namespace:'urn:test:venues',semantic_version:'1.2',revision_no:3,adoptionProvenance:{canonicalUri:'https://example.org/Venue',sourceLocation:'https://example.org/venues.ttl',publisher:'http://spcdata.digitpa.gov.it/Amministrazione/agid',licenseWarning:'Licenza mancante'},validationFindings:[{message:'Controllare la copertura'}],definition_json:{datatype:'http://www.w3.org/2001/XMLSchema#string'},interchangeSnapshot:{content:'<urn:test> a <urn:Ontology> .'}};
    await page.route('**/trusted-human/semantic/api/**',async route=>{
      const req=route.request();
      if(sessionExpired){await route.fulfill({status:401,json:{code:'THS_SESSION_EXPIRED'}});return;}
      if(req.method()==='POST'){
        posts.push({path:new URL(req.url()).pathname,body:req.postDataJSON(),csrf:req.headers()['x-csrf-token']});
        if(req.url().endsWith('/decision'))semantic={...semantic,status:'APPROVED',approvalDecisionId:id};
        else semantic={...semantic,lifecycleStatus:'ACTIVE'};
        await route.fulfill({json:{status:'stored'}});
      }else await route.fulfill({headers:{'X-OUF-Review-Actor':'b'.repeat(64)},json:{card:semantic,csrfHeader:'X-CSRF-TOKEN',csrfToken:'test-csrf'}});
    });
    await page.goto(`${base}/trusted-human/semantic/?challenge=${id}`);await page.locator('article.card').waitFor();
    assert.equal(posts.length,0);assert.equal(await page.locator('#approve').isDisabled(),true);
    await page.getByRole('heading',{name:'Stai approvando l’adozione della classe «Luogo di spettacolo» (Performance venue)'}).waitFor();
    assert.match(await page.locator('article dl').innerText(),/Agenzia per l’Italia Digitale \(AgID\)/);
    assert.match(await page.locator('article dl').innerText(),/Descrizione non disponibile nella fonte/);
    assert.match(await page.locator('.warnings').innerText(),/Licenza mancante/);
    assert.match(await page.locator('.warnings').innerText(),/Controllare la copertura/);
    assert.equal(await page.getByRole('link',{name:'Consulta la fonte'}).getAttribute('href'),'https://example.org/venues.ttl');
    await page.locator('article input').check();sessionExpired=true;await page.locator('#approve').click();
    await page.getByRole('heading',{name:'Sessione scaduta: accedi di nuovo'}).waitFor();
    await page.getByRole('button',{name:'Accedi di nuovo'}).click();await page.locator('article.card').waitFor();
    assert.equal(new URL(page.url()).search,'?challenge='+id);
    assert.match(await page.locator('article output:visible').innerText(),/Approva le proposte selezionate/);
    assert.equal(await page.locator('article input').isChecked(),true);assert.equal(posts.length,0);
    await page.locator('article input[type=checkbox]').check();await page.locator('#approve').click();
    await page.getByRole('status').filter({hasText:'1 decisioni registrate'}).waitFor();
    assert.deepEqual(posts[0].body,{decision:'APPROVED',expectedContentHash:hash});assert.equal(posts[0].csrf,'test-csrf');
    assert.equal(posts.length,1);assert.equal(await page.locator('#complete').isDisabled(),true);
    await page.locator('article input[type=checkbox]').check();await page.locator('#complete').click();
    await page.getByText('Stato: ACTIVE',{exact:true}).waitFor();assert.equal(posts.length,2);
    assert.deepEqual(posts[1].body,{approvalDecisionId:id,contentHash:hash});
    await page.reload();await page.locator('article.card').waitFor();assert.equal(posts.length,2);
    semantic={...semantic,status:'OPEN',lifecycleStatus:'IN_REVIEW',artifact_type:'PROPERTY',
      label_json:{it:'Nome <img>',en:'Name'},adoptionProvenance:{canonicalUri:'urn:test:name',sourceLocation:'javascript:void(0)',license:'CC BY 4.0'}};
    await page.reload();await page.getByRole('heading',{name:'Stai approvando l’adozione della proprietà «Nome <img>» (Name)'}).waitFor();
    assert.equal(await page.locator('article img, article a').count(),0);
    await page.locator('article input').check();sessionExpired=true;await page.locator('#approve').click();
    await page.getByRole('button',{name:'Accedi di nuovo'}).waitFor();
    semantic={...semantic,target_content_hash:'d'.repeat(64)};
    await page.getByRole('button',{name:'Accedi di nuovo'}).click();await page.locator('article.card').waitFor();
    assert.equal(await page.locator('article input').isChecked(),false);assert.equal(posts.length,2);
    await t.test('d084_16 mostra la scheda DATATYPE restituita dal Semantic', async () => {
      const component = {nome:'strade', etichetta:'Strade <img>', tipo:'Testo', cardinalità:'Più valori',
        obbligatorio:'Sì', vincoli:'Lunghezza massima 80'};
      const review = {componenti:[component], versionePrecedente:'Prima pubblicazione', differenze:[]};
      semantic = {...semantic, artifact_type:'DATATYPE', label_json:{it:'Indirizzo'}, datatypeReview:review,
        validationFindings:['Il componente «strade» è senza etichetta italiana.']};
      delete semantic.adoptionProvenance;
      await page.reload();
      await page.getByRole('heading', {name:'Stai approvando il tipo strutturato «Indirizzo»'}).waitFor();
      const table = page.getByRole('table', {name:'Componenti del tipo strutturato'});
      await table.waitFor();
      assert.deepEqual(await table.locator('thead th').allTextContents(), ['Nome','Tipo','Cardinalità','Obbligatorio','Vincoli']);
      assert.deepEqual(await table.locator('tbody tr > *').allTextContents(), ['strade · Strade <img>','Testo','Più valori','Sì','Lunghezza massima 80']);
      assert.equal(await table.locator('img').count(), 0);
      assert.match(await page.locator('.warnings').innerText(), /Il componente «strade»/);
      assert.doesNotMatch(await page.locator('.warnings').innerText(), /Licenza/);
      await page.getByText('Prima pubblicazione', {exact:true}).waitFor();
      review.versionePrecedente = '1.0';
      const old = {name:'strade',type:'string',cardinality:'MANY',required:false};
      review.differenze = [
        {campo:'strade', modifica:'Modificato', prima:old, dopo:{...old,required:true,constraints:{maxLength:80}}},
        {campo:'cap', modifica:'Aggiunto', dopo:{...old,name:'cap'}},
        {campo:'civico', modifica:'Rimosso', prima:{...old,name:'civico'}},
        {campo:'Ordine dei componenti', modifica:'Modificato', prima:['a','b'], dopo:['b','a']}
      ];
      await page.reload();
      await page.getByText('1.0', {exact:true}).waitFor();
      for (const change of review.differenze) {
        await page.getByRole('heading', {name:change.campo + ' · ' + change.modifica}).waitFor();
      }
      for (const text of ['Prima · strade · testo · Più valori · Facoltativo',
        'Dopo · strade · testo · Più valori · Obbligatorio · Lunghezza massima 80',
        'Dopo · cap · testo · Più valori · Facoltativo','Prima · civico · testo · Più valori · Facoltativo',
        'Prima · a → b','Dopo · b → a']) await page.getByText(text, {exact:true}).waitFor();
      review.differenze = [];
      await page.reload();
      await page.getByText('Nessuna differenza.', {exact:true}).waitFor();
      const viewport = page.viewportSize();
      await page.setViewportSize({width:375,height:720});
      const region = page.getByRole('region', {name:'Componenti del tipo strutturato'});
      await region.focus();
      assert.equal(await region.evaluate(el => el === document.activeElement && el.scrollWidth > el.clientWidth), true);
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true);
      await page.setViewportSize(viewport);
      assert.equal(posts.length, 2);
    });
    await t.test('d084_31 presenta e rende scorrevole la mappa di valori semantica a 375 px', async () => {
      const viewport = page.viewportSize();
      await page.setViewportSize({width:375,height:720});
      const review = {name:'Stati <img>', targetVocabulary:{label:'Stato di apertura',semanticId:'urn:test:vocabolario',publicationSetId:id},
        entryCount:2000, sourceColumn:{fieldName:'codice_stato',sourceId:'source-1'},
        entries:Array.from({length:2000}, (_, i) => ({code:'C'+String(i).padStart(4,'0'),
          concept:'urn:test:concetto:'+('x'.repeat(120))+i, conceptLabel:'Etichetta <img> '+i})),
        changes:{added:['C1999'],removed:['COLD'],changed:[{code:'C0000',from:'urn:before',to:'urn:after'}]}};
      semantic = {...semantic, artifact_type:'VALUE_MAP', label_json:{it:review.name}, valueMapReview:review, validationFindings:[]};
      await page.reload();
      await page.getByRole('heading', {name:'Stai approvando la mappa di valori «Stati <img>»'}).waitFor();
      const view = page.locator('.value-map-review');
      for (const text of ['Stato di apertura','urn:test:vocabolario','2000','codice_stato','source-1','1 aggiunti · 1 rimossi · 1 modificati',
        'Prima · urn:before','Dopo · urn:after','COLD']) await view.getByText(text, {exact:true}).waitFor({state:'attached'});
      const region = view.getByRole('region', {name:'Codici e concetti associati'});
      const rows = region.locator('tbody tr');
      assert.equal(await rows.count(),2000);
      assert.equal(await rows.first().getByRole('rowheader').innerText(),'C0000');
      assert.match(await rows.first().getByRole('cell').innerText(),/Etichetta <img> 0/);
      assert.equal(await view.locator('img').count(),0);
      await region.focus();
      assert.equal(await region.evaluate(el => el === document.activeElement && el.scrollHeight > el.clientHeight && el.scrollWidth === el.clientWidth),true);
      await rows.last().scrollIntoViewIfNeeded();
      assert.ok(await region.evaluate(el => el.scrollTop > 0));
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),true);
      await page.locator('article input[type=checkbox]').check();
      assert.equal(await page.locator('#approve').isEnabled(),true);
      delete semantic.valueMapReview;
      await page.reload();
      await page.getByText(/Dettagli della mappa di valori non disponibili/).waitFor();
      await page.locator('article input[type=checkbox]').check();
      assert.equal(await page.locator('#approve').isDisabled(),true);
      assert.equal(posts.length,2);
      await page.setViewportSize(viewport);
    });
    let sourcePosts=0,budgetPosts=0,budgetCommand;const source={challenge_id:id,status:'CREATED',onboardingState:'IN_REVIEW',lockVersion:2,configuration_hash:'sha256:'+hash,source_id:'test-source',source_name:'Test source',expires_at:'2999-01-01T00:00:00Z',configuration:{semanticMapping:{targetClasses:[{classIri:"urn:example:class",category:{schemeIri:"urn:example:scheme",conceptIri:"urn:example:concept"}},{classIri:"urn:example:other"}],propertyMappings:[{sourceField:'nome',targetPropertyIri:'urn:name',transform:'IDENTITY'}]}},progress:{ingestionCompatibilityObserved:false}};
    source.configuration.dataAccessPolicies=[{scope:'PROPERTY',target:'urn:name',label:'RESTRICTED'}];
    source.termVerification='COMPLETE';
    source.verifiedTerms=Object.fromEntries(['urn:example:class','urn:example:other','urn:name'].map(iri=>[iri,{
      artifactType:iri==='urn:name'?'PROPERTY':'CLASS',labelIt:'Termine '+iri,descriptionIt:'Descrizione',
      semanticVersion:'1',revisionId:id,publicationSetId:id,publisher:'Comune',license:'CC BY'}]));
    source.newColumns=[{column:'note',decision:'EXCLUDE'},{column:'nome',decision:'MAP',mapping:{propertyIri:'urn:name',transform:'IDENTITY',reconciliation:{cardinality:'SINGLE'}}}];
    await page.route('**/trusted-human/onboarding/api/**',async route=>{
      if(route.request().method()==='POST' && route.request().url().endsWith('/resume')){
        sourcePosts++;assert.deepEqual(route.request().postDataJSON(),{configurationHash:'sha256:'+hash,expectedVersion:3,expectedGeneration:1,blockedCode:'ONB_READINESS_DEPENDENCY_403'});
        source.progress.activationProgress={...source.progress.activationProgress,readiness_phase:'PAUSED',readiness_generation:2};
        await route.fulfill({json:{state:'APPROVED'}});return;
      }
      if(route.request().method()==='POST'){
        sourcePosts++;
        if(route.request().url().endsWith('/budget')) {
          budgetPosts++;budgetCommand=route.request().postDataJSON();
          assert.equal(budgetCommand.configurationHash,'sha256:'+hash);assert.equal(budgetCommand.expectedVersion,3);
          assert.equal(budgetCommand.expectedGeneration,4);assert.equal(budgetCommand.maximumAttempts,3);
          assert.equal(budgetCommand.maximumElapsedSeconds,900);assert.equal(typeof budgetCommand.commandKey,'string');
          source.progress.activationProgress={readiness_phase:'PAUSED',readiness_generation:5,cumulativeAttempts:3,sessionAttempts:0,budgetExhausted:false,humanBudgetGrantAvailable:false};
          // The owner stored the decision, but the browser lost the response.
          await route.abort('failed');
        } else {
          assert.deepEqual(route.request().postDataJSON(),{configurationHash:'sha256:'+hash,expectedVersion:source.lockVersion});
          await route.fulfill(route.request().url().endsWith('/activate')?{json:{state:'ACTIVE'}}:{status:412,json:{code:'ONB_REVIEW_STALE_CARD'}});
        }
      }
      else await route.fulfill({json:{card:source,csrfHeader:'X-CSRF-TOKEN',csrfToken:'test-csrf'}});
    });
    await page.goto(`${base}/trusted-human/onboarding/?challenge=${id}`);
    for(const label of ['Termine urn:example:class · urn:example:class','Vocabolario: urn:example:scheme','Categoria: urn:example:concept','Categoria assente'])
      await page.getByText(label,{exact:true}).waitFor({state:'visible'});
    await page.locator('article.card').waitFor();assert.equal(sourcePosts,0);
    assert.match(await page.locator('article').innerText(),/RESTRICTED/);
    await page.getByText('note · Nessuna proprietà pubblicata · Accesso: non indicato',{exact:true}).waitFor();
    const decisions=page.getByRole('table').filter({has:page.getByRole('columnheader',{name:'Decisione',exact:true})});
    assert.equal(await decisions.getByRole('row',{name:/note EXCLUDE Conservata solo nel file/}).isVisible(),true);
    const mapped=decisions.getByRole('row',{name:/nome MAP Termine urn:name · urn:name/});
    await mapped.getByText('Trasformazione e regole').click();
    assert.match(await mapped.innerText(),/IDENTITY/);assert.match(await mapped.innerText(),/SINGLE/);
    await page.locator('#approve').click();
    await page.getByRole('status').filter({hasText:'nessun invio viene ripetuto automaticamente'}).waitFor();assert.equal(sourcePosts,1);
    await page.locator('#refresh').click();await page.getByRole('status').filter({hasText:'Esamina le proposte'}).waitFor();assert.equal(sourcePosts,1);assert.deepEqual(errors,[]);
    // An approved source can now request real readiness before activation; no hidden approval or automatic POST.
    source.status='CONFIRMED';source.onboardingState='APPROVED';source.lockVersion=3;
    source.progress={ingestionCompatibilityObserved:false,activationProgress:{readiness_phase:'NOT_STARTED'}};
    await page.reload();await page.locator('article.card').waitFor();

    assert.equal(await page.locator('#complete').isEnabled(),true);
    assert.equal(await page.locator('#approve').isDisabled(),true);
    const priorSourcePosts=sourcePosts;
    await page.locator('#complete').click();
    await page.getByRole('status').filter({hasText:'1 decisioni registrate'}).waitFor();
    assert.equal(sourcePosts,priorSourcePosts+1);
    source.progress.activationProgress={readiness_phase:'PAUSED',readiness_next_at:'2999-01-01T00:00:00Z'};
    await page.reload();await page.locator('article.card').waitFor();
    assert.equal(await page.locator('#complete').isDisabled(),true);
    assert.equal(sourcePosts,priorSourcePosts+1);
    source.progress.activationProgress={readiness_phase:'BLOCKED',readiness_generation:1,readiness_attempts:1,readiness_started_at:new Date().toISOString(),readiness_last_code:'ONB_READINESS_DEPENDENCY_403'};
    await page.reload();await page.locator('article.card').waitFor();
    assert.equal(await page.locator('#complete').isDisabled(),true);assert.equal(await page.locator('#resume').isEnabled(),true);
    const beforeResume=sourcePosts;await page.locator('#resume').click();
    await page.getByRole('status').filter({hasText:'Ripresa registrata'}).waitFor();
    assert.equal(sourcePosts,beforeResume+1);assert.equal(source.onboardingState,'APPROVED');
    assert.equal(await page.locator('#complete').isEnabled(),true);
    await page.reload();await page.locator('article.card').waitFor();
    assert.equal(await page.locator('#complete').isEnabled(),true);assert.equal(await page.locator('#resume').isDisabled(),true);
    assert.equal(sourcePosts,beforeResume+1);
    source.progress.activationProgress={readiness_phase:'ACTIVATING',readiness_generation:4,cumulativeAttempts:3,budgetExhausted:true,
      humanBudgetGrantAvailable:true,reconciledBudgetReason:'ONB_READINESS_INTERRUPTED'};
    source.expires_at='2020-01-01T00:00:00Z'; // Already CONFIRMED approval is retained.
    await page.reload();await page.locator('article.card').waitFor();

    assert.equal(await page.locator('#complete').isDisabled(),true);
    assert.match(await page.locator('textarea').inputValue(),/interrotto/);
    assert.equal(await page.locator('#budget').isEnabled(),true);
    const beforeBudget=sourcePosts;
    await page.locator('#budget').click();
    await page.getByRole('status').filter({hasText:'nessun invio viene ripetuto automaticamente'}).waitFor();
    assert.equal(budgetPosts,1);assert.equal(sourcePosts,beforeBudget+1);
    await page.locator('#refresh').click();await page.getByRole('status').filter({hasText:'Esamina le proposte'}).waitFor();

    assert.equal(await page.locator('#budget').isDisabled(),true);
    assert.equal(await page.locator('#complete').isEnabled(),true);
    assert.equal(sourcePosts,beforeBudget+1); // No hidden activation after budget grant or refresh.
    assert.equal(source.progress.activationProgress.cumulativeAttempts,3);
    fs.mkdirSync('target/browser-evidence',{recursive:true});await page.screenshot({path:'target/browser-evidence/workflow-source-review.png',fullPage:true});
  }finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
});

