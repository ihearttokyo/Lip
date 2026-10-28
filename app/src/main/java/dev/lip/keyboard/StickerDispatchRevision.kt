package dev.lip.keyboard

/** A completed dispatch may revise only its still-exact claimed editor state, never a reentrant replacement. */
internal object StickerDispatchRevision {
    fun finish(session:ImeSession,request:StickerHandoffRequest,cursor:ImeCursor?,connection:Any?,window:Any?):Boolean=synchronized(session){
        if(connection!==request.connection||window!==request.window||!session.claimedContextCurrent(request.ticket,cursor))return false
        session.editorChanged();true
    }
}
