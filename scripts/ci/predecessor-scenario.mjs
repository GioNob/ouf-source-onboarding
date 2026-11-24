import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {setTimeout as sleep} from 'node:timers/promises';
import {app, caps, csv, sha256, tenant} from './predecessor-fixture.mjs';

const files = '/api/onboarding/v1/managed-files';
const ths = '/trusted-human/onboarding/';
const type = 'https://example.org/onto/Place', property = 'urn:fixture:name';
const publication = randomUUID(), revision = randomUUID();
const feedback = [{section: 'FIELD', target: 'unused', comment: 'Classificare come personale.'}];

function proposal(sourceId) {
  const policyRef = 'urn:fixture:identity';
  return {
    semanticBindings: [{semanticId: type, semanticVersion: '1', revisionId: revision, publicationSetId: publication}],
    semanticPublicationSetRef: publication, limits: {maxRows: 100, maxColumns: 2, maxCellChars: 1000},
    udp: {
      resolution: {strategyId: 'GOVERNED_IDENTITY', strategyVersion: '1', policyRef, governedIdentity: {
        ref: policyRef, version: '1', tenantId: tenant, sourceId, canonicalClass: type, allowAutoNew: false, maxCandidates: 10,
        signals: [{id: property, semanticRef: property + '@' + publication, comparator: 'TEXT_V1',
          excludesOnDisagreement: false, uniqueWithinScope: false, assertionRef: 'urn:fixture:signal'}],
        sufficientRules: [{id: 'name-rule', signalIds: [property], assertionRef: 'urn:fixture:rule'}],
      }},
      materialization: {policyRef: 'urn:fixture:authority', checkpointInterval: 1, bitemporalProperties: [],
        properties: [{sourceField: property, propertyIri: property, datatype: 'http://www.w3.org/2001/XMLSchema#string',
          accessLabel: 'OPEN', authorityOrder: [sourceId]}]},
    },
  };
}

async function json(path, {method = 'GET', headers = {}, body, status = 200} = {}) {
  const response = await fetch(app + path, {method, headers: {'Content-Type': 'application/json', ...headers},
    body, redirect: 'manual', signal: AbortSignal.timeout(20000)});
  if (response.status !== status) {
    const error = await response.json().catch(() => ({}));
    const code = /^[A-Z][A-Z0-9_]{1,80}$/.test(error.code) ? error.code : 'NO_CODE';
    throw new Error(`${method} ${path} returned ${response.status}, expected ${status} (${code})`);
  }
  return response.json();
}

function command(fixture, route, capability, args, key) {
  const path = '/api/internal/v1/onboarding/managed-file-mcp/' + route;
  const body = JSON.stringify({CorrelationID: key, Arguments: args});
  return json(path, {method: 'POST', body, headers: {
    'X-OUF-Managed-File-Receipt': fixture.receipt(path, capability, body, key),
  }});
}

async function profile(fixture, sourceId) {
  const headers = {Authorization: 'Bearer ' + fixture.bearer()};
  const {asset_id: assetId} = await json(files, {method: 'POST', headers, status: 201, body: JSON.stringify({
    stagingRef: 'object://ci/' + sourceId + '.csv', contentHash: 'sha256:' + sha256(csv),
    mediaType: 'text/csv', sizeBytes: csv.length, retentionRef: 'retention://ci',
  })});
  const {job_id: job} = await json(`${files}/${assetId}/profile`, {method: 'POST', status: 202,
    headers: {...headers, 'Idempotency-Key': 'profile-' + sourceId}});
  for (let n = 0; n < 45; n++) {
    const result = await json(`${files}/${assetId}/profile-jobs/${job}`, {headers});
    if (result.status === 'SUCCEEDED') {
      assert.ok(result.resultRef);
      return {sourceId, assetId, profileId: result.resultRef};
    }
    assert.ok(['QUEUED', 'RUNNING'].includes(result.status), 'Unexpected profile state: ' + result.status);
    await sleep(1000);
  }
  throw new Error('Profile did not finish within the CI deadline');
}

function createDraft(fixture, file, label, key) {
  return command(fixture, 'create', caps.create, {...file, name: 'CI ' + file.sourceId, owner: 'CI',
    targetClassIri: type, semanticRefs: [type + '@1'], sourceObjectKeyFields: ['name'],
    fields: [{fieldName: 'name', extractionDecision: 'INCLUDE', dataAccessLabel: 'OPEN',
      targetPropertyIri: property, transform: 'IDENTITY'},
    {fieldName: 'unused', extractionDecision: 'EXCLUDE', dataAccessLabel: label}],
  }, key);
}

function prepare(fixture, draft, commandKey) {
  return command(fixture, 'review-prepare', caps.prepare, {
    sourceId: draft.sourceId, onboardingVersionId: draft.onboardingVersionId,
    expectedVersion: 0, commandKey, executionProposal: proposal(draft.sourceId),
  }, commandKey);
}

async function version(fixture, draft) {
  const path = `/api/onboarding/v1/sources/${draft.sourceId}/onboarding-versions/${draft.onboardingVersionId}`;
  const row = await json(path, {headers: {Authorization: 'Bearer ' + fixture.bearer()}});
  assert.equal(row.onboarding_version_id, draft.onboardingVersionId);
  assert.equal(row.source_id, draft.sourceId);
  assert.deepEqual(row.configuration.extractionProfile.selection, {assetId: draft.assetId, fileProfileId: draft.profileId});
  return row;
}

