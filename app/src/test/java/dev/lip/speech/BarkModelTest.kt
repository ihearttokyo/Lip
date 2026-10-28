package dev.lip.speech

import java.io.File
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class BarkModelTest {
    @Test fun cleanIsStandardAndProfilesAreIsolated() {
        assertEquals(BarkProfile.CLEAN, BarkProfile.fromId(null))
        assertEquals(BarkProfile.CLEAN, BarkProfile.fromId("unknown"))
        assertEquals(BarkProfile.PRO, BarkProfile.fromId("pro"))
        assertNotEquals(BarkProfile.CLEAN.fileName, BarkProfile.PRO.fileName)
        assertNotEquals(BarkProfile.CLEAN.directoryName, BarkProfile.PRO.directoryName)
        assertEquals(0, BarkProfile.CLEAN.paddingSamples)
        assertEquals(4000, BarkProfile.PRO.paddingSamples)
        assertEquals(BarkModel.NAME, BarkProfile.PRO.fileName)
        assertEquals(BarkModel.SOURCE_URL, BarkProfile.PRO.sourceUrl)
    }
    @Test fun bothProfilesPinRevisionAndHash() {
        for (profile in BarkProfile.entries) {
            assertEquals(40, profile.revision.length)
            assertEquals(64, profile.sha256.length)
            assertTrue(profile.sourceUrl.contains(profile.revision))
            assertTrue(profile.sourceUrl.endsWith(profile.fileName))
        }
        assertEquals("d1eec8fc4b9aab0fb6a83ddba6a56a0f93b094d350d069f885a843e31eaee0cf", BarkProfile.CLEAN.sha256)
    }

    @Test fun modelIsPinnedEnglishDerivativeNotWhisper() {
        assertEquals(364_020_736L, BarkModel.BYTES)
        assertTrue(BarkModel.SOURCE_URL.contains(BarkModel.REVISION))
        assertTrue(BarkModel.SOURCE_URL.endsWith(BarkModel.NAME))
        assertEquals(64, BarkModel.SHA256.length)
        assertNotEquals(ModelFile.NAME, BarkModel.NAME)
    }
    @Test fun modelFileNamesDoNotOverwriteLegacyPayloads() {
        val directory = Files.createTempDirectory("lip-bark-model").toFile()
        val payload = byteArrayOf(1, 2, 3, 4)
        val sha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it.toInt() and 255) }
        val legacy = File(directory, ModelFile.NAME).apply { writeText("legacy-preserved") }
        val bark = File(directory, BarkModel.NAME).apply { writeBytes(payload) }
        try {
            val model = ModelFile(directory, URL("https://example.invalid/model"), payload.size.toLong(), sha,
                { error("Must not download a verified local model") }, { Long.MAX_VALUE }, BarkModel.NAME)
            assertEquals(bark, model.verifiedFile())
            assertEquals(bark, model.install())
            assertEquals("legacy-preserved", legacy.readText())
        } finally {
            // Delete only this test's known files; no user model/history paths.
            bark.delete(); legacy.delete(); directory.delete()
        }
    }
}
