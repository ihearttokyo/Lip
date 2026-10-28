package dev.lip

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import org.json.JSONObject

/** Pure in-memory synthetic parser checks on owned emulator. No provider/history
 * preferences/records/keys/clipboard/activity or private content access. */
class HistoryImportRunner : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val report=JSONObject().put("passed",false).put("scope","native in-memory portable history parser only; not import/restore or SAF recovery")
        var assertions=0
        fun verify(ok:Boolean){check(ok){"Synthetic parser assertion failed"};assertions++}
        try {
            check(Build.HARDWARE in listOf("ranchu","goldfish")&&Build.PRODUCT.contains("sdk")&&targetContext.packageName=="dev.lip.android")
            val fixture="""{"schema":"dev.lip.dictation-history","version":1,"sourcePlatform":"macos","record":{"id":"fixture","createdAtUnixMs":1700000000125,"original":"か\u3099 你好\n🎙️","output":"が 你好\n🎙️","language":null,"model":null,"modelDetails":null,"legacyCloudCleanup":false}}"""
            val d=HistoryImport.decode(fixture.toByteArray())
            verify(d.record.original=="か\u3099 你好\n🎙️"&&d.record.output=="が 你好\n🎙️")
            verify(d.sourcePlatform=="macos"&&d.record.language==null&&d.record.model==null&&d.record.modelDetails==null)
            val entry=Transcript("fixture",1700000000125,"か\u3099 你好\n🎙️","が 你好\n🎙️","en-US",false,"clean","synthetic")
            val android=HistoryImport.decode(HistoryTransfer.encode(entry))
            verify(android.record.original==entry.raw&&android.record.language==entry.language&&android.sourcePlatform=="android")
            for (bad in listOf(
                fixture.replace("\"version\":1","\"version\":1,\"version\":1"),
                fixture.replace("\"id\":\"fixture\"","\"id\":\"\\uD800\""),
                fixture.replace("1700000000125","1.0"),
                fixture.replace("\"language\":null","\"language\":{}"),
                fixture+"{}")) verify(runCatching{HistoryImport.decode(bad.toByteArray())}.isFailure)
            verify(runCatching{HistoryImport.decode(byteArrayOf(0xFF.toByte()))}.isFailure)
            val bytes=fixture.toByteArray();val boundary=bytes+ByteArray(HistoryTransfer.MAX_BYTES-bytes.size){32}
            verify(HistoryImport.decode(boundary)==d)
            verify(runCatching{HistoryImport.decode(boundary+byteArrayOf(32))}.isFailure)
            verify(HistoryImport.classify(d.record,listOf(d.record))==HistoryImportDisposition.IDENTICAL)
            verify(HistoryImport.classify(d.record.copy(output="other"),listOf(d.record))==HistoryImportDisposition.CONFLICT)
            verify(HistoryImport.classify(d.record,listOf(d.record,d.record))==HistoryImportDisposition.AMBIGUOUS)
            verify(HistoryImport.classify(d.record,emptyList())==HistoryImportDisposition.NEW)
            report.put("passed",true)
        } catch(e:Throwable) { report.put("error",e.javaClass.simpleName) }
        report.put("assertions",assertions).put("phone_tested",false)
        finish(if(report.getBoolean("passed"))Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply{putString(REPORT_KEY_STREAMRESULT,"\nHISTORY_IMPORT_RESULT $report\n")})
    }
}
