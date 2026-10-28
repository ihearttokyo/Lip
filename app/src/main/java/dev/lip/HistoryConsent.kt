package dev.lip

import java.util.UUID

/** Memory-only state. No provider, preferences, callbacks or disk under these locks. */
class HistoryConsentGate(enabled: Boolean = false) {
    data class Snapshot(val request: UUID, val requested: Boolean, val ready: Boolean, val preparing: Boolean, val failed: Boolean)
    private var state = Snapshot(UUID.randomUUID(), enabled, enabled, false, false)
    @Synchronized fun snapshot(): Snapshot = state
    @Synchronized fun isCurrent(request: UUID): Boolean = state.request == request
    @Synchronized fun request(enabled: Boolean): UUID {
        val id = UUID.randomUUID()
        state = Snapshot(id, enabled, false, true, false)
        return id
    }
    @Synchronized fun complete(id: UUID, persisted: Boolean): Boolean {
        if (id != state.request) return false
        state = state.copy(ready = state.requested && persisted, preparing = false, failed = !persisted)
        return true
    }
    @Synchronized fun ticket(): HistoryConsentTicket? =
        if (state.requested && state.ready) HistoryConsentTicket(state.request) else null
    @Synchronized fun eligible(ticket: HistoryConsentTicket): Boolean =
        state.requested && state.ready && state.request == ticket.request && ticket.available()
    @Synchronized fun claim(ticket: HistoryConsentTicket) {
        check(state.requested && state.ready && state.request == ticket.request) { "History consent changed." }
        ticket.claim()
    }
    @Synchronized fun sameRequest(ticket: HistoryConsentTicket): Boolean =
        state.requested && state.ready && state.request == ticket.request && ticket.reportable()
}

class HistoryConsentTicket internal constructor(val request: UUID) {
    private enum class State { AVAILABLE, REVOKED, CLAIMED }
    private var state = State.AVAILABLE
    @Synchronized fun revoke() { if (state != State.CLAIMED) state = State.REVOKED }
    @Synchronized internal fun available() = state == State.AVAILABLE
    @Synchronized internal fun reportable() = state != State.REVOKED
    @Synchronized internal fun claim() {
        check(state == State.AVAILABLE) { "History capture was canceled or already saved." }
        state = State.CLAIMED
    }
}

object HistoryConsentPresentation {
    fun describe(value: HistoryConsentGate.Snapshot): String = when {
        value.preparing && value.requested -> "History: On requested · saving preference; no new saves yet."
        value.preparing -> "History: Off requested · draining writes; preference unconfirmed."
        value.failed -> "History: preference persistence unconfirmed; no new saves. Existing records preserved."
        value.ready -> "History: On for eligible new recordings only."
        else -> "History: Off · existing records preserved."
    }
}

object HistoryConsentPolicy {
    fun explicit(value: Any?): Boolean = value is Boolean && value
}

/** Run ONLY on the shared serial preference executor. Reordered submissions may be
 * obsolete before reaching that executor: never let them overwrite confirmed Off.
 * No gate lock spans drain, persistence or callbacks. */
object HistoryConsentPersistence {
    fun apply(gate: HistoryConsentGate, request: UUID, enabled: Boolean, drain: () -> Unit, persist: (Boolean) -> Boolean): Boolean {
        if (!gate.isCurrent(request)) return false
        if (!enabled) drain()
        if (!gate.isCurrent(request)) return false
        val saved = runCatching { persist(enabled) }.getOrDefault(false)
        return gate.complete(request, saved)
    }
}
