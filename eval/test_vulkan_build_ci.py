"""Pure bounded fixtures; subprocess and network calls are denied."""
import copy
import json
from pathlib import Path
import unittest
from unittest.mock import patch

import vulkan_build_ci as vb
import vulkan_preflight as vp

SHA = 'a' * 40
ROOT = Path(__file__).resolve().parent.parent
PINS = json.loads((ROOT / 'eval/vulkan-preflight-pins.json').read_text())


def context():
    env = dict(GITHUB_ACTIONS='true', GITHUB_REPOSITORY='ihearttokyo/Lip', GITHUB_EVENT_NAME='push',
               GITHUB_REPOSITORY_OWNER='ihearttokyo',
               GITHUB_WORKFLOW_REF='ihearttokyo/Lip/.github/workflows/vulkan-build.yml@refs/heads/codex/lip-vulkan-build',
               GITHUB_REF='refs/heads/codex/lip-vulkan-build', GITHUB_SHA=SHA, GITHUB_RUN_ID='123',
               GITHUB_RUN_ATTEMPT='1', GITHUB_SERVER_URL='https://github.com', RUNNER_OS='Linux',
               RUNNER_ARCH='X64', RUNNER_ENVIRONMENT='github-hosted', ImageOS='ubuntu24', ImageVersion='fixture')
    event = dict(repository=dict(full_name='ihearttokyo/Lip', private=False), ref=env['GITHUB_REF'],
                 after=SHA, head_commit=dict(id=SHA, message='build [vulkan-build-canary]'))
    return [env, event, SHA, event['head_commit']['message'], vp.WHISPER, vp.WHISPER,
            '', 'Linux', 'x86_64', 1001]


