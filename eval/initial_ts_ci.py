"""Opt-in hosted initial-timestamp research; no production or Android acceptance."""
from array import array
from copy import deepcopy
import difflib
import hashlib
import json
import math
import os
from pathlib import Path
import random
import re
import resource
import shutil
import signal
import subprocess
import sys
import time
import urllib.request

from benchmark import SCORER_VERSION, score_case, verify_audio
from controls import prepare as prepare_controls
from fetch_corpus import verify_delivery, verify_row
from observe_vad import file_identity, pcm_samples
from qwen17_ci import download, run_logged, verify_admission
from qwen17_cli import load_json, strict_json
from vad_holdouts import (EDGE_SAMPLES, GAIN_DBS, HUMAN_IDS, NOISE_AMPLITUDE, NOISE_SEED,
                          PAUSE_SAMPLES, identity, layout_pcm, quantize_pcm, write_wave)

GIB = 1024 ** 3
OUTPUT_BYTES = 128 * 1024
ARTIFACT_BYTES = 32 * 1024 * 1024
JOB_SECONDS = 175 * 60
REF = 'refs/heads/codex/lip-initial-timestamp'
WORKFLOW_REF = 'ihearttokyo/Lip/.github/workflows/initial-ts-experiment.yml@' + REF
MARKER = '[initial-ts-canary]'
QUIET_CASES_SHA256 = 'c4c7a8c6309f6d9beefe5648ffe2a6b4d5c6fe8d5a14673b485d0c74d745819c'
RUNTIME_REVISION = '306c88f4d1286aec1bf96e544632897886af5501'
SOURCE_PATHS = {'eval/' + name for name in ('corpus.json', 'controls.json', 'benchmark.py',
                'fetch_corpus.py', 'vad_holdouts.py', 'observe_vad.py', 'controls.py',
                'qwen17_ci.py', 'qwen17_cli.py', 'sensevoice_cli.py')}
COMMON_FLAGS = {'BUILD_SHARED_LIBS': 'OFF', 'WHISPER_BUILD_TESTS': 'OFF',
                'WHISPER_BUILD_EXAMPLES': 'ON', 'WHISPER_BUILD_SERVER': 'OFF',
                'WHISPER_CURL': 'OFF', 'WHISPER_SDL2': 'OFF', 'WHISPER_COMMON_FFMPEG': 'OFF',
                'WHISPER_COREML': 'OFF', 'WHISPER_OPENVINO': 'OFF',
                'WHISPER_USE_SYSTEM_GGML': 'OFF', 'GGML_CPU': 'ON',
                'GGML_NATIVE': 'OFF', 'GGML_OPENMP': 'OFF', 'GGML_BLAS': 'OFF',
                'GGML_BACKEND_DL': 'OFF', 'GGML_CPU_ALL_VARIANTS': 'OFF', 'GGML_CCACHE': 'OFF',
                **{'GGML_' + name: 'OFF' for name in ('CUDA', 'MUSA', 'HIP', 'VULKAN', 'WEBGPU',
                   'ZDNN', 'VIRTGPU', 'VIRTGPU_BACKEND', 'METAL', 'ACCELERATE', 'RPC', 'SYCL',
                   'OPENVINO', 'ET', 'ET_SYSEMU', 'OPENCL', 'HEXAGON', 'ZENDNN')}}

# Only instrumentation and this standard-field assignment are added to the pinned CLI.
EARLY_PATCH = '''
    const char * lip_initial_ts = std::getenv("LIP_INITIAL_TS");
    if (!lip_initial_ts || (std::strcmp(lip_initial_ts, "1.0") != 0 && std::strcmp(lip_initial_ts, "30.0") != 0)) {
        fprintf(stderr, "LIP_INITIAL_TS must be exactly 1.0 or 30.0\\n");
        return 64;
    }
'''
PARAM_PATCH = '''            if (wparams.strategy != WHISPER_SAMPLING_GREEDY || wparams.n_threads != 4 ||
                params.n_processors != 1 || cparams.use_gpu || !cparams.flash_attn ||
                cparams.dtw_token_timestamps || wparams.audio_ctx != 0 || !wparams.no_context ||
                wparams.n_max_text_ctx != 224 || wparams.greedy.best_of != 5 ||
                wparams.no_timestamps || wparams.token_timestamps || wparams.translate || wparams.vad ||
                wparams.detect_language || wparams.offset_ms || wparams.duration_ms ||
                !params.prompt.empty() || !params.suppress_regex.empty() || wparams.single_segment ||
                wparams.max_tokens || wparams.max_len || wparams.suppress_nst ||
                wparams.temperature != 0.0f || wparams.temperature_inc != 0.2f ||
                wparams.entropy_thold != 2.4f || wparams.logprob_thold != -1.0f ||
                wparams.no_speech_thold != 0.6f || wparams.max_initial_ts != 1.0f) {
                fprintf(stderr, "Unmatched initial-timestamp research policy\\n");
                return 65;
            }
            wparams.max_initial_ts = std::strcmp(lip_initial_ts, "1.0") == 0 ? 1.0f : 30.0f;
            fprintf(stderr, "LIP_PARAMS {\\"max_initial_ts\\":%.1f,\\"n_threads\\":%d,\\"best_of\\":%d,"
                "\\"audio_ctx\\":%d,\\"n_max_text_ctx\\":%d,\\"no_context\\":%d,\\"no_timestamps\\":%d,"
                "\\"token_timestamps\\":%d,\\"use_gpu\\":%d,\\"flash_attn\\":%d,\\"vad\\":%d,"
                "\\"translate\\":%d,\\"temperature\\":%.9g,\\"temperature_inc\\":%.9g,"
                "\\"entropy_thold\\":%.9g,\\"logprob_thold\\":%.9g,\\"no_speech_thold\\":%.9g,"
                "\\"prompt_bytes\\":%zu,\\"n_processors\\":%d}\\n",
                wparams.max_initial_ts, wparams.n_threads, wparams.greedy.best_of, wparams.audio_ctx,
                wparams.n_max_text_ctx, wparams.no_context, wparams.no_timestamps, wparams.token_timestamps,
                cparams.use_gpu, cparams.flash_attn, wparams.vad, wparams.translate, wparams.temperature,
                wparams.temperature_inc, wparams.entropy_thold, wparams.logprob_thold, wparams.no_speech_thold,
                params.prompt.size(), params.n_processors);

'''
BACKEND_PATCH = """    if (ggml_backend_dev_count() != 1 ||
        ggml_backend_dev_type(ggml_backend_dev_get(0)) != GGML_BACKEND_DEVICE_TYPE_CPU) {
        fprintf(stderr, "Require exactly one registered CPU backend\\n");
        return 66;
    }
"""
CALL_ANCHOR = '            if (whisper_full_parallel(ctx, wparams, pcmf32.data(), pcmf32.size(), params.n_processors) != 0) {'


