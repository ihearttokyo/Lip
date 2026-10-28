package dev.lip.agent

import dev.lip.core.CompletedDictationSource
import java.util.Collections
import java.util.EnumSet

/** Desired UI modes, NOT provider API parameters or guaranteed capabilities. */
enum class AgentEffort(val label: String) {
    INSTANT("Instant"), MEDIUM("Medium"), HIGH("High"), EXTRA_HIGH("Extra High"), PRO("Pro")
}
enum class AgentRouteChoice { NONE, CHATGPT_APP, CLAUDE_APP, PAIRED_CODEX }

/** Created only by a separately admitted adapter, never merely from saved settings.
 * All identity/capability changes require configure(), even when labels are equal.
 * No adapter is admitted by this metadata type, and no account tokens belong here.
 */
class AgentDestination(
    val route: AgentRouteChoice,
    val providerLabel: String,
    val routeLabel: String,
    val accountIdentity: String,
    val accountLabel: String,
    val actionLabel: String,
    efforts: Set<AgentEffort>,
) {
    val supportedEfforts: Set<AgentEffort> = Collections.unmodifiableSet(
        EnumSet.noneOf(AgentEffort::class.java).also { it.addAll(efforts) })
    init {
        require(route != AgentRouteChoice.NONE)
        require(listOf(providerLabel, routeLabel, accountIdentity, accountLabel, actionLabel).all { it.isNotBlank() })
        require(providerLabel.length <= 64 && routeLabel.length <= 80 && accountIdentity.length <= 512 &&
            accountLabel.length <= 320 && actionLabel.length <= 80)
        require(listOf(providerLabel, routeLabel, accountIdentity, accountLabel, actionLabel).none { text -> text.any { it.isISOControl() } })
    }
    override fun toString() = "AgentDestination(redacted)"
}

/** Dispatch means the adapter was invoked/launched, never provider acceptance. */
enum class AgentDispatchOutcome { DISPATCHED, AMBIGUOUS, REFUSED }
enum class AgentUnavailable { NO_DESTINATION, NO_RESULT, NO_UI, BUSY, ALREADY_ATTEMPTED, STALE }

/** RAM-only admission; no Android, network, editor, clipboard, storage or auth APIs.
 * Result checks and adapter callbacks run OUTSIDE the state monitor. The UI owner
 * must revoke synchronously on context/privacy changes and validate on its UI
 * thread immediately before an adapter call. This cannot make remote acceptance
 * exactly-once; it only bounds local adapter invocation to one explicit action.
 */
