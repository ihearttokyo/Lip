"""Pure fixtures; no recognition, model acquisition, native launch or app acceptance."""
import copy
import hashlib
from pathlib import Path
import struct
import tempfile
from contextlib import ExitStack, contextmanager
import unittest
from unittest.mock import patch

import normalization_ci as ci
import test_quant_ci as legacy

ROOT = Path(__file__).resolve().parent.parent


def wave(samples, tag=1, channels=1, rate=16000, extra=b''):
    bits = 16 if tag == 1 else 32
    fmt = struct.pack('<HHIIHH', tag, channels, rate, rate * channels * bits // 8, channels * bits // 8, bits)
    payload = struct.pack('<' + ('h' if tag == 1 else 'f') * len(samples), *samples)
    chunks = b'fmt ' + struct.pack('<I', len(fmt)) + fmt + extra + b'data' + struct.pack('<I', len(payload)) + payload
    return b'RIFF' + struct.pack('<I', len(chunks) + 4) + b'WAVE' + chunks


def context():
    env, event, head, _ = legacy.context()
    message = 'diagnostic ' + ci.MARKER
    env.update(GITHUB_REF=ci.REF, GITHUB_WORKFLOW_REF=ci.WORKFLOW_REF)
    event.update(ref=ci.REF); event['head_commit']['message'] = message
    return env, event, head, message


class NormalizationTest(unittest.TestCase):
    def setUp(self):
        for name in ('urllib.request.urlopen', 'subprocess.Popen', 'subprocess.check_output', 'os.execve', 'ctypes.CDLL'):
            guard = patch(name, side_effect=AssertionError('No network or native access in fixtures'))
            guard.start(); self.addCleanup(guard.stop)

    def test_pcm16_full_wave_negative_peak_quantization_and_accounting(self):
        source = wave([-32768, 32767, 3, 1, 0, -1])
        derived, record = ci.normalize_wave(source)
        self.assertEqual(list(ci.decode_wave(derived)[1]), [-16384, 16384, 2, 0, 0, 0])
        self.assertEqual(record['gain'], .5)
        self.assertEqual((record['peak_before'], record['peak_after']), (1., .5))
        self.assertEqual((record['sample_count'], record['zero_before'], record['zero_after'], record['round_to_zero']), (6, 1, 3, 2))
        self.assertEqual(record['clipped_samples'], 0)
        self.assertEqual(record['source_sha256'], hashlib.sha256(source).hexdigest())
        self.assertEqual(record['derived_sha256'], hashlib.sha256(derived).hexdigest())
        self.assertEqual(list(ci.ts.pcm_samples(derived)), [v / 32768 for v in [-16384, 16384, 2, 0, 0, 0]])

    def test_float32_preserves_encoding_every_sample_and_metadata(self):
        extra = b'JUNK\x03\0\0\0abc\0'
        source = wave([-.25, .125, 0., -0., .00001], 3, extra=extra)
        derived, record = ci.normalize_wave(source)
        self.assertEqual(record['gain'], 2.)
        self.assertEqual(record['format']['encoding'], 'ieee_float32')
        self.assertEqual(derived[:44 + len(extra)], source[:44 + len(extra)])
        self.assertEqual(list(ci.ts.pcm_samples(derived)), [-.5, .25, 0., -0., struct.unpack('<f', struct.pack('<f', .00002))[0]])
        self.assertEqual(record['frame_count'], 5)

    def test_exact_zero_bytes_identity_including_negative_float_zero(self):
        for source in (wave([0, 0]), wave([0., -0.], 3)):
            derived, record = ci.normalize_wave(source)
            self.assertEqual(derived, source)
            self.assertEqual(record['gain'], 1.)
            self.assertTrue(record['exact_zero_identity'])
            self.assertEqual(record['nonzero_after'], 0)

    def test_multichannel_peak_is_global_no_frame_reorder_or_format_change(self):
        source = wave([100, -400, 200, 0], channels=2, rate=48000)
        derived, record = ci.normalize_wave(source)
        self.assertEqual(list(ci.decode_wave(derived)[1]), [4096, -16384, 8192, 0])
        self.assertEqual(record['frame_count'], 2)
        self.assertEqual(record['format']['channels'], 2)
        self.assertEqual(record['format']['sample_rate'], 48000)
        self.assertEqual(derived[:44], source[:44])

    def test_malformed_unsupported_nonfinite_and_out_of_range_fail_closed(self):
        good = wave([1, 2])
        invalid = [good[:-1], good + b'x', b'RF64' + good[4:], good[:12], wave([], 1),
                   wave([float('nan')], 3), wave([float('inf')], 3), wave([1.01], 3),
                   wave([-1.01], 3), wave([1], channels=2), wave([1], rate=0),
                   good[:20] + struct.pack('<H', 6) + good[22:],
                   good[:32] + struct.pack('<H', 4) + good[34:]]
        for source in invalid:
            with self.subTest(source=source[:28]), self.assertRaises(ValueError): ci.normalize_wave(source)
        for extra in (b'fmt \x10\0\0\0' + good[20:36], b'data\x02\0\0\0\0\0',
                      b'fact\x04\0\0\0\x09\0\0\0', b'PEAK\x04\0\0\0\0\0\0\0',
                      b'LIST\x04\0\0\0wavl'):
            with self.assertRaises(ValueError): ci.normalize_wave(wave([1, 2], extra=extra))

    def test_unknown_chunks_fact_extensions_and_very_small_float_are_preserved(self):
        extra = b'LIST\x08\0\0\0INFOtest' + b'fact\x04\0\0\0\x02\0\0\0'
        source = wave([1e-40, -1e-40], 3, extra=extra)
        derived, record = ci.normalize_wave(source)
        self.assertEqual(list(ci.decode_wave(derived)[1]), [.5, -.5])
        self.assertEqual(record['zero_before'], 0)
        self.assertEqual(record['zero_after'], 0)
        self.assertEqual(derived[:44 + len(extra)], source[:44 + len(extra)])
        extended = source[:16] + struct.pack('<I', 18) + source[20:36] + b'\0\0' + source[36:]
        extended = extended[:4] + struct.pack('<I', len(extended) - 8) + extended[8:]
        self.assertEqual(ci.normalize_wave(extended)[1]['format'], record['format'])
        for changed in (source[:-1], source[:4] + b'\0\0\0\0' + source[8:],
                        source[:28] + struct.pack('<I', 9999) + source[32:]):
            with self.assertRaises(ValueError): ci.normalize_wave(changed)

    def test_budget_enforces_both_limits_and_reserves_cleanup(self):
        wall = ci.ABSOLUTE_DEADLINE_EPOCH - 700
        ci.require_budget(1000, 600, monotonic=100, wall=wall)
        for deadline, mono, utc in ((729, 100, wall), (1000, 100, ci.ABSOLUTE_DEADLINE_EPOCH - 629),
                                    (float('inf'), 0, wall), (1000, 100, float('nan'))):
            with self.assertRaises((TimeoutError, ValueError)): ci.require_budget(deadline, 600, monotonic=mono, wall=utc)
        self.assertEqual(ci.ABSOLUTE_DEADLINE, '2026-10-09T23:48:00Z')

    def test_whole_build_budget_includes_source_copy_and_restores_alarm_handler(self):
        before_handler = ci.signal.getsignal(ci.signal.SIGALRM)
        before_alarm = ci.signal.getitimer(ci.signal.ITIMER_REAL)
        self.assertEqual(before_alarm, (0., 0.))
        def expires(*args):
            remaining, interval = ci.signal.getitimer(ci.signal.ITIMER_REAL)
            self.assertGreater(remaining, 899.)
            self.assertEqual(interval, 0.)
            ci.signal.getsignal(ci.signal.SIGALRM)(ci.signal.SIGALRM, None)
        with patch.object(ci.time, 'time', return_value=ci.ABSOLUTE_DEADLINE_EPOCH - 3600), \
             patch.object(ci.ts, 'build_cli', side_effect=expires) as inherited:
            with self.assertRaises(TimeoutError): ci.build_cli(ROOT, Path('/fixture'), Path('/fixture'), {}, {}, {}, ci.time.monotonic() + 3600)
            inherited.assert_called_once()
        self.assertIs(ci.signal.getsignal(ci.signal.SIGALRM), before_handler)
        self.assertEqual(ci.signal.getitimer(ci.signal.ITIMER_REAL), before_alarm)
        with patch.object(ci.signal, 'getitimer', return_value=(3., 0.)), patch.object(ci.ts, 'build_cli') as inherited:
            with self.assertRaises(ValueError): ci.build_cli(ROOT, Path('/fixture'), Path('/fixture'), {}, {}, {}, ci.time.monotonic() + 3600)
            inherited.assert_not_called()

    def test_phase_timer_restores_custom_handler_and_rejects_existing_timers(self):
        before = ci.signal.getsignal(ci.signal.SIGALRM)
        previous = lambda *_: None
        ci.signal.signal(ci.signal.SIGALRM, previous)
        try:
            with patch.object(ci.time, 'time', return_value=ci.ABSOLUTE_DEADLINE_EPOCH - 3600):
                for error in (None, ValueError('fixture failure')):
                    try:
                        with ci.phase_timer(ci.time.monotonic() + 3600, ci.time.monotonic() + 60):
                            self.assertGreater(ci.signal.getitimer(ci.signal.ITIMER_REAL)[0], 59)
                            if error is not None: raise error
                    except ValueError as caught: self.assertIs(caught, error)
                    self.assertIs(ci.signal.getsignal(ci.signal.SIGALRM), previous)
                    self.assertEqual(ci.signal.getitimer(ci.signal.ITIMER_REAL), (0., 0.))
                for existing in ((3., 0.), (0., 3.)):
                    with patch.object(ci.signal, 'getitimer', return_value=existing), \
                         patch.object(ci.signal, 'setitimer') as timer, \
                         self.assertRaisesRegex(ValueError, 'existing operation alarm'):
                        with ci.phase_timer(ci.time.monotonic() + 3600, ci.time.monotonic() + 60):
                            self.fail('Existing timer admitted')
                    timer.assert_not_called()
                    self.assertIs(ci.signal.getsignal(ci.signal.SIGALRM), previous)
        finally:
            ci.signal.signal(ci.signal.SIGALRM, before)

    def test_whole_build_return_rechecks_elapsed_and_cleanup(self):
        for elapsed, jump in ((901, 0), (1, 3570), (1, 3600)):
            clock = [0, 0]
            def build(*args): clock[:] = [elapsed, jump]; return Path('/fixture'), {}
            with self.subTest(elapsed=elapsed, wall_jump=jump), \
                 patch.object(ci.time, 'monotonic', side_effect=lambda: 1000 + clock[0]), \
                 patch.object(ci.time, 'time', side_effect=lambda: ci.ABSOLUTE_DEADLINE_EPOCH - 3600 + sum(clock)), \
                 patch.object(ci.ts, 'build_cli', side_effect=build), self.assertRaises(TimeoutError):
                ci.build_cli(ROOT, Path('/fixture'), Path('/fixture'), {}, {}, {}, 4600)
            self.assertEqual(ci.signal.getitimer(ci.signal.ITIMER_REAL), (0., 0.))

    def test_exact_normalization_admission_and_legacy_identity_are_distinct(self):
        env, event, head, message = context()
        ci.require_environment(env, event, head, message, 'linux', 1001, '24.04')
        for key, value in [('GITHUB_REF', ci.q.REF), ('GITHUB_WORKFLOW_REF', ci.q.WORKFLOW_REF),
                           ('GITHUB_EVENT_NAME', 'pull_request'), ('RUNNER_ARCH', 'ARM64'), ('RUNNER_ENVIRONMENT', 'self-hosted')]:
            with self.assertRaises(ValueError): ci.require_environment(dict(env, **{key: value}), event, head, message, 'linux', 1001, '24.04')
        for bad in ('[PEAK-NORMALIZATION-DIAGNOSTIC]', '[q4-quality-canary]', '[peak-normalization-diagnostic-extra]'):
            altered = copy.deepcopy(event); altered['head_commit']['message'] = bad
            with self.assertRaises(ValueError): ci.require_environment(env, altered, head, bad, 'linux', 1001, '24.04')
        with patch.object(ci.sys, 'platform', 'darwin'), patch.object(ci.ts, 'git') as git:
            with self.assertRaises(ValueError): ci.preflight(ROOT)
            with self.assertRaises(ValueError): ci.infer_worker([])
            git.assert_not_called()

    def test_frozen_sources_q5_only_and_legacy_schedule(self):
        pins = ci.ts.load_json(ROOT / 'eval/normalization-pins.json')
        initial = ci.verify_sources(ROOT, pins)
        self.assertEqual(pins['model'], initial['model'])
        self.assertNotIn('models', pins)
        cases = ci.q.frozen_cases(ROOT, initial)
        schedule = ci.q.paired_schedule(cases, cases, normalization=True)
        self.assertEqual(len(schedule), 72)
        for index in range(36):
            pair = schedule[index * 2:index * 2 + 2]
            self.assertEqual(pair[0][0], pair[1][0])
            self.assertEqual([arm for _, arm in pair], list(ci.ARMS if index % 2 == 0 else ci.ARMS[::-1]))
        self.assertEqual([arm for _, arm in ci.q.paired_schedule(cases, cases)[:2]], ['q5_0', 'q4_0'])
        for mutate in (lambda p: p.update(model=ci.q.Q4), lambda p: p['source_sha256'].pop('eval/quant_ci.py'),
                       lambda p: p['source_sha256'].update({'eval/initial_ts_ci.py': '0' * 64}), lambda p: p.update(target_peak=.9)):
            changed = copy.deepcopy(pins); mutate(changed)
            with self.assertRaises(ValueError): ci.verify_sources(ROOT, changed)

    def test_completion_strict_controls_and_regressions_without_acceptance_claim(self):
        cases = legacy.frozen()
        rows = [{'id': case['id'], 'arm': arm, 'origin': case['origin'], 'status': 'ok',
                 'raw': case['reference_raw'], 'segments': [], 'score': ci.ts.score_case(case, case['reference_raw'])}
                for case, arm in ci.q.paired_schedule(cases, cases, normalization=True)]
        report = ci.completion(rows, cases)
        self.assertTrue(report['runtime_success'])
        self.assertEqual(report['acceptance'], 'not_assessed')
        self.assertTrue(all(value is False for value in report['acceptance_flags'].values()))
        for changed in (rows[:-1], rows[::-1], [dict(rows[0], status='timeout')] + rows[1:]):
            self.assertFalse(ci.completion(changed, cases)['runtime_success'])
        candidate = next(row for row in rows if row['id'] == 'noise-en' and row['arm'] == 'normalized')
        candidate['segments'] = [{'text': ' '}]
        report = ci.completion(rows, cases)
        self.assertFalse(report['candidate_controls_exact_empty'])
        self.assertFalse(report['screen_eligible_for_independent_review'])
        self.assertTrue(next(pair for pair in report['pairs'] if pair['id'] == 'noise-en')['lost_strict_pass'])

    def test_normalized_regression_summary_keeps_lost_anchors_passes_and_repeats(self):
        cases = legacy.frozen()
        rows = [{'id': case['id'], 'arm': arm, 'origin': case['origin'], 'status': 'ok',
                 'raw': case['reference_raw'], 'segments': [], 'score': ci.ts.score_case(case, case['reference_raw'])}
                for case, arm in ci.q.paired_schedule(cases, cases, normalization=True)]
        case = next(case for case in cases if case['id'] == 'fleurs-en-013')
        row = next(row for row in rows if row['id'] == case['id'] and row['arm'] == 'normalized')
        raw = case['reference_raw'].replace('none', 'all')
        row.update(raw=raw, score=ci.ts.score_case(case, raw), segments=[{'text': 'No.'}, {'text': 'No.'}])
        report = ci.completion(rows, cases)
        pair = next(pair for pair in report['pairs'] if pair['id'] == case['id'])
        self.assertIn('negation', pair['new_failed_anchors'])
        self.assertTrue(pair['lost_strict_pass'])
        self.assertTrue(pair['duplicate_segment_increase'])
        self.assertGreater(pair['errors_normalized'], pair['errors_baseline'])
        self.assertFalse(report['measured_nonregression_passed'])
        self.assertFalse(report['screen_eligible_for_independent_review'])
        self.assertEqual(set(report['summary']), {'baseline', 'normalized'})
        self.assertEqual(len(report['acceptance_flags']), 5)

    def test_fixed_worker_script_is_reused_with_separate_labels_and_no_q4(self):
        pin = ci.ts.load_json(ROOT / 'eval/initial-ts-pins.json')['model']
        case = {'id': 'fixture', 'language': 'en', 'origin': 'fixture', 'sha256': 'a' * 64,
                'reference_raw': 'No.', 'max_error_rate': .05, 'checks': []}
        with tempfile.TemporaryDirectory() as temp:
            work = Path(temp); evidence = work / 'evidence'; evidence.mkdir()
            def failure(command, env, log, timeout, byte_limit):
                self.assertEqual(command[2], str(ROOT / 'eval/normalization_ci.py'))
                self.assertEqual(env['LIP_INITIAL_TS'], '1.0')
                self.assertEqual(env['LIP_QUANT_ARM'], 'q5_0')
                self.assertEqual(env['LIP_NORMALIZATION_ARM'], 'normalized')
                raise ci.q.subprocess.CalledProcessError(66, command)
            with patch.object(ci.ts, 'run_logged', side_effect=failure):
                result = ci.q.run_native(case, 'q5_0', work / 'audio', work / 'bin', work / pin['name'], work,
                    evidence, {'LIP_NORMALIZATION_ARM': 'normalized'}, pin, worker_script=ROOT / 'eval/normalization_ci.py')
            self.assertEqual(result['arm'], 'normalized')
            self.assertEqual(result['status'], 'engine_error')
            self.assertTrue((evidence / 'fixture-normalized-score.json').exists())
            with self.assertRaises(ValueError): ci.q.run_native(case, 'q5_0', work / 'audio', work / 'bin', work / pin['name'], work,
                evidence, {}, pin, worker_script=ROOT / 'eval/initial_ts_ci.py')

    @contextmanager
    def hosted_boundaries(self, runner, *, drift=None):
        # Simulate only unavailable OS/Git identity probes; production admission/source/audio validators stay live.
        env, event, head, message = context()
        event_path = runner / 'event.json'; event_path.write_bytes(ci.ts.encode(event))
        env.update(GITHUB_EVENT_PATH=str(event_path), RUNNER_TEMP=str(runner))
        def git(repository, *args):
            if args == ('rev-parse', 'HEAD'):
                return ci.ts.RUNTIME_REVISION if repository.name == 'whisper.cpp' else head
            if args[:1] == ('log',): return message
            if args[:1] == ('status',):
                return ' M third_party/whisper.cpp' if drift == 'dirty' else ''
            if args[:1] == ('ls-tree',):
                return '160000 commit ' + ('b' * 40 if drift == 'gitlink' else ci.ts.RUNTIME_REVISION) + '\tthird_party/whisper.cpp'
            raise AssertionError(args)
        read_text = Path.read_text
        def host_text(path, *args, **kwargs):
            if str(path) == '/etc/os-release': return 'ID=ubuntu\nVERSION_ID="24.04"\n'
            if str(path) == '/proc/meminfo': return 'MemAvailable: 10485760 kB\n'
            return read_text(path, *args, **kwargs)
        with ExitStack() as stack:
            for guard in (patch.dict(ci.os.environ, env), patch.object(ci.sys, 'platform', 'linux'),
                          patch.object(ci.os, 'geteuid', return_value=ci.os.getuid()),
                          patch.object(ci.ts, 'git', side_effect=git), patch.object(ci.Path, 'read_text', host_text),
                          patch.object(ci.os, 'cpu_count', return_value=4),
                          patch.object(ci.os, 'sched_getaffinity', return_value={0, 1, 2, 3}, create=True),
                          patch.object(ci.shutil, 'disk_usage', return_value=type('Disk', (), {'free': 6 * ci.ts.GIB})()),
                          patch.object(ci.time, 'time', return_value=ci.ABSOLUTE_DEADLINE_EPOCH - 3600)):
                stack.enter_context(guard)
            yield env

    def test_actual_preflight_source_scope_gitlink_and_worker_model_failure(self):
        with tempfile.TemporaryDirectory() as temp:
            runner = Path(temp).resolve()
            with self.hosted_boundaries(runner):
                pins, initial, cases, _, _ = ci.preflight(ROOT)
                self.assertEqual(len(cases), 36)
                self.assertEqual(pins['model']['bytes'], 574041195)
                work, evidence = ci.owned_directories(runner)
                controls = work / 'controls'; controls.mkdir(); ci.ts.prepare_controls(controls)
                case = next(case for case in cases if case['id'] == 'silence-en')
                roots = {case['id']: controls}
                entries = ci.prepare_normalized([case], roots, work, evidence)
                self.assertTrue(entries[case['id']]['exact_zero_identity'])
                binary = work / 'build/bin/whisper-cli'; binary.parent.mkdir(parents=True); binary.write_bytes(b'not executable')
                model = work / pins['model']['name']; model.write_bytes(b'not a model; must fail the real pin')
                timing = Path('/usr/bin/time').resolve()
                bindings = {'identity': {k: ci.os.environ[k] for k in ci.q.IDENTITY_KEYS},
                    'absolute_deadline': ci.ABSOLUTE_DEADLINE, 'monotonic_deadline': ci.time.monotonic() + 3600,
                    'binary': ci.ts.file_identity(binary, 1024), 'time': ci.ts.file_identity(timing, 128 * 1024 * 1024),
                    'model': ci.ts.file_identity(model, 1024), 'normalization': entries}
                binding_path = work / 'worker-bindings.json'; binding_path.write_bytes(ci.ts.encode(bindings))
                for arm in ci.ARMS:
                    stem = work / (case['id'] + '-' + arm) / 'transcript'; stem.parent.mkdir()
                    audio = controls / case['audio'] if arm == 'baseline' else work / 'normalized' / (case['id'] + '.wav')
                    args = [str(binary), str(model), str(audio), case['language'], str(stem)]
                    with patch.dict(ci.os.environ, LIP_NORMALIZATION_ARM=arm, LIP_QUANT_ARM='q5_0', LIP_INITIAL_TS='1.0'):
                        with self.assertRaisesRegex(ValueError, 'Complete Q5 artifact witness'):
                            ci.validate_worker(args, ROOT)
                        arbitrary_time = copy.deepcopy(bindings); arbitrary_time['time'] = bindings['binary']
                        binding_path.write_bytes(ci.ts.encode(arbitrary_time))
                        with self.assertRaisesRegex(ValueError, 'Fixed installed timing tool'):
                            ci.validate_worker(args, ROOT)
                        binding_path.write_bytes(ci.ts.encode(bindings))
                        stem.with_suffix('.txt').write_bytes(b'preexisting evidence')
                        with self.assertRaisesRegex(ValueError, 'Fresh native output'):
                            ci.validate_worker(args, ROOT)
                        stem.with_suffix('.txt').unlink()
                        for index, value in ((0, '/arbitrary/bin'), (1, '/arbitrary/model'), (2, '/arbitrary/audio'),
                                             (3, 'fr'), (4, '/arbitrary/output')):
                            changed = list(args); changed[index] = value
                            with self.assertRaises(ValueError): ci.validate_worker(changed, ROOT)
                        for key, value in (('LIP_INITIAL_TS', '30.0'), ('LIP_QUANT_ARM', 'q4_0'), ('LIP_NORMALIZATION_ARM', 'q4_0')):
                            with patch.dict(ci.os.environ, **{key: value}), self.assertRaises(ValueError): ci.validate_worker(args, ROOT)
                        with patch.object(ci.time, 'time', return_value=ci.ABSOLUTE_DEADLINE_EPOCH - 149), self.assertRaises(TimeoutError):
                            ci.validate_worker(args, ROOT)
                self.assertLess(ci.ts.evidence_size(evidence), 32 * 1024 * 1024)
                with self.assertRaises(FileExistsError): ci.owned_directories(runner)
        for drift in ('dirty', 'gitlink'):
            with tempfile.TemporaryDirectory() as temp, self.hosted_boundaries(Path(temp).resolve(), drift=drift), self.assertRaises(ValueError):
                ci.preflight(ROOT)

    def test_actual_main_budget_stop_retains_incomplete_receipt_without_native_or_acquisition(self):
        with tempfile.TemporaryDirectory() as temp:
            runner = Path(temp).resolve()
            with self.hosted_boundaries(runner), patch.object(ci.time, 'time', return_value=ci.ABSOLUTE_DEADLINE_EPOCH - 60), \
                 patch.object(ci.sys, 'argv', ['normalization_ci.py']), patch.object(ci.ts, 'freeze_tools') as tools, patch('builtins.print'):
                self.assertEqual(ci.main(), 1)
                tools.assert_not_called()
            report = ci.ts.load_json(runner / ci.EVIDENCE_NAME / 'completion.json')
            self.assertEqual(report['error_type'], 'TimeoutError')
            self.assertEqual(report['phase'], 'freeze_installed_tools')
            self.assertEqual(report['results_count'], 0)
            self.assertFalse(report['runtime_success'])
            self.assertTrue(all(value is False for value in report['acceptance_flags'].values()))

    def main_phase_report(self, runner, clock, *, tools=None, download=None, identity=None, corpus=None, wall_seconds=3600):
        # Only unavailable tool/build/download boundaries are simulated; main admission and receipts stay live.
        before_handler = ci.signal.getsignal(ci.signal.SIGALRM)
        before_alarm = ci.signal.getitimer(ci.signal.ITIMER_REAL)
        with self.hosted_boundaries(runner), ExitStack() as stack:
            for guard in (patch.object(ci.time, 'monotonic', side_effect=lambda: 1000 + clock[0]),
                          patch.object(ci.time, 'time', side_effect=lambda: ci.ABSOLUTE_DEADLINE_EPOCH - wall_seconds + clock[0] + (clock[1] if len(clock) > 1 else 0)),
                          patch.object(ci.sys, 'argv', ['normalization_ci.py']), patch('builtins.print'),
                          patch.object(ci.ts, 'freeze_tools', side_effect=tools or (lambda: {})),
                          patch.object(ci, 'build_cli', return_value=(runner / 'fixture-binary', {})),
                          patch.object(ci.ts, 'download', side_effect=download or AssertionError('download boundary denied')),
                          patch.object(ci.ts, 'acquire_corpus', side_effect=corpus or AssertionError('corpus boundary denied'))):
                stack.enter_context(guard)
            if identity is not None: stack.enter_context(patch.object(ci.ts, 'file_identity', side_effect=identity))
            self.assertEqual(ci.main(), 1)
        self.assertIs(ci.signal.getsignal(ci.signal.SIGALRM), before_handler)
        self.assertEqual(ci.signal.getitimer(ci.signal.ITIMER_REAL), before_alarm)
        report = ci.ts.load_json(runner / ci.EVIDENCE_NAME / 'completion.json')
        self.assertEqual(report['status'], 'incomplete_diagnostic')
        self.assertEqual(report['results_count'], 0)
        self.assertFalse(report['runtime_success'])
        self.assertTrue(all(value is False for value in report['acceptance_flags'].values()))
        return report

    def test_actual_main_tool_phase_has_whole_timer_and_retains_timeout(self):
        observed = []
        def tools():
            observed.append(ci.signal.getitimer(ci.signal.ITIMER_REAL))
            if observed[-1][0] > 0: ci.signal.getsignal(ci.signal.SIGALRM)(ci.signal.SIGALRM, None)
            return {}
        with tempfile.TemporaryDirectory() as temp:
            report = self.main_phase_report(Path(temp).resolve(), [0], tools=tools)
        self.assertGreater(observed[0][0], 59)
        self.assertEqual(observed[0][1], 0)
        self.assertEqual((report['phase'], report['error_type']), ('freeze_installed_tools', 'TimeoutError'))
        self.assertNotIn('tools', report)

    def test_actual_main_tool_return_rechecks_absolute_deadline_and_cleanup(self):
        for elapsed in (71, 110):
            clock = [0]
            def tools(): clock[0] = elapsed; return {}
            with self.subTest(elapsed=elapsed), tempfile.TemporaryDirectory() as temp:
                report = self.main_phase_report(Path(temp).resolve(), clock, tools=tools, wall_seconds=100)
                self.assertEqual((report['phase'], report['error_type']), ('freeze_installed_tools', 'TimeoutError'))
                self.assertNotIn('tools', report)

    def test_actual_main_tool_return_reserves_cleanup_after_wall_only_jump(self):
        for jump in (61, 100):
            clock = [0, 0]
            def tools(): clock[:] = [10, jump]; return {}
            with self.subTest(wall_jump=jump), tempfile.TemporaryDirectory() as temp:
                report = self.main_phase_report(Path(temp).resolve(), clock, tools=tools, wall_seconds=100)
                self.assertEqual((report['phase'], report['error_type']), ('freeze_installed_tools', 'TimeoutError'))
                self.assertNotIn('tools', report)

    def test_actual_main_tool_timer_covers_protected_probes_and_full_readbacks(self):
        clock, probes, readbacks = [0], [], []
        freeze_tools, real_identity = ci.ts.freeze_tools, ci.ts.file_identity
        def probe(command, **kwargs):
            probes.append(ci.signal.getitimer(ci.signal.ITIMER_REAL))
            self.assertEqual(kwargs['timeout'], 10)
            clock[0] += 9
            return b'fixture version; never executed\n'
        def identity(path, cap):
            readbacks.append(ci.signal.getitimer(ci.signal.ITIMER_REAL))
            result = real_identity(path, cap); clock[0] += 2
            if clock[0] > 60 and readbacks[-1][0] > 0:
                ci.signal.getsignal(ci.signal.SIGALRM)(ci.signal.SIGALRM, None)
            return result
        with tempfile.TemporaryDirectory() as temp:
            runner = Path(temp).resolve(); installed = {}
            for name in ('cmake', 'ninja', 'gcc-13', 'g++-13'):
                path = runner / name; path.write_bytes(b'fixture tool; never executed'); installed[name] = str(path)
            with patch.object(ci.ts.shutil, 'which', side_effect=installed.get), \
                 patch.object(ci.ts.subprocess, 'check_output', side_effect=probe):
                report = self.main_phase_report(runner, clock, tools=freeze_tools, identity=identity)
        self.assertEqual((len(probes), len(readbacks)), (6, 6))
        self.assertTrue(all(59 < seconds <= 60 and interval == 0 for seconds, interval in probes + readbacks))
        self.assertEqual((report['phase'], report['error_type']), ('freeze_installed_tools', 'TimeoutError'))
        self.assertNotIn('tools', report)

    def test_actual_main_q5_readback_uses_only_remaining_acquisition_timer(self):
        clock, observed = [0], []
        real_identity = ci.ts.file_identity
        def download(pin, path):
            self.assertEqual(ci.signal.getitimer(ci.signal.ITIMER_REAL), (0., 0.))
            path.write_bytes(b'fixture bytes, never a model')
            clock[0] = 599
        def identity(path, cap):
            observed.append(ci.signal.getitimer(ci.signal.ITIMER_REAL))
            result = real_identity(path, cap)
            clock[0] += 3
            if observed[-1][0] > 0: ci.signal.getsignal(ci.signal.SIGALRM)(ci.signal.SIGALRM, None)
            return result
        with tempfile.TemporaryDirectory() as temp:
            report = self.main_phase_report(Path(temp).resolve(), clock, download=download, identity=identity)
        self.assertGreater(observed[0][0], 0)
        self.assertLessEqual(observed[0][0], 1)
        self.assertEqual(observed[0][1], 0)
        self.assertEqual((report['phase'], report['error_type']), ('one_complete_Q5_acquisition', 'TimeoutError'))
        self.assertNotIn('model', report)

    def test_actual_main_q5_readback_return_rechecks_total_elapsed(self):
        clock = [0]
        real_identity = ci.ts.file_identity
        def download(pin, path): path.write_bytes(b'fixture bytes, never a model'); clock[0] = 599
        def identity(path, cap):
            result = real_identity(path, cap); clock[0] += 3
            return result
        with tempfile.TemporaryDirectory() as temp:
            report = self.main_phase_report(Path(temp).resolve(), clock, download=download, identity=identity)
        self.assertEqual((report['phase'], report['error_type']), ('one_complete_Q5_acquisition', 'TimeoutError'))
        self.assertNotIn('model', report)

    def test_actual_main_q5_timer_setup_cannot_extend_the_acquisition_deadline(self):
        clock = [0]
        real_alarm = ci.signal.getitimer
        real_identity = ci.ts.file_identity
        def download(pin, path): path.write_bytes(b'fixture bytes, never a model'); clock[0] = 599
        def alarm(which):
            if clock[0] == 599: clock[0] += 2
            return real_alarm(which)
        with tempfile.TemporaryDirectory() as temp, \
             patch.object(ci.signal, 'getitimer', side_effect=alarm), \
             patch.object(ci.ts, 'file_identity', wraps=real_identity) as readback:
            report = self.main_phase_report(Path(temp).resolve(), clock, download=download)
            readback.assert_not_called()
        self.assertEqual((report['phase'], report['error_type']), ('one_complete_Q5_acquisition', 'TimeoutError'))

    def test_actual_main_q5_exhausted_transfer_never_starts_readback(self):
        for elapsed in (600, 601):
            clock = [0]
            def download(pin, path): path.write_bytes(b'fixture bytes, never a model'); clock[0] = elapsed
            with self.subTest(elapsed=elapsed), tempfile.TemporaryDirectory() as temp, \
                 patch.object(ci.ts, 'file_identity') as readback:
                report = self.main_phase_report(Path(temp).resolve(), clock, download=download)
                readback.assert_not_called()
                self.assertEqual((report['phase'], report['error_type']), ('one_complete_Q5_acquisition', 'TimeoutError'))

    def test_actual_main_corpus_return_rechecks_elapsed_and_cleanup(self):
        pin = ci.ts.load_json(ROOT / 'eval/normalization-pins.json')['model']
        # Simulate the unavailable upstream model witness only; no path reaches normalization or inference.
        def identity(path, cap): return {'path': str(path), 'bytes': pin['bytes'], 'sha256': pin['sha256']}
        for elapsed, jump in ((601, 0), (1, 3570), (1, 3600)):
            clock = [0, 0]
            def corpus(*args): clock[:] = [elapsed, jump]; return ROOT / 'eval/corpus.json'
            with self.subTest(elapsed=elapsed, wall_jump=jump), tempfile.TemporaryDirectory() as temp:
                report = self.main_phase_report(Path(temp).resolve(), clock, download=lambda *_: None,
                                                identity=identity, corpus=corpus)
                self.assertEqual((report['phase'], report['error_type']), ('complete_public_corpus_acquisition', 'TimeoutError'))

    def test_every_workflow_executed_fixture_suite_is_source_pinned(self):
        text = (ROOT / '.github/workflows/normalization-experiment.yml').read_text()
        executed = {'eval/' + name for name in ci.re.findall(r"'(test_[a-z_]+\.py)'", text)}
        self.assertEqual(executed, {'eval/test_initial_ts_ci.py', 'eval/test_quant_ci.py', 'eval/test_normalization_ci.py'})
        pins = ci.ts.load_json(ROOT / 'eval/normalization-pins.json')
        self.assertLessEqual(executed, ci.SOURCE_PATHS)
        self.assertLessEqual(executed, pins['source_sha256'].keys())
        self.assertEqual(pins['source_sha256']['eval/test_initial_ts_ci.py'],
                         '1059cc41c91ef5fad5b99a5a04f6128b4c5c2b1276b5456f4698afd5bbb1a785')
        for name in executed:
            self.assertEqual(pins['source_sha256'][name], hashlib.sha256((ROOT / name).read_bytes()).hexdigest())

    def test_full_manifest_counts_readback_and_ownership_links(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp).resolve(); work, evidence = ci.owned_directories(root)
            original = work / 'original'; original.mkdir(); (original / 'audio').mkdir()
            cases = []
            for index, source in enumerate((wave([1, -2, 0]), wave([.25, -.125], 3), wave([0, 0]))):
                case_id = 'fixture-' + str(index); path = original / 'audio' / (case_id + '.wav'); path.write_bytes(source)
                cases.append({'id': case_id, 'audio': 'audio/' + path.name, 'size_bytes': len(source), 'sha256': hashlib.sha256(source).hexdigest()})
            entries = ci.prepare_normalized(cases, {case['id']: original for case in cases}, work, evidence)
            self.assertEqual(len(entries), 3)
            for case in cases:
                entry = entries[case['id']]; path = work / 'normalized' / entry['audio']
                self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), entry['derived_sha256'])
                self.assertEqual(entry['nonzero_before'] + entry['zero_before'], entry['sample_count'])
                self.assertEqual(entry['nonzero_after'] + entry['zero_after'], entry['sample_count'])
                self.assertEqual(ci.owned_path(path, work), path)
            link = work / 'link.wav'; link.symlink_to(path)
            with self.assertRaises(ValueError): ci.owned_path(link, work)

    def test_workflow_exact_branch_marker_deadline_and_text_only(self):
        text = (ROOT / '.github/workflows/normalization-experiment.yml').read_text()
        self.assertIn(ci.WORKFLOW_REF, text)
        self.assertIn("grep -F '" + ci.MARKER + "'", text)
        self.assertIn('persist-credentials: false', text)
        self.assertIn('timeout-minutes: 180', text)
        self.assertIn('${{ runner.temp }}/lip-normalization-evidence/', text)
        self.assertNotIn('workflow_dispatch', text)
        self.assertNotIn('secrets.', text)
        self.assertNotIn('sudo', text)
        self.assertNotIn('q4-quality-canary', text)
        for line in text.splitlines():
            if 'uses:' in line: self.assertRegex(line, r'@[0-9a-f]{40}')


if __name__ == '__main__':
    unittest.main()