def encode(value):
    return (json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + '\n').encode('utf-8')


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
        raise ValueError('Require exact unprivileged owned hosted Ubuntu24.04 push identity')
    repo, commit = event.get('repository', {}), event.get('head_commit', {})
    if (repo.get('full_name') != 'ihearttokyo/Lip' or repo.get('private') is not False or
            repo.get('visibility') != 'public' or repo.get('owner', {}).get('login') != 'ihearttokyo' or
            event.get('ref') != REF or event.get('after') != head or commit.get('id') != head or
            not isinstance(message, str) or MARKER not in message or
            commit.get('message', '').rstrip('\n') != message.rstrip('\n')):
        raise ValueError('Public event, actual head and exact case-sensitive opt-in must agree')


def require_capacity(cpus, available_memory, free_disk):
    if (any(type(value) is not int for value in (cpus, available_memory, free_disk)) or
            cpus < 4 or available_memory < 10 * GIB or free_disk < 6 * GIB):
        raise ValueError('Require four CPUs, ten GiB available RAM and six GiB disk; no fallback')


def require_clip_budget(deadline, now):
    if deadline - now < 125:
        raise TimeoutError('Hold remaining clips before the job deadline; reserve receipt/upload headroom')


def arm_value(value):
    if value not in ('1.0', '30.0'):
        raise ValueError('Only the exact two frozen initial-timestamp arms are allowed')
    return float(value)


def verify_sources(repository, pins):
    if pins.get('schema_version') != 1 or pins.get('runtime_revision') != RUNTIME_REVISION or set(pins['source_sha256']) != SOURCE_PATHS:
        raise ValueError('Source pin scope drift')
    for name, digest in pins['source_sha256'].items():
        path = repository / name
        if path.is_symlink() or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise ValueError('Frozen source identity drift: ' + name)
    model = pins['model']
    if model != {'name': 'ggml-large-v3-turbo-q5_0.bin', 'revision': '5359861c739e955e79d9a303bcbc70fb988958b1',
                 'url': 'https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-large-v3-turbo-q5_0.bin',
                 'bytes': 574041195, 'sha256': '394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2'}:
        raise ValueError('Only the frozen public Q5_0 model is allowed')


def validate_quiet(pins, corpus):
    cases = pins['quiet_cases']
    expected = {f'{family}-minus{-gain}db-pause-{position}' for family in HUMAN_IDS
                for gain in GAIN_DBS for position in ('before', 'after')}
    if (len(cases) != 12 or {c['id'] for c in cases} != expected or
            hashlib.sha256(encode(cases)).hexdigest() != QUIET_CASES_SHA256):
        raise ValueError('Require exactly the twelve unchanged frozen public derivatives')
    originals = {c['id']: c for c in corpus['cases']}
    for case in cases:
        source = originals[case['source']['case_id']]
        if (case['origin'] != 'human_recording' or case['license'] != 'CC-BY-4.0' or
                case['audio'] != 'audio/' + case['id'] + '.wav' or
                any(case[k] != source[k] for k in ('language', 'reference_raw', 'checks', 'max_error_rate')) or
                case['source'] != dict(source['source'], case_id=source['id'],
                    audio_sha256=source['sha256'], frozen_audio='sources/' + source['id'] + '.wav')):
            raise ValueError('Quiet references, anchors or full public source identity drifted')


