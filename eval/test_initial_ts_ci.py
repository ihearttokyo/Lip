"""Deterministic experiment guards; fixtures are not ASR acceptance evidence."""
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import initial_ts_ci as ci

ROOT = Path(__file__).resolve().parent.parent


def inputs():
    pins = json.loads((ROOT / 'eval/initial-ts-pins.json').read_bytes())
    corpus = json.loads((ROOT / 'eval/corpus.json').read_bytes())
    return pins, corpus


def context():
    sha = 'a' * 40
    env = {'GITHUB_ACTIONS': 'true', 'RUNNER_OS': 'Linux', 'RUNNER_ARCH': 'X64',
           'RUNNER_ENVIRONMENT': 'github-hosted', 'ImageOS': 'ubuntu24',
           'GITHUB_REPOSITORY': 'ihearttokyo/Lip', 'GITHUB_REPOSITORY_OWNER': 'ihearttokyo',
           'GITHUB_EVENT_NAME': 'push', 'GITHUB_REF': ci.REF, 'GITHUB_SHA': sha,
           'GITHUB_SERVER_URL': 'https://github.com', 'GITHUB_RUN_ID': '123',
           'GITHUB_RUN_ATTEMPT': '1', 'GITHUB_WORKFLOW_REF': ci.WORKFLOW_REF}
    message = 'research [initial-ts-canary]'
    event = {'repository': {'full_name': 'ihearttokyo/Lip', 'private': False,
                           'visibility': 'public', 'owner': {'login': 'ihearttokyo'}},
             'ref': ci.REF, 'after': sha, 'head_commit': {'id': sha, 'message': message}}
    return env, event, sha, message


