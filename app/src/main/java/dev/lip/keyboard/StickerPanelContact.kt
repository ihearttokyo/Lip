package dev.lip.keyboard

/** Main-thread contact authority only. Callback payloads remain RAM-only and are
 * never logged. Click consumes an existing DOWN/key/explicit-a11y activation; it
 * never manufactures one. A canceled contact epoch cannot revive after ABA.
 */
internal class StickerPanelContact {
    class Ticket internal constructor(internal val epoch:Any,internal val invoke:()->Unit){override fun toString()="StickerContact(redacted)"}
    private var epoch=Any()
    private var pending:Ticket?=null
    fun snapshot()=epoch
    fun isCurrent(snapshot:Any)=snapshot===epoch
    fun bind(snapshot:Any,invoke:()->Unit):Ticket? {
        if(!isCurrent(snapshot))return null
        return Ticket(snapshot,invoke).also{pending=it}
    }
    fun claim(ticket:Ticket):(() -> Unit)? {
        if(pending!==ticket)return null
        pending=null
        return if(isCurrent(ticket.epoch))ticket.invoke else null
    }
    fun cancel(){epoch=Any();pending=null}
}
/** Unsplittable parent touch gate: child B cannot receive a fresh DOWN while A
 * remains held. Cancellation persists until every pointer lifts.
 */
internal class StickerPanelTouchGate {
    data class Decision(val consume:Boolean,val cancel:Boolean=false)
    var blocked=false;private set
    fun event(pointers:Int,lastUp:Boolean,canceled:Boolean):Decision {
        if(blocked){if(lastUp||canceled)blocked=false;return Decision(true)}
        if(canceled)return Decision(false,true)
        if(pointers!=1){blocked=true;return Decision(true,true)}
        return Decision(false)
    }
}
