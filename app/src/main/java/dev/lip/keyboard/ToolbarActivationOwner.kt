package dev.lip.keyboard

/** CPU metadata only; no editor/clipboard text or provider parameters. */
class ToolbarActivationContext(val action:ToolbarAction,val connection:Any?,val editor:Pair<Long,Long>?,val mapping:Any,
    val render:Any,val transition:Any,val start:Int,val end:Int,val inputType:Int,val imeOptions:Int,
    val fieldId:Int,val packageName:String?,val page:KeyboardPage,val composing:Boolean,val busy:Boolean,
    val visible:Boolean,val interactive:Boolean,val unlocked:Boolean) {
    fun same(other:ToolbarActivationContext)=action==other.action&&connection===other.connection&&editor==other.editor&&mapping===other.mapping&&
        render===other.render&&transition===other.transition&&start==other.start&&end==other.end&&inputType==other.inputType&&imeOptions==other.imeOptions&&
        fieldId==other.fieldId&&packageName==other.packageName&&page==other.page&&composing==other.composing&&busy==other.busy&&
        visible==other.visible&&interactive==other.interactive&&unlocked==other.unlocked
    override fun toString()="ToolbarContext(metadata-only)"
}
class ToolbarActivationOwner {
    class Ticket internal constructor(internal val context:ToolbarActivationContext,internal val epoch:Any)
    class Claim internal constructor(internal val context:ToolbarActivationContext,internal val epoch:Any)
    private var epoch=Any();private var pending:Ticket?=null;private var claimed:Claim?=null
    fun begin(context:ToolbarActivationContext):Ticket {cancel();return Ticket(context,epoch).also{pending=it}}
    fun isCurrent(ticket:Ticket,current:ToolbarActivationContext)=pending===ticket&&ticket.epoch===epoch&&ticket.context.same(current)
    fun discard(ticket:Ticket){if(pending===ticket)cancel()}
    fun claim(ticket:Ticket,current:ToolbarActivationContext):Claim? {
        if(pending!==ticket)return null
        pending=null
        if(ticket.epoch!==epoch||!ticket.context.same(current))return null
        return Claim(ticket.context,epoch).also{claimed=it}
    }
    fun admit(claim:Claim,current:ToolbarActivationContext,allowed:Boolean):Boolean {
        if(claimed!==claim)return false
        claimed=null;return allowed&&claim.epoch===epoch&&claim.context.same(current)
    }
    fun cancel(){pending=null;claimed=null;epoch=Any()}
    fun hasContact()=pending!=null||claimed!=null
}

/** Physical stream lifetime outlives a consumed long-action ticket. Only toolbar
 * DOWN starts it; ordinary two-thumb typing outside the toolbar is unchanged.
 */
class ToolbarTouchStream {
    data class Decision(val consume:Boolean,val cancel:Boolean=false)
    private var active=false
    var blocked=false;private set
    fun begin(){active=true}
    fun event(pointers:Int,canceled:Boolean,hasContact:Boolean):Decision {
        if(blocked)return Decision(true)
        if(pointers>1&&(active||hasContact)){blocked=true;return Decision(true,true)}
        return Decision(false,canceled)
    }
    fun end(){active=false;blocked=false}
}
