package dev.lip.speech

import org.junit.Assert.*
import org.junit.Test

class PcmWindowTest {
    @Test fun oddPacketBoundariesPreserveEverySignedLittleEndianSample() {
        val window = PcmWindow()
        val bytes = byteArrayOf(0, 0x80.toByte(), 0xFF.toByte(), 0x7F, 0, 0)
        window.append(bytes.copyOfRange(0, 1))
        window.append(bytes.copyOfRange(1, 3))
        window.append(bytes.copyOfRange(3, 5))
        window.append(bytes.copyOfRange(5, 6))
        assertArrayEquals(floatArrayOf(-1f, 32767f / 32768f, 0f), window.snapshot()!!.samples, 0f)
        assertEquals(0L, window.snapshot()!!.startSample)
    }

    @Test fun snapshotsAreCopiedAndPacketLengthExcludesUnusedBytes() {
        val window = PcmWindow()
        window.append(byteArrayOf(1, 0, 2, 0), 2)
        window.snapshot()!!.samples[0] = 42f
        assertArrayEquals(floatArrayOf(1f / 32768f), window.snapshot()!!.samples, 0f)
    }

    @Test fun observedBoundaryKeepsUncommittedOverlapAndRejectsStaleOrForeignWindows() {
        val window = PcmWindow()
        window.append(pcm(80_000, 1))
        val first = window.snapshot()!!
        window.commit(first, 4_500)
        window.append(pcm(16_000, 2))
        val second = window.snapshot()!!
        assertEquals(72_000L, second.startSample)
        assertEquals(24_000, second.samples.size)
        assertEquals(1f / 32768f, second.samples[7_999], 0f)
        assertEquals(2f / 32768f, second.samples[8_000], 0f)
        assertThrows(IllegalStateException::class.java) { window.commit(first, 1_000) }
        assertThrows(IllegalArgumentException::class.java) { PcmWindow().commit(second, 1) }
    }

    @Test fun unsafeBoundaryNeverCutsAtWindowEndOrInventsProgress() {
        val window = PcmWindow()
        window.append(pcm(48_000))
        val snapshot = window.snapshot()!!
        for (endMs in listOf(-1L, 0L, 2_501L, 3_000L, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { window.commit(snapshot, endMs) }
        }
        assertEquals(48_000, window.snapshot()!!.samples.size)
        assertEquals(0L, window.snapshot()!!.startSample)
    }

    @Test fun fullBufferRejectsWholePacketWithoutLosingPendingByteOrAcceptedAudio() {
        val window = PcmWindow()
        window.append(pcm(479_999))
        window.append(byteArrayOf(0))
        assertThrows(IllegalStateException::class.java) { window.append(byteArrayOf(0, 1, 0, 2)) }
        assertEquals(479_999, window.snapshot()!!.samples.size)
        window.append(byteArrayOf(0x80.toByte()))
        val full = window.snapshot()!!
        assertEquals(480_000, full.samples.size)
        assertEquals(-1f, full.samples.last(), 0f)
        assertThrows(IllegalStateException::class.java) { window.append(byteArrayOf(1, 0)) }
        assertArrayEquals(full.samples, window.snapshot()!!.samples, 0f)
    }

    @Test fun finishDrainsUncommittedRemainderOnceAndStopsAcceptingAudio() {
        val window = PcmWindow()
        window.append(pcm(48_000, 1))
        val before = window.snapshot()!!
        window.commit(before, 2_500)
        val final = window.finish()!!
        assertEquals(40_000L, final.startSample)
        assertEquals(8_000, final.samples.size)
        assertNull(window.finish())
        assertNull(window.snapshot())
        assertThrows(IllegalStateException::class.java) { window.append(byteArrayOf(0, 0)) }
        assertThrows(IllegalStateException::class.java) { window.commit(before, 1) }
    }

    @Test fun danglingFinalByteFailsWithoutPaddingOrDroppingIt() {
        val window = PcmWindow()
        window.append(byteArrayOf(1, 0, 0xFF.toByte()))
        assertThrows(IllegalStateException::class.java) { window.finish() }
        assertArrayEquals(floatArrayOf(1f / 32768f), window.snapshot()!!.samples, 0f)
        window.append(byteArrayOf(0x7F))
        assertArrayEquals(floatArrayOf(1f / 32768f, 32767f / 32768f), window.finish()!!.samples, 0f)
    }

    @Test fun cancelClearsSamplesAndOddByteWithoutProducingAFinalWindow() {
        val window = PcmWindow()
        window.append(byteArrayOf(1, 0, 2))
        window.cancel(); window.cancel()
        assertNull(window.snapshot())
        assertNull(window.finish())
        assertThrows(IllegalStateException::class.java) { window.append(byteArrayOf(0)) }
    }

    @Test fun verifiedZeroWindowRetainsLookaheadAndNewlyArrivingSpeech() {
        val window = PcmWindow()
        window.append(pcm(32_000))
        val silence = window.snapshot()!!
        window.append(pcm(16_000, 1))
        window.consumeVerifiedSilence(silence)
        val next = window.snapshot()!!
        assertEquals(24_000L, next.startSample)
        assertEquals(24_000, next.samples.size)
        assertEquals(0f, next.samples[7_999], 0f)
        assertEquals(1f / 32768f, next.samples[8_000], 0f)
    }

    @Test fun silenceDiscardRequiresFiniteExactZerosAndCannotTrustMutatedSnapshot() {
        val window = PcmWindow()
        window.append(pcm(16_000))
        var snapshot = window.snapshot()!!
        for (notZero in listOf(1f / 32768f, Float.NaN, Float.POSITIVE_INFINITY)) {
            snapshot.samples[0] = notZero
            assertThrows(IllegalArgumentException::class.java) { window.consumeVerifiedSilence(snapshot) }
        }
        window.append(pcm(1, 1))
        snapshot = window.snapshot()!!
        snapshot.samples.fill(0f)
        assertThrows(IllegalArgumentException::class.java) { window.consumeVerifiedSilence(snapshot) }
        assertEquals(16_001, window.snapshot()!!.samples.size)
    }

    private fun pcm(samples: Int, value: Int = 0) = ByteArray(samples * 2) { index ->
        if (index % 2 == 0) value.toByte() else (value shr 8).toByte()
    }
}
