package dev.lip.keyboard

import android.content.Context

/** One process-shared confirmation boundary across reconstructed Activity/IME store wrappers. */
class KeyboardPreferencesStore(context:Context) {
    init {
        synchronized(lock){
            if(shared==null){
                val prefs=context.applicationContext.getSharedPreferences("keyboard_presentation_v1",Context.MODE_PRIVATE)
                shared=KeyboardPreferenceRepository({prefs.all}){value->
                    prefs.edit().putInt("version",3).putString("hand",value.hand.name).putString("height",value.height.name)
                        .putString("theme",value.theme.name).putBoolean("haptics",value.haptics)
                        .putString("keyLabels",value.keyLabels.name).putString("emojiPreview",value.emojiPreview.name).putInt("heightPercent",value.heightPercent).commit()
                }
            }
        }
    }
    fun load()=synchronized(lock){checkNotNull(shared).load()}
    /** commit(false) may already mutate SharedPreferences memory: never read that failed cache as confirmed. */
    fun save(value:KeyboardPreferences):Boolean = synchronized(lock){checkNotNull(shared).save(value)}
    fun update(transform:(KeyboardPreferences)->KeyboardPreferences):Boolean=synchronized(lock){checkNotNull(shared).update(transform)}
    fun saveHeight(percent:Int):Boolean=synchronized(lock){checkNotNull(shared).saveHeight(percent)}
    companion object {
        private val lock=Any()
        private var shared:KeyboardPreferenceRepository?=null
    }
}
