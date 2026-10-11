# Stroke-order data: bundling more characters

The writing guide animates real stroke paths from bundled JSON in
`app/src/main/assets/strokes/`. This note is the whole procedure for extending it.

## Adding a character

1. Fetch its data from the verified source:
   `https://cdn.jsdelivr.net/npm/hanzi-writer-data@latest/<url-encoded char>.json`
2. Validate before committing — same checks the app enforces at load:
   - `strokes` and `medians` arrays exist with equal, non-zero length;
   - every stroke string starts with `M` and uses only `M L Q C Z` commands;
   - every median has at least 2 `[x, y]` points.
3. Save it as `app/src/main/assets/strokes/<lowercase-hex-codepoint>.json`
   (e.g. 你 → `4f60.json`). ASCII filenames only: `res/` forbids CJK names and
   this keeps `assets/` consistent.
4. Add the character to `SUPPORTED_STROKE_CHARACTERS` in
   `app/src/main/java/com/example/data/strokes/StrokeGeometry.kt`.
5. Extend `StrokeGeometryTest.every promised character loads…` with the
   character and its verified stroke count.

There is deliberately no downloader in the app: bundled data works fully
offline, and fetching at runtime would trade that for network failures,
unverifiable payloads, and quota abuse of someone else's CDN.

## License and attribution

The data comes from [hanzi-writer-data](https://github.com/chanind/hanzi-writer-data)
(Make Me a Hanzi project), which is **not MIT-licensed**. It is distributed under
the **Arphic Public License**; the full text ships at
`app/src/main/assets/strokes/ARPHICPL.TXT`, and the writing tab shows a credit
line naming Make Me a Hanzi and the ARPHICPL. Do not remove either without
replacing them with an equivalent compliant attribution, and do not mix in
stroke data from sources with incompatible licenses.
