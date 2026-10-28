package dev.lip

import dev.lip.auth.AuthException
import dev.lip.core.CleanupRules
import dev.lip.core.OutputAssessment
import org.junit.Assert.*
import org.junit.Test

class DictationCleanupTest {
    @Test fun consentedProviderFailureDoesNotDiagnoseMissingConnection() {
        val reason = "ChatGPT usage limit reached. Manage usage in ChatGPT settings."
        val status = CleanupRules.cleanupStatus("polished", null, true, AuthException(reason))
        assertFalse("A consented cleanup failure must not be diagnosed as missing login: $status", status.contains("Connect ChatGPT"))
        assertTrue(status.contains(reason))
        assertTrue(status.contains("Local formatting only"))
        assertTrue(status.contains("raw kept"))
        assertTrue(status.contains("nothing auto-inserted"))
    }

    @Test fun consentDisabledDoesNotAskForAnotherLoginOrAcceptAnOldResult() {
        val status = CleanupRules.cleanupStatus("polished", OutputAssessment(true, emptyList()), false,
            AuthException("ChatGPT usage limit reached. Manage usage in ChatGPT settings."))
        assertTrue(status.contains("ChatGPT cleanup is off. Enable it in Settings and consent to text cleanup."))
        assertFalse(status.contains("Connect ChatGPT"))
        assertFalse(status.contains("usage limit"))
        assertFalse(status.contains("cleaned with ChatGPT"))
        assertTrue(status.contains("nothing auto-inserted"))
    }

    @Test fun missingSessionAndMissingPlanPermissionHaveDifferentRecoverySteps() {
        val session = "Continue with ChatGPT to enable cleanup."
        val plan = "Enable ChatGPT plan use by continuing with ChatGPT again."
        for (reason in listOf(session, plan)) {
            val status = CleanupRules.cleanupStatus("light", null, true, AuthException(reason))
            assertTrue(status.contains(reason))
            assertFalse(status.contains("Connect ChatGPT"))
        }
        assertNotEquals(CleanupRules.cleanupStatus("light", null, true, AuthException(session)),
            CleanupRules.cleanupStatus("light", null, true, AuthException(plan)))
    }

    @Test fun safeNetworkSessionAndProviderFailuresRemainActionable() {
        val reasons = listOf(
            "ChatGPT connection failed. Existing credentials were preserved.",
            "ChatGPT cleanup was interrupted. Your original text is preserved.",
            "ChatGPT request failed (HTTP 503). Existing credentials were preserved.",
            "ChatGPT session ended. Continue with ChatGPT again.",
            "ChatGPT declined cleanup. Your original text is preserved.",
            "ChatGPT cleanup timed out. Your original text is preserved.",
            "Saved ChatGPT credentials could not be read. Existing data has been preserved.",
            "ChatGPT account changed. The previous cleanup was discarded.",
            "No ChatGPT cleanup models are available. Try again later.",
            "Continue with ChatGPT again to renew cleanup.",
        )
        for (reason in reasons) {
            val status = CleanupRules.cleanupStatus("polished", null, true, AuthException(reason))
            assertTrue("Lost sanitized reason: $reason", status.contains(reason))
            assertFalse(status.contains("Connect ChatGPT"))
            assertEquals(reason, CleanupRules.cleanupFailure(AuthException(reason)))
        }
    }

    @Test fun unknownExceptionsAndUnrecognizedAuthMessagesNeverReachTheUi() {
        val unsafe = "fixture-private-text https://example.invalid/auth?code=fixture bearer=fixture-token"
        for (failure in listOf(null, IllegalStateException(unsafe), AuthException(unsafe),
            IllegalStateException("Continue with ChatGPT to enable cleanup."),
            AuthException("ChatGPT request failed (HTTP 503). Existing credentials were preserved. $unsafe"))) {
            val status = CleanupRules.cleanupStatus("polished", null, true, failure)
            assertTrue(status.contains("ChatGPT cleanup failed. Try again later."))
            assertEquals("ChatGPT cleanup failed. Try again later.", CleanupRules.cleanupFailure(failure))
            assertFalse(status.contains("fixture"))
            assertFalse(status.contains("Continue with ChatGPT"))
        }
    }

    @Test fun httpFailureAllowsOnlyTheExactSanitizedStatusPattern() {
        for (status in listOf(100, 199, 302, 401, 429, 500, 599)) {
            val reason = "ChatGPT request failed (HTTP $status). Existing credentials were preserved."
            assertEquals(reason, CleanupRules.cleanupFailure(AuthException(reason)))
        }
        for (status in listOf("99", "600", "999", "503\nfixture-private-text")) {
            val reason = "ChatGPT request failed (HTTP $status). Existing credentials were preserved."
            assertEquals("ChatGPT cleanup failed. Try again later.", CleanupRules.cleanupFailure(AuthException(reason)))
        }
    }

    @Test fun successfulReviewAndVerbatimStatusesStayUnchanged() {
        assertEquals("Ready · cleaned with ChatGPT",
            CleanupRules.cleanupStatus("polished", OutputAssessment(true, emptyList()), true, null))
        assertEquals("Review required · Numbers, signs or units changed; Negation changed",
            CleanupRules.cleanupStatus("light", OutputAssessment(false,
                listOf("Numbers, signs or units changed", "Negation changed")), true, null))
        val rawOnly = "Ready · on-device transcript"
        assertEquals(rawOnly, CleanupRules.cleanupStatus("verbatim", null, false, null))
        assertEquals(rawOnly, CleanupRules.cleanupStatus("verbatim", null, true, AuthException("fixture-private-text")))
    }
}