def patch_cli(source, pins):
    if hashlib.sha256(source).hexdigest() != pins['cli_sha256']:
        raise ValueError('CLI does not match the pinned upstream source')
    result = source.decode('utf-8')
    for anchor, replacement in [('#include <cstring>\n', '#include <cstring>\n#include <cstdlib>\n'),
                                ('int main(int argc, char ** argv) {\n', 'int main(int argc, char ** argv) {\n' + EARLY_PATCH),
                                ('    ggml_backend_load_all();\n', '    ggml_backend_load_all();\n' + BACKEND_PATCH),
                                (CALL_ANCHOR, PARAM_PATCH + CALL_ANCHOR)]:
        if result.count(anchor) != 1:
            raise ValueError('Experimental adapter anchor is missing or ambiguous')
        result = result.replace(anchor, replacement)
    return result.encode('utf-8')


def unpatch_cli(source):
    return source.decode('utf-8').replace('#include <cstdlib>\n', '').replace(EARLY_PATCH, '').replace(PARAM_PATCH, '').replace(BACKEND_PATCH, '').encode('utf-8')


def evidence_size(root):
    total = 0
    for path in root.iterdir():
        if path.is_symlink() or not path.is_file() or path.suffix not in ('.json', '.log', '.txt', '.patch'):
            raise ValueError('Evidence must be fixed public text files, never symlinks, audio or models')
        total += path.stat().st_size
    if total > ARTIFACT_BYTES:
        raise ValueError('Text evidence exceeds thirty-two MiB')
    return total


def retain(path, data):
    limit = ARTIFACT_BYTES if path.name == 'completion.json' else ARTIFACT_BYTES - OUTPUT_BYTES
    if path.name == 'completion.json' and len(data) > OUTPUT_BYTES:
        raise ValueError('Completion receipt exceeds its reserved bound')
    if evidence_size(path.parent) + len(data) > limit:
        raise ValueError('Text evidence would exceed thirty-two MiB')
    with path.open('xb') as target:
        target.write(data)


def save(path, value):
    retain(path, encode(value))


def owned_directories(runner_temp):
    runner_temp = runner_temp.resolve(strict=True)
    work, evidence = runner_temp / 'lip-initial-ts-work', runner_temp / 'lip-initial-ts-evidence'
    if work.exists() or work.is_symlink() or evidence.exists() or evidence.is_symlink():
        raise FileExistsError('Refuse to reuse any existing experiment paths')
    evidence.mkdir(); work.mkdir()
    return work, evidence


def prepare_quiet(corpus_path, output, pins):
    corpus = load_json(corpus_path)
    validate_quiet(pins, corpus)
    output.mkdir(); (output / 'audio').mkdir(); (output / 'sources').mkdir()
    originals = {c['id']: c for c in corpus['cases']}
    rng = random.Random(NOISE_SEED)
    noise = array('h', (rng.randint(-NOISE_AMPLITUDE, NOISE_AMPLITUDE) for _ in range(PAUSE_SAMPLES)))
    if sys.byteorder != 'little': noise.byteswap()
    noise_pcm = noise.tobytes()
    noise_path = output / 'sources/seeded-noise-20s.wav'
    write_wave(noise_path, noise_pcm)
    _, noise_id = identity(noise_path)
    cases = deepcopy(pins['quiet_cases'])
    for family in HUMAN_IDS:
        original = originals[family]
        audio = verify_audio(original, corpus_path.parent)
        data, source_id = identity(audio)
        if source_id != {k: original[k] for k in ('size_bytes', 'sha256')}:
            raise ValueError('Source changed after verification')
        samples = pcm_samples(data)
        if len(samples) != original['expected_num_samples']:
            raise ValueError('Full source sample count drift')
        with (output / 'sources' / (family + '.wav')).open('xb') as source: source.write(data)
        for case in (c for c in cases if c['source']['case_id'] == family):
            transform = case['transform']
            if noise_id != {k: transform['noise'][k] for k in ('size_bytes', 'sha256')}:
                raise ValueError('Seeded noise identity drift')
            attenuated, clipped = quantize_pcm(samples, transform['gain_db'])
            pcm, start, end = layout_pcm(attenuated, transform['pause_position'], noise_pcm)
            if clipped != 0 or [start, end] != case['truth']['source_envelope_samples'] or len(pcm) // 2 != len(samples) + PAUSE_SAMPLES + EDGE_SAMPLES:
                raise ValueError('Unchanged full source envelope or PCM count drift')
            path = output / case['audio']; write_wave(path, pcm)
            verify_audio(case, output)
    manifest = {'schema_version': 1, 'id': 'lip-initial-ts-public-quiet-v1',
                'full_frozen_manifest_sha256': pins['full_quiet_manifest_sha256'], 'cases': cases}
    with (output / 'manifest.json').open('xb') as target: target.write(encode(manifest))
    return manifest