class InitialTimestampTest(unittest.TestCase):
    def test_both_arms_match_returned_jni_greedy_best_of(self):
        vendor = (ROOT / 'third_party/whisper.cpp/src/whisper.cpp').read_text()
        greedy = vendor.split('switch (strategy) {', 1)[1].split('case WHISPER_SAMPLING_BEAM_SEARCH:', 1)[0]
        self.assertIn('/*.best_of   =*/ 5,', greedy)
        jni = (ROOT / 'app/src/main/cpp/whisper_jni.cpp').read_text()
        self.assertIn('whisper_full_default_params(WHISPER_SAMPLING_GREEDY)', jni)
        self.assertNotIn('params.greedy.best_of', jni)
        for arm in ('1.0', '30.0'):
            self.assertEqual(ci.expected_params(arm)['best_of'], 5)
        command = ci.cli_command('/bin/fixture', '/model', '/audio', 'en', '/output')
        self.assertEqual(command[command.index('-bo') + 1], '5')
        self.assertIn('wparams.greedy.best_of != 5', ci.PARAM_PATCH)
        receipt = ci.completion([])
        self.assertTrue(receipt['production_policy_parity'])
        self.assertIsNone(receipt['production_policy_gap'])
        self.assertEqual(receipt['acceptance'], 'not_assessed')

    def test_only_exact_marker_context_identity_and_unprivileged_host_are_admitted(self):
        env, event, sha, message = context()
        ci.require_environment(env, event, sha, message, 'linux', 1001, '24.04')
        for value in ('[INITIAL-TS-CANARY]', '[initial-ts-corpus]', 'ordinary push'):
            changed = copy.deepcopy(event); changed['head_commit']['message'] = value
            with self.subTest(marker=value), self.assertRaises(ValueError):
                ci.require_environment(env, changed, sha, value, 'linux', 1001, '24.04')
        for key, value in [('GITHUB_SHA', 'b' * 40), ('GITHUB_REF', 'refs/heads/main'),
                           ('RUNNER_ENVIRONMENT', 'self-hosted'), ('GITHUB_EVENT_NAME', 'workflow_dispatch'),
                           ('GITHUB_RUN_ID', ''), ('ImageOS', 'ubuntu22'), ('RUNNER_ARCH', 'ARM64'),
                           ('GITHUB_WORKFLOW_REF', 'other'), ('GITHUB_SERVER_URL', 'https://other')]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                ci.require_environment(dict(env, **{key: value}), event, sha, message, 'linux', 1001, '24.04')
        for platform, uid, release in [('darwin', 1001, '24.04'), ('linux', 0, '24.04'), ('linux', 1001, '22.04')]:
            with self.subTest(platform=platform, uid=uid), self.assertRaises(ValueError):
                ci.require_environment(env, event, sha, message, platform, uid, release)
        for mutate in [lambda e: e['repository'].update(private=True),
                       lambda e: e.update(after='b' * 40),
                       lambda e: e['head_commit'].update(id='b' * 40),
                       lambda e: e['repository'].update(full_name='other/Lip')]:
            changed = copy.deepcopy(event); mutate(changed)
            with self.assertRaises(ValueError):
                ci.require_environment(env, changed, sha, message, 'linux', 1001, '24.04')
        with self.assertRaises(ValueError):
            ci.require_environment(env, event, sha, 'different [initial-ts-canary]', 'linux', 1001, '24.04')

    def test_capacity_and_arms_fail_closed(self):
        ci.require_capacity(4, 10 * ci.GIB, 6 * ci.GIB)
        for args in [(3, 10 * ci.GIB, 6 * ci.GIB), (4, 10 * ci.GIB - 1, 6 * ci.GIB),
                     (4, 10 * ci.GIB, 6 * ci.GIB - 1), (4, float('nan'), 6 * ci.GIB)]:
            with self.assertRaises(ValueError): ci.require_capacity(*args)
        for value, expected in [('1.0', 1.0), ('30.0', 30.0)]:
            self.assertEqual(ci.arm_value(value), expected)
        for value in [1, 30, '', '1', '30', '1.00', '30.00', '-1.0', 'nan', 'inf', ' 1.0']:
            with self.subTest(value=value), self.assertRaises(ValueError): ci.arm_value(value)

    def test_frozen_sources_and_exact12_reference_bound_public_cases(self):
        pins, corpus = inputs()
        ci.verify_sources(ROOT, pins)
        ci.validate_quiet(pins, corpus)
        encoded = ci.encode(pins['quiet_cases'])
        self.assertEqual(hashlib.sha256(encoded).hexdigest(), ci.QUIET_CASES_SHA256)
        mutations = [lambda p: p['quiet_cases'].pop(),
                     lambda p: p['quiet_cases'].append(p['quiet_cases'][0]),
                     lambda p: p['quiet_cases'][0].update(origin='synthetic_speech'),
                     lambda p: p['quiet_cases'][0].update(reference_raw='easier'),
                     lambda p: p['quiet_cases'][0].update(checks=[]),
                     lambda p: p['quiet_cases'][0].update(sha256='0' * 64),
                     lambda p: p['quiet_cases'][0]['source'].update(num_samples=1),
                     lambda p: p['quiet_cases'][0]['truth'].update(source_envelope_samples=[0, 1]),
                     lambda p: p['quiet_cases'][0].update(audio='../escape.wav')]
        for mutate in mutations:
            changed = copy.deepcopy(pins); mutate(changed)
            with self.assertRaises(ValueError): ci.validate_quiet(changed, corpus)
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); (root / 'eval').mkdir()
            for path in pins['source_sha256']:
                target = root / path; target.write_bytes((ROOT / path).read_bytes())
            (root / 'eval/benchmark.py').write_bytes(b'drift')
            with self.assertRaises(ValueError): ci.verify_sources(root, pins)

    def test_adapter_is_hash_bound_one_assignment_and_keeps_standard_call(self):
        pins, _ = inputs()
        source = (ROOT / 'third_party/whisper.cpp/examples/cli/cli.cpp').read_bytes()
        adapted = ci.patch_cli(source, pins)
        self.assertEqual(adapted.count(b'wparams.max_initial_ts ='), 1)
        self.assertIn(b'"1.0"', adapted); self.assertIn(b'"30.0"', adapted)
        self.assertIn(b'wparams.no_context', adapted)
        self.assertIn(b'GGML_BACKEND_DEVICE_TYPE_CPU', adapted)
        self.assertIn(b'ggml_backend_dev_count() != 1', adapted)
        self.assertIn(b'whisper_full_parallel(ctx, wparams, pcmf32.data(), pcmf32.size(), params.n_processors)', adapted)
        self.assertEqual(ci.unpatch_cli(adapted), source)
        for changed in [source + b'\n', source.replace(b'whisper_full_parallel', b'other_call')]:
            with self.assertRaises(ValueError): ci.patch_cli(changed, pins)
        self.assertEqual(ci.patch_cli(source, pins), adapted)

    def test_fresh_owned_outputs_and_text_only_artifact_caps(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            work, evidence = ci.owned_directories(root)
            with self.assertRaises(FileExistsError): ci.owned_directories(root)
            ci.save(evidence / 'fixture.json', {'scope': 'fixture'})
            with self.assertRaises(FileExistsError): ci.save(evidence / 'fixture.json', {})
            outside = root / 'outside'; outside.write_text('preserve')
            (evidence / 'escape.txt').symlink_to(outside)
            with self.assertRaises(ValueError): ci.evidence_size(evidence)
            self.assertEqual(outside.read_text(), 'preserve')
            self.assertTrue(work.is_relative_to(root.resolve()))
        with tempfile.TemporaryDirectory() as temp:
            target = Path(temp) / 'fixture.json'
            with patch.object(ci, 'ARTIFACT_BYTES', 5), self.assertRaises(ValueError):
                ci.save(target, {'too': 'large'})
            self.assertFalse(target.exists())

    def test_cli_transcript_keeps_segments_without_filtering_or_fabricated_confidence(self):
        document = {'params': {'language': 'en', 'translate': False, 'model': '/fixture/model'},
                    'result': {'language': 'en'}, 'transcription': [
                        {'text': ' No.', 'offsets': {'from': 20000, 'to': 21000},
                         'timestamps': {'from': '00:00:20,000', 'to': '00:00:21,000'}},
                        {'text': ' No.', 'offsets': {'from': 21000, 'to': 22000},
                         'timestamps': {'from': '00:00:21,000', 'to': '00:00:22,000'}}]}
        raw, segments = ci.parse_outputs(b'No.\nNo.\n', ci.encode(document), 'en', '/fixture/model')
        self.assertEqual(raw, 'No.\nNo.'); self.assertEqual(segments, document['transcription'])
        for txt, data in [(b'No.\n', ci.encode(document)), (b'x' * (ci.OUTPUT_BYTES + 1), b'{}'),
                          (b'', b'{"transcription":[],"transcription":[]}')]:
            with self.assertRaises(ValueError): ci.parse_outputs(txt, data, 'en', '/fixture/model')
        document['transcription'] = []; self.assertEqual(ci.parse_outputs(b'', ci.encode(document), 'en', '/fixture/model'), ('', []))

    def test_paired_order_counts_and_incomplete_status_are_not_quality_acceptance(self):
        pins, corpus = inputs()
        controls = json.loads((ROOT / 'eval/controls.json').read_bytes())
        cases = corpus['cases'] + pins['quiet_cases'] + controls['cases']
        schedule = ci.paired_schedule(cases)
        self.assertEqual(len(schedule), 72)
        for i in range(36):
            first, second = schedule[2*i:2*i+2]
            self.assertEqual(first[0]['id'], second[0]['id'])
            self.assertEqual([first[1], second[1]], ['1.0', '30.0'] if i % 2 == 0 else ['30.0', '1.0'])
        with self.assertRaises(ValueError): ci.paired_schedule(cases + [cases[0]])
        results = [{'id': c['id'], 'arm': arm, 'status': 'ok', 'score': {'passed': False}}
                   for c, arm in schedule]
        report = ci.completion(results)
        self.assertEqual(report['status'], 'complete_diagnostic')
        self.assertEqual(report['acceptance'], 'not_assessed')
        self.assertFalse(report['quality_passed'])
        for changed in [results[:-1], [dict(results[0], status='timeout')] + results[1:],
                        [results[0]] + results[1:-1] + [results[0]]]:
            self.assertEqual(ci.completion(changed)['status'], 'incomplete_diagnostic')

    def test_negative_controls_require_exactly_empty_segments_and_raw_output(self):
        pins, corpus = inputs()
        controls = json.loads((ROOT / 'eval/controls.json').read_bytes())
        cases = corpus['cases'] + pins['quiet_cases'] + controls['cases']
        results = [{'id': c['id'], 'arm': arm, 'origin': c['origin'], 'status': 'ok',
                    'raw': c['reference_raw'], 'segments': [], 'score': {'passed': True}}
                   for c, arm in ci.paired_schedule(cases)]
        self.assertTrue(ci.completion(results)['quality_passed'])
        index = next(i for i, r in enumerate(results) if r['origin'] == 'negative_control')
        case = next(c for c in controls['cases'] if c['id'] == results[index]['id'])
        for text in (' .', ' \t'):
            segment = {'text': text, 'offsets': {'from': 0, 'to': 1000},
                       'timestamps': {'from': '00:00:00,000', 'to': '00:00:01,000'}}
            document = {'params': {'language': case['language'], 'translate': False, 'model': '/fixture/model'},
                        'result': {'language': case['language']}, 'transcription': [segment]}
            raw, segments = ci.parse_outputs((text.lstrip(' \t') + '\n').encode(),
                                             ci.encode(document), case['language'], '/fixture/model')
            score = ci.score_case(case, raw)
            self.assertTrue(score['passed'])
            changed = copy.deepcopy(results)
            changed[index].update(raw=raw, segments=segments, score=score)
            with self.subTest(text=text):
                report = ci.completion(changed)
                self.assertEqual(report['status'], 'complete_diagnostic')
                self.assertEqual(report['acceptance'], 'not_assessed')
                self.assertFalse(report['quality_passed'])
        for raw, segments in (('.', []), ('', None)):
            changed = copy.deepcopy(results); changed[index].update(raw=raw, segments=segments)
            self.assertFalse(ci.completion(changed)['quality_passed'])

    def test_timeout_retains_failed_diagnostics_without_success(self):
        pins, corpus = inputs(); case = corpus['cases'][0]
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); evidence = root / 'evidence'; evidence.mkdir()
            with patch.object(ci, 'run_logged', side_effect=subprocess.TimeoutExpired('fixture', 120)):
                result = ci.run_native(case, '1.0', Path('/fixture/audio'), root / 'bin', root / 'model',
                                       root, evidence, {'PATH': '/usr/bin'})
            self.assertEqual(result['status'], 'timeout'); self.assertNotIn('score', result)
            saved = json.loads((evidence / (case['id'] + '-1.0-score.json')).read_bytes())
            self.assertEqual(saved['status'], 'timeout')
            self.assertTrue((evidence / (case['id'] + '-1.0.log')).is_file())


    def test_unjoined_owned_process_stops_after_retaining_failure(self):
        _, corpus = inputs(); case = corpus['cases'][0]
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); evidence = root / 'evidence'; evidence.mkdir()
            with patch.object(ci, 'run_logged', side_effect=subprocess.TimeoutExpired('fixture cleanup', 5)):
                with self.assertRaises(RuntimeError):
                    ci.run_native(case, '1.0', Path('/fixture/audio'), root / 'bin', root / 'model',
                                  root, evidence, {'PATH': '/usr/bin'})
            saved = json.loads((evidence / (case['id'] + '-1.0-score.json')).read_bytes())
            self.assertEqual(saved['status'], 'cleanup_timeout')

    def test_artifact_cap_reserves_failure_completion_receipt(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            with patch.object(ci, 'ARTIFACT_BYTES', ci.OUTPUT_BYTES + 10):
                with self.assertRaises(ValueError): ci.retain(root / 'too-large.txt', b'x' * 11)
                ci.save(root / 'completion.json', {'status': 'incomplete_diagnostic'})
                self.assertLessEqual(ci.evidence_size(root), ci.OUTPUT_BYTES + 10)

    def test_worker_cannot_load_native_model_on_mac(self):
        with patch.object(ci.sys, 'platform', 'darwin'), patch.object(ci.os, 'execve') as execute:
            with self.assertRaises(ValueError): ci.infer_worker([])
            execute.assert_not_called()

    def test_workflow_gate_is_before_heavy_step_and_has_no_job_runner_temp(self):
        source = (ROOT / '.github/workflows/initial-ts-experiment.yml').read_text()
        self.assertNotIn('workflow_dispatch', source)
        self.assertNotIn('sudo', source); self.assertNotIn('setup-java', source)
        exact = source.index("grep -F '[initial-ts-canary]'")
        self.assertLess(source.index('actions/checkout@'), exact)
        self.assertLess(exact, source.index('eval/initial_ts_ci.py --preflight'))
        self.assertLess(exact, source.index('run: python3 -B eval/initial_ts_ci.py\n'))
        self.assertGreater(source.index('${{ runner.temp }}'), source.index('    steps:'))
        self.assertIn('persist-credentials: false', source)
        self.assertIn('contents: read', source)
        self.assertNotIn('security-events:', source)
        self.assertIn('timeout-minutes: 180', source)
        for line in source.splitlines():
            if 'uses:' in line: self.assertRegex(line, r'@[0-9a-f]{40}')


    def test_runtime_fixture_uses_actual_parameters_and_retains_raw_failed_scores(self):
        case = {'id': 'fixture', 'language': 'en', 'origin': 'fixture', 'sha256': 'a' * 64,
                'reference_raw': 'Do not send it.', 'max_error_rate': .05, 'checks': []}
        for drift in (False, True):
            with self.subTest(drift=drift), tempfile.TemporaryDirectory() as temp:
                root = Path(temp); evidence = root / 'evidence'; evidence.mkdir()
                def fixture(command, env, log, **kwargs):
                    self.assertEqual(kwargs, {'timeout': 120, 'byte_limit': 32 * 1024})
                    self.assertEqual(env['LIP_INITIAL_TS'], '30.0')
                    stem = Path(command[-1])
                    params = ci.expected_params('30.0'); params['n_threads'] = 8 if drift else 4
                    log.write(b'LIP_PARAMS ' + json.dumps(params).encode() + b'\n')
                    doc = {'params': {'language': 'en', 'translate': False, 'model': str(root / 'model')},
                           'result': {'language': 'en'}, 'transcription': [
                           {'text': ' unrelated fixture', 'timestamps': {'from': '00:00:20,000', 'to': '00:00:21,000'},
                            'offsets': {'from': 20000, 'to': 21000}}]}
                    stem.with_suffix('.txt').write_bytes(b'unrelated fixture\n')
                    stem.with_suffix('.json').write_bytes(ci.encode(doc))
                    (stem.parent / 'peak-kib.txt').write_text('1024\n')
                with patch.object(ci, 'run_logged', side_effect=fixture):
                    result = ci.run_native(case, '30.0', root / 'audio', root / 'bin', root / 'model',
                                           root, evidence, {'PATH': '/usr/bin'})
                self.assertEqual(result['status'], 'invalid_input_or_output' if drift else 'ok')
                self.assertEqual((evidence / 'fixture-30.0-raw.txt').read_bytes(), b'unrelated fixture\n')
                self.assertEqual((evidence / 'fixture-30.0-segments.json').read_bytes(), (root / 'fixture-30.0/transcript.json').read_bytes())
                if not drift:
                    self.assertFalse(result['score']['passed'])
                    self.assertEqual(result['actual_params'], ci.expected_params('30.0'))
                    self.assertEqual(result['peak_rss_bytes'], 1024 * 1024)


    def test_job_budget_holds_remaining_clips_and_leaves_receipt_upload_headroom(self):
        self.assertEqual(ci.JOB_SECONDS, 175 * 60)
        ci.require_clip_budget(1000, 875)
        for now in (875.001, 1000, 1001):
            with self.assertRaises(TimeoutError): ci.require_clip_budget(1000, now)


    def test_incomplete_or_inconsistent_segment_timestamp_metadata_is_rejected(self):
        document = {'params': {'language': 'en', 'translate': False, 'model': '/fixture/model'},
                    'result': {'language': 'en'}, 'transcription': [
                        {'text': ' No.', 'offsets': {'from': 20000, 'to': 21000},
                         'timestamps': {'from': '00:00:20,000', 'to': '00:00:21,000'}}]}
        for timestamps in ({}, {'from': '00:00:00,000', 'to': '00:00:21,000'}, 'missing'):
            changed = copy.deepcopy(document); changed['transcription'][0]['timestamps'] = timestamps
            with self.subTest(timestamps=timestamps), self.assertRaises(ValueError):
                ci.parse_outputs(b'No.\n', ci.encode(changed), 'en', '/fixture/model')


if __name__ == '__main__':
    unittest.main()
