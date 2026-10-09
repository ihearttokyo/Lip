"""Opt-in RAW EN observation for rejection, never native/Android qualification."""
from array import array
from collections.abc import Mapping
import hashlib
import importlib.metadata
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import re
import resource
import shutil
import stat
import subprocess
import sys
import sysconfig
import time
import urllib.request
import zipfile

import initial_ts_ci as ts
from benchmark import score_case, verify_audio
from controls import prepare as prepare_controls
from observe_vad import file_identity, pcm_samples
from qwen17_ci import download, run_logged
from qwen17_cli import load_json, strict_json

REF = 'refs/heads/codex/lip-moonshine-canary'
WORKFLOW_REF = 'ihearttokyo/Lip/.github/workflows/moonshine-canary.yml@' + REF
MARKER = '[moonshine-canary]'
GIB = 1024 ** 3
TEXT_BYTES = 16 * 1024 ** 2
OUTPUT_BYTES = 512 * 1024
CASE_IDS = ('fleurs-en-000', 'fleurs-en-003', 'fleurs-en-011', 'fleurs-en-013', 'fleurs-en-017', 'fleurs-en-021',
            'fleurs-en-011-minus24db-pause-before', 'fleurs-en-011-minus24db-pause-after',
            'fleurs-en-011-minus42db-pause-before', 'fleurs-en-011-minus42db-pause-after', 'silence-en', 'noise-en')
NATIVE_ENV = {'MOONSHINE_ORT_SINGLE_THREAD': '1'}
NATIVE_CONTROLS_SHA256 = '963547671946baeba8445750415269e43185d489e0f8107dc8353043144defbb'
NATIVE_ERROR = re.compile(rb'ORT Error:|\[E:onnxruntime[,:]|Failed to | failed:|Encoder window misaligned:|frontend split weight .* is not float32|Cross K/V not valid, call compute_cross_kv first|State is null|Logits output is null')
THREAD_LIMIT = 'Process thread snapshots are not a continuous hard maximum; LOG_ORT_ERROR only logs failures. No speed/threadpool/native EOS safety proof.'
QUALIFICATION = dict(quality_passed=False, native_completion_verified=False,
                     three_language_parity=False, android_verified=False, promotion_allowed=False)
BLOCKER = ('Native EOS/token exhaustion/internal sample count are unverified; C++ decode_step errors '
           'silently break. Publisher is unchanged. Android and EN/JA/ZH parity remain blocked. '
           'Raw failure rejects; raw match requires instrumented native EOF/error proof next, not adoption.')
OPTIONS = dict(vad_threshold='0', vad_max_segment_duration='0', use_speculative_decoding='false',
               word_timestamps='false', identify_speakers='false', return_audio_data='false', ort_providers='CPU')
FROZEN = {'models': '66abf7fa5cd05d3b593fcfeba286eee302caf66d70e244a5c0c9c61d8247e146',
          'wheel': '24bca3fc04dbe3acc85306855ead36ea7071443e2b13f4bd5d5294376f9e4f60',
          'publisher_python_sha256': '2ed293d6aface956452a5399aa323268caf018e328e6700605fe3e29775cb174'}


def require_host():
    expected = {'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'github-hosted', 'RUNNER_OS': 'Linux',
                'RUNNER_ARCH': 'X64', 'GITHUB_REF': REF, 'GITHUB_WORKFLOW_REF': WORKFLOW_REF,
                'GITHUB_REPOSITORY': 'ihearttokyo/Lip', 'GITHUB_EVENT_NAME': 'push', **NATIVE_ENV}
    if sys.platform != 'linux' or os.geteuid() == 0 or any(os.environ.get(k) != v for k, v in expected.items()):
        raise ValueError('Require owned unprivileged hosted Linux push before Path/tools/acquisition/native')


def require_native_controls(pins=None, env=None):
    if (not isinstance(pins, dict) or pins.get('source_revision') != '234f60faa0eb388b01cdf7e60aca232af37aefda' or
            hashlib.sha256(ts.encode(pins.get('native_controls'))).hexdigest() != NATIVE_CONTROLS_SHA256):
        raise ValueError('Missing or changed pinned ort-utils single-thread control proof')
    if not isinstance(env, Mapping) or any(env.get(k) != v for k, v in NATIVE_ENV.items()):
        raise ValueError('Require exact RAW reliability environment before native import')


def observe_threads(record, stage):
    count = len(list(Path('/proc/self/task').iterdir()))
    record(dict(event='ThreadObservation', stage=stage, threads=count, limit=3,
                continuous_thread_bound_verified=False))
    if not 0 < count <= 3: raise ValueError('Observed process thread bound violation; hold')


