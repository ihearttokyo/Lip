package dev.lip

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import dev.lip.auth.SecureStore
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Synthetic owned-emulator namespace/alias/FD only; no provider picker/audio/private data. */
class HistoryTransferRunner : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val report=JSONObject().put("passed",false).put("scope","native physical history accounting/missing-key preservation/local-FD export only")
        val nonce=UUID.randomUUID().toString()
        val namespace="hx-"+nonce;val alias="dev.lip.fixture."+nonce
        val root=File(targetContext.noBackupFilesDir,namespace)
        val plain=File(targetContext.cacheDir,"hx-export-$nonce.json")
        val occupied=File(targetContext.cacheDir,"hx-existing-$nonce.json")
        var ownsNamespace=false;var ownsAlias=false;var ownsPlain=false;var ownsOccupied=false;var cleanup=true
        try {
            check(Build.HARDWARE in listOf("ranchu","goldfish")&&Build.PRODUCT.contains("sdk")&&targetContext.packageName=="dev.lip.android")
            check(!root.exists());ownsNamespace=true
            val initialKeys=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
            check(!initialKeys.containsAlias(alias));ownsAlias=true
            val store=SecureStore(targetContext,namespace,alias)
            val e=Transcript(id="fixture",time=1700000000125L,raw="か\u3099 你好\n🎙️",clean="が 你好\n🎙️",language="en-US",usedChatGpt=false,model="clean",modelDetails="synthetic")
            val bytes=AppStore.encodeHistory(listOf(e)).toByteArray().size+29
            val records=HistoryRecords(store::read,store::write,{store.names("history-")},store::delete,store::writeAdmitted,
                limits=HistoryRetentionLimits(bytes=bytes.toLong()),usage=store::historyUsage)
            records.save(e,false);check(!root.exists())
            records.save(e,true);val name=store.names("history-").single();val initial=File(root,name).readBytes()
            check(store.historyUsage()==HistoryUsage(bytes.toLong(),1))
            check(runCatching{records.save(e.copy(id="second"),true)}.isFailure);check(File(root,name).readBytes().contentEquals(initial))
            for(suffix in listOf(".new",".bak")) check(File(root,name+suffix).createNewFile()).also{File(root,name+suffix).writeBytes(initial)}
            check(store.historyUsage()==HistoryUsage(bytes.toLong()*3,3))
            check(runCatching{records.save(e.copy(id="blocked"),true)}.isFailure)
            for(suffix in listOf(".new",".bak"))check(File(root,name+suffix).delete())
            report.put("actual_pending_backup_count_and_bytes",true).put("bounds_preserve_original",true)
            val oldPayload="""[{"id":"legacy","time":10,"raw":"raw","clean":"Clean.","language":"en-US","chatgpt":false}]"""
            val oldReplay=org.json.JSONArray().put(JSONObject().put("id","legacy").put("time",10L).put("raw","raw").put("clean","Clean.").put("language","en-US").put("chatgpt",false)).toString()
            check(oldReplay==oldPayload) // Actual Android old encoder order, not Maven JSONObject order.
            val oldName="history-"+java.lang.Long.toHexString(10L xor Long.MIN_VALUE).padStart(16,'0')+"-"+
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(oldPayload.toByteArray()))
            store.write("history",oldPayload);store.write(oldName,oldPayload) // Interrupted OLD migration already copied one item.
            check(store.historyUsage().legacy);check(runCatching{records.save(e.copy(id="no-migrate"),true)}.isFailure)
            check(records.page().entries.size==2&&!store.historyUsage().legacy&&store.names("history-").size==2&&store.read(oldName)==oldPayload)
            report.put("literal_old_android_partial_migration_no_duplicate",true)
            check(runCatching{records.save(e.copy(id="overfull"),true)}.isFailure)
            report.put("explicit_legacy_migration_preserved_overfull",true)
            val ciphertext=root.listFiles()!!.associate{it.name to it.readBytes()}
            val keys=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
            check(keys.containsAlias(alias));keys.deleteEntry(alias) // ONLY this generated fixture key.
            check(runCatching{store.write("history-new", "must not replace key")}.isFailure)
            check(!keys.containsAlias(alias));check(root.listFiles()!!.map{it.name}.toSet()==ciphertext.keys)
            for((file,value) in ciphertext)check(File(root,file).readBytes().contentEquals(value))
            check(runCatching{store.read(name)}.isFailure)
            report.put("missing_fixture_key_no_replacement_original_ciphertext_unchanged",true)
            val export=HistoryTransfer.encode(e)
            ownsPlain=plain.createNewFile();check(ownsPlain)
            HistoryDocumentExport.writeDescriptor(ParcelFileDescriptor.open(plain,ParcelFileDescriptor.MODE_READ_WRITE),export)
            check(plain.readBytes().contentEquals(export))
            val decoded=JSONObject(plain.readText()).getJSONObject("record");check(decoded.getString("original")==e.raw&&decoded.getLong("createdAtUnixMs")==e.time)
            ownsOccupied=occupied.createNewFile();check(ownsOccupied);occupied.writeText("KEEP ORIGINAL")
            check(runCatching{HistoryDocumentExport.writeDescriptor(ParcelFileDescriptor.open(occupied,ParcelFileDescriptor.MODE_READ_WRITE),export)}.isFailure)
            check(occupied.readText()=="KEEP ORIGINAL")
            val pipe=ParcelFileDescriptor.createPipe()
            try {check(runCatching{HistoryDocumentExport.writeDescriptor(pipe[1],export)}.isFailure)}finally{pipe[0].close();pipe[1].close()}
            report.put("empty_fd_exact_export_nonempty_and_pipe_refused",true).put("passed",true)
        } catch(e:Throwable) {report.put("error",e.javaClass.simpleName+": "+(e.message?:"fixture failed"))}
        finally {
            if(ownsNamespace)try{for(f in root.listFiles().orEmpty())if(!f.delete())cleanup=false;if(root.exists()&&!root.delete())cleanup=false}catch(_:Throwable){cleanup=false}
            if(ownsPlain&&plain.exists()&&!plain.delete())cleanup=false
            if(ownsOccupied&&occupied.exists()&&!occupied.delete())cleanup=false
            if(ownsAlias)try{val keys=KeyStore.getInstance("AndroidKeyStore").apply{load(null)};if(keys.containsAlias(alias))keys.deleteEntry(alias)}catch(_:Throwable){cleanup=false}
            report.put("cleanup_ok",cleanup).put("physical_phone_tested",false).put("shared_key_deleted",false).put("provider_or_settings_ui_tested",false)
            if(!cleanup)report.put("passed",false)
        }
        finish(if(report.getBoolean("passed"))Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply{putString(REPORT_KEY_STREAMRESULT,"\nHISTORY_TRANSFER_RESULT $report\n")})
    }
}
