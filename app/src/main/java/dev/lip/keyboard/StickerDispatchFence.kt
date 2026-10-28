package dev.lip.keyboard

import java.util.concurrent.atomic.AtomicReference

/** One cancellation/dispatch admission boundary. DISPATCHING implies neither completion nor revocation. */
internal class StickerDispatchFence {
    enum class State { PENDING, CANCELED, DISPATCHING }
    private val current=AtomicReference(State.PENDING)
    val state:State get()=current.get()
    fun beginDispatch():Boolean=current.compareAndSet(State.PENDING,State.DISPATCHING)
    /** True when cancellation won or was already latched; false once dispatch admission won. */
    fun cancel():Boolean {
        while(true){when(current.get()){
            State.CANCELED->return true
            State.DISPATCHING->return false
            State.PENDING->if(current.compareAndSet(State.PENDING,State.CANCELED))return true
        }}
    }
    override fun toString()="StickerDispatchFence(redacted)"
}
