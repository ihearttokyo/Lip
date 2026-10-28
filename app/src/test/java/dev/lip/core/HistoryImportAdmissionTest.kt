package dev.lip.core

import dev.lip.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class HistoryImportAdmissionTest {
    private fun entry()=PortableHistoryRecord("import-id",1700000000125L,"か\u3099 你好\n🎙️","が 你好\n🎙️",null,null,null,true)
    private class Fixture(val limits:HistoryRetentionLimits=HistoryRetentionLimits()) {
        val files=linkedMapOf<String,String>();var writes=0;var stage:(()->Unit)?=null;var failWrite=false;var failRead=false
        val records=HistoryRecords({if(failRead)error("fixture read failure");files[it]}, {n,v->writes++;files[n]=v}, {files.keys.toList()}, {files.remove(it);Unit},
            writeAdmitted={n,v,admit->stage?.invoke();admit();if(failWrite)error("fixture write failure");writes++;files[n]=v},limits=limits)
        fun import(record:PortableHistoryRecord=entryStatic(),lease:HistoryImportLease=HistoryImportLease())=records.importRecord(record,lease::claim)
        companion object { fun entryStatic()=PortableHistoryRecord("import-id",1700000000125L,"か\u3099 你好\n🎙️","が 你好\n🎙️",null,null,null,true) }
    }
    @Test fun unknownMetadataRoundTripsWithoutInventingLanguageOrCanonicalOldBytes() {
        val e=entry();val local=HistoryImport.local(e);assertEquals(e,HistoryImport.portable(local))
        val encoded=AppStore.encodeHistory(listOf(local));assertEquals(listOf(local),AppStore.decodeHistory(encoded))
        val exported=HistoryImport.decode(HistoryTransfer.encode(local));assertEquals(e,exported.record)
        // Literal OLD six-put encoder output, not arbitrary input JSON key order.
        val old="""[{"raw":"x","language":"zh-CN","id":"old","time":10,"clean":"X","chatgpt":true}]"""
        assertEquals(old,AppStore.encodeHistory(AppStore.decodeHistory(old)))
        assertTrue(runCatching{AppStore.decodeHistory(old.replace(",\"language\":\"zh-CN\"", ""))}.isFailure)
    }
    @Test fun newThenIdenticalIsSingleWriteAtExactCapacity() {
        val f=Fixture(HistoryRetentionLimits(entries=1));val e=entry()
        assertEquals(HistoryImportDisposition.NEW,f.import(e));val before=f.files.toMap()
        assertEquals(HistoryImportDisposition.IDENTICAL,f.import(e));assertEquals(1,f.writes);assertEquals(before,f.files)
        assertEquals(listOf(HistoryImport.local(e)),f.records.page().entries)
    }
    @Test fun allFieldConflictsPreserveExistingPayloadAndId() {
        val e=entry();val changed=listOf(e.copy(createdAtUnixMs=1),e.copy(original="other"),e.copy(output="other"),e.copy(language="en-US"),e.copy(model="clean"),e.copy(modelDetails="details"),e.copy(legacyCloudCleanup=false))
        val f=Fixture();f.import(e);val before=f.files.toMap()
        for(c in changed)assertTrue(runCatching{f.import(c)}.isFailure)
        assertEquals(1,f.writes);assertEquals(before,f.files)
    }
    @Test fun duplicatesMissingCorruptAndUnmigratedRefuseWithoutMutation() {
        for(mode in listOf("duplicate","missing","corrupt","legacy")) {
            val f=Fixture();val e=HistoryImport.local(entry());val encoded=AppStore.encodeHistory(listOf(e))
            when(mode) { "duplicate"->{f.files["history-a"]=encoded;f.files["history-b"]=encoded};"missing"->{f.files["history-a"]=encoded;f.failRead=true};"corrupt"->f.files["history-a"]="invalid";"legacy"->f.files["history"]=encoded }
            val before=f.files.toMap();assertTrue(runCatching{f.import()}.isFailure);assertEquals(0,f.writes);assertEquals(before,f.files)
        }
    }
    @Test fun canceledDoubleClaimAndStageCancellationCannotPublish() {
        val f=Fixture();val lease=HistoryImportLease();lease.cancel()
        assertTrue(runCatching{f.import(lease=lease)}.isFailure);assertTrue(f.files.isEmpty());assertEquals(0,f.writes)
        val stageLease=HistoryImportLease();f.stage={stageLease.cancel()}
        assertTrue(runCatching{f.import(lease=stageLease)}.isFailure);assertTrue(f.files.isEmpty());assertEquals(0,f.writes)
        val once=HistoryImportLease();once.claim();assertTrue(runCatching{once.claim()}.isFailure)
        f.stage=null;f.import();val noop=HistoryImportLease();f.import(lease=noop);assertTrue(runCatching{noop.claim()}.isFailure)
    }
    @Test fun writeReadbackCapacityAndInvalidInputFailuresNeverOverwriteExisting() {
        val f=Fixture();f.import();val before=f.files.toMap();f.failWrite=true
        assertTrue(runCatching{f.import(entry().copy(id="new"))}.isFailure);assertEquals(before,f.files)
        for(limits in listOf(HistoryRetentionLimits(entries=0),HistoryRetentionLimits(bytes=0),HistoryRetentionLimits(payload=1),HistoryRetentionLimits(file=1))) {
            val limited=Fixture(limits);assertTrue(runCatching{limited.import()}.isFailure);assertEquals(0,limited.writes);assertTrue(limited.files.isEmpty())
        }
        for(bad in listOf(entry().copy(id=""),entry().copy(createdAtUnixMs=-1),entry().copy(model="x".repeat(4097)),entry().copy(original="\uD800"))) {
            val invalid=Fixture();assertTrue(runCatching{invalid.import(bad)}.isFailure);assertEquals(0,invalid.writes)
        }
        val files=linkedMapOf<String,String>();var written=false
        val records=HistoryRecords({if(written)error("readback fixture failure");files[it]},{n,v->files[n]=v;written=true},{files.keys.toList()},{})
        assertTrue(runCatching{records.importRecord(entry(),HistoryImportLease()::claim)}.isFailure)
        assertEquals(1,files.size) // Admitted failure is unconfirmed, NOT auto-deleted/retried.
    }
    @Test fun malformedLocalPrimitiveTypesGrammarAndShapeRefuseBeforePublication() {
        val valid="""[{"id":"old","time":10,"raw":"x","clean":"X","language":"en-US","chatgpt":false}]"""
        val malformed=listOf(
            valid.replace("10","1.5"),valid.replace("10","\"10\""),valid.replace("\"raw\":\"x\"","\"raw\":123"),
            valid.replace("\"language\":\"en-US\"","\"language\":true"),valid.replace("false","\"false\""),
            valid.replace("\"id\":\"old\"","\"id\":null"),valid.replace("\"id\":\"old\"","\"id\":\"old\",\"id\":\"again\""),
            valid.replace("\"id\"","id"),valid.replace("\"time\":10,", ""),valid.replace("}]",",\"extra\":null}]"),valid+"[]")
        for(payload in malformed) {
            assertNotEquals("Malformed fixture must differ from valid local bytes",valid,payload)
            val f=Fixture();f.files["history-invalid"]=payload;val before=f.files.toMap()
            assertTrue(runCatching{f.records.previewImport(entry())}.isFailure)
            assertTrue(runCatching{f.import()}.isFailure);assertEquals(0,f.writes);assertEquals(before,f.files)
        }
        assertEquals("old",HistoryImport.decodeLocal(valid.toByteArray()).id)
    }
    @Test fun competingSameIdImportsSerializeIntoOneNewAndOneIdentical() {
        val f=Fixture();val start=CountDownLatch(1);val new=AtomicInteger();val identical=AtomicInteger();val errors=AtomicInteger()
        val threads=(0..1).map{Thread{start.await();try{when(f.import()){HistoryImportDisposition.NEW->new.incrementAndGet();HistoryImportDisposition.IDENTICAL->identical.incrementAndGet();else->errors.incrementAndGet()}}catch(_:Throwable){errors.incrementAndGet()}}.apply{start()}}
        start.countDown();threads.forEach{it.join(3000);assertFalse(it.isAlive)}
        assertEquals(0,errors.get());assertEquals(1,new.get());assertEquals(1,identical.get());assertEquals(1,f.writes);assertEquals(1,f.files.size)
    }
}
