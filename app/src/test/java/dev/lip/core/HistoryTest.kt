package dev.lip.core

import dev.lip.AppStore
import dev.lip.HistoryRecords
import java.io.IOException
import dev.lip.Transcript
import org.junit.Assert.*
import org.junit.Test

class HistoryTest {
    @Test fun encryptedPayloadCodecPreservesRawAndCleanMultilingualText() {
        val entries = listOf(Transcript("id", 123L, "uh 你好\nか\u3099", "你好\nが", "zh-CN", true))
        assertEquals(entries, AppStore.decodeHistory(AppStore.encodeHistory(entries)))
        assertTrue(AppStore.decodeHistory("[]").isEmpty())
    }
    @Test(expected = org.json.JSONException::class) fun corruptedHistoryMustNotBecomeEmptyHistory() {
        AppStore.decodeHistory("corrupted")
    }
    @Test fun independentRecordsExceedTheFormerWholeHistoryLimit() {
        val disk = Disk()
        val records = disk.records()
        repeat(5) { index -> records.save(entry("$index", index.toLong(), "a".repeat(1_000_000))) }
        assertEquals(5, records.page(limit = 20).entries.size)
        assertEquals(5, disk.files.size)
        assertFalse(disk.files.containsKey("history"))
        assertTrue(disk.files.values.sumOf { it.toByteArray(Charsets.UTF_8).size } > 4 * 1024 * 1024)
    }

    @Test fun legacyMigrationPreservesUnicodeAndDuplicateEntriesAcrossRestart() {
        val disk = Disk()
        val first = entry("older", 10, "你好\nか\u3099")
        val newest = entry("newest", 20, "raw 🎙️\n東京")
        val legacy = listOf(newest, first, first)
        disk.files["history"] = AppStore.encodeHistory(legacy)
        val migrated = disk.records().page().entries
        assertEquals(legacy.groupingBy { it }.eachCount(), migrated.groupingBy { it }.eachCount())
        assertFalse(disk.files.containsKey("history"))
        val saved = disk.files.toMap()
        assertEquals(migrated, disk.records().page().entries)
        assertEquals(saved, disk.files)
    }

    @Test fun migrationWriteFailureKeepsLegacyThenRetriesWithoutDuplicates() {
        val disk = Disk()
        val legacy = (1..3).map { entry("$it", it.toLong(), "原文 $it") }
        val original = AppStore.encodeHistory(legacy)
        disk.files["history"] = original
        disk.failWrite = 2
        assertTrue(runCatching { disk.records().page() }.isFailure)
        assertEquals(original, disk.files["history"])
        disk.failWrite = null
        assertEquals(legacy.map { it.id }.toSet(), disk.records().page().entries.map { it.id }.toSet())
        assertEquals(3, disk.files.size)
        assertFalse(disk.files.containsKey("history"))
    }

    @Test fun migrationReadbackFailurePreservesOriginalBytes() {
        val disk = Disk()
        val original = AppStore.encodeHistory(listOf(entry("a", 1, "preserve this")))
        disk.files["history"] = original
        disk.corruptNewWrites = true
        assertTrue(runCatching { disk.records().page() }.isFailure)
        assertEquals(original, disk.files["history"])
    }

    @Test fun pagingIsBoundedAndSearchesBothRawAndCleanWithoutDuplicates() {
        val disk = Disk()
        val records = disk.records()
        repeat(45) { index -> records.save(entry("$index", index.toLong(), if (index % 2 == 0) "東京" else "raw $index")) }
        val first = records.page(limit = 20)
        assertEquals((44 downTo 25).map(Int::toString), first.entries.map { it.id })
        assertNotNull(first.nextCursor)
        val second = records.page(after = first.nextCursor, limit = 20)
        assertEquals((24 downTo 5).map(Int::toString), second.entries.map { it.id })
        val final = records.page(after = second.nextCursor, limit = 20)
        assertEquals((4 downTo 0).map(Int::toString), final.entries.map { it.id })
        assertNull(final.nextCursor)
        assertEquals(20, records.page("東京", limit = 20).entries.size)
        assertEquals(listOf("17"), records.page("clean 17", limit = 20).entries.map { it.id })
        assertTrue(records.page("not present").entries.isEmpty())
    }

    @Test fun deletionClearAndDisabledRetentionStayEffective() {
        val disk = Disk()
        val records = disk.records()
        repeat(3) { records.save(entry("$it", it.toLong(), "raw $it")) }
        val before = disk.files.toMap()
        records.save(entry("disabled", 4, "must not save"), enabled = false)
        assertEquals(before, disk.files)
        records.deleteTranscript("1")
        assertEquals(listOf("2", "0"), records.page().entries.map { it.id })
        assertTrue(disk.files.all { before[it.key] == it.value })
        records.clear()
        assertTrue(disk.files.isEmpty())
        assertTrue(records.page().entries.isEmpty())
    }

    @Test fun corruptRecordWriteMustFailInsteadOfReportingASuccessfulSave() {
        val disk = Disk()
        val records = disk.records()
        records.save(entry("existing", 1, "keep this"))
        val original = disk.files.toMap()
        disk.corruptNewWrites = true
        assertTrue(runCatching { records.save(entry("new", 2, "new text")) }.isFailure)
        assertTrue(original.all { disk.files[it.key] == it.value })
    }

    @Test fun noOpRecordWriteMustFailAndPreserveExistingHistory() {
        val disk = Disk()
        val records = disk.records()
        records.save(entry("existing", 1, "keep this"))
        val original = disk.files.toMap()
        disk.skipNewWrites = true
        assertTrue(runCatching { records.save(entry("new", 2, "new text")) }.isFailure)
        assertEquals(original, disk.files)
        assertEquals(listOf("existing"), records.page().entries.map { it.id })
    }

    @Test fun damagedRecordCannotSilentlyBecomeEmptyHistory() {
        val disk = Disk()
        val records = disk.records()
        records.save(entry("a", 1, "raw"))
        disk.files[disk.files.keys.single()] = "corrupted"
        assertTrue(runCatching { records.page() }.isFailure)
        assertEquals("corrupted", disk.files.values.single())
    }

    private fun entry(id: String, time: Long, raw: String) =
        Transcript(id, time, raw, "clean $id", "ja-JP", false)

    // Plaintext fixture verifies record operations/failure recovery, not Android Keystore encryption.
    private class Disk {
        val files = linkedMapOf<String, String>()
        var failWrite: Int? = null
        var writes = 0
        var corruptNewWrites = false
        var skipNewWrites = false
        fun records() = HistoryRecords(
            read = { files[it] },
            write = { name, value ->
                writes++
                if (writes == failWrite || value.toByteArray(Charsets.UTF_8).size > 4 * 1024 * 1024 - 29)
                    throw IOException("Simulated per-file write failure")
                if (!skipNewWrites || name == "history")
                    files[name] = if (corruptNewWrites && name != "history") "corrupted" else value
            },
            names = { files.keys.filter { it.startsWith("history-") } },
            delete = { files.remove(it); Unit },
        )
    }
}