def verify_mapping(work, expected_lib):
    mapped = sorted({line.split()[-1] for line in Path('/proc/self/maps').read_text().splitlines()
                     if '.so' in line and line.split()[-1].startswith('/')})
    if str(expected_lib) not in mapped or any(('moonshine' in p or 'onnxruntime' in p) and
            not Path(p).resolve().is_relative_to(work / 'wheel') for p in mapped):
        raise ValueError('Native library mapping escaped owned verified wheel')
    return mapped


def require_environment(env, event, head, message, platform, uid, release):
    expected = {'GITHUB_ACTIONS': 'true', 'RUNNER_OS': 'Linux', 'RUNNER_ARCH': 'X64',
                'RUNNER_ENVIRONMENT': 'github-hosted', 'ImageOS': 'ubuntu24',
                'GITHUB_REPOSITORY': 'ihearttokyo/Lip', 'GITHUB_REPOSITORY_OWNER': 'ihearttokyo',
                'GITHUB_EVENT_NAME': 'push', 'GITHUB_REF': REF, 'GITHUB_SERVER_URL': 'https://github.com',
                'GITHUB_WORKFLOW_REF': WORKFLOW_REF, **NATIVE_ENV}
    if (platform != 'linux' or uid == 0 or release != '24.04' or
            any(env.get(k) != v for k, v in expected.items()) or
            any(not re.fullmatch(r'[1-9][0-9]*', env.get(k, '')) for k in ('GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT')) or
            not re.fullmatch(r'[0-9a-f]{40}', head) or head != env.get('GITHUB_SHA')):
        raise ValueError('Exact hosted identity mismatch')
    repo, commit = event.get('repository', {}), event.get('head_commit', {})
    if (repo.get('full_name') != 'ihearttokyo/Lip' or repo.get('private') is not False or
            repo.get('visibility') != 'public' or repo.get('owner', {}).get('login') != 'ihearttokyo' or
            event.get('ref') != REF or event.get('after') != head or commit.get('id') != head or
            not isinstance(message, str) or MARKER not in message or
            commit.get('message', '').rstrip('\n') != message.rstrip('\n')):
        raise ValueError('Public event, actual HEAD and case-sensitive opt-in mismatch')


def verify_sources(repository, pins):
    require_native_controls(pins, NATIVE_ENV)
    for name, digest in {'initial-ts-pins.json': '1f4e05b8b8d8fb7907789e6c3709ae7cd6e7cd014010dd4c47651f4fdbd6df6c',
                         'initial_ts_ci.py': '04adcd5d695b6ebdbc94cafcb1cd459fd801736a54a8ca04c828afef04c19c73'}.items():
        path = repository / 'eval' / name
        if path.is_symlink() or file_identity(path, 256 * 1024)['sha256'] != digest:
            raise ValueError('Frozen helper/source chain drift')
    original = load_json(repository / 'eval/initial-ts-pins.json'); ts.verify_sources(repository, original)
    if (pins.get('schema_version') != 1 or pins.get('source_revision') != '234f60faa0eb388b01cdf7e60aca232af37aefda' or
            pins.get('asset_revision') != '0bf2f2e5aff22e6fbba4300b00a4e00bbc4f8aae' or
            pins.get('runtime_revision') != ts.RUNTIME_REVISION or pins.get('source_sha256') != original['source_sha256'] or
            pins.get('options') != OPTIONS or pins.get('qualification') != QUALIFICATION or
            pins.get('model_arch') != 5 or pins.get('native_header_version') != 30000 or
            any(hashlib.sha256(ts.encode(pins.get(k))).hexdigest() != v for k, v in FROZEN.items())):
        raise ValueError('Frozen Moonshine source/options/artifact manifest drift')


def select_cases(corpus, quiet, controls):
    for cases, digest in [(corpus['cases'], '5e8e56d119eb74cc00ddfe8dcabf25e82297626d07f29f13901cba1353f6e28f'),
                          (controls['cases'], 'b36bbba5abad9a6c9a2ea97ecce981fcea484b55f289064d3df90ef4dc33bf2c')]:
        if hashlib.sha256(ts.encode(cases)).hexdigest() != digest: raise ValueError('Frozen case plan drift')
    ts.validate_quiet({'quiet_cases': quiet['cases']}, corpus)
    return [c for c in corpus['cases'] + quiet['cases'] + controls['cases'] if c['language'] == 'en']


