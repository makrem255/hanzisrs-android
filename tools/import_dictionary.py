"""
Import script for HSK 1 dictionary from CC-CEDICT + HSK 3.0 word list.

Usage:
    python tools/import_dictionary.py

Sources:
    - CC-CEDICT (https://www.mdbg.net/chinese/dictionary?page=cc-cedict)
      License: CC BY-SA 4.0
    - HSK 3.0 vocabulary (https://github.com/ivankra/hsk30)
      License: MIT

Transformations:
    - Filter HSK 3.0 words to HSK level 1
    - Cross-reference with CC-CEDICT for definitions and numbered Pinyin
    - Convert numbered Pinyin to tone-marked Pinyin
    - Extract first 4 definitions, join with "; "
    - Deduplicate by simplified Hanzi (first occurrence wins)
    - Sort by Hanzi for deterministic output
    - Set HSK level from HSK 3.0 word list
    - Leave radical, stroke, example fields empty (not fabricated)

Output:
    app/src/main/assets/dictionary/pilot.json
"""
import csv
import gzip
import json
import re
import os
import sys

# --- Tone mark conversion (mirrors PinyinConverter.kt) ---

TONE_MARKS = {
    'a': ['a', '\u0101', '\u00e1', '\u01ce', '\u00e0'],
    'o': ['o', '\u014d', '\u00f3', '\u01d2', '\u00f2'],
    'e': ['e', '\u0113', '\u00e9', '\u011b', '\u00e8'],
    'i': ['i', '\u012b', '\u00ed', '\u01d0', '\u00ec'],
    'u': ['u', '\u016b', '\u00fa', '\u01d4', '\u00f9'],
    '\u00fc': ['\u00fc', '\u01d6', '\u01d8', '\u01da', '\u01dc'],
}

def tone_mark_target(base):
    if 'a' in base:
        return 'a'
    if 'o' in base:
        return 'o'
    if 'e' in base:
        return 'e'
    if base.endswith('iu'):
        return 'u'
    if base.endswith('ui'):
        return 'i'
    for c in reversed(base):
        if c in 'iu\u00fc':
            return c
    return None

def convert_syllable(syllable):
    m = re.match(r'^(.*?)([0-5])$', syllable)
    if not m:
        return None
    base = m.group(1)
    tone = int(m.group(2))
    if not base:
        return None

    normalized = base.replace('v', '\u00fc').replace('V', '\u00fc').replace('u:', '\u00fc').replace('U:', '\u00fc')

    if not all(c.isalpha() or c == '\u00fc' for c in normalized):
        return None

    lower = normalized.lower()

    if tone == 0 or tone == 5:
        return lower

    target = tone_mark_target(lower)
    if target is None:
        return lower

    idx = lower.find(target)
    if idx < 0:
        return lower

    marked = TONE_MARKS.get(lower[idx])
    if marked is None or tone < 1 or tone > 4:
        return lower
    chars = list(lower)
    chars[idx] = marked[tone]
    return ''.join(chars)

def from_numbered(numbered):
    trimmed = numbered.strip()
    if not trimmed:
        return ''
    syllables = trimmed.split()
    converted = []
    for s in syllables:
        result = convert_syllable(s)
        if result is None:
            return trimmed
        converted.append(result)
    return ''.join(converted)

# --- Parse CC-CEDICT ---

def load_cedict(path):
    cedict = {}
    with gzip.open(path, 'rt', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            m = re.match(r'^(\S+)\s+(\S+)\s+\[([^\]]+)\]\s+/(.+)/\s*$', line)
            if not m:
                continue
            trad, simp, pinyin, defn = m.groups()
            key = simp
            if key not in cedict:
                cedict[key] = []
            cedict[key].append((pinyin.strip().lower(), defn))
    return cedict

# --- Main import ---

def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.dirname(script_dir)
    assets_dir = os.path.join(project_root, 'app', 'src', 'main', 'assets', 'dictionary')

    # Paths to source files (downloaded separately)
    cedict_path = os.path.join(script_dir, 'cedict_1_0_ts_utf-8_mdbg.txt.gz')
    hsk_path = os.path.join(script_dir, 'hsk30-expanded.csv')

    if not os.path.exists(cedict_path):
        print(f'ERROR: CC-CEDICT file not found: {cedict_path}')
        print('Download from: https://www.mdbg.net/chinese/export/cedict/cedict_1_0_ts_utf-8_mdbg.txt.gz')
        sys.exit(1)
    if not os.path.exists(hsk_path):
        print(f'ERROR: HSK 3.0 file not found: {hsk_path}')
        print('Download from: https://github.com/ivankra/hsk30/blob/master/hsk30-expanded.csv')
        sys.exit(1)

    # Load CC-CEDICT
    cedict = load_cedict(cedict_path)
    print(f'CC-CEDICT entries: {sum(len(v) for v in cedict.values())} ({len(cedict)} unique forms)')

    # Parse HSK 3.0 CSV, filter to HSK 1
    hsk_words = []
    seen = set()
    with open(hsk_path, 'r', encoding='utf-8') as f:
        reader = csv.DictReader(f)
        for row in reader:
            if row['Level'] != '1':
                continue
            word = row['Simplified']
            if word in seen:
                continue
            seen.add(word)
            hsk_words.append(row)

    print(f'HSK 1 unique words: {len(hsk_words)}')

    # Cross-reference with CC-CEDICT
    entries = []
    missing = 0
    for row in hsk_words:
        word = row['Simplified']
        trad = row['Traditional']

        cedict_entries = cedict.get(word, [])
        if not cedict_entries:
            missing += 1
            continue

        # Take the first entry's definition and pinyin
        pinyin_num, defn = cedict_entries[0]

        # Convert numbered pinyin to tone-marked
        pinyin_tone = from_numbered(pinyin_num)

        # Extract first 4 definitions, join with "; "
        defs = [d.strip() for d in defn.split('/') if d.strip()]
        short_defs = []
        for d in defs[:4]:
            if len(d) > 60:
                d = d[:57] + '...'
            short_defs.append(d)
        meaning = '; '.join(short_defs)

        entries.append({
            'h': word,
            't': trad,
            'p': pinyin_tone,
            'pn': pinyin_num,
            'm': meaning,
            'l': 1,
        })

    # Sort by hanzi for deterministic output
    entries.sort(key=lambda e: e['h'])

    print(f'Matched entries: {len(entries)}')
    print(f'Missing definitions: {missing}')

    # Write output
    output_path = os.path.join(assets_dir, 'pilot.json')
    output = {
        'source': 'CC-CEDICT + HSK 3.0',
        'version': '2026-10-19',
        'license': 'CC BY-SA 4.0 (CC-CEDICT) + MIT (HSK 3.0 word list)',
        'attribution': 'CC-CEDICT (https://cc-cedict.org/) and HSK 3.0 vocabulary (ivankra/hsk30)',
        'entries': entries,
    }

    with open(output_path, 'w', encoding='utf-8') as f:
        json.dump(output, f, ensure_ascii=False, indent=2)

    print(f'Written to {output_path}')
    print(f'File size: {os.path.getsize(output_path)} bytes')

if __name__ == '__main__':
    main()
