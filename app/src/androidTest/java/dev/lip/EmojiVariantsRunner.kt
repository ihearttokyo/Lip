package dev.lip

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import dev.lip.keyboard.*

/** Direct owned-view stale listener regression; synthetic pick counter, NOT host editor acceptance. */
class EmojiVariantsRunner:Instrumentation(){
    private lateinit var activity:Activity
    private lateinit var panel:EmojiPanel
    private var picks=0
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
        return checkNotNull(walk(panel)){"Owned view missing: $name"}
    }
    override fun onCreate(args:Bundle?){super.onCreate(args);start()}
    override fun onStart(){
        val report=Bundle();var result=Activity.RESULT_CANCELED
        try{
            check(android.os.Build.HARDWARE=="ranchu"&&targetContext.packageName=="dev.lip.android")
            val catalog=EmojiAssets.load(targetContext)
            activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ui{
                panel=EmojiPanel(activity,700,7,catalog,null,"thumbs up",null,EmojiRecents(),false,View(activity),{picks++},{},{},{},{},{})
                activity.setContentView(panel)
            }
            waitForIdleSync()
            lateinit var base:View
            lateinit var oldCancel:View
            lateinit var oldChoice:View
            ui{base=find("Insert emoji thumbs up");check(base.performLongClick())}
            waitForIdleSync()
            ui{oldCancel=find("Cancel emoji skin tone choices");oldChoice=find("Choose emoji thumbs up: dark skin tone");oldCancel.performClick()}
            waitForIdleSync()
            ui{check(base.performLongClick())}
            waitForIdleSync()
            ui{
                val current=find("Emoji skin tone choices")
                oldCancel.performClick();oldChoice.performClick();base.performClick()
                check(current.isShown&&find("Emoji skin tone choices")===current)
                check(!base.isShown&&picks==0){"Stale cancelled chooser revealed base or picked text"}
                find("Choose emoji thumbs up: medium skin tone").performClick()
                find("Choose emoji thumbs up: medium skin tone").performClick()
                check(picks==1){"Choice was not one shot"}
            }
            report.putBoolean("EMOJI-DIRECT-STALE-CANCEL-A-TO-B",true)
            report.putBoolean("EMOJI-DIRECT-STALE-CHOICE-AND-HIDDEN-BASE",true)
            report.putBoolean("EMOJI-DIRECT-CHOICE-ONE-SHOT",true)
            ui{panel.close();find("Cancel emoji skin tone choices").performClick();check(picks==1)}
            report.putString("result","PASS3 direct owned-view synthetic chooser checks only")
            result=Activity.RESULT_OK
        }catch(t:Throwable){report.putString("failure",t.stackTraceToString())}
        finally{if(::activity.isInitialized)runCatching{ui{activity.finish()}}.onFailure{report.putString("cleanup-failure",it.toString());result=Activity.RESULT_CANCELED};finish(result,report)}
    }
}
