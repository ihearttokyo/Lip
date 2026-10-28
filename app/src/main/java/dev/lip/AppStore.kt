package dev.lip

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import dev.lip.auth.SecureStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Transcript(
    val id: String = UUID.randomUUID().toString(),
    val time: Long = System.currentTimeMillis(),
    val raw: String,
    val clean: String,
    val language: String?,
    val usedChatGpt: Boolean,
    val model: String? = null,
    val modelDetails: String? = null,
)

/** Missing portable data must be visible to the consumer: suggestions/manual
 * remain usable, but automatic correction MUST be disabled while unavailable.
 */
data class TypingDictionarySnapshot(val words: List<String>, val portableAvailable: Boolean) {
    override fun toString() = "TypingDictionarySnapshot(words=${words.size}, portableAvailable=$portableAvailable)"
}
internal fun typingDictionarySnapshotOf(manual: List<String>, portable: List<dev.lip.vocabulary.PortableVocabulary.Entry>?): TypingDictionarySnapshot {
    val words = LinkedHashMap<String, String>()
    manual.forEach { words.putIfAbsent(it.lowercase(java.util.Locale.ROOT), it) }
    portable?.filter(dev.lip.vocabulary.PortableVocabularyStore::activeEnglish)?.forEach {
        words.putIfAbsent(it.term.lowercase(java.util.Locale.ROOT), it.term)
    }
    return TypingDictionarySnapshot(words.values.toList(), portable != null)
}

class AppStore(context: Context) {
    val settings = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    private val secure = SecureStore(context)
    private val portableWords = dev.lip.vocabulary.PortableVocabularyStore(context)
    private val historyRecords = HistoryRecords(secure::read, secure::write, { secure.names("history-") }, secure::delete, secure::writeAdmitted, usage = { secure.historyUsage() })
    private val historyConsent = historyConsentFor(settings)
    fun historyReadiness() = historyConsent.snapshot()
    fun historyTicket() = historyConsent.ticket()
    fun historyResultCurrent(ticket: HistoryConsentTicket) = historyConsent.sameRequest(ticket)
    fun setHistoryEnabled(value: Boolean, done: (HistoryConsentGate.Snapshot) -> Unit = {}) {
        val request = historyConsent.request(value) // Immediate CPU-only revoke, before queued IO.
        notifyHistoryObservers()
        historyPreferences.execute {
            HistoryConsentPersistence.apply(historyConsent, request, value,
                drain = { HistoryRecords.awaitDrain() },
                persist = { settings.edit().putBoolean(HISTORY_CONSENT, it).commit() })
            Handler(Looper.getMainLooper()).post {
                historyObservers.forEach { it() }
                val current = historyConsent.snapshot()
                if (current.request == request) done(current)
            }
        }
    }
    fun observeHistory(listener: () -> Unit) { historyObservers.add(listener); listener() }
    fun unobserveHistory(listener: () -> Unit) { historyObservers.remove(listener) }
    private fun notifyHistoryObservers() { Handler(Looper.getMainLooper()).post { historyObservers.forEach { it() } } }
    var typingSuggestions:Boolean
        get()=settings.getBoolean("typingSuggestions",true)
        set(value){settings.edit().putBoolean("typingSuggestions",value).apply()}
    var typingAutocorrect:Boolean
        get()=settings.getBoolean("typingAutocorrect",true)
        set(value){settings.edit().putBoolean("typingAutocorrect",value).apply()}
    var speechModel: String
        get() = dev.lip.speech.BarkProfile.fromId(settings.getString("speechModel", null)).id
        set(value) { settings.edit().putString("speechModel", dev.lip.speech.BarkProfile.fromId(value).id).apply() }
    var language: String
        get() = settings.getString("language", "en-US") ?: "en-US"
        set(value) { settings.edit().putString("language", value).apply() }
    var style: String
        get() = settings.getString("style", "polished") ?: "polished"
        set(value) { settings.edit().putString("style", value).apply() }
    var model: String
        get() = settings.getString("model", "") ?: ""
        set(value) { settings.edit().putString("model", value).apply() }
    val historyEnabled: Boolean
        get() = historyConsent.snapshot().requested
    var autoInsert: Boolean
        get() = settings.getBoolean("autoInsert", true)
        set(value) { settings.edit().putBoolean("autoInsert", value).apply() }
    var cloudConsent: Boolean
        get() = settings.getBoolean("cloudConsent", false)
        set(value) { settings.edit().putBoolean("cloudConsent", value).apply() }
    var liveCleanup: Boolean
        get() = settings.getBoolean("liveCleanup", false)
        set(value) { settings.edit().putBoolean("liveCleanup", value).apply() }
    var bubbleEnabled: Boolean
        get() = settings.getBoolean("bubble", true)
        set(value) { settings.edit().putBoolean("bubble", value).apply() }

