"""Opt-in whole-artifact Q5/Q4 research; never Android or distribution acceptance."""
from collections import Counter
import hashlib
import math
import os
from pathlib import Path
import re
import resource
import shutil
import subprocess
import sys
import time

import initial_ts_ci as ts
from benchmark import units

REF = 'refs/heads/codex/lip-q4-quality'
WORKFLOW_REF = 'ihearttokyo/Lip/.github/workflows/quant-experiment.yml@' + REF
MARKER = '[q4-quality-canary]'
ARMS = ('q5_0', 'q4_0')
WORK_NAME, EVIDENCE_NAME = 'lip-quant-work', 'lip-quant-evidence'
INITIAL_PINS_SHA256 = '1f4e05b8b8d8fb7907789e6c3709ae7cd6e7cd014010dd4c47651f4fdbd6df6c'
INITIAL_RUNNER_SHA256 = '04adcd5d695b6ebdbc94cafcb1cd459fd801736a54a8ca04c828afef04c19c73'
Q4 = {'name': 'ggml-large-v3-turbo-q4_0.bin', 'revision': '2df9591948d91c0a80d47faa2a3a96d195ce138a',
      'url': 'https://huggingface.co/Pomni/whisper-large-v3-turbo-ggml-allquants/resolve/2df9591948d91c0a80d47faa2a3a96d195ce138a/ggml-large-v3-turbo-q4_0.bin',
      'bytes': 473992235, 'sha256': '2ceb779ca61ce46ed6e9aa43c4a0f8a89a37f683df3c0ba3119238fcd5852155'}
IDENTITY_KEYS = ('GITHUB_ACTIONS', 'RUNNER_OS', 'RUNNER_ARCH', 'RUNNER_ENVIRONMENT', 'ImageOS',
                 'GITHUB_REPOSITORY', 'GITHUB_REPOSITORY_OWNER', 'GITHUB_EVENT_NAME', 'GITHUB_REF',
                 'GITHUB_SERVER_URL', 'GITHUB_WORKFLOW_REF', 'GITHUB_SHA', 'GITHUB_RUN_ID',
                 'GITHUB_RUN_ATTEMPT', 'GITHUB_EVENT_PATH', 'RUNNER_TEMP')


def require_environment(env, event, head, message, platform, uid, release):
    expected = {'GITHUB_ACTIONS': 'true', 'RUNNER_OS': 'Linux', 'RUNNER_ARCH': 'X64',
                'RUNNER_ENVIRONMENT': 'github-hosted', 'ImageOS': 'ubuntu24',
                'GITHUB_REPOSITORY': 'ihearttokyo/Lip', 'GITHUB_REPOSITORY_OWNER': 'ihearttokyo',
                'GITHUB_EVENT_NAME': 'push', 'GITHUB_REF': REF,
                'GITHUB_SERVER_URL': 'https://github.com', 'GITHUB_WORKFLOW_REF': WORKFLOW_REF}
    if (platform != 'linux' or uid == 0 or release != '24.04' or
            any(env.get(k) != v for k, v in expected.items()) or
            any(not re.fullmatch(r'[1-9][0-9]*', env.get(k, '')) for k in ('GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT')) or
            not re.fullmatch(r'[0-9a-f]{40}', head) or head != env.get('GITHUB_SHA')):
        raise ValueError('Require exact unprivileged owned hosted Ubuntu24/X64 quant push identity')
    repo, commit = event.get('repository', {}), event.get('head_commit', {})
    if (repo.get('full_name') != 'ihearttokyo/Lip' or repo.get('private') is not False or
            repo.get('visibility') != 'public' or repo.get('owner', {}).get('login') != 'ihearttokyo' or
            event.get('ref') != REF or event.get('after') != head or commit.get('id') != head or
            not isinstance(message, str) or MARKER not in message or
            commit.get('message', '').rstrip('\n') != message.rstrip('\n')):
        raise ValueError('Public event, actual head and case-sensitive quant opt-in must agree')


