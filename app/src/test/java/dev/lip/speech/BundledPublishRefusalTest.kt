package dev.lip.speech

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.URL
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class BundledPublishRefusalTest {
    @Test fun rejectedPlatformPublishNeverFallsBackToRenameOrClobbersTarget() {
        val dir = Files.createTempDirectory("lip-publish-refusal-").toFile()
        val payload = byteArrayOf(1, 2, 3, 4)
        val old = byteArrayOf(8, 9)
        val target = File(dir, "model.gguf")
        val sha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val model = ModelFile(dir, URL("https://unused.invalid"), payload.size.toLong(), sha,
            { throw AssertionError("No HTTP") }, { Long.MAX_VALUE }, target.name)
        var calls = 0
        try {
            try {
                model.installBundled({ ByteArrayInputStream(payload) }, publishNoReplace = { source, destination ->
                    calls++; assertArrayEquals(payload, source.readBytes())
                    destination.writeBytes(old)
                    throw FileAlreadyExistsException(destination.path)
                })
                fail("Conflicting target must fail")
            } catch (_: IOException) { }
            assertEquals(1, calls)
            assertArrayEquals(old, target.readBytes())
            assertEquals(listOf("model.gguf"), dir.listFiles()!!.map { it.name })
        } finally { if (target.exists()) check(target.delete()); check(dir.delete()) }
    }

    @Test fun deniedPublisherDoesNotInvokeAnyFallback() {
        val dir = Files.createTempDirectory("lip-publish-denied-").toFile()
        val payload = byteArrayOf(1, 2, 3)
        val sha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        var calls = 0
        try {
            val model = ModelFile(dir, URL("https://unused.invalid"), 3, sha,
                { throw AssertionError("No HTTP") }, { Long.MAX_VALUE }, "model.gguf")
            try {
                model.installBundled({ ByteArrayInputStream(payload) }, publishNoReplace = { _, _ ->
                    calls++; throw IOException("Synthetic platform denial")
                })
                fail("Denied publisher must fail")
            } catch (expected: IOException) { assertEquals("Synthetic platform denial", expected.message) }
            assertEquals(1, calls); assertTrue(dir.listFiles()!!.isEmpty())
        } finally { check(dir.delete()) }
    }
}
