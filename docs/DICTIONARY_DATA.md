# Dictionary Data

## Source

- **Dictionary:** CC-CEDICT (MDBG)
- **Source URL:** https://www.mdbg.net/chinese/dictionary?page=cc-cedict
- **License:** Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0)
- **License URL:** https://creativecommons.org/licenses/by-sa/4.0/
- **Source version:** 2026-10-19 05:01:26 GMT (125,301 entries)
- **Pilot entries verified:** 2026-10-19 via MDBG dictionary lookup

## Pilot dataset

6 common HSK 1 words, verified against live CC-CEDICT entries:

| Hanzi | Traditional | Numbered Pinyin | Tone-marked Pinyin | Definition | HSK |
|-------|-----------|-----------------|-------------------|------------|-----|
| 妈妈 | 媽媽 | ma1 ma5 | māma | mama; mommy; mother | 1 |
| 你好 | 你好 | ni3 hao3 | nǐhǎo | hello; hi | 1 |
| 老师 | 老師 | lao3 shi1 | lǎoshī | teacher | 1 |
| 学校 | 學校 | xue2 xiao4 | xuéxiào | school | 1 |
| 学生 | 學生 | xue2 sheng5 | xuéshēng | student; schoolchild | 1 |
| 中国 | 中國 | Zhong1 guo2 | zhōngguó | China | 1 |

## Transformations applied

1. Filtered to 6 common HSK 1 words
2. Numbered pinyin (e.g., "ma1 ma5") converted to tone-marked pinyin (e.g., "māma")
3. Spaces removed between syllables to match app display convention
4. Uppercase initials normalized to lowercase
5. Definitions: "/" separators replaced with "; "
6. HSK level taken from CC-CEDICT's own tagging
7. No radical, stroke, example, or verification data added (left empty/unknown)

## License notes

- CC BY-SA 4.0 requires attribution and share-alike for the dictionary data
- The dictionary is distributed as a separable asset (`assets/dictionary/`)
- The application code is licensed separately
- Attribution file: `assets/dictionary/ATTRIBUTION.txt`
- In-app attribution: Library screen footer or About section
- **Unresolved legal question:** Whether bundling as an app asset constitutes "distribution" under CC BY-SA. Legal review recommended before scaling.