package dev.lip.speech

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

/** Real loopback HTTP and disk; tiny synthetic bytes, never a model download. */
class ModelFileTest {
    @Test fun productionPinMatchesTheVerifiedImmutableTurboManifest() {
        assertEquals("ggml-large-v3-turbo-q5_0.bin", ModelFile.NAME)
        assertEquals(574_041_195L, ModelFile.BYTES)
        assertEquals("394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2", ModelFile.SHA256)
        assertEquals("https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-large-v3-turbo-q5_0.bin", ModelFile.SOURCE_URL)
    }

    @Test fun streamsAndVerifiesPublishedFileWithBoundedProgress() {
        Fixture().use { fixture ->
            fixture.reply(Reply(200, payload))
            val progress = mutableListOf<Pair<Long, Long>>()
            val installed = fixture.model.install { bytes, total -> progress.add(bytes to total) }
            assertEquals(fixture.target, installed)
            assertArrayEquals(payload, installed.readBytes())
            assertEquals(installed, fixture.model.verifiedFile())
            assertTrue(progress.size in 2..102)
            assertEquals(0L, progress.first().first)
            assertEquals(payload.size.toLong(), progress.last().first)
            assertTrue(progress.all { it.first in 0..payload.size.toLong() && it.second == payload.size.toLong() })
            assertTrue(progress.zipWithNext().all { (first, second) -> first.first <= second.first })
            assertEquals(1, fixture.requests.size)
            assertEquals("GET", fixture.requests.single().method)
            assertEquals("identity", fixture.requests.single().headers["accept-encoding"])
            assertTrue(fixture.requests.single().headers["authorization"].isNullOrEmpty())
            assertTrue(fixture.requests.single().headers["cookie"].isNullOrEmpty())
            fixture.noPartials()
        }
    }

    @Test fun alreadyGoodModelNeedsNoConnectionAndIsNeverOverwritten() {
        Fixture(free = 0).use { fixture ->
            fixture.target.writeBytes(payload)
            assertTrue(fixture.target.setLastModified(1_000_000L))
            val modified = fixture.target.lastModified()
            assertEquals(fixture.target, fixture.model.install())
            assertEquals(modified, fixture.target.lastModified())
            assertArrayEquals(payload, fixture.target.readBytes())
            assertTrue(fixture.requests.isEmpty() && fixture.opened.isEmpty())
            fixture.noPartials()
        }
    }

    @Test fun shortOversizedAndWrongHashBodiesPreservePreviousTarget() {
        for (reply in listOf(
            Reply(200, payload.copyOf(payload.size - 1), declaredLength = payload.size.toLong()),
            Reply(200, payload + byteArrayOf(1), declaredLength = 0), // Chunked: enforce the stream limit too.
            Reply(200, payload.copyOf().apply { this[0] = 99 }),
        )) Fixture().use { fixture ->
            fixture.target.writeBytes(previous)
            fixture.reply(reply)
            assertThrows(IOException::class.java) { fixture.model.install() }
            assertArrayEquals(previous, fixture.target.readBytes())
            assertEquals(1, fixture.requests.size)
            fixture.noPartials()
        }
    }

    @Test fun existingPartialIsNeverReusedOrDeletedByAnotherAttempt() {
        Fixture().use { fixture ->
            val earlier = File(fixture.directory, "earlier-attempt.part").apply { writeBytes(previous) }
            fixture.reply(Reply(503))
            assertThrows(IOException::class.java) { fixture.model.install() }
            assertArrayEquals(previous, earlier.readBytes())
            assertFalse(fixture.target.exists())
            assertEquals(listOf(earlier), fixture.directory.listFiles()!!.toList())
        }
    }

    @Test fun manuallyFollowsOnlyBoundedHttpsRedirects() {
        Fixture().use { fixture ->
            fixture.reply(Reply(302, location = "/redirect-one"))
            fixture.reply(Reply(307, location = "https://cdn.example.test/weights"))
            fixture.reply(Reply(200, payload))
            assertArrayEquals(payload, fixture.model.install().readBytes())
            assertEquals(listOf("/fixture/model.bin", "/redirect-one", "/weights"), fixture.requests.map { it.path })
            assertTrue(fixture.opened.all { it.protocol == "https" && it.userInfo == null })
        }
    }

