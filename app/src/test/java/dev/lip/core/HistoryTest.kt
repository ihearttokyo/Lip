package dev.lip.core

import dev.lip.AppStore
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
}