def preflight():
    require_host()
    repository = Path(__file__).resolve().parent.parent
    release = dict(line.split('=', 1) for line in Path('/etc/os-release').read_text().splitlines() if '=' in line)
    head, message = ts.git(repository, 'rev-parse', 'HEAD'), ts.git(repository, 'log', '-1', '--format=%B')
    require_environment(os.environ, load_json(os.environ['GITHUB_EVENT_PATH']), head, message,
                        sys.platform, os.geteuid(), release.get('VERSION_ID', '').strip('"') if release.get('ID') == 'ubuntu' else '')
    if ts.git(repository, 'status', '--porcelain', '--untracked-files=all'):
        raise ValueError('Actual HEAD source must be clean, including recursive vendor')
    pins = load_json(repository / 'eval/moonshine-pins.json'); verify_sources(repository, pins)
    require_native_controls(pins, os.environ)
    vendor = repository / 'third_party/whisper.cpp'
    if (ts.git(vendor, 'rev-parse', 'HEAD') != ts.RUNTIME_REVISION or
            ts.git(vendor, 'status', '--porcelain', '--untracked-files=all') or
            ts.git(repository, 'ls-tree', 'HEAD', 'third_party/whisper.cpp').split()[2] != ts.RUNTIME_REVISION):
        raise ValueError('Require pristine actual306 gitlink and vendor HEAD')
    select_cases(load_json(repository / 'eval/corpus.json'),
                 {'cases': load_json(repository / 'eval/initial-ts-pins.json')['quiet_cases']}, load_json(repository / 'eval/controls.json'))
    temp = Path(os.environ['RUNNER_TEMP']).resolve(strict=True)
    if temp == Path('/') or temp.is_relative_to(repository): raise ValueError('Runner temp must be outside source')
    mem = re.search(r'^MemAvailable:\s+(\d+) kB$', Path('/proc/meminfo').read_text(), re.M)
    ts.require_capacity(min(os.cpu_count(), len(os.sched_getaffinity(0))),
                        int(mem[1]) * 1024 if mem else 0, shutil.disk_usage(temp).free)
    return repository, pins, temp


def sample_protocol(data):
    values = pcm_samples(data); original = values.tobytes(); count = len(values)
    offset = 12
    while data[offset:offset + 4] != b'data':
        size = int.from_bytes(data[offset + 4:offset + 8], 'little'); offset += 8 + size + size % 2
    size = int.from_bytes(data[offset + 4:offset + 8], 'little')
    last = next((i for i in range(count - 1, -1, -1) if values[i] != 0), None)
    values.extend(array('f', [0]) * ((-count) % 2560))
    return values, {'original_frames': count, 'padded_frames': len(values), 'zero_pad_frames': len(values) - count,
                    'last_nonzero_frame': last, 'source_wav_sha256': hashlib.sha256(data).hexdigest(),
                    'source_pcm_sha256': hashlib.sha256(data[offset + 8:offset + 8 + size]).hexdigest(),
                    'original_f32_sha256': hashlib.sha256(original).hexdigest(),
                    'padded_f32_sha256': hashlib.sha256(values.tobytes()).hexdigest(),
                    'native_sample_count_verified': False, 'protocol': 'intact original + zero LCM2560; unpaced8000 chunks'}


def validate_lines(lines):
    if (not isinstance(lines, list) or any(not isinstance(line, dict) or
            not isinstance(line.get('text'), str) or type(line.get('line_id')) is not int or
            not 0 <= line['line_id'] < 2 ** 64 for line in lines) or
            len({line['line_id'] for line in lines}) != len(lines)):
        raise ValueError('Malformed publisher raw lines')
    return lines


def snapshot(line):
    value = {k: v for k, v in vars(line).items() if k != 'audio_data'}
    for name in ('speaker_spans', 'words'):
        value[name] = [vars(item).copy() for item in (value.get(name) or [])]
    value['audio_data_count'] = len(getattr(line, 'audio_data', None) or [])
    return validate_lines([strict_json(ts.encode(value))])[0]


def feed_stream(stream, samples, record):
    failed = []; started = time.monotonic()
    def listener(event):
        try:
            error = getattr(event, 'error', None)
            value = {'event': type(event).__name__, 'elapsed_seconds': time.monotonic() - started,
                     'stream_handle': event.stream_handle, 'line': snapshot(event.line) if event.line else None}
            if error is not None:
                value.update(error_type=type(error).__name__, error=str(error), error_code=getattr(error, 'error_code', None))
                failed.append('Publisher Error event')
            record(value)
        except Exception as error: failed.append(type(error).__name__)
    def checked(value, transcript=False):
        if failed or type(value) is int and value < 0: raise ValueError('Observed publisher/listener failure')
        if transcript:
            if value is None or not isinstance(getattr(value, 'lines', None), list): raise ValueError('Missing publisher transcript')
            return validate_lines([snapshot(line) for line in value.lines])
    stream.add_listener(listener); checked(stream.start())
    for offset in range(0, len(samples), 8000):
        checked(stream.add_audio(samples[offset:offset + 8000], 16000))
        if offset + 8000 < len(samples):
            lines = checked(stream.update_transcription(), True)
            record({'event': 'Update', 'elapsed_seconds': time.monotonic() - started, 'lines': lines})
    stop_started = time.monotonic(); lines = checked(stream.stop(), True)
    stop_seconds = time.monotonic() - stop_started
    record({'event': 'Stop', 'elapsed_seconds': time.monotonic() - started, 'lines': lines, 'stop_seconds': stop_seconds})
    return dict(lines=lines, raw='\n'.join(line['text'] for line in lines), stop_seconds=stop_seconds)


