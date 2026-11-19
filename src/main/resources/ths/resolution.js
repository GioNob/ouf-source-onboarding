(() => {
  'use strict';
  const rows = document.getElementById('cases');
  const status = document.getElementById('status');
  const confirm = document.getElementById('confirm');
  const refresh = document.getElementById('refresh');
  const selectionStatus = document.getElementById('selection-status');
  let current = null;
  let csrf = null;
  let submitting = false;
  const deferredCases = new Set();
  const node = (tag, className, value) => {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (value !== undefined) element.textContent = String(value);
    return element;
  };
  const own = (obj, key) => Object.prototype.hasOwnProperty.call(obj || {}, key);
  const display = value => value === null ? 'null' : typeof value === 'object' ? JSON.stringify(value) : String(value);
  const fieldName = key => {
    const tail = key.split(/[\/#:]/).filter(Boolean).pop() || key;
    return tail.replace(/([a-zà-ÿ])([A-Z])/g, '$1 $2').replace(/[_-]+/g, ' ');
  };
  const objectName = object => {
    const fields = object && object.fields || {};
    const key = Object.keys(fields).find(k => /^(nome|denominazione|name|title|label)(\b|[ _-])/i.test(fieldName(k)) && typeof fields[k] === 'string' && fields[k].trim());
    return key ? fields[key] : '';
  };
  function message(value, error = false) {
    status.textContent = value;
    status.classList.toggle('error', error);
  }
  function select(label, values, initial, id) {
    const box = node('div');
    const caption = node('label', null, label);
    const field = node('select');
    field.id = id; caption.htmlFor = id;
    values.forEach(([value, name]) => {
      const option = node('option', null, name); option.value = value; field.append(option);
    });
    field.value = initial; box.append(caption, field); return [box, field];
  }
  function caseRows() { return [...rows.querySelectorAll('article.case-card')]; }
  function selectedRows() { return caseRows().filter(row => !row.querySelector('.defer').checked); }
  function selectionSummary() {
    const selected = selectedRows().length, deferred = caseRows().length - selected;
    selectionStatus.textContent = `${selected} casi da confermare · ${deferred} lasciati in quarantena`;
  }
  function valid() {
    return !submitting && current && current.issues.length && selectedRows().length > 0 && selectedRows().every(row =>
      row.querySelector('.action').value &&
      (row.querySelector('.action').value !== 'APPROVE' || row.querySelector('.target').value));
  }
  function objectRow(label, object, columns) {
    const row = node('tr');
    const heading = node('th'); heading.scope = 'row'; heading.append(node('strong', null, label));
    if (object && (object.reference || object.sourceId)) {
      const reference = object.sourceId ? `Sorgente: ${object.sourceId}\nRecord: ${object.sourceObjectId}` : `ID: ${object.reference}`;
      heading.append(node('small', 'object-reference', reference));
      if (object.revision !== null && object.revision !== undefined) heading.append(node('small', 'object-reference', `Revisione ${object.revision}`));
    }
    row.append(heading);
    columns.forEach(key => {
      const cell = node('td', object && own(object.fields, key) ? 'value' : 'missing');
      cell.textContent = object && own(object.fields, key) ? display(object.fields[key]) : '—';
      row.append(cell);
    });
    return row;
  }
  function comparison(issue, target, action) {
    const box = node('div', 'comparison');
    const unavailable = node('p', 'preview-state');
    const explanation = node('div', 'property-explanation');
    const scroll = node('div', 'comparison-scroll'); scroll.tabIndex = 0;
    scroll.setAttribute('aria-label', 'Confronto dei campi, scorribile orizzontalmente');
    const table = node('table', 'object-comparison');
    table.append(node('caption', null, 'Confronto dei dati · stessa colonna = stesso campo'));
    const incoming = issue.incomingObject;
    const candidates = issue.candidateObjects || {};
    const columns = [...new Set([...Object.keys(incoming && incoming.fields || {}),
      ...Object.values(candidates).flatMap(o => Object.keys(o.fields || {})),
      ...Object.values(issue.propertyPreviews || {}).flatMap(p => Object.keys(p.properties || {}))])].sort();
    const head = node('thead'), titles = node('tr');
    const corner = node('th', null, 'Oggetto'); corner.scope = 'col'; titles.append(corner);
    const names = columns.map(fieldName);
    columns.forEach((key, index) => {
      const th = node('th', null, names.indexOf(names[index]) === names.lastIndexOf(names[index]) ? names[index] : key);
      th.scope = 'col'; th.title = key; titles.append(th);
    });
    head.append(titles); table.append(head);
    const body = node('tbody'); body.append(objectRow('Oggetto in ingresso', incoming, columns)); table.append(body);
    function update() {
      const candidate = candidates[target.value];
      body.querySelectorAll('tr').forEach((r, i) => { if (i) r.remove(); });
      const label = action.value === 'APPROVE' ? 'Oggetto candidato di destinazione' : 'Oggetto candidato · solo confronto';
      const row = objectRow(label, candidate, columns); row.className = 'candidate-row';
      [...row.querySelectorAll('td')].forEach((cell, index) => {
        const key = columns[index];
        if (incoming && candidate && own(incoming.fields, key) && own(candidate.fields, key)
            && JSON.stringify(incoming.fields[key]) !== JSON.stringify(candidate.fields[key])) cell.classList.add('different');
      });
      body.append(row);
      explanation.replaceChildren();
      if (action.value === 'APPROVE') {
        const preview = (issue.propertyPreviews || {})[target.value];
        if (preview && preview.available) {
          const properties = preview.properties || {};
          const projected = {fields: {}};
          columns.forEach(key => { if (properties[key] && properties[key].available && properties[key].status !== 'DELETED') projected.fields[key] = properties[key].proposedValue; });
          const result = objectRow('Risultato previsto del collegamento', projected, columns);
          result.className = 'result-row';
          const names = {CONFLICT:'Conflitto: il valore corrente resta invariato', ADDED:'Aggiunta', RECONCILED:'Valore riconciliato', UNCHANGED:'Valore confermato', HUMAN_SELECTED:'Scelta umana già registrata', DELETED:'Cancellazione esplicita autorizzata', NO_VALID_CONTRIBUTION:'Nessun contributo valido: valore non disponibile alla consultazione'};
          [...result.querySelectorAll('td')].forEach((cell, index) => {
            const property = properties[columns[index]];
            if (!property || !property.available) { cell.textContent = 'Regola dedicata · risultato non calcolato qui'; return; }
            if (property.status === 'NO_VALID_CONTRIBUTION') cell.textContent = 'Non disponibile';
            const description = names[property.status] || property.status;
            cell.append(node('small', 'property-status', description));
            if (property.conflict) cell.classList.add('property-conflict');
            const detail = node('details', 'property-evidence');
            detail.append(node('summary', null, `${fieldName(columns[index])} · ${description}`));
            const rule = property.rule || {}, config = rule.reconciliation || {};
            detail.append(node('p', null, config.cardinality === 'MULTI' ? 'Unione dei valori distinti; ogni provenienza resta conservata.' : 'Valore singolo; prevalgono le fonti nell’ordine dichiarato, altrimenti le differenze richiedono una scelta separata.'));
            if (Array.isArray(rule.authorityOrder) && rule.authorityOrder.length) detail.append(node('p', null, `Priorità delle fonti: ${rule.authorityOrder.join(' → ')}`));
            for (const contribution of property.contributions || []) {
              const provenance = contribution.provenance || {}, source = provenance.sourceIdentity || {};
              detail.append(node('p', 'source-evidence', `Fonte: ${contribution.sourceId} · record: ${source.sourceObjectId || '—'} · valore originale: ${contribution.valueJson} · accesso: ${contribution.accessLabel}`));
            }
            explanation.append(detail);
          });
          body.append(result);
          explanation.prepend(node('p', 'reconciliation-help', 'Il collegamento conferma l’identità. I conflitti sui singoli campi restano aperti per una scelta separata; gli altri campi possono avanzare. L’anteprima sarà verificata nuovamente alla conferma.'));
        } else explanation.append(node('p', 'reconciliation-help', 'Anteprima delle proprietà non disponibile: manca una policy di riconciliazione verificata per questo record. Nessun risultato viene dedotto dai soli campi uguali.'));
      }
      if (!incoming || !incoming.available) unavailable.textContent = 'Dati del record in ingresso non disponibili in questa risposta.';
      else if (!target.value) unavailable.textContent = 'Seleziona un candidato per confrontarne i campi.';
      else if (!candidate || !candidate.available) unavailable.textContent = 'Dati del candidato non disponibili: la riga non indica un oggetto vuoto.';
      else unavailable.textContent = 'Le celle evidenziate hanno valori diversi. — indica un campo non presente; null e campi vuoti non implicano una cancellazione.';
    }
    target.addEventListener('change', update); action.addEventListener('change', update); update();
    scroll.append(table); box.append(scroll, unavailable, explanation); return box;
  }
  function render(pkg) {
    rows.replaceChildren();
    if (!pkg.issues.length) { message('Non ci sono casi incerti aperti per questo tenant.'); confirm.disabled = true; return; }
    pkg.issues.forEach((issue, index) => {
      const row = node('article', 'case-card'); row.dataset.id = issue.issueId; row.dataset.version = issue.version; row.dataset.reviewHash = issue.reviewHash || ''; 
      const heading = node('header', 'case-heading');
      heading.append(node('p', 'case-number', `CASO ${index + 1} DI ${pkg.issues.length}`),
        node('h2', null, objectName(issue.incomingObject) || `Record ${issue.incomingObject && issue.incomingObject.sourceObjectId || index + 1}`),
        node('p', 'case-id', `Caso ${issue.issueId} · versione ${issue.version}`));
      const deferLabel = node('label', 'defer-label');
      const defer = node('input'); defer.type = 'checkbox'; defer.className = 'defer';
      defer.checked = deferredCases.has(issue.issueId);
      defer.disabled = typeof issue.reviewHash !== 'string';
      deferLabel.append(defer, node('span', null, 'Lascia in quarantena'));
      const deferHelp = node('p', 'defer-help', 'Questo caso resterà aperto. Potrai decidere in seguito e confermare intanto gli altri.');
      row.append(heading, deferLabel, deferHelp);
      const controls = node('div', 'case-controls');
      const evidence = Array.isArray(issue.evidence) ? issue.evidence[0] : null;
      const canCreate = evidence && evidence.complete === true && evidence.outcome === 'REVIEW_REQUIRED' &&
        typeof evidence.coverageRef === 'string' && evidence.coverageRef.startsWith('indexed-snapshot://');
      const [actionBox, action] = select('Decisione', [['', 'Seleziona una decisione'], ['APPROVE', 'Collega a un oggetto'],
        ...(canCreate ? [['CREATE_NEW', 'Crea un oggetto distinto']] : []), ['DISMISS', 'Chiudi il caso']],
        issue.suggestedTargetUrbanObjectId ? 'APPROVE' : '', `action-${index}`);
      action.className = 'action';
      const [targetBox, target] = select('Oggetto candidato da confrontare', [['', 'Seleziona un candidato'],
        ...issue.candidateRefs.map(ref => [ref, `${objectName((issue.candidateObjects || {})[ref]) || 'Oggetto'} · ${ref}`])],
        issue.suggestedTargetUrbanObjectId || '', `target-${index}`);
      target.className = 'target'; target.disabled = issue.candidateRefs.length === 0;
      const rationale = node('div', 'rationale');
      const label = node('label', null, 'Nota facoltativa'), reason = node('textarea');
      label.htmlFor = `reason-${index}`; reason.id = label.htmlFor; reason.className = 'reason-input'; reason.maxLength = 1000;
      reason.addEventListener('input', () => { confirm.disabled = !valid(); }); rationale.append(label, reason);
      const effect = node('p', 'decision-effect');
      defer.addEventListener('change', () => {
        if (defer.checked) deferredCases.add(issue.issueId); else deferredCases.delete(issue.issueId);
        action.disabled = defer.checked; target.disabled = defer.checked || issue.candidateRefs.length === 0; reason.disabled = defer.checked;
        row.classList.toggle('deferred', defer.checked); updateEffect();
      });
      function updateEffect() {
        effect.textContent = defer.checked ? 'Resta in quarantena: nessuna decisione verrà inviata per questo caso.' : action.value === 'APPROVE' ? 'Il record in ingresso verrà collegato all’oggetto candidato selezionato.' :
          action.value === 'CREATE_NEW' ? 'Verrà creato un oggetto distinto. Il candidato mostrato resta soltanto a confronto.' :
          action.value === 'DISMISS' ? 'Il caso verrà chiuso senza collegare il record al candidato.' : 'Scegli una decisione dopo aver confrontato i dati.';
        selectionSummary(); confirm.disabled = !valid();
      }
      action.addEventListener('change', updateEffect); target.addEventListener('change', updateEffect);
      row.append(comparison(issue, target, action)); controls.append(actionBox, targetBox, rationale); row.append(controls, effect);
      const details = node('details', 'technical-details');
      details.append(node('summary', null, 'Mostra JSON, motivo ed evidenze'), node('pre', null, JSON.stringify(issue, null, 2)));
      row.append(details); rows.append(row);
      if (defer.checked) { action.disabled = true; target.disabled = true; reason.disabled = true; row.classList.add('deferred'); }
      updateEffect();
    });
    selectionSummary(); message(`${pkg.issues.length} casi aperti. Confronta i dati; lascia in quarantena quelli su cui vuoi decidere più tardi.`);
    confirm.disabled = !valid();
  }
  async function load() {
    if (submitting) return;
    confirm.disabled = true; message('Caricamento dei casi…');
    try {
      const response = await fetch('api/package', {credentials: 'same-origin', cache: 'no-store'});
      if (!response.ok) throw new Error(`Lettura non disponibile (HTTP ${response.status}).`);
      const data = await response.json(); current = data.package; csrf = {[data.csrfHeader]: data.csrfToken}; render(current);
    } catch (error) { current = null; rows.replaceChildren(); message(error.message, true); }
  }
  refresh.addEventListener('click', load);
  confirm.addEventListener('click', async () => {
    if (!valid()) return;
    submitting = true; confirm.disabled = true; refresh.disabled = true;
    const fields = [...rows.querySelectorAll('input,select,textarea')].map(element => [element, element.disabled]);
    fields.forEach(([element]) => { element.disabled = true; });
    const choices = selectedRows().map(row => ({issueId: row.dataset.id, expectedVersion: Number(row.dataset.version),
      action: row.querySelector('.action').value,
      ...(row.dataset.reviewHash ? {expectedReviewHash: row.dataset.reviewHash} : {}),
      targetUrbanObjectId: row.querySelector('.action').value === 'APPROVE' ? row.querySelector('.target').value : null,
      reason: row.querySelector('.reason-input').value.trim()}));
    try {
      const response = await fetch('api/package/confirm', {method: 'POST', credentials: 'same-origin',
        headers: {'Content-Type': 'application/json', ...csrf}, body: JSON.stringify({snapshotHash: current.snapshotHash, choices})});
      if (!response.ok) {
        let code = '';
        const raw = await response.text();
        if (raw.length <= 8192) {
          try { const problem = JSON.parse(raw); const candidate = problem.code || problem.detail;
            if (typeof candidate === 'string' && /^[A-Z][A-Z0-9_]{1,159}$/.test(candidate)) code = ` · ${candidate}`;
          } catch (_) { /* Never render arbitrary upstream response content. */ }
        }
        throw new Error(`Conferma respinta (HTTP ${response.status}${code}). Aggiorna e verifica di nuovo il pacchetto.`);
      }
      const result = await response.json(); submitting = false; await load();
      message(`Pacchetto confermato: ${result.issueCount} decisioni, riferimento ${result.packageId}.`);
    } catch (error) {
      current = null; message(`${error.message} Nessun invio viene ripetuto automaticamente.`, true);
    } finally { submitting = false; refresh.disabled = false; fields.forEach(([element, disabled]) => { element.disabled = disabled; }); selectionSummary(); confirm.disabled = !valid(); }
  });
  load();
})();

