"""Bounded public artifact fixtures; no compiler, SDK, network or inference."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from cpu_artifacts import collect, compiled_guard, main, MAX_BYTES

ROOT = Path(__file__).resolve().parents[1]
SHA = 'd01bfed60fca9c11d1f690089264de4ca07c483a'


class CpuArtifactsTest(unittest.TestCase):
    def setUp(self):
        self.make_fixture('false')

    def make_fixture(self, arm):
        temp = tempfile.TemporaryDirectory(prefix='cpu-artifacts-')
        self.addCleanup(temp.cleanup)
        self.base = Path(temp.name).resolve()
        self.root = self.base / 'checkout'
        self.runner = self.base / 'runner-temp'
        self.runner.mkdir()
        self.output = self.runner / 'capture'
        self.builds = {}
        for abi in ('arm64-v8a', 'x86_64'):
            build = self.root / 'app/.cxx/RelWithDebInfo/fixture' / abi
            build.mkdir(parents=True)
            self.builds[abi] = build
            output = self.root / 'app/build/intermediates/cxx/RelWithDebInfo/fixture/obj' / abi
            output.mkdir(parents=True)
            values = {'ANDROID_ABI': abi, 'CMAKE_ANDROID_ARCH_ABI': abi,
                      'ANDROID_STL': 'c++_shared', 'ANDROID_PLATFORM': 'android-33',
                      'CMAKE_SYSTEM_NAME': 'Android', 'CMAKE_BUILD_TYPE': 'RelWithDebInfo',
                      'CMAKE_HOME_DIRECTORY': str(self.root / 'app/src/main/cpp'),
                      'CMAKE_CACHEFILE_DIR': str(build), 'CMAKE_LIBRARY_OUTPUT_DIRECTORY': str(output),
                      'LIP_GGML_DOTPROD': 'ON' if arm == 'true' else 'OFF',
                      'BUILD_SHARED_LIBS': 'OFF', 'GGML_BACKEND_DL': 'ON', 'GGML_NATIVE': 'OFF',
                      'GGML_CPU_ALL_VARIANTS': 'ON' if abi == 'arm64-v8a' else 'OFF',
                      'GGML_CPU_ARM_ARCH': '', 'CMAKE_CACHE_MAJOR_VERSION': '4',
                      'CMAKE_CACHE_MINOR_VERSION': '1', 'CMAKE_CACHE_PATCH_VERSION': '2'}
            (build / 'CMakeCache.txt').write_text(''.join(f'{k}:STRING={v}\n' for k, v in values.items()))
            (build / 'compile_commands.json').write_text(json.dumps([
                {'directory': str(build), 'file': str(build / 'whisper-cancel/whisper.cpp'),
                 'arguments': ['clang++', '-c', 'whisper.cpp']},
                {'directory': str(build), 'file': str(build / 'ggml-dotprod/src/ggml.c'),
                 'arguments': ['clang', '-c', 'ggml.c']}]))
            reply = build / '.cmake/api/v1/reply'
            reply.mkdir(parents=True)
            names = ['lip_whisper', 'ggml', 'ggml-base']
            names += ['ggml-cpu'] if abi == 'x86_64' else ['ggml-cpu-android_armv8.0_1']
            if abi == 'arm64-v8a' and arm == 'true':
                names.append('ggml-cpu-android_armv8.2_1')
            targets = []
            agp = {}
            for name in names:
                library = output / f'lib{name}.so'
                library.write_bytes(b'\x7fELF' + b'simulated-unstripped-fixture-' + name.encode())
                target = {'name': name, 'type': 'MODULE_LIBRARY' if name.startswith('ggml-cpu') else 'SHARED_LIBRARY',
                          'artifacts': [{'path': str(library)}]}
                (reply / f'target-{name}.json').write_text(json.dumps(target))
                targets.append({'name': name, 'jsonFile': f'target-{name}.json'})
                agp[name] = {'artifactName': name, 'abi': abi, 'output': str(library)}
            (reply / 'codemodel-v2-fixture.json').write_text(json.dumps({'kind': 'codemodel', 'version': {'major': 2},
                'paths': {'build': str(build), 'source': str(self.root / 'app/src/main/cpp')},
                'configurations': [{'name': 'RelWithDebInfo', 'targets': targets}]}))
            (build / 'android_gradle_build.json').write_text(json.dumps({'libraries': agp}))
            stl = self.root / 'app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib' / abi / 'libc++_shared.so'
            stl.parent.mkdir(parents=True)
            stl.write_bytes(b'\x7fELFsimulated-stl-fixture')
        for name, path in [('main', 'app/build/outputs/apk/debug/app-debug.apk'),
                           ('test', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')]:
            apk = self.root / path
            apk.parent.mkdir(parents=True)
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('classes.dex', name)
                archive.writestr('META-INF/MANIFEST.MF', 'signer A')
                archive.writestr('META-INF/CERT.RSA', 'signer A')
                archive.writestr('META-INF/services/public.Service', 'preserve payload')

    def capture(self, arm='false', gate='success'):
        return collect(self.root, self.runner, self.output, arm,
                       {'source_sha': SHA, 'image_os': 'ubuntu24', 'image_version': 'fixture',
                        'artifact_name': f'Lip-cpu-{SHA}-{arm}-1-1'}, gate)

    def test_valid_both_arms_and_key_independent_payload(self):
        for arm in ('false', 'true'):
            with self.subTest(arm=arm):
                self.make_fixture(arm)
                record = self.capture(arm)
                self.assertEqual(record['status'], 'complete_capture')
                self.assertEqual(record['acceptance'], 'not_assessed')
                self.assertEqual(set(record['abis']), set(self.builds))
                self.assertLessEqual(record['total_bytes'], MAX_BYTES)
                self.assertTrue((self.output / 'provenance.json').is_file())
                for row in record['files']:
                    data = (self.output / row['path']).read_bytes()
                    self.assertEqual(hashlib.sha256(data).hexdigest(), row['sha256'])
                    self.assertEqual(len(data), row['bytes'])
                payload = record['apk_payloads']['main']
                self.assertEqual([r['path'] for r in payload], ['META-INF/services/public.Service', 'classes.dex'])
        first = record['apk_payloads']
        self.output = self.runner / 'different-signer'
        apk = self.root / 'app/build/outputs/apk/debug/app-debug.apk'
        with zipfile.ZipFile(apk, 'w') as archive:
            archive.writestr('classes.dex', 'main')
            archive.writestr('META-INF/MANIFEST.MF', 'different signer')
            archive.writestr('META-INF/CERT.EC', 'different signer')
            archive.writestr('META-INF/services/public.Service', 'preserve payload')
        self.assertEqual(self.capture('true')['apk_payloads'], first)

    def test_missing_duplicate_wrong_arm_and_bad_cache(self):
        cases = ('missing', 'duplicate', 'wrong_arm', 'backend', 'stl', 'abi', 'output', 'empty_commands', 'missing_target')
        for case in cases:
            with self.subTest(case=case):
                self.make_fixture('false')
                build = self.builds['arm64-v8a']
                if case == 'missing':
                    (build / 'compile_commands.json').unlink()
                elif case == 'duplicate':
                    shutil.copytree(build, self.root / 'app/.cxx/RelWithDebInfo/other/arm64-v8a')
                elif case == 'empty_commands':
                    (build / 'compile_commands.json').write_text('[]')
                elif case == 'missing_target':
                    (build / '.cmake/api/v1/reply/target-ggml.json').unlink()
                else:
                    old, new = {'wrong_arm': ('LIP_GGML_DOTPROD:STRING=OFF', 'LIP_GGML_DOTPROD:STRING=ON'),
                                'backend': ('GGML_BACKEND_DL:STRING=ON', 'GGML_BACKEND_DL:STRING=OFF'),
                                'stl': ('c++_shared', 'c++_static'), 'abi': ('ANDROID_ABI:STRING=arm64-v8a', 'ANDROID_ABI:STRING=x86_64'),
                                'output': (str(self.root / 'app/build/intermediates/cxx/RelWithDebInfo/fixture/obj/arm64-v8a'), str(self.base / 'outside'))}[case]
                    cache = build / 'CMakeCache.txt'
                    cache.write_text(cache.read_text().replace(old, new))
                with self.assertRaises((ValueError, FileNotFoundError)):
                    self.capture()
                self.assertFalse((self.output / 'provenance.json').exists())

    def test_tools_mirror_ignored_but_cannot_replace_real(self):
        mirror = self.root / 'app/.cxx/tools/debug/arm64-v8a'
        shutil.copytree(self.builds['arm64-v8a'], mirror)
        self.capture()
        self.output = self.runner / 'missing-real'
        (self.builds['arm64-v8a'] / 'compile_commands.json').unlink()
        with self.assertRaises(ValueError):
            self.capture()

    def test_outside_symlink_and_destination_rejected(self):
        apk = self.root / 'app/build/outputs/apk/debug/app-debug.apk'
        outside = self.base / 'outside.apk'
        shutil.copyfile(apk, outside)
        apk.unlink()
        apk.symlink_to(outside)
        with self.assertRaises(ValueError):
            self.capture()
        self.assertFalse((self.output / 'provenance.json').exists())
        self.output = self.root / 'unsafe-capture'
        with self.assertRaises(ValueError):
            self.capture()

    def test_oversize_is_rejected_before_copy(self):
        with patch('cpu_artifacts.MAX_BYTES', 64), self.assertRaises(ValueError):
            self.capture()
        self.assertFalse((self.output / 'provenance.json').exists())

    def test_failed_build_has_no_complete_receipt(self):
        with self.assertRaises(ValueError):
            self.capture(gate='failure')
        self.assertFalse((self.output / 'provenance.json').exists())
        self.assertTrue((self.output / 'diagnostics.json').is_file())
        diagnostic = json.loads((self.output / 'diagnostics.json').read_text())
        self.assertEqual(diagnostic['status'], 'diagnostic_only')
        self.assertNotIn('passed', diagnostic)

    def test_guard_is_exact_ordinary_workflow_body(self):
        import textwrap
        ordinary = (ROOT / '.github/workflows/android.yml').read_text()
        body = ordinary.split('      - name: Verify compiled cancellation patch in both ABIs\n', 1)[1]
        expected = textwrap.dedent(body.split("          python3 - <<'PY'\n", 1)[1].split('          PY\n', 1)[0])
        self.assertEqual(compiled_guard(ROOT), expected)
        self.assertIn("'tools' in database.relative_to(root / 'app/.cxx').parts", expected)

    def test_stale_missing_and_mismatched_selected_outputs(self):
        for case in ('stale', 'missing_library', 'missing_stl', 'non_elf', 'agp', 'codemodel', 'outside_reply', 'pristine_source'):
            with self.subTest(case=case):
                self.make_fixture('false')
                build = self.builds['arm64-v8a']
                output = self.root / 'app/build/intermediates/cxx/RelWithDebInfo/fixture/obj/arm64-v8a'
                if case == 'stale':
                    (output / 'libggml-cpu-android_armv8.2_1.so').write_bytes(b'\x7fELFstale')
                elif case == 'missing_library':
                    (output / 'libggml.so').unlink()
                elif case == 'missing_stl':
                    (self.root / 'app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/arm64-v8a/libc++_shared.so').unlink()
                elif case == 'non_elf':
                    (output / 'libggml.so').write_bytes(b'not a built library')
                elif case == 'agp':
                    path = build / 'android_gradle_build.json'
                    model = json.loads(path.read_text())
                    model['libraries']['ggml']['abi'] = 'x86_64'
                    path.write_text(json.dumps(model))
                elif case == 'codemodel':
                    reply = build / '.cmake/api/v1/reply'
                    shutil.copyfile(reply / 'codemodel-v2-fixture.json', reply / 'codemodel-v2-stale.json')
                elif case == 'outside_reply':
                    path = build / '.cmake/api/v1/reply/codemodel-v2-fixture.json'
                    model = json.loads(path.read_text())
                    model['configurations'][0]['targets'][0]['jsonFile'] = str(self.base / 'outside.json')
                    path.write_text(json.dumps(model))
                else:
                    path = build / 'compile_commands.json'
                    rows = json.loads(path.read_text())
                    rows[1]['file'] = str(self.root / 'third_party/whisper.cpp/ggml/src/ggml.c')
                    path.write_text(json.dumps(rows))
                with self.assertRaises((ValueError, FileNotFoundError)):
                    self.capture()
                self.assertFalse((self.output / 'provenance.json').exists())

    def test_bad_apk_and_existing_destination_fail_closed(self):
        for case in ('missing', 'duplicate', 'unsafe', 'no_dex', 'existing_destination'):
            with self.subTest(case=case):
                self.make_fixture('false')
                apk = self.root / 'app/build/outputs/apk/debug/app-debug.apk'
                if case == 'missing':
                    apk.unlink()
                elif case == 'existing_destination':
                    self.output.mkdir()
                    (self.output / 'user-work.txt').write_text('preserve')
                else:
                    with zipfile.ZipFile(apk, 'w') as archive:
                        archive.writestr('classes.dex' if case != 'no_dex' else 'nothing.txt', 'fixture')
                        if case == 'duplicate':
                            import warnings
                            with warnings.catch_warnings():
                                warnings.simplefilter('ignore', UserWarning)
                                archive.writestr('classes.dex', 'duplicate')
                        if case == 'unsafe':
                            archive.writestr('../outside', 'fixture')
                with self.assertRaises((ValueError, FileNotFoundError, FileExistsError)):
                    self.capture()
                self.assertFalse((self.output / 'provenance.json').exists())
                if case == 'existing_destination':
                    self.assertEqual((self.output / 'user-work.txt').read_text(), 'preserve')

    def test_workflow_reuses_gate_and_keeps_capture_isolated(self):
        workflow = (ROOT / '.github/workflows/cpu-experiment.yml').read_text()
        self.assertIn("exec(compile(compiled_guard(root),", workflow)
        self.assertIn("for database in databases(root).values():", workflow)
        self.assertIn("'--ggml-source', str(database.parent / 'ggml-dotprod')", workflow)
        self.assertNotIn('shlex', workflow)
        self.assertIn('include-hidden-files: true', workflow)
        self.assertIn('path: ${{ env.LIP_CPU_EVIDENCE }}', workflow)
        self.assertIn('ref: ${{ github.sha }}', workflow)
        self.assertIn('--max-workers=2', workflow)
        self.assertEqual(workflow.count('if: always()'), 2)

    def test_experiment_requires_exact_branch_opt_in_push(self):
        workflow = (ROOT / '.github/workflows/cpu-experiment.yml').read_text()
        self.assertIn("on:\n  push:\n    branches: ['codex/lip-dotprod-experiment']\n", workflow)
        self.assertNotIn('workflow_dispatch', workflow)
        self.assertIn("github.event_name == 'push'", workflow)
        self.assertIn("contains(github.event.head_commit.message, '[cpu-dotprod]')", workflow)

    def test_launch_guard_rejects_unmarked_or_wrong_push_before_capture(self):
        valid = {'LIP_DOTPROD': 'false', 'GITHUB_SHA': SHA,
                 'GITHUB_REPOSITORY': 'ihearttokyo/Lip',
                 'GITHUB_REF': 'refs/heads/codex/lip-dotprod-experiment',
                 'GITHUB_EVENT_NAME': 'push', 'ImageOS': 'ubuntu24', 'ImageVersion': 'fixture',
                 'LIP_CPU_ARTIFACT_NAME': 'fixture', 'RUNNER_TEMP': str(self.runner),
                 'LIP_CPU_EVIDENCE': str(self.output), 'LIP_CPU_GATE_OUTCOME': 'success'}
        cases = [('valid', {}, SHA, 'experiment [cpu-dotprod]'),
                 ('dispatch', {'GITHUB_EVENT_NAME': 'workflow_dispatch'}, SHA, '[cpu-dotprod]'),
                 ('wrong_repository', {'GITHUB_REPOSITORY': 'other/Lip'}, SHA, '[cpu-dotprod]'),
                 ('wrong_branch', {'GITHUB_REF': 'refs/heads/main'}, SHA, '[cpu-dotprod]'),
                 ('wrong_checkout', {}, '0' * 40, '[cpu-dotprod]'),
                 ('uppercase_marker', {}, SHA, '[CPU-DOTPROD]'),
                 ('mixedcase_marker', {}, SHA, '[cpu-Dotprod]'),
                 ('missing_marker', {}, SHA, 'ordinary commit')]
        for name, changes, checkout, message in cases:
            with self.subTest(name=name), patch.dict(os.environ, {**valid, **changes}, clear=True), \
                    patch('cpu_artifacts.subprocess.check_output', side_effect=[checkout, message]), \
                    patch('cpu_artifacts.collect') as capture:
                if name == 'valid':
                    main()
                    capture.assert_called_once()
                    self.assertEqual(capture.call_args.args[3], 'false')
                    self.assertEqual(capture.call_args.args[4]['source_sha'], SHA)
                else:
                    with self.assertRaisesRegex(ValueError, 'Require exact gated checkout SHA'):
                        main()
                    capture.assert_not_called()

    def test_exact_marker_preflight_runs_before_any_setup_or_build(self):
        workflow = (ROOT / '.github/workflows/cpu-experiment.yml').read_text()
        marker = '      - name: Check exact experiment opt-in\n        run: '
        self.assertEqual(workflow.count(marker), 1)
        script = workflow.split(marker)[1].splitlines()[0]
        self.assertEqual(script, "git log -1 --format=%B | grep -F '[cpu-dotprod]' >/dev/null")
        self.assertLess(workflow.index(marker), workflow.index('- uses: actions/setup-java@'))
        self.assertLess(workflow.index(marker), workflow.index('Install pinned SDK components'))
        self.assertLess(workflow.index(marker), workflow.index('Unit tests, lint, and build this arm'))
        tools = self.base / 'fake-git'
        tools.mkdir()
        git = tools / 'git'
        git.write_text('#!/bin/sh\n[ "$1" = log ] && [ "$2" = -1 ] && [ "$3" = --format=%B ] || exit 2\ncat "$MESSAGE_FILE"\n')
        git.chmod(0o755)
        message_file = self.base / 'message.txt'
        cases = [('experiment [cpu-dotprod]', 0), ('ordinary commit', 1),
                 ('experiment [CPU-DOTPROD]', 1), ('experiment [cpu-Dotprod]', 1),
                 ('$(touch forbidden) [cpu-dotprod]', 0),
                 ('x' * (1024 * 1024) + '\n[cpu-dotprod]', 0)]
        for message, expected in cases:
            with self.subTest(expected=expected, marker=message[-32:]):
                message_file.write_text(message)
                result = subprocess.run(['bash', '-c', script], cwd=self.base,
                    env={**os.environ, 'PATH': str(tools) + os.pathsep + os.environ['PATH'],
                         'MESSAGE_FILE': str(message_file)}, capture_output=True, timeout=5)
                self.assertEqual(result.returncode, expected, result.stderr)
                self.assertFalse((self.base / 'forbidden').exists())


if __name__ == '__main__':
    unittest.main()
