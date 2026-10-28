package dev.lip.keyboard

/** Explicit host editing commands only; no clipboard reader, cache, observer or history. */
data class ClipboardAvailability(val copy:Boolean,val paste:Boolean)
object ClipboardActions {
    fun available(inputType:Int,imeOptions:Int,start:Int,end:Int,connected:Boolean):ClipboardAvailability {
        val allowed=connected&&(inputType and 0xf) in 1..4&&
            !ImeFieldPolicy.forInput(inputType,imeOptions).protected&&imeOptions and 0x1000000==0
        return ClipboardAvailability(allowed&&start>=0&&end>=0&&start!=end,allowed)
    }
}
