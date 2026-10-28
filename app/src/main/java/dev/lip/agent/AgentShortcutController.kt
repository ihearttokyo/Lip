package dev.lip.agent

import android.content.Context
import android.os.Looper
import android.view.View
import dev.lip.core.CompletedDictationSource

/** No production adapter exists yet. An eventual adapter needs its own verified
 * route/account/consent/effort contract; saved settings are NOT that admission.
 * dispatch must launch/enqueue promptly, never perform blocking inference here.
 */
interface AgentAdapter {
    val destination: AgentDestination
    fun dispatch(payload: AgentHandoffBroker.Payload): AgentDispatchOutcome
}

/** Native UI bridge. All methods run on the UI thread; source/context changes
 * synchronously revoke leases and dismiss the metadata-only popup. No field,
 * clipboard, history, provider login or transport implementation lives here.
 */
class AgentShortcutController(
    context: Context,
    currentResult: () -> CompletedDictationSource.Record?,
    resultCurrent: (CompletedDictationSource.Record) -> Boolean,
    private val settings: AgentHandoffPreferencesStore,
    private val resolve: (AgentRouteChoice) -> AgentAdapter?,
    private val eligible: () -> Boolean,
    private val status: (String) -> Unit,
) {
    private val context = context
    private val broker = AgentHandoffBroker(currentResult, resultCurrent)
    private var revision: Any? = null
    private var adapter: AgentAdapter? = null
    private var destination: AgentDestination? = null
    private var ui: Any? = null
    private var menu: AgentEffortMenu? = null
    internal var lastMenuDismissReason: AgentEffortMenu.DismissReason? = null; private set
    internal var lastMenuShowResult: Boolean? = null; private set
    private var closed = false
    private val main = android.os.Handler(Looper.getMainLooper())
    private val settingsObserver: () -> Unit = {
        val apply = { if (!closed) refresh(); Unit }
        if (Looper.myLooper() == Looper.getMainLooper()) apply() else main.post { apply() }
    }
    init { settings.observe(settingsObserver) }
    private fun checkThread() = check(Looper.myLooper() == Looper.getMainLooper())

    fun bindUi(owner: Any?) {
        checkThread()
        menu?.dismiss(); menu = null
        ui = owner
        broker.bindUi(owner)
    }

    fun sourceChanged() {
        checkThread()
        broker.revoke()
        menu?.dismiss(); menu = null
    }

    fun close() {
        checkThread()
        closed = true
        settings.unobserve(settingsObserver)
        main.removeCallbacksAndMessages(null)
        bindUi(null)
        broker.configure(null)
        adapter = null; destination = null
    }

    private fun refresh(): AgentHandoffPreferenceSnapshot {
        val snapshot = settings.snapshot()
        val resolved = if (snapshot.value.route == AgentRouteChoice.NONE) null else runCatching { resolve(snapshot.value.route) }.getOrNull()
        val target = runCatching { resolved?.destination?.takeIf { it.route == snapshot.value.route } }.getOrNull()
        if (snapshot.revision !== revision || resolved !== adapter || target !== destination) {
            menu?.dismiss(); menu = null
            broker.configure(target)
            revision = snapshot.revision; adapter = if (target != null) resolved else null; destination = target
        }
        return snapshot
    }

    /** Pin only a real admitted route; never turn a saved enum into a fake button. */
    fun pinned(): Boolean {
        checkThread()
        return !closed && refresh().value.pinned && adapter != null
    }

    private fun allowed(anchor: View): Boolean = !closed && ui != null && anchor.isAttachedToWindow &&
        anchor.isShown && runCatching(eligible).getOrDefault(false)

    private fun unavailable(reason: AgentUnavailable?) {
        status(when (reason) {
            AgentUnavailable.NO_RESULT -> "Finish a Lip dictation first. Nothing was sent."
            AgentUnavailable.ALREADY_ATTEMPTED -> "This dictation was already attempted. Check the destination; no automatic retry."
            AgentUnavailable.BUSY -> "An agent action is already in progress."
            AgentUnavailable.NO_DESTINATION -> "No verified direct/background destination is configured. Nothing was sent."
            else -> "This agent action is no longer current. Nothing was sent."
        })
    }

    fun tap(anchor: View) {
        checkThread()
        menu?.dismiss(); menu = null
        val snapshot = refresh()
        if (!allowed(anchor)) { sourceChanged(); unavailable(AgentUnavailable.STALE); return }
        val opened = broker.open(snapshot.value.defaultEffort)
        val offer = opened.offer ?: run { unavailable(opened.unavailable); return }
        if (offer.selected !in offer.destination.supportedEfforts) {
            broker.cancel(offer)
            status("The chosen mode is unavailable for this destination. Hold the shortcut to choose a supported mode.")
            return
        }
        commit(offer, anchor, snapshot.revision)
    }

    fun showModes(anchor: View): Boolean {
        checkThread()
        menu?.dismiss(); menu = null
        lastMenuDismissReason = null; lastMenuShowResult = null
        val snapshot = refresh()
        if (!allowed(anchor)) { sourceChanged(); unavailable(AgentUnavailable.STALE); return false }
        val opened = broker.open(snapshot.value.defaultEffort)
        val offer = opened.offer ?: run { unavailable(opened.unavailable); return false }
        val owner = ui ?: return false
        val target = offer.destination
        val popup = AgentEffortMenu(context, anchor,
            AgentEffortMenu.Model("Choose effort", "${target.providerLabel} · ${target.routeLabel}\n${target.accountLabel}",
                offer.selected, AgentEffort.entries.associateWith { effort ->
                    AgentEffortMenu.ModeAvailability(effort in target.supportedEfforts, "Not supported by this destination.")
                }, target.actionLabel, actionEnabled = true),
            onModeSelected = { mode ->
                if (ui !== owner || !allowed(anchor) || settings.snapshot().revision !== snapshot.revision ||
                    !broker.select(offer, mode, owner)) sourceChanged()
            },
            onExplicitAction = { mode ->
                if (broker.select(offer, mode, owner)) commit(offer, anchor, snapshot.revision)
                else unavailable(AgentUnavailable.STALE)
            },
            onDismiss = { reason ->
                lastMenuDismissReason = reason
                menu = null
                // ACTION dismissal precedes its one explicit action callback.
                // Every other close is cancellation, never transport admission.
                if (reason != AgentEffortMenu.DismissReason.ACTION) broker.cancel(offer)
            })
        menu = popup
        return popup.show().also { lastMenuShowResult = it }
    }

    private fun commit(offer: AgentHandoffBroker.Offer, anchor: View, settingsRevision: Any) {
        val snapshot = refresh()
        val owner = ui
        val port = adapter
        if (snapshot.revision !== settingsRevision || owner == null || port == null ||
            port.destination !== offer.destination || !allowed(anchor)) {
            broker.cancel(offer); unavailable(AgentUnavailable.STALE); return
        }
        val permit = broker.claim(offer, owner) ?: run { unavailable(AgentUnavailable.STALE); return }
        val result = broker.dispatch(permit, owner) { payload ->
            if (settings.snapshot().revision !== settingsRevision || ui !== owner || adapter !== port ||
                port.destination !== payload.destination || !allowed(anchor)) AgentDispatchOutcome.REFUSED
            else port.dispatch(payload)
        }
        status(when (result) {
            AgentDispatchOutcome.DISPATCHED -> "Action dispatched. Provider acceptance is not confirmed."
            AgentDispatchOutcome.AMBIGUOUS -> "Action unconfirmed. Check the destination; it will not be retried automatically."
            AgentDispatchOutcome.REFUSED -> "Action refused. Nothing else was attempted."
        })
    }
}
