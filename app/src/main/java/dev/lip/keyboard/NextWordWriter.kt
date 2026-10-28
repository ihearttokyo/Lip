package dev.lip.keyboard
import android.view.inputmethod.InputConnection

/** Stale prediction callbacks must not even finish another caret's composition. */
object NextWordWriter {
    fun write(owner:NextWordOwner,request:NextWordRequest,index:Int,cursor:ImeCursor?,connection:InputConnection,
        stillCurrent:()->Boolean):String? {
        val word=owner.claim(request,index,cursor,connection)?:return null
        if(!connection.finishComposingText()||!stillCurrent())return null
        val inserted=SwipeTextFormat.insertion(word,checkNotNull(cursor),false,false,true)
        return if(connection.commitText(inserted,1))inserted else null // Ambiguous refusal: never retry/rollback.
    }
}
