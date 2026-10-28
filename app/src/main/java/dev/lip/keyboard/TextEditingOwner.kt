package dev.lip.keyboard

/** Metadata only; never contains editor text or clipboard contents. */
class TextEditingContext(val connection:Any?,val editor:Pair<Long,Long>?,val panel:Any,
    val fieldId:Int,val packageName:String?,val state:TextEditingState) {
    fun same(other:TextEditingContext)=connection===other.connection&&editor==other.editor&&panel===other.panel&&
        fieldId==other.fieldId&&packageName==other.packageName&&state==other.state
    override fun toString()="TextEditingContext(metadata-only)"
}
/** One explicit contact/activation -> at most one command. No UI, timers or IPC
 * under this monitor; matching coordinates never repair an editor-revision ABA.
 */
class TextEditingOwner {
    class Ticket internal constructor(internal val action:TextEditingAction,internal val selecting:Boolean,
        internal val context:TextEditingContext,internal val began:Long,internal val epoch:Long) {
        override fun toString()="TextEditingTicket(metadata-only)"
    }
    class Claim internal constructor(val action:TextEditingAction,val selecting:Boolean,val connection:Any,internal val epoch:Long,internal val panel:Any) {
        override fun toString()="TextEditingClaim(metadata-only)"
    }
    private var pending:Ticket?=null
    private var claimed:Claim?=null
    private var epoch=0L
    @Synchronized fun begin(action:TextEditingAction,selecting:Boolean,context:TextEditingContext,now:Long):Ticket? {
        pending=null;claimed=null;epoch++
        if(context.connection==null||context.editor==null||context.packageName.isNullOrBlank()||now<0||!TextEditingActions.available(action,context.state))return null
        return Ticket(action,selecting,context,now,epoch).also{pending=it}
    }
    @Synchronized fun claim(ticket:Ticket,current:TextEditingContext,now:Long):Claim? {
        if(pending!==ticket)return null
        pending=null // Consume before caller can perform any host IPC, including a false/throw.
        if(ticket.epoch!=epoch||now<ticket.began||now-ticket.began>30_000||!ticket.context.same(current)||
            !TextEditingActions.available(ticket.action,current.state))return null
        return Claim(ticket.action,ticket.selecting,checkNotNull(current.connection),epoch,current.panel).also{claimed=it}
    }
    /** Final CPU-only one-use admission AFTER all potentially reentrant cleanup. */
    @Synchronized fun admit(claim:Claim,allowed:Boolean):Boolean {
        if(claimed!==claim)return false
        claimed=null
        return allowed&&claim.epoch==epoch
    }
    /** A detached panel cannot revive a claimed intent by reattaching. Exact
     * incarnation matching also keeps an old panel from canceling a newer one.
     */
    @Synchronized fun closePanel(panel:Any){
        if(pending?.context?.panel===panel||claimed?.panel===panel){pending=null;claimed=null;epoch++}
    }
    @Synchronized fun cancel(ticket:Ticket?=null){
        if(ticket==null||pending===ticket){pending=null;claimed=null;epoch++}
    }
}

/** Gate the UNSPLIT parent stream before LinearLayout can give each child an
 * apparently independent single-pointer DOWN. Block until every pointer lifts.
 */
class TextEditingTouchGate {
    enum class Action { DOWN, MOVE, POINTER_DOWN, POINTER_UP, UP, CANCEL, OTHER }
    data class Decision(val consume:Boolean,val cancelAll:Boolean=false)
    var blocked=false;private set
    fun event(action:Action,pointers:Int):Decision {
        if(blocked){
            if(action==Action.CANCEL||action==Action.UP&&pointers<=1)blocked=false
            return Decision(true)
        }
        if(action==Action.CANCEL)return Decision(false,true)
        if(pointers>1||pointers<1||action==Action.POINTER_DOWN){blocked=true;return Decision(true,true)}
        return Decision(false)
    }
}

/** Main-thread panel lifetime/contact generation. Ending a blocked stream never
 * resurrects a snapshot captured before its cancellation.
 */
class TextEditingInteractionEpoch {
    private var current=Any()
    fun invalidate(){current=Any()}
    fun snapshot(eligible:Boolean):Any?=if(eligible)current else null
    fun owns(snapshot:Any,eligible:Boolean)=eligible&&snapshot===current
}
