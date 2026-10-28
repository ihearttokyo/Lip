package dev.lip.keyboard

/** Actual bounded host read, coordinates normalized to absolute UTF16 offsets. */
data class WordDeleteWindow(val offset:Int,val text:String,val start:Int,val end:Int) {
    val valid get()=offset>=0&&text.length<=192&&offset<=Int.MAX_VALUE-text.length&&start>=offset&&end>=start&&end<=offset+text.length
}

/** One contact, one original bounded text window, no optimistic selection acknowledgment. Main-thread only. */
class WordDeleteTransaction(
    private val origin:WordDeleteWindow,
    private val starts:List<Int>,
    private val port:Port,
    private val clock:()->Long,
    private val contactStarted:Long=clock(),
) {
    interface Port {
        fun valid():Boolean
        fun read():WordDeleteWindow?
        fun select(start:Int,end:Int):Boolean
        /** IPC acceptance only, not proof the host applied the deletion. */
        fun deleteSelection():Boolean
    }
    enum class State { ACTIVE, WAITING, DISPATCHED, REFUSED, CANCELED }
    var state=State.ACTIVE;private set
    var words=0;private set
    private var acknowledged=origin.start
    private var requested:Int?=null
    private var deadline=0L
    val running get()=state==State.ACTIVE||state==State.WAITING
    private val began=contactStarted
    private var lastTime=began
    private var readDeadline=began
    private var displacement=0L
    private var released=false
    private var mutationInFlight=false
    init {
        require(origin.valid&&origin.start==origin.end&&origin.start-origin.offset<=64&&origin.offset+origin.text.length-origin.end<=64)
        require(starts.size<=16&&starts.all{it>=origin.offset&&it<origin.start}&&starts.zipWithNext().all{(a,b)->a>b})
    }
    private fun matches(now:WordDeleteWindow?,at:Int):Boolean {
        if(now?.valid!=true||now.start!=at||now.end!=origin.end||now.offset>origin.offset||now.offset+now.text.length<origin.offset+origin.text.length)return false
        val from=origin.offset-now.offset
        return now.text.substring(from,from+origin.text.length)==origin.text
    }
    private fun alive():Boolean {
        if(!running)return false
        val valid=try{port.valid()}catch(_:RuntimeException){false}
        val now=clock() // Re-sample AFTER external code; eventTime is not execution time.
        if(!running||!valid||now<lastTime||now-began>30_000||requested!=null&&now>=deadline){cancel();return false}
        lastTime=now
        return true
    }
    private fun freshRead():WordDeleteWindow? {
        if(!alive())return null
        readDeadline=clock()+250
        val read=try{port.read()}catch(_:RuntimeException){cancel();return null}
        if(!alive()||clock()>=readDeadline){cancel();return null}
        return read
    }
    private fun freshMatches(at:Int):Boolean {
        val actual=freshRead()
        if(!matches(actual,at)||!alive()||clock()>=readDeadline){cancel();return false}
        return true
    }
    fun move(delta:Int){
        if(!alive()||released)return
        if(delta !in -16..16){cancel();return}
        displacement+=delta.toLong() // Keep signed absolute contact displacement, including overshoot.
        words=(-displacement).coerceIn(0,starts.size.toLong()).toInt()
        advance()
    }
    fun release(){if(alive()){released=true;advance()}}
    fun cancel(){if(running)state=State.CANCELED}
    /** Selection callback is a revocation signal, NOT a content ACK or connection identity. */
    fun selectionChanged(start:Int,end:Int){
        if(running&&(end!=origin.end||start!=(requested?:acknowledged)))cancel()
    }
    fun advance(){
        if(!alive()||mutationInFlight)return
        val expected=requested
        if(expected!=null){
            val actual=freshRead()
            if(!running)return
            if(matches(actual,expected)){
                acknowledged=expected;requested=null;state=State.ACTIVE
            }else{
                // Only the exact last acknowledged state may still be in flight. Anything else revokes.
                if(!matches(actual,acknowledged))cancel()
                return
            }
        }
        val target=if(words==0)origin.start else starts[words-1]
        if(target!=acknowledged){
            if(!freshMatches(acknowledged))return
            requested=target;deadline=clock()+250;state=State.WAITING // Publish BEFORE IPC/reentrant callbacks.
            if(!alive()||clock()>=readDeadline){cancel();return}
            mutationInFlight=true
            val accepted=try{port.select(target,origin.end)}catch(_:RuntimeException){false}finally{mutationInFlight=false}
            if(!accepted&&running)state=State.REFUSED
            else alive()
            return // Never assume Boolean means the host applied it.
        }
        if(released){
            if(words==0){state=State.CANCELED;return}
            if(!freshMatches(target))return
            state=State.DISPATCHED // Consume BEFORE IPC; even an ambiguous failure cannot retry.
            val accepted=try{port.deleteSelection()}catch(_:RuntimeException){false}
            if(!accepted)state=State.REFUSED
        }
    }
}
