package dev.lip.keyboard

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SavedClip(val id:String,val text:String,val time:Long,val pinned:Boolean=false)

/** Bounded text ledger. Pinned items deliberately outlive TTL; clipboard origins are never inferred. */
object ClipboardHistory {
    const val MAX_ITEMS=20
    const val MAX_PINS=10
    const val MAX_CHARS=4096
    const val TTL_MS=60*60*1000L
    fun admissible(text:String):Boolean {
        if(text.length !in 1..MAX_CHARS||text.isBlank())return false
        var i=0
        while(i<text.length){
            val c=text[i++]
            if(c.isHighSurrogate()){if(i==text.length||!text[i++].isLowSurrogate())return false}
            else if(c.isLowSurrogate())return false
        }
        return true
    }
    fun live(entry:SavedClip,now:Long)=entry.pinned||(now>=entry.time&&now-entry.time<TTL_MS)
    fun prune(entries:List<SavedClip>,now:Long)=entries.filter{live(it,now)}
    fun add(entries:List<SavedClip>,text:String,now:Long,id:String=UUID.randomUUID().toString()):List<SavedClip>{
        require(now>=0&&admissible(text))
        val live=prune(entries,now);val prior=live.firstOrNull{it.text==text}
        val next=(listOf(SavedClip(prior?.id?:id,text,now,prior?.pinned?:false))+live.filter{it.text!=text}).toMutableList()
        while(next.size>MAX_ITEMS){val victim=next.indexOfLast{!it.pinned};check(victim>=0);next.removeAt(victim)}
        return next
    }
    fun setPinned(entries:List<SavedClip>,id:String,pinned:Boolean,now:Long):List<SavedClip>{
        val live=prune(entries,now);val selected=live.firstOrNull{it.id==id}?:return live
        if(selected.pinned==pinned)return live
        check(!pinned||live.count{it.pinned}<MAX_PINS){"Pin limit reached"}
        return live.map{if(it.id==id)it.copy(pinned=pinned,time=now)else it}
    }
    fun pin(entries:List<SavedClip>,id:String,now:Long):List<SavedClip> =
        setPinned(entries,id,!(entries.firstOrNull{it.id==id}?.pinned?:false),now)
    fun encode(entries:List<SavedClip>):String {
        validate(entries)
        return JSONObject().put("version",1).put("items",JSONArray(entries.map{
            JSONObject().put("id",it.id).put("text",it.text).put("time",it.time).put("pinned",it.pinned)
        })).toString()
    }
    fun decode(value:String):List<SavedClip>{
        require(value.length<=1024*1024){"History payload too large"}
        val root=JSONObject(value)
        require(root.keys().asSequence().toSet()==setOf("version","items")&&root.get("version")==1)
        val array=root.getJSONArray("items");require(array.length()<=MAX_ITEMS)
        return (0 until array.length()).map{i->
            val item=array.getJSONObject(i)
            require(item.keys().asSequence().toSet()==setOf("id","text","time","pinned"))
            val time=item.get("time");require(time is Long||time is Int)
            require(item.get("id") is String&&item.get("text") is String&&item.get("pinned") is Boolean)
            SavedClip(item.getString("id"),item.getString("text"),(time as Number).toLong(),item.getBoolean("pinned"))
        }.also(::validate)
    }
    private fun validate(entries:List<SavedClip>){
        require(entries.size<=MAX_ITEMS&&entries.count{it.pinned}<=MAX_PINS)
        require(entries.map{it.id}.distinct().size==entries.size&&entries.map{it.text}.distinct().size==entries.size)
        require(entries.all{it.id.matches(Regex("[a-f0-9-]{36}"))&&it.time>=0&&admissible(it.text)})
    }
}

/** Caller serializes on an executor; global lock also fences overlapping service incarnations. */
class ClipboardHistoryRepository(private val read:()->String?,private val write:(String)->Unit,private val erase:()->Unit){
    fun update(now:Long,authorized:()->Boolean,transform:(List<SavedClip>)->List<SavedClip> = {it}):List<SavedClip> = synchronized(lock){
        check(authorized()){"History operation revoked"}
        val original=read();val before=original?.let(ClipboardHistory::decode)?:emptyList()
        val after=ClipboardHistory.prune(transform(ClipboardHistory.prune(before,now)),now)
        val encoded=ClipboardHistory.encode(after)
        check(authorized()){"History operation revoked"}
        if(original!=encoded){write(encoded);check(read()==encoded){"History save could not be verified"}}
        check(authorized()){"History operation revoked"}
        after
    }
    fun clear()=synchronized(lock){erase();check(read()==null){"History erasure could not be verified"}}
    companion object{private val lock=Any()}
}
