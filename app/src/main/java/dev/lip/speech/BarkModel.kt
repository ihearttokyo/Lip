package dev.lip.speech

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Explicit English-only, quantized derivative. Not the publisher's BF16 benchmark runtime. */
object BarkModel {
    const val NAME = "underdog-bark-3.0-pro-q4pw.gguf"
    const val BYTES = 364_020_736L
    const val SHA256 = "2370bf360720b322a54bdc4cfe6198713b347e79ebec7853956ade93618d5986"
    const val REVISION = "6b96418d56b68646807cdc71ee4c0a4222af2d0a"
    const val SOURCE_URL = "https://huggingface.co/scholzmx/underdog-bark-3.0-pro-gguf/resolve/$REVISION/$NAME"
    fun file(directory: File, profile: BarkProfile = BarkProfile.PRO) = ModelFile(directory, URL(profile.sourceUrl), BYTES, profile.sha256,
        { it.openConnection() as HttpURLConnection }, { directory.usableSpace }, profile.fileName)
    /** Packaging availability only, not verified model/runtime readiness. */
    fun bundledAvailable(context: Context, profile: BarkProfile): Boolean = try {
        context.assets.openFd("speech/${profile.fileName}").use { it.length == BYTES }
    } catch (_: IOException) { false }

    /** Returns null for an ordinary non-bundled build. Never starts a download. */
    fun installBundled(context: Context, directory: File, profile: BarkProfile,
        isCanceled: () -> Boolean = { false }): File? {
        if (isCanceled()) throw java.util.concurrent.CancellationException("Bundled model loading canceled")
        if (!bundledAvailable(context, profile)) return null
        return file(directory, profile).installBundled(
            { context.assets.open("speech/${profile.fileName}") }, isCanceled, BundledModelPublisher::publish)
    }

    fun engine(path: String) = engine(path, BarkProfile.PRO)
    fun engine(path: String, profile: BarkProfile) = engine(path, profile, BarkLoadingTicket())
    internal fun engine(path: String, profile: BarkProfile, ticket: BarkLoadingTicket): WhisperEngine {
        val calls = CheckedBarkNativeCalls(profile, ticket)
        try { return WhisperEngine(path, calls) }
        catch (failure: Throwable) {
            try { calls.disposeFailedConstruction() } catch (_: Throwable) { /* Native registry retains unknown ownership. */ }
            throw failure
        }
    }
}

/** Reuses the existing bounded capture/lease safeguards, not Whisper's inference implementation. */
enum class BarkProfile(val id: String, val title: String, val revision: String, val sha256: String, val paddingSamples: Int) {
    CLEAN("clean", "Bark 3.0 Clean · standard", "000c6b353d237ff647644ea2c3beb3910a21215b",
        "d1eec8fc4b9aab0fb6a83ddba6a56a0f93b094d350d069f885a843e31eaee0cf", 0),
    PRO("pro", "Bark 3.0 Pro · optional", BarkModel.REVISION, BarkModel.SHA256, 4000);
    val fileName get() = "underdog-bark-3.0-$id-q4pw.gguf"
    val sourceUrl get() = "https://huggingface.co/scholzmx/underdog-bark-3.0-$id-gguf/resolve/$revision/$fileName"
    val directoryName get() = "speech-bark-$id"
    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: CLEAN
    }
}

internal class BarkNativeCalls(private val paddingSamples: Int) : WhisperEngine.NativeCalls {
    init { System.loadLibrary("lip_bark") }
    override fun open(modelPath: ByteArray): Long = openWithPadding(modelPath, paddingSamples)
    private external fun openWithPadding(modelPath: ByteArray, paddingSamples: Int): Long
    override external fun transcribe(handle: Long, pcm: FloatArray, language: ByteArray, prompt: ByteArray): Array<WhisperEngine.Segment>
    override external fun abort(handle: Long, abort: Boolean)
    override external fun close(handle: Long)
}


/** Checked production path. Legacy BarkNativeCalls remains only for explicit host fixtures. */
internal class CheckedBarkNativeCalls(profile: BarkProfile, private val loading: BarkLoadingTicket) : WhisperEngine.NativeCalls {
    private val ticket: Long
    private var opened = false
    private var disposalAttempted = false
    init {
        System.loadLibrary("lip_bark")
        ticket = createTicket(when (profile) { BarkProfile.CLEAN -> 0; BarkProfile.PRO -> 1 })
        check(ticket != 0L) { "Cannot create a checked local speech session" }
        try { loading.bind { cancelTicket(ticket) } }
        catch (failure: Throwable) {
            try { disposeFailedConstruction() } catch (_: Throwable) { }
            throw failure
        }
    }
    override fun open(modelPath: ByteArray): Long {
        check(!opened && !disposalAttempted) { "Loading ticket has already been used" }
        try {
            if (loading.isCanceled()) throw java.util.concurrent.CancellationException("Model loading canceled")
            val ready = loadTicket(ticket, modelPath)
            if (!ready && loading.isCanceled()) throw java.util.concurrent.CancellationException("Model loading canceled")
            check(ready) { "Cannot prepare the checked local speech model" }
            if (!loading.commit()) throw java.util.concurrent.CancellationException("Model loading canceled")
            opened = true
            return ticket
        } catch (failure: Throwable) {
            try { disposeFailedConstruction() } catch (_: Throwable) { }
            throw failure
        }
    }
    internal fun disposeFailedConstruction() {
        loading.finishFailed()
        if (!disposalAttempted) { disposalAttempted = true; closeChecked(ticket) }
    }
    private external fun createTicket(profile: Int): Long
    private external fun loadTicket(handle: Long, modelPath: ByteArray): Boolean
    private external fun cancelTicket(handle: Long)
    override external fun admission(handle: Long): Boolean
    override external fun retainedWork(handle: Long): Boolean
    override external fun closeChecked(handle: Long): Boolean
    private external fun abortChecked(handle: Long, abort: Boolean)
    private external fun transcribeChecked(handle: Long, pcm: FloatArray, language: ByteArray, prompt: ByteArray): Array<WhisperEngine.Segment>
    override fun transcribe(handle: Long, pcm: FloatArray, language: ByteArray, prompt: ByteArray) = transcribeChecked(handle, pcm, language, prompt)
    override fun abort(handle: Long, abort: Boolean) = abortChecked(handle, abort)
    override fun close(handle: Long) { closeChecked(handle) }
}

/** Android app data forbids hardlinks under SELinux. Kernel no-replace rename
 * publishes the readback-verified inode without ever replacing a racing target.
 * Unsupported syscall/filesystem errors fail closed; no renameTo fallback.
 */
internal object BundledModelPublisher {
    fun publish(source: File, destination: File) {
        System.loadLibrary("lip_bark")
        val error = publishNoReplace(source.absolutePath.toByteArray(Charsets.UTF_8),
            destination.absolutePath.toByteArray(Charsets.UTF_8))
        if (error == 17) throw java.nio.file.FileAlreadyExistsException(destination.absolutePath)
        if (error != 0) throw IOException("Atomic bundled model publication failed (errno=$error); existing data preserved")
    }
    private external fun publishNoReplace(source: ByteArray, destination: ByteArray): Int
}
