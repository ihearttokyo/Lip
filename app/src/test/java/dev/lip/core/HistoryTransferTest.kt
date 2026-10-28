package dev.lip.core

import dev.lip.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HistoryTransferTest {
    private fun entry(raw: String = "か\u3099 你好\n🎙️") = Transcript("test",1700000000125L,raw,"が 你好\n🎙️","en-US",false,"clean","synthetic")
    @Test fun exactUnicodeAndKnownMetadataUseSharedSchema() {
        val e = entry(); val root = JSONObject(String(HistoryTransfer.encode(e),Charsets.UTF_8)); val r=root.getJSONObject("record")
        assertEquals("dev.lip.dictation-history",root.getString("schema"));assertEquals(1,root.getInt("version"));assertEquals("android",root.getString("sourcePlatform"))
        assertEquals(setOf("id","createdAtUnixMs","original","output","language","model","modelDetails","legacyCloudCleanup"),r.keys().asSequence().toSet())
        assertEquals(e.raw,r.getString("original")); assertEquals(e.clean,r.getString("output"));assertEquals(e.time,r.getLong("createdAtUnixMs"))
        assertEquals("clean",r.getString("model"));assertEquals("synthetic",r.getString("modelDetails"));assertFalse(r.getBoolean("legacyCloudCleanup"))
    }
    @Test fun unknownLegacyModelIsNullNotInventedAndCloudFlagIsRetained() {
        val e=entry().copy(model=null,modelDetails=null,usedChatGpt=true)
        val r=JSONObject(String(HistoryTransfer.encode(e),Charsets.UTF_8)).getJSONObject("record")
        assertTrue(r.isNull("model"));assertTrue(r.isNull("modelDetails"));assertTrue(r.getBoolean("legacyCloudCleanup"))
        val old="""[{"id":"old","time":10,"raw":"x","clean":"X","language":"zh-CN","chatgpt":true}]"""
        val decoded=AppStore.decodeHistory(old).single();assertNull(decoded.model);assertNull(decoded.modelDetails)
        assertEquals(decoded,AppStore.decodeHistory(AppStore.encodeHistory(listOf(decoded))).single())
    }
    @Test fun malformedIdDateOrMetadataRefusesWithoutTruncation() {
        for(e in listOf(entry().copy(id=""),entry().copy(id="x".repeat(257)),entry().copy(time=-1),entry().copy(time=253402300800000L),entry().copy(model="x".repeat(4097))))
            assertTrue(runCatching{HistoryTransfer.encode(e)}.isFailure)
        assertEquals(0L,JSONObject(String(HistoryTransfer.encode(entry().copy(time=0)),Charsets.UTF_8)).getJSONObject("record").getLong("createdAtUnixMs"))
    }
    @Test fun exportLimitRefusesInsteadOfTrimmingOriginal() {
        val raw="x".repeat(HistoryTransfer.MAX_BYTES);val e=entry(raw)
        assertTrue(runCatching{HistoryTransfer.encode(e)}.isFailure);assertEquals(raw,e.raw)
    }
    @Test fun malformedUtf16NeverSilentlyChangesOriginalExport() {
        for (bad in listOf("\uD800", "\uDC00")) {
            for (e in listOf(entry(bad),entry().copy(clean=bad),entry().copy(id=bad),entry().copy(language=bad),entry().copy(model=bad),entry().copy(modelDetails=bad)))
                assertTrue(runCatching { HistoryTransfer.encode(e) }.isFailure)
        }
        val valid=entry("\uD83C\uDF99️")
        assertEquals(valid.raw,JSONObject(String(HistoryTransfer.encode(valid),Charsets.UTF_8)).getJSONObject("record").getString("original"))
    }
    @Test fun retentionPayloadBoundaryAndPlusOnePreservePriorRecords() {
        val files=linkedMapOf<String,String>();val e=entry("test");val bytes=AppStore.encodeHistory(listOf(e)).toByteArray().size
        fun records(cap:Int)=HistoryRecords({files[it]},{n,v->files[n]=v},{files.keys.toList()},{files.remove(it);Unit},limits=HistoryRetentionLimits(payload=cap))
        assertTrue(runCatching{records(bytes-1).save(e,true)}.isFailure);assertTrue(files.isEmpty())
        records(bytes).save(e,true);val before=files.toMap()
        assertTrue(runCatching{records(bytes-1).save(e.copy(id="next"),true)}.isFailure);assertEquals(before,files)
    }
    @Test fun encryptedByteAndFileBoundariesIncludeGcmOverhead() {
        val e=entry();val size=AppStore.encodeHistory(listOf(e)).toByteArray().size
        for(exact in listOf(false,true)) {
            val files=linkedMapOf<String,String>()
            val cap=size+29+(if(exact)0 else -1)
            val records=HistoryRecords({files[it]},{n,v->files[n]=v},{files.keys.toList()},{files.remove(it);Unit},limits=HistoryRetentionLimits(bytes=cap.toLong(),file=cap))
            assertEquals(exact,runCatching{records.save(e,true)}.isSuccess)
            assertEquals(if(exact)1 else 0,files.size)
        }
    }
    @Test fun countLimitNeverEvictsAndDisabledDoesNoIo() {
        val files=linkedMapOf<String,String>();var counted=0
        val records=HistoryRecords({files[it]},{n,v->files[n]=v},{files.keys.toList()},{files.remove(it);Unit},limits=HistoryRetentionLimits(entries=1),usage={counted++;HistoryUsage(files.values.sumOf{it.toByteArray().size.toLong()+29},files.size)})
        records.save(entry(),false);assertEquals(0,counted)
        records.save(entry(),true);val before=files.toMap()
        assertTrue(runCatching{records.save(entry().copy(id="next"),true)}.isFailure);assertEquals(before,files)
    }
    @Test fun pendingBackupOverfullUnknownAndLegacyCapacityFailClosed() {
        for(usage in listOf(HistoryUsage(50L*1024*1024,1),HistoryUsage(1,2000),HistoryUsage(-1,1),HistoryUsage(0,-1),HistoryUsage(0,0,true))) {
            var writes=0
            val records=HistoryRecords({null},{_,_->writes++},{emptyList()},{},usage={usage})
            assertTrue(runCatching{records.save(entry(),true)}.isFailure);assertEquals(0,writes)
        }
        val records=HistoryRecords({null},{_,_->fail("Unexpected write")},{emptyList()},{},usage={throw java.io.IOException("fixture count failed")})
        assertTrue(runCatching{records.save(entry(),true)}.isFailure)
    }
    @Test fun oldOversizedRecordStillReadableButNewSaveStops() {
        val old=entry("x".repeat(1024*1024));val encoded=AppStore.encodeHistory(listOf(old));val files=linkedMapOf("history-existing" to encoded)
        val records=HistoryRecords({files[it]},{n,v->files[n]=v},{files.keys.toList()},{files.remove(it);Unit},usage={HistoryUsage(60L*1024*1024,1)})
        assertEquals(listOf(old),records.page().entries);assertTrue(runCatching{records.save(entry(),true)}.isFailure);assertEquals(encoded,files["history-existing"])
    }
}
