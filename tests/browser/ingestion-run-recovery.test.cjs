const {test} = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const {chromium} = require('playwright');
const asset = require('./ths-asset.cjs');

const run = '00000000-0000-4000-8000-000000000001';
const path = '/trusted-human/ingestion-run-recovery/';
const card = {
  run_id: run, control_version: 7, source_id: 'fonte-di-prova-'.repeat(14),
  state: 'PAUSED', failure_code: 'ING_RECOVERY_BUDGET_EXHAUSTED',
  effects: {lakeWritesConfirmed: 10, handoffsAcknowledged: 8, handoffsUnconfirmed: 2},
  recoveryBudget: {max_attempts: 128, remaining_attempts: 0, cumulative_attempts: 128, deadline_at: '2026-01-07T00:00:00Z'}
};
const envelope = (view, linkCurrent = true) => ({card: view, linkCurrent, csrfHeader: 'X-CSRF-TOKEN', csrfToken: 'fixture'});

async function surface(exercise) {
  const assets = {
    [path]: ['ingestion-run-recovery.html', 'text/html;charset=UTF-8'],
    [path + 'recovery.js']: ['ingestion-run-recovery.js', 'text/javascript'],
    [path + 'recovery.css']: ['ingestion-run-recovery.css', 'text/css']
  };
  const server = http.createServer((request, response) => {
    const resource = assets[request.url.split('?')[0]];
    if (!resource) { response.writeHead(404).end(); return; }
    const [name, contentType] = resource;
    response.writeHead(200, {'Content-Type': contentType, 'Content-Security-Policy':
      "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; form-action 'self'"});
    response.end(asset(name));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  let browser;
  try {
    browser = await chromium.launch({headless: true});
    const page = await browser.newPage({viewport: {width: 375, height: 780}});
    await exercise(page, 'http://127.0.0.1:' + server.address().port + path + '?runId=' + run + '&expectedVersion=7');
  } finally {
    await browser?.close();
    await new Promise(resolve => server.close(resolve));
  }
}

test('50b-B01 shows cause, effects and finite budget at 375 px and submits resume once with CSRF', async () => surface(async (page, url) => {
  const writes = [];
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.route('**/ingestion-run-recovery/api/**', route => {
    const request = route.request();
    if (request.method() === 'GET') return route.fulfill({json: envelope(card)});
    writes.push([new URL(request.url()).pathname, request.headers()['x-csrf-token']]);
    return route.fulfill({json: {...card, state: 'RUNNING', control_version: 8}});
  });
  await page.goto(url);
  await page.locator('#decision').waitFor();
  assert.match(await page.locator('#cause').innerText(), /budget di recupero/);
  assert.match(await page.locator('#effects').innerText(), /deposito confermate: 10.*Consegne confermate: 8/);
  assert.match(await page.locator('#budget').innerText(), /0 su 128.*Scadenza/);
  const layout = await page.evaluate(() => ({
    viewport: innerWidth, content: document.documentElement.clientWidth, scroll: document.documentElement.scrollWidth
  }));
  assert.equal(layout.viewport, 375);
  assert.equal(layout.scroll, layout.content);
  assert.equal(await page.locator('#confirm').isDisabled(), true);
  assert.deepEqual(writes, []);
  await page.getByRole('radio', {name: /Riprendi/}).check();
  await page.locator('#confirm').click();
  await page.getByRole('status').filter({hasText: 'ha accettato la ripresa'}).waitFor();
  assert.deepEqual(writes, [[path + 'api/' + run + '/7/resume', 'fixture']]);
  assert.deepEqual(errors, []);
}));

test('50b-B02 offers abort alone when required and reconciles a lost response without replay', async () => surface(async (page, url) => {
  let current = {...card, effects: undefined, lake_next_action: 'ABORT_REQUIRED'};
  const writes = [];
  await page.route('**/ingestion-run-recovery/api/**', route => {
    const request = route.request();
    if (request.method() === 'GET') return route.fulfill({json: envelope(current, current.control_version === 7)});
    writes.push([new URL(request.url()).pathname, request.headers()['x-csrf-token']]);
    current = {...current, state: 'ABORTED', control_version: 8};
    return route.abort('failed');
  });
  await page.goto(url);
  await page.locator('#decision').waitFor();
  assert.equal(await page.locator('#resume-option').isHidden(), true);
  assert.match(await page.locator('#effects').innerText(), /non ha fornito un riepilogo completo/);
  await page.getByRole('radio', {name: /Interrompi/}).check();
  await page.locator('#confirm').click();
  await page.getByRole('status').filter({hasText: 'Esito della decisione da verificare'}).waitFor();
  await page.getByRole('button', {name: 'Rileggi lo stato'}).click();
  await page.getByRole('status').filter({hasText: 'Lo stato è cambiato'}).waitFor();
  assert.equal(await page.locator('#run-state').innerText(), 'Interrotto');
  assert.equal(await page.locator('#decision').isHidden(), true);
  assert.deepEqual(writes, [[path + 'api/' + run + '/7/abort', 'fixture']]);
}));

test('50b-B03 rejects invalid links, hides denied runs and reuses the session-expired notice', async () => surface(async (page, url) => {
  let status = 403;
  let reads = 0;
  await page.route('**/ingestion-run-recovery/api/**', route => {
    reads++;
    assert.equal(route.request().method(), 'GET');
    return route.fulfill({status, json: {code: status === 401 ? 'THS_SESSION_EXPIRED' : 'THS_ACCESS_DENIED'}});
  });
  await page.goto(url + '&expectedVersion=8');
  await page.getByRole('status').filter({hasText: 'non identifica'}).waitFor();
  assert.equal(reads, 0);
  await page.goto(url);
  await page.getByRole('status').filter({hasText: 'non è disponibile per il tuo account'}).waitFor();
  assert.equal(await page.locator('#run').isHidden(), true);
  status = 401;
  await page.reload();
  await page.getByRole('button', {name: 'Accedi di nuovo'}).waitFor();
  assert.equal(await page.locator('main').evaluate(node => node.inert), true);
  assert.equal(reads, 2);
}));
