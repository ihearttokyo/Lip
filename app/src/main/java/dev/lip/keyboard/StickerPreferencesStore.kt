package dev.lip.keyboard

import android.content.Context

/** Process-shared confirmation fence, independent of the frozen keyboard_presentation_v1 protocol. */
internal class StickerPreferencesStore(context:Context) {
    init {
        synchronized(lock){
            if(shared==null){
                val prefs=context.applicationContext.getSharedPreferences("sticker_presentation_v1",Context.MODE_PRIVATE)
                shared=StickerPreferenceRepository({prefs.all}){scale->
                    val current=prefs.all
                    // Never clear unknown fields or downgrade a future schema implicitly.
                    if(current.keys.any{it !in setOf("version","previewScale")}||
                        current.containsKey("version")&&current["version"]!=1)false
                    else prefs.edit().putInt("version",1).putString("previewScale",scale.name).commit()
                }
            }
        }
    }
    fun load():KeyboardScale=synchronized(lock){checkNotNull(shared).load()}
    fun save(scale:KeyboardScale):Boolean=synchronized(lock){checkNotNull(shared).save(scale)}
    companion object {
        private val lock=Any()
        private var shared:StickerPreferenceRepository?=null
    }
}
