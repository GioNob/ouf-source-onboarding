'use strict';
(() => {
  const $ = id => document.getElementById(id);
  const flow = location.pathname.split('/')[2];
  const semantic = flow === 'semantic';
  const params = new URLSearchParams(location.search);
  const ids = (params.get('challenges') || params.get('challenge') || '').split(',');
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  const cards = new Map();
  let busy = false;
  const status = (text, error = false) => { $('status').textContent = text; $('status').classList.toggle('error', error); };
  function element(tag, value, parent) { const e = document.createElement(tag); e.textContent = String(value ?? '—'); parent.append(e); return e; }
  function detail(label, value, parent) { const e = document.createElement('details'); element('summary', label, e); element('pre', JSON.stringify(value, null, 2), e); parent.append(e); }
  function sourceLink(parent, label, value) {
    if (!value) return;
    let url;
    try { url = new URL(value); } catch { /* Preserve non-web identifiers as text. */ }
    if (url && ['https:', 'http:'].includes(url.protocol) && !url.username && !url.password) {
      const link = element('a', label, parent);
      link.href = url.href; link.target = '_blank'; link.rel = 'noopener noreferrer';
    } else element('span', label, parent);
  }
  function semanticSummary(card, article) {
    const types = {CLASS:'della classe', PROPERTY:'della proprietà', RELATIONSHIP:'della relazione',
      VOCABULARY:'del vocabolario', CONCEPT:'del concetto', ONTOLOGY:'dell’ontologia'};
    const source = card.adoptionProvenance || {};
    const label = card.label_json?.it || card.local_name || card.semantic_id;
    const english = card.label_json?.en;
    const heading = card.status === 'OPEN' && !expired(card) ? 'Stai approvando l’adozione' : 'Richiesta di adozione';
    element('h2', `${heading} ${types[card.artifact_type] || 'della definizione'} «${label}»${english ? ' (' + english + ')' : ''}`, article);
    const facts = document.createElement('dl');
    const fact = (name, value) => { element('dt', name, facts); return element('dd', value, facts); };
    const iri = source.canonicalUri || card.semantic_id;
    const id = fact('Identificativo (IRI)', '');
    sourceLink(id, iri, iri);
    if (source.sourceLocation && source.sourceLocation !== iri) {
      id.append(' · '); sourceLink(id, 'Consulta la fonte', source.sourceLocation);
    }
    if (card.semantic_id && card.semantic_id !== iri) fact('Identificativo nel registro', card.semantic_id);
    const poi = /^https?:\/\/w3id\.org\/italia\/onto\/POI\/?$/.test(card.namespace);
    fact('Ontologia di provenienza (namespace)', poi ? `Punti di interesse (POI) · ${card.namespace}` : card.namespace);
    const publisher = source.publisher;
    const agid = /^https?:\/\/spcdata\.digitpa\.gov\.it\/Amministrazione\/agid\/?$/i.test(publisher);
    fact('Editore (publisher)', agid ? `Agenzia per l’Italia Digitale (AgID) · ${publisher}` : publisher);
    if (card.authority_ref && card.authority_ref !== publisher) fact('Autorità di riferimento', card.authority_ref);
    fact('Descrizione', card.description_json?.it || card.description_json?.en ||
      card.definition_json?.description?.it || 'Descrizione non disponibile nella fonte.');
    if (source.license) fact('Licenza', source.license);
    fact('Versione semantica', card.semantic_version); fact('Revisione', card.revision_no);
    const date = new Date(card.expires_at);
    fact('Scadenza della richiesta', Number.isFinite(date.valueOf()) ? date.toLocaleString('it-IT', {timeZoneName:'short'}) : card.expires_at);
    article.append(facts);
    const warnings = [];
    if (source.licenseWarning || !source.license) warnings.push(source.licenseWarning || 'Licenza non indicata nella fonte.');
    for (const finding of card.validationFindings || []) {
      warnings.push(typeof finding === 'string' ? finding : finding.message || finding.code || JSON.stringify(finding));
    }
    if (warnings.length) {
      const section = document.createElement('section'); section.className = 'warnings';
      element('h3', 'Avvisi da esaminare', section);
      const list = document.createElement('ul');
      for (const warning of warnings) element('li', warning, list);
      section.append(list); article.append(section);
    }
    element('h3', 'Cosa comporta l’approvazione', article);
    element('p', 'Approvi questa revisione. Con il successivo comando «Pubblica» la definizione diventa pubblicata e utilizzabile nei mapping delle fonti dell’ente. Non modifica dati esistenti e può essere deprecata in seguito.', article);
  }
  const unavailable = 'non disponibile';
  const accessNames = {OPEN:'pubblico', PERSONAL:'dato personale', SENSITIVE:'dato sensibile', RESTRICTED:'accesso riservato', ANONYMOUS:'dato anonimo'};
  const limitNames = {maxRows:'Righe massime', maxColumns:'Colonne massime', maxCellChars:'Caratteri massimi per cella'};
  const sectionNames = {FIELD:'Campo', IDENTITY:'Identità', SEMANTIC:'Riferimento semantico', LIMITS:'Limite'};
  function datatype(transform) {
    if (transform === 'IDENTITY') return 'testo';
    if (transform?.startsWith('DECIMAL_')) return 'numero';
    if (transform?.startsWith('DATE_')) return 'data';
    return transform || '—';
  }
  function shortIri(iri) { return iri?.split(/[/#:]/).filter(Boolean).pop() || iri; }
  function identityText(target, value, label) {
    if (target === 'sourceKey') return 'Chiave della fonte · ' + (value || []).map(field => field === '$managedRowOrdinal' ? 'posizione della riga nel file' : field).join(', ');
    if (target === 'maxCandidates') return 'Numero massimo di candidati da confrontare · ' + (value ?? unavailable);
    if (target === 'allowAutoNew') return 'Creazione automatica di nuovi oggetti · ' + (value == null ? unavailable : value ? 'Sì. In assenza di corrispondenze si può creare un nuovo oggetto.' : 'No. La creazione richiede una decisione ulteriore.');
    if (target.startsWith('sufficientRules:')) return 'Due righe descrivono lo stesso oggetto se coincidono tutti i segnali della regola «' + value.id + '» · ' + value.signalIds.map(label).join(' e ');
    if (target.startsWith('distinctRules:')) return 'Regola di distinzione «' + value.id + '» · ' + value.signalIds.map(label).join(' e ');
    if (target.startsWith('signal:')) return 'Segnale di confronto · ' + label(value.id) + ' · ' + ({TEXT_V1:'confronto testuale',DECIMAL_V1:'confronto numerico',CONCEPT:'confronto del concetto',JSON_V1:'confronto del valore strutturato'}[value.comparator] || value.comparator);
    if (target === 'exactKey') return 'Due righe descrivono lo stesso oggetto se hanno lo stesso valore per ' + label(value) + '.';
    if (target.startsWith('weighted:')) return 'Segnale di confronto · ' + label(value.property) + ' · peso ' + value.weight + ' · criterio ' + value.comparator +
      (value.tolerance == null ? '' : ' · tolleranza ' + value.tolerance);
    const criteria = {highThreshold:'Soglia di corrispondenza alta',reviewThreshold:'Soglia di revisione',minimumMargin:'Margine minimo fra candidati',
      blockingProperties:'Proprietà usate per selezionare i candidati',blockingDistanceMeters:'Distanza massima in metri',allowSpatialIdentity:'Confronto spaziale per l’identità'};
    if (Object.hasOwn(criteria,target)) return criteria[target] + ' · ' + (Array.isArray(value) ? value.map(label).join(', ') : typeof value === 'boolean' ? (value ? 'sì' : 'no') : value ?? unavailable);
    if (target === 'strategy') return 'Strategia di confronto · ' + ({GOVERNED_IDENTITY:'regole di identità governate',EXACT_SOURCE_KEY:'uguaglianza della chiave della fonte'}[value] || value);
    return 'Criteri di confronto · ' + JSON.stringify(value);
  }
  function fieldDescription(row) {
    return [row.included ? 'inclusa' : 'esclusa', row.property, row.transform, row.access, row.sourceKey && 'chiave della fonte', row.signal && 'segnale d’identità',row.authorityOrder?.length && 'ordine delle autorità ' + row.authorityOrder.join(', '), row.reconciliation && 'regole ' + JSON.stringify(row.reconciliation)].filter(Boolean).join(' · ');
  }
  function referenceDescription(ref) {
    return [ref.kind === 'CLASS' ? 'Classe' : 'Proprietà', ref.labelIt || 'Etichetta italiana non disponibile',
      'versione ' + (ref.semanticVersion || unavailable), ref.revisionId && 'revisione ' + ref.revisionId,
      ref.publicationSetId ? 'riferimento pubblicato nel set ' + ref.publicationSetId : 'pubblicazione non documentata',
      ref.descriptionIt || 'descrizione assente nello snapshot', ref.license || 'licenza non indicata nello snapshot'].filter(Boolean).join(' · ');
  }
  function reviewCard(card, article, flags) {
    const snap = card.reviewSnapshot, file = snap.file, warnings = [];
    const editable = canApprove({card});
    const label = iri => snap.SEMANTIC[iri]?.labelIt || shortIri(iri);
    function flag(section, target, parent) {
      if (!editable) return;
      const box = document.createElement('input'); box.type = 'checkbox';
      const choice = element('label','',parent); choice.append(box); element('span','Da modificare',choice);
      box.setAttribute('aria-label','Da modificare · ' + sectionNames[section] + ' ' + target);
      const comment = element('textarea','',parent); comment.maxLength = 500; comment.rows = 2;
      comment.placeholder = 'Commento facoltativo (500 caratteri)';
      comment.setAttribute('aria-label','Commento per ' + sectionNames[section] + ' ' + target);
      comment.hidden = true;
      box.onchange = () => { comment.hidden = !box.checked; controls(); };
      flags.push({section,target,box,comment});
    }
    const heading = editable ? 'Stai approvando l’attivazione della fonte' : 'Richiesta di attivazione della fonte';
    element('h2', heading + ' «' + snap.sourceName + '» (' + card.source_id + ') del proprietario ' + snap.owner,article);
    const facts = element('dl','',article);
    const fact = (name,value) => { element('dt',name,facts); return element('dd',value,facts); };
    fact('File',file.name || 'Nome originale non disponibile · ' + file.assetId);
    fact('Dimensione',file.size + ' byte'); fact('Profilo del file',(file.rows ?? unavailable) + ' righe · ' + file.columns + ' colonne · ' + file.format);
    for (const target of card.configuration.semanticMapping.targetClasses) {
      const ref = snap.SEMANTIC[target.classIri];
      const dd = fact('Classe di destinazione',(ref?.labelIt || 'Etichetta italiana non disponibile') + ' · ');
      sourceLink(dd,target.classIri,target.classIri);
      if (target.category) fact('Categoria',target.category.conceptIri + ' · vocabolario ' + target.category.schemeIri);
    }
    fact('Versione dell’onboarding',card.version);
    fact('Scadenza della richiesta',new Date(card.expires_at).toLocaleString('it-IT',{timeZoneName:'short'}));
    if (card.previousReview) {
      const previous = card.previousReview, diff = element('section','',article); diff.className = 'warnings';
      element('h3','Differenze rispetto alla versione ' + previous.version,diff);
      const describe = (section,target,value) => value == null ? 'assente' : section === 'FIELD' ? fieldDescription(value) :
        section === 'IDENTITY' ? identityText(target,value,label) : section === 'SEMANTIC' ? referenceDescription(value) : String(value);
      for (const change of previous.changes) {
        const {section,target,before,after} = change;
        element('p',sectionNames[section] + ' ' + (limitNames[target] || target) + ' · ' + describe(section,target,before) + ' → ' + describe(section,target,after),diff);
      }
      if (previous.changes.length) detail('Valori precedenti e proposti per ogni differenza',previous.changes,diff);
      if (!previous.changes.length) element('p','Nessuna differenza nelle voci esaminate.',diff);
      for (const note of previous.feedback) {
        const changed = previous.changes.some(change => change.section === note.section && change.target === note.target);
        element('p','Riscontro precedente · ' + sectionNames[note.section] + ' ' + (limitNames[note.target] || note.target) + ' · ' +
          (note.comment || 'segnalato senza commento') + (changed ? ' (voce modificata)' : ' (voce invariata)'),diff);
      }
    }
    element('h3','Campi del file',article);
    const wrap = element('div','',article); wrap.className = 'field-scroll'; wrap.tabIndex = 0;
    wrap.setAttribute('role','region'); wrap.setAttribute('aria-label','Campi del file, tabella scorrevole');
    const table = element('table','',wrap); element('caption','Decisione di acquisizione per ogni colonna del file',table);
    const head = element('tr','',element('thead','',table));
    for (const text of ['Colonna del file','Decisione','Proprietà semantica','Tipo di dato','Accesso','Ruolo',...(editable?['Riscontro']:[])]) element('th',text,head).scope = 'col';
    const rows = element('tbody','',table);
    for (const name of snap.columnOrder) {
      const row = snap.FIELD[name], tr = element('tr','',rows);
      if (!row.included) tr.className = 'excluded';
      element('th',name,tr).scope = 'row';
      element('td',row.included ? 'Inclusa' : 'Esclusa · non verrà acquisita',tr);
      const property = element('td',row.property ? (snap.SEMANTIC[row.property]?.labelIt || 'Etichetta italiana non disponibile') + ' · ' : '—',tr);
      if (row.property) sourceLink(property,shortIri(row.property),row.property);
      if (row.reconciliation) detail('Regole di riconciliazione della proprietà',row.reconciliation,property);
      if (row.authorityOrder?.length) element('p','Ordine delle autorità · ' + row.authorityOrder.join(', '),property);
      element('td',datatype(row.transform),tr);
      element('td',row.access ? row.access + ' · ' + accessNames[row.access] : 'Non indicato',tr);
      element('td',[row.sourceKey && 'chiave della fonte',row.signal && 'segnale d’identità'].filter(Boolean).join(' · ') || '—',tr);
      if (editable) flag('FIELD',name,element('td','',tr));
      if (!row.access) warnings.push(name + ' · classificazione di accesso non disponibile nello snapshot.');
      if (['PERSONAL','SENSITIVE','RESTRICTED'].includes(row.access)) warnings.push(name + ' · ' + accessNames[row.access] + (row.included ? ' · inclusa' : ' · esclusa'));
      if (row.included && row.transform === 'IDENTITY' && ['DECIMAL','LONG'].includes(row.profileType)) warnings.push(name + ' · il profilo rileva un numero che verrà acquisito come testo.');
    }
    for (const [section,title] of [['IDENTITY','Identità degli oggetti'],['SEMANTIC','Riferimenti semantici'],['LIMITS','Limiti dell’acquisizione']]) {
      element('h3',title,article);
      if (section === 'SEMANTIC' && snap.bindings?.length) {
        detail('Versioni e insiemi di pubblicazione vincolati alla configurazione',snap.bindings,article);
      }
      const list = element('ul','',article); list.className = 'review-items';
      for (const [target,value] of Object.entries(snap[section])) {
        const item = element('li','',list);
        if (section === 'IDENTITY') element('span',identityText(target,value,label),item);
        if (section === 'LIMITS') element('span',limitNames[target] + ' · ' + (value ?? unavailable),item);
        if (section === 'SEMANTIC') {
          sourceLink(item,target,target); element('p',referenceDescription(value),item);
          if (!value.license) warnings.push(label(target) + ' · licenza non indicata nello snapshot.');
          if (!value.descriptionIt) warnings.push(label(target) + ' · descrizione assente nello snapshot.');
        }
        flag(section,target,item);
      }
    }
    if (warnings.length) {
      const section = element('section','',article); section.className = 'warnings'; element('h3','Avvisi da esaminare',section);
      const list = element('ul','',section); for (const warning of warnings) element('li',warning,list);
    }
    element('h3','Cosa comporta l’approvazione',article);
    element('p','Approvi questa configurazione. Con il successivo comando «Verifica e attiva», superate le verifiche, la fonte diventa attiva. Ingestion acquisisce il file e UDP materializza gli oggetti con le sole proprietà incluse e le regole d’identità mostrate. Le colonne escluse non vengono acquisite. Puoi rivedere la configurazione o disattivare la fonte per fermare le acquisizioni future.',article);
  }
  const selected = () => [...cards.values()].filter(item => !semantic || item.checkbox.checked);
  const expired = card => Date.parse(card.expires_at) <= Date.now();
  const canApprove = item => semantic ? item.card.status === 'OPEN' && !expired(item.card) : item.card.status === 'CREATED' && item.card.onboardingState === 'IN_REVIEW' && !expired(item.card);
  const missingFileSnapshot = card => !card.reviewSnapshot && (card.configuration?.extractionProfile?.selection?.assetId || card.configuration?.semanticMapping?.sourceType?.typeCode === 'FILE');
  const canComplete = item => semantic ? item.card.status === 'APPROVED' && item.card.lifecycleStatus !== 'ACTIVE' && !!item.card.approvalDecisionId : item.card.onboardingState === 'APPROVED' && !item.card.progress?.activationProgress?.budgetExhausted && item.card.progress?.activationProgress?.counterLineageVerified !== false && !['BLOCKED','EXHAUSTED'].includes(item.card.progress?.activationProgress?.readiness_phase) && !(Date.parse(item.card.progress?.activationProgress?.readiness_lease_until) > Date.now()) && !(Date.parse(item.card.progress?.activationProgress?.readiness_next_at) > Date.now());
  const canBudget = item => !semantic && item.card.onboardingState === 'APPROVED' && item.card.status === 'CONFIRMED' && item.card.progress?.activationProgress?.humanBudgetGrantAvailable === true && (item.reason?.value.trim().length || 0) >= 10;
  const canResume = item => !semantic && item.card.onboardingState === 'APPROVED' && item.card.status === 'CONFIRMED'
    && item.card.progress?.activationProgress?.readiness_phase === 'BLOCKED'
    && ['ONB_READINESS_DEPENDENCY_403','ONB_UDP_IDENTITY_PREFLIGHT_REQUIRED'].includes(item.card.progress.activationProgress.readiness_last_code)
    && (typeof item.card.progress.activationProgress.budgetExhausted === 'boolean'
      ? !item.card.progress.activationProgress.budgetExhausted && item.card.progress.activationProgress.counterLineageVerified !== false
      : item.card.progress.activationProgress.readiness_attempts < 128 && Date.parse(item.card.progress.activationProgress.readiness_started_at) + 86400000 > Date.now() + 50000)
    && !(Date.parse(item.card.progress.activationProgress.readiness_lease_until) > Date.now())
    && !(Date.parse(item.card.progress.activationProgress.readiness_next_at) > Date.now());
  function controls() {
    const items = selected(); $('actions').disabled = busy || items.length === 0;
    const flagged = items.some(item => item.flags.some(flag => flag.box.checked));
    $('approve').disabled = flagged || !items.length || !items.every(canApprove) || items.some(item => missingFileSnapshot(item.card));
    $('changes').disabled = !flagged || !items.every(canApprove);
    $('reject').disabled = flagged || !items.length || !items.every(canApprove);
    $('complete').disabled = !items.length || !items.every(canComplete);
    $('resume').disabled = !items.length || !items.every(canResume);
    $('budget').disabled = !items.length || !items.every(canBudget);
  }
  function render(id, response) {
    const card = response.card;
    let reason;
    const flags = [];
    if (card.challenge_id !== id) throw new Error('La risposta non appartiene alla revisione richiesta.');
    const article = document.createElement('article'); article.className = 'card';
    if (semantic) semanticSummary(card, article);
    else if (card.reviewSnapshot) reviewCard(card,article,flags);
    else {
      element('h2', card.source_name || card.source_id, article);
      element('p', `Fonte dati · ${card.source_id}`, article);
      if (missingFileSnapshot(card)) element('p','La tabella completa del proprietario non è disponibile. Aggiorna la scheda prima di approvare.',article).className = 'warnings';
    }
    const states = {IN_REVIEW:'in revisione', APPROVED:'approvata', ACTIVE:'attiva', REJECTED:'rifiutata', CHANGES_REQUESTED:'modifiche richieste', SUPERSEDED:'sostituita', REVOKED:'revocata'};
    const state = semantic ? card.lifecycleStatus || card.status : card.reviewSnapshot ? states[card.onboardingState] || card.onboardingState : card.onboardingState;
    element('p','Stato: ' + state,article).className = 'state';
    if (card.progress?.reviewFeedback?.length) {
      element('h3','Modifiche richieste all’agente',article);
      for (const note of card.progress.reviewFeedback) element('p',sectionNames[note.section] + ' ' + note.target + ' · ' + (note.comment || 'segnalato senza commento'),article);
    }
    if (expired(card) && ["CREATED", "OPEN"].includes(card.status)) element('p', 'La richiesta è scaduta. Torna alla chat per la riconciliazione.', article);
    if (semantic) {
      const definition = card.definition_json || {};
      detail('Definizione, ambito e vincoli', definition, article);
      if (card.interchangeSnapshot) detail('Contenuto semantico esatto', card.interchangeSnapshot, article);
    } else {
      const config = card.configuration || {}, mapping = config.semanticMapping || {};
      if (!card.reviewSnapshot) {
      element('h3', 'Classi di destinazione', article);
      for (const target of mapping.targetClasses || []) {
        element('h3', target.classIri, article);
        if (target.category) {
          element('p', `Vocabolario: ${target.category.schemeIri}`, article);
          element('p', `Categoria: ${target.category.conceptIri}`, article);
        } else element('p', 'Categoria assente', article);
      }
      const rows = mapping.propertyMappings || [];
      const labels = new Map((config.dataAccessPolicies || []).filter(p => p.scope === 'PROPERTY').map(p => [p.target, p.label]));
      element('h3', 'Campi inclusi e corrispondenze', article);
      const table = document.createElement('table'), head = document.createElement('tr');
      for (const name of ['Campo della fonte', 'Proprietà', 'Trasformazione', 'Etichetta di accesso']) element('th', name, head);
      table.append(head);
      const appendField = (field, property, transform) => {
        const tr = document.createElement('tr');
        for (const value of [field, property, transform, labels.get(property) || labels.get(field)]) element('td', value, tr);
        table.append(tr);
      };
      for (const row of rows) appendField(row.sourceField, row.targetPropertyIri, row.transform);
      const mapped = new Set(rows.map(row => row.sourceField));
      const projection = config.extractionProfile?.projection?.[mapping.sourceType?.typeCode || 'FILE'] || [];
      for (const field of projection) if (!mapped.has(field)) appendField(field, null, null);
      article.append(table);
      element('h3', 'Campi esclusi', article);
      const excluded = (card.newColumns || []).filter(item => item.decision === 'EXCLUDE');
      if (!excluded.length) element('p', 'La scheda della fonte non elenca campi esclusi.', article);
      for (const item of excluded) element('p', `${item.column} · Nessuna proprietà pubblicata · Accesso: ${item.accessLabel || 'non indicato'}`, article);
      }
      if (card.newColumns?.length) {
        element('h3', 'Decisioni sulle colonne nuove', article);
        const decisions = document.createElement('table'), header = document.createElement('tr');
        for (const label of ['Colonna nuova', 'Decisione', 'Effetto sulla proiezione']) element('th', label, header);
        decisions.append(header);
        for (const item of card.newColumns) {
          const row = document.createElement('tr');
          element('td', item.column, row);
          element('td', item.decision, row);
          const effect = element('td', item.decision === 'EXCLUDE' ? 'Conservata solo nel file' : item.mapping.propertyIri, row);
          if (item.decision === 'MAP') detail('Trasformazione e regole', item.mapping, effect);
          decisions.append(row);
        }
        article.append(decisions);
      }
      detail('Identità delle righe e classificazione di accesso', { sourceObjectIdentityPolicy: config.sourceObjectIdentityPolicy, dataAccessPolicies: config.dataAccessPolicies }, article);
      detail('Confronto con gli oggetti esistenti e materializzazione', config.extractionProfile?.runtime?.udp, article);
      if (card.publicationEffects) {
        element('h3', 'Effetto della nuova pubblicazione', article);
        element('p', card.publicationEffects.description, article);
        detail('Versione di partenza della revisione', card.revisionBase, article);
        if (card.assetRef) detail('Nuovo file e profilo da approvare', card.assetRef, article);
      }
      detail('Configurazione completa da approvare', config, article);
      if(card.progress?.activationProgress) detail('Avanzamento delle verifiche e tentativi disponibili', card.progress.activationProgress, article);
      if(card.progress?.activationProgress?.budgetExhausted && card.onboardingState === 'APPROVED') {
        element('p', 'Il tempo o i tentativi disponibili per le verifiche sono terminati. La configurazione resta approvata.', article);
        if(card.progress.activationProgress.humanBudgetGrantAvailable === true) {
          element('p', `Puoi concedere fino a 3 nuovi tentativi entro 15 minuti. I ${card.progress.activationProgress.cumulativeAttempts} tentativi già registrati e lo storico restano conservati. Poi potrai scegliere separatamente “Verifica e attiva”, che può pubblicare la configurazione e avviare un’acquisizione.`, article);
          const label = document.createElement('label'); element('span', 'Motivo della nuova concessione (10–500 caratteri, senza credenziali)', label);
          reason = document.createElement('textarea'); reason.maxLength = 500; reason.rows = 3;
          if (card.progress.activationProgress.reconciledBudgetReason)
            reason.value = 'Ripetere le verifiche della dipendenza entro i limiti indicati.';
          if (card.progress.activationProgress.reconciledBudgetReason === 'ONB_READINESS_INTERRUPTED')
            reason.value = 'Ripetere le verifiche dopo il tentativo interrotto senza pubblicazione.';
          if (card.progress.activationProgress.reconciledBudgetReason === 'ONB_UDP_IDENTITY_PREFLIGHT_REQUIRED')
            reason.value = 'Richiedere una nuova prova UDP per la configurazione approvata.';
          if (card.progress.activationProgress.reconciledBudgetReason === 'ONB_READINESS_DEPENDENCY_403')
            reason.value = 'Verificare di nuovo le autorizzazioni correnti della dipendenza.';
          reason.oninput = controls; label.append(reason); article.append(label);
        } else element('p', 'Occorre riconciliare lo stato prima di concedere altri tentativi.', article);
      }
      if (card.onboardingState === 'APPROVED' && !card.progress?.ingestionCompatibilityObserved) element('p', 'Approvazione registrata. “Verifica e attiva” controlla la configurazione con Ingestion e confronta l’identità con gli oggetti esistenti prima di attivare.', article);
    }
    detail('Riferimenti della revisione', { challenge: id, version: card.onboarding_version_id || card.revision_id, hash: card.configuration_hash || card.target_content_hash, expiresAt: card.expires_at }, article);
    const label = document.createElement('label'), checkbox = document.createElement('input'); checkbox.type = 'checkbox';
    if (semantic) {
      label.append(checkbox); element('span', 'Ho esaminato questa proposta e la seleziono per la decisione.', label); article.append(label);
    }
    const previous = cards.get(id); if (previous) { previous.article.replaceWith(article); } else $('cards').append(article);
    const intent = document.createElement('output'); intent.hidden = true; article.append(intent);
    intent.oninput = () => { intent.hidden = !intent.value; };
    cards.set(id, { card, csrfHeader: response.csrfHeader, csrfToken: response.csrfToken, checkbox, article, reason, intent, flags });
    checkbox.onchange = controls;
    const fields = [intent, ...flags.flatMap(flag => [flag.box,flag.comment])];
    if (semantic) fields.push(checkbox);
    if (reason) fields.push(reason);
    ThsSession.bind(JSON.stringify([id, card.target_content_hash || card.configuration_hash, card.status, card.lifecycleStatus,
      card.lockVersion, card.progress?.activationProgress?.readiness_generation]), fields);
  }
  async function load(id) {
    const response = await ThsSession.fetch(`api/${encodeURIComponent(id)}`, { credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'application/json' } });
    if (!response.ok) throw new Error(`Revisione non disponibile (HTTP ${response.status}).`);
    render(id, await response.json());
  }
  async function refresh() {
    if (busy) return; busy = true; controls();
    try { for (const id of ids) await load(id); status('Esamina le proposte. Approvazione e pubblicazione o attivazione sono decisioni distinte.'); }
    catch (error) { status(error.message, true); }
    finally { busy = false; controls(); }
  }
  async function decide(action) {
    const items = selected(); if (busy || !items.length) return;
    if (action === 'resume' ? !items.every(canResume) : action === 'budget' ? !items.every(canBudget) : action === 'complete' ? !items.every(canComplete) : !items.every(canApprove)) return;
    const feedback = items.flatMap(item => item.flags.filter(flag => flag.box.checked)
      .map(flag => ({section:flag.section,target:flag.target,comment:flag.comment.value.trim()})));
    if (['approve','reject'].includes(action) && feedback.length) return;
    if (action === 'approve' && items.some(item => missingFileSnapshot(item.card))) return;
    if (action === 'changes' && (!feedback.length || feedback.length > 128 || new Blob([JSON.stringify(feedback)]).size > 48*1024)) {
      status('Seleziona da 1 a 128 voci e mantieni il riscontro entro 48 KiB.',true); return;
    }
    busy = true; controls(); let completed = 0;
    for (const item of items) item.intent.value = `Scelta da verificare dopo il nuovo accesso: «${$(action).textContent}».`;
    try {
      for (const item of items) {
        const card = item.card, id = card.challenge_id;
        const operation = semantic ? (action === 'complete' ? 'publish' : 'decision') : (action === 'resume' ? 'resume' : action === 'budget' ? 'budget' : action === 'complete' ? 'activate' : action === 'changes' ? 'changes' : action === 'approve' ? 'confirm' : 'reject');
        let body = semantic ? (operation === 'publish' ? { approvalDecisionId: card.approvalDecisionId, contentHash: card.target_content_hash } : { decision: action === 'approve' ? 'APPROVED' : 'REJECTED', expectedContentHash: card.target_content_hash }) : { configurationHash: card.configuration_hash, expectedVersion: card.lockVersion };
        if (action === 'changes') body.reviewFeedback = feedback;
        if (action === 'budget') {
          const progress = card.progress.activationProgress;
          const slot = `ouf-readiness-budget:${id}:${card.configuration_hash}:${progress.readiness_generation}`;
          let key = sessionStorage.getItem(slot);
          if (!key) { key = crypto.randomUUID(); sessionStorage.setItem(slot, key); }
          body = { configurationHash: card.configuration_hash, expectedVersion: card.lockVersion,
            expectedGeneration: progress.readiness_generation, commandKey: key, reason: item.reason.value.trim(),
            maximumAttempts: 3, maximumElapsedSeconds: 900 };
        }
        if (operation === 'resume') Object.assign(body, { expectedGeneration: card.progress.activationProgress.readiness_generation, blockedCode: card.progress.activationProgress.readiness_last_code });
        const response = await ThsSession.fetch(`api/${encodeURIComponent(id)}/${operation}`, { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json', [item.csrfHeader]: item.csrfToken }, body: JSON.stringify(body) });
        if (!response.ok) {
          const failure = await response.json().catch(() => null);
          const code = failure?.code || failure?.detail;
          const reason = typeof code === 'string' && /^[A-Z][A-Z0-9_]{1,159}$/.test(code) ? ' · ' + code : '';
          throw new Error(`Operazione non completata (HTTP ${response.status}${reason}).`);
        }
        await load(id); completed++;
      }
      status(action === 'resume' ? 'Ripresa registrata. Per proseguire scegli “Verifica e attiva”.' : `${completed} decisioni registrate. Puoi tornare alla chat per proseguire.`);
    } catch (error) {
      status(`${error.message} Decisioni già confermate in questa operazione: ${completed}. Aggiorna lo stato; nessun invio viene ripetuto automaticamente.`, true);
    } finally { busy = false; controls(); }
  }
  $('title').textContent = semantic ? 'Rivedi e pubblica la semantica' : 'Rivedi e attiva la fonte';
  $('approve').textContent = semantic ? 'Approva le proposte selezionate' : 'Approva la configurazione';
  $('complete').textContent = semantic ? 'Pubblica le proposte approvate' : 'Verifica e attiva la fonte approvata';
  $('resume').hidden = semantic; $('resume').onclick = () => decide('resume');
  $('changes').hidden = semantic; $('changes').onclick = () => decide('changes');
  $('budget').hidden = semantic;
  $('budget').onclick = () => decide('budget');
  $('approve').onclick = () => decide('approve'); $('complete').onclick = () => decide('complete'); $('reject').onclick = () => decide('reject'); $('refresh').onclick = refresh;
  if (!semantic && !params.has('challenge') && !params.has('challenges')) {
    $('title').textContent = 'Accesso effettuato';
    status('Accesso effettuato: apri il link della richiesta da approvare');
    for (const id of ['intro','refresh','actions']) $(id).hidden = true;
    document.querySelector('.note').hidden = true;
  } else if (!['onboarding', 'semantic'].includes(flow) || ids.length > (semantic ? 21 : 1) || new Set(ids).size !== ids.length || !ids.every(id => uuid.test(id))) status('Apri il collegamento di revisione fornito dalla chat.', true);
  else refresh();
})();

