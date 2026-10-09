package dev.lip

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Real AudioRecord-to-pipe Stop-tail probe; byte counts are transport evidence, not ASR evidence. */
class MicTailRunner : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val report = JSONObject().put("event", "pcm_stop_tail").put("passed", false).put("transport_only", true)
        val positiveRead = CountDownLatch(1)
        val releaseWriter = CountDownLatch(1)
        val eof = CountDownLatch(1)
        val accepted = AtomicInteger()
        val positiveReads = AtomicInteger()
        val received = AtomicLong()
        val eofAt = AtomicLong()
        val sourceErrors = AtomicInteger()
        val readerError = AtomicReference<Exception?>()
        val hookTimedOut = AtomicBoolean()
        val writer = AtomicReference<Thread?>()
        var source: PcmSource? = null
        var reader: Thread? = null
        var home: Activity? = null
        var stopAt = 0L
        var stage = "isolation"
        var cleanupOk = true
        try {
            check(Build.HARDWARE in listOf("ranchu", "goldfish") && Build.PRODUCT.contains("sdk"))
            check(targetContext.packageName == "dev.lip.android")
            stage = "microphone_permission"
            check(targetContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            stage = "foreground_activity"
            home = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync()
            val focusDeadline = SystemClock.elapsedRealtime() + 3_000
            val focused = AtomicBoolean()
            while (!focused.get() && SystemClock.elapsedRealtime() < focusDeadline) {
                runOnMainSync { focused.set(home?.hasWindowFocus() == true) }
                if (!focused.get()) Thread.sleep(25)
            }
            check(focused.get())

            val pcm = PcmSource(targetContext) { count ->
                writer.set(Thread.currentThread())
                if (positiveReads.incrementAndGet() == 1) {
                    accepted.set(count)
                    positiveRead.countDown()
                    if (!releaseWriter.await(10, TimeUnit.SECONDS)) {
                        hookTimedOut.set(true)
                        error("Stop-tail hook timed out")
                    }
                }
            }.also { source = it }
            reader = thread(isDaemon = true, name = "lip-mic-tail-reader") {
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(pcm.input).use { input ->
                        val buffer = ByteArray(2_048)
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) { eofAt.set(SystemClock.elapsedRealtime()); eof.countDown(); break }
                            check(count > 0)
                            received.addAndGet(count.toLong())
                        }
                    }
                } catch (error: Exception) { readerError.set(error) }
            }
            stage = "start_capture"
            runOnMainSync { pcm.start({}, { sourceErrors.incrementAndGet() }) }
            stage = "await_positive_read"
            check(positiveRead.await(10, TimeUnit.SECONDS) && accepted.get() > 0)
            stage = "stop_audio"
            stopAt = SystemClock.elapsedRealtime()
            runOnMainSync { pcm.finishAudio() }
            releaseWriter.countDown()
            stage = "await_pipe_eof"
            check(eof.await(3, TimeUnit.SECONDS))
            check(eofAt.get() - stopAt in 0L..2_999L)
            stage = "join_capture_threads"
            reader?.join(3_000)
            writer.get()?.join(3_000)
            check(reader?.isAlive == false && writer.get()?.isAlive == false)
            stage = "accepted_tail_exactly_once"
            check(positiveReads.get() == 1 && received.get() == accepted.get().toLong())
            stage = "zero_capture_errors"
            check(sourceErrors.get() == 0 && readerError.get() == null && !hookTimedOut.get())
            report.put("passed", true)
        } catch (error: Exception) { report.put("error_stage", stage).put("error_class", error.javaClass.simpleName) }
        finally {
            releaseWriter.countDown()
            try { runOnMainSync { source?.close() } } catch (_: Exception) { cleanupOk = false }
            try { reader?.join(3_000); writer.get()?.join(3_000) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); cleanupOk = false }
            if (reader?.isAlive == true || writer.get()?.isAlive == true) cleanupOk = false
            try { runOnMainSync { home?.finish() } } catch (_: Exception) { cleanupOk = false }
            if (!cleanupOk || sourceErrors.get() != 0 || readerError.get() != null || hookTimedOut.get()) report.put("passed", false)
            report.put("accepted_bytes", accepted.get()).put("received_bytes", received.get())
                .put("positive_reads", positiveReads.get()).put("source_errors", sourceErrors.get())
                .put("reader_error", readerError.get()?.javaClass?.simpleName ?: "none")
                .put("hook_timeout", hookTimedOut.get()).put("eof_received", eof.count == 0L)
                .put("stop_to_eof_ms", if (stopAt > 0 && eofAt.get() > 0) eofAt.get() - stopAt else -1)
                .put("reader_joined", reader?.isAlive != true).put("writer_joined", writer.get()?.isAlive != true)
                .put("teardown_ok", cleanupOk)
        }
        finish(if (report.getBoolean("passed")) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("pcm_stop_tail", report.toString()) })
    }
}
