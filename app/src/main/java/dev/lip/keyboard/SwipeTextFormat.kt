package dev.lip.keyboard

/** Explicit typing spacing, not speech cleanup. Never rewrites existing surrounding text. */
object SwipeTextFormat {
    fun insertion(word:String,cursor:ImeCursor,capital:Boolean,caps:Boolean,spacing:Boolean):String {
        require(word.length in 1..48 && word.all{it.isLetter()||it=='\''})
        val display=when{caps->word.uppercase(java.util.Locale.ROOT);capital->word.replaceFirstChar{it.uppercaseChar()};else->word}
        val prefix=if(spacing&&cursor.before.lastOrNull()?.isLetterOrDigit()==true)" " else ""
        val suffix=if(spacing&&(cursor.after.isEmpty()||cursor.after.first().isLetterOrDigit()))" " else ""
        return prefix+display+suffix
    }
}
