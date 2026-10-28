package dev.lip

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import dev.lip.keyboard.*
import org.json.JSONObject

/** Direct owned-view regression, NOT actual host editor/IME or narrow-window acceptance. */
class SizingVisibilityRunner:Instrumentation(){
    private var activity:Activity?=null
    private var panel:EmojiPanel?=null
    private lateinit var args:Bundle
    private fun ui(action:()->Unit){
        val error=java.util.concurrent.atomic.AtomicReference<Throwable>()
        runOnMainSync{try{action()}catch(t:Throwable){error.set(t)}}
        error.get()?.let{throw it}
    }
    private fun find(name:String):View {
        fun walk(v:View):View? {
            if(v.contentDescription?.toString()==name&&v.isShown)return v
            if(v is ViewGroup)for(i in 0 until v.childCount)walk(v.getChildAt(i))?.let{return it}
            return null
        }
        return checkNotNull(walk(checkNotNull(panel))){"Owned visible component control unavailable"}
    }
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);args=arguments?:Bundle();start()}
    override fun onStart(){
        val report=JSONObject().put("driverVersion",6).put("passed",false).put("scope","direct owned view;NOT host editor/narrow-window/IME acceptance")
        var result=Activity.RESULT_CANCELED
        try{
            check(android.os.Build.HARDWARE=="ranchu"&&targetContext.packageName=="dev.lip.android.sizingfixture"&&context.packageName=="dev.lip.android.sizingfixture.test")
            val owner=checkNotNull(args.getString("owner"));check(java.util.UUID.fromString(owner).toString()==owner);report.put("owner",owner)
            val catalog=EmojiAssets.load(targetContext);var picks=0
            val host=startActivitySync(Intent(targetContext,KeyboardSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));activity=host
            ui{
                panel=EmojiPanel(host,700,7,catalog,null,"thumbs up",null,EmojiRecents(),false,View(host),{picks++},{},{},{},{},{})
                host.setContentView(panel)
            }
            waitForIdleSync()
            ui{check(find("Insert emoji thumbs up").performLongClick())}
            waitForIdleSync()
            ui{
                val p=checkNotNull(panel);val cached=find("Choose emoji thumbs up: light skin tone")
                val heading=p.getChildAt(1)
                heading.measure(View.MeasureSpec.makeMeasureSpec(640,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                val cap=3*p.getChildAt(0).height+heading.measuredHeight+(24*host.resources.displayMetrics.density).toInt()
                fun resize(limit:Int){p.measure(View.MeasureSpec.makeMeasureSpec(640,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(limit,View.MeasureSpec.AT_MOST));p.layout(0,0,p.measuredWidth,p.measuredHeight)}
                resize(cap)
                check(!cached.isShown){"No-room chooser must actually be hidden"}
                report.put("noRoomCapPixels",cap).put("hiddenBeforeCachedAction",true).put("attachedAfterMeasuredShrink",cached.isAttachedToWindow)
                cached.performClick();report.put("picksAfterHiddenAction",picks)
                check(picks==0){"Hidden tone choice inserted"}
                resize(2000)
                val current=find("Choose emoji thumbs up: light skin tone")
                val chooser=find("Emoji skin tone choices");check(current.isAttachedToWindow)
                // Separate direct-view listener discriminator: no intervening layout
                // may detach the cached view and accidentally mask a missing guard.
                chooser.visibility=View.INVISIBLE
                check(current.isAttachedToWindow&&!current.isShown)
                report.put("directVisibilityTransition",true)
                current.performClick();report.put("picksAfterAttachedHiddenAction",picks)
                check(picks==0){"Attached hidden tone choice inserted"}
                chooser.visibility=View.VISIBLE
                current.performClick();current.performClick()
                report.put("picksAfterExpandedDoubleAction",picks);check(picks==1){"Expanded current choice must remain one-shot"}
            }
            report.put("passed",true);result=Activity.RESULT_OK
        }catch(t:Throwable){report.put("failure",android.util.Log.getStackTraceString(t))}
        finally{
            runCatching{ui{panel?.close();activity?.finish()}}.onFailure{report.put("cleanupFailure",it.javaClass.simpleName);result=Activity.RESULT_CANCELED;report.put("passed",false)}
            finish(result,Bundle().apply{putString("driverResult",report.toString())})
        }
    }
}
