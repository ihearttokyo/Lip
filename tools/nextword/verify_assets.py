"""Verify deterministic public derivative, bundled notices, optional exact APK contents."""
import argparse,hashlib,pathlib,zipfile
from build_english import ROOT,BASE,build
p=argparse.ArgumentParser();p.add_argument('--apk');args=p.parse_args()
data,metadata=build();asset=ROOT/'app/src/main/assets/nextword/en.tsv';assert asset.read_bytes()==data
files={'assets/nextword/en.tsv':asset}
for name in ('NOTICE.md','CC-BY-4.0.txt','CC-BY-2.0-FR.txt','upstream-LICENSE','source.json','english-manifest.json'):
 files['assets/nextword-licenses/'+name]=BASE/name
if args.apk:
 with zipfile.ZipFile(args.apk) as z:
  for name,source in files.items():assert z.read(name)==source.read_bytes(),name
print('Verified',len(files),'exact next-word assets/notices;',metadata['shipped_rows'],'public count rows; not quality/device acceptance')
