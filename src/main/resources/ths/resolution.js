(() => {
  'use strict';
  const rows = document.getElementById('cases');
  const status = document.getElementById('status');
  const confirm = document.getElementById('confirm');
  const refresh = document.getElementById('refresh');
  let current = null;
  let csrf = null;
  const node = (tag, className, value) => {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (value !== undefined) element.textContent = String(value);
    return element;
  };
  function message(value, error = false) {
    status.textContent = value;
    status.classList.toggle('error', error);
  }
  function select(label, values, initial) {
    const box = node('div');
    const caption = node('label', null, label);
    const field = node('select');
    values.forEach(([value, name]) => {
      const option = node('option', null, name);
      option.value = value;
      field.append(option);
    });
    field.value = initial;
    box.append(caption, field);
    return [box, field];
  }
  function valid() {
    if (!current || !current.issues.length) return false;
    return [...rows.querySelectorAll('tr')].every(row =>
      row.querySelector('.action').value && row.querySelector('.reason-input').value.trim() &&
      (row.querySelector('.action').value !== 'APPROVE' || row.querySelector('.target').value));
  }
  function render(pkg) {
    rows.replaceChildren();
    if (!pkg.issues.length) {
      message('Non ci sono casi incerti aperti per questo tenant.');
      confirm.disabled = true;
      return;
    }
    pkg.issues.forEach(issue => {
      const row = node('tr');
      row.dataset.id = issue.issueId;
      row.dataset.version = issue.version;
      const id = node('td');
      id.append(node('div', 'case-id', issue.issueId), node('div', null, `Versione ${issue.version}`));
      const info = node('td');
      info.append(node('div', 'reason', issue.reasonCode));
      const details = node('details');
      details.append(node('summary', null, 'Mostra evidenze e candidati'),
        node('pre', null, JSON.stringify({candidati: issue.candidateRefs, evidenze: issue.evidence}, null, 2)));
      info.append(details);
      const proposal = node('td');
      const evidence = Array.isArray(issue.evidence) ? issue.evidence[0] : null;
      const canCreate = evidence && evidence.complete === true &&
        evidence.outcome === 'REVIEW_REQUIRED' &&
        typeof evidence.coverageRef === 'string' &&
        evidence.coverageRef.startsWith('indexed-snapshot://');
      const [actionBox, action] = select('Decisione', [['', 'Seleziona una decisione'],
        ['APPROVE', 'Collega a un oggetto'],
        ...(canCreate ? [['CREATE_NEW', 'Crea un oggetto distinto']] : []),
        ['DISMISS', 'Chiudi il caso']],
        issue.suggestedTargetUrbanObjectId ? 'APPROVE' : '');
      action.className = 'action';
      const [targetBox, target] = select('Oggetto di destinazione',
        [['', 'Nessun oggetto selezionato'], ...issue.candidateRefs.map(ref => [ref, ref])],
        issue.suggestedTargetUrbanObjectId || '');
      target.className = 'target';
      action.addEventListener('change', () => {
        target.disabled = action.value !== 'APPROVE';
        confirm.disabled = !valid();
      });
      target.disabled = action.value !== 'APPROVE';
      target.addEventListener('change', () => { confirm.disabled = !valid(); });
      proposal.append(actionBox, targetBox);
      const rationale = node('td');
      const label = node('label', null, 'Motivazione della scelta');
      const reason = node('textarea');
      reason.className = 'reason-input';
      reason.maxLength = 1000;
      reason.addEventListener('input', () => { confirm.disabled = !valid(); });
      rationale.append(label, reason);
      row.append(id, info, proposal, rationale);
      rows.append(row);
    });
    message(`${pkg.issues.length} casi aperti. Verifica ogni riga prima di confermare.`);
    confirm.disabled = !valid();
  }
  async function load() {
    confirm.disabled = true;
    message('Caricamento dei casi…');
    try {
      const response = await fetch('api/package', {credentials: 'same-origin', cache: 'no-store'});
      if (!response.ok) throw new Error(`Lettura non disponibile (HTTP ${response.status}).`);
      const data = await response.json();
      current = data.package;
      csrf = {[data.csrfHeader]: data.csrfToken};
      render(current);
    } catch (error) { current = null; rows.replaceChildren(); message(error.message, true); }
  }
  refresh.addEventListener('click', load);
  confirm.addEventListener('click', async () => {
    if (!valid()) return;
    confirm.disabled = true;
    const choices = [...rows.querySelectorAll('tr')].map(row => ({
      issueId: row.dataset.id,
      expectedVersion: Number(row.dataset.version),
      action: row.querySelector('.action').value,
      targetUrbanObjectId: row.querySelector('.action').value === 'APPROVE' ? row.querySelector('.target').value : null,
      reason: row.querySelector('.reason-input').value.trim()
    }));
    try {
      const response = await fetch('api/package/confirm', {method: 'POST', credentials: 'same-origin',
        headers: {'Content-Type': 'application/json', ...csrf},
        body: JSON.stringify({snapshotHash: current.snapshotHash, choices})});
      if (!response.ok) throw new Error(`Conferma respinta (HTTP ${response.status}). Aggiorna e verifica di nuovo il pacchetto.`);
      const result = await response.json();
      await load();
      message(`Pacchetto confermato: ${result.issueCount} decisioni, riferimento ${result.packageId}.`);
    } catch (error) { message(error.message, true); confirm.disabled = !valid(); }
  });
  load();
})();
