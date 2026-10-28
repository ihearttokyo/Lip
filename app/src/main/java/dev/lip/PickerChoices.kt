package dev.lip

/** Rendering a settings picker must not persist defaults or coerce a legacy value. */
class PickerChoices(values: List<String>, initial: String) {
    init { require(values.isNotEmpty() && values.distinct().size == values.size) }
    val unavailable = initial !in values
    val options: List<String?> = if(unavailable) listOf(null)+values else values
    val initialIndex = options.indexOf(initial).coerceAtLeast(0)
    private var last = initial
    fun selected(position: Int): String? {
        val candidate=options.getOrNull(position) ?: return null
        if(candidate==last)return null
        last=candidate;return candidate
    }
}
