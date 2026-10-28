package dev.lip

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import dev.lip.keyboard.WordDeleteBoundaries
import dev.lip.keyboard.WordDeleteWindow

/** Actual Android ICU, synthetic windows only. Not remote-editor/touch/device qualification. */
class WordDeleteBoundaryRunner:Instrumentation(){
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    override fun onStart(){
        val report=Bundle();var result=Activity.RESULT_CANCELED
        try{
            check(android.os.Build.HARDWARE=="ranchu"&&targetContext.packageName=="dev.lip.android")
            fun starts(text:String,caret:Int=text.length,offset:Int=0)=WordDeleteBoundaries.starts(WordDeleteWindow(offset,text,offset+caret,offset+caret))
            check(starts("one two three")==listOf(8,4,0));report.putBoolean("DELETE-ICU-ENGLISH-ORDER",true)
            check(starts("one don't!  ").first()==4);check(starts("one é").first()==4);check(starts("one 👨‍👩‍👧‍👦").first()==4)
            report.putBoolean("DELETE-ICU-APOSTROPHE-COMBINING-FAMILY",true)
            check(starts("one 👨‍👩‍👧‍👦",6).isEmpty());check(starts("one é",5).isEmpty())
            report.putBoolean("DELETE-ICU-INSIDE-CLUSTER-REFUSED",true)
            check(starts("x".repeat(64),offset=16).isEmpty());check(starts("x".repeat(60)+" two",offset=16)==listOf(77))
            report.putBoolean("DELETE-ICU-TRUNCATED-PREFIX-REFUSED",true)
            check(starts("?!")==listOf(0));check(starts(" ").isEmpty());check(starts(("a ").repeat(32)).size==16)
            report.putBoolean("DELETE-ICU-PUNCTUATION-EMPTY-BOUNDED",true)
            check(WordDeleteBoundaries.starts(WordDeleteWindow(-1,"abc",3,3)).isEmpty())
            report.putBoolean("DELETE-ICU-UNKNOWN-OFFSET-REFUSED",true)
            report.putString("result","PASS actual Android ICU boundary controls only; no native remote-editor/touch/phone qualification");result=Activity.RESULT_OK
        }catch(error:Throwable){report.putString("failure",error.stackTraceToString())}
        finish(result,report)
    }
}
