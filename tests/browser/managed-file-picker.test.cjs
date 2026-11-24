const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { runInNewContext } = require('node:vm');
const { webcrypto } = require('node:crypto');

const script = readFileSync('src/main/resources/ths/managed-file-picker.js', 'utf8');
const csv = {mediaType: 'text/csv', maxBytes: 50};
const xlsx = {mediaType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', maxBytes: 50};
const gpkg = {mediaType: 'application/geopackage+sqlite3', maxBytes: 50};

async function submit(name, formats, size = 3) {
  const calls = [];
  let onSubmit;
  const file = {name, size, arrayBuffer: async () => new ArrayBuffer(size)};
  const elements = {
    upload: {addEventListener: (_, fn) => { onSubmit = fn; }},
    file: {files: [file], dataset: {formats: JSON.stringify(formats)}},
    status: {}, send: {}, result: {}
  };
  const session = {fetch: async (path, options) => {
    calls.push({path, options});
    return {ok: true, json: async () => path.endsWith('/session')
      ? {csrfToken: 'fixture', csrfHeader: 'X-CSRF'}
      : {assetId: '00000000-0000-4000-8000-000000000001'}};
  }};
  runInNewContext(script, {document: {getElementById: id => elements[id]}, URLSearchParams,
    window: {location: {search: ''}}, crypto: webcrypto, ThsSession: session});
  await onSubmit({preventDefault() {}});
  return {calls, file, status: elements.status.textContent};
}

for (const [name, formats, type] of [
  ['dati.CsV', {csv, xlsx}, csv], ['dati.XLSX', {csv, xlsx}, xlsx], ['dati.gpkg', {gpkg}, gpkg]
]) {
  test('picker forwards ' + name + ' with its configured media type', async () => {
    const {calls, file} = await submit(name, formats);
    const upload = calls.at(-1);
    assert.equal(upload.path, '/trusted-human/managed-files/upload');
    assert.equal(upload.options.headers['Content-Type'], type.mediaType);
    assert.equal(upload.options.body, file);
    assert.match(upload.options.headers['X-Content-SHA256'], /^sha256:[0-9a-f]{64}$/);
  });
}

test('disabled extension and per-format size are checked before upload', async () => {
  assert.equal((await submit('disabled.xlsx', {csv})).calls.length, 0);
  assert.equal((await submit('large.csv', {csv}, csv.maxBytes + 1)).calls.length, 0);
});
