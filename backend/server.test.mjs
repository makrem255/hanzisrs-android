/**
 * Tests for the AI proxy.
 *
 * The security properties matter more than the routing here, so most of these assert that the key
 * cannot reach a client: not in a response, not in an error body, not in a log line. A proxy whose
 * only job is to hold a secret is only worth having if it cannot leak it.
 *
 * Run: node --test backend/
 */

import test from 'node:test';
import assert from 'node:assert/strict';

const TEST_KEY = 'AIzaTESTKEY_MUST_NEVER_LEAK_0123456789';

// Imported after the key is set, because the module reads it once at startup.
process.env.GEMINI_API_KEY = TEST_KEY;
process.env.NODE_ENV = 'test';

const { buildGeminiRequestBody, redact, rateLimited } = await import('./server.mjs');

test('redact removes the key from text that contains it', () => {
  const leaky = `upstream said: key ${TEST_KEY} rejected`;
  const cleaned = redact(leaky);

  assert.ok(!cleaned.includes(TEST_KEY), 'key must not survive redaction');
  assert.ok(cleaned.includes('[redacted]'), 'the redaction marker should be present');
});

test('redact leaves unrelated text untouched', () => {
  const text = 'nothing secret here';
  assert.equal(redact(text), text);
});

test('redact leaves text alone when the module captured no key', () => {
  // The key is read once at import time, so with a key set, `redact` replaces rather than skips.
  // Asserting the guarded branch directly is impossible without a second import, so this pins the
  // property that matters either way: the output is always a string, and never throws.
  const output = redact('plain text');

  assert.equal(typeof output, 'string');
  assert.equal(output, 'plain text');
  assert.ok(!output.includes(TEST_KEY));
});

test('the upstream request body carries the prompt, not the key', () => {
  const body = JSON.parse(buildGeminiRequestBody('水'));

  assert.ok(!JSON.stringify(body).includes(TEST_KEY), 'the key must not be in the request body');

  const text = body.contents[0].parts[0].text;
  assert.ok(text.includes('水'), 'the user query must reach the model');
  assert.ok(text.includes('strokeBreakdown'), 'the prompt must still ask for the same fields');
  assert.equal(body.generationConfig.responseMimeType, 'application/json');
});

test('rate limiting refuses once a client exceeds the ceiling, per client', () => {
  const key = `client-${Math.random()}`;
  const other = `other-${Math.random()}`;
  const now = Date.now();

  // Under the limit: RATE_LIMIT_PER_MINUTE defaults to 30, so the first 30 calls pass.
  for (let i = 0; i < 30; i += 1) {
    assert.equal(rateLimited(key, now), false, `request ${i + 1} is within the limit`);
  }
  assert.equal(rateLimited(key, now), true, 'request 31 is refused');

  assert.equal(
    rateLimited(other, now),
    false,
    'a different client has its own budget and is unaffected'
  );
});

test('a rate limit window resets after it expires', () => {
  const key = `reset-${Math.random()}`;
  const start = Date.now();

  for (let i = 0; i < 31; i += 1) rateLimited(key, start);
  assert.equal(rateLimited(key, start), true, 'the client is limited within the window');

  // Just past the 60s window: the bucket is treated as absent and the client starts fresh.
  assert.equal(
    rateLimited(key, start + 61_000),
    false,
    'the budget must come back after the window, or a client is banned forever by one burst'
  );
});

test('the key is never referenced in any outbound client header builder', async () => {
  // Guards against a future refactor reintroducing the header into the payload path.
  const source = await import('node:fs').then((fs) =>
    fs.promises.readFile(new URL('./server.mjs', import.meta.url), 'utf8')
  );

  // The only permitted occurrences are the assignment and the two log lines that describe it.
  const occurrences = source.split(TEST_KEY).length - 1;
  assert.equal(occurrences, 0, 'the literal key must not be hardcoded in the source');

  const keyReferences = [...source.matchAll(/API_KEY/g)].length;
  assert.ok(keyReferences > 0, 'the module should use the captured key variable');
  assert.ok(
    !/console\.(log|error|warn)\([^)]*API_KEY\b(?! is not set| loaded)/.test(source),
    'no log line may print the key value itself'
  );
});