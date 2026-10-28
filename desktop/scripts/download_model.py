#!/usr/bin/env python3
"""Download only the pinned Core ML files; verify size and LFS SHA-256."""
import hashlib, json, pathlib, urllib.request, sys
manifest = json.loads((pathlib.Path(__file__).resolve().parents[1] / 'model-manifest.json').read_text())
root = pathlib.Path(sys.argv[1]).expanduser()
for entry in manifest['files']:
    name = entry['rfilename']
    target = root / name
    target.parent.mkdir(parents=True, exist_ok=True)
    def valid(path):
        if not path.is_file() or path.stat().st_size != entry['size']: return False
        expected = entry.get('lfs', {}).get('sha256')
        if not expected:
            digest = hashlib.sha1(f"blob {entry['size']}\0".encode())
            with path.open('rb') as stream:
                while block := stream.read(1024 * 1024): digest.update(block)
            return digest.hexdigest() == entry['blobId']
        with path.open('rb') as stream:
            return hashlib.file_digest(stream, 'sha256').hexdigest() == expected
    if valid(target):
        print('Verified:', name, flush=True)
        continue
    partial = target.with_name(target.name + '.partial')
    print('Downloading:', name, entry['size'], flush=True)
    url = f"https://huggingface.co/{manifest['repo']}/resolve/{manifest['revision']}/{name}"
    with urllib.request.urlopen(url, timeout=120) as response, partial.open('wb') as out:
        received = 0
        while block := response.read(1024 * 1024):
            received += len(block)
            if received > entry['size']: raise SystemExit('Download exceeded pinned size: ' + name)
            out.write(block)
    if not valid(partial): raise SystemExit('Integrity check failed: ' + name)
    partial.replace(target)
(root / 'revision.txt').write_text(manifest['revision'] + '\n')
print('Model download verified.', flush=True)
