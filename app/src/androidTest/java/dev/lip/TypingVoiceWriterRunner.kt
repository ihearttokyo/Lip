package dev.lip

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputConnectionWrapper
import dev.lip.keyboard.ImeVoiceTextWriter

/** Native Android editable + injected completed-ASR payload. Not an audio/model/editor matrix test. */
class TypingVoiceWriterRunner:Instrumentation(){
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    override fun onStart(){
        val report=Bundle();var outcome=Activity.RESULT_CANCELED
        try{
            check(android.os.Build.HARDWARE=="ranchu"&&targetContext.packageName=="dev.lip.android")
            var failure:Throwable?=null
            runOnMainSync{
                try{
                    fun connection(value:SpannableStringBuilder)=object:BaseInputConnection(View(targetContext),true){override fun getEditable()=value}
                    val bad=SpannableStringBuilder("teh x");val old=connection(bad)
                    old.setComposingRegion(0,4);Selection.setSelection(bad,5);old.commitText("VOICE",1)
                    check(bad.toString()=="VOICEx"){"Negative control did not reproduce composition redirect"}
                    val good=SpannableStringBuilder("teh x");val actual=connection(good)
                    actual.setComposingRegion(0,4);Selection.setSelection(good,5)
                    check(ImeVoiceTextWriter.write(actual,"VOICE"));check(good.toString()=="teh xVOICE")
                    check(BaseInputConnection.getComposingSpanStart(good)==-1)
                    report.putBoolean("NATIVE-VOICE-WRITER-LATE-MARKER-AT-CARET",true)
                    var commits=0
                    val refusal=object:InputConnectionWrapper(actual,false){
                        override fun finishComposingText()=false
                        override fun commitText(text:CharSequence?,position:Int):Boolean{commits++;return false}
                    }
                    check(!ImeVoiceTextWriter.write(refusal,"VOICE"));check(commits==0)
                    val ambiguous=object:InputConnectionWrapper(actual,false){
                        override fun finishComposingText()=true
                        override fun commitText(text:CharSequence?,position:Int):Boolean{commits++;return false}
                    }
                    check(!ImeVoiceTextWriter.write(ambiguous,"VOICE"));check(commits==1)
                    report.putBoolean("NATIVE-VOICE-WRITER-REFUSAL-NO-RETRY",true)
                }catch(error:Throwable){failure=error}
            }
            failure?.let{throw it}
            outcome=Activity.RESULT_OK
            report.putString("result","PASS native Android editable/shared voice writer with synthetic ASR payload only; no microphone/recognition/ticket/window/remote-editor qualification")
        }catch(error:Throwable){report.putString("failure",error.stackTraceToString())}
        finish(outcome,report)
    }
}
