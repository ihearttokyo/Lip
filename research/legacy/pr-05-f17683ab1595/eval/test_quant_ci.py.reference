"""Pure source-contract fixtures, never inference or quality evidence."""
import copy
import hashlib
import os
import subprocess
import tempfile
import json
from pathlib import Path
import unittest
from unittest.mock import patch

import quant_ci as ci

ROOT = Path(__file__).resolve().parent.parent


def context():
    sha = 'a' * 40
    env = {'GITHUB_ACTIONS': 'true', 'RUNNER_OS': 'Linux', 'RUNNER_ARCH': 'X64',
           'RUNNER_ENVIRONMENT': 'github-hosted', 'ImageOS': 'ubuntu24',
           'GITHUB_REPOSITORY': 'ihearttokyo/Lip', 'GITHUB_REPOSITORY_OWNER': 'ihearttokyo',
           'GITHUB_EVENT_NAME': 'push', 'GITHUB_REF': ci.REF, 'GITHUB_SHA': sha,
           'GITHUB_SERVER_URL': 'https://github.com', 'GITHUB_RUN_ID': '123',
           'GITHUB_RUN_ATTEMPT': '1', 'GITHUB_WORKFLOW_REF': ci.WORKFLOW_REF}
    message = 'research [q4-quality-canary]'
    event = {'repository': {'full_name': 'ihearttokyo/Lip', 'private': False,
                           'visibility': 'public', 'owner': {'login': 'ihearttokyo'}},
             'ref': ci.REF, 'after': sha, 'head_commit': {'id': sha, 'message': message}}
    return env, event, sha, message


def frozen():
    pins = json.loads((ROOT / 'eval/initial-ts-pins.json').read_bytes())
    return (json.loads((ROOT / 'eval/corpus.json').read_bytes())['cases'] + pins['quiet_cases'] +
            json.loads((ROOT / 'eval/controls.json').read_bytes())['cases'])


