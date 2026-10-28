package dev.lip.keyboard

/** Original implementation of the geometry in Jared's supplied Gboard references. */
enum class KeyboardPage { LETTERS, SYMBOLS, EXTRA_SYMBOLS, NUMBER_PAD, EMOJI, CLIPBOARD, GIF, STICKERS }
enum class KeyAction { TYPE, SHIFT, DELETE, MODE, EMOJI, SPACE, EDITOR_ACTION, EXTRA_SYMBOLS, NUMBER_PAD, SPACER }
data class KeySpec(val label: String, val action: KeyAction = KeyAction.TYPE, val units: Float = 1f, val hint: String? = null)

object KeyboardLayoutSpec {
    /** Keep portrait reference proportions, but leave an editor viewport in short landscape windows. */
    fun rowHeightPx(width: Int, height: Int, density: Float, landscape: Boolean): Int =
        if(landscape)(height*.09f).toInt().coerceIn((32*density).toInt(),(40*density).toInt())
        else(width*.1125f).toInt().coerceIn((40*density).toInt(),(56*density).toInt())

    fun rowHeights(total: Int,count: Int): List<Int> {
        require(total>0&&count in 1..10&&total>=count)
        return (0 until count).map { total*(it+1)/count-total*it/count }
    }
    private fun chars(text: String) = text.map { KeySpec(it.toString()) }
    private fun action() = KeySpec("", KeyAction.EDITOR_ACTION, 1.5f)
    private fun delete() = KeySpec("", KeyAction.DELETE, 1.5f)
    fun rows(page: KeyboardPage, uppercase: Boolean): List<List<KeySpec>> {
        val numbers = chars("1234567890")
        fun letters(text: String, hints: List<String>) = text.mapIndexed { i, c ->
            KeySpec(if (uppercase) c.uppercase() else c.toString(), hint = hints[i])
        }
        return when (page) {
            KeyboardPage.LETTERS -> listOf(numbers,
                letters("qwertyuiop", listOf("%", "\\", "|", "=", "[", "]", "<", ">", "{", "}")),
                listOf(KeySpec("", KeyAction.SPACER, .5f)) + letters("asdfghjkl", listOf("@", "#", "$", "_", "&", "-", "+", "(", ")")) + KeySpec("", KeyAction.SPACER, .5f),
                listOf(KeySpec("", KeyAction.SHIFT, 1.5f)) + letters("zxcvbnm", listOf("*", "\"", "'", ":", ";", "!", "?")) + delete(),
                listOf(KeySpec("?123", KeyAction.MODE, 1.5f), KeySpec("", KeyAction.EMOJI), KeySpec("English", KeyAction.SPACE, 5f), KeySpec("."), action()))
            KeyboardPage.SYMBOLS -> listOf(numbers, chars("@#\$_&-+()/"),
                listOf(KeySpec("=\\<", KeyAction.EXTRA_SYMBOLS, 1.5f)) + chars("*\"':;!?") + delete(),
                listOf(KeySpec("ABC", KeyAction.MODE, 1.5f), KeySpec(","), KeySpec("12\n34", KeyAction.NUMBER_PAD), KeySpec("English", KeyAction.SPACE, 4f), KeySpec("."), action()))
            KeyboardPage.EXTRA_SYMBOLS -> listOf(chars("~`|•√π÷×¶∆"), chars("£¢€¥^°={}\\"),
                listOf(KeySpec("?123", KeyAction.EXTRA_SYMBOLS, 1.5f)) + chars("©®™✓[]§") + delete(),
                listOf(KeySpec("ABC", KeyAction.MODE, 1.5f), KeySpec(","), KeySpec("12\n34", KeyAction.NUMBER_PAD), KeySpec("English", KeyAction.SPACE, 4f), KeySpec("."), action()))
            else -> emptyList() // Numeric pad/emoji have their own referenced surfaces.
        }
    }
    fun center(row: List<KeySpec>, index: Int): Float =
        (row.take(index).sumOf { it.units.toDouble() }.toFloat() + row[index].units / 2) / row.sumOf { it.units.toDouble() }.toFloat()
}

object KeyboardVisualTokens {
    const val BACKDROP: Int = -0x1000000 // #000000, never cream.
    const val FOREGROUND: Int = -0x1 // #FFFFFF, all key characters and hints.
    val KEY_SURFACE: Int = 0xff2c2c2e.toInt()
    val TOOLBAR: Int = 0xff353537.toInt()
    const val KEY_RADIUS_DP = 6f
    const val KEY_GAP_DP = 2f
}
