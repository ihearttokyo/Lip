"""Stdlib fixtures only; never execute native code, network or an inference process."""
from array import array
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import moonshine_ci as m
import initial_ts_ci as ts
import zipfile

ROOT = Path(__file__).resolve().parent.parent


def wav(values, tag=3):
    bits = 32 if tag == 3 else 16
    payload = struct.pack('<' + ('f' if tag == 3 else 'h') * len(values), *values)
    fmt = struct.pack('<HHIIHH', tag, 1, 16000, 16000 * bits // 8, bits // 8, bits)
    body = b'WAVEfmt ' + struct.pack('<I', 16) + fmt + b'data' + struct.pack('<I', len(payload)) + payload
    return b'RIFF' + struct.pack('<I', len(body)) + body


def admission():
    head = 'a' * 40
    message = 'test [moonshine-canary]'
    env = {'GITHUB_ACTIONS': 'true', 'RUNNER_OS': 'Linux', 'RUNNER_ARCH': 'X64',
           'RUNNER_ENVIRONMENT': 'github-hosted', 'ImageOS': 'ubuntu24',
           'GITHUB_REPOSITORY': 'ihearttokyo/Lip', 'GITHUB_REPOSITORY_OWNER': 'ihearttokyo',
           'GITHUB_EVENT_NAME': 'push', 'GITHUB_REF': m.REF, 'GITHUB_WORKFLOW_REF': m.WORKFLOW_REF,
           'GITHUB_SERVER_URL': 'https://github.com', 'GITHUB_RUN_ID': '1',
           'GITHUB_RUN_ATTEMPT': '1', 'GITHUB_SHA': head, 'MOONSHINE_ORT_SINGLE_THREAD': '1'}
    event = {'ref': m.REF, 'after': head, 'repository': {'full_name': 'ihearttokyo/Lip',
             'private': False, 'visibility': 'public', 'owner': {'login': 'ihearttokyo'}},
             'head_commit': {'id': head, 'message': message}}
    return env, event, head, message, 'linux', 1001, '24.04'


def native_events(log, work):
    pins = json.loads((ROOT / 'eval/moonshine-pins.json').read_bytes())
    admission = dict(event='NativeAdmission', environment=m.NATIVE_ENV, native_controls=pins['native_controls'],
                     cpu_affinity=[0, 1, 2], wheel=dict(path=str((work / pins['wheel']['name']).resolve()),
                     bytes=pins['wheel']['bytes'], sha256=pins['wheel']['sha256']))
    log.write(b'MOONSHINE_EVENT ' + json.dumps(admission).encode() + b'\n')
    for stage in ('after_model_load', 'Stop', 'after_unload'):
        log.write(b'MOONSHINE_EVENT ' + json.dumps(dict(event='ThreadObservation', stage=stage, threads=1)).encode() + b'\n')


class Contract(unittest.TestCase):
    def setUp(self):
        for target in ('subprocess.Popen', 'urllib.request.urlopen', 'ctypes.CDLL', 'os.killpg'):
            guard = patch(target, side_effect=AssertionError('Real process/network/native prohibited in fixtures'))
            guard.start(); self.addCleanup(guard.stop)

    def test_raw_positive_cannot_qualify_native_quality(self):
        results = [{'id': str(i), 'status': 'ok', 'origin': 'human_recording', 'raw': 'raw',
                    'lines': [{'text': 'raw'}], 'score': {'passed': True},
                    'native_completion_verified': True} for i in range(12)]
        got = m.completion(results)
        self.assertIs(got['quality_passed'], False)
        self.assertIs(got['native_completion_verified'], False)

    def test_block_consumer_tail_requires_exact_lcm_zero_padding(self):
        samples = array('f', [0]) * 2561; samples[-1] = .875
        def blocks(values, size): return values[:len(values) // size * size]
        for size in (512, 1280):
            self.assertNotIn(.875, blocks(samples, size))
        padded, receipt = m.sample_protocol(wav(samples))
        for size in (512, 1280):
            self.assertIn(.875, blocks(padded, size))
        self.assertEqual(padded[:len(samples)], samples)
        self.assertEqual(padded[len(samples):], array('f', [0]) * 2559)
        self.assertIs(receipt['native_sample_count_verified'], False)

    def test_wrong_host_denies_before_path_tools_network_native(self):
        with patch.object(m.sys, 'platform', 'darwin') if hasattr(m, 'sys') else patch('sys.platform', 'darwin'):
            with patch.object(m, 'Path', side_effect=AssertionError('Path reached')):
                with self.assertRaises(ValueError): m.preflight()

    def test_missing_native_thread_control_proof_holds_public_run(self):
        with self.assertRaisesRegex(ValueError, 'ort-utils'): m.require_native_controls()


    def test_single_thread_environment_is_mandatory(self):
        args = admission()
        for value in (None, '', '0', '2', 'true'):
            changed = deepcopy(args)
            if value is None: changed[0].pop('MOONSHINE_ORT_SINGLE_THREAD')
            else: changed[0]['MOONSHINE_ORT_SINGLE_THREAD'] = value
            with self.subTest(value=value), self.assertRaises(ValueError): m.require_environment(*changed)

    def test_observed_thread_violation_and_ort_log_fail_with_prefix(self):
        case = next(c for c in json.loads((ROOT / 'eval/corpus.json').read_bytes())['cases'] if c['id'] == 'fleurs-en-013')
        for diagnostic in (b'MOONSHINE_EVENT {"event":"ThreadObservation","threads":4}\n',
                           b'ORT Error: SetIntraOpNumThreads failed\n',
                           b'[E:onnxruntime:, session.cc:1] error\n',
                           b'Failed to encode: -1\n', b'Failed to process audio chunk: -1\n',
                           b'Encoder window misaligned: start_idx=1\n'):
            with self.subTest(diagnostic=diagnostic), tempfile.TemporaryDirectory() as temp:
                work = Path(temp) / 'w'; evidence = Path(temp) / 'e'; work.mkdir(); evidence.mkdir()
                def simulated(command, env, log, **bounds):
                    log.write(b'MOONSHINE_EVENT {"event":"Update","lines":[{"text":"prefix","line_id":1}]}\n')
                    log.write(diagnostic)
                    native_events(log, work)
                    value = {'id': case['id'], 'status': 'ok', 'raw': case['reference_raw'],
                             'lines': [{'text': case['reference_raw'], 'line_id': 1}], 'peak_rss_bytes': 1024}
                    log.write(b'MOONSHINE_RESULT ' + json.dumps(value).encode() + b'\n')
                with patch.object(m, 'run_logged', simulated): result = m.run_native(case, work, evidence, {})
                self.assertNotEqual(result['status'], 'ok')
                self.assertNotIn('score', result)
                self.assertEqual(result['raw'], 'prefix')
                self.assertIn(diagnostic, (evidence / (case['id'] + '.log')).read_bytes())
                if b'ThreadObservation' in diagnostic: self.assertEqual(result['observed_peak_threads'], 4)

    def test_pinned_controls_admit_only_exact_environment_and_source_proof(self):
        pins = json.loads((ROOT / 'eval/moonshine-pins.json').read_bytes())
        m.require_native_controls(pins, m.NATIVE_ENV)
        for changed in (None, {}, {**pins, 'source_revision': '0' * 40},
                        {**pins, 'native_controls': {}},
                        {**pins, 'native_controls': {**pins['native_controls'], 'intra_op_threads': 3}}):
            with self.subTest(pins=changed), self.assertRaises(ValueError): m.require_native_controls(changed, m.NATIVE_ENV)
        for env in (None, 1, {}, {'MOONSHINE_ORT_SINGLE_THREAD': '0'}, {'MOONSHINE_ORT_SINGLE_THREAD': '2'}):
            with self.subTest(env=env), self.assertRaises(ValueError): m.require_native_controls(pins, env)

    def test_thread_snapshots_record_before_bound_failure_and_fail_unavailable(self):
        for count in (0, 1, 3, 4):
            events = []
            with patch.object(m, 'Path') as proc:
                proc.return_value.iterdir.return_value = iter(range(count))
                if 0 < count <= 3: m.observe_threads(events.append, 'after_model_load')
                else:
                    with self.assertRaises(ValueError): m.observe_threads(events.append, 'after_model_load')
            self.assertEqual(events[0]['threads'], count)
            self.assertFalse(events[0]['continuous_thread_bound_verified'])
        with patch.object(m, 'Path') as proc:
            proc.return_value.iterdir.side_effect = OSError('proc unavailable')
            with self.assertRaises(OSError): m.observe_threads(lambda value: None, 'after_model_load')

    def test_success_requires_observed_thread_and_environment_receipts(self):
        case = next(c for c in json.loads((ROOT / 'eval/corpus.json').read_bytes())['cases'] if c['id'] == 'fleurs-en-013')
        for drift in ('missing', 'environment', 'source', 'wheel', 'affinity', 'stop_observation', 'malformed_event', 'wheel_none'):
            with self.subTest(drift=drift), tempfile.TemporaryDirectory() as temp:
                import io
                work = Path(temp) / 'w'; evidence = Path(temp) / 'e'; work.mkdir(); evidence.mkdir()
                def simulated(command, env, log, **bounds):
                    log.write(b'MOONSHINE_EVENT {"event":"Update","lines":[{"text":"prefix","line_id":1}]}\n')
                    source = io.BytesIO(); native_events(source, work)
                    events = [json.loads(line[16:]) for line in source.getvalue().splitlines()]
                    if drift == 'missing': events = []
                    elif drift == 'environment': events[0]['environment']['MOONSHINE_ORT_SINGLE_THREAD'] = '0'
                    elif drift == 'source': events[0]['native_controls']['intra_op_threads'] = 3
                    elif drift == 'wheel': events[0]['wheel']['sha256'] = '0' * 64
                    elif drift == 'affinity': events[0]['cpu_affinity'] = [0, 1, 2, 3]
                    elif drift == 'malformed_event': events.append([])
                    elif drift == 'wheel_none': events[0]['wheel'] = None
                    elif drift == 'stop_observation': events = [e for e in events if e.get('stage') != 'Stop']
                    for value in events: log.write(b'MOONSHINE_EVENT ' + json.dumps(value).encode() + b'\n')
                    value = dict(id=case['id'], status='ok', raw=case['reference_raw'],
                                 lines=[{'text': case['reference_raw']}], peak_rss_bytes=1024)
                    log.write(b'MOONSHINE_RESULT ' + json.dumps(value).encode() + b'\n')
                with patch.object(m, 'run_logged', simulated): result = m.run_native(case, work, evidence, {})
                self.assertNotEqual(result['status'], 'ok')
                self.assertEqual(result['raw'], 'prefix')
                self.assertNotIn('score', result)

    def test_mapping_verifies_owned_library_before_inference(self):
        from types import SimpleNamespace as Obj
        with tempfile.TemporaryDirectory() as temp:
            work = Path(temp).resolve(); wheel = work / 'wheel'; wheel.mkdir()
            lib = wheel / 'libmoonshine.so'; lib.write_text('mock mapping, not a native binary')
            for paths, accepted in (([str(lib)], True), ([], False),
                                    ([str(lib), '/usr/lib/libonnxruntime.so'], False),
                                    ([str(lib), '/usr/lib/libmoonshine.so'], False)):
                text = ''.join('addr perms offset dev inode ' + p + '\n' for p in paths)
                def path(value):
                    return Obj(read_text=lambda: text) if value == '/proc/self/maps' else Path(value)
                with self.subTest(paths=paths), patch.object(m, 'Path', side_effect=path):
                    if accepted: self.assertEqual(m.verify_mapping(work, lib), [str(lib)])
                    else:
                        with self.assertRaises(ValueError): m.verify_mapping(work, lib)

    def test_missing_or_wrong_control_denies_before_path_or_tools(self):
        env = admission()[0]
        for value in (None, '0', '2'):
            changed = deepcopy(env)
            if value is None: changed.pop('MOONSHINE_ORT_SINGLE_THREAD')
            else: changed['MOONSHINE_ORT_SINGLE_THREAD'] = value
            with patch.dict(m.os.environ, changed, clear=True), patch.object(m.sys, 'platform', 'linux'), \
                 patch.object(m.os, 'geteuid', return_value=1001), \
                 patch.object(m, 'Path', side_effect=AssertionError('Path reached')):
                with self.subTest(value=value), self.assertRaises(ValueError): m.preflight()

    def test_exact_host_and_case_sensitive_marker(self):
        args = admission()
        m.require_environment(*args)
        for key, value in [('GITHUB_REF', ts.REF), ('RUNNER_ARCH', 'ARM64'),
                           ('RUNNER_ENVIRONMENT', 'self-hosted'), ('GITHUB_EVENT_NAME', 'pull_request'),
                           ('GITHUB_WORKFLOW_REF', ts.WORKFLOW_REF), ('GITHUB_SHA', 'b' * 40)]:
            changed = deepcopy(args); changed[0][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): m.require_environment(*changed)
        for slot, value in [(3, 'test [Moonshine-canary]'), (4, 'darwin'), (5, 0), (6, '22.04')]:
            changed = list(deepcopy(args)); changed[slot] = value
            with self.subTest(slot=slot), self.assertRaises(ValueError): m.require_environment(*changed)
        changed = deepcopy(args); changed[1]['repository']['private'] = True
        with self.assertRaises(ValueError): m.require_environment(*changed)
        changed = deepcopy(args); changed[1]['head_commit']['message'] += ' drift'
        with self.assertRaises(ValueError): m.require_environment(*changed)

    def test_every_original_sample_and_tail_survives(self):
        for length in (1, 511, 512, 1279, 1280, 2559, 2560, 7999, 8000, 64000, 479999, 480000):
            values = array('f', ((i % 13 - 6) / 8 for i in range(length)))
            values[-1] = .875
            data = wav(values)
            padded, receipt = m.sample_protocol(data)
            with self.subTest(length=length):
                self.assertEqual(padded[:length], values)
                self.assertEqual(len(padded) % 2560, 0)
                self.assertEqual(padded[length:], array('f', [0]) * ((-length) % 2560))
                self.assertEqual(receipt['original_frames'], length)
                self.assertEqual(receipt['padded_frames'], len(padded))
                self.assertEqual(receipt['last_nonzero_frame'], length - 1)
                self.assertEqual(receipt['source_pcm_sha256'], hashlib.sha256(data[44:]).hexdigest())
                self.assertEqual(receipt['original_f32_sha256'], hashlib.sha256(values.tobytes()).hexdigest())
                self.assertEqual(receipt['padded_f32_sha256'], hashlib.sha256(padded.tobytes()).hexdigest())
        with self.assertRaises(ValueError): m.sample_protocol(wav([float('nan')]))
        with self.assertRaises(ValueError): m.sample_protocol(wav([1.25]))
        with self.assertRaises(ValueError): m.sample_protocol(wav([0])[:-1])
        values, _ = m.sample_protocol(wav([-32768, 1, 32767], tag=1))
        self.assertEqual(list(values[:3]), [-1, 1 / 32768, 32767 / 32768])

    def test_file_fed_publisher_stream_leaves_final_audio_for_stop(self):
        class Stream:
            def __init__(self): self.calls = []; self.listener = None
            def add_listener(self, listener): self.listener = listener
            def start(self): self.calls.append(('start',))
            def add_audio(self, chunk, sample_rate): self.calls.append(('audio', list(chunk), sample_rate))
            def update_transcription(self):
                self.calls.append(('update',)); return type('Transcript', (), {'lines': []})()
            def stop(self): self.calls.append(('stop',)); return type('Transcript', (), {'lines': []})()
        for length in (2560, 81920, 64000):
            stream = Stream(); samples = array('f', [0]) * length; samples[-1] = .5
            result = m.feed_stream(stream, samples, lambda event: None)
            self.assertEqual(result['lines'], [])
            self.assertEqual(stream.calls[-2][0], 'audio')
            self.assertEqual(stream.calls[-1], ('stop',))
            chunks = [call[1] for call in stream.calls if call[0] == 'audio']
            self.assertEqual([x for chunk in chunks for x in chunk], list(samples))
            self.assertTrue(all(len(c) == 8000 for c in chunks[:-1]))
            self.assertTrue(all(call[2] == 16000 for call in stream.calls if call[0] == 'audio'))
            self.assertEqual(sum(c[0] == 'update' for c in stream.calls), len(chunks) - 1)
        stream = Stream(); stream.stop = lambda: None
        with self.assertRaises(ValueError): m.feed_stream(stream, array('f', [0]) * 2560, lambda event: None)

    def test_frontend_only_exact_git_blob_is_admitted_and_read_back(self):
        data = b'frontend fixture'
        pin = {'name': 'frontend.model.ort', 'bytes': len(data),
               'git_blob_sha1': hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()}
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / pin['name']; path.write_bytes(data)
            got = m.verify_artifact(pin, path)
            self.assertEqual(got['sha256'], hashlib.sha256(data).hexdigest())
            for modified in ({**pin, 'git_blob_sha1': '0' * 40}, {**pin, 'name': 'encoder.ort'},
                             {**pin, 'bytes': len(data) - 1}, {'name': 'encoder.ort', 'bytes': len(data)}):
                with self.assertRaises(ValueError): m.verify_artifact(modified, path)
            pin = {'name': 'encoder.ort', 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
            path = Path(temp) / pin['name']; path.write_bytes(data)
            m.verify_artifact(pin, path)
            with self.assertRaises(ValueError): m.verify_artifact({**pin, 'sha256': '0' * 64}, path)

    def test_case_plan_preserves_full_sources_references_gates(self):
        corpus = json.loads((ROOT / 'eval/corpus.json').read_bytes())
        quiet = {'cases': json.loads((ROOT / 'eval/initial-ts-pins.json').read_bytes())['quiet_cases']}
        controls = json.loads((ROOT / 'eval/controls.json').read_bytes())
        selected = m.select_cases(corpus, quiet, controls)
        self.assertEqual(len(selected), 12)
        self.assertEqual([sum(c['origin'] == kind for c in selected) for kind in ('human_recording', 'negative_control')], [10, 2])
        self.assertTrue(all(c['language'] == 'en' for c in selected))
        all_cases = corpus['cases'] + quiet['cases'] + controls['cases']
        self.assertTrue(all(case == next(c for c in all_cases if c['id'] == case['id']) for case in selected))
        broken = deepcopy(quiet); broken['cases'][0]['max_error_rate'] = 1
        with self.assertRaises(ValueError): m.select_cases(corpus, broken, controls)
        with self.assertRaises(ValueError): m.select_cases({'cases': corpus['cases'][:-1]}, quiet, controls)

    def test_archive_rejects_traversal_symlinks_duplicate_and_oversize(self):
        for entries in [('../escape',), ('/escape',), ('x', 'x')]:
            with tempfile.TemporaryDirectory() as temp:
                path = Path(temp) / 'wheel.zip'
                with zipfile.ZipFile(path, 'w') as z:
                    for entry in entries: z.writestr(entry, b'test')
                with self.assertRaises(ValueError): m.unpack_wheel(path, Path(temp) / 'out')
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'wheel.zip'
            with zipfile.ZipFile(path, 'w') as z:
                entry = zipfile.ZipInfo('link'); entry.external_attr = 0o120777 << 16; z.writestr(entry, b'other')
            with self.assertRaises(ValueError): m.unpack_wheel(path, Path(temp) / 'out')

    def test_local_sources_and_frozen_model_manifest(self):
        pins = json.loads((ROOT / 'eval/moonshine-pins.json').read_bytes())
        m.verify_sources(ROOT, pins)
        self.assertEqual(sum(f['bytes'] for f in pins['models']), 269141623)
        self.assertEqual(len(pins['models']), 8)
        self.assertNotIn('decoder_kv_with_attention.ort', {f['name'] for f in pins['models']})
        self.assertEqual(pins['options']['vad_threshold'], '0')
        self.assertEqual(pins['options']['vad_max_segment_duration'], '0')
        self.assertEqual(pins['options']['use_speculative_decoding'], 'false')
        broken = deepcopy(pins); broken['models'][0]['url'] = 'https://download.moonshine.ai/no-fallback'
        with self.assertRaises(ValueError): m.verify_sources(ROOT, broken)


    def test_publisher_error_none_negative_and_listener_failure_preserve_events(self):
        from types import SimpleNamespace as Obj
        class Stream:
            def add_listener(self, listener): self.listener = listener
            def start(self): pass
            def add_audio(self, samples, rate): pass
            def update_transcription(self): return Obj(lines=[])
            def stop(self): return Obj(lines=[])
        events = []; stream = Stream()
        def error_stop():
            stream.listener(type('Error', (), {'line': None, 'stream_handle': 9,
                            'error': ValueError('observed decode failure')})())
            return Obj(lines=[])
        stream.stop = error_stop
        with self.assertRaises(ValueError): m.feed_stream(stream, array('f', [0]) * 2560, events.append)
        self.assertEqual(events[0]['event'], 'Error')
        self.assertEqual(events[0]['error'], 'observed decode failure')
        for method, output in [('start', -1), ('add_audio', -2), ('update_transcription', None), ('stop', None)]:
            stream = Stream(); setattr(stream, method, lambda *args, value=output: value)
            with self.subTest(method=method), self.assertRaises(ValueError):
                m.feed_stream(stream, array('f', [0]) * 10240, events.append)

    def test_snapshot_copies_raw_before_mutation_and_never_audio(self):
        from types import SimpleNamespace as Obj
        line = Obj(text=' raw ', line_id=3, words=[], speaker_spans=[], audio_data=[.5])
        copied = m.snapshot(line); line.text = 'changed'; line.words.append(Obj(word='x'))
        self.assertEqual(copied['text'], ' raw ')
        self.assertEqual(copied['words'], [])
        self.assertNotIn('audio_data', copied)

    def test_process_positive_failure_timeout_join_and_output_cap(self):
        case = next(c for c in json.loads((ROOT / 'eval/corpus.json').read_bytes())['cases'] if c['id'] == 'fleurs-en-013')
        self.assertEqual(case['id'], 'fleurs-en-013')
        prefix = {'event': 'Update', 'lines': [{'text': 'prefix', 'line_id': 1}]}
        final = {'id': case['id'], 'status': 'ok', 'raw': case['reference_raw'],
                 'lines': [{'text': case['reference_raw'], 'line_id': 1}], 'peak_rss_bytes': 1024}
        for error, status in [(None, 'ok'), (subprocess.CalledProcessError(1, 'mock'), 'engine_error'),
                              (subprocess.TimeoutExpired('mock', 120), 'timeout'),
                              (subprocess.TimeoutExpired('mock', 5), 'cleanup_timeout'),
                              (ValueError('cap'), 'invalid_input_or_output')]:
            with self.subTest(status=status), tempfile.TemporaryDirectory() as temp:
                work = Path(temp) / 'work'; evidence = Path(temp) / 'evidence'; work.mkdir(); evidence.mkdir()
                def simulated(command, env, log, **bounds):
                    self.assertEqual(bounds['timeout'], 120)
                    log.write(b'MOONSHINE_EVENT ' + json.dumps(prefix).encode() + b'\n')
                    if error: raise error
                    native_events(log, work)
                    log.write(b'MOONSHINE_RESULT ' + json.dumps(final).encode() + b'\n')
                with patch.object(m, 'run_logged', simulated):
                    if status == 'cleanup_timeout':
                        with self.assertRaises(RuntimeError): m.run_native(case, work, evidence, {})
                        result = json.loads((evidence / (case['id'] + '-score.json')).read_bytes())
                    else: result = m.run_native(case, work, evidence, {})
                self.assertEqual(result['status'], status)
                self.assertFalse(result['quality_passed']); self.assertFalse(result['native_completion_verified'])
                self.assertEqual(result['raw'], case['reference_raw'] if error is None else 'prefix')
                self.assertTrue((evidence / (case['id'] + '.log')).read_bytes().startswith(b'MOONSHINE_EVENT '))

    def test_success_log_with_error_event_must_fail(self):
        case = next(c for c in json.loads((ROOT / 'eval/corpus.json').read_bytes())['cases'] if c['id'] == 'fleurs-en-013')
        with tempfile.TemporaryDirectory() as temp:
            work = Path(temp) / 'w'; evidence = Path(temp) / 'e'; work.mkdir(); evidence.mkdir()
            def simulated(command, env, log, **bounds):
                log.write(b'MOONSHINE_EVENT {"event":"Error","error":"failed"}\n')
                value = {'id': case['id'], 'status': 'ok', 'raw': case['reference_raw'],
                         'lines': [{'text': case['reference_raw'], 'line_id': 1}], 'peak_rss_bytes': 1024}
                log.write(b'MOONSHINE_RESULT ' + json.dumps(value).encode() + b'\n')
            with patch.object(m, 'run_logged', simulated): result = m.run_native(case, work, evidence, {})
            self.assertNotEqual(result['status'], 'ok')
            self.assertNotIn('score', result)

    def test_complete_raw_match_is_unproven_and_controls_are_exact_empty(self):
        cases = m.select_cases(json.loads((ROOT / 'eval/corpus.json').read_bytes()),
            {'cases': json.loads((ROOT / 'eval/initial-ts-pins.json').read_bytes())['quiet_cases']},
            json.loads((ROOT / 'eval/controls.json').read_bytes()))
        results = [dict(id=c['id'], origin=c['origin'], status='ok', raw=c['reference_raw'],
                        lines=[] if c['origin'] == 'negative_control' else [{'text': c['reference_raw']}],
                        score=m.score_case(c, c['reference_raw'])) for c in cases]
        result = m.completion(results)
        self.assertEqual(result['status'], 'complete_diagnostic'); self.assertTrue(result['strict_scores_passed'])
        for key in m.QUALIFICATION: self.assertIs(result[key], False)
        self.assertIn('silently break', result['blocker']); self.assertIn('Android', result['blocker'])
        results[-1]['lines'] = [{'text': ''}]
        self.assertFalse(m.completion(results)['strict_scores_passed'])
        results[-1]['status'] = 'timeout'
        self.assertEqual(m.completion(results)['status'], 'incomplete_diagnostic')

    def test_archive_size_and_artifact_caps_fail_before_extract_or_write(self):
        from types import SimpleNamespace as Obj
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp)
            with patch.object(m.zipfile, 'ZipFile') as archive:
                archive.return_value.__enter__.return_value.infolist.return_value = [Obj(
                    filename='oversize', file_size=256 * 1024 ** 2 + 1, external_attr=0)]
                with self.assertRaises(ValueError): m.unpack_wheel(path / 'wheel.whl', path / 'out')
                archive.return_value.__enter__.return_value.extractall.assert_not_called()
            with self.assertRaises(ValueError): m.retain(path / 'completion.json', b'x' * (128 * 1024 + 1))
            with self.assertRaises(ValueError): m.retain(path / 'case.log', b'x' * (m.OUTPUT_BYTES + 1))
            self.assertFalse(list(path.iterdir()))

    def test_workflow_is_opt_in_and_guarded_before_acquisition(self):
        workflow = (ROOT / '.github/workflows/moonshine-canary.yml').read_text()
        self.assertIn('timeout-minutes: 45', workflow)
        self.assertIn("MOONSHINE_ORT_SINGLE_THREAD: '1'", workflow)
        self.assertIn("grep -F '[moonshine-canary]'", workflow)
        self.assertNotIn('workflow_dispatch', workflow)
        self.assertNotIn('pip install', workflow)
        self.assertLess(workflow.index('eval/moonshine_ci.py --preflight'), workflow.index('RAW EN only'))
        self.assertIn('lip-moonshine-evidence/', workflow)

    def test_single_line_failed_event_retains_latest_raw_prefix(self):
        case = next(c for c in json.loads((ROOT / 'eval/corpus.json').read_bytes())['cases'] if c['id'] == 'fleurs-en-013')
        with tempfile.TemporaryDirectory() as temp:
            work = Path(temp) / 'w'; evidence = Path(temp) / 'e'; work.mkdir(); evidence.mkdir()
            def simulated(command, env, log, **bounds):
                for text in ('early', ' latest prefix '):
                    event = {'event': 'LineTextChanged', 'line': {'text': text, 'line_id': 1}}
                    log.write(b'MOONSHINE_EVENT ' + json.dumps(event).encode() + b'\n')
                log.write(b'MOONSHINE_EVENT {"event":"Error","error":"observed failure"}\n')
                raise subprocess.CalledProcessError(1, command)
            with patch.object(m, 'run_logged', simulated): result = m.run_native(case, work, evidence, {})
            self.assertEqual(result['raw'], ' latest prefix ')
            self.assertEqual(result['status'], 'engine_error')
            self.assertIn(b'"event":"Error"', (evidence / (case['id'] + '.log')).read_bytes())

    def test_wrong_worker_host_never_reaches_native_or_resource_setup(self):
        with patch.object(m.sys, 'platform', 'darwin'), patch.object(m, 'Path', side_effect=AssertionError('Path reached')):
            for mode in ('--infer-worker', '--acquire-worker'):
                with self.subTest(mode=mode), self.assertRaises(ValueError): m.worker(mode, [])

    def test_malformed_events_reject_and_preserve_complete_last_valid_prefix(self):
        case = next(c for c in json.loads((ROOT / 'eval/corpus.json').read_bytes())['cases'] if c['id'] == 'fleurs-en-013')
        prefix = [{'text': ' valid prefix ', 'line_id': 1}, {'text': 'second', 'line_id': 2}]
        bad_lines = (None, [None], [{'line_id': 1}], [{'text': 'bad'}],
                     [{'text': None, 'line_id': 1}], [{'text': 3, 'line_id': 1}],
                     [{'text': 'bad', 'line_id': None}], [{'text': 'bad', 'line_id': []}],
                     [{'text': 'bad', 'line_id': True}], [{'text': 'bad', 'line_id': '1'}],
                     [{'text': 'bad', 'line_id': -1}], [{'text': 'bad', 'line_id': 2 ** 64}],
                     [{'text': 'candidate', 'line_id': 3}, {'line_id': 4}],
                     [{'text': 'first', 'line_id': 3}, {'text': 'duplicate', 'line_id': 3}])
        events = [{'event': 'Update', 'lines': lines} for lines in bad_lines]
        events += [{'event': 'LineTextChanged', 'line': lines[0]} for lines in bad_lines
                   if isinstance(lines, list) and lines and lines[0] is not None and len(lines) == 1]
        for event in events:
            for cap in (False, True):
                with self.subTest(event=event, cap=cap), tempfile.TemporaryDirectory() as temp:
                    work = Path(temp) / 'w'; evidence = Path(temp) / 'e'; work.mkdir(); evidence.mkdir()
                    def simulated(command, env, log, **bounds):
                        for value in ({'event': 'Update', 'lines': prefix}, event):
                            log.write(b'MOONSHINE_EVENT ' + json.dumps(value).encode() + b'\n')
                        if cap: raise ValueError('output cap')
                        native_events(log, work)
                        final = dict(id=case['id'], status='ok', raw=case['reference_raw'],
                                     lines=[{'text': case['reference_raw'], 'line_id': 1}], peak_rss_bytes=1024)
                        log.write(b'MOONSHINE_RESULT ' + json.dumps(final).encode() + b'\n')
                    with patch.object(m, 'run_logged', simulated): result = m.run_native(case, work, evidence, {})
                    self.assertEqual(result['status'], 'invalid_input_or_output')
                    self.assertEqual(result['lines'], prefix)
                    self.assertEqual(result['raw'], ' valid prefix \nsecond')
                    self.assertNotIn('score', result)
                    self.assertFalse(m.raw_passed(result))
                    for key in m.QUALIFICATION: self.assertIs(result[key], False)
                    self.assertEqual(len(list(evidence.iterdir())), 2)
                    self.assertEqual((evidence / (case['id'] + '.log')).read_bytes(), (work / (case['id'] + '.log')).read_bytes())
                    self.assertEqual(json.loads((evidence / (case['id'] + '-score.json')).read_bytes()), result)

    def test_valid_empty_update_replaces_failed_prefix_with_empty_snapshot(self):
        case = next(c for c in json.loads((ROOT / 'eval/corpus.json').read_bytes())['cases'] if c['id'] == 'fleurs-en-013')
        with tempfile.TemporaryDirectory() as temp:
            work = Path(temp) / 'w'; evidence = Path(temp) / 'e'; work.mkdir(); evidence.mkdir()
            def simulated(command, env, log, **bounds):
                for lines in ([{'text': 'earlier', 'line_id': 1}], []):
                    log.write(b'MOONSHINE_EVENT ' + json.dumps({'event': 'Update', 'lines': lines}).encode() + b'\n')
                raise subprocess.CalledProcessError(1, command)
            with patch.object(m, 'run_logged', simulated): result = m.run_native(case, work, evidence, {})
            self.assertEqual(result['status'], 'engine_error')
            self.assertEqual(result['lines'], [])
            self.assertEqual(result['raw'], '')
            self.assertEqual(len(list(evidence.iterdir())), 2)

    def test_malformed_final_lines_reject_and_retain_prefix(self):
        case = next(c for c in json.loads((ROOT / 'eval/corpus.json').read_bytes())['cases'] if c['id'] == 'fleurs-en-013')
        for lines in (None, [None], [{'line_id': 1}], [{'text': case['reference_raw']}],
                      [{'text': 3, 'line_id': 1}], [{'text': case['reference_raw'], 'line_id': False}]):
            with self.subTest(lines=lines), tempfile.TemporaryDirectory() as temp:
                work = Path(temp) / 'w'; evidence = Path(temp) / 'e'; work.mkdir(); evidence.mkdir()
                def simulated(command, env, log, **bounds):
                    log.write(b'MOONSHINE_EVENT {"event":"Update","lines":[{"text":"prefix","line_id":1}]}\n')
                    native_events(log, work)
                    final = dict(id=case['id'], status='ok', raw=case['reference_raw'], lines=lines, peak_rss_bytes=1024)
                    log.write(b'MOONSHINE_RESULT ' + json.dumps(final).encode() + b'\n')
                with patch.object(m, 'run_logged', simulated): result = m.run_native(case, work, evidence, {})
                self.assertNotEqual(result['status'], 'ok')
                self.assertEqual(result['raw'], 'prefix')
                self.assertNotIn('score', result)
                self.assertEqual(len(list(evidence.iterdir())), 2)

    def test_publisher_raw_line_schema_rejects_invalid_snapshot_and_transcript(self):
        from types import SimpleNamespace as Obj
        for fields in ({'line_id': 1}, {'text': 'bad'}, {'text': None, 'line_id': 1},
                       {'text': 'bad', 'line_id': True}, {'text': 'bad', 'line_id': []}):
            with self.subTest(fields=fields), self.assertRaises(ValueError): m.snapshot(Obj(**fields))
        for line_id in (0, 2 ** 64 - 1):
            self.assertEqual(m.snapshot(Obj(text=' raw ', line_id=line_id))['text'], ' raw ')
        class Stream:
            def add_listener(self, listener): self.listener = listener
            def start(self): pass
            def add_audio(self, samples, rate): pass
            def update_transcription(self): return Obj(lines=[Obj(text='bad', line_id=True)])
            def stop(self): return Obj(lines=[Obj(text='bad', line_id=True)])
        for method in ('Update', 'Stop', 'listener'):
            events = []; stream = Stream()
            if method == 'listener':
                def bad_stop():
                    stream.listener(type('LineTextChanged', (), {'stream_handle': 1, 'line': Obj(text='bad', line_id=True)})())
                    return Obj(lines=[])
                stream.stop = bad_stop
            with self.subTest(method=method), self.assertRaises(ValueError):
                m.feed_stream(stream, array('f', [0]) * (10240 if method == 'Update' else 2560), events.append)
            self.assertEqual(events, [])

    def test_known_source_logged_errors_reject_matching_success(self):
        case = next(c for c in json.loads((ROOT / 'eval/corpus.json').read_bytes())['cases'] if c['id'] == 'fleurs-en-013')
        for diagnostic in (b'Cross K/V not valid, call compute_cross_kv first\n',
                           b'State is null\n', b'Logits output is null\n'):
            with self.subTest(diagnostic=diagnostic), tempfile.TemporaryDirectory() as temp:
                work = Path(temp) / 'w'; evidence = Path(temp) / 'e'; work.mkdir(); evidence.mkdir()
                def simulated(command, env, log, **bounds):
                    log.write(b'MOONSHINE_EVENT {"event":"Update","lines":[{"text":"prefix","line_id":1}]}\n')
                    log.write(diagnostic); native_events(log, work)
                    final = dict(id=case['id'], status='ok', raw=case['reference_raw'],
                                 lines=[{'text': case['reference_raw'], 'line_id': 1}], peak_rss_bytes=1024)
                    log.write(b'MOONSHINE_RESULT ' + json.dumps(final).encode() + b'\n')
                with patch.object(m, 'run_logged', simulated): result = m.run_native(case, work, evidence, {})
                self.assertEqual(result['status'], 'invalid_input_or_output')
                self.assertEqual(result['raw'], 'prefix')
                self.assertNotIn('score', result)
                self.assertFalse(m.raw_passed(result))
                for key in m.QUALIFICATION: self.assertIs(result[key], False)
                self.assertIn(diagnostic, (evidence / (case['id'] + '.log')).read_bytes())
                self.assertEqual(json.loads((evidence / (case['id'] + '-score.json')).read_bytes()), result)


if __name__ == '__main__': unittest.main()
