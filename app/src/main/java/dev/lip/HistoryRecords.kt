package dev.lip

import java.security.MessageDigest
import java.util.Base64

data class HistoryPage(val entries: List<Transcript>, val nextCursor: String?)

/** SecureStore supplies encrypted atomic files; JVM fixtures supply deterministic storage failures. */
class HistoryRecords(
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit,
    private val names: () -> List<String>,
    private val delete: (String) -> Unit,
) {
    fun save(entry: Transcript, enabled: Boolean = true) = synchronized(lock) {
        if (!enabled) return@synchronized
        migrate()
        val payload = AppStore.encodeHistory(listOf(entry))
        val record = name(entry, payload)
        write(record, payload)
        check(read(record) == payload) { "History save could not be verified. The current transcript remains available." }
    }

    fun page(query: String = "", after: String? = null, limit: Int = 20): HistoryPage = synchronized(lock) {
        require(limit in 1..100) { "Invalid history page size" }
        migrate()
        val entries = mutableListOf<Transcript>()
        var cursor: String? = null
        // ponytail: linear filename/search scan, but only one record plus the page is decrypted at a time.
        // Move to encrypted SQLite rows if measured file-count latency warrants it.
        for (name in names().sortedDescending()) {
            if (after != null && name >= after) continue
            val entry = read(name)?.let { AppStore.decodeHistory(it).single() } ?: continue
            if (!entry.raw.contains(query, true) && !entry.clean.contains(query, true)) continue
            if (entries.size == limit) return@synchronized HistoryPage(entries, cursor)
            entries.add(entry)
            cursor = name
        }
        HistoryPage(entries, null)
    }

    fun deleteTranscript(id: String) = synchronized(lock) {
        migrate()
        val targets = names().filter { name ->
            read(name)?.let { AppStore.decodeHistory(it).single().id == id } == true
        }
        targets.forEach(delete)
    }

    fun clear() = synchronized(lock) {
        delete("history")
        names().forEach(delete)
    }

    private fun migrate() {
        val legacy = read("history") ?: return
        val copies = mutableMapOf<String, Int>()
        for (entry in AppStore.decodeHistory(legacy)) {
            val payload = AppStore.encodeHistory(listOf(entry))
            val base = name(entry, payload)
            val count = copies.getOrDefault(base, 0)
            copies[base] = count + 1
            val name = if (count == 0) base else "$base.$count"
            // A deterministic copy can be repaired from the still-intact original after an interrupted migration.
            if (runCatching { read(name) }.getOrNull() != payload) write(name, payload)
            check(read(name) == payload) { "History migration could not be verified. Original data was preserved." }
        }
        // Every copy is readable before removing the aggregate. Failed writes never remove the original.
        delete("history")
    }

    private fun name(entry: Transcript, payload: String): String {
        val time = java.lang.Long.toHexString(entry.time xor Long.MIN_VALUE).padStart(16, '0')
        val hash = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8)))
        return "history-$time-$hash"
    }

    companion object {
        // Migration/save/delete must serialize across AppStore instances, not just within one screen.
        private val lock = Any()
    }
}
