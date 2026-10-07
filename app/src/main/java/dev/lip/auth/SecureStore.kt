package dev.lip.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Shared encrypted app storage. Keys and ciphertext are excluded from Android backup. */
class SecureStore(context: Context) {
    private val directory = File(context.applicationContext.noBackupFilesDir, "encrypted")

    fun read(name: String): String? = synchronized(lock) {
        val file = file(name)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@synchronized null
        try {
            val bytes = file.openRead().use {
                if (it.channel.size() > MAX_BYTES) throw AuthException("Encrypted storage exceeds its size limit.")
                it.readBytes()
            }
            if (bytes.size < 29 || bytes[0] != 1.toByte()) throw AuthException("Encrypted storage format is invalid.")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(bytes.copyOfRange(13, bytes.size)), Charsets.UTF_8)
        } catch (_: Exception) { throw AuthException("Encrypted storage could not be read. Existing data has been preserved.") }
    }

    fun write(name: String, value: String) = synchronized(lock) {
        val file = file(name)
        val plain = value.toByteArray(Charsets.UTF_8)
        if (plain.size > MAX_BYTES - 29) throw AuthException("Encrypted storage exceeds its size limit.")
        try {
            if (!directory.isDirectory && !directory.mkdirs()) throw AuthException("Encrypted storage is unavailable.")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
            if (cipher.iv.size != 12) throw AuthException("The encryption provider returned an unsupported nonce.")
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
            val output = file.startWrite()
            try { output.write(bytes); file.finishWrite(output) }
            catch (e: Exception) { file.failWrite(output); throw e }
        } catch (_: Exception) { throw AuthException("Encrypted storage could not be saved. Previous data has been preserved.") }
    }

    fun delete(name: String) = synchronized(lock) {
        val target = file(name)
        try { target.delete(); verifyDeleted(target.baseFile) }
        catch (_: Exception) { throw AuthException("Encrypted data could not be completely removed. Try again.") }
    }

    private fun file(name: String): AtomicFile {
        require(name.matches(Regex("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*")) && name.length <= 80) { "Invalid storage name" }
        return AtomicFile(File(directory, name))
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) throw AuthException("The encryption key is unavailable.")
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    companion object {
        internal fun verifyDeleted(baseFile: File) {
            if (listOf("", ".bak", ".new").any { File(baseFile.path + it).exists() })
                throw AuthException("Encrypted data could not be completely removed. Try again.")
        }
        private val lock = Any()
        private const val KEY_ALIAS = "dev.lip.encrypted.v1"
        private const val MAX_BYTES = 4 * 1024 * 1024
    }
}