class QuantTest(unittest.TestCase):
    def setUp(self):
        for target in ('urllib.request.urlopen', 'subprocess.Popen', 'subprocess.check_output', 'os.execve', 'ctypes.CDLL'):
            guard = patch(target, side_effect=AssertionError('Network/unmocked launch denied in pure fixtures'))
            guard.start(); self.addCleanup(guard.stop)

    def test_exact_quant_context_is_admitted_without_rewriting_actual_ref(self):
        env, event, sha, message = context()
        ci.require_environment(env, event, sha, message, 'linux', 1001, '24.04')
        self.assertEqual(env['GITHUB_REF'], 'refs/heads/codex/lip-q4-quality')

    def test_all_frozen_pairs_use_artifact_arms_adjacent_alternating(self):
        cases = frozen()
        schedule = ci.paired_schedule(cases, cases)
        self.assertEqual(len(schedule), 72)
        for i in range(36):
            pair = schedule[2*i:2*i+2]
            self.assertEqual(pair[0][0], pair[1][0])
            self.assertEqual([arm for _, arm in pair], ['q5_0', 'q4_0'] if i % 2 == 0 else ['q4_0', 'q5_0'])
        for mutation in (cases[:-1], cases + [cases[0]], [dict(cases[0], max_error_rate=1)] + cases[1:]):
            with self.assertRaises(ValueError): ci.paired_schedule(mutation, cases)

    def test_wrong_marker_identity_platform_and_prior_experiment_are_denied(self):
        env, event, sha, message = context()
        for text in ('[Q4-QUALITY-CANARY]', '[initial-ts-canary]', '[qwen17-canary]', '[q4-quality-canary-extra]', 'ordinary'):
            changed = copy.deepcopy(event); changed['head_commit']['message'] = text
            with self.subTest(text=text), self.assertRaises(ValueError):
                ci.require_environment(env, changed, sha, text, 'linux', 1001, '24.04')
        for key, value in [('GITHUB_SHA', 'b' * 40), ('GITHUB_REF', ci.ts.REF),
                           ('GITHUB_WORKFLOW_REF', ci.ts.WORKFLOW_REF), ('GITHUB_EVENT_NAME', 'pull_request'),
                           ('GITHUB_REPOSITORY', 'fork/Lip'), ('GITHUB_REPOSITORY_OWNER', 'fork'),
                           ('GITHUB_ACTIONS', 'false'), ('RUNNER_OS', 'macOS'), ('RUNNER_ARCH', 'ARM64'),
                           ('RUNNER_ENVIRONMENT', 'self-hosted'), ('ImageOS', 'ubuntu22'),
                           ('GITHUB_SERVER_URL', 'https://other'), ('GITHUB_RUN_ID', '0'), ('GITHUB_RUN_ATTEMPT', '')]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                ci.require_environment(dict(env, **{key: value}), event, sha, message, 'linux', 1001, '24.04')
        for platform, uid, release in [('darwin', 1001, '24.04'), ('linux', 0, '24.04'), ('linux', 1001, '22.04')]:
            with self.subTest(platform=platform), self.assertRaises(ValueError):
                ci.require_environment(env, event, sha, message, platform, uid, release)
        for mutate in [lambda e: e['repository'].update(private=True), lambda e: e['repository'].update(visibility='private'),
                       lambda e: e['repository']['owner'].update(login='fork'), lambda e: e.update(ref=ci.ts.REF),
                       lambda e: e.update(after='b' * 40), lambda e: e['head_commit'].update(id='b' * 40)]:
            changed = copy.deepcopy(event); mutate(changed)
            with self.assertRaises(ValueError): ci.require_environment(env, changed, sha, message, 'linux', 1001, '24.04')
        with self.assertRaises(ValueError): ci.require_environment(env, event, sha, 'other ' + message, 'linux', 1001, '24.04')

    def test_local_preflight_and_worker_deny_before_tools_git_or_paths(self):
        with patch.object(ci.sys, 'platform', 'darwin'), patch.object(ci.ts, 'git') as git, \
             patch.object(ci.ts, 'freeze_tools') as tools, patch.object(ci, 'owned_directories') as allocate:
            with self.assertRaises(ValueError): ci.main()
            with self.assertRaises(ValueError): ci.infer_worker([])
            git.assert_not_called(); tools.assert_not_called(); allocate.assert_not_called()

    def test_fixed_two_model_and_captured_source_pins_fail_closed(self):
        pins = ci.ts.load_json(ROOT / 'eval/quant-pins.json')
        initial = ci.verify_sources(ROOT, pins)
        self.assertEqual(pins['models']['q5_0'], initial['model'])
        self.assertEqual(pins['models']['q4_0'], ci.Q4)
        self.assertEqual(sum(p['bytes'] for p in pins['models'].values()), 1048033430)
        for mutate in (lambda p: p['models'].pop('q4_0'), lambda p: p['models']['q4_0'].update(sha256='0' * 64),
                       lambda p: p['models']['q5_0'].update(bytes=1), lambda p: p['models']['q4_0'].update(revision='main'),
                       lambda p: p['source_sha256'].update({'eval/initial_ts_ci.py': '0' * 64}),
                       lambda p: p.update(runtime_revision='0' * 40), lambda p: p['source_sha256'].pop('NOTICE.md')):
            changed = copy.deepcopy(pins); mutate(changed)
            with self.assertRaises(ValueError): ci.verify_sources(ROOT, changed)
        self.assertEqual(ci.frozen_cases(ROOT, initial), frozen())
        self.assertEqual(pins['prior_evidence']['historical_q4']['historical_per_clip_regressions'], ['fleurs-zh-020'])
        self.assertEqual(pins['prior_evidence']['initial_ts_regressions'], ['fleurs-ja-010-minus42db-pause-before'])
        self.assertFalse(pins['prior_evidence']['historical_q4']['latency_pass'])
        self.assertIn('not certified', pins['provenance']['q4_0'])

    def test_fresh_owned_roots_outputs_and_completion_reserve(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); work, evidence = ci.owned_directories(root)
            self.assertEqual(work.name, 'lip-quant-work'); self.assertEqual(evidence.name, 'lip-quant-evidence')
            with self.assertRaises(FileExistsError): ci.owned_directories(root)
            text = work / 'fixture.txt'; text.write_bytes(b'abc')
            self.assertEqual(ci.read_output(text, 3), b'abc')
            with self.assertRaises(ValueError): ci.read_output(text, 2)
            link = work / 'link.txt'; link.symlink_to(text)
            with self.assertRaises(ValueError): ci.read_output(link)
            with self.assertRaises(ValueError): ci.read_output(work)
            with patch.object(ci.ts, 'ARTIFACT_BYTES', ci.ts.OUTPUT_BYTES + 10):
                with self.assertRaises(ValueError): ci.ts.retain(evidence / 'large.txt', b'x' * 11)
                ci.ts.save(evidence / 'completion.json', {'status': 'incomplete_diagnostic'})
                self.assertLessEqual(ci.ts.evidence_size(evidence), ci.ts.OUTPUT_BYTES + 10)

    def results(self):
        cases = frozen()
        rows = [{'id': c['id'], 'arm': arm, 'origin': c['origin'], 'status': 'ok',
                 'raw': c['reference_raw'], 'segments': [], 'score': ci.ts.score_case(c, c['reference_raw'])}
                for c, arm in ci.paired_schedule(cases, cases)]
        return cases, rows

    def replace_raw(self, rows, case, arm, raw, segments=None):
        row = next(r for r in rows if r['id'] == case['id'] and r['arm'] == arm)
        row.update(raw=raw, score=ci.ts.score_case(case, raw), segments=segments or [])
        return row

    def test_runtime_counts_order_and_score_consistency_are_not_acceptance(self):
        cases, rows = self.results(); report = ci.completion(rows, cases)
        self.assertTrue(report['runtime_success']); self.assertTrue(report['quality_passed'])
        self.assertEqual(report['acceptance'], 'not_assessed')
        self.assertFalse(report['screen_eligible_for_independent_review'])  # No strict quiet improvement.
        self.assertIn('not_assessed', report['semantic_fact_and_repetition_review'])
        for altered in (rows[:-1], rows[::-1], [dict(rows[0], status='timeout')] + rows[1:],
                        rows[:-1] + [rows[0]], [dict(rows[0], arm='1.0')] + rows[1:]):
            result = ci.completion(altered, cases)
            self.assertFalse(result['runtime_success']); self.assertFalse(result['quality_passed'])
            self.assertIsNone(result['measured_nonregression_passed'])
        changed = copy.deepcopy(rows); changed[0]['score']['passed'] = False
        with self.assertRaises(ValueError): ci.completion(changed, cases)

    def test_controls_require_raw_empty_and_segments_empty_independently(self):
        cases, rows = self.results(); control = next(c for c in cases if c['id'] == 'noise-en')
        for raw, segments in (('.', []), ('', [{'text': ' '}]), ('', None), (' ', [])):
            changed = copy.deepcopy(rows)
            row = self.replace_raw(changed, control, 'q4_0', raw)
            row['segments'] = segments
            result = ci.completion(changed, cases)
            self.assertTrue(result['runtime_success'])
            self.assertFalse(result['quality_passed']); self.assertFalse(result['candidate_controls_exact_empty'])
            self.assertFalse(result['screen_eligible_for_independent_review'])

    def test_known_zh020_regression_is_not_hidden_by_aggregate_passes(self):
        cases, rows = self.results(); case = next(c for c in cases if c['id'] == 'fleurs-zh-020')
        text = '他在2000年制作的第一千张邮票取材于大卫克罗克艾伦斯特尔的名画《瑞典国王的伟大功绩》并因此入选吉尼斯世界纪录大权'
        base = self.replace_raw(rows, case, 'q5_0', text)
        candidate = self.replace_raw(rows, case, 'q4_0', text + '啊')
        self.assertEqual(base['score']['raw']['errors'], 9)
        self.assertEqual(candidate['score']['raw']['errors'], 10)
        report = ci.completion(rows, cases)
        self.assertEqual(report['summary']['q5_0']['original']['strict_passes'], report['summary']['q4_0']['original']['strict_passes'])
        self.assertFalse(report['measured_nonregression_passed'])
        pair = next(p for p in report['pairs'] if p['id'] == case['id'])
        self.assertTrue(pair['historical_regression_case']); self.assertTrue(pair['increased_errors'])
        self.assertEqual((pair['errors_q5'], pair['errors_q4']), (9, 10))
        self.assertFalse(report['screen_eligible_for_independent_review'])

    def test_anchor_loss_and_duplicate_segments_require_review(self):
        cases, rows = self.results(); case = next(c for c in cases if c['id'] == 'fleurs-en-013')
        self.replace_raw(rows, case, 'q4_0', case['reference_raw'].replace('none', 'all'))
        report = ci.completion(rows, cases); pair = next(p for p in report['pairs'] if p['id'] == case['id'])
        self.assertIn('negation', pair['new_failed_anchors']); self.assertFalse(report['measured_nonregression_passed'])
        cases, rows = self.results()
        row = next(r for r in rows if r['id'] == case['id'] and r['arm'] == 'q4_0')
        row['segments'] = [{'text': ' No.'}, {'text': ' No.'}]
        report = ci.completion(rows, cases); pair = next(p for p in report['pairs'] if p['id'] == case['id'])
        self.assertTrue(pair['duplicate_segment_increase']); self.assertFalse(report['measured_nonregression_passed'])

    def test_quiet_improvement_requires_all_candidate_quality_and_controls(self):
        cases, rows = self.results(); case = next(c for c in cases if '-minus' in c['id'])
        self.replace_raw(rows, case, 'q5_0', 'incorrect')
        report = ci.completion(rows, cases)
        self.assertTrue(report['quiet_strict_passes_increased'])
        self.assertTrue(report['candidate_quality_passed']); self.assertTrue(report['candidate_controls_exact_empty'])
        self.assertTrue(report['screen_eligible_for_independent_review']); self.assertFalse(report['quality_passed'])
        self.assertEqual(report['acceptance'], 'not_assessed')
        other = next(c for c in cases if c['id'] == 'fleurs-en-000')
        self.replace_raw(rows, other, 'q5_0', 'incorrect'); self.replace_raw(rows, other, 'q4_0', 'incorrect')
        self.assertFalse(ci.completion(rows, cases)['screen_eligible_for_independent_review'])

    def native_fixture(self, command, env, log, timeout, byte_limit):
        self.assertEqual(env['LIP_INITIAL_TS'], '1.0')
        self.assertEqual(env['GITHUB_REF'], ci.REF)
        self.assertEqual(timeout, 120); self.assertEqual(byte_limit, 32 * 1024)
        self.assertEqual(command[3], '--infer-worker')
        model, language, stem = command[5], command[7], Path(command[8])
        segment = {'text': ' No. No.', 'offsets': {'from': 0, 'to': 1000},
                   'timestamps': {'from': '00:00:00,000', 'to': '00:00:01,000'}}
        stem.with_suffix('.txt').write_bytes(b'No. No.\n')
        stem.with_suffix('.json').write_bytes(ci.ts.encode({'params': {'model': model, 'language': language, 'translate': False},
            'result': {'language': language}, 'transcription': [segment]}))
        (stem.parent / 'peak-kib.txt').write_text('4096\n')
        log.write(b'LIP_PARAMS ' + ci.ts.encode(ci.ts.expected_params('1.0')).replace(b'\n', b' ') + b'\n')

    def test_native_fixture_records_fixed_params_unfiltered_failure_and_exact_model(self):
        case = {'id': 'fixture', 'language': 'en', 'origin': 'fixture', 'sha256': 'a' * 64,
                'reference_raw': 'No.', 'max_error_rate': .05, 'checks': []}
        pins = ci.ts.load_json(ROOT / 'eval/quant-pins.json')
        for arm in ci.ARMS:
            with self.subTest(arm=arm), tempfile.TemporaryDirectory() as temp:
                root = Path(temp); evidence = root / 'evidence'; evidence.mkdir(); pin = pins['models'][arm]
                with patch.object(ci.ts, 'run_logged', side_effect=self.native_fixture):
                    row = ci.run_native(case, arm, root / 'audio', root / 'binary', root / pin['name'], root, evidence,
                                        {'GITHUB_REF': ci.REF}, pin)
                self.assertEqual(row['status'], 'ok'); self.assertEqual(row['raw'], 'No. No.')
                self.assertFalse(row['score']['passed']); self.assertEqual(row['actual_params']['max_initial_ts'], 1.0)
                self.assertEqual(row['actual_params']['best_of'], 5); self.assertEqual(row['peak_rss_bytes'], 4194304)
                self.assertEqual(row['model']['sha256'], pin['sha256'])
                self.assertEqual((evidence / ('fixture-' + arm + '-raw.txt')).read_bytes(), b'No. No.\n')
                self.assertEqual(row['segments'][0]['text'], ' No. No.')
                self.assertIn('not_collected', row['decoder_trace'])

    def test_native_timeout_join_failure_engine_error_and_invalid_params_are_retained(self):
        case = frozen()[0]; pin = ci.ts.load_json(ROOT / 'eval/quant-pins.json')['models']['q5_0']
        def drift(*args, **kwargs):
            self.native_fixture(*args, **kwargs)
            log = args[2]; log.seek(0); log.truncate()
            log.write(b'LIP_PARAMS ' + ci.ts.encode(dict(ci.ts.expected_params('1.0'), max_initial_ts=30.0)).replace(b'\n', b' ') + b'\n')
        for failure, status in ((subprocess.TimeoutExpired('fixture', 120), 'timeout'),
                                (subprocess.TimeoutExpired('fixture', 5), 'cleanup_timeout'),
                                (subprocess.CalledProcessError(7, 'fixture'), 'engine_error'),
                                (drift, 'invalid_input_or_output')):
            with self.subTest(status=status), tempfile.TemporaryDirectory() as temp:
                root = Path(temp); evidence = root / 'evidence'; evidence.mkdir()
                with patch.object(ci.ts, 'run_logged', side_effect=failure):
                    if status == 'cleanup_timeout':
                        with self.assertRaises(RuntimeError):
                            ci.run_native(case, 'q5_0', root / 'audio', root / 'bin', root / pin['name'], root, evidence,
                                          {'GITHUB_REF': ci.REF}, pin)
                    else:
                        row = ci.run_native(case, 'q5_0', root / 'audio', root / 'bin', root / pin['name'], root, evidence,
                                            {'GITHUB_REF': ci.REF}, pin)
                        self.assertEqual(row['status'], status)
                saved = ci.ts.load_json(evidence / (case['id'] + '-q5_0-score.json'))
                self.assertEqual(saved['status'], status); self.assertNotIn('score', saved)
                self.assertTrue((evidence / (case['id'] + '-q5_0.log')).is_file())

    def test_worker_requires_quant_arm_fixed_timestamp_and_paths_even_after_admission(self):
        pins = ci.ts.load_json(ROOT / 'eval/quant-pins.json'); cases = frozen()
        with tempfile.TemporaryDirectory() as temp:
            runner = Path(temp).resolve(); root = runner / ci.WORK_NAME; root.mkdir()
            admission = (pins, {}, cases, runner, {})
            case = cases[0]; expected = [str(root / 'build/bin/whisper-cli'), str(root / pins['models']['q5_0']['name']),
                str(root / 'original' / case['audio']), case['language'], str(root / (case['id'] + '-q5_0') / 'transcript')]
            with patch.object(ci, 'preflight', return_value=admission):
                for arm, stamp in [('q5_0', '30.0'), ('q5_0', '1.00'), ('1.0', '1.0'), ('q8_0', '1.0')]:
                    with patch.dict(ci.os.environ, LIP_QUANT_ARM=arm, LIP_INITIAL_TS=stamp), self.assertRaises(ValueError):
                        ci.infer_worker(expected)
                with patch.dict(ci.os.environ, LIP_QUANT_ARM='q5_0', LIP_INITIAL_TS='1.0'):
                    for i, value in [(0, '/arbitrary/bin'), (1, '/arbitrary/model'), (2, '/arbitrary/audio'), (3, 'fr'), (4, '/arbitrary/output')]:
                        changed = list(expected); changed[i] = value
                        with self.subTest(index=i), self.assertRaises(ValueError): ci.infer_worker(changed)

    def test_workflow_is_exact_opt_in_pinned_unprivileged_and_text_only(self):
        text = (ROOT / '.github/workflows/quant-experiment.yml').read_text()
        self.assertNotIn('workflow_dispatch', text); self.assertNotIn('pull_request:', text)
        self.assertNotIn('sudo', text); self.assertNotIn('setup-', text); self.assertNotIn('initial-ts-canary', text)
        self.assertIn('github.workflow_ref ==', text); self.assertIn(ci.WORKFLOW_REF, text)
        exact = text.index("grep -F '[q4-quality-canary]'")
        self.assertLess(text.index('actions/checkout@'), exact)
        self.assertLess(exact, text.index('eval/quant_ci.py --preflight'))
        self.assertLess(exact, text.index('run: python3 -B eval/quant_ci.py\n'))
        self.assertIn('persist-credentials: false', text); self.assertIn('submodules: recursive', text)
        self.assertIn('contents: read', text); self.assertIn('timeout-minutes: 180', text)
        self.assertIn('${{ runner.temp }}/lip-quant-evidence/', text)
        self.assertIn("if: always() && steps.exact.outcome == 'success'", text)
        for line in text.splitlines():
            if 'uses:' in line: self.assertRegex(line, r'@[0-9a-f]{40}')

    def test_preflight_checks_actual_event_head_clean_vendor_gitlink_before_tools(self):
        env, event, sha, message = context()
        with tempfile.TemporaryDirectory() as temp:
            runner = Path(temp).resolve(); event_path = runner / 'event.json'; event_path.write_bytes(ci.ts.encode(event))
            env.update(GITHUB_EVENT_PATH=str(event_path), RUNNER_TEMP=str(runner))
            def git(repository, *args):
                if args == ('rev-parse', 'HEAD'):
                    return ci.ts.RUNTIME_REVISION if repository.name == 'whisper.cpp' else sha
                if args[:1] == ('log',): return message
                if args[:1] == ('status',): return ''
                if args[:1] == ('ls-tree',): return '160000 commit ' + ci.ts.RUNTIME_REVISION + '\tthird_party/whisper.cpp'
                raise AssertionError(args)
            def read_text(path, *args, **kwargs):
                if str(path) == '/etc/os-release': return 'ID=ubuntu\nVERSION_ID="24.04"\n'
                if str(path) == '/proc/meminfo': return 'MemAvailable: 10485760 kB\n'
                raise AssertionError(path)
            with patch.dict(ci.os.environ, env), patch.object(ci.sys, 'platform', 'linux'), \
                 patch.object(ci.os, 'geteuid', return_value=1001), patch.object(ci.ts, 'git', side_effect=git), \
                 patch.object(ci.Path, 'read_text', read_text), patch.object(ci.os, 'cpu_count', return_value=4), \
                 patch.object(ci.os, 'sched_getaffinity', return_value={0, 1, 2, 3}, create=True), \
                 patch.object(ci.shutil, 'disk_usage', return_value=type('Disk', (), {'free': 6 * ci.ts.GIB})()), \
                 patch.object(ci.ts, 'freeze_tools') as tools, patch.object(ci, 'owned_directories') as allocate:
                self.assertEqual(ci.preflight(ROOT)[2], frozen())
                for target in ('head', 'root_dirty', 'vendor_dirty', 'vendor_head', 'gitlink'):
                    def drift(repository, *args):
                        if target == 'head' and args == ('rev-parse', 'HEAD') and repository == ROOT: return 'b' * 40
                        if target == 'root_dirty' and args[:1] == ('status',) and repository == ROOT: return '?? other-owner.txt'
                        if target == 'vendor_dirty' and args[:1] == ('status',) and repository.name == 'whisper.cpp': return ' M src/whisper.cpp'
                        if target == 'vendor_head' and args == ('rev-parse', 'HEAD') and repository.name == 'whisper.cpp': return 'b' * 40
                        if target == 'gitlink' and args[:1] == ('ls-tree',): return '160000 commit ' + 'b' * 40 + '\tvendor'
                        return git(repository, *args)
                    with patch.object(ci.ts, 'git', side_effect=drift), self.subTest(target=target), self.assertRaises(ValueError):
                        ci.preflight(ROOT)
                tools.assert_not_called(); allocate.assert_not_called()

    def test_worker_positive_fixture_launches_only_frozen_time_cli_with_caps(self):
        pins = ci.ts.load_json(ROOT / 'eval/quant-pins.json'); cases = frozen(); case = cases[0]
        with tempfile.TemporaryDirectory() as temp:
            runner = Path(temp).resolve(); root = runner / ci.WORK_NAME; root.mkdir()
            binary = root / 'build/bin/whisper-cli'; binary.parent.mkdir(parents=True); binary.write_bytes(b'fixture, never executable')
            timing = root / 'time-fixture'; timing.write_bytes(b'fixture, never executable')
            model = root / pins['models']['q5_0']['name']; model.write_bytes(b'fixture, not model bytes')
            # Simulate a prior complete hash witness with tiny fixture files; no real model is opened.
            pins = copy.deepcopy(pins); pins['models']['q5_0'].update(bytes=model.stat().st_size, sha256=hashlib.sha256(model.read_bytes()).hexdigest())
            time_id = ci.ts.file_identity(timing, 1024)
            (root / 'worker-bindings.json').write_bytes(ci.ts.encode({'binary': ci.ts.file_identity(binary, 1024),
                'time': time_id, 'models': {'q5_0': ci.ts.file_identity(model, 1024)}}))
            stem = root / (case['id'] + '-q5_0') / 'transcript'; stem.parent.mkdir()
            audio = root / 'original' / case['audio']
            args = [str(binary), str(model), str(audio), case['language'], str(stem)]
            with patch.object(ci, 'preflight', return_value=(pins, {}, cases, runner, {})), \
                 patch.dict(ci.os.environ, LIP_QUANT_ARM='q5_0', LIP_INITIAL_TS='1.0'), \
                 patch.object(ci.ts, 'verify_audio', return_value=audio) as verify, \
                 patch.object(ci.resource, 'setrlimit') as limits, patch.object(ci.os, 'execve') as execute:
                ci.infer_worker(args)
                verify.assert_called_once_with(case, root / 'original')
                self.assertEqual(limits.call_args_list[0].args, (ci.resource.RLIMIT_AS, (8 * ci.ts.GIB, 8 * ci.ts.GIB)))
                self.assertEqual(limits.call_args_list[1].args, (ci.resource.RLIMIT_FSIZE, (ci.ts.OUTPUT_BYTES, ci.ts.OUTPUT_BYTES)))
                command = execute.call_args.args[1]
                self.assertEqual(command[0], str(timing)); self.assertEqual(command[5:], ci.ts.cli_command(binary, model, audio, case['language'], stem))
                self.assertEqual(command[command.index('-bo') + 1], '5')
                binary.write_bytes(b'changed')
                with self.assertRaises(ValueError): ci.infer_worker(args)
                self.assertEqual(execute.call_count, 1)

    def test_oversized_native_output_retains_bounded_prefix_and_failure(self):
        case = {'id': 'fixture', 'language': 'en', 'origin': 'fixture', 'sha256': 'a' * 64,
                'reference_raw': 'No.', 'max_error_rate': .05, 'checks': []}
        pin = ci.ts.load_json(ROOT / 'eval/quant-pins.json')['models']['q5_0']
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); evidence = root / 'evidence'; evidence.mkdir()
            def oversized(*args, **kwargs):
                self.native_fixture(*args, **kwargs)
                Path(args[0][8]).with_suffix('.txt').write_bytes(b'x' * (ci.ts.OUTPUT_BYTES + 1))
            with patch.object(ci.ts, 'run_logged', side_effect=oversized):
                row = ci.run_native(case, 'q5_0', root / 'audio', root / 'bin', root / pin['name'], root, evidence,
                                    {'GITHUB_REF': ci.REF}, pin)
            self.assertEqual(row['status'], 'invalid_input_or_output'); self.assertTrue(row['output_exceeded_bound'])
            self.assertNotIn('score', row)
            self.assertEqual((evidence / 'fixture-q5_0-raw.txt').stat().st_size, ci.ts.OUTPUT_BYTES)

    def main_fixture(self, temp, model_hash_drift=False, runtime_error=False):
        runner = Path(temp).resolve(); pins = copy.deepcopy(ci.ts.load_json(ROOT / 'eval/quant-pins.json'))
        initial = ci.ts.load_json(ROOT / 'eval/initial-ts-pins.json'); cases = frozen()
        fixture_bytes = b'fixture, not model bytes'
        for pin in pins['models'].values(): pin.update(bytes=len(fixture_bytes), sha256=hashlib.sha256(fixture_bytes).hexdigest())
        env, _, _, _ = context(); env.update(GITHUB_EVENT_PATH=str(runner / 'event.json'), RUNNER_TEMP=str(runner))
        binary = runner / ci.WORK_NAME / 'build/bin/whisper-cli'
        binary_id = {'path': str(binary), 'bytes': 24, 'sha256': 'b' * 64}
        def build(*args): return binary, {'binary': binary_id}
        def download(pin, path): path.write_bytes(fixture_bytes)
        def acquire(repository, work, _):
            original = work / 'original'; original.mkdir(); path = original / 'corpus.json'
            path.write_bytes((repository / 'eval/corpus.json').read_bytes()); return path
        def controls(root): (root / 'controls.json').write_bytes((ROOT / 'eval/controls.json').read_bytes())
        def native(case, arm, *args):
            status = 'timeout' if runtime_error and case['id'] == cases[0]['id'] and arm == 'q5_0' else 'ok'
            return {'id': case['id'], 'arm': arm, 'status': status, 'origin': case['origin'], 'raw': case['reference_raw'],
                    'segments': [], 'score': ci.ts.score_case(case, case['reference_raw'])}
        actual_identity = ci.ts.file_identity
        def bounded_identity(path, cap):
            if path == binary: return binary_id
            result = actual_identity(path, cap)
            return dict(result, sha256='0' * 64) if model_hash_drift else result
        with patch.object(ci, 'preflight', return_value=(pins, initial, cases, runner, {'fixture': True})), \
             patch.dict(ci.os.environ, env), patch.object(ci.sys, 'argv', ['quant_ci.py']), \
             patch.object(ci.ts, 'freeze_tools', return_value={'time': {'fixture': True}}), \
             patch.object(ci.ts, 'build_cli', side_effect=build), patch.object(ci.ts, 'download', side_effect=download) as get, \
             patch.object(ci.ts, 'acquire_corpus', side_effect=acquire) as corpus, \
             patch.object(ci.ts, 'prepare_quiet', return_value={'cases': initial['quiet_cases']}), \
             patch.object(ci.ts, 'prepare_controls', side_effect=controls), \
             patch.object(ci.ts, 'verify_audio', side_effect=lambda c, root: root / c['audio']), \
             patch.object(ci.ts, 'verify_tools'), patch.object(ci.ts, 'file_identity', side_effect=bounded_identity), \
             patch.object(ci, 'run_native', side_effect=native) as infer, patch('builtins.print'):
            code = ci.main()
        report = ci.ts.load_json(runner / ci.EVIDENCE_NAME / 'completion.json')
        return code, report, get.call_count, corpus.call_count, infer.call_count

    def test_complete_main_fixture_keeps_runtime_quality_and_acceptance_separate(self):
        with tempfile.TemporaryDirectory() as temp:
            code, report, downloads, corpus, calls = self.main_fixture(temp)
            self.assertEqual((code, downloads, corpus, calls), (0, 2, 1, 72))
            self.assertTrue(report['runtime_success']); self.assertTrue(report['quality_passed'])
            self.assertEqual(report['acceptance'], 'not_assessed'); self.assertFalse(report['screen_eligible_for_independent_review'])
            self.assertEqual(len(report['execution_order']), 72); self.assertNotIn('current_case', report)
            self.assertEqual(report['limits']['experiment_wall_seconds'], 175 * 60)
            self.assertEqual(report['limits']['hosted_job_minutes'], 180)
            self.assertIn('prior_failures_and_limits', report)
            self.assertTrue((Path(temp) / ci.EVIDENCE_NAME / 'comparison.json').is_file())

    def test_main_hash_failure_stops_before_corpus_and_inference_without_fallback(self):
        with tempfile.TemporaryDirectory() as temp:
            code, report, downloads, corpus, calls = self.main_fixture(temp, model_hash_drift=True)
            self.assertEqual((code, downloads, corpus, calls), (1, 1, 0, 0))
            self.assertEqual(report['status'], 'incomplete_diagnostic'); self.assertFalse(report['runtime_success'])
            self.assertEqual(report['error_type'], 'ValueError'); self.assertNotIn('models', report)
            self.assertEqual(report['acceptance'], 'not_assessed')

    def test_main_runtime_failure_still_retains_full_schedule_and_truthful_failure(self):
        with tempfile.TemporaryDirectory() as temp:
            code, report, downloads, corpus, calls = self.main_fixture(temp, runtime_error=True)
            self.assertEqual((code, downloads, corpus, calls), (1, 2, 1, 72))
            self.assertFalse(report['runtime_success']); self.assertFalse(report['quality_passed'])
            self.assertEqual(sum(r['status'] == 'timeout' for r in report['execution_order']), 1)
            self.assertEqual(report['results_count'], 72); self.assertIsNone(report['measured_nonregression_passed'])

    def test_combined_case_record_is_bounded_without_losing_unfiltered_files(self):
        case = {'id': 'fixture', 'language': 'en', 'origin': 'fixture', 'sha256': 'a' * 64,
                'reference_raw': '', 'max_error_rate': .05, 'checks': []}
        pin = ci.ts.load_json(ROOT / 'eval/quant-pins.json')['models']['q5_0']
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); evidence = root / 'evidence'; evidence.mkdir()
            text = '.' * (70 * 1024)
            def large_valid(command, env, log, timeout, byte_limit):
                self.native_fixture(command, env, log, timeout, byte_limit)
                stem = Path(command[8]); raw_bytes = (text + '\n').encode()
                segment = {'text': ' ' + text, 'offsets': {'from': 0, 'to': 1000},
                           'timestamps': {'from': '00:00:00,000', 'to': '00:00:01,000'}}
                stem.with_suffix('.txt').write_bytes(raw_bytes)
                stem.with_suffix('.json').write_bytes(ci.ts.encode({'params': {'model': command[5], 'language': 'en', 'translate': False},
                    'result': {'language': 'en'}, 'transcription': [segment]}))
            with patch.object(ci.ts, 'run_logged', side_effect=large_valid):
                row = ci.run_native(case, 'q5_0', root / 'audio', root / 'bin', root / pin['name'], root, evidence,
                                    {'GITHUB_REF': ci.REF}, pin)
            score_path = evidence / 'fixture-q5_0-score.json'
            self.assertLessEqual(score_path.stat().st_size, ci.ts.OUTPUT_BYTES)
            self.assertEqual(row['status'], 'invalid_input_or_output')
            self.assertTrue(row['record_exceeded_bound'])
            self.assertEqual((evidence / 'fixture-q5_0-raw.txt').read_bytes(), (text + '\n').encode())
            self.assertEqual(ci.ts.load_json(evidence / 'fixture-q5_0-segments.json')['transcription'][0]['text'], ' ' + text)


if __name__ == '__main__':
    unittest.main()