    @Test fun rejectsDowngradesRedirectLoopsAndNon200ResponsesWithoutRetry() {
        val replies = listOf(
            listOf(Reply(302, location = "http://example.test/insecure")),
            List(6) { Reply(302, location = "/loop") },
            listOf(Reply(206, payload)), listOf(Reply(403)), listOf(Reply(503)),
        )
        for (responses in replies) Fixture().use { fixture ->
            fixture.target.writeBytes(previous)
            responses.forEach(fixture::reply)
            assertThrows(IOException::class.java) { fixture.model.install() }
            assertArrayEquals(previous, fixture.target.readBytes())
            assertEquals(responses.size, fixture.requests.size)
            assertTrue(fixture.opened.all { it.protocol == "https" })
            fixture.noPartials()
        }
    }

    @Test fun diskGuardFailsBeforeOpeningConnectionAndPreservesTarget() {
        Fixture(free = payload.size.toLong()).use { fixture ->
            fixture.target.writeBytes(previous)
            assertThrows(IOException::class.java) { fixture.model.install() }
            assertTrue(fixture.opened.isEmpty())
            assertArrayEquals(previous, fixture.target.readBytes())
            fixture.noPartials()
        }
    }

    @Test fun cancelInterruptsStalledReadAndAllowsOnlyAnExplicitFreshAttempt() {
        Fixture().use { fixture ->
            fixture.target.writeBytes(previous)
            val release = CountDownLatch(1)
            val headersSent = CountDownLatch(1)
            fixture.reply(Reply(200, payload, stall = release, headersSent = headersSent))
            val failure = AtomicReference<Throwable>()
            val worker = thread(isDaemon = true) {
                try { fixture.model.install() } catch (error: Throwable) { failure.set(error) }
            }
            try {
                assertTrue("Download did not reach its read", headersSent.await(3, TimeUnit.SECONDS))
                fixture.model.cancel()
                worker.join(3_000)
                assertFalse("Canceled read exceeded its bound", worker.isAlive)
                assertTrue("Expected CancellationException, actual failure: ${failure.get()?.stackTraceToString()}", failure.get() is CancellationException)
                assertArrayEquals(previous, fixture.target.readBytes())
                assertEquals(1, fixture.requests.size)
                fixture.noPartials()
                release.countDown()
                fixture.reply(Reply(200, payload))
                assertArrayEquals(payload, fixture.model.install().readBytes())
                assertEquals(2, fixture.requests.size)
            } finally { release.countDown(); worker.join(3_000) }
        }
    }

    @Test fun corruptedDiskReadbackCannotBePublishedAsVerifiedModel() {
        Fixture().use { fixture ->
            fixture.target.writeBytes(previous)
            fixture.reply(Reply(200, payload))
            assertThrows(IOException::class.java) {
                fixture.model.install { bytes, _ ->
                    if (bytes == payload.size.toLong()) {
                        val partial = fixture.directory.listFiles()!!.single { it.name.endsWith(".part") }
                        partial.writeBytes(payload.copyOf().apply { this[0] = 99 })
                    }
                }
            }
            assertArrayEquals(previous, fixture.target.readBytes())
            fixture.noPartials()
        }
    }

    @Test fun modelPathDirectoryIsNeverDeletedOrReplaced() {
        Fixture().use { fixture ->
            assertTrue(fixture.target.mkdir())
            val marker = File(fixture.target, "preserve.txt").apply { writeText("preserve") }
            assertThrows(IOException::class.java) { fixture.model.install() }
            assertEquals("preserve", marker.readText())
            assertTrue(fixture.opened.isEmpty())
            fixture.noPartials()
            assertTrue(marker.delete()); assertTrue(fixture.target.delete())
        }
    }

    @Test fun verifiedFileRejectsWrongLengthHashAndIncompleteStagingFiles() {
        Fixture().use { fixture ->
            assertNull(fixture.model.verifiedFile())
            File(fixture.directory, "unrelated.part").writeBytes(payload)
            fixture.target.writeBytes(payload.copyOf(payload.size - 1))
            assertNull(fixture.model.verifiedFile())
            fixture.target.writeBytes(payload.copyOf().apply { this[0] = 99 })
            assertNull(fixture.model.verifiedFile())
            fixture.target.writeBytes(payload)
            assertEquals(fixture.target, fixture.model.verifiedFile())
            assertArrayEquals(payload, File(fixture.directory, "unrelated.part").readBytes())
        }
    }