def acquire_corpus(repository, work, pins):
    # Reuse admitted row/delivery validators; bound metadata too, unlike the older fetch entrypoint.
    original = load_json(repository / 'eval/corpus.json')
    if len(original['cases']) != 18 or sum(c['size_bytes'] for c in original['cases']) != 9615124:
        raise ValueError('Only the unchanged complete eighteen-file public corpus is allowed')
    root = work / 'original'; root.mkdir(); (root / 'audio').mkdir()
    deadline = time.monotonic() + 600
    if signal.getitimer(signal.ITIMER_REAL) != (0.0, 0.0):
        raise ValueError('Refuse to replace an existing acquisition deadline')
    def expired(_signal, _frame): raise TimeoutError('Corpus acquisition deadline exceeded')
    previous = signal.signal(signal.SIGALRM, expired)
    signal.setitimer(signal.ITIMER_REAL, 600)
    def retrieve(url, cap, method='GET'):
        remaining = deadline - time.monotonic()
        if remaining <= 0: raise TimeoutError('Corpus acquisition deadline exceeded')
        try:
            with urllib.request.urlopen(urllib.request.Request(url, method=method), timeout=min(40, remaining)) as response:
                data = response.read(cap + 1)
                headers = dict(response.headers)
        except (OSError, ValueError):
            raise ValueError('Public corpus delivery failed; URLs and headers withheld') from None
        if len(data) > cap or time.monotonic() > deadline:
            raise ValueError('Corpus delivery exceeds byte/time bound')
        return data, {k.lower(): v for k, v in headers.items()}
    try:
        for config in sorted({c['source']['config'] for c in original['cases']}):
            metadata = None
            for length in (36, 35):
                try:
                    data, _ = retrieve('https://datasets-server.huggingface.co/rows?dataset=google/fleurs&config=' + config +
                                       '&split=validation&offset=0&length=' + str(length), 2 * 1024 * 1024)
                    metadata = json.loads(data); break
                except ValueError:
                    pass
            if metadata is None: raise ValueError('Complete pinned metadata is unavailable')
            rows = {item['row_idx']: item['row'] for item in metadata['rows']}
            if len(rows) != len(metadata['rows']): raise ValueError('Duplicate source rows')
            for case in (c for c in original['cases'] if c['source']['config'] == config):
                index = case['source']['row_index']; url = verify_row(case, rows[index], index)
                _, head = retrieve(url, 0, 'HEAD')
                data, get = retrieve(url, case['size_bytes'])
                if int(head.get('content-length', '0')) != case['size_bytes'] or int(get.get('content-length', '0')) != case['size_bytes']:
                    raise ValueError('Complete corpus delivery size mismatch')
                verify_delivery(case, data, head.get('etag'), get.get('etag'))
                path = root / case['audio']
                if path.parent != root / 'audio' or path.name != case['id'] + '.wav': raise ValueError('Audio target escapes allowlist')
                with path.open('xb') as target: target.write(data)
                verify_audio(case, root)
                if len(pcm_samples(data)) != case['expected_num_samples']: raise ValueError('Audio sample count drift')
        (root / 'corpus.json').write_bytes(encode(original))
        verify_admission(load_json(repository / 'eval/corpus.json'), load_json(root / 'corpus.json'))
        return root / 'corpus.json'
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0); signal.signal(signal.SIGALRM, previous)


def git(repository, *args):
    return subprocess.check_output(['git', '-C', str(repository), *args], timeout=10, text=True).strip()


def preflight(repository):
    # This guard runs before querying tools, allocating paths, or acquiring/building anything.
    if sys.platform != 'linux' or os.geteuid() == 0:
        raise ValueError('Experimental ELF/model execution is hosted Linux only')
    release = dict(line.split('=', 1) for line in Path('/etc/os-release').read_text().splitlines() if '=' in line)
    event = load_json(os.environ['GITHUB_EVENT_PATH'])
    head, message = git(repository, 'rev-parse', 'HEAD'), git(repository, 'log', '-1', '--format=%B')
    require_environment(os.environ, event, head, message, sys.platform, os.geteuid(),
                        release.get('VERSION_ID', '').strip('"') if release.get('ID') == 'ubuntu' else '')
    if git(repository, 'status', '--porcelain', '--untracked-files=all'):
        raise ValueError('Require a clean actual SHA checkout, including recursive vendor')
    pins = load_json(repository / 'eval/initial-ts-pins.json'); verify_sources(repository, pins)
    corpus = load_json(repository / 'eval/corpus.json'); validate_quiet(pins, corpus)
    vendor = repository / 'third_party/whisper.cpp'
    if git(vendor, 'rev-parse', 'HEAD') != pins['runtime_revision'] or git(vendor, 'status', '--porcelain', '--untracked-files=all'):
        raise ValueError('Vendor must be the pristine pinned recursive checkout')
    if git(repository, 'ls-tree', 'HEAD', 'third_party/whisper.cpp').split()[2] != pins['runtime_revision']:
        raise ValueError('Actual repository gitlink differs from the vendor pin')
    patch_cli((vendor / 'examples/cli/cli.cpp').read_bytes(), pins)
    runner_temp = Path(os.environ['RUNNER_TEMP']).resolve(strict=True)
    if runner_temp == Path('/') or runner_temp.is_relative_to(repository): raise ValueError('Runner temp must be outside source')
    mem = re.search(r'^MemAvailable:\s+(\d+) kB$', Path('/proc/meminfo').read_text(), re.M)
    capacity = {'logical_cpus': os.cpu_count(), 'affinity_cpus': len(os.sched_getaffinity(0)),
                'available_memory_bytes': int(mem[1]) * 1024 if mem else 0,
                'free_disk_bytes': shutil.disk_usage(runner_temp).free}
    require_capacity(min(capacity['logical_cpus'], capacity['affinity_cpus']), capacity['available_memory_bytes'], capacity['free_disk_bytes'])
    return pins, runner_temp, capacity


