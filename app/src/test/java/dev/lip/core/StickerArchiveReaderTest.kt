package dev.lip.core

import dev.lip.stickers.StickerArchiveReader as Reader
import dev.lip.stickers.StickerArchiveException
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StickerArchiveReaderTest {
    private val payload="original synthetic sticker metadata".toByteArray()
    private fun archive(vararg names:String, stored:Boolean=false, bytes:ByteArray=payload):ByteArray {
        val out=ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for (name in names) {
                val e=ZipEntry(name); e.time=1_700_000_000_000L
                if (stored) { e.method=ZipEntry.STORED; e.size=bytes.size.toLong(); e.compressedSize=e.size; e.crc=CRC32().apply { update(bytes) }.value }
                z.putNextEntry(e); z.write(bytes); z.closeEntry()
            }
        }
        return out.toByteArray()
    }
    private fun read(bytes:ByteArray)=Reader.read(ByteArrayInputStream(bytes))
    private fun reject(bytes:ByteArray,reason:StickerArchiveException.Reason?=null) {
        try { read(bytes); fail("untrusted archive accepted") }
        catch(e:StickerArchiveException) { if (reason!=null) assertEquals(reason,e.reason); assertFalse(e.message!!.contains("private")) }
    }
    private fun u16(b:ByteArray,p:Int)=(b[p].toInt() and 255) or ((b[p+1].toInt() and 255) shl 8)
    private fun u32(b:ByteArray,p:Int)=(0..3).fold(0L) { v,i -> v or ((b[p+i].toLong() and 255) shl (8*i)) }
    private fun put(b:ByteArray,p:Int,n:Long,count:Int=4) { repeat(count) { b[p+it]=(n ushr (8*it)).toByte() } }
    private fun central(b:ByteArray)=u32(b,b.size-6).toInt()
    private fun changed(block:(ByteArray,Int)->Unit):ByteArray=archive("face.webp",stored=true).also { block(it,central(it)) }

    @Test fun storedAndDeflateDescriptorAreByteExact() {
        for (stored in listOf(true,false)) {
            val input=archive("contents.json","faces/a.webp",stored=stored)
            val members=read(input); assertEquals(listOf("contents.json","faces/a.webp"),members.map { it.path })
            members.forEach { assertArrayEquals(payload,it.bytes()); assertEquals(payload.size,it.size); assertEquals(64,it.sha256.length) }
            members[0].bytes().fill(0); assertArrayEquals(payload,members[0].bytes())
            assertArrayEquals(input,archive("contents.json","faces/a.webp",stored=stored))
        }
    }
    @Test fun sourceRemainsOpenAndCancellationHasNoResult() {
        var closed=false
        val src=object:ByteArrayInputStream(archive("face.webp")) { override fun close() { closed=true; super.close() } }
        Reader.read(src); assertFalse(closed)
        var checks=0
        try { Reader.read(ByteArrayInputStream(archive("face.webp"))) { ++checks<5 }; fail() }
        catch(e:StickerArchiveException) { assertEquals(StickerArchiveException.Reason.CANCELED,e.reason) }
        assertTrue(checks>=5)
    }
    @Test fun sourceIoAndZeroProgressAreStaticRefusals() {
        for (source in listOf(object:InputStream(){override fun read():Int=throw java.io.IOException("private filename")},
            object:InputStream(){override fun read():Int=0; override fun read(b:ByteArray,o:Int,n:Int)=0})) {
            try { Reader.read(source); fail() } catch(e:StickerArchiveException){assertEquals(StickerArchiveException.Reason.SOURCE,e.reason);assertFalse(e.message!!.contains("private"))}
        }
    }
    @Test fun compressedSourceHasHardReadCeiling() {
        var consumed=0
        val source=object:InputStream(){ override fun read():Int { consumed++;return 0 }; override fun read(b:ByteArray,o:Int,n:Int):Int { b.fill(0,o,o+n);consumed+=n;return n } }
        try { Reader.read(source); fail() } catch(e:StickerArchiveException){assertEquals(StickerArchiveException.Reason.LIMIT,e.reason)}
        assertEquals(Reader.MAX_SOURCE_BYTES+1,consumed)
    }
    @Test fun unsafePathsAndNestedPayloadNamesRefuse() {
        for (path in listOf("../private.webp","/private.webp","a//b.webp","./a.webp","a/../b.webp","C:private.webp","a\\private.webp",
            "a./b.webp","a /b.webp","x\u0000.webp","x\u202e.webp","x\u2067.webp","pack.zip","pack.apk","pack.exe","image.svg","a/b/c/d/e/f/g/h/i.webp","x".repeat(240)+".webp")) reject(archive(path),StickerArchiveException.Reason.PATH)
    }
    @Test fun canonicalCaseAndImplicitDirectoryCollisionsRefuse() {
        for (names in listOf(arrayOf("A.webp","a.webp"),arrayOf("é.webp","e\u0301.webp"),arrayOf("Straße.webp","STRASSE.webp"),arrayOf("a.webp","A.webp/b.webp"),arrayOf("a.webp/b.webp","A.webp"))) reject(archive(*names),StickerArchiveException.Reason.PATH)
    }
    @Test fun invalidUtf8AndLegacyNonAsciiRefuse() {
        reject(changed { b,c -> b[30]=0xff.toByte(); b[c+46]=0xff.toByte() },StickerArchiveException.Reason.PATH)
        val b=archive("é.webp",stored=true); val c=central(b); put(b,6,0,2);put(b,c+8,0,2); reject(b,StickerArchiveException.Reason.FORMAT)
    }
    @Test fun crcPayloadAndLocalMetadataDisagreementsRefuse() {
        reject(changed { b,_ -> b[30+"face.webp".length]=(b[30+"face.webp".length].toInt() xor 1).toByte() },StickerArchiveException.Reason.INTEGRITY)
        reject(changed { b,c -> put(b,c+16,1) },StickerArchiveException.Reason.INTEGRITY)
        reject(changed { b,_ -> put(b,18,1) },StickerArchiveException.Reason.INTEGRITY)
        reject(changed { b,_ -> b[30]='g'.code.toByte() },StickerArchiveException.Reason.INTEGRITY)
        reject(changed { b,_ -> put(b,8,8,2) },StickerArchiveException.Reason.FORMAT)
    }
    @Test fun unsupportedZipDialectsRefuseBeforePayload() {
        for (mutate in listOf<(ByteArray,Int)->Unit>(
            {b,c->put(b,c+8,0x801,2)}, // encrypted
            {b,c->put(b,c+8,0x840,2)}, // strong encryption
            {b,c->put(b,c+6,45,2)}, // zip64 feature version
            {b,c->put(b,c+10,99,2)}, // unsupported method
            {b,c->put(b,c+30,1,2)}, // central extras
            {b,c->put(b,c+32,1,2)}, // entry comment
            {b,c->put(b,c+34,1,2)}, // other disk
            {b,c->put(b,c+38,0xa1ff0000L)}, // symlink
            {b,c->put(b,c+38,0x41ff0000L)}, // directory
            {b,c->put(b,c+38,0x21ff0000L)}, // character device
            {b,c->put(b,c+38,0x10)}, // DOS directory
            {b,_->put(b,b.size-18,1,2)}, // EOCD split
            {b,_->put(b,b.size-2,1,2)}, // ZIP comment
            {b,_->put(b,28,1,2)} // local extras
        )) reject(changed(mutate))
    }
    @Test fun prefixesTrailersOffsetsAndTruncationRefuse() {
        val b=archive("face.webp",stored=true)
        for (cut in listOf(0,1,3,20,29,b.size-1,b.size-22)) reject(b.copyOf(cut))
        reject(b+byteArrayOf(0));reject(byteArrayOf(0)+b)
        reject(changed { a,c->put(a,c+42,1) })
        reject(changed { a,_->put(a,a.size-6,0) })
        reject(changed { a,c->put(a,c+20,0x7fffffffL) })
    }
    @Test fun descriptorsAndDeflateMustConsumeExactly() {
        val b=archive("face.webp"); val c=central(b)
        reject(b.copyOf().also { put(it,c-12,1) },StickerArchiveException.Reason.INTEGRITY)
        reject(b.copyOf().also { put(it,14,1) },StickerArchiveException.Reason.FORMAT)
        reject(b.copyOf().also { it[30+"face.webp".length]=0x7f })
        // Valid unsigned standard descriptor: remove its 4-byte signature and rebind directory offset.
        assertEquals(0x08074b50L,u32(b,c-16))
        val unsigned=b.copyOfRange(0,c-16)+b.copyOfRange(c-12,b.size)
        put(unsigned,unsigned.size-6,(c-4).toLong());assertArrayEquals(payload,read(unsigned).single().bytes())
    }
    @Test fun declaredAndActualExpansionLimitsRefuse() {
        reject(changed { b,c->put(b,c+24,(Reader.MAX_MEMBER_BYTES+1).toLong()) },StickerArchiveException.Reason.LIMIT)
        val tiny=archive("face.webp",bytes=ByteArray(100000))
        // Lie consistently in central/local/descriptor about a shorter expanded output.
        val c=central(tiny);put(tiny,c+24,1);put(tiny,c-4,1);reject(tiny,StickerArchiveException.Reason.INTEGRITY)
        val nine=archive(*(1..9).map { "$it.webp" }.toTypedArray(),bytes=ByteArray(Reader.MAX_MEMBER_BYTES))
        reject(nine,StickerArchiveException.Reason.LIMIT)
    }
    @Test fun exactMemberAndCountBoundariesAreSupported() {
        assertEquals(Reader.MAX_MEMBER_BYTES,read(archive("face.webp",bytes=ByteArray(Reader.MAX_MEMBER_BYTES))).single().size)
        val names=(1..Reader.MAX_MEMBERS).map { "$it.webp" }.toTypedArray()
        assertEquals(Reader.MAX_MEMBERS,read(archive(*names,bytes=byteArrayOf())).size)
        reject(archive(*names,"extra.webp"),StickerArchiveException.Reason.LIMIT)
    }
    @Test fun orderingOverlapAndDuplicateLocalRecordsRefuse() {
        val b=archive("a.webp","b.webp",stored=true);val c=central(b);val next=c+46+"a.webp".length
        put(b,next+42,0);reject(b)
        reject(archive())
    }
    @Test fun cancellationDuringExpansionAndSourceRefusalNeverClosesOwner() {
        val input=archive("large.webp",bytes=ByteArray(Reader.MAX_MEMBER_BYTES))
        var calls=0
        try { Reader.read(ByteArrayInputStream(input)) { ++calls<12 };fail() }
        catch(e:StickerArchiveException){assertEquals(StickerArchiveException.Reason.CANCELED,e.reason)}
        assertEquals(12,calls) // beyond source, directory and local-header checks, within inflate loop
        var closed=false
        val source=object:ByteArrayInputStream(byteArrayOf(0)){override fun close(){closed=true;super.close()}}
        try { Reader.read(source);fail() } catch(_:StickerArchiveException){}
        assertFalse(closed)
    }
    @Test fun cumulativeExpansionBoundaryAndDeclaredOversizeAreDistinct() {
        val eight=archive(*(1..8).map { "$it.webp" }.toTypedArray(),bytes=ByteArray(Reader.MAX_MEMBER_BYTES))
        val members=read(eight)
        assertEquals(Reader.MAX_EXPANDED_BYTES,members.sumOf{it.size})
        reject(changed { b,c -> put(b,c+24,0xffffffffL) },StickerArchiveException.Reason.LIMIT)
        reject(changed { b,c -> put(b,c+20,0xffffffffL) },StickerArchiveException.Reason.LIMIT)
    }
    @Test fun readonlyInputAndUntrustedOutputRemainSeparated() {
        val input=archive("a.png",stored=true);val baseline=input.copyOf()
        val members=read(input);assertArrayEquals(baseline,input)
        input.fill(0);assertArrayEquals(payload,members.single().bytes())
        // Suffix alone is explicitly NOT image validation; later stages must refuse this fake PNG.
        assertArrayEquals(payload,members.single().bytes())
    }

    @Test fun boundedDeterministicMutationCorpusHasNoUncheckedFailureOrFalseContent() {
        val baselines=listOf(archive("face.webp",stored=true),archive("face.webp"))
        var accepted=0;var refused=0
        for (base in baselines) for (offset in base.indices) for (mask in listOf(1,8,255)) {
            val variant=base.copyOf();variant[offset]=(variant[offset].toInt() xor mask).toByte()
            val members=try { read(variant) } catch(_:StickerArchiveException){refused++;continue}
            // An independent standard ZIP reader must agree on every accepted local record.
            java.util.zip.ZipInputStream(ByteArrayInputStream(variant)).use { z ->
                for (member in members) {
                    assertEquals(member.path,z.nextEntry!!.name)
                    assertArrayEquals(member.bytes(),z.readBytes())
                    z.closeEntry()
                }
                assertNull(z.nextEntry)
            }
            accepted++
        }
        assertTrue(accepted>0);assertTrue(refused>0)
        println("Sticker ZIP deterministic mutation corpus: $accepted accepted with independent content agreement; $refused static refusals")
    }

    @Test fun unsignedDescriptorCrcEqualToSignatureMustStillWork() {
        val special=byteArrayOf(0xac.toByte(),0x0a,0x7a,0xd5.toByte())
        assertEquals(0x08074b50L,CRC32().apply{update(special)}.value)
        val signed=archive("collision.webp",bytes=special);val c=central(signed)
        assertArrayEquals(special,read(signed).single().bytes())
        assertEquals(0x08074b50L,u32(signed,c-16))
        val unsigned=signed.copyOfRange(0,c-16)+signed.copyOfRange(c-12,signed.size)
        put(unsigned,unsigned.size-6,(c-4).toLong())
        assertArrayEquals(special,read(unsigned).single().bytes())
    }
    @Test fun uncheckedSourceFailuresNeverExposeTransportText() {
        for (error in listOf(SecurityException("private filename"),java.io.UncheckedIOException(java.io.IOException("private filename")))) {
            val source=object:InputStream(){override fun read():Int=throw error}
            try { Reader.read(source);fail() }
            catch(e:StickerArchiveException){assertEquals(StickerArchiveException.Reason.SOURCE,e.reason);assertFalse(e.message!!.contains("private"))}
        }
    }

}
