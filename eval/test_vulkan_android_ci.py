"""Source-only deterministic fixtures; native tools and downloads are forbidden."""
import copy
import hashlib
import io
import json
from pathlib import Path
import shlex
import struct
import tempfile
import unittest
from unittest.mock import patch
import wave

import vulkan_android_ci as ci
import vulkan_build_ci as vb
import vulkan_preflight as vp

ROOT = Path(__file__).resolve().parent.parent


def wav(samples=(-32768, -1, 0, 1, 32767)):
    stream = io.BytesIO()
    with wave.open(stream, 'wb') as out:
        out.setparams((1, 2, 16000, len(samples), 'NONE', 'not compressed'))
        out.writeframes(struct.pack('<' + 'h' * len(samples), *samples))
    return stream.getvalue()


class AndroidTest(unittest.TestCase):
    def setUp(self):
        for name in ('vulkan_build_ci.subprocess.Popen', 'vulkan_preflight.urllib.request.urlopen'):
            guard = patch(name, side_effect=AssertionError('Native/network denied'))
            guard.start()
            self.addCleanup(guard.stop)

    def compilation_fixture(self):
        temp = tempfile.TemporaryDirectory(dir=Path(tempfile.gettempdir()).resolve())
        self.addCleanup(temp.cleanup)
        scratch = Path(temp.name)
        vendor = ROOT / 'third_party/whisper.cpp'
        evidence = scratch / 'evidence'; evidence.mkdir()
        report = vp.Report(evidence, {}); report.data.update(ci.ACCEPTANCE)
        source = ci.generated_source(ROOT, scratch, report)
        build = scratch / 'build'; build.mkdir()
        toolchain = scratch / 'toolchain'
        generated = build / 'whisper-cancel/whisper.cpp'; generated.parent.mkdir()
        cancellation = (vendor / 'src/whisper.cpp').read_text()
        anchor = 'static bool ggml_graph_compute_helper(\n      ggml_backend_sched_t   sched,\n        struct ggml_cgraph * graph,\n                       int   n_threads,\n'
        cancellation = ci.replace_once(cancellation, anchor,
            anchor + '       ggml_abort_callback   abort_callback,\n                      void * abort_callback_data,\n')
        anchor = '        ggml_backend_reg_t reg = dev ? ggml_backend_dev_backend_reg(dev) : nullptr;\n'
        cancellation = ci.replace_once(cancellation, anchor, anchor + '\n' +
            '        auto * set_abort_callback_fn = (ggml_backend_set_abort_callback_t) ggml_backend_reg_get_proc_address(reg, "ggml_backend_set_abort_callback");\n' +
            '        if (set_abort_callback_fn) {\n            set_abort_callback_fn(backend, abort_callback, abort_callback_data);\n        }\n')
        cancellation = cancellation.replace('ggml_graph_compute_helper(sched, gf, n_threads)',
            'ggml_graph_compute_helper(sched, gf, n_threads, abort_callback, abort_callback_data)')
        cancellation = ci.replace_once(cancellation, 'ggml_graph_compute_helper(sched, gf, vctx->n_threads, false)',
            'ggml_graph_compute_helper(sched, gf, vctx->n_threads, nullptr, nullptr, false)')
        generated.write_text(cancellation)
        self.assertEqual(vp.file_identity(generated)['sha256'], ci.CANCEL_SHA)
        def elf(path, machine=183, kind=1):
            path.parent.mkdir(parents=True, exist_ok=True)
            data = bytearray(64); data[:6] = b'\x7fELF\x02\x01'
            struct.pack_into('<HH', data, 16, kind, machine); path.write_bytes(data)
        shaders = vendor / 'ggml/src/ggml-vulkan/vulkan-shaders'
        shader_root = build / 'ggml/src/ggml-vulkan'; shader_root.mkdir(parents=True)
        spv = shader_root / 'vulkan-shaders.spv'; spv.mkdir()
        sources, declarations = [], []
        for i, shader in enumerate(sorted(shaders.glob('*.comp'))):
            unit = shader_root / (shader.name + '.cpp'); sources.append(unit)
            unit.write_text(f'const uint64_t v{i}_len = 20;\nconst unsigned char v{i}_data[20] = {{0}};\n')
            declarations.append(f'extern const unsigned char v{i}_data[];\n')
            (spv / f'v{i}.spv').write_bytes(b'\x03\x02\x23\x07' + bytes(16))
        self.assertEqual(len(sources), 135)
        (shader_root / 'ggml-vulkan-shaders.hpp').write_text(''.join(declarations))
        sources += [vendor / name for name in ('ggml/src/ggml-vulkan/ggml-vulkan.cpp',
            'ggml/src/ggml.c', 'ggml/src/ggml-cpu/ggml-cpu.cpp', 'examples/common.cpp',
            'examples/common-ggml.cpp', 'examples/common-whisper.cpp', 'examples/grammar-parser.cpp')]
        sources += [source / 'cli.cpp', generated]
        report.data['connected_source'] = {str(p.relative_to(vendor)): vp.file_identity(p)
            for p in sources if p.is_relative_to(vendor)}
        rows = []
        for i, path in enumerate(sources):
            obj = build / 'objects' / f'{i}.o'; elf(obj)
            argv = [str(toolchain / 'bin/clang++'), '--target=aarch64-none-linux-android33',
                    '-march=armv8-a', '-o', str(obj.relative_to(build)), '-c', str(path)]
            rows.append(dict(file=str(path), directory=str(build), command=shlex.join(argv), output=str(obj)))
        (build / 'compile_commands.json').write_text(json.dumps(rows))
        elf(build / 'Release/vulkan-shaders-gen', 62, 3)
        for name in ('whisper', 'ggml-cpu', 'ggml-vulkan'): elf(build / ('lib' + name + '.so'), kind=3)
        cache = {'GGML_CPU':'ON', 'GGML_VULKAN':'ON', 'GGML_NATIVE':'OFF', 'GGML_CPU_ARM_ARCH':'armv8-a',
            'GGML_BACKEND_DL':'OFF', 'ANDROID_ABI':'arm64-v8a', 'ANDROID_PLATFORM':'android-33',
            'ANDROID_STL':'c++_shared', 'CMAKE_BUILD_TYPE':'Release'}
        (build / 'CMakeCache.txt').write_text(''.join(k + ':STRING=' + v + '\n' for k, v in cache.items()))
        commands = []
        for row in rows:
            args = shlex.split(row['command']); obj = args[args.index('-o') + 1]
            commands.append(shlex.join(args[:-4] + ['-MD', '-MT', obj, '-MF', obj + '.d'] + args[-4:]))
        graph = 'digraph ninja {\n' + ''.join(f'"{i}" [label="{Path(r["output"]).relative_to(build)}"]\n'
            for i, r in enumerate(rows)) + '}\n'
        inventory = {'commands':'\n'.join(commands) + '\n: && clang++ objects/0.o -o bin/whisper-cli && :\n', 'graph':graph}
        def checked(_report, argv, *_args, **_kwargs):
            if '-t' in argv: return inventory[argv[argv.index('-t') + 1]]
            name = Path(argv[-1]).name
            symbol = {'libwhisper.so':'whisper_full', 'libggml-cpu.so':'ggml_backend_cpu_reg',
                      'libggml-vulkan.so':'ggml_backend_vk_reg'}[name]
            return '00000000 T ' + symbol + '\n'
        return vendor, source, build, toolchain, report, rows, inventory, checked

    def test_top_level_ggml_layout_and_actual_compilation_caller(self):
        vendor, source, build, toolchain, report, rows, inventory, checked = self.compilation_fixture()
        with patch.object(vb, 'checked', side_effect=checked):
            ci.verify_compilation(vendor, source, build, toolchain, Path('/fixture/ninja'), report, 100)
        text = (source / 'CMakeLists.txt').read_text()
        ggml = 'add_subdirectory([=[' + str(vendor / 'ggml') + ']=] ggml)'
        whisper = 'add_subdirectory([=[' + str(vendor) + ']=] whisper)'
        self.assertIn(ggml, text)
        self.assertLess(text.index(ggml), text.index(whisper))
        self.assertEqual(len(report.data['all_objects']), len(rows))
        self.assertEqual(len(report.data['generated_spirv']), 135)

    def test_compile_evidence_reads_are_bounded_before_allocation(self):
        for name, message in (('compile_commands.json', 'Incomplete compile commands'),
                              ('CMakeCache.txt', 'Incomplete CMake cache')):
            with self.subTest(file=name):
                vendor, source, build, toolchain, report, rows, inventory, checked = self.compilation_fixture()
                selected = build / name
                reads = []
                class BoundedStream(io.BytesIO):
                    def read(self, size=-1):
                        reads.append(size)
                        self_outer.assertEqual(size, vb.LOG_LIMIT + 1)
                        return super().read(size)
                self_outer = self
                oversized = BoundedStream(b' ' * (vb.LOG_LIMIT + 1))
                original_open = Path.open
                def opened(path, *args, **kwargs):
                    if path == selected: return oversized
                    return original_open(path, *args, **kwargs)
                with patch.object(Path, 'open', opened), patch.object(vb, 'checked', side_effect=checked):
                    with self.assertRaisesRegex(ValueError, message):
                        ci.verify_compilation(vendor, source, build, toolchain, Path('/fixture/ninja'), report, 100)
                self.assertEqual(reads, [vb.LOG_LIMIT + 1])
                self.assertTrue(oversized.closed)

    def test_target_inventory_excludes_unbuilt_unrelated_configured_row(self):
        vendor, source, build, toolchain, report, rows, inventory, checked = self.compilation_fixture()
        extra = dict(rows[-1], file=str(vendor / 'src/parakeet.cpp'), output=str(build / 'objects/parakeet.o'))
        extra['command'] = shlex.join([str(toolchain / 'bin/clang++'), '--target=aarch64-none-linux-android33',
            '-o', 'objects/parakeet.o', '-c', extra['file']])
        (build / 'compile_commands.json').write_text(json.dumps(rows + [extra]))
        with patch.object(vb, 'checked', side_effect=checked):
            ci.verify_compilation(vendor, source, build, toolchain, Path('/fixture/ninja'), report, 100)
        self.assertEqual(len(report.data['all_objects']), len(rows))
        self.assertEqual(report.data['compile_inventory']['configured_unbuilt_rows'], 1)

    def test_target_inventory_rejects_missing_duplicate_foreign_extra_and_incomplete(self):
        vendor, source, build, toolchain, report, rows, inventory, checked = self.compilation_fixture()
        original = dict(inventory)
        commands = original['commands'].splitlines()
        common = next(i for i, row in enumerate(rows) if row['file'].endswith('/common-whisper.cpp'))
        cases = [
            ('duplicate command', rows, original['commands'] + commands[common] + '\n', original['graph']),
            ('missing required common command', rows, '\n'.join(commands[:common] + commands[common+1:]) + '\n', original['graph']),
            ('missing required compile row', rows[:common] + rows[common+1:], original['commands'], original['graph']),
            ('duplicate compile row', rows + [rows[common]], original['commands'], original['graph']),
            ('foreign command', rows, original['commands'] + '/foreign/clang++ -c /foreign/x.cpp -o foreign.o\n', original['graph']),
            ('extra command', rows, original['commands'] + commands[common].replace(' -march=armv8-a', ' -O0') + '\n', original['graph']),
            ('missing graph object', rows, original['commands'], original['graph'].replace(f'"{common}" [label="objects/{common}.o"]\n', '')),
            ('extra graph object', rows, original['commands'], original['graph'].replace('}\n', '"extra" [label="objects/extra.o"]\n}\n')),
            ('duplicate graph object', rows, original['commands'], original['graph'].replace('}\n', f'"extra" [label="objects/{common}.o"]\n}}\n')),
            ('foreign graph object', rows, original['commands'], original['graph'].replace(f'objects/{common}.o', '../foreign.o')),
            ('truncated graph', rows, original['commands'], original['graph'][:-2]),
            ('unframed graph', rows, original['commands'], ''),
            ('empty commands', rows, '', original['graph']),
            ('unterminated commands', rows, original['commands'].rstrip('\n'), original['graph']),
        ]
        for i, (name, changed_rows, changed_commands, changed_graph) in enumerate(cases):
            with self.subTest(case=name), patch.object(vb, 'checked', side_effect=checked):
                report.evidence = build.parent / f'case-{i}'; report.evidence.mkdir()
                (build / 'compile_commands.json').write_text(json.dumps(changed_rows))
                inventory.update(commands=changed_commands, graph=changed_graph)
                with self.assertRaises(ValueError):
                    ci.verify_compilation(vendor, source, build, toolchain, Path('/fixture/ninja'), report, 100)

    def test_actual_required_compile_boundaries_cannot_be_filtered_out(self):
        vendor, source, build, toolchain, report, rows, inventory, checked = self.compilation_fixture()
        common = next(i for i, row in enumerate(rows) if row['file'].endswith('/common-whisper.cpp'))
        foreign = build.parent / 'foreign.cpp'; foreign.write_text('foreign')
        cases = []
        for name, old, new in [('wrong API', 'android33', 'android34'),
                              ('DOTPROD', '-march=armv8-a', '-march=armv8-a+dotprod'),
                              ('i8mm', '-march=armv8-a', '-march=armv8-a+i8mm'),
                              ('extra input', ' -c ', ' ' + str(foreign) + ' -c ')]:
            changed = copy.deepcopy(rows); changed[common]['command'] = changed[common]['command'].replace(old, new)
            cases.append((name, changed))
        for name, field, replacement in [('foreign source', 'file', str(foreign)),
                ('source mismatch', 'file', rows[common-1]['file']),
                ('duplicate object', 'output', rows[common-1]['output']),
                ('foreign object', 'output', str(build.parent / 'foreign.o'))]:
            changed = copy.deepcopy(rows)
            args = shlex.split(changed[common]['command'])
            flag = '-c' if field == 'file' else '-o'
            if name != 'source mismatch': args[args.index(flag)+1] = replacement
            changed[common].update({field:replacement, 'command':shlex.join(args)})
            cases.append((name, changed))
        changed = copy.deepcopy(rows); extra = dict(rows[common], output=str(build / 'objects/extra.o'))
        args = shlex.split(extra['command']); args[args.index('-o')+1] = 'objects/extra.o'
        extra['command'] = shlex.join(args); changed.append(extra)
        cases.append(('duplicate source', changed))
        for name, ending in [('missing shader', '.comp.cpp'), ('missing CPU', '/ggml-cpu.cpp'),
                             ('missing generated Whisper', '/whisper-cancel/whisper.cpp'), ('missing CLI', '/source/cli.cpp')]:
            index = next(i for i, row in enumerate(rows) if row['file'].endswith(ending))
            cases.append((name, rows[:index] + rows[index+1:]))
        for i, (name, changed) in enumerate(cases):
            with self.subTest(case=name), patch.object(vb, 'checked', side_effect=checked):
                report.evidence = build.parent / f'boundary-{i}'; report.evidence.mkdir()
                (build / 'compile_commands.json').write_text(json.dumps(changed))
                inventory.update(commands='\n'.join(r['command'] for r in changed) + '\n',
                    graph='digraph ninja {\n' + ''.join(f'"{j}" [label="{r["output"]}"]\n' for j, r in enumerate(changed)) + '}\n')
                with self.assertRaises(ValueError):
                    ci.verify_compilation(vendor, source, build, toolchain, Path('/fixture/ninja'), report, 100)

    def test_required_objects_and_generated_source_identities_are_mandatory(self):
        for case in ('missing object', 'host object', 'cancellation drift', 'CLI drift', 'connected pin drift'):
            with self.subTest(case=case):
                vendor, source, build, toolchain, report, rows, inventory, checked = self.compilation_fixture()
                if case == 'missing object':
                    obj = Path(rows[-1]['output']); obj.rename(obj.with_suffix('.saved'))
                elif case == 'host object':
                    obj = Path(rows[-1]['output']); data = bytearray(obj.read_bytes())
                    struct.pack_into('<H', data, 18, 62); obj.write_bytes(data)
                elif case == 'cancellation drift':
                    (build / 'whisper-cancel/whisper.cpp').write_text('drift')
                elif case == 'CLI drift':
                    (source / 'cli.cpp').write_text('drift')
                else:
                    report.data['connected_source']['examples/common-whisper.cpp']['sha256'] = '0' * 64
                with patch.object(vb, 'checked', side_effect=checked), self.assertRaises((ValueError, FileNotFoundError)):
                    ci.verify_compilation(vendor, source, build, toolchain, Path('/fixture/ninja'), report, 100)

    def test_only_use_gpu_changes_and_old_guard_rejects_gpu(self):
        cpu, gpu = ci.expected_params('cpu'), ci.expected_params('gpu')
        self.assertEqual({k for k in cpu if cpu[k] != gpu[k]}, {'use_gpu'})
        self.assertEqual(cpu['max_initial_ts'], 1.0)
        self.assertEqual(cpu['strategy'], 'GREEDY')
        ci.verify_params(cpu, 'cpu')
        ci.verify_params(gpu, 'gpu')
        ci.verify_params({**gpu, 'temperature_inc': .200000003, 'entropy_thold': 2.4000001, 'no_speech_thold': .600000024}, 'gpu')
        with self.assertRaises(ValueError): ci.verify_params({**gpu, 'use_gpu': True}, 'gpu')
        with self.assertRaises(ValueError): ci.verify_params({**gpu, 'temperature_inc': .20001}, 'gpu')
        with self.assertRaises(ValueError): ci.verify_params(gpu, 'cpu')
        for arm in ('CPU', 'vulkan', '', None):
            with self.assertRaises(ValueError): ci.expected_params(arm)
        for key, value in [('strategy', 'BEAM'), ('max_initial_ts', 30), ('no_timestamps', 1),
                           ('n_threads', 8), ('prompt_bytes', 1), ('temperature_inc', 0)]:
            changed = {**cpu, key: value}
            with self.subTest(key=key), self.assertRaises(ValueError): ci.verify_params(changed, 'cpu')
        with self.assertRaises(ValueError): ci.verify_params({**cpu, 'extra': 1}, 'cpu')

    def test_command_same_binary_common_parameters_no_nt(self):
        cpu = ci.cli_command('binary', 'model', 'audio', 'ja', 'out', 'cpu')
        gpu = ci.cli_command('binary', 'model', 'audio', 'ja', 'out', 'gpu')
        self.assertEqual([x for x in cpu if x != '-ng'], gpu)
        self.assertNotIn('-nt', cpu)
        self.assertEqual(cpu[cpu.index('-bo') + 1], '5')
        with self.assertRaises(ValueError): ci.cli_command('b', 'm', 'a', 'auto', 'o', 'gpu')

    def test_pin_and_patch_anchor_drift(self):
        raw = (ROOT / 'third_party/whisper.cpp/examples/cli/cli.cpp').read_bytes()
        adapted = ci.patch_cli(raw)
        self.assertIn(b'LIP_PARAMS', adapted)
        self.assertIn(b'LIP_PCM', adapted)
        self.assertIn(b'ggml_backend_reg_count()', adapted)
        self.assertIn(b'lip_cpu_registry', adapted)
        self.assertNotIn(b'ggml_backend_dev_count() != 1', adapted)
        self.assertNotIn(b'wparams.max_initial_ts =', adapted)
        self.assertEqual(ci.unpatch_cli(adapted), raw)
        with self.assertRaises(ValueError): ci.patch_cli(raw + b'\n')
        with self.assertRaises(ValueError): ci.replace_once('anchor anchor', 'anchor', 'x')
        with self.assertRaises(ValueError): ci.replace_once('none', 'anchor', 'x')
        ci.verify_sources(ROOT)

    def test_receipts_must_be_unique_match_arm_and_full_pcm(self):
        params = 'LIP_PARAMS ' + json.dumps(ci.expected_params('gpu'))
        pcm = 'LIP_PCM ' + json.dumps(dict(samples=5, full_pcm=1, signed_pcm16_div32768=1))
        log = params + '\n' + pcm + '\n'
        ci.verify_receipts(log, 'gpu', 5)
        for value in (log + params, log.replace('"samples": 5', '"samples": 4'),
                      log.replace('"use_gpu": 1', '"use_gpu": 0'), log.replace('"full_pcm": 1', '"full_pcm": true'),
                      params + '\n', log.replace('"strategy": "GREEDY"', '"strategy": "BEAM"')):
            with self.assertRaises(ValueError): ci.verify_receipts(value, 'gpu', 5)

    def test_host_denied_before_tools_and_workflow_is_separate(self):
        with patch.dict(ci.os.environ, {}, clear=True), patch.object(ci, 'read_context', side_effect=AssertionError('No tools')):
            with self.assertRaises(ValueError): ci.main('admit')
        workflow = (ROOT / '.github/workflows/vulkan-android-bench.yml').read_text()
        for value in (ci.REF, ci.MARKER, 'persist-credentials: false', 'submodules: recursive',
                      'steps.build.outcome == \'success\'', '${{ steps.admit.outputs.package }}'):
            self.assertIn(value, workflow)
        self.assertNotIn('workflow_dispatch', workflow)
        self.assertNotIn('vulkan_build_ci.py build', workflow)

    def test_admission_entrypoint_retains_only_bounded_controlled_failure(self):
        import ast
        from contextlib import redirect_stderr
        from types import SimpleNamespace
        tree = ast.parse((ROOT / 'eval/vulkan_android_ci.py').read_text())
        entry = compile(ast.Module(body=[tree.body[-1]], type_ignores=[]), '<actual entrypoint>', 'exec')
        for error in (vp.DiagnosticError('Required host\nidentity'), vp.DiagnosticError('x' * 1024),
                      RuntimeError('private unexpected error')):
            def denied(*_args): raise error
            untouched = SimpleNamespace(setrlimit=lambda *_: self.fail('Resources changed before admission'))
            output = io.StringIO()
            with redirect_stderr(output), self.assertRaises(SystemExit) as exited:
                exec(entry, dict(__name__='__main__', require_host=denied, os=ci.os, platform=ci.platform,
                                 resource=untouched, sys=ci.sys, vp=vp))
            self.assertEqual(exited.exception.code, 1)
            line = output.getvalue()
            expected = 'Android benchmark preparation held: ' + type(error).__name__
            if isinstance(error, vp.DiagnosticError): expected += ': ' + ' '.join(str(error).split())[:512]
            self.assertEqual(line, expected + '\n')
            self.assertNotIn('private unexpected error', line)

    def test_parent_soft_limit_permits_qualified_native_child_limit(self):
        import ast
        from contextlib import redirect_stderr
        tree = ast.parse((ROOT / 'eval/vulkan_android_ci.py').read_text())
        entry = compile(ast.Module(body=[tree.body[-1]], type_ignores=[]), '<actual entrypoint>', 'exec')
        limits, calls = {}, []
        def setrlimit(key, pair):
            if key in limits and pair[1] > limits[key][1]:
                raise ValueError('Cannot raise inherited hard limit')
            limits[key] = pair
            calls.append((key, pair))
        def child_launch():
            vb.limits(1)
            return 0
        output = io.StringIO()
        with patch.object(ci.resource, 'setrlimit', side_effect=setrlimit), redirect_stderr(output):
            with self.assertRaises(SystemExit) as exited:
                exec(entry, dict(__name__='__main__', require_host=lambda *_: None, os=ci.os,
                     platform=ci.platform, resource=ci.resource, sys=ci.sys, vp=vp, vb=vb, main=child_launch))
        self.assertEqual(exited.exception.code, 0, output.getvalue())
        self.assertEqual(calls[0], (ci.resource.RLIMIT_AS, (2 * 1024**3, vb.AS_LIMIT)))
        self.assertEqual(calls[1], (ci.resource.RLIMIT_AS, (vb.AS_LIMIT, vb.AS_LIMIT)))

    def test_full_pcm_exact_float_equivalence_and_source_hash(self):
        data = wav()
        values = struct.pack('<5f', -1, -1 / 32768, 0, 1 / 32768, 32767 / 32768)
        sha = hashlib.sha256(data).hexdigest()
        self.assertEqual(ci.verify_pcm(data, values, sha, 5)['samples'], 5)
        for raw, decoded, digest, count in [(data[:-2], values, sha, 5), (data, values[:-4], sha, 5),
                (data, values, '0' * 64, 5), (data, values, sha, 4),
                (data, struct.pack('<5f', -1, 0, 0, 1 / 32768, 32767 / 32768), sha, 5)]:
            with self.assertRaises(ValueError): ci.verify_pcm(raw, decoded, digest, count)

    def test_enumeration_is_never_execution_and_acceptance_stays_false(self):
        with self.assertRaises(ValueError): ci.require_q5_witness('Found Vulkan GPU Q5 pipeline')
        ci.require_q5_witness('Vulkan Timings:\nMUL_MAT_VEC q5_0 m=512 n=1 k=512: 2 x 10 us = 20 us\nTotal time: 20 us.\n')
        ci.require_q5_witness('Vulkan Timings:\nMUL_MAT q5_1 m=512 n=1 k=512: 2 x 1e+06 us = 2e+06 us\nTotal time: 2e+06 us.\n')
        for text in ('MUL_MAT q5_0 m=1 n=1 k=1: 0 x 0 us = 0 us',
                     'Vulkan Timings:\nMUL_MAT f32 m=1 n=1 k=1: 1 x 2 us = 2 us',
                     'Vulkan Timings:\nMUL_MAT q5_0 m=0 n=1 k=1: 1 x 2 us = 2 us',
                     'Vulkan Timings:\nMUL_MAT q5_0 m=1 n=1 k=1: 1 x 0 us = 0 us'):
            with self.assertRaises(ValueError): ci.require_q5_witness(text + '\nTotal time: 2 us.\n')
        ci.require_false_acceptance(ci.ACCEPTANCE)
        for key in ci.ACCEPTANCE:
            with self.assertRaises(ValueError): ci.require_false_acceptance({**ci.ACCEPTANCE, key: True})

    def test_q5_witness_scans_each_completed_explicit_timing_block(self):
        f16 = '----------------\nVulkan Timings:\nMUL_MAT f16 m=512 n=1 k=512: 1 x 5 us = 5 us\nTotal time: 5 us.\n'
        q5 = 'MUL_MAT_VEC q5_0 m=512 n=1 k=512: 2 x 10 us = 20 us\n'
        ci.require_q5_witness(f16 + '----------------\nVulkan Timings:\n' + q5 + 'Total time: 20 us.\n')
        ci.require_q5_witness('Vulkan Timings:\n' + q5 + 'Vulkan Timings:\n' + q5 + 'Total time: 20 us.\n')
        for text in (f16 + q5, 'Found Vulkan Q5 pipeline\n' + q5,
                     f16 + 'Vulkan Timings:\n' + q5,
                     'prefix Vulkan Timings:\n' + q5 + 'Total time: 20 us.\n',
                     'Vulkan Timings:\n' + q5 + 'Total time: nan us.\n',
                     f16 + 'Vulkan Timings:\n' + q5.replace('10 us = 20', '1e999 us = 1e999') + 'Total time: 20 us.\n',
                     'Vulkan Timings:\n' + q5 + 'Vulkan Timings:\nTotal time: 20 us.\n',
                     f16 + 'Vulkan Timings:\n' + q5.replace('m=512', 'm=0') + 'Total time: 20 us.\n',
                     f16 + 'Vulkan Timings:\n' + q5.replace('10 us = 20', '0 us = 0') + 'Total time: 20 us.\n'):
            with self.subTest(text=text), self.assertRaises(ValueError): ci.require_q5_witness(text)

    def test_config_cpu_on_no_arch_experiment(self):
        args = list(map(str, ci.configure_command(*[Path('/fixture/' + x) for x in
            ('cmake', 'ninja', 'source', 'build', 'ndk', 'hpp', 'spirv', 'package', 'glslc')])))
        self.assertIn('-DGGML_CPU=ON', args)
        self.assertIn('-DGGML_VULKAN=ON', args)
        self.assertIn('-DANDROID_PLATFORM=android-33', args)
        self.assertIn('-DGGML_CPU_ARM_ARCH=armv8-a', args)
        self.assertNotIn('-DGGML_CPU=OFF', args)
        self.assertIn('-DCMAKE_EXE_LINKER_FLAGS=-Wl,--no-undefined,-z,max-page-size=16384', args)

    def test_elf_alignment_interpreter_and_host_rejection(self):
        with tempfile.TemporaryDirectory(dir=Path(tempfile.gettempdir()).resolve()) as tmp:
            path = Path(tmp) / 'binary'
            def elf(machine=183, align=16384, interp=b'/system/bin/linker64\0'):
                hdr = struct.pack('<16sHHIQQQIHHHHHH', b'\x7fELF\x02\x01\x01' + b'\0' * 9,
                    3, machine, 1, 0, 64, 0, 0, 64, 56, 2, 0, 0, 0)
                return hdr + struct.pack('<IIQQQQQQ', 1, 5, 0, 0, 0, 200, 200, align) + \
                    struct.pack('<IIQQQQQQ', 3, 4, 176, 0, 0, len(interp), len(interp), 1) + interp + b'\0' * 32
            path.write_bytes(elf())
            ci.verify_elf(path, executable=True)
            for data in (elf(machine=62), elf(align=4096), elf(interp=b'/lib/ld-linux.so\0'), b'not ELF'):
                path.write_bytes(data)
                with self.assertRaises(ValueError): ci.verify_elf(path, executable=True)

    def test_closure_missing_cpu_system_stubs_and_hosttools_denied(self):
        libs = {name: {'needed': [], 'kind': 'built'} for name in
                ('libwhisper.so.1', 'libggml.so', 'libggml-base.so', 'libggml-cpu.so', 'libggml-vulkan.so')}
        libs['libwhisper.so.1']['needed'] = list(libs)[1:] + ['libc++_shared.so', 'libm.so', 'libvulkan.so']
        libs['libc++_shared.so'] = {'needed': ['libc.so'], 'kind': 'NDK runtime'}
        ci.verify_closure(['libwhisper.so.1'], libs)
        for name in ('libggml-cpu.so', 'libc++_shared.so'):
            changed = copy.deepcopy(libs); changed.pop(name)
            with self.assertRaises(ValueError): ci.verify_closure(['libwhisper.so.1'], changed)
        changed = copy.deepcopy(libs); changed['libvulkan.so'] = {'needed': [], 'kind': 'API33 link stub'}
        with self.assertRaises(ValueError): ci.verify_closure(['libwhisper.so.1'], changed)
        with self.assertRaises(ValueError): ci.package_name('../audio.wav')
        with self.assertRaises(ValueError): ci.package_name('glslc')
        with self.assertRaises(ValueError): ci.package_name('model.bin')

    def test_generated_source_and_package_complete_readback_fixture(self):
        import zipfile
        with tempfile.TemporaryDirectory(dir=Path(tempfile.gettempdir()).resolve()) as tmp:
            scratch = Path(tmp); evidence = scratch / 'evidence'; evidence.mkdir()
            report = vp.Report(evidence, {}); report.data.update(ci.ACCEPTANCE)
            source = ci.generated_source(ROOT, scratch, report)
            self.assertEqual(ci.unpatch_cli((source / 'cli.cpp').read_bytes()),
                             (ROOT / 'third_party/whisper.cpp/examples/cli/cli.cpp').read_bytes())
            self.assertEqual(sorted(p.name for p in source.iterdir()), ['CMakeLists.txt', 'cli.cpp'])
            self.assertIn('include([=[', (source / 'CMakeLists.txt').read_text())
            build = scratch / 'build'; (build / 'bin').mkdir(parents=True)
            toolchain = scratch / 'toolchain'; runtime = toolchain / 'sysroot/usr/lib/aarch64-linux-android'
            (runtime / '33').mkdir(parents=True)
            def elf(executable=False):
                interp = b'/system/bin/linker64\0'
                header = struct.pack('<16sHHIQQQIHHHHHH', b'\x7fELF\x02\x01\x01' + b'\0' * 9,
                    3, 183, 1, 0, 64, 0, 0, 64, 56, 2 if executable else 1, 0, 0, 0)
                load = struct.pack('<IIQQQQQQ', 1, 5, 0, 0, 0, 200, 200, 16384)
                return header + load + (struct.pack('<IIQQQQQQ', 3, 4, 176, 0, 0, len(interp), len(interp), 1) + interp if executable else b'') + b'\0' * 100
            binary = build / 'bin/whisper-cli'; binary.write_bytes(elf(True)); binary.chmod(0o755)
            needed = {'whisper-cli':['libwhisper.so.1'], 'libwhisper.so.1':['libggml.so.0', 'libc++_shared.so'],
                'libggml.so.0':['libggml-base.so.0', 'libggml-cpu.so.0', 'libggml-vulkan.so.0'],
                'libggml-base.so.0':[], 'libggml-cpu.so.0':[], 'libggml-vulkan.so.0':['libvulkan.so'],
                'libc++_shared.so':['libc.so']}
            for name in needed:
                if name != 'whisper-cli':
                    path = (runtime if name == 'libc++_shared.so' else build / 'bin') / name
                    path.write_bytes(elf())
            for name in ('libvulkan.so', 'libc.so'): (runtime / '33' / name).write_bytes(elf())
            for name in ('whisper-license.txt', 'Vulkan-Hpp-license.txt', 'SPIRV-Headers-license.txt',
                         'ndk-notices.txt', 'ndk-toolchain-notices.txt'): (evidence / name).write_text('fixture notice ' + name)
            def readelf(report, argv, *args, **kwargs):
                name = Path(argv[-1]).name
                return (' (SONAME) Library soname: [' + name + ']\n' if name != 'whisper-cli' else '') + \
                    ''.join(' (NEEDED) Shared library: [' + n + ']\n' for n in needed[name])
            with patch.object(ci.vb, 'checked', side_effect=readelf):
                ci.package(build, toolchain, scratch, report, 100)
            with zipfile.ZipFile(scratch / 'vulkan-android-bench.zip') as archive:
                self.assertIsNone(archive.testzip())
                self.assertEqual(set(archive.namelist()), set(needed) | {'NOTICES.txt', 'manifest.json'})
                manifest = json.loads(archive.read('manifest.json'))
                ci.require_false_acceptance(manifest)
                self.assertEqual(set(manifest['system_dependencies_not_packaged']), {'libc.so', 'libvulkan.so'})
                for name, identity in manifest['files'].items():
                    self.assertEqual(hashlib.sha256(archive.read(name)).hexdigest(), identity['sha256'])
                self.assertEqual(archive.getinfo('whisper-cli').external_attr >> 16 & 0o777, 0o755)
            real_stat = Path.stat
            for size in (128 * 1024**2 - 1, 128 * 1024**2, 128 * 1024**2 + 1, 129 * 1024**2, 256 * 1024**2):
                destination = scratch / str(size); destination.mkdir()
                target = destination / 'vulkan-android-bench.zip'
                def stat(path, *args, **kwargs):
                    observed = real_stat(path, *args, **kwargs)
                    if path == target:
                        values = list(observed); values[6] = size
                        return ci.os.stat_result(values)
                    return observed
                with self.subTest(zip_bytes=size), patch.object(ci.vb, 'checked', side_effect=readelf), patch.object(Path, 'stat', stat):
                    if size <= 128 * 1024**2:
                        ci.package(build, toolchain, destination, report, 100)
                    else:
                        with self.assertRaisesRegex(ValueError, '128 MiB'):
                            ci.package(build, toolchain, destination, report, 100)
                self.assertLess(real_stat(target).st_size, 16384)

    def test_exact_optin_not_old_admission(self):
        from test_vulkan_build_ci import context
        args = context()
        args[0].update(ci.ENVIRONMENT)
        args[1]['ref'] = ci.REF
        args[1]['head_commit']['message'] = args[3] = 'prepare [vulkan-android-bench]'
        self.assertEqual(ci.require_context(*args)['ref'], ci.REF)
        with self.assertRaises(ValueError): vb.require_context(*args)
        for key, value in [('GITHUB_REF', vb.REF), ('RUNNER_ENVIRONMENT', 'self-hosted'),
                           ('GITHUB_WORKFLOW_REF', 'other'), ('GITHUB_EVENT_NAME', 'workflow_dispatch')]:
            bad = copy.deepcopy(args); bad[0][key] = value
            with self.assertRaises(ValueError): ci.require_context(*bad)
        bad = copy.deepcopy(args); bad[3] = '[VULKAN-ANDROID-BENCH]'
        with self.assertRaises(ValueError): ci.require_context(*bad)


if __name__ == '__main__': unittest.main()
