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
  const selected = () => [...cards.values()].filter(item => !semantic || item.checkbox.checked);
  const expired = card => Date.parse(card.expires_at) <= Date.now();
  const canApprove = item => semantic ? item.card.status === 'OPEN' && !expired(item.card) : item.card.status === 'CREATED' && item.card.onboardingState === 'IN_REVIEW' && !expired(item.card);
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
    $('approve').disabled = !items.length || !items.every(canApprove);
    $('reject').disabled = !items.length || !items.every(canApprove);
    $('complete').disabled = !items.length || !items.every(canComplete);
    $('resume').disabled = !items.length || !items.every(canResume);
    $('budget').disabled = !items.length || !items.every(canBudget);
  }
  function render(id, response) {
    const card = response.card;
    let reason;
    if (card.challenge_id !== id) throw new Error('La risposta non appartiene alla revisione richiesta.');
    const article = document.createElement('article'); article.className = 'card';
    if (semantic) semanticSummary(card, article);
    else {
      element('h2', card.source_name || card.source_id, article);
      element('p', `Fonte dati · ${card.source_id}`, article);
    }
    element('p', semantic ? `Stato: ${card.lifecycleStatus || card.status}` : `Stato: ${card.onboardingState}`, article).className = 'state';
    if (expired(card) && ["CREATED", "OPEN"].includes(card.status)) element('p', 'La richiesta è scaduta. Torna alla chat per la riconciliazione.', article);
    if (semantic) {
      const definition = card.definition_json || {};
      detail('Definizione, ambito e vincoli', definition, article);
      if (card.interchangeSnapshot) detail('Contenuto semantico esatto', card.interchangeSnapshot, article);
    } else {
      const config = card.configuration || {}, mapping = config.semanticMapping || {};
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
    cards.set(id, { card, csrfHeader: response.csrfHeader, csrfToken: response.csrfToken, checkbox, article, reason, intent });
    checkbox.onchange = controls;
    const fields = [intent];
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
    busy = true; controls(); let completed = 0;
    for (const item of items) item.intent.value = `Scelta da verificare dopo il nuovo accesso: «${$(action).textContent}».`;
    try {
      for (const item of items) {
        const card = item.card, id = card.challenge_id;
        const operation = semantic ? (action === 'complete' ? 'publish' : 'decision') : (action === 'resume' ? 'resume' : action === 'budget' ? 'budget' : action === 'complete' ? 'activate' : action === 'approve' ? 'confirm' : 'reject');
        let body = semantic ? (operation === 'publish' ? { approvalDecisionId: card.approvalDecisionId, contentHash: card.target_content_hash } : { decision: action === 'approve' ? 'APPROVED' : 'REJECTED', expectedContentHash: card.target_content_hash }) : { configurationHash: card.configuration_hash, expectedVersion: card.lockVersion };
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
  $('budget').hidden = semantic;
  $('budget').onclick = () => decide('budget');
  $('approve').onclick = () => decide('approve'); $('complete').onclick = () => decide('complete'); $('reject').onclick = () => decide('reject'); $('refresh').onclick = refresh;
  if (!['onboarding', 'semantic'].includes(flow) || ids.length > (semantic ? 21 : 1) || new Set(ids).size !== ids.length || !ids.every(id => uuid.test(id))) status('Apri il collegamento di revisione fornito dalla chat.', true);
  else refresh();
})();