def verify_artifact(pin, path):
    if path.is_symlink() or path.name != pin['name']: raise ValueError('Artifact path identity drift')
    got = file_identity(path, pin['bytes'])
    if got['bytes'] != pin['bytes']: raise ValueError('Incomplete artifact')
    if 'sha256' in pin:
        if got['sha256'] != pin['sha256']: raise ValueError('Artifact SHA256 mismatch')
    elif pin['name'] == 'frontend.model.ort' and re.fullmatch(r'[0-9a-f]{40}', pin.get('git_blob_sha1', '')):
        data = path.read_bytes()
        if hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest() != pin['git_blob_sha1']:
            raise ValueError('Exact frontend Git blob mismatch')
        got['git_blob_sha1'] = pin['git_blob_sha1']
    else: raise ValueError('No hashless fallback')
    return got


def unpack_wheel(path, output):
    with zipfile.ZipFile(path) as archive:
        names = set(); total = 0
        for entry in archive.infolist():
            name = PurePosixPath(entry.filename); total += entry.file_size
            mode = stat.S_IFMT(entry.external_attr >> 16)
            if (name.is_absolute() or '..' in name.parts or '\\' in entry.filename or
                    entry.filename in names or mode not in (0, stat.S_IFREG, stat.S_IFDIR) or
                    total > 256 * 1024 ** 2): raise ValueError('Unsafe or oversized wheel archive')
            names.add(entry.filename)
        output.mkdir(); archive.extractall(output)


def dependency_identity():
    # This import path needs only platformdirs, not numpy/sounddevice or download dependencies.
    dist = importlib.metadata.distribution('platformdirs'); rows = {}
    for name in dist.files or []:
        if str(name).endswith(('.py', '/METADATA')):
            path = dist.locate_file(name)
            if path.is_symlink(): raise ValueError('Installed dependency symlink')
            rows[str(name)] = file_identity(path, 1024 * 1024)['sha256']
    origin = importlib.util.find_spec('platformdirs')
    if (not rows or not any(name.endswith('/METADATA') for name in rows) or origin is None or
            Path(origin.origin).resolve() != dist.locate_file('platformdirs/__init__.py').resolve()):
        raise ValueError('Missing or shadowed installed platformdirs identity; request pinned pure Python wheel metadata, no pip')
    return dict(name='platformdirs', version=dist.version, files=rows)


def admit_inputs(repository, pins, work):
    if work.is_symlink() or any(p.is_symlink() for p in work.rglob('*')):
        raise ValueError('Owned work must contain no symlinks')
    corpus = load_json(work / 'original/corpus.json'); quiet = load_json(work / 'quiet/manifest.json')
    controls = load_json(work / 'controls/controls.json'); cases = select_cases(corpus, quiet, controls)
    for case in cases:
        root = work / ('controls' if case['origin'] == 'negative_control' else 'quiet' if 'transform' in case else 'original')
        audio = verify_audio(case, root); _, receipt = sample_protocol(audio.read_bytes())
        if 'expected_num_samples' in case and receipt['original_frames'] != case['expected_num_samples']:
            raise ValueError('Original sample count mismatch')
    assets = [verify_artifact(pin, work / 'models' / pin['name']) for pin in pins['models']]
    if {p.name for p in (work / 'models').iterdir()} != {p['name'] for p in pins['models']}:
        raise ValueError('Require only the exact eight assets; no optional attention fallback')
    verify_artifact(pins['wheel'], work / pins['wheel']['name'])
    with zipfile.ZipFile(work / pins['wheel']['name']) as archive:
        expected = {entry.filename for entry in archive.infolist() if not entry.is_dir()}
        if {p.relative_to(work / 'wheel').as_posix() for p in (work / 'wheel').rglob('*') if p.is_file()} != expected:
            raise ValueError('Owned wheel tree has extra or missing files')
        for entry in archive.infolist():
            if not entry.is_dir():
                path = work / 'wheel' / entry.filename
                if path.is_symlink() or file_identity(path, entry.file_size)['sha256'] != hashlib.sha256(archive.read(entry)).hexdigest():
                    raise ValueError('Unpacked wheel readback mismatch')
    for name, digest in pins['publisher_python_sha256'].items():
        if file_identity(work / 'wheel' / name, 128 * 1024)['sha256'] != digest:
            raise ValueError('Publisher Python source drift')
    if not (work / 'wheel/moonshine_voice/libmoonshine.so').is_file(): raise ValueError('Owned native library missing; no fallback')
    if dependency_identity() != load_json(work / 'dependency.json'): raise ValueError('Installed dependency changed')
    for name, digest in load_json(work / 'dependency.json')['files'].items():
        if name.startswith('platformdirs/') and file_identity(work / 'dependency' / name, 1024 * 1024)['sha256'] != digest:
            raise ValueError('Owned pure Python dependency readback changed')
    return cases, assets


