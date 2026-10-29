package dev.lip.keyboard

/** UI metadata only, in a namespace separate from docked presentation preferences. */
enum class FloatingKeyboardMode { DOCKED, FLOATING }

data class FloatingKeyboardPreferences(val mode:FloatingKeyboardMode=FloatingKeyboardMode.DOCKED,
    val xBp:Int=5000,val yBp:Int=10000,val widthBp:Int=9000,val heightBp:Int=4500) {
    init { require(xBp in 0..10000&&yBp in 0..10000&&widthBp in 1..10000&&heightBp in 1..10000){"Invalid floating geometry."} }
    fun encode():Map<String,Any> = mapOf("version" to 1,"mode" to mode.name,"xBp" to xBp,"yBp" to yBp,"widthBp" to widthBp,"heightBp" to heightBp)
    companion object {
        private val keys=setOf("version","mode","xBp","yBp","widthBp","heightBp")
        internal fun decodeChecked(values:Map<String,*>):Pair<FloatingKeyboardPreferences,Boolean> {
            if(values.isEmpty())return FloatingKeyboardPreferences() to true
            if(values.keys!=keys||values["version"]!=1)return FloatingKeyboardPreferences() to false
            val mode=FloatingKeyboardMode.entries.firstOrNull{it.name==values["mode"]}?:return FloatingKeyboardPreferences() to false
            val x=values["xBp"] as? Int?:return FloatingKeyboardPreferences() to false
            val y=values["yBp"] as? Int?:return FloatingKeyboardPreferences() to false
            val w=values["widthBp"] as? Int?:return FloatingKeyboardPreferences() to false
            val h=values["heightBp"] as? Int?:return FloatingKeyboardPreferences() to false
            if(x !in 0..10000||y !in 0..10000||w !in 1..10000||h !in 1..10000)return FloatingKeyboardPreferences() to false
            return FloatingKeyboardPreferences(mode,x,y,w,h) to true
        }
        fun decode(values:Map<String,*>)=decodeChecked(values).first
    }
}
data class FloatingKeyboardSnapshot(val value:FloatingKeyboardPreferences,val valid:Boolean,val revision:Long)

/** Process-local confirmed authority. A failed backend write may mutate its cache;
 * this repository never rereads that cache as a successful confirmation.
 */
class FloatingKeyboardPreferenceRepository(read:()->Map<String,*>,private val write:(FloatingKeyboardPreferences)->Boolean) {
    private val initial=runCatching{FloatingKeyboardPreferences.decodeChecked(read())}.getOrElse{FloatingKeyboardPreferences() to false}
    private var confirmed=FloatingKeyboardSnapshot(initial.first,initial.second,0)
    private var mutating=false
    @Synchronized fun snapshot()=confirmed
    @Synchronized fun load()=confirmed.value
    @Synchronized fun save(value:FloatingKeyboardPreferences,expectedRevision:Long):Boolean=change(expectedRevision){value}
    @Synchronized fun update(transform:(FloatingKeyboardPreferences)->FloatingKeyboardPreferences):Boolean=change(null,transform)
    @Synchronized fun setMode(mode:FloatingKeyboardMode):Boolean=change(null){it.copy(mode=mode)}
    @Synchronized fun saveGeometry(value:FloatingKeyboardPreferences,expectedRevision:Long):Boolean=change(expectedRevision){
        it.copy(xBp=value.xBp,yBp=value.yBp,widthBp=value.widthBp,heightBp=value.heightBp)
    }
    /** Only CPU field transforms belong here; no UI/IPC callbacks or eager draft writes. */
    private fun change(expectedRevision:Long?,transform:(FloatingKeyboardPreferences)->FloatingKeyboardPreferences):Boolean {
        if(mutating||(expectedRevision!=null&&expectedRevision!=confirmed.revision)||confirmed.revision==Long.MAX_VALUE)return false
        mutating=true
        try {
            val value=runCatching{transform(confirmed.value)}.getOrNull()?:return false
            if(!runCatching{write(value)}.getOrDefault(false))return false
            confirmed=FloatingKeyboardSnapshot(value,true,confirmed.revision+1);return true
        }finally{mutating=false}
    }
}
