// Loopback-only CI peers. Both application images use their own IAM, policy, THS and profiler.
import assert from 'node:assert/strict';
import {createHash, createHmac, generateKeyPairSync, randomBytes, randomUUID, sign} from 'node:crypto';
import {createServer} from 'node:http';

export const app = 'http://127.0.0.1:8080';
export const tenant = 'ci-tenant', subject = 'ci-reviewer';
export const csv = Buffer.from('name,unused\nKiosk,1\n');
export const caps = {
  upload: 'ouf.managed-source.file.upload', profile: 'ouf.managed-source.file.profile',
  preview: 'ouf.managed-source.preview', create: 'ouf.managed-source.onboarding.create',
  prepare: 'ouf.managed-source.onboarding.review.prepare', write: 'ouf.onboarding.configuration.write',
};
export const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const encode = object => Buffer.from(JSON.stringify(object)).toString('base64url');
const audience = 'ci-onboarding';
const client = 'ouf-ths';
const callback = app + '/login/oauth2/code/' + client;

export function policy() {
  const now = Date.now();
  return {
    bundleId: 'ci-return', version: 1, publishedAt: new Date(now).toISOString(),
    capabilities: Object.values(caps).map(capabilityId => ({
      capabilityId, operation: capabilityId === caps.preview ? 'READ' : 'COMMAND',
      requiredScope: capabilityId, allowedActors: ['HUMAN'],
    })),
    grants: Object.values(caps).map(capabilityId => ({
      grantId: 'ci-' + capabilityId, capabilityId, tenantId: tenant, subjectId: subject,
      validFrom: new Date(now - 60_000).toISOString(), validUntil: new Date(now + 3600_000).toISOString(),
    })),
  };
}

export async function startFixture() {
  const {privateKey, publicKey} = generateKeyPairSync('rsa', {modulusLength: 2048});
  const jwk = {...publicKey.export({format: 'jwk'}), kid: 'ci', use: 'sig', alg: 'RS256'};
  const key = randomBytes(32).toString('hex');
  const clientSecret = randomUUID(), objectToken = randomUUID();
  const codes = new Map();
  let issuer;
  function jwt(claims) {
    const now = Math.floor(Date.now() / 1000);
    const payload = encode({alg: 'RS256', kid: jwk.kid}) + '.' + encode({
      iss: issuer, sub: subject, iat: now, exp: now + 600, ...claims,
    });
    return payload + '.' + sign('sha256', Buffer.from(payload), privateKey).toString('base64url');
  }
  function bearer() {
    return jwt({aud: audience, tenant_id: tenant, acr: 'ci-human', ouf_actor_type: 'HUMAN',
      scope: Object.values(caps).join(' ')});
  }
  function receipt(path, capability, body, idempotencyKey) {
    const now = Math.floor(Date.now() / 1000);
    const payload = encode({v: 1, purpose: 'managed-file-owner', method: 'POST', path,
      bodyHash: sha256(body), capability, iat: now, exp: now + 30, issuer, audience,
      workload: 'ouf-mcp-server', subject, tenant, acr: 'ci-human', roles: '', scope: capability, idempotencyKey});
    return payload + '.' + createHmac('sha256', key).update('ouf-managed-file-owner-v1.' + payload).digest('base64url');
  }
  const server = createServer(async (req, res) => {
    const url = new URL(req.url, issuer);
    const send = data => {
      res.writeHead(200, {'Content-Type': 'application/json'});
      res.end(JSON.stringify(data));
    };
    try {
      switch (url.pathname) {
        case '/.well-known/openid-configuration':
          return send({issuer, authorization_endpoint: issuer + '/authorize', token_endpoint: issuer + '/token',
            jwks_uri: issuer + '/jwks', response_types_supported: ['code'], subject_types_supported: ['public'],
            id_token_signing_alg_values_supported: ['RS256'], token_endpoint_auth_methods_supported: ['client_secret_basic']});
        case '/jwks':
          return send({keys: [jwk]});
        case '/authorize': {
          const p = url.searchParams;
          assert.equal(p.get('client_id'), client);
          assert.equal(p.get('redirect_uri'), callback);
          assert.equal(p.get('code_challenge_method'), 'S256');
          assert.ok(p.get('nonce') && p.get('code_challenge') && p.get('state'));
          const code = randomUUID();
          codes.set(code, p);
          const next = new URL(callback);
          next.search = new URLSearchParams({code, state: p.get('state')});
          res.writeHead(302, {Location: next.href});
          return res.end();
        }
        case '/token': {
          const basic = Buffer.from(client + ':' + clientSecret).toString('base64');
          assert.ok(req.method === 'POST' && req.headers.authorization === 'Basic ' + basic);
          let body = '';
          for await (const chunk of req) body += chunk;
          const form = new URLSearchParams(body), request = codes.get(form.get('code'));
          assert.ok(request && codes.delete(form.get('code')));
          assert.equal(form.get('grant_type'), 'authorization_code');
          assert.equal(form.get('redirect_uri'), callback);
          assert.equal(createHash('sha256').update(form.get('code_verifier')).digest('base64url'), request.get('code_challenge'));
          return send({access_token: bearer(), token_type: 'Bearer', expires_in: 600,
            id_token: jwt({aud: client, nonce: request.get('nonce')}), scope: 'openid'});
        }
        case '/internal/object-storage/v1/content':
          assert.ok(req.headers.authorization === 'Bearer ' + objectToken);
          assert.ok(['object://ci/legacy.csv', 'object://ci/review.csv'].includes(url.searchParams.get('ref')));
          res.writeHead(200, {'Content-Type': 'text/csv', 'Content-Length': csv.length});
          return res.end(csv);
        case '/api/trusted-human/v1/ingestion/onboarding-readiness':
          assert.equal(req.method, 'POST');
          assert.ok(req.headers.authorization?.startsWith('Bearer '));
          res.writeHead(422);
          return res.end();
        default:
          res.writeHead(404);
          res.end();
      }
    } catch {
      // Keep codes, verifiers, tokens, cookies and request bodies out of runner logs.
      console.error('CI fixture rejected request at ' + url.pathname);
      res.writeHead(400);
      res.end();
    }
  });
  await new Promise((resolve, reject) => server.once('error', reject).listen(0, '127.0.0.1', resolve));
  issuer = 'http://127.0.0.1:' + server.address().port;
  return {issuer, audience, client, clientSecret, objectToken, key, bearer, receipt, close: () => server.close()};
}
