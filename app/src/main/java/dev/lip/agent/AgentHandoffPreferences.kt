package dev.lip.agent

import android.content.Context
import java.util.concurrent.CopyOnWriteArraySet

/** Desired UI settings only: never credentials, account identity, text or permission to send. */
data class AgentHandoffPreferences(
    val route: AgentRouteChoice = AgentRouteChoice.NONE,
    val pinned: Boolean = false,
    val defaultEffort: AgentEffort = AgentEffort.MEDIUM,
) {
    fun encode(): Map<String, Any> = mapOf("version" to 1, "route" to route.name,
        "pinned" to pinned, "effort" to defaultEffort.name)
    companion object {
        /** Unknown, partial, future or malformed records cannot enable a shortcut/route. */
        fun decode(values: Map<String, *>): AgentHandoffPreferences {
            if (values.keys != setOf("version", "route", "pinned", "effort") || values["version"] != 1)
                return AgentHandoffPreferences()
            val route = AgentRouteChoice.entries.firstOrNull { it.name == values["route"] }
                ?: return AgentHandoffPreferences()
            val effort = AgentEffort.entries.firstOrNull { it.name == values["effort"] }
                ?: return AgentHandoffPreferences()
            val pinned = values["pinned"] as? Boolean ?: return AgentHandoffPreferences()
            return AgentHandoffPreferences(route, pinned, effort)
        }
    }
}

/** Immutable confirmed value with opaque identity; equal settings never revive old UI ownership. */
class AgentHandoffPreferenceSnapshot internal constructor(val value: AgentHandoffPreferences, val revision: Any) {
    override fun toString() = "AgentHandoffPreferenceSnapshot(redacted)"
}

/** A failed commit may mutate SharedPreferences' cache, never this confirmed snapshot. */
class AgentHandoffPreferenceRepository(read: () -> Map<String, *>, private val write: (AgentHandoffPreferences) -> Boolean) {
    private var confirmed = AgentHandoffPreferenceSnapshot(
        AgentHandoffPreferences.decode(runCatching(read).getOrDefault(emptyMap<String, Any>())), Any())
    private val observers = CopyOnWriteArraySet<() -> Unit>()
    @Synchronized fun load() = confirmed.value
    @Synchronized fun snapshot() = confirmed
    /** Observes FUTURE confirmed revisions only; registration does not invoke code. */
    fun observe(listener: () -> Unit) { observers.add(listener) }
    fun unobserve(listener: () -> Unit) { observers.remove(listener) }
    fun save(value: AgentHandoffPreferences): Boolean {
        val saved = synchronized(this) {
            if (!runCatching { write(value) }.getOrDefault(false)) false
            else {
                confirmed = AgentHandoffPreferenceSnapshot(value, Any())
                true
            }
        }
        // A listener may synchronously read on another thread or reenter the
        // store. Never invoke it under this repository's or the Store's lock.
        if (saved) observers.forEach { listener ->
            if (listener in observers) runCatching(listener)
        }
        return saved
    }
}

/** Shared confirmation boundary across reconstructed settings and keyboard wrappers. */
class AgentHandoffPreferencesStore(context: Context) {
    init {
        synchronized(lock) {
            if (shared == null) {
                val prefs = runCatching { context.applicationContext.getSharedPreferences("agent_handoff_ui_v1", Context.MODE_PRIVATE) }.getOrNull()
                shared = AgentHandoffPreferenceRepository({ prefs?.all ?: emptyMap<String, Any>() }) { value ->
                    prefs?.edit()?.clear()?.putInt("version", 1)?.putString("route", value.route.name)
                        ?.putBoolean("pinned", value.pinned)?.putString("effort", value.defaultEffort.name)?.commit() == true
                }
            }
        }
    }
    private fun repository() = synchronized(lock) { checkNotNull(shared) }
    fun load() = repository().load()
    fun snapshot() = repository().snapshot()
    fun observe(listener: () -> Unit) { repository().observe(listener) }
    fun unobserve(listener: () -> Unit) { repository().unobserve(listener) }
    fun save(value: AgentHandoffPreferences): Boolean = repository().save(value)
    companion object {
        private val lock = Any()
        private var shared: AgentHandoffPreferenceRepository? = null
    }
}
