package dev.lip

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import dev.lip.auth.ChatGptClient
import dev.lip.uiprotocol.CloudCleanupProbe
import org.json.JSONObject

/** Requires an existing guest login and existing app consent; performs no sign-in or setup. */
class LipCloudCleanupRunner : Instrumentation() {
    private var isolated = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        isolated = arguments?.getString("isolated_emulator") == "true"
        start()
    }

    override fun onStart() {
        val report = if (!isolated || targetContext.packageName != "dev.lip.android" ||
            Build.HARDWARE !in listOf("ranchu", "goldfish") || !Build.PRODUCT.contains("sdk")) {
            JSONObject().put("passed", false).put("status", "isolated_emulator_required")
        } else {
            val store = AppStore(targetContext)
            CloudCleanupProbe.run(ChatGptClient(targetContext), store.model) {
                store.cloudConsent && !Thread.currentThread().isInterrupted
            }
        }
        finish(if (report.optBoolean("passed")) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("lip_cloud_cleanup", report.toString()) })
    }
}