    private data class Request(val method: String, val path: String, val headers: Map<String, String>)
    private data class Reply(
        val status: Int,
        val body: ByteArray = byteArrayOf(),
        val location: String? = null,
        val declaredLength: Long = body.size.toLong(),
        val stall: CountDownLatch? = null,
        val headersSent: CountDownLatch = CountDownLatch(1),
    )

    private class Fixture(free: Long = Long.MAX_VALUE) : AutoCloseable {
        val directory = Files.createTempDirectory("lip-model-fixture-").toFile()
        val target = File(directory, ModelFile.NAME)
        val requests = CopyOnWriteArrayList<Request>()
        val opened = CopyOnWriteArrayList<URL>()
        private val responses = ConcurrentLinkedQueue<Reply>()
        private val releases = CopyOnWriteArrayList<CountDownLatch>()
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        @Volatile private var failure: Exception? = null
        private val worker = thread(isDaemon = true, name = "lip-model-http-fixture") {
            try { while (!server.isClosed) server.accept().use(::respond) }
            catch (error: Exception) { if (!server.isClosed) failure = error }
        }

        private fun respond(socket: Socket) {
            socket.soTimeout = 5_000
            val input = socket.getInputStream().buffered()
            val first = line(input).split(' ')
            require(first.size == 3)
            val headers = mutableMapOf<String, String>()
            var headerChars = 0
            while (true) {
                val header = line(input)
                headerChars += header.length + 2
                require(headerChars <= 16_384)
                if (header.isEmpty()) break
                val pair = header.split(':', limit = 2)
                require(pair.size == 2)
                headers[pair[0].lowercase()] = pair[1].trim()
            }
            requests.add(Request(first[0], first[1].substringBefore('?'), headers))
            val reply = responses.poll() ?: Reply(503)
            val chunked = reply.declaredLength == 0L
            val framing = if (chunked) "Transfer-Encoding: chunked" else "Content-Length: ${reply.declaredLength}"
            val responseHeaders = "HTTP/1.1 ${reply.status} Fixture\r\nContent-Type: application/octet-stream\r\n" +
                "$framing\r\nConnection: close\r\n" +
                (reply.location?.let { "Location: $it\r\n" } ?: "") + "\r\n"
            try {
                val output = socket.getOutputStream()
                output.write(responseHeaders.toByteArray(Charsets.US_ASCII)); output.flush()
                reply.headersSent.countDown()
                check(reply.stall?.await(5, TimeUnit.SECONDS) != false)
                if (chunked && reply.body.isNotEmpty()) {
                    output.write("${reply.body.size.toString(16)}\r\n".toByteArray(Charsets.US_ASCII))
                    output.write(reply.body); output.write("\r\n".toByteArray(Charsets.US_ASCII))
                } else if (!chunked) output.write(reply.body)
                if (chunked) output.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
                output.flush()
            } catch (_: IOException) { /* Rejected/canceled clients may stop reading the body. */ }
        }

        private fun line(input: InputStream): String {
            val value = StringBuilder()
            repeat(8_192) {
                val next = input.read()
                require(next != -1)
                if (next == '\n'.code) return value.toString().removeSuffix("\r")
                value.append(next.toChar())
            }
            error("Fixture request line exceeded its safe limit")
        }
        val model = ModelFile(directory, URL("https://huggingface.co/fixture/model.bin"),
            payload.size.toLong(), sha256(payload), { official ->
                opened.add(official)
                URL("http://127.0.0.1:${server.localPort}${official.path}").openConnection() as HttpURLConnection
            }, { free })

        fun reply(reply: Reply) { responses.add(reply); reply.stall?.let(releases::add) }
        fun noPartials() { assertTrue(directory.listFiles()!!.none { it.name.endsWith(".part") }) }
        override fun close() {
            releases.forEach(CountDownLatch::countDown)
            server.close(); worker.join(5_000)
            check(!worker.isAlive) { "Fixture server did not stop" }
            failure?.let { throw AssertionError("Fixture server failed", it) }
            directory.listFiles().orEmpty().forEach { file ->
                if (file.isDirectory) file.listFiles().orEmpty().forEach { assertTrue(it.delete()) }
                assertTrue(file.delete())
            }
            assertTrue(directory.delete())
        }
    }

    companion object {
        private val payload = ByteArray(512) { it.toByte() }
        private val previous = "previous file must survive".toByteArray()
        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
