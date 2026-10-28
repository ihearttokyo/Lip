package dev.lip

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import dev.lip.keyboard.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Test APK only. Exact UI preference snapshot/restore, never text/history/clipboard/default IME. */
class PresentationStateRunner:Instrumentation(){
    private var args=Bundle()
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);args=arguments?:Bundle();start()}
    private val allowed=setOf("version","hand","height","theme","haptics")
    private fun validate(values:Map<String,*>) {
        check(values.keys.all{it in allowed}){"Unknown UI preference keys; no mutation permitted"}
        values.forEach{(key,value)->check(when(key){"version"->value is Int;"haptics"->value is Boolean;else->value is String&&value.length<=64}){"Unsupported original UI preference type"}}
    }
    override fun onStart(){
        val report=Bundle();var result=Activity.RESULT_CANCELED
        try{
            check(android.os.Build.HARDWARE=="ranchu"&&targetContext.packageName=="dev.lip.android")
            val owner=checkNotNull(args.getString("owner"));check(owner.matches(Regex("[a-f0-9-]{36}")))
            val dir=targetContext.noBackupFilesDir;val prefs=targetContext.getSharedPreferences("keyboard_presentation_v1",0)
            when(args.getString("operation")){
                "prepare","snapshot"->{
                    check(dir.listFiles().orEmpty().none{it.name.startsWith("cold-presentation-")&&it.name.endsWith(".json")}){"Pending UI restoration ticket; restore before another probe"}
                    val original=prefs.all.toMap();validate(original)
                    val seed=KeyboardPreferences(
                        hand=KeyboardHand.valueOf(args.getString("hand","FULL")),
                        height=KeyboardHeight.valueOf(args.getString("height","TALL")))
                    val record=JSONObject().put("schema",2).put("owner",owner).put("values",JSONObject(original)).put("seed",JSONObject(seed.encode()))
                    val ticket=File.createTempFile("cold-presentation-",".json",dir)
                    report.putString("restore-ticket",ticket.name)
                    FileOutputStream(ticket).use{it.write(record.toString().toByteArray(Charsets.UTF_8));it.fd.sync()}
                    val directoryFd=android.system.Os.open(dir.path,android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_CLOEXEC,0)
                    try{check(android.system.OsConstants.S_ISDIR(android.system.Os.fstat(directoryFd).st_mode));android.system.Os.fsync(directoryFd)}finally{android.system.Os.close(directoryFd)}
                    val written=JSONObject(ticket.readText(Charsets.UTF_8));check(written.getString("owner")==owner)
                    val verified=written.getJSONObject("values");check(verified.keys().asSequence().associateWith{verified.get(it)}==original)
                    if(args.getString("operation")=="prepare"){
                        check(KeyboardPreferencesStore(targetContext).save(seed))
                        check(prefs.all==seed.encode())
                        report.putBoolean(if(seed==KeyboardPreferences(height=KeyboardHeight.TALL))"PRESENTATION-PERSISTED-TALL" else "PRESENTATION-PERSISTED-SEEDED-CONFIG",true)
                    }else report.putBoolean("PRESENTATION-ORIGINAL-SNAPSHOT-ONLY",true)
                    report.putInt("setup-pid",android.os.Process.myPid())
                }
                "restore"->{
                    val name=args.getString("ticket")?:run{
                        val matching=dir.listFiles().orEmpty().filter{file->file.name.startsWith("cold-presentation-")&&file.name.endsWith(".json")&&!java.nio.file.Files.isSymbolicLink(file.toPath())&&file.length() in 1..16384&&runCatching{JSONObject(file.readText(Charsets.UTF_8)).getString("owner")==owner}.getOrDefault(false)}
                        check(matching.size==1){"Unique owned restoration ticket unavailable; preserve pending records"};matching.single().name
                    }
                    check(name.matches(Regex("cold-presentation-[a-zA-Z0-9._-]+\\.json")))
                    val ticket=File(dir,name);check(ticket.canonicalFile.parentFile==dir.canonicalFile&&!java.nio.file.Files.isSymbolicLink(ticket.toPath())&&ticket.isFile&&ticket.length() in 1..16384)
                    val record=JSONObject(ticket.readText(Charsets.UTF_8));check(record.getString("owner")==owner)
                    val values=record.getJSONObject("values");val original=values.keys().asSequence().associateWith{values.get(it)};validate(original)
                    val schema=record.optInt("schema",1);check(schema in 1..2)
                    val seed=if(schema==1)KeyboardPreferences(height=KeyboardHeight.TALL).encode() else {
                        val json=record.getJSONObject("seed");val saved=json.keys().asSequence().associateWith{json.get(it)}
                        validate(saved);check(KeyboardPreferences.decode(saved).encode()==saved){"Invalid seeded configuration; keep recovery ticket"};saved
                    }
                    val current=prefs.all.toMap()
                    if(current==original){
                        report.putBoolean("PRESENTATION-ALREADY-ORIGINAL-NO-REWRITE",true)
                    }else{
                        check(current==seed){"UI preference conflict; retaining original ticket without overwriting current values"}
                        val edit=prefs.edit()
                        (allowed-original.keys).forEach{edit.remove(it)}
                        original.forEach{(key,value)->when(value){is String->edit.putString(key,value);is Int->edit.putInt(key,value);is Boolean->edit.putBoolean(key,value);else->error("Unsupported restore type")}}
                        check(edit.commit())
                    }
                    check(prefs.all==original)
                    check(ticket.delete()){"Original values restored, but ticket cleanup not confirmed"}
                    report.putBoolean("PRESENTATION-EXACT-ORIGINAL-RESTORED",true)
                }
                else->error("Unknown explicit UI-state operation")
            }
            result=Activity.RESULT_OK
        }catch(t:Throwable){report.putString("failure",android.util.Log.getStackTraceString(t))}
        finish(result,report)
    }
}
