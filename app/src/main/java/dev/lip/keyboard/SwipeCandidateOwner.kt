package dev.lip.keyboard

enum class CandidateOrigin { SWIPE, TYPED, AUTO }

/** A short-lived, in-memory claim over text inserted by this keyboard, never arbitrary editor text. */
class SwipeCandidateState internal constructor(
    val base:ImeCursor,val inserted:String,val alternatives:List<String>,
    internal val connection:Any,internal val epoch:Long,val origin:CandidateOrigin,val automaticAllowed:Boolean,
) {
    val start get()=base.start
    val end get()=start+inserted.length
    internal var expectedSelection=true
    internal var ticket:ImeTicket?=null
    val ready get()=ticket!=null
}
class SwipeCandidateOwner(private val session:ImeSession) {
    var current:SwipeCandidateState?=null
        private set
    fun clear(){current=null}
    fun stage(base:ImeCursor,inserted:String,alternatives:List<String>,connection:Any,origin:CandidateOrigin=CandidateOrigin.SWIPE,expectSelection:Boolean=true,automaticAllowed:Boolean=true):SwipeCandidateState? {
        clear()
        val epoch=session.editorGeneration()?:return null
        require(base.eligible && inserted.length in 1..128 && alternatives.size in 1..3 && inserted in alternatives)
        require(alternatives.all{it.length in 1..128 && if(origin==CandidateOrigin.SWIPE)
            it.trim(' ').length in 1..48 && it.trim(' ').all{c->c.isLetter()||c=='\''}
            else TypingBoundary.valid(it)&&TypingBoundary.suffix(it)==TypingBoundary.suffix(inserted)})
        require(base.start<=Int.MAX_VALUE-alternatives.maxOf{it.length})
        return SwipeCandidateState(base,inserted,alternatives.distinct().toList(),connection,epoch,origin,automaticAllowed).also{it.expectedSelection=expectSelection;current=it}
    }
    /** At most one expected selection acknowledgement belongs to the pending owned insertion. */
    fun ownsSelection(start:Int,end:Int):Boolean {
        val state=current?:return false
        if(state.ready&&state.origin==CandidateOrigin.SWIPE||!state.expectedSelection||start!=state.end||end!=start)return false
        state.expectedSelection=false;return true
    }
    private fun exact(state:SwipeCandidateState,cursor:ImeCursor?,before:String?,connection:Any?):Boolean =
        current===state&&connection===state.connection&&session.editorGeneration()==state.epoch&&
        cursor?.eligible==true&&cursor.packageName==state.base.packageName&&cursor.fieldId==state.base.fieldId&&
        cursor.start==state.end&&cursor.end==state.end&&cursor.before==(state.base.before+state.inserted).takeLast(32)&&
        cursor.after==state.base.after&&before==state.inserted
    fun confirm(state:SwipeCandidateState,cursor:ImeCursor?,before:String?,connection:Any?):Boolean {
        if(!exact(state,cursor,before,connection)){if(current===state)clear();return false}
        state.ticket=session.capture(cursor)?:return false;if(state.origin==CandidateOrigin.SWIPE)state.expectedSelection=false;return true
    }
    /** Consume before any composing IPC. No retry after refusal or an ambiguous write. */
    fun claim(state:SwipeCandidateState,index:Int,cursor:ImeCursor?,before:String?,connection:Any?):String? {
        if(current!==state)return null
        val ticket=state.ticket
        val valid=index in state.alternatives.indices && ticket!=null && exact(state,cursor,before,connection)
        clear()
        return if(valid&&session.claim(ticket!!,cursor))state.alternatives[index] else null
    }
}
