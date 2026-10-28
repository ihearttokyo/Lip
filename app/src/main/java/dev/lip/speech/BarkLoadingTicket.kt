package dev.lip.speech

/** Cancellation exists before native preparation; it never owns a cached engine. */
class BarkLoadingTicket internal constructor() {
    private val state = Any()
    private var canceled = false
    private var bound = false
    private var finished = false
    private var signaled = false
    private var cancelNative: (() -> Unit)? = null

    fun cancel() {
        val signal = synchronized(state) {
            canceled = true
            pendingSignal()
        }
        signalSafely(signal) // Nonblocking, outside monitor; cancellation stays sticky.
    }

    internal fun bind(cancel: () -> Unit) {
        val signal = synchronized(state) {
            check(!bound && !finished) { "Loading ticket has already been used" }
            bound = true
            cancelNative = cancel
            pendingSignal()
        }
        signalSafely(signal)
    }

    internal fun isCanceled(): Boolean = synchronized(state) { canceled }

    private fun signalSafely(callback: (() -> Unit)?) {
        try { callback?.invoke() } catch (_: Exception) {
            // Failed signaling cannot make a canceled loading operation adoptable.
            // The worker checks this ticket before load and again at commit/close.
        }
    }

    internal fun commit(): Boolean = synchronized(state) {
        check(bound && !finished) { "Loading ticket is not preparing" }
        finished = true
        cancelNative = null
        !canceled
    }

    internal fun finishFailed() = synchronized(state) {
        finished = true
        cancelNative = null
    }

    private fun pendingSignal(): (() -> Unit)? {
        if (!canceled || finished || signaled) return null
        val callback = cancelNative ?: return null
        signaled = true
        return callback
    }
}
