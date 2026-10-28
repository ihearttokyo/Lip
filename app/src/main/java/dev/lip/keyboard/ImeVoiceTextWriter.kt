package dev.lip.keyboard

import android.view.inputmethod.InputConnection

/** Called only after the dictation owner consumes its exact editor/caret ticket.
 * Finish markers, not text: a late canceled typing region must not redirect this insertion.
 * Dispatch booleans are not proof of host rendering; never retry an ambiguous write.
 */
object ImeVoiceTextWriter {
    fun write(connection:InputConnection,text:String):Boolean =
        connection.finishComposingText()&&connection.commitText(text,1)
}