class BuildTest(unittest.TestCase):
    def setUp(self):
        for name in ('vulkan_preflight.subprocess.Popen', 'vulkan_preflight.urllib.request.urlopen'):
            guard = patch(name, side_effect=AssertionError('Live tool/network denied in pure fixtures'))
            guard.start()
            self.addCleanup(guard.stop)

    def test_build_push_admits_its_actual_ref_without_spoofing_preflight(self):
        args = context()
        self.assertEqual(vb.require_context(*args)['ref'], args[0]['GITHUB_REF'])
        self.assertEqual(vp.REF, 'refs/heads/codex/lip-vulkan-preflight')
        with self.assertRaises(ValueError):
            vp.require_context(*args)
    def folder(self):
        import tempfile
        temp = tempfile.TemporaryDirectory(dir=Path(tempfile.gettempdir()).resolve())
        self.addCleanup(temp.cleanup)
        return Path(temp.name)

    def test_context_rejects_wrong_event_host_dirty_vendor_and_case(self):
        args = context()
        for index, value in [(2, 'b' * 40), (3, '[VULKAN-BUILD-CANARY]'), (4, 'b' * 40),
                             (5, 'b' * 40), (6, ' M source'), (7, 'Darwin'), (8, 'arm64'), (9, 0)]:
            changed = copy.deepcopy(args)
            changed[index] = value
            with self.subTest(index=index), self.assertRaises(ValueError):
                vb.require_context(*changed)
        for key, value in [('GITHUB_REF', vp.REF), ('GITHUB_REPOSITORY', 'other/Lip'),
                           ('RUNNER_ENVIRONMENT', 'self-hosted'), ('GITHUB_EVENT_NAME', 'workflow_dispatch'),
                           ('ImageOS', 'ubuntu22'), ('GITHUB_RUN_ID', '0'), ('ImageVersion', '')]:
            changed = copy.deepcopy(args)
            changed[0][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                vb.require_context(*changed)
        for field in ('after', 'ref'):
            changed = copy.deepcopy(args)
            changed[1][field] = 'wrong'
            with self.assertRaises(ValueError):
                vb.require_context(*changed)
        changed = copy.deepcopy(args)
        changed[1]['repository']['private'] = True
        with self.assertRaises(ValueError):
            vb.require_context(*changed)

    def test_mac_and_wrong_runner_fail_before_git_sdk_or_network(self):
        env = context()[0]
        for system, changed in [('Darwin', env), ('Linux', {**env, 'GITHUB_REF': vp.REF})]:
            with patch.dict(vb.os.environ, changed, clear=True), patch.object(vb.platform, 'system', return_value=system), \
                    patch.object(vb.platform, 'machine', return_value='x86_64'), patch.object(vb.os, 'geteuid', return_value=1001), \
                    patch.object(vb, 'read_context', side_effect=AssertionError('Admission must not reach Git')), \
                    self.assertRaises(ValueError):
                vb.main('admit')

    def test_exact_owned_workflow_and_owner_before_paths_or_tools(self):
        args = context()
        for key, value in [('GITHUB_WORKFLOW_REF', 'ihearttokyo/Lip/.github/workflows/other.yml@' + vb.REF),
                           ('GITHUB_WORKFLOW_REF', None), ('GITHUB_REPOSITORY_OWNER', 'other'),
                           ('GITHUB_REPOSITORY_OWNER', None)]:
            changed = copy.deepcopy(args)
            if value is None:
                changed[0].pop(key)
            else:
                changed[0][key] = value
            with self.subTest(key=key, value=value), patch.object(vb, 'Path', side_effect=AssertionError('Paths forbidden')), \
                    self.assertRaises(ValueError):
                vb.require_context(*changed)
            with patch.dict(vb.os.environ, changed[0], clear=True), patch.object(vb.platform, 'system', return_value='Linux'), \
                    patch.object(vb.platform, 'machine', return_value='x86_64'), patch.object(vb.os, 'geteuid', return_value=1001), \
                    patch.object(vb, 'Path', side_effect=AssertionError('Paths forbidden')), self.assertRaises(ValueError):
                vb.main('admit')

    def test_read_context_rejects_bad_actual_event_before_git(self):
        root = self.folder()
        event_path = root / 'event.json'
        args = context()
        args[1]['head_commit']['message'] = '[VULKAN-BUILD-CANARY]'
        event_path.write_text(json.dumps(args[1]))
        env = {**args[0], 'GITHUB_WORKSPACE': str(root), 'GITHUB_EVENT_PATH': str(event_path)}
        with patch.dict(vb.os.environ, env, clear=True), patch.object(vb.platform, 'system', return_value='Linux'), \
                patch.object(vb.platform, 'machine', return_value='x86_64'), \
                patch.object(vb, 'checked', side_effect=AssertionError('Git forbidden before event')), self.assertRaises(ValueError):
            vb.read_context(root, PINS, 100)

    def test_owned_paths_refuse_stale_linked_mismatched_and_foreign_paths(self):
        root = self.folder()
        env = {**context()[0], 'RUNNER_TEMP': str(root), 'VULKAN_RUNNER_TEMP': str(root)}
        evidence, scratch = vb.paths(env, fresh=True)
        env.update(VULKAN_EVIDENCE=str(evidence), VULKAN_SCRATCH=str(scratch))
        self.assertEqual(vb.paths(env), [evidence, scratch])
        with self.assertRaises(ValueError):
            vb.paths(env, fresh=True)
        with self.assertRaises(ValueError):
            vb.paths({**env, 'VULKAN_EVIDENCE': str(root)})
        link = root / 'link'
        link.symlink_to(root, target_is_directory=True)
        with self.assertRaises(ValueError):
            vb.paths({**env, 'VULKAN_RUNNER_TEMP': str(link)})

    def test_capacity_exact_thresholds_and_missing_memavailable(self):
        from types import SimpleNamespace
        evidence = self.folder()
        report = vp.Report(evidence, {})
        for ram, disk, passed in [(10, 6, True), (9, 6, False), (10, 5, False), (0, 6, False)]:
            text = 'MemAvailable: ' + str(ram * 1024**2) + ' kB\n' if ram else 'MemTotal: 999999999 kB\n'
            with patch.object(vb.Path, 'read_text', return_value=text), \
                    patch.object(vb.shutil, 'disk_usage', return_value=SimpleNamespace(free=disk * 1024**3)):
                if passed:
                    vb.require_capacity(evidence, report)
                else:
                    with self.assertRaises(ValueError):
                        vb.require_capacity(evidence, report)
        self.assertEqual(vp.command_budget(5, 2700, now=0, ceiling=2700), 5)
        with self.assertRaises(TimeoutError):
            vp.command_budget(5, 2700, now=5, ceiling=2700)

    def command(self, chunks=(b'raw\n', b''), wait_error=None, cleanup_error=None, launch_error=None, clock=None):
        from types import SimpleNamespace
        from unittest.mock import MagicMock
        import itertools
        clock = itertools.chain(clock, itertools.repeat(clock[-1])) if clock else None
        child = SimpleNamespace(stdout=MagicMock(), pid=123, returncode=0,
                                wait=MagicMock(side_effect=wait_error, return_value=0))
        selector = MagicMock()
        selector.__enter__.return_value = selector
        selector.get_map.side_effect = [True] * len(chunks) + [False]
        selector.select.return_value = [(SimpleNamespace(fileobj=child.stdout), 1)]
        evidence = self.folder()
        with patch.object(vb.subprocess, 'Popen', side_effect=launch_error, return_value=child), \
                patch.object(vb.selectors, 'DefaultSelector', return_value=selector), \
                patch.object(vb.os, 'read', side_effect=chunks), \
                patch.object(vb, 'stop_group', side_effect=cleanup_error) as stopped, \
                patch.object(vp, 'command_budget', return_value=5), \
                patch.object(vb.time, 'monotonic', side_effect=clock, return_value=0):
            result = vb.run_command(['compiler'], evidence, evidence, 'compile', 5)
        if launch_error is None:
            stopped.assert_called_once_with(child)
            child.stdout.close.assert_called_once()
        self.assertEqual((evidence / 'compile.txt').read_bytes(), result['text'].encode())
        return result

    def test_actual_command_status_output_timeout_launch_and_group_join_hold(self):
        import subprocess
        result = self.command()
        self.assertEqual((result['process_status'], result['cleanup_status'], result['exit_code']), ('EXITED', 'JOINED', 0))
        self.assertTrue(result['output']['complete'])
        for result, status in [(self.command(chunks=(b'x' * 65536,) * 17), 'OUTPUT_LIMIT'),
                               (self.command(wait_error=subprocess.TimeoutExpired('compiler', 5)), 'TIMEOUT'),
                               (self.command(launch_error=OSError()), 'LAUNCH_FAILED'),
                               (self.command(chunks=(b'',), clock=[0, 6, 6]), 'TIMEOUT'),
                               (self.command(wait_error=KeyboardInterrupt()), 'INTERRUPTED'),
                               (self.command(wait_error=vp.DiagnosticInterrupted(vb.signal.SIGTERM)), 'INTERRUPTED')]:
            with self.subTest(status=status):
                self.assertEqual(result['process_status'], status)
                self.assertFalse(result['output']['complete'])
                self.assertLessEqual(result['output']['retained_bytes'], vb.LOG_LIMIT)
        held = self.command(cleanup_error=TimeoutError())
        self.assertEqual(held['cleanup_status'], 'HOLD')
        self.assertFalse(held['output']['complete'])

    def test_stop_group_checks_whole_group_after_leader_wait(self):
        from unittest.mock import MagicMock
        child = MagicMock(pid=123)
        with patch.object(vb.os, 'killpg', side_effect=[None, ProcessLookupError()]) as kill:
            vb.stop_group(child)
        child.wait.assert_called_once_with(timeout=5)
        self.assertEqual(kill.call_args_list[1].args, (123, 0))
        with patch.object(vb.os, 'killpg'), patch.object(vb.time, 'monotonic', side_effect=[0, 6]), self.assertRaises(TimeoutError):
            vb.stop_group(child)

    def test_checked_keeps_raw_failed_nonzero_and_cleanup_hold_status(self):
        report = vp.Report(self.folder(), {})
        for status, code, cleanup in [('TIMEOUT', None, 'JOINED'), ('EXITED', 1, 'JOINED'), ('EXITED', 0, 'HOLD')]:
            result = dict(id='failed', process_status=status, exit_code=code, cleanup_status=cleanup, text='raw failure')
            with patch.object(vb, 'run_command', return_value=result), self.assertRaises(ValueError):
                vb.checked(report, ['compiler'], report.evidence, 'failed', 10)
            self.assertEqual(report.data['probes'][-1]['process_status'], status)
            self.assertEqual(report.data['pending_operation'], 'failed')

    def test_compiler_limits_and_fixed_sdk_allocator_contract(self):
        with patch.object(vb.resource, 'setrlimit') as call:
            vb.limits(5)
        self.assertEqual(call.call_args_list[0].args, (vb.resource.RLIMIT_AS, (vb.AS_LIMIT, vb.AS_LIMIT)))
        self.assertEqual(call.call_args_list[1].args, (vb.resource.RLIMIT_FSIZE, (vb.FILE_LIMIT, vb.FILE_LIMIT)))
        self.assertIn('-XX:ActiveProcessorCount=1', vb.JAVA_OPTS)
        self.assertEqual(vb.SECONDS, 2700)

    def test_configure_uses_original_standalone_backend_not_header_only(self):
        argv = list(map(str, vb.configure_command(*[Path('/' + p) for p in
                                                  ('cmake', 'ninja', 'vendor', 'build', 'ndk', 'hpp', 'spirv', 'package', 'locked-glslc')])))
        for expected in ('/vendor/ggml', '-DGGML_VULKAN=ON', '-DBUILD_SHARED_LIBS=ON',
                         '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-33', '-DGGML_CCACHE=OFF',
                         '-DCMAKE_SHARED_LINKER_FLAGS=-Wl,--no-undefined', '-DVulkan_GLSLC_EXECUTABLE=/locked-glslc'):
            self.assertIn(expected, argv)
        self.assertFalse(any('GGML_VULKAN_SHADERS_GEN_TOOLCHAIN' in a or 'COOPMAT' in a for a in argv))

    def test_archive_full_readback_mismatch_holds_before_unpack(self):
        root = self.folder()
        report = vp.Report(root, {})
        archive = root / 'archive.gz'
        archive.write_bytes(b'changed')
        report.data['archives'].append(dict(bytes=7, sha256='0' * 64))
        with patch.object(vp, 'acquire_archive', return_value=archive), \
                patch.object(vp, 'unpack_archive', side_effect=AssertionError('Must not consume changed archive')), self.assertRaises(ValueError):
            vb.acquire(PINS['archives'][0], root, report, 10)

    def test_workflow_is_exact_unprivileged_text_only_canary(self):
        text = (ROOT / '.github/workflows/vulkan-build.yml').read_text()
        for expected in ('branches: [codex/lip-vulkan-build]', '[vulkan-build-canary]', 'runs-on: ubuntu-24.04',
                         "github.repository_owner == 'ihearttokyo'", "github.ref == 'refs/heads/codex/lip-vulkan-build'",
                         "github.workflow_ref == 'ihearttokyo/Lip/.github/workflows/vulkan-build.yml@refs/heads/codex/lip-vulkan-build'",
                         'contents: read', 'persist-credentials: false', 'submodules: recursive',
                         'eval/vulkan_build_ci.py admit', 'eval/vulkan_build_ci.py install', 'eval/vulkan_build_ci.py build',
                         'steps.admit.outcome == \'success\'', '/*.txt', '/*.json'):
            self.assertIn(expected, text)
        for forbidden in ('sudo', 'gradle', 'adb ', 'emulator', '*.so', '*.a', '*.apk', 'pull_request', 'workflow_dispatch'):
            self.assertNotIn(forbidden, text)

    def test_outer_deadline_during_cleanup_keeps_raw_command_status(self):
        from contextlib import contextmanager
        @contextmanager
        def exhausted():
            yield
            raise TimeoutError('Overall deadline exhausted during join')
        with patch.object(vp, 'retention_boundary', exhausted):
            result = self.command()
        self.assertEqual(result['process_status'], 'TIMEOUT')
        self.assertEqual(result['cleanup_status'], 'JOINED')
        self.assertFalse(result['output']['complete'])
        self.assertEqual(result['text'], 'raw\n')

    def test_pre_yield_cleanup_interruption_still_joins_closes_and_retains_prefix(self):
        from contextlib import contextmanager
        for error, status in [(TimeoutError('entry timeout'), 'TIMEOUT'), (KeyboardInterrupt(), 'INTERRUPTED'),
                              (vp.DiagnosticInterrupted(vb.signal.SIGTERM), 'INTERRUPTED')]:
            entries = []
            @contextmanager
            def interrupted_entry():
                entries.append('before yield')
                raise error
                yield
            with self.subTest(status=status, error=type(error).__name__), patch.object(vp, 'retention_boundary', interrupted_entry):
                result = self.command()
            self.assertEqual(entries, ['before yield'])
            self.assertEqual((result['process_status'], result['cleanup_status']), (status, 'JOINED'))
            self.assertEqual(result['error_type'], type(error).__name__)
            self.assertEqual(result['text'], 'raw\n')
            self.assertEqual(result['output']['bytes_observed'], 4)
            self.assertFalse(result['output']['complete'])

    def test_double_cleanup_fault_keeps_primary_cancellation_holds_and_blocks_next_work(self):
        from contextlib import contextmanager
        from unittest.mock import Mock
        import subprocess
        @contextmanager
        def interrupted_entry():
            raise TimeoutError('boundary entry timeout')
            yield
        for primary, status in [(subprocess.TimeoutExpired('compiler', 5), 'TIMEOUT'),
                                (vp.DiagnosticInterrupted(vb.signal.SIGTERM), 'INTERRUPTED')]:
            with self.subTest(status=status), patch.object(vp, 'retention_boundary', interrupted_entry):
                result = self.command(wait_error=primary, cleanup_error=KeyboardInterrupt())
            self.assertEqual((result['process_status'], result['cleanup_status']), (status, 'HOLD'))
            self.assertEqual(result['error_type'], type(primary).__name__)
            self.assertEqual(result['cleanup_error_type'], 'KeyboardInterrupt')
            self.assertEqual(result['text'], 'raw\n')
            self.assertFalse(result['output']['complete'])
            report, next_work = vp.Report(self.folder(), {}), Mock()
            with patch.object(vb, 'run_command', return_value=result), self.assertRaises(ValueError):
                vb.checked(report, ['compiler'], report.evidence, 'held', 10)
                next_work()
            next_work.assert_not_called()
            self.assertEqual(report.data['probes'][-1]['cleanup_status'], 'HOLD')

    def step_fixture(self):
        root = self.folder()
        env = {**context()[0], 'RUNNER_TEMP': str(root), 'VULKAN_RUNNER_TEMP': str(root),
               'GITHUB_OUTPUT': str(root / 'github-output'), 'ANDROID_HOME': str(root / 'sdk')}
        identity = vb.require_context(*context())
        with patch.dict(vb.os.environ, env, clear=True), \
                patch.object(vb.platform, 'system', return_value='Linux'), \
                patch.object(vb.platform, 'machine', return_value='x86_64'), \
                patch.object(vb, 'read_context', return_value=identity):
            self.assertEqual(vb.main('admit'), 0)
        evidence, scratch = [Path(line.split('=', 1)[1]) for line in (root / 'github-output').read_text().splitlines()]
        env.update(VULKAN_EVIDENCE=str(evidence), VULKAN_SCRATCH=str(scratch))
        return env, identity, evidence, scratch

    def test_step_readmission_failure_is_retained_and_blocks_sdk(self):
        env, _, evidence, _ = self.step_fixture()
        with patch.dict(vb.os.environ, env, clear=True), \
                patch.object(vb.platform, 'system', return_value='Linux'), \
                patch.object(vb.platform, 'machine', return_value='x86_64'), \
                patch.object(vb, 'read_context', side_effect=vp.DiagnosticError('Pristine source drift')):
            self.assertEqual(vb.main('install'), 1)
        report = json.loads((evidence / 'report.json').read_text())
        self.assertEqual(report['status'], 'FAILED')
        self.assertEqual(report['build_status'], 'FAILED')
        self.assertFalse(report['android_or_gpu_acceptance'])
        self.assertIn('Pristine source drift', report['error_message'])


    def elf(self, path, machine=183, kind=1):
        import struct
        path.parent.mkdir(parents=True, exist_ok=True)
        data = bytearray(64)
        data[:6] = b'\x7fELF\x02\x01'
        struct.pack_into('<HH', data, 16, kind, machine)
        path.write_bytes(data)
        return path

    def output_fixture(self):
        root = self.folder()
        vendor, build, toolchain = root / 'vendor', root / 'build', root / 'toolchain'
        shaders = vendor / 'ggml/src/ggml-vulkan/vulkan-shaders'
        shaders.mkdir(parents=True)
        rows = []
        for name, source in [('a.comp.cpp', build / 'src/ggml-vulkan/a.comp.cpp'),
                             ('b.comp.cpp', build / 'src/ggml-vulkan/b.comp.cpp'),
                             ('ggml-vulkan.cpp', vendor / 'ggml/src/ggml-vulkan/ggml-vulkan.cpp'),
                             ('ggml.c', vendor / 'ggml/src/ggml.c')]:
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text('source')
            obj = self.elf(build / 'objects' / (name + '.o'))
            rows.append(dict(file=str(source), directory=str(build),
                             command='/ndk/clang++ --target=aarch64-none-linux-android33 -c ' + str(source) + ' -o ' + str(obj)))
            if name.endswith('.comp.cpp'):
                shader_name = name[0]
                (shaders / (shader_name + '.comp')).write_text('shader')
                source.write_text('const uint64_t ' + shader_name + '_len = 20;\nconst unsigned char ' + shader_name + '_data[20] = {0};\n')
        header = build / 'src/ggml-vulkan/ggml-vulkan-shaders.hpp'
        header.write_text('extern const unsigned char a_data[];\nextern const unsigned char b_data[];\n')
        spv = build / 'src/ggml-vulkan/vulkan-shaders.spv'
        spv.mkdir()
        for name in ('a', 'b'):
            (spv / (name + '.spv')).write_bytes(b'\x03\x02\x23\x07' + bytes(16))
        (build / 'compile_commands.json').write_text(json.dumps(rows))
        self.elf(build / 'Release/vulkan-shaders-gen', 62, 3)
        for name in ('ggml', 'ggml-base', 'ggml-vulkan'):
            self.elf(build / 'src' / ('lib' + name + '.so'), kind=3)
        self.elf(toolchain / 'sysroot/usr/lib/aarch64-linux-android/33/libvulkan.so', kind=3)
        report = vp.Report(self.folder(), {})
        return vendor, build, toolchain, report, rows, shaders

    def fixture_inspection(self, report, argv, cwd, name, deadline, **kwargs):
        path = str(argv[-1])
        if '-D' in argv:
            symbol = 'ggml_backend_vk_reg' if 'ggml-vulkan' in path else 'ggml_init' if 'ggml-base' in path else 'ggml_backend_load_all'
            return '00000000 T ' + symbol + '\n'
        if '-d' in argv:
            lib = Path(path).name
            return ('0 (SONAME) Library soname: [' + lib + ']\n' +
                    ('0 (NEEDED) Shared library: [libvulkan.so]\n' if 'ggml-vulkan' in lib else ''))
        return 'original compile and shared-link commands\n'

    def test_full_objects_reject_missing_shader_wrong_api_and_host_object(self):
        _, build, _, _, rows, shaders = self.output_fixture()
        self.assertEqual(len(vb.qualify_objects(rows, shaders, build)), 4)
        for changed in (rows[1:], rows + [rows[0]], [dict(r, command=r['command'].replace('android33', 'android34')) for r in rows]):
            with self.assertRaises(ValueError):
                vb.qualify_objects(changed, shaders, build)
        self.elf(build / 'objects/a.comp.cpp.o', machine=62)
        with self.assertRaises(ValueError):
            vb.qualify_objects(rows, shaders, build)

    def test_actual_cmake_shaped_absolute_sources_relative_objects_are_valid(self):
        _, build, _, _, rows, shaders = self.output_fixture()
        for row in rows:
            source = Path(row['file'])
            obj = self.elf(build / 'src/ggml-vulkan/CMakeFiles/ggml-vulkan.dir' / (source.name + '.o'))
            row['command'] = ('/ndk/clang++ --target=aarch64-none-linux-android33 --sysroot=/ndk/sysroot '
                              '-DGGML_SHARED -DGGML_VULKAN -I' + str(shaders.parent) +
                              ' -isystem /ndk/include -O3 -DNDEBUG -std=c++17 -fPIC -MD -MT ' +
                              str(obj.relative_to(build)) + ' -MF ' + str(obj.relative_to(build)) + '.d -o ' +
                              str(obj.relative_to(build)) + ' -c ' + str(source))
        objects = vb.qualify_objects(rows, shaders, build)
        self.assertEqual({r['source'] for r in objects}, {r['file'] for r in rows})
        self.assertEqual({r['object']['elf_machine'] for r in objects}, {183})

    def test_all_actual_shader_basenames_from_foreign_sources_are_rejected(self):
        root = self.folder()
        shaders = ROOT / 'third_party/whisper.cpp/ggml/src/ggml-vulkan/vulkan-shaders'
        self.assertEqual(len(list(shaders.glob('*.comp'))), 135)
        build, rows = root / 'build', []
        for name in [p.name + '.cpp' for p in shaders.glob('*.comp')] + ['ggml-vulkan.cpp']:
            source = root / 'foreign' / name
            source.parent.mkdir(exist_ok=True)
            source.write_text('foreign source')
            obj = self.elf(build / 'objects' / (name + '.o'))
            rows.append(dict(file=str(source), directory=str(build),
                             command='/ndk/clang++ --target=aarch64-none-linux-android33 -c ' + str(source) + ' -o ' + str(obj)))
        self.assertEqual(len(rows), 136)
        with self.assertRaises(ValueError):
            vb.qualify_objects(rows, shaders, build)

    def test_object_sources_reject_foreign_mismatched_duplicate_and_extra_inputs(self):
        vendor, build, _, _, rows, shaders = self.output_fixture()
        foreign = self.folder() / 'ggml-vulkan.cpp'
        foreign.write_text('foreign')
        for index in range(len(rows)):
            changed = copy.deepcopy(rows)
            source = Path(rows[index]['file'])
            replacement = foreign.parent / source.name
            replacement.write_text('foreign')
            changed[index].update(file=str(replacement), command=rows[index]['command'].replace(str(source), str(replacement)))
            with self.subTest(index=index), self.assertRaises(ValueError):
                vb.qualify_objects(changed, shaders, build)
        for command in [rows[0]['command'].replace(rows[0]['file'], str(foreign)),
                        rows[0]['command'] + ' -c ' + str(foreign),
                        rows[0]['command'] + ' ' + str(foreign),
                        rows[0]['command'].replace(' -c ', ' ' + str(foreign.with_suffix('')) + ' -c ')]:
            changed = copy.deepcopy(rows)
            changed[0]['command'] = command
            with self.subTest(command=command), self.assertRaises(ValueError):
                vb.qualify_objects(changed, shaders, build)
        escaped = vendor / 'ggml/../../foreign/ggml.c'
        escaped.resolve().parent.mkdir(exist_ok=True)
        escaped.write_text('escaped')
        changed = copy.deepcopy(rows)
        changed[-1].update(file=str(escaped), command=rows[-1]['command'].replace(rows[-1]['file'], str(escaped)))
        with self.assertRaises(ValueError):
            vb.qualify_objects(changed, shaders, build)

    def test_source_object_and_ancestor_symlinks_and_object_escape_are_rejected(self):
        for location in ('generated', 'vendor', 'object', 'source-ancestor', 'object-ancestor', 'object-escape'):
            _, build, _, _, rows, shaders = self.output_fixture()
            index = 2 if location == 'vendor' else 0
            source = Path(rows[index]['file'])
            obj = build / 'objects' / (source.name + '.o')
            foreign = build / 'saved' if location in ('object', 'object-ancestor') else self.folder() / 'foreign'
            if location in ('source-ancestor', 'object-ancestor'):
                directory = source.parent if location == 'source-ancestor' else obj.parent
                directory.rename(foreign)
                directory.symlink_to(foreign, target_is_directory=True)
            elif location == 'object-escape':
                self.elf(foreign)
                rows[index]['command'] = rows[index]['command'].replace(str(obj), str(build / '../' / foreign.name))
                self.elf(build.parent / foreign.name)
            else:
                path = obj if location == 'object' else source
                path.rename(foreign)
                path.symlink_to(foreign)
            with self.subTest(location=location), self.assertRaises(ValueError):
                vb.qualify_objects(rows, shaders, build)

    def test_full_backend_evidence_keeps_core_closure_and_rejects_missing_generated_spirv(self):
        vendor, build, toolchain, report, _, _ = self.output_fixture()
        with patch.object(vb, 'checked', side_effect=self.fixture_inspection):
            vb.verify_output(vendor, build, toolchain, '/ninja', report, 100)
        self.assertEqual(len(report.data['libraries']), 3)
        self.assertEqual(report.data['dynamic_closure']['libvulkan.so']['identity']['elf_machine'], 183)
        self.assertFalse(report.data['android_or_gpu_acceptance'])
        (build / 'src/ggml-vulkan/vulkan-shaders.spv/b.spv').unlink()
        report = vp.Report(self.folder(), {})
        with patch.object(vb, 'checked', side_effect=self.fixture_inspection), self.assertRaises(ValueError):
            vb.verify_output(vendor, build, toolchain, '/ninja', report, 100)

    def test_output_rejects_android_generator_and_missing_dynamic_dependency(self):
        vendor, build, toolchain, report, _, _ = self.output_fixture()
        self.elf(build / 'Release/vulkan-shaders-gen', machine=183, kind=3)
        with self.assertRaises(ValueError):
            vb.verify_output(vendor, build, toolchain, '/ninja', report, 100)
        self.elf(build / 'Release/vulkan-shaders-gen', machine=62, kind=3)
        (toolchain / 'sysroot/usr/lib/aarch64-linux-android/33/libvulkan.so').unlink()
        with patch.object(vb, 'checked', side_effect=self.fixture_inspection), self.assertRaises((OSError, ValueError)):
            vb.verify_output(vendor, build, toolchain, '/ninja', report, 100)

    def test_sdk_setup_keeps_fixed_bounds_stage_and_replay_refusal(self):
        env, identity, evidence, _ = self.step_fixture()
        sdkmanager = Path(env['ANDROID_HOME']) / 'cmdline-tools/latest/bin/sdkmanager'
        sdkmanager.parent.mkdir(parents=True)
        sdkmanager.write_text('fixture sdkmanager')
        with patch.dict(vb.os.environ, env, clear=True), patch.object(vb.platform, 'system', return_value='Linux'), \
                patch.object(vb.platform, 'machine', return_value='x86_64'), patch.object(vb, 'read_context', return_value=identity), \
                patch.object(vb, 'require_capacity'), patch.object(vb, 'checked', return_value='fixture') as commands:
            self.assertEqual(vb.main('install'), 0)
            setup = commands.call_args_list[1]
            self.assertEqual(list(map(str, setup.args[1]))[1:], ['ndk;30.0.16248370', 'cmake;4.1.2'])
            self.assertEqual(setup.kwargs['env']['JAVA_OPTS'], vb.JAVA_OPTS)
            self.assertEqual(setup.kwargs['env']['MALLOC_ARENA_MAX'], '2')
            self.assertEqual(setup.kwargs['address_space'], 2 * 1024**3)
            self.assertEqual(setup.kwargs['seconds'], 1200)
            self.assertEqual(vb.main('install'), 1)
        report = json.loads((evidence / 'report.json').read_text())
        self.assertEqual(report['status'], 'FAILED')
        self.assertIn('replay', report['error_message'])

    def test_successful_build_is_not_gpu_quality_latency_or_cancellation_acceptance(self):
        env, identity, evidence, _ = self.step_fixture()
        report = json.loads((evidence / 'report.json').read_text())
        report.update(stage='installed', sdk_setup_complete=True)
        (evidence / 'report.json').write_text(json.dumps(report))
        with patch.dict(vb.os.environ, env, clear=True), patch.object(vb.platform, 'system', return_value='Linux'), \
                patch.object(vb.platform, 'machine', return_value='x86_64'), patch.object(vb, 'read_context', return_value=identity), \
                patch.object(vb, 'require_capacity'), patch.object(vb, 'build_backend') as backend:
            self.assertEqual(vb.main('build'), 0)
            backend.assert_called_once()
        report = json.loads((evidence / 'report.json').read_text())
        self.assertEqual((report['status'], report['build_status']), ('PASSED', 'PASSED'))
        for key in ('android_or_gpu_acceptance', 'gpu_execution_acceptance', 'quality_acceptance', 'latency_acceptance', 'cancellation_acceptance'):
            self.assertIs(report[key], False)

    def test_outer_deadline_and_source_drift_never_run_build(self):
        env, identity, evidence, _ = self.step_fixture()
        report = json.loads((evidence / 'report.json').read_text())
        report.update(stage='installed', sdk_setup_complete=True, deadline=vb.time.monotonic() - 1)
        (evidence / 'report.json').write_text(json.dumps(report))
        with patch.dict(vb.os.environ, env, clear=True), patch.object(vb.platform, 'system', return_value='Linux'), \
                patch.object(vb.platform, 'machine', return_value='x86_64'), patch.object(vb, 'read_context', return_value=identity), \
                patch.object(vb, 'build_backend', side_effect=AssertionError('Expired admission forbids build')):
            self.assertEqual(vb.main('build'), 1)
        report = json.loads((evidence / 'report.json').read_text())
        self.assertEqual(report['status'], 'FAILED')
        self.assertEqual(report['error_type'], 'TimeoutError')


    def test_archive_support_comes_from_original_complete_validated_archive(self):
        import gzip
        import hashlib
        import io
        import tarfile
        root = self.folder()
        report = vp.Report(self.folder(), {})
        pin = PINS['archives'][1]
        data = io.BytesIO()
        with tarfile.open(fileobj=data, mode='w') as archive:
            for name, content in [('LICENSE', b'fixture license'), ('CMakeLists.txt', b'original source config'),
                                  ('include/spirv/unified1/spirv.hpp', b'// SPDX-License-Identifier: MIT\n'),
                                  ('cmake/SPIRV-HeadersConfig.cmake.in', b'original package template')]:
                info = tarfile.TarInfo(pin['root'] + '-' + pin['commit'] + '/' + name)
                info.size = len(content)
                archive.addfile(info, io.BytesIO(content))
        path = root / 'SPIRV-Headers.tar.gz'
        path.write_bytes(gzip.compress(data.getvalue()))
        def downloaded(*args):
            report.data['archives'].append(dict(transfer_status='COMPLETE', consumption_status='NOT_RUN', **vp.file_identity(path)))
            return path
        with patch.object(vp, 'acquire_archive', side_effect=downloaded):
            selected = vb.acquire(pin, root, report, 100)
        self.assertEqual((selected / 'cmake/SPIRV-HeadersConfig.cmake.in').read_bytes(), b'original package template')
        record = report.data['archives'][0]
        self.assertEqual(record['full_readback'], vp.file_identity(path))
        self.assertEqual(record['consumption_status'], 'COMPLETE')
        self.assertIn('cmake/SPIRV-HeadersConfig.cmake.in', record['build_support'])

    def test_build_backend_runs_original_cmake_host_generator_and_global_shader_lock(self):
        from types import SimpleNamespace
        root = self.folder()
        scratch = root / 'scratch'
        scratch.mkdir()
        vendor = root / 'third_party/whisper.cpp'
        shaders = vendor / 'ggml/src/ggml-vulkan/vulkan-shaders'
        shaders.mkdir(parents=True)
        (shaders / 'vulkan-shaders-gen.cpp').write_text('std::max(1u, std::min(16u, std::thread::hardware_concurrency()))')
        sdk = root / 'sdk'
        ndk = sdk / 'ndk' / PINS['ndk']
        ndk.mkdir(parents=True)
        (ndk / 'source.properties').write_text('Pkg.Revision = 30.0.16248370\n')
        core = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include/vulkan/vulkan_core.h'
        core.parent.mkdir(parents=True)
        core.write_text('qualified fixture header')
        hpp, spirv = scratch / 'hpp', scratch / 'spirv'
        hpp.mkdir(); spirv.mkdir()
        report = vp.Report(self.folder(), {})
        def commands(report, argv, cwd, name, deadline, **kwargs):
            if name == 'cmake-version':
                return 'cmake version 4.1.2\n'
            if name in ('gcc-target', 'gxx-target'):
                return 'x86_64-linux-gnu\n'
            if name == 'spirv-install':
                package = scratch / 'spirv-package/lib/cmake/SPIRV-Headers/SPIRV-HeadersConfig.cmake'
                package.parent.mkdir(parents=True)
                package.write_text('original configured package')
            return ''
        def feature(argv, cwd, evidence, name, deadline, **kwargs):
            extension = vp.FEATURES[name.removeprefix('feature-')]
            text = "shader.comp:3: error: '#extension' : extension not supported: " + extension + '\n1 error generated.\n'
            return dict(id=name, text=text, exit_code=1, process_status='EXITED', cleanup_status='JOINED')
        with patch.dict(vb.os.environ, {'ANDROID_HOME': str(sdk)}, clear=True), \
                patch.object(vb, 'require_capacity'), patch.object(vp, 'CORE_SHA', vp.file_identity(core)['sha256']), \
                patch.object(vp, 'elf_identity', return_value={'elf_machine': 62}), \
                patch.object(vb, 'checked', side_effect=commands) as call, \
                patch.object(vb, 'run_command', side_effect=feature), \
                patch.object(vb, 'acquire', side_effect=[hpp, spirv]), patch.object(vb, 'verify_output') as verify:
            vb.build_backend(root, PINS, scratch, report, 100)
        build = next(c for c in call.call_args_list if c.args[3] == 'android-build')
        self.assertEqual(list(map(str, build.args[1]))[-4:], ['--target', 'ggml', '--parallel', '2'])
        self.assertEqual(build.kwargs['env']['CMAKE_BUILD_PARALLEL_LEVEL'], '2')
        self.assertEqual(report.data['shader_control']['active_glslc_maximum'], 1)
        self.assertIn(' -x ', (scratch / 'locked-glslc').read_text())
        self.assertNotIn('COOPMAT', (scratch / 'locked-glslc').read_text())
        self.assertEqual(set(report.data['optional_extensions'].values()), {'UNSUPPORTED'})
        verify.assert_called_once()

    def test_missing_sdk_success_marker_is_not_build_admission(self):
        env, identity, evidence, _ = self.step_fixture()
        data = json.loads((evidence / 'report.json').read_text())
        data['stage'] = 'installed'
        (evidence / 'report.json').write_text(json.dumps(data))
        with patch.dict(vb.os.environ, env, clear=True), patch.object(vb.platform, 'system', return_value='Linux'), \
                patch.object(vb.platform, 'machine', return_value='x86_64'), patch.object(vb, 'read_context', return_value=identity), \
                patch.object(vb, 'require_capacity'), patch.object(vb, 'build_backend') as backend:
            self.assertEqual(vb.main('build'), 1)
        backend.assert_not_called()

    def test_duplicate_target_flag_is_not_api33_compilation(self):
        _, build, _, _, rows, shaders = self.output_fixture()
        rows[0]['command'] += ' --target=aarch64-none-linux-android34'
        with self.assertRaises(ValueError):
            vb.qualify_objects(rows, shaders, build)


    def test_actual_git_answers_source_hashes_and_message_agree_with_event(self):
        import hashlib
        root = self.folder()
        source = root / 'third_party/whisper.cpp/ggml/source.cpp'
        source.parent.mkdir(parents=True)
        source.write_bytes(b'pinned source')
        event_path = root / 'event.json'
        env, event = context()[:2]
        event_path.write_text(json.dumps(event))
        env.update(GITHUB_WORKSPACE=str(root), GITHUB_EVENT_PATH=str(event_path))
        for name in ('eval/vulkan_build_ci.py', 'eval/test_vulkan_build_ci.py', '.github/workflows/vulkan-build.yml',
                     'eval/vulkan_preflight.py', 'eval/vulkan-preflight-pins.json'):
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('fixture source identity')
        pins = dict(whisper_files={'ggml/source.cpp': hashlib.sha256(b'pinned source').hexdigest()})
        dirty = ''
        message = event['head_commit']['message']
        def git(report, argv, cwd, name, deadline, **kwargs):
            if argv[1] == 'status':
                return dirty
            if argv[1] == 'show':
                return message
            if argv[1] == 'ls-tree':
                return '160000 commit ' + vp.WHISPER + '\tthird_party/whisper.cpp'
            return vp.WHISPER if cwd == root / 'third_party/whisper.cpp' else SHA
        with patch.dict(vb.os.environ, env, clear=True), patch.object(vb.platform, 'system', return_value='Linux'), \
                patch.object(vb.platform, 'machine', return_value='x86_64'), patch.object(vb, 'checked', side_effect=git):
            identity = vb.read_context(root, pins, 100)
            self.assertEqual(identity['checkout_sha'], SHA)
            self.assertEqual(identity['pinned_files']['ggml/source.cpp']['sha256'], pins['whisper_files']['ggml/source.cpp'])
            dirty = ' M source.cpp'
            with self.assertRaises(ValueError):
                vb.read_context(root, pins, 100)
            dirty = ''
            message = 'another [vulkan-build-canary]'
            with self.assertRaises(ValueError):
                vb.read_context(root, pins, 100)
            message = event['head_commit']['message']
            source.write_bytes(b'changed source')
            with self.assertRaises(ValueError):
                vb.read_context(root, pins, 100)

    def test_generated_variants_reject_duplicate_arrays_bad_lengths_and_malformed_spirv(self):
        _, build, _, _, rows, _ = self.output_fixture()
        header = build / 'src/ggml-vulkan/ggml-vulkan-shaders.hpp'
        spv = header.parent / 'vulkan-shaders.spv'
        sources = [Path(r['file']) for r in rows if r['file'].endswith('.comp.cpp')]
        self.assertEqual(set(vb.qualify_generated(header, sources, spv)), {'a', 'b'})
        original = sources[0].read_text()
        for changed in (original + original, original.replace('_len = 20', '_len = 24')):
            sources[0].write_text(changed)
            with self.assertRaises(ValueError):
                vb.qualify_generated(header, sources, spv)
        sources[0].write_text(original)
        (spv / 'a.spv').write_bytes(bytes(20))
        with self.assertRaises(ValueError):
            vb.qualify_generated(header, sources, spv)

    def test_text_artifact_cap_never_earns_complete_output(self):
        from unittest.mock import MagicMock
        from types import SimpleNamespace
        evidence = self.folder()
        (evidence / 'prior.txt').write_bytes(b'x' * (80 * 1024))
        selector = MagicMock()
        selector.__enter__.return_value = selector
        selector.get_map.side_effect = [True, True, False]
        child = SimpleNamespace(stdout=MagicMock(), pid=123, returncode=0, wait=MagicMock(return_value=0))
        selector.select.return_value = [(SimpleNamespace(fileobj=child.stdout), 1)]
        with patch.object(vb.subprocess, 'Popen', return_value=child), patch.object(vb.selectors, 'DefaultSelector', return_value=selector), \
                patch.object(vb.os, 'read', side_effect=[b'y' * (64 * 1024), b'']), patch.object(vb, 'stop_group'), \
                patch.object(vp, 'ARTIFACT_LIMIT', 128 * 1024):
            result = vb.run_command(['compiler'], evidence, evidence, 'compile', vb.time.monotonic() + 10)
        self.assertEqual(result['process_status'], 'ARTIFACT_LIMIT')
        self.assertFalse(result['output']['complete'])
        self.assertLess(sum(p.stat().st_size for p in evidence.iterdir()), 128 * 1024)


    def test_consumption_failure_retains_archive_phase_status(self):
        root = self.folder()
        report = vp.Report(self.folder(), {})
        path = root / 'bad.tar.gz'
        path.write_bytes(b'not gzip')
        report.data['archives'].append(dict(transfer_status='COMPLETE', consumption_status='NOT_RUN', **vp.file_identity(path)))
        with patch.object(vp, 'acquire_archive', return_value=path), self.assertRaises(ValueError):
            vb.acquire(PINS['archives'][1], root, report, 100)
        self.assertEqual(report.data['archives'][0]['consumption_status'], 'FAILED')


if __name__ == '__main__':
    unittest.main()
