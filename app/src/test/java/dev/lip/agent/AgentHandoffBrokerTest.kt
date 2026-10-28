package dev.lip.agent

import dev.lip.core.CompletedDictationSource
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AgentHandoffBrokerTest {
    private fun target(account: String = "fixture-account", efforts: Set<AgentEffort> = AgentEffort.entries.toSet()) =
        AgentDestination(AgentRouteChoice.PAIRED_CODEX, "Synthetic agent", "Fixture only", account,
            "Synthetic account", "Dispatch synthetic fixture", efforts)
    private class Fixture {
        val source = CompletedDictationSource()
        val ui = Any()
        val target = AgentDestination(AgentRouteChoice.PAIRED_CODEX, "Synthetic agent", "Fixture only", "fixture-account",
            "Synthetic account", "Dispatch synthetic fixture", AgentEffort.entries.toSet())
        var record = source.complete(source.begin(true, "en-US", "clean"), "  Agenda:\n1. Zoë at 3 p.m. 🧑🏽‍💻\n")!!
        val broker = AgentHandoffBroker({ source.current() }, { source.isCurrent(it) }).also { it.configure(target); it.bindUi(ui) }
        fun offer(mode: AgentEffort = AgentEffort.MEDIUM) = broker.open(mode).offer!!
        fun permit() = broker.claim(offer(), ui)!!
    }

    @Test fun defaultsAndMissingDestinationNeverReadAResult() {
        var reads = 0
        val broker = AgentHandoffBroker({ reads++; null }, { false })
        assertEquals(AgentUnavailable.NO_DESTINATION, broker.open(AgentEffort.MEDIUM).unavailable)
        broker.configure(target())
        assertEquals(AgentUnavailable.NO_UI, broker.open(AgentEffort.MEDIUM).unavailable)
        assertEquals(0, reads)
        broker.bindUi(Any())
        assertEquals(AgentUnavailable.NO_RESULT, broker.open(AgentEffort.MEDIUM).unavailable)
        assertEquals(1, reads)
    }

    @Test fun allFiveNamesAreDesiredModesNotProviderParameters() {
        assertEquals(listOf("Instant", "Medium", "High", "Extra High", "Pro"), AgentEffort.entries.map { it.label })
        val fixture = Fixture()
        for (mode in AgentEffort.entries) {
            val offer = fixture.offer(mode)
            assertEquals(mode, offer.selected)
            fixture.broker.cancel(offer)
        }
    }

    @Test fun openAndSelectOnlyChangeThePendingChoice() {
        val f = Fixture(); val offer = f.offer()
        assertTrue(f.broker.select(offer, AgentEffort.PRO, f.ui))
        assertEquals(AgentEffort.PRO, offer.selected)
        assertTrue(f.source.isCurrent(f.record))
        f.broker.cancel(offer)
        assertNull(f.broker.claim(offer, f.ui))
    }

    @Test fun unsupportedDefaultOrSelectionCannotSilentlyFallback() {
        val f = Fixture(); f.broker.configure(target(efforts = setOf(AgentEffort.HIGH)))
        val offer = f.offer(AgentEffort.PRO)
        assertEquals(AgentEffort.PRO, offer.selected)
        assertNull(f.broker.claim(offer, f.ui))
        assertFalse(f.broker.select(offer, AgentEffort.INSTANT, f.ui))
        assertTrue(f.broker.select(offer, AgentEffort.HIGH, f.ui))
        assertNotNull(f.broker.claim(offer, f.ui))
    }

    @Test fun emptyCapabilitiesHaveNoDispatchableMode() {
        val f = Fixture(); f.broker.configure(target(efforts = emptySet()))
        val offer = f.offer()
        for (mode in AgentEffort.entries) assertFalse(f.broker.select(offer, mode, f.ui))
        assertNull(f.broker.claim(offer, f.ui))
    }

    @Test fun explicitDispatchUsesExactImmutableTextProvenanceAndSelection() {
        val f = Fixture(); val offer = f.offer()
        assertTrue(f.broker.select(offer, AgentEffort.EXTRA_HIGH, f.ui))
        val permit = f.broker.claim(offer, f.ui)!!
        var calls = 0
        assertEquals(AgentDispatchOutcome.DISPATCHED, f.broker.dispatch(permit, f.ui) { payload ->
            calls++
            assertEquals(f.record.text, payload.text)
            assertEquals("en-US", payload.language); assertEquals("clean", payload.modelId)
            assertEquals(AgentEffort.EXTRA_HIGH, payload.effort)
            assertSame(f.target, payload.destination)
            AgentDispatchOutcome.DISPATCHED
        })
        assertEquals(1, calls)
    }

    @Test fun claimAndDispatchCannotBeDuplicated() {
        val f = Fixture(); val offer = f.offer(); val permit = f.broker.claim(offer, f.ui)!!
        assertNull(f.broker.claim(offer, f.ui))
        var calls = 0
        repeat(2) { f.broker.dispatch(permit, f.ui) { calls++; AgentDispatchOutcome.DISPATCHED } }
        assertEquals(1, calls)
        assertEquals(AgentUnavailable.ALREADY_ATTEMPTED, f.broker.open(AgentEffort.MEDIUM).unavailable)
    }

    @Test fun cancelAndReplacementInvalidateOnlyTheIntendedOffer() {
        val f = Fixture(); val old = f.offer(); val current = f.offer()
        f.broker.cancel(old)
        assertNull(f.broker.claim(old, f.ui))
        assertNotNull(f.broker.claim(current, f.ui))
        f.broker.revoke()
        val canceled = f.offer(); f.broker.cancel(canceled)
        assertNull(f.broker.claim(canceled, f.ui))
    }

    @Test fun equalTextNewResultDoesNotRenewAnOldOffer() {
        val f = Fixture(); val offer = f.offer()
        f.record = f.source.complete(f.source.begin(true, "en-US", "clean"), f.record.text)!!
        assertNull(f.broker.claim(offer, f.ui))
        assertNotNull(f.broker.open(AgentEffort.MEDIUM).offer)
    }

    @Test fun revokedResultRejectsBeforeClaimOrAdapter() {
        val f = Fixture(); val offer = f.offer(); f.source.revoke()
        assertNull(f.broker.claim(offer, f.ui))
        val g = Fixture(); val permit = g.permit(); g.source.revoke()
        assertEquals(AgentDispatchOutcome.REFUSED, g.broker.dispatch(permit, g.ui) { fail("No revoked adapter call"); AgentDispatchOutcome.DISPATCHED })
    }

    @Test fun accountAndCapabilityABAInvalidateEvenWithEqualLabels() {
        val f = Fixture(); val offer = f.offer()
        f.broker.configure(target("account-b")); f.broker.configure(f.target)
        assertNull(f.broker.claim(offer, f.ui))
        val capabilityOffer = f.offer()
        f.broker.configure(target(efforts = setOf(AgentEffort.MEDIUM)))
        assertNull(f.broker.claim(capabilityOffer, f.ui))
        val identical = f.offer(); f.broker.configure(target(efforts = setOf(AgentEffort.MEDIUM)))
        assertNull(f.broker.claim(identical, f.ui))
    }

    @Test fun reconfiguringSameObjectStillInvalidatesTheLease() {
        val f = Fixture(); val offer = f.offer()
        f.broker.configure(f.target)
        assertNull(f.broker.claim(offer, f.ui))
    }

    @Test fun uiIncarnationAndOwnerUseIdentityNotEquality() {
        val f = Fixture(); val offer = f.offer()
        f.broker.bindUi(null); f.broker.bindUi(f.ui)
        assertNull(f.broker.claim(offer, f.ui))
        val ownerA = object { override fun equals(other: Any?) = true; override fun hashCode() = 1 }
        val ownerB = object { override fun equals(other: Any?) = true; override fun hashCode() = 1 }
        f.broker.bindUi(ownerA); val current = f.offer()
        assertFalse(f.broker.select(current, AgentEffort.HIGH, ownerB))
        assertNull(f.broker.claim(current, ownerB))
        assertNotNull(f.broker.claim(current, ownerA))
    }

    @Test fun routeOrUiChangeAfterClaimRefusesWithoutCallingAdapter() {
        for (change in 0..2) {
            val f = Fixture(); val permit = f.permit()
            when (change) { 0 -> f.broker.configure(target("new-account")); 1 -> f.broker.bindUi(Any()); else -> f.broker.revoke() }
            assertEquals(AgentDispatchOutcome.REFUSED, f.broker.dispatch(permit, f.ui) { fail("Stale dispatch"); AgentDispatchOutcome.DISPATCHED })
        }
    }

    @Test fun forgedOrForeignPermitsCannotDispatch() {
        val f = Fixture(); val actual = f.permit()
        val other = Fixture()
        assertEquals(AgentDispatchOutcome.REFUSED, other.broker.dispatch(actual, other.ui) { fail("Foreign permit"); AgentDispatchOutcome.DISPATCHED })
        assertEquals(AgentDispatchOutcome.REFUSED, f.broker.dispatch(AgentHandoffBroker.Permit(), f.ui) { fail("Forged permit"); AgentDispatchOutcome.DISPATCHED })
        assertEquals(AgentDispatchOutcome.DISPATCHED, f.broker.dispatch(actual, f.ui) { AgentDispatchOutcome.DISPATCHED })
    }

    @Test fun throwAfterPossibleEffectIsAmbiguousAndNeverRetried() {
        val f = Fixture(); val permit = f.permit(); var calls = 0
        assertEquals(AgentDispatchOutcome.AMBIGUOUS, f.broker.dispatch(permit, f.ui) { calls++; throw IllegalStateException("Synthetic possible send") })
        assertEquals(AgentDispatchOutcome.REFUSED, f.broker.dispatch(permit, f.ui) { calls++; AgentDispatchOutcome.DISPATCHED })
        assertEquals(AgentUnavailable.ALREADY_ATTEMPTED, f.broker.open(AgentEffort.HIGH).unavailable)
        assertEquals(1, calls)
    }

    @Test fun ambiguousAttemptCannotBeResetByOrdinaryUiOrConfigurationChanges() {
        val f = Fixture()
        f.broker.dispatch(f.permit(), f.ui) { AgentDispatchOutcome.AMBIGUOUS }
        repeat(3) { change ->
            when (change) {
                0 -> { f.broker.bindUi(null); f.broker.bindUi(f.ui) }
                1 -> { f.broker.configure(target("other-account")); f.broker.configure(f.target) }
                else -> f.broker.revoke()
            }
            assertEquals(AgentUnavailable.ALREADY_ATTEMPTED, f.broker.open(AgentEffort.HIGH).unavailable)
        }
        f.record = f.source.complete(f.source.begin(true, "en-US", "clean"), "New independently completed query")!!
        assertNotNull(f.broker.open(AgentEffort.MEDIUM).offer)
    }

    @Test fun errorAfterPossibleEffectIsAlsoTypedAmbiguous() {
        val f = Fixture(); var calls = 0
        val permit = f.permit()
        assertEquals(AgentDispatchOutcome.AMBIGUOUS, f.broker.dispatch(permit, f.ui) {
            calls++; throw AssertionError("Synthetic error after possible side effect")
        })
        assertEquals(AgentDispatchOutcome.REFUSED, f.broker.dispatch(permit, f.ui) { calls++; AgentDispatchOutcome.DISPATCHED })
        assertEquals(1, calls)
    }

    @Test fun controllerRecreationDoesNotForgetAnAmbiguousAttempt() {
        val f = Fixture()
        f.broker.dispatch(f.permit(), f.ui) { AgentDispatchOutcome.AMBIGUOUS }
        val recreated = AgentHandoffBroker({ f.source.current() }, { f.source.isCurrent(it) })
        recreated.configure(f.target); recreated.bindUi(Any())
        assertEquals(AgentUnavailable.ALREADY_ATTEMPTED, recreated.open(AgentEffort.MEDIUM).unavailable)
    }

    @Test fun twoIndependentControllersCannotDispatchTheSameRecordTwice() {
        val f = Fixture()
        val other = AgentHandoffBroker({ f.source.current() }, { f.source.isCurrent(it) })
        other.configure(f.target); other.bindUi(f.ui)
        val firstPermit = f.permit()
        val secondPermit = other.claim(other.open(AgentEffort.MEDIUM).offer!!, f.ui)!!
        var calls = 0
        f.broker.dispatch(firstPermit, f.ui) { calls++; AgentDispatchOutcome.AMBIGUOUS }
        assertEquals(AgentDispatchOutcome.REFUSED, other.dispatch(secondPermit, f.ui) { calls++; AgentDispatchOutcome.DISPATCHED })
        assertEquals(1, calls)
    }

    @Test fun adapterReportedAmbiguityAndRefusalNeverAutoRetry() {
        for (outcome in listOf(AgentDispatchOutcome.AMBIGUOUS, AgentDispatchOutcome.REFUSED)) {
            val f = Fixture(); var calls = 0
            assertEquals(outcome, f.broker.dispatch(f.permit(), f.ui) { calls++; outcome })
            assertEquals(1, calls)
            assertEquals(AgentUnavailable.ALREADY_ATTEMPTED, f.broker.open(AgentEffort.PRO).unavailable)
        }
    }

    @Test fun reentrantAdapterCannotReusePermitOrOpenAnotherAction() {
        val f = Fixture(); val permit = f.permit(); var calls = 0
        f.broker.dispatch(permit, f.ui) {
            calls++
            assertEquals(AgentDispatchOutcome.REFUSED, f.broker.dispatch(permit, f.ui) { calls++; AgentDispatchOutcome.DISPATCHED })
            assertEquals(AgentUnavailable.BUSY, f.broker.open(AgentEffort.MEDIUM).unavailable)
            f.broker.configure(target("new-account")); f.broker.bindUi(f.ui)
            assertEquals(AgentUnavailable.BUSY, f.broker.open(AgentEffort.MEDIUM).unavailable)
            AgentDispatchOutcome.DISPATCHED
        }
        assertEquals(1, calls)
    }

    @Test fun reentrantFreshnessRevokeCannotIssueAPermit() {
        val source = CompletedDictationSource(); source.complete(source.begin(true, "en-US", "clean"), "Fixture")
        lateinit var broker: AgentHandoffBroker
        var revoking = false; val ui = Any()
        broker = AgentHandoffBroker({ source.current() }, { if (revoking) broker.revoke(); true })
        broker.configure(target()); broker.bindUi(ui)
        val offer = broker.open(AgentEffort.MEDIUM).offer!!
        revoking = true
        assertNull(broker.claim(offer, ui))
    }

    @Test fun openRejectsConfigChangedInsideResultCallback() {
        val source = CompletedDictationSource(); source.complete(source.begin(true, "en-US", "clean"), "Fixture")
        lateinit var broker: AgentHandoffBroker
        broker = AgentHandoffBroker({ broker.configure(target("replacement")); source.current() }, { true })
        broker.configure(target()); broker.bindUi(Any())
        assertEquals(AgentUnavailable.STALE, broker.open(AgentEffort.MEDIUM).unavailable)
    }

    @Test fun throwingResultCallbacksFailClosed() {
        val broker = AgentHandoffBroker({ throw IllegalStateException("Synthetic") }, { false })
        broker.configure(target()); broker.bindUi(Any())
        assertEquals(AgentUnavailable.NO_RESULT, broker.open(AgentEffort.MEDIUM).unavailable)
        val source = CompletedDictationSource(); source.complete(source.begin(true, "en-US", "clean"), "Fixture")
        val second = AgentHandoffBroker({ source.current() }, { throw IllegalStateException("Synthetic") })
        second.configure(target()); second.bindUi(Any())
        assertEquals(AgentUnavailable.STALE, second.open(AgentEffort.MEDIUM).unavailable)
    }

    @Test fun freshnessAndAdapterCallbacksDoNotHoldTheBrokerMonitor() {
        val source = CompletedDictationSource(); source.complete(source.begin(true, "en-US", "clean"), "Fixture")
        lateinit var broker: AgentHandoffBroker; val ui = Any(); var checkMonitor = false
        fun otherThreadCanBind() {
            val done = CountDownLatch(1)
            val worker = Thread { broker.bindUi(ui); done.countDown() }.apply { start() }
            assertTrue("Callback held broker monitor", done.await(2, TimeUnit.SECONDS))
            worker.join(2_000)
        }
        broker = AgentHandoffBroker({ source.current() }, { if (checkMonitor) otherThreadCanBind(); true })
        broker.configure(target()); broker.bindUi(ui)
        val offer = broker.open(AgentEffort.MEDIUM).offer!!
        checkMonitor = true
        assertNull(broker.claim(offer, ui)) // Reentrant epoch change refuses safely.
        checkMonitor = false
        val fresh = broker.open(AgentEffort.MEDIUM).offer!!
        val permit = broker.claim(fresh, ui)!!
        assertEquals(AgentDispatchOutcome.DISPATCHED, broker.dispatch(permit, ui) { otherThreadCanBind(); AgentDispatchOutcome.DISPATCHED })
    }

    @Test fun concurrentDuplicateDispatchInvokesExactlyOneLocalAdapter() {
        val f = Fixture(); val permit = f.permit(); val start = CountDownLatch(1); val done = CountDownLatch(12)
        val calls = AtomicInteger()
        val workers = (1..12).map {
            Thread {
                try {
                    start.await(2, TimeUnit.SECONDS)
                    f.broker.dispatch(permit, f.ui) { calls.incrementAndGet(); Thread.sleep(30); AgentDispatchOutcome.DISPATCHED }
                } finally { done.countDown() }
            }.apply { start() }
        }
        start.countDown()
        assertTrue(done.await(3, TimeUnit.SECONDS)); workers.forEach { it.join(1_000) }
        assertEquals(1, calls.get())
    }

    @Test fun destinationCapabilitiesCannotBeChangedThroughMutableAliases() {
        val capabilities = mutableSetOf(AgentEffort.HIGH)
        val target = target(efforts = capabilities)
        capabilities += AgentEffort.PRO
        assertEquals(setOf(AgentEffort.HIGH), target.supportedEfforts)
        try { (target.supportedEfforts as MutableSet).add(AgentEffort.INSTANT); fail("Mutable capabilities") }
        catch (_: UnsupportedOperationException) { }
    }

    @Test fun diagnosticsContainNeitherQueryNorAccountIdentity() {
        val f = Fixture(); val offer = f.offer(); val permit = f.broker.claim(offer, f.ui)!!
        for (value in listOf(f.target.toString(), offer.toString(), permit.toString())) {
            assertFalse(value.contains(f.record.text)); assertFalse(value.contains("fixture-account"))
        }
        f.broker.dispatch(permit, f.ui) {
            assertFalse(it.toString().contains(it.text)); assertFalse(it.toString().contains("fixture-account"))
            AgentDispatchOutcome.DISPATCHED
        }
    }

    @Test fun invalidDestinationMetadataCannotHideOrOverflowItsIdentity() {
        for (account in listOf(" ", "line\nspoof", "x".repeat(321))) {
            try {
                AgentDestination(AgentRouteChoice.PAIRED_CODEX, "Agent", "Direct fixture", "account", account,
                    "Dispatch", setOf(AgentEffort.HIGH))
                fail("Invalid destination metadata admitted")
            } catch (_: IllegalArgumentException) { }
        }
        val maximum = AgentDestination(AgentRouteChoice.PAIRED_CODEX, "p".repeat(64), "r".repeat(80), "a".repeat(512),
            "visible-" + "x".repeat(312), "d".repeat(80), setOf(AgentEffort.HIGH))
        assertEquals(320, maximum.accountLabel.length)
    }
}
