package dev.lip.keyboard

import dev.lip.vocabulary.PortableVocabulary
import java.util.Collections

/** A single lexical union for the existing immutable neural model/beam decoder.
 * No training, geometric fallback, second inference, storage mutation or data access.
 * Personal priors are a fixed prospective policy, not a measured accuracy claim.
 */
class PersonalSwipeLexicon private constructor(
    val entries: List<SwipeLexeme>,
    val eligibleCount: Int,
    val addedCount: Int,
    val unsupportedCount: Int
) {
    private val displays = entries.associate { it.word to it.display }
    val count: Int get() = entries.size
    fun display(key: String): String? = displays[key]

    companion object {
        const val MAX_MANUAL_ENTRIES = 200
        const val MAX_PORTABLE_ENTRIES = 100_000
        private data class Personal(val display: String, val weight: Int)

        /** Inputs must be one admitted snapshot. Caps fail, never truncate a source.
         * Source order is deterministic precedence: manual first, then portable.
         * Every original base key/frequency/flag is preserved; personal spelling may
         * override its display. OOV additions are sorted by their ASCII native key.
         */
        fun build(base: List<SwipeLexeme>, manual: List<String>, portable: List<PortableVocabulary.Entry>): PersonalSwipeLexicon {
            require(base.size <= SwipeAssetCatalog.MAX_ENTRIES) { "Base swipe vocabulary exceeds its source limit" }
            require(manual.size <= MAX_MANUAL_ENTRIES) { "Manual swipe vocabulary exceeds its source limit" }
            require(portable.size <= MAX_PORTABLE_ENTRIES) { "Portable swipe vocabulary exceeds its source limit" }
            val baseWords = base.map { it.word }
            if (base.isNotEmpty()) SwipeInputBounds.lexicon(baseWords, IntArray(base.size) { base[it].frequency })
            // The source asset has already authenticated display spelling. Preserve
            // its admitted offensive flags rather than silently re-filtering here.
            val personal = LinkedHashMap<String, Personal>()
            var unsupported = 0
            fun admit(display: String, weight: Int) {
                val key = nativeKey(display)
                if (key == null) { unsupported++; return }
                val previous = personal[key]
                if (previous == null) personal[key] = Personal(display, weight)
                else if (weight > previous.weight) personal[key] = previous.copy(weight = weight)
            }
            for (display in manual) admit(display, 1)
            for (entry in portable) {
                require(entry.weight in 1..1_000_000) { "Invalid portable swipe vocabulary weight" }
                if (entry.language != "en" && entry.language != "und") unsupported++
                else admit(entry.term, entry.weight)
            }
            val rows = ArrayList<SwipeLexeme>(base.size + personal.size)
            val baseKeys = HashSet<String>(baseWords)
            for (entry in base) {
                val spelling = personal[entry.word]?.display
                rows.add(if (spelling == null) entry else entry.copy(display = spelling))
            }
            var added = 0
            for ((key, value) in personal.entries.sortedBy { it.key }) if (key !in baseKeys) {
                rows.add(SwipeLexeme(key, personalFrequency(value.weight), value.display, false))
                added++
            }
            require(rows.size in 1..SwipeInputBounds.MAX_LEXICON_ENTRIES) { "Personal swipe vocabulary exceeds the native union limit" }
            // This is the exact same native grammar/path/frequency gate used by open.
            SwipeInputBounds.lexicon(rows.map { it.word }, IntArray(rows.size) { rows[it].frequency })
            return PersonalSwipeLexicon(Collections.unmodifiableList(rows), personal.size, added, unsupported)
        }

        /** ASCII letters with optional single internal apostrophes; no transliteration,
         * phrase splitting, Unicode lowercasing or silent alteration of saved terms.
         */
        private fun nativeKey(display: String): String? {
            if (display.length !in 1..48) return null
            val key = StringBuilder(display.length)
            var followsLetter = false
            var previous = '\u0000'
            var steps = 0
            for (raw in display) {
                val letter = if (raw in 'A'..'Z') raw + ('a' - 'A') else raw
                if (letter in 'a'..'z') {
                    followsLetter = true
                    if (letter != previous) { steps++; previous = letter }
                } else {
                    if (letter != '\'' || !followsLetter) return null
                    followsLetter = false
                }
                key.append(letter)
            }
            return if (followsLetter && steps <= 40) key.toString() else null
        }

        /** Frozen:128 + min(127, floor(log2(weight))*8), manual weight1. */
        private fun personalFrequency(weight: Int): Int = 128 + minOf(127, (31 - Integer.numberOfLeadingZeros(weight)) * 8)
    }
}
