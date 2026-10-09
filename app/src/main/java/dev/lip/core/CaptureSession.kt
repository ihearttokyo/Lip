package dev.lip.core

/** Provider utterances are pieces of one explicit user-controlled session. */
class CaptureSession(private val language: String) {
    private val segments = mutableListOf<String>()
    private var hypothesis = ""
    var finishing = false; private set
    var ended = false; private set
    var revision = 0L; private set
    val current: String get() = hypothesis
    val finalized: String get() = join(segments)
    val transcript: String get() = join(segments + hypothesis)
    fun join(values: List<String>): String = buildString {
        fun asciiWord(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '$'
        for (value in values.map { it.trim(' ', '\t', '\r') }.filter(String::isNotEmpty)) {
            if (isNotEmpty() && last() != '\n' && value.first() != '\n' && ((!language.startsWith("ja") && !language.startsWith("zh")) || asciiWord(last()) && asciiWord(value.first()))) append(' ')
            append(value)
        }
    }
    fun partial(value: String) { if (!ended) hypothesis = value }
    fun segment(value: String) {
        if (ended) return
        if (value.isNotEmpty()) { segments.add(value); revision++ }
        hypothesis = ""
    }
    fun stop() { finishing = true }
    fun complete(): String { ended = true; return transcript }
    fun cancel() { ended = true; hypothesis = ""; segments.clear() }
}