def tree_identity(source):
    rows = []
    for path in sorted(source.rglob('*')):
        if path.is_symlink(): raise ValueError('Copied native source cannot contain symlinks')
        if path.is_file(): rows.append(path.relative_to(source).as_posix() + ':' + file_identity(path, 32 * 1024 * 1024)['sha256'])
    return {'file_count': len(rows), 'sha256': hashlib.sha256(('\n'.join(rows) + '\n').encode()).hexdigest(),
            'format': 'SHA256 of sorted relative-path:SHA256 newline records'}


def freeze_tools():
    tools = {}
    for name, command in {'cmake': ['/usr/bin/cmake', '--version'], 'ninja': ['/usr/bin/ninja', '--version'],
                          'cc': ['/usr/bin/gcc-13', '--version'], 'cxx': ['/usr/bin/g++-13', '--version'],
                          'time': ['/usr/bin/time', '--version'], 'python': [sys.executable, '--version']}.items():
        executable = Path(command[0]).resolve(strict=True)
        version = subprocess.check_output(command, timeout=10, stderr=subprocess.STDOUT).decode('utf-8')
        if len(version.encode()) > 4096: raise ValueError('Tool version diagnostic exceeds bound')
        tools[name] = dict(file_identity(executable, 128 * 1024 * 1024), version=version, argv=command)
    return tools


def verify_tools(tools):
    for tool in tools.values():
        if any(file_identity(Path(tool['path']), 128 * 1024 * 1024)[k] != tool[k] for k in ('bytes', 'sha256')):
            raise ValueError('Frozen installed tool changed before inference')


def build_cli(repository, work, evidence, pins, env, tools):
    vendor, source, build = repository / 'third_party/whisper.cpp', work / 'source', work / 'build'
    shutil.copytree(vendor, source, ignore=shutil.ignore_patterns('.git'))
    before = tree_identity(source)
    cli = source / 'examples/cli/cli.cpp'; pristine = cli.read_bytes(); adapted = patch_cli(pristine, pins)
    if unpatch_cli(adapted) != pristine: raise ValueError('Adapter changed more than its exact instrumentation')
    cli.write_bytes(adapted)
    retain(evidence / 'upstream-cli.txt', pristine); retain(evidence / 'experimental-cli.txt', adapted)
    patch_text = ''.join(difflib.unified_diff(pristine.decode().splitlines(True), adapted.decode().splitlines(True),
                         fromfile='examples/cli/cli.cpp', tofile='examples/cli/cli.cpp'))
    retain(evidence / 'initial-ts.patch', patch_text.encode())
    retain(evidence / 'whisper-license.txt', (vendor / 'LICENSE').read_bytes())
    commands = [[tools['cmake']['argv'][0], '-S', str(source), '-B', str(build), '-G', 'Ninja',
                 '-DCMAKE_BUILD_TYPE=Release', '-DCMAKE_C_COMPILER=' + tools['cc']['argv'][0],
                 '-DCMAKE_CXX_COMPILER=' + tools['cxx']['argv'][0], '-DCMAKE_MAKE_PROGRAM=' + tools['ninja']['argv'][0],
                 *['-D' + k + '=' + v for k, v in COMMON_FLAGS.items()]],
                [tools['cmake']['argv'][0], '--build', str(build), '--target', 'whisper-cli', '-j', '2']]
    deadline = time.monotonic() + 900
    with (evidence / 'build.log').open('xb') as log:
        for command in commands: run_logged(command, env, log, timeout=max(.001, deadline - time.monotonic()), byte_limit=1024 * 1024)
    cache_bytes = (build / 'CMakeCache.txt').read_bytes()
    if len(cache_bytes) > 256 * 1024: raise ValueError('CMake cache exceeds its bound')
    cache = dict(re.findall(r'^([^/#\n][^:\n]*):[^=\n]+=(.*)$', cache_bytes.decode(), re.M))
    if any(cache.get(k) != v for k, v in COMMON_FLAGS.items()) or cache.get('CMAKE_BUILD_TYPE') != 'Release':
        raise ValueError('Actual host backend/thread CMake policy differs from the frozen CPU-only flags')
    compiled_bytes = (build / 'compile_commands.json').read_bytes()
    if len(compiled_bytes) > 2 * 1024 * 1024: raise ValueError('Compiler database exceeds its bound')
    compiled = json.loads(compiled_bytes)
    rows = [row for row in compiled if Path(row['file']).name == 'cli.cpp']
    if len(rows) != 1 or Path(rows[0]['file']).resolve() != cli.resolve():
        raise ValueError('Experimental CLI translation unit was not selected')
    binary = build / 'bin/whisper-cli'
    with binary.open('rb') as stream:
        if stream.read(4) != b'\x7fELF': raise ValueError('Expected the newly built Linux ELF only')
    retain(evidence / 'cmake-cache.txt', cache_bytes); retain(evidence / 'compile-commands.json', compiled_bytes)
    return binary, {'source_revision': pins['runtime_revision'], 'pristine_source_tree': before,
        'adapted_source_tree_postconfigure': tree_identity(source), 'pristine_cli_sha256': pins['cli_sha256'],
        'adapted_cli_sha256': hashlib.sha256(adapted).hexdigest(), 'patch_sha256': hashlib.sha256(patch_text.encode()).hexdigest(),
        'binary': file_identity(binary, 128 * 1024 * 1024), 'commands': commands, 'actual_cache_policy': {k: cache[k] for k in COMMON_FLAGS},
        'tools': tools, 'cpu_only': True, 'android_scheduler_patch': 'not_applied_not_evaluated',
        'policy': 'Decoder parameters match the pinned JNI GREEDY policy; this is not Android runtime acceptance.'}


