package dev.lip

/** Lossless platform bridge. Import validation remains strict before conversion. */
fun HistoryImport.portable(entry: Transcript) = PortableHistoryRecord(entry.id, entry.time, entry.raw,
    entry.clean, entry.language, entry.model, entry.modelDetails, entry.usedChatGpt)
fun HistoryImport.local(record: PortableHistoryRecord) = Transcript(record.id, record.createdAtUnixMs,
    record.original, record.output, record.language, record.legacyCloudCleanup, record.model, record.modelDetails)

/** RAM-only operation authorization. No storage, preferences or capture consent.
 * Cancellation prevents only unclaimed admission, not an already-published save. */
class HistoryImportLease {
    private var live = true
    @Synchronized fun cancel() { live = false }
    @Synchronized fun claim() { check(live) { "Import canceled or already admitted." }; live = false }
}
