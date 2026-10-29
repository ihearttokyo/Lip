package dev.lip.keyboard

/** A WRAP request is not evidence of the old full viewport having traversed. */
object FloatingWindowTransition {
    fun dockTraversed(attached:Boolean,laidOut:Boolean,frameHeight:Int,decorHeight:Int,navigationHeight:Int,wrapRequested:Boolean):Boolean =
        attached&&laidOut&&wrapRequested&&frameHeight>0&&decorHeight>0&&navigationHeight in 0..decorHeight&&
            decorHeight.toLong()<=frameHeight.toLong()+navigationHeight
}