def cli_command(binary, model, audio, language, output):
    if language not in ('en', 'ja', 'zh'): raise ValueError('Unadmitted language')
    return [str(binary), '-m', str(model), '-f', str(audio), '-l', language, '-ng', '-t', '4',
            '-p', '1', '-bs', '1', '-bo', '5', '-mc', '224', '-ac', '0', '-otxt', '-oj', '-of', str(output)]


def expected_params(arm):
    return {'max_initial_ts': arm_value(arm), 'n_threads': 4, 'best_of': 5, 'audio_ctx': 0,
            'n_max_text_ctx': 224, 'no_context': 1, 'no_timestamps': 0, 'token_timestamps': 0,
            'use_gpu': 0, 'flash_attn': 1, 'vad': 0, 'translate': 0, 'temperature': 0,
            'temperature_inc': .2, 'entropy_thold': 2.4, 'logprob_thold': -1,
            'no_speech_thold': .6, 'prompt_bytes': 0, 'n_processors': 1}


def parse_outputs(text_bytes, json_bytes, language, model):
    if len(text_bytes) > OUTPUT_BYTES or len(json_bytes) > OUTPUT_BYTES:
        raise ValueError('Transcript or standard JSON exceeded128KiB')
    raw = text_bytes.decode('utf-8').strip()
    doc = strict_json(json_bytes)
    if doc.get('params') != {'model': model, 'language': language, 'translate': False} or doc.get('result') != {'language': language}:
        raise ValueError('Standard CLI JSON language/model policy mismatch')
    segments = doc.get('transcription')
    if not isinstance(segments, list) or len(segments) > 1024:
        raise ValueError('Standard CLI segments are missing or excessive')
    texts = []
    for segment in segments:
        if (not isinstance(segment, dict) or set(segment) != {'text', 'timestamps', 'offsets'} or
                not isinstance(segment['text'], str) or set(segment['offsets']) != {'from', 'to'} or
                any(type(value) is not int or not 0 <= value <= 30000 for value in segment['offsets'].values()) or
                segment['offsets']['from'] > segment['offsets']['to']):
            raise ValueError('Incomplete standard non-full segment output')
        timestamps = {key: f'00:00:{value // 1000:02d},{value % 1000:03d}'
                      for key, value in segment['offsets'].items()}
        if segment['timestamps'] != timestamps:
            raise ValueError('Standard timestamp labels are incomplete or differ from offsets')
        texts.append(segment['text'].lstrip(' \t') + '\n')
    if ''.join(texts).encode('utf-8') != text_bytes:
        raise ValueError('Raw text and unfiltered standard JSON segments disagree')
    return raw, segments


def run_native(case, arm, audio, binary, model, work, evidence, env):
    arm_value(arm)
    label = case['id'] + '-' + arm
    if not re.fullmatch(r'[A-Za-z0-9_-]+-(?:1|30)\.0', label): raise ValueError('Output label escapes allowlist')
    outputs = work / label; outputs.mkdir()
    stem = outputs / 'transcript'
    command = [sys.executable, '-B', str(Path(__file__).resolve()), '--infer-worker',
               str(binary), str(model), str(audio), case['language'], str(stem)]
    result = {'id': case['id'], 'arm': arm, 'language': case['language'], 'audio_sha256': case['sha256'],
              'origin': case['origin'], 'command': cli_command(binary, model, audio, case['language'], stem),
              'timing_scope': 'cold_per_clip_CLI_not_warm_Android', 'address_space_bytes': 8 * GIB,
              'clip_timeout_seconds': 120, 'confidence': 'not_in_standard_non_full_JSON', 'decoder_trace': 'not_collected'}
    started = time.perf_counter()
    cleanup_failed = False
    try:
        # Logs are produced in private work first so every retained file passes the aggregate evidence cap.
        with (outputs / 'native.log').open('xb') as log:
            run_logged(command, dict(env, LIP_INITIAL_TS=arm, RUNNER_TEMP=str(work.parent)), log,
                       timeout=120, byte_limit=32 * 1024)
        text_bytes = (stem.with_suffix('.txt')).read_bytes()
        json_bytes = (stem.with_suffix('.json')).read_bytes()
        raw, segments = parse_outputs(text_bytes, json_bytes, case['language'], str(model))
        lines = (outputs / 'native.log').read_bytes().splitlines()
        actual = [strict_json(line[len(b'LIP_PARAMS '):]) for line in lines if line.startswith(b'LIP_PARAMS ')]
        expected = expected_params(arm)
        if (len(actual) != 1 or set(actual[0]) != set(expected) or
                any(type(actual[0][k]) not in (int, float) or not math.isfinite(actual[0][k]) or
                    not math.isclose(actual[0][k], v, abs_tol=2e-7, rel_tol=0) for k, v in expected.items())):
            raise ValueError('Actual decoder parameter receipt differs from the matched policy')
        peak_kib = int((outputs / 'peak-kib.txt').read_text().strip())
        if not 0 < peak_kib * 1024 <= 8 * GIB: raise ValueError('Peak RSS is missing or outside its bound')
        result.update(status='ok', raw=raw, score=score_case(case, raw), segments=segments,
                      actual_params=actual[0], peak_rss_bytes=peak_kib * 1024)
    except subprocess.TimeoutExpired as error:
        cleanup_failed = error.timeout == 5  # stop_owned_group's fixed join bound, not the120s clip deadline.
        result['status'] = 'cleanup_timeout' if cleanup_failed else 'timeout'
    except subprocess.CalledProcessError as error:
        result.update(status='engine_error', exit_code=error.returncode)
    except (ValueError, OSError, KeyError, TypeError) as error:
        result.update(status='invalid_input_or_output', error_type=type(error).__name__)
    finally:
        result['elapsed_seconds'] = time.perf_counter() - started
        for name, suffix in [('native.log', '.log'), ('transcript.txt', '-raw.txt'),
                             ('transcript.json', '-segments.json'), ('peak-kib.txt', '-peak.txt')]:
            path = outputs / name
            if path.exists():
                if path.is_symlink() or path.stat().st_size > OUTPUT_BYTES: raise ValueError('Runtime output exceeded its retained bound')
                retain(evidence / (label + suffix), path.read_bytes())
        # A failed start still leaves explicit empty diagnostics and its failure status.
        if not (evidence / (label + '.log')).exists(): retain(evidence / (label + '.log'), b'')
        save(evidence / (label + '-score.json'), result)
    if cleanup_failed:
        raise RuntimeError('Owned process group could not be joined; remaining pairs held')
    return result


