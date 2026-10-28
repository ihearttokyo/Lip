package dev.lip.keyboard

/** One rendered consent/erasure prompt, consumed before action; cached nodes cannot replay it. */
class ClipboardConfirmation {
    data class Prompt internal constructor(val kind:String,internal val serial:Long)
    private var serial=0L
    var current:Prompt?=null;private set
    fun begin(kind:String):Prompt{require(kind in setOf("enable","clear","off"));return Prompt(kind,++serial).also{current=it}}
    fun cancel(){serial++;current=null}
    fun claim(prompt:Prompt?):Boolean{
        if(prompt==null||current!==prompt||prompt.serial!=serial)return false
        cancel();return true
    }
}
