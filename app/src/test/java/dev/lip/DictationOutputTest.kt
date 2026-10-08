package dev.lip

import org.junit.Assert.*
import org.junit.Test

class DictationOutputTest {
    @Test fun rawCleanedRawRoundTripPreservesExactCandidates() {
        val raw = " か\u3099 🙂\nUSDJPY 150.25 "
        val cleaned = "が 🙂\nUSDJPY 150.25\n"
        val output = Dictation.OutputChoices()
        output.complete(raw, cleaned)
        assertEquals(cleaned, output.selected)
        assertFalse(output.isRaw)
        assertEquals(raw, output.select(raw = true, busy = false))
        assertTrue(output.isRaw)
        assertEquals(cleaned, output.cleaned)
        assertEquals(cleaned, output.select(raw = false, busy = false))
        assertFalse(output.isRaw)
        assertEquals(raw, output.select(raw = true, busy = false))
        assertEquals(raw, output.raw)
        assertEquals(cleaned, output.cleaned)
    }

    @Test fun busySelectionCannotChangeTheChosenOutput() {
        val output = Dictation.OutputChoices()
        output.complete("raw", "cleaned")
        assertNull(output.select(raw = true, busy = true))
        assertNull(output.select(raw = false, busy = true))
        assertEquals("cleaned", output.selected)
        assertFalse(output.isRaw)
    }

    @Test fun resetAndRawOnlyFailureCannotResurrectAnOldCleanedCandidate() {
        val output = Dictation.OutputChoices()
        output.complete("old raw", "old cleaned")
        output.reset()
        assertEquals("", output.raw)
        assertNull(output.cleaned)
        assertEquals("", output.selected)
        assertNull(output.select(raw = false, busy = false))
        output.complete("retained after error", null)
        assertTrue(output.isRaw)
        assertEquals("retained after error", output.selected)
        assertNull(output.select(raw = false, busy = false))
        assertEquals("retained after error", output.selected)
        output.complete("new raw", "new cleaned")
        assertEquals("new cleaned", output.selected)
        assertEquals("new raw", output.select(raw = true, busy = false))
    }

    @Test fun missingRawCandidateCannotReplaceTheCleanedOutputWithBlankText() {
        val output = Dictation.OutputChoices()
        output.complete(" \n", "cleaned")
        assertNull(output.select(raw = true, busy = false))
        assertEquals("cleaned", output.selected)
        assertFalse(output.isRaw)
    }
}
