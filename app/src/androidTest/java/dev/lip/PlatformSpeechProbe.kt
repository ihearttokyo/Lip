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
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Public on-device availability/support metadata only; never starts recognition or writes audio. */
class PlatformSpeechProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val report = JSONObject().put("event", "platform_speech_metadata").put("passed", false)
            .put("scope", "public on-device availability/support metadata only")
            .put("recognition_tested", false).put("audio_written", false)
            .put("model_download_requested", false).put("backend_changed", false)
        val requests = JSONArray()
        var stage = "isolation"
        try {
            check(Build.HARDWARE in listOf("ranchu", "goldfish") && Build.PRODUCT.contains("sdk"))
            check(targetContext.packageName == "dev.lip.android" && Build.VERSION.SDK_INT >= 33)
            stage = "public_availability"
            runOnMainSync {
                val id = targetContext.resources.getIdentifier("config_defaultOnDeviceSpeechRecognitionService", "string", "android")
                check(id != 0)
                val configured = targetContext.getString(id)
                val component = ComponentName.unflattenFromString(configured)
                val available = SpeechRecognizer.isOnDeviceRecognitionAvailable(targetContext)
                report.put("configured_component", configured).put("on_device_available", available)
                check(available == (component != null))
                if (component != null) {
                    try {
                        @Suppress("DEPRECATION")
                        val service = targetContext.packageManager.getServiceInfo(component, 0)
                        report.put("service_metadata_visible", true).put("service_enabled", service.enabled)
                            .put("service_application_enabled", service.applicationInfo.enabled)
                    } catch (error: Exception) {
                        report.put("service_metadata_visible", false).put("service_metadata_error", error.javaClass.simpleName)
                    }
                }
                check(available)
            }
            stage = "locale_support"
            for (locale in listOf("en-US", "ja-JP", "zh-CN")) requests.put(probe(locale))
            report.put("passed", (0 until requests.length()).all { index ->
                val request = requests.getJSONObject(index)
                request.optString("outcome") == "support_result" && request.optBoolean("requested_language_installed") &&
                    request.optBoolean("teardown_ok")
            } && requests.length() == 3)
        } catch (error: Exception) {
            report.put("error_stage", stage).put("error_class", error.javaClass.simpleName)
        }
        report.put("requests", requests)
        finish(if (report.getBoolean("passed")) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("platform_speech_probe", report.toString()) })
    }

    private fun probe(locale: String): JSONObject {
        val started = SystemClock.elapsedRealtime()
        val completed = CountDownLatch(1)
        val terminal = AtomicReference<JSONObject?>()
        var recognizer: SpeechRecognizer? = null
        var pipe: Array<ParcelFileDescriptor>? = null
        var result = JSONObject().put("outcome", "request_exception")
        var cleanupOk = true
        try {
            val ownedPipe = ParcelFileDescriptor.createPipe().also { pipe = it }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, ownedPipe[0])
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
                .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            val callback = object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    val response = JSONObject().put("outcome", "support_result")
                        .put("installed_on_device_languages", JSONArray(support.installedOnDeviceLanguages))
                        .put("pending_on_device_languages", JSONArray(support.pendingOnDeviceLanguages))
                        .put("supported_on_device_languages", JSONArray(support.supportedOnDeviceLanguages))
                        .put("online_languages", JSONArray(support.onlineLanguages))
                        .put("requested_language_installed", support.installedOnDeviceLanguages.any { it.equals(locale, ignoreCase = true) })
                    if (terminal.compareAndSet(null, response)) completed.countDown()
                }

                override fun onError(error: Int) {
                    val response = JSONObject().put("outcome", "error").put("error_code", error)
                    if (terminal.compareAndSet(null, response)) completed.countDown()
                }
            }
            runOnMainSync {
                val ownedRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(targetContext).also { recognizer = it }
                ownedRecognizer.checkRecognitionSupport(intent, targetContext.mainExecutor, callback)
            }
            result = if (completed.await(15, TimeUnit.SECONDS)) checkNotNull(terminal.get())
                else JSONObject().put("outcome", "timeout")
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            result.put("outcome", "request_exception").put("error_class", error.javaClass.simpleName)
        } finally {
            try { runOnMainSync { recognizer?.destroy() } } catch (_: Exception) { cleanupOk = false }
            pipe?.forEach { descriptor ->
                try { descriptor.close() } catch (_: Exception) { cleanupOk = false }
            }
        }
        return result.put("locale", locale).put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            .put("callback_received", completed.count == 0L).put("teardown_ok", cleanupOk)
    }
}
