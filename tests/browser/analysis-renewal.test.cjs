const {test} = require('node:test');
const assert = require('node:assert/strict');
const {chromium} = require('playwright');
const asset = require('./ths-asset.cjs');

const origin = 'https://ths.example';
const root = '/trusted-human/analysis-renewal/';
const url = origin + root;
const firstLink = 'Primo_link_rinnovo-001';
const nextLink = 'Altro_link_rinnovo-002';
const uuidV4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const proposal = {
  analysisId: 'a'.repeat(64), incidentId: 'incidente-A', proposalVersion: 1, proposalHash: 'b'.repeat(64), expectedSeq: '4',
  proposalState: 'OPEN', planLabel: 'ricreatori.csv · 18 colonne', previous: null, requestedChanges: [],
  reason: 'ANALYSIS_BUDGET_EXHAUSTED', remainingOrdinals: [0, 7],
  initialMaximum: {Calls: 100, Attempts: 120, Objects: 800, Bytes: 1048576, Millis: 3600000},
  consumed: {Calls: 100, Attempts: 117, Objects: 400, Bytes: 524288, Millis: 7500},
  increment: {Calls: 20, Attempts: 15, Objects: 0, Bytes: 0, Millis: 2500},
  totalMaximum: {Calls: 120, Attempts: 135, Objects: 800, Bytes: 1048576, Millis: 3602500},
  expiresAt: '2099-03-18T12:00:00Z'
};

async function surface(run) {
  const browser = await chromium.launch({headless: true, channel: process.env.THS_TEST_BROWSER_CHANNEL});
  try {
    const page = await browser.newPage({viewport: {width: 375, height: 800}});
    const errors = [];
    page.on('pageerror', e => errors.push(e.message));
    const fake = {
      card: structuredClone(proposal), writes: [], actor: 'a'.repeat(64),
      async read(route) {
        await route.fulfill({headers: {'X-OUF-Review-Actor': this.actor}, json: {
          card: this.card, csrfHeader: 'X-CSRF-TOKEN', csrfToken: 'fixture-csrf'
        }});
      },
      async decide(route) { await route.fulfill({json: {decisionRef: 'ricevuta'}}); }
    };
    // Intercept every request, including login return. No external server or identity provider.
    await page.route('**/*', async route => {
      const req = route.request(), path = new URL(req.url()).pathname;
      assert.equal(new URL(req.url()).origin, origin);
      if (new URL(req.url()).searchParams.has('__ths_login'))
        return route.fulfill({contentType: 'text/html', body: '<script>location.replace(location.pathname)</script>'});
      if (req.method() === 'POST') {
        assert.equal(path, new URL(page.url()).pathname + '/decision');
        fake.writes.push({headers: req.headers(), body: req.postDataJSON()});
        return fake.decide(route);
      }
      if (req.headers().accept === 'application/json') return fake.read(route);
      const [name, type] = path.endsWith('/assets/renewal.js') ? ['analysis-renewal.js', 'text/javascript']
        : path.endsWith('/assets/renewal.css') ? ['analysis-renewal.css', 'text/css'] : ['analysis-renewal.html', 'text/html'];
      await route.fulfill({contentType: type + ';charset=UTF-8', body: asset(name)});
    });
    await page.goto(url + firstLink);
    await page.locator('#proposal').waitFor();
    await run(page, fake);
    assert.deepEqual(errors, []);
  } finally { await browser.close(); }
}