def retain(path, data):
    files = list(path.parent.iterdir())
    if any(p.is_symlink() or not p.is_file() or p.suffix not in ('.json', '.log', '.md') for p in files):
        raise ValueError('Only bounded public text evidence may be retained')
    reserve = 128 * 1024 if path.name != 'completion.json' else 0
    if len(data) > (128 * 1024 if path.name == 'completion.json' else OUTPUT_BYTES) or sum(p.stat().st_size for p in files) + len(data) > TEXT_BYTES - reserve:
        raise ValueError('Text evidence cap exceeded')
    with path.open('xb') as target: target.write(data)


def raw_passed(result):
    return (result.get('status') == 'ok' and result.get('score', {}).get('passed') is True and
            (result.get('origin') != 'negative_control' or (result.get('raw') == '' and result.get('lines') == [])))


def completion(results):
    complete = len(results) == 12 and {r['id'] for r in results} == set(CASE_IDS) and all(r.get('status') == 'ok' for r in results)
    passed = complete and all(raw_passed(r) for r in results)
    return dict(QUALIFICATION, schema_version=1, status='complete_diagnostic' if complete else 'incomplete_diagnostic',
                strict_scores_passed=passed, results_count=len(results), blocker=BLOCKER,
                continuous_thread_bound_verified=False, thread_limit_disclosure=THREAD_LIMIT,
                next_gate='instrumented native EOF/error proof' if passed else 'reject or inspect retained failed prefixes')


