package dev.lip

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.lip.keyboard.*
import org.json.JSONObject

/** Native owned-view layout/action checks; NOT actual narrow IME/receiver/physical evidence. */
class GifLayoutComponentRunner:Instrumentation(){
    private var activity:Activity?=null
    private var panel:GifPanel?=null
    private lateinit var args:Bundle
    private fun ui(action:()->Unit){
        val error=java.util.concurrent.atomic.AtomicReference<Throwable>()
        runOnMainSync{try{action()}catch(t:Throwable){error.set(t)}}
        error.get()?.let{throw it}
    }
    private fun all(root:View):List<View> = buildList{
        fun walk(v:View){add(v);if(v is ViewGroup)for(i in 0 until v.childCount)walk(v.getChildAt(i))};walk(root)
    }
    private fun find(name:String)=all(checkNotNull(panel)).single{it.contentDescription?.toString()==name}
    private fun fitText():Int {
        var checked=0
        for(v in all(checkNotNull(panel)))if(v is TextView&&v !is GlassKeyButton){
            val layout=checkNotNull(v.layout);check(layout.height<=v.height-v.compoundPaddingTop-v.compoundPaddingBottom){"Owned component text clipped vertically"}
            for(i in 0 until layout.lineCount){check(layout.getLineWidth(i)<=v.width-v.compoundPaddingLeft-v.compoundPaddingRight){"Owned component text clipped horizontally"};check(layout.getEllipsisCount(i)==0){"Owned component text ellipsized"}}
            checked++
        }
        return checked
    }
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);args=arguments?:Bundle();start()}
    override fun onStart(){
        val report=JSONObject().put("driverVersion",2).put("passed",false).put("scope","direct owned view measured-width/no-match/busy/close;NOT real narrow IME/receiver/physical acceptance")
        var outcome=Activity.RESULT_CANCELED
        try{
            check(android.os.Build.HARDWARE=="ranchu"&&targetContext.packageName=="dev.lip.android.sizingfixture"&&context.packageName=="dev.lip.android.sizingfixture.test")
            val owner=checkNotNull(args.getString("owner"));check(java.util.UUID.fromString(owner).toString()==owner);report.put("owner",owner)
            val host=startActivitySync(Intent(targetContext,KeyboardSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));activity=host
            var picks=0;var cancels=0;var searches=0;var backs=0
            ui{panel=GifPanel(host,435,"happy",true,{picks++},{searches++},{backs++},{cancels++});host.setContentView(panel)}
            waitForIdleSync()
            ui{
                val p=checkNotNull(panel)
                fun resize(width:Int){p.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(700,View.MeasureSpec.AT_MOST));p.layout(0,0,p.measuredWidth,p.measuredHeight)}
                resize(640);report.put("narrowMeasuredTextViews",fitText())
                resize(1600);check(fitText()>0);resize(640);check(fitText()>0)
                val tile=find("Attach GIF: Grinning face");p.setBusy(true)
                check(!tile.isEnabled);tile.performClick();check(picks==0)
                val cancel=find("Cancel pending GIF attachment");report.put("cancelHeightBeforeTraversal",cancel.height);resize(640)
                check(fitText()>0);report.put("cancelMeasuredHeight",cancel.height)
                check(cancel.isShown&&cancel.height>=48*host.resources.displayMetrics.density){"Laid-out visible Cancel target below48dp"}
                cancel.performClick();check(cancels==1)
                p.setBusy(false);check(tile.isEnabled);tile.performClick();check(picks==1)
                p.close();tile.performClick();cancel.performClick();find("Search local GIF catalog").performClick();find("Return from GIFs to letters").performClick()
                check(picks==1&&cancels==1&&searches==0&&backs==0){"Closed panel action escaped"}
                report.put("busyAndClosedActionsFenced",true)
                panel=GifPanel(host,435,"no-result-query",false,{picks++},{},{},{})
                host.setContentView(panel)
            }
            waitForIdleSync()
            ui{
                val p=checkNotNull(panel);p.measure(View.MeasureSpec.makeMeasureSpec(640,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(700,View.MeasureSpec.AT_MOST));p.layout(0,0,p.measuredWidth,p.measuredHeight)
                check(all(p).none{it.contentDescription?.toString()?.startsWith("Attach GIF: ")==true})
                report.put("noMatchTextViews",fitText()).put("noMatchNoTiles",true)
            }
            report.put("passed",true);outcome=Activity.RESULT_OK
        }catch(t:Throwable){report.put("failure",android.util.Log.getStackTraceString(t))}
        finally{
            runCatching{ui{panel?.close();activity?.finish()}}.onFailure{report.put("cleanupFailure",it.javaClass.simpleName).put("passed",false);outcome=Activity.RESULT_CANCELED}
            finish(outcome,Bundle().apply{putString("driverResult",report.toString())})
        }
    }
}
