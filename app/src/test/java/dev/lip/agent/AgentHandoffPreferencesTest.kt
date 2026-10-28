package dev.lip.agent

import org.junit.Assert.*
import org.junit.Test

class AgentHandoffPreferencesTest {
    @Test fun defaultsHaveNoRouteOrPinAndDesiredMediumOnly() {
        val value = AgentHandoffPreferences()
        assertEquals(AgentRouteChoice.NONE, value.route); assertFalse(value.pinned)
        assertEquals(AgentEffort.MEDIUM, value.defaultEffort)
        assertEquals(value, AgentHandoffPreferences.decode(emptyMap<String, Any>()))
    }
    @Test fun allTypedDesiredSettingsRoundTripWithoutPayloadOrAccount() {
        for (route in AgentRouteChoice.entries) for (effort in AgentEffort.entries) for (pin in listOf(false, true)) {
            val value = AgentHandoffPreferences(route, pin, effort)
            assertEquals(value, AgentHandoffPreferences.decode(value.encode()))
            assertEquals(setOf("version", "route", "pinned", "effort"), value.encode().keys)
        }
    }
    @Test fun malformedOrFutureRecordsFailClosedEntirely() {
        val active = AgentHandoffPreferences(AgentRouteChoice.CHATGPT_APP, true, AgentEffort.PRO).encode()
        val mutations = listOf(active + ("version" to 2), active + ("version" to "1"), active + ("route" to "UNKNOWN"),
            active + ("effort" to "unknown"), active + ("pinned" to "true"), active - "version", active - "effort",
            active + ("account" to "must-not-be-stored"))
        for (record in mutations) assertEquals(AgentHandoffPreferences(), AgentHandoffPreferences.decode(record))
    }
    @Test fun failedCommitNeverAdoptsMutatedBackendCache() {
        var cache = AgentHandoffPreferences().encode()
        var permit = false; var writes = 0
        val repository = AgentHandoffPreferenceRepository({ cache }) { value ->
            writes++; cache = value.encode(); permit
        }
        val desired = AgentHandoffPreferences(AgentRouteChoice.PAIRED_CODEX, true, AgentEffort.HIGH)
        assertFalse(repository.save(desired)); assertEquals(desired.encode(), cache)
        assertEquals(AgentHandoffPreferences(), repository.load()); assertEquals(1, writes)
        permit = true
        assertTrue(repository.save(desired)); assertEquals(desired, repository.load()); assertEquals(2, writes)
    }
    @Test fun thrownReadOrWriteDoesNotAdmitUnconfirmedSetting() {
        val repository = AgentHandoffPreferenceRepository({ throw IllegalStateException("synthetic read failure") }) {
            throw IllegalStateException("synthetic write failure")
        }
        assertEquals(AgentHandoffPreferences(), repository.load())
        assertFalse(repository.save(AgentHandoffPreferences(pinned = true)))
        assertEquals(AgentHandoffPreferences(), repository.load())
    }

    @Test fun successfulEqualSaveAndABAAdvanceRevisionButFailureCannot() {
        var permit = true
        var throwing = false
        val repository = AgentHandoffPreferenceRepository({ emptyMap<String, Any>() }) {
            if (throwing) throw IllegalStateException("Synthetic failure")
            permit
        }
        val a = repository.snapshot()
        assertSame(a, repository.snapshot())
        assertEquals(repository.load(), a.value)
        assertTrue(repository.save(a.value))
        val equal = repository.snapshot()
        assertEquals(a.value, equal.value); assertNotSame(a.revision, equal.revision)
        val b = AgentHandoffPreferences(AgentRouteChoice.PAIRED_CODEX, true, AgentEffort.PRO)
        assertTrue(repository.save(b))
        val changed = repository.snapshot()
        assertEquals(b, changed.value); assertNotSame(equal.revision, changed.revision)
        assertTrue(repository.save(a.value))
        val returned = repository.snapshot()
        assertEquals(a.value, returned.value); assertNotSame(a.revision, returned.revision)
        assertNotSame(changed.revision, returned.revision)
        permit = false
        assertFalse(repository.save(b)); assertSame(returned, repository.snapshot())
        throwing = true
        assertFalse(repository.save(b)); assertSame(returned.revision, repository.snapshot().revision)
        assertEquals(a.value, repository.load())
    }

    @Test fun confirmedObserverRunsAfterLockAndOnlyOnSuccessfulRevisions() {
        var permit = true
        var throwing = false
        val repository = AgentHandoffPreferenceRepository({ emptyMap<String, Any>() }) {
            if (throwing) throw IllegalStateException("Synthetic failure")
            permit
        }
        var calls = 0
        val revisions = ArrayList<Any>()
        val errors = ArrayList<String>()
        val workers = ArrayList<Thread>()
        val observer: () -> Unit = {
            calls++
            val snapshot = repository.snapshot()
            revisions.add(snapshot.revision)
            val done = java.util.concurrent.CountDownLatch(1)
            val seen = java.util.concurrent.atomic.AtomicReference<AgentHandoffPreferenceSnapshot>()
            val worker = Thread { seen.set(repository.snapshot()); done.countDown() }
            workers.add(worker); worker.start()
            if (!done.await(2, java.util.concurrent.TimeUnit.SECONDS)) errors.add("Observer ran while repository lock held")
            else if (seen.get() !== snapshot) errors.add("Observer did not see confirmed revision")
        }
        repository.observe(observer)
        assertEquals(0, calls) // Observe is not an implicit change/callback.
        val initial = repository.snapshot()
        assertTrue(repository.save(initial.value))
        assertEquals(1, calls); assertNotSame(initial.revision, revisions.single())
        assertTrue(repository.save(initial.value))
        assertEquals(2, calls); assertNotSame(revisions[0], revisions[1])
        val confirmed = repository.snapshot()
        permit = false
        assertFalse(repository.save(AgentHandoffPreferences(pinned = true)))
        throwing = true
        assertFalse(repository.save(AgentHandoffPreferences(pinned = true)))
        assertEquals(2, calls); assertSame(confirmed, repository.snapshot())
        repository.unobserve(observer)
        throwing = false; permit = true
        assertTrue(repository.save(initial.value)); assertEquals(2, calls)
        workers.forEach { it.join(3_000); assertFalse(it.isAlive) }
        assertTrue(errors.toString(), errors.isEmpty())
    }
}
