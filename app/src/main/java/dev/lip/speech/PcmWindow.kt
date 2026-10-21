package dev.lip.speech

/** Bounded PCM16LE collection; snapshots are caller-owned copies, never guessed text. */
class PcmWindow {
    private val samples = FloatArray(16_000 * 30)
    private var count = 0
    private var lowByte = -1
    private var startSample = 0L
    private var ended = false

    class Window internal constructor(
        val samples: FloatArray,
        val startSample: Long,
        internal val owner: PcmWindow,
    )

    @Synchronized fun append(packet: ByteArray, length: Int = packet.size) {
        check(!ended) { "PCM capture has ended" }
        require(length in 0..packet.size) { "Invalid PCM packet length" }
        val incoming = (length.toLong() + if (lowByte < 0) 0 else 1) / 2
        check(incoming <= samples.size - count) {
            "Speech reached the 30-second audio window without a safe segment boundary. Stop capture and retain the transcript."
        }
        for (index in 0 until length) {
            if (lowByte < 0) lowByte = packet[index].toInt() and 255
            else {
                samples[count++] = (lowByte or (packet[index].toInt() shl 8)).toShort().toFloat() / 32768f
                lowByte = -1
            }
        }
    }

    @Synchronized fun snapshot(): Window? =
        if (ended || count == 0) null else Window(samples.copyOf(count), startSample, this)

    /** Only pass an observed ASR segment end; the retained tail is uncommitted overlap. */
    @Synchronized fun commit(window: Window, observedSegmentEndMs: Long) {
        verify(window)
        require(observedSegmentEndMs in 1..30_000) { "Invalid speech segment boundary" }
        val boundary = (observedSegmentEndMs * 16).toInt()
        require(boundary <= window.samples.size - LOOKAHEAD) { "Speech segment has no safe lookahead" }
        advance(boundary)
    }

    /** Caller must verify empty output from ASR or exact-zero proof; zeros do not establish microphone/VAD silence. */
    @Synchronized fun consumeVerifiedSilence(window: Window) {
        verify(window)
        require(window.samples.size > LOOKAHEAD) { "Silent window has no safe lookahead" }
        require(window.samples.indices.all { window.samples[it] == 0f && samples[it] == 0f }) {
            "Silence discard requires unchanged, finite, exact-zero PCM"
        }
        advance(window.samples.size - LOOKAHEAD)
    }

    @Synchronized fun finish(): Window? {
        if (ended) return null
        check(lowByte < 0) { "PCM ended with an incomplete sample; transcript retained" }
        val remainder = snapshot()
        cancel()
        return remainder
    }

    @Synchronized fun cancel() {
        ended = true
        samples.fill(0f)
        count = 0
        lowByte = -1
    }

    private fun verify(window: Window) {
        check(!ended) { "PCM capture has ended" }
        require(window.owner === this) { "PCM window belongs to another capture" }
        check(window.startSample == startSample) { "PCM window is stale; ignore its late result" }
        require(window.samples.size <= count) { "Invalid PCM window length" }
    }

    private fun advance(boundary: Int) {
        samples.copyInto(samples, 0, boundary, count)
        val previous = count
        count -= boundary
        startSample += boundary
        samples.fill(0f, count, previous)
    }

    private companion object { const val LOOKAHEAD = 8_000 } // 500 ms at 16 kHz.
}
