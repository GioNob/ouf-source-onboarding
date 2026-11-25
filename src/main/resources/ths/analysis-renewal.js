'use strict';
(() => {
  const byId = id => document.getElementById(id);
  const fields = {Calls: 'Chiamate', Attempts: 'Tentativi', Objects: 'Oggetti', Bytes: 'Dati', Millis: 'Tempo di esecuzione'};
  const explanations = {
    Calls: 'Richieste di ricerca o lettura previste dal lavoro.',
    Attempts: 'Invii al servizio, compresi quelli ripetuti dopo un errore.',
    Objects: 'Elementi che il servizio può esaminare durante l’analisi.',
    Bytes: 'Quantità di dati che il servizio può elaborare.',
    Millis: 'Tempo dedicato all’esecuzione dei tentativi di analisi.'
  };
  const reasons = {ANALYSIS_BUDGET_EXHAUSTED: 'L’analisi ha raggiunto uno dei limiti disponibili.'};
  const states = {
    OPEN: 'Proposta in attesa della tua decisione.',
    APPROVED: 'Rinnovo approvato. Segui l’analisi delle colonne nella conversazione.',
    CLOSED_PARTIAL: 'Chiusura senza rinnovo registrata. Consulta i risultati nella conversazione.',
    CHANGES_REQUESTED: 'Modifiche richieste. Apri la nuova proposta dalla conversazione quando sarà pronta.',
    SUPERSEDED: 'Questa versione è stata sostituita. Apri la proposta aggiornata dalla conversazione.',
    EXPIRED: 'La proposta è scaduta. Torna alla conversazione: l’agente può preparare una nuova proposta.'
  };
  const columns = {initialMaximum: 'tetto iniziale', consumed: 'consumato', increment: 'incremento', totalMaximum: 'tetto totale'};
  const messages = {
    ANALYSIS_INPUT_INVALID: 'I dati della richiesta non sono validi. Rileggi la proposta e controlla i commenti.',
    ANALYSIS_AUTHORITY_DENIED: 'Il tuo account non è autorizzato a decidere su questo piano.',
    ANALYSIS_MANDATE_UNKNOWN: 'Questa proposta non è disponibile per il tuo account.',
    ANALYSIS_STALE_EXECUTION: 'Il collegamento riguarda una versione superata. Apri la proposta aggiornata dalla conversazione.',
    ANALYSIS_COMMAND_CONFLICT: 'Una conferma precedente riguarda una scelta diversa. Rileggi lo stato del piano.',
    ANALYSIS_RENEWAL_USED: 'Il rinnovo per questa interruzione è già stato utilizzato. Consulta il riepilogo del piano.',
    ANALYSIS_MANDATE_EXPIRED: 'L’autorizzazione al lavoro è scaduta. Torna alla conversazione per verificare come proseguire.',
    ANALYSIS_BACKOFF: 'Il servizio è occupato. Attendi prima di rileggere la proposta.',
    ANALYSIS_AUTHORITY_UNAVAILABLE: 'Il servizio di verifica delle autorizzazioni è temporaneamente indisponibile.',
    THS_SESSION_EXPIRED: 'La sessione è scaduta. Accedi di nuovo per rileggere la proposta.',
    THS_ACCESS_DENIED: 'Accesso negato. Verifica il tuo accesso alla scheda.',
    THS_CSRF_REQUIRED: 'La protezione della conferma non è disponibile. Riapri la scheda.',
    THS_UNAVAILABLE: 'Accesso temporaneamente indisponibile.'
  };
  const number = new Intl.NumberFormat('it-IT', {maximumFractionDigits: 6});
  const date = new Intl.DateTimeFormat('it-IT', {dateStyle: 'long', timeStyle: 'long'});
  const path = location.pathname;
  const reviewRef = decodeURIComponent(path.split('/').pop());
  const text = value => typeof value === 'string' && value.trim().length > 0;
  // The contract counts Unicode characters, as the Gateway does: a symbol outside the BMP is one character.
  const characters = value => [...value].length;
  const count = value => Number.isSafeInteger(value) && value >= 0;
  const digest = value => typeof value === 'string' && /^[0-9a-f]{64}$/.test(value);
  const sequence = value => typeof value === 'string' && /^(0|[1-9][0-9]{0,18})$/.test(value)
    && BigInt(value) <= 9223372036854775807n;
  const comments = new Map();
  let card, csrf, actor, historyKey, expiryTimer, locked = true, retryAt = 0;

  function amount(field, value) {
    if (field === 'Millis') {
      const parts = [];
      const hours = Math.floor(value / 3600000);
      const minutes = Math.floor(value / 60000) % 60;
      const seconds = (value % 60000) / 1000;
      if (hours) parts.push(number.format(hours) + ' h');
      if (minutes) parts.push(minutes + ' min');
      if (seconds || !parts.length) parts.push(number.format(seconds) + ' s');
      return parts.join(' ');
    }
    if (field === 'Bytes') {
      if (value >= 1000000) return number.format(value / 1000000) + ' MB';
      if (value >= 1000) return number.format(value / 1000) + ' kB';
      return number.format(value) + ' byte';
    }
    return number.format(value);
  }

  function revision(c) {
    return c && Number.isSafeInteger(c.proposalVersion) && c.proposalVersion >= 1
      && text(c.reason) && Object.hasOwn(reasons, c.reason) && text(c.expiresAt) && Number.isFinite(Date.parse(c.expiresAt))
      && Array.isArray(c.remainingOrdinals) && c.remainingOrdinals.length <= 1024
      && c.remainingOrdinals.every(ordinal => count(ordinal) && ordinal < 1024)
      && Object.keys(fields).every(f => Object.keys(columns).every(k => count(c[k]?.[f]))
        && c.increment[f] <= Math.floor(c.initialMaximum[f] / 4)
        && c.initialMaximum[f] + c.increment[f] === c.totalMaximum[f])
      && Object.keys(fields).some(f => c.increment[f] > 0);
  }

  function feedbackItems(items) {
    return Array.isArray(items) && items.length <= 16 && items.every(item => item && text(item.field)
      && (item.field === 'general' || Object.hasOwn(fields, item.field)) && text(item.comment) && characters(item.comment) <= 500);
  }

  function complete(c) {
    return revision(c) && digest(c.analysisId) && digest(c.proposalHash) && sequence(c.expectedSeq) && text(c.incidentId)
      && text(c.planLabel) && characters(c.planLabel) <= 200 && text(c.proposalState) && Object.hasOwn(states, c.proposalState)
      && (c.previous == null || (revision(c.previous) && c.previous.proposalVersion < c.proposalVersion))
      && (c.requestedChanges === undefined || feedbackItems(c.requestedChanges));
  }

  function notes() {
    return [...comments].filter(([, input]) => input.value.trim()).map(([field, input]) => ({field, comment: input.value.trim()}));
  }

  function controls(lock) {
    locked = lock;
    byId('actions').disabled = lock;
    for (const input of comments.values()) input.disabled = lock;
    const annotated = notes().length > 0;
    byId('changes').disabled = !annotated;
    byId('approve').disabled = byId('close').disabled = annotated;
  }

  function history() {
    const list = byId('differences');
    list.replaceChildren();
    const add = message => { const li = document.createElement('li'); li.textContent = message; list.append(li); return li; };
    let before = card.previous, feedback = card.requestedChanges;
    if (card.proposalVersion > 1 && (!before || feedback === undefined)) {
      let prior;
      try { prior = JSON.parse(sessionStorage.getItem(historyKey)); } catch { /* Storage may be disabled. */ }
      const cached = prior?.card;
      if (revision(cached) && cached.analysisId === card.analysisId && cached.incidentId === card.incidentId
          && cached.proposalVersion === (before?.proposalVersion ?? card.proposalVersion - 1)) {
        before ??= cached;
        if (feedback === undefined && feedbackItems(prior.items)) feedback = prior.items;
      }
    }
    for (const item of feedback ?? [])
      add((fields[item.field] ?? 'Generale') + ' · Modifica richiesta: ' + item.comment);
    if (before) {
      add('Confronto con la versione ' + before.proposalVersion + '.');
      const changes = [];
      for (const [field, label] of Object.entries(fields)) for (const [k, heading] of Object.entries(columns)) {
        if (before[k][field] !== card[k][field])
          changes.push(label + ', ' + heading + ': ' + amount(field, before[k][field]) + ' → ' + amount(field, card[k][field]));
      }
      if (before.reason !== card.reason) changes.push('Motivo: ' + reasons[before.reason] + ' → ' + reasons[card.reason]);
      if (Date.parse(before.expiresAt) !== Date.parse(card.expiresAt))
        changes.push('Scadenza: ' + date.format(new Date(before.expiresAt)) + ' → ' + date.format(new Date(card.expiresAt)));
      const columnsChanged = String(before.remainingOrdinals) !== String(card.remainingOrdinals);
      if (columnsChanged) {
        const li = add('Colonne ancora da analizzare: ' + before.remainingOrdinals.length + ' → ' + card.remainingOrdinals.length);
        const details = document.createElement('details'), summary = document.createElement('summary');
        summary.textContent = 'Posizioni nel file';
        details.append(summary, 'Prima: ' + positions(before) + '. Ora: ' + positions(card) + '.');
        li.append(details);
      }
      if (!changes.length && !columnsChanged) changes.push('Le voci mostrate sono invariate.');
      changes.forEach(add);
    } else if (card.proposalVersion > 1) {
      add('Il confronto con la versione precedente non è disponibile in questa scheda.');
    }
    byId('history').hidden = !list.children.length;
  }

  function positions(revision) {
    return revision.remainingOrdinals.map(ordinal => ordinal + 1).join(', ') || 'Nessuna';
  }

  function showState(state) {
    const open = state === 'OPEN';
    byId('actions').hidden = byId('feedback-heading').hidden = !open;
    for (const input of comments.values()) input.parentElement.hidden = !open;
    controls(!open);
    byId('status').textContent = states[state];
  }

  function updateExpiry() {
    const remaining = Date.parse(card.expiresAt) - Date.now();
    if (remaining <= 0) showState('EXPIRED');
    else expiryTimer = setTimeout(updateExpiry, Math.min(remaining, 2147483647));
  }

  function render() {
    byId('plan').textContent = card.planLabel;
    byId('version').textContent = card.proposalVersion;
    byId('reason').textContent = reasons[card.reason];
    byId('remaining-count').textContent = byId('effect-count').textContent = card.remainingOrdinals.length;
    byId('positions').hidden = !card.remainingOrdinals.length;
    byId('ordinals').textContent = positions(card);
    byId('expiry').dateTime = card.expiresAt;
    byId('expiry').textContent = date.format(new Date(card.expiresAt));
    byId('rows').replaceChildren();
    comments.clear();
    for (const [field, label] of Object.entries(fields)) {
      const row = byId('rows').insertRow();
      const heading = document.createElement('th');
      heading.scope = 'row'; heading.textContent = label; row.append(heading);
      const explanation = document.createElement('small');
      explanation.textContent = explanations[field]; heading.append(explanation);
      for (const k of Object.keys(columns)) row.insertCell().textContent = amount(field, card[k][field]);
      row.insertCell().textContent = amount(field, Math.max(0, card.totalMaximum[field] - card.consumed[field]));
      const input = document.createElement('textarea');
      input.rows = 2; input.maxLength = 500; input.setAttribute('aria-label', 'Modifica richiesta per ' + label);
      row.insertCell().append(input); comments.set(field, input);
      input.addEventListener('input', () => controls(locked));
    }
    history();
    byId('proposal').hidden = false;
    showState(card.proposalState);
    if (card.proposalState === 'OPEN') {
      updateExpiry();
      if (!locked) ThsSession.bind(JSON.stringify([reviewRef, card.proposalVersion, card.proposalHash, card.expectedSeq]), [...comments.values()]);
    }
  }

  async function failure(response, writing) {
    controls(true);
    byId('proposal').hidden = true;
    const code = (await response.json().catch(() => null))?.code;
    // A read sends only the link: an invalid request there is a malformed link, not a comment to fix.
    let message = !writing && code === 'ANALYSIS_INPUT_INVALID'
      ? 'Il collegamento non è valido. Apri la proposta dalla conversazione.' : messages[code];
    if (!message && response.status === 403) message = 'Conferma rifiutata. Riapri la scheda per aggiornare la protezione di sessione.';
    if (!message) message = writing ? 'Esito della decisione da verificare. Rileggi lo stato prima di decidere ancora.' : 'Proposta non disponibile. Rileggi lo stato.';
    if ([429, 503].includes(response.status)) {
      const value = response.headers.get('Retry-After');
      const until = value && (/^\d+$/.test(value) ? Date.now() + Number(value) * 1000 : Date.parse(value));
      if (until > Date.now() && Number.isFinite(new Date(until).getTime())) {
        retryAt = until;
        message += ' Rileggi dopo ' + date.format(new Date(until)) + '.';
      }
    }
    byId('status').textContent = message;
    byId('refresh').hidden = response.status === 401;
  }

  async function read() {
    if (Date.now() < retryAt) return;
    clearTimeout(expiryTimer);
    controls(true); byId('refresh').hidden = true; byId('proposal').hidden = true;
    try {
      const response = await ThsSession.fetch(path, {headers: {Accept: 'application/json'}, cache: 'no-store', redirect: 'error'});
      if (!response.ok) return await failure(response, false);
      const view = await response.json();
      actor = response.headers.get('X-OUF-Review-Actor');
      if (!complete(view.card) || !text(view.csrfHeader) || !text(view.csrfToken) || !digest(actor))
        throw new Error();
      card = view.card;
      csrf = {[view.csrfHeader]: view.csrfToken};
      historyKey = 'ouf-renewal-feedback:' + JSON.stringify([actor, card.analysisId, card.incidentId]);
      render();
    } catch {
      byId('proposal').hidden = true; byId('refresh').hidden = false;
      byId('status').textContent = 'Impossibile leggere la proposta completa. Rileggi prima di decidere.';
    }
  }

  async function decide(decision) {
    if (locked || card.proposalState !== 'OPEN') return;
    if (Date.parse(card.expiresAt) <= Date.now()) return showState('EXPIRED');
    const items = notes();
    if ((decision === 'CHANGES_REQUESTED') !== Boolean(items.length)) return;
    controls(true);
    clearTimeout(expiryTimer);
    const {proposalVersion, proposalHash, expectedSeq} = card;
    const command = {reviewRef, proposalVersion, proposalHash, expectedSeq, decision, items};
    try {
      // The same actor, link, revision and exact intent reproduce the same key after login or a lost reply.
      const bytes = new TextEncoder().encode(JSON.stringify([actor, command]));
      const key = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes), 0, 16);
      key[6] = 0x40 | (key[6] & 0x0f);
      key[8] = 0x80 | (key[8] & 0x3f);
      const hex = Array.from(key, b => b.toString(16).padStart(2, '0')).join('');
      command.commandKey = [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.slice(20)].join('-');
      const response = await ThsSession.fetch(path + '/decision', {method: 'POST', redirect: 'error',
        headers: {Accept: 'application/json', 'Content-Type': 'application/json', ...csrf}, body: JSON.stringify(command)});
      if (!response.ok) return await failure(response, true);
      const receipt = await response.json();
      if (!receipt || typeof receipt !== 'object' || Array.isArray(receipt)) throw new Error();
      if (decision === 'CHANGES_REQUESTED') {
        try { sessionStorage.setItem(historyKey, JSON.stringify({card, items})); } catch { /* Owner keeps the decision. */ }
      }
      showState({APPROVE: 'APPROVED', CHANGES_REQUESTED: 'CHANGES_REQUESTED', CLOSE_PARTIAL: 'CLOSED_PARTIAL'}[decision]);
    } catch {
      byId('status').textContent = 'Esito della decisione da verificare. Rileggi lo stato prima di decidere ancora.';
      byId('refresh').hidden = false;
    }
  }
  byId('approve').onclick = () => decide('APPROVE');
  byId('changes').onclick = () => decide('CHANGES_REQUESTED');
  byId('close').onclick = () => decide('CLOSE_PARTIAL');
  byId('refresh').onclick = read;
  read();
})();
