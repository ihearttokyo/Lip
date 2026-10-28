package dev.lip.keyboard

class GifRequest internal constructor(val item:GifItem,internal val ticket:ImeTicket,internal val connection:Any)
/** One accepted attachment at a time; no silent supersession, retry or clipboard/text fallback. */
class GifRequestOwner(private val session:ImeSession){
    var current:GifRequest?=null;private set
    fun begin(item:GifItem,cursor:ImeCursor?,connection:Any?,mimeTypes:Array<String>?):GifRequest?{
        if(current!=null||connection==null||!GifCatalog.accepts(mimeTypes))return null
        return session.capture(cursor)?.let{GifRequest(item,it,connection).also{current=it}}
    }
    fun cancel(){current=null}
    fun claim(request:GifRequest,cursor:ImeCursor?,connection:Any?,mimeTypes:Array<String>?):Boolean{
        if(current!==request)return false
        current=null
        return connection===request.connection&&GifCatalog.accepts(mimeTypes)&&session.claim(request.ticket,cursor)
    }
}