def verify_sources(repository, pins):
    initial_path = repository / 'eval/initial-ts-pins.json'
    if (initial_path.is_symlink() or hashlib.sha256(initial_path.read_bytes()).hexdigest() != INITIAL_PINS_SHA256 or
            pins.get('schema_version') != 1 or pins.get('runtime_revision') != ts.RUNTIME_REVISION or
            set(pins['source_sha256']) != {'eval/initial_ts_ci.py', 'eval/initial-ts-pins.json',
                '.github/workflows/initial-ts-experiment.yml', 'app/src/main/cpp/whisper_jni.cpp',
                'third_party/whisper.cpp/src/whisper.cpp', 'NOTICE.md'} or
            pins['source_sha256']['eval/initial_ts_ci.py'] != INITIAL_RUNNER_SHA256 or
            pins['source_sha256']['eval/initial-ts-pins.json'] != INITIAL_PINS_SHA256):
        raise ValueError('Captured source pin scope/identity drift')
    initial = ts.load_json(initial_path)
    ts.verify_sources(repository, initial)
    ts.validate_quiet(initial, ts.load_json(repository / 'eval/corpus.json'))
    if pins['models'] != {'q5_0': initial['model'], 'q4_0': Q4}:
        raise ValueError('Only the two fixed complete model artifacts are admitted')
    for name, digest in pins['source_sha256'].items():
        path = repository / name
        if path.is_symlink() or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise ValueError('Frozen source identity drift: ' + name)
    return initial


def frozen_cases(repository, initial):
    return (ts.load_json(repository / 'eval/corpus.json')['cases'] + initial['quiet_cases'] +
            ts.load_json(repository / 'eval/controls.json')['cases'])


def paired_schedule(cases, frozen):
    if (len(cases) != 36 or len(frozen) != 36 or len({c['id'] for c in cases}) != 36 or
            len({c['id'] for c in frozen}) != 36 or
            {c['id']: c for c in cases} != {c['id']: c for c in frozen}):
        raise ValueError('Require exactly the unchanged 18 originals, 12 public quiet and 6 controls')
    return [(case, arm) for index, case in enumerate(sorted(cases, key=lambda c: c['id']))
            for arm in (ARMS if index % 2 == 0 else ARMS[::-1])]


def owned_directories(runner_temp):
    root = runner_temp.resolve(strict=True)
    work, evidence = root / WORK_NAME, root / EVIDENCE_NAME
    if any(p.exists() or p.is_symlink() for p in (work, evidence)):
        raise FileExistsError('Refuse existing quant work or evidence roots')
    evidence.mkdir(); work.mkdir()
    return work, evidence


def preflight(repository):
    # Reject local/native access before git, tools, allocations or acquisition.
    if sys.platform != 'linux' or os.geteuid() == 0:
        raise ValueError('Quant native/model execution is unprivileged hosted Linux only')
    release = dict(line.split('=', 1) for line in Path('/etc/os-release').read_text().splitlines() if '=' in line)
    event = ts.load_json(os.environ['GITHUB_EVENT_PATH'])
    head, message = ts.git(repository, 'rev-parse', 'HEAD'), ts.git(repository, 'log', '-1', '--format=%B')
    require_environment(os.environ, event, head, message, sys.platform, os.geteuid(),
                        release.get('VERSION_ID', '').strip('"') if release.get('ID') == 'ubuntu' else '')
    if ts.git(repository, 'status', '--porcelain', '--untracked-files=all', '--ignore-submodules=none'):
        raise ValueError('Require a clean actual checkout including recursive vendor')
    pins = ts.load_json(repository / 'eval/quant-pins.json'); initial = verify_sources(repository, pins)
    vendor = repository / 'third_party/whisper.cpp'
    if (ts.git(vendor, 'rev-parse', 'HEAD') != ts.RUNTIME_REVISION or
            ts.git(vendor, 'status', '--porcelain', '--untracked-files=all', '--ignore-submodules=none') or
            ts.git(repository, 'ls-tree', 'HEAD', 'third_party/whisper.cpp').split()[2] != ts.RUNTIME_REVISION):
        raise ValueError('Vendor must be the pristine pinned recursive checkout and actual gitlink')
    ts.patch_cli((vendor / 'examples/cli/cli.cpp').read_bytes(), initial)
    frozen = frozen_cases(repository, initial); paired_schedule(frozen, frozen)
    runner_temp = Path(os.environ['RUNNER_TEMP']).resolve(strict=True)
    if runner_temp == Path('/') or runner_temp.is_relative_to(repository):
        raise ValueError('Fresh runner work must be outside the source checkout')
    mem = re.search(r'^MemAvailable:\s+(\d+) kB$', Path('/proc/meminfo').read_text(), re.M)
    capacity = {'logical_cpus': os.cpu_count(), 'affinity_cpus': len(os.sched_getaffinity(0)),
                'available_memory_bytes': int(mem[1]) * 1024 if mem else 0,
                'free_disk_bytes': shutil.disk_usage(runner_temp).free}
    ts.require_capacity(min(capacity['logical_cpus'], capacity['affinity_cpus']),
                        capacity['available_memory_bytes'], capacity['free_disk_bytes'])
    return pins, initial, frozen, runner_temp, capacity


