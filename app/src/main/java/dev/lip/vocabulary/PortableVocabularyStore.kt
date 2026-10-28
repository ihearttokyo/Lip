package dev.lip.vocabulary

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.util.AtomicFile
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.util.Collections
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Dedicated encrypted 16 MiB vocabulary, never credentials/history or automatic learning.
 * Blocking methods belong on a worker. The process lock covers reread+merge+publish.
 */
class PortableVocabularyStore(context: Context, private val codec: PortableVocabulary = PortableVocabulary()) {
    data class Preview(val existing: Int, val incoming: Int, val merged: Int, val added: Int,
        val updated: Int, val activeEnglish: Int)
    class Snapshot internal constructor(val entries: List<PortableVocabulary.Entry>, val revision: String) {
        override fun toString() = "PortableVocabularySnapshot(redacted)"
    }
    class MergeResult internal constructor(val preview: Preview, val changed: Boolean, val revision: String)
    private class Stored(val bytes: ByteArray, val revision: String)
    private val directory = File(context.applicationContext.noBackupFilesDir, "portable-vocabulary-v1")
    private val file = AtomicFile(File(directory, "vocabulary.enc"))

    fun snapshot(): Snapshot = synchronized(lock) {
        val stored = readLocked()
        Snapshot(Collections.unmodifiableList(ArrayList(codec.decode(stored.bytes))), stored.revision)
    }
    fun preview(incoming: List<PortableVocabulary.Entry>): Preview = synchronized(lock) {
        val existing = codec.decode(readLocked().bytes)
        describe(existing, codec.merge(emptyList(), incoming), codec.merge(existing, incoming))
    }
    /** Admission is one final CPU-only claim. Cancel before claim preserves prior data;
     * an already-admitted atomic commit may complete after the screen closes.
     */
    fun merge(incoming: List<PortableVocabulary.Entry>, admit: () -> Unit): MergeResult = synchronized(lock) {
        val before = readLocked() // Never replace an unreadable/corrupt encrypted store.
        val existing = codec.decode(before.bytes)
        val approved = codec.merge(emptyList(), incoming)
        val merged = codec.merge(existing, approved)
        val bytes = codec.encode(merged)
        val preview = describe(existing, approved, merged)
        if (bytes.contentEquals(before.bytes)) {
            admit(); return@synchronized MergeResult(preview, false, before.revision)
        }
        val revision = UUID.randomUUID().toString()
        writeLocked(bytes, revision, admit)
        MergeResult(preview, true, revision)
    }
    fun export(): ByteArray = synchronized(lock) { codec.encode(codec.decode(readLocked().bytes)) }

    private fun describe(old: List<PortableVocabulary.Entry>, incoming: List<PortableVocabulary.Entry>, merged: List<PortableVocabulary.Entry>): Preview {
        // Merge preserves the approved old spelling/source, so exact display keys
        // suffice for update counts; identity/normalization belongs ONLY to codec.
        val existing = old.associateBy { it.language to it.term }
        val updates = merged.count { row -> existing[row.language to row.term]?.let { it != row } == true }
        return Preview(old.size, incoming.size, merged.size, merged.size - old.size, updates,
            merged.count { activeEnglish(it) })
    }
    private fun pathsSafe() {
        check(!Files.isSymbolicLink(directory.toPath()))
        if (directory.exists()) check(directory.isDirectory && directory.list()?.all {
            it in setOf("vocabulary.enc", "vocabulary.enc.bak", "vocabulary.enc.new")
        } == true)
        check(listOf("", ".bak", ".new").all { suffix ->
            val path = File(file.baseFile.path + suffix)
            !Files.isSymbolicLink(path.toPath()) && (!path.exists() || path.isFile)
        })
    }
    private fun readLocked(): Stored {
        try {
            pathsSafe()
            if (listOf("", ".bak", ".new").none { File(file.baseFile.path + it).exists() })
                return Stored(codec.encode(emptyList()), "empty")
            val bytes = file.openRead().use { input ->
                check(input.channel.size() in OVERHEAD.toLong()..MAX_STORED.toLong())
                input.readNBytes(MAX_STORED + 1).also { check(it.size <= MAX_STORED) }
            }
            check(bytes.size >= OVERHEAD && bytes[0] == 1.toByte())
            val revision = String(bytes, 1, 36, Charsets.US_ASCII)
            check(UUID.fromString(revision).toString() == revision)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(37, 49)))
            cipher.updateAAD(AAD); cipher.updateAAD(bytes.copyOfRange(0, 37))
            val plain = cipher.doFinal(bytes, 49, bytes.size - 49)
            check(plain.size <= PortableVocabulary.MAX_BYTES)
            return Stored(plain, revision)
        } catch (_: Exception) { throw IllegalStateException("Portable vocabulary could not be read. Existing encrypted data was preserved.") }
    }
    private fun writeLocked(bytes: ByteArray, revision: String, admit: () -> Unit) {
        try {
            pathsSafe(); check(bytes.size <= PortableVocabulary.MAX_BYTES)
            check(directory.isDirectory || directory.mkdirs()); Os.chmod(directory.path, 0x1c0) //0700
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(true)); check(cipher.iv.size == 12)
            val header = byteArrayOf(1) + revision.toByteArray(Charsets.US_ASCII)
            cipher.updateAAD(AAD); cipher.updateAAD(header)
            val encrypted = header + cipher.iv + cipher.doFinal(bytes)
            val output = file.startWrite()
            try {
                Os.fchmod(output.fd, 0x180) //0600
                output.write(encrypted); output.flush(); output.fd.sync()
                // Ciphertext and schema are already validated before admission.
                admit()
                file.finishWrite(output)
            } catch (failure: Exception) { file.failWrite(output); throw failure }
            val verified = readLocked()
            check(verified.revision == revision && verified.bytes.contentEquals(bytes))
        } catch (_: Exception) { throw IllegalStateException("Vocabulary merge was not confirmed. Existing data was not deliberately deleted; check Vocabulary before retrying.") }
    }
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY, null) as? SecretKey)?.let { return it }
        check(create && directory.list()?.isEmpty() == true) // Never replace a lost key over existing ciphertext/stages.
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    companion object {
        private val lock = Any()
        private const val KEY = "dev.lip.portable-vocabulary.v1"
        private const val OVERHEAD = 1 + 36 + 12 + 16
        private const val MAX_STORED = PortableVocabulary.MAX_BYTES + OVERHEAD
        private val AAD = "dev.lip.portable-vocabulary.v1".toByteArray(Charsets.US_ASCII)
        private val word = Regex("[A-Za-z]+(?:'[A-Za-z]+)*")
        fun activeEnglish(entry: PortableVocabulary.Entry): Boolean = entry.language in setOf("en", "und") && entry.term.length in 1..48 && word.matches(entry.term)
    }
}

/** Screen-lifetime authorization; no file URI, term, history or saved credential. */
class VocabularyMergeLease {
    private var live = true
    @Synchronized fun cancel() { live = false }
    @Synchronized fun claim() { check(live) { "Vocabulary operation canceled or already admitted." }; live = false }
}
