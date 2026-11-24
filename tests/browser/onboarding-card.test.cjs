const {test} = require('node:test');
const assert = require('node:assert/strict');
const {chromium} = require('playwright');

test('task51e-04 · la THS spiega il blocco per dimensione a 375 px', async () => {
  const browser = await chromium.launch({headless:true});
  try {
    const page = await browser.newPage({viewport:{width:375,height:844}});
    const id = '00000000-0000-4000-8000-000000000002';
    const card = {challenge_id:id, onboarding_version_id:id, source_id:'luoghi', source_name:'Luoghi civici', version:2,
      configuration_hash:'sha256:'+'a'.repeat(64), status:'CONFIRMED', onboardingState:'APPROVED', lockVersion:3,
      progress:{workflowState:'APPROVED', reasonCode:'ONB_PUBLICATION_ENVELOPE_TOO_LARGE',
        allowedActions:['WITHDRAW_CANDIDATE'], publicationEnvelopeBlock:{bytes:2400000,limitBytes:2097152},
        activationProgress:{humanActivationAllowed:false,humanRecheckAllowed:false,humanBudgetGrantAvailable:false}}};
    const posts = [], errors = [];
    page.on('pageerror', error=>errors.push(error.message));
    await page.route('**/*', route=>{
      const url = new URL(route.request().url());
      assert.equal(url.hostname,'ths.test');
      if (route.request().method() === 'POST') posts.push(url.pathname);
      if (url.pathname.includes('/api/')) return route.fulfill({json:{card,csrfHeader:'X-CSRF-TOKEN',csrfToken:'fixture'}});
      const asset=url.pathname.endsWith('review.js')?'workflow-review.js':url.pathname.endsWith('review.css')?'workflow-review.css':'workflow-review.html';
      return route.fulfill({body:require('./ths-asset.cjs')(asset),contentType:asset.endsWith('.js')?'text/javascript':asset.endsWith('.css')?'text/css':'text/html'});
    });
    await page.goto('https://ths.test/trusted-human/onboarding/?challenge='+id);
    const heading = page.getByRole('heading',{name:'Pubblicazione troppo grande'});
    await heading.waitFor();
    const message = await heading.locator('..').innerText();
    assert.match(message,/2\.400\.000 byte.*2\.097\.152 byte/);
    assert.match(message,/non può essere attivata/);
    assert.match(message,/chiedi all’agente di ritirare questa proposta e preparare una revisione più piccola/);
    for (const button of ['complete','resume','budget']) assert.ok(await page.locator('#'+button).isDisabled(),button);
    assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth));
    card.onboardingState='IN_REVIEW'; card.status='CREATED'; card.expires_at=new Date(Date.now()+60_000).toISOString();
    await page.reload();
    await page.getByText(/Scegli “Rifiuta” per chiudere questa proposta/).waitFor();
    assert.ok(await page.locator('#approve').isDisabled());
    assert.ok(await page.locator('#reject').isEnabled());
    assert.deepEqual(posts,[]);
    assert.deepEqual(errors,[]);
  } finally { await browser.close(); }
});