    @Synchronized fun dictionary(): List<String> =
        JSONArray(secure.read("dictionary") ?: "[]").let { array ->
            List(array.length()) { array.getString(it) }
        }
    @Synchronized fun saveDictionary(words: List<String>) {
        val mutation = dev.lip.keyboard.SwipeVocabularyEpoch.process.beginWrite()
        try {
            secure.write("dictionary", JSONArray(words.map(String::trim).filter(String::isNotEmpty).distinct()).toString())
            settings.edit().putString("dictionaryRevision", UUID.randomUUID().toString()).apply()
        } finally { mutation.finish() }
    }
    fun portableVocabularyEntries() = portableWords.snapshot().entries
    fun previewPortableVocabulary(incoming: List<dev.lip.vocabulary.PortableVocabulary.Entry>) = portableWords.preview(incoming)
    fun mergePortableVocabulary(incoming: List<dev.lip.vocabulary.PortableVocabulary.Entry>, admit: () -> Unit): dev.lip.vocabulary.PortableVocabularyStore.MergeResult {
        val mutation = dev.lip.keyboard.SwipeVocabularyEpoch.process.beginWrite()
        try {
            val result = portableWords.merge(incoming, admit)
            if (result.changed) settings.edit().putString("portableVocabularyRevision", result.revision).apply()
            return result
        } finally { mutation.finish() }
    }
    fun exportPortableVocabulary(): ByteArray = portableWords.export()
    /** Manual spelling wins; portable phrases/other languages remain stored but
     * are not advertised as active English keyboard/speech-model support.
     */
    fun typingDictionarySnapshot(): TypingDictionarySnapshot {
        val manual = dictionary() // Legacy-store failure remains explicit, not silently discarded.
        val portable = runCatching { portableVocabularyEntries() }.getOrNull()
        return typingDictionarySnapshotOf(manual, portable)
    }
    fun typingDictionary(): List<String> = typingDictionarySnapshot().words
    fun historyPage(query: String = "", after: String? = null, limit: Int = 20): HistoryPage =
        historyRecords.page(query, after, limit)
    fun save(entry: Transcript, ticket: HistoryConsentTicket?): Boolean {
        if (ticket == null || !historyConsent.eligible(ticket)) return false
        historyRecords.save(entry, enabled = true) { historyConsent.claim(ticket) }
        return true
    }
    fun previewHistoryImport(record: PortableHistoryRecord) = historyRecords.previewImport(record)
    fun importHistory(record: PortableHistoryRecord, lease: HistoryImportLease): HistoryImportDisposition =
        historyRecords.importRecord(record, lease::claim)
    fun deleteTranscript(id: String) = historyRecords.deleteTranscript(id)
    fun clearHistory() = historyRecords.clear()

    companion object {
        private const val HISTORY_CONSENT = "history.consent.v1"
        private val historyPreferences = java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread(task, "lip-history-preference").apply { isDaemon = true } }
        private val historyObservers = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
        private val registry = Any()
        private var processHistoryConsent: HistoryConsentGate? = null
        private fun historyConsentFor(settings: SharedPreferences): HistoryConsentGate {
            // Read outside the admission lock. Existing gate is never overwritten by
            // stale preferences while an asynchronous consent change is pending.
            val explicit = runCatching { settings.all[HISTORY_CONSENT] }.getOrNull()
            return synchronized(registry) { processHistoryConsent ?: HistoryConsentGate(HistoryConsentPolicy.explicit(explicit)).also { processHistoryConsent = it } }
        }
        fun encodeHistory(entries: List<Transcript>): String = JSONArray(entries.map { entry ->
            JSONObject().put("id", entry.id).put("time", entry.time).put("raw", entry.raw)
                .put("clean", entry.clean).put("language", entry.language ?: JSONObject.NULL).put("chatgpt", entry.usedChatGpt)
                .apply {
                    // Preserve old canonical payload bytes/hash names for unknown metadata.
                    entry.model?.let { put("model", it) }
                    entry.modelDetails?.let { put("modelDetails", it) }
                }
        }).toString()

        fun decodeHistory(value: String): List<Transcript> = JSONArray(value).let { array ->
            List(array.length()) { index ->
                array.getJSONObject(index).let {
                    Transcript(it.getString("id"), it.getLong("time"), it.getString("raw"),
                        it.getString("clean"), if (it.has("language") && it.isNull("language")) null else it.getString("language"), it.getBoolean("chatgpt"),
                        if (it.isNull("model")) null else it.getString("model"), if (it.isNull("modelDetails")) null else it.getString("modelDetails"))
                }
            }
        }
    }
}
