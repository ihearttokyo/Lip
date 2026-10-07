package dev.lip.core

data class EditorSnapshot(
    val generation: Long,
    val packageName: String,
    val windowId: Int,
    val fieldId: String,
    val text: String,
    val start: Int,
    val end: Int,
    val protected: Boolean = false,
)

object EditorGuard {
    fun canInsert(original: EditorSnapshot, current: EditorSnapshot?): Boolean =
        !original.protected && current != null && !current.protected &&
            original == current && original.start in 0..original.text.length &&
            original.end in 0..original.text.length

    fun expectedInsertion(text: String, start: Int, end: Int, insertion: String): String {
        require(start in 0..text.length && end in 0..text.length)
        return text.replaceRange(minOf(start, end), maxOf(start, end), insertion)
    }
}
