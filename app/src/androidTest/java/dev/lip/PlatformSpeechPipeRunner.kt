package dev.lip

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Closed multilingual file-fed fixture probe; not microphone, insertion, or a corpus accuracy score. */
class PlatformSpeechPipeRunner : Instrumentation() {
    private lateinit var args: Bundle
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); args = arguments ?: Bundle(); start() }

    override fun onStart() {
        val report = JSONObject().put("event", "platform_speech_pipe").put("passed", false)
            .put("backend_changed", false)
            .put("result_scope", "probe acceptance, not corpus accuracy").put("scored_result", JSONObject.NULL)
            .put("registry_manifest_sha256", REGISTRY_MANIFEST_SHA256)
            .put("microphone_playback", false).put("model_download_requested", false)
            .put("pacing", "unpaced file-fed PCM16 little-endian mono 16000 Hz")
            .put("raw_selection", "index 1 for exactly two formatted/raw candidates; otherwise index 0")
            .put("transcript_selection", "first full final snapshot if present; otherwise ordered segments")
        val events = JSONArray()
        val segments = ArrayList<List<String>>()
        val segmentTimesNs = ArrayList<Long>()
        val segmentAcceptedBytes = ArrayList<Long>()
        val seenSegments = HashSet<List<String>>()
        var finalCandidates: List<String>? = null
        var duplicateSegments = 0
        var duplicateFinals = 0
        var conflictingFinal = false
        var bounded = true
        var lateCallbacks = 0L
        var rmsCallbacks = 0L
        var bufferCallbacks = 0L
        var recognitionError: Int? = null
        var ghostCandidateSeen = false
        var fixtureId: String? = null
        var admittedFixture: Fixture? = null
        val canceled = AtomicBoolean()
        val terminal = AtomicReference<String?>()
        val recognitionStarted = AtomicLong()
        val terminalAt = AtomicLong()
        val terminalAtNs = AtomicLong()
        val acceptedBytes = AtomicLong()
        val eofAt = AtomicLong()
        val eofAtNs = AtomicLong()
        val writerStartedNs = AtomicLong()
        val firstZeroAcceptedNs = AtomicLong()
        val secondSourceAcceptedNs = AtomicLong()
        val maxPaceLatenessNs = AtomicLong()
        val packetCount = AtomicLong()
        val boundaryPackets = ArrayList<JSONObject>()
        val writerError = AtomicReference<String?>()
        val writerClosed = AtomicBoolean()
        val eofCompletionFence = Any()
        val writerDone = CountDownLatch(1)
        val completion = CountDownLatch(1)
        var recognizer: SpeechRecognizer? = null
        var pipe: Array<ParcelFileDescriptor>? = null
        var writer: Thread? = null
        var home: Activity? = null
        var cleanupOk = true
        var runFailed = false
        var stage = "isolation"
        fun open() = terminal.get() == null && !canceled.get()
        fun event(name: String, candidates: List<String>? = null, code: Int? = null) {
            val late = !open()
            if (late) lateCallbacks++
            if (events.length() >= 128) { bounded = false; return }
            val item = JSONObject().put("type", name).put("late_ignored", late)
                .put("since_start_ms", SystemClock.elapsedRealtime() - recognitionStarted.get())
            if (candidates != null) item.put("candidates", JSONArray(candidates)).put("selected_raw", raw(candidates))
            if (code != null) item.put("code", code)
            events.put(item)
        }
        fun resultEvent(name: String, bundle: Bundle): List<String> {
            val values = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            if (open() && values.any { it.isNotBlank() }) ghostCandidateSeen = true
            if (values.size > 4 || values.any { it.length > 512 }) bounded = false
            return values.take(4).map { it.take(512) }.also { event(name, it) }
        }
        fun complete(outcome: String) {
            synchronized(eofCompletionFence) {
                val now = SystemClock.elapsedRealtime()
                val eof = eofAt.get()
                val completionCandidate = outcome == "segmented_end" ||
                    (admittedFixture?.control == true && outcome == "error" && recognitionError == SpeechRecognizer.ERROR_NO_MATCH)
                val boundedOutcome = when {
                    completionCandidate && !completionWithinEofWindow(eof, now) ->
                        if (eof <= 0 || now < eof) "completion_before_eof" else "completion_timeout"
                    eof > 0 && now - eof > AFTER_EOF_MS -> "completion_timeout"
                    else -> outcome
                }
                if (terminal.compareAndSet(null, boundedOutcome)) {
                    terminalAt.set(now)
                    terminalAtNs.set(SystemClock.elapsedRealtimeNanos())
                    completion.countDown()
                }
            }
        }
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle) = event("ready")
            override fun onBeginningOfSpeech() = event("beginning")
            override fun onRmsChanged(rmsdB: Float) { rmsCallbacks++ }
            override fun onBufferReceived(buffer: ByteArray) { bufferCallbacks++ }
            override fun onEndOfSpeech() = event("speech_end")
            override fun onPartialResults(partialResults: Bundle) { resultEvent("partial", partialResults) }
            override fun onSegmentResults(segmentResults: Bundle) {
                val values = resultEvent("segment", segmentResults)
                if (!open()) return
                // Repeated payloads are ambiguous without segment IDs: never silently score deduplicated text as a pass.
                if (rejectDuplicateSegment(admittedFixture?.paced == true, seenSegments, values)) duplicateSegments++
                else if (segments.size < 32) {
                    if (admittedFixture?.paced != true) seenSegments.add(values)
                    segments.add(values)
                    segmentTimesNs.add(SystemClock.elapsedRealtimeNanos())
                    segmentAcceptedBytes.add(acceptedBytes.get())
                } else bounded = false
            }
            override fun onResults(results: Bundle) {
                val values = resultEvent("final", results)
                if (!open()) return
                if (finalCandidates == null) finalCandidates = values
                else { duplicateFinals++; if (finalCandidates != values) conflictingFinal = true }
            }
            override fun onEndOfSegmentedSession() {
                event("segmented_end")
                if (open()) complete("segmented_end")
            }
            override fun onError(error: Int) {
                event("error", code = error)
                if (open()) { recognitionError = error; complete("error"); canceled.set(true) }
            }
            override fun onEvent(eventType: Int, params: Bundle) = event("provider_event", code = eventType)
        }
        try {
            check(Build.HARDWARE in listOf("ranchu", "goldfish") && Build.PRODUCT.contains("sdk"))
            check(targetContext.packageName == "dev.lip.android" && Build.VERSION.SDK_INT >= 33)
            stage = "completion_boundary_self_check"
            checkCompletionBoundary()
            checkRegistryAndControlBoundaries()
            checkInstalledLanguageMapping()
            checkContinuousHelpers()
            checkShortPacingHelpers()
            check(raw(listOf("formatted", "raw")) == "raw" && raw(listOf("first")) == "first")
            check(words(REFERENCE).size == 17 && words(REFERENCE.replace("three", "3")) != words(REFERENCE))
            stage = "admitted_fixture_argument"
            val requestedId = args.getString("fixture_id")
            val admitted = fixtureArgumentValid(args.keySet(), requestedId)
            report.put("fixture_argument_rejected", !admitted).put("fixture_id_missing", requestedId == null)
                .put("override_keys_rejected", args.keySet() != setOf("fixture_id"))
            check(admitted)
            val id = checkNotNull(requestedId)
            fixtureId = id
            val fixture = checkNotNull(FIXTURES[id]).also { admittedFixture = it }
            report.put("fixture", fixtureId).put("locale", fixture.locale)
                .put("fixture_kind", if (fixture.control) "negative_control" else "human_recording")
                .put("external_scoring_required", !fixture.control)
                .put("scoring_status", if (fixture.control) "control assertions only" else "external original scorer v2 required")
            if (fixture.paced) report.put("registry_manifest_sha256", CONTINUOUS_MANIFEST_SHA256)
                .put("prerun_contract_sha256", CONTINUOUS_PRERUN_CONTRACT_SHA256)
                .put("fixture_assembly", "synthetic concatenation of complete human recordings and exact generated zeros")
                .put("pacing", "paced PCM16: max640 samples/40ms, exact region boundaries, no catch-up")
                .put("transcript_selection", "ordered segment raw selections; final snapshot separate")
            if (fixture.pacedShort) report.put("prerun_contract_sha256", SHORT_PRERUN_CONTRACT_SHA256)
                .put("source_case_id", id.removeSuffix("-paced"))
                .put("scoring_case_id", id.removeSuffix("-paced"))
                .put("pacing", "paced-short PCM16: max640 samples/40ms, no catch-up, full last packet duration")
            stage = "fixture_identity"
            val directory = File(targetContext.noBackupFilesDir, "asr-fixtures")
            val file = File(directory, fixture.leaf)
            check(file.canonicalFile.parentFile == directory.canonicalFile && file.length() == fixture.bytes.toLong())
            val pcm = ByteArray(fixture.bytes)
            DataInputStream(file.inputStream()).use { input -> input.readFully(pcm); check(input.read() == -1) }
            val hash = MessageDigest.getInstance("SHA-256").digest(pcm).joinToString("") { "%02x".format(it) }
            check(hash == fixture.sha256)
            report.put("fixture_leaf", fixture.leaf).put("fixture_sha256", hash).put("fixture_bytes", pcm.size)
                .put("audio_duration_ms", fixture.durationMs)
            stage = "provider_and_foreground"
            runOnMainSync {
                val id = targetContext.resources.getIdentifier("config_defaultOnDeviceSpeechRecognitionService", "string", "android")
                check(id != 0)
                val component = ComponentName.unflattenFromString(targetContext.getString(id))
                check(component == ComponentName.unflattenFromString(PROVIDER) && SpeechRecognizer.isOnDeviceRecognitionAvailable(targetContext))
                @Suppress("DEPRECATION")
                val service = targetContext.packageManager.getServiceInfo(checkNotNull(component), 0)
                check(service.enabled && service.applicationInfo.enabled)
                report.put("provider", component.flattenToString())
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(targetContext)
            }
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).also { home = it }
            val focusDeadline = SystemClock.elapsedRealtime() + 3_000
            val focused = AtomicBoolean()
            while (!focused.get() && SystemClock.elapsedRealtime() < focusDeadline) {
                runOnMainSync { focused.set(activity.hasWindowFocus()) }
                if (!focused.get()) Thread.sleep(25)
            }
            check(focused.get())
            report.put("foreground_main_verified", true)
            val ownedPipe = ParcelFileDescriptor.createReliablePipe().also { pipe = it }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, fixture.locale)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, ownedPipe[0])
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
                .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
            val ownedRecognizer = checkNotNull(recognizer)
            stage = "installed_locale_support"
            val support = installedSupport(ownedRecognizer, intent, fixture.locale)
            report.put("support", support)
            check(support.optString("outcome") == "support_result" && support.optBoolean("requested_language_installed"))
            stage = "recognition_and_writer"
            runOnMainSync {
                ownedRecognizer.setRecognitionListener(listener)
                recognitionStarted.set(SystemClock.elapsedRealtime())
                ownedRecognizer.startListening(intent)
                report.put("start_listening_requests", 1)
            }
            writer = thread(isDaemon = true, name = "lip-platform-pcm-writer") {
                writerStartedNs.set(SystemClock.elapsedRealtimeNanos())
                var normalEof = false
                try {
                    val fd = ownedPipe[1].fileDescriptor
                    Os.fcntlInt(fd, OsConstants.F_SETFL, Os.fcntlInt(fd, OsConstants.F_GETFL, 0) or OsConstants.O_NONBLOCK)
                    val poll = arrayOf(StructPollfd().apply { this.fd = fd; this.events = OsConstants.POLLOUT.toShort() })
                    val deadline = SystemClock.elapsedRealtime() + WRITER_MS
                    var offset = 0
                    if (fixture.paced || fixture.pacedShort) {
                        val start = writerStartedNs.get()
                        val deadlineNs = start + (if (fixture.paced) PACED_WRITER_MS else WRITER_MS) * 1_000_000L
                        var previousStart = 0L
                        var previousSamples = 0
                        while (offset < pcm.size) {
                            val sampleOffset = offset / 2
                            val samples = if (fixture.paced) pacedPacketSamples(sampleOffset, pcm.size / 2)
                                else shortPacketSamples(sampleOffset, pcm.size / 2)
                            val end = offset + samples * 2
                            var target = packetTargetNs(start, sampleOffset, previousStart, previousSamples)
                            if (fixture.paced && sampleOffset == 440_640) {
                                check(firstZeroAcceptedNs.get() > 0)
                                target = secondSourceTargetNs(target, firstZeroAcceptedNs.get())
                            }
                            waitUntilNs(target, deadlineNs, canceled)
                            var firstAccepted = 0L
                            while (offset < end) {
                                check(!canceled.get() && SystemClock.elapsedRealtimeNanos() < deadlineNs)
                                try {
                                    val count = Os.write(fd, pcm, offset, end - offset)
                                    check(count > 0)
                                    val acceptedAt = SystemClock.elapsedRealtimeNanos()
                                    offset += count
                                    acceptedBytes.addAndGet(count.toLong())
                                    if (firstAccepted == 0L) {
                                        firstAccepted = acceptedAt
                                        val late = acceptedAt - (start + sampleOffset * 62_500L)
                                        maxPaceLatenessNs.set(maxOf(maxPaceLatenessNs.get(), late))
                                        if (fixture.paced && sampleOffset == 120_640) firstZeroAcceptedNs.set(acceptedAt)
                                        if (fixture.paced && sampleOffset == 440_640) secondSourceAcceptedNs.set(acceptedAt)
                                        if (sampleOffset == 0 || fixture.paced && (sampleOffset == 120_640 || sampleOffset == 440_640)) {
                                            boundaryPackets.add(JSONObject().put("sample_offset", sampleOffset)
                                                .put("packet_samples", samples).put("first_acceptance_ns", acceptedAt)
                                                .put("absolute_target_ns", start + sampleOffset * 62_500L)
                                                .put("effective_target_ns", target).put("accepted_bytes", acceptedBytes.get()))
                                        }
                                        check(late <= 1_000_000_000L)
                                    }
                                } catch (error: ErrnoException) {
                                    if (error.errno != OsConstants.EAGAIN && error.errno != OsConstants.EINTR) throw error
                                    try { Os.poll(poll, 100) } catch (interrupted: ErrnoException) {
                                        if (interrupted.errno != OsConstants.EINTR) throw interrupted
                                    }
                                }
                            }
                            previousStart = firstAccepted
                            previousSamples = samples
                            packetCount.incrementAndGet()
                        }
                        // Full last packet duration, not just full bytes, must elapse before owned EOF.
                        waitUntilNs(packetTargetNs(start, pcm.size / 2, previousStart, previousSamples), deadlineNs, canceled)
                    } else while (offset < pcm.size) {
                        check(!canceled.get() && SystemClock.elapsedRealtime() < deadline)
                        try {
                            val count = Os.write(fd, pcm, offset, minOf(4_096, pcm.size - offset))
                            check(count > 0)
                            offset += count
                            acceptedBytes.addAndGet(count.toLong())
                        } catch (error: ErrnoException) {
                            if (error.errno != OsConstants.EAGAIN && error.errno != OsConstants.EINTR) throw error
                            try { Os.poll(poll, 100) } catch (interrupted: ErrnoException) {
                                if (interrupted.errno != OsConstants.EINTR) throw interrupted
                            }
                        }
                    }
                    normalEof = true
                } catch (error: Exception) { writerError.set(error.javaClass.simpleName) }
                finally {
                    try {
                        if (normalEof) synchronized(eofCompletionFence) {
                            ownedPipe[1].close()
                            eofAtNs.set(SystemClock.elapsedRealtimeNanos())
                            eofAt.set(SystemClock.elapsedRealtime())
                        }
                        else ownedPipe[1].closeWithError("Lip fixed PCM pipe failed")
                        writerClosed.set(true)
                    } catch (error: Exception) { writerError.compareAndSet(null, error.javaClass.simpleName) }
                    writerDone.countDown()
                }
            }
            stage = "writer_eof"
            val writerLimit = if (fixture.paced) PACED_WRITER_MS else WRITER_MS
            if (!writerDone.await(writerLimit + 1_000, TimeUnit.MILLISECONDS) || eofAt.get() == 0L) complete("writer_failure")
            else {
                stage = "completion_after_eof"
                var remaining = (AFTER_EOF_MS - (SystemClock.elapsedRealtime() - eofAt.get())).coerceAtLeast(0L)
                if (fixture.paced) remaining = minOf(remaining,
                    (PACED_TOTAL_MS - (SystemClock.elapsedRealtimeNanos() - writerStartedNs.get()) / 1_000_000L).coerceAtLeast(0L))
                if (!completion.await(remaining, TimeUnit.MILLISECONDS)) complete("completion_timeout")
            }
        } catch (error: Exception) {
            runFailed = true
            if (error is InterruptedException) Thread.currentThread().interrupt()
            report.put("error_stage", stage).put("error_class", error.javaClass.simpleName)
        } finally {
            canceled.set(true)
            try { runOnMainSync { recognizer?.cancel() } } catch (_: Exception) { cleanupOk = false }
            try { runOnMainSync { recognizer?.destroy() } } catch (_: Exception) { cleanupOk = false }
            try { pipe?.get(0)?.close() } catch (_: Exception) { cleanupOk = false }
            try { writer?.join(3_000) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); cleanupOk = false }
            pipe?.forEach { descriptor -> try { descriptor.close() } catch (_: Exception) { cleanupOk = false } }
            if (writer?.isAlive == true) cleanupOk = false
            try { runOnMainSync { home?.finish() } } catch (_: Exception) { cleanupOk = false }
            try { runOnMainSync {
                val selected = if (preferFullFinal(admittedFixture?.paced == true) && finalCandidates != null)
                    raw(checkNotNull(finalCandidates)) else segments.joinToString(" ") { raw(it) }.trim()
                val candidateZero = if (preferFullFinal(admittedFixture?.paced == true) && finalCandidates != null)
                    checkNotNull(finalCandidates).firstOrNull().orEmpty() else segments.joinToString(" ") { it.firstOrNull().orEmpty() }.trim()
                val fixture = admittedFixture
                val actualWords = if (fixture?.locale == "en-US") words(selected) else emptyList()
                val narrative = selected.replace(Regex("\\s+"), " ")
                val count = Regex("\\b(?:three|3) people\\b", RegexOption.IGNORE_CASE).containsMatchIn(narrative)
                val negation = Regex("\\bnone of them were hurt\\b", RegexOption.IGNORE_CASE).containsMatchIn(narrative)
                val en13 = fixtureId == "fleurs-en-013"
                val expectedWords = words(REFERENCE)
                val en13Passed = actualWords == expectedWords && count && negation
                val nonemptySegments = segments.indices.filter { raw(segments[it]).isNotBlank() }
                val firstStableNs = nonemptySegments.firstOrNull()?.let { segmentTimesNs[it] } ?: 0L
                val lastStableNs = nonemptySegments.lastOrNull()?.let { segmentTimesNs[it] } ?: 0L
                val continuousPassed = fixture == null || !fixture.paced || (continuousTimingValid(firstZeroAcceptedNs.get(),
                    secondSourceAcceptedNs.get(), firstStableNs, lastStableNs, nonemptySegments.size, maxPaceLatenessNs.get()) &&
                    nonemptySegments.any { segmentTimesNs[it] >= secondSourceAcceptedNs.get() && segmentAcceptedBytes[it] > 440_640L * 2 } &&
                    writerStartedNs.get() > 0 && eofAtNs.get() - writerStartedNs.get() <= PACED_WRITER_MS * 1_000_000L &&
                    eofAtNs.get() - writerStartedNs.get() >= fixture.durationMs * 1_000_000L &&
                    terminalAtNs.get() >= eofAtNs.get() && terminalAtNs.get() - eofAtNs.get() <= AFTER_EOF_MS * 1_000_000L &&
                    terminalAtNs.get() - writerStartedNs.get() <= PACED_TOTAL_MS * 1_000_000L)
                val shortPacingPassed = fixture == null || !fixture.pacedShort || shortTimingValid(writerStartedNs.get(),
                    eofAtNs.get(), fixture.durationMs, maxPaceLatenessNs.get())
                val workflow = fixture != null && acceptedTerminal(fixture.control, terminal.get(), recognitionError, eofAt.get(), terminalAt.get()) &&
                    writerClosed.get() && acceptedBytes.get() == fixture.bytes.toLong() && writerError.get() == null && writer?.isAlive == false &&
                    !runFailed && cleanupOk && bounded && duplicateSegments == 0 && !conflictingFinal && continuousPassed && shortPacingPassed
                val controlPassed = workflow && emptyControlOutput(ghostCandidateSeen, selected, candidateZero)
                val probePassed = workflow && (fixture?.control != true || controlPassed) && (!en13 || en13Passed)
                report.put("events", events).put("events_bounded", bounded).put("selected_raw", selected)
                    .put("candidate_zero_text", candidateZero).put("final_candidates", JSONArray(finalCandidates.orEmpty()))
                    .put("actual_words", if (fixture?.locale == "en-US") JSONArray(actualWords) else JSONObject.NULL)
                    .put("max_error_rate", if (fixture?.control == true) 0 else 0.05)
                    .put("control_passed", if (fixture?.control == true) controlPassed else JSONObject.NULL)
                    .put("active_nonempty_candidate_seen", ghostCandidateSeen)
                    .put("segment_count", segments.size).put("duplicate_segment_payloads", duplicateSegments)
                    .put("duplicate_final_callbacks", duplicateFinals).put("conflicting_final", conflictingFinal)
                    .put("late_callbacks_ignored", lateCallbacks).put("rms_callback_count", rmsCallbacks)
                    .put("buffer_callback_count", bufferCallbacks).put("terminal", terminal.get() ?: "not_started_or_exception")
                    .put("recognition_error_code", recognitionError ?: JSONObject.NULL)
                    .put("accepted_writer_bytes", acceptedBytes.get()).put("writer_error", writerError.get() ?: JSONObject.NULL)
                    .put("writer_eof_sent", eofAt.get() > 0).put("writer_closed", writerClosed.get())
                    .put("writer_joined", writer?.isAlive == false).put("teardown_ok", cleanupOk)
                    .put("recognition_ms", if (terminalAt.get() > 0) terminalAt.get() - recognitionStarted.get() else -1)
                    .put("eof_to_completion_ms", if (terminalAt.get() > 0 && eofAt.get() > 0) terminalAt.get() - eofAt.get() else -1)
                    .put("writer_limit_ms", if (fixture?.paced == true) PACED_WRITER_MS else WRITER_MS).put("after_eof_limit_ms", AFTER_EOF_MS)
                    .put("workflow_passed", workflow).put("probe_passed", probePassed).put("passed", probePassed)
                if (fixture?.paced == true) {
                    val timedSegments = JSONArray()
                    segments.indices.forEach { index -> timedSegments.put(JSONObject().put("index", index)
                        .put("callback_ns", segmentTimesNs[index]).put("accepted_writer_bytes", segmentAcceptedBytes[index])
                        .put("candidates", JSONArray(segments[index])).put("selected_raw", raw(segments[index]))) }
                    report.put("paced_segments", timedSegments).put("boundary_packets", JSONArray(boundaryPackets))
                        .put("writer_start_ns", writerStartedNs.get()).put("first_zero_acceptance_ns", firstZeroAcceptedNs.get())
                        .put("second_source_acceptance_ns", secondSourceAcceptedNs.get()).put("first_nonempty_stable_ns", firstStableNs)
                        .put("last_nonempty_stable_ns", lastStableNs)
                        .put("actual_pause_ns", if (firstZeroAcceptedNs.get() > 0 && secondSourceAcceptedNs.get() > 0)
                            secondSourceAcceptedNs.get() - firstZeroAcceptedNs.get() else JSONObject.NULL)
                        .put("eof_ns", eofAtNs.get()).put("terminal_ns", terminalAtNs.get()).put("packet_count", packetCount.get())
                        .put("max_absolute_packet_lateness_ns", maxPaceLatenessNs.get()).put("continuous_timing_passed", continuousPassed)
                        .put("completion_from_writer_limit_ms", PACED_TOTAL_MS)
                }
                if (fixture?.pacedShort == true) {
                    report.put("boundary_packets", JSONArray(boundaryPackets)).put("writer_start_ns", writerStartedNs.get())
                        .put("eof_ns", eofAtNs.get()).put("terminal_ns", terminalAtNs.get()).put("packet_count", packetCount.get())
                        .put("max_absolute_packet_lateness_ns", maxPaceLatenessNs.get()).put("short_pacing_passed", shortPacingPassed)
                }
                if (en13) report.put("expected_words", JSONArray(expectedWords)).put("strict_words_match", actualWords == expectedWords)
                    .put("count_anchor", count).put("negation_anchor", negation).put("en13_strict_passed", en13Passed)
            } } catch (error: Exception) {
                report.put("passed", false).put("error_stage", "receipt_snapshot").put("error_class", error.javaClass.simpleName)
            }
        }
        finish(if (report.getBoolean("passed")) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("platform_speech_pipe", report.toString()) })
    }

    private fun installedSupport(recognizer: SpeechRecognizer, intent: Intent, locale: String): JSONObject {
        val done = CountDownLatch(1)
        val result = AtomicReference<JSONObject?>()
        runOnMainSync {
            recognizer.checkRecognitionSupport(intent, targetContext.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    val installed = installedLanguage(support.installedOnDeviceLanguages, locale)
                    val value = JSONObject().put("outcome", "support_result")
                        .put("installed_on_device_languages", JSONArray(support.installedOnDeviceLanguages))
                        .put("requested_locale", locale).put("requested_language_installed", installed)
                    if (locale == "en-US") value.put("en_us_installed", installed)
                    if (result.compareAndSet(null, value)) done.countDown()
                }
                override fun onError(error: Int) {
                    if (result.compareAndSet(null, JSONObject().put("outcome", "error").put("error_code", error))) done.countDown()
                }
            })
        }
        return if (done.await(15, TimeUnit.SECONDS)) checkNotNull(result.get()) else JSONObject().put("outcome", "timeout")
    }

    private fun checkRegistryAndControlBoundaries() {
        check(!fixtureArgumentValid(emptySet(), null))
        check(!fixtureArgumentValid(setOf("fixture_id"), "unknown"))
        check(!fixtureArgumentValid(setOf("fixture_id", "locale"), "fleurs-en-013"))
        check(FIXTURES.size == 50 && FIXTURES.keys.all { fixtureArgumentValid(setOf("fixture_id"), it) })
        check(FIXTURES.values.map { it.leaf }.distinct().size == 22)
        val controls = setOf("silence-en", "noise-en", "silence-ja", "noise-ja", "silence-zh", "noise-zh")
        check(FIXTURES.filterValues { it.control }.keys == controls + controls.map { "$it-paced" })
        check(FIXTURES.filterValues { !it.paced && !it.pacedShort }.values.groupBy { it.locale }.mapValues { it.value.size } == mapOf("en-US" to 8, "ja-JP" to 8, "cmn-Hans-CN" to 8))
        check(FIXTURES.filterValues { it.paced }.keys == setOf("en13-pause20-en11", "en13-pause20-repeat-en13"))
        check(FIXTURES["fleurs-en-013"]?.bytes == FIXTURE_BYTES && FIXTURES["fleurs-en-013"]?.sha256 == FIXTURE_SHA256)
        check(!acceptedTerminal(false, "error", SpeechRecognizer.ERROR_NO_MATCH, 1_000L, 1_000L))
        check(acceptedTerminal(true, "error", SpeechRecognizer.ERROR_NO_MATCH, 1_000L, 1_000L))
        check(!acceptedTerminal(true, "error", SpeechRecognizer.ERROR_NO_MATCH, 0L, 1_000L))
        check(!acceptedTerminal(true, "error", SpeechRecognizer.ERROR_NO_MATCH, 1_000L, 999L))
        check(acceptedTerminal(true, "error", SpeechRecognizer.ERROR_NO_MATCH, 1_000L, 16_000L))
        check(!acceptedTerminal(true, "error", SpeechRecognizer.ERROR_NO_MATCH, 1_000L, 16_001L))
        check(!acceptedTerminal(true, "error", SpeechRecognizer.ERROR_NETWORK, 1_000L, 1_000L))
        check(!emptyControlOutput(true, "", ""))
        check(emptyControlOutput(false, "", ""))
        check(!emptyControlOutput(false, "ghost", ""))
    }
    private fun fixtureArgumentValid(keys: Set<String>, id: String?) = keys == setOf("fixture_id") && id != null && id in FIXTURES
    private fun checkInstalledLanguageMapping() {
        check(installedLanguage(listOf("en-US"), "en-US"))
        check(installedLanguage(listOf("ja-JP"), "ja-JP"))
        check(installedLanguage(listOf("cmn-Hans-CN"), "cmn-Hans-CN"))
        check(!installedLanguage(listOf("en-US"), "ja-JP"))
        check(!installedLanguage(listOf("zh-CN"), "cmn-Hans-CN"))
        check(!installedLanguage(emptyList(), "en-US"))
    }
    private fun installedLanguage(languages: List<String>, locale: String) = languages.any { it.equals(locale, ignoreCase = true) }
    private fun checkContinuousHelpers() {
        check(pacedPacketSamples(0, 571_200) == 640)
        check(pacedPacketSamples(120_320, 571_200) == 320)
        check(pacedPacketSamples(120_640, 571_200) == 640)
        check(pacedPacketSamples(440_000, 571_200) == 640)
        check(pacedPacketSamples(560_960, 561_280) == 320)
        check(packetTargetNs(1_000_000_000L, 640, 1_500_000_000L, 640) == 1_540_000_000L)
        check(packetTargetNs(1_000_000_000L, 120_640, 0L, 0) == 8_540_000_000L)
        check(packetTargetNs(1_000_000_000L, 561_280, 36_100_000_000L, 320) == 36_120_000_000L)
        check(secondSourceTargetNs(28_540_000_000L, 9_000_000_000L) == 29_000_000_000L)
        check(!rejectDuplicateSegment(true, setOf(listOf("same")), listOf("same")))
        check(rejectDuplicateSegment(false, setOf(listOf("same")), listOf("same")))
        check(!preferFullFinal(true))
        check(preferFullFinal(false))
        check(!continuousTimingValid(1_000_000_000L, 20_990_000_000L, 2_000_000_000L, 22_000_000_000L, 2, 0L))
        check(continuousTimingValid(1_000_000_000L, 21_000_000_000L, 2_000_000_000L, 22_000_000_000L, 2, 0L))
        check(!continuousTimingValid(1_000_000_000L, 21_000_000_000L, 22_000_000_000L, 23_000_000_000L, 2, 0L))
        check(!continuousTimingValid(1_000_000_000L, 21_000_000_000L, 2_000_000_000L, 22_000_000_000L, 1, 0L))
        check(!continuousTimingValid(1_000_000_000L, 21_000_000_000L, 2_000_000_000L, 22_000_000_000L, 2, 1_000_000_001L))
        check(!continuousTimingValid(1_000_000_000L, 21_000_000_000L, 2_000_000_000L, 20_000_000_000L, 2, 0L))
    }
    private fun pacedPacketSamples(offset: Int, total: Int) = minOf(640, (if (offset < 120_640) 120_640 else if (offset < 440_640) 440_640 else total) - offset)
    private fun packetTargetNs(start: Long, offset: Int, previousStart: Long, previousSamples: Int) = maxOf(start + offset * 62_500L, previousStart + previousSamples * 62_500L)
    private fun secondSourceTargetNs(target: Long, firstZero: Long) = maxOf(target, firstZero + 20_000_000_000L)
    private fun rejectDuplicateSegment(paced: Boolean, seen: Set<List<String>>, value: List<String>) = !paced && value in seen
    private fun preferFullFinal(paced: Boolean) = !paced
    private fun continuousTimingValid(firstZero: Long, second: Long, firstStable: Long, lastStable: Long, nonempty: Int, lateness: Long) = firstZero > 0 && second - firstZero >= 20_000_000_000L && firstStable > 0 && firstStable < second && lastStable >= second && nonempty >= 2 && lateness <= 1_000_000_000L
    private fun checkShortPacingHelpers() {
        check(FIXTURES.filterValues { it.pacedShort }.keys == SHORT_CASE_IDS.map { "$it-paced" }.toSet())
        SHORT_CASE_IDS.forEach { id ->
            val original = checkNotNull(FIXTURES[id])
            val alias = checkNotNull(FIXTURES["$id-paced"])
            check(!original.paced && !original.pacedShort)
            check(alias == original.copy(pacedShort = true) && !alias.paced)
            check(rejectDuplicateSegment(alias.paced, setOf(listOf("same")), listOf("same")) && preferFullFinal(alias.paced))
        }
        check(shortPacketSamples(0, 159_360) == 640)
        check(shortPacketSamples(120_320, 159_360) == 640)
        check(shortPacketSamples(158_720, 159_360) == 640)
        check(shortPacketSamples(159_000, 159_360) == 360)
        check(shortTimingValid(1_000_000_000L, 10_960_000_000L, 9_960L, 0L))
        check(!shortTimingValid(1_000_000_000L, 10_959_999_999L, 9_960L, 0L))
        check(shortTimingValid(1_000_000_000L, 21_000_000_000L, 9_960L, 1_000_000_000L))
        check(!shortTimingValid(1_000_000_000L, 21_000_000_001L, 9_960L, 0L))
        check(!shortTimingValid(1_000_000_000L, 10_960_000_000L, 9_960L, 1_000_000_001L))
        check(!shortTimingValid(0L, 10_960_000_000L, 9_960L, 0L))
    }
    private fun shortPacketSamples(offset: Int, total: Int) = minOf(640, total - offset)
    private fun shortTimingValid(start: Long, eof: Long, durationMs: Long, lateness: Long) = start > 0 && eof - start >= durationMs * 1_000_000L && eof - start <= WRITER_MS * 1_000_000L && lateness <= 1_000_000_000L
    private fun waitUntilNs(target: Long, deadline: Long, canceled: AtomicBoolean) {
        while (true) {
            check(!canceled.get())
            if (Thread.interrupted()) throw InterruptedException()
            val now = SystemClock.elapsedRealtimeNanos()
            check(now < deadline)
            val remaining = target - now
            if (remaining <= 0) return
            // ponytail: <=3 ms CPU tail per 40 ms packet; measure CPU, replace if that budget fails.
            if (remaining > SPIN_TAIL_NS) java.util.concurrent.locks.LockSupport.parkNanos(remaining - SPIN_TAIL_NS)
        }
    }
    private fun acceptedTerminal(control: Boolean, outcome: String?, error: Int?, eof: Long, now: Long) = completionWithinEofWindow(eof, now) && (outcome == "segmented_end" || control && outcome == "error" && error == SpeechRecognizer.ERROR_NO_MATCH)
    private fun emptyControlOutput(ghost: Boolean, raw: String, candidateZero: String) = !ghost && raw.isBlank() && candidateZero.isBlank()
    private fun checkCompletionBoundary() {
        check(!completionWithinEofWindow(0L, 1L))
        check(!completionWithinEofWindow(1_000L, 999L))
        check(completionWithinEofWindow(1_000L, 1_000L))
        check(completionWithinEofWindow(1_000L, 16_000L))
        check(!completionWithinEofWindow(1_000L, 16_001L))
    }
    private fun completionWithinEofWindow(eof: Long, now: Long) = eof > 0 && now >= eof && now - eof <= AFTER_EOF_MS
    private fun raw(candidates: List<String>) = if (candidates.size == 2) candidates[1] else candidates.firstOrNull().orEmpty()
    private fun words(value: String) = Regex("[+-]?\\d+(?:\\.\\d+)?|[\\p{L}\\p{N}]+(?:'[\\p{L}\\p{N}]+)*|[%$€£¥]")
        .findAll(Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.ROOT).replace('’', '\''))
        .map { it.value }.toList()

    private data class Fixture(val leaf: String, val bytes: Int, val sha256: String, val durationMs: Long, val control: Boolean = false, val locale: String = "en-US", val paced: Boolean = false, val pacedShort: Boolean = false)

    private companion object {
        const val REGISTRY_MANIFEST_SHA256 = "2e41a5475d379f7a574db3b65d073c59129209285ab4d749a7696b7e4d1e3f06"
        const val CONTINUOUS_MANIFEST_SHA256 = "2f4a08310fc3190bd6ba33532ce7a6cc1f71f87786c0170c4a068634fb06a8fb"
        const val CONTINUOUS_PRERUN_CONTRACT_SHA256 = "80b47a18d15f972df023cec296f932ffa3dfaedae303a31ee444d19df748fdaa"
        const val SHORT_PRERUN_CONTRACT_SHA256 = "917fd837a162283b58248a9727aaa298b12de9fa7b6d6c8e7b49a511f32568f6"
        val SHORT_CASE_IDS = setOf(
            "fleurs-en-000", "fleurs-en-003", "fleurs-en-011", "fleurs-en-013", "fleurs-en-017", "fleurs-en-021",
            "fleurs-ja-002", "fleurs-ja-010", "fleurs-ja-013", "fleurs-ja-025", "fleurs-ja-030", "fleurs-ja-034",
            "fleurs-zh-001", "fleurs-zh-008", "fleurs-zh-011", "fleurs-zh-020", "fleurs-zh-022", "fleurs-zh-034",
            "silence-en", "noise-en", "silence-ja", "noise-ja", "silence-zh", "noise-zh",
        )
        val FIXTURES = mapOf(
            "fleurs-en-000" to Fixture("fleurs-en-000.pcm", 209_280, "c5bf543a4fb1cfcc1f6f261a3464771eb7be5f32f37c58848848eccbabff12c1", 6_540L),
            "fleurs-en-003" to Fixture("fleurs-en-003.pcm", 132_480, "fec0b9894152d1b631fc2c67618cf235232f5bd495dee4dcf82306c23fbc2d87", 4_140L),
            "fleurs-en-011" to Fixture("fleurs-en-011.pcm", 261_120, "18284f3382025f6d163f01842cf0782adf7c7f3c8e392635122b5d0670c95b7d", 8_160L),
            "fleurs-en-013" to Fixture("fleurs-en-013.pcm", 241_280, "e42fdeed81feac1d9d660e888ceb0351e785760f24ec7b8e9d8420cfe96a800f", 7_540L),
            "fleurs-en-017" to Fixture("fleurs-en-017.pcm", 272_640, "9535b30495183423f1f9d81130d18ed62f92eef92e98a66c580c0724680ec4dc", 8_520L),
            "fleurs-en-021" to Fixture("fleurs-en-021.pcm", 261_120, "9e85298be68ef1d414a5ee4316cf23b3cd95dd852c33e0c7c621fcf614e358db", 8_160L),
            "silence-en" to Fixture("control-silence.pcm", 160_000, "b9ce164d30e4101b009fe4be765a070593cfbdd48f897853de159a8c177fabe8", 5_000L, true),
            "noise-en" to Fixture("control-noise.pcm", 160_000, "c9ef1df7a218d126f547b13bd2677fbaab47c58f4026b5813e50d499b55e22d5", 5_000L, true),
            "fleurs-ja-002" to Fixture("fleurs-ja-002.pcm", 255_360, "89ddf0beb9dbbf6b205fd1536cf2b860cb0e9c7f38dc400798c93085188a5e98", 7_980L, locale = "ja-JP"),
            "fleurs-ja-010" to Fixture("fleurs-ja-010.pcm", 295_680, "5036b306e926baf15abd7cdf4020657bbd85b8328d616f37345521a6b16512b4", 9_240L, locale = "ja-JP"),
            "fleurs-ja-013" to Fixture("fleurs-ja-013.pcm", 326_400, "ff26f4d3827233367746bd5ea2a95b5faddf2fda0bf58196414add30897c7e6e", 10_200L, locale = "ja-JP"),
            "fleurs-ja-025" to Fixture("fleurs-ja-025.pcm", 318_720, "122b55959e247a6d9772dc85e8725f597b3a43e29035befae9c57c32c2485568", 9_960L, locale = "ja-JP"),
            "fleurs-ja-030" to Fixture("fleurs-ja-030.pcm", 349_440, "7d694842d1cc0ecf71580ad0a32055082b99663bcaa3f1038c31f4eb59825a1d", 10_920L, locale = "ja-JP"),
            "fleurs-ja-034" to Fixture("fleurs-ja-034.pcm", 261_120, "33a122096ca635b8663dfbd01db8acb2bf93be3eb252d0076bb7ab7a53a4f822", 8_160L, locale = "ja-JP"),
            "silence-ja" to Fixture("control-silence.pcm", 160_000, "b9ce164d30e4101b009fe4be765a070593cfbdd48f897853de159a8c177fabe8", 5_000L, true, "ja-JP"),
            "noise-ja" to Fixture("control-noise.pcm", 160_000, "c9ef1df7a218d126f547b13bd2677fbaab47c58f4026b5813e50d499b55e22d5", 5_000L, true, "ja-JP"),
            "fleurs-zh-001" to Fixture("fleurs-zh-001.pcm", 238_080, "37eac09ed89a9b1541a7ca6e70c907a1ef2711ac51e4e52d110bd430c7e09661", 7_440L, locale = "cmn-Hans-CN"),
            "fleurs-zh-008" to Fixture("fleurs-zh-008.pcm", 366_720, "927fd15ce74e0003867b9b597f98196a7f3cedbb141f20502a50c7595cc09a1e", 11_460L, locale = "cmn-Hans-CN"),
            "fleurs-zh-011" to Fixture("fleurs-zh-011.pcm", 178_560, "8b91cf2fc2ccdfdbf81dc8ddcee437d4f0a65482164e5a551c4266a88da0ddc0", 5_580L, locale = "cmn-Hans-CN"),
            "fleurs-zh-020" to Fixture("fleurs-zh-020.pcm", 412_800, "ea74504d2b35a7fdfcf702dc674bf03bbf833b8f05ff3280d7562e47bac61a86", 12_900L, locale = "cmn-Hans-CN"),
            "fleurs-zh-022" to Fixture("fleurs-zh-022.pcm", 266_880, "8d73b68889d04f0ce19dcecf40a8587b103c9b285a91d8bfcc19212c3c914cec", 8_340L, locale = "cmn-Hans-CN"),
            "fleurs-zh-034" to Fixture("fleurs-zh-034.pcm", 159_360, "4c6f2da5618b8e0e4090c6076f25c45f2241c18d4c0a5a9757db29162c6935a3", 4_980L, locale = "cmn-Hans-CN"),
            "silence-zh" to Fixture("control-silence.pcm", 160_000, "b9ce164d30e4101b009fe4be765a070593cfbdd48f897853de159a8c177fabe8", 5_000L, true, "cmn-Hans-CN"),
            "noise-zh" to Fixture("control-noise.pcm", 160_000, "c9ef1df7a218d126f547b13bd2677fbaab47c58f4026b5813e50d499b55e22d5", 5_000L, true, "cmn-Hans-CN"),
            "en13-pause20-en11" to Fixture("en13-pause20-en11.pcm", 1_142_400, "b50297c0a71d60c3135e9f13d788655ae7e38b5243862c309763b15407011115", 35_700L, paced = true),
            "en13-pause20-repeat-en13" to Fixture("en13-pause20-repeat-en13.pcm", 1_122_560, "8ea7158d098e18d0968a73947750bd6a23ed3b73609c39b21a4e59928cd2817e", 35_080L, paced = true),
        ).let { original ->
            original + SHORT_CASE_IDS.associate { id -> "$id-paced" to checkNotNull(original[id]).copy(pacedShort = true) }
        }
        const val PROVIDER = "com.google.android.as/com.google.android.apps.miphone.aiai.app.AiAiSpeechRecognitionService"
        const val FIXTURE_BYTES = 241_280
        const val FIXTURE_SHA256 = "e42fdeed81feac1d9d660e888ceb0351e785760f24ec7b8e9d8420cfe96a800f"
        const val REFERENCE = "Although three people were inside the house when the car impacted it, none of them were hurt."
        const val WRITER_MS = 20_000L
        const val AFTER_EOF_MS = 15_000L
        const val PACED_WRITER_MS = 41_000L
        const val PACED_TOTAL_MS = 56_000L
        const val SPIN_TAIL_NS = 3_000_000L
    }
}