class AgentHandoffBroker(
    private val currentResult: () -> CompletedDictationSource.Record?,
    private val resultCurrent: (CompletedDictationSource.Record) -> Boolean,
) {
    /** UI retains metadata/opaque identity only, never a transcript reference. */
    class Offer internal constructor(val destination: AgentDestination, selected: AgentEffort) {
        @Volatile var selected: AgentEffort = selected; internal set
        override fun toString() = "AgentHandoffBroker.Offer(redacted)"
    }
    class Permit internal constructor() {
        override fun toString() = "AgentHandoffBroker.Permit(redacted)"
    }
    class Payload internal constructor(
        val text: String, val language: String, val modelId: String,
        val destination: AgentDestination, val effort: AgentEffort,
    ) { override fun toString() = "AgentHandoffBroker.Payload(redacted)" }
    class OpenResult internal constructor(val offer: Offer?, val unavailable: AgentUnavailable?)
    private class Pending(
        val offer: Offer, val record: CompletedDictationSource.Record, val epoch: Any, val ui: Any,
    )
    private class Claimed(val permit: Permit, val pending: Pending, val effort: AgentEffort)

    private val lock = Any()
    private var destination: AgentDestination? = null
    private var epoch = Any()
    private var ui: Any? = null
    private var pending: Pending? = null
    private var claimed: Claimed? = null
    private var dispatching = false

    /** Account/route/capability ABA is invalidated even with the same descriptor. */
    fun configure(value: AgentDestination?) = synchronized(lock) {
        destination = value
        invalidateLocked()
    }

    /** Keyboard/editor/render incarnation, never cursor text. */
    fun bindUi(owner: Any?) = synchronized(lock) {
        ui = owner
        invalidateLocked()
    }

    /** Drop every query-holding lease on privacy/result/settings revocation. */
    fun revoke() = synchronized(lock) { invalidateLocked() }
    private fun invalidateLocked() {
        epoch = Any()
        pending = null
        claimed = null
        // An already invoked adapter cannot be undone; keep its BUSY fence until
        // it returns, even if the route/UI changes reentrantly during that call.
    }

    fun open(defaultEffort: AgentEffort): OpenResult {
        val state = synchronized(lock) {
            if (dispatching || claimed != null) return OpenResult(null, AgentUnavailable.BUSY)
            pending = null // A new open also drops an obsolete/failed previous offer.
            val target = destination ?: return OpenResult(null, AgentUnavailable.NO_DESTINATION)
            val owner = ui ?: return OpenResult(null, AgentUnavailable.NO_UI)
            Triple(target, owner, epoch)
        }
        val record = runCatching(currentResult).getOrNull() ?: return OpenResult(null, AgentUnavailable.NO_RESULT)
        if (!runCatching { resultCurrent(record) }.getOrDefault(false)) return OpenResult(null, AgentUnavailable.STALE)
        return synchronized(lock) {
            if (dispatching || claimed != null) return OpenResult(null, AgentUnavailable.BUSY)
            if (state.third !== epoch || state.first !== destination || state.second !== ui)
                return OpenResult(null, AgentUnavailable.STALE)
            if (alreadyAttempted(record)) return OpenResult(null, AgentUnavailable.ALREADY_ATTEMPTED)
            val offer = Offer(state.first, defaultEffort)
            pending = Pending(offer, record, epoch, state.second)
            OpenResult(offer, null)
        }
    }

    /** Opening or selecting a mode does not claim or dispatch anything. */
    fun select(offer: Offer, effort: AgentEffort, owner: Any): Boolean = synchronized(lock) {
        val item = pending ?: return false
        if (item.offer !== offer || item.ui !== owner || item.epoch !== epoch || ui !== owner ||
            effort !in item.offer.destination.supportedEfforts) return false
        offer.selected = effort
        true
    }

    fun cancel(offer: Offer) = synchronized(lock) {
        if (pending?.offer === offer) pending = null
    }

    /** One immutable selection, consumed before any freshness callback can reenter. */
    fun claim(offer: Offer, owner: Any): Permit? {
        val item = synchronized(lock) {
            val item = pending ?: return null
            if (dispatching || claimed != null || item.offer !== offer || item.ui !== owner || ui !== owner ||
                item.epoch !== epoch || offer.selected !in offer.destination.supportedEfforts) return null
            pending = null
            Claimed(Permit(), item, offer.selected).also { claimed = it }
        }
        val fresh = runCatching { resultCurrent(item.pending.record) }.getOrDefault(false)
        return synchronized(lock) {
            if (!fresh || claimed !== item || item.pending.epoch !== epoch || ui !== owner) {
                if (claimed === item) claimed = null
                return null
            }
            item.permit
        }
    }

    /** A single adapter invocation at most. Throw/timeout/unknown is AMBIGUOUS,
     * never an instruction to retry or fall back to another route/account.
     */
    fun dispatch(permit: Permit, owner: Any, adapter: (Payload) -> AgentDispatchOutcome): AgentDispatchOutcome {
        val item = synchronized(lock) {
            val item = claimed ?: return AgentDispatchOutcome.REFUSED
            if (item.permit !== permit || dispatching || ui !== owner || item.pending.ui !== owner ||
                item.pending.epoch !== epoch) return AgentDispatchOutcome.REFUSED
            claimed = null // Consume BEFORE result validation or adapter callbacks.
            if (!claimAttempt(item.pending.record)) return AgentDispatchOutcome.REFUSED
            dispatching = true
            item
        }
        try {
            if (!runCatching { resultCurrent(item.pending.record) }.getOrDefault(false))
                return AgentDispatchOutcome.REFUSED
            val admitted = synchronized(lock) { epoch === item.pending.epoch && ui === owner && destination === item.pending.offer.destination }
            if (!admitted) return AgentDispatchOutcome.REFUSED
            val record = item.pending.record
            val payload = Payload(record.text, record.language, record.modelId, item.pending.offer.destination, item.effort)
            return try { adapter(payload) } catch (_: Throwable) { AgentDispatchOutcome.AMBIGUOUS }
        } finally {
            synchronized(lock) { dispatching = false }
        }
    }

    companion object {
        // Process-wide because an IME/controller may be recreated while the
        // process-lifetime source still owns the SAME completed result. Weak keys
        // and non-referencing values retain no revoked query; Record uses identity
        // equality. No automatic reset on configuration, window, or route changes.
        private val attempts = java.util.WeakHashMap<CompletedDictationSource.Record, Boolean>()
        private fun alreadyAttempted(record: CompletedDictationSource.Record): Boolean =
            synchronized(attempts) { attempts.containsKey(record) }
        private fun claimAttempt(record: CompletedDictationSource.Record): Boolean = synchronized(attempts) {
            if (attempts.containsKey(record)) false else { attempts[record] = true; true }
        }
    }
}
