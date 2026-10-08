package dev.lip

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import dev.lip.speech.WhisperEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Actual packaged JNI inference; only hash-bound public fixtures in the test sandbox. */
class LocalAsrRunner : Instrumentation() {
    private lateinit var args: Bundle
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        args = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val report = Bundle()
        var stagedModel: File? = null
        var resultCode = Activity.RESULT_CANCELED
        var cancelOutput: JSONObject? = null
        var finishAllowed = true
        try {
            val mode = args.getString("mode") ?: "default"
            require(mode in setOf("default", "cancel_active"))
            if (mode == "cancel_active") {
                val allowed = setOf("model", "modelSha256", "pcm", "pcmSha256", "language", "prompt", "mode")
                require(args.keySet().all { it in allowed })
            }
            val directory = File(targetContext.noBackupFilesDir, "asr-fixtures")
            fun readInput(name: String, limit: Long, write: (ByteArray, Int) -> Unit) {
                val leaf = args.getString(name) ?: error("Missing $name")
                require(Regex("[a-zA-Z0-9._-]+").matches(leaf) && leaf != "." && leaf != "..")
                val file = File(directory, leaf)
                require(file.canonicalFile.parentFile == directory.canonicalFile)
                require(file.length() in 1..limit)
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                file.inputStream().use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= limit)
                        digest.update(buffer, 0, count)
                        write(buffer, count)
                    }
                }
                require(size > 0)
                require(digest.digest().joinToString("") { "%02x".format(it) } == args.getString("${name}Sha256"))
            }
            // The upload path may be replaced. JNI sees only this unique verified private copy.
            val model = File.createTempFile("verified-model-", ".bin", directory).also { stagedModel = it }
            FileOutputStream(model).use { output ->
                readInput("model", 1_200_000_000) { buffer, count -> output.write(buffer, 0, count) }
                output.fd.sync()
            }
            val bytes = ByteArrayOutputStream().use { output ->
                readInput("pcm", WhisperEngine.MAX_SAMPLES.toLong() * 2) { buffer, count -> output.write(buffer, 0, count) }
                output.toByteArray()
            }
            require(bytes.size % 2 == 0)
            val samples = FloatArray(bytes.size / 2) { index ->
                ((bytes[index * 2].toInt() and 255) or (bytes[index * 2 + 1].toInt() shl 8)).toShort() / 32768f
            }
            if (mode == "cancel_active") {
                require(samples.any { it != 0f }) { "cancel_active requires nonzero original PCM" }
                val output = JSONObject().put("mode", mode).put("samples", samples.size)
                    .put("stagedModelLeaf", model.name)
                    .put("model", args.getString("model")).put("modelSha256", args.getString("modelSha256"))
                    .put("pcm", args.getString("pcm")).put("pcmSha256", args.getString("pcmSha256"))
                    .put("language", args.getString("language") ?: "en").put("prompt", args.getString("prompt") ?: "")
                    .put("cleanupDeadlineMs", 15_000).put("diagnosticJoinLimitMs", 120_000)
                    .put("passed", false).also { cancelOutput = it }
                finishAllowed = false // Unexpected reporting errors must not finish over an owned worker.
                finishAllowed = cancelActive(model, samples, output)
                if (output.getBoolean("passed")) resultCode = Activity.RESULT_OK
            } else {
            val opened = SystemClock.elapsedRealtime()
            val engine = WhisperEngine(model.absolutePath)
            val loadMs = SystemClock.elapsedRealtime() - opened
            val output = JSONObject().put("modelLoadMs", loadMs).put("samples", samples.size)
            engine.use {
                // Repeat on one resident context: distinguish warm inference from model loading.
                val runs = JSONArray()
                repeat(2) {
                    val started = SystemClock.elapsedRealtime()
                    val segments = engine.transcribe(samples, args.getString("language") ?: "en", args.getString("prompt") ?: "")
                    val rows = JSONArray()
                    for (segment in segments) {
                        check(segment.startMs >= 0 && segment.endMs >= segment.startMs)
                        check(segment.noSpeechProbability.isFinite())
                        rows.put(JSONObject().put("text", segment.text).put("startMs", segment.startMs)
                            .put("endMs", segment.endMs).put("noSpeechProbability", segment.noSpeechProbability))
                    }
                    runs.put(JSONObject().put("elapsedMs", SystemClock.elapsedRealtime() - started).put("segments", rows))
                }
                output.put("runs", runs)
                for (zero in listOf(floatArrayOf(0f), floatArrayOf(-0f),
                    FloatArray(WhisperEngine.MAX_SAMPLES) { if (it % 2 == 0) 0f else -0f }))
                    check(engine.transcribe(zero, "en").isEmpty()) { "Exact-zero PCM produced segments" }
                for (language in listOf("en", "ja", "zh"))
                    check(engine.transcribe(floatArrayOf(0f, -0f), language,
                        "词".repeat(WhisperEngine.MAX_PROMPT_CHARACTERS)).isEmpty())
                output.put("exactZeroChecksPassed", true)

                fun invalidInput(pcm: FloatArray, language: String = "en", prompt: String = "") {
                    try {
                        engine.transcribe(pcm, language, prompt)
                        error("Invalid ASR input accepted")
                    } catch (_: IllegalArgumentException) { }
                }
                invalidInput(floatArrayOf())
                invalidInput(FloatArray(WhisperEngine.MAX_SAMPLES + 1))
                invalidInput(floatArrayOf(0f, -0f), "fr")
                invalidInput(floatArrayOf(0f, -0f), prompt = "\u0000")
                invalidInput(floatArrayOf(0f, -0f), prompt = "x".repeat(WhisperEngine.MAX_PROMPT_CHARACTERS + 1))
                for (sample in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 1.01f, -1.01f))
                    invalidInput(floatArrayOf(0f, sample, -0f))
                output.put("invalidInputChecksPassed", true)

                engine.cancel()
                check(engine.transcribe(floatArrayOf(0f, -0f), "en").isEmpty()) { "Cancel poisoned a fresh zero window" }
                output.put("freshZeroAfterCancelPassed", true)
            }
            engine.close() // Terminal close is idempotent.
            for (pcm in listOf(samples, floatArrayOf(0f, -0f))) {
                check(runCatching { engine.transcribe(pcm, "en") }.exceptionOrNull()?.javaClass == IllegalStateException::class.java) {
                    "Closed engine accepted inference"
                }
            }
            output.put("closedEngineChecksPassed", true)
            report.putString(REPORT_KEY_STREAMRESULT, "\nASR_RESULT $output\n")
            resultCode = Activity.RESULT_OK
            }
        } catch (error: Throwable) {
            cancelOutput?.put("errorClass", error.javaClass.name)?.put("passed", false)
            report.putString(REPORT_KEY_STREAMRESULT, "FAIL: ${error.javaClass.simpleName}: ${error.message}\n")
        } finally {
            if (finishAllowed && stagedModel?.let { it.exists() && !it.delete() } == true) {
                resultCode = Activity.RESULT_CANCELED
                cancelOutput?.put("passed", false)
                report.putString(REPORT_KEY_STREAMRESULT, "FAIL: Verified staging model cleanup failed\n")
            }
        }
        cancelOutput?.let { output ->
            output.put("stagingRemoved", stagedModel?.exists() == false)
            output.put("parentHold", !finishAllowed)
            val accepted = output.optJSONArray("acceptedPathIds") ?: JSONArray().also { output.put("acceptedPathIds", it) }
            if (output.getBoolean("stagingRemoved")) accepted.put("staging_removed")
            report.putString(REPORT_KEY_STREAMRESULT, "\nASR_RESULT $output\n")
        }
        if (!finishAllowed) {
            sendStatus(1, Bundle().apply { putString(REPORT_KEY_STREAMRESULT, "\nASR_CANCEL_HOLD $cancelOutput\n") })
            return // Never let instrumentation finish kill an unjoined owned native call.
        }
        finish(resultCode, report)
    }

    private data class NativeTask(val tid: Int, val comm: String, val startTicks: Long, val cpuTicks: Long) {
        val identity: String get() = "$tid:$startTicks"
        fun json() = JSONObject().put("tid", tid).put("comm", comm).put("startTicks", startTicks).put("cpuTicks", cpuTicks)
    }

    private fun parseTask(tid: Int, stat: String): NativeTask {
        val left = stat.indexOf('(')
        val right = stat.lastIndexOf(')')
        check(left > 0 && right > left && stat.substring(0, left).trim().toInt() == tid)
        val fields = stat.substring(right + 1).trim().split(Regex("\\s+"))
        check(fields.size >= 20)
        return NativeTask(tid, stat.substring(left + 1, right), fields[19].toLong(), fields[11].toLong() + fields[12].toLong())
    }

    private fun tasks(): List<NativeTask> = (File("/proc/self/task").listFiles() ?: error("Cannot enumerate owned tasks")).mapNotNull { directory ->
        val tid = directory.name.toIntOrNull() ?: return@mapNotNull null
        try { parseTask(tid, File(directory, "stat").readText()) }
        catch (error: Exception) { if (directory.exists()) throw error else null }
    }

    private fun inNative(thread: Thread) = thread.stackTrace.any {
        it.className == "dev.lip.speech.WhisperEngine" && it.methodName == "nativeTranscribe" && it.isNativeMethod
    }

    private fun joinUntil(thread: Thread, deadlineNs: Long): Boolean {
        while (thread.isAlive) {
            val left = deadlineNs - SystemClock.elapsedRealtimeNanos()
            if (left <= 0) return false
            thread.join(left / 1_000_000, (left % 1_000_000).toInt())
        }
        return SystemClock.elapsedRealtimeNanos() <= deadlineNs
    }

    /** Returns permission to finish, not a deadline pass: late cleanup never changes the gate. */
    private fun cancelActive(model: File, samples: FloatArray, output: JSONObject): Boolean {
        val cleanupNs = 15_000_000_000L
        val diagnosticNs = 120_000_000_000L
        val stableNs = 2_000_000_000L
        val name = "lip-asr-canary" // Fits Linux comm without truncation; native children inherit it.
        val language = args.getString("language") ?: "en"
        val prompt = args.getString("prompt") ?: ""
        val accepted = JSONArray().also { output.put("acceptedPathIds", it) }
        accepted.put("nonzero_verified_inputs")
        // Real parser self-check: /proc comm may contain spaces and ')' characters.
        val stat = "7 (a ) b) R " + (4..22).joinToString(" ") { it.toString() }
        check(parseTask(7, stat) == NativeTask(7, "a ) b", 22, 29))
        output.put("taskStatParserSelfCheck", true).put("kernelStage", "not observed")

        fun execute(cancel: Boolean): Boolean {
            val phase = JSONObject().put("passed", false).put("parentHold", false)
                .put("lateCleanupOnly", false).put("cancelCleanupWithin15s", false)
                .put("nativeStackObserved", false).put("cpuProgress2s", false)
                .put("freshZeroAfterCancelPassed", false)
            output.put(if (cancel) "cancel" else "fresh", phase)
            val ready = CountDownLatch(1)
            val go = CountDownLatch(1)
            val armed = AtomicBoolean()
            val callerTid = AtomicInteger()
            val engineRef = AtomicReference<WhisperEngine?>()
            val segments = AtomicReference<List<WhisperEngine.Segment>?>()
            val inferenceError = AtomicReference<Throwable?>()
            val closeError = AtomicReference<Throwable?>()
            val resetError = AtomicReference<Throwable?>()
            val zeroReset = AtomicBoolean()
            val inferenceStarted = AtomicLong()
            val inferenceEnded = AtomicLong()
            val closeStarted = AtomicLong()
            val closeEnded = AtomicLong()
            val loadStarted = AtomicLong()
            val loadEnded = AtomicLong()
            val thread = Thread({
                callerTid.set(Process.myTid())
                var engine: WhisperEngine? = null
                try {
                    loadStarted.set(SystemClock.elapsedRealtimeNanos())
                    engine = WhisperEngine(model.absolutePath)
                    engineRef.set(engine)
                    loadEnded.set(SystemClock.elapsedRealtimeNanos())
                    ready.countDown()
                    go.await()
                    if (armed.get()) {
                        inferenceStarted.set(SystemClock.elapsedRealtimeNanos())
                        try { segments.set(engine.transcribe(samples, language, prompt)) }
                        catch (error: Throwable) { inferenceError.set(error) }
                        finally { inferenceEnded.set(SystemClock.elapsedRealtimeNanos()) }
                        if (cancel && inferenceError.get() is CancellationException) {
                            try { zeroReset.set(engine.transcribe(floatArrayOf(0f, -0f), "en").isEmpty()) }
                            catch (error: Throwable) { resetError.set(error) }
                        }
                    }
                } catch (error: Throwable) { inferenceError.set(error) }
                finally {
                    ready.countDown()
                    if (engine != null) {
                        closeStarted.set(SystemClock.elapsedRealtimeNanos())
                        try { engine.close() } catch (error: Throwable) { closeError.set(error) }
                        finally { closeEnded.set(SystemClock.elapsedRealtimeNanos()) }
                    }
                }
            }, name).apply { isDaemon = true }
            var cancelNs = 0L
            var activeCancel = false
            var deadlineNs = 0L
            var baseline = emptySet<String>()
            val observed = linkedMapOf<String, NativeTask>()
            var joinedInBound = false
            var deadlineGate = false
            var terminal = false
            var threadStarted = false
            fun state(): Boolean {
                phase.put("workerAlive", thread.isAlive).put("workerState", thread.state.name)
                if (thread.isAlive) {
                    phase.put("nativeStackAtObservation", inNative(thread))
                        .put("workerStack", JSONArray(thread.stackTrace.map { it.toString() }))
                    phase.put("closed", JSONObject.NULL).put("handle", JSONObject.NULL)
                } else {
                    val engine = engineRef.get()
                    phase.put("closed", engine?.let { it.javaClass.getDeclaredField("closed").apply { isAccessible = true }.getBoolean(it) } ?: true)
                    phase.put("handle", engine?.let { it.javaClass.getDeclaredField("handle").apply { isAccessible = true }.getLong(it) } ?: 0L)
                }
                val remaining = if (threadStarted) tasks().filter {
                    it.identity in observed || (it.comm == name && it.identity !in baseline)
                } else emptyList()
                remaining.forEach { observed[it.identity] = it }
                phase.put("remainingOwnedTasks", JSONArray(remaining.map { it.json() }))
                    .put("nativeTasksQuiescent", remaining.isEmpty())
                return !thread.isAlive && phase.getBoolean("closed") && phase.getLong("handle") == 0L && remaining.isEmpty()
            }
            try {
                check(tasks().none { it.comm == name }) { "Canary thread name already owned elsewhere" }
                thread.start()
                threadStarted = true
                check(ready.await(20, TimeUnit.SECONDS)) { "Model/worker preflight exceeded 20 seconds" }
                val engine = engineRef.get() ?: error("Owned model load failed")
                check(thread.isAlive)
                val initial = tasks()
                val caller = initial.single { it.tid == callerTid.get() }
                check(caller.comm == name)
                baseline = initial.filter { it.tid != caller.tid }.map { it.identity }.toSet()
                check(initial.none { it.comm == name && it.tid != caller.tid })
                observed[caller.identity] = caller
                phase.put("callerTid", caller.tid).put("callerIdentity", caller.identity)
                    .put("modelLoadNs", loadEnded.get() - loadStarted.get())
                armed.set(true)
                go.countDown()
                if (cancel) {
                    val preflightEnd = SystemClock.elapsedRealtimeNanos() + 20_000_000_000L
                    var firstCohort = emptySet<String>()
                    var previous = emptyList<NativeTask>()
                    var stableFrom = 0L
                    while (true) {
                        check(thread.isAlive && SystemClock.elapsedRealtimeNanos() < preflightEnd) { "No stable active JNI/CPU witness within 20 seconds" }
                        val before = inNative(thread)
                        val currentTasks = tasks().filter { it.comm == name && it.identity !in baseline }
                        currentTasks.forEach { observed[it.identity] = it }
                        val native = currentTasks.filter { it.tid != caller.tid }
                        val active = before && inNative(thread) && currentTasks.any { it.identity == caller.identity }
                        if (active) phase.put("nativeStackObserved", true)
                        // ponytail: frozen two-core canary expects one native worker; recalibrate for other guests.
                        if (active && native.size == 1 && firstCohort.isEmpty()) {
                            firstCohort = native.map { it.identity }.toSet()
                            phase.put("firstNativeCohort", JSONArray(native.map { it.json() }))
                        }
                        // Pinned mel has one joined cohort; any later native cohort is post-mel.
                        val afterFirstCohort = native.size == 1 && firstCohort.isNotEmpty() &&
                            native.all { it.identity !in firstCohort }
                        val now = SystemClock.elapsedRealtimeNanos()
                        if (!active || !afterFirstCohort || previous.any { old -> currentTasks.none { it.identity == old.identity } }) {
                            previous = emptyList(); stableFrom = 0L
                        }
                        if (active && afterFirstCohort) {
                            if (previous.isEmpty()) { previous = currentTasks; stableFrom = now }
                            if (now - stableFrom >= stableNs) {
                                fun progressed(tid: Int): Boolean {
                                    val current = currentTasks.single { it.tid == tid }
                                    val previous = previous.singleOrNull { it.identity == current.identity } ?: return false
                                    return current.cpuTicks > previous.cpuTicks
                                }
                                check(progressed(caller.tid) && native.any { progressed(it.tid) }) { "Active JNI cohort made no CPU progress" }
                                phase.put("cpuProgress2s", true).put("stableStartedNs", stableFrom).put("stableEndedNs", now)
                                    .put("cpuBefore", JSONArray(previous.map { it.json() })).put("cpuAfter", JSONArray(currentTasks.map { it.json() }))
                                    .put("encoderBeginWitness", "post-first-native-cohort turnover on pinned mel/encoder callpath; inferred, not a callback or exact kernel-stage trace")
                                break
                            }
                        }
                        Thread.sleep(20)
                    }
                    check(inNative(thread)) { "JNI ended before Cancel" }
                    cancelNs = SystemClock.elapsedRealtimeNanos()
                    deadlineNs = cancelNs + cleanupNs
                    phase.put("cancelRequestedNs", cancelNs)
                    engine.cancel()
                    val returnedNs = SystemClock.elapsedRealtimeNanos()
                    activeCancel = true
                    phase.put("cancelReturnedNs", returnedNs).put("cancelCallElapsedNs", returnedNs - cancelNs)
                    runCatching { sendStatus(1, Bundle().apply { putString(REPORT_KEY_STREAMRESULT, "\nASR_CANCEL_REQUESTED $phase\n") }) }
                        .onFailure { phase.put("statusErrorClass", it.javaClass.name) }
                } else {
                    phase.put("freshContext", "new verified-model context after terminal canceled-context cleanup")
                    deadlineNs = SystemClock.elapsedRealtimeNanos() + diagnosticNs
                }
            } catch (error: Throwable) {
                phase.put("errorClass", error.javaClass.name).put("errorMessage", error.message)
            } finally {
                if (deadlineNs == 0L) deadlineNs = SystemClock.elapsedRealtimeNanos() + cleanupNs
                if (!activeCancel && thread.isAlive && (cancel || phase.has("errorClass"))) {
                    try { engineRef.get()?.cancel() }
                    catch (error: Throwable) { phase.put("fallbackCancelErrorClass", error.javaClass.name) }
                }
                go.countDown()
                try {
                    joinedInBound = joinUntil(thread, deadlineNs)
                    val joinedObservedNs = SystemClock.elapsedRealtimeNanos()
                    if (cancelNs > 0) phase.put("cancelToJoinObservationNs", joinedObservedNs - cancelNs)
                    terminal = state()
                    while (joinedInBound && !terminal && SystemClock.elapsedRealtimeNanos() < deadlineNs) {
                        Thread.sleep(1)
                        terminal = state()
                    }
                    deadlineGate = joinedInBound && terminal && SystemClock.elapsedRealtimeNanos() <= deadlineNs
                    phase.put("workerJoinedWithin15s", cancel && joinedInBound)
                        .put("workerJoinedWithinDeadline", joinedInBound).put("cleanupObservedNs", SystemClock.elapsedRealtimeNanos())
                    if (cancel) phase.put("cancelCleanupWithin15s", activeCancel && deadlineGate)
                    else phase.put("freshInferenceDeadlinePassed", deadlineGate)
                    phase.put("cleanupStateAtDeadline", JSONObject(phase.toString()))
                } catch (error: Throwable) { phase.put("cleanupObservationErrorClass", error.javaClass.name) }
                if (!terminal) {
                    phase.put("lateCleanupOnly", true)
                    runCatching { sendStatus(1, Bundle().apply { putString(REPORT_KEY_STREAMRESULT, "\nASR_CANCEL_DEADLINE_FAILED $phase\n") }) }
                        .onFailure { phase.put("statusErrorClass", it.javaClass.name) }
                    try { engineRef.get()?.cancel() }
                    catch (error: Throwable) { phase.put("diagnosticCancelErrorClass", error.javaClass.name) }
                    val lateStartedNs = SystemClock.elapsedRealtimeNanos()
                    val lateDeadline = lateStartedNs + diagnosticNs
                    try {
                        val joinedLate = joinUntil(thread, lateDeadline)
                        phase.put("workerJoinedAfterDiagnostic", joinedLate)
                        do {
                            terminal = state()
                            if (!terminal && joinedLate && SystemClock.elapsedRealtimeNanos() < lateDeadline) Thread.sleep(20)
                        } while (!terminal && joinedLate && SystemClock.elapsedRealtimeNanos() < lateDeadline)
                    } catch (error: Throwable) { phase.put("diagnosticObservationErrorClass", error.javaClass.name) }
                    finally { phase.put("diagnosticJoinElapsedNs", SystemClock.elapsedRealtimeNanos() - lateStartedNs) }
                }
                phase.put("callerTid", callerTid.get()).put("modelLoadStartedNs", loadStarted.get()).put("modelLoadEndedNs", loadEnded.get())
                    .put("inferenceStartedNs", inferenceStarted.get()).put("inferenceEndedNs", inferenceEnded.get())
                    .put("inferenceExceptionClass", inferenceError.get()?.javaClass?.name ?: JSONObject.NULL)
                    .put("closeStartedNs", closeStarted.get()).put("closeEndedNs", closeEnded.get())
                    .put("closeExceptionClass", closeError.get()?.javaClass?.name ?: JSONObject.NULL)
                    .put("resetExceptionClass", resetError.get()?.javaClass?.name ?: JSONObject.NULL)
                    .put("freshZeroAfterCancelPassed", zeroReset.get()).put("observedOwnedTasks", JSONArray(observed.values.map { it.json() }))
                    .put("parentHold", !terminal).put("finalObservedNs", SystemClock.elapsedRealtimeNanos())
                if (cancelNs > 0) phase.put("cancelToFinalObservationNs", SystemClock.elapsedRealtimeNanos() - cancelNs)
            }
            if (!terminal) return false
            val rows = JSONArray()
            val decoded = segments.get().orEmpty()
            for (segment in decoded) {
                check(segment.startMs >= 0 && segment.endMs >= segment.startMs && segment.noSpeechProbability.isFinite())
                rows.put(JSONObject().put("text", segment.text).put("startMs", segment.startMs)
                    .put("endMs", segment.endMs).put("noSpeechProbability", segment.noSpeechProbability))
            }
            phase.put("segments", rows)
            if (cancel) {
                val cancellation = inferenceError.get() is CancellationException
                phase.put("cancellationException", cancellation).put("returnedTranscript", segments.get() != null)
                phase.put("passed", !phase.has("errorClass") && activeCancel && phase.getBoolean("cancelCleanupWithin15s") && cancellation &&
                    segments.get() == null && zeroReset.get() && closeError.get() == null && resetError.get() == null)
                if (phase.getBoolean("nativeStackObserved")) accepted.put("active_jni")
                if (phase.has("encoderBeginWitness")) accepted.put("encoder_begin_cohort")
                if (phase.getBoolean("cpuProgress2s")) accepted.put("cpu_progress_2s")
                if (activeCancel) accepted.put("cancel_call")
                if (phase.getBoolean("cancelCleanupWithin15s")) accepted.put("cleanup_15s")
                if (cancellation) accepted.put("cancellation_exception")
                if (zeroReset.get()) accepted.put("same_context_zero_reset")
            } else {
                val raw = decoded.joinToString("") { it.text }
                phase.put("freshRaw", raw).put("externallyScored", false)
                phase.put("passed", !phase.has("errorClass") && phase.optBoolean("freshInferenceDeadlinePassed") && inferenceError.get() == null &&
                    closeError.get() == null && decoded.any { it.text.isNotBlank() })
                if (phase.getBoolean("passed")) accepted.put("fresh_nonzero")
            }
            val engine = engineRef.get()
            if (engine != null) {
                accepted.put(if (cancel) "closed_handle_zero" else "fresh_closed_handle_zero")
                accepted.put(if (cancel) "owned_tasks_quiescent" else "fresh_owned_tasks_quiescent")
                engine.close()
                for (pcm in listOf(samples, floatArrayOf(0f, -0f))) {
                    check(runCatching { engine.transcribe(pcm, "en") }.exceptionOrNull()?.javaClass == IllegalStateException::class.java) {
                        "Closed canary engine accepted inference"
                    }
                }
                phase.put("closedEngineChecksPassed", true)
            }
            return true
        }

        if (!execute(true)) return false
        if (!output.getJSONObject("cancel").getBoolean("passed")) {
            output.put("freshSkipped", "Cancellation acceptance failed; late cleanup cannot enable fresh speech")
            return true
        }
        if (!execute(false)) return false
        output.put("passed", output.getJSONObject("fresh").getBoolean("passed"))
        return true
    }
}
