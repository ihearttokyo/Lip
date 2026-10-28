package dev.lip
import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputConnectionWrapper
import dev.lip.keyboard.*

/** Native Android editable/shared production writer; no ASR, private field or remote editor claim. */
class NextWordWriterRunner:Instrumentation(){
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    override fun onStart(){
        val report=Bundle();var outcome=Activity.RESULT_CANCELED
        try{
            check(android.os.Build.HARDWARE=="ranchu"&&targetContext.packageName=="dev.lip.android")
            val loadStart=android.os.SystemClock.uptimeMillis()
            val bundled=NextWordAssets.load(targetContext)
            report.putLong("diagnostic-asset-load-ms",android.os.SystemClock.uptimeMillis()-loadStart)
            val typingStart=android.os.SystemClock.uptimeMillis()
            TypingSuggestions(SwipeAssets.dictionary(targetContext).entries,emptyList())
            report.putLong("diagnostic-typing-build-ms",android.os.SystemClock.uptimeMillis()-typingStart)
            check(bundled.suggest(ImeCursor("fixture",1,12,12,"LEFT i want ","RIGHT",false))==listOf("to","you","a"))
            report.putBoolean("NEXT-ASSET-NATIVE-LOAD-SYNTHETIC-CONTEXT",true)
            var failure:Throwable?=null
            runOnMainSync{
                try{
                    fun cursor()=ImeCursor("fixture",1,7,7,"i want ","",false)
                    fun base(text:SpannableStringBuilder)=object:BaseInputConnection(View(targetContext),true){override fun getEditable()=text}
                    // Known broken ordering ends foreign markers even though claim then refuses.
                    val bad=SpannableStringBuilder("i want ");Selection.setSelection(bad,7);val old=base(bad);old.setComposingRegion(1,2)
                    val badSession=ImeSession();badSession.beginEditor();val badOwner=NextWordOwner(badSession)
                    val badRequest=badOwner.begin(cursor(),old)!!;badOwner.complete(badRequest,listOf("to"),cursor(),old);badSession.editorChanged()
                    old.finishComposingText();check(badOwner.claim(badRequest,0,cursor(),old)==null)
                    check(BaseInputConnection.getComposingSpanStart(bad)==-1);report.putBoolean("NEXT-WRITER-NEGATIVE-OLD-ORDER",true)
                    for(mode in 0..3){
                        val text=SpannableStringBuilder("i want ");Selection.setSelection(text,7);val actual=base(text);actual.setComposingRegion(1,2)
                        var finishes=0;var commits=0
                        val wrapper=object:InputConnectionWrapper(actual,false){
                            override fun finishComposingText():Boolean{finishes++;return super.finishComposingText()}
                            override fun commitText(value:CharSequence?,position:Int):Boolean{commits++;return super.commitText(value,position)}
                        }
                        val session=ImeSession();session.beginEditor();val owner=NextWordOwner(session);val request=owner.begin(cursor(),wrapper)!!
                        check(owner.complete(request,listOf("to"),cursor(),wrapper))
                        when(mode){0->session.editorChanged();1->session.beginEditor()}
                        val at=if(mode==2)cursor().copy(start=0,end=0,before="",after="i want ") else cursor()
                        val conn=if(mode==3)object:InputConnectionWrapper(wrapper,false){} else wrapper
                        check(NextWordWriter.write(owner,request,0,at,conn){true}==null)
                        check(finishes==0&&commits==0&&text.toString()=="i want ")
                        check(BaseInputConnection.getComposingSpanStart(text)==1&&BaseInputConnection.getComposingSpanEnd(text)==2)
                    }
                    report.putBoolean("NEXT-WRITER-STALE-NO-FOREIGN-MARKER-MUTATION",true)
                    val text=SpannableStringBuilder("i want ");Selection.setSelection(text,7);val actual=base(text);var finishes=0;var commits=0
                    var refuseFinish=false;var refuseCommit=false
                    val wrapper=object:InputConnectionWrapper(actual,false){
                        override fun finishComposingText():Boolean{finishes++;return !refuseFinish&&super.finishComposingText()}
                        override fun commitText(value:CharSequence?,position:Int):Boolean{commits++;return !refuseCommit&&super.commitText(value,position)}
                    }
                    val session=ImeSession();session.beginEditor();val owner=NextWordOwner(session)
                    fun request()=owner.begin(cursor(),wrapper)!!.also{check(owner.complete(it,listOf("to"),cursor(),wrapper))}
                    refuseFinish=true;check(NextWordWriter.write(owner,request(),0,cursor(),wrapper){true}==null&&commits==0)
                    refuseFinish=false;check(NextWordWriter.write(owner,request(),0,cursor(),wrapper){false}==null&&commits==0)
                    refuseCommit=true;check(NextWordWriter.write(owner,request(),0,cursor(),wrapper){true}==null&&commits==1)
                    refuseCommit=false;val good=request();check(NextWordWriter.write(owner,good,0,cursor(),wrapper){true}=="to ")
                    check(text.toString()=="i want to "&&commits==2)
                    val before=finishes;check(NextWordWriter.write(owner,good,0,cursor(),wrapper){true}==null&&finishes==before&&commits==2)
                    report.putBoolean("NEXT-WRITER-SINGLE-APPEND-REFUSAL-NO-RETRY",true)
                }catch(error:Throwable){failure=error}
            }
            failure?.let{throw it};outcome=Activity.RESULT_OK
            report.putString("result","PASS native editable/shared production append writer only; no remote-editor/model/UI/device quality qualification")
        }catch(error:Throwable){report.putString("failure",error.stackTraceToString())}
        finish(outcome,report)
    }
}
