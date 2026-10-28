package dev.lip.keyboard

/** Explicit manual pick guard; never reads host text, even for private fields. */
class EmojiPickOwner(private val session:ImeSession){
    class Ticket internal constructor(internal val generation:Pair<Long,Long>,internal val connection:Any,internal val panel:Any,internal val start:Int,internal val end:Int){internal var used=false}
    fun capture(connection:Any?,panel:Any,start:Int,end:Int):Ticket? {
        if(connection==null||start<0||end<0)return null
        return session.manualGeneration()?.let{Ticket(it,connection,panel,start,end)}
    }
    fun current(ticket:Ticket,connection:Any?,panel:Any?,start:Int,end:Int)=!ticket.used&&ticket.connection===connection&&ticket.panel===panel&&ticket.generation==session.manualGeneration()&&ticket.start==start&&ticket.end==end
    fun claim(ticket:Ticket,connection:Any?,panel:Any?,start:Int,end:Int):Boolean {
        if(!current(ticket,connection,panel,start,end))return false
        ticket.used=true;return true
    }
    fun claimedCurrent(ticket:Ticket,connection:Any?,panel:Any?,start:Int,end:Int)=ticket.used&&ticket.connection===connection&&ticket.panel===panel&&ticket.generation==session.manualGeneration()&&ticket.start==start&&ticket.end==end
}