test('ONB-55D-01 · PET-onboarding-145 · il solo link spiega il rinnovo e le cinque voci', () => surface(async (page, fake) => {
  assert.equal(await page.locator('#plan').innerText(), proposal.planLabel);
  assert.equal(await page.locator('#reason').innerText(), 'L’analisi ha raggiunto uno dei limiti disponibili.');
  assert.equal(await page.locator('#remaining-count').innerText(), '2');
  assert.equal(await page.locator('#ordinals').isHidden(), true);
  await page.getByText('Posizioni nel file', {exact: true}).click();
  assert.equal(await page.locator('#ordinals').innerText(), '1, 8');
  assert.equal(await page.locator('tbody tr').count(), 5);
  assert.deepEqual((await page.locator('tbody tr').first().locator('td').allTextContents()).slice(0, 5),
    ['100', '100', '20', '120', '20']);
  assert.match(await page.locator('tbody tr').nth(3).innerText(), /1,048576 MB.*524,288 kB/s);
  assert.match(await page.locator('tbody tr').last().innerText(), /1 h.*7,5 s.*2,5 s.*1 h 2,5 s.*59 min 55 s/s);
  assert.equal((await page.locator('#proposal').innerText()).match(/2099/g).length, 1);
  assert.equal(await page.locator('tbody th small').count(), 5);
  assert.match(await page.locator('article').innerText(), /Restano ferme le colonne con altri problemi/);
  assert.equal(await page.getByRole('textbox').first().getAttribute('maxlength'), '500');
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
  assert.equal(fake.writes.length, 0);
  await page.getByRole('button', {name: 'Chiudi senza rinnovo'}).click();
  await page.getByRole('status').filter({hasText: 'Chiusura senza rinnovo registrata'}).waitFor();
  assert.equal(fake.writes[0].body.decision, 'CLOSE_PARTIAL');
}));

test('ONB-55D-02 · commenti, ritorno dal login e confronto della nuova versione sullo stesso incidente', () => surface(async (page, fake) => {
  fake.decide = route => route.fulfill({status: 401, json: {code: 'THS_SESSION_EXPIRED'}});
  const input = page.getByRole('textbox', {name: 'Modifica richiesta per Chiamate'});
  await input.fill('Bastano dieci chiamate.');
  assert.equal(await page.locator('#approve').isDisabled(), true);
  await page.locator('#changes').click();
  await page.getByRole('button', {name: 'Accedi di nuovo'}).waitFor();
  assert.equal(await page.locator('main').evaluate(el => el.inert), true);
  await page.getByRole('button', {name: 'Accedi di nuovo'}).click();
  await page.locator('#proposal').waitFor();
  assert.equal(await input.inputValue(), 'Bastano dieci chiamate.');
  fake.decide = route => route.fulfill({json: {decisionRef: 'confermata'}});
  await page.locator('#changes').click();
  await page.getByRole('status').filter({hasText: 'Modifiche richieste'}).waitFor();
  assert.deepEqual(fake.writes[0].body, fake.writes[1].body);
  assert.deepEqual(fake.writes[1].body.items, [{field: 'Calls', comment: 'Bastano dieci chiamate.'}]);
  assert.equal(fake.writes[1].body.decision, 'CHANGES_REQUESTED');
  Object.assign(fake.card, {proposalVersion: 2, proposalHash: 'c'.repeat(64), expectedSeq: '5'});
  delete fake.card.requestedChanges; // The server has no feedback field; use this actor's local fallback.
  fake.card.increment.Calls = 10; fake.card.totalMaximum.Calls = 110;
  await page.goto(url + nextLink);
  await page.locator('#history').waitFor();
  const diff = await page.locator('#history').innerText();
  assert.match(diff, /Bastano dieci chiamate/);
  assert.match(diff, /Chiamate, incremento: 20 → 10/);
  assert.match(diff, /Chiamate, tetto totale: 120 → 110/);
  assert.equal(await input.inputValue(), '');
  fake.actor = 'b'.repeat(64);
  await page.reload(); await page.locator('#proposal').waitFor();
  assert.doesNotMatch(await page.locator('#history').innerText(), /Bastano dieci chiamate/);
}));

