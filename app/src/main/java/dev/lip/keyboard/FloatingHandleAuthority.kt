package dev.lip.keyboard

/** CPU-only gesture identity survives UP admission until the final write check.
 * New contact/cancel/close revokes it even after the active operation was removed.
 */
class FloatingHandleAuthority {
    private var epoch=Any()
    private var dispatched:Any?=null
    fun reserve():Any {epoch=Any();dispatched=null;return epoch}
    fun current(ticket:Any)=ticket===epoch
    fun cancel(){epoch=Any();dispatched=null}
    fun dispatch(ticket:Any,allowed:()->Boolean,action:()->Boolean):Boolean {
        if(!current(ticket)||dispatched===ticket)return false
        dispatched=ticket // Consume before the external freshness callback, even on refusal.
        if(!runCatching(allowed).getOrDefault(false)||!current(ticket))return false
        return action()
    }
}
