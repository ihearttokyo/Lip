"""Opt-in hosted Linux diagnostic. Collection never qualifies Android or GPU use."""
import argparse
from contextlib import contextmanager
import gzip
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import resource
import selectors
import shlex
import shutil
import signal
import struct
import subprocess
import sys
import tarfile
import time
import urllib.request

WHISPER = '306c88f4d1286aec1bf96e544632897886af5501'
HEADERS = '2fa203425eb4af9dfc6b03f97ef72b0b5bcb8350'
HPP = '9d55db6621613adf9ccea21dbc853fbd27ea2924'
SPIRV = 'b824a462d4256d720bebb40e78b9eb8f78bbb305'
CORE_SHA = 'b8944f451db2231a07acbce90d1d6de2993fc6d656ab120cd833447ec9120710'
REF = 'refs/heads/codex/lip-vulkan-preflight'
MARKER = '[vulkan-preflight]'
TOTAL_SECONDS = 300
ARTIFACT_LIMIT = 16 * 1024**2
FAILURE_RESERVE = 64 * 1024
OUTPUT_LIMIT = 256 * 1024
TARGETS = ('aarch64-none-linux-android33', 'x86_64-none-linux-android33')
FEATURES = {'coopmat': 'GL_KHR_cooperative_matrix', 'coopmat2': 'GL_NV_cooperative_matrix2',
            'coopmat2_decode_vector': 'GL_NV_cooperative_matrix_decode_vector',
            'integer_dot': 'GL_EXT_integer_dot_product', 'bfloat16': 'GL_EXT_bfloat16',
            'float_e2m1': 'GL_EXT_float_e2m1', 'float_e4m3': 'GL_EXT_float_e4m3'}
FP32 = {'FLOAT_TYPE': 'float', 'FLOAT_TYPEV2': 'vec2'}
FP16 = {'FLOAT_TYPE': 'float16_t', 'FLOAT_TYPEV2': 'f16vec2', 'FLOAT_TYPEV4': 'f16vec4',
        'FLOAT16': '1', 'FLOAT_TYPE_MAX': 'float16_t(65504.0)'}
SPV_NAMES = ('CapabilityDenormPreserve CapabilityRoundingModeRTE ExecutionModeDenormPreserve '
             'ExecutionModeRoundingModeRTE LoopControlDontUnrollMask LoopControlMaskNone '
             'LoopControlUnrollMask OpCapability OpCodeMask OpEntryPoint OpExecutionMode '
             'OpExecutionModeId OpExtension OpLabel OpLoopMerge WordCountShift').split()


class DiagnosticError(ValueError):
    """Messages constructed here contain only public diagnostic facts."""


class DiagnosticInterrupted(DiagnosticError):
    def __init__(self, signum):
        self.signal_name = signal.Signals(signum).name
        super().__init__('Diagnostic interrupted by ' + self.signal_name)


@contextmanager
def retention_boundary():
    # Defer cancellation and suspend the outer alarm only during bounded cleanup/receipt writes.
    started = time.monotonic()
    timer = signal.getitimer(signal.ITIMER_REAL)
    mask = signal.pthread_sigmask(signal.SIG_BLOCK, {signal.SIGALRM, signal.SIGTERM, signal.SIGINT})
    signal.setitimer(signal.ITIMER_REAL, 0)
    try:
        yield
    finally:
        remaining = timer[0] - (time.monotonic() - started)
        if remaining > 0:
            signal.setitimer(signal.ITIMER_REAL, remaining, timer[1])
        signal.pthread_sigmask(signal.SIG_SETMASK, mask)
        if timer[0] > 0 and remaining <= 0:
            raise TimeoutError('Diagnostic deadline exhausted during cleanup/retention')


def retain_text(evidence, name, data):
    files = [p for p in evidence.iterdir() if p.is_file()]
    other = sum(p.stat().st_size for p in files if p.name != 'report.json')
    total = sum(p.stat().st_size for p in files)
    available = max(0, min(ARTIFACT_LIMIT - FAILURE_RESERVE - other, ARTIFACT_LIMIT - total))
    retained = data[:available]
    with (evidence / name).open('xb') as output:
        output.write(retained)
    return retained


