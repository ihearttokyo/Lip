package dev.lip.keyboard

import java.util.Collections
import java.util.concurrent.CopyOnWriteArraySet

/** Stable action IDs, never a persisted slot index or provider permission. */
enum class ToolbarAction(val label:String,val description:String,val configurable:Boolean=true) {
    TOOLS("Tools","Lip keyboard menu",false),
    AGENT("Agent / future translation","Local translation not yet available in this English preview"),
    EMOJI("Emoji","Show emoji"), GIF("Local GIFs","Show local GIF catalog"),
    CLIPBOARD("Clipboard","Show clipboard actions and optional encrypted text history"),
    UNDO("Undo / Redo","Show adjacent Lip-owned Undo and Redo; cancel pending dictation"),
    TEXT_EDITING("Text editing","Open text editing"), STICKERS("Local sticker packs","Open local sticker packs"),
    MICROPHONE("Microphone","Record with the selected local Bark model",false)
}
class ToolbarPreferences(slots:List<ToolbarAction> = DEFAULT) {
    val slots:List<ToolbarAction> = Collections.unmodifiableList(ArrayList(slots))
    init{require(this.slots.size==5&&this.slots.distinct().size==5&&this.slots.all{it.configurable})}
    fun encode():Map<String,Any> = mapOf("version" to 1,"slots" to slots.joinToString(","){it.name})
    override fun equals(other:Any?)=other is ToolbarPreferences&&slots==other.slots
    override fun hashCode()=slots.hashCode()
    companion object {
        val DEFAULT:List<ToolbarAction> = Collections.unmodifiableList(listOf(ToolbarAction.AGENT,ToolbarAction.EMOJI,ToolbarAction.GIF,ToolbarAction.CLIPBOARD,ToolbarAction.UNDO))
        fun decode(values:Map<String,*>):ToolbarPreferences? {
            if(values.isEmpty())return ToolbarPreferences()
            if(values.keys!=setOf("version","slots")||values["version"]!=1)return null
            val raw=values["slots"] as? String?:return null
            if(raw.length>128)return null
            val ids=raw.split(',');if(ids.size!=5)return null
            val slots=ids.map{id->ToolbarAction.entries.firstOrNull{it.name==id&&it.configurable}?:return null}
            return runCatching{ToolbarPreferences(slots)}.getOrNull()
        }
    }
}
class ToolbarSnapshot internal constructor(val value:ToolbarPreferences,val valid:Boolean,val revision:Any){override fun toString()="ToolbarSnapshot(metadata-only)"}
/** Only successful commits advance confirmed mapping+revision. Observers never
 * run under repository/store locks; equal successful saves revoke stale UI too.
 */
class ToolbarPreferenceRepository(read:()->Map<String,*>,private val write:(ToolbarPreferences)->Boolean) {
    private var confirmed=runCatching{ToolbarPreferences.decode(read())}.getOrNull().let{ToolbarSnapshot(it?:ToolbarPreferences(),it!=null,Any())}
    private val observers=CopyOnWriteArraySet<()->Unit>()
    @Synchronized fun snapshot()=confirmed
    fun observe(listener:()->Unit){observers.add(listener)}
    fun unobserve(listener:()->Unit){observers.remove(listener)}
    fun save(value:ToolbarPreferences,expectedRevision:Any?=null):Boolean {
        val saved=synchronized(this){
            if(expectedRevision!=null&&confirmed.revision!==expectedRevision)false
            else if(!runCatching{write(value)}.getOrDefault(false))false
            else{confirmed=ToolbarSnapshot(value,true,Any());true}
        }
        if(saved)observers.forEach{if(it in observers)runCatching{it()}}
        return saved
    }
}
/** Both preview and consumer use these exact seven positions. */
object ToolbarActionRegistry {
    fun actions(value:ToolbarPreferences)=listOf(ToolbarAction.TOOLS)+value.slots+ToolbarAction.MICROPHONE
    fun bounds(width:Int,index:Int):Pair<Int,Int>{require(width>=0&&index in 0..6);val left=(width*.15f*index).toInt();return left to (left+(width*.1f).toInt())}
    fun replace(value:ToolbarPreferences,index:Int,action:ToolbarAction):ToolbarPreferences {
        require(index in 0..4&&action.configurable)
        val slots=value.slots.toMutableList();val existing=slots.indexOf(action)
        if(existing>=0)slots[existing]=slots[index]
        slots[index]=action;return ToolbarPreferences(slots)
    }
    fun move(value:ToolbarPreferences,index:Int,delta:Int):ToolbarPreferences {
        require(index in 0..4&&delta in listOf(-1,1)&&index+delta in 0..4)
        val slots=value.slots.toMutableList();Collections.swap(slots,index,index+delta);return ToolbarPreferences(slots)
    }
}
