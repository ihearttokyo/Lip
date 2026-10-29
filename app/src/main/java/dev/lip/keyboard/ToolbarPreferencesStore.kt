package dev.lip.keyboard

import android.content.Context

/** UI IDs only. No text, clipboard, accounts, permissions or media contents. */
class ToolbarPreferencesStore(context:Context) {
    private val repository:ToolbarPreferenceRepository
    init{
        repository=synchronized(lock){shared?:run{
            val prefs=runCatching{context.applicationContext.getSharedPreferences("keyboard_toolbar_v1",Context.MODE_PRIVATE)}.getOrNull()
            ToolbarPreferenceRepository({prefs?.all?:error("Toolbar settings unavailable.")}){value->
                prefs?.edit()?.clear()?.putInt("version",1)?.putString("slots",value.encode()["slots"] as String)?.commit()==true
            }.also{shared=it}
        }}
    }
    fun snapshot()=repository.snapshot()
    fun save(value:ToolbarPreferences,expectedRevision:Any?=null)=repository.save(value,expectedRevision)
    fun observe(listener:()->Unit)=repository.observe(listener)
    fun unobserve(listener:()->Unit)=repository.unobserve(listener)
    companion object{private val lock=Any();private var shared:ToolbarPreferenceRepository?=null}
}
