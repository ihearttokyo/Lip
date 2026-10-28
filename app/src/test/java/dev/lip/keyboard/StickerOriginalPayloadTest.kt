package dev.lip.keyboard

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

/** Arbitrary self-authored bytes test immutable binding, NOT compressed-image decoding or native store loading. */
class StickerOriginalPayloadTest {
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun data(size:Int=73)=ByteArray(size){(it*17+3).toByte()}
    private fun asset(bytes:ByteArray,mime:String="image/png",length:Int=bytes.size,sha:String=hash(bytes))=StickerAssetIdentity(
        "00000000-0000-0000-0000-000000000001","a".repeat(64),"synthetic-face",sha,mime,length)
    private fun rejected(work:()->Unit){
        val e=assertThrows(StickerOriginalException::class.java){work()}
        assertEquals(StickerOriginalException.Reason.INTEGRITY,e.reason);assertEquals("Original sticker unavailable.",e.message);assertNull(e.cause)
    }
    @Test fun copiesInputBeforeBindingAndEachOutputIsFresh(){
        val input=data();val original=input.copyOf();val identity=asset(input);val payload=StickerOriginalPayload(identity,input)
        input.fill(0);assertArrayEquals(original,payload.copyBytes());assertSame(identity,payload.asset)
        val first=payload.copyBytes();val second=payload.copyBytes();assertNotSame(first,second)
        first.fill(0);assertArrayEquals(original,second);assertArrayEquals(original,payload.copyBytes())
    }
    @Test fun privateSnapshotMustHaveExactLengthAndHash(){
        val bytes=data()
        rejected{StickerOriginalPayload(asset(bytes,length=bytes.size+1),bytes)}
        rejected{StickerOriginalPayload(asset(bytes,sha="0".repeat(64)),bytes)}
        rejected{StickerOriginalPayload(asset(bytes),bytes.copyOf().also{it[0]=(it[0].toInt() xor 1).toByte()})}
        rejected{StickerOriginalPayload(asset(byteArrayOf(1)),byteArrayOf())}
    }
    @Test fun sourceMimeIdentityIsPreservedWithoutEncodedDecodeOrTranscoding(){
        val bytes=data()
        for(mime in listOf("image/png","image/webp","IMAGE/PNG")){
            val identity=asset(bytes,mime);val payload=StickerOriginalPayload(identity,bytes)
            assertEquals(identity,payload.asset);assertEquals(mime.lowercase(java.util.Locale.ROOT),payload.asset.mime)
            assertArrayEquals(bytes,payload.copyBytes())
        }
        assertThrows(IllegalArgumentException::class.java){asset(bytes,"image/gif")}
        assertThrows(IllegalArgumentException::class.java){asset(bytes,"image/png; charset=utf8")}
    }
    @Test fun minimumAndEightMiBBoundAreExact(){
        val one=byteArrayOf(42);assertArrayEquals(one,StickerOriginalPayload(asset(one),one).copyBytes())
        val max=data(8*1024*1024);val payload=StickerOriginalPayload(asset(max),max)
        assertEquals(max.size,payload.asset.byteLength);assertEquals(hash(max),hash(payload.copyBytes()))
        rejected{StickerOriginalPayload(asset(max),ByteArray(8*1024*1024+1))}
    }
    @Test fun defaultRenderingAndErrorsDoNotExposePrivateIdentityOrBytes(){
        val bytes=data();val identity=asset(bytes);val payload=StickerOriginalPayload(identity,bytes)
        val text=payload.toString();assertEquals("StickerOriginalPayload(redacted)",text)
        for(secret in listOf(identity.commitId,identity.archiveSha256,identity.stickerId,identity.memberSha256,bytes.contentToString()))assertFalse(text.contains(secret))
        rejected{StickerOriginalPayload(identity,byteArrayOf(1))}
    }
}