def file_identity(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return {'bytes': path.stat().st_size, 'sha256': digest.hexdigest()}


def require_pins(pins):
    expected = {'schema_version': 1, 'whisper': WHISPER, 'ndk': '30.0.16248370', 'cmake': '4.1.2',
                'vulkan_headers': HEADERS, 'hpp_headers_gitlink': HEADERS, 'vulkan_core_sha256': CORE_SHA}
    if any(pins.get(k) != v for k, v in expected.items()):
        raise DiagnosticError('Qualified dependency pins changed')
    archives = pins.get('archives', [])
    if [(p['repository'], p['commit'], p['root'], p['license']) for p in archives] != [
            ('KhronosGroup/Vulkan-Hpp', HPP, 'Vulkan-Hpp', 'LICENSE.txt'),
            ('KhronosGroup/SPIRV-Headers', SPIRV, 'SPIRV-Headers', 'LICENSE')]:
        raise DiagnosticError('Require exact source-qualified header archives')
    for pin in archives:
        for key, bound in [('compressed_limit', 16 * 1024**2), ('expanded_limit', 128 * 1024**2),
                           ('member_limit', 16 * 1024**2), ('files_limit', 10000)]:
            if type(pin[key]) is not int or not 0 < pin[key] <= bound:
                raise DiagnosticError('Archive resource ceiling changed')
        if pin.get('independent_archive_sha256') is not None:
            raise DiagnosticError('No independently preknown archive hash was qualified')
    if not pins.get('whisper_files') or any(not re.fullmatch(r'[0-9a-f]{64}', h)
                                          for h in pins['whisper_files'].values()):
        raise DiagnosticError('Missing consumed source identities')


def require_context(env, event, checkout_sha, message, vendor_sha, gitlink, dirty, system, machine, uid):
    expected = {'GITHUB_ACTIONS': 'true', 'GITHUB_REPOSITORY': 'ihearttokyo/Lip', 'GITHUB_EVENT_NAME': 'push',
                'GITHUB_REF': REF, 'GITHUB_SERVER_URL': 'https://github.com', 'RUNNER_OS': 'Linux',
                'RUNNER_ARCH': 'X64', 'RUNNER_ENVIRONMENT': 'github-hosted', 'ImageOS': 'ubuntu24'}
    repo = event.get('repository', {})
    head = event.get('head_commit') or {}
    if (any(env.get(k) != v for k, v in expected.items()) or
            repo.get('full_name') != 'ihearttokyo/Lip' or repo.get('private') is not False or
            event.get('ref') != REF or event.get('after') != checkout_sha or head.get('id') != checkout_sha or
            env.get('GITHUB_SHA') != checkout_sha or not re.fullmatch(r'[0-9a-f]{40}', checkout_sha) or
            MARKER not in message or head.get('message') != message or
            vendor_sha != WHISPER or gitlink != WHISPER or dirty or
            system != 'Linux' or machine != 'x86_64' or uid <= 0 or not env.get('ImageVersion') or
            any(not re.fullmatch(r'[1-9][0-9]*', env.get(k, '')) for k in ('GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT'))):
        raise DiagnosticError('Require exact opt-in public push, checkout, pristine source and hosted Linux identity')
    return {'checkout_sha': checkout_sha, 'head_message_sha256': hashlib.sha256(message.encode()).hexdigest(),
            'whisper_revision': vendor_sha, 'repository': repo['full_name'], 'ref': REF, 'uid': uid,
            'run_id': env['GITHUB_RUN_ID'], 'run_attempt': env['GITHUB_RUN_ATTEMPT'],
            'runner_environment': env['RUNNER_ENVIRONMENT'], 'image_os': env['ImageOS'],
            'image_version': env['ImageVersion'], 'host_system': system, 'host_machine': machine}


def no_links(path):
    path = path.absolute()
    if any(p.is_symlink() for p in (path, *path.parents)):
        raise DiagnosticError('Owned path or ancestor is a link')
    return path


def fresh_directories(temp, run_id, attempt):
    if any(not re.fullmatch(r'[1-9][0-9]*', value) for value in (run_id, attempt)):
        raise DiagnosticError('Invalid owned-directory run identity')
    temp = no_links(temp)
    if not temp.is_dir() or temp.stat().st_uid != os.geteuid():
        raise DiagnosticError('Require owned RUNNER_TEMP')
    paths = [temp / ('lip-vulkan-preflight-' + run_id + '-' + attempt + suffix)
             for suffix in ('-evidence', '-scratch')]
    if any(p.exists() or p.is_symlink() for p in paths):
        raise DiagnosticError('Refuse stale or linked diagnostic directories')
    for p in paths:
        p.mkdir(mode=0o700)
    return paths


def command_budget(deadline, requested, now=None, ceiling=60):
    remaining = deadline - (time.monotonic() if now is None else now)
    if remaining <= 0 or requested <= 0:
        raise TimeoutError('Diagnostic deadline exhausted')
    return min(requested, ceiling, remaining)


def process_limits(seconds, file_size_bytes=32 * 1024**2):
    resource.setrlimit(resource.RLIMIT_AS, (2 * 1024**3, 2 * 1024**3))
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    resource.setrlimit(resource.RLIMIT_FSIZE, (file_size_bytes, file_size_bytes))
    resource.setrlimit(resource.RLIMIT_CPU, (math.ceil(seconds) + 1, math.ceil(seconds) + 1))


def run_command(argv, directory, evidence, name, deadline, seconds=60, ceiling=60, retain=True, env=None, file_size_bytes=32 * 1024**2):
    budget = command_budget(deadline, seconds, ceiling=ceiling)
    started = time.monotonic()
    result = {'id': name, 'argv': [str(a) for a in argv], 'cwd': str(directory),
              'timeout_seconds': budget, 'address_space_bytes': 2 * 1024**3, 'file_size_limit_bytes': file_size_bytes,
              'process_status': 'LAUNCH_FAILED', 'exit_code': None}
    streams = {k: bytearray() for k in ('stdout', 'stderr')}
    observed = {k: {'bytes': 0, 'hash': hashlib.sha256()} for k in streams}
    child = None
    try:
        child = subprocess.Popen(result['argv'], cwd=directory, env=env,
                                 stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                 start_new_session=True, preexec_fn=lambda: process_limits(budget, file_size_bytes))
        result['process_status'] = 'EXITED'
        with selectors.DefaultSelector() as selector:
            selector.register(child.stdout, selectors.EVENT_READ, 'stdout')
            selector.register(child.stderr, selectors.EVENT_READ, 'stderr')
            while selector.get_map():
                remaining = started + budget - time.monotonic()
                if remaining <= 0:
                    result['process_status'] = 'TIMEOUT'
                    break
                for key, _ in selector.select(min(remaining, .5)):
                    chunk = os.read(key.fileobj.fileno(), 65536)
                    kind = key.data
                    if not chunk:
                        selector.unregister(key.fileobj)
                        continue
                    observed[kind]['bytes'] += len(chunk)
                    observed[kind]['hash'].update(chunk)
                    available = OUTPUT_LIMIT - len(streams[kind])
                    streams[kind].extend(chunk[:available])
                    if len(chunk) > available:
                        result['process_status'] = 'OUTPUT_LIMIT'
                        break
                if result['process_status'] != 'EXITED':
                    break
            if result['process_status'] == 'EXITED':
                try:
                    result['exit_code'] = child.wait(timeout=max(.001, started + budget - time.monotonic()))
                except subprocess.TimeoutExpired:
                    result['process_status'] = 'TIMEOUT'
    except TimeoutError:
        result['process_status'] = 'TIMEOUT'
    except (OSError, subprocess.SubprocessError) as error:
        result['process_status'] = 'LAUNCH_FAILED'
        result['error_type'] = type(error).__name__
    except (KeyboardInterrupt, DiagnosticInterrupted) as error:
        result.update(process_status='INTERRUPTED', error_type=type(error).__name__,
                      interrupt_signal=error.signal_name if isinstance(error, DiagnosticInterrupted) else 'SIGINT')
    finally:
        try:
            with retention_boundary():
                if child is not None:
                    try:
                        os.killpg(child.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    try:
                        child.wait(timeout=5)
                    finally:
                        child.stdout.close()
                        child.stderr.close()
                        result['exit_code'] = child.returncode
                result['elapsed_seconds'] = time.monotonic() - started
                for kind, data in streams.items():
                    if retain and evidence is not None:
                        retained = retain_text(evidence, name + '-' + kind + '.txt', data)
                        if len(retained) < len(data):
                            result['artifact_limit_reached'] = True
                            if result['process_status'] == 'EXITED':
                                result['process_status'] = 'ARTIFACT_LIMIT'
                        data = retained
                    result[kind] = {'bytes_observed': observed[kind]['bytes'],
                                    'observed_sha256': observed[kind]['hash'].hexdigest(),
                                    'complete': result['process_status'] == 'EXITED',
                                    'retained_bytes': len(data) if retain else 0,
                                    'retained_sha256': hashlib.sha256(data).hexdigest() if retain else None}
                    result[kind + '_text'] = bytes(data).decode('utf-8', errors='replace') if retain else ''
        except (TimeoutError, KeyboardInterrupt, DiagnosticInterrupted) as error:
            if isinstance(error, TimeoutError):
                result['cleanup_deadline_exhausted'] = True
                if result['process_status'] == 'EXITED':
                    result['process_status'] = 'TIMEOUT'
                result.setdefault('error_type', type(error).__name__)
            else:
                result.update(process_status='INTERRUPTED', error_type=type(error).__name__)
                result['interrupt_signal'] = error.signal_name if isinstance(error, DiagnosticInterrupted) else 'SIGINT'
        if result['process_status'] != 'EXITED':
            for kind in streams:
                result[kind]['complete'] = False
    return result


class Report:
    def __init__(self, evidence, context):
        self.evidence = evidence
        self.data = {'schema_version': 1, 'identity': context, 'status': 'PARTIAL',
                     'collection_status': 'PARTIAL', 'android_or_gpu_acceptance': False,
                     'scope': 'Tools/header syntax/representative shaders only; no full build or GPU execution',
                     'probes': [], 'archives': [], 'stage': 'admitted'}
        self.save()

    def save(self):
        with retention_boundary():
            content = (json.dumps(self.data, indent=2, allow_nan=False) + '\n').encode()
            total = sum(p.stat().st_size for p in self.evidence.iterdir() if p.is_file() and p.name != 'report.json')
            exhausted = total + len(content) > ARTIFACT_LIMIT
            failed = self.data['status'] == 'FAILED'
            if exhausted:
                fields = ('schema_version', 'identity', 'pins', 'stage', 'pending_operation', 'error_type',
                          'error_message', 'interrupted_operation', 'interrupt_signal', 'scope',
                          'install_admission_status', 'collect_admission_status', 'install_elapsed_seconds',
                          'collect_elapsed_seconds', 'diagnostic_deadline_monotonic')
                small = {k: self.data[k] for k in fields if k in self.data}
                small.update(status='FAILED', collection_status='PARTIAL', android_or_gpu_acceptance=False,
                             metadata_truncated=True, artifact_limit_bytes=ARTIFACT_LIMIT)
                small.setdefault('error_type', 'DiagnosticError')
                small.setdefault('error_message', 'Diagnostic artifact exceeds 16 MiB')
                probe_fields = ('id', 'argv', 'cwd', 'status', 'process_status', 'exit_code', 'error_type',
                                'interrupt_signal', 'elapsed_seconds', 'timeout_seconds', 'stdout', 'stderr',
                                'address_space_bytes', 'file_size_limit_bytes', 'artifact_limit_reached',
                                'cleanup_deadline_exhausted')
                small['probes'] = [{k: p[k] for k in probe_fields if k in p} for p in self.data['probes']]
                for mode in ('install', 'collect'):
                    key = mode + '_admission_commands'
                    if key in self.data:
                        small[key] = [{k: p[k] for k in probe_fields if k in p} for p in self.data[key]]
                archive_fields = ('root', 'commit', 'transfer_status', 'consumption_status', 'bytes', 'sha256',
                                  'complete', 'partial_file', 'license_identity', 'retained_license_identity', 'error_type')
                small['archives'] = [{k: p[k] for k in archive_fields if k in p} for p in self.data['archives']]
                content = (json.dumps(small, indent=2, allow_nan=False) + '\n').encode()
                if total + len(content) > ARTIFACT_LIMIT:
                    raise DiagnosticError('No capacity for bounded failure receipt; preserve existing files')
                self.data = small
            (self.evidence / 'report.json').write_bytes(content)
            if exhausted and not failed:
                raise DiagnosticError('Diagnostic artifact exceeds 16 MiB')

    def fail(self, error):
        self.data.update(status='FAILED', collection_status='PARTIAL', error_type=type(error).__name__)
        if isinstance(error, DiagnosticError):
            self.data['error_message'] = str(error)
        self.save()

    def finish(self, completed=False):
        if completed and self.data['status'] != 'FAILED':
            self.data.update(status='PASSED', collection_status='COMPLETE', stage='finished')
        self.save()


def feature_status(exit_code, stderr, extension, process_status='EXITED'):
    if process_status != 'EXITED':
        return 'FAILED'
    if exit_code == 0:
        return 'SUPPORTED'
    if exit_code != 1:
        return 'FAILED'
    lines = stderr.decode('utf-8', errors='replace').strip().splitlines()
    expected = r"[^\n]+:[0-9]+: error: '#extension' : extension not supported: " + re.escape(extension)
    if lines and re.fullmatch(expected, lines[0]) and lines[1:] in ([], ['1 error generated.']):
        return 'UNSUPPORTED'
    return 'FAILED'


def record_probe(report, result, extension=None, reject_diagnostics=False):
    result = dict(result)
    if result['process_status'] == 'INTERRUPTED':
        report.data.update(interrupted_operation=result['id'], interrupt_signal=result.get('interrupt_signal'))
    if extension:
        result['extension'] = extension
        status = feature_status(result['exit_code'], result['stderr_text'].encode(), extension,
                                result['process_status'])
    else:
        status = ('PASSED' if result['exit_code'] == 0 and result['process_status'] == 'EXITED' and
                  (not reject_diagnostics or not result['stderr_text']) else 'FAILED')
    result['status'] = status
    result.pop('stdout_text', None)
    result.pop('stderr_text', None)
    report.data['probes'].append(result)
    if status == 'FAILED':
        report.data['status'] = 'FAILED'
    report.save()
    if status == 'FAILED':
        raise DiagnosticError('Required diagnostic failed: ' + result['id'])


def copy_transfer(source, target, expected, limit, deadline, now=time.monotonic):
    digest, size = hashlib.sha256(), 0
    while True:
        if now() >= deadline:
            raise TimeoutError('Archive transfer deadline exhausted')
        chunk = source.read(65536)
        if not chunk:
            break
        size += len(chunk)
        if size > limit:
            raise DiagnosticError('Archive exceeds compressed ceiling')
        target.write(chunk)
        digest.update(chunk)
    if size == 0 or expected is not None and size != expected:
        raise DiagnosticError('Incomplete archive transfer')
    return {'bytes': size, 'sha256': digest.hexdigest(), 'complete': True}


@contextmanager
def operation_deadline(deadline):
    started = time.monotonic()
    previous_timer = signal.getitimer(signal.ITIMER_REAL)
    previous_handler = signal.getsignal(signal.SIGALRM)
    remaining = command_budget(deadline, 60)
    if previous_timer[0] > 0:
        remaining = min(remaining, previous_timer[0])

    def expired(_signal, _frame):
        raise TimeoutError('Archive or total diagnostic deadline exceeded')

    signal.signal(signal.SIGALRM, expired)
    signal.setitimer(signal.ITIMER_REAL, remaining)
    try:
        yield
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous_handler)
        if previous_timer[0] > 0:
            signal.setitimer(signal.ITIMER_REAL, max(0, previous_timer[0] - (time.monotonic() - started)), previous_timer[1])


def acquire_archive(pin, scratch, report, deadline):
    name = pin['root']
    record = {**pin, 'transfer_status': 'PARTIAL', 'consumption_status': 'NOT_RUN',
              'url': 'https://codeload.github.com/' + pin['repository'] + '/tar.gz/' + pin['commit'],
              'binding': 'Exact commit URL and archive root over TLS; digest observed, not independently preknown'}
    report.data['archives'].append(record)
    report.save()
    path = scratch / (name + '.tar.gz')
    started = time.monotonic()
    archive_deadline = min(deadline, started + 60)
    record['timeout_seconds'] = archive_deadline - started
    try:
        with operation_deadline(archive_deadline), urllib.request.urlopen(
                record['url'], timeout=command_budget(archive_deadline, 30)) as source:
            if source.status != 200 or source.geturl() != record['url']:
                raise DiagnosticError('Require exact public archive without redirects')
            length = source.headers.get('Content-Length')
            expected = int(length) if length is not None else None
            if expected is not None and not 0 < expected <= pin['compressed_limit']:
                raise DiagnosticError('Archive Content-Length exceeds ceiling')
            with path.open('xb') as output:
                record.update(copy_transfer(source, output, expected, pin['compressed_limit'], archive_deadline))
        record.update(transfer_status='COMPLETE', elapsed_seconds=time.monotonic() - started)
        report.save()  # Record the actual archive digest before consuming any member.
        return path
    except (Exception, KeyboardInterrupt) as error:
        record.update(transfer_status='FAILED', error_type=type(error).__name__,
                      elapsed_seconds=time.monotonic() - started)
        if path.exists():
            record['partial_file'] = file_identity(path)
        report.fail(error)
        if isinstance(error, (KeyboardInterrupt, DiagnosticInterrupted)):
            raise
        # HTTP exceptions may contain signed URLs or credential-bearing response headers.
        raise DiagnosticError('Pinned public archive acquisition failed') from None


def selected_member(name, pin):
    return (name == pin['license'] or name == 'CMakeLists.txt' or
            (name.startswith('vulkan/') and name.endswith('.hpp')) or
            (name.startswith('include/spirv/') and name.endswith(('.h', '.hpp'))))


def unpack_archive(archive, destination, pin):
    if archive.stat().st_size > pin['compressed_limit']:
        raise DiagnosticError('Archive compressed ceiling exceeded')
    no_links(destination)
    if destination.exists():
        raise DiagnosticError('Require fresh selected-header directory')
    tarpath = archive.with_suffix('.tar')
    expanded = 0
    try:
        with gzip.open(archive, 'rb') as source, tarpath.open('xb') as output:
            while chunk := source.read(65536):
                expanded += len(chunk)
                if expanded > pin['expanded_limit']:
                    raise DiagnosticError('Archive expanded ceiling exceeded')
                output.write(chunk)
        with tarpath.open('rb') as source:
            source.seek(-1024, os.SEEK_END)
            if expanded % 512 or source.read() != bytes(1024):
                raise DiagnosticError('Missing complete tar terminator')
        root = pin['root'] + '-' + pin['commit']
        seen, selected, count = set(), {}, 0
        with tarfile.open(tarpath, 'r:') as tar:
            for member in tar:
                count += 1
                raw = member.name.rstrip('/')
                parts = raw.split('/')
                if (count > pin['files_limit'] or raw in seen or '\\' in raw or
                        any(p in ('', '.', '..') for p in parts) or parts[0] != root or
                        not (member.isfile() or member.isdir()) or member.issparse() or
                        member.size > pin['member_limit'] or member.size < 0):
                    raise DiagnosticError('Unsafe, duplicate or oversized archive member')
                seen.add(raw)
                relative = '/'.join(parts[1:])
                if member.isfile() and selected_member(relative, pin):
                    selected[relative] = member
            with tarpath.open('rb') as remainder:
                remainder.seek(tar.offset)
                while chunk := remainder.read(65536):
                    if any(chunk):
                        raise DiagnosticError('Unexpected data after complete tar contents')
            if not set([pin['license'], *pin['required']]).issubset(selected):
                raise DiagnosticError('Archive lacks required complete header/license selection')
            destination.mkdir(mode=0o700)
            identities = {}
            for name, member in selected.items():
                path = destination / name
                path.parent.mkdir(parents=True, exist_ok=True)
                with tar.extractfile(member) as source, path.open('xb') as output:
                    shutil.copyfileobj(source, output, 65536)
                if path.stat().st_size != member.size:
                    raise DiagnosticError('Incomplete selected archive member')
                identities[name] = file_identity(path)
        for name in identities:
            if not name.endswith(('.h', '.hpp')):
                continue
            text = (destination / name).read_text()
            identities[name]['spdx'] = re.findall(r'SPDX-License-Identifier:\s*([^\r\n]+)', text)
            for delimiter, include in re.findall(r'^\s*#\s*include\s*([<"])([^>"\n]+)[>"]', text, re.M):
                if delimiter == '"' and include.endswith(('.h', '.hpp')):
                    path = (destination / name).parent.joinpath(include).resolve()
                    if not path.is_relative_to(destination.resolve()):
                        raise DiagnosticError('Transitive selected header escapes root')
                    required = path.relative_to(destination.resolve()).as_posix()
                elif include.startswith('vulkan/') and include.endswith('.hpp'):
                    required = include
                elif include.startswith('spirv/'):
                    required = 'include/' + include
                else:
                    continue
                if required not in identities:
                    raise DiagnosticError('Missing transitive selected header')
        return identities
    except (OSError, EOFError, tarfile.TarError) as error:
        raise DiagnosticError('Incomplete or malformed archive: ' + type(error).__name__) from None


def shader_plan(glslc, shaders, scratch):
    plan = []

    def add(name, source, defines=None, optimize=False, target='vulkan1.2', extension=None):
        argv = [str(glslc), '-fshader-stage=compute', '--target-env=' + target,
                str(source), '-o', str(scratch / (name + '.spv'))]
        if optimize:
            argv.append('-O')
        if defines is not None:
            argv += ['-MD', '-MF', str(scratch / (name + '.d'))]
            argv += ['-D' + k + '=' + v for k, v in sorted(defines.items())]
        probe = {'id': name, 'argv': argv}
        if extension:
            probe['extension'] = extension
        plan.append(probe)

    for target in ('vulkan1.2', 'vulkan1.3'):
        add('tiny-' + target, scratch / 'tiny.comp', target=target)
    for name, extension in FEATURES.items():
        add('feature-' + name, shaders / 'feature-tests' / (name + '.comp'),
            target='vulkan1.3', extension=extension)
    # The pinned generator uses FP32 base for Q5/norm; the earlier receipt also requested FP16-base Q5.
    add('dequant-q5-generator', shaders / 'dequant_q5_0.comp', {**FP32, 'DATA_A_Q5_0': '1', 'D_TYPE': 'float16_t'}, True)
    add('dequant-q5-fp16-base', shaders / 'dequant_q5_0.comp', {**FP16, 'DATA_A_Q5_0': '1', 'D_TYPE': 'float16_t'}, True)
    add('norm-fp32', shaders / 'norm.comp', {**FP32, 'A_TYPE': 'float', 'D_TYPE': 'float'}, True)
    fa = {**FP16, 'ACC_TYPE': 'float', 'ACC_TYPEV2': 'vec2', 'ACC_TYPEV4': 'vec4',
          'DATA_A_IQ4_NL': '1', 'Q_TYPE': 'float', 'D_TYPE': 'float', 'D_TYPEV4': 'vec4'}
    add('flash-normal', shaders / 'flash_attn.comp', fa, True)
    add('flash-dot2', shaders / 'flash_attn.comp', {**fa, 'DOT2_F16': '1'})
    return plan


def header_command(clang, target, sysroot, hpp, spirv, source):
    if target not in TARGETS:
        raise DiagnosticError('Unqualified Android target')
    return [str(clang), '--target=' + target, '--sysroot=' + str(sysroot), '-std=c++17', '-fsyntax-only',
            '-H', '-I' + str(hpp), '-I' + str(spirv / 'include'), '-I' + str(sysroot / 'usr/include'), str(source)]


def elf_identity(path, machine):
    with path.open('rb') as source:
        header = source.read(64)
    if (len(header) < 64 or header[:6] != b'\x7fELF\x02\x01' or
            struct.unpack_from('<H', header, 18)[0] != machine):
        raise DiagnosticError('Wrong ELF architecture: ' + path.name)
    return {**file_identity(path), 'requested_path': str(path), 'resolved_path': str(path.resolve()),
            'elf_class': 64, 'elf_endianness': 'little', 'elf_machine': machine,
            'elf_osabi': header[7], 'elf_type': struct.unpack_from('<H', header, 16)[0]}


def include_evidence(stderr, sysroot, hpp, spirv):
    paths = [Path(p) for p in re.findall(r'^\.+ (.+)$', stderr, re.M)]
    required = {sysroot / 'usr/include/vulkan/vulkan_core.h', hpp / 'vulkan/vulkan.hpp',
                spirv / 'include/spirv/unified1/spirv.hpp'}
    if not required.issubset(paths):
        raise DiagnosticError('Required qualified includes absent from actual -H evidence')
    for path in paths:
        if ('vulkan' in path.parts and not (path.is_relative_to(hpp) or
                                            path.is_relative_to(sysroot / 'usr/include/vulkan')) or
                'spirv' in path.parts and not path.is_relative_to(spirv / 'include/spirv')):
            raise DiagnosticError('Mixed/unqualified target headers')
    return {str(p): file_identity(p) for p in sorted(set(paths))}


def read_context(repository, pins, deadline=None, commands=None):
    deadline = min(time.monotonic() + 30, deadline) if deadline is not None else time.monotonic() + 30
    commands = [] if commands is None else commands

    def git(*args, cwd=repository):
        result = run_command(['git', *args], cwd, None, 'admission-git-' + str(len(commands)), deadline, 10)
        commands.append({k: v for k, v in result.items() if not k.endswith('_text')})
        if result['process_status'] == 'INTERRUPTED':
            raise DiagnosticInterrupted(getattr(signal, result['interrupt_signal']))
        if result['process_status'] != 'EXITED' or result['exit_code'] != 0:
            raise DiagnosticError('Checkout admission Git inspection failed')
        return result['stdout_text'].rstrip('\n')

    if repository != Path(os.environ['GITHUB_WORKSPACE']).absolute():
        raise DiagnosticError('Require actual checked-out workspace')
    event_path = Path(os.environ['GITHUB_EVENT_PATH'])
    if event_path.stat().st_size > 1024**2:
        raise DiagnosticError('Push event too large')
    event = json.loads(event_path.read_text())
    vendor = repository / 'third_party/whisper.cpp'
    sha = git('rev-parse', 'HEAD')
    message = git('show', '-s', '--format=%B', 'HEAD')
    gitlink = git('ls-tree', 'HEAD', 'third_party/whisper.cpp').split()[2]
    vendor_sha = git('rev-parse', 'HEAD', cwd=vendor)
    dirty = git('status', '--porcelain', '--untracked-files=all', cwd=vendor)
    dirty += git('status', '--porcelain', '--untracked-files=all')
    identity = require_context(os.environ, event, sha, message, vendor_sha, gitlink, dirty,
                               platform.system(), platform.machine(), os.geteuid())
    consumed = {}
    for name, expected in pins['whisper_files'].items():
        path = no_links(vendor / name)
        if not path.is_relative_to(vendor):
            raise DiagnosticError('Source identity escapes pinned vendor')
        observed = file_identity(path)
        if observed['sha256'] != expected:
            raise DiagnosticError('Consumed pinned source changed: ' + name)
        consumed[name] = observed
    identity.update(admission_commands=commands, consumed_whisper_files=consumed)
    return identity


def owned_paths(env):
    temp = no_links(Path(env['VULKAN_RUNNER_TEMP']))
    if temp != Path(env['RUNNER_TEMP']).absolute():
        raise DiagnosticError('Step runner.temp does not match native RUNNER_TEMP')
    prefix = 'lip-vulkan-preflight-' + env['GITHUB_RUN_ID'] + '-' + env['GITHUB_RUN_ATTEMPT']
    result = []
    for key, suffix in [('VULKAN_EVIDENCE', '-evidence'), ('VULKAN_SCRATCH', '-scratch')]:
        path = no_links(Path(env[key]))
        if path != temp / (prefix + suffix) or not path.is_dir() or path.stat().st_uid != os.geteuid():
            raise DiagnosticError('Unowned diagnostic path')
        result.append(path)
    return result


def collect(repository, pins, scratch, report, deadline):
    free = shutil.disk_usage(scratch).free
    report.data.update(stage='capacity', scratch_free_bytes=free, required_free_bytes=2 * 1024**3)
    report.save()
    if free < 2 * 1024**3:
        raise DiagnosticError('Require two GiB free before bounded header/probe acquisition')
    ndk = Path(os.environ['ANDROID_HOME']) / 'ndk' / pins['ndk']
    toolchain = ndk / 'toolchains/llvm/prebuilt/linux-x86_64'
    sysroot = toolchain / 'sysroot'
    core = sysroot / 'usr/include/vulkan/vulkan_core.h'
    properties = ndk / 'source.properties'
    report.data.update(stage='ndk-identity', pending_operation='source.properties and vulkan_core.h')
    report.save()
    props = properties.read_text()
    if not re.search(r'^Pkg.Revision\s*=\s*' + re.escape(pins['ndk']) + r'\s*$', props, re.M):
        raise DiagnosticError('Wrong installed NDK revision')
    if file_identity(core)['sha256'] != CORE_SHA:
        raise DiagnosticError('Linux NDK revision-335 C header digest mismatch')
    report.data.update(stage='tools', ndk_properties={'text': props, **file_identity(properties)},
                       c_header={**file_identity(core), 'revision': 335,
                                 'spdx': re.findall(r'SPDX-License-Identifier:\s*([^\r\n]+)', core.read_text()),
                                 'source_commit': HEADERS, 'notice_prefix': core.read_text()[:1600]},
                       tools={}, android_api33_stubs={}, ndk_notices={})
    for notice in ('NOTICE', 'NOTICE.toolchain'):
        report.data['pending_operation'] = 'ndk-' + notice
        report.save()
        report.data['ndk_notices'][notice] = file_identity(ndk / notice)
    for triplet, machine in [('aarch64-linux-android', 183), ('x86_64-linux-android', 62)]:
        stub = sysroot / 'usr/lib' / triplet / '33/libvulkan.so'
        report.data['pending_operation'] = 'stub-' + triplet
        report.save()
        report.data['android_api33_stubs'][triplet] = elf_identity(stub, machine)
    tools = {'glslc': ndk / 'shader-tools/linux-x86_64/glslc', 'android-clang': toolchain / 'bin/clang++',
             'cmake': Path(os.environ['ANDROID_HOME']) / 'cmake' / pins['cmake'] / 'bin/cmake',
             'ninja': Path(os.environ['ANDROID_HOME']) / 'cmake' / pins['cmake'] / 'bin/ninja',
             'host-gcc': Path(shutil.which('gcc') or '/missing/gcc'),
             'host-g++': Path(shutil.which('g++') or '/missing/g++')}
    report.data['planned_tools'] = {k: str(v) for k, v in tools.items()}
    for name, tool in tools.items():
        report.data['pending_operation'] = name
        report.save()
        if not os.access(tool, os.X_OK):
            raise DiagnosticError('Required Linux tool missing: ' + name)
        report.data['tools'][name] = elf_identity(tool, 62)
        report.save()
        result = run_command([tool, '--version'], scratch, report.evidence, name + '-version', deadline, 10)
        record_probe(report, result)
        if name == 'cmake' and not re.search(r'^cmake version 4\.1\.2\s*$', result['stdout_text'], re.M):
            raise DiagnosticError('Installed CMake version differs from qualified 4.1.2')
        if name in ('host-gcc', 'host-g++'):
            target = run_command([tool, '-dumpmachine'], scratch, report.evidence, name + '-target', deadline, 10)
            record_probe(report, target)
            report.data['tools'][name]['default_target'] = target['stdout_text'].strip()
            if target['stdout_text'].strip() != 'x86_64-linux-gnu':
                raise DiagnosticError('Generator compiler does not target native Linux x86_64')
    record_probe(report, run_command([tools['glslc'], '--help'], scratch, report.evidence, 'glslc-help', deadline, 10))
    report.data['generator'] = {'kind': 'pinned source, not built/executed', 'host_cxx': 'host-g++',
                                'source': report.data['identity']['consumed_whisper_files'][
                                    'ggml/src/ggml-vulkan/vulkan-shaders/vulkan-shaders-gen.cpp']}
    report.data.update(stage='headers', acquisition_manifest=pins['archives'])
    report.save()
    sources = []
    for pin in pins['archives']:
        report.data['pending_operation'] = 'archive-' + pin['root']
        report.save()
        path = acquire_archive(pin, scratch, report, deadline)
        selected = scratch / pin['root']
        record = report.data['archives'][-1]
        try:
            headers = unpack_archive(path, selected, pin)
        except (Exception, KeyboardInterrupt) as error:
            record.update(consumption_status='FAILED', error_type=type(error).__name__)
            report.fail(error)
            raise
        record.update(consumption_status='COMPLETE', selected_headers=headers)
        license_text = selected / pin['license']
        if license_text.stat().st_size > 256 * 1024:
            raise DiagnosticError('License text exceeds evidence ceiling')
        record['license_identity'] = file_identity(license_text)
        retained = retain_text(report.evidence, pin['root'] + '-license.txt', license_text.read_bytes())
        record['retained_license_identity'] = {'bytes': len(retained), 'sha256': hashlib.sha256(retained).hexdigest(),
                                               'complete': len(retained) == license_text.stat().st_size}
        if not record['retained_license_identity']['complete']:
            raise DiagnosticError('License retention exceeds diagnostic artifact ceiling')
        report.save()
        sources.append(selected)
    shaders = repository / 'third_party/whisper.cpp/ggml/src/ggml-vulkan/vulkan-shaders'
    (scratch / 'tiny.comp').write_text('#version 450\nlayout(local_size_x=1) in;\nvoid main() {}\n')
    report.data['tiny_shader'] = {**file_identity(scratch / 'tiny.comp'), 'text': (scratch / 'tiny.comp').read_text()}
    plan = shader_plan(tools['glslc'], shaders, scratch)
    report.data.update(stage='shaders', planned_shader_probes=plan, completed_shader_probes=[])
    report.save()
    for probe in plan:
        report.data['pending_operation'] = probe['id']
        report.save()
        result = run_command(probe['argv'], scratch, report.evidence, probe['id'], deadline)
        record_probe(report, result, probe.get('extension'), reject_diagnostics=True)
        if result['exit_code'] == 0:
            output = scratch / (probe['id'] + '.spv')
            with output.open('rb') as source:
                if source.read(4) != b'\x03\x02\x23\x07' or output.stat().st_size < 20 or output.stat().st_size % 4:
                    raise DiagnosticError('Invalid/missing diagnostic SPIR-V output')
            report.data['probes'][-1]['output_identity'] = file_identity(output)
            depfile = scratch / (probe['id'] + '.d')
            if '-MD' in probe['argv']:
                if not depfile.is_file() or depfile.stat().st_size > OUTPUT_LIMIT:
                    raise DiagnosticError('Missing or oversized shader include depfile')
                deps = shlex.split(depfile.read_text().replace('\\\n', ' '))[1:]
                known = {repository / 'third_party/whisper.cpp' / p for p in pins['whisper_files']}
                if not deps or any(Path(p).resolve() not in known for p in deps):
                    raise DiagnosticError('Shader include outside qualified frozen source set')
                report.data['probes'][-1]['include_identities'] = {p: file_identity(Path(p)) for p in deps}
        report.data['completed_shader_probes'].append(probe['id'])
        report.save()
    hpp, spirv = sources
    source = scratch / 'header-probe.cpp'
    source.write_text('#include <vulkan/vulkan.hpp>\n#include <spirv/unified1/spirv.hpp>\n'
                      'static_assert(VK_HEADER_VERSION == 335);\n'
                      'using Dispatch = vk::detail::DispatchLoaderDynamic;\n'
                      'using NV = vk::PhysicalDeviceCooperativeMatrix2PropertiesNV;\n'
                      'static_assert(sizeof(Dispatch) > 0 && sizeof(NV) > 0);\n'
                      'constexpr unsigned enums[] = {' + ','.join('spv::' + x for x in SPV_NAMES) + '};\n')
    report.data.update(stage='android-header-syntax', header_probe={**file_identity(source), 'text': source.read_text()},
                       planned_android_targets=list(TARGETS), completed_android_targets=[])
    report.save()
    for target in TARGETS:
        report.data['pending_operation'] = 'headers-' + target
        report.save()
        result = run_command(header_command(tools['android-clang'], target, sysroot, hpp, spirv, source),
                             scratch, report.evidence, 'headers-' + target, deadline)
        record_probe(report, result)
        report.data['probes'][-1]['actual_include_identities'] = include_evidence(result['stderr_text'], sysroot, hpp, spirv)
        report.data['completed_android_targets'].append(target)
        report.save()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('admit', 'install', 'collect'))
    mode = parser.parse_args().mode
    started = time.monotonic()
    repository = Path(__file__).absolute().parent.parent
    pins = json.loads((repository / 'eval/vulkan-preflight-pins.json').read_text())
    require_pins(pins)
    previous_handlers = {}

    def interrupted(signum, _frame):
        raise DiagnosticInterrupted(signum)

    try:
        for signum in (signal.SIGTERM, signal.SIGINT):
            previous_handlers[signum] = signal.signal(signum, interrupted)
        if mode == 'admit':
            identity = read_context(repository, pins)  # Admission still precedes SDK/tools/network/compilation.
            if Path(os.environ['VULKAN_RUNNER_TEMP']).absolute() != Path(os.environ['RUNNER_TEMP']).absolute():
                raise DiagnosticError('Step runner.temp does not match native RUNNER_TEMP')
            evidence, scratch = fresh_directories(Path(os.environ['VULKAN_RUNNER_TEMP']), identity['run_id'], identity['run_attempt'])
            report = Report(evidence, identity)
            report.data['pins'] = pins
            report.data['diagnostic_sources'] = {str(p.relative_to(repository)): file_identity(p) for p in
                (repository / 'eval/vulkan_preflight.py', repository / 'eval/vulkan-preflight-pins.json',
                 repository / '.github/workflows/vulkan-preflight.yml')}
            report.save()
            with Path(os.environ['GITHUB_OUTPUT']).open('a') as output:
                output.write('evidence=' + str(evidence) + '\nscratch=' + str(scratch) + '\n')
            return 0
        evidence, scratch = owned_paths(os.environ)
        path = no_links(evidence / 'report.json')
        if path.stat().st_size > ARTIFACT_LIMIT:
            raise DiagnosticError('Existing owned report exceeds diagnostic artifact ceiling')
        report = Report.__new__(Report)
        report.evidence = evidence
        report.data = json.loads(path.read_text())
        if (report.data.get('schema_version') != 1 or report.data.get('pins') != pins or
                report.data.get('status') != 'PARTIAL' or not isinstance(report.data.get('identity'), dict)):
            raise DiagnosticError('Invalid existing owned admission report; do not replay diagnostic')
        try:
            report.data.update(stage=mode + '-admission', pending_operation='checkout-readmission')
            report.data[mode + '_admission_status'] = 'PARTIAL'
            report.data[mode + '_admission_commands'] = []
            deadline = started + 1200
            if mode == 'collect':
                if report.data.get('sdk_setup_complete') is not True:
                    raise DiagnosticError('Pinned SDK setup did not complete')
                if signal.getitimer(signal.ITIMER_REAL) != (0.0, 0.0):
                    raise DiagnosticError('Refuse to replace existing diagnostic alarm')

                def expired(_signal, _frame):
                    raise TimeoutError('Five-minute total diagnostic deadline exceeded')

                deadline = report.data['sdk_setup_finished_monotonic'] + TOTAL_SECONDS
                remaining = command_budget(deadline, TOTAL_SECONDS, ceiling=TOTAL_SECONDS)
                report.data['diagnostic_deadline_monotonic'] = deadline
                previous_handlers[signal.SIGALRM] = signal.signal(signal.SIGALRM, expired)
                signal.setitimer(signal.ITIMER_REAL, remaining)
            report.save()
            identity = read_context(repository, pins, deadline=deadline,
                                    commands=report.data[mode + '_admission_commands'])
            stable = lambda value: {k: v for k, v in value.items() if k != 'admission_commands'}
            if stable(report.data['identity']) != stable(identity):
                raise DiagnosticError('Admission identity changed; do not replay diagnostic')
            report.data[mode + '_admission_commands'] = identity['admission_commands']
            report.data[mode + '_admission_status'] = 'COMPLETE'
            report.save()
            command_budget(deadline, 60)
            if mode == 'install':
                report.data.update(stage='sdk-setup', pending_operation='sdkmanager-identity')
                sdkmanager = Path(os.environ['ANDROID_HOME']) / 'cmdline-tools/latest/bin/sdkmanager'
                report.data['sdkmanager_identity'] = file_identity(sdkmanager)
                env = {**os.environ, 'MALLOC_ARENA_MAX': '2', 'JAVA_OPTS': '-Xmx512m -XX:MaxMetaspaceSize=256m -XX:CompressedClassSpaceSize=128m '
                                               '-XX:ReservedCodeCacheSize=128m -XX:ActiveProcessorCount=1'}
                report.data['pending_operation'] = 'sdkmanager-version'
                report.save()
                record_probe(report, run_command([sdkmanager, '--version'], scratch, evidence, 'sdkmanager-version',
                                                 deadline, seconds=10, env=env))
                report.data['pending_operation'] = 'sdk-setup'
                report.save()
                result = run_command([sdkmanager, 'ndk;' + pins['ndk'], 'cmake;' + pins['cmake']],
                                     scratch, evidence, 'sdk-setup', deadline,
                                     seconds=1200, ceiling=1200, env=env, file_size_bytes=2 * 1024**3)
                record_probe(report, result)
                report.data['sdk_setup_complete'] = True
                report.data['sdk_setup_finished_monotonic'] = time.monotonic()
            else:
                collect(repository, pins, scratch, report, deadline)
                report.data['diagnostic_elapsed_seconds'] = time.monotonic() - report.data['sdk_setup_finished_monotonic']
                if time.monotonic() > deadline:
                    raise TimeoutError('Diagnostic collection completed outside five-minute deadline')
                report.finish(completed=True)
            report.save()
            return 0
        except (Exception, KeyboardInterrupt) as error:
            if signal.SIGALRM in previous_handlers:
                signal.setitimer(signal.ITIMER_REAL, 0)
            if report.data.get(mode + '_admission_status') == 'PARTIAL':
                report.data[mode + '_admission_status'] = 'FAILED'
            if isinstance(error, (KeyboardInterrupt, DiagnosticInterrupted)):
                commands = report.data.get(mode + '_admission_commands', [])
                operation = (commands[-1]['id'] if commands and commands[-1]['process_status'] == 'INTERRUPTED'
                             else report.data['pending_operation'])
                report.data.update(interrupted_operation=operation,
                                   interrupt_signal=error.signal_name if isinstance(error, DiagnosticInterrupted) else 'SIGINT')
            report.data[mode + '_elapsed_seconds'] = time.monotonic() - started
            report.fail(error)
            return 1
    finally:
        if signal.SIGALRM in previous_handlers:
            signal.setitimer(signal.ITIMER_REAL, 0)
        for signum, previous in previous_handlers.items():
            signal.signal(signum, previous)


if __name__ == '__main__':
    try:
        if sys.platform != 'linux' or platform.machine() != 'x86_64' or os.geteuid() <= 0:
            raise DiagnosticError('CLI requires unprivileged Linux x86_64')
        resource.setrlimit(resource.RLIMIT_AS, (2 * 1024**3, 2 * 1024**3))
        sys.exit(main())
    except Exception as error:
        print('Preflight admission/collection stopped: ' + type(error).__name__, file=sys.stderr)
        sys.exit(1)
