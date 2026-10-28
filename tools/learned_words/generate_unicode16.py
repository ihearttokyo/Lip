"""Derive only required Unicode16 data from a resident CPython3.14 runtime.
No network, private input, application access or dependency/bootstrap operation.
Default checks the existing bytes; --write explicitly regenerates owned artifacts.
"""
import argparse
import hashlib
import json
from pathlib import Path
import platform
import random
import sys
import unicodedata as u

ROOT = Path(__file__).resolve().parents[2]
TABLE = ROOT / 'desktop/Sources/LipLocal/VocabularyData/unicode16.json'
VECTORS = ROOT / 'docs/contracts/learned-words/normalization-differential-v1.json'

def packed(value):
    return (json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(',', ':')) + '\n').encode()

def produce():
    if platform.python_implementation() != 'CPython' or sys.version_info[:2] != (3, 14) or u.unidata_version != '16.0.0':
        raise SystemExit('Requires resident CPython3.14 with Unicode16.0.0; no download was attempted')
    def ranges(predicate):
        result = []
        for cp in range(0x110000):
            if predicate(u.category(chr(cp))):
                if result and result[-1][1] + 1 == cp: result[-1][1] = cp
                else: result.append([cp, cp])
        return result
    fold = {}; ccc = {}; decomposition = {}; composition = {}
    for cp in range(0x110000):
        char = chr(cp)
        folded = char.casefold()
        if folded != char: fold[str(cp)] = list(map(ord, folded))
        combining = u.combining(char)
        if combining: ccc[str(cp)] = combining
        raw = u.decomposition(char)
        if raw and not raw.startswith('<'):
            parts = [int(x, 16) for x in raw.split()]
            decomposition[str(cp)] = parts
            if len(parts) == 2 and u.normalize('NFC', ''.join(map(chr, parts))) == char:
                composition[','.join(map(str, parts))] = cp
    table = dict(unicode_version='16.0.0', letter_ranges=ranges(lambda c: c.startswith('L')),
                 other_ranges=ranges(lambda c: c.startswith('C')), casefold=fold,
                 ccc=ccc, decomposition=decomposition, composition=composition)
    cases = {'', 'Straße', 'cafe\u0301', '\U00010570', '東京', '中文', 'a\u0315\u0300', '\u0344',
             '\u1100\u1161', '\u1100\u1161\u11a8', '\uac00\u11a8', 'A\u030a\u0301',
             '\u212b', '\u1f88', '\u0130', '\u001c cafe\u0301\u0085'}
    for cp in set(map(int, fold)) | set(map(int, decomposition)):
        cases.add(chr(cp))
    for pair in composition:
        cases.add(''.join(chr(int(x)) for x in pair.split(',')))
    # Include every algorithmic Hangul syllable and split decomposition.
    for cp in range(0xac00, 0xd7a4):
        cases.add(chr(cp)); cases.add(u.normalize('NFD', chr(cp)))
    rng = random.Random(160000)
    marks = list(map(int, ccc))
    for _ in range(1024):
        cases.add(chr(rng.choice([0x41, 0x61, 0x391, 0x3b1, 0x5d0, 0x1100, 0x1161])) +
                  ''.join(chr(rng.choice(marks)) for _ in range(rng.randrange(1, 6))))
    rows = [dict(input=s, nfc=u.normalize('NFC', s), fold=u.normalize('NFC', s.casefold())) for s in sorted(cases)]
    vectors = dict(unicode_version='16.0.0', oracle='resident CPython3.14 unicodedata.normalize and str.casefold; no Foundation/Swift oracle',
                   random_seed=160000, cases=rows)
    return packed(table), packed(vectors)

def main():
    p = argparse.ArgumentParser(description=__doc__); p.add_argument('--write', action='store_true'); args = p.parse_args()
    table, vectors = produce()
    for path, data in [(TABLE, table), (VECTORS, vectors)]:
        if args.write:
            path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(data)
        elif path.read_bytes() != data: raise SystemExit('Generated Unicode artifact differs; no file changed')
        print(f'{path.relative_to(ROOT)} {len(data)} bytes SHA256 {hashlib.sha256(data).hexdigest()}')

if __name__ == '__main__': main()
