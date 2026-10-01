/**
 * HanziSRS AI proxy.
 *
 * The Android client cannot hold the Gemini API key. Anything shipped in an APK is public:
 * `BuildConfig` fields are compiled into `classes.dex` as string constants and are recoverable
 * by anyone who unzips the app. So the client calls *this* server, and this server calls Gemini
 * with a key that only ever exists in the server's environment.
 *
 * Deliberately dependency-free - Node's built-in `http` and `fetch` are enough, and a proxy that
 * holds a paid API key should not be an npm-install attack surface.
 *
 * Run:
 *   GEMINI_API_KEY=... node server.mjs
 *
 * Contract with the app:
 *   POST /v1/word   { "query": "水" }   ->  200 with the Gemini `generateContent` body, verbatim.
 *   Anything else                        ->  502/503 with { "error": "..." }.
 *
 * The response is passed through unmodified on purpose. The client already parses the Gemini
 * envelope, strips markdown fences, and refuses to invent a radical or a stroke count for fields
 * the model omitted. Doing that work in one place, in code that is already tested, is better than
 * reimplementing it here and having two parsers drift apart.
 */

import http from 'node:http';

const PORT = Number(process.env.PORT ?? 8080);
const MODEL_ID = process.env.GEMINI_MODEL ?? 'gemini-3.5-flash';
const GEMINI_BASE_URL = 'https://generativelanguage.googleapis.com/v1beta';

// Requests per minute per client address. The key is the only thing being protected, and an
// unmetered endpoint holding one is an invitation to drain it. This is a ceiling, not an
// identity system - see the honesty note in README.md.
const RATE_LIMIT_PER_MINUTE = Number(process.env.RATE_LIMIT_PER_MINUTE ?? 30);

/** Read once at startup. Never logged, never echoed, never returned to a client. */
const API_KEY = process.env.GEMINI_API_KEY ?? '';

/** @type {Map<string, {count: number, resetAt: number}>} */
const rateLimitBuckets = new Map();

function clientKey(req) {
  // `x-forwarded-for` is only trustworthy behind a proxy that overwrites it, which is why
  // TRUST_PROXY is an explicit opt-in rather than the default.
  if (process.env.TRUST_PROXY === 'true') {
    const forwarded = req.headers['x-forwarded-for'];
    if (typeof forwarded === 'string' && forwarded.length > 0) {
      return forwarded.split(',')[0].trim();
    }
  }
  return req.socket.remoteAddress ?? 'unknown';
}

function rateLimited(key, now) {
  const bucket = rateLimitBuckets.get(key);
  if (!bucket || now > bucket.resetAt) {
    rateLimitBuckets.set(key, { count: 1, resetAt: now + 60_000 });
    return false;
  }
  bucket.count += 1;
  return bucket.count > RATE_LIMIT_PER_MINUTE;
}

// Without this a long-running server accumulates one entry per address it has ever seen.
setInterval(() => {
  const now = Date.now();
  for (const [key, bucket] of rateLimitBuckets) {
    if (now > bucket.resetAt) rateLimitBuckets.delete(key);
  }
}, 60_000).unref();

function sendJson(res, status, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(body),
    'cache-control': 'no-store',
  });
  res.end(body);
}

/**
 * Belt and braces against the one way this server could still leak the key: an upstream echo.
 * If Google ever returned the credential in an error body, forwarding it verbatim would hand it
 * to the app and from there to anyone with the APK.
 */
function redact(text) {
  if (!API_KEY) return text;
  return text.split(API_KEY).join('[redacted]');
}

