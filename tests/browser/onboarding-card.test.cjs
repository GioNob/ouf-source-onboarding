const {test} = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const {chromium} = require('playwright');

test('la THS rende ogni colonna, raccoglie modifiche e distingue la nuova revisione dal rifiuto', async () => {
  const browser = await chromium.launch({headless:true});
  try {
    const page = await browser.newPage({viewport:{width:390,height:844}});
    const id = '00000000-0000-4000-8000-000000000001', base = 'https://ths.test/trusted-human/onboarding/';
    const cls = 'https://example.org/luoghi/Luogo';
    const names = ['codice','nome','latitudine','capienza','apertura','referente','indirizzo','civico','frazione','provincia','telefono','mail','sito','orario','note','zona','stato','gestore','anno','ingresso','uscita'];
    const labels = ['ha codice','denominazione','latitudine','capienza','data di apertura'];
    const field = {}, semantic = {}, signals = {};
    const ref = (kind,labelIt) => ({kind,labelIt,semanticVersion:'1.3',publicationSetId:'set-luoghi',descriptionIt:'Definizione del catalogo',license:'CC BY 4.0'});
    semantic[cls] = ref('CLASS','Luogo pubblico');
    for (const [i,name] of names.entries()) {
      const iri = 'https://example.org/luoghi/' + name;
      field[name] = {included:i<5, property:i<5?iri:null, transform:['IDENTITY','IDENTITY','IDENTITY','DECIMAL_DOT_V1','DATE_ISO_V1'][i] || null,
        access:i===5?'PERSONAL':'OPEN',sourceKey:i===0,signal:i<5,profileType:['STRING','STRING','DECIMAL','LONG','DATE'][i] || 'STRING'};
      if (i<5) {
        semantic[iri] = ref('PROPERTY',labels[i]);
        signals['signal:'+iri] = {id:iri,comparator:'TEXT_V1'};
      }
    }
    semantic[field.latitudine.property].license = null;
    const snapshot = {sourceName:'Luoghi civici',owner:'Comune',file:{name:'Luoghi.csv',size:4217,rows:27,columns:21,format:'CSV'},
      columnOrder:names,FIELD:field,SEMANTIC:semantic,LIMITS:{maxRows:1500,maxColumns:25,maxCellChars:800},
      IDENTITY:{sourceKey:['codice'],...signals,'sufficientRules:codice':{id:'codice',signalIds:[field.codice.property]},allowAutoNew:false,maxCandidates:5}};
    const card = {challenge_id:id,source_id:'luoghi',version:1,status:'CREATED',onboardingState:'IN_REVIEW',configuration_hash:'sha256:'+'b'.repeat(64),lockVersion:2,
      expires_at:'2999-01-01T00:00:00Z',reviewSnapshot:snapshot,configuration:{semanticMapping:{targetClasses:[{classIri:cls}]}}};
    const posts=[], errors=[];
    page.on('pageerror',error=>errors.push(error.message));
    await page.route('**/*',async route=>{
      const url=new URL(route.request().url());
      assert.equal(url.hostname,'ths.test');
      if(url.pathname.includes('/api/')){
        if(route.request().method()==='POST'){
          posts.push({url:url.pathname,body:route.request().postDataJSON(),csrf:route.request().headers()['x-csrf-token']});
          card.status='CANCELLED'; card.onboardingState=url.pathname.endsWith('/changes')?'CHANGES_REQUESTED':'REJECTED';
          return route.fulfill({json:{}});
        }
        return route.fulfill({json:{card,csrfHeader:'X-CSRF-TOKEN',csrfToken:'fixture'}});
      }
      const asset=url.pathname.endsWith('session.js')?'session.js':url.pathname.endsWith('review.js')?'workflow-review.js':url.pathname.endsWith('review.css')?'workflow-review.css':'workflow-review.html';
      return route.fulfill({path:path.resolve('src/main/resources/ths',asset)});
    });
    await page.goto(base);
    await page.getByText('Accesso effettuato: apri il link della richiesta da approvare').waitFor();
    assert.equal(await page.locator('#actions').isVisible(),false);
    await page.goto(base+'?challenge='+id);
    const table=page.getByRole('table',{name:'Decisione di acquisizione per ogni colonna del file'});
    await table.waitFor();
    assert.equal(await table.getByRole('rowheader').count(),21);
    assert.equal(await table.getByText('Inclusa',{exact:true}).count(),5);
    assert.equal(await table.getByText('Esclusa · non verrà acquisita',{exact:true}).count(),16);
    assert.equal(await table.getByRole('link',{name:'codice'}).getAttribute('href'),field.codice.property);
    assert.match(await table.innerText(),/dato personale/);
    for(const text of ['testo','numero','data','ha codice','chiave della fonte','segnale d’identità'])assert.ok((await table.innerText()).includes(text));
    const article=page.locator('article');
    for(const text of ['Luoghi.csv','4217 byte','27 righe · 21 colonne','Luogo pubblico','del proprietario Comune','Cosa comporta l’approvazione','Ingestion acquisisce','UDP materializza','disattivare','1500','set-luoghi','tutti i segnali','creazione richiede'])assert.ok((await article.innerText()).includes(text),text);
    const warnings=await page.locator('.warnings').innerText();
    for(const text of ['latitudine · il profilo rileva un numero','latitudine · licenza','referente · dato personale · esclusa'])assert.ok(warnings.includes(text),text);
    assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth));
    assert.equal(await page.locator('#changes').isDisabled(),true);
    const flag=table.getByRole('checkbox',{name:'Da modificare · Campo referente',exact:true});
    await flag.check();
    const comment=table.getByRole('textbox',{name:'Commento per Campo referente'});
    assert.equal(await comment.getAttribute('maxlength'),'500');
    await comment.fill('Rivedere <b>il contatto</b>');
    for (const [section,target] of [['Identità','maxCandidates'],['Riferimento semantico',cls],['Limite','maxRows']]) {
      await page.getByRole('checkbox',{name:'Da modificare · ' + section + ' ' + target,exact:true}).check();
    }
    assert.equal(await page.locator('#approve').isDisabled(),true);
    assert.equal(posts.length,0);
    await page.locator('#changes').click();
    await page.getByText('Stato: modifiche richieste',{exact:true}).waitFor();
    assert.deepEqual(posts[0],{url:'/trusted-human/onboarding/api/'+id+'/changes',csrf:'fixture',
      body:{configurationHash:card.configuration_hash,expectedVersion:2,reviewFeedback:[{section:'FIELD',target:'referente',comment:'Rivedere <b>il contatto</b>'},
        {section:'IDENTITY',target:'maxCandidates',comment:''},{section:'SEMANTIC',target:cls,comment:''},{section:'LIMITS',target:'maxRows',comment:''}]}});
    card.previousReview={version:1,feedback:posts[0].body.reviewFeedback,changes:[{section:'FIELD',target:'referente',before:{...field.referente},after:{...field.referente,access:'RESTRICTED'}}]};
    field.referente.access='RESTRICTED'; card.version=2; card.status='CREATED'; card.onboardingState='IN_REVIEW';
    await page.reload(); await page.getByRole('heading',{name:'Differenze rispetto alla versione 1'}).waitFor();
    const diff=page.locator('.warnings').first();
    assert.match(await diff.innerText(),/PERSONAL.*→.*RESTRICTED/);
    assert.match(await diff.innerText(),/Rivedere <b>il contatto<\/b>/);
    assert.equal(await diff.locator('b').count(),0);
    assert.equal(await page.locator('#changes').isDisabled(),true);
    await page.locator('#reject').click();
    await page.getByText('Stato: rifiutata',{exact:true}).waitFor();
    assert.ok(posts[1].url.endsWith('/reject'));
    assert.equal(posts[1].body.reviewFeedback,undefined);
    assert.deepEqual(errors,[]);
  } finally { await browser.close(); }
});
