'use strict';
(() => {
  const element = id => document.getElementById(id);
  const query = new URLSearchParams(location.search);
  const runId = query.get('runId');
  const version = query.get('expectedVersion');
  const validLink = [...query].length === 2 && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(runId || '')
    && /^(0|[1-9][0-9]{0,15})$/.test(version || '') && Number.isSafeInteger(Number(version));
  const endpoint = validLink ? 'api/' + runId + '/' + version : null;
  const form = document.querySelector('#decision form');
  const status = element('status');
  const choices = element('choices');
  const confirm = element('confirm');
  const reload = element('reload');
  let review;

  const states = {
    PAUSED: 'In pausa', ABORTED: 'Interrotto', SUCCEEDED: 'Completato',
    COMPLETED_WITH_WARNINGS: 'Completato con avvisi', FAILED: 'Fallito',
    RUNNING: 'In corso', READY: 'In coda', DRAINING: 'Consegna in corso'
  };
  const causes = {
    ING_RECOVERY_BUDGET_EXHAUSTED: 'I tentativi o il tempo del budget di recupero sono terminati.',
    ING_ACTIVE_PUBLICATION_CHANGED: 'La configurazione pubblicata è cambiata o non è stata verificata di recente.',
    ING_HANDOFF_BLOCKED: 'La consegna dei dati a valle è bloccata.',
    ING_OPERATOR_ABORTED: 'Un operatore ha interrotto l’acquisizione.',
    ING_LAKE_CONTENT_DIVERGED: 'I dati trovati nel deposito differiscono da quelli attesi per questo run.',
    ING_LAKE_OUTCOME_UNKNOWN: 'L’esito di una scrittura nel deposito è incerto.',
    ING_LAKE_RECEIPT_STORE_UNCONFIRMED: 'Ingestion deve verificare la registrazione della ricevuta di scrittura.'
  };
  const count = value => Number.isSafeInteger(value) && value >= 0;
  const known = (map, key) => Object.hasOwn(map, key) ? map[key] : undefined;

  function render(card) {
    element('run').hidden = false;
    element('source').textContent = card.source_id || 'Fonte non indicata';
    element('run-id').textContent = card.run_id;
    element('run-state').textContent = known(states, card.state) || 'Stato comunicato da Ingestion: ' + card.state;
    const ownerCode = card.lake_cause_code || card.failure_code;
    const code = typeof ownerCode === 'string' && /^[A-Z][A-Z0-9_]{2,100}$/.test(ownerCode) ? ownerCode : null;
    const explanation = known(causes, code)
      || (code ? 'Codice comunicato da Ingestion: ' + code + '.' : 'Ingestion non ha indicato una causa.');
    element('cause').textContent = card.lake_next_action === 'ABORT_REQUIRED'
      ? explanation + ' Per questo run è disponibile solo l’interruzione.' : explanation;
    const effects = card.effects;
    const evidence = [];
    if (count(effects?.lakeWritesConfirmed)) evidence.push('Scritture nel deposito confermate: ' + effects.lakeWritesConfirmed);
    if (count(effects?.handoffsAcknowledged)) evidence.push('Consegne confermate: ' + effects.handoffsAcknowledged);
    if (count(effects?.handoffsUnconfirmed)) evidence.push('Consegne con esito da verificare: ' + effects.handoffsUnconfirmed);
    if (evidence.length < 3) evidence.push('Ingestion non ha fornito un riepilogo completo degli effetti già scritti');
    element('effects').textContent = evidence.join('. ') + '.';
    const budget = card.recoveryBudget;
    const deadline = budget?.deadline_at ? new Date(budget.deadline_at) : null;
    element('budget').textContent = count(budget?.remaining_attempts) && count(budget?.max_attempts)
      ? 'Tentativi residui nell’ultimo budget: ' + budget.remaining_attempts + ' su ' + budget.max_attempts
        + (count(budget.cumulative_attempts) ? '. Tentativi complessivi: ' + budget.cumulative_attempts : '')
        + (deadline && Number.isFinite(deadline.getTime()) ? '. Scadenza: ' + deadline.toLocaleString('it-IT') + '.' : '.')
      : 'Ingestion non ha restituito un budget di recupero precedente.';
  }

  async function read() {
    review = null;
    form.reset();
    confirm.disabled = true;
    element('run').hidden = true;
    element('decision').hidden = true;
    reload.hidden = true;
    if (!endpoint) {
      status.textContent = 'Il collegamento non identifica un run e una versione validi. Riapri la scheda dal collegamento di recupero.';
      return;
    }
    try {
      const response = await ThsSession.fetch(endpoint);
      if (!response.ok) {
        status.textContent = [403, 404].includes(response.status)
          ? 'Questo run non è disponibile per il tuo account.' : 'Impossibile leggere lo stato dell’acquisizione.';
        reload.hidden = [401, 403, 404].includes(response.status);
        return;
      }
      const envelope = await response.json();
      const card = envelope.card;
      render(card);
      if (!envelope.linkCurrent || String(card.control_version) !== version) {
        status.textContent = 'Lo stato è cambiato dopo l’emissione del collegamento. Apri il nuovo collegamento di recupero per prendere una decisione.';
        return;
      }
      if (card.state !== 'PAUSED') {
        status.textContent = 'Il run non è in pausa. Qui puoi consultare lo stato attuale.';
        return;
      }
      const resume = form.elements.action[0];
      resume.disabled = card.lake_next_action === 'ABORT_REQUIRED';
      element('resume-option').hidden = resume.disabled;
      review = envelope;
      choices.disabled = false;
      element('decision').hidden = false;
      status.textContent = 'Esamina lo stato prima di confermare.';
    } catch {
      status.textContent = 'Impossibile leggere lo stato dell’acquisizione.';
      reload.hidden = false;
    }
  }

  form.addEventListener('change', () => { confirm.disabled = !review || !form.elements.action.value; });
  form.addEventListener('submit', async event => {
    event.preventDefault();
    const action = form.elements.action.value;
    if (!review || !['resume', 'abort'].includes(action)) return;
    const {csrfHeader, csrfToken} = review;
    review = null;
    choices.disabled = true;
    status.textContent = 'Decisione in corso…';
    try {
      const result = await ThsSession.fetch(endpoint + '/' + action, {method: 'POST', headers: {[csrfHeader]: csrfToken}});
      if (result.ok) {
        render(await result.json());
        status.textContent = action === 'resume' ? 'Ingestion ha accettato la ripresa.' : 'Ingestion ha confermato l’interruzione.';
      } else if (result.status === 409 || result.status === 412) {
        status.textContent = 'La decisione non è stata accettata. Rileggi lo stato prima di decidere.';
        reload.hidden = false;
      } else if (result.status === 403) {
        status.textContent = 'La decisione è stata rifiutata. Verifica l’accesso e rileggi lo stato.';
        reload.hidden = false;
      } else {
        throw new Error('Decision outcome unavailable');
      }
    } catch {
      status.textContent = 'Esito della decisione da verificare. Rileggi lo stato prima di decidere di nuovo.';
      reload.hidden = false;
    } finally {
      element('decision').hidden = true;
    }
  });
  reload.addEventListener('click', read);
  read();
})();