def run_native(case, work, evidence, env):
    if case['id'] not in CASE_IDS or case['language'] != 'en': raise ValueError('Only frozen EN cases')
    result = dict(QUALIFICATION, id=case['id'], origin=case['origin'], language='en', audio_sha256=case['sha256'],
                  status='invalid_input_or_output', raw='', lines=[], blocker=BLOCKER)
    log_path = work / (case['id'] + '.log'); started = time.monotonic(); cleanup_failed = False
    try:
        with log_path.open('xb') as log:
            run_logged([sys.executable, '-B', str(Path(__file__).resolve()), '--infer-worker', case['id']],
                       env, log, timeout=120, byte_limit=OUTPUT_BYTES)
        output = log_path.read_bytes().splitlines()
        events = [strict_json(line[16:]) for line in output if line.startswith(b'MOONSHINE_EVENT ')]
        if any(not isinstance(e, dict) for e in events): raise ValueError('Malformed publisher event')
        for event in events:
            if 'lines' in event: validate_lines(event['lines'])
            elif event.get('line') is not None: validate_lines([event['line']])
        if (any(e.get('event') == 'Error' for e in events) or
                any(NATIVE_ERROR.search(line) for line in output if not line.startswith(b'MOONSHINE_'))):
            raise ValueError('Observed publisher/ORT error')
        observations = [e for e in events if e.get('event') == 'ThreadObservation']
        if any(type(e.get('threads')) is not int or not 0 < e['threads'] <= 3 for e in observations):
            raise ValueError('Observed process thread bound violation')
        admissions = [e for e in events if e.get('event') == 'NativeAdmission']
        pins = load_json(Path(__file__).resolve().parent / 'moonshine-pins.json')
        if (len(admissions) != 1 or admissions[0].get('native_controls') != pins['native_controls'] or
                not {'after_model_load', 'Stop', 'after_unload'} <= {e.get('stage') for e in observations}):
            raise ValueError('Missing frozen environment/source/thread observations')
        admitted = admissions[0]; require_native_controls(pins, admitted.get('environment'))
        cpus = admitted.get('cpu_affinity'); wheel = admitted.get('wheel', {})
        if (not isinstance(cpus, list) or len(cpus) != 3 or any(type(cpu) is not int or cpu < 0 for cpu in cpus) or
                len(set(cpus)) != 3 or not isinstance(wheel, dict) or wheel.get('path') != str((work / pins['wheel']['name']).resolve()) or
                wheel.get('bytes') != pins['wheel']['bytes'] or wheel.get('sha256') != pins['wheel']['sha256']):
            raise ValueError('Observed CPU affinity or selected wheel identity mismatch')
        final = [strict_json(line[17:]) for line in output if line.startswith(b'MOONSHINE_RESULT ')]
        if len(final) != 1 or not isinstance(final[0], dict) or final[0].get('id') != case['id'] or final[0].get('status') != 'ok':
            raise ValueError('Missing successful raw observation')
        lines = validate_lines(final[0].get('lines'))
        if (final[0].get('raw') != '\n'.join(line['text'] for line in lines) or
                type(final[0].get('peak_rss_bytes')) is not int or not 0 < final[0]['peak_rss_bytes'] <= 4 * GIB):
            raise ValueError('Raw lines or memory evidence mismatch')
        result.update(final[0]); result.update(QUALIFICATION)
        result['native_admission'] = admitted
        result['score'] = score_case(case, result['raw'])
    except subprocess.TimeoutExpired as error:
        cleanup_failed = error.timeout == 5; result['status'] = 'cleanup_timeout' if cleanup_failed else 'timeout'
    except subprocess.CalledProcessError as error:
        result.update(status='engine_error', exit_code=error.returncode)
    except (ValueError, OSError, KeyError, TypeError) as error:
        result.update(status='invalid_input_or_output', error_type=type(error).__name__)
    finally:
        data = log_path.read_bytes() if log_path.exists() else b''
        if result['status'] != 'ok':
            prefix = {}
            for line in data.splitlines():
                if line.startswith(b'MOONSHINE_EVENT '):
                    try:
                        value = strict_json(line[16:])
                        if not isinstance(value, dict): continue
                        if 'lines' in value: prefix = {v['line_id']: v for v in validate_lines(value['lines'])}
                        elif value.get('line') is not None:
                            candidate = validate_lines([value['line']])[0]
                            prefix[candidate['line_id']] = candidate
                    except (ValueError, KeyError, TypeError): pass
            result.update(lines=list(prefix.values()), raw='\n'.join(v['text'] for v in prefix.values()))
        observations = []
        for line in data.splitlines():
            if line.startswith(b'MOONSHINE_EVENT '):
                try:
                    value = strict_json(line[16:])
                    if isinstance(value, dict) and value.get('event') == 'ThreadObservation': observations.append(value)
                except (ValueError, TypeError): pass
        result.update(thread_observations=observations,
                      observed_peak_threads=max((e['threads'] for e in observations if type(e.get('threads')) is int), default=None),
                      continuous_thread_bound_verified=False, thread_limit_disclosure=THREAD_LIMIT)
        result['elapsed_seconds'] = time.monotonic() - started
        retain(evidence / (case['id'] + '.log'), data); retain(evidence / (case['id'] + '-score.json'), ts.encode(result))
    if cleanup_failed: raise RuntimeError('Owned group could not join; hold all remaining cases')
    return result


