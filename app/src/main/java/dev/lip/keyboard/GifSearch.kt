package dev.lip.keyboard

/** Ephemeral keyboard-owned query. Its token binds rendered keys so stale search keys never type into an editor. */
class GifSearch {
    var token:Any?=null;private set
    var query="";private set
    val active get()=token!=null
    fun begin(){token=Any()}
    fun finish(){token=null}
    fun clear(){token=null;query=""}
    fun append(value:String){if(active&&query.length+value.length<=64)query+=value}
    fun delete(){if(active&&query.isNotEmpty())query=query.substring(0,query.offsetByCodePoints(query.length,-1))}
}
