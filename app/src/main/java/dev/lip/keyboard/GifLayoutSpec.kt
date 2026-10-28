package dev.lip.keyboard

/** Pixel budgets only. Android measures the actual wrapped text and tile heights. */
object GifLayoutSpec {
    fun columns(width:Int,density:Float):Int {
        require(width>0&&density.isFinite()&&density>0)
        return (width/(128.0*density)).toInt().coerceIn(1,4)
    }

    fun panelHeight(base:Int,available:Int,header:Int,intro:Int,firstRow:Int):Int {
        require(base>0&&available>0&&header>0&&intro>=0&&firstRow>=0)
        val firstScreen=header.toLong()+intro.toLong()+firstRow.toLong()
        return maxOf(base.toLong(),firstScreen).coerceAtMost(available.toLong()).toInt()
    }
}
