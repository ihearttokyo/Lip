package dev.lip

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import dev.lip.keyboard.*
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Own new scale channel only. Never retires or rewrites the older sizing recovery protocol. */
class StickerBrowserStateRunner:Instrumentation(){
    private var args=Bundle()
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);args=arguments?:Bundle();start()}
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun newFile(file:File,bytes:ByteArray){
        check(file.createNewFile());java.io.FileOutputStream(file).use{it.write(bytes);it.fd.sync()}
        val fd=android.system.Os.open(file.parentFile!!.absolutePath,android.system.OsConstants.O_RDONLY,0)
        try{android.system.Os.fsync(fd)}finally{android.system.Os.close(fd)}
    }
    private fun validate(values:Map<String,*>) {
        check(values.isEmpty()||values==StickerPreferences.encode(StickerPreferences.decode(values)))
    }
    override fun onStart(){
        val report=JSONObject().put("passed",false);var code=Activity.RESULT_CANCELED
        try{
            check(android.os.Build.HARDWARE=="ranchu"&&targetContext.packageName=="dev.lip.android.sizingfixture"&&context.packageName=="dev.lip.android.sizingfixture.test")
            check(StickerBrowserFeature.enabled(targetContext))
            val owner=checkNotNull(args.getString("owner"));check(java.util.UUID.fromString(owner).toString()==owner)
            val ticket=File(targetContext.noBackupFilesDir,"sticker-browser-$owner-original.json")
            val marker=File(targetContext.noBackupFilesDir,"sticker-browser-$owner-restored.json")
            val prefFile=File(targetContext.applicationInfo.dataDir,"shared_prefs/sticker_presentation_v1.xml")
            val operation=args.getString("operation");check(operation in listOf("setup","restore"))
            val existed=prefFile.exists()
            val prefs=targetContext.getSharedPreferences("sticker_presentation_v1",0)
            when(operation){
                "setup"->{
                    check(!ticket.exists()&&!marker.exists())
                    val before=prefs.all.toMap();validate(before)
                    val original=JSONObject().put("version",1).put("owner",owner).put("existed",existed).put("original",JSONObject(before)).toString().toByteArray()
                    newFile(ticket,original)
                    check(StickerPreferencesStore(targetContext).save(KeyboardScale.SYSTEM))
                    check(prefs.all==StickerPreferences.encode(KeyboardScale.SYSTEM))
                    report.put("originalSHA256",hash(original))
                }
                "restore"->{
                    check(ticket.isFile&&!marker.exists())
                    val bytes=ticket.readBytes();val original=JSONObject(String(bytes,Charsets.UTF_8))
                    check(original.getInt("version")==1&&original.getString("owner")==owner)
                    val json=original.getJSONObject("original");val before=json.keys().asSequence().associateWith{json.get(it)}
                    validate(before);validate(prefs.all)
                    val edit=prefs.edit().remove("version").remove("previewScale")
                    before.forEach{(key,value)->when(value){is Int->edit.putInt(key,value);is String->edit.putString(key,value);else->error("Unknown scale value")}}
                    check(edit.commit()&&prefs.all==before)
                    // Exact rollback of this campaign's newly-created namespace only; never app-data clear.
                    if(!original.getBoolean("existed")){
                        check(before.isEmpty());check(targetContext.deleteSharedPreferences("sticker_presentation_v1"));check(!prefFile.exists())
                    }
                    newFile(marker,JSONObject().put("owner",owner).put("state","restored-exact-original").put("originalSHA256",hash(bytes)).toString().toByteArray())
                    report.put("actualPreferencesRestored",true).put("originalSHA256",hash(bytes)).put("originalFileExistenceRestored",prefFile.exists()==original.getBoolean("existed"))
                }
            }
            report.put("passed",true).put("owner",owner).put("operation",operation);code=Activity.RESULT_OK
        }catch(t:Throwable){report.put("failure",android.util.Log.getStackTraceString(t))}
        finish(code,Bundle().apply{putString("stateResult",report.toString())})
    }
}
