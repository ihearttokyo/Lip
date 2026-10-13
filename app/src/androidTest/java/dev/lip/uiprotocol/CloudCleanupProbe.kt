package dev.lip.uiprotocol

import dev.lip.auth.ChatGptClient
import dev.lip.core.CleanupRules
import org.json.JSONArray
import org.json.JSONObject

/** Public-text cleanup validation only; never reads audio, dictionary or history. */
object CloudCleanupProbe {
    fun run(client: ChatGptClient, preferredModel: String, permitted: () -> Boolean): JSONObject {
        val rows = JSONArray()
        val report = JSONObject().put("passed", false).put("status", "not_requested")
            .put("scope", "Public-text cleanup only; NOT speech or parity").put("rows", rows)
        fun stop(status: String) = report.put("status", status)
        var stage = "session"
        try {
            if (!permitted()) return stop("consent_required")
            val session = client.session() ?: return stop("sign_in_required")
            if (!session.canUsePlan) return stop("plan_permission_required")
            if (!permitted()) return stop("cancelled")
            stage = "catalog"
            val models = client.listModels()
            if (!permitted()) return stop("cancelled")
            val model = (models.firstOrNull { it.slug == preferredModel } ?: models.firstOrNull())?.slug
                ?: return stop("models_unavailable")
            if (!model.matches(Regex("[A-Za-z0-9._:/-]{1,200}"))) return stop("models_unavailable")
            report.put("model", model)
            stage = "cleanup"
            var passed = true
            for ((language, raw) in FIXTURES) {
                if (!permitted()) return stop("cancelled")
                val started = System.nanoTime()
                val cleaned = client.clean(raw, model, "light", emptyList())
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                if (!permitted()) return stop("cancelled")
                // Binder reports are bounded; never truncate an oversized output into a passing candidate.
                if (cleaned.length > 1_024) return stop("cleanup_failed")
                    .put("stage", stage).put("error", "Public cleanup probe output exceeded 1024 characters.")
                val assessment = CleanupRules.assess(raw, cleaned, "light", emptyList())
                val punctuated = cleaned.trimEnd().endsWith(if (language == "en-US") "." else "。")
                passed = passed && assessment.acceptableForAuto && punctuated
                rows.put(JSONObject().put("language", language).put("raw", raw).put("cleaned", cleaned)
                    .put("elapsed_ms", elapsedMs).put("safe", assessment.acceptableForAuto)
                    .put("reasons", JSONArray(assessment.reasons)).put("punctuated", punctuated))
            }
            return stop("completed").put("passed", passed)
        } catch (failure: Exception) {
            return stop("${stage}_failed").put("stage", stage).put("error", CleanupRules.cleanupFailure(failure))
        }
    }

    private val FIXTURES = listOf(
        "en-US" to "please send the report to Mika tomorrow keep 1000 JPY unchanged",
        "ja-JP" to "明日 美香に レポートを送ってください 1000 JPYは変えないでください",
        "zh-CN" to "明天请把报告发给美香 保留1000 JPY 不要改动",
    )
}
