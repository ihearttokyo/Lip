package dev.lip.stickers

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Ingress only: members are UNTRUSTED, never installed, extracted or decoded. */
class StickerArchiveMember internal constructor(val path: String, private val payload: ByteArray) {
    val size: Int get() = payload.size
    val sha256: String = MessageDigest.getInstance("SHA-256").digest(payload)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    fun bytes(): ByteArray = payload.copyOf()
}

class StickerArchiveException(val reason: Reason) : IllegalArgumentException("Sticker archive refused: ${reason.name}.") {
    enum class Reason { FORMAT, LIMIT, PATH, INTEGRITY, CANCELED, SOURCE }
}

object StickerArchiveReader {
    const val MAX_SOURCE_BYTES = 16 * 1024 * 1024
    const val MAX_EXPANDED_BYTES = 64 * 1024 * 1024
    const val MAX_MEMBER_BYTES = 8 * 1024 * 1024
    const val MAX_MEMBERS = 128
    private fun refuse(reason: StickerArchiveException.Reason): Nothing = throw StickerArchiveException(reason)
    private fun checkLive(live: () -> Boolean) { if (!live()) refuse(StickerArchiveException.Reason.CANCELED) }

    /** Does not close source. The caller owns its URI grant/descriptor/lifetime. */
    fun read(source: InputStream, live: () -> Boolean = { true }): List<StickerArchiveMember> {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        try {
            while (true) {
                checkLive(live)
                val n = source.read(buffer, 0, minOf(buffer.size, MAX_SOURCE_BYTES - out.size() + 1))
                checkLive(live)
                if (n == -1) break
                if (n == 0) refuse(StickerArchiveException.Reason.SOURCE) // no stalled busy-loop
                if (n > MAX_SOURCE_BYTES - out.size()) refuse(StickerArchiveException.Reason.LIMIT)
                out.write(buffer, 0, n)
            }
        } catch (e: StickerArchiveException) { throw e }
        catch (_: java.io.IOException) { refuse(StickerArchiveException.Reason.SOURCE) }
        catch (_: java.io.UncheckedIOException) { refuse(StickerArchiveException.Reason.SOURCE) }
        catch (_: SecurityException) { refuse(StickerArchiveException.Reason.SOURCE) }
        return parse(out.toByteArray(), live)
    }

    private data class Entry(val name: String, val encodedName: ByteArray, val flags: Int,
        val method: Int, val crc: Long, val compressed: Int, val size: Int, val offset: Int)

    private class Bytes(val data: ByteArray) {
        fun span(at: Int, length: Int) {
            if (at < 0 || length < 0 || at > data.size - length) refuse(StickerArchiveException.Reason.FORMAT)
        }
        fun u16(at: Int): Int { span(at, 2); return (data[at].toInt() and 255) or ((data[at+1].toInt() and 255) shl 8) }
        fun u32(at: Int): Long { span(at, 4); return (0..3).fold(0L) { n, i -> n or ((data[at+i].toLong() and 255L) shl (8*i)) } }
        fun small(at: Int): Int { val n=u32(at); if (n>Int.MAX_VALUE) refuse(StickerArchiveException.Reason.LIMIT); return n.toInt() }
        fun signature(at: Int, value: Long) { if (u32(at)!=value) refuse(StickerArchiveException.Reason.FORMAT) }
    }

