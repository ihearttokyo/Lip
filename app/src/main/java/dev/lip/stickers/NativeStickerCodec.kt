package dev.lip.stickers

/**
 * Internal isolated-worker transport only. The caller must bind/check the source
 * hash and metadata, and copy rgba before exposing a decoded-preview proof.
 * Code: 0 success, 1 format, 2 configured limit, 3 codec, 4 resource, 5 state.
 * Failure metadata/pixels are empty; peakRssKb may still report the worker peak.
 */
internal data class NativeStickerCodecResult(
    val code:Int,
    val sourceWidth:Int,
    val sourceHeight:Int,
    val width:Int,
    val height:Int,
    val frames:Int,
    val durationMs:Long,
    val peakRssKb:Long,
    val rgba:ByteArray,
)

/** Never call with compressed input in the IME/UI process; JNI enforces isolation. */
internal object NativeStickerCodec {
    init { System.loadLibrary("lip_sticker_codec") }
    external fun decode(bytes:ByteArray):NativeStickerCodecResult
}