test('ONB-55D-03 · doppio clic, CSRF e riconciliazione con chiave identica dopo risposta persa', () => surface(async (page, fake) => {
  const receipts = new Map();
  fake.decide = route => {
    const {commandKey} = route.request().postDataJSON();
    if (receipts.has(commandKey)) return route.fulfill({json: receipts.get(commandKey)});
    receipts.set(commandKey, {decisionRef: 'ricevuta'});
    return route.abort('failed');
  };
  await page.locator('#approve').evaluate(button => { button.click(); button.click(); });
  await page.getByRole('status').filter({hasText: 'Esito della decisione da verificare'}).waitFor();
  assert.equal(fake.writes.length, 1);
  assert.equal(fake.writes[0].headers['x-csrf-token'], 'fixture-csrf');
  const body = fake.writes[0].body;
  assert.deepEqual(body, {decision: 'APPROVE', items: [], reviewRef: firstLink, proposalVersion: 1,
    proposalHash: proposal.proposalHash, expectedSeq: '4', commandKey: body.commandKey});
  assert.match(body.commandKey, uuidV4);
  await page.locator('#refresh').click(); await page.locator('#approve').click();
  await page.getByRole('status').filter({hasText: 'Rinnovo approvato'}).waitFor();
  assert.deepEqual(fake.writes.map(write => write.body), [body, body]);
  assert.equal(receipts.size, 1);
  // The fixture returns the same OPEN revision to compare a different intent.
  fake.decide = route => route.fulfill({json: {decisionRef: 'chiusa'}});
  await page.reload(); await page.locator('#close').click();
  await page.getByRole('status').filter({hasText: 'Chiusura senza rinnovo registrata'}).waitFor();
  assert.match(fake.writes[2].body.commandKey, uuidV4);
  assert.notEqual(fake.writes[2].body.commandKey, body.commandKey);
}));

test('ONB-55D-04 · vecchio link ed esiti del Gateway, compreso Retry-After', () => surface(async (page, fake) => {
  fake.decide = route => route.fulfill({status: 409, json: {code: 'ANALYSIS_STALE_EXECUTION'}});
  await page.locator('#approve').click();
  await page.getByRole('status').filter({hasText: 'versione superata'}).waitFor();
  assert.equal(fake.writes.length, 1);
  assert.equal(fake.writes[0].body.proposalVersion, 1);
  for (const [status, code, message] of [
    [400, 'ANALYSIS_INPUT_INVALID', 'collegamento non è valido'], [403, 'ANALYSIS_AUTHORITY_DENIED', 'non è autorizzato'],
    [404, 'ANALYSIS_MANDATE_UNKNOWN', 'non è disponibile per il tuo account'],
    [409, 'ANALYSIS_STALE_EXECUTION', 'versione superata'], [409, 'ANALYSIS_COMMAND_CONFLICT', 'scelta diversa'],
    [409, 'ANALYSIS_RENEWAL_USED', 'già stato utilizzato'], [410, 'ANALYSIS_MANDATE_EXPIRED', 'autorizzazione al lavoro è scaduta'],
    [429, 'ANALYSIS_BACKOFF', 'servizio è occupato'], [503, 'ANALYSIS_AUTHORITY_UNAVAILABLE', 'temporaneamente indisponibile']
  ]) {
    fake.read = route => route.fulfill({status, headers: {'Retry-After': '15'}, json: {code}});
    await page.reload();
    await page.getByRole('status').filter({hasText: message}).waitFor();
    assert.equal(await page.locator('#proposal').isHidden(), true);
    if (status === 429 || status === 503) assert.match(await page.getByRole('status').innerText(), /Rileggi dopo/);
  }
  assert.equal(fake.writes.length, 1);
}));

test('ONB-55D-05 · ogni proposta conclusa indica il prossimo passo senza offrire decisioni', () => surface(async (page, fake) => {
  for (const [state, message] of Object.entries({
    APPROVED: 'Rinnovo approvato', CLOSED_PARTIAL: 'Chiusura senza rinnovo', CHANGES_REQUESTED: 'Modifiche richieste',
    SUPERSEDED: 'versione è stata sostituita', EXPIRED: 'proposta è scaduta', OPEN: 'proposta è scaduta'
  })) {
    fake.card.proposalState = state;
    if (state === 'OPEN') fake.card.expiresAt = '2000-01-01T00:00:00Z';
    await page.reload();
    await page.getByRole('status').filter({hasText: message}).waitFor();
    assert.match(await page.getByRole('status').innerText(), /conversazione/);
    assert.equal(await page.locator('#actions').getByRole('button').count(), 0);
    assert.equal(await page.getByRole('textbox').count(), 0);
    assert.equal(await page.locator('#proposal').isVisible(), true);
  }
  assert.equal(fake.writes.length, 0);
}));

