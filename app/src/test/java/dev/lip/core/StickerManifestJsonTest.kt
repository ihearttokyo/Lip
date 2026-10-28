package dev.lip.core

import dev.lip.stickers.StickerManifestJson
import dev.lip.stickers.StickerPackException
import org.junit.Assert.*
import org.junit.Test

class StickerManifestJsonTest{
    private fun parse(text:String)=StickerManifestJson.parse(text.toByteArray(),{true})
    private fun reject(text:String,reason:StickerPackException.Reason=StickerPackException.Reason.JSON){
        val e=assertThrows(StickerPackException::class.java){parse(text)};assertEquals(reason,e.reason);assertFalse(e.message!!.contains("private"))
    }
    @Test fun exactIntegersBooleansNullAndValidUnicode(){
        val r=parse(""" {"a":1,"b":-9223372036854775808,"v":[true,false,null],"s":"\uD83D\uDE00","literal":"😀"} """)
        assertEquals(1L,r["a"]);assertEquals(Long.MIN_VALUE,r["b"]);assertEquals(listOf(true,false,null),r["v"]);assertEquals("😀",r["s"]);assertEquals(r["literal"],r["s"])
    }
    @Test fun duplicateKeysIncludingEscapedEquivalentRefused(){
        reject("""{"a":1,"a":2}""");reject("""{"a":null,"\u0061":0}""");reject("""{"parent":{"x":1,"x":1}}""")
    }
    @Test fun strictNumberProfileAndTrailingTokens(){
        for(v in listOf("01","-01","+1","1.","1.0","1e0","NaN","Infinity","-","--1","0x1","true false"))reject("{\"private\":$v}")
        reject("{\"x\":9223372036854775808}",StickerPackException.Reason.LIMIT)
        reject("{} {}");reject("{}private");reject("[]")
    }
    @Test fun strictPunctuationWhitespaceAndComments(){
        for(v in listOf(""," ","{","{a:1}","{'a':1}","{\"a\":1,}","{\"a\":[1,]}","/*private*/{}","{\"a\":1//private\n}","\uFEFF{}","\u00a0{}"))reject(v)
    }
    @Test fun malformedUtf8AndSurrogatesRejected(){
        for(b in listOf(byteArrayOf(0xc0.toByte(),0xaf.toByte()),byteArrayOf(0xed.toByte(),0xa0.toByte(),0x80.toByte()),byteArrayOf(0xe2.toByte(),0x82.toByte()))){
            assertEquals(StickerPackException.Reason.JSON,assertThrows(StickerPackException::class.java){StickerManifestJson.parse(b,{true})}.reason)
        }
        for(v in listOf("""\uD800""","""\uDC00""","""\uD800\u0041""","""\uD800x""","""\uXX00""","""\u００61""","""\q"""))reject("{\"x\":\"$v\"}")
        reject("{\"x\":\"line\nprivate\"}")
    }
    @Test fun byteDepthAndNodeCapsBeforeUnboundedWork(){
        reject("{\"x\":\""+"x".repeat(65_536)+"\"}",StickerPackException.Reason.LIMIT)
        reject("{\"x\":"+"[".repeat(13)+"0"+"]".repeat(13)+"}",StickerPackException.Reason.LIMIT)
        reject("{\"x\":["+List(8192){"0"}.joinToString(",")+"]}",StickerPackException.Reason.LIMIT)
    }
    @Test fun cooperativeCancellationIsStatic(){
        var checks=0
        val e=assertThrows(StickerPackException::class.java){StickerManifestJson.parse(("{\"private\":\""+"x".repeat(4096)+"\"}").toByteArray()){++checks<8}}
        assertEquals(StickerPackException.Reason.CANCELED,e.reason);assertFalse(e.message!!.contains("private"));assertTrue(checks>=8)
    }
}
