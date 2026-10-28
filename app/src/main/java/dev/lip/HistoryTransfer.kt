package dev.lip

import org.json.JSONObject
import java.nio.charset.StandardCharsets.UTF_8

/** Explicit plaintext export only. Never automatically upload/import these bytes. */
object HistoryTransfer {
    const val SCHEMA = "dev.lip.dictation-history"
    const val MAX_BYTES = 4 * 1024 * 1024
    fun strictBytes(value: String): ByteArray {
        val encoded = UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value))
        return ByteArray(encoded.remaining()).also { encoded.get(it) }
    }
    fun encode(entry: Transcript): ByteArray {
        // Validate original fields BEFORE JSON serialization can escape/rewrite them.
        for (value in listOf(entry.id,entry.raw,entry.clean,entry.language,entry.model,entry.modelDetails)) if (value != null) strictBytes(value)
        require(entry.id.isNotBlank() && strictBytes(entry.id).size <= 256) { "Invalid history ID; original retained." }
        require(entry.time in 0..253402300799999L) { "Invalid history date; original retained." }
        for (value in listOf(entry.language, entry.model, entry.modelDetails))
            require(value == null || strictBytes(value).size <= 4096) { "History metadata too large; original retained." }
        val item = JSONObject().put("id",entry.id).put("createdAtUnixMs",entry.time)
            .put("original",entry.raw).put("output",entry.clean).put("language",entry.language ?: JSONObject.NULL)
            .put("model",entry.model ?: JSONObject.NULL).put("modelDetails",entry.modelDetails ?: JSONObject.NULL)
            .put("legacyCloudCleanup",entry.usedChatGpt)
        val bytes = strictBytes(JSONObject().put("schema",SCHEMA).put("version",1).put("sourcePlatform","android").put("record",item).toString(2))
        require(bytes.size <= MAX_BYTES) { "History export exceeds 4 MiB; original retained." }
        return bytes
    }
}

data class HistoryUsage(val bytes: Long, val entries: Int, val legacy: Boolean = false)
data class HistoryRetentionLimits(val bytes: Long = 50L*1024*1024, val entries: Int = 2000, val payload: Int = 1024*1024, val file: Int = 2*1024*1024)