function readBody(req, limitBytes = 8 * 1024) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > limitBytes) {
        reject(new Error('payload too large'));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

/**
 * The same prompt the client used to send, verbatim.
 *
 * Kept here because the prompt is now a server concern: the client sends a query, not a prompt.
 * The wording is unchanged so word data already produced by the old direct call keeps parsing.
 */
const SYSTEM_PROMPT = `You are an expert Chinese linguist and educator. Analyze the provided Chinese character (Hanzi) or Pinyin input.
Return ONLY a valid, single JSON object with the following fields:
- hanzi: Chinese character(s) in Simplified Chinese.
- pinyin: Pinyin with correct tone marks (e.g. "xuéxí", "hǎo").
- meaning: Concise English translation and grammatical function.
- hskLevel: Integer from 1 to 6 (default 1).
- radical: Radical with meaning (e.g. "子 (child)").
- exampleCn: Natural, contextual example sentence in Simplified Chinese.
- examplePy: Pinyin for the example sentence.
- exampleEn: English translation for the example sentence.
- strokeCount: Integer number of strokes for the primary character.
- strokeBreakdown: Comma-separated list of stroke names with tone/direction (e.g. "点 (Diǎn), 横折 (Héng Zhé), 竖 (Shù)").
Do NOT wrap the JSON in Markdown code fences if possible, or provide standard raw JSON.`;

function buildGeminiRequestBody(query) {
  return JSON.stringify({
    contents: [
      { role: 'user', parts: [{ text: `${SYSTEM_PROMPT}\n\nUser Input: ${query}` }] },
    ],
    generationConfig: { temperature: 0.3, responseMimeType: 'application/json' },
  });
}

async function handleGenerateWord(req, res) {
  if (!API_KEY) {
    // Deliberately does not say which variable is missing. Naming it is harmless here, but the
    // message reaches whoever is probing the endpoint, and a generic message costs the operator
    // nothing because the server logs the real reason at startup.
    return sendJson(res, 503, {
      error: 'AI service is not configured on this server.',
    });
  }

  let query;
  try {
    const raw = await readBody(req);
    const parsed = JSON.parse(raw || '{}');
    query = typeof parsed.query === 'string' ? parsed.query.trim() : '';
  } catch {
    return sendJson(res, 400, { error: 'Request body must be JSON with a "query" string.' });
  }

  if (query.length === 0) {
    return sendJson(res, 400, { error: 'A non-empty "query" is required.' });
  }
  // Mirrors the client's own MAX_PINYIN_LENGTH guard, so an oversized request is refused here
  // rather than spending a token on it.
  if (query.length > 64) {
    return sendJson(res, 400, { error: 'Query is too long.' });
  }

  let upstream;
  try {
    upstream = await fetch(
      `${GEMINI_BASE_URL}/models/${MODEL_ID}:generateContent`,
      {
        method: 'POST',
        headers: {
          'content-type': 'application/json',
          'x-goog-api-key': API_KEY,
        },
        body: buildGeminiRequestBody(query),
        signal: AbortSignal.timeout(30_000),
      },
    );
  } catch (cause) {
    console.error('[ai-proxy] upstream request failed:', cause?.message ?? cause);
    return sendJson(res, 502, { error: 'The AI service could not be reached.' });
  }

  const text = redact(await upstream.text());

  if (!upstream.ok) {
    // Upstream status is passed through so the client can still distinguish a rejected key from a
    // quota problem from a bad model name - `AiFailure.Api` reads the status code.
    console.warn(`[ai-proxy] gemini returned ${upstream.status}`);
    return sendJson(res, upstream.status, {
      error: text || `The AI service returned HTTP ${upstream.status}.`,
    });
  }

  res.writeHead(200, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(text),
    'cache-control': 'no-store',
  });
  res.end(text);
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`);

  if (req.method === 'GET' && url.pathname === '/healthz') {
    // Reports whether the key is present, never anything about its value.
    return sendJson(res, API_KEY ? 200 : 503, {
      status: API_KEY ? 'ok' : 'missing_gemini_api_key',
    });
  }

  if (url.pathname === '/v1/word') {
    if (req.method !== 'POST') {
      res.setHeader('allow', 'POST');
      return sendJson(res, 405, { error: 'Use POST for /v1/word.' });
    }
    if (rateLimited(clientKey(req), Date.now())) {
      return sendJson(res, 429, { error: 'Too many requests. Try again shortly.' });
    }
    return handleGenerateWord(req, res).catch((error) => {
      console.error('[ai-proxy] unhandled failure:', error?.message ?? error);
      if (!res.headersSent) sendJson(res, 500, { error: 'Internal error.' });
    });
  }

  sendJson(res, 404, { error: 'Not found.' });
});

if (process.env.NODE_ENV !== 'test') {
  server.listen(PORT, () => {
    // Startup line states whether the key loaded. It does not print the key, and there is no code
    // path in this file that can.
    console.log(`[ai-proxy] listening on :${PORT} (model=${MODEL_ID})`);
    if (!API_KEY) {
      console.warn('[ai-proxy] GEMINI_API_KEY is not set - /v1/word will return 503.');
    } else {
      console.log('[ai-proxy] GEMINI_API_KEY loaded from environment.');
    }
  });
}

export { server, buildGeminiRequestBody, redact, rateLimited, readBody };