"""Pure/synthetic contracts only; no shader, toolchain, device or network proof."""
import copy
import gzip
import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import vulkan_preflight as vp

ROOT = Path(__file__).resolve().parent
PINS = json.loads((ROOT / 'vulkan-preflight-pins.json').read_text())
SHA = 'a' * 40


def context():
    env = dict(GITHUB_ACTIONS='true', GITHUB_REPOSITORY='ihearttokyo/Lip',
               GITHUB_EVENT_NAME='push', GITHUB_REF='refs/heads/codex/lip-vulkan-preflight',
               GITHUB_SHA=SHA, GITHUB_RUN_ID='123', GITHUB_RUN_ATTEMPT='1',
               GITHUB_SERVER_URL='https://github.com', RUNNER_OS='Linux', RUNNER_ARCH='X64',
               RUNNER_ENVIRONMENT='github-hosted', ImageOS='ubuntu24', ImageVersion='fixture')
    event = {'repository': {'full_name': 'ihearttokyo/Lip', 'private': False},
             'ref': env['GITHUB_REF'], 'after': SHA,
             'head_commit': {'id': SHA, 'message': 'probe [vulkan-preflight]'}}
    return env, event


def archive(entries):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode='w') as tar:
        for name, content, kind in entries:
            info = tarfile.TarInfo(name)
            info.type = kind
            info.size = len(content) if kind == tarfile.REGTYPE else 0
            if kind in (tarfile.SYMTYPE, tarfile.LNKTYPE):
                info.linkname = 'elsewhere'
            tar.addfile(info, io.BytesIO(content) if info.size else None)
    return gzip.compress(output.getvalue())


def archive_pin():
    return {'root': 'Fixture', 'commit': SHA, 'license': 'LICENSE',
            'required': ['vulkan/vulkan.hpp', 'vulkan/child.hpp'],
            'compressed_limit': 4096, 'expanded_limit': 16384,
            'member_limit': 2048, 'files_limit': 10}


def entries():
    prefix = 'Fixture-' + SHA + '/'
    return [(prefix + 'LICENSE', b'license', tarfile.REGTYPE),
            (prefix + 'vulkan/vulkan.hpp', b'#include <vulkan/child.hpp>\n', tarfile.REGTYPE),
            (prefix + 'vulkan/child.hpp', b'// SPDX-License-Identifier: MIT\n', tarfile.REGTYPE)]


class PreflightTest(unittest.TestCase):
    def test_optional_unrelated_error_is_not_supported(self):
        extension = 'GL_KHR_cooperative_matrix'
        self.assertEqual(vp.feature_status(0, b'', extension), 'SUPPORTED')
        expected = b"shader.comp:3: error: '#extension' : extension not supported: GL_KHR_cooperative_matrix\n1 error generated.\n"
        self.assertEqual(vp.feature_status(1, expected, extension), 'UNSUPPORTED')
        for rc, stderr in [(1, b'permission denied'), (-9, expected),
                           (1, expected + b'shader.comp:9: error: syntax error\n'),
                           (1, expected.replace(b'cooperative_matrix', b'other')),
                           (1, b'extension not supported: GL_KHR_cooperative_matrix2\n')]:
            with self.subTest(rc=rc, stderr=stderr):
                self.assertEqual(vp.feature_status(rc, stderr, extension), 'FAILED')
        self.assertEqual(vp.feature_status(1, expected, extension, 'TIMEOUT'), 'FAILED')

    def test_exact_context_message_checkout_and_source_pin(self):
        env, event = context()
        args = [env, event, SHA, event['head_commit']['message'], vp.WHISPER,
                vp.WHISPER, '', 'Linux', 'x86_64', 1001]
        self.assertEqual(vp.require_context(*args)['checkout_sha'], SHA)
        for index, value in [(2, 'b' * 40), (3, '[VULKAN-PREFLIGHT]'), (4, 'b' * 40),
                             (5, 'b' * 40), (6, ' M shader'), (7, 'Darwin'), (8, 'arm64'), (9, 0)]:
            changed = list(args)
            changed[index] = value
            with self.subTest(index=index), self.assertRaises(ValueError):
                vp.require_context(*changed)
        for key, value in [('GITHUB_REPOSITORY', 'other/Lip'), ('GITHUB_REF', 'refs/heads/main'),
                           ('GITHUB_EVENT_NAME', 'workflow_dispatch'), ('RUNNER_ENVIRONMENT', 'self-hosted'),
                           ('RUNNER_ARCH', 'ARM64'), ('GITHUB_RUN_ID', ''), ('ImageOS', 'ubuntu22')]:
            changed = list(args)
            changed[0] = {**env, key: value}
            with self.subTest(key=key), self.assertRaises(ValueError):
                vp.require_context(*changed)
        for change in [lambda e: e['repository'].update(private=True),
                       lambda e: e.update(after='b' * 40),
                       lambda e: e['head_commit'].update(message='[VULKAN-PREFLIGHT]')]:
            changed = list(args)
            changed[1] = copy.deepcopy(event)
            change(changed[1])
            with self.assertRaises(ValueError):
                vp.require_context(*changed)

    def test_pins_cannot_be_silently_upgraded_or_mixed(self):
        vp.require_pins(PINS)
        for field in ('whisper', 'vulkan_headers', 'hpp_headers_gitlink', 'ndk', 'cmake', 'vulkan_core_sha256'):
            changed = copy.deepcopy(PINS)
            changed[field] = 'wrong'
            with self.subTest(field=field), self.assertRaises(ValueError):
                vp.require_pins(changed)
        changed = copy.deepcopy(PINS)
        changed['archives'][0]['commit'] = 'b' * 40
        with self.assertRaises(ValueError):
            vp.require_pins(changed)
        changed = copy.deepcopy(PINS)
        changed['archives'][0]['compressed_limit'] = 1 << 30
        with self.assertRaises(ValueError):
            vp.require_pins(changed)

    def unpack(self, content, pin=None):
        temp = tempfile.TemporaryDirectory(dir=Path(tempfile.gettempdir()).resolve())
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        path = root / 'source.tar.gz'
        path.write_bytes(content)
        return vp.unpack_archive(path, root / 'selected', pin or archive_pin()), root

    def test_archive_safe_complete_selection_and_transitive_headers(self):
        result, root = self.unpack(archive(entries()))
        self.assertEqual(set(result), {'LICENSE', 'vulkan/vulkan.hpp', 'vulkan/child.hpp'})
        self.assertEqual(result['LICENSE']['sha256'], hashlib.sha256(b'license').hexdigest())
        self.assertEqual((root / 'selected/vulkan/child.hpp').read_bytes(), entries()[2][1])
        bad = entries()[:-1]
        with self.assertRaises(ValueError):
            self.unpack(archive(bad))
        bad = entries()
        bad[1] = (bad[1][0], b'#include "missing.hpp"\n', tarfile.REGTYPE)
        with self.assertRaises(ValueError):
            self.unpack(archive(bad))

    def test_archive_rejects_traversal_links_duplicates_bounds_and_truncation(self):
        prefix = 'Fixture-' + SHA + '/'
        for name, kind in [('/escape', tarfile.REGTYPE), (prefix + '../escape', tarfile.REGTYPE),
                           (prefix + 'vulkan/../../escape', tarfile.REGTYPE),
                           (prefix + 'link', tarfile.SYMTYPE), (prefix + 'hard', tarfile.LNKTYPE),
                           (prefix + 'fifo', tarfile.FIFOTYPE), (prefix + 'back\\slash', tarfile.REGTYPE),
                           ('Fixture-' + 'b' * 40 + '/extra', tarfile.REGTYPE)]:
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.unpack(archive(entries() + [(name, b'x', kind)]))
        for bad in [entries() + [entries()[0]], entries() + [(prefix + 'big', b'x' * 2049, tarfile.REGTYPE)],
                    entries() + [(prefix + str(i), b'x', tarfile.REGTYPE) for i in range(12)]]:
            with self.assertRaises(ValueError):
                self.unpack(archive(bad))
        with self.assertRaises(ValueError):
            self.unpack(archive(entries())[:-5])
        concatenated_pin = archive_pin()
        concatenated_pin['expanded_limit'] = 32768
        with self.assertRaises(ValueError):
            self.unpack(gzip.compress(gzip.decompress(archive(entries())) + gzip.decompress(archive(entries()))), concatenated_pin)
        tiny = archive_pin()
        tiny['expanded_limit'] = 100
        with self.assertRaises(ValueError):
            self.unpack(archive(entries()), tiny)

    def test_complete_transfer_length_and_byte_deadline_limits(self):
        output = io.BytesIO()
        record = vp.copy_transfer(io.BytesIO(b'public'), output, 6, 10, 20, now=lambda: 1)
        self.assertTrue(record['complete'])
        self.assertEqual(record['sha256'], hashlib.sha256(b'public').hexdigest())
        for expected, limit, now in [(7, 10, lambda: 1), (6, 5, lambda: 1), (6, 10, lambda: 21)]:
            with self.assertRaises((ValueError, TimeoutError)):
                vp.copy_transfer(io.BytesIO(b'public'), io.BytesIO(), expected, limit, 20, now=now)

    def test_normal_and_dot2_exact_argv_and_both_android_targets(self):
        plan = vp.shader_plan(Path('/tools/glslc'), Path('/vendor/shaders'), Path('/owned'))
        self.assertEqual(len(plan), 14)
        normal = next(p for p in plan if p['id'] == 'flash-normal')
        dot2 = next(p for p in plan if p['id'] == 'flash-dot2')
        self.assertIn('-O', normal['argv'])
        self.assertNotIn('-O', dot2['argv'])
        self.assertIn('-DDOT2_F16=1', dot2['argv'])
        for probe in (normal, dot2):
            for flag in ['-MD', '-MF', '--target-env=vulkan1.2', '-DFLOAT16=1',
                         '-DFLOAT_TYPE_MAX=float16_t(65504.0)', '-DACC_TYPE=float', '-DDATA_A_IQ4_NL=1']:
                self.assertIn(flag, probe['argv'])
        q5 = next(p for p in plan if p['id'] == 'dequant-q5-generator')
        self.assertIn('-DFLOAT_TYPE=float', q5['argv'])
        self.assertIn('-DD_TYPE=float16_t', q5['argv'])
        norm = next(p for p in plan if p['id'] == 'norm-fp32')
        self.assertNotIn('-DFLOAT16=1', norm['argv'])
        self.assertEqual(sum('extension' in p for p in plan), 7)
        for target in vp.TARGETS:
            argv = vp.header_command('/clang++', target, Path('/sysroot'), Path('/hpp'), Path('/spv'), Path('/probe.cpp'))
            for flag in ['--target=' + target, '--sysroot=/sysroot', '-fsyntax-only', '-std=c++17', '-H',
                         '-I/hpp', '-I/spv/include', '-I/sysroot/usr/include']:
                self.assertIn(flag, argv)
            self.assertNotIn('-c', argv)

    def test_resource_time_and_artifact_bounds(self):
        self.assertEqual(vp.command_budget(100, 60, now=50), 50)
        self.assertEqual(vp.command_budget(100, 1000, now=1), 60)
        with self.assertRaises(TimeoutError):
            vp.command_budget(100, 60, now=100)
        with patch.object(vp.resource, 'setrlimit') as limits:
            vp.process_limits(60)
        self.assertIn((vp.resource.RLIMIT_AS, (2 * 1024**3, 2 * 1024**3)), [c.args for c in limits.call_args_list])
        self.assertEqual(vp.TOTAL_SECONDS, 300)
        self.assertEqual(vp.ARTIFACT_LIMIT, 16 * 1024**2)
        with tempfile.TemporaryDirectory(dir=Path(tempfile.gettempdir()).resolve()) as temp:
            root = Path(temp)
            owned = vp.fresh_directories(root, '123', '1')
            self.assertTrue(owned[0].is_dir())
            with self.assertRaises(ValueError):
                vp.fresh_directories(root, '123', '1')
            link = root / 'link'
            link.symlink_to(root, target_is_directory=True)
            with self.assertRaises(ValueError):
                vp.fresh_directories(link, '124', '1')

    def test_failure_retained_and_collection_cannot_override_it(self):
        with tempfile.TemporaryDirectory(dir=Path(tempfile.gettempdir()).resolve()) as temp:
            report = vp.Report(Path(temp), {'checkout_sha': SHA})
            result = {'id': 'required', 'exit_code': 2, 'process_status': 'EXITED',
                      'stderr_text': 'bad syntax', 'stdout_text': ''}
            with self.assertRaises(ValueError):
                vp.record_probe(report, result)
            report.finish()
            saved = json.loads((Path(temp) / 'report.json').read_text())
            self.assertEqual(saved['status'], 'FAILED')
            self.assertEqual(saved['collection_status'], 'PARTIAL')
            self.assertEqual(saved['probes'][0]['status'], 'FAILED')
            self.assertFalse(saved['android_or_gpu_acceptance'])

    def test_system_headers_are_not_missing_archive_members(self):
        fixture = entries()
        fixture[2] = (fixture[2][0], b'#include <stdint.h>\n', tarfile.REGTYPE)
        self.unpack(archive(fixture))

    def test_mock_process_retains_exact_sizes_hashes_and_stops_at_output_limit(self):
        class Pipe:
            def __init__(self, fd):
                self.fd = fd
            def fileno(self):
                return self.fd
            def close(self):
                pass

        class Selector:
            def __init__(self):
                self.rows = {}
            def __enter__(self):
                return self
            def __exit__(self, *_):
                pass
            def register(self, pipe, _events, kind):
                self.rows[pipe] = kind
            def unregister(self, pipe):
                del self.rows[pipe]
            def get_map(self):
                return self.rows
            def select(self, _timeout):
                return [(SimpleNamespace(fileobj=p, data=k), None) for p, k in list(self.rows.items())]

        for payload in (b'fixture output', b'x' * (vp.OUTPUT_LIMIT + 1)):
            stdout, stderr = Pipe(11), Pipe(12)
            child = SimpleNamespace(pid=12345, stdout=stdout, stderr=stderr, returncode=0,
                                    wait=lambda timeout: 0)
            chunks = {11: [payload[i:i+65536] for i in range(0, len(payload), 65536)] + [b''], 12: [b'']}
            with tempfile.TemporaryDirectory(dir=Path(tempfile.gettempdir()).resolve()) as temp, \
                    patch.object(vp.subprocess, 'Popen', return_value=child) as launch, \
                    patch.object(vp.selectors, 'DefaultSelector', Selector), \
                    patch.object(vp.os, 'read', side_effect=lambda fd, size: chunks[fd].pop(0)), \
                    patch.object(vp.os, 'killpg') as kill:
                result = vp.run_command(['/fixture/compiler'], Path(temp), Path(temp), 'fixture', vp.time.monotonic()+5)
                self.assertTrue(launch.call_args.kwargs['start_new_session'])
                self.assertEqual(launch.call_args.kwargs['stdin'], vp.subprocess.DEVNULL)
                kill.assert_called_once_with(12345, vp.signal.SIGKILL)
                self.assertEqual(result['stdout']['bytes_observed'], len(payload))
                self.assertEqual(result['stdout']['observed_sha256'], hashlib.sha256(payload).hexdigest())
                self.assertEqual((Path(temp) / 'fixture-stdout.txt').read_bytes(), payload[:vp.OUTPUT_LIMIT])
                self.assertEqual(result['process_status'], 'OUTPUT_LIMIT' if len(payload) > vp.OUTPUT_LIMIT else 'EXITED')
                self.assertEqual(result['stdout']['complete'], len(payload) <= vp.OUTPUT_LIMIT)

    def test_admission_recheck_elapsed_changes_and_collection_failure_persist(self):
        env, event = context()
        identity = vp.require_context(env, event, SHA, event['head_commit']['message'], vp.WHISPER,
                                      vp.WHISPER, '', 'Linux', 'x86_64', 1001)
        with tempfile.TemporaryDirectory(dir=Path(tempfile.gettempdir()).resolve()) as temp:
            temp = Path(temp)
            evidence, scratch = vp.fresh_directories(temp, '123', '1')
            report = vp.Report(evidence, {**identity, 'admission_commands': [{'elapsed_seconds': 1}]})
            report.data['pins'] = PINS
            report.save()
            env.update(RUNNER_TEMP=str(temp), VULKAN_RUNNER_TEMP=str(temp), VULKAN_EVIDENCE=str(evidence), VULKAN_SCRATCH=str(scratch),
                       ANDROID_HOME=str(temp / 'sdk'))
            sdkmanager = temp / 'sdk/cmdline-tools/latest/bin/sdkmanager'
            sdkmanager.parent.mkdir(parents=True)
            sdkmanager.write_text('synthetic SDK shell script; never executed')
            rechecked = {**identity, 'admission_commands': [{'elapsed_seconds': 2}]}
            result = dict(id='sdk-fixture', exit_code=0, process_status='EXITED', stdout_text='', stderr_text='')
            with patch.dict(vp.os.environ, env), patch.object(vp, 'read_context', return_value=rechecked), \
                    patch.object(vp, 'run_command', return_value=result) as setup_commands, \
                    patch.object(vp.sys, 'argv', ['vulkan_preflight.py', 'install']):
                self.assertEqual(vp.main(), 0)
                self.assertEqual(setup_commands.call_args_list[-1].kwargs['file_size_bytes'], 2 * 1024**3)
                self.assertEqual(setup_commands.call_args_list[-1].kwargs['seconds'], 1200)
                self.assertTrue(all(c.kwargs.get('retain', True) for c in setup_commands.call_args_list),
                                'Public SDK version and setup output must be retained for failure diagnosis')
                self.assertTrue(all(c.kwargs['env'].get('MALLOC_ARENA_MAX') == '2' for c in setup_commands.call_args_list))
            saved = json.loads((evidence / 'report.json').read_text())
            self.assertTrue(saved['sdk_setup_complete'])
            self.assertEqual(saved['status'], 'PARTIAL')
            with patch.dict(vp.os.environ, env), patch.object(vp, 'read_context', return_value=rechecked), \
                    patch.object(vp, 'collect', side_effect=vp.DiagnosticError('fixture required failure')), \
                    patch.object(vp.sys, 'argv', ['vulkan_preflight.py', 'collect']):
                self.assertEqual(vp.main(), 1)
            saved = json.loads((evidence / 'report.json').read_text())
            self.assertEqual(saved['status'], 'FAILED')
            self.assertEqual(saved['collection_status'], 'PARTIAL')
            self.assertEqual(saved['error_message'], 'fixture required failure')

    def test_full_synthetic_orchestration_and_required_failure_hold_headers(self):
        for failed in (None, 'flash-dot2'):
            with self.subTest(failed=failed), tempfile.TemporaryDirectory(dir=Path(tempfile.gettempdir()).resolve()) as temp:
                root = Path(temp)
                scratch, evidence, repo = root / 'scratch', root / 'evidence', root / 'repo'
                scratch.mkdir()
                evidence.mkdir()
                repo.mkdir()
                ndk = root / 'sdk/ndk' / PINS['ndk']
                sysroot = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/sysroot'
                core = sysroot / 'usr/include/vulkan/vulkan_core.h'
                core.parent.mkdir(parents=True)
                core.write_text('// SPDX-License-Identifier: Apache-2.0\n#define VK_HEADER_VERSION 335\n')
                ndk.joinpath('source.properties').write_text('Pkg.Revision = ' + PINS['ndk'] + '\n')
                for notice in ('NOTICE', 'NOTICE.toolchain'):
                    ndk.joinpath(notice).write_text('synthetic license notice')
                tool_paths = [ndk / 'shader-tools/linux-x86_64/glslc',
                              ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin/clang++',
                              root / ('sdk/cmake/' + PINS['cmake'] + '/bin/cmake'),
                              root / ('sdk/cmake/' + PINS['cmake'] + '/bin/ninja'), root / 'gcc', root / 'g++']
                for path in tool_paths:
                    path.parent.mkdir(parents=True, exist_ok=True)
                    header = bytearray(64)
                    header[:6] = b'\x7fELF\x02\x01'
                    header[18:20] = (62).to_bytes(2, 'little')
                    path.write_bytes(header)
                    path.chmod(0o700)
                for triplet, machine in [('aarch64-linux-android', 183), ('x86_64-linux-android', 62)]:
                    path = sysroot / 'usr/lib' / triplet / '33/libvulkan.so'
                    path.parent.mkdir(parents=True)
                    header = bytearray(64)
                    header[:6] = b'\x7fELF\x02\x01'
                    header[18:20] = machine.to_bytes(2, 'little')
                    path.write_bytes(header)
                shaders = repo / 'third_party/whisper.cpp/ggml/src/ggml-vulkan/vulkan-shaders'
                for filename in PINS['whisper_files']:
                    path = repo / 'third_party/whisper.cpp' / filename
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text('synthetic source; not qualified shader bytes')
                identity = {'consumed_whisper_files': {p: {'sha256': h} for p, h in PINS['whisper_files'].items()}}
                report = vp.Report(evidence, identity)
                seen = []

                def acquire(pin, work, current, deadline):
                    prefix = pin['root'] + '-' + pin['commit'] + '/'
                    names = {pin['license'], *pin['required']}
                    rows = [(prefix + name, b'// synthetic public header/license\n', tarfile.REGTYPE) for name in names]
                    path = work / (pin['root'] + '.tar.gz')
                    path.write_bytes(archive(rows))
                    current.data['archives'].append({'transfer_status': 'COMPLETE', **vp.file_identity(path)})
                    return path

                def command(argv, directory, output, name, deadline, seconds=60):
                    seen.append(name)
                    result = dict(id=name, argv=[str(a) for a in argv], exit_code=0, process_status='EXITED',
                                  stdout_text='synthetic version', stderr_text='')
                    if name == 'cmake-version':
                        result['stdout_text'] = 'cmake version 4.1.2\n'
                    if name in ('host-gcc-target', 'host-g++-target'):
                        result['stdout_text'] = 'x86_64-linux-gnu\n'
                    if name == failed:
                        result.update(exit_code=2, stderr_text='synthetic compiler failure')
                    elif name.startswith('feature-'):
                        extension = vp.FEATURES[name.removeprefix('feature-')]
                        result.update(exit_code=1, stderr_text="shader.comp:3: error: '#extension' : extension not supported: " + extension + '\n1 error generated.\n')
                    elif '-fshader-stage=compute' in argv:
                        target = Path(argv[argv.index('-o') + 1])
                        target.write_bytes(b'\x03\x02\x23\x07' + bytes(16))
                        if '-MD' in argv:
                            Path(argv[argv.index('-MF') + 1]).write_text(str(target) + ': ' + str(argv[3]))
                    elif name.startswith('headers-'):
                        paths = [core, scratch / 'Vulkan-Hpp/vulkan/vulkan.hpp',
                                 scratch / 'SPIRV-Headers/include/spirv/unified1/spirv.hpp']
                        result['stderr_text'] = ''.join('. ' + str(p) + '\n' for p in paths)
                    return result

                with patch.dict(vp.os.environ, ANDROID_HOME=str(root / 'sdk')), \
                        patch.object(vp.shutil, 'disk_usage', return_value=SimpleNamespace(free=4 * 1024**3)), \
                        patch.object(vp.shutil, 'which', side_effect=lambda name: str(root / name)), \
                        patch.object(vp, 'CORE_SHA', vp.file_identity(core)['sha256']), \
                        patch.object(vp, 'acquire_archive', side_effect=acquire), \
                        patch.object(vp, 'run_command', side_effect=command):
                    if failed:
                        with self.assertRaises(ValueError):
                            vp.collect(repo, PINS, scratch, report, vp.time.monotonic()+300)
                        report.finish()
                    else:
                        vp.collect(repo, PINS, scratch, report, vp.time.monotonic()+300)
                        report.finish(completed=True)
                saved = json.loads((evidence / 'report.json').read_text())
                self.assertFalse(saved['android_or_gpu_acceptance'])
                if failed:
                    self.assertEqual(saved['status'], 'FAILED')
                    self.assertEqual(saved['collection_status'], 'PARTIAL')
                    self.assertFalse(any(name.startswith('headers-') for name in seen))
                else:
                    self.assertEqual(saved['status'], 'PASSED')
                    self.assertEqual(saved['collection_status'], 'COMPLETE')
                    self.assertEqual(saved['completed_android_targets'], list(vp.TARGETS))
                    self.assertEqual(len(saved['completed_shader_probes']), 14)
                    self.assertEqual(sum(p['status'] == 'UNSUPPORTED' for p in saved['probes']), 7)

    def test_operation_deadline_restores_remaining_total_not_a_fresh_budget(self):
        with patch.object(vp.signal, 'getitimer', return_value=(200.0, 0.0)), \
                patch.object(vp.signal, 'getsignal', return_value='previous-handler'), \
                patch.object(vp.signal, 'signal') as handler, \
                patch.object(vp.signal, 'setitimer') as timers, \
                patch.object(vp.time, 'monotonic', side_effect=[10, 10, 15]):
            with vp.operation_deadline(70):
                pass
        self.assertEqual(timers.call_args_list[0].args, (vp.signal.ITIMER_REAL, 60))
        self.assertEqual(timers.call_args_list[-1].args, (vp.signal.ITIMER_REAL, 195, 0))
        self.assertEqual(handler.call_args_list[-1].args, (vp.signal.SIGALRM, 'previous-handler'))

    def test_wrong_elf_and_mixed_target_include_evidence_fail(self):
        with tempfile.TemporaryDirectory(dir=Path(tempfile.gettempdir()).resolve()) as temp:
            root = Path(temp)
            binary = root / 'host'
            binary.write_bytes(b'not ELF' + bytes(64))
            with self.assertRaises(ValueError):
                vp.elf_identity(binary, 62)
            with self.assertRaises(ValueError):
                vp.include_evidence('. /usr/include/vulkan/vulkan.hpp\n', root / 'sysroot', root / 'hpp', root / 'spv')
            with self.assertRaises(ValueError):
                vp.fresh_directories(root, '../123', '1')

    def test_low_capacity_stops_before_tools_or_transfers(self):
        with tempfile.TemporaryDirectory(dir=Path(tempfile.gettempdir()).resolve()) as temp:
            root = Path(temp)
            report = vp.Report(root, {})
            with patch.object(vp.shutil, 'disk_usage', return_value=SimpleNamespace(free=2 * 1024**3-1)), \
                    patch.object(vp, 'acquire_archive') as acquire, patch.object(vp, 'run_command') as command:
                with self.assertRaisesRegex(ValueError, 'two GiB free'):
                    vp.collect(root, PINS, root, report, vp.time.monotonic()+300)
                acquire.assert_not_called()
                command.assert_not_called()

    def test_workflow_opt_in_pinned_and_gate_before_sdk(self):
        workflow = (ROOT.parent / '.github/workflows/vulkan-preflight.yml').read_text()
        self.assertNotIn('workflow_dispatch', workflow)
        self.assertIn('codex/lip-vulkan-preflight', workflow)
        self.assertIn("contains(github.event.head_commit.message, '[vulkan-preflight]')", workflow)
        self.assertIn('persist-credentials: false', workflow)
        self.assertIn('submodules: recursive', workflow)
        self.assertIn('timeout-minutes: 30', workflow)
        self.assertLess(workflow.index('vulkan_preflight.py admit'), workflow.index('sdkmanager'))
        self.assertNotIn('sudo', workflow)
        self.assertNotIn('gradlew', workflow)
        self.assertNotIn('setup-java', workflow)


class RepairTest(unittest.TestCase):
    def saved_report(self, root, finished=None):
        env, event = context()
        identity = vp.require_context(env, event, SHA, event['head_commit']['message'], vp.WHISPER,
                                      vp.WHISPER, '', 'Linux', 'x86_64', 1001)
        identity['admission_commands'] = []
        evidence, scratch = vp.fresh_directories(root, '123', '1')
        report = vp.Report(evidence, identity)
        report.data.update(pins=PINS, stage='sdk-setup', sdk_setup_complete=True,
                           sdk_setup_finished_monotonic=vp.time.monotonic() if finished is None else finished)
        report.save()
        env.update(RUNNER_TEMP=str(root), VULKAN_RUNNER_TEMP=str(root),
                   VULKAN_EVIDENCE=str(evidence), VULKAN_SCRATCH=str(scratch))
        return env, identity, report, scratch

    def test_portable_temp_root_with_macos_directory_absent(self):
        mkdir = tempfile._os.mkdir
        with tempfile.TemporaryDirectory(dir=Path(tempfile.gettempdir()).resolve()) as temp:
            root = Path(temp)
            case = PreflightTest()

            def linux_mkdir(path, *args, **kwargs):
                if Path(path).parent == Path('/private/tmp'):
                    raise FileNotFoundError('Modeled Linux has no /private/tmp')
                return mkdir(path, *args, **kwargs)

            with patch.object(tempfile, 'gettempdir', return_value=str(root)), \
                    patch.object(tempfile._os, 'mkdir', side_effect=linux_mkdir):
                try:
                    _, fixture = case.unpack(archive(entries()))
                    self.assertEqual(fixture.parent, root)
                finally:
                    case.doCleanups()

    def test_readmission_is_inside_setup_anchored_timer(self):
        with tempfile.TemporaryDirectory(dir=Path(tempfile.gettempdir()).resolve()) as temp:
            env, identity, report, _ = self.saved_report(Path(temp), finished=0)
            clock, timer, observed = [291.0], [0.0, 0.0], []

            def set_timer(_kind, seconds, interval=0):
                timer[:] = [seconds, interval]

            def admission(*_args, deadline=None, **_kwargs):
                observed.append((timer[0], deadline))
                clock[0] += 30 if deadline is None else vp.command_budget(deadline, 30)
                if deadline is not None and clock[0] >= deadline:
                    raise TimeoutError('Modeled Git used the remaining admission budget')
                return identity

            with patch.dict(vp.os.environ, env), patch.object(vp.sys, 'argv', ['preflight', 'collect']), \
                    patch.object(vp.time, 'monotonic', side_effect=lambda: clock[0]), \
                    patch.object(vp.signal, 'getitimer', side_effect=lambda _: tuple(timer)), \
                    patch.object(vp.signal, 'setitimer', side_effect=set_timer), \
                    patch.object(vp, 'read_context', side_effect=admission), patch.object(vp, 'collect') as collect:
                self.assertEqual(vp.main(), 1)
                collect.assert_not_called()
            self.assertEqual(observed, [(9.0, 300)])
            self.assertLessEqual(clock[0], 300)
            self.assertEqual(json.loads((report.evidence / 'report.json').read_text())['status'], 'FAILED')

    def test_failed_readmission_is_retained_before_sdk_or_tools(self):
        for mode in ('install', 'collect'):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory(dir=Path(tempfile.gettempdir()).resolve()) as temp:
                env, _, report, _ = self.saved_report(Path(temp))
                with patch.dict(vp.os.environ, env), patch.object(vp.sys, 'argv', ['preflight', mode]), \
                        patch.object(vp, 'read_context', side_effect=vp.DiagnosticError('Pristine source failed re-admission')), \
                        patch.object(vp, 'collect') as collect, patch.object(vp, 'run_command') as command:
                    self.assertEqual(vp.main(), 1)
                    collect.assert_not_called()
                    command.assert_not_called()
                saved = json.loads((report.evidence / 'report.json').read_text())
                self.assertEqual(saved['status'], 'FAILED')
                self.assertEqual(saved['stage'], mode + '-admission')
                self.assertEqual(saved[mode + '_admission_status'], 'FAILED')
                self.assertEqual(saved['pending_operation'], 'checkout-readmission')
                self.assertEqual(saved['error_type'], 'DiagnosticError')

    def test_git_readmission_uses_remaining_deadline_and_keeps_failed_command(self):
        with tempfile.TemporaryDirectory(dir=Path(tempfile.gettempdir()).resolve()) as temp:
            env, event = context()
            event_path = Path(temp) / 'event.json'
            event_path.write_text(json.dumps(event))
            env.update(GITHUB_WORKSPACE=str(ROOT.parent), GITHUB_EVENT_PATH=str(event_path))
            commands = []

            def command(argv, directory, evidence, name, deadline, seconds):
                self.assertEqual(deadline, 300)
                self.assertEqual(vp.command_budget(deadline, seconds), 9)
                return dict(id=name, process_status='TIMEOUT', exit_code=None, stdout_text='', stderr_text='',
                            timeout_seconds=9, stdout={'bytes_observed': 0, 'complete': False})

            with patch.dict(vp.os.environ, env), patch.object(vp.time, 'monotonic', return_value=291), \
                    patch.object(vp, 'run_command', side_effect=command):
                with self.assertRaisesRegex(ValueError, 'Git inspection failed'):
                    vp.read_context(ROOT.parent, PINS, deadline=300, commands=commands)
            self.assertEqual(commands[0]['id'], 'admission-git-0')
            self.assertEqual(commands[0]['process_status'], 'TIMEOUT')
            self.assertEqual(commands[0]['timeout_seconds'], 9)

    def test_interrupted_readmission_names_the_actual_git_command(self):
        with tempfile.TemporaryDirectory(dir=Path(tempfile.gettempdir()).resolve()) as temp:
            env, _, report, _ = self.saved_report(Path(temp))

            def admission(*_args, commands, **_kwargs):
                commands.append(dict(id='admission-git-0', process_status='INTERRUPTED',
                                     interrupt_signal='SIGTERM', exit_code=-9))
                raise vp.DiagnosticInterrupted(vp.signal.SIGTERM)

            with patch.dict(vp.os.environ, env), patch.object(vp.sys, 'argv', ['preflight', 'collect']), \
                    patch.object(vp, 'read_context', side_effect=admission), patch.object(vp, 'collect') as collect:
                self.assertEqual(vp.main(), 1)
                collect.assert_not_called()
            saved = json.loads((report.evidence / 'report.json').read_text())
            self.assertEqual(saved['status'], 'FAILED')
            self.assertEqual(saved['collect_admission_status'], 'FAILED')
            self.assertEqual(saved['interrupted_operation'], 'admission-git-0')
            self.assertEqual(saved['collect_admission_commands'][0]['process_status'], 'INTERRUPTED')

    def command_fixture(self, scratch, evidence, payload=b'', interrupt=None, name='fixture'):
        pipes = [SimpleNamespace(fileno=lambda: 10, close=lambda: None),
                 SimpleNamespace(fileno=lambda: 11, close=lambda: None)]
        child = SimpleNamespace(pid=12345, stdout=pipes[0], stderr=pipes[1], returncode=-2 if interrupt else 0,
                                wait=lambda timeout: -2 if interrupt else 0)

        class Selector:
            def __enter__(self):
                self.rows = []
                return self
            def __exit__(self, *_):
                pass
            def register(self, pipe, _events, kind):
                self.rows.append(SimpleNamespace(fileobj=pipe, data=kind))
            def unregister(self, pipe):
                self.rows = [key for key in self.rows if key.fileobj is not pipe]
            def get_map(self):
                return self.rows
            def select(self, _timeout):
                ready_to_interrupt = not payload or len(chunks[10]) == 1
                if interrupt == 'KeyboardInterrupt' and ready_to_interrupt:
                    raise KeyboardInterrupt()
                if interrupt and ready_to_interrupt:
                    signum = getattr(vp.signal, interrupt)
                    handler = vp.signal.getsignal(signum)
                    assert callable(handler), 'Cancellation handler must be installed'
                    handler(signum, None)
                return [(key, None) for key in self.rows]

        chunks = {10: [payload[i:i+65536] for i in range(0, len(payload), 65536)] + [b''], 11: [b'']}

        cleanup_timers = []

        def kill(pid, signum):
            self.assertEqual((pid, signum), (12345, vp.signal.SIGKILL))
            cleanup_timers.append(vp.signal.getitimer(vp.signal.ITIMER_REAL))

        with patch.object(vp.subprocess, 'Popen', return_value=child), \
                patch.object(vp.selectors, 'DefaultSelector', Selector), \
                patch.object(vp.os, 'read', side_effect=lambda fd, _: chunks[fd].pop(0)), \
                patch.object(vp.os, 'killpg', side_effect=kill) as cleanup:
            result = vp.run_command(['/synthetic/compiler'], scratch, evidence, name, vp.time.monotonic()+60)
            cleanup.assert_called_once()
            self.assertEqual(cleanup_timers, [(0.0, 0.0)])
            return result

    def test_interrupted_operation_cleanup_status_and_handlers_are_retained(self):
        for interruption in ('KeyboardInterrupt', 'SIGTERM', 'SIGINT'):
            with self.subTest(interruption=interruption), tempfile.TemporaryDirectory(dir=Path(tempfile.gettempdir()).resolve()) as temp:
                env, identity, report, scratch = self.saved_report(Path(temp))
                handlers = {s: vp.signal.getsignal(s) for s in (vp.signal.SIGALRM, vp.signal.SIGTERM, vp.signal.SIGINT)}

                def collect(*args):
                    current = args[3]
                    current.data['pending_operation'] = 'interrupted-probe'
                    vp.record_probe(current, self.command_fixture(scratch, current.evidence,
                                    interrupt=interruption, name='interrupted-probe'))

                with patch.dict(vp.os.environ, env), patch.object(vp.sys, 'argv', ['preflight', 'collect']), \
                        patch.object(vp, 'read_context', return_value=identity), patch.object(vp, 'collect', side_effect=collect):
                    try:
                        self.assertEqual(vp.main(), 1)
                    except KeyboardInterrupt:
                        self.fail('KeyboardInterrupt must retain the command and failure report')
                saved = json.loads((report.evidence / 'report.json').read_text())
                self.assertEqual(saved['status'], 'FAILED')
                self.assertEqual(saved['interrupted_operation'], 'interrupted-probe')
                self.assertEqual(saved['probes'][0]['process_status'], 'INTERRUPTED')
                self.assertFalse(saved['probes'][0]['stdout']['complete'])
                self.assertEqual({s: vp.signal.getsignal(s) for s in handlers}, handlers)
                self.assertEqual(vp.signal.getitimer(vp.signal.ITIMER_REAL), (0.0, 0.0))

    def test_text_cap_is_enforced_before_publish_and_leaves_failure_room(self):
        with tempfile.TemporaryDirectory(dir=Path(tempfile.gettempdir()).resolve()) as temp:
            root = Path(temp)
            report = vp.Report(root, {})
            (root / 'prior.txt').write_bytes(b'x' * (vp.ARTIFACT_LIMIT - 64 * 1024))
            payload = b'y' * vp.OUTPUT_LIMIT
            result = self.command_fixture(root, root, payload=payload)
            self.assertEqual(result['process_status'], 'ARTIFACT_LIMIT')
            self.assertEqual(result['stdout']['bytes_observed'], len(payload))
            self.assertEqual(result['stdout']['observed_sha256'], hashlib.sha256(payload).hexdigest())
            self.assertFalse(result['stdout']['complete'])
            self.assertLess(result['stdout']['retained_bytes'], len(payload))
            self.assertLessEqual(sum(p.stat().st_size for p in root.iterdir()), vp.ARTIFACT_LIMIT)
            with self.assertRaises(ValueError):
                vp.record_probe(report, result)
            report.fail(vp.DiagnosticError('Artifact budget exhausted'))
            saved = json.loads((root / 'report.json').read_text())
            self.assertEqual(saved['status'], 'FAILED')
            self.assertEqual(saved['probes'][0]['stdout'], result['stdout'])
            self.assertLessEqual(sum(p.stat().st_size for p in root.iterdir()), vp.ARTIFACT_LIMIT)

    def test_metadata_exhaustion_retains_small_failure_and_partial_identities(self):
        with tempfile.TemporaryDirectory(dir=Path(tempfile.gettempdir()).resolve()) as temp:
            root = Path(temp)
            report = vp.Report(root, {})
            partial = {'bytes_observed': 5, 'observed_sha256': hashlib.sha256(b'part!').hexdigest(),
                       'complete': False, 'retained_bytes': 5, 'retained_sha256': hashlib.sha256(b'part!').hexdigest()}
            report.data['probes'] = [{'id': 'interrupted-probe', 'process_status': 'INTERRUPTED', 'stdout': partial}]
            report.data['oversized_metadata'] = 'x' * vp.ARTIFACT_LIMIT
            report.fail(vp.DiagnosticError('Bounded fixture failed'))
            saved = json.loads((root / 'report.json').read_text())
            self.assertEqual(saved['status'], 'FAILED')
            self.assertEqual(saved['collection_status'], 'PARTIAL')
            self.assertTrue(saved['metadata_truncated'])
            self.assertEqual(saved['probes'][0]['stdout'], partial)
            self.assertLessEqual(sum(p.stat().st_size for p in root.iterdir()), vp.ARTIFACT_LIMIT)

    def test_save_exhaustion_publishes_failed_receipt_before_raising(self):
        with tempfile.TemporaryDirectory(dir=Path(tempfile.gettempdir()).resolve()) as temp:
            root = Path(temp)
            report = vp.Report(root, {})
            report.data['oversized_metadata'] = 'x' * vp.ARTIFACT_LIMIT
            with self.assertRaisesRegex(ValueError, 'exceeds 16 MiB'):
                report.save()
            saved = json.loads((root / 'report.json').read_text())
            self.assertEqual(saved['status'], 'FAILED')
            self.assertTrue(saved['metadata_truncated'])
            report.fail(vp.DiagnosticError('Artifact budget exhausted'))
            self.assertEqual(json.loads((root / 'report.json').read_text())['status'], 'FAILED')

    def test_artifact_limit_does_not_erase_interruption(self):
        with tempfile.TemporaryDirectory(dir=Path(tempfile.gettempdir()).resolve()) as temp:
            root = Path(temp)
            report = vp.Report(root, {})
            (root / 'prior.txt').write_bytes(b'x' * (vp.ARTIFACT_LIMIT - 64 * 1024))
            result = self.command_fixture(root, root, payload=b'partial', interrupt='KeyboardInterrupt')
            self.assertEqual(result['process_status'], 'INTERRUPTED')
            self.assertTrue(result['artifact_limit_reached'])
            with self.assertRaises(ValueError):
                vp.record_probe(report, result)
            saved = json.loads((root / 'report.json').read_text())
            self.assertEqual(saved['interrupted_operation'], 'fixture')
            self.assertFalse(saved['probes'][0]['stdout']['complete'])

    def test_archive_cancellation_retains_failed_transfer_and_interrupt_type(self):
        for error in (KeyboardInterrupt(), vp.DiagnosticInterrupted(vp.signal.SIGTERM)):
            with self.subTest(error=type(error).__name__), tempfile.TemporaryDirectory(dir=Path(tempfile.gettempdir()).resolve()) as temp:
                report = vp.Report(Path(temp), {})
                with patch.object(vp.urllib.request, 'urlopen', side_effect=error):
                    with self.assertRaises(type(error)):
                        vp.acquire_archive(PINS['archives'][0], Path(temp), report, vp.time.monotonic()+60)
                saved = json.loads((Path(temp) / 'report.json').read_text())
                self.assertEqual(saved['archives'][0]['transfer_status'], 'FAILED')
                self.assertEqual(saved['archives'][0]['error_type'], type(error).__name__)

    def test_quoted_subdirectory_parent_includes_and_root_escape(self):
        prefix = 'Fixture-' + SHA + '/'
        for include, good in [('detail/missing.hpp', False), ('vulkan/child.hpp', False),
                              ('../../vulkan/child.hpp', False),
                              ('../missing.hpp', False), ('../vulkan/child.hpp', True),
                              ('detail/../child.hpp', True), ('detail/present.hpp', True)]:
            with self.subTest(include=include), tempfile.TemporaryDirectory(dir=Path(tempfile.gettempdir()).resolve()) as temp:
                rows = entries()
                rows[1] = (rows[1][0], ('#include "' + include + '"\n').encode(), tarfile.REGTYPE)
                rows.append((prefix + 'vulkan/detail/present.hpp', b'#include "../child.hpp"\n', tarfile.REGTYPE))
                source = Path(temp) / 'source.tar.gz'
                source.write_bytes(archive(rows))
                if good:
                    self.assertIn('vulkan/vulkan.hpp', vp.unpack_archive(source, Path(temp) / 'selected', archive_pin()))
                else:
                    with self.assertRaises(ValueError):
                        vp.unpack_archive(source, Path(temp) / 'selected', archive_pin())


    def test_near_deadline_cancellation_keeps_primary_and_cleanup_expiry(self):
        with tempfile.TemporaryDirectory(dir=Path(tempfile.gettempdir()).resolve()) as temp:
            env, identity, report, scratch = self.saved_report(Path(temp), finished=0)
            clock, timer = [299.0], [0.0, 0.0]
            handlers = {s: vp.signal.getsignal(s) for s in (vp.signal.SIGALRM, vp.signal.SIGTERM, vp.signal.SIGINT)}
            child = SimpleNamespace(pid=12345, returncode=-9, stdout=unittest.mock.Mock(), stderr=unittest.mock.Mock())

            def join(timeout):
                clock[0] += 2
                return -9

            child.wait = unittest.mock.Mock(side_effect=join)

            class Selector:
                def __enter__(self): return self
                def __exit__(self, *_): pass
                def register(self, *_): pass
                def get_map(self): return True
                def select(self, _): vp.signal.getsignal(vp.signal.SIGTERM)(vp.signal.SIGTERM, None)

            def set_timer(_kind, seconds, interval=0):
                timer[:] = [seconds, interval]

            def collect(*args):
                current = args[3]
                current.data['pending_operation'] = 'cancel-at-deadline'
                with patch.object(vp.subprocess, 'Popen', return_value=child), \
                        patch.object(vp.selectors, 'DefaultSelector', Selector), patch.object(vp.os, 'killpg') as kill:
                    result = vp.run_command(['/synthetic/compiler'], scratch, current.evidence,
                                            'cancel-at-deadline', args[4])
                    kill.assert_called_once_with(12345, vp.signal.SIGKILL)
                vp.record_probe(current, result)

            with patch.dict(vp.os.environ, env), patch.object(vp.sys, 'argv', ['preflight', 'collect']), \
                    patch.object(vp.time, 'monotonic', side_effect=lambda: clock[0]), \
                    patch.object(vp.signal, 'getitimer', side_effect=lambda _: tuple(timer)), \
                    patch.object(vp.signal, 'setitimer', side_effect=set_timer), \
                    patch.object(vp, 'read_context', return_value=identity), patch.object(vp, 'collect', side_effect=collect):
                self.assertEqual(vp.main(), 1)
            saved = json.loads((report.evidence / 'report.json').read_text())
            probe = saved['probes'][0]
            self.assertEqual((saved['status'], saved['collection_status']), ('FAILED', 'PARTIAL'))
            self.assertEqual((probe['process_status'], probe['error_type'], probe['interrupt_signal']),
                             ('INTERRUPTED', 'DiagnosticInterrupted', 'SIGTERM'))
            self.assertTrue(probe['cleanup_deadline_exhausted'])
            self.assertEqual((saved['interrupted_operation'], saved['interrupt_signal']), ('cancel-at-deadline', 'SIGTERM'))
            self.assertFalse(saved['android_or_gpu_acceptance'])
            for kind in ('stdout', 'stderr'):
                self.assertFalse(probe[kind]['complete'])
                self.assertEqual(probe[kind]['retained_sha256'], hashlib.sha256(b'').hexdigest())
            child.wait.assert_called_once_with(timeout=5)
            child.stdout.close.assert_called_once()
            child.stderr.close.assert_called_once()
            self.assertEqual({s: vp.signal.getsignal(s) for s in handlers}, handlers)
            self.assertEqual(timer, [0, 0])
            with patch.object(vp.time, 'monotonic', return_value=clock[0]), self.assertRaises(TimeoutError):
                vp.command_budget(300, 60)
            report.data = saved
            report.data['oversized_metadata'] = 'x' * vp.ARTIFACT_LIMIT
            report.fail(vp.DiagnosticInterrupted(vp.signal.SIGTERM))
            compact = json.loads((report.evidence / 'report.json').read_text())
            self.assertTrue(compact['metadata_truncated'])
            self.assertTrue(compact['probes'][0]['cleanup_deadline_exhausted'])
            self.assertEqual(compact['probes'][0]['error_type'], 'DiagnosticInterrupted')
            self.assertLessEqual(sum(p.stat().st_size for p in report.evidence.iterdir()), vp.ARTIFACT_LIMIT)

    def test_archive_compaction_keeps_cancellation_through_main_and_collect(self):
        for phase in ('transfer', 'consumption'):
            with self.subTest(phase=phase), tempfile.TemporaryDirectory(dir=Path(tempfile.gettempdir()).resolve()) as temp:
                root = Path(temp)
                env, identity, report, scratch = self.saved_report(root, finished=0)
                env['ANDROID_HOME'] = str(root / 'sdk')
                ndk = root / 'sdk/ndk' / PINS['ndk']
                core = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include/vulkan/vulkan_core.h'
                core.parent.mkdir(parents=True)
                core.write_text('// SPDX-License-Identifier: Apache-2.0\n#define VK_HEADER_VERSION 335\n')
                (ndk / 'source.properties').write_text('Pkg.Revision = ' + PINS['ndk'] + '\n')
                for notice in ('NOTICE', 'NOTICE.toolchain'):
                    (ndk / notice).write_text('synthetic license notice')
                identity['consumed_whisper_files'] = {p: {'sha256': h} for p, h in PINS['whisper_files'].items()}
                report.data['identity'] = identity
                report.save()
                handlers = {s: vp.signal.getsignal(s) for s in (vp.signal.SIGALRM, vp.signal.SIGTERM, vp.signal.SIGINT)}
                original_acquire, original_unpack, original_collect = vp.acquire_archive, vp.unpack_archive, vp.collect
                original_transfer = vp.copy_transfer
                pin = PINS['archives'][0]
                error = vp.DiagnosticInterrupted(vp.signal.SIGTERM)
                clock, timer = [299.0], [0.0, 0.0]
                caught = []

                def fill(current):
                    current.data['padding'] = ''
                    overhead = len((json.dumps(current.data, indent=2, allow_nan=False) + '\n').encode())
                    current.data['padding'] = 'x' * (vp.ARTIFACT_LIMIT - overhead)
                    current.save()

                class Source(io.BytesIO):
                    status = 200
                    headers = {'Content-Length': '7'}
                    def geturl(self):
                        return 'https://codeload.github.com/' + pin['repository'] + '/tar.gz/' + pin['commit']
                    def read(self, _size):
                        if self.tell():
                            raise error
                        fill(current_report[0])
                        return super().read(4)

                current_report = [None]

                def acquire(selected_pin, work, current, deadline):
                    current_report[0] = current
                    if phase == 'transfer':
                        try:
                            return original_acquire(selected_pin, work, current, deadline)
                        except vp.DiagnosticInterrupted as observed:
                            caught.append(observed)
                            raise
                    prefix = selected_pin['root'] + '-' + selected_pin['commit'] + '/'
                    rows = [(prefix + name, b'// synthetic public header/license\n', tarfile.REGTYPE)
                            for name in {selected_pin['license'], *selected_pin['required']}]
                    path = work / (selected_pin['root'] + '.tar.gz')
                    path.write_bytes(archive(rows))
                    current.data['archives'].append({**selected_pin, 'transfer_status': 'COMPLETE',
                                                   'consumption_status': 'NOT_RUN', **vp.file_identity(path)})
                    fill(current)
                    return path

                def unpack(*args):
                    original_unpack(*args)
                    raise error

                def command(argv, directory, evidence, name, deadline, seconds=60):
                    text = ('cmake version 4.1.2\n' if name == 'cmake-version' else
                            'x86_64-linux-gnu\n' if name.endswith('-target') else 'synthetic version')
                    return dict(id=name, exit_code=0, process_status='EXITED', stdout_text=text, stderr_text='')

                def collect(*args):
                    with patch.object(vp, 'CORE_SHA', vp.file_identity(core)['sha256']):
                        return original_collect(*args)

                def set_timer(_kind, seconds, interval=0):
                    timer[:] = [seconds, interval]

                with patch.dict(vp.os.environ, env), patch.object(vp.sys, 'argv', ['preflight', 'collect']), \
                        patch.object(vp.time, 'monotonic', side_effect=lambda: clock[0]), \
                        patch.object(vp.signal, 'getitimer', side_effect=lambda _: tuple(timer)), \
                        patch.object(vp.signal, 'setitimer', side_effect=set_timer), \
                        patch.object(vp, 'read_context', return_value=identity), \
                        patch.object(vp.shutil, 'disk_usage', return_value=SimpleNamespace(free=4 * 1024**3)), \
                        patch.object(vp.shutil, 'which', side_effect=lambda name: str(root / name)), \
                        patch.object(vp, 'collect', side_effect=collect), \
                        patch.object(vp.os, 'access', return_value=True), patch.object(vp, 'elf_identity', return_value={}), \
                        patch.object(vp, 'run_command', side_effect=command), \
                        patch.object(vp, 'acquire_archive', side_effect=acquire), patch.object(vp, 'unpack_archive', side_effect=unpack), \
                        patch.object(vp, 'copy_transfer', side_effect=lambda *args: original_transfer(*args, now=lambda: clock[0])), \
                        patch.object(vp.urllib.request, 'urlopen', return_value=Source(b'partial')):
                    self.assertEqual(vp.main(), 1)
                saved = json.loads((report.evidence / 'report.json').read_text())
                self.assertEqual((saved['status'], saved['collection_status'], saved['error_type']),
                                 ('FAILED', 'PARTIAL', 'DiagnosticInterrupted'))
                self.assertEqual((saved['interrupted_operation'], saved['interrupt_signal']), ('archive-Vulkan-Hpp', 'SIGTERM'))
                self.assertTrue(saved['metadata_truncated'])
                record = saved['archives'][0]
                self.assertEqual(record[phase + '_status'], 'FAILED')
                self.assertEqual(record['error_type'], 'DiagnosticInterrupted')
                if phase == 'transfer':
                    self.assertEqual(caught, [error])
                    self.assertEqual(record['partial_file'], {'bytes': 4, 'sha256': hashlib.sha256(b'part').hexdigest()})
                else:
                    self.assertEqual(record['transfer_status'], 'COMPLETE')
                    self.assertTrue((scratch / pin['root'] / pin['license']).is_file())
                self.assertLessEqual(sum(p.stat().st_size for p in report.evidence.iterdir()), vp.ARTIFACT_LIMIT)
                self.assertFalse(saved['android_or_gpu_acceptance'])
                self.assertEqual({s: vp.signal.getsignal(s) for s in handlers}, handlers)
                self.assertEqual(timer, [0, 0])


    def test_archive_http_failure_does_not_retain_private_error_body(self):
        with tempfile.TemporaryDirectory(dir=Path(tempfile.gettempdir()).resolve()) as temp:
            report = vp.Report(Path(temp), {})
            private = 'private response body and signed URL placeholder'
            with patch.object(vp.urllib.request, 'urlopen', side_effect=OSError(private)):
                with self.assertRaisesRegex(vp.DiagnosticError, '^Pinned public archive acquisition failed$'):
                    vp.acquire_archive(PINS['archives'][0], Path(temp), report, vp.time.monotonic()+60)
            saved = (Path(temp) / 'report.json').read_text()
            self.assertNotIn(private, saved)
            self.assertEqual(json.loads(saved)['archives'][0]['error_type'], 'OSError')


if __name__ == '__main__':
    unittest.main()
