package dev.lip.keyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One running/one latest queued job. Cancellation revokes results, never blocks the UI. */
class SwipeDecodeWorker(context:Context) {
    private val context=context.applicationContext
    private val main=Handler(Looper.getMainLooper())
    private val executor=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(1),ThreadPoolExecutor.DiscardOldestPolicy())
    @Volatile private var closed=false
    private var engine:SwipeEngine?=null
    private fun engine(shape:SwipeGeometry):SwipeEngine {
        val current=engine
        if(current==null)return SwipeAssets.open(context,shape).also{engine=it}
        current.prepare(shape)
        return current
    }
    private fun mainOnly(){check(Looper.myLooper()==Looper.getMainLooper()){"Swipe queue is owned by the IME main thread"}}
    private fun enqueue(task:()->Unit){if(!closed){executor.queue.clear();executor.execute{if(!closed)task()}}}
    fun prepare(shape:SwipeGeometry){mainOnly();if(!closed&&executor.queue.isEmpty())executor.execute{if(!closed)runCatching{engine(shape)}}}
    fun decode(shape:SwipeGeometry,points:List<SwipePoint>,done:(Result<List<String>>)->Unit){
        mainOnly();val snapshot=points.toList()
        enqueue {
            val result=runCatching {
                val candidates=engine(shape).decodeForGeometry(shape,snapshot)
                val dictionary=SwipeAssets.dictionary(context)
                candidates.map{checkNotNull(dictionary.display(it))}
            }
            main.post{if(!closed)done(result)}
        }
    }
    fun cancelQueued(){mainOnly();executor.queue.clear()}
    fun close(){
        mainOnly();if(closed)return;closed=true;executor.queue.clear()
        executor.execute{engine?.close();engine=null}
        executor.shutdown()
    }
}
