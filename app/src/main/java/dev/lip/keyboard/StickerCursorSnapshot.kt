package dev.lip.keyboard

/** Fresh single-read caret coordinates; cached selection alone cannot distinguish repetitive text. */
internal object StickerCursorSnapshot {
    fun capture(packageName:String,fieldId:Int,text:CharSequence,start:Int,end:Int,offset:Int,
        reportedStart:Int,reportedEnd:Int):ImeCursor? {
        // Never copy more than the requested bounded context, or repair an unknown offset.
        if(offset<0||text.length>64||start !in 0..32||end!=start||end>text.length||
            text.length-end>32||offset>Int.MAX_VALUE-start)return null
        val at=offset+start
        if(reportedStart!=at||reportedEnd!=at)return null
        return ImeCursor(packageName,fieldId,at,at,text.subSequence(0,start).toString(),
            text.subSequence(end,text.length).toString(),false).takeIf{it.eligible}
    }
}