    private fun path(raw: ByteArray): String {
        if (raw.isEmpty() || raw.size > 240) refuse(StickerArchiveException.Reason.PATH)
        val value = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString() }
            catch (_: java.nio.charset.CharacterCodingException) { refuse(StickerArchiveException.Reason.PATH) }
        if (value.any { it=='\\' || it==':' || it< ' ' || it in '\u007f'..'\u009f' ||
            it=='\u061c' || it in '\u200e'..'\u200f' || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' }) refuse(StickerArchiveException.Reason.PATH)
        val parts=value.split('/')
        if (parts.size>8 || parts.any { it.isEmpty() || it=="." || it==".." || it.endsWith('.') || it.endsWith(' ') }) refuse(StickerArchiveException.Reason.PATH)
        if (value.substringAfterLast('.', "").lowercase(Locale.ROOT) !in setOf("json","webp","png","txt")) refuse(StickerArchiveException.Reason.PATH)
        return value
    }

    private fun parse(data: ByteArray, live: () -> Boolean): List<StickerArchiveMember> {
        checkLive(live)
        val b=Bytes(data)
        val end=data.size-22
        b.signature(end, 0x06054b50L)
        if (b.u16(end+4)!=0 || b.u16(end+6)!=0 || b.u16(end+20)!=0 || b.u16(end+8)!=b.u16(end+10)) refuse(StickerArchiveException.Reason.FORMAT)
        val count=b.u16(end+10)
        if (count !in 1..MAX_MEMBERS) refuse(StickerArchiveException.Reason.LIMIT)
        val directorySize=b.small(end+12); val directory=b.small(end+16)
        if (directory>end || directorySize!=end-directory) refuse(StickerArchiveException.Reason.FORMAT)
        val entries=ArrayList<Entry>(); val names=HashSet<String>(); var at=directory; var total=0L
        repeat(count) {
            checkLive(live); b.span(at,46); b.signature(at,0x02014b50L)
            val needed=b.u16(at+6); val flags=b.u16(at+8); val method=b.u16(at+10)
            // Standard stored/deflate, UTF8 and data descriptor only. Refuse ZIP64/encryption.
            if (needed>20 || flags and (0x800 or 8).inv()!=0 || method !in listOf(0,8)) refuse(StickerArchiveException.Reason.FORMAT)
            val compressed=b.small(at+20); val size=b.small(at+24)
            if (size>MAX_MEMBER_BYTES || compressed>MAX_SOURCE_BYTES) refuse(StickerArchiveException.Reason.LIMIT)
            total+=size.toLong(); if (total>MAX_EXPANDED_BYTES) refuse(StickerArchiveException.Reason.LIMIT)
            val length=b.u16(at+28); val extra=b.u16(at+30); val comment=b.u16(at+32)
            val mode=(b.u32(at+38) ushr 16).toInt() and 0xf000
            if (extra!=0 || comment!=0 || b.u16(at+34)!=0 || mode !in listOf(0,0x8000) || b.u32(at+38) and 0x18L !=0L) refuse(StickerArchiveException.Reason.FORMAT)
            b.span(at+46,length)
            if (at+46+length> end) refuse(StickerArchiveException.Reason.FORMAT)
            val encoded=data.copyOfRange(at+46,at+46+length); val name=path(encoded)
            if (flags and 0x800==0 && encoded.any { it<0 }) refuse(StickerArchiveException.Reason.FORMAT)
            val folded=Normalizer.normalize(name.uppercase(Locale.ROOT).lowercase(Locale.ROOT),Normalizer.Form.NFC)
            if (names.any { it==folded || it.startsWith("$folded/") || folded.startsWith("$it/") }) refuse(StickerArchiveException.Reason.PATH)
            names.add(folded)
            entries+=Entry(name,encoded,flags,method,b.u32(at+16),compressed,size,b.small(at+42))
            at+=46+length
        }
        if (at!=end) refuse(StickerArchiveException.Reason.FORMAT)
        val result=ArrayList<StickerArchiveMember>(); var cursor=0
        for ((index,e) in entries.withIndex()) {
            checkLive(live)
            if (e.offset!=cursor) refuse(StickerArchiveException.Reason.FORMAT)
            b.span(cursor,30); b.signature(cursor,0x04034b50L)
            if (b.u16(cursor+4)>20 || b.u16(cursor+6)!=e.flags || b.u16(cursor+8)!=e.method || b.u16(cursor+26)!=e.encodedName.size || b.u16(cursor+28)!=0) refuse(StickerArchiveException.Reason.FORMAT)
            val descriptor=e.flags and 8!=0
            if (!descriptor && (b.u32(cursor+14)!=e.crc || b.u32(cursor+18)!=e.compressed.toLong() || b.u32(cursor+22)!=e.size.toLong())) refuse(StickerArchiveException.Reason.INTEGRITY)
            if (descriptor && (b.u32(cursor+14)!=0L || b.u32(cursor+18)!=0L || b.u32(cursor+22)!=0L)) refuse(StickerArchiveException.Reason.FORMAT)
            val payload=cursor+30+e.encodedName.size
            b.span(cursor+30,e.encodedName.size)
            if (!data.copyOfRange(cursor+30,payload).contentEquals(e.encodedName)) refuse(StickerArchiveException.Reason.INTEGRITY)
            b.span(payload,e.compressed)
            if (payload>directory || e.compressed>directory-payload) refuse(StickerArchiveException.Reason.FORMAT)
            val bytes = if (e.method==0) {
                if (e.compressed!=e.size) refuse(StickerArchiveException.Reason.INTEGRITY)
                data.copyOfRange(payload,payload+e.size)
            } else inflate(data,payload,e,live)
            if (CRC32().apply { update(bytes) }.value!=e.crc) refuse(StickerArchiveException.Reason.INTEGRITY)
            cursor=payload+e.compressed
            if (descriptor) {
                // Descriptor length comes from the next record boundary, not CRC bytes:
                // an unsigned descriptor CRC may itself equal the signature.
                val boundary=entries.getOrNull(index+1)?.offset ?: directory
                when (boundary-cursor) {
                    16 -> { b.signature(cursor,0x08074b50L); cursor+=4 }
                    12 -> Unit
                    else -> refuse(StickerArchiveException.Reason.FORMAT)
                }
                b.span(cursor,12)
                if (b.u32(cursor)!=e.crc || b.u32(cursor+4)!=e.compressed.toLong() || b.u32(cursor+8)!=e.size.toLong()) refuse(StickerArchiveException.Reason.INTEGRITY)
                cursor+=12
            }
            if (cursor>directory) refuse(StickerArchiveException.Reason.FORMAT)
            result+=StickerArchiveMember(e.name,bytes)
        }
        if (cursor!=directory) refuse(StickerArchiveException.Reason.FORMAT)
        checkLive(live)
        return result.toList()
    }

    private fun inflate(data: ByteArray, start: Int, e: Entry, live: () -> Boolean): ByteArray {
        val decoder=Inflater(true)
        try {
            decoder.setInput(data,start,e.compressed)
            val result=ByteArray(e.size); var at=0; val probe=ByteArray(1)
            while (!decoder.finished()) {
                checkLive(live)
                val n=if (at<result.size) decoder.inflate(result,at,minOf(32*1024,result.size-at)) else decoder.inflate(probe)
                if (at==result.size && n!=0) refuse(StickerArchiveException.Reason.INTEGRITY)
                at+=n
                if (n==0 && !decoder.finished()) refuse(StickerArchiveException.Reason.INTEGRITY)
            }
            if (at!=e.size || decoder.remaining!=0) refuse(StickerArchiveException.Reason.INTEGRITY)
            return result
        } catch (_: DataFormatException) { refuse(StickerArchiveException.Reason.INTEGRITY) }
        finally { decoder.end() }
    }
}
