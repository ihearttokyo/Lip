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
import android.speech.ModelDownloadListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** At most one provider-managed installation request; never starts recognition or writes audio. */
class PlatformSpeechDownloadRunner : Instrumentation() {
    private var requestedLocale: String? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        requestedLocale = try {
            @Suppress("DEPRECATION")
            val values = arguments?.let { supplied ->
                supplied.keySet().associateWith { supplied.get(it) as? String }
            }.orEmpty()
            parseRequestArguments(values)
        } catch (_: Exception) { null }
        if (requestedLocale == null) {
            val rejected = report(null).put("outcome", "invalid_arguments")
                .put("error_stage", "argument_validation").put("teardown_ok", true)
            finish(Activity.RESULT_CANCELED, Bundle().apply {
                putString("platform_speech_download", rejected.toString())
            })
            return
        }
        start()
    }

    override fun onStart() {
        val locale = checkNotNull(requestedLocale)
        val report = report(locale)
        var recognizer: SpeechRecognizer? = null
        var pipe: Array<ParcelFileDescriptor>? = null
        var stage = "isolation"
        var cleanupOk = true
        try {
            check(Build.HARDWARE in listOf("ranchu", "goldfish") && Build.PRODUCT.contains("sdk"))
            check(targetContext.packageName == "dev.lip.android" && Build.VERSION.SDK_INT >= 34)
            stage = "verified_provider"
            runOnMainSync {
                val id = targetContext.resources.getIdentifier("config_defaultOnDeviceSpeechRecognitionService", "string", "android")
                check(id != 0)
                val configured = targetContext.getString(id)
                val component = ComponentName.unflattenFromString(configured)
                val available = SpeechRecognizer.isOnDeviceRecognitionAvailable(targetContext)
                report.put("configured_component", configured).put("on_device_available", available)
                check(available && component == ComponentName.unflattenFromString(PROVIDER))
                @Suppress("DEPRECATION")
                val service = targetContext.packageManager.getServiceInfo(checkNotNull(component), 0)
                check(service.enabled && service.applicationInfo.enabled)
                @Suppress("DEPRECATION")
                val provider = targetContext.packageManager.getPackageInfo(component.packageName, 0)
                report.put("provider_version_name", provider.versionName).put("provider_version_code", provider.longVersionCode)
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(targetContext)
            }
            val ownedPipe = ParcelFileDescriptor.createPipe().also { pipe = it }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, ownedPipe[0])
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
                .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            val ownedRecognizer = checkNotNull(recognizer)
            stage = "support_before_download"
            val before = support(ownedRecognizer, intent, locale)
            report.put("support_before", before)
            check(before.optString("outcome") == "support_result")
            if (!needsDownload(before.optBoolean("requested_language_installed"),
                    before.optBoolean("requested_language_pending"), before.optBoolean("requested_language_supported"))) {
                report.put("outcome", "already_installed_metadata_only")
                    .put("installation_evidence", "support_metadata_only")
                    .put("installation_verified", true).put("passed", true)
            } else {
                stage = "one_download_request"
                val download = download(ownedRecognizer, intent, report)
                report.put("download", download)
                if (download.optString("outcome") == "success") {
                    stage = "installed_after_success"
                    val after = support(ownedRecognizer, intent, locale)
                    report.put("support_after", after)
                    val installed = after.optString("outcome") == "support_result" && after.optBoolean("requested_language_installed")
                    report.put("outcome", "download_success_callback")
                        .put("installation_evidence", "support_after_success_callback")
                        .put("installation_verified", installed)
                        .put("passed", installed && download.optBoolean("progress_valid"))
                }
            }
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            report.put("error_stage", stage).put("error_class", error.javaClass.simpleName)
        } finally {
            try { runOnMainSync { recognizer?.destroy() } } catch (_: Exception) { cleanupOk = false }
            pipe?.forEach { descriptor ->
                try { descriptor.close() } catch (_: Exception) { cleanupOk = false }
            }
            report.put("teardown_ok", cleanupOk)
            if (!cleanupOk) report.put("passed", false)
        }
        finish(if (report.getBoolean("passed")) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("platform_speech_download", report.toString()) })
    }

    private fun report(locale: String?) = JSONObject().put("event", "platform_speech_model_installation").put("passed", false)
        .put("locale", locale ?: JSONObject.NULL).put("recognition_tested", false).put("audio_written", false)
        .put("backend_changed", false).put("download_requests", 0).put("installation_verified", false)
        .put("provider_managed_model", true).put("model_bytes", JSONObject.NULL).put("model_sha256", JSONObject.NULL)
        .put("download_byte_limit_supported", false).put("download_cancellation_guaranteed", false)

    private fun support(recognizer: SpeechRecognizer, intent: Intent, locale: String): JSONObject {
        val completed = CountDownLatch(1)
        val terminal = AtomicReference<JSONObject?>()
        val callback = object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) {
                val installed = hasLocale(support.installedOnDeviceLanguages, locale)
                val response = JSONObject().put("outcome", "support_result")
                    .put("installed_on_device_languages", JSONArray(support.installedOnDeviceLanguages))
                    .put("pending_on_device_languages", JSONArray(support.pendingOnDeviceLanguages))
                    .put("supported_on_device_languages", JSONArray(support.supportedOnDeviceLanguages))
                    .put("online_languages", JSONArray(support.onlineLanguages))
                    .put("requested_language_installed", installed)
                    .put("requested_language_pending", hasLocale(support.pendingOnDeviceLanguages, locale))
                    .put("requested_language_supported", installed || hasLocale(support.supportedOnDeviceLanguages, locale))
                if (terminal.compareAndSet(null, response)) completed.countDown()
            }
            override fun onError(error: Int) {
                if (terminal.compareAndSet(null, JSONObject().put("outcome", "error").put("error_code", error))) completed.countDown()
            }
        }
        runOnMainSync { recognizer.checkRecognitionSupport(intent, targetContext.mainExecutor, callback) }
        return if (completed.await(15, TimeUnit.SECONDS)) checkNotNull(terminal.get())
            else JSONObject().put("outcome", "timeout")
    }

    private fun download(recognizer: SpeechRecognizer, intent: Intent, report: JSONObject): JSONObject {
        val started = SystemClock.elapsedRealtime()
        val completed = CountDownLatch(1)
        val terminal = AtomicReference<JSONObject?>()
        val progress = ArrayList<Int>(101)
        var progressCalls = 0L
        var invalidProgressCalls = 0L
        var firstInvalidProgress: Int? = null
        fun complete(outcome: String, error: Int? = null) {
            val elapsed = SystemClock.elapsedRealtime() - started
            val response = JSONObject().put("outcome", if (elapsed > DOWNLOAD_WAIT_MS) "timeout" else outcome)
                .put("elapsed_ms", elapsed)
            if (error != null) response.put("error_code", error)
            if (terminal.compareAndSet(null, response)) completed.countDown()
        }
        val listener = object : ModelDownloadListener {
            override fun onProgress(completedPercent: Int) {
                if (terminal.get() != null) return
                progressCalls++
                val last = progress.lastOrNull() ?: -1
                if (completedPercent !in 0..100 || completedPercent < last) {
                    invalidProgressCalls++
                    if (firstInvalidProgress == null) firstInvalidProgress = completedPercent
                } else if (completedPercent != last) progress.add(completedPercent)
            }
            override fun onSuccess() = complete("success")
            override fun onScheduled() = complete("scheduled")
            override fun onError(error: Int) = complete("error", error)
        }
        runOnMainSync {
            report.put("download_requests", 1)
            recognizer.triggerModelDownload(intent, targetContext.mainExecutor, listener)
        }
        val remaining = (DOWNLOAD_WAIT_MS - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(0L)
        if (!completed.await(remaining, TimeUnit.MILLISECONDS)) {
            terminal.compareAndSet(null, JSONObject().put("outcome", "timeout")
                .put("elapsed_ms", SystemClock.elapsedRealtime() - started))
        }
        val result = checkNotNull(terminal.get())
        runOnMainSync {
            result.put("progress_percent", JSONArray(progress)).put("progress_calls", progressCalls)
                .put("invalid_progress_calls", invalidProgressCalls).put("progress_valid", invalidProgressCalls == 0L)
                .put("first_invalid_progress", firstInvalidProgress ?: JSONObject.NULL)
        }
        // This bounds observation only: the public API cannot cancel a provider-managed transfer.
        return result.put("listener_wait_limit_ms", DOWNLOAD_WAIT_MS)
    }

    private companion object {
        // BEGIN PURE_DOWNLOAD_REQUEST_CONTRACT
        internal fun parseRequestArguments(values: Map<String, String?>): String {
            require(values.keys == setOf("locale"))
            return values["locale"]?.takeIf { it in setOf("en-US", "ja-JP", "cmn-Hans-CN") }
                ?: throw IllegalArgumentException("Unsupported download locale")
        }

        internal fun needsDownload(installed: Boolean, pending: Boolean, supported: Boolean): Boolean {
            check(supported)
            if (installed) return false
            check(!pending)
            return true
        }

        internal fun hasLocale(languages: List<String>, locale: String) =
            languages.any { it.equals(locale, ignoreCase = true) }
        // END PURE_DOWNLOAD_REQUEST_CONTRACT
        const val PROVIDER = "com.google.android.as/com.google.android.apps.miphone.aiai.app.AiAiSpeechRecognitionService"
        const val DOWNLOAD_WAIT_MS = 300_000L
    }
}
