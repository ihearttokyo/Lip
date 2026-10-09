"""Opt-in full-wave peak diagnostic; never app defaults or accepted inference quality."""
from array import array
from contextlib import contextmanager
from datetime import datetime
import hashlib
import math
import os
from pathlib import Path
import re
import resource
import shutil
import signal
import struct
import sys
import time

import quant_ci as q

ts = q.ts
REF = 'refs/heads/codex/lip-peak-normalization'
WORKFLOW_REF = 'ihearttokyo/Lip/.github/workflows/normalization-experiment.yml@' + REF
MARKER = '[peak-normalization-diagnostic]'
ARMS = q.NORMALIZATION_ARMS
WORK_NAME, EVIDENCE_NAME = 'lip-normalization-work', 'lip-normalization-evidence'
ABSOLUTE_DEADLINE = '2026-10-09T23:48:00Z'
ABSOLUTE_DEADLINE_EPOCH = datetime.fromisoformat(ABSOLUTE_DEADLINE).timestamp()
CLEANUP_SECONDS = 30
MAX_WAVE_BYTES = 4 * 1024 * 1024
SOURCE_PATHS = ts.SOURCE_PATHS | {
    'eval/initial_ts_ci.py', 'eval/initial-ts-pins.json', 'eval/quant_ci.py', 'eval/test_quant_ci.py',
    'eval/normalization_ci.py', 'eval/test_normalization_ci.py', 'eval/test_initial_ts_ci.py',
    '.github/workflows/initial-ts-experiment.yml', '.github/workflows/normalization-experiment.yml',
    'app/src/main/cpp/whisper_jni.cpp', 'third_party/whisper.cpp/src/whisper.cpp',
    'third_party/whisper.cpp/examples/cli/cli.cpp', 'third_party/whisper.cpp/examples/common-whisper.cpp',
    'third_party/whisper.cpp/examples/miniaudio.h', 'NOTICE.md'}
ACCEPTANCE_FLAGS = dict.fromkeys(('phone', 'warm_live', 'cancellation', 'cloud_parity', 'full_acceptance'), False)


def require_budget(deadline, seconds, *, monotonic=None, wall=None):
    now = time.monotonic() if monotonic is None else monotonic
    utc = time.time() if wall is None else wall
    if any(type(v) not in (int, float) or not math.isfinite(v) for v in (deadline, seconds, now, utc)) or seconds < 0:
        raise ValueError('Finite operation and deadline required')
    if min(deadline - now, ABSOLUTE_DEADLINE_EPOCH - utc) < seconds + CLEANUP_SECONDS:
        raise TimeoutError('Operation plus owned cleanup cannot fit both experiment deadlines')


@contextmanager
def phase_timer(deadline, end):
    seconds = end - time.monotonic()
    if seconds <= 0: raise TimeoutError('Whole operation has no remaining time')
    require_budget(deadline, seconds)
    if signal.getitimer(signal.ITIMER_REAL) != (0.0, 0.0):
        raise ValueError('Never replace an existing operation alarm')
    def expired(_signal, _frame): raise TimeoutError('Whole operation deadline exceeded')
    previous = signal.signal(signal.SIGALRM, expired)
    try:
        seconds = end - time.monotonic()
        if seconds <= 0: raise TimeoutError('Whole operation has no remaining time')
        signal.setitimer(signal.ITIMER_REAL, seconds)
        yield
        if time.monotonic() > end: raise TimeoutError('Whole operation deadline exceeded')
        require_budget(deadline, 0)
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous)


def build_cli(repository, work, evidence, initial, env, tools, deadline):
    with phase_timer(deadline, time.monotonic() + 900):
        return ts.build_cli(repository, work, evidence, initial, env, tools)