def worker(mode, args):
    repository, pins, temp = preflight(); work = temp / 'lip-moonshine-work'
    require_native_controls(pins, os.environ)
    resource.setrlimit(resource.RLIMIT_AS, (4 * GIB, 4 * GIB))
    resource.setrlimit(resource.RLIMIT_FSIZE, (OUTPUT_BYTES if mode == '--infer-worker' else 512 * 1024 ** 2,) * 2)
    if mode == '--acquire-worker':
        if args: raise ValueError('No acquisition overrides')
        original = ts.acquire_corpus(repository, work, pins)
        ts.prepare_quiet(original, work / 'quiet', load_json(repository / 'eval/initial-ts-pins.json'))
        prepare_controls(work / 'controls'); (work / 'models').mkdir()
        for pin in [pins['wheel'], *pins['models']]:
            path = work / pin['name'] if pin == pins['wheel'] else work / 'models' / pin['name']
            if 'sha256' in pin: download(pin, path)
            else:
                try:
                    with urllib.request.urlopen(pin['url'], timeout=40) as source: data = source.read(pin['bytes'] + 1)
                except Exception: raise ValueError('Pinned frontend delivery failed; URL withheld') from None
                with path.open('xb') as target: target.write(data)
            verify_artifact(pin, path)
        unpack_wheel(work / pins['wheel']['name'], work / 'wheel')
        dependency = dependency_identity(); (work / 'dependency.json').write_bytes(ts.encode(dependency))
        dist = importlib.metadata.distribution('platformdirs')
        for name in dependency['files']:
            if name.startswith('platformdirs/'):
                target = work / 'dependency' / name; target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(dist.locate_file(name), target)
        cases, assets = admit_inputs(repository, pins, work)
        (work / 'admission.json').write_bytes(ts.encode(dict(cases=cases, assets=assets, dependency=dependency_identity())))
        return 0
    if len(args) != 1: raise ValueError('One frozen case ID required')
    cases, assets = admit_inputs(repository, pins, work); case = next(c for c in cases if c['id'] == args[0])
    os.sched_setaffinity(0, sorted(os.sched_getaffinity(0))[:3])
    if any(name == 'platformdirs' or name.startswith(('platformdirs.', 'moonshine_voice', 'google_crc32c')) for name in sys.modules):
        raise ValueError('No preloaded publisher/dependency imports')
    sys.path[:] = [str(work / 'wheel'), str(work / 'dependency'), sysconfig.get_path('stdlib'), sysconfig.get_config_var('DESTSHARED')]
    def record(value):
        data = json.dumps(value, ensure_ascii=False, allow_nan=False).encode()
        if len(data) > OUTPUT_BYTES: raise ValueError('Event output cap')
        sys.stdout.buffer.write(b'MOONSHINE_EVENT ' + data + b'\n'); sys.stdout.buffer.flush()
        if value.get('event') in ('Update', 'Stop'): observe_threads(record, value['event'])
    record(dict(event='NativeAdmission', environment={k: os.environ.get(k) for k in NATIVE_ENV},
                native_controls=pins['native_controls'], cpu_affinity=sorted(os.sched_getaffinity(0)),
                thread_limit_disclosure=THREAD_LIMIT,
                wheel=verify_artifact(pins['wheel'], work / pins['wheel']['name'])))
    observe_threads(record, 'before_import')
    import moonshine_voice
    from moonshine_voice import moonshine_api as api
    from moonshine_voice.transcriber import Transcriber
    expected_lib = (work / 'wheel/moonshine_voice/libmoonshine.so').resolve(strict=True); lib = api._MoonshineLib().lib
    if Path(lib._name).resolve(strict=True) != expected_lib or moonshine_voice.__version__ != '0.1.5' or lib.moonshine_get_version() != 30000:
        raise ValueError('Loaded publisher library/version mismatch; no fallback')
    for name, module in list(sys.modules.items()):
        if name.startswith('moonshine_voice') and not Path(module.__file__).resolve().is_relative_to(work / 'wheel'):
            raise ValueError('Publisher import escaped owned wheel')
    verify_mapping(work, expected_lib); observe_threads(record, 'after_import')
    catalog = strict_json(api.moonshine_get_stt_catalog_string().encode('utf-8')); en = next(lang for lang in catalog['languages'] if lang['code'] == 'en')
    catalog_root = 'https://download.moonshine.ai/model/medium-streaming-en/quantized_26_08_21'
    if not any(v['model_arch'] == 5 and v['download_url'] == catalog_root for v in en['models']):
        raise ValueError('Native catalog mismatch')
    dependencies = strict_json(api.moonshine_get_stt_dependencies_string('en', {'model_arch': '5', 'word_timestamps': 'false'}).encode('utf-8'))
    files = [f for group in dependencies['groups'] for f in group['files']]
    if (len(dependencies['groups']) != 1 or dependencies['groups'][0]['base_url'] != catalog_root or
            len(files) != 8 or {f['name']: f['size'] for f in files} != {p['name']: p['bytes'] for p in pins['models']}):
        raise ValueError('Native catalog requires different artifacts; no fallback')
    root = work / ('controls' if case['origin'] == 'negative_control' else 'quiet' if 'transform' in case else 'original')
    record(dict(event='NativeIdentity', native_library=file_identity(expected_lib, 256 * 1024 ** 2),
                native_header_version=30000, package_version=moonshine_voice.__version__,
                loaded_library_paths=verify_mapping(work, expected_lib), catalog=catalog, dependencies=dependencies,
                metadata_limits='ABI/catalog/wheel identity do not attest compiled source or native EOS/error safety'))
    samples, integrity = sample_protocol(verify_audio(case, root).read_bytes())
    result = dict(QUALIFICATION, id=case['id'], status='ok', input=integrity, options=OPTIONS,
                  model_assets=assets, wheel=verify_artifact(pins['wheel'], work / pins['wheel']['name']),
                  native_controls=pins['native_controls'], environment={k: os.environ.get(k) for k in NATIVE_ENV},
                  continuous_thread_bound_verified=False, thread_limit_disclosure=THREAD_LIMIT, native_library=file_identity(expected_lib, 256 * 1024 ** 2),
                  catalog=catalog, native_header_version=30000,
                  metadata_limits='ABI version is not native build/source or EOS proof; internal sample/token counts unavailable',
                  timing_scope='cold unpaced hosted CPU with RAW-only single-thread reliability escape hatch, not production/Android/mic', blocker=BLOCKER)
    with Transcriber(work / 'models', api.ModelArch.MEDIUM_STREAMING, update_interval=60, options=OPTIONS) as transcriber:
        verify_mapping(work, expected_lib); observe_threads(record, 'after_model_load')
        stream = transcriber.create_stream(update_interval=60)
        observe_threads(record, 'after_stream_create')
        result.update(feed_stream(stream, samples, record))
        result['loaded_library_paths'] = verify_mapping(work, expected_lib)
    observe_threads(record, 'after_unload')
    result['peak_rss_bytes'] = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss * 1024
    print('MOONSHINE_RESULT ' + json.dumps(result, ensure_ascii=False, allow_nan=False), flush=True)
    return 0


