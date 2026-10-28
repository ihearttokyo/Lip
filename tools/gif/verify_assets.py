"""Verify exact license-cleared assets, attribution and decoded frame identity; optionally the APK."""
import pathlib,json,hashlib,io,argparse,zipfile
from PIL import Image
root=pathlib.Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser();p.add_argument('--apk');args=p.parse_args()
m=json.loads((root/'third_party/gif/manifest.json').read_text());assert len(m['items'])==12
apk=zipfile.ZipFile(args.apk) if args.apk else None
for item in m['items']:
 original=(root/f'third_party/gif/originals/{item["id"]}.gif').read_bytes()
 packed=(root/f'app/src/main/assets/gif/{item["id"]}.gif').read_bytes()
 assert len(original)==item['source_bytes'] and hashlib.sha256(original).hexdigest()==item['source_sha256']
 assert len(packed)==item['bytes'] and hashlib.sha256(packed).hexdigest()==item['sha256']
 source=Image.open(io.BytesIO(original));out=Image.open(io.BytesIO(packed))
 assert source.size==out.size==(512,512) and source.n_frames==out.n_frames and 1<source.n_frames<=100
 for i in range(source.n_frames):
  source.seek(i);out.seek(i)
  assert source.info.get('duration')==out.info.get('duration') and source.convert('RGBA').tobytes()==out.convert('RGBA').tobytes()
 assert b'CC BY 4.0' in packed and b'Visual frames unchanged' in packed
 if apk:assert apk.read(f'assets/gif/{item["id"]}.gif')==packed
if apk:
 for archive,path in [('gif-NOTICE.md','third_party/gif/NOTICE.md'),('gif-CC-BY-4.0.txt','third_party/gif/CC-BY-4.0.txt'),('gif-manifest.json','third_party/gif/manifest.json')]:
  assert apk.read('assets/'+archive)==(root/path).read_bytes()
print('12 pinned GIFs, decoded visual-frame identity and portable attribution verified'+('; APK bytes/notices exact' if apk else '')+'; not editor/product acceptance')
