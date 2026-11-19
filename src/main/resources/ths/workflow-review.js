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
  const selected = () => [...cards.values()].filter(item => item.checkbox.checked);
  const expired = card => Date.parse(card.expires_at) <= Date.now();
  const canApprove = item => semantic ? item.card.status === 'OPEN' && !expired(item.card) : item.card.status === 'CREATED' && item.card.onboardingState === 'IN_REVIEW' && !expired(item.card);
  const canComplete = item => semantic ? item.card.status === 'APPROVED' && item.card.lifecycleStatus !== 'ACTIVE' && !!item.card.approvalDecisionId : item.card.onboardingState === 'APPROVED' && !item.card.progress?.activationProgress?.budgetExhausted && item.card.progress?.activationProgress?.counterLineageVerified !== false && !['BLOCKED','EXHAUSTED'].includes(item.card.progress?.activationProgress?.readiness_phase) && !(Date.parse(item.card.progress?.activationProgress?.readiness_lease_until) > Date.now()) && !(Date.parse(item.card.progress?.activationProgress?.readiness_next_at) > Date.now());
  const canBudget = item => !semantic && item.card.onboardingState === 'APPROVED' && item.card.status === 'CONFIRMED' && item.card.progress?.activationProgress?.humanBudgetGrantAvailable === true && (item.reason?.value.trim().length || 0) >= 10;
  const canResume = item => !semantic && item.card.onboardingState === 'APPROVED' && item.card.status === 'CONFIRMED'
    && item.card.progress?.activationProgress?.readiness_phase === 'BLOCKED'
    && item.card.progress.activationProgress.readiness_last_code === 'ONB_READINESS_DEPENDENCY_403'
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
    element('h2', semantic ? (card.label_json?.it || card.local_name || card.semantic_id) : (card.source_name || card.source_id), article);
    element('p', semantic ? `Semantica · ${card.artifact_type}` : `Fonte dati · ${card.source_id}`, article);
    element('p', semantic ? `Stato: ${card.lifecycleStatus || card.status}` : `Stato: ${card.onboardingState}`, article).className = 'state';
    if (expired(card) && ["CREATED", "OPEN"].includes(card.status)) element('p', 'La richiesta è scaduta. Torna alla chat per la riconciliazione.', article);
    if (semantic) {
      const definition = card.definition_json || {};
      const description = card.description_json?.it || definition.description?.it;
      if (description) element('p', description, article);
      detail('Definizione, ambito e vincoli', definition, article);
      if (card.validationFindings?.length) detail('Avvertenze del validatore', card.validationFindings, article);
      if (card.interchangeSnapshot) detail('Contenuto semantico esatto', card.interchangeSnapshot, article);
    } else {
      const config = card.configuration || {}, mapping = config.semanticMapping || {};
      const rows = mapping.propertyMappings || [];
      element('h3', 'Campi inclusi e corrispondenze', article);
      const table = document.createElement('table'), head = document.createElement('tr');
      for (const name of ['Campo della fonte', 'Proprietà', 'Trasformazione']) element('th', name, head);
      table.append(head);
      for (const row of rows) { const tr = document.createElement('tr'); for (const value of [row.sourceField, row.targetPropertyIri, row.transform]) element('td', value, tr); table.append(tr); }
      article.append(table);
      detail('Identità delle righe e classificazione di accesso', { sourceObjectIdentityPolicy: config.sourceObjectIdentityPolicy, dataAccessPolicies: config.dataAccessPolicies }, article);
      detail('Confronto con gli oggetti esistenti e materializzazione', config.extractionProfile?.runtime?.udp, article);
      if (card.publicationEffects) {
        element('h3', 'Effetto della nuova pubblicazione', article);
        element('p', card.publicationEffects.description, article);
        detail('Versione di partenza della revisione', card.revisionBase, article);
      }
      detail('Configurazione completa da approvare', config, article);
      if(card.progress?.activationProgress) detail('Avanzamento delle verifiche e tentativi disponibili', card.progress.activationProgress, article);
      if(card.progress?.activationProgress?.budgetExhausted && card.onboardingState === 'APPROVED') {
        element('p', 'Il tempo o i tentativi disponibili per le verifiche sono terminati. La configurazione resta approvata.', article);
        if(card.progress.activationProgress.humanBudgetGrantAvailable === true) {
          element('p', `Puoi concedere fino a 3 nuovi tentativi entro 15 minuti. I ${card.progress.activationProgress.cumulativeAttempts} tentativi già registrati e lo storico restano conservati. Poi potrai scegliere separatamente “Verifica e attiva”, che può pubblicare la configurazione e avviare un’acquisizione.`, article);
          const label = document.createElement('label'); element('span', 'Motivo della nuova concessione (10–500 caratteri, senza credenziali)', label);
          reason = document.createElement('textarea'); reason.maxLength = 500; reason.rows = 3; reason.oninput = controls; label.append(reason); article.append(label);
        } else element('p', 'Occorre riconciliare lo stato prima di concedere altri tentativi.', article);
      }
      if (card.onboardingState === 'APPROVED' && !card.progress?.ingestionCompatibilityObserved) element('p', 'Approvazione registrata. “Verifica e attiva” controlla la configurazione con Ingestion e confronta l’identità con gli oggetti esistenti prima di attivare.', article);
    }
    detail('Riferimenti della revisione', { challenge: id, version: card.onboarding_version_id || card.revision_id, hash: card.configuration_hash || card.target_content_hash, expiresAt: card.expires_at }, article);
    const label = document.createElement('label'), checkbox = document.createElement('input'); checkbox.type = 'checkbox';
    label.append(checkbox); element('span', 'Ho esaminato questa proposta e la seleziono per la decisione.', label); article.append(label);
    const previous = cards.get(id); if (previous) { previous.article.replaceWith(article); } else $('cards').append(article);
    cards.set(id, { card, csrfHeader: response.csrfHeader, csrfToken: response.csrfToken, checkbox, article, reason }); checkbox.onchange = controls;
  }
  async function load(id) {
    const response = await fetch(`api/${encodeURIComponent(id)}`, { credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'application/json' } });
    if (!response.ok) throw new Error(`Revisione non disponibile (${response.status}). La sessione o i permessi potrebbero richiedere un nuovo accesso.`);
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
        const response = await fetch(`api/${encodeURIComponent(id)}/${operation}`, { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json', [item.csrfHeader]: item.csrfToken }, body: JSON.stringify(body) });
        if (!response.ok) { let reason = ''; try { const failure = await response.json(); reason = failure.code || failure.detail || ''; } catch {} throw new Error(`Operazione non completata (${response.status})${reason ? ': ' + reason : ''}.`); }
        await load(id); completed++;
      }
      status(action === 'resume' ? 'Ripresa registrata. Approvazione e tentativi precedenti conservati. Seleziona di nuovo la fonte e scegli “Verifica e attiva”.' : `${completed} decisioni registrate. Puoi tornare alla chat per proseguire.`);
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

