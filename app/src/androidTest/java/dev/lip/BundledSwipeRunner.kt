package dev.lip

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import dev.lip.keyboard.*

/** Actual APK assets + complete admitted English lexicon; synthetic paths, never quality acceptance. */
class BundledSwipeRunner:Instrumentation() {
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    override fun onStart(){
        val report=Bundle();var result=Activity.RESULT_CANCELED
        try {
            check(targetContext.packageName=="dev.lip.android")
            val data=SwipeAssets.dictionary(targetContext)
            check(data.entries.size==246650&&data.admitted().size==246468)
            check(data.display("iphone")=="iPhone")
            report.putBoolean("APK-VERIFIED-ADMITTED-ENGLISH-DICTIONARY",true)
            val geometry=FloatArray(104);var index=0
            listOf("qwertyuiop","asdfghjkl","zxcvbnm").forEachIndexed{row,letters->
                val offset=when(row){0->0f;1->.5f;else->1.5f}
                letters.forEachIndexed{col,_->geometry[index++]=(col+offset+.5f)/10;geometry[index++]=(row+.5f)/3;geometry[index++]=.045f;geometry[index++]=.15f}
            }
            val engine=SwipeAssets.open(targetContext,SwipeGeometry(geometry,.45f))
            try {
                for(word in listOf("hello","world","keyboard")){
                    val path=word.fold(""){out,c->if(out.lastOrNull()==c)out else out+c}
                    val points=mutableListOf<SwipePoint>()
                    for(letter in path){val key=SwipeModel.LETTERS.indexOf(letter);val x=geometry[key*4];val y=geometry[key*4+1]
                        if(points.isEmpty())points+=SwipePoint(x,y,0f)
                        else{val previous=points.last();for(step in 1..20){val f=step/20f;points+=SwipePoint(previous.x+(x-previous.x)*f,previous.y+(y-previous.y)*f,previous.milliseconds+step*10f)}}
                    }
                    val candidates=engine.decode(points)
                    check(word in candidates){"Synthetic full-lexicon control missed top3: $word / $candidates"}
                    report.putString("synthetic-$word",candidates.joinToString(","))
                }
                report.putBoolean("FULL-LEXICON-NEURAL-CONTROLS",true)
                report.putLong("diagnostic-native-heap-bytes-not-RSS",android.os.Debug.getNativeHeapAllocatedSize())
            }finally{engine.close()}
            check(SwipeAssets.model(targetContext).length()==SwipeModel.BYTES.toLong())
            report.putBoolean("APK-MODEL-VERIFIED-INSTALL",true)
            report.putString("result","PASS actual bundled assets/native integration; synthetic centerlines only, not phone/human/quality/performance acceptance")
            result=Activity.RESULT_OK
        }catch(error:Throwable){report.putString("failure",error.javaClass.simpleName+": "+error.message)}
        finish(result,report)
    }
}
