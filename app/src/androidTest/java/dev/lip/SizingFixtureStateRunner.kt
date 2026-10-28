package dev.lip

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import dev.lip.keyboard.*
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Seed/restore only. Never active while the separate consumer driver runs. */
class SizingFixtureStateRunner:Instrumentation(){
    private var arguments=Bundle()
    private val keys=setOf("version","hand","height","theme","haptics","keyLabels","emojiPreview")
    override fun onCreate(args:Bundle?){super.onCreate(args);arguments=args?:Bundle();start()}
    private fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun newFile(file:File,bytes:ByteArray){
        check(file.createNewFile()) { "Unresolved owned recovery file" }
        java.io.FileOutputStream(file).use{it.write(bytes);it.fd.sync()}
        val fd=android.system.Os.open(file.parentFile!!.absolutePath,android.system.OsConstants.O_RDONLY,0)
        try{check(android.system.OsConstants.S_ISDIR(android.system.Os.fstat(fd).st_mode));android.system.Os.fsync(fd)}finally{android.system.Os.close(fd)}
    }
    private fun validate(map:Map<String,*>) {
        check(map.keys.all{it in keys}) { "Unknown appearance keys" }
        map.forEach{(key,value)->check(when(key){"version"->value is Int;"haptics"->value is Boolean;else->value is String&&value.length<=64})}
    }
    private fun map(json:JSONObject)=json.keys().asSequence().associateWith{json.get(it)}.also{validate(it)}
    override fun onStart(){
        val report=JSONObject().put("passed",false);var outcome=Activity.RESULT_CANCELED
        try{
            check(android.os.Build.HARDWARE=="ranchu"&&targetContext.packageName=="dev.lip.android.sizingfixture"&&context.packageName=="dev.lip.android.sizingfixture.test")
            val owner=checkNotNull(arguments.getString("owner"));check(java.util.UUID.fromString(owner).toString()==owner)
            val operation=arguments.getString("operation");check(operation in listOf("setup","restore"))
            val ticket=File(targetContext.noBackupFilesDir,"sizing-driver-original.json")
            val marker=File(targetContext.noBackupFilesDir,"sizing-driver-restored.json")
            val prefs=targetContext.getSharedPreferences("keyboard_presentation_v1",0)
            when(operation){
                "setup"->{
                    check(!ticket.exists()&&!marker.exists()) { "Pending driver restoration must be reconciled" }
                    check(!File(targetContext.noBackupFilesDir,"sizing-recovery.json").exists()&&!File(targetContext.noBackupFilesDir,"sizing-restored.json").exists()) { "Previous campaign must be reconciled" }
                    val variant=arguments.getString("variant");check(variant in listOf("portrait","compact-landscape-font2"))
                    val original=prefs.all.toMap();validate(original)
                    val seed=KeyboardPreferences(height=if(variant=="portrait")KeyboardHeight.STANDARD else KeyboardHeight.COMPACT)
                    val bytes=JSONObject().put("version",1).put("owner",owner).put("original",JSONObject(original)).put("seed",JSONObject(seed.encode())).toString().toByteArray()
                    newFile(ticket,bytes)
                    check(KeyboardPreferencesStore(targetContext).save(seed));check(prefs.all==seed.encode())
                    report.put("originalSHA256",digest(bytes)).put("seed",JSONObject(seed.encode()))
                }
                "restore"->{
                    check(ticket.isFile&&!marker.exists()) { "Missing original or existing restoration marker" }
                    val bytes=ticket.readBytes();val record=JSONObject(String(bytes,Charsets.UTF_8));check(record.getInt("version")==1&&record.getString("owner")==owner)
                    val original=map(record.getJSONObject("original"));val seed=map(record.getJSONObject("seed"))
                    val current=prefs.all.toMap();validate(current)
                    if(current!=original){
                        val parsed=KeyboardPreferences.decode(current)
                        check(parsed.encode()==current) { "Unrecognized current state" }
                        val seeded=KeyboardPreferences.decode(seed)
                        check(parsed.copy(keyLabels=seeded.keyLabels,emojiPreview=seeded.emojiPreview)==seeded) { "Appearance changed outside the admitted sizing controls" }
                        check(KeyboardPreferencesStore(targetContext).save(KeyboardPreferences.decode(original)))
                        val edit=prefs.edit()
                        (keys-original.keys).forEach{edit.remove(it)}
                        original.forEach{(key,v)->when(v){is Int->edit.putInt(key,v);is String->edit.putString(key,v);is Boolean->edit.putBoolean(key,v);else->error("Unsupported original value")}}
                        check(edit.commit())
                    }
                    check(prefs.all==original)
                    newFile(marker,JSONObject().put("owner",owner).put("state","restored-exact-original").put("originalSHA256",digest(bytes)).toString().toByteArray())
                    report.put("actualPreferencesRestored",true).put("originalSHA256",digest(bytes))
                }
            }
            report.put("passed",true).put("operation",operation).put("owner",owner);outcome=Activity.RESULT_OK
        }catch(t:Throwable){report.put("failure",android.util.Log.getStackTraceString(t))}
        finish(outcome,Bundle().apply{putString("stateResult",report.toString())})
    }
}
