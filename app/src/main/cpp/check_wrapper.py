#!/usr/bin/env python3
"""Static JNI contract check. Does not claim native inference/lifecycle evidence."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--cancel-patch', type=Path, metavar='WHISPER_CPP',
                    help='Also check scheduler cancellation in this generated source')
args = parser.parse_args()

root = Path(__file__).resolve().parents[4]
engine = root / 'app/src/main/java/dev/lip/speech/WhisperEngine.kt'
assert engine.is_file(), 'WhisperEngine is missing'
kotlin = engine.read_text()
native = (root / 'app/src/main/cpp/whisper_jni.cpp').read_text()
cmake = (root / 'app/src/main/cpp/CMakeLists.txt').read_text()

methods = set(re.findall(r'external fun (\w+)\(', kotlin))
exports = set(re.findall(r'Java_dev_lip_speech_WhisperEngine_(\w+)\(', native))
assert methods == exports == {'nativeOpen', 'nativeTranscribe', 'nativeAbort', 'nativeClose'}
assert 'synchronized(inference)' in kotlin and 'synchronized(state)' in kotlin
assert 'closed = true' in kotlin and 'handle = 0' in kotlin
assert 'cancellation != revision' in kotlin and 'CancellationException' in kotlin
assert 'std::atomic<bool>' in native and 'params.abort_callback = should_abort' in native
assert 'params.encoder_begin_callback' in native
for flag in ('translate', 'print_progress', 'print_realtime', 'print_timestamps', 'print_special'):
    assert f'params.{flag} = false;' in native, flag
assert 'params.no_context = true;' in native
assert 'context_params.use_gpu = false;' in native
assert 'whisper_log_set' in native and 'print_timings' not in native
assert native.count('catch (const std::bad_alloc &)') == 2
assert native.count('catch (const std::exception &)') == 2
assert 'std::min(4u,' in native
assert 'whisper_full_get_segment_no_speech_prob' in native
assert 'NewByteArray' in native and 'GetByteArrayRegion' in native
assert '([BJJF)V' in native and 'Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(utf8)).toString()' in kotlin
assert 'pcm.size in 1..MAX_SAMPLES' in kotlin and 'MAX_SAMPLES = 16_000 * 30' in kotlin
assert 'MAX_PROMPT_CHARACTERS = 1_024' in kotlin
transcribe = kotlin[kotlin.index('fun transcribe('):kotlin.index('/** Abort the active inference.')]
guarded_call = ('val segments = if (ExactZeroPcm.matches(pcm)) emptyArray<Segment>() else '
                'nativeTranscribe(handle, pcm, language.toByteArray(Charsets.UTF_8), dictionaryPrompt.toByteArray(Charsets.UTF_8))')
assert guarded_call in ' '.join(transcribe.split()), 'Exact-zero PCM must bypass only nativeTranscribe'
assert 'fun matches(pcm: FloatArray): Boolean = pcm.all { it == 0f }' in kotlin
guards = ('require(pcm.size in 1..MAX_SAMPLES)', 'require(language in setOf("en", "ja", "zh"))',
          "require(dictionaryPrompt.length <= MAX_PROMPT_CHARACTERS && '\\u0000' !in dictionaryPrompt)",
          'check(!closed)', 'nativeAbort(handle, false)', 'cancellation\n',
          'val segments = if', 'if (closed || cancellation != revision)', 'segments.toList()')
positions = [transcribe.index(guard) for guard in guards]
assert positions == sorted(positions), 'Exact-zero guard must preserve validation and lifecycle ordering'
assert transcribe.count('nativeTranscribe(') == 1 and 'return' not in transcribe
assert re.search(r'synchronized\(state\) \{\s*if \(closed \|\| cancellation != revision\) '
                 r'throw CancellationException\("Speech inference canceled"\)\s*\}\s*segments.toList\(\)', transcribe)
assert 'std::isfinite(sample)' in native
for option in ('BUILD_SHARED_LIBS', 'WHISPER_BUILD_TESTS', 'WHISPER_BUILD_EXAMPLES',
               'WHISPER_BUILD_SERVER', 'GGML_NATIVE', 'GGML_OPENMP', 'WHISPER_CURL'):
    assert re.search(rf'set\({option} OFF\b', cmake), option
pin = subprocess.check_output(['git', '-C', str(root / 'third_party/whisper.cpp'),
                               'rev-parse', 'HEAD'], text=True).strip()
assert pin == '306c88f4d1286aec1bf96e544632897886af5501'
assert (root / 'third_party/whisper.cpp/LICENSE').read_text().startswith('MIT License')
runner = (root / 'app/src/androidTest/java/dev/lip/LocalAsrRunner.kt').read_text()
closed_assertion = 'check(runCatching { engine.transcribe(pcm, "en") }.exceptionOrNull()?.javaClass == IllegalStateException::class.java)'
assert runner.count(closed_assertion) == 2, 'Both closed-engine checks must reject a normal return without catching their own assertion'
assert 'catch (_: IllegalStateException)' not in runner, 'Closed assertion may catch its own failure'
cancel_runner = runner[runner.index('private fun cancelActive('):]
assert cancel_runner.count('native.size == expectedWorkers') == 2, \
    'Both mel and post-mel cohorts must match the hardware-calibrated worker count, not one worker'
assert 'native.size == 1' not in cancel_runner
assert 'import android.system.Os\n' in runner and 'import android.system.OsConstants\n' in runner
assert 'val onlineProcessors = Os.sysconf(OsConstants._SC_NPROCESSORS_ONLN)' in cancel_runner
assert 'val expectedWorkers = onlineProcessors.coerceIn(1L, 4L).toInt() - 1' in cancel_runner
assert 'params.n_threads = static_cast<int>(std::min(4u, std::max(1u, std::thread::hardware_concurrency())));' in native
assert 'check(expectedWorkers > 0)' in cancel_runner, 'Invalid/one-core queries cannot provide a native worker witness'
assert '.put("onlineProcessors", onlineProcessors).put("expectedNativeWorkers", expectedWorkers)' in cancel_runner
assert 'if (active && native.size == expectedWorkers && firstCohort.isEmpty())' in cancel_runner
assert 'firstCohort = native.map { it.identity }.toSet()' in cancel_runner
assert re.search(r'val afterFirstCohort = native.size == expectedWorkers && firstCohort.isNotEmpty\(\) &&\s*'
                 r'native.all \{ it.identity !in firstCohort \}', cancel_runner)
assert 'check(progressed(caller.tid) && native.all { progressed(it.tid) })' in cancel_runner, \
    'Caller and every owned native worker must progress across the stable two-second witness'
assert 'native.any { progressed(it.tid) }' not in cancel_runner
if args.cancel_patch:
    vendor = args.cancel_patch.read_text()
    helper = vendor[vendor.index('static bool ggml_graph_compute_helper(\n      ggml_backend_sched_t'):
                    vendor.index('// TODO: move these functions to ggml-base')]
    assert 'ggml_abort_callback   abort_callback,' in helper, 'Scheduler drops abort_callback'
    assert 'void * abort_callback_data,' in helper, 'Scheduler drops abort_callback_data'
    assert '(ggml_backend_set_abort_callback_t) ggml_backend_reg_get_proc_address' in helper
    assert re.search(r'if \(set_abort_callback_fn\) \{\s*'
                     r'set_abort_callback_fn\(backend, abort_callback, abort_callback_data\);\s*\}', helper), \
        'Register callbacks even when null, clearing reused backends'
    assert 'fn_set_n_threads(backend, n_threads);' in helper
    assert 'const bool t = (ggml_backend_sched_graph_compute(sched, graph) == GGML_STATUS_SUCCESS);' in helper
    assert 'if (!t || sched_reset)' in helper and 'ggml_backend_sched_reset(sched);' in helper
    encoder = vendor[vendor.index('static bool whisper_encode_internal('):
                     vendor.index('static struct ggml_cgraph * whisper_build_graph_decoder(')]
    decoder = vendor[vendor.index('static bool whisper_decode_internal('):
                     vendor.index('//  500 -> 00:05.000')]
    call = 'ggml_graph_compute_helper(sched, gf, n_threads, abort_callback, abort_callback_data)'
    assert encoder.count(call) == 3, 'Conv/encoder/cross must forward both callbacks'
    assert decoder.count(call) == 1, 'Decoder must forward both callbacks'
    assert 'ggml_graph_compute_helper(sched, gf, vctx->n_threads, nullptr, nullptr, false)' in vendor
    assert vendor.count('ggml_graph_compute_helper(') == 9, 'Unexpected helper caller or overload'
    patches = root / 'app/src/main/cpp/patches'
    metadata = json.loads((patches / 'whisper-scheduler-abort.json').read_text())
    for path, key in ((root / 'third_party/whisper.cpp/src/whisper.cpp', 'input_sha256'),
                      (patches / 'whisper-scheduler-abort.patch', 'patch_sha256'),
                      (args.cancel_patch, 'output_sha256')):
        assert hashlib.sha256(path.read_bytes()).hexdigest() == metadata[key], key
    print('OK (static): scheduler abort registration, four inference callsites, VAD null/reset semantics, patch scope/hashes')
print('OK (static): pin/license, JNI parity, bounded PCM/prompt, exact-zero routing, UTF-8, cancellation wiring, CPU/no-log/no-network config')
