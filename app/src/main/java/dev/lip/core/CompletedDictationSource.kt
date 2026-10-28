package dev.lip.core

/**
 * Process-memory provenance for a future, explicit agent action. This is not a
 * transcript history or a transport. Only the successful producer may publish;
 * recovery text and the state of a text field are deliberately not inputs.
 */
class CompletedDictationSource {
    class Attempt internal constructor(internal val language: String, internal val modelId: String) {
        override fun toString() = "CompletedDictationSource.Attempt(redacted)"
    }

    /** Opaque identity, not a data class: equal text never renews an old lease. */
    class Record internal constructor(val text: String, val language: String, val modelId: String) {
        override fun toString() = "CompletedDictationSource.Record(redacted)"
    }

    private var attempt: Attempt? = null
    private var eligible = false
    private var published = false
    private var record: Record? = null

    /** Even a refused start invalidates the previous result and every old lease. */
    @Synchronized fun begin(allowed: Boolean, language: String, modelId: String): Attempt {
        revoke()
        return Attempt(language, modelId).also {
            attempt = it
            eligible = allowed && language.isNotBlank() && modelId.isNotBlank()
        }
    }

    /** Called once, only after successful final recognition and local formatting. */
    @Synchronized fun complete(owner: Attempt, text: String): Record? {
        if (attempt !== owner || !eligible || published) return null
        published = true
        if (text.isBlank()) {
            revoke()
            return null
        }
        return Record(text, owner.language, owner.modelId).also { record = it }
    }

    /** An explicit Raw/Cleaned choice invalidates an already-open chooser lease. */
    @Synchronized fun revise(owner: Attempt, text: String): Record? {
        if (attempt !== owner || !eligible || !published || record == null || text.isBlank()) return null
        return Record(text, owner.language, owner.modelId).also { record = it }
    }

    @Synchronized fun current(): Record? = record

    /** A future chooser must recheck identity, not text equality, before dispatch. */
    @Synchronized fun isCurrent(candidate: Record): Boolean = record === candidate

    /** No observer/callback or I/O under this monitor; late producers stay revoked. */
    @Synchronized fun revoke() {
        attempt = null
        eligible = false
        published = false
        record = null
    }
}