def main():
    repository, pins, temp = preflight()
    require_native_controls(pins, os.environ)
    if sys.argv[1:] == ['--preflight']: return 0
    if sys.argv[1:]: raise ValueError('No runtime overrides')
    work, evidence = temp / 'lip-moonshine-work', temp / 'lip-moonshine-evidence'
    if any(p.exists() or p.is_symlink() for p in (work, evidence)): raise ValueError('Never reuse uncertain work/evidence')
    work.mkdir(); evidence.mkdir(); results = []; report = {}; started = time.monotonic()
    env = {k: v for k, v in os.environ.items() if k.startswith(('GITHUB_', 'RUNNER_', 'Image'))}
    env.update(PATH='/usr/bin:/bin', HOME=str(work), LANG='C.UTF-8', OMP_NUM_THREADS='3', OPENBLAS_NUM_THREADS='3', PYTHONNOUSERSITE='1', **NATIVE_ENV)
    cases = select_cases(load_json(repository / 'eval/corpus.json'),
                         {'cases': load_json(repository / 'eval/initial-ts-pins.json')['quiet_cases']}, load_json(repository / 'eval/controls.json'))
    current_case = None
    try:
        with (work / 'acquisition.log').open('xb') as log:
            run_logged([sys.executable, '-B', str(Path(__file__).resolve()), '--acquire-worker'], env, log, timeout=600, byte_limit=OUTPUT_BYTES)
        cases, _ = admit_inputs(repository, pins, work); retain(evidence / 'admission.json', (work / 'admission.json').read_bytes())
        inference_started = time.monotonic()
        for case in cases:
            if time.monotonic() - inference_started > 775: raise TimeoutError('900s aggregate inference budget; reserve join/receipt')
            current_case = case
            results.append(run_native(case, work, evidence, env))
            if not raw_passed(results[-1]):
                report.update(rejected_case=case['id'], remaining_cases_held=True); break
    except Exception as error:
        if current_case is not None and not any(r['id'] == current_case['id'] for r in results):
            path = evidence / (current_case['id'] + '-score.json')
            if path.exists(): results.append(load_json(path))
        report.update(error_type=type(error).__name__, remaining_cases_held=True)
    finally:
        if (work / 'acquisition.log').exists(): retain(evidence / 'acquisition.log', (work / 'acquisition.log').read_bytes())
        report.update(completion(results)); report.update(results=[{k: r[k] for k in ('id', 'status')} for r in results], case_plan=cases,
            elapsed_seconds=time.monotonic() - started, source_sha256=pins['source_sha256'],
            native_controls=pins['native_controls'], environment={k: env.get(k) for k in NATIVE_ENV},
            continuous_thread_bound_verified=False, thread_limit_disclosure=THREAD_LIMIT,
            observed_peak_threads=max((r['observed_peak_threads'] for r in results if r.get('observed_peak_threads') is not None), default=None),
            pins_sha256=file_identity(repository / 'eval/moonshine-pins.json', 128 * 1024)['sha256'],
            runner_sha256=file_identity(Path(__file__), 128 * 1024)['sha256'], attribution=pins['attribution'],
            identity={k: env.get(k) for k in ('GITHUB_SHA', 'GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT', 'GITHUB_WORKFLOW_REF')})
        retain(evidence / 'completion.json', ts.encode(report))
        retain(evidence / 'observation-findings.md', ('# Raw EN observation\n\n' + report['status'] +
            '; strict raw comparisons: ' + str(report['strict_scores_passed']) + '\n\n' +
            'quality_passed=false; native_completion_verified=false; android_verified=false; promotion_allowed=false.\n\n' + BLOCKER + '\n\n' + THREAD_LIMIT + '\n').encode())
    return 0 if report['status'] == 'complete_diagnostic' and report['strict_scores_passed'] else 1


if __name__ == '__main__':
    if sys.argv[1:2] in (['--infer-worker'], ['--acquire-worker']):
        raise SystemExit(worker(sys.argv[1], sys.argv[2:]))
    raise SystemExit(main())