def read_output(path, cap=ts.OUTPUT_BYTES):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > cap:
        raise ValueError('Fixed runtime text output exceeds its bound or is not a regular file')
    with path.open('rb') as stream: data = stream.read(cap + 1)
    if len(data) > cap: raise ValueError('Runtime output grew beyond its bound')
    return data


def run_native(case, arm, audio, binary, model, work, evidence, env, pin):
    if arm not in ARMS or model != work / pin['name'] or pin['name'] != 'ggml-large-v3-turbo-' + arm + '.bin':
        raise ValueError('Only the paired fixed artifact paths are allowed')
    label = case['id'] + '-' + arm
    if not re.fullmatch(r'[A-Za-z0-9_-]+-q[45]_0', label): raise ValueError('Unadmitted output label')
    outputs = work / label; outputs.mkdir(); stem = outputs / 'transcript'
    command = [sys.executable, '-B', str(Path(__file__).resolve()), '--infer-worker',
               str(binary), str(model), str(audio), case['language'], str(stem)]
    result = {'id': case['id'], 'arm': arm, 'language': case['language'], 'audio_sha256': case['sha256'],
              'origin': case['origin'], 'model': pin, 'command': ts.cli_command(binary, model, audio, case['language'], stem),
              'timing_scope': 'cold_per_clip_CLI_not_warm_Android', 'address_space_bytes': 8 * ts.GIB,
              'clip_timeout_seconds': 120, 'confidence': 'not_in_standard_non_full_JSON', 'decoder_trace': 'not_collected'}
    started = time.perf_counter(); cleanup_failed = False
    try:
        with (outputs / 'native.log').open('xb') as log:
            ts.run_logged(command, dict(env, LIP_INITIAL_TS='1.0', LIP_QUANT_ARM=arm), log, timeout=120, byte_limit=32 * 1024)
        raw, segments = ts.parse_outputs(read_output(stem.with_suffix('.txt')), read_output(stem.with_suffix('.json')),
                                         case['language'], str(model))
        lines = read_output(outputs / 'native.log', 32 * 1024).splitlines()
        actual = [ts.strict_json(line[len(b'LIP_PARAMS '):]) for line in lines if line.startswith(b'LIP_PARAMS ')]
        expected = ts.expected_params('1.0')
        if (len(actual) != 1 or set(actual[0]) != set(expected) or
                any(type(actual[0][k]) not in (int, float) or not math.isfinite(actual[0][k]) or
                    not math.isclose(actual[0][k], v, abs_tol=2e-7, rel_tol=0) for k, v in expected.items())):
            raise ValueError('Actual decoder parameters differ from fixed returned JNI policy')
        peak = int(read_output(outputs / 'peak-kib.txt', 128).decode().strip()) * 1024
        if not 0 < peak <= 8 * ts.GIB: raise ValueError('Peak RSS is missing or outside its bound')
        result.update(status='ok', raw=raw, score=ts.score_case(case, raw), segments=segments,
                      actual_params=actual[0], peak_rss_bytes=peak)
    except subprocess.TimeoutExpired as error:
        cleanup_failed = error.timeout == 5  # Fixed owned-group join bound, not the clip deadline.
        result['status'] = 'cleanup_timeout' if cleanup_failed else 'timeout'
    except subprocess.CalledProcessError as error:
        result.update(status='engine_error', exit_code=error.returncode)
    except (ValueError, OSError, KeyError, TypeError) as error:
        result.update(status='invalid_input_or_output', error_type=type(error).__name__)
    finally:
        result['elapsed_seconds'] = time.perf_counter() - started
        for name, suffix, cap in [('native.log', '.log', 32 * 1024), ('transcript.txt', '-raw.txt', ts.OUTPUT_BYTES),
                                 ('transcript.json', '-segments.json', ts.OUTPUT_BYTES), ('peak-kib.txt', '-peak.txt', 128)]:
            path = outputs / name
            if path.exists() or path.is_symlink():
                # Preserve a bounded prefix even when the failed native output exceeds the cap.
                if path.is_symlink() or not path.is_file(): raise ValueError('Refuse nonregular native evidence')
                with path.open('rb') as stream: data = stream.read(cap)
                if path.stat().st_size > cap:
                    result.update(status='invalid_input_or_output', output_exceeded_bound=True)
                ts.retain(evidence / (label + suffix), data)
        if not (evidence / (label + '.log')).exists(): ts.retain(evidence / (label + '.log'), b'')
        if len(ts.encode(result)) > ts.OUTPUT_BYTES:
            # Full unfiltered outputs remain in their separate capped files.
            result.pop('raw', None); result.pop('segments', None)
            result.update(status='invalid_input_or_output', record_exceeded_bound=True)
        ts.save(evidence / (label + '-score.json'), result)
    if cleanup_failed: raise RuntimeError('Owned process group was not joined; remaining pairs held')
    return result


