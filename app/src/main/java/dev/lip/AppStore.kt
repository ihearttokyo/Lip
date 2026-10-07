package dev.lip

import android.content.Context
import dev.lip.auth.SecureStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Transcript(
    val id: String = UUID.randomUUID().toString(),
    val time: Long = System.currentTimeMillis(),
    val raw: String,
    val clean: String,
    val language: String,
    val usedChatGpt: Boolean,
)

class AppStore(context: Context) {
    val settings = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    private val secure = SecureStore(context)
    var language: String
        get() = settings.getString("language", "en-US") ?: "en-US"
        set(value) { settings.edit().putString("language", value).apply() }
    var style: String
        get() = settings.getString("style", "polished") ?: "polished"
        set(value) { settings.edit().putString("style", value).apply() }
    var model: String
        get() = settings.getString("model", "") ?: ""
        set(value) { settings.edit().putString("model", value).apply() }
    var historyEnabled: Boolean
        get() = settings.getBoolean("history", true)
        set(value) { settings.edit().putBoolean("history", value).apply() }
    var autoInsert: Boolean
        get() = settings.getBoolean("autoInsert", true)
        set(value) { settings.edit().putBoolean("autoInsert", value).apply() }
    var cloudConsent: Boolean
        get() = settings.getBoolean("cloudConsent", false)
        set(value) { settings.edit().putBoolean("cloudConsent", value).apply() }
    var bubbleEnabled: Boolean
        get() = settings.getBoolean("bubble", true)
        set(value) { settings.edit().putBoolean("bubble", value).apply() }

    @Synchronized fun dictionary(): List<String> =
        JSONArray(secure.read("dictionary") ?: "[]").let { array ->
            List(array.length()) { array.getString(it) }
        }
    @Synchronized fun saveDictionary(words: List<String>) {
        secure.write("dictionary", JSONArray(words.map(String::trim).filter(String::isNotEmpty).distinct()).toString())
    }
    @Synchronized fun history(): List<Transcript> = decodeHistory(secure.read("history") ?: "[]")
    @Synchronized fun save(entry: Transcript) {
        if (historyEnabled) writeHistory(listOf(entry) + history())
    }
    @Synchronized fun deleteTranscript(id: String) = writeHistory(history().filterNot { it.id == id })
    @Synchronized fun clearHistory() = secure.delete("history")

    // ponytail: rewrites a local history file; use encrypted SQLite rows if large histories make saves slow.
    private fun writeHistory(entries: List<Transcript>) = secure.write("history", encodeHistory(entries))

    companion object {
        fun encodeHistory(entries: List<Transcript>): String = JSONArray(entries.map { entry ->
            JSONObject().put("id", entry.id).put("time", entry.time).put("raw", entry.raw)
                .put("clean", entry.clean).put("language", entry.language).put("chatgpt", entry.usedChatGpt)
        }).toString()

        fun decodeHistory(value: String): List<Transcript> = JSONArray(value).let { array ->
            List(array.length()) { index ->
                array.getJSONObject(index).let {
                    Transcript(it.getString("id"), it.getLong("time"), it.getString("raw"),
                        it.getString("clean"), it.getString("language"), it.getBoolean("chatgpt"))
                }
            }
        }
    }
}
