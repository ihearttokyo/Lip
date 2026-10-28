package dev.lip.keyboard

/** Separate UI-only schema. No text, pack identifiers, image contents or receiver settings. */
internal object StickerPreferences {
    private val keys=setOf("version","previewScale")
    fun encode(scale:KeyboardScale):Map<String,Any> = mapOf("version" to 1,"previewScale" to scale.name)
    fun decode(values:Map<String,*>):KeyboardScale {
        if(values.keys!=keys||values["version"]!=1)return KeyboardScale.SYSTEM
        val name=values["previewScale"] as? String?:return KeyboardScale.SYSTEM
        return KeyboardScale.entries.firstOrNull{it.name==name}?:KeyboardScale.SYSTEM
    }
}

/** A failed SharedPreferences commit may mutate its memory; only confirmed writes advance consumers. */
internal class StickerPreferenceRepository(read:()->Map<String,*>,private val write:(KeyboardScale)->Boolean) {
    private var confirmed=runCatching{StickerPreferences.decode(read())}.getOrDefault(KeyboardScale.SYSTEM)
    @Synchronized fun load():KeyboardScale=confirmed
    @Synchronized fun save(scale:KeyboardScale):Boolean {
        val saved=runCatching{write(scale)}.getOrDefault(false)
        if(saved)confirmed=scale
        return saved
    }
}
