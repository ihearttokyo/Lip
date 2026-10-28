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
    private val writeAdmitted: (String, String, () -> Unit) -> Unit = { name, value, admit -> admit(); write(name, value) },
    private val limits: HistoryRetentionLimits = HistoryRetentionLimits(),
    private val usage: () -> HistoryUsage = {
        val files = names(); HistoryUsage(files.sumOf { (read(it) ?: error("History entry unavailable")).toByteArray(Charsets.UTF_8).size.toLong() + 29L }, files.size, files.contains("history"))
    },
) {
    fun save(entry: Transcript, enabled: Boolean, admit: () -> Unit = {}) = synchronized(lock) {
        if (!enabled) return@synchronized
        // Capture saves never migrate unrelated legacy data before admission.
        // Explicit history browsing retains the verified migration path below.
        val payload = AppStore.encodeHistory(listOf(entry))
        val size = payload.toByteArray(Charsets.UTF_8).size.toLong()
        check(size <= limits.payload && size + 29L <= limits.file) { "History record limit reached; original text kept in memory." }
        val used = usage()
        check(!used.legacy) { "Review History to migrate the existing archive before new saves; original preserved." }
        check(used.bytes >= 0 && used.entries >= 0 && used.entries < limits.entries && used.bytes <= limits.bytes && size + 29L <= limits.bytes - used.bytes) {
            "History storage limit reached; no old record was removed."
        }
        val record = name(entry, payload)
        writeAdmitted(record, payload, admit)
        check(read(record) == payload) { "History save could not be verified. The current transcript remains available." }
    }

    /** Explicit one-record restore, independent of automatic recording consent.
     * The lock covers dedup and final admission; no legacy migration or overwrite. */
    fun previewImport(record: PortableHistoryRecord): HistoryImportDisposition = synchronized(lock) {
        HistoryTransfer.encode(HistoryImport.local(record)) // Validate constructed callers as well as parsed input.
        val used = usage()
        check(!used.legacy) { "Review History before importing into an older archive." }
        check(used.bytes >= 0 && used.entries >= 0) { "History capacity is unavailable; existing data preserved." }
        // Existing overfull archives remain readable, but do not scan an unbounded
        // archive as part of import. A normal archive has at most 2,000 files.
        val currentNames = names()
        check(currentNames.size <= limits.entries) { "History archive is over its import limit." }
        val matches = ArrayList<PortableHistoryRecord>(2)
        for(name in currentNames) {
            val entry = read(name)?.let { HistoryImport.decodeLocal(HistoryTransfer.strictBytes(it)) }
                ?: error("History record unavailable; nothing imported.")
            if(entry.id == record.id) {
                matches.add(entry)
                check(matches.size < 2) { "History ID is ambiguous; existing entries preserved." }
            }
        }
        HistoryImport.classify(record, matches)
    }
    fun importRecord(record: PortableHistoryRecord, admit: () -> Unit): HistoryImportDisposition = synchronized(lock) {
        val disposition = previewImport(record)
        when(disposition) {
            HistoryImportDisposition.NEW -> save(HistoryImport.local(record), true, admit)
            HistoryImportDisposition.IDENTICAL -> admit() // Consume explicit one-shot authorization even for a no-op.
            else -> error("History ID conflict or ambiguity; existing entries preserved.")
        }
        disposition
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
        fun awaitDrain() { synchronized(lock) { /* Earlier admitted save/readback has returned. */ } }
    }
}
