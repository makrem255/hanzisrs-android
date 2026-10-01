# HanziSRS AI backend

The Android app cannot hold the Gemini API key. Anything shipped in an APK is public: a Gradle
`buildConfigField` becomes a string constant in `classes.dex`, so the key that used to sit in this
project's `BuildConfig` was recoverable by anyone who downloaded and unzipped the app.

So the key lives here, in this process's environment, and never reaches the client.

```
Android app  ──POST /v1/word {query}──▶  this server  ──x-goog-api-key: $GEMINI_API_KEY──▶  Gemini
   (no key)                              (key in env)                                       (returns body)
```

The server passes Gemini's response back verbatim, so the app's existing parsing — including its
refusal to invent a radical or stroke count for fields the model omitted — is unchanged.

## Running it

```bash
cd backend
cp .env.example .env        # then put your real key in .env — it is gitignored
node --env-file=.env server.mjs
```

Node 20+ (for `--env-file`) or 18+ with the variables exported some other way. No dependencies —
a server holding a paid API key should not be an npm install attack surface.

Verify it is up:

```bash
curl http://localhost:8080/healthz     # {"status":"ok"} — or 503 if the key is missing
```

Point the app at it:

```bash
gradlew :app:assembleDebug -PHANZISRS_AI_BACKEND_URL=http://10.0.2.2:8080
```

`10.0.2.2` is the host machine as seen from the Android emulator.

## Tests

```bash
node --test backend/
```

These include the tests that matter most: that a successful response contains no credential, and
that an upstream error which *echoes* the key has it redacted before it reaches the client.

## What this does and does not protect against

**Fixed:** the Gemini credential is no longer extractable from the APK. That was the actual
vulnerability, and it is closed.

**Not fixed by this, and worth being explicit about:** the `/v1/word` endpoint is public. Anyone
can point a modified client at your deployment and spend your quota. Moving a secret server-side
stops secret theft; it does not by itself stop abuse.

What limits abuse here:

- The per-address rate limit (default 30/min). A ceiling, not an identity check.
- Query length and payload limits, so a malformed or oversized request costs nothing.
- `/healthz` reports *whether* a key is loaded, never any part of its value.

What would properly close it, and is deliberately out of scope here:

- **Android App Check** (or Play Integrity) — the client proves it is your app, and the server
  verifies the attestation before spending a token. This is the real fix for endpoint abuse and
  requires a Firebase project plus Play App Signing.
- **Per-user auth on the proxy** — verify the learner's session before generating, so the quota
  belongs to a person rather than to an IP address.

The app is local-only and single-device today (see `docs/PRODUCT_AUDIT.md`), so there is no
account system to attach per-user auth to yet. Until there is, treat this endpoint as
rate-limited-but-public and keep the key's blast radius in mind — a Google AI Studio key with a
spending cap is the cheap mitigation.

## Deployment

Any host that runs Node and gives you a secret store works. The relevant settings:

- `GEMINI_API_KEY` — set it in the platform's secret store, **not** in a committed file.
- `PORT` — most platforms inject this; Cloud Run needs `PORT=8080` and listens on `0.0.0.0`.
- `TRUST_PROXY=true` — only if your platform sets `X-Forwarded-For` itself. Otherwise clients can
  spoof it and dodge their own rate limit.

Do not enable `TRUST_PROXY` behind a proxy that merely passes the header through from the client.

## Cost note

Every `/v1/word` call is a billable Gemini call. The rate limit is per address and resets each
minute; it is not an authentication mechanism and will not stop a determined abuser. Google AI
Studio projects support a spending cap — set one.