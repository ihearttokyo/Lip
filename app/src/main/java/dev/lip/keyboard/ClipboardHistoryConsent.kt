package dev.lip.keyboard

import java.util.UUID

data class ClipboardConsent(val enabled:Boolean,val erasing:Boolean,val token:String?=null)
enum class ClipboardEraseResult { APPLIED, STALE, FAILED }

/** Process-wide fence survives controller reconstruction and cached-but-failed preference commits. */
class ClipboardConsentOwner {
    internal val lock=Any()
    internal var initialized=false
    @Volatile internal var failed=false
    @Volatile internal var enabled=false
    @Volatile internal var erasing=false
    internal var failedIntent:ClipboardConsent?=null
    companion object{internal val process=ClipboardConsentOwner()}
}
class ClipboardHistoryConsent(
    private val read:()->ClipboardConsent,private val commit:(ClipboardConsent)->Boolean,
    private val owner:ClipboardConsentOwner=ClipboardConsentOwner.process,
){
    init{synchronized(owner.lock){if(!owner.initialized){val state=read();owner.enabled=state.enabled;owner.erasing=state.erasing;owner.initialized=true}}}
    val persistenceFailed get()=owner.failed
    fun capturePermitted()=!owner.failed&&owner.enabled&&!owner.erasing // No lock taken inside a repository transaction.
    fun current()=synchronized(owner.lock){read()}
    private fun save(value:ClipboardConsent):Boolean{
        val success=runCatching{commit(value)&&read()==value}.getOrDefault(false)
        if(success){owner.enabled=value.enabled;owner.erasing=value.erasing;owner.failedIntent=null;owner.failed=false}
        else{owner.failedIntent=value;owner.failed=true}
        return success
    }
    fun enable():Boolean=synchronized(owner.lock){
        if(read().erasing)return@synchronized false
        save(ClipboardConsent(true,false,UUID.randomUUID().toString()))
    }
    fun requestErase(restoreEnabled:Boolean):String?=synchronized(owner.lock){
        val value=ClipboardConsent(restoreEnabled,true,UUID.randomUUID().toString())
        if(save(value))value.token else null
    }
    fun retryFailed():Boolean=synchronized(owner.lock){
        val intent=owner.failedIntent?:return@synchronized !owner.failed
        save(intent)
    }
    /** Compare and finalize under one owner lock; an older eraser never adopts a newer tombstone. */
    fun finishErase(repository:ClipboardHistoryRepository,token:String):ClipboardEraseResult=synchronized(owner.lock){
        val state=read()
        if(owner.failed)return@synchronized ClipboardEraseResult.FAILED
        if(!state.erasing||state.token!=token)return@synchronized ClipboardEraseResult.STALE
        repository.clear()
        if(save(state.copy(erasing=false)))ClipboardEraseResult.APPLIED else ClipboardEraseResult.FAILED
    }
}
