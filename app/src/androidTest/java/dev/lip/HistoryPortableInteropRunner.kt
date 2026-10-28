package dev.lip

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import org.json.JSONObject
import java.util.Base64

/** Synthetic payload-only actual Android codec/bridge. No storage, providers,
 * preferences, key, clipboard, activity or private content. Fixture identity only. */
class HistoryPortableInteropRunner:Instrumentation(){
    private var arguments:Bundle?=null
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);this.arguments=arguments;start()}
    override fun onStart(){
        val report=JSONObject().put("passed",false).put("scope","actual Android/Swift synthetic portable codec and local-model bridge only; not admission/UI/Keychain")
        var checks=0
        fun verify(ok:Boolean){check(ok){"Synthetic interchange mismatch"};checks++}
        fun payload(name:String):ByteArray?{val encoded=arguments?.getString(name)?:return null;require(encoded.length<=8192);return Base64.getDecoder().decode(encoded)}
        try{
            check(targetContext.packageName=="dev.lip.android.historyfixture"&&Build.HARDWARE in listOf("ranchu","goldfish")&&Build.PRODUCT.contains("sdk"))
            val raw="INTEROP_SYNTHETIC か\u3099 你好\n🎙️";val output="INTEROP_SYNTHETIC が 你好\n🎙️"
            val entry=Transcript("android-fixture-東京",1700000000125,raw,output,null,true,null,null)
            val bytes=HistoryTransfer.encode(entry);val android=HistoryImport.decode(bytes)
            verify(android.record==HistoryImport.portable(entry)&&android.sourcePlatform=="android")
            payload("mac_b64")?.let{macBytes->
                val mac=HistoryImport.decode(macBytes)
                val expected=PortableHistoryRecord("A0000000-0000-4000-8000-000000000001",1700000000125,raw,output,"en-US","clean","synthetic interchange",false)
                verify(mac.sourcePlatform=="macos"&&mac.record==expected)
                val returned=HistoryTransfer.encode(HistoryImport.local(mac.record))
                verify(HistoryImport.decode(returned).record==expected)
                report.put("mac_return_b64",Base64.getEncoder().encodeToString(returned))
            }
            payload("android_return_b64")?.let{returned->
                val mac=HistoryImport.decode(returned)
                verify(mac.sourcePlatform=="macos"&&mac.record==android.record)
                report.put("android_to_mac_to_android_exact",true)
            }
            check(arguments?.containsKey("mac_b64")==true||arguments?.containsKey("android_return_b64")==true)
            report.put("android_export_b64",Base64.getEncoder().encodeToString(bytes)).put("passed",true)
        }catch(e:Throwable){report.put("error",e.javaClass.simpleName)}
        report.put("checks",checks).put("phone_tested",false)
        finish(if(report.getBoolean("passed"))Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply{putString(REPORT_KEY_STREAMRESULT,"\nHISTORY_PORTABLE_INTEROP_RESULT $report\n")})
    }
}
