package dev.lip.keyboard

import android.icu.text.BreakIterator
import java.util.Locale

/** Platform ICU word segmentation, constrained to platform extended grapheme boundaries. */
object WordDeleteBoundaries {
    fun starts(window:WordDeleteWindow):List<Int> {
        if(!window.valid||window.start!=window.end||window.start-window.offset !in 0..64)return emptyList()
        val before=window.text.substring(0,window.start-window.offset)
        if(before.isEmpty())return emptyList()
        val words=BreakIterator.getWordInstance(Locale.ENGLISH).apply{setText(before)}
        val clusters=BreakIterator.getCharacterInstance(Locale.ROOT).apply{setText(window.text)}
        if(!clusters.isBoundary(before.length))return emptyList()
        val starts=mutableListOf<Int>()
        var end=words.last();var start=words.previous()
        while(start!=BreakIterator.DONE&&starts.size<16){
            val token=before.substring(start,end)
            val unit=token.codePoints().anyMatch{Character.isLetterOrDigit(it)||Character.getType(it) in setOf(Character.OTHER_SYMBOL.toInt(),Character.MATH_SYMBOL.toInt(),Character.CURRENCY_SYMBOL.toInt())}
            if(unit&&clusters.isBoundary(start)&&(start>0||window.offset==0))starts.add(window.offset+start)
            end=start;start=words.previous()
        }
        // A punctuation-only document prefix is itself deletable, but never a truncated unknown token.
        if(starts.isEmpty()&&window.offset==0&&before.any{!it.isWhitespace()})starts.add(0)
        return starts
    }
}
