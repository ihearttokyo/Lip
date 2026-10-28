package dev.lip

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import dev.lip.auth.SecureStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Owned synthetic isolated key/namespace/local-FD test. No main history, consent,
 * picker, clipboard, activity, microphone or private-data access. */
class HistoryImportAdmissionRunner:Instrumentation(){
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    override fun onStart(){
        val report=JSONObject().put("passed",false).put("scope","native isolated encrypted one-record import admission and readonly local-FD source; not picker/UI/Mac/restore recovery")
        val nonce=UUID.randomUUID().toString();val namespace="hi-$nonce";val alias="dev.lip.fixture.import.$nonce"
        val root=File(targetContext.noBackupFilesDir,namespace);val source=File(targetContext.cacheDir,"hi-source-$nonce.json")
        var ownRoot=false;var ownKey=false;var ownSource=false;var store:SecureStore?=null;var assertions=0;var cleanup=true
        fun verify(ok:Boolean){check(ok){"Synthetic native admission assertion failed"};assertions++}
        try{
            check(Build.HARDWARE in listOf("ranchu","goldfish")&&Build.PRODUCT.contains("sdk")&&targetContext.packageName=="dev.lip.android")
            check(!root.exists()&&!source.exists());ownRoot=true
            check(source.createNewFile());ownSource=true
            val keyStore=KeyStore.getInstance("AndroidKeyStore").apply{load(null)};check(!keyStore.containsAlias(alias));ownKey=true
            val secure=SecureStore(targetContext,namespace,alias);store=secure
            val records=HistoryRecords(secure::read,secure::write,{secure.names("history-")},secure::delete,secure::writeAdmitted,usage={secure.historyUsage()})
            val portable=PortableHistoryRecord("synthetic-$nonce",1700000000125,"か\u3099 你好\n🎙️","が 你好\n🎙️",null,null,null,true)
            verify(records.previewImport(portable)==HistoryImportDisposition.NEW)
            verify(!root.exists()&&!keyStore.containsAlias(alias)) // Preview does not create storage/key.
            verify(records.importRecord(portable,HistoryImportLease()::claim)==HistoryImportDisposition.NEW)
            val names=secure.names("history-");verify(names.size==1)
            val bytes=File(root,names.single()).readBytes();val decoded=records.page().entries.single()
            verify(HistoryImport.portable(decoded)==portable)
            verify(!bytes.toString(Charsets.UTF_8).contains(portable.original))
            verify(HistoryImport.decode(HistoryTransfer.encode(decoded)).record==portable)
            verify(records.importRecord(portable,HistoryImportLease()::claim)==HistoryImportDisposition.IDENTICAL)
            verify(File(root,names.single()).readBytes().contentEquals(bytes))
            verify(runCatching{records.importRecord(portable.copy(output="different"),HistoryImportLease()::claim)}.isFailure)
            verify(secure.names("history-")==names&&File(root,names.single()).readBytes().contentEquals(bytes))
            verify(runCatching{records.importRecord(portable.copy(id="canceled-$nonce")){error("fixture revoked at encrypted publication")}}.isFailure)
            verify(secure.names("history-")==names&&File(root,names.single()).readBytes().contentEquals(bytes))
            val legacy=Transcript("old-$nonce",10,"x","X","ja-JP",false)
            val old=JSONArray(listOf(JSONObject().put("id",legacy.id).put("time",legacy.time).put("raw",legacy.raw).put("clean",legacy.clean).put("language",legacy.language).put("chatgpt",false))).toString()
            verify(AppStore.encodeHistory(AppStore.decodeHistory(old))==old)
            // Native Android permissive JSONObject getter mismatch witness:
            // unrelated type-corrupt records must block NEW import, not be coerced.
            val malformed=listOf(
                """[{"id":"old","time":1.5,"raw":123,"clean":"X","language":true,"chatgpt":"false"}]""",
                """[{"id":"old","time":"10","raw":"x","clean":"X","language":"en-US","chatgpt":false}]""",
                """[{id:"old",time:10,raw:"x",clean:"X",language:"en-US",chatgpt:false}]""")
            val permissive=AppStore.decodeHistory(malformed.first()).single()
            verify(permissive.time==1L&&permissive.raw=="123"&&permissive.language=="true"&&!permissive.usedChatGpt)
            report.put("platform_permissive_decoder_coercion_witness",true)
            for(payload in malformed) {
                secure.write("history-malformed",payload)
                val priorNames=secure.names("history-").toSet()
                val priorBytes=File(root,"history-malformed").readBytes()
                verify(runCatching{records.previewImport(portable.copy(id="new-$nonce"))}.isFailure)
                verify(runCatching{records.importRecord(portable.copy(id="new-$nonce"),HistoryImportLease()::claim)}.isFailure)
                verify(secure.names("history-").toSet()==priorNames&&File(root,"history-malformed").readBytes().contentEquals(priorBytes)&&File(root,names.single()).readBytes().contentEquals(bytes))
                secure.delete("history-malformed")
            }
            source.outputStream().use{it.write(HistoryTransfer.encode(decoded))}
            val originalSource=source.readBytes()
            val read=HistoryDocumentImport.readDescriptor(ParcelFileDescriptor.open(source,ParcelFileDescriptor.MODE_READ_ONLY),CancellationSignal())
            verify(read.record==portable&&source.readBytes().contentEquals(originalSource))
            val canceled=CancellationSignal().apply{cancel()}
            verify(runCatching{HistoryDocumentImport.readDescriptor(ParcelFileDescriptor.open(source,ParcelFileDescriptor.MODE_READ_ONLY),canceled)}.isFailure)
            val pipe=ParcelFileDescriptor.createPipe()
            try{verify(runCatching{HistoryDocumentImport.readDescriptor(pipe[0],CancellationSignal())}.isFailure)}finally{runCatching{pipe[0].close()};pipe[1].close()}
            verify(source.readBytes().contentEquals(originalSource))
            report.put("passed",true)
        }catch(e:Throwable){report.put("error",e.javaClass.simpleName)}
        finally{
            try{if(ownRoot&&root.exists()){
                val secure=checkNotNull(store)
                for(name in secure.names("history-"))secure.delete(name)
                check(root.list()?.isEmpty()==true);check(root.delete())
            }}catch(_:Throwable){cleanup=false}
            try{if(ownKey){check(!root.exists());val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)};if(ks.containsAlias(alias))ks.deleteEntry(alias);check(!ks.containsAlias(alias))}}catch(_:Throwable){cleanup=false}
            try{if(ownSource&&source.exists())check(source.delete())}catch(_:Throwable){cleanup=false}
        }
        report.put("assertions",assertions).put("cleanup_ok",cleanup).put("phone_tested",false)
        if(!cleanup)report.put("passed",false)
        finish(if(report.getBoolean("passed"))Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply{putString(REPORT_KEY_STREAMRESULT,"\nHISTORY_IMPORT_ADMISSION_RESULT $report\n")})
    }
}
