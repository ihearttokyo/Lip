package dev.lip.speech

/** Recover only known native availability/resource failures; unrelated fatal errors propagate. */
internal inline fun <T> nativeSpeech(block: () -> T): T = try {
    block()
} catch (_: OutOfMemoryError) {
    throw IllegalStateException("Local speech is unavailable or out of memory.")
} catch (_: LinkageError) {
    throw IllegalStateException("Local speech is unavailable or out of memory.")
}