def strict_pass(case, result):
    return (result.get('status') == 'ok' and result.get('score', {}).get('passed') is True and
            (case['origin'] != 'negative_control' or (result.get('raw') == '' and result.get('segments') == [])))


def completion(results, cases):
    schedule = paired_schedule(cases, cases)
    complete = (len(results) == 72 and [(r.get('id'), r.get('arm')) for r in results] ==
                [(c['id'], arm) for c, arm in schedule] and all(r.get('status') == 'ok' for r in results))
    report = {'schema_version': 1, 'status': 'complete_diagnostic' if complete else 'incomplete_diagnostic',
              'runtime_success': complete, 'acceptance': 'not_assessed', 'quality_passed': False,
              'measured_nonregression_passed': None, 'screen_eligible_for_independent_review': False,
              'results_count': len(results), 'execution_order': [
                  {'id': r['id'], 'arm': r['arm'], 'status': r['status']} for r in results],
              'semantic_fact_and_repetition_review': 'not_assessed; parent reviews all raw/segments, not only flagged pairs',
              'production_policy_scope': 'Matched decoder parameters only; standard CLI lacks production exact-zero bypass.',
              'promotion': 'Never automatic; no production/model/APK change or distribution authorized.'}
    if not complete: return report
    by_key = {(r['id'], r['arm']): r for r in results}; pairs = []
    summary = {arm: {population: {'total': 0, 'strict_passes': 0} for population in ('original', 'quiet', 'controls')} for arm in ARMS}
    for case in sorted(cases, key=lambda c: c['id']):
        base, candidate = (by_key[(case['id'], arm)] for arm in ARMS)
        for arm, row in zip(ARMS, (base, candidate)):
            if row['score'] != ts.score_case(case, row['raw']): raise ValueError('Retained score differs from frozen scorer')
            population = 'controls' if case['origin'] == 'negative_control' else 'quiet' if '-minus' in case['id'] else 'original'
            summary[arm][population]['total'] += 1
            summary[arm][population]['strict_passes'] += int(strict_pass(case, row))
        lost = [check['name'] for check, new in zip(base['score']['checks'], candidate['score']['checks'])
                if check['passed'] and not new['passed']]
        # A duplicate-segment witness is not a complete semantic repetition detector.
        def repeated(row):
            counts = Counter(tuple(units(s['text'], case['language'])) for s in (row.get('segments') or []))
            return sum(count - 1 for text, count in counts.items() if text)
        repeated_increase = repeated(candidate) > repeated(base)
        increased = candidate['score']['raw']['errors'] > base['score']['raw']['errors']
        failed_pass = strict_pass(case, base) and not strict_pass(case, candidate)
        pairs.append({'id': case['id'], 'population': population,
                      'errors_q5': base['score']['raw']['errors'], 'errors_q4': candidate['score']['raw']['errors'],
                      'reference_units': base['score']['raw']['reference_units'],
                      'increased_errors': increased, 'new_failed_anchors': lost, 'lost_strict_pass': failed_pass,
                      'duplicate_segment_increase': repeated_increase, 'raw_changed': base['raw'] != candidate['raw'],
                      'fact_anchor_outcomes': {arm: row['score']['checks'] for arm, row in zip(ARMS, (base, candidate))},
                      'measured_regression': increased or bool(lost) or failed_pass or repeated_increase,
                      'historical_regression_case': case['id'] == 'fleurs-zh-020'})
    nonregression = not any(p['measured_regression'] for p in pairs)
    quality = all(strict_pass(case, by_key[(case['id'], arm)]) for case in cases for arm in ARMS)
    candidate_quality = all(strict_pass(case, by_key[(case['id'], 'q4_0')]) for case in cases)
    quiet_improved = summary['q4_0']['quiet']['strict_passes'] > summary['q5_0']['quiet']['strict_passes']
    report.update(quality_passed=quality, candidate_quality_passed=candidate_quality, summary=summary, pairs=pairs,
                  measured_nonregression_passed=nonregression, quiet_strict_passes_increased=quiet_improved,
                  candidate_controls_exact_empty=summary['q4_0']['controls']['strict_passes'] == 6,
                  screen_eligible_for_independent_review=nonregression and quiet_improved and candidate_quality)
    return report


