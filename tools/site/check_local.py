"""Static local-preview integrity. Not browser/accessibility/release acceptance."""
from pathlib import Path
from html.parser import HTMLParser
import hashlib,json,re
repo=Path(__file__).resolve().parents[2];root=repo/'docs'
class Document(HTMLParser):
 def __init__(self):super().__init__();self.refs=[];self.ids=[];self.forms=0;self.headings=0;self.csp=[]
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if 'id' in a:self.ids.append(a['id'])
  if tag=='form':self.forms+=1
  if tag=='h1':self.headings+=1
  if tag in ['a','img','script','link']:
   for name in ['href','src']:
    if a.get(name):self.refs.append(a[name])
  if tag=='meta' and a.get('http-equiv','').lower()=='content-security-policy':self.csp.append(a.get('content',''))
docs={}
for name in ['index.html','privacy.html','credits.html']:
 d=Document();d.feed((root/name).read_text());docs[name]=d
 assert d.headings==1 and d.forms==0 and len(d.ids)==len(set(d.ids)),name
 assert len(d.csp)==1 and "connect-src 'none'" in d.csp[0] and "form-action 'none'" in d.csp[0],name
for name,d in docs.items():
 for ref in d.refs:
  if ref.startswith('https://'):continue # No external requests or link availability claim.
  assert not re.match(r'^(?:http:|//|javascript:|data:|mailto:)',ref),ref
  path,_,anchor=ref.partition('#');target=root/(path or name)
  assert target.is_file(),(name,ref)
  if anchor:
   assert target.name in docs and anchor in docs[target.name].ids,(name,ref)
index=(root/'index.html').read_text();script=(root/'app.js').read_text();privacy=(root/'privacy.html').read_text()
assert '/releases/download/' not in index
assert 'Development preview' in index and 'English speech only' in index and 'CPU baseline' in index
assert 'on by default' in privacy and 'off by default' in privacy
assert not re.search(r'navigator\.(?:clipboard|mediaDevices)|\bfetch\s*\(|XMLHttpRequest|localStorage|sessionStorage|indexedDB|sendBeacon|WebSocket|serviceWorker|document\.cookie|\beval\s*\(',script)
manifest=json.loads((root/'assets/screens/provenance.json').read_text())
for image in manifest:
 target=repo/image['file'];source=repo/image['source'];assert hashlib.sha256(target.read_bytes()).hexdigest()==image['sha256']==hashlib.sha256(source.read_bytes()).hexdigest()
 assert target.read_bytes().startswith(b'\x89PNG\r\n\x1a\n')
initial=['index.html','style.css','app.js','assets/mark.svg','assets/screens/keyboard.png'];size=sum((root/p).stat().st_size for p in initial)
assert size<1024*1024,size
lock=json.loads((root/'contracts/site-preview-v1.lock.json').read_text());assert hashlib.sha256((repo/lock['path']).read_bytes()).hexdigest()==lock['sha256']
print(json.dumps({'status':'PASS','pages':list(docs),'native_image_hashes':len(manifest),'initial_asset_bytes':size,'scope':'static source/link/provenance/CSP/resource checks only; no external link/browser/device/release acceptance'},indent=2))
