/**
 * End-to-end proof that the key does not leave the server.
 *
 * The unit tests check the helpers. This one starts the real HTTP server, points it at a local
 * stand-in for Google, and drives it over a real socket. That is the only way to test the property
 * that actually matters here: whatever the client can observe, it cannot see the credential.
 *
 * Run: node --test backend/e2e.test.mjs
 */

import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';

const TEST_KEY = 'AIzaE2E_SECRET_0123456789abcdefghij';

process.env.GEMINI_API_KEY = TEST_KEY;
process.env.NODE_ENV = 'test';
process.env.RATE_LIMIT_PER_MINUTE = '100';

const { server } = await import('./server.mjs');

/**
 * A raw HTTP client for talking to the server under test.
 *
 * Deliberately not `fetch`: the test stubs `globalThis.fetch` to stand in for Google, and a
 * stubbed global would intercept the test's own requests to the server too, making every
 * assertion meaningless. Going straight to `node:http` keeps the two directions independent.
 */
function request(port, { method = 'GET', path = '/', body = null } = {}) {
  return new Promise((resolve, reject) => {
    const payload = body === null ? null : Buffer.from(body, 'utf8');
    const req = http.request(
      {
        host: '127.0.0.1',
        port,
        path,
        method,
        headers: payload
          ? {
              'content-type': 'application/json',
              'content-length': payload.length,
            }
          : {},
      },
      (res) => {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () =>
          resolve({
            status: res.statusCode,
            headers: res.headers,
            body: Buffer.concat(chunks).toString('utf8'),
          })
        );
      }
    );
    req.on('error', reject);
    if (payload) req.write(payload);
    req.end();
  });
}

/** What the server sent upstream, captured from the stubbed fetch. */
const upstreamCalls = [];

let port;

test.before(async () => {
  globalThis.fetch = async (url, options) => {
    upstreamCalls.push({ url, options });

    if (options.headers['x-goog-api-key'] !== TEST_KEY) {
      // If the server ever stopped forwarding the key, the upstream call would fail in the wild
      // and every one of these tests would still pass. Assert it explicitly.
      throw new Error('the server did not send the key upstream');
    }

    return new Response(
      JSON.stringify({
        candidates: [
          {
            content: {
              parts: [
                {
                  text: JSON.stringify({
                    hanzi: '水',
                    pinyin: 'shuǐ',
                    meaning: 'water',
                    hskLevel: 1,
                    radical: '水 (water)',
                    exampleCn: '多喝水。',
                    examplePy: 'Duō hē shuǐ.',
                    exampleEn: 'Drink more water.',
                    strokeCount: 4,
                    strokeBreakdown: '竖钩 (Shù Gōu), 横撇 (Héng Piě), 撇 (Piě), 捺 (Nà)',
                  }),
                },
              ],
            },
          },
        ],
      }),
      { status: 200, headers: { 'content-type': 'application/json' } }
    );
  };

  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  port = server.address().port;
});

test.after(async () => {
  await new Promise((resolve) => server.close(resolve));
});

test('healthz confirms the key is loaded without revealing it', async () => {
  const response = await request(port, { path: '/healthz' });

  assert.equal(response.status, 200);
  assert.equal(JSON.parse(response.body).status, 'ok');
  assert.ok(!response.body.includes(TEST_KEY), 'health output must not contain the key');
});

test('a successful generation returns word data and no credential', async () => {
  const response = await request(port, {
    method: 'POST',
    path: '/v1/word',
    body: JSON.stringify({ query: '水' }),
  });

  assert.equal(response.status, 200, `expected 200, got ${response.status}: ${response.body}`);
  assert.ok(!response.body.includes(TEST_KEY), 'THE KEY LEAKED INTO THE RESPONSE');

  const parsed = JSON.parse(response.body);
  const modelText = parsed.candidates[0].content.parts[0].text;
  assert.equal(JSON.parse(modelText).hanzi, '水', 'the payload must be passed through verbatim');
});

test('the key is sent upstream, which is the only place it appears', async () => {
  assert.ok(upstreamCalls.length > 0, 'the previous test should have produced an upstream call');
  assert.ok(
    upstreamCalls.every((c) => c.options.headers['x-goog-api-key'] === TEST_KEY),
    'the server authenticates to Gemini on every call'
  );
});

test('an upstream error that echoes the key has it redacted before reaching the client', async () => {
  const realFetch = globalThis.fetch;
  globalThis.fetch = async () =>
    new Response(JSON.stringify({ error: { message: `bad key: ${TEST_KEY}` } }), {
      status: 400,
      headers: { 'content-type': 'application/json' },
    });

  try {
    const response = await request(port, {
      method: 'POST',
      path: '/v1/word',
      body: JSON.stringify({ query: '火' }),
    });

    assert.ok(!response.body.includes(TEST_KEY), 'THE KEY LEAKED THROUGH AN ERROR BODY');
    assert.ok(response.body.includes('[redacted]'), 'the redaction marker should be visible instead');
    assert.equal(response.status, 400, 'the upstream status must still reach the client');
  } finally {
    globalThis.fetch = realFetch;
  }
});

test('a malformed request is rejected without touching Gemini', async () => {
  const before = upstreamCalls.length;
  const response = await request(port, {
    method: 'POST',
    path: '/v1/word',
    body: JSON.stringify({ notAQuery: true }),
  });

  assert.equal(response.status, 400);
  assert.ok(!response.body.includes(TEST_KEY));
  assert.equal(
    upstreamCalls.length,
    before,
    'a rejected request must not spend a token'
  );
});

test('GET /v1/word is refused', async () => {
  const response = await request(port, { path: '/v1/word' });

  assert.equal(response.status, 405);
  assert.equal(response.headers.allow, 'POST');
});

test('an unknown path is a 404, not a proxy to anywhere', async () => {
  const response = await request(port, { path: '/anything-else' });
  assert.equal(response.status, 404);
});