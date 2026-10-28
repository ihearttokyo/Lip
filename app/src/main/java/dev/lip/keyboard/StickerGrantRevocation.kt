package dev.lip.keyboard

import java.util.concurrent.atomic.AtomicReference

/** Records one actual revocation callback outcome, never resource drain or Android permission proof by itself. */
internal class StickerGrantRevocation(private val revoke:()->Unit) {
    enum class State { NOT_STARTED, PENDING, SUCCEEDED, UNCONFIRMED }
    private val outcome=AtomicReference(State.NOT_STARTED)
    val state:State get()=outcome.get()

    /** PENDING is not completion. Callback runs once outside locks; failure is permanent, with no retry. */
    fun request():State {
        if(!outcome.compareAndSet(State.NOT_STARTED,State.PENDING))return outcome.get()
        val terminal=try{revoke();State.SUCCEEDED}catch(_:Throwable){State.UNCONFIRMED}
        outcome.set(terminal)
        return terminal
    }
    override fun toString()="StickerGrantRevocation(redacted)"
}
