"""Exact asset/attribution identity checks, including optional built APK contents."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile
ROOT=Path(__file__).resolve().parents[2]
MODEL_SHA='941272a7017229a73e07d1c0bd766757e739174db3a42fb622c26256b8c46110'
AOSP_SHA='d04394c729fc9fda050d5733d8ee11663fb54fe54d94532de968cf85c1cc42fb'
def verified(data,size,sha):
    if len(data)!=size or hashlib.sha256(data).hexdigest()!=sha:raise ValueError('Bundled artifact identity mismatch')
def verify(apk=None):
    directory=ROOT/'third_party/swipe/data-licenses';notice=json.loads((directory/'manifest.json').read_text())
    dictionary=json.loads((ROOT/'third_party/swipe/english-data-manifest.json').read_text())
    if notice['files']['AOSP-NOTICE.txt']['sha256']!=AOSP_SHA:raise ValueError('AOSP attribution pin changed')
    items={'assets/swipe/en.tsv':(ROOT/'app/src/main/assets/swipe/en.tsv',dictionary['bytes'],dictionary['sha256']),
           'assets/swipe/swipe-model.bin':(ROOT/'app/src/main/assets/swipe/swipe-model.bin',2115260,MODEL_SHA)}
    for name,pin in notice['files'].items():items['assets/swipe-licenses/'+name]=(directory/name,pin['bytes'],pin['sha256'])
    main_notice=ROOT/'NOTICE.md'
    items['assets/NOTICE.md']=(main_notice,main_notice.stat().st_size,hashlib.sha256(main_notice.read_bytes()).hexdigest())
    for _,(path,size,sha) in items.items():verified(path.read_bytes(),size,sha)
    if apk:
        with zipfile.ZipFile(apk) as archive:
            for name,(path,size,sha) in items.items():
                if archive.getinfo(name).file_size!=size:raise ValueError('APK asset size mismatch')
                data=archive.read(name);verified(data,size,sha)
                if data!=path.read_bytes():raise ValueError('APK attribution differs from source')
    return len(items)
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--apk');args=parser.parse_args()
    print(f'Verified {verify(args.apk)} exact public assets/notices; not product quality acceptance')
