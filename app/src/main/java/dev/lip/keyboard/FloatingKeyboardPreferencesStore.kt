package dev.lip.keyboard

import android.content.Context
import java.util.concurrent.CopyOnWriteArraySet

/** Separate local namespace; no docked height/hand, editor, media or account access. */
class FloatingKeyboardPreferencesStore(context:Context) {
    init { synchronized(lock) {
        if(shared==null){
            val prefs=context.applicationContext.getSharedPreferences("keyboard_floating_v1",Context.MODE_PRIVATE)
            shared=FloatingKeyboardPreferenceRepository({prefs.all}){value->
                // Complete owned namespace replacement happens only on explicit save.
                prefs.edit().clear().putInt("version",1).putString("mode",value.mode.name)
                    .putInt("xBp",value.xBp).putInt("yBp",value.yBp).putInt("widthBp",value.widthBp).putInt("heightBp",value.heightBp).commit()
            }
        }
    } }
    fun load()=synchronized(lock){checkNotNull(shared).load()}
    fun snapshot()=synchronized(lock){checkNotNull(shared).snapshot()}
    fun save(value:FloatingKeyboardPreferences,expectedRevision:Long)=mutate{it.save(value,expectedRevision)}
    fun setMode(mode:FloatingKeyboardMode)=mutate{it.setMode(mode)}
    fun saveGeometry(value:FloatingKeyboardPreferences,expectedRevision:Long)=mutate{it.saveGeometry(value,expectedRevision)}
    fun update(transform:(FloatingKeyboardPreferences)->FloatingKeyboardPreferences)=mutate{it.update(transform)}
    /** Notifications run OUTSIDE confirmation locks. Consumers marshal to their UI. */
    fun observe(listener:()->Unit){listeners.add(listener)}
    fun unobserve(listener:()->Unit){listeners.remove(listener)}
    private fun mutate(action:(FloatingKeyboardPreferenceRepository)->Boolean):Boolean {
        val saved=synchronized(lock){action(checkNotNull(shared))}
        if(saved)listeners.forEach{runCatching{it()}}
        return saved
    }
    companion object {
        private val lock=Any()
        private var shared:FloatingKeyboardPreferenceRepository?=null
        private val listeners=CopyOnWriteArraySet<()->Unit>()
    }
}
