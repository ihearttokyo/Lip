"""Opt-in hosted ARM64/API33 same-binary CLI package; no runtime acceptance."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import resource
import shlex
import shutil
import signal
import struct
import sys
import time
import zipfile

import vulkan_build_ci as vb
import vulkan_preflight as vp
from qwen17_cli import strict_json

REF = 'refs/heads/codex/lip-vulkan-android'
MARKER = '[vulkan-android-bench]'
ENVIRONMENT = {**vb.ENVIRONMENT, 'GITHUB_REF': REF,
    'GITHUB_WORKFLOW_REF': 'ihearttokyo/Lip/.github/workflows/vulkan-android-bench.yml@' + REF}
ACCEPTANCE = dict(android_or_gpu_acceptance=False, gpu_execution_acceptance=False,
                  quality_acceptance=False, latency_acceptance=False, cancellation_acceptance=False)
CLI_SHA = '840f331f80a98c41fc21eb4cf109c4c6a5496b8f248e9bbce58dd733dece76b2'
SOURCE_PINS = {
    'third_party/whisper.cpp/examples/cli/cli.cpp': CLI_SHA,
    'third_party/whisper.cpp/examples/miniaudio.h': 'c029554572ae71a94b7825ef1ab9bd7e646344a79e2e852a03f8b18e428c1443',
    'app/src/main/cpp/patches/whisper-scheduler-abort.json': 'c53d9166c8a4bb02166aba432b46631415cb26dc92baad8fb8996de6b3f64048',
    'app/src/main/java/dev/lip/speech/PcmWindow.kt': 'b4214dbf18888f06525bf43928b0f55bb8b82b2db53cb393678243dd8e0c3e7f',
    'app/src/androidTest/java/dev/lip/LocalAsrRunner.kt': '68e93d689ca935116ae4a21aaf2b0c1878f3d0c14921b75bb3d6d4b2041d8791',
    'third_party/whisper.cpp/examples/common-whisper.cpp': '852fbc77d2461322a82b9c571cf4703bac3c78c5c51d3a90e80792ce0c04e313',
    'app/src/main/cpp/whisper_jni.cpp': '7f6e5ed3587b7ed1bb7a1cb4da81290caef35db8042e9d36e7a7c823727b2d04',
    'app/src/main/cpp/patches/whisper-scheduler-abort.cmake': '922077b169161b3ad0a3e02bf05534d4f8411e834fafe5424d3eb28713ec0729',
    'app/src/main/cpp/patches/whisper-scheduler-abort.patch': 'ce3cc61128694ef7b8f31965a32251f24751db5e87e12c82c8be71a95cf669c4',
    'third_party/whisper.cpp/src/whisper.cpp': '00d02d64b6818ef24ab97cc2184794634668e7cb0343ede2791d78a3f651dc45'}
CANCEL_SHA = '4cee50443113fab137c0ae6c93fcf5a701c92c600588ba7f1bc840ed3dfacdd7'
SYSTEM = {'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so', 'libvulkan.so'}
LIBRARY = r'lib[A-Za-z0-9_+.-]+\.so(?:\.[0-9]+)*'


def expected_params(arm):
    if arm not in ('cpu', 'gpu'):
        raise vp.DiagnosticError('Require exact cpu or gpu arm')
    # Same receipt fields as the initial-ts adapter, plus explicit sampling strategy.
    return dict(max_initial_ts=1.0, n_threads=4, best_of=5, audio_ctx=0, n_max_text_ctx=224,
        no_context=1, no_timestamps=0, token_timestamps=0, use_gpu=int(arm == 'gpu'), flash_attn=1,
        vad=0, translate=0, temperature=0, temperature_inc=.2, entropy_thold=2.4,
        logprob_thold=-1, no_speech_thold=.6, prompt_bytes=0, n_processors=1, strategy='GREEDY')


def verify_params(actual, arm):
    expected = expected_params(arm)
    if not isinstance(actual, dict) or actual.keys() != expected.keys():
        raise vp.DiagnosticError('Unmatched decoder parameter receipt')
    for key, value in expected.items():
        observed = actual[key]
        if isinstance(value, str):
            matched = type(observed) is str and observed == value
        elif isinstance(value, float):
            matched = type(observed) in (int, float) and math.isfinite(observed) and math.isclose(observed, value, abs_tol=2e-7, rel_tol=0)
        else:
            matched = type(observed) is int and observed == value
        if not matched:
            raise vp.DiagnosticError('Unmatched decoder parameter receipt: ' + key)


def verify_receipts(text, arm, samples):
    if len(text.encode()) > vb.LOG_LIMIT or type(samples) is not int or not 0 < samples <= 480000:
        raise vp.DiagnosticError('Unbounded or unmatched native receipt')
    params = [strict_json(line[len('LIP_PARAMS '):].encode()) for line in text.splitlines() if line.startswith('LIP_PARAMS ')]
    pcm = [strict_json(line[len('LIP_PCM '):].encode()) for line in text.splitlines() if line.startswith('LIP_PCM ')]
    expected_pcm = dict(samples=samples, full_pcm=1, signed_pcm16_div32768=1)
    if len(params) != 1 or len(pcm) != 1 or pcm[0] != expected_pcm or any(type(v) is not int for v in pcm[0].values()):
        raise vp.DiagnosticError('Require one matched complete native PCM/parameter receipt')
    verify_params(params[0], arm)
    return dict(parameters=params[0], pcm=pcm[0])


def cli_command(binary, model, audio, language, output, arm):
    expected_params(arm)
    if language not in ('en', 'ja', 'zh'):
        raise vp.DiagnosticError('Require fixed supported language')
    return list(map(str, [binary, '-m', model, '-f', audio, '-l', language,
        *(['-ng'] if arm == 'cpu' else []), '-t', '4', '-p', '1', '-bs', '1', '-bo', '5',
        '-mc', '224', '-ac', '0', '-otxt', '-oj', '-of', output]))


def replace_once(text, anchor, replacement):
    if text.count(anchor) != 1:
        raise vp.DiagnosticError('Missing or ambiguous source patch anchor')
    return text.replace(anchor, replacement, 1)


EARLY = r'''
    const char * lip_arm = std::getenv("LIP_BENCH_ARM");
    if (!lip_arm || (std::strcmp(lip_arm, "cpu") && std::strcmp(lip_arm, "gpu"))) return 64;
'''
REGISTRY = r'''
    bool lip_cpu_registry = false;
    for (size_t lip_r = 0; lip_r < ggml_backend_reg_count(); ++lip_r) {
        const char * lip_name = ggml_backend_reg_name(ggml_backend_reg_get(lip_r));
        if (std::strcmp(lip_name, "CPU") == 0) lip_cpu_registry = true;
        else if (std::strcmp(lip_name, "Vulkan") != 0) return 66;
    }
    if (!lip_cpu_registry) return 66;
'''
# The initial-ts assertion is preserved; only use_gpu is varied, never max_initial_ts.
PARAM = r'''            if (wparams.strategy != WHISPER_SAMPLING_GREEDY || wparams.n_threads != 4 ||
                params.n_processors != 1 || cparams.use_gpu != (std::strcmp(lip_arm, "gpu") == 0) || !cparams.flash_attn ||
                cparams.dtw_token_timestamps || wparams.audio_ctx != 0 || !wparams.no_context ||
                wparams.n_max_text_ctx != 224 || wparams.greedy.best_of != 5 ||
                wparams.no_timestamps || wparams.token_timestamps || wparams.translate || wparams.vad ||
                wparams.detect_language || wparams.offset_ms || wparams.duration_ms ||
                !params.prompt.empty() || !params.suppress_regex.empty() || wparams.single_segment ||
                wparams.max_tokens || wparams.max_len || wparams.suppress_nst ||
                wparams.temperature != 0.0f || wparams.temperature_inc != 0.2f ||
                wparams.entropy_thold != 2.4f || wparams.logprob_thold != -1.0f ||
                wparams.no_speech_thold != 0.6f || wparams.max_initial_ts != 1.0f ||
                wparams.n_grammar_rules || params.diarize || params.tinydiarize ||
                params.fname_inp.size() != 1 || !whisper_is_multilingual(ctx) ||
                (params.language != "en" && params.language != "ja" && params.language != "zh")) {
                fprintf(stderr, "Unmatched Android benchmark policy\n"); return 65;
            }
            fprintf(stderr, "LIP_PARAMS {\"max_initial_ts\":%.1f,\"n_threads\":%d,\"best_of\":%d,"
                "\"audio_ctx\":%d,\"n_max_text_ctx\":%d,\"no_context\":%d,\"no_timestamps\":%d,"
                "\"token_timestamps\":%d,\"use_gpu\":%d,\"flash_attn\":%d,\"vad\":%d,"
                "\"translate\":%d,\"temperature\":%.9g,\"temperature_inc\":%.9g,"
                "\"entropy_thold\":%.9g,\"logprob_thold\":%.9g,\"no_speech_thold\":%.9g,"
                "\"prompt_bytes\":%zu,\"n_processors\":%d,\"strategy\":\"GREEDY\"}\n",
                wparams.max_initial_ts, wparams.n_threads, wparams.greedy.best_of, wparams.audio_ctx,
                wparams.n_max_text_ctx, wparams.no_context, wparams.no_timestamps, wparams.token_timestamps,
                cparams.use_gpu, cparams.flash_attn, wparams.vad, wparams.translate, wparams.temperature,
                wparams.temperature_inc, wparams.entropy_thold, wparams.logprob_thold, wparams.no_speech_thold,
                params.prompt.size(), params.n_processors);

'''
PCM = r'''
        // Verify the stock decoder against full canonical PCM16, not a replacement decoder.
        std::ifstream lip_pcm(fname_inp, std::ios::binary);
        unsigned char lip_header[44];
        lip_pcm.read(reinterpret_cast<char *>(lip_header), 44);
        auto lip_u32 = [](const unsigned char * p) -> uint32_t {
            return uint32_t(p[0]) | (uint32_t(p[1]) << 8) | (uint32_t(p[2]) << 16) | (uint32_t(p[3]) << 24);
        };
        if (!lip_pcm || std::memcmp(lip_header, "RIFF", 4) || std::memcmp(lip_header + 8, "WAVEfmt ", 8) ||
            lip_u32(lip_header + 16) != 16 || lip_u32(lip_header + 20) != 0x00010001 ||
            lip_u32(lip_header + 24) != 16000 || lip_u32(lip_header + 28) != 32000 ||
            lip_u32(lip_header + 32) != 0x00100002 || std::memcmp(lip_header + 36, "data", 4) ||
            pcmf32.empty() || pcmf32.size() > 480000 || lip_u32(lip_header + 40) != pcmf32.size() * 2 ||
            lip_u32(lip_header + 4) != 36 + pcmf32.size() * 2) return 67;
        for (size_t lip_i = 0; lip_i < pcmf32.size(); ++lip_i) {
            unsigned char lip_bytes[2];
            if (!lip_pcm.read(reinterpret_cast<char *>(lip_bytes), 2)) return 67;
            const int lip_s = int(lip_bytes[0]) | (int(lip_bytes[1]) << 8);
            const float lip_expected = (lip_s >= 32768 ? lip_s - 65536 : lip_s) / 32768.0f;
            if (std::memcmp(&lip_expected, &pcmf32[lip_i], sizeof(float))) return 67;
        }
        if (lip_pcm.peek() != std::char_traits<char>::eof()) return 67;
        fprintf(stderr, "LIP_PCM {\"samples\":%zu,\"full_pcm\":1,\"signed_pcm16_div32768\":1}\n", pcmf32.size());
'''
CALL = '            if (whisper_full_parallel(ctx, wparams, pcmf32.data(), pcmf32.size(), params.n_processors) != 0) {'
AUDIO = '        if (!whisper_is_multilingual(ctx)) {'
PATCHES = [('    ggml_backend_load_all();', '    ggml_backend_load_all();' + EARLY + REGISTRY),
           (AUDIO, PCM + '\n' + AUDIO), (CALL, PARAM + CALL)]


def patch_cli(raw):
    if hashlib.sha256(raw).hexdigest() != CLI_SHA:
        raise vp.DiagnosticError('Pristine CLI source pin drift')
    text = raw.decode()
    for anchor, replacement in PATCHES:
        text = replace_once(text, anchor, replacement)
    return text.encode()


def unpatch_cli(raw):
    text = raw.decode()
    for anchor, replacement in reversed(PATCHES):
        text = replace_once(text, replacement, anchor)
    return text.encode()


def verify_sources(root):
    identities = {}
    for name, digest in SOURCE_PINS.items():
        identity = vp.file_identity(vp.no_links(root / name))
        if identity['sha256'] != digest:
            raise vp.DiagnosticError('Benchmark source pin drift: ' + name)
        identities[name] = identity
    metadata = json.loads(vp.no_links(root / 'app/src/main/cpp/patches/whisper-scheduler-abort.json').read_text())
    if (metadata['pin'] != vp.WHISPER or metadata['input_sha256'] != SOURCE_PINS['third_party/whisper.cpp/src/whisper.cpp'] or
            metadata['output_sha256'] != CANCEL_SHA or
            metadata['patch_sha256'] != SOURCE_PINS['app/src/main/cpp/patches/whisper-scheduler-abort.patch']):
        raise vp.DiagnosticError('Cancellation source metadata drift')
    return identities


def verify_pcm(wav, decoded, sha, samples):
    if (type(samples) is not int or not 0 < samples <= 480000 or len(wav) != 44 + 2 * samples or
            hashlib.sha256(wav).hexdigest() != sha or len(decoded) != 4 * samples):
        raise vp.DiagnosticError('Incomplete or unmatched full PCM identity')
    header = b'RIFF' + struct.pack('<I', len(wav) - 8) + b'WAVEfmt ' + struct.pack('<IHHIIHH', 16, 1, 1, 16000, 32000, 2, 16) + b'data' + struct.pack('<I', 2 * samples)
    expected = b''.join(struct.pack('<f', sample[0] / 32768) for sample in struct.iter_unpack('<h', wav[44:]))
    if wav[:44] != header or decoded != expected:
        raise vp.DiagnosticError('Stock decoded floats differ from signed PCM16 /32768f')
    return dict(samples=samples, wav_sha256=sha, decoded_sha256=hashlib.sha256(decoded).hexdigest(), full_pcm=True)


def require_q5_witness(text):
    if len(text.encode()) > vb.LOG_LIMIT or 'Vulkan Timings:\n' not in text:
        raise vp.DiagnosticError('Require bounded actual Vulkan timing logger output')
    number = r'([0-9]+(?:\.[0-9]+)?(?:e[+-]?[0-9]+)?)'
    pattern = (r'^(?:[^:\n]+ )?MUL_MAT(?:_ID)?(?:_VEC)? q5_[01] m=[1-9][0-9]* n=[1-9][0-9]* k=[1-9][0-9]*'
               r'[^:\n]*: [1-9][0-9]* x ' + number + r' us = ' + number + r' us(?: \([^\n]*\))?$')
    framed, witness = False, False
    for row in text.splitlines():
        if row == 'Vulkan Timings:':
            framed, witness = True, False
        elif framed and row.startswith('Total time:'):
            total = re.fullmatch(r'Total time: ' + number + r' us\.', row)
            if total and math.isfinite(float(total[1])) and float(total[1]) > 0 and witness: return
            framed = False
        elif framed:
            match = re.fullmatch(pattern, row)
            witness |= bool(match and all(math.isfinite(float(v)) and float(v) > 0 for v in match.groups()))
    raise vp.DiagnosticError('Require completed positive Vulkan Q5 matrix timestamp record before timing')


def require_false_acceptance(data):
    if any(data.get(k) is not False for k in ACCEPTANCE):
        raise vp.DiagnosticError('Artifact preparation cannot confer production/runtime acceptance')


def require_host(env, system, machine, uid):
    if (any(env.get(k) != v for k, v in ENVIRONMENT.items()) or system != 'Linux' or machine != 'x86_64' or
            uid <= 0 or not env.get('ImageVersion') or
            any(not re.fullmatch(r'[1-9][0-9]*', env.get(k, '')) for k in ('GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT'))):
        raise vp.DiagnosticError('Require unprivileged exact hosted Android benchmark push')


def require_context(env, event, sha, message, vendor_sha, gitlink, dirty, system, machine, uid):
    require_host(env, system, machine, uid)
    repo, head = event.get('repository', {}), event.get('head_commit') or {}
    if (repo.get('full_name') != 'ihearttokyo/Lip' or repo.get('private') is not False or
            event.get('ref') != REF or event.get('after') != sha or head.get('id') != sha or
            env.get('GITHUB_SHA') != sha or not re.fullmatch(r'[0-9a-f]{40}', sha) or
            MARKER not in message or head.get('message') != message or
            vendor_sha != vp.WHISPER or gitlink != vp.WHISPER or dirty):
        raise vp.DiagnosticError('Require pristine pinned exact public opt-in HEAD/event/message')
    return dict(checkout_sha=sha, message_sha256=hashlib.sha256(message.encode()).hexdigest(),
        whisper_revision=vendor_sha, ref=REF, uid=uid, run_id=env['GITHUB_RUN_ID'],
        run_attempt=env['GITHUB_RUN_ATTEMPT'], image_version=env['ImageVersion'])


def read_context(root, pins, deadline, report=None):
    env = os.environ
    require_host(env, platform.system(), platform.machine(), os.geteuid())
    if root != Path(env['GITHUB_WORKSPACE']).absolute():
        raise vp.DiagnosticError('Require actual workspace')
    event_path = vp.no_links(Path(env['GITHUB_EVENT_PATH']))
    if event_path.stat().st_size > vb.LOG_LIMIT:
        raise vp.DiagnosticError('Event exceeds bound')
    event = json.loads(event_path.read_text())
    message = (event.get('head_commit') or {}).get('message', '')
    require_context(env, event, env.get('GITHUB_SHA', ''), message, vp.WHISPER, vp.WHISPER, '', platform.system(), platform.machine(), os.geteuid())
    vendor = root / 'third_party/whisper.cpp'
    def git(*args, cwd=root):
        return vb.checked(report, ['git', *args], cwd, 'admission-git-' + str(len(report.data['probes']) if report else args), deadline, seconds=10).rstrip('\n')
    identity = require_context(env, event, git('rev-parse', 'HEAD'), git('show', '-s', '--format=%B', 'HEAD'),
        git('rev-parse', 'HEAD', cwd=vendor), git('ls-tree', 'HEAD', 'third_party/whisper.cpp').split()[2],
        git('status', '--porcelain', '--untracked-files=all') + git('status', '--porcelain', '--untracked-files=all', '--ignored=matching', cwd=vendor),
        platform.system(), platform.machine(), os.geteuid())
    identities = verify_sources(root)
    for name, digest in pins['whisper_files'].items():
        item = vp.file_identity(vp.no_links(vendor / name))
        if item['sha256'] != digest: raise vp.DiagnosticError('Qualified source drift: ' + name)
        identities['third_party/whisper.cpp/' + name] = item
    identity['sources'] = identities
    identity['helpers'] = {name: vp.file_identity(vp.no_links(root / name)) for name in
        ('eval/vulkan_android_ci.py', 'eval/test_vulkan_android_ci.py', '.github/workflows/vulkan-android-bench.yml',
         'eval/vulkan_build_ci.py', 'eval/vulkan_preflight.py', 'eval/vulkan-preflight-pins.json', 'eval/qwen17_cli.py',
         'app/src/main/cpp/patches/whisper-scheduler-abort.json')}
    return identity


def paths(env, fresh=False):
    # Reuse qualified owned-path checks; separate run-specific directory identity.
    temp = vp.no_links(Path(env['VULKAN_RUNNER_TEMP']))
    if temp != Path(env['RUNNER_TEMP']).absolute() or temp.stat().st_uid != os.geteuid():
        raise vp.DiagnosticError('Require actual owned runner.temp')
    prefix = 'lip-vulkan-android-' + env['GITHUB_RUN_ID'] + '-' + env['GITHUB_RUN_ATTEMPT']
    result = [temp / (prefix + suffix) for suffix in ('-evidence', '-scratch')]
    for path in result:
        vp.no_links(path)
        if fresh and path.exists(): raise vp.DiagnosticError('Refuse stale directories')
        if not fresh and (not path.is_dir() or path.stat().st_uid != os.geteuid()): raise vp.DiagnosticError('Unowned directory')
    if fresh:
        for path in result: path.mkdir(mode=0o700)
    elif result != [Path(env['VULKAN_EVIDENCE']), Path(env['VULKAN_SCRATCH'])]:
        raise vp.DiagnosticError('Path identity changed')
    return result


def configure_command(*args):
    command = vb.configure_command(*args)
    command[command.index('-DGGML_CPU=OFF')] = '-DGGML_CPU=ON'
    command[command.index('-DCMAKE_SHARED_LINKER_FLAGS=-Wl,--no-undefined')] = '-DCMAKE_SHARED_LINKER_FLAGS=-Wl,--no-undefined,-z,max-page-size=16384'
    return command + ['-DCMAKE_EXE_LINKER_FLAGS=-Wl,--no-undefined,-z,max-page-size=16384',
        '-DGGML_CPU_ARM_ARCH=armv8-a', '-DGGML_CPU_ALL_VARIANTS=OFF', '-DGGML_BLAS=OFF',
        '-DWHISPER_BUILD_TESTS=OFF', '-DWHISPER_BUILD_EXAMPLES=ON', '-DWHISPER_BUILD_SERVER=OFF',
        '-DWHISPER_CURL=OFF', '-DWHISPER_SDL2=OFF', '-DWHISPER_COMMON_FFMPEG=OFF']


def generated_source(root, scratch, report):
    source = vp.no_links(scratch / 'source'); source.mkdir(mode=0o700)
    vendor = vp.no_links(root / 'third_party/whisper.cpp')
    original = vendor / 'examples/cli/cli.cpp'
    raw = original.read_bytes(); adapted = patch_cli(raw)
    if unpatch_cli(adapted) != raw: raise vp.DiagnosticError('CLI adapter changed unrelated bytes')
    cli = source / 'cli.cpp'; cli.write_bytes(adapted)
    patch = root / 'app/src/main/cpp/patches/whisper-scheduler-abort.cmake'
    if any(']=]' in str(p) for p in (vendor, patch, original, cli)): raise vp.DiagnosticError('Unsafe CMake bracket path')
    text = ('cmake_minimum_required(VERSION 3.22)\nproject(lip_android_bench LANGUAGES C CXX ASM)\n'
        'add_subdirectory([=[' + str(vendor / 'ggml') + ']=] ggml)\n'
        'add_subdirectory([=[' + str(vendor) + ']=] whisper)\n'
        'include([=[' + str(patch) + ']=])\n'
        'set_source_files_properties([=[' + str(original) + ']=] TARGET_DIRECTORY whisper-cli PROPERTIES HEADER_FILE_ONLY TRUE)\n'
        'target_sources(whisper-cli PRIVATE [=[' + str(cli) + ']=])\n')
    (source / 'CMakeLists.txt').write_text(text)
    for name, data in [('adapted-cli.txt', adapted), ('source-parent.txt', text.encode())]:
        if vp.retain_text(report.evidence, name, data) != data: raise vp.DiagnosticError('Incomplete generated source retention')
    report.data['generated_source'] = dict(cli=vp.file_identity(cli), parent=vp.file_identity(source / 'CMakeLists.txt'),
                                          pristine_cli_sha256=CLI_SHA, cancellation_output_sha256=CANCEL_SHA)
    report.save()
    return source


def verify_elf(path, executable=False):
    identity = vp.elf_identity(vp.no_links(path), 183)
    size = identity['bytes']
    if identity['elf_type'] != 3: raise vp.DiagnosticError('Require Android shared/PIE ELF')
    with path.open('rb') as stream:
        header = struct.unpack('<16sHHIQQQIHHHHHH', stream.read(64))
        offset, width, count = header[5], header[9], header[10]
        if width != 56 or not 0 < count <= 128 or offset + width * count > size:
            raise vp.DiagnosticError('Invalid ELF program table')
        stream.seek(offset); rows = [struct.unpack('<IIQQQQQQ', stream.read(56)) for _ in range(count)]
        loads, interpreters = [], []
        for kind, flags, off, virtual, physical, file_size, mem_size, align in rows:
            if off + file_size > size or file_size > mem_size: raise vp.DiagnosticError('Truncated ELF segment')
            if kind == 1:
                if align < 16384 or align & (align - 1) or off % align != virtual % align:
                    raise vp.DiagnosticError('Every LOAD segment must support 16-KiB pages')
                loads.append(dict(offset=off, virtual_address=virtual, alignment=align))
            if kind == 3:
                if file_size > 128: raise vp.DiagnosticError('Unbounded ELF interpreter')
                stream.seek(off); interpreters.append(stream.read(file_size))
        if not loads or (interpreters != [b'/system/bin/linker64\0'] if executable else interpreters):
            raise vp.DiagnosticError('Wrong Android interpreter or missing LOAD segments')
    return dict(identity=identity, loads=loads, interpreter='/system/bin/linker64' if executable else None)


def verify_closure(needed, libraries):
    if (len(libraries) > 32 or any(not re.fullmatch(LIBRARY, name) or name in SYSTEM or
            row.get('kind') not in ('built', 'NDK runtime') for name, row in libraries.items()) or
            libraries.get('libc++_shared.so', {}).get('kind') != 'NDK runtime'):
        raise vp.DiagnosticError('Unsafe package/system stub or missing matching shared C++ runtime')
    seen, pending = set(), list(needed)
    while pending:
        name = pending.pop()
        if name in SYSTEM or name in seen: continue
        if name not in libraries: raise vp.DiagnosticError('Missing actual non-system SONAME dependency: ' + name)
        seen.add(name); pending.extend(libraries[name]['needed'])
    if seen != set(libraries) or any(not any(name.startswith(prefix + '.so') for name in seen)
        for prefix in ('libwhisper', 'libggml', 'libggml-base', 'libggml-cpu', 'libggml-vulkan')):
        raise vp.DiagnosticError('CPU/Vulkan/Whisper linkage or full exact closure missing')
    return sorted(seen)


def package_name(name):
    if (name not in ('whisper-cli', 'NOTICES.txt', 'manifest.json') and
            (not re.fullmatch(LIBRARY, name) or name in SYSTEM)):
        raise vp.DiagnosticError('Unapproved or unsafe package member')
    return name


def package(build, toolchain, scratch, report, deadline):
    binary = vp.no_links(build / 'bin/whisper-cli')
    if not binary.stat().st_mode & 0o111:
        raise vp.DiagnosticError('CLI package requires executable permission')
    readelf = toolchain / 'bin/llvm-readelf'
    def inspect(path, executable=False):
        result = verify_elf(path, executable)
        text = vb.checked(report, [readelf, '-h', '-d', '-l', path], build, 'elf-' + path.name, deadline)
        result.update(path=str(path), needed=vb.needed(text))
        sonames = re.findall(r'\(SONAME\).*Library soname: \[([^\]\n]+)\]', text)
        if not executable and (len(sonames) != 1 or not re.fullmatch(LIBRARY, sonames[0])):
            raise vp.DiagnosticError('Require actual safe SONAME')
        return result, sonames
    exe, _ = inspect(binary, True)
    libraries = {}
    candidates = {p.resolve() for p in build.rglob('lib*.so*')}
    candidates.add(toolchain / 'sysroot/usr/lib/aarch64-linux-android/libc++_shared.so')
    for path in sorted(candidates):
        runtime = path.name == 'libc++_shared.so'
        if not runtime and not path.is_relative_to(build.resolve()): raise vp.DiagnosticError('Library escapes owned build')
        info, names = inspect(path)
        if names[0] in libraries: raise vp.DiagnosticError('Ambiguous SONAME')
        info['kind'] = 'NDK runtime' if runtime else 'built'; libraries[names[0]] = info
    names = verify_closure(exe['needed'], libraries)
    manifest = dict(schema_version=1, scope='ARM64/API33 cold-per-clip CLI; not warm app/JNI',
        variable='use_gpu only', source_revision=vp.WHISPER, binary=exe, libraries=libraries,
        parameters={arm: expected_params(arm) for arm in ('cpu', 'gpu')}, **ACCEPTANCE)
    files = {'whisper-cli': binary, **{name: Path(libraries[name]['path']) for name in names}}
    require_false_acceptance(report.data)
    system_names = sorted({name for row in [exe, *libraries.values()] for name in row['needed'] if name in SYSTEM})
    manifest['system_dependencies_not_packaged'] = {name: vp.elf_identity(
        toolchain / 'sysroot/usr/lib/aarch64-linux-android/33' / name, 183) for name in system_names}
    notices = (report.evidence / 'whisper-license.txt').read_bytes()
    for name in ('Vulkan-Hpp-license.txt', 'SPIRV-Headers-license.txt', 'ndk-notices.txt', 'ndk-toolchain-notices.txt'):
        notices += b'\n\n' + name.encode() + b'\n' + (report.evidence / name).read_bytes()
    payloads = {'NOTICES.txt': notices}
    manifest['files'] = {name: vp.file_identity(path) for name, path in files.items()}
    manifest['files']['NOTICES.txt'] = dict(bytes=len(notices), sha256=hashlib.sha256(notices).hexdigest())
    payloads['manifest.json'] = (json.dumps(manifest, indent=2, allow_nan=False) + '\n').encode()
    if sum(path.stat().st_size for path in files.values()) + sum(map(len, payloads.values())) > 256 * 1024**2:
        raise vp.DiagnosticError('Expanded package exceeds 256 MiB')
    target = scratch / 'vulkan-android-bench.zip'
    with zipfile.ZipFile(target, 'x', compression=zipfile.ZIP_DEFLATED) as archive:
        for name, path in files.items():
            archive.write(path, package_name(name))
        for name, data in payloads.items(): archive.writestr(package_name(name), data)
    if target.stat().st_size > 128 * 1024**2: raise vp.DiagnosticError('ZIP package exceeds fixed 128 MiB binary artifact cap')
    with zipfile.ZipFile(target) as archive:
        if archive.testzip() is not None or set(archive.namelist()) != set(files) | set(payloads):
            raise vp.DiagnosticError('Package CRC/member readback failed')
        for info in archive.infolist():
            package_name(info.filename)
            if info.file_size > 256 * 1024**2: raise vp.DiagnosticError('Unbounded archive member')
            digest = hashlib.sha256()
            with archive.open(info) as data:
                while chunk := data.read(65536): digest.update(chunk)
            expected = manifest['files'].get(info.filename)
            if expected and (digest.hexdigest() != expected['sha256'] or info.file_size != expected['bytes']):
                raise vp.DiagnosticError('Package complete SHA readback failed')
        if archive.read('manifest.json') != payloads['manifest.json']: raise vp.DiagnosticError('Manifest readback failed')
    report.data.update(package=vp.file_identity(target), package_manifest=manifest, package_path=str(target))
    report.save()


def target_compile_rows(rows, commands, graph, build, toolchain):
    if (not isinstance(rows, list) or not rows or len(commands.encode()) > vb.LOG_LIMIT or
            not commands.endswith('\n') or len(graph.encode()) > vb.LOG_LIMIT or
            not graph.startswith('digraph ninja {\n') or not graph.endswith('}\n')):
        raise vp.DiagnosticError('Incomplete whisper-cli prerequisite command/graph inventory')
    graph_objects = []
    for label in re.findall(r'^"[^"\n]+" \[label="([^"\n]+)"(?:, [^\n]*)?\]$', graph, re.M):
        if label.endswith('.o'):
            obj = vp.no_links(build / label).resolve()
            if not obj.is_relative_to(build): raise vp.DiagnosticError('Foreign prerequisite graph object')
            graph_objects.append(obj)
    if not graph_objects or len(set(graph_objects)) != len(graph_objects):
        raise vp.DiagnosticError('Missing or duplicate prerequisite graph objects')

    def compile_args(args):
        if (not args or args[0] not in (str(toolchain / 'bin/clang'), str(toolchain / 'bin/clang++')) or
                any(args.count(flag) != 1 or args.index(flag) + 1 >= len(args) for flag in ('-c', '-o'))):
            raise vp.DiagnosticError('Require one original NDK compilation source/object')
        inputs, takes_value = [], False
        for arg in args[1:]:
            if takes_value: takes_value = False
            elif arg in ('-o', '-I', '-isystem', '-iquote', '-include', '-imacros', '-idirafter', '-isysroot',
                         '--sysroot', '-target', '--target', '-x', '-MF', '-MT', '-MQ', '-Xclang', '-Xpreprocessor', '-Xassembler'):
                takes_value = True
            elif not arg.startswith('-'): inputs.append(arg)
        if takes_value or inputs != [args[args.index('-c') + 1]]:
            raise vp.DiagnosticError('Additional or ambiguous prerequisite compilation input')
        # Ninja adds dependency-file flags absent from CMake's compile database; preserve every other token.
        normalized, i = [], 0
        while i < len(args):
            if args[i] == '-MD': i += 1
            elif args[i] in ('-MT', '-MF'): i += 2
            else: normalized.append(args[i]); i += 1
        return tuple(normalized)

    configured = {}
    for row in rows:
        args = row.get('arguments') or shlex.split(row['command'])
        key = compile_args(args)
        if key in configured: raise vp.DiagnosticError('Duplicate configured compile command')
        configured[key] = row
    selected, objects, sources = [], set(), set()
    for index, line in enumerate(commands.splitlines()):
        args = shlex.split(line)
        if '-c' not in args: continue
        key = compile_args(args)
        if key not in configured: raise vp.DiagnosticError('Prerequisite command missing from compile database')
        row = configured[key]
        directory = vp.no_links(Path(row['directory'])).resolve()
        actual = vp.no_links(directory / row['file']).resolve()
        obj = vp.no_links(directory / args[args.index('-o') + 1]).resolve()
        if (directory != build or actual != vp.no_links(build / args[args.index('-c') + 1]).resolve() or
                'output' in row and vp.no_links(directory / row['output']).resolve() != obj):
            raise vp.DiagnosticError('Prerequisite command/source/object differs from compile row')
        if obj in objects or actual in sources:
            raise vp.DiagnosticError('Duplicate prerequisite command/source/object')
        selected.append((row, index)); objects.add(obj); sources.add(actual)
    if objects != set(graph_objects):
        raise vp.DiagnosticError('Incomplete or extra prerequisite compilation command/object inventory')
    return selected


def verify_compilation(vendor, source, build, toolchain, ninja, report, deadline):
    build = vp.no_links(build).resolve()
    with (build / 'compile_commands.json').open('rb') as stream:
        raw = stream.read(vb.LOG_LIMIT + 1)
    if len(raw) > vb.LOG_LIMIT or vp.retain_text(report.evidence, 'compile-commands.txt', raw) != raw:
        raise vp.DiagnosticError('Incomplete compile commands')
    configured_rows = json.loads(raw)
    commands = vb.checked(report, [ninja, '-C', build, '-t', 'commands', 'whisper-cli'], build, 'link-and-build-commands', deadline)
    graph = vb.checked(report, [ninja, '-C', build, '-t', 'graph', 'whisper-cli'], build, 'prerequisite-graph', deadline)
    selected = target_compile_rows(configured_rows, commands, graph, build, toolchain)
    rows = [row for row, _ in selected]
    report.data['compile_inventory'] = dict(target='whisper-cli', scope='Complete Ninja target prerequisites, not all configured targets',
        configured_rows=len(configured_rows), required_compile_rows=len(rows), configured_unbuilt_rows=len(configured_rows)-len(rows),
        command_count=len(commands.splitlines()), commands_sha256=hashlib.sha256(commands.encode()).hexdigest(),
        graph_sha256=hashlib.sha256(graph.encode()).hexdigest())
    ggml_rows = [r for r in rows if Path(r['file']).is_relative_to(vendor / 'ggml') or str(r['file']).endswith('.comp.cpp')]
    report.data['objects'] = vb.qualify_objects(ggml_rows, vendor / 'ggml/src/ggml-vulkan/vulkan-shaders', build)
    generated = build / 'whisper-cancel/whisper.cpp'
    if vp.file_identity(generated)['sha256'] != CANCEL_SHA:
        raise vp.DiagnosticError('Generated cancellation source differs from unchanged qualified patch')
    compiled = []
    for row, command_index in selected:
        args = row.get('arguments') or shlex.split(row['command'])
        targets = [arg for arg in args if arg.startswith('--target=')]
        if (len(targets) != 1 or not re.fullmatch(r'--target=aarch64(?:-none)?-linux-android33', targets[0]) or
                any(a in ('-target', '--target', '-arch') for a in args)):
            raise vp.DiagnosticError('Require actual ARM64/API33 compile commands')
        if any(('dotprod' in a or 'i8mm' in a or a in ('-mcpu=native', '-march=native')) for a in args):
            raise vp.DiagnosticError('Unqualified CPU architecture experiment')
        if args.count('-c') != 1 or args.count('-o') != 1:
            raise vp.DiagnosticError('Ambiguous compile source/object')
        directory = vp.no_links(Path(row['directory'])).resolve()
        actual = vp.no_links(directory / args[args.index('-c') + 1]).resolve()
        if actual != Path(row['file']).resolve() or not directory.is_relative_to(build):
            raise vp.DiagnosticError('Source/compile directory mismatch')
        if not actual.is_relative_to(vendor) and actual not in (source / 'cli.cpp', generated) and not actual.name.endswith('.comp.cpp'):
            raise vp.DiagnosticError('Unexpected generated source')
        identity = vp.file_identity(actual)
        if actual.is_relative_to(vendor) and identity != report.data['connected_source'].get(str(actual.relative_to(vendor))):
            raise vp.DiagnosticError('Required source differs from pinned connected vendor inventory')
        if actual == source / 'cli.cpp' and identity != report.data['generated_source']['cli']:
            raise vp.DiagnosticError('Required CLI differs from generated adapter identity')
        obj = vp.no_links(directory / args[args.index('-o') + 1]).resolve()
        if not obj.is_relative_to(build) or vp.elf_identity(obj, 183)['elf_type'] != 1:
            raise vp.DiagnosticError('Expected owned actual ARM64 object')
        compiled.append(dict(command_index=command_index, source=str(actual), source_identity=identity,
                             object_path=str(obj), object=vp.elf_identity(obj, 183)))
    paths_compiled = [r['source'] for r in compiled]
    if (paths_compiled.count(str(source / 'cli.cpp')) != 1 or paths_compiled.count(str(generated)) != 1 or
            str(vendor / 'src/whisper.cpp') in paths_compiled or
            str(vendor / 'examples/cli/cli.cpp') in paths_compiled or
            not any('/ggml-cpu/' in p for p in paths_compiled)):
        raise vp.DiagnosticError('Actual CPU/Whisper/adapted CLI source selection missing')
    report.data['all_objects'] = compiled
    header = build / 'ggml/src/ggml-vulkan/ggml-vulkan-shaders.hpp'
    report.data['generated_spirv'] = vb.qualify_generated(header,
        [Path(r['source']) for r in compiled if r['source'].endswith('.comp.cpp')], header.parent / 'vulkan-shaders.spv')
    report.data['host_generator'] = vp.elf_identity(build / 'Release/vulkan-shaders-gen', 62)
    with (build / 'CMakeCache.txt').open('rb') as stream:
        cache_raw = stream.read(vb.LOG_LIMIT + 1)
    if len(cache_raw) > vb.LOG_LIMIT or vp.retain_text(report.evidence, 'cmake-cache.txt', cache_raw) != cache_raw:
        raise vp.DiagnosticError('Incomplete CMake cache')
    cache = dict(re.findall(r'^([^/#\n][^:\n]*):[^=\n]+=(.*)$', cache_raw.decode(), re.M))
    if any(cache.get(k) != v for k, v in {'GGML_CPU':'ON', 'GGML_VULKAN':'ON', 'GGML_NATIVE':'OFF',
            'GGML_CPU_ARM_ARCH':'armv8-a', 'GGML_BACKEND_DL':'OFF', 'ANDROID_ABI':'arm64-v8a',
            'ANDROID_PLATFORM':'android-33', 'ANDROID_STL':'c++_shared', 'CMAKE_BUILD_TYPE':'Release'}.items()):
        raise vp.DiagnosticError('Actual cache differs from same-binary API33 CPU/Vulkan contract')
    nm = toolchain / 'bin/llvm-nm'
    for name, symbol in [('whisper', 'whisper_full'), ('ggml-cpu', 'ggml_backend_cpu_reg'), ('ggml-vulkan', 'ggml_backend_vk_reg')]:
        matches = {p.resolve() for p in build.rglob('lib' + name + '.so*')}
        if len(matches) != 1: raise vp.DiagnosticError('Missing or ambiguous actual library: ' + name)
        text = vb.checked(report, [nm, '-D', '--defined-only', matches.pop()], build, name + '-symbols', deadline)
        if not re.search(r'\b' + symbol + r'$', text, re.M): raise vp.DiagnosticError('Missing actual library symbol')
    report.save()


def build_benchmark(root, pins, scratch, report, deadline):
    vb.require_capacity(scratch, report)
    vendor = root / 'third_party/whisper.cpp'
    source = generated_source(root, scratch, report)
    ndk = vp.no_links(Path(os.environ['ANDROID_HOME']) / 'ndk' / pins['ndk'])
    toolchain = ndk / 'toolchains/llvm/prebuilt/linux-x86_64'
    core = toolchain / 'sysroot/usr/include/vulkan/vulkan_core.h'
    if (not re.search(r'^Pkg.Revision\s*=\s*' + re.escape(pins['ndk']) + r'\s*$', (ndk / 'source.properties').read_text(), re.M) or
            vp.file_identity(core)['sha256'] != vp.CORE_SHA): raise vp.DiagnosticError('Unqualified NDK/revision-335 header')
    report.data['ndk_identity'] = dict(properties=vp.file_identity(ndk / 'source.properties'), vulkan_core=vp.file_identity(core), header_revision=335)
    sdk_bin = Path(os.environ['ANDROID_HOME']) / 'cmake' / pins['cmake'] / 'bin'
    cmake, ninja = sdk_bin / 'cmake', sdk_bin / 'ninja'
    tools = dict(cmake=cmake, ninja=ninja, glslc=ndk / 'shader-tools/linux-x86_64/glslc',
        gcc=Path(shutil.which('gcc') or '/missing'), gxx=Path(shutil.which('g++') or '/missing'),
        flock=Path(shutil.which('flock') or '/missing'), clang=toolchain / 'bin/clang++',
        nm=toolchain / 'bin/llvm-nm', readelf=toolchain / 'bin/llvm-readelf', patch=Path(shutil.which('patch') or '/missing'))
    report.data['tools'] = {name: vp.elf_identity(path, 62) for name, path in tools.items()}
    if not re.search(r'^cmake version 4\.1\.2\s*$', vb.checked(report, [cmake, '--version'], scratch, 'cmake-version', deadline), re.M):
        raise vp.DiagnosticError('Unqualified CMake')
    for name in ('gcc', 'gxx'):
        if vb.checked(report, [tools[name], '-dumpmachine'], scratch, name + '-target', deadline).strip() != 'x86_64-linux-gnu':
            raise vp.DiagnosticError('Require original native generator compiler')
    shaders = vendor / 'ggml/src/ggml-vulkan/vulkan-shaders'
    if 'std::max(1u, std::min(16u, std::thread::hardware_concurrency()))' not in (shaders / 'vulkan-shaders-gen.cpp').read_text():
        raise vp.DiagnosticError('Original shader generator cap drift')
    report.data['connected_source'] = {str(p.relative_to(vendor)): vp.file_identity(vp.no_links(p))
        for folder in ('ggml', 'src', 'include', 'examples') for p in (vendor / folder).rglob('*') if p.is_file()}
    report.data['optional_extensions'] = {}
    for name, extension in vp.FEATURES.items():
        result = vb.run_command([tools['glslc'], '-o', scratch / (name + '.spv'), '-fshader-stage=compute',
            '--target-env=vulkan1.3', shaders / 'feature-tests' / (name + '.comp')], scratch, report.evidence, 'feature-' + name, deadline, seconds=60)
        status = vp.feature_status(result['exit_code'], result['text'].encode(), extension, result['process_status'])
        report.data['probes'].append({k:v for k,v in result.items() if k != 'text'})
        report.data['optional_extensions'][name] = status; report.save()
        if status != 'UNSUPPORTED' or result['cleanup_status'] != 'JOINED': raise vp.DiagnosticError('Qualified feature result changed')
    hpp, spirv = [vb.acquire(pin, scratch, report, deadline) for pin in pins['archives']]
    prefix = scratch / 'spirv-package'
    vb.checked(report, [cmake, '-S', spirv, '-B', scratch / 'spirv-package-build', '-G', 'Ninja',
        '-DCMAKE_MAKE_PROGRAM=' + str(ninja), '-DCMAKE_INSTALL_PREFIX=' + str(prefix),
        '-DSPIRV_HEADERS_ENABLE_TESTS=OFF', '-DSPIRV_HEADERS_ENABLE_EXAMPLES=OFF'], scratch, 'spirv-configure', deadline)
    vb.checked(report, [cmake, '--install', scratch / 'spirv-package-build'], scratch, 'spirv-install', deadline)
    configs = list(prefix.rglob('SPIRV-HeadersConfig.cmake'))
    if len(configs) != 1: raise vp.DiagnosticError('Require original SPIRV package')
    wrapper = scratch / 'locked-glslc'
    wrapper.write_text('#!/bin/sh\nexec ' + shlex.quote(str(tools['flock'])) + ' -x ' + shlex.quote(str(scratch / 'shader.lock')) +
                       ' ' + shlex.quote(str(tools['glslc'])) + ' "$@"\n'); wrapper.chmod(0o700)
    report.data['shader_control'] = dict(workers=2, original_generator_slots_maximum=16, active_glslc_maximum=1,
                                         wrapper=vp.file_identity(wrapper), text=wrapper.read_text())
    build = scratch / 'android-build'
    environment = {**os.environ, 'CMAKE_BUILD_PARALLEL_LEVEL':'2', 'MALLOC_ARENA_MAX':'2'}
    vb.checked(report, configure_command(cmake, ninja, source, build, ndk, hpp, spirv, configs[0].parent, wrapper),
               scratch, 'android-configure', deadline, env=environment)
    vb.require_capacity(scratch, report)
    vb.checked(report, [cmake, '--build', build, '--target', 'whisper-cli', '--parallel', '2'], scratch, 'android-build', deadline, env=environment)
    verify_compilation(vendor, source, build, toolchain, ninja, report, deadline)
    notices = {'whisper-license.txt': (vendor / 'LICENSE').read_bytes(), 'ndk-notices.txt': (ndk / 'NOTICE').read_bytes(),
               'ndk-toolchain-notices.txt': (ndk / 'NOTICE.toolchain').read_bytes()}
    miniaudio = (vendor / 'examples/miniaudio.h').read_bytes()
    marker = b'This software is available as a choice of the following licenses.'
    if miniaudio.count(marker) != 1: raise vp.DiagnosticError('Missing miniaudio notice')
    notices['whisper-license.txt'] += b'\nminiaudio\n' + miniaudio[miniaudio.index(marker):]
    for name, data in notices.items():
        if len(data) > vb.LOG_LIMIT or vp.retain_text(report.evidence, name, data) != data: raise vp.DiagnosticError('Incomplete bundled notice')
    package(build, toolchain, scratch, report, deadline)


def main(mode=None):
    require_host(os.environ, platform.system(), platform.machine(), os.geteuid())
    if mode is None:
        parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('mode', choices=('admit', 'install', 'build'))
        mode = parser.parse_args().mode
    if mode not in ('admit', 'install', 'build'): raise vp.DiagnosticError('Unknown stage')
    root = Path(__file__).absolute().parent.parent
    pins = json.loads((root / 'eval/vulkan-preflight-pins.json').read_text()); vp.require_pins(pins)
    if mode == 'admit':
        identity = read_context(root, pins, time.monotonic() + 30)
        evidence, scratch = paths(os.environ, True)
        report = vp.Report(evidence, identity)
        report.data.update(pins=pins, deadline=time.monotonic() + vb.SECONDS, build_status='NOT_RUN',
                           scope='Isolated ARM64/API33 same-binary cold-per-clip CLI preparation', **ACCEPTANCE)
        report.save()
        with Path(os.environ['GITHUB_OUTPUT']).open('a') as output:
            output.write('evidence=' + str(evidence) + '\nscratch=' + str(scratch) + '\npackage=' + str(scratch / 'vulkan-android-bench.zip') + '\n')
        return 0
    evidence, scratch = paths(os.environ)
    path = vp.no_links(evidence / 'report.json')
    if path.stat().st_size > vp.ARTIFACT_LIMIT: raise vp.DiagnosticError('Report exceeds bound')
    report = vp.Report.__new__(vp.Report); report.evidence, report.data = evidence, json.loads(path.read_text())
    previous = {}
    try:
        require_false_acceptance(report.data)
        if (report.data.get('pins') != pins or report.data.get('status') != 'PARTIAL' or
                report.data.get('stage') != ('admitted' if mode == 'install' else 'installed') or
                mode == 'build' and report.data.get('sdk_setup_complete') is not True): raise vp.DiagnosticError('Replay or source/stage drift')
        deadline = report.data['deadline']; remaining = vp.command_budget(deadline, vb.SECONDS, ceiling=vb.SECONDS)
        if signal.getitimer(signal.ITIMER_REAL) != (0.0, 0.0): raise vp.DiagnosticError('Existing alarm must remain')
        def expired(_sig, _frame): raise TimeoutError('45-minute hosted build deadline exhausted')
        def interrupted(sig, _frame): raise vp.DiagnosticInterrupted(sig)
        for sig, handler in ((signal.SIGALRM, expired), (signal.SIGINT, interrupted), (signal.SIGTERM, interrupted)):
            previous[sig] = signal.signal(sig, handler)
        signal.setitimer(signal.ITIMER_REAL, remaining)
        identity = read_context(root, pins, deadline, report)
        if report.data['identity'] != identity: raise vp.DiagnosticError('Admission identity changed')
        vb.require_capacity(scratch, report)
        if mode == 'install':
            sdkmanager = Path(os.environ['ANDROID_HOME']) / 'cmdline-tools/latest/bin/sdkmanager'
            report.data['sdkmanager_identity'] = vp.file_identity(sdkmanager)
            environment = {**os.environ, 'JAVA_OPTS': vb.JAVA_OPTS, 'MALLOC_ARENA_MAX':'2'}
            vb.checked(report, [sdkmanager, '--version'], scratch, 'sdkmanager-version', deadline, seconds=10, env=environment, address_space=2*1024**3)
            vb.checked(report, [sdkmanager, 'ndk;' + pins['ndk'], 'cmake;' + pins['cmake']], scratch, 'sdk-setup', deadline,
                       seconds=1200, env=environment, address_space=2*1024**3, file_limit=2*1024**3)
            report.data.update(stage='installed', sdk_setup_complete=True)
        else:
            report.data.update(stage='building', build_status='PARTIAL'); report.save()
            build_benchmark(root, pins, scratch, report, deadline)
            if read_context(root, pins, deadline, report) != identity: raise vp.DiagnosticError('Source changed during build')
            require_false_acceptance(report.data); vp.command_budget(deadline, 1)
            report.data['build_status'] = 'PASSED'; report.finish(completed=True)
        report.save(); return 0
    except (Exception, KeyboardInterrupt) as error:
        if signal.SIGALRM in previous: signal.setitimer(signal.ITIMER_REAL, 0)
        report.data.update(**ACCEPTANCE)
        report.fail(error); return 1
    finally:
        if signal.SIGALRM in previous: signal.setitimer(signal.ITIMER_REAL, 0)
        for sig, handler in previous.items(): signal.signal(sig, handler)


if __name__ == '__main__':
    try:
        require_host(os.environ, platform.system(), platform.machine(), os.geteuid())
        resource.setrlimit(resource.RLIMIT_AS, (2*1024**3, 2*1024**3))
        sys.exit(main())
    except (Exception, KeyboardInterrupt) as error:
        print('Android benchmark preparation held: ' + type(error).__name__, file=sys.stderr); sys.exit(1)