async function openCard(fixture, review) {
  // A fresh browser session must reach both the real HTML and its authenticated card endpoint.
  const challenge = review.challengeId;
  const target = new URL(review.reviewUrl);
  assert.equal(target.origin, 'https://ths.example.invalid');
  assert.equal(target.pathname, ths);
  assert.equal(target.searchParams.get('challenge'), challenge);
  let url = new URL(target.pathname + target.search, app), cookie;
  for (let n = 0; n < 12; n++) {
    assert.ok([app, fixture.issuer].includes(url.origin), 'Unexpected login redirect origin');
    const headers = {Accept: 'text/html'};
    if (cookie && url.origin === app) headers.Cookie = cookie;
    const page = await fetch(url, {headers, redirect: 'manual', signal: AbortSignal.timeout(10000)});
    if (url.origin === app) {
      for (const set of page.headers.getSetCookie()) {
        if (set.startsWith('JSESSIONID=')) cookie = set.split(';')[0];
      }
    }
    if (page.status === 302 || page.status === 303) {
      url = new URL(page.headers.get('location'), url);
      continue;
    }
    assert.equal(page.status, 200, 'THS login failed at ' + url.pathname);
    assert.equal(url.origin, app);
    assert.equal(url.pathname, ths);
    assert.match(page.headers.get('content-type'), /text\/html/);
    assert.ok((await page.text()).includes('review.js'), 'THS review page missing');
    assert.ok(cookie);
    const result = await json(ths + 'api/' + challenge, {headers: {Cookie: cookie}});
    assert.equal(result.card.challenge_id, challenge);
    assert.equal(result.card.onboarding_version_id, review.onboardingVersionId);
    assert.equal(result.card.configuration_hash, review.configurationHash);
    assert.ok(result.csrfHeader && result.csrfToken, 'Missing THS write proof');
    return {card: result.card, headers: {Cookie: cookie, [result.csrfHeader]: result.csrfToken}};
  }
  throw new Error('THS login redirect limit exceeded');
}

export async function writeV42(fixture) {
  const legacyFile = await profile(fixture, 'legacy');
  const legacy = await createDraft(fixture, legacyFile, 'OPEN', 'create-legacy');
  const legacyReview = await prepare(fixture, legacy, 'prepare-legacy');
  assert.equal(legacyReview.challengeStatus, 'CREATED');
  const file = await profile(fixture, 'review');
  const parent = await createDraft(fixture, file, 'OPEN', 'create-parent');
  assert.equal((await version(fixture, parent)).state, 'DRAFT');
  return {legacy, legacyReview, parent, file};
}

export async function writeV43(fixture, {parent, file}) {
  const review = await prepare(fixture, parent, 'prepare-parent');
  const {card, headers} = await openCard(fixture, review);
  assert.deepEqual(card.reviewSnapshot.columnOrder, ['name', 'unused']);
  assert.equal(card.reviewSnapshot.file.assetId, file.assetId);
  const changes = await json(ths + 'api/' + review.challengeId + '/changes', {method: 'POST', headers,
    body: JSON.stringify({configurationHash: review.configurationHash, expectedVersion: card.lockVersion, reviewFeedback: feedback})});
  assert.equal(changes.state, 'CHANGES_REQUESTED');
  assert.equal(changes.challengeStatus, 'CANCELLED');
  assert.deepEqual(changes.reviewFeedback, feedback);
  const child = await createDraft(fixture, file, 'PERSONAL', 'create-child');
  const row = await version(fixture, child);
  assert.equal(row.state, 'DRAFT');
  assert.equal(row.version, 2);
  assert.equal(row.parent_version_id, parent.onboardingVersionId);
  return {review, child};
}

export async function verifyReturn(fixture, {legacy, legacyReview, parent, file}, {review, child}) {
  const original = await version(fixture, legacy);
  assert.equal(original.configuration_hash, legacyReview.configurationHash);
  assert.equal(original.state, 'IN_REVIEW');
  const rejected = await version(fixture, parent);
  assert.equal(rejected.state, 'REJECTED');
  assert.equal(rejected.configuration_hash, review.configurationHash);
  const {card} = await openCard(fixture, review);
  assert.equal(card.onboardingState, 'REJECTED');
  assert.equal(card.progress.state, 'REJECTED');
  assert.equal(card.progress.challengeStatus, 'CANCELLED');
  const row = await version(fixture, child);
  // The V42 read API does not project parent_version_id; the link is verified in the database by the caller.
  assert.ok(row.parent_version_id === undefined || row.parent_version_id === parent.onboardingVersionId);
  assert.equal(row.state, 'DRAFT');
  // Reconcile a V43 command with V42, then exercise a new V42 write and its identical retry.
  assert.deepEqual(await createDraft(fixture, file, 'PERSONAL', 'create-child'), child);
  const prepared = await prepare(fixture, child, 'return-review');
  assert.equal(prepared.state, 'IN_REVIEW');
  assert.equal(prepared.challengeStatus, 'CREATED');
  assert.notEqual(prepared.challengeId, review.challengeId);
  assert.deepEqual(await prepare(fixture, child, 'return-review'), prepared);
  assert.equal((await version(fixture, child)).configuration_hash, prepared.configurationHash);
}
