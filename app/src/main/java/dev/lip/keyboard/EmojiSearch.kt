package dev.lip.keyboard

/** Keyboard-owned query; the mode token binds every rendered key, never the host editor. */
class EmojiSearch {
    var token:Any?=null;private set
    var query="";private set
    val active get()=token!=null
    fun begin(){token=Any()}
    fun finish(){token=null}
    fun clear(){token=null;query=""}
    fun append(value:String){
        if(!active||query.length+value.length>64||value.any{it<' '})return
        var i=0
        while(i<value.length){val c=value[i];if(c.isHighSurrogate()){if(i+1>=value.length||!value[i+1].isLowSurrogate())return;i+=2}else{if(c.isLowSurrogate())return;i++}}
        query+=value
    }
    fun delete(){if(active&&query.isNotEmpty())query=query.substring(0,query.offsetByCodePoints(query.length,-1))}
}
