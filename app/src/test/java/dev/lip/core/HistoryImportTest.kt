package dev.lip.core

import dev.lip.*
import org.junit.Assert.*
import org.junit.Test

class HistoryImportTest {
    private val fixture = """{"schema":"dev.lip.dictation-history","version":1,"sourcePlatform":"macos","record":{"id":"test","createdAtUnixMs":1700000000125,"original":"か\u3099 你好\n🎙️","output":"が 你好\n🎙️","language":null,"model":null,"modelDetails":null,"legacyCloudCleanup":false}}"""
    private fun decode(text: String = fixture) = HistoryImport.decode(text.toByteArray(Charsets.UTF_8))
    private fun refuses(text: String) { assertTrue("Invalid fixture accepted",runCatching { decode(text) }.isFailure) }
    @Test fun macGoldenPreservesUnknownMetadataAndExactUnicode() {
        val d=decode(); assertEquals("macos",d.sourcePlatform);assertEquals("test",d.record.id)
        assertEquals("か\u3099 你好\n🎙️",d.record.original);assertEquals("が 你好\n🎙️",d.record.output)
        assertNull(d.record.language);assertNull(d.record.model);assertNull(d.record.modelDetails);assertFalse(d.record.legacyCloudCleanup)
        assertEquals(1700000000125L,d.record.createdAtUnixMs)
    }
    @Test fun androidActualExporterRoundTripPreservesEveryField() {
        val entry=Transcript("id",0,"\u0000\b\u000c\n\r\t /\\\"🎙️","done","en-US",true,"clean","fixture")
        val decoded=HistoryImport.decode(HistoryTransfer.encode(entry))
        assertEquals("android",decoded.sourcePlatform)
        assertEquals(PortableHistoryRecord(entry.id,entry.time,entry.raw,entry.clean,entry.language,entry.model,entry.modelDetails,entry.usedChatGpt),decoded.record)
    }
    @Test fun fieldOrderWhitespaceAndEquivalentEscapesAreAllowed() {
        val variant=fixture.replace("\"schema\":\"dev.lip.dictation-history\",\"version\":1", "\"version\" : 1, \"schema\" : \"dev.lip.dictation-history\"")
            .replace("🎙️","\\uD83C\\uDF99\\uFE0F")
        assertEquals(decode(),decode(" \t\n\r$variant \r\n"))
    }
    @Test fun duplicateUnknownMissingOrNestedKeysAreRefused() {
        for (text in listOf(
            fixture.replace("\"version\":1", "\"version\":1,\"version\":1"),
            fixture.replace("\"version\":1", "\"version\":1,\"vers\\u0069on\":1"),
            fixture.replace("\"id\":\"test\"", "\"id\":\"test\",\"id\":\"other\""),
            fixture.replace("\"id\":\"test\",", ""),
            fixture.replace("\"language\":null,", ""),
            fixture.replace("\"language\":null", "\"language\":null,\"extra\":[]"),
            fixture.replace("\"record\":{", "\"extra\":{},\"record\":{"),
            fixture.replace("\"language\":null", "\"language\":{}"),
            fixture.replace("\"language\":null", "\"language\":[]"))) refuses(text)
    }
    @Test fun JSONPermissivenessAndTrailingDocumentsAreRefused() {
        for (text in listOf("\uFEFF$fixture", "$fixture{}", "$fixture trailing", "$fixture\u00a0",
            fixture.replace("\"schema\"", "schema"),fixture.replace('"','\''),
            fixture.replace("\"version\":1", "\"version\":1/*comment*/"),
            fixture.replace("\"version\":1", "\"version\":1,"),
            fixture.replace("\"language\":null", "\"language\":undefined"),
            fixture.replace("\"original\":", "\"original\"="))) refuses(text)
    }
    @Test fun integerTypesRangesAndCoercionsAreStrict() {
        for (v in listOf("-1","253402300800000","9223372036854775808","01","1.0","1e2","\"1\"","true","null","+1"))
            refuses(fixture.replace("1700000000125",v))
        for (v in listOf("1.0","1e0","\"1\"","01","0","2","true")) refuses(fixture.replace("\"version\":1", "\"version\":$v"))
        for (v in listOf("\"false\"","0","null","False")) refuses(fixture.replace("\"legacyCloudCleanup\":false", "\"legacyCloudCleanup\":$v"))
        assertEquals(253402300799999L,decode(fixture.replace("1700000000125","253402300799999")).record.createdAtUnixMs)
    }
    @Test fun invalidUTF8EscapesControlsAndUnpairedSurrogatesAreRefused() {
        for (bytes in listOf(byteArrayOf(0xC0.toByte(),0xAF.toByte()), byteArrayOf(0xED.toByte(),0xA0.toByte(),0x80.toByte()),fixture.toByteArray()+byteArrayOf(0xFF.toByte())))
            assertTrue(runCatching { HistoryImport.decode(bytes) }.isFailure)
        for (v in listOf("\\uD800","\\uDC00","\\uD800x","\\uZZZZ","\\u٠٠٠٠","\\x41","\\v","a\nb","a\tb"))
            refuses(fixture.replace("\"id\":\"test\"", "\"id\":\"$v\""))
        refuses(fixture.dropLast(3));refuses(fixture.replace("\"sourcePlatform\":\"macos\"","\"sourcePlatform\":\"other\""))
    }
    @Test fun metadataByteBoundariesNotCharacterCounts() {
        val metadata="é".repeat(2048)
        assertEquals(metadata,decode(fixture.replace("\"model\":null","\"model\":\"$metadata\"")).record.model)
        for (field in listOf("language","model","modelDetails")) refuses(fixture.replace("\"$field\":null", "\"$field\":\"${metadata}a\""))
        assertEquals(128,decode(fixture.replace("\"id\":\"test\"", "\"id\":\"${"é".repeat(128)}\"")).record.id.length)
        refuses(fixture.replace("\"id\":\"test\"", "\"id\":\"${"é".repeat(129)}\""))
        refuses(fixture.replace("\"id\":\"test\"", "\"id\":\" \""))
    }
    @Test fun exactDocumentByteLimitAndHugeNestingFailBoundedly() {
        val bytes=fixture.toByteArray();val padded=bytes+ByteArray(HistoryTransfer.MAX_BYTES-bytes.size){32}
        assertEquals(decode(),HistoryImport.decode(padded))
        assertTrue(runCatching{HistoryImport.decode(padded+byteArrayOf(32))}.isFailure)
        refuses(fixture.replace("\"language\":null", "\"language\":"+"[".repeat(10000)+"0"+"]".repeat(10000)))
    }
    @Test fun conflictClassificationNeverOverwritesOrReidentifies() {
        val entry=decode().record;val same=entry.copy();val conflict=entry.copy(output="different")
        val originals=listOf(entry)
        assertEquals(HistoryImportDisposition.NEW,HistoryImport.classify(entry,emptyList()))
        assertEquals(HistoryImportDisposition.IDENTICAL,HistoryImport.classify(same,originals))
        assertEquals(HistoryImportDisposition.CONFLICT,HistoryImport.classify(conflict,originals))
        assertEquals(HistoryImportDisposition.AMBIGUOUS,HistoryImport.classify(entry,listOf(entry,entry)))
        assertEquals(listOf(entry),originals);assertEquals("test",conflict.id)
        for(changed in listOf(entry.copy(createdAtUnixMs=1),entry.copy(original="other"),entry.copy(language="en-US"),entry.copy(model="clean"),entry.copy(modelDetails="details"),entry.copy(legacyCloudCleanup=true)))
            assertEquals(HistoryImportDisposition.CONFLICT,HistoryImport.classify(changed,originals))
    }
}
