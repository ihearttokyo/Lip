package dev.lip

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import dev.lip.keyboard.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Worker-path microbenchmark + exact full-lexicon native oracle; no editor/phone/quality acceptance. */
class SwipeGeometryRunner:Instrumentation(){
    private var mode="worker"
    override fun onCreate(args:Bundle?){super.onCreate(args);mode=args?.getString("mode")?:"worker";if(mode !in listOf("worker","workerIdentity","equivalence")){finish(Activity.RESULT_CANCELED,Bundle().apply{putString("failure","Unknown geometry test mode")});return};start()}
    private fun shape(index:Int):SwipeGeometry {
        val values=FloatArray(104);var i=0
        listOf("qwertyuiop","asdfghjkl","zxcvbnm").forEachIndexed{row,letters->
            val offset=when(row){0->0f;1->.5f;else->1.5f}
            letters.forEachIndexed{col,_->
                values[i++]=(col+offset+.5f)/10+(if(index==1&&col%2==0).0004f else 0f)
                values[i++]=(row+.5f)/3;values[i++]=if(index==1).044f else .045f;values[i++]=.15f
            }
        };return SwipeGeometry(values,when(index){0->.45f;1->.52f;else->.18f})
    }
    private fun trace(shape:SwipeGeometry,word:String):List<SwipePoint>{
        val out=ArrayList<SwipePoint>();var last:Char?=null
        for(c in word){if(c==last)continue;last=c;val i=SwipeModel.LETTERS.indexOf(c)*4;val x=shape.rectangles[i];val y=shape.rectangles[i+1]
            if(out.isEmpty())out.add(SwipePoint(x,y,0f))else{val from=out.last();for(step in 1..20){val f=step/20f;out.add(SwipePoint(from.x+(x-from.x)*f,from.y+(y-from.y)*f,from.milliseconds+step*10))}}
        };return out
    }
    override fun onStart(){
        val report=Bundle();var result=Activity.RESULT_CANCELED
        try{
            check(android.os.Build.HARDWARE=="ranchu"&&targetContext.packageName=="dev.lip.android")
            if(mode=="worker"||mode=="workerIdentity"){
                lateinit var worker:SwipeDecodeWorker;runOnMainSync{worker=SwipeDecodeWorker(targetContext)}
                try{
                    var firstHandle:Long?=null
                    for((iteration,index) in listOf(0,1,2,0,1,2).withIndex()){
                        val s=shape(index);val latch=CountDownLatch(1);val got=AtomicReference<Result<List<String>>>()
                        val start=SystemClock.elapsedRealtimeNanos()
                        runOnMainSync{
                            val epoch=checkNotNull(SwipeVocabularyEpoch.process.snapshot())
                            val access=SwipeVocabularyAccess() // Disposable emulator component only; not a real editor lease.
                            worker.prepare(s,epoch,access);worker.decode(s,trace(s,"hello"),epoch,access){got.set(it);latch.countDown()}
                        }
                        check(latch.await(15,TimeUnit.SECONDS)){"Existing15s geometry observer expired at $iteration"}
                        val elapsed=SystemClock.elapsedRealtimeNanos()-start
                        val candidates=checkNotNull(got.get()).getOrThrow();check("hello" in candidates)
                        if(mode=="workerIdentity"){
                            val nativeEngine=worker.javaClass.getDeclaredField("engine").apply{isAccessible=true}.get(worker)
                            val handle=checkNotNull(nativeEngine).javaClass.getDeclaredField("handle").apply{isAccessible=true}.getLong(nativeEngine)
                            report.putLong("worker-$iteration-handle",handle)
                            if(firstHandle==null)firstHandle=handle else check(handle==firstHandle){"Shape transition reopened the model context at $iteration"}
                        }
                        report.putLong("worker-$iteration-nanos",elapsed);report.putString("worker-$iteration-candidates",candidates.joinToString(","))
                    }
                    report.putBoolean("SWIPE-WORKER-SIX-GEOMETRIES-SAME-CONTROLS",true)
                    if(mode=="workerIdentity")report.putBoolean("SWIPE-ACTUAL-WORKER-ONE-HANDLE",true)
                }finally{runOnMainSync{worker.close()}}
            }else equivalence(report)
            result=Activity.RESULT_OK
        }catch(t:Throwable){report.putString("failure",android.util.Log.getStackTraceString(t))}
        finish(result,report)
    }
    private fun equivalence(report:Bundle){
        val shapes=(0..2).map(::shape);val words=listOf("hello","world","keyboard","meeting")
        val expected=HashMap<Pair<Int,String>,List<String>>()
        // Independent fresh-engine oracle per actual full lexicon and shape, unchanged rank/beam.
        for((index,s) in shapes.withIndex()){
            val fresh=SwipeAssets.open(targetContext,s)
            try{for(word in words){val values=fresh.decode(trace(s,word),8);check(values.isNotEmpty());expected[index to word]=values}}
            finally{fresh.close()}
        }
        val engine=SwipeAssets.open(targetContext,shapes[0]);val handleField=engine.javaClass.getDeclaredField("handle").apply{isAccessible=true};val handle=handleField.getLong(engine)
        try{
            for(index in listOf(0,1,2,0,2,1))for(word in words){
                engine.prepare(shapes[index])
                check(engine.decodeForGeometry(shapes[index],trace(shapes[index],word),8)==expected[index to word]){"Fresh/reused ordered top8 differs: $index / $word"}
                check(handleField.getLong(engine)==handle)
            }
            report.putBoolean("SWIPE-FULL-LEXICON-FRESH-REUSED-TOP8-EXACT",true)
            report.putBoolean("SWIPE-SAME-NATIVE-HANDLE-ACROSS-RESHAPES",true)
            engine.prepare(shapes[0]);val points=trace(shapes[0],"hello");val original=engine.decode(points,8)
            val invalid=listOf(
                shapes[0].rectangles.copyOf(103),
                shapes[0].rectangles.copyOf().apply{this[0]=Float.NaN},
                shapes[0].rectangles.copyOf().apply{this[4]=this[0];this[5]=this[1]},
                shapes[0].rectangles.copyOf().apply{this[2]=0f})
            for(array in invalid){
                check(runCatching{SwipeNative.reconfigure(handle,array,.45f)}.exceptionOrNull() is IllegalArgumentException)
                check(engine.decode(points,8)==original)
                check(runCatching{SwipeNative.decodeForGeometry(handle,array,.45f,SwipeInputBounds.pack(points),8)}.exceptionOrNull() is IllegalArgumentException)
                check(engine.decode(points,8)==original)
            }
            for(aspect in listOf(0f,Float.NaN,2.01f)){
                check(runCatching{SwipeNative.reconfigure(handle,shapes[0].rectangles,aspect)}.exceptionOrNull() is IllegalArgumentException)
                check(engine.decode(points,8)==original)
            }
            for(packed in listOf(floatArrayOf(0f,0f,0f),SwipeInputBounds.pack(points).apply{this[2]=Float.NaN})){
                check(runCatching{SwipeNative.decodeForGeometry(handle,shapes[1].rectangles,shapes[1].aspect,packed,8)}.exceptionOrNull() is IllegalArgumentException)
                check(engine.decode(points,8)==original)
            }
            report.putBoolean("SWIPE-NATIVE-INVALID-GEOMETRY-TRACE-NO-MUTATION",true)
            val errors=java.util.concurrent.ConcurrentLinkedQueue<Throwable>();val start=CountDownLatch(1);val done=CountDownLatch(3)
            val pool=java.util.concurrent.Executors.newFixedThreadPool(3)
            try{
                repeat(2){lane->pool.execute{try{start.await();repeat(6){iteration->val index=(iteration+lane)%3;val word=words[(iteration+lane)%words.size];check(engine.decodeForGeometry(shapes[index],trace(shapes[index],word),8)==expected[index to word])}}catch(t:Throwable){errors.add(t)}finally{done.countDown()}}}
                pool.execute{try{start.await();repeat(40){iteration->val s=shapes[iteration%3];SwipeNative.reconfigure(handle,s.rectangles,s.aspect)}}catch(t:Throwable){errors.add(t)}finally{done.countDown()}}
                start.countDown();check(done.await(30,TimeUnit.SECONDS)){"Fixed30s concurrent shaped-decode fixture expired"};check(errors.isEmpty()){errors.joinToString()}
            }finally{pool.shutdownNow()}
            report.putBoolean("SWIPE-SHAPED-DECODE-ATOMIC-AGAINST-RAW-UPDATE",true)
            val mainResult=AtomicReference<Throwable?>();runOnMainSync{mainResult.set(runCatching{engine.prepare(shapes[0])}.exceptionOrNull())};check(mainResult.get() is IllegalStateException)
            report.putBoolean("SWIPE-PREPARE-MAIN-THREAD-REFUSED",true)
        }finally{engine.close()}
        check(runCatching{engine.prepare(shapes[0])}.exceptionOrNull() is IllegalStateException)
        check(runCatching{engine.decodeForGeometry(shapes[0],trace(shapes[0],"hello"))}.exceptionOrNull() is IllegalStateException)
        check(runCatching{SwipeNative.reconfigure(handle,shapes[0].rectangles,shapes[0].aspect)}.exceptionOrNull() is IllegalArgumentException)
        report.putBoolean("SWIPE-REUSE-CLOSED-HANDLES-REFUSED",true)
    }
}
