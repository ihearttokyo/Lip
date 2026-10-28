"""Bounded one-time vendor import; never a runtime downloader. Preserve source originals."""
import hashlib,json,pathlib,urllib.request,datetime
ROOT=pathlib.Path(__file__).resolve().parents[2]
CATALOG=[('1f600','happy','Grinning face',['smile','happy','hello']),('1f602','laugh','Face with tears of joy',['laugh','lol','funny']),('1f970','love','Smiling face with hearts',['love','thanks','heart']),('1f64f','thanks','Folded hands',['thanks','please','pray']),('1f389','party','Party popper',['party','celebrate','congratulations']),('1f44b','hello','Waving hand',['hello','hi','bye']),('1f44d','yes','Thumbs up',['yes','ok','good']),('1f622','sad','Crying face',['sad','cry','sorry']),('1f914','think','Thinking face',['think','hmm','question']),('1f973','celebrate','Partying face',['party','birthday','celebrate']),('1f525','fire','Fire',['fire','great','awesome']),('1f44f','clap','Clapping hands',['clap','well done','bravo'])]
MAX_FILE=2*1024*1024;MAX_TOTAL=16*1024*1024
origin=ROOT/'third_party/gif/originals';origin.mkdir(exist_ok=True)
assets=ROOT/'app/src/main/assets/gif';assets.mkdir(exist_ok=True)
rows=[];total=0
for code,id,title,tags in CATALOG:
 url=f'https://fonts.gstatic.com/s/e/notoemoji/latest/{code}/512.gif'
 src=origin/f'{id}.gif'
 if not src.exists():
  req=urllib.request.Request(url,headers={'User-Agent':'Lip-local-asset-import/1.0'})
  with urllib.request.urlopen(req,timeout=30) as response:
   data=response.read(MAX_FILE+1)
  if len(data)>MAX_FILE:raise RuntimeError('GIF exceeds bounded import limit')
  src.write_bytes(data)
 raw=src.read_bytes();total+=len(raw)
 assert total<=MAX_TOTAL and raw.startswith(b'GIF89a') and raw.endswith(b';')
 assert int.from_bytes(raw[6:8],'little')==512 and int.from_bytes(raw[8:10],'little')==512
 attribution=f'{title} — Animated Noto Emoji by Google. CC BY 4.0 https://creativecommons.org/licenses/by/4.0/ Source: {url} Visual frames unchanged; Lip added this attribution comment.'
 value=attribution.encode();blocks=b''.join(bytes([len(value[i:i+255])])+value[i:i+255] for i in range(0,len(value),255))
 packed=raw[:-1]+b'\x21\xfe'+blocks+b'\x00;'
 (assets/f'{id}.gif').write_bytes(packed)
 rows.append({'id':id,'title':title,'tags':tags,'source_url':url,'source_sha256':hashlib.sha256(raw).hexdigest(),'source_bytes':len(raw),'bytes':len(packed),'sha256':hashlib.sha256(packed).hexdigest(),'attribution':attribution})
manifest={'schema':1,'source':'https://googlefonts.github.io/noto-emoji-files/?view=docs','license':'CC-BY-4.0','author':'Google','retrieved_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'changes':'Attribution GIF comment only; image frames unchanged','items':rows}
(ROOT/'third_party/gif/manifest.json').write_text(json.dumps(manifest,indent=2,ensure_ascii=False)+'\n')
print(f'Imported {len(rows)} originals, {total} source bytes; attribution comments added without frame edits')
