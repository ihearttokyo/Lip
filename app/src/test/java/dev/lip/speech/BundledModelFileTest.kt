package dev.lip.speech

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** Synthetic bytes only: these controls do not prove model/JNI inference. */
class BundledModelFileTest {
    private val bytes = ByteArray(131_077) { (it * 17).toByte() }
    private val hash get() = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun model(dir: File) = ModelFile(dir, URL("https://unused.invalid/model"), bytes.size.toLong(), hash,
        { throw AssertionError("Bundled installation must not use HTTP") }, { Long.MAX_VALUE }, "model.gguf")
    private fun fixture(test: (File) -> Unit) {
        val dir = Files.createTempDirectory("lip-bundle-test-").toFile()
        try { test(dir) }
        finally {
            // All children belong to this newly-created test directory; no user paths.
            dir.listFiles()?.forEach { check(it.delete()) }
            check(dir.delete())
        }
    }
    private fun fails(block: () -> Unit): Throwable {
        try { block() } catch (failure: Throwable) { return failure }
        throw AssertionError("Expected refusal")
    }
    private fun noPartials(dir: File) = assertFalse(dir.listFiles()!!.any { it.name.endsWith(".part") })

    @Test fun streamedVerifiedPublishAndValidReuse() = fixture { dir ->
        var opens = 0; var closes = 0; var largest = 0
        val result = model(dir).installBundled({
            opens++
            object : ByteArrayInputStream(bytes) {
                override fun read(buffer: ByteArray, off: Int, length: Int): Int {
                    largest = maxOf(largest, length); return super.read(buffer, off, length)
                }
                override fun close() { closes++; super.close() }
            }
        })
        assertArrayEquals(bytes, result.readBytes()); assertEquals(1, opens); assertEquals(1, closes)
        assertTrue(largest in 1..65_536); noPartials(dir)
        assertEquals(result, model(dir).installBundled({ throw AssertionError("Valid target must win") }))
    }

    @Test fun existingInvalidDataIsPreserved() = fixture { dir ->
        val target = File(dir, "model.gguf"); val old = byteArrayOf(9, 8, 7); target.writeBytes(old)
        assertTrue(fails { model(dir).installBundled({ throw AssertionError("Do not open asset") }) } is IOException)
        assertArrayEquals(old, target.readBytes()); noPartials(dir)
    }

    @Test fun corruptShortAndOversizeInputsNeverPublish() {
        for (payload in listOf(bytes.copyOf(bytes.size - 1), bytes + 0, bytes.copyOf().also { it[0] = 99 })) fixture { dir ->
            var closed = false
            assertTrue(fails { model(dir).installBundled({ object : ByteArrayInputStream(payload) {
                override fun close() { closed = true; super.close() }
            } }) } is IOException)
            assertTrue(closed); assertFalse(File(dir, "model.gguf").exists()); noPartials(dir)
        }
    }

    @Test fun cancellationClosesInputAndCleansOwnedStaging() = fixture { dir ->
        var canceled = false; var closed = false; var reads = 0
        assertTrue(fails { model(dir).installBundled({ object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, off: Int, length: Int): Int {
                reads++; val result = super.read(buffer, off, length); canceled = true; return result
            }
            override fun close() { closed = true; super.close() }
        } }, { canceled }) } is CancellationException)
        assertEquals(1, reads); assertTrue(closed); assertFalse(File(dir, "model.gguf").exists()); noPartials(dir)
    }

    @Test fun existingTargetVerificationIsCancelable() = fixture { dir ->
        File(dir, "model.gguf").writeBytes(bytes)
        var checkpoints = 0
        assertTrue(fails { model(dir).installBundled({ throw AssertionError("No asset needed") }, { ++checkpoints >= 3 }) } is CancellationException)
        assertTrue(checkpoints >= 3); assertArrayEquals(bytes, File(dir, "model.gguf").readBytes()); noPartials(dir)
    }

    @Test fun racingValidTargetWinsWithoutReplacement() = fixture { dir ->
        val target = File(dir, "model.gguf")
        val result = model(dir).installBundled({ target.writeBytes(bytes); ByteArrayInputStream(bytes) })
        assertEquals(target, result); assertArrayEquals(bytes, target.readBytes()); noPartials(dir)
    }

    @Test fun racingInvalidTargetIsPreserved() = fixture { dir ->
        val target = File(dir, "model.gguf"); val old = byteArrayOf(2, 4, 6)
        assertTrue(fails { model(dir).installBundled({ target.writeBytes(old); ByteArrayInputStream(bytes) }) } is IOException)
        assertArrayEquals(old, target.readBytes()); noPartials(dir)
    }

    @Test fun noProgressInputFailsWithoutSpinning() = fixture { dir ->
        var reads = 0; var closed = false
        assertTrue(fails { model(dir).installBundled({ object : InputStream() {
            override fun read(): Int = throw AssertionError("Use bounded bulk reads")
            override fun read(buffer: ByteArray, off: Int, length: Int): Int { reads++; return 0 }
            override fun close() { closed = true }
        } }) } is IOException)
        assertEquals(1, reads); assertTrue(closed); assertFalse(File(dir, "model.gguf").exists()); noPartials(dir)
    }
}