def infer_worker(args):
    repository = Path(__file__).resolve().parent.parent
    pins, initial, cases, runner_temp, _ = preflight(repository)
    arm = os.environ.get('LIP_QUANT_ARM')
    if len(args) != 5 or arm not in ARMS or os.environ.get('LIP_INITIAL_TS') != '1.0':
        raise ValueError('Fixed quant worker arguments/1.0 timestamp required')
    binary, model, audio, language, stem = map(str, args)
    root = runner_temp / WORK_NAME
    case_id = Path(stem).parent.name.removesuffix('-' + arm)
    case = next((c for c in cases if c['id'] == case_id), None)
    if case is None: raise ValueError('Unadmitted worker case')
    population = 'controls' if case['origin'] == 'negative_control' else 'quiet' if '-minus' in case_id else 'original'
    expected = [root / 'build/bin/whisper-cli', root / pins['models'][arm]['name'], root / population / case['audio'],
                root / (case_id + '-' + arm) / 'transcript']
    if language != case['language'] or any(Path(value).resolve() != path for value, path in zip((binary, model, audio, stem), expected)):
        raise ValueError('Worker paths/language escape the fixed owned allowlist')
    bindings = ts.load_json(root / 'worker-bindings.json')
    if (ts.file_identity(Path(binary), 128 * 1024 * 1024) != bindings['binary'] or
            ts.file_identity(Path(bindings['time']['path']), 128 * 1024 * 1024) !=
            {k: bindings['time'][k] for k in ('path', 'bytes', 'sha256')} or
            bindings['models'][arm]['sha256'] != pins['models'][arm]['sha256'] or
            Path(model).is_symlink() or Path(model).stat().st_size != pins['models'][arm]['bytes']):
        raise ValueError('Verified binary/time/model witness changed before native launch')
    ts.verify_audio(case, root / population)
    resource.setrlimit(resource.RLIMIT_AS, (8 * ts.GIB, 8 * ts.GIB))
    resource.setrlimit(resource.RLIMIT_FSIZE, (ts.OUTPUT_BYTES, ts.OUTPUT_BYTES))
    command = [bindings['time']['path'], '-f', '%M', '-o', str(Path(stem).parent / 'peak-kib.txt'),
               *ts.cli_command(binary, model, audio, language, stem)]
    os.execve(command[0], command, os.environ)


