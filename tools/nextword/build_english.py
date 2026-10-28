#!/usr/bin/env python3
"""Deterministic derivative of pinned public n-gram counts; no network or private input."""
import gzip,hashlib,json,pathlib,re,collections
ROOT=pathlib.Path(__file__).resolve().parents[2]
BASE=ROOT/'third_party/nextword'
def sha(data):return hashlib.sha256(data).hexdigest()
def build():
    manifest=json.loads((BASE/'source.json').read_text())
    for row in manifest['files'].values():
        data=(BASE/row['file']).read_bytes();assert len(data)==row['bytes'] and sha(data)==row['sha256']
    dictionary=(ROOT/'app/src/main/assets/swipe/en.tsv').read_bytes()
    assert sha(dictionary)=='658112c11991a779917e6e0b1e28a89aeaa27f24abde1f058c8224101f565dfd'
    admitted={p[0] for line in dictionary.decode('ascii').splitlines()[1:] if len(p:=line.split('\t'))==4 and 'offensive' not in p[3].split(':')}
    word=re.compile(r"[a-z]+(?:'[a-z]+)*");groups=collections.defaultdict(list);counts={}
    for n,filename in ((2,'en_bigrams.txt.gz'),(3,'en_trigrams.txt.gz')):
        raw=gzip.decompress((BASE/filename).read_bytes());assert len(raw)<=5_000_000
        lines=raw.decode('ascii').splitlines();assert len(lines)==(150000 if n==2 else 75000)
        seen=set();kept=0
        for line in lines:
            p=line.split(' ');assert len(p)==n+1 and p[-1].isdigit() and int(p[-1])>0
            tokens=p[:-1];assert all(0<len(t)<=48 and word.fullmatch(t) for t in tokens)
            identity=tuple(tokens);assert identity not in seen;seen.add(identity)
            if all(t in admitted for t in tokens):
                groups[' '.join(tokens[:-1])].append((int(p[-1]),tokens[-1]));kept+=1
        counts[filename]={'input':len(lines),'admitted':kept}
    output=['#lip-nextword-en-v1'];shipped=0
    for context in sorted(groups):
        for count,nextword in sorted(groups[context],key=lambda x:(-x[0],x[1]))[:3]:
            output.append(f'{context}\t{nextword}\t{count}');shipped+=1
    data=('\n'.join(output)+'\n').encode('ascii')
    return data,{'format':'lip-nextword-en-v1','source_revision':manifest['revision'],'bytes':len(data),'sha256':sha(data),'source_counts':counts,'contexts':len(groups),'shipped_rows':shipped,'lexicon_sha256':sha(dictionary),'license':'CC-BY-4.0 AND CC-BY-2.0-FR; see NOTICE.md','transform':'admitted nonoffensive ASCII English lexicon intersection; top3/count-desc/lexical ties per one/two-word context; no private data'}
if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser();parser.add_argument('--check',action='store_true');args=parser.parse_args()
    data,manifest=build();target=ROOT/'app/src/main/assets/nextword/en.tsv';metadata=BASE/'english-manifest.json'
    if args.check:assert target.read_bytes()==data and json.loads(metadata.read_text())==manifest
    else:target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data);metadata.write_text(json.dumps(manifest,indent=2)+'\n')
    print(json.dumps(manifest,indent=2))
