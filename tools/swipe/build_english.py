"""Deterministic, hash-bound English derivative; not quality tuning or model training."""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import re
SOURCE_SHA256='70ff45c76412fb8de1a9c47b204d5982344a8a1bbce23945af7d7dc27f9e84bb'
SOURCE_BYTES=10849906
FLAGS={'abbreviation','medical','offensive','hand-added','babytalk','technical','australian','nonword','drugs'}
ROW=re.compile(r' word=(.*),f=(\d+),flags=(.*),originalFreq=(\d+)\n?')
WORD=re.compile(r"[a-z]+(?:'[a-z]+)*")

def convert(text):
    lines=text.splitlines(keepends=True)
    if not lines or not lines[0].startswith('dictionary=main:en_us,'):raise ValueError('Unknown source dictionary header')
    words={};counts=collections.Counter()
    for line in lines[1:]:
        match=ROW.fullmatch(line)
        if not match:raise ValueError('Unknown dictionary record')
        display,freq,flag,original=match.groups();frequency=int(freq)
        if not 0<=frequency<=255 or not 0<=int(original)<=255:raise ValueError('Invalid source frequency')
        flags=set(flag.split(':')) if flag else set()
        if not flags<=FLAGS:raise ValueError('Unknown source flags')
        counts['source_records']+=1;key=display.lower()
        if not WORD.fullmatch(key) or len(display)>48:counts['unsupported_spelling']+=1;continue
        path=[]
        for char in key.replace("'",''):
            if not path or path[-1]!=char:path.append(char)
        if len(path)>40:counts['unsupported_path']+=1;continue
        if 'nonword' in flags:counts['flagged_nonword']+=1;continue
        if key in words:counts['duplicate_identity']+=1
        if key not in words or frequency>words[key][0]:words[key]=(frequency,display,':'.join(sorted(flags)) or '-')
    output='#lip-swipe-english-v1\n'+''.join(f'{word}\t{freq}\t{display}\t{flags}\n' for word,(freq,display,flags) in sorted(words.items()))
    counts['entries']=len(words);counts['offensive_entries']=sum('offensive' in v[2].split(':') for v in words.values())
    return output.encode('ascii'),dict(counts)

def build(source,output,receipt):
    data=Path(source).read_bytes()
    if len(data)!=SOURCE_BYTES or hashlib.sha256(data).hexdigest()!=SOURCE_SHA256:raise ValueError('Source integrity failure')
    result,counts=convert(data.decode('utf-8'))
    Path(output).write_bytes(result)
    manifest={'format':'lip-swipe-english-v1','source_sha256':SOURCE_SHA256,'source_bytes':SOURCE_BYTES,'source_url':'https://github.com/itsdestin/swipe-keyboard/releases/download/language-files-2026-10-23-apostrophes/en_swipe.combined','source_revision':'f48099755cd1ebeb3f3a9e40f680a683ccc95b2b','bytes':len(result),'sha256':hashlib.sha256(result).hexdigest(),'counts':counts,'transform':'ASCII English/apostrophe identities, max48chars/max40collapsed-path, nonword excluded; highest frequency/first original spelling on tie; preserve flags; no quality-result-based pruning','license':'Apache-2.0 AND ODC-By-1.0 AND CC-BY-2.0-FR; retain full notices/contributors and applicable source terms'}
    Path(receipt).write_text(json.dumps(manifest,indent=2)+'\n')

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('source');parser.add_argument('output');parser.add_argument('receipt');args=parser.parse_args();build(args.source,args.output,args.receipt)
