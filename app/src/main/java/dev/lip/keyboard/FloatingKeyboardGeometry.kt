package dev.lip.keyboard

/** Window-local integer geometry only. The caller supplies actual measured page
 * and handle budgets; this solver knows neither display size nor keyboard rows.
 */
object FloatingKeyboardGeometry {
    data class Rect(val left:Int,val top:Int,val right:Int,val bottom:Int) {
        val width:Int get()=(right.toLong()-left).takeIf{it in 1..Int.MAX_VALUE.toLong()}?.toInt()?:0
        val height:Int get()=(bottom.toLong()-top).takeIf{it in 1..Int.MAX_VALUE.toLong()}?.toInt()?:0
    }
    data class Insets(val left:Int,val top:Int,val right:Int,val bottom:Int)
    data class Limits(val minWidth:Int,val minHeight:Int,val maxWidth:Int=Int.MAX_VALUE,val maxHeight:Int=Int.MAX_VALUE)
    sealed class Fit {
        data class Floating(val rect:Rect,val safe:Rect):Fit()
        data class Docked(val reason:String):Fit()
    }
    /** No-fit never mutates/replaces the saved preferred geometry. */
    fun resolve(value:FloatingKeyboardPreferences,window:Rect,insets:Insets,limits:Limits):Fit {
        if(value.mode==FloatingKeyboardMode.DOCKED)return Fit.Docked("docked")
        val safe=safeRect(window,insets)?:return Fit.Docked("invalid_window")
        if(limits.minWidth<=0||limits.minHeight<=0||limits.maxWidth<limits.minWidth||limits.maxHeight<limits.minHeight)return Fit.Docked("invalid_limits")
        val maxW=minOf(limits.maxWidth,safe.width);val maxH=minOf(limits.maxHeight,safe.height)
        if(maxW<limits.minWidth||maxH<limits.minHeight)return Fit.Docked("no_fit")
        val w=scale(safe.width,value.widthBp).coerceIn(limits.minWidth,maxW)
        val h=scale(safe.height,value.heightBp).coerceIn(limits.minHeight,maxH)
        val left=safe.left.toLong()+scale(safe.width-w,value.xBp)
        val top=safe.top.toLong()+scale(safe.height-h,value.yBp)
        return Fit.Floating(Rect(left.toInt(),top.toInt(),(left+w).toInt(),(top+h).toInt()),safe)
    }
    /** Insets and origins are in the SAME actual window coordinate space. */
    fun safeRect(window:Rect,insets:Insets):Rect? {
        if(window.left<0||window.top<0||window.width==0||window.height==0||
            listOf(insets.left,insets.top,insets.right,insets.bottom).any{it<0})return null
        val left=window.left.toLong()+insets.left;val top=window.top.toLong()+insets.top
        val right=window.right.toLong()-insets.right;val bottom=window.bottom.toLong()-insets.bottom
        if(left>=right||top>=bottom||left !in 0..Int.MAX_VALUE.toLong()||top !in 0..Int.MAX_VALUE.toLong()||
            right !in 0..Int.MAX_VALUE.toLong()||bottom !in 0..Int.MAX_VALUE.toLong())return null
        return Rect(left.toInt(),top.toInt(),right.toInt(),bottom.toInt())
    }
    /** Only a real contained draft can become a preference. Positions are relative
     * to available FREE SPACE after the card size, not the entire viewport.
     * Caller retains its original revision/window/contact witness until Apply/UP.
     */
    fun normalize(rect:Rect,safe:Rect,mode:FloatingKeyboardMode=FloatingKeyboardMode.FLOATING):FloatingKeyboardPreferences? {
        if(safe.left<0||safe.top<0||safe.width==0||safe.height==0||rect.width==0||rect.height==0||
            rect.left<safe.left||rect.top<safe.top||rect.right>safe.right||rect.bottom>safe.bottom)return null
        return FloatingKeyboardPreferences(mode,basis(rect.left.toLong()-safe.left,safe.width-rect.width),
            basis(rect.top.toLong()-safe.top,safe.height-rect.height),basis(rect.width.toLong(),safe.width).coerceAtLeast(1),
            basis(rect.height.toLong(),safe.height).coerceAtLeast(1))
    }
    private fun scale(extent:Int,bp:Int):Int=((extent.toLong()*bp+5000)/10000).toInt()
    private fun basis(value:Long,extent:Int):Int=if(extent==0)5000 else ((value*10000+extent/2)/extent).toInt().coerceIn(0,10000)
}
