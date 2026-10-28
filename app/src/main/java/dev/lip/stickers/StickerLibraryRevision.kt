package dev.lip.stickers

import java.util.concurrent.CopyOnWriteArraySet

/** Process-local cache/request admission only; durable integrity is still checked
 * by the store. No lock covers storage, listeners, decoder work or Android IPC.
 */
internal object StickerLibraryRevision {
    private val lock=Any()
    private var token=Any()
    private var writers=0
    private val listeners=CopyOnWriteArraySet<()->Unit>()
    fun snapshot():Any?=synchronized(lock){if(writers==0)token else null}
    fun isCurrent(snapshot:Any)=synchronized(lock){writers==0&&token===snapshot}
    fun beginChange(){synchronized(lock){writers++;token=Any()};notifyChanged()}
    fun endChange(){synchronized(lock){check(writers>0);writers--;token=Any()};notifyChanged()}
    fun observe(listener:()->Unit){listeners.add(listener)}
    fun unobserve(listener:()->Unit){listeners.remove(listener)}
    private fun notifyChanged(){listeners.forEach{runCatching{it()}}}
}