test('ONB-55D-06 · la nuova versione mostra confronto e commenti del server anche dal solo link', () => surface(async (page, fake) => {
  const previous = {};
  for (const key of ['proposalVersion', 'reason', 'remainingOrdinals', 'initialMaximum', 'consumed', 'increment', 'totalMaximum', 'expiresAt'])
    previous[key] = structuredClone(proposal[key]);
  Object.assign(fake.card, {previous, requestedChanges: [{field: 'general', comment: 'Bastano dieci chiamate.'}],
    proposalVersion: 2, expectedSeq: '5', proposalHash: 'c'.repeat(64), remainingOrdinals: [7]});
  fake.card.increment.Calls = 10;
  fake.card.totalMaximum.Calls = 110;
  await page.evaluate(() => sessionStorage.clear());
  await page.goto(url + nextLink);
  await page.locator('#history').waitFor();
  assert.match(await page.locator('#history').innerText(), /Generale · Modifica richiesta: Bastano dieci chiamate/);
  assert.match(await page.locator('#history').innerText(), /Chiamate, incremento: 20 → 10/);
  await page.locator('#history summary').click();
  assert.match(await page.locator('#history').innerText(), /Prima: 1, 8. Ora: 8/);

  // A stale local copy must not override the server's version or an explicitly empty list.
  await page.evaluate(({actor, card}) => {
    sessionStorage.setItem('ouf-renewal-feedback:' + JSON.stringify([actor, card.analysisId, card.incidentId]),
      JSON.stringify({card, items: [{field: 'Calls', comment: 'Solo nella cache'}]}));
  }, {actor: fake.actor, card: {...proposal, remainingOrdinals: [3]}});
  fake.card.requestedChanges = [];
  await page.reload();
  await page.locator('#history').waitFor();
  await page.locator('#history summary').click();
  const diff = await page.locator('#history').innerText();
  assert.match(diff, /Prima: 1, 8. Ora: 8/);
  assert.doesNotMatch(diff, /Solo nella cache|Bastano dieci/);
  assert.equal(fake.writes.length, 0);
}));

test('ONB-55D-01 · digest, sequenze, versioni e campi di presentazione fuori contratto bloccano la decisione', () => surface(async (page, fake) => {
  for (const invalid of [{expectedSeq: 4}, {expectedSeq: '04'}, {expectedSeq: '9223372036854775808'},
    {proposalVersion: 0}, {proposalVersion: '1'}, {proposalVersion: 2 ** 53}, {analysisId: 'A'.repeat(64)},
    {proposalHash: 'b'.repeat(63)}, {reason: 'unknown'}, {proposalState: 'unknown'}, {planLabel: 'x'.repeat(201)}, {planLabel: '🟢'.repeat(201)},
    {requestedChanges: [{field: 'general', comment: '🟢'.repeat(501)}]}]) {
    fake.card = {...proposal, ...invalid};
    await page.reload();
    await page.getByRole('status').filter({hasText: 'Impossibile leggere la proposta completa'}).waitFor();
    assert.equal(await page.locator('#approve').isHidden(), true);
  }
  // 200 Unicode characters outside the BMP are 400 UTF-16 units: the contract limit counts characters.
  fake.card = {...proposal, expectedSeq: '9223372036854775807', proposalVersion: Number.MAX_SAFE_INTEGER,
    planLabel: '🟢'.repeat(200), requestedChanges: [{field: 'general', comment: '🟢'.repeat(500)}],
    consumed: {...proposal.consumed, Calls: 130}};
  await page.reload(); await page.locator('#proposal').waitFor();
  assert.equal(await page.locator('#plan').innerText(), '🟢'.repeat(200));
  // A consumption above the total never shows a negative availability.
  assert.equal(await page.locator('tbody tr').first().locator('td').nth(4).textContent(), '0');
  await page.locator('#approve').click();
  await page.getByRole('status').filter({hasText: 'Rinnovo approvato'}).waitFor();
  assert.equal(fake.writes.length, 1);
  assert.equal(fake.writes[0].body.expectedSeq, '9223372036854775807');
}));