def decode_wave(data):
    if (type(data) is not bytes or not 44 <= len(data) <= MAX_WAVE_BYTES or data[:4] != b'RIFF' or
            data[8:12] != b'WAVE' or int.from_bytes(data[4:8], 'little') != len(data) - 8):
        raise ValueError('Bounded complete RIFF WAVE required')
    offset, fmt, payload, fact = 12, None, None, None
    while offset + 8 <= len(data):
        kind, size = data[offset:offset + 4], int.from_bytes(data[offset + 4:offset + 8], 'little')
        start, end = offset + 8, offset + 8 + size
        if end + size % 2 > len(data): raise ValueError('Truncated WAVE chunk')
        if kind == b'fmt ':
            if fmt is not None or size not in (16, 18) or (size == 18 and data[start + 16:end] != b'\0\0'):
                raise ValueError('Unsupported or duplicate WAVE format')
            fmt = struct.unpack_from('<HHIIHH', data, start)
        elif kind == b'data':
            if payload is not None or fmt is None: raise ValueError('Duplicate or unordered WAVE data')
            payload = (start, end)
        elif kind == b'fact':
            if fact is not None or size != 4: raise ValueError('Unsupported fact chunk')
            fact = int.from_bytes(data[start:end], 'little')
        elif kind in (b'PEAK', b'plst', b'wavl', b'slnt') or (kind == b'LIST' and data[start:start + 4] == b'wavl'):
            raise ValueError('Amplitude metadata or alternate sample layout is unsafe to retain')
        offset = end + size % 2
    if offset != len(data) or fmt is None or payload is None: raise ValueError('Missing or incomplete WAVE chunks')
    tag, channels, rate, byte_rate, alignment, bits = fmt
    start, end = payload
    if ((tag, bits) not in ((1, 16), (3, 32)) or not 1 <= channels <= 8 or not 1 <= rate <= 192000 or
            alignment != channels * bits // 8 or byte_rate != rate * alignment or
            (end - start) % alignment or not 0 < (end - start) // alignment <= rate * 30):
        raise ValueError('Only bounded complete PCM16 or IEEE float32 frames are supported')
    frames = (end - start) // alignment
    if fact is not None and fact != frames: raise ValueError('WAVE fact frame count disagrees with PCM')
    samples = array('h' if tag == 1 else 'f'); samples.frombytes(memoryview(data)[start:end])
    if sys.byteorder != 'little': samples.byteswap()
    if tag == 3 and any(not math.isfinite(v) or not -1 <= v <= 1 for v in samples):
        raise ValueError('Finite float32 samples within [-1,1] required')
    return {'format': {'encoding': 'pcm16' if tag == 1 else 'ieee_float32', 'tag': tag, 'bits': bits,
                      'channels': channels, 'sample_rate': rate, 'block_align': alignment, 'byte_rate': byte_rate},
            'frame_count': frames, 'payload_start': start, 'payload_end': end}, samples


def normalize_wave(data):
    layout, samples = decode_wave(data)
    scale = 32768.0 if layout['format']['tag'] == 1 else 1.0
    peak = max(abs(value) for value in samples) / scale
    gain = .5 / peak if peak else 1.0
    transformed = (array(samples.typecode, (round(value * gain) if scale != 1 else value * gain for value in samples))
                   if peak else samples)
    encoded = array(transformed.typecode, transformed)
    if sys.byteorder != 'little': encoded.byteswap()
    start, end = layout['payload_start'], layout['payload_end']
    derived = data[:start] + encoded.tobytes() + data[end:] if peak else data
    decoded_layout, decoded = decode_wave(derived)
    if decoded_layout != layout or decoded != transformed or len(derived) != len(data):
        raise ValueError('Full decoded frame/sample consistency failed')
    before, after = (array('f', (value / scale for value in values)) for values in (samples, decoded))
    if layout['format']['channels'] == 1 and layout['format']['sample_rate'] == 16000:
        if ts.pcm_samples(data) != before or ts.pcm_samples(derived) != after:
            raise ValueError('Full decoder-scale PCM disagrees with original format')
    if sys.byteorder != 'little': before.byteswap(); after.byteswap()
    zeros_before, zeros_after = sum(value == 0 for value in samples), sum(value == 0 for value in decoded)
    clipped = sum(not -.5 <= value / scale <= .5 for value in decoded)
    if clipped: raise ValueError('Peak normalization must not clip')
    return derived, {'source_sha256': hashlib.sha256(data).hexdigest(), 'derived_sha256': hashlib.sha256(derived).hexdigest(),
        'size_bytes': len(data), 'format': layout['format'], 'frame_count': layout['frame_count'], 'sample_count': len(samples),
        'gain': gain, 'peak_before': peak, 'peak_after': max(abs(value) for value in decoded) / scale,
        'zero_before': zeros_before, 'zero_after': zeros_after, 'nonzero_before': len(samples) - zeros_before,
        'nonzero_after': len(decoded) - zeros_after, 'round_to_zero': sum(old != 0 and new == 0 for old, new in zip(samples, decoded)),
        'clipped_samples': clipped, 'exact_zero_identity': peak == 0,
        'source_decoded_pcm_sha256': hashlib.sha256(before.tobytes()).hexdigest(),
        'derived_decoded_pcm_sha256': hashlib.sha256(after.tobytes()).hexdigest(),
        'decoder_scale': 'PCM16/32768; IEEE float32 unchanged', 'quantization': 'PCM16 round nearest ties even; float32 IEEE rounding'}


def require_environment(env, event, head, message, platform, uid, release):
    expected = {'GITHUB_ACTIONS': 'true', 'RUNNER_OS': 'Linux', 'RUNNER_ARCH': 'X64',
                'RUNNER_ENVIRONMENT': 'github-hosted', 'ImageOS': 'ubuntu24',
                'GITHUB_REPOSITORY': 'ihearttokyo/Lip', 'GITHUB_REPOSITORY_OWNER': 'ihearttokyo',
                'GITHUB_EVENT_NAME': 'push', 'GITHUB_REF': REF,
                'GITHUB_SERVER_URL': 'https://github.com', 'GITHUB_WORKFLOW_REF': WORKFLOW_REF}
    if (platform != 'linux' or uid == 0 or release != '24.04' or
            any(env.get(k) != v for k, v in expected.items()) or
            any(not re.fullmatch(r'[1-9][0-9]*', env.get(k, '')) for k in ('GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT')) or
            not re.fullmatch(r'[0-9a-f]{40}', head) or head != env.get('GITHUB_SHA')):
        raise ValueError('Exact unprivileged public hosted Ubuntu24/X64 normalization push required')
    repo, commit = event.get('repository', {}), event.get('head_commit', {})
    if (repo.get('full_name') != 'ihearttokyo/Lip' or repo.get('private') is not False or
            repo.get('visibility') != 'public' or repo.get('owner', {}).get('login') != 'ihearttokyo' or
            event.get('ref') != REF or event.get('after') != head or commit.get('id') != head or
            not isinstance(message, str) or MARKER not in message or
            commit.get('message', '').rstrip('\n') != message.rstrip('\n')):
        raise ValueError('Actual head/event and exact case-sensitive normalization marker must agree')


def verify_sources(repository, pins):
    initial_path = repository / 'eval/initial-ts-pins.json'
    if (initial_path.is_symlink() or hashlib.sha256(initial_path.read_bytes()).hexdigest() != q.INITIAL_PINS_SHA256 or
            pins.get('schema_version') != 1 or pins.get('runtime_revision') != ts.RUNTIME_REVISION or
            pins.get('target_peak') != .5 or pins.get('absolute_deadline') != ABSOLUTE_DEADLINE or
            set(pins['source_sha256']) != SOURCE_PATHS or
            pins['source_sha256'].get('eval/initial_ts_ci.py') != q.INITIAL_RUNNER_SHA256 or
            pins['source_sha256'].get('eval/initial-ts-pins.json') != q.INITIAL_PINS_SHA256):
        raise ValueError('Frozen normalization rule/source scope drift')
    initial = ts.load_json(initial_path)
    ts.verify_sources(repository, initial)
    ts.validate_quiet(initial, ts.load_json(repository / 'eval/corpus.json'))
    if pins['model'] != initial['model'] or 'models' in pins: raise ValueError('Only one fixed complete Q5 artifact is admitted')
    for name, digest in pins['source_sha256'].items():
        path = repository / name
        if path.is_symlink() or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise ValueError('Frozen source drift: ' + name)
    return initial


def preflight(repository):
    if sys.platform != 'linux' or os.geteuid() == 0:
        raise ValueError('Normalization native/model route is unprivileged hosted Linux only')
    release = dict(line.split('=', 1) for line in Path('/etc/os-release').read_text().splitlines() if '=' in line)
    event = ts.load_json(os.environ['GITHUB_EVENT_PATH'])
    head, message = ts.git(repository, 'rev-parse', 'HEAD'), ts.git(repository, 'log', '-1', '--format=%B')
    require_environment(os.environ, event, head, message, sys.platform, os.geteuid(),
                        release.get('VERSION_ID', '').strip('"') if release.get('ID') == 'ubuntu' else '')
    if ts.git(repository, 'status', '--porcelain', '--untracked-files=all', '--ignore-submodules=none'):
        raise ValueError('Clean actual source and recursive vendor checkout required')
    pins = ts.load_json(repository / 'eval/normalization-pins.json'); initial = verify_sources(repository, pins)
    vendor = repository / 'third_party/whisper.cpp'
    if (ts.git(vendor, 'rev-parse', 'HEAD') != ts.RUNTIME_REVISION or
            ts.git(vendor, 'status', '--porcelain', '--untracked-files=all', '--ignore-submodules=none') or
            ts.git(repository, 'ls-tree', 'HEAD', 'third_party/whisper.cpp').split()[2] != ts.RUNTIME_REVISION):
        raise ValueError('Actual pristine vendor checkout and gitlink must match the runtime pin')
    ts.patch_cli((vendor / 'examples/cli/cli.cpp').read_bytes(), initial)
    cases = q.frozen_cases(repository, initial); q.paired_schedule(cases, cases, normalization=True)
    runner = Path(os.environ['RUNNER_TEMP']).resolve(strict=True)
    if runner == Path('/') or runner.is_relative_to(repository): raise ValueError('Owned temp must be outside the checkout')
    mem = re.search(r'^MemAvailable:\s+(\d+) kB$', Path('/proc/meminfo').read_text(), re.M)
    capacity = {'logical_cpus': os.cpu_count(), 'affinity_cpus': len(os.sched_getaffinity(0)),
                'available_memory_bytes': int(mem[1]) * 1024 if mem else 0, 'free_disk_bytes': shutil.disk_usage(runner).free}
    ts.require_capacity(min(capacity['logical_cpus'], capacity['affinity_cpus']), capacity['available_memory_bytes'], capacity['free_disk_bytes'])
    require_budget(time.monotonic() + ts.JOB_SECONDS, 0)
    return pins, initial, cases, runner, capacity


def owned_directories(runner):
    work, evidence = runner / WORK_NAME, runner / EVIDENCE_NAME
    if any(path.exists() or path.is_symlink() for path in (work, evidence)):
        raise FileExistsError('Refuse reused normalization work/evidence')
    evidence.mkdir(); work.mkdir()
    return work, evidence


def population(case):
    return 'controls' if case['origin'] == 'negative_control' else 'quiet' if '-minus' in case['id'] else 'original'


def owned_path(path, root, *, directory=False):
    if (path.is_symlink() or path.resolve(strict=True) != path or not path.is_relative_to(root) or
            path.stat().st_uid != os.geteuid() or not (path.is_dir() if directory else path.is_file())):
        raise ValueError('Expected nonsymlink owned regular path required')
    return path


def prepare_normalized(cases, roots, work, evidence):
    output = work / 'normalized'; output.mkdir(); entries = {}
    for case in cases:
        source = ts.verify_audio(case, roots[case['id']])
        data = q.read_output(source, MAX_WAVE_BYTES)
        derived, record = normalize_wave(data)
        if hashlib.sha256(data).hexdigest() != case['sha256']: raise ValueError('Source changed after admission')
        path = output / (case['id'] + '.wav')
        with path.open('xb') as target: target.write(derived)
        if q.read_output(path, MAX_WAVE_BYTES) != derived: raise ValueError('Full normalized WAV readback mismatch')
        entries[case['id']] = dict(record, id=case['id'], source_audio=case['audio'], audio=path.name)
    ts.save(evidence / 'normalization-manifest.json', {'rule': 'gain=0.5/maxabs(all_samples); exact zero identity', 'cases': list(entries.values())})
    return entries


def validate_worker(args, repository):
    pins, initial, cases, runner, _ = preflight(repository)
    arm = os.environ.get('LIP_NORMALIZATION_ARM')
    if (len(args) != 5 or arm not in ARMS or os.environ.get('LIP_INITIAL_TS') != '1.0' or
            os.environ.get('LIP_QUANT_ARM') != 'q5_0'):
        raise ValueError('Only fixed baseline/normalized Q5 timestamp1.0 worker arguments admitted')
    binary, model, audio, language, stem = Path(args[0]), Path(args[1]), Path(args[2]), args[3], Path(args[4])
    root = runner / WORK_NAME; owned_path(root, root, directory=True)
    case_id = stem.parent.name.removesuffix('-' + arm)
    case = next((c for c in cases if c['id'] == case_id), None)
    if case is None: raise ValueError('Unadmitted normalization worker case')
    source = root / population(case) / case['audio']
    expected_audio = source if arm == 'baseline' else root / 'normalized' / (case_id + '.wav')
    expected = (root / 'build/bin/whisper-cli', root / pins['model']['name'], expected_audio, root / (case_id + '-' + arm) / 'transcript')
    if language != case['language'] or (binary, model, audio, stem) != expected:
        raise ValueError('Native paths/language differ from the exact owned allowlist')
    owned_path(stem.parent, root, directory=True)
    if any(path.exists() or path.is_symlink() for path in (stem.with_suffix('.txt'), stem.with_suffix('.json'), stem.parent / 'peak-kib.txt')):
        raise ValueError('Fresh native output paths required; never overwrite retained work')
    bindings = ts.strict_json(q.read_output(owned_path(root / 'worker-bindings.json', root)))
    if bindings['identity'] != {k: os.environ[k] for k in q.IDENTITY_KEYS} or bindings['absolute_deadline'] != ABSOLUTE_DEADLINE:
        raise ValueError('Fresh worker ownership identity/deadline drift')
    require_budget(bindings['monotonic_deadline'], 120)
    timing = Path(bindings['time']['path'])
    if timing != Path('/usr/bin/time').resolve(strict=True) or timing.is_symlink() or not timing.is_file():
        raise ValueError('Fixed installed timing tool required')
    for path, identity, cap in ((binary, bindings['binary'], 128 * 1024 * 1024),
                               (timing, {k: bindings['time'][k] for k in ('path', 'bytes', 'sha256')}, 128 * 1024 * 1024),
                               (model, bindings['model'], pins['model']['bytes'])):
        if path != timing: owned_path(path, root)
        if path.is_symlink() or ts.file_identity(path, cap) != identity: raise ValueError('Full worker binary/time/model identity drift')
    if bindings['model']['bytes'] != pins['model']['bytes'] or bindings['model']['sha256'] != pins['model']['sha256']:
        raise ValueError('Complete Q5 artifact witness differs from pin')
    owned_path(source, root); ts.verify_audio(case, root / population(case))
    data = q.read_output(source, MAX_WAVE_BYTES); derived, record = normalize_wave(data)
    entry = bindings['normalization'][case_id]
    if any(entry.get(k) != value for k, value in record.items()): raise ValueError('Full normalization accounting drift')
    if q.read_output(owned_path(audio, root), MAX_WAVE_BYTES) != (data if arm == 'baseline' else derived):
        raise ValueError('Every full decoded PCM sample must match the fixed rule')
    require_budget(bindings['monotonic_deadline'], 120)
    return [bindings['time']['path'], '-f', '%M', '-o', str(stem.parent / 'peak-kib.txt'),
            *ts.cli_command(binary, model, audio, language, stem)]


def infer_worker(args):
    command = validate_worker(args, Path(__file__).resolve().parent.parent)
    resource.setrlimit(resource.RLIMIT_AS, (8 * ts.GIB, 8 * ts.GIB))
    resource.setrlimit(resource.RLIMIT_FSIZE, (ts.OUTPUT_BYTES, ts.OUTPUT_BYTES))
    os.execve(command[0], command, os.environ)


def completion(results, cases):
    report = q.completion(results, cases, normalization=True)
    report.update(acceptance_flags=dict(ACCEPTANCE_FLAGS), variable='full-wave peak0.5 only; same Q5 artifact/binary/decoder',
                  exact_zero_limit='Exact zero WAV bytes are unchanged; prior strict silence hallucinations remain disqualifying.',
                  human_semantic_review='parent_required_for_every_pair; not_performed')
    return report


def main():
    repository = Path(__file__).resolve().parent.parent
    pins, initial, frozen, runner, capacity = preflight(repository)
    if sys.argv[1:] == ['--preflight']:
        print('Exact normalization hosted preflight passed; no tools/acquisition/build/inference.')
        return 0
    if sys.argv[1:]: raise ValueError('Only exact preflight or the full matched diagnostic is supported')
    if signal.getitimer(signal.ITIMER_REAL) != (0.0, 0.0): raise ValueError('Do not replace an existing operation alarm')
    work, evidence = owned_directories(runner)
    started = time.monotonic(); deadline = started + ts.JOB_SECONDS; results = []
    report = {'schema_version': 1, 'status': 'incomplete_diagnostic', 'runtime_success': False, 'acceptance': 'not_assessed',
              'quality_passed': False, 'acceptance_flags': dict(ACCEPTANCE_FLAGS), 'capacity': capacity,
              'identity': {k: os.environ[k] for k in q.IDENTITY_KEYS}, 'source_sha256': pins['source_sha256'],
              'pins_sha256': hashlib.sha256((repository / 'eval/normalization-pins.json').read_bytes()).hexdigest(),
              'limits': {'absolute_deadline': ABSOLUTE_DEADLINE, 'experiment_wall_seconds': ts.JOB_SECONDS,
                  'hosted_job_minutes': 180, 'artifact_bytes': ts.ARTIFACT_BYTES, 'model_bytes': pins['model']['bytes'],
                  'build_workers': 2, 'decode_threads': 4, 'build_timeout_seconds': 900, 'acquisition_timeout_seconds': 600,
                  'clip_timeout_seconds': 120, 'cleanup_reserve_seconds': CLEANUP_SECONDS, 'address_space_bytes': 8 * ts.GIB},
              'scorer_version': ts.SCORER_VERSION, 'prior_evidence': pins['prior_evidence'], 'human_semantic_review': 'parent_required; not_performed'}
    try:
        report['phase'] = 'freeze_installed_tools'
        with phase_timer(deadline, time.monotonic() + 60): tools = ts.freeze_tools()
        report['tools'] = tools
        env = {k: os.environ[k] for k in q.IDENTITY_KEYS}
        env.update(PATH='/usr/bin:/bin', HOME=str(work), LANG='C.UTF-8', OMP_NUM_THREADS='4', OPENBLAS_NUM_THREADS='1')
        report['phase'] = 'build_common_pinned_CPU_CLI'
        binary, build = build_cli(repository, work, evidence, initial, env, tools, deadline)
        report['build'] = build; ts.save(evidence / 'build.json', build)
        report['phase'] = 'one_complete_Q5_acquisition'; require_budget(deadline, 600)
        model_deadline = time.monotonic() + 600
        model = work / pins['model']['name']; ts.download(pins['model'], model)
        # The protected downloader owns its alarm; readback gets only its unused budget.
        with phase_timer(deadline, model_deadline):
            model_id = ts.file_identity(model, pins['model']['bytes'])
        if model_id['bytes'] != pins['model']['bytes'] or model_id['sha256'] != pins['model']['sha256']:
            raise ValueError('Complete fresh Q5 artifact mismatch; no fallback')
        report['model'] = model_id
        report['phase'] = 'complete_public_corpus_acquisition'; require_budget(deadline, 600)
        corpus_deadline = time.monotonic() + 600
        corpus_path = ts.acquire_corpus(repository, work, initial); corpus = ts.load_json(corpus_path)
        if time.monotonic() > corpus_deadline: raise TimeoutError('Whole corpus acquisition deadline exceeded')
        require_budget(deadline, 0)
        report['phase'] = 'verify_full_inputs_and_normalize'; require_budget(deadline, 120)
        quiet = ts.prepare_quiet(corpus_path, work / 'quiet', initial)
        controls_root = work / 'controls'; controls_root.mkdir(); ts.prepare_controls(controls_root)
        if hashlib.sha256((controls_root / 'controls.json').read_bytes()).hexdigest() != initial['source_sha256']['eval/controls.json']:
            raise ValueError('Frozen generated controls drift')
        controls = ts.load_json(controls_root / 'controls.json'); cases, roots = [], {}
        for name, manifest in (('original', corpus), ('quiet', quiet), ('controls', controls)):
            ts.save(evidence / (name + '-manifest.json'), manifest)
            for case in manifest['cases']:
                ts.verify_audio(case, work / name); roots[case['id']] = work / name; cases.append(case)
        schedule = q.paired_schedule(cases, frozen, normalization=True)
        entries = prepare_normalized(cases, roots, work, evidence)
        ts.save(evidence / 'pair-order.json', [{'id': case['id'], 'arm': arm} for case, arm in schedule])
        ts.verify_tools(tools)
        if ts.file_identity(binary, 128 * 1024 * 1024) != build['binary']: raise ValueError('Common binary drift')
        with (work / 'worker-bindings.json').open('xb') as target:
            target.write(ts.encode({'identity': report['identity'], 'absolute_deadline': ABSOLUTE_DEADLINE,
                'monotonic_deadline': deadline, 'binary': build['binary'], 'time': tools['time'], 'model': model_id, 'normalization': entries}))
        ts.retain(evidence / 'model-notice.txt', (repository / 'NOTICE.md').read_bytes())
        ts.save(evidence / 'attribution.json', {k: corpus[k] for k in ('license', 'attribution', 'source_card', 'paper', 'source_revision', 'license_url')} | {
            'transformations': 'Full public WAV normalized to peak0.5; original encoding/rate/channels/order/count/metadata retained; no VAD/crop/reference hint.',
            'controls': 'MIT generated full silence/noise; strict empty TXT AND segments unchanged.',
            'native': 'OpenAI Whisper / ggml MIT; no model, wave, private data or secrets in uploaded artifacts.'})
        ts.save(evidence / 'provenance.json', report)
        report['phase'] = 'matched_paired_inference'
        for case, arm in schedule:
            require_budget(deadline, 120); report['current_case'] = {'id': case['id'], 'arm': arm}
            audio = ts.verify_audio(case, roots[case['id']]) if arm == 'baseline' else work / 'normalized' / (case['id'] + '.wav')
            admitted = dict(case, sha256=entries[case['id']]['derived_sha256']) if arm == 'normalized' else case
            results.append(q.run_native(admitted, 'q5_0', audio, binary, model, work, evidence,
                           dict(env, LIP_NORMALIZATION_ARM=arm), pins['model'], worker_script=Path(__file__).resolve()))
        report.pop('current_case', None)
        paired = completion(results, frozen); ts.save(evidence / 'comparison.json', paired)
        report.update({k: value for k, value in paired.items() if k != 'pairs'}); report['phase'] = 'paired_screen_finished'
    except Exception as error:
        report.update(status='incomplete_diagnostic', runtime_success=False, error_type=type(error).__name__,
                      results_count=len(results), execution_order=[{'id': row['id'], 'arm': row['arm'], 'status': row['status']} for row in results])
    report['experiment_elapsed_seconds'] = time.monotonic() - started
    ts.save(evidence / 'completion.json', report); ts.evidence_size(evidence)
    print(ts.encode({'status': report['status'], 'acceptance': 'not_assessed', 'evidence': str(evidence)}).decode().strip())
    return 0 if report['runtime_success'] else 1


if __name__ == '__main__':
    try:
        if sys.argv[1:2] == ['--infer-worker']: infer_worker(sys.argv[2:])
        else: raise SystemExit(main())
    except Exception as error:
        print('Normalization diagnostic failed: ' + type(error).__name__ + '; no acceptance claim.', file=sys.stderr)
        raise SystemExit(1) from None
