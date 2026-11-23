import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {appendFileSync, chmodSync, mkdtempSync, rmSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {setTimeout as sleep} from 'node:timers/promises';
import {app, policy, sha256, startFixture} from './predecessor-fixture.mjs';
import {issuePicker, readPicker, verifyReturn, writePredecessor, writeCandidate} from './predecessor-scenario.mjs';

const [previous, candidate] = process.argv.slice(2);
for (const id of [previous, candidate]) assert.match(id, /^sha256:[0-9a-f]{64}$/);
assert.notEqual(previous, candidate);
const db = process.env.CI_POSTGRES_CONTAINER;
assert.match(db, /^[0-9a-f]{12,64}$/);
assert.ok(process.env.OUF_ONB_DB_PASSWORD);
const docker = (...args) => execFileSync('docker', args, {encoding: 'utf8', timeout: 45_000}).trim();
const user = 'ouf_onboarding';
function sql(query) {
  return docker('exec', db, 'psql', '-XAtq', '-v', 'ON_ERROR_STOP=1', '-U', user, '-d', user, '-c', query);
}
function history(last) {
  const rows = JSON.parse(sql(`select json_agg(row_to_json(h) order by installed_rank)
    from ouf_onboarding.flyway_schema_history h where version is not null`));
  assert.ok(rows.every(r => r.success));
  if (last) assert.equal(rows.at(-1).version, last);
  return rows;
}

async function ready(container, phase) {
  const deadline = Date.now() + 120_000;
  while (Date.now() < deadline && docker('inspect', '-f', '{{.State.Running}}', container) === 'true') {
    let response;
    try {
      response = await fetch(app + '/actuator/health/readiness', {redirect: 'manual', signal: AbortSignal.timeout(2000)});
    } catch (error) {
      // While the container starts, the socket may refuse, reset or close early (UND_ERR_SOCKET).
      // Keep polling until the deadline; a stopped container ends the loop and fails below.
      const transient = ['ECONNREFUSED', 'ECONNRESET', 'UND_ERR_SOCKET', 'UND_ERR_CLOSED'];
      if (error.name !== 'TimeoutError' && !transient.includes(error.cause?.code) && error.message !== 'fetch failed') throw error;
    }
    if (response?.status === 200) {
      assert.equal((await response.json()).status, 'UP');
      console.log(phase + ': readiness UP');
      return;
    }
    if (response && response.status !== 503) throw new Error(phase + ': unexpected readiness HTTP ' + response.status);
    await sleep(1000);
  }
  // Classify the startup failure without copying application logs or OIDC parameters into CI.
  const logs = execFileSync('docker', ['logs', '--tail', '300', container],
    {encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe']});
  const flyway = /FlywayValidateException|Validate failed|Migration of schema .* failed|applied migration not resolved locally/i.test(logs);
  if (phase === 'predecessor return on candidate data' && process.env.GITHUB_STEP_SUMMARY) {
    const reason = flyway ? 'I log indicano un errore di migrazione o validazione Flyway.' : 'La readiness non ha raggiunto UP.';
    appendFileSync(process.env.GITHUB_STEP_SUMMARY, `## Domande aperte\n\nIl predecessore si è fermato su V46. ${reason} La qualificazione D-080 resta aperta.\n`);
  }
  throw new Error(phase + ': readiness failed' + (flyway ? ' during Flyway migration or validation' : '') +
    '. Return proof stopped with the packaged migration settings.');
}

const work = mkdtempSync(join(tmpdir(), 'ouf-return-'));
const fixture = await startFixture();
const containers = [];
try {
  // Bind individual read-only fixture files. The images retain their production UID and entrypoint.
  writeFileSync(join(work, 'delegation'), fixture.key);
  writeFileSync(join(work, 'object-token'), fixture.objectToken);
  for (const name of ['delegation', 'object-token']) chmodSync(join(work, name), 0o444);
  const properties = {
    'server.address': '127.0.0.1',
    'ouf.iam.enabled': true, 'ouf.iam.issuer': fixture.issuer, 'ouf.iam.audience': fixture.audience,
    'ouf.authorization.ths.enabled': true, 'ouf.authorization.ths.public-origin': 'https://ths.example.invalid',
    'ouf.workflow.ths.gateway-base-url': app, 'ouf.authorization.delegation.key-file': '/ci/delegation',
    'ouf.onboarding.udp-identity.gateway-url': fixture.issuer,
    'ouf.onboarding.object-store.gateway-base-url': fixture.issuer, 'ouf.onboarding.object-store.token-file': '/ci/object-token',
    'spring.security.oauth2.client.registration.ouf-ths.provider': 'ci',
    'spring.security.oauth2.client.registration.ouf-ths.client-id': fixture.client,
    'spring.security.oauth2.client.registration.ouf-ths.client-secret': fixture.clientSecret,
    'spring.security.oauth2.client.registration.ouf-ths.authorization-grant-type': 'authorization_code',
    'spring.security.oauth2.client.registration.ouf-ths.redirect-uri': app + '/login/oauth2/code/ouf-ths',
    'spring.security.oauth2.client.registration.ouf-ths.scope': 'openid',
    'spring.security.oauth2.client.provider.ci.issuer-uri': fixture.issuer,
  };
  // Pass generated values by environment name so CLI diagnostics cannot print them.
  process.env.SPRING_APPLICATION_JSON = JSON.stringify(properties);
  function run(image) {
    const container = docker('run', '-d', '--network', 'host',
      '-e', 'OUF_ONB_DB_URL=jdbc:postgresql://127.0.0.1:5432/ouf_onboarding', '-e', 'OUF_ONB_DB_USER=' + user,
      '-e', 'OUF_ONB_DB_PASSWORD', '-e', 'SPRING_APPLICATION_JSON',
      '--mount', 'type=bind,src=' + join(work, 'delegation') + ',dst=/ci/delegation,readonly',
      '--mount', 'type=bind,src=' + join(work, 'object-token') + ',dst=/ci/object-token,readonly', image);
    containers.push(container);
    assert.equal(docker('inspect', '-f', '{{.Image}}', container), image);
    return container;
  }
  assert.equal(sql("select current_setting('server_version_num')::int / 10000"), '17');
  assert.equal(sql("select to_regclass('ouf_onboarding.flyway_schema_history') is null"), 't');
  const old = run(previous);
  await ready(old, 'predecessor on empty database');
  const baseline = history();
  const predecessorHasDistinctValues = baseline.at(-1).version === '45';
  assert.ok(predecessorHasDistinctValues || baseline.at(-1).version === '43');
  // Seed only the isolated IAM policy; the runtime writes every onboarding row through HTTP.
  const bundle = policy(), payload = JSON.stringify(bundle);
  assert.ok(!payload.includes("'"));
  sql(`begin;
    insert into ouf_authorization.policy_bundle(bundle_id,version,published_at,published_by,content_hash,bundle_payload)
      values ('ci-return',1,'${bundle.publishedAt}','ci-fixture','sha256:${sha256(payload)}','${payload}'::jsonb);
    insert into ouf_authorization.active_policy_bundle(singleton_key,bundle_id,version) values(true,'ci-return',1);
    commit`);
  docker('restart', old);
  await ready(old, 'predecessor with CI policy');
  const seed = await writePredecessor(fixture);
  docker('stop', old);

  const next = run(candidate);
  await ready(next, 'candidate on populated database');
  const candidateHistory = history('46');
  assert.deepEqual(candidateHistory.slice(0, baseline.length), baseline, 'candidate must retain the exact deployed migration history');
  const expected = predecessorHasDistinctValues ? [] :
    ['V44__managed_profile_distinct_values.sql', 'V45__managed_value_map_preparation.sql'];
  assert.deepEqual(candidateHistory.slice(baseline.length).map(row => row.script), [...expected, 'V46__managed_file_handoff.sql']);
  const commandKey = randomUUID();
  const issued = await issuePicker(fixture, commandKey);
  const pending = await readPicker(fixture, issued.handoffId);
  assert.equal(pending.state, 'PENDING');
  assert.equal(pending.waitUntil, issued.waitUntil);
  assert.equal(pending.observationVersion, '1');
  docker('restart', next);
  await ready(next, 'candidate restarted with pending picker');
  assert.deepEqual(await issuePicker(fixture, commandKey), issued);
  assert.deepEqual(await readPicker(fixture, issued.handoffId), pending);
  // Only the candidate writes this table. Compare every field across the predecessor return.
  const pickerRow = () => sql('select to_jsonb(h)::text from ouf_onboarding.managed_file_handoff h');
  const pickerEvidence = pickerRow();
  assert.equal(JSON.parse(pickerEvidence).handoff_id, issued.handoffId);
  const result = await writeCandidate(fixture, seed);
  function distinctProfile(profileId) {
    assert.match(profileId, /^[0-9a-f-]{36}$/);
    return sql(`select coalesce(distinct_cell_values, 'NULL') from ouf_onboarding.file_profile where profile_id='${profileId}'`);
  }
  const valueEvidence = distinctProfile(result.typed.profileId);
  assert.deepEqual(JSON.parse(valueEvidence), {'': {unused: ['1']}});
  function candidateEvidence() {
    return JSON.parse(sql(`select json_build_object('outcome', d.review_outcome, 'decision', d.decision,
      'feedback', d.review_feedback, 'snapshot', s.snapshot, 'version', s.onboarding_version_id, 'challenge', d.challenge_id)
      from ouf_onboarding.approval_decision d join ouf_onboarding.managed_review_snapshot s using(onboarding_version_id)
      join ouf_onboarding.approval_challenge c on c.challenge_id=d.challenge_id
      where c.source_id='review'`));
  }
  const evidence = candidateEvidence();
  assert.equal(evidence.version, seed.parent.onboardingVersionId);
  assert.equal(evidence.challenge, result.review.challengeId);
  assert.equal(evidence.outcome, 'CHANGES_REQUESTED');
  assert.equal(evidence.decision, 'REJECT');
  assert.deepEqual(evidence.feedback, [{section: 'FIELD', target: 'unused', comment: 'Classificare come personale.'}]);
  assert.equal(evidence.snapshot.file.assetId, seed.file.assetId);
  console.log('V43 persisted review feedback, snapshot and same-file child draft');
  docker('stop', next);
  docker('start', old);
  assert.equal(docker('inspect', '-f', '{{.Image}}', old), previous);
  await ready(old, 'predecessor return on candidate data');
  const oldObservation = await readPicker(fixture, issued.handoffId);
  assert.equal(oldObservation.status, 'PENDING');
  for (const field of ['state', 'issuedAt', 'waitUntil', 'observationVersion']) {
    assert.ok(!(field in oldObservation), 'predecessor uses its original handoff reader');
  }
  const returnedProfile = await verifyReturn(fixture, seed, result, predecessorHasDistinctValues);
  assert.equal(distinctProfile(result.typed.profileId), valueEvidence);
  const returnedValues = distinctProfile(returnedProfile.profileId);
  if (predecessorHasDistinctValues) assert.deepEqual(JSON.parse(returnedValues), JSON.parse(valueEvidence));
  else assert.equal(returnedValues, 'NULL');
  assert.equal(sql(`select parent_version_id from ouf_onboarding.onboarding_version where onboarding_version_id='${result.child.onboardingVersionId}'`),
    seed.parent.onboardingVersionId, 'child draft keeps its parent link after the return');
  assert.deepEqual(history('46'), candidateHistory);
  assert.equal(pickerRow(), pickerEvidence);
  assert.deepEqual(candidateEvidence(), evidence);
  assert.equal(sql("select count(*) from ouf_onboarding.onboarding_version where source_id='review'"), '2');
  assert.equal(sql("select count(*) from ouf_onboarding.managed_onboarding_review where source_id='review'"), '2');
  assert.equal(sql("select count(*) from ouf_onboarding.approval_challenge where source_id='review'"), '2');
  console.log('D-080 PASS: predecessor reconciled profile and review, preserving the picker and migration history through V46');
} finally {
  fixture.close();
  for (const container of containers.reverse()) docker('rm', '-f', container);
  rmSync(work, {recursive: true, force: true});
}
