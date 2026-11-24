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

export function issuePicker(fixture, key) {
  return command(fixture, 'handoff-issue', caps.upload, {commandKey: key}, key);
}

export function readPicker(fixture, handoffId) {
  return command(fixture, 'handoff', caps.upload, {handoffId}, randomUUID());
}

async function profile(fixture, sourceId) {
  const headers = {Authorization: 'Bearer ' + fixture.bearer()};
  const {asset_id: assetId} = await json(files, {method: 'POST', headers, status: 201, body: JSON.stringify({
    stagingRef: 'object://ci/' + sourceId + '.csv', contentHash: 'sha256:' + sha256(csv),
    mediaType: 'text/csv', sizeBytes: csv.length, retentionRef: 'retention://ci',
  })});
  const request = () => json(`${files}/${assetId}/profile`, {method: 'POST', status: 202,
    headers: {...headers, 'Idempotency-Key': 'profile-' + sourceId}});
  const {job_id: job} = await request();
  for (let n = 0; n < 45; n++) {
    const result = await json(`${files}/${assetId}/profile-jobs/${job}`, {headers});
    if (result.status === 'SUCCEEDED') {
      assert.ok(result.resultRef);
      if (sourceId === 'return-profile') {
        const replay = await request();
        assert.equal(replay.job_id, job);
        assert.equal(replay.resultRef, result.resultRef);
      }
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

export async function writePredecessor(fixture) {
  const legacyFile = await profile(fixture, 'legacy');
  const legacy = await createDraft(fixture, legacyFile, 'OPEN', 'create-legacy');
  const legacyReview = await prepare(fixture, legacy, 'prepare-legacy');
  assert.equal(legacyReview.challengeStatus, 'CREATED');
  const decision = {configurationHash: legacyReview.configurationHash, expectedVersion: legacyReview.lockVersion};
  // Confirm directly at the HUMAN owner endpoint. The loopback fixture provides no semantic catalogue for THS term lookup.
  const approved = await json(`/api/trusted-human/v1/approval-challenges/${legacyReview.challengeId}/review/confirm`, {
    method: 'POST', headers: {Authorization: 'Bearer ' + fixture.bearer()}, body: JSON.stringify(decision),
  });
  assert.equal(approved.state, 'APPROVED');
  const {headers} = await openCard(fixture, legacyReview);
  const refusal = await json(ths + 'api/' + legacyReview.challengeId + '/activate', {method: 'POST', headers,
    body: JSON.stringify({...decision, expectedVersion: approved.lockVersion}), status: 409});
  assert.equal(refusal.code, 'ONB_READINESS_DEPENDENCY_422');
  const file = await profile(fixture, 'review');
  const parent = await createDraft(fixture, file, 'OPEN', 'create-parent');
  assert.equal((await version(fixture, parent)).state, 'DRAFT');
  return {legacy, legacyReview, parent, file};
}

export async function writeCandidate(fixture, {legacyReview, parent, file}) {
  const blocked = await openCard(fixture, legacyReview);
  const before = blocked.card.progress.activationProgress;
  assert.equal(before.readiness_phase, 'BLOCKED');
  assert.equal(before.humanRecheckAllowed, true);
  assert.equal(before.cumulativeAttempts, 1);
  const resumed = await json(ths + 'api/' + legacyReview.challengeId + '/resume', {method: 'POST', headers: blocked.headers,
    body: JSON.stringify({configurationHash: legacyReview.configurationHash, expectedVersion: blocked.card.lockVersion,
      expectedGeneration: before.readiness_generation, blockedCode: before.readiness_last_code})});
  const readiness = resumed.activationProgress;
  assert.equal(resumed.state, 'APPROVED');
  assert.equal(readiness.readiness_phase, 'PAUSED');
  assert.equal(readiness.readiness_generation, before.readiness_generation + 1);
  assert.equal(readiness.cumulativeAttempts, before.cumulativeAttempts);
  assert.equal(readiness.budgetExpiresAt, before.budgetExpiresAt);
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
  const typedFile = await profile(fixture, 'value-types');
  const result = await command(fixture, 'preview', caps.preview,
    {assetId: typedFile.assetId, profileId: typedFile.profileId}, 'candidate-profile-read');
  const [redacted, eligible] = result.columns;
  assert.equal(redacted.distinctValues, undefined);
  assert.equal(redacted.distinctCount, 1);
  assert.deepEqual(eligible.distinctValues, ['1']);
  assert.equal(result.redactedSample[0].name, '[REDACTED]');
  const typed = await command(fixture, 'create', caps.create, {...typedFile, name:'CI value types', owner:'CI',
    targetClassIri:type, semanticRefs:[type+'@1',property+'@1'], sourceObjectKeyFields:['name'],
    fields:[{fieldName:'name',extractionDecision:'INCLUDE',dataAccessLabel:'OPEN'},
      {fieldName:'unused',extractionDecision:'INCLUDE',dataAccessLabel:'OPEN'}],
    compositions:[{kind:'CONSTRUCTOR',constructorId:'TEXT_JOIN_V1',targetPropertyIri:property,dataAccessLabel:'OPEN',
      inputs:[{role:'part',fieldName:'name',reader:'TEXT_NFC_TRIM_V1'},{role:'part',fieldName:'unused',reader:'IDENTITY'}],
      params:{separator:' / '}}]}, 'create-typed');
  const mapping = (await version(fixture, typed)).configuration.semanticMapping;
  assert.equal(mapping.compositions[0].inputs[0].reader,'TEXT_NFC_TRIM_V1');
  return {review, child, readiness, typed, mapping};
}

export async function verifyReturn(fixture, {legacy, legacyReview, parent, file}, {review, child, readiness, typed, mapping}, predecessorHasDistinctValues) {
  assert.deepEqual((await version(fixture, typed)).configuration.semanticMapping, mapping);
  const original = await version(fixture, legacy);
  assert.equal(original.configuration_hash, legacyReview.configurationHash);
  assert.equal(original.state, 'APPROVED');
  const resumed = (await openCard(fixture, legacyReview)).card.progress.activationProgress;
  for (const key of ['readiness_phase', 'readiness_generation', 'readiness_last_code', 'cumulativeAttempts', 'budgetExpiresAt'])
    assert.equal(resumed[key], readiness[key], 'predecessor reads retained readiness ' + key);
  const rejected = await version(fixture, parent);
  assert.equal(rejected.state, 'REJECTED');
  assert.equal(rejected.configuration_hash, review.configurationHash);
  const {card} = await openCard(fixture, review);
  // Both predecessors project a rejection with review notes as CHANGES_REQUESTED,
  // both in the progress and in the card state derived from it (the version row stays REJECTED, checked above).
  assert.equal(card.onboardingState, 'CHANGES_REQUESTED');
  assert.equal(card.progress.state, 'CHANGES_REQUESTED');
  assert.equal(card.progress.challengeStatus, 'CANCELLED');
  const row = await version(fixture, child);
  assert.equal(row.parent_version_id, parent.onboardingVersionId);
  assert.equal(row.state, 'DRAFT');
  // Reconcile a candidate command, then exercise a new predecessor write and its identical retry.
  // The older V43 predecessor replays the command without the additive D-085 observation fields.
  const additive = new Set(['workflowState', 'reasonCode', 'allowedActions', 'lockVersion']);
  // task-52c derives the proposal requirement from the retained configuration (nothing is stored), so a
  // predecessor without it replays the same draft as DRAFT_READY without the derived fields. A predecessor
  // with task-52c (the exact deployed 20e90de) already declared them on its own draft: its replay is unchanged.
  const derived = new Set(['executionProposalRequired', 'missingSections']);
  const predecessorDerivesProposal = 'executionProposalRequired' in parent;
  const replayed = Object.fromEntries(Object.entries(child)
    .filter(([key]) => (predecessorDerivesProposal || !derived.has(key)) && (predecessorHasDistinctValues || !additive.has(key))));
  if (!predecessorDerivesProposal && replayed.reasonCode === 'EXECUTION_PROPOSAL_REQUIRED') replayed.reasonCode = 'DRAFT_READY';
  assert.deepEqual(await createDraft(fixture, file, 'PERSONAL', 'create-child'), replayed);
  const prepared = await prepare(fixture, child, 'return-review');
  assert.equal(prepared.state, 'IN_REVIEW');
  assert.equal(prepared.challengeStatus, 'CREATED');
  assert.notEqual(prepared.challengeId, review.challengeId);
  assert.deepEqual(await prepare(fixture, child, 'return-review'), prepared);
  assert.equal((await version(fixture, child)).configuration_hash, prepared.configurationHash);
  const headers = {Authorization: 'Bearer ' + fixture.bearer()};
  const retained = await json(`${files}/${typed.assetId}/profiles/${typed.profileId}`, {headers});
  assert.deepEqual(retained.inferred_schema.map(c => c.distinctValues), [1, 1]);
  assert.equal(retained.distinct_cell_values, undefined);
  return profile(fixture, 'return-profile');
}

// A new source written by the predecessor after the return, on the file it profiled after the return (task-62h).
export async function createReturnSource(fixture, file) {
  const draft = await createDraft(fixture, file, 'OPEN', 'create-return');
  assert.equal(draft.sourceId, file.sourceId);
  assert.equal((await version(fixture, draft)).state, 'DRAFT');
  return draft;
}
