"""Deterministic, offline CLDR49 + Unicode17 fully-qualified emoji join. No network or DTD resolution."""
from pathlib import Path
import argparse,hashlib,json,re,unicodedata,xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[2]
SOURCE=ROOT/'third_party/emoji'

def sha(data):return hashlib.sha256(data).hexdigest()
def annotations(raw):
    if '<!ENTITY' in raw:raise ValueError('Entities are forbidden')
    tree=ET.fromstring(raw)
    result={}
    for node in tree.findall('./annotations/annotation'):
        if set(node.attrib) not in ({'cp'},{'cp','type'}) or node.get('type') not in (None,'tts'):raise ValueError('Unknown annotation form')
        key=(node.attrib['cp'].replace('\ufe0f',''),node.get('type','keywords'))
        if key in result:raise ValueError('Duplicate normalized annotation')
        text=(node.text or '').strip()
        if not text or any(c in text for c in '\t\r\n'):raise ValueError('Invalid annotation text')
        result[key]=text
    return result

def qualified(raw):
    category=None;subgroup=None;result=[];seen=set();categories=[]
    for line in raw.splitlines():
        if line.startswith('# group: '):
            category=line.removeprefix('# group: ');continue
        if line.startswith('# subgroup: '):subgroup=line.removeprefix('# subgroup: ');continue
        if not line or line.startswith('#'):continue
        match=re.fullmatch(r'([0-9A-F ]+)\s*;\s*(fully-qualified|minimally-qualified|unqualified|component)\s*# (.+)',line)
        if not match:raise ValueError('Unknown emoji input row')
        if match[2]!='fully-qualified':continue
        if category is None or subgroup is None:raise ValueError('Missing group')
        if category not in categories:categories.append(category)
        sequence=''.join(chr(int(c,16)) for c in match[1].split())
        key=sequence.replace('\ufe0f','')
        if key in seen:raise ValueError('Duplicate normalized qualified sequence')
        seen.add(key);result.append((sequence,categories.index(category),subgroup))
    return result,categories

def build():
    manifest=json.loads((SOURCE/'source-manifest.json').read_text())
    for item in manifest['sources']:
        data=(SOURCE/item['file']).read_bytes()
        if len(data)!=item['bytes'] or sha(data)!=item['sha256']:raise ValueError('Source identity drift: '+item['file'])
    primary=annotations((SOURCE/'source/cldr-en.xml').read_text())
    derived=annotations((SOURCE/'source/cldr-derived-en.xml').read_text())
    for key,value in derived.items():
        if key in primary and primary[key]!=value:raise ValueError('Contradictory primary/derived annotation')
    names={**derived,**primary}
    rows,categories=qualified((SOURCE/'source/emoji-test.txt').read_text())
    output=[]
    for sequence,category,subgroup in rows:
        key=sequence.replace('\ufe0f','')
        title=names.get((key,'tts'))
        if title is None:raise ValueError('Qualified sequence lacks CLDR name: '+sequence)
        terms={title,*[x.strip() for x in names.get((key,'keywords'),'').split('|') if x.strip()]}
        output.append('\t'.join(('_'.join(f'{ord(c):x}' for c in sequence),sequence,title,str(category),subgroup,'|'.join(sorted(terms)))))
    data=('\n'.join(output)+'\n').encode()
    return data,{'version':1,'cldr_release':manifest['cldr_release'],'cldr_commit':manifest['cldr_commit'],'emoji_version':'17.0','rows':len(rows),'categories':categories,'bytes':len(data),'sha256':sha(data),'qualification':'fully-qualified only; component/nonqualified rows omitted; device font availability separate','join':'remove FE0F for annotation lookup only; emitted sequences unchanged from fully-qualified source','source_manifest_sha256':sha((SOURCE/'source-manifest.json').read_bytes())}

def normalize(value):
    value=''.join(c for c in unicodedata.normalize('NFKD',value) if not unicodedata.category(c).startswith('M')).lower()
    return ' '.join(re.sub('[^a-z0-9]+',' ',value).split())

def indexed(data):
    rows=[]
    for line in data.decode().splitlines():
        fields=line.split('\t');phrases=sorted({normalize(p) for p in fields[5].split('|') if normalize(p)})
        words=sorted({w for phrase in phrases for w in phrase.split()})
        rows.append('\t'.join([*fields,normalize(fields[2]),'|'.join(phrases),' '.join(words)]))
    return ('\n'.join(rows)+'\n').encode()

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--write',action='store_true');args=parser.parse_args()
    data,metadata=build();prepared=indexed(data);metadata['indexed']={'bytes':len(prepared),'sha256':sha(prepared),'format':'original six columns + normalized title, sorted normalized phrases, sorted unique words; exact JVM/Android index equality required'};asset=ROOT/'app/src/main/assets/emoji/english.tsv';out=SOURCE/'english-manifest.json'
    if args.write:
        asset.parent.mkdir(parents=True,exist_ok=True);asset.write_bytes(data);asset.with_name('english-indexed.tsv').write_bytes(prepared);out.write_text(json.dumps(metadata,indent=2,ensure_ascii=False)+'\n')
    else:
        assert asset.read_bytes()==data;assert asset.with_name('english-indexed.tsv').read_bytes()==prepared;assert json.loads(out.read_text())==metadata
    print(json.dumps(metadata,ensure_ascii=False))
