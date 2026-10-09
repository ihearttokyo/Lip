"""Opt-in Linux full ARM64/API33 backend cross-build; never GPU/runtime acceptance."""
import argparse
from contextlib import ExitStack
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
import subprocess
import sys
import tarfile
import time

import vulkan_preflight as vp

REF = 'refs/heads/codex/lip-vulkan-build'
MARKER = '[vulkan-build-canary]'
SECONDS = 45 * 60
LOG_LIMIT = 1024**2
AS_LIMIT = 8 * 1024**3
FILE_LIMIT = 1024**3
JAVA_OPTS = ('-Xmx512m -XX:MaxMetaspaceSize=256m -XX:CompressedClassSpaceSize=128m '
             '-XX:ReservedCodeCacheSize=128m -XX:ActiveProcessorCount=1')
ENVIRONMENT = dict(GITHUB_ACTIONS='true', GITHUB_REPOSITORY='ihearttokyo/Lip', GITHUB_EVENT_NAME='push',
                   GITHUB_REPOSITORY_OWNER='ihearttokyo',
                   GITHUB_WORKFLOW_REF='ihearttokyo/Lip/.github/workflows/vulkan-build.yml@' + REF,
                   GITHUB_REF=REF, GITHUB_SERVER_URL='https://github.com', RUNNER_OS='Linux',
                   RUNNER_ARCH='X64', RUNNER_ENVIRONMENT='github-hosted', ImageOS='ubuntu24')


def require_host(env, system, machine, uid):
    if (any(env.get(k) != v for k, v in ENVIRONMENT.items()) or system != 'Linux' or
            machine != 'x86_64' or uid <= 0 or not env.get('ImageVersion') or
            any(not re.fullmatch(r'[1-9][0-9]*', env.get(k, '')) for k in ('GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT'))):
        raise vp.DiagnosticError('Require unprivileged owned GitHub-hosted Ubuntu24 X64 build push')


def require_context(env, event, sha, message, vendor_sha, gitlink, dirty, system, machine, uid):
    require_host(env, system, machine, uid)
    repo, head = event.get('repository', {}), event.get('head_commit') or {}
    if (repo.get('full_name') != 'ihearttokyo/Lip' or repo.get('private') is not False or
            event.get('ref') != REF or event.get('after') != sha or head.get('id') != sha or
            env.get('GITHUB_SHA') != sha or not re.fullmatch(r'[0-9a-f]{40}', sha) or
            MARKER not in message or head.get('message') != message or
            vendor_sha != vp.WHISPER or gitlink != vp.WHISPER or dirty):
        raise vp.DiagnosticError('Require exact public opt-in event/HEAD/message and pristine pinned source')
    return dict(checkout_sha=sha, message_sha256=hashlib.sha256(message.encode()).hexdigest(),
                whisper_revision=vendor_sha, ref=REF, repository=repo['full_name'], uid=uid,
                run_id=env['GITHUB_RUN_ID'], run_attempt=env['GITHUB_RUN_ATTEMPT'],
                image_os=env['ImageOS'], image_version=env['ImageVersion'])


def limits(seconds, address_space=AS_LIMIT, file_limit=FILE_LIMIT):
    for key, bound in ((resource.RLIMIT_AS, address_space), (resource.RLIMIT_FSIZE, file_limit),
                       (resource.RLIMIT_CORE, 0), (resource.RLIMIT_CPU, math.ceil(seconds) + 1)):
        resource.setrlimit(key, (bound, bound))


