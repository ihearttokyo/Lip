package dev.lip

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import dev.lip.keyboard.*
import java.io.File

/** Real packaged Android JNI + pinned weights, deliberately tiny synthetic lexicon only. */
class SwipeNativeRunner: Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments);start() }
    override fun onStart() {
        val report=Bundle();var result=Activity.RESULT_CANCELED
        try {
            check(targetContext.packageName=="dev.lip.android")
            val geometry=FloatArray(104);var i=0
            listOf("qwertyuiop","asdfghjkl","zxcvbnm").forEachIndexed { row,letters ->
                val offset=when(row){0->0f;1->.5f;else->1.5f}
                letters.forEachIndexed { col,_ -> geometry[i++]=(col+offset+.5f)/10;geometry[i++]=(row+.5f)/3;geometry[i++]=.045f;geometry[i++]=.15f }
            }
            val shape=SwipeGeometry(geometry,.45f)
            val words=listOf("hello","help","held","world","keyboard","meeting","friday","test")
            val points=mutableListOf<SwipePoint>()
            for(letter in "helo") {
                val key=SwipeModel.LETTERS.indexOf(letter);val x=geometry[key*4];val y=geometry[key*4+1]
                if(points.isEmpty())points+=SwipePoint(x,y,0f)
                else { val previous=points.last();for(step in 1..20){val f=step/20f;points+=SwipePoint(previous.x+(x-previous.x)*f,previous.y+(y-previous.y)*f,previous.milliseconds+step*10f)} }
            }
            val engine=SwipeEngine.open(File(targetContext.noBackupFilesDir,"swipe-fixtures/swipe-model.bin"),shape,words,IntArray(words.size){200})
            try {
                val candidates=engine.decode(points)
                check("hello" in candidates) { "Pinned synthetic HELLO control missed top3: $candidates" }
                report.putString("candidates",candidates.joinToString(","))
                report.putBoolean("ANDROID-NEURAL-FORWARD-BEAM",true)
                var mainDecodeRefused=false;var mainCloseRefused=false
                runOnMainSync {
                    mainDecodeRefused=runCatching{engine.decode(points)}.exceptionOrNull() is IllegalStateException
                    mainCloseRefused=runCatching{engine.close()}.exceptionOrNull() is IllegalStateException
                }
                check(mainDecodeRefused&&mainCloseRefused)
                report.putBoolean("MAIN-THREAD-COMPUTE-REFUSED",true)
                check(runCatching{engine.decode(listOf(SwipePoint(Float.NaN,0f,0f),SwipePoint(1f,1f,1f)))}.isFailure)
                report.putBoolean("INVALID-TRACE-REFUSED",true)
            }finally{engine.close();engine.close()}
            check(runCatching{engine.decode(points)}.isFailure)
            report.putBoolean("CLOSED-ENGINE-REFUSED",true)
            // Reopening after close verifies context lifetime and no accidental process-global stale handle.
            SwipeEngine.open(File(targetContext.noBackupFilesDir,"swipe-fixtures/swipe-model.bin"),shape,words,IntArray(words.size){200}).use {
                check("hello" in it.decode(points));report.putBoolean("REOPEN-NEURAL-ENGINE",true)
            }
            check(runCatching{SwipeNative.decode(0,SwipeInputBounds.pack(points),3)}.isFailure)
            check(runCatching{SwipeNative.decode(0,floatArrayOf(Float.NaN,0f,0f,1f,1f,1f),3)}.isFailure)
            check(runCatching{SwipeNative.open(ByteArray(0),geometry,.45f,arrayOf("hello"),intArrayOf(200))}.isFailure)
            report.putBoolean("RAW-JNI-INVALID-INPUT-REFUSED",true)
            val corrupt=File.createTempFile("bad-swipe-",".bin",targetContext.noBackupFilesDir)
            try {
                val bytes=SwipeModel.verifiedBytes(File(targetContext.noBackupFilesDir,"swipe-fixtures/swipe-model.bin"));bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();corrupt.writeBytes(bytes)
                check(runCatching{SwipeEngine.open(corrupt,shape,words,IntArray(words.size){200})}.isFailure)
                report.putBoolean("SAME-SIZE-CORRUPT-MODEL-REFUSED",true)
            }finally{check(corrupt.delete())}
            val verified=SwipeModel.verifiedBytes(File(targetContext.noBackupFilesDir,"swipe-fixtures/swipe-model.bin"))
            val raw=SwipeNative.open(verified,geometry,.45f,words.toTypedArray(),IntArray(words.size){200})
            val gate=java.util.concurrent.CountDownLatch(1)
            val failure=java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val decoder=Thread {
                try { gate.await();repeat(20){
                    try{SwipeNative.decode(raw,SwipeInputBounds.pack(points),3)}catch(expected:IllegalArgumentException){ /* Close may win; stale handles must fail, never dereference freed memory. */ }
                } }catch(error:Throwable){failure.set(error)}
            }
            val closer=Thread { try{gate.await();SwipeNative.close(raw);SwipeNative.close(raw)}catch(error:Throwable){failure.set(error)} }
            decoder.start();closer.start();gate.countDown();decoder.join(5000);closer.join(5000)
            check(!decoder.isAlive&&!closer.isAlive&&failure.get()==null)
            check(runCatching{SwipeNative.decode(raw,SwipeInputBounds.pack(points),3)}.isFailure)
            report.putBoolean("RAW-JNI-CLOSE-DECODE-RACE",true)
            val boundary=SwipeNative.open(verified,geometry,.45f,arrayOf("ab".repeat(20)),intArrayOf(200))
            SwipeNative.close(boundary)
            repeat(5) {
                check(runCatching{SwipeNative.open(verified,geometry,.45f,arrayOf("hello","ab".repeat(20)+"a"),intArrayOf(200,100))}.isFailure)
                check(runCatching{SwipeNative.open(verified,FloatArray(104),.45f,arrayOf("hello"),intArrayOf(200))}.isFailure)
            }
            report.putBoolean("NATIVE-40-41-PATH-BOUNDARY",true)
            val handles=java.util.Collections.synchronizedList(mutableListOf<Long>())
            val refused=java.util.concurrent.atomic.AtomicInteger()
            val start=java.util.concurrent.CountDownLatch(1)
            val workers=(0 until 6).map { Thread {
                try { start.await();handles.add(SwipeNative.open(verified,geometry,.45f,arrayOf("hello"),intArrayOf(200))) }
                catch(expected:IllegalArgumentException){refused.incrementAndGet()}
                catch(error:Throwable){failure.set(error)}
            } }
            try {
                workers.forEach{it.start()};start.countDown();workers.forEach{it.join(5000)}
                check(workers.none{it.isAlive}&&failure.get()==null&&handles.size==4&&refused.get()==2)
                check(runCatching{SwipeNative.open(verified,geometry,.45f,arrayOf("hello"),intArrayOf(200))}.isFailure)
            }finally{handles.toList().forEach{SwipeNative.close(it)}}
            val recovered=SwipeNative.open(verified,geometry,.45f,arrayOf("hello"),intArrayOf(200));SwipeNative.close(recovered)
            report.putBoolean("CAPACITY-CONCURRENT-OPEN-FAILURE-RECOVERY",true)
            report.putString("result","PASS named native integration controls only; synthetic trace/tiny lexicon; not human swipe, device speed or Gboard quality")
            result=Activity.RESULT_OK
        }catch(error:Throwable){report.putString("failure",error.javaClass.simpleName+": "+error.message)}
        finish(result,report)
    }
}