def paired_schedule(cases):
    if len(cases) != 36 or len({c['id'] for c in cases}) != 36:
        raise ValueError('Matched research requires all36 unique18+12+6 inputs')
    return [(case, arm) for index, case in enumerate(sorted(cases, key=lambda c: c['id']))
            for arm in (('1.0', '30.0') if index % 2 == 0 else ('30.0', '1.0'))]


def completion(results):
    keys = {(r['id'], r['arm']) for r in results}
    complete = (len(results) == 72 and len(keys) == 72 and
                len({r['id'] for r in results}) == 36 and
                all(r.get('status') == 'ok' and r['arm'] in ('1.0', '30.0') for r in results))
    return {'schema_version': 1, 'status': 'complete_diagnostic' if complete else 'incomplete_diagnostic',
            'acceptance': 'not_assessed', 'quality_passed': complete and all(
                r.get('score', {}).get('passed') is True and
                (r.get('origin') != 'negative_control' or (r.get('raw') == '' and r.get('segments') == []))
                for r in results),
            'results_count': len(results), 'execution_order': [{'id': r['id'], 'arm': r['arm'], 'status': r['status']} for r in results],
            'promotion': 'Parent independently recomputes scores and checks nonregression, strict quiet and phone safeguards.',
            'production_policy_parity': True, 'production_policy_gap': None,
            'production_policy_scope': 'Decoder parameters only; hosted CPU execution is not Android runtime acceptance.'}


def infer_worker(args):
    # No model or native library can load in local Mac/static/test paths.
    if (sys.platform != 'linux' or os.geteuid() == 0 or os.environ.get('GITHUB_ACTIONS') != 'true' or
            os.environ.get('RUNNER_OS') != 'Linux' or os.environ.get('GITHUB_REF') != REF or
            os.environ.get('RUNNER_ENVIRONMENT') != 'github-hosted' or os.environ.get('GITHUB_REPOSITORY') != 'ihearttokyo/Lip'):
        raise ValueError('Native worker is unprivileged public hosted Linux only')
    arm_value(os.environ.get('LIP_INITIAL_TS'))
    if len(args) != 5: raise ValueError('Fixed native worker arguments required')
    binary, model, audio, language, stem = args
    root = Path(os.environ['RUNNER_TEMP']).resolve(strict=True) / 'lip-initial-ts-work'
    for value in (binary, model, audio, stem):
        if not Path(value).resolve().is_relative_to(root): raise ValueError('Native worker path escapes fresh owned work')
    resource.setrlimit(resource.RLIMIT_AS, (8 * GIB, 8 * GIB))
    resource.setrlimit(resource.RLIMIT_FSIZE, (OUTPUT_BYTES, OUTPUT_BYTES))
    command = ['/usr/bin/time', '-f', '%M', '-o', str(Path(stem).parent / 'peak-kib.txt'),
               *cli_command(binary, model, audio, language, stem)]
    os.execve(command[0], command, os.environ)