def main():
    repository = Path(__file__).resolve().parent.parent
    pins, initial, frozen, runner_temp, capacity = preflight(repository)
    if sys.argv[1:] == ['--preflight']:
        print('Exact quant hosted preflight passed; no tool query/acquisition/build/inference.')
        return 0
    if sys.argv[1:]: raise ValueError('Only preflight or the complete paired screen is supported')
    work, evidence = owned_directories(runner_temp)
    started = time.monotonic(); deadline = started + ts.JOB_SECONDS; results = []
    report = {'schema_version': 1, 'status': 'incomplete_diagnostic', 'runtime_success': False, 'acceptance': 'not_assessed',
              'quality_passed': False, 'fresh_model_bytes_verified': False, 'capacity': capacity, 'identity': {k: os.environ.get(k) for k in IDENTITY_KEYS if k != 'GITHUB_EVENT_PATH'},
              'limits': {'artifact_bytes': ts.ARTIFACT_BYTES, 'model_bytes': sum(p['bytes'] for p in pins['models'].values()),
                         'corpus_audio_bytes': 9615124, 'metadata_per_request_bytes': 2 * 1024 * 1024,
                         'metadata_max_requests': 6, 'model_timeout_seconds_each': 600, 'corpus_timeout_seconds': 600,
                         'build_timeout_seconds': 900, 'build_workers': 2, 'clip_timeout_seconds': 120,
                         'address_space_bytes': 8 * ts.GIB, 'native_log_bytes': 32 * 1024,
                         'output_bytes_each': ts.OUTPUT_BYTES, 'experiment_wall_seconds': ts.JOB_SECONDS, 'hosted_job_minutes': 180},
              'scorer_version': ts.SCORER_VERSION, 'source_sha256': dict(initial['source_sha256'], **pins['source_sha256']),
              'pins_sha256': hashlib.sha256((repository / 'eval/quant-pins.json').read_bytes()).hexdigest(),
              'runner_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'test_sha256': hashlib.sha256((repository / 'eval/test_quant_ci.py').read_bytes()).hexdigest(),
              'workflow_sha256': hashlib.sha256((repository / '.github/workflows/quant-experiment.yml').read_bytes()).hexdigest(),
              'prior_failures_and_limits': pins['prior_evidence'], 'artifact_provenance': pins['provenance']}
    try:
        report['phase'] = 'freeze_installed_tools'; tools = ts.freeze_tools(); report['tools'] = tools
        env = {k: os.environ[k] for k in IDENTITY_KEYS}
        env.update(PATH='/usr/bin:/bin', HOME=str(work), LANG='C.UTF-8', OMP_NUM_THREADS='4', OPENBLAS_NUM_THREADS='1')
        report['phase'] = 'build_common_pinned_CPU_CLI'
        binary, build = ts.build_cli(repository, work, evidence, initial, env, tools)
        report['build'] = build; ts.save(evidence / 'build.json', build)
        report['phase'] = 'complete_both_pinned_model_acquisitions'; models = {}; identities = {}
        for arm in ARMS:
            pin = pins['models'][arm]; model = work / pin['name']; ts.download(pin, model)
            identity = ts.file_identity(model, pin['bytes'])
            if identity['bytes'] != pin['bytes'] or identity['sha256'] != pin['sha256']:
                raise ValueError('Complete model readback differs from the fixed pin; no partial fallback')
            models[arm] = model; identities[arm] = identity
        report.update(models=identities, fresh_model_bytes_verified=True)
        report['phase'] = 'complete_public_corpus_acquisition'; corpus_path = ts.acquire_corpus(repository, work, initial)
        corpus = ts.load_json(corpus_path); quiet = ts.prepare_quiet(corpus_path, work / 'quiet', initial)
        controls_root = work / 'controls'; controls_root.mkdir(); ts.prepare_controls(controls_root)
        if hashlib.sha256((controls_root / 'controls.json').read_bytes()).hexdigest() != initial['source_sha256']['eval/controls.json']:
            raise ValueError('Generated controls drift from the frozen source')
        controls = ts.load_json(controls_root / 'controls.json'); cases = []; roots = {}
        for name, manifest in (('original', corpus), ('quiet', quiet), ('controls', controls)):
            ts.save(evidence / (name + '-manifest.json'), manifest)
            for case in manifest['cases']:
                ts.verify_audio(case, work / name); roots[case['id']] = work / name; cases.append(case)
        schedule = paired_schedule(cases, frozen)
        ts.save(evidence / 'pair-order.json', [{'id': c['id'], 'arm': a} for c, a in schedule])
        ts.verify_tools(tools)
        if ts.file_identity(binary, 128 * 1024 * 1024) != build['binary']:
            raise ValueError('Common binary changed before the paired screen')
        with (work / 'worker-bindings.json').open('xb') as target:
            target.write(ts.encode({'binary': build['binary'], 'time': tools['time'], 'models': identities}))
        ts.retain(evidence / 'model-notice.txt', (repository / 'NOTICE.md').read_bytes())
        ts.save(evidence / 'attribution.json', {k: corpus[k] for k in ('license', 'attribution', 'source_card', 'paper', 'source_revision', 'license_url')} | {
            'models': pins['provenance'], 'transformations': 'Byte-identical pinned public quiet derivatives; no new gain/crop/VAD/filter policy.',
            'controls': 'MIT generated silence and seeded uniform noise; two WAV identities, six language labels; standard CLI lacks production zero bypass.',
            'native': 'OpenAI Whisper / ggml authors MIT; upstream license and model notice retained; no Android cancellation qualification.'})
        ts.save(evidence / 'provenance.json', report); report['phase'] = 'matched_paired_inference'
        for case, arm in schedule:
            ts.require_clip_budget(deadline, time.monotonic())
            report['current_case'] = {'id': case['id'], 'arm': arm}
            results.append(run_native(case, arm, ts.verify_audio(case, roots[case['id']]), binary, models[arm], work, evidence, env, pins['models'][arm]))
        report.pop('current_case', None)
        paired = completion(results, frozen)
        # Keep the completion receipt small enough to survive a saturated artifact budget.
        ts.save(evidence / 'comparison.json', paired)
        report.update({k: v for k, v in paired.items() if k != 'pairs'}); report['phase'] = 'paired_screen_finished'
    except Exception as error:
        # Acquisition errors can contain signed URLs; retain safe classes, never messages.
        report.update(status='incomplete_diagnostic', runtime_success=False, error_type=type(error).__name__,
                      results_count=len(results), execution_order=[{'id': r['id'], 'arm': r['arm'], 'status': r['status']} for r in results])
    report['experiment_elapsed_seconds'] = time.monotonic() - started
    ts.save(evidence / 'completion.json', report); ts.evidence_size(evidence)
    print(ts.encode({'status': report['status'], 'acceptance': 'not_assessed', 'evidence': str(evidence)}).decode().strip())
    return 0 if report['runtime_success'] else 1


if __name__ == '__main__':
    try:
        if sys.argv[1:2] == ['--infer-worker']: infer_worker(sys.argv[2:])
        else: raise SystemExit(main())
    except Exception as error:
        print('Quant screen failed: ' + type(error).__name__ + '; no acceptance claim.', file=sys.stderr)
        raise SystemExit(1) from None