def stop_group(child):
    try:
        os.killpg(child.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    child.wait(timeout=5)
    deadline = time.monotonic() + 5
    while True:
        try:
            os.killpg(child.pid, 0)
        except ProcessLookupError:
            return
        if time.monotonic() >= deadline:
            raise TimeoutError('Owned process group did not join; hold all downstream work')
        time.sleep(.05)


def run_command(argv, cwd, evidence, name, deadline, seconds=SECONDS, env=None,
                address_space=AS_LIMIT, file_limit=FILE_LIMIT):
    budget = vp.command_budget(deadline, seconds, ceiling=SECONDS)
    started, output, digest, observed, child = time.monotonic(), bytearray(), hashlib.sha256(), 0, None
    result = dict(id=name, argv=list(map(str, argv)), cwd=str(cwd), timeout_seconds=budget,
                  address_space_bytes=address_space, file_size_limit_bytes=file_limit,
                  process_status='LAUNCH_FAILED', exit_code=None, cleanup_status='NOT_LAUNCHED', text='',
                  output=dict(bytes_observed=0, observed_sha256=digest.hexdigest(), retained_bytes=0,
                              retained_sha256=digest.hexdigest(), complete=False))
    def interrupted(error):
        if result['process_status'] not in ('TIMEOUT', 'INTERRUPTED'):
            result['process_status'] = 'TIMEOUT' if isinstance(error, (TimeoutError, subprocess.TimeoutExpired)) else 'INTERRUPTED'
        result.setdefault('error_type', type(error).__name__)
        if result['process_status'] == 'INTERRUPTED':
            result.setdefault('interrupt_signal', error.signal_name if isinstance(error, vp.DiagnosticInterrupted) else 'SIGINT')
    try:
        child = subprocess.Popen(result['argv'], cwd=cwd, env=env, stdin=subprocess.DEVNULL,
                                 stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True,
                                 preexec_fn=lambda: limits(budget, address_space, file_limit))
        result['process_status'] = 'EXITED'
        result['cleanup_status'] = 'HOLD'
        with selectors.DefaultSelector() as selector:
            selector.register(child.stdout, selectors.EVENT_READ)
            while selector.get_map():
                remaining = started + budget - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError('Command deadline exhausted')
                for key, _ in selector.select(min(remaining, .5)):
                    chunk = os.read(key.fileobj.fileno(), 65536)
                    if not chunk:
                        selector.unregister(key.fileobj)
                        continue
                    observed += len(chunk)
                    digest.update(chunk)
                    available = LOG_LIMIT - len(output)
                    output.extend(chunk[:available])
                    if len(chunk) > available:
                        result['process_status'] = 'OUTPUT_LIMIT'
                        raise vp.DiagnosticError('Command output exceeded one MiB')
            result['exit_code'] = child.wait(timeout=max(.001, started + budget - time.monotonic()))
    except (TimeoutError, subprocess.TimeoutExpired) as error:
        interrupted(error)
    except (KeyboardInterrupt, vp.DiagnosticInterrupted) as error:
        interrupted(error)
    except (OSError, subprocess.SubprocessError, vp.DiagnosticError) as error:
        if result['process_status'] != 'OUTPUT_LIMIT':
            result['process_status'] = 'LAUNCH_FAILED'
        result['error_type'] = type(error).__name__
    finally:
        retained = output
        try:
            with ExitStack() as boundary:
                try:
                    boundary.enter_context(vp.retention_boundary())
                except (TimeoutError, KeyboardInterrupt, vp.DiagnosticInterrupted) as error:
                    interrupted(error)
                # Boundary entry can itself be interrupted; still attempt bounded cleanup exactly once.
                if child is not None:
                    try:
                        stop_group(child)
                        result['cleanup_status'] = 'JOINED'
                    except (OSError, TimeoutError, subprocess.SubprocessError, KeyboardInterrupt, vp.DiagnosticInterrupted) as error:
                        result.update(cleanup_status='HOLD', cleanup_error_type=type(error).__name__)
                        if isinstance(error, (TimeoutError, subprocess.TimeoutExpired, KeyboardInterrupt, vp.DiagnosticInterrupted)):
                            interrupted(error)
                    finally:
                        try:
                            child.stdout.close()
                        except (OSError, TimeoutError, KeyboardInterrupt, vp.DiagnosticInterrupted) as error:
                            result.update(cleanup_status='HOLD', cleanup_error_type=type(error).__name__)
                            if isinstance(error, (TimeoutError, KeyboardInterrupt, vp.DiagnosticInterrupted)):
                                interrupted(error)
                        result['exit_code'] = child.returncode
                retained = vp.retain_text(evidence, name + '.txt', output) if evidence is not None else output
                if len(retained) < len(output):
                    result['artifact_limit_reached'] = True
                    if result['process_status'] == 'EXITED':
                        result['process_status'] = 'ARTIFACT_LIMIT'
        except (TimeoutError, KeyboardInterrupt, vp.DiagnosticInterrupted) as error:
            interrupted(error)
        finally:
            result.update(elapsed_seconds=time.monotonic() - started,
                          output=dict(bytes_observed=observed, observed_sha256=digest.hexdigest(),
                                      retained_bytes=len(retained), retained_sha256=hashlib.sha256(retained).hexdigest(),
                                      complete=result['process_status'] == 'EXITED' and result['cleanup_status'] == 'JOINED'),
                          text=bytes(retained).decode('utf-8', errors='replace'))
    return result


def checked(report, argv, cwd, name, deadline, **kwargs):
    if report is not None:
        report.data.update(pending_operation=name)
        report.save()
    result = run_command(argv, cwd, report.evidence if report else None, name, deadline, **kwargs)
    passed = result['process_status'] == 'EXITED' and result['exit_code'] == 0 and result['cleanup_status'] == 'JOINED'
    if report is not None:
        report.data['probes'].append({k: v for k, v in result.items() if k != 'text'})
        report.save()
    if not passed:
        raise vp.DiagnosticError('Required command failed or cleanup held: ' + name)
    return result['text']


def read_context(root, pins, deadline, report=None):
    env = os.environ
    require_host(env, platform.system(), platform.machine(), os.geteuid())
    if root != Path(env['GITHUB_WORKSPACE']).absolute():
        raise vp.DiagnosticError('Require actual checkout workspace')
    path = vp.no_links(Path(env['GITHUB_EVENT_PATH']))
    if path.stat().st_size > LOG_LIMIT:
        raise vp.DiagnosticError('Event exceeds byte ceiling')
    event = json.loads(path.read_text())
    # Event/host denial precedes even Git inspection; the actual vendor is checked below.
    head = event.get('head_commit') or {}
    require_context(env, event, env.get('GITHUB_SHA', ''), head.get('message', ''),
                    vp.WHISPER, vp.WHISPER, '', platform.system(), platform.machine(), os.geteuid())
    vendor = root / 'third_party/whisper.cpp'
    def git(*args, cwd=root):
        name = 'admission-git-' + str(len(report.data['probes'])) if report else 'admission-git'
        return checked(report, ['git', *args], cwd, name, deadline, seconds=10).rstrip('\n')
    identity = require_context(env, event, git('rev-parse', 'HEAD'), git('show', '-s', '--format=%B', 'HEAD'),
                               git('rev-parse', 'HEAD', cwd=vendor),
                               git('ls-tree', 'HEAD', 'third_party/whisper.cpp').split()[2],
                               git('status', '--porcelain', '--untracked-files=all') +
                               git('status', '--porcelain', '--untracked-files=all', '--ignored=matching', cwd=vendor),
                               platform.system(), platform.machine(), os.geteuid())
    consumed = {}
    for name, expected in pins['whisper_files'].items():
        item = vp.file_identity(vp.no_links(vendor / name))
        if item['sha256'] != expected:
            raise vp.DiagnosticError('Pinned source digest changed: ' + name)
        consumed[name] = item
    identity['pinned_files'] = consumed
    identity['sources'] = {name: vp.file_identity(vp.no_links(root / name)) for name in
        ('eval/vulkan_build_ci.py', 'eval/test_vulkan_build_ci.py', '.github/workflows/vulkan-build.yml',
         'eval/vulkan_preflight.py', 'eval/vulkan-preflight-pins.json')}
    return identity


def paths(env, fresh=False):
    temp = vp.no_links(Path(env['VULKAN_RUNNER_TEMP']))
    if temp != Path(env['RUNNER_TEMP']).absolute() or temp.stat().st_uid != os.geteuid():
        raise vp.DiagnosticError('Require actual owned runner.temp')
    prefix = 'lip-vulkan-build-' + env['GITHUB_RUN_ID'] + '-' + env['GITHUB_RUN_ATTEMPT']
    result = [temp / (prefix + suffix) for suffix in ('-evidence', '-scratch')]
    for path in result:
        vp.no_links(path)
        if fresh:
            if path.exists():
                raise vp.DiagnosticError('Refuse stale build directories')
        elif not path.is_dir() or path.stat().st_uid != os.geteuid():
            raise vp.DiagnosticError('Unowned build directory')
    if fresh:
        for path in result:
            path.mkdir(mode=0o700)
    elif result != [Path(env['VULKAN_EVIDENCE']), Path(env['VULKAN_SCRATCH'])]:
        raise vp.DiagnosticError('Build path identity changed')
    return result


def require_capacity(scratch, report):
    memory = Path('/proc/meminfo').read_text()
    match = re.search(r'^MemAvailable:\s+([0-9]+) kB$', memory, re.M)
    available = int(match[1]) * 1024 if match else 0
    free = shutil.disk_usage(scratch).free
    report.data['capacity'] = dict(available_ram_bytes=available, free_disk_bytes=free)
    report.save()
    if available < 10 * 1024**3 or free < 6 * 1024**3:
        raise vp.DiagnosticError('Require ten GiB available RAM and six GiB scratch free')


def acquire(pin, scratch, report, deadline):
    path = vp.acquire_archive(pin, scratch, report, deadline)
    record = report.data['archives'][-1]
    record['consumption_status'] = 'PARTIAL'
    try:
        readback = vp.file_identity(path)
        if readback != {k: record[k] for k in ('bytes', 'sha256')}:
            raise vp.DiagnosticError('Complete archive readback SHA/size mismatch')
        selected = scratch / pin['root']
        record['full_readback'] = readback
        record['selected_headers'] = vp.unpack_archive(path, selected, pin)
        # Restore the original SPIRV CMake support from the same fully validated tar, not a fake package config.
        record['build_support'] = {}
        if pin['root'] == 'SPIRV-Headers':
            with tarfile.open(path.with_suffix('.tar'), 'r:') as source:
                for member in source:
                    if member.isfile():
                        relative = Path(*Path(member.name).parts[1:])
                        target = vp.no_links(selected / relative)
                        if not target.is_relative_to(selected) or '..' in relative.parts:
                            raise vp.DiagnosticError('Archive build support escapes root')
                        if not target.exists():
                            target.parent.mkdir(parents=True, exist_ok=True)
                            with source.extractfile(member) as data, target.open('xb') as output:
                                shutil.copyfileobj(data, output, 65536)
                            if target.stat().st_size != member.size:
                                raise vp.DiagnosticError('Incomplete archive build support')
                            record['build_support'][relative.as_posix()] = vp.file_identity(target)
        for name, expected in record['selected_headers'].items():
            if vp.file_identity(selected / name) != {k: expected[k] for k in ('bytes', 'sha256')}:
                raise vp.DiagnosticError('Selected dependency readback changed')
        license_path = selected / pin['license']
        license_data = license_path.read_bytes()
        if len(license_data) > LOG_LIMIT or vp.retain_text(report.evidence, pin['root'] + '-license.txt', license_data) != license_data:
            raise vp.DiagnosticError('Incomplete license retention')
        record['consumption_status'] = 'COMPLETE'
        report.save()
        return selected
    except (Exception, KeyboardInterrupt) as error:
        record.update(consumption_status='FAILED', error_type=type(error).__name__)
        report.save()
        raise


def configure_command(cmake, ninja, vendor, build, ndk, hpp, spirv, package, glslc):
    sysroot = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/sysroot'
    return [cmake, '-S', vendor / 'ggml', '-B', build, '-G', 'Ninja', '-DCMAKE_MAKE_PROGRAM=' + str(ninja),
            '-DCMAKE_TOOLCHAIN_FILE=' + str(ndk / 'build/cmake/android.toolchain.cmake'),
            '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-33', '-DANDROID_STL=c++_shared',
            '-DCMAKE_BUILD_TYPE=Release', '-DBUILD_SHARED_LIBS=ON', '-DGGML_BACKEND_DL=OFF',
            '-DGGML_VULKAN=ON', '-DGGML_CPU=OFF', '-DGGML_NATIVE=OFF', '-DGGML_CCACHE=OFF',
            '-DGGML_OPENMP=OFF', '-DGGML_BUILD_TESTS=OFF', '-DGGML_BUILD_EXAMPLES=OFF',
            '-DCMAKE_EXPORT_COMPILE_COMMANDS=ON', '-DCMAKE_SHARED_LINKER_FLAGS=-Wl,--no-undefined',
            '-DCMAKE_CXX_FLAGS=-I' + str(hpp) + ' -I' + str(spirv / 'include'),
            '-DVulkan_INCLUDE_DIR=' + str(sysroot / 'usr/include'),
            '-DVulkan_LIBRARY=' + str(sysroot / 'usr/lib/aarch64-linux-android/33/libvulkan.so'),
            '-DVulkan_GLSLC_EXECUTABLE=' + str(glslc), '-DSPIRV-Headers_DIR=' + str(package)]


def qualify_objects(rows, shaders, build):
    build = vp.no_links(build).resolve()
    shaders = vp.no_links(shaders).resolve()
    vendor = shaders.parents[3]
    generated_root = build / 'src/ggml-vulkan'
    expected = {generated_root / (vp.no_links(p).name + '.cpp') for p in shaders.glob('*.comp')}
    result = []
    for row in rows:
        args = row.get('arguments') or shlex.split(row['command'])
        targets = [a for a in args if a.startswith('--target=')]
        if len(targets) != 1 or not re.fullmatch(r'--target=aarch64(?:-none)?-linux-android33', targets[0]):
            raise vp.DiagnosticError('Compilation is not ARM64 API33')
        if args.count('-c') != 1 or args.count('-o') != 1 or any(args.index(flag) + 1 >= len(args) for flag in ('-c', '-o')):
            raise vp.DiagnosticError('Require one actual compilation input and object output')
        inputs, takes_value = [], False
        for arg in args[1:]:
            if takes_value:
                takes_value = False
            elif arg in ('-o', '-I', '-isystem', '-iquote', '-include', '-imacros', '-idirafter', '-isysroot',
                         '--sysroot', '-target', '--target', '-x', '-MF', '-MT', '-MQ', '-Xclang', '-Xpreprocessor', '-Xassembler'):
                takes_value = True
            elif not arg.startswith('-'):
                inputs.append(arg)
        if inputs != [args[args.index('-c') + 1]]:
            raise vp.DiagnosticError('Additional or ambiguous compilation input')
        directory = vp.no_links(Path(row['directory'])).resolve()
        if not directory.is_relative_to(build):
            raise vp.DiagnosticError('Compilation directory escapes owned build')
        source, compiled, obj = [vp.no_links(directory / name).resolve() for name in
                                 (row['file'], inputs[0], args[args.index('-o') + 1])]
        if (source != compiled or
                (source not in expected if source.name.endswith('.comp.cpp') else not source.is_relative_to(vendor / 'ggml'))):
            raise vp.DiagnosticError('Compilation source differs from owned generated or pinned vendor source')
        if not obj.is_relative_to(build):
            raise vp.DiagnosticError('Compiled object escapes owned build')
        identity = vp.elf_identity(obj, 183)
        if identity['elf_type'] != 1:
            raise vp.DiagnosticError('Expected actual Android relocatable object')
        result.append(dict(source=str(source), source_identity=vp.file_identity(source), object=identity))
    generated = [Path(row['source']) for row in result if row['source'].endswith('.comp.cpp')]
    if not expected or set(generated) != expected or len(generated) != len(expected):
        raise vp.DiagnosticError('Missing or duplicate generated shader compilation')
    if sum(Path(row['source']) == shaders.parent / 'ggml-vulkan.cpp' for row in result) != 1:
        raise vp.DiagnosticError('Missing full Vulkan backend compilation')
    return result


def qualify_generated(header, sources, spv):
    if header.stat().st_size > LOG_LIMIT:
        raise vp.DiagnosticError('Generated header exceeds evidence ceiling')
    declarations = re.findall(r'extern const unsigned char ([A-Za-z0-9_]+)_data\[\];', header.read_text())
    lengths, arrays = {}, {}
    for source in sources:
        with source.open() as content:
            for line in content:
                match = re.match(r'const uint64_t ([A-Za-z0-9_]+)_len = ([0-9]+);', line)
                if match:
                    if match[1] in lengths:
                        raise vp.DiagnosticError('Duplicate generated shader variant')
                    lengths[match[1]] = int(match[2])
                match = re.match(r'const unsigned char ([A-Za-z0-9_]+)_data\[([0-9]+)\]', line)
                if match:
                    if match[1] in arrays:
                        raise vp.DiagnosticError('Duplicate generated shader array')
                    arrays[match[1]] = int(match[2])
    files = {p.stem: p for p in spv.glob('*.spv')}
    if (not declarations or len(set(declarations)) != len(declarations) or
            set(declarations) != set(lengths) or lengths != arrays or set(lengths) != set(files)):
        raise vp.DiagnosticError('Incomplete generated header/CPP/SPIR-V variant coverage')
    identities = {}
    for name, path in files.items():
        with path.open('rb') as data:
            valid = data.read(4) == b'\x03\x02\x23\x07'
        identity = vp.file_identity(path)
        if not valid or identity['bytes'] < 20 or identity['bytes'] % 4 or identity['bytes'] != lengths[name]:
            raise vp.DiagnosticError('Invalid or incomplete generated SPIR-V: ' + name)
        identities[name] = identity
    return identities


def needed(text):
    return re.findall(r'\(NEEDED\).*Shared library: \[([^\]\n]+)\]', text)


def verify_output(vendor, build, toolchain, ninja, report, deadline):
    generator = build / 'Release/vulkan-shaders-gen'
    report.data['host_generator'] = vp.elf_identity(generator, 62)
    if report.data['host_generator']['elf_type'] not in (2, 3):
        raise vp.DiagnosticError('Host generator is not a Linux executable')
    rows_path = build / 'compile_commands.json'
    if rows_path.stat().st_size > LOG_LIMIT:
        raise vp.DiagnosticError('Compile commands exceed retained log ceiling')
    data = rows_path.read_bytes()
    if vp.retain_text(report.evidence, 'compile-commands.txt', data) != data:
        raise vp.DiagnosticError('Incomplete compile-command retention')
    rows = json.loads(data)
    report.data['objects'] = qualify_objects(rows, vendor / 'ggml/src/ggml-vulkan/vulkan-shaders', build)
    header = build / 'src/ggml-vulkan/ggml-vulkan-shaders.hpp'
    report.data['generated_header'] = vp.file_identity(header)
    report.data['generated_spirv'] = qualify_generated(header,
        [Path(row['source']) for row in report.data['objects'] if row['source'].endswith('.comp.cpp')],
        header.parent / 'vulkan-shaders.spv')
    checked(report, [ninja, '-C', build, '-t', 'commands', 'ggml'], build, 'link-and-build-commands', deadline)
    nm, readelf = toolchain / 'bin/llvm-nm', toolchain / 'bin/llvm-readelf'
    libraries = {}
    for name, symbol in [('ggml-vulkan', 'ggml_backend_vk_reg'), ('ggml-base', 'ggml_init'),
                         ('ggml', 'ggml_backend_load_all')]:
        matches = list(build.rglob('lib' + name + '.so'))
        if len(matches) != 1:
            raise vp.DiagnosticError('Require exactly one full library: ' + name)
        path = matches[0].resolve()
        if not path.is_relative_to(build.resolve()):
            raise vp.DiagnosticError('Library escapes owned build')
        identity = vp.elf_identity(path, 183)
        symbols = checked(report, [nm, '-D', '--defined-only', path], build, name + '-symbols', deadline)
        if identity['elf_type'] != 3 or not re.search(r'\b' + symbol + r'$', symbols, re.M):
            raise vp.DiagnosticError('Missing full linked ARM64 backend/core symbol: ' + symbol)
        dynamic = checked(report, [readelf, '-h', '-d', path], build, name + '-readelf', deadline)
        sonames = re.findall(r'\(SONAME\).*Library soname: \[([^\]\n]+)\]', dynamic)
        if len(sonames) != 1:
            raise vp.DiagnosticError('Require actual linked library SONAME')
        libraries[sonames[0]] = dict(path=str(path), identity=identity, needed=needed(dynamic))
    pending = [name for lib in libraries.values() for name in lib['needed']]
    closure = dict(libraries)
    while pending:
        name = pending.pop()
        if name in closure:
            continue
        if not re.fullmatch(r'lib[A-Za-z0-9_+.-]+\.so(?:\.[0-9]+)*', name) or len(closure) >= 32:
            raise vp.DiagnosticError('Unsafe or unbounded dynamic dependency')
        base = toolchain / 'sysroot/usr/lib/aarch64-linux-android'
        path = base / ('libc++_shared.so' if name == 'libc++_shared.so' else '33/' + name)
        identity = vp.elf_identity(path, 183)
        if identity['elf_type'] != 3:
            raise vp.DiagnosticError('Dynamic dependency is not an ARM64 shared library')
        dynamic = checked(report, [readelf, '-h', '-d', path], build, 'dependency-' + name, deadline)
        closure[name] = dict(path=str(path), identity=identity, needed=needed(dynamic),
                             kind='NDK runtime' if name == 'libc++_shared.so' else 'API33 link stub, not device library')
        pending.extend(closure[name]['needed'])
    report.data.update(libraries=libraries, dynamic_closure=closure)
    report.save()


def build_backend(root, pins, scratch, report, deadline):
    require_capacity(scratch, report)
    ndk = Path(os.environ['ANDROID_HOME']) / 'ndk' / pins['ndk']
    toolchain = ndk / 'toolchains/llvm/prebuilt/linux-x86_64'
    core = toolchain / 'sysroot/usr/include/vulkan/vulkan_core.h'
    if (not re.search(r'^Pkg.Revision\s*=\s*' + re.escape(pins['ndk']) + r'\s*$',
                      (ndk / 'source.properties').read_text(), re.M) or
            vp.file_identity(core)['sha256'] != vp.CORE_SHA):
        raise vp.DiagnosticError('Installed NDK/header identity differs from qualified revision 335')
    report.data['ndk_identity'] = dict(properties=vp.file_identity(ndk / 'source.properties'),
                                      vulkan_core=vp.file_identity(core), header_revision=335)
    report.data['api33_vulkan_link_stub'] = vp.elf_identity(
        toolchain / 'sysroot/usr/lib/aarch64-linux-android/33/libvulkan.so', 183)
    sdk_bin = Path(os.environ['ANDROID_HOME']) / 'cmake' / pins['cmake'] / 'bin'
    cmake, ninja = sdk_bin / 'cmake', sdk_bin / 'ninja'
    glslc = ndk / 'shader-tools/linux-x86_64/glslc'
    tools = dict(cmake=cmake, ninja=ninja, glslc=glslc, gcc=Path(shutil.which('gcc') or '/missing'),
                 gxx=Path(shutil.which('g++') or '/missing'), flock=Path(shutil.which('flock') or '/missing'),
                 clang=toolchain / 'bin/clang++', nm=toolchain / 'bin/llvm-nm', readelf=toolchain / 'bin/llvm-readelf')
    report.data['tools'] = {name: vp.elf_identity(path, 62) for name, path in tools.items()}
    version = checked(report, [cmake, '--version'], scratch, 'cmake-version', deadline)
    if not re.search(r'^cmake version 4\.1\.2\s*$', version, re.M):
        raise vp.DiagnosticError('Installed CMake version changed')
    for name in ('gcc', 'gxx'):
        if checked(report, [tools[name], '-dumpmachine'], scratch, name + '-target', deadline).strip() != 'x86_64-linux-gnu':
            raise vp.DiagnosticError('Original ExternalProject requires native Linux host compiler')
    vendor = root / 'third_party/whisper.cpp'
    shaders = vendor / 'ggml/src/ggml-vulkan/vulkan-shaders'
    generator_source = (shaders / 'vulkan-shaders-gen.cpp').read_text()
    if 'std::max(1u, std::min(16u, std::thread::hardware_concurrency()))' not in generator_source:
        raise vp.DiagnosticError('Original generator concurrency cap changed')
    report.data['connected_source'] = {str(p.relative_to(vendor)): vp.file_identity(vp.no_links(p))
                                      for p in (vendor / 'ggml').rglob('*') if p.is_file()}
    report.data['optional_extensions'] = {}
    for name, extension in vp.FEATURES.items():
        report.data['pending_operation'] = 'feature-' + name
        report.save()
        result = run_command([glslc, '-o', scratch / (name + '.spv'), '-fshader-stage=compute',
                              '--target-env=vulkan1.3', shaders / 'feature-tests' / (name + '.comp')],
                             scratch, report.evidence, 'feature-' + name, deadline, seconds=60)
        status = vp.feature_status(result['exit_code'], result['text'].encode(), extension, result['process_status'])
        report.data['probes'].append({k: v for k, v in result.items() if k != 'text'})
        report.data['optional_extensions'][name] = status
        report.save()
        if status != 'UNSUPPORTED' or result['cleanup_status'] != 'JOINED':
            raise vp.DiagnosticError('Optional feature differs from qualified unsupported result: ' + name)
    hpp, spirv = [acquire(pin, scratch, report, deadline) for pin in pins['archives']]
    package_build, prefix = scratch / 'spirv-package-build', scratch / 'spirv-package'
    checked(report, [cmake, '-S', spirv, '-B', package_build, '-G', 'Ninja',
                     '-DCMAKE_MAKE_PROGRAM=' + str(ninja), '-DCMAKE_INSTALL_PREFIX=' + str(prefix),
                     '-DSPIRV_HEADERS_ENABLE_TESTS=OFF', '-DSPIRV_HEADERS_ENABLE_EXAMPLES=OFF'],
            scratch, 'spirv-configure', deadline)
    checked(report, [cmake, '--install', package_build], scratch, 'spirv-install', deadline)
    packages = list(prefix.rglob('SPIRV-HeadersConfig.cmake'))
    if len(packages) != 1:
        raise vp.DiagnosticError('Original SPIRV header package configuration missing')
    report.data['spirv_package_config'] = vp.file_identity(packages[0])
    wrapper = scratch / 'locked-glslc'
    wrapper.write_text('#!/bin/sh\nexec ' + shlex.quote(str(tools['flock'])) + ' -x ' +
                       shlex.quote(str(scratch / 'shader.lock')) + ' ' + shlex.quote(str(glslc)) + ' "$@"\n')
    wrapper.chmod(0o700)
    report.data['shader_control'] = dict(workers=2, original_generator_slots_maximum=16,
                                       active_glslc_maximum=1, wrapper=vp.file_identity(wrapper), text=wrapper.read_text())
    build = scratch / 'android-build'
    environment = {**os.environ, 'CMAKE_BUILD_PARALLEL_LEVEL': '2', 'MALLOC_ARENA_MAX': '2'}
    checked(report, configure_command(cmake, ninja, vendor, build, ndk, hpp, spirv, packages[0].parent, wrapper),
            scratch, 'android-configure', deadline, env=environment)
    require_capacity(scratch, report)
    checked(report, [cmake, '--build', build, '--target', 'ggml', '--parallel', '2'],
            scratch, 'android-build', deadline, env=environment)
    verify_output(vendor, build, toolchain, ninja, report, deadline)


def main(mode=None):
    require_host(os.environ, platform.system(), platform.machine(), os.geteuid())
    if mode is None:
        parser = argparse.ArgumentParser(description=__doc__)
        parser.add_argument('mode', choices=('admit', 'install', 'build'))
        mode = parser.parse_args().mode
    root = Path(__file__).absolute().parent.parent
    pins = json.loads((root / 'eval/vulkan-preflight-pins.json').read_text())
    vp.require_pins(pins)
    if mode not in ('admit', 'install', 'build'):
        raise vp.DiagnosticError('Unknown build stage')
    started = time.monotonic()
    if mode == 'admit':
        identity = read_context(root, pins, started + 30)
        evidence, scratch = paths(os.environ, fresh=True)
        report = vp.Report(evidence, identity)
        report.data.update(pins=pins, scope='Full Android ARM64/API33 ggml Vulkan cross-build only',
                           build_status='NOT_RUN', gpu_execution_acceptance=False, quality_acceptance=False,
                           latency_acceptance=False, cancellation_acceptance=False, deadline=started + SECONDS)
        report.save()
        with Path(os.environ['GITHUB_OUTPUT']).open('a') as output:
            output.write('evidence=' + str(evidence) + '\nscratch=' + str(scratch) + '\n')
        return 0
    evidence, scratch = paths(os.environ)
    path = vp.no_links(evidence / 'report.json')
    if path.stat().st_size > vp.ARTIFACT_LIMIT:
        raise vp.DiagnosticError('Existing report exceeds artifact cap')
    report = vp.Report.__new__(vp.Report)
    report.evidence, report.data = evidence, json.loads(path.read_text())
    previous = {}
    try:
        if (not isinstance(report.data.get('identity'), dict) or report.data.get('pins') != pins or
                report.data.get('status') != 'PARTIAL' or report.data.get('stage') != ('admitted' if mode == 'install' else 'installed') or
                mode == 'build' and report.data.get('sdk_setup_complete') is not True):
            raise vp.DiagnosticError('Admission/source drift or stage replay')
        deadline = report.data['deadline']
        remaining = vp.command_budget(deadline, SECONDS, ceiling=SECONDS)
        if signal.getitimer(signal.ITIMER_REAL) != (0.0, 0.0):
            raise vp.DiagnosticError('Refuse to replace existing alarm')
        def expired(_sig, _frame):
            raise TimeoutError('Total 45-minute build deadline exceeded')
        def interrupted(sig, _frame):
            raise vp.DiagnosticInterrupted(sig)
        for sig, handler in ((signal.SIGALRM, expired), (signal.SIGINT, interrupted), (signal.SIGTERM, interrupted)):
            previous[sig] = signal.signal(sig, handler)
        signal.setitimer(signal.ITIMER_REAL, remaining)
        report.data['pending_operation'] = 'checkout-readmission'
        report.save()
        identity = read_context(root, pins, deadline, report)
        if report.data['identity'] != identity:
            raise vp.DiagnosticError('Admission/source identity changed')
        require_capacity(scratch, report)
        if mode == 'install':
            sdkmanager = Path(os.environ['ANDROID_HOME']) / 'cmdline-tools/latest/bin/sdkmanager'
            report.data['sdkmanager_identity'] = vp.file_identity(sdkmanager)
            environment = {**os.environ, 'JAVA_OPTS': JAVA_OPTS, 'MALLOC_ARENA_MAX': '2'}
            checked(report, [sdkmanager, '--version'], scratch, 'sdkmanager-version', deadline,
                    seconds=10, env=environment, address_space=2 * 1024**3)
            checked(report, [sdkmanager, 'ndk;' + pins['ndk'], 'cmake;' + pins['cmake']], scratch, 'sdk-setup',
                    deadline, seconds=1200, env=environment, address_space=2 * 1024**3, file_limit=2 * 1024**3)
            report.data.update(stage='installed', sdk_setup_complete=True)
        else:
            report.data.update(stage='building', build_status='PARTIAL')
            report.save()
            build_backend(root, pins, scratch, report, deadline)
            if read_context(root, pins, deadline, report) != identity:
                raise vp.DiagnosticError('Checkout or consumed source changed during build')
            vp.command_budget(deadline, 1)
            report.data['build_status'] = 'PASSED'
            report.finish(completed=True)
        report.save()
        return 0
    except (Exception, KeyboardInterrupt) as error:
        if signal.SIGALRM in previous:
            signal.setitimer(signal.ITIMER_REAL, 0)
        report.data['build_status'] = 'FAILED'
        report.fail(error)
        return 1
    finally:
        if signal.SIGALRM in previous:
            signal.setitimer(signal.ITIMER_REAL, 0)
        for sig, handler in previous.items():
            signal.signal(sig, handler)


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (Exception, KeyboardInterrupt) as error:
        print('Build admission stopped: ' + type(error).__name__, file=sys.stderr)
        sys.exit(1)