def main():
    repository = Path(__file__).resolve().parent.parent
    pins, runner_temp, capacity = preflight(repository)
    if sys.argv[1:] == ['--preflight']:
        print('Exact public hosted experiment preflight passed; no acquisition/build/inference ran.')
        return 0
    if sys.argv[1:]: raise ValueError('Only preflight or the complete frozen experiment is supported')
    work, evidence = owned_directories(runner_temp)
    started = time.monotonic(); deadline = started + JOB_SECONDS
    report = {'schema_version': 1, 'status': 'incomplete_diagnostic', 'acceptance': 'not_assessed', 'capacity': capacity,
              'identity': {k: os.environ.get(k) for k in ('GITHUB_SHA', 'GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT',
                  'GITHUB_WORKFLOW_REF', 'RUNNER_OS', 'RUNNER_ARCH', 'RUNNER_ENVIRONMENT', 'ImageOS', 'ImageVersion')},
              'limits': {'artifact_bytes': ARTIFACT_BYTES, 'model_bytes': pins['model']['bytes'],
                  'corpus_audio_bytes': 9615124, 'metadata_per_request_bytes': 2 * 1024 * 1024,
                  'metadata_max_requests': 6, 'corpus_timeout_seconds': 600, 'model_timeout_seconds': 600,
                  'build_timeout_seconds': 900, 'clip_timeout_seconds': 120, 'address_space_bytes': 8 * GIB,
                  'experiment_wall_seconds': JOB_SECONDS, 'hosted_job_minutes': 180},
              'scorer_version': SCORER_VERSION, 'source_sha256': pins['source_sha256'],
              'pins_sha256': hashlib.sha256((repository / 'eval/initial-ts-pins.json').read_bytes()).hexdigest(),
              'runner_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
    results = []
    try:
        report['phase'] = 'freeze_installed_tools'
        tools = freeze_tools(); report['tools'] = tools
        env = {'PATH': '/usr/bin:/bin', 'HOME': str(work), 'LANG': 'C.UTF-8',
               'GITHUB_ACTIONS': 'true', 'RUNNER_OS': 'Linux', 'GITHUB_REF': REF,
               'RUNNER_ENVIRONMENT': 'github-hosted', 'GITHUB_REPOSITORY': 'ihearttokyo/Lip',
               'OMP_NUM_THREADS': '4', 'OPENBLAS_NUM_THREADS': '1'}
        report['phase'] = 'build_pinned_experimental_CLI'
        binary, build = build_cli(repository, work, evidence, pins, env, tools)
        report['build'] = build; save(evidence / 'build.json', build)
        report['phase'] = 'complete_pinned_model_acquisition'
        model = work / pins['model']['name']; download(pins['model'], model)
        report['model'] = dict(file_identity(model, pins['model']['bytes']), revision=pins['model']['revision'])
        report['phase'] = 'complete_public_corpus_acquisition'
        corpus_path = acquire_corpus(repository, work, pins); corpus = load_json(corpus_path)
        report['phase'] = 'verify_deterministic_quiet_and_controls'
        quiet_root = work / 'quiet'; quiet = prepare_quiet(corpus_path, quiet_root, pins)
        controls_root = work / 'controls'; controls_root.mkdir(); prepare_controls(controls_root)
        if hashlib.sha256((controls_root / 'controls.json').read_bytes()).hexdigest() != pins['source_sha256']['eval/controls.json']:
            raise ValueError('Generated controls differ from the frozen manifest')
        controls = load_json(controls_root / 'controls.json')
        save(evidence / 'original-manifest.json', corpus); save(evidence / 'quiet-manifest.json', quiet)
        save(evidence / 'controls-manifest.json', controls)
        save(evidence / 'attribution.json', {k: corpus[k] for k in ('license', 'attribution', 'source_card', 'paper', 'source_revision', 'license_url')} | {
            'transformations': 'Untrimmed public source; fixed -24/-42dB ties-even PCM16; concatenate seeded655-amplitude noise20s/0.5s before/after; no additive noise or normalization.',
            'controls': 'MIT generated silence and seeded uniform noise; no speech synthesis.',
            'native': 'MIT ggml authors; upstream license retained; no Android scheduler cancellation qualification.'})
        cases, roots = [], {}
        for manifest, root in ((corpus, corpus_path.parent), (quiet, quiet_root), (controls, controls_root)):
            if not 1 <= len(manifest['cases']) <= 32: raise ValueError('Keep existing per-manifest32-case bound')
            for case in manifest['cases']:
                verify_audio(case, root); roots[case['id']] = root; cases.append(case)
        schedule = paired_schedule(cases); verify_tools(tools)
        if file_identity(binary, 128 * 1024 * 1024) != build['binary'] or report['model']['sha256'] != pins['model']['sha256']:
            raise ValueError('Model/binary changed before both arms')
        save(evidence / 'provenance.json', report)
        report['phase'] = 'matched_paired_inference'
        for case, arm in schedule:
            require_clip_budget(deadline, time.monotonic())
            results.append(run_native(case, arm, verify_audio(case, roots[case['id']]), binary, model, work, evidence, env))
        report.update(completion(results))
        report['phase'] = 'paired_comparison_finished'
    except Exception as error:
        # Provider exceptions may include signed URLs; retain only safe exception classes.
        report.update(status='incomplete_diagnostic', error_type=type(error).__name__, execution_order=[{'id': r['id'], 'arm': r['arm'], 'status': r['status']} for r in results])
    report['experiment_elapsed_seconds'] = time.monotonic() - started
    save(evidence / 'completion.json', report)
    evidence_size(evidence)
    print(json.dumps({'status': report['status'], 'acceptance': 'not_assessed', 'evidence': str(evidence)}))
    return 0 if report['status'] == 'complete_diagnostic' else 1


if __name__ == '__main__':
    try:
        if sys.argv[1:2] == ['--infer-worker']:
            infer_worker(sys.argv[2:])
        else:
            raise SystemExit(main())
    except Exception as error:
        print('Initial-timestamp experiment failed: ' + type(error).__name__ + '; no acceptance claim.', file=sys.stderr)
        raise SystemExit(1) from None
