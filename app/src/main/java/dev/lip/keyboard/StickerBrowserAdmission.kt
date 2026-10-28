package dev.lip.keyboard

import java.util.concurrent.atomic.AtomicBoolean

/** Cancellation revokes delivery, but capacity remains owned until the actual read returns. */
internal class StickerBrowserAdmission {
    internal class Ticket internal constructor(){
        private val live=AtomicBoolean(true)
        fun cancel(){live.set(false)}
        fun isLive()=live.get()
    }
    private var active:Ticket?=null
    @Synchronized fun begin():Ticket? {
        if(active!=null)return null
        return Ticket().also{active=it}
    }
    @Synchronized fun finish(ticket:Ticket):Boolean {
        if(active!==ticket)return false
        active=null;return true
    }
    @Synchronized fun isActive()=active!=null
}
