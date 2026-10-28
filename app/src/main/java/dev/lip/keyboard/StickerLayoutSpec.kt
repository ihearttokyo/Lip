package dev.lip.keyboard

import kotlin.math.ceil

/** Pure pixel/page bounds. Android measures actual wrapped labels before budgeting height. */
internal object StickerLayoutSpec {
    const val MAX_PAGE_ITEMS=8
    data class Geometry(val columns:Int,val tileWidth:Int,val imageEdge:Int,val gap:Int,val padding:Int)
    fun geometry(width:Int,density:Float,scale:KeyboardScale):Geometry {
        require(width>0&&density.isFinite()&&density>0)
        fun pixels(dp:Double)=ceil(dp*density.toDouble()).coerceIn(1.0,Int.MAX_VALUE.toDouble()).toInt()
        val desired=pixels(96.0*scale.factor.toDouble())
        val requestedPadding=pixels(8.0)
        val gap=pixels(6.0)
        val desiredTile=desired.toLong()+2L*requestedPadding
        val columns=((width.toLong()+gap)/(desiredTile+gap)).coerceIn(1,4).toInt()
        val tileWidth=((width.toLong()-gap.toLong()*(columns-1))/columns).coerceAtLeast(1).toInt()
        val padding=minOf(requestedPadding,(tileWidth-1)/2)
        val edge=minOf(desired,(tileWidth-2*padding).coerceAtLeast(1))
        return Geometry(columns,tileWidth,edge,gap,padding)
    }
    fun panelHeight(base:Int,available:Int,header:Int,intro:Int,firstRow:Int):Int {
        require(base>0&&available>0&&header>0&&intro>=0&&firstRow>=0)
        return maxOf(base.toLong(),header.toLong()+intro+firstRow).coerceAtMost(available.toLong()).toInt()
    }
    fun validatePage(offset:Int,size:Int,total:Int,maximum:Int) {
        require(maximum in 1..128&&total in 0..maximum&&size in 0..MAX_PAGE_ITEMS)
        require(offset>=0&&offset%MAX_PAGE_ITEMS==0)
        if(total==0)require(offset==0&&size==0)
        else require(offset<total&&size==minOf(MAX_PAGE_ITEMS,total-offset))
    }
}