test('la THS rende ogni colonna, raccoglie modifiche e distingue la nuova revisione dal rifiuto', async () => {
  const browser = await chromium.launch({headless:true});
  try {
    const page = await browser.newPage({viewport:{width:375,height:844}});
    const id = '00000000-0000-4000-8000-000000000001', base = 'https://ths.test/trusted-human/onboarding/';
    const cls = 'https://example.org/luoghi/Luogo';
    const names = ['codice','nome','latitudine','capienza','apertura','referente','indirizzo','civico','frazione','provincia','telefono','mail','sito','orario','note','zona','stato','gestore','anno','ingresso','uscita'];
    const labels = ['ha codice','denominazione','latitudine','capienza','data di apertura'];
    const field = {}, semantic = {}, signals = {};
    const ref = (artifactType,labelIt) => ({artifactType,labelIt,semanticVersion:'1.3',revisionId:id,publicationSetId:'set-luoghi',descriptionIt:'Definizione del catalogo',publisher:'Comune di Trieste',license:'CC BY 4.0'});
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
    semantic[field.latitudine.property].descriptionIt = null;
    const agent = {labelIt:'FALSO-AGENTE',descriptionIt:'FALSO-AGENTE',license:'FALSO-AGENTE'};
    const snapshot = {sourceName:'Luoghi civici',owner:'Comune',file:{name:'Luoghi.csv',size:4217,rows:27,columns:21,format:'CSV'},
      columnOrder:names,FIELD:field,SEMANTIC:Object.fromEntries(Object.keys(semantic).map(iri=>[iri,agent])),LIMITS:{maxRows:1500,maxColumns:25,maxCellChars:800},
      IDENTITY:{sourceKey:['codice'],...signals,'sufficientRules:codice':{id:'codice',signalIds:[field.codice.property]},allowAutoNew:false,maxCandidates:5}};
    const mapRef='urn:test:map@'+id, vocabulary='urn:test:vocabulary@'+id;
    field.nome = {...field.nome,transform:'VALUE_MAP_V1',transformParams:{valueMapRef:mapRef},
      valueMap:{valueMapRef:mapRef,name:'Stati di apertura',targetVocabulary:vocabulary,entryCount:2}};
    const card = {challenge_id:id,source_id:'luoghi',version:1,status:'CREATED',onboardingState:'IN_REVIEW',configuration_hash:'sha256:'+'b'.repeat(64),lockVersion:2,
      expires_at:'2999-01-01T00:00:00Z',reviewSnapshot:snapshot,verifiedTerms:semantic,termVerification:'COMPLETE',
      configuration:{semanticReviewTerms:{[cls]:agent},semanticReferenceBindings:[{reviewTerms:[agent]}],semanticMapping:{targetClasses:[{classIri:cls}]}}};
    const typeId='urn:test:postal', typeRef=typeId+'@'+id;
    const pin={semanticId:typeId,publicationSetId:id,label:{it:'Recapito pubblicato'},definitionSha256:'a'.repeat(64),
      definition:{components:[{name:'indirizzo',type:'string',cardinality:'ONE',required:true,labels:{it:'Indirizzo postale'}}]}};
    const joined={constructorId:'TEXT_JOIN_V1',inputs:[{role:'part',fieldName:'indirizzo',reader:'TEXT_NFC_TRIM_V1'},
      {role:'part',fieldName:'civico',reader:'IDENTITY'}],params:{separator:' / '}};
    const address={kind:'STRUCTURED',targetPropertyIri:'urn:indirizzo',dataAccessLabel:'OPEN',typeRef,
      components:[{component:'indirizzo',constructor:joined}]};
    pin.definition.components.push({name:'forme',type:'geometry',cardinality:'MANY',required:false},
      {name:'punti',type:'geometry',cardinality:'MANY',required:false});
    address.components.push({component:'forme',items:[{fieldName:'indirizzo',reader:'WKT_V1',transformParams:{crs:'EPSG:6708'}}]},
      {component:'punti',constructor:{constructorId:'LIST_SPLIT_V1',inputs:[{role:'value',fieldName:'indirizzo',reader:'IDENTITY'}],
        params:{separator:'|',itemReader:'WKT_V1',itemReaderParams:{crs:'EPSG:32633'}}}});
    card.configuration.semanticReferenceBindings.push(pin);
    card.configuration.semanticMapping.compositions=[address];
    snapshot.DATATYPE={[typeRef]:pin};
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
      return route.fulfill({body:require('./ths-asset.cjs')(asset),contentType:asset.endsWith('.js')?'text/javascript':asset.endsWith('.css')?'text/css':'text/html'});
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
    assert.equal(await table.getByRole('link',{name:field.codice.property,exact:true}).getAttribute('href'),field.codice.property);
    assert.match(await table.innerText(),/dato personale/);
    // d084_25 — the column shows the governed summary, with no source-code table.
    const mappedName=table.getByRole('row').filter({has:page.getByRole('rowheader',{name:'nome',exact:true})});
    assert.match(await mappedName.innerText(),/Mappa di valori Stati di apertura/);
    assert.ok((await mappedName.innerText()).includes('Vocabolario di destinazione '+vocabulary));
    assert.match(await mappedName.innerText(),/2 voci/);
    assert.doesNotMatch(await mappedName.innerText(),/Concetto associato|Codice della fonte/);
    for(const text of ['testo','numero','data','ha codice','chiave della fonte','segnale d’identità'])assert.ok((await table.innerText()).includes(text));
    const article=page.locator('article');
    for(const text of ['Luoghi.csv','4217 byte','27 righe · 21 colonne','Luogo pubblico','del proprietario Comune','Cosa comporta l’approvazione','Ingestion acquisisce','UDP materializza','disattivare','1500','set-luoghi','tutti i segnali','creazione richiede'])assert.ok((await article.innerText()).includes(text),text);
    assert.match(await article.innerText(),/Recapito pubblicato/);
    assert.match(await article.innerText(),/Indirizzo postale.*testo.*un valore.*obbligatorio/);
    assert.match(await article.innerText(),/Unisce in ordine.*Uniforma i caratteri Unicode/);
    assert.match(await article.innerText(),/Elenco in ordine.*EPSG:6708/);
    assert.match(await article.innerText(),/Divide il testo.*EPSG:32633/);
    const warnings=await page.locator('.warnings').innerText();
    for(const text of ['latitudine · il profilo rileva un numero','latitudine · licenza','latitudine · descrizione italiana assente','referente · dato personale · esclusa'])assert.ok(warnings.includes(text),text);
    for(const text of ['verificato dal registro semantico','Definizione del catalogo','Classe','Proprietà','Comune di Trieste','CC BY 4.0']) assert.ok((await article.innerText()).includes(text),text);
    assert.doesNotMatch(await article.textContent(),/FALSO-AGENTE/);
    assert.ok(await page.locator('#approve').isEnabled());
    for (const state of ['UNAVAILABLE','INCOMPLETE']) {
      card.termVerification=state;card.verifiedTerms=state==='UNAVAILABLE'?{}:{...semantic};
      delete card.verifiedTerms[cls];
      await page.reload(); await table.waitFor();
      const notices=await page.locator('.warnings').allTextContents();
      assert.ok(notices.some(text=>text.includes(cls+' · etichetta non verificata dal registro')));
      if(state==='UNAVAILABLE')assert.ok(notices.some(text=>text.includes('Registro semantico non disponibile')));
      else assert.match(await table.innerText(),/ha codice/);
      assert.ok(await page.locator('#approve').isDisabled());
      assert.ok(await page.locator('#reject').isEnabled());
      assert.doesNotMatch(await article.textContent(),/FALSO-AGENTE/);
    }
    card.termVerification='COMPLETE';card.verifiedTerms=semantic;
    assert.ok((await article.textContent()).includes('Righe massime · 1500')); // frozen predecessor
    snapshot.LIMITS_BASIS={
      maxRows:{effective:1500,observed:27,ceiling:127,installationMaximum:1000000,marginPercent:25,minHeadroom:100,origin:'ABOVE_CEILING'},
      maxColumns:{effective:25,observed:21,ceiling:29,installationMaximum:1024,marginPercent:25,minHeadroom:8,origin:'RESTRICTED'},
      maxCellChars:{effective:800,observed:null,ceiling:800,installationMaximum:800,marginPercent:25,minHeadroom:4,origin:'DERIVED'}
    };
    await page.reload();await table.waitFor();assert.ok(await page.locator('#approve').isEnabled());
    for (const text of ['Valore reale nel file · 27','Tetto 127','valore reale + 25 %','almeno + 100',
      'ristretto dall’agente','calcolato dall’Onboarding','non misurato per questo profilo','prima di consegnare qualsiasi riga'])
      assert.ok((await article.textContent()).includes(text),text);
    assert.match(await article.locator('.warnings').allTextContents().then(parts=>parts.join(' ')),/supera il tetto attuale di 127/);
    assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth));
    const columnScroll=page.getByRole('region',{name:'Campi del file, tabella scorrevole'});
    assert.ok(await columnScroll.evaluate(region=>region.scrollWidth>region.clientWidth));
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
    card.previousReview.changes.push({section:'SEMANTIC',target:cls,before:{...agent,revisionId:'old'},after:{...agent,revisionId:'new'}});
    const old=structuredClone(address);old.components[0].constructor.params.separator=' | ';
    card.previousReview.changes.push({section:'COMPOSITION',target:'urn:indirizzo',before:old,after:address});
    field.referente.access='RESTRICTED'; card.version=2; card.status='CREATED'; card.onboardingState='IN_REVIEW';
    await page.reload(); await page.getByRole('heading',{name:'Differenze rispetto alla versione 1'}).waitFor();
    const diff=page.locator('.warnings').first();
    assert.match(await diff.innerText(),/Composizione urn:indirizzo.*Unisce in ordine.* → .*Unisce in ordine/);
    assert.match(await diff.innerText(),/PERSONAL.*→.*RESTRICTED/);
    assert.doesNotMatch(await diff.textContent(),/FALSO-AGENTE/);
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
