"""Capture only bounded public Android CPU-experiment artifacts, not acceptance."""
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import textwrap
import zipfile

MAX_BYTES = 512 * 1024 * 1024
RECEIPT_BYTES = 8 * 1024 * 1024
ABIS = ('arm64-v8a', 'x86_64')
APKS = {'main': 'app/build/outputs/apk/debug/app-debug.apk',
        'test': 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'}
PINS = {'java': '17', 'gradle': '8.13', 'sdk': '36', 'build_tools': '36.0.0',
        'ndk': '30.0.16248370', 'cmake': '4.1.2'}


def bounded(path, root):
    path = Path(path).resolve()
    if not path.is_relative_to(root) or path == root:
        raise ValueError('Artifact path escapes its assigned root')
    return path


def read_json(path, root):
    path = bounded(path, root)
    if not 0 < path.stat().st_size <= min(MAX_BYTES, 16 * 1024 * 1024):
        raise ValueError('Empty or oversized compiled metadata')
    return json.loads(path.read_text())


def databases(root):
    found = {}
    cxx = root / 'app/.cxx'
    for path in cxx.rglob('compile_commands.json'):
        parts = path.relative_to(cxx).parts
        # Use the ordinary workflow's tested exclusion; a mirror is not a build root.
        if 'tools' in parts:
            continue
        if len(parts) != 4 or parts[0] != 'RelWithDebInfo' or parts[2] not in ABIS:
            raise ValueError('Unexpected real ABI build database shape')
        abi = parts[2]
        if abi in found:
            raise ValueError('Ambiguous real ABI build database')
        found[abi] = bounded(path, root)
    if set(found) != set(ABIS):
        raise ValueError('Missing real ABI build database')
    return found


def compiled_guard(root):
    workflow = (root / '.github/workflows/android.yml').read_text()
    marker = '      - name: Verify compiled cancellation patch in both ABIs\n'
    if workflow.count(marker) != 1:
        raise ValueError('Ordinary compiled cancellation step drift')
    step = workflow.split(marker, 1)[1].split('      - ', 1)[0]
    opening, closing = "          python3 - <<'PY'\n", '          PY\n'
    if step.count(opening) != 1 or step.count(closing) != 1:
        raise ValueError('Ordinary compiled cancellation heredoc drift')
    return textwrap.dedent(step.split(opening, 1)[1].split(closing, 1)[0])


def metadata_files(root):
    files = set()
    cxx = root / 'app/.cxx'
    for marker in ('compile_commands.json', 'CMakeCache.txt'):
        for path in cxx.rglob(marker):
            parts = path.relative_to(cxx).parts
            if 'tools' in parts or len(parts) != 4 or parts[2] not in ABIS:
                continue
            build = bounded(path.parent, root)
            files.update(build / name for name in ('CMakeCache.txt', 'compile_commands.json',
                                                   'android_gradle_build.json') if (build / name).is_file())
            files.update((build / '.cmake/api/v1/reply').glob('*.json'))
    return [(path, 'metadata/' + path.relative_to(root).as_posix()) for path in sorted(files)]


def native_files(root, arm):
    builds = databases(root)
    files, mappings = [], {}
    for abi, database in builds.items():
        build = database.parent
        cache = bounded(build / 'CMakeCache.txt', root)
        if not 0 < cache.stat().st_size <= 1024 * 1024:
            raise ValueError('Empty or oversized CMake cache')
        values = {}
        for line in cache.read_text().splitlines():
            if not line or line.startswith(('#', '//')):
                continue
            match = re.fullmatch(r'([^:=]+):[^=]+=(.*)', line)
            if not match or match[1] in values:
                raise ValueError('CMake cache syntax or duplicate-key drift')
            values[match[1]] = match[2]
        output = root / 'app/build/intermediates/cxx/RelWithDebInfo' / build.parent.name / 'obj' / abi
        required = {'ANDROID_ABI': abi, 'CMAKE_ANDROID_ARCH_ABI': abi, 'ANDROID_STL': 'c++_shared',
                    'ANDROID_PLATFORM': 'android-33', 'CMAKE_SYSTEM_NAME': 'Android',
                    'CMAKE_BUILD_TYPE': 'RelWithDebInfo', 'CMAKE_HOME_DIRECTORY': str(root / 'app/src/main/cpp'),
                    'CMAKE_CACHEFILE_DIR': str(build), 'CMAKE_LIBRARY_OUTPUT_DIRECTORY': str(output),
                    'LIP_GGML_DOTPROD': 'ON' if arm == 'true' else 'OFF',
                    'BUILD_SHARED_LIBS': 'OFF', 'GGML_BACKEND_DL': 'ON', 'GGML_NATIVE': 'OFF',
                    'GGML_CPU_ALL_VARIANTS': 'ON' if abi == 'arm64-v8a' else 'OFF',
                    'GGML_CPU_ARM_ARCH': '', 'CMAKE_CACHE_MAJOR_VERSION': '4',
                    'CMAKE_CACHE_MINOR_VERSION': '1', 'CMAKE_CACHE_PATCH_VERSION': '2'}
        if any(values.get(key) != value for key, value in required.items()):
            raise ValueError('Wrong arm, ABI, backend, STL, toolchain or output cache')
        output = bounded(output, root)
        commands = read_json(database, root)
        if not isinstance(commands, list) or not commands:
            raise ValueError('Empty compilation database')
        sources = [bounded(build / row['file'], root) for row in commands]
        if (any(bounded(row['directory'], root) != build for row in commands) or
                any(source.is_relative_to(root / 'third_party/whisper.cpp/ggml') for source in sources) or
                not any(source.is_relative_to(build / 'ggml-dotprod') for source in sources)):
            raise ValueError('GGML compilation must use this generated source tree')
        reply = build / '.cmake/api/v1/reply'
        models = list(reply.glob('codemodel-v2-*.json'))
        if len(models) != 1:
            raise ValueError('Missing or ambiguous CMake codemodel')
        model = read_json(models[0], root)
        if (model.get('kind') != 'codemodel' or model.get('version', {}).get('major') != 2 or
                model.get('paths') != {'build': str(build), 'source': str(root / 'app/src/main/cpp')} or
                len(model.get('configurations', [])) != 1 or
                model['configurations'][0]['name'] != 'RelWithDebInfo'):
            raise ValueError('CMake codemodel identity drift')
        expected = {'lip_whisper', 'ggml', 'ggml-base'}
        expected |= {'ggml-cpu'} if abi == 'x86_64' else {'ggml-cpu-android_armv8.0_1'}
        if abi == 'arm64-v8a' and arm == 'true':
            expected.add('ggml-cpu-android_armv8.2_1')
        libraries = {}
        for row in model['configurations'][0]['targets']:
            name = row['name']
            target = read_json(bounded(reply / row['jsonFile'], reply.resolve()), root)
            if target['type'] not in ('SHARED_LIBRARY', 'MODULE_LIBRARY'):
                continue
            if name not in expected or name in libraries or target['name'] != name:
                raise ValueError('Unexpected or duplicate native target')
            kind = 'MODULE_LIBRARY' if name.startswith('ggml-cpu') else 'SHARED_LIBRARY'
            if target['type'] != kind or len(target.get('artifacts', [])) != 1:
                raise ValueError('Native target artifact/type drift')
            library = bounded(build / target['artifacts'][0]['path'], root)
            if library.parent != output or library.name != f'lib{name}.so':
                raise ValueError('Selected native output is not this ABI build output')
            libraries[name] = library
        if set(libraries) != expected:
            raise ValueError('Missing required native target')
        agp = read_json(build / 'android_gradle_build.json', root)
        for name, library in libraries.items():
            rows = [row for row in agp['libraries'].values() if row.get('artifactName') == name]
            if len(rows) != 1 or rows[0]['abi'] != abi or Path(rows[0]['output']) != library:
                raise ValueError('AGP selected output disagrees with CMake')
        if {bounded(p, root) for p in output.glob('lib*.so*')} - set(libraries.values()) - {output / 'libc++_shared.so'}:
            raise ValueError('Stale or unexpected native output')
        stl = root / 'app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib' / abi / 'libc++_shared.so'
        for library in [*libraries.values(), stl]:
            library = bounded(library, root)
            with library.open('rb') as stream:
                if stream.read(4) != b'\x7fELF':
                    raise ValueError('Missing or empty built ELF output')
            files.append((library, f'libraries/{abi}/{library.name}'))
        mappings[abi] = {'build_root': build.relative_to(root).as_posix(),
                         'library_output': output.relative_to(root).as_posix(),
                         'targets': sorted(expected), 'cache': required}
    return files, mappings


def digest(stream):
    result, size = hashlib.sha256(), 0
    while chunk := stream.read(1024 * 1024):
        size += len(chunk)
        if size > MAX_BYTES:
            raise ValueError('Artifact exceeds the byte bound')
        result.update(chunk)
    return result.hexdigest(), size


def apk_payload(path):
    rows, size = [], 0
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        if not entries or len(entries) > 10000 or len({p.filename for p in entries}) != len(entries):
            raise ValueError('Empty, ambiguous or excessive APK entries')
        for entry in sorted(entries, key=lambda p: p.filename):
            name = entry.filename
            if len(name.encode()) > 512 or PurePosixPath(name).is_absolute() or '..' in PurePosixPath(name).parts:
                raise ValueError('Unsafe APK entry path')
            size += entry.file_size
            if size > MAX_BYTES:
                raise ValueError('APK expanded payload exceeds the byte bound')
            if entry.is_dir() or re.fullmatch(r'META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))', name, re.I):
                continue
            with archive.open(entry) as stream:
                sha, count = digest(stream)
            rows.append({'path': name, 'sha256': sha, 'bytes': count})
    if not any(row['path'] == 'classes.dex' for row in rows):
        raise ValueError('APK has no DEX payload')
    return rows


def copy_files(files, root, destination):
    sources, names, total = [], set(), 0
    for path, name in files:
        path = bounded(path, root)
        target = bounded(destination / name, destination)
        count = path.stat().st_size
        total += count
        if not path.is_file() or count <= 0 or total > MAX_BYTES - RECEIPT_BYTES or target in names:
            raise ValueError('Missing, duplicate, empty or oversized artifact set')
        sources.append((path, target, count))
        names.add(target)
    records = []
    for source, target, count in sources:
        target.parent.mkdir(parents=True, exist_ok=True)
        sha = hashlib.sha256()
        written = 0
        with source.open('rb') as stream, target.open('xb') as output:
            while chunk := stream.read(1024 * 1024):
                written += len(chunk)
                if written > count:
                    raise ValueError('Artifact changed during capture')
                sha.update(chunk)
                output.write(chunk)
        if written != count:
            raise ValueError('Artifact changed during capture')
        records.append({'path': target.relative_to(destination).as_posix(),
                        'source_path': source.relative_to(root).as_posix(),
                        'sha256': sha.hexdigest(), 'bytes': count})
    return records, total


def save(path, record):
    data = (json.dumps(record, indent=2, sort_keys=True, allow_nan=False) + '\n').encode()
    if len(data) > RECEIPT_BYTES:
        raise ValueError('Artifact receipt exceeds its reserved byte bound')
    with path.open('xb') as stream:
        stream.write(data)


def collect(root, runner_temp, destination, arm, identity, gate):
    root, runner_temp = Path(root).resolve(), Path(runner_temp).resolve()
    destination = bounded(destination, runner_temp)
    if (root == runner_temp or root.is_relative_to(runner_temp) or runner_temp.is_relative_to(root) or
            arm not in ('false', 'true') or not re.fullmatch(r'[0-9a-f]{40}', identity['source_sha'])):
        raise ValueError('Invalid isolated capture identity or roots')
    destination.mkdir(exist_ok=False)
    record = {'schema_version': 1, **identity, 'lip_dotprod': arm, 'tool_pins': PINS,
              'acceptance': 'not_assessed', 'gate_outcome': gate}
    try:
        files, mappings = native_files(root, arm)
        payloads = {name: apk_payload(bounded(root / path, root)) for name, path in APKS.items()}
        files += [(root / path, f'apks/{name}.apk') for name, path in APKS.items()]
        files += metadata_files(root)
        if gate != 'success':
            raise ValueError('Build or compiled/source gate did not succeed')
        rows, total = copy_files(files, root, destination)
        complete = dict(record, status='complete_capture', files=rows, total_bytes=total, abis=mappings, apk_payloads=payloads)
        save(destination / 'provenance.json', complete)
        return complete
    except Exception as error:
        record.update(status='diagnostic_only', failure_kind=type(error).__name__)
        if type(error) is ValueError:
            record['failure_reason'] = str(error)
        # Preserve public metadata only; failed builds never get a complete receipt.
        if not list(destination.iterdir()):
            try:
                rows, total = copy_files(metadata_files(root), root, destination)
                record.update(files=rows, total_bytes=total)
            except (ValueError, OSError):
                record['metadata_capture'] = 'unavailable'
        save(destination / 'diagnostics.json', record)
        raise


def main():
    root = Path.cwd().resolve()
    arm, sha = os.environ['LIP_DOTPROD'], os.environ['GITHUB_SHA']
    if (os.environ['GITHUB_REPOSITORY'] != 'ihearttokyo/Lip' or
            os.environ['GITHUB_REF'] != 'refs/heads/codex/lip-dotprod-experiment' or
            os.environ['GITHUB_EVENT_NAME'] != 'push' or
            subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip() != sha or
            '[cpu-dotprod]' not in subprocess.check_output(['git', 'log', '-1', '--format=%B'], text=True)):
        raise ValueError('Require exact gated checkout SHA')
    identity = {'source_sha': sha, 'image_os': os.environ['ImageOS'], 'image_version': os.environ['ImageVersion'],
                'artifact_name': os.environ['LIP_CPU_ARTIFACT_NAME']}
    inputs = ['.github/workflows/cpu-experiment.yml', '.github/workflows/android.yml',
              'gradle/wrapper/gradle-wrapper.properties', 'app/build.gradle.kts',
              'app/src/main/cpp/CMakeLists.txt', 'app/src/main/cpp/check_wrapper.py']
    inputs += ['app/src/main/cpp/patches/' + name for name in
               ('ggml-android-dotprod.cmake', 'ggml-android-dotprod.json', 'ggml-android-dotprod.patch',
                'whisper-scheduler-abort.cmake', 'whisper-scheduler-abort.json', 'whisper-scheduler-abort.patch')]
    identity['source_inputs'] = []
    for name in inputs:
        with bounded(root / name, root).open('rb') as stream:
            hash_value, size = digest(stream)
        identity['source_inputs'].append({'path': name, 'sha256': hash_value, 'bytes': size})
    identity['ggml_source_provenance'] = read_json(root / 'app/src/main/cpp/patches/ggml-android-dotprod.json', root)
    collect(root, Path(os.environ['RUNNER_TEMP']), Path(os.environ['LIP_CPU_EVIDENCE']), arm,
            identity, os.environ['LIP_CPU_GATE_OUTCOME'])


if __name__ == '__main__':
    try:
        main()
    except Exception:
        sys.exit('CPU artifact capture failed; retained diagnostics are not acceptance')
