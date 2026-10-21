package dev.lip.speech

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.Semaphore

/** Blocking local verification/explicit installation; call on a worker, never from UI rendering. */
class ModelFile internal constructor(
    private val directory: File,
    private val source: URL,
    private val expectedBytes: Long,
    private val expectedSha256: String,
    private val openConnection: (URL) -> HttpURLConnection,
    private val freeBytes: () -> Long,
) {
    constructor(directory: File) : this(directory, URL(SOURCE_URL), BYTES, SHA256,
        { it.openConnection() as HttpURLConnection }, { directory.usableSpace })

    private val target = File(directory, NAME)
    private val state = Any()
    @Volatile private var canceled = false
    private var active = false
    private var connection: HttpURLConnection? = null

    fun verifiedFile(): File? = target.takeIf { valid(it, abort = false) }

    /** One user-requested attempt; no background retry/resume and no whole-model allocation. */
    fun install(progress: (Long, Long) -> Unit = { _, _ -> }): File {
        if (!installPermit.tryAcquire()) throw IOException("Another speech model installation is active")
        synchronized(state) { active = true; canceled = false }
        var partial: File? = null
        try {
            if (valid(target, abort = true)) { checkCanceled(); return target }
            if (target.exists() && !target.isFile) throw IOException("Speech model path is not a file; existing data was preserved")
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Speech model storage is unavailable")
            if (freeBytes() < expectedBytes + 64L * 1024 * 1024) throw IOException("Not enough free space for the speech model and 64 MiB reserve")
            checkCanceled()
            val staging = Files.createTempFile(directory.toPath(), "speech-model-", ".part").toFile()
            partial = staging
            progress(0, expectedBytes)
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            var percent = 0
            try {
                val response = connect()
                response.inputStream.use { input ->
                    FileOutputStream(staging).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            checkCanceled()
                            val count = input.read(buffer)
                            checkCanceled()
                            if (count < 0) break
                            if (count > expectedBytes - received) throw IOException("Speech model exceeded its pinned byte limit")
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            received += count
                            val currentPercent = (received * 100 / expectedBytes).toInt()
                            if (currentPercent > percent) { percent = currentPercent; progress(received, expectedBytes) }
                        }
                        output.flush(); output.fd.sync()
                    }
                }
            } catch (_: Exception) {
                checkCanceled()
                // Never expose signed redirect URLs or server bodies through exception messages/causes.
                throw IOException("Speech model transfer failed. Previous file preserved; try again")
            }
            if (received != expectedBytes || hex(digest.digest()) != expectedSha256)
                throw IOException("Speech model did not match its pinned size/checksum")
            if (!valid(staging, abort = true)) throw IOException("Speech model failed disk readback verification")
            checkCanceled()
            // A good existing file wins even if it appeared while this attempt was downloading.
            if (valid(target, abort = true)) { checkCanceled(); return target }
            synchronized(state) {
                checkCanceled()
                Files.move(staging.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                partial = null // Same-directory atomic rename retains the readback-verified inode/content.
            }
            if (!target.isFile || target.length() != expectedBytes) throw IOException("Speech model publish was not confirmed")
            return target
        } finally {
            val closing = synchronized(state) { active = false; connection.also { connection = null } }
            try { closing?.disconnect() }
            finally {
                try { partial?.let { Files.deleteIfExists(it.toPath()) } }
                finally { installPermit.release() }
            }
        }
    }

    /** Invalidates only the active attempt; disconnect may wait for the bounded I/O timeout. */
    fun cancel() {
        val closing = synchronized(state) {
            if (!active) return
            canceled = true
            connection
        }
        closing?.disconnect()
    }

    private fun connect(): HttpURLConnection {
        var location = source
        for (hop in 0..5) {
            checkCanceled()
            if (location.protocol != "https" || location.userInfo != null || location.ref != null)
                throw IOException("Speech model requires an anonymous HTTPS source")
            val request = openConnection(location).apply {
                instanceFollowRedirects = false
                useCaches = false
                connectTimeout = 2_000
                readTimeout = 1_000
                requestMethod = "GET"
                setRequestProperty("Accept-Encoding", "identity")
            }
            synchronized(state) { checkCanceled(); connection = request }
            val status = request.responseCode
            if (status == 200) {
                val length = request.contentLengthLong
                if (length >= 0 && length != expectedBytes) throw IOException("Speech model response size differs from its pin")
                return request
            }
            if (status !in setOf(301, 302, 303, 307, 308)) throw IOException("Speech model server did not return a complete file")
            val redirect = request.getHeaderField("Location") ?: throw IOException("Speech model redirect is missing its destination")
            if (hop == 5) throw IOException("Speech model exceeded its redirect limit")
            request.disconnect()
            synchronized(state) { if (connection === request) connection = null }
            location = URL(location, redirect)
        }
        throw IOException("Speech model exceeded its redirect limit")
    }

    private fun valid(file: File, abort: Boolean): Boolean {
        if (!file.isFile || file.length() != expectedBytes) return false
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (abort) checkCanceled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            hex(digest.digest()) == expectedSha256
        } catch (_: IOException) { false }
    }

    private fun checkCanceled() {
        if (canceled) throw CancellationException("Speech model installation canceled")
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    companion object {
        // ponytail: one artifact install at a time; split locks only when multiple artifacts exist.
        private val installPermit = Semaphore(1)
        const val NAME = "ggml-large-v3-turbo-q5_0.bin"
        const val BYTES = 574_041_195L
        const val SHA256 = "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2"
        const val SOURCE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-large-v3-turbo-q5_0.bin"
    }
}
