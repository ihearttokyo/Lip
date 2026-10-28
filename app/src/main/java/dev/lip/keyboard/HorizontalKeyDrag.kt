package dev.lip.keyboard

import kotlin.math.abs

/** Geometry only. No text, editor coordinates, clock polling or background timers. */
class HorizontalKeyDrag(private val slop:Float,private val quantum:Float,private val holdMillis:Long) {
    data class Update(val consume:Boolean,val begin:Boolean=false,val steps:Int=0)
    private enum class Phase { IDLE, PENDING, ACTIVE, CANCELED, ENDED }
    private var phase=Phase.IDLE
    val active get()=phase==Phase.ACTIVE
    private var x=0f;private var y=0f;private var time=0L;private var position=0;private var spent=0
    init{require(slop.isFinite()&&slop>0&&quantum.isFinite()&&quantum>slop&&holdMillis>0)}
    fun down(x:Float,y:Float,time:Long){
        this.x=x;this.y=y;this.time=time;position=0;spent=0
        phase=if(x.isFinite()&&y.isFinite())Phase.PENDING else Phase.CANCELED
    }
    fun cancel(){phase=Phase.CANCELED}
    fun move(x:Float,y:Float,time:Long,pointers:Int):Update {
        if(phase==Phase.IDLE||phase==Phase.ENDED)return Update(false)
        if(phase==Phase.CANCELED)return Update(true)
        val dx=x-this.x;val dy=y-this.y
        if(pointers!=1||!dx.isFinite()||!dy.isFinite()||time<this.time||time-this.time>30_000||spent>=512||abs(dy)>quantum*3){cancel();return Update(true)}
        var begin=false
        if(phase==Phase.PENDING){
            if(time-this.time>=holdMillis){cancel();return Update(true)}
            if(abs(dy)>slop&&abs(dy)>abs(dx)){cancel();return Update(true)}
            if(abs(dx)<slop||abs(dx)<abs(dy)*1.5f)return Update(false)
            phase=Phase.ACTIVE;begin=true
        }
        val target=(dx/quantum).toInt().coerceIn(-128,128)
        val limit=minOf(16,512-spent)
        val delta=(target-position).coerceIn(-limit,limit);position+=delta;spent+=abs(delta)
        return Update(true,begin,delta)
    }
    fun up(x:Float,y:Float,pointers:Int):Update {
        val tap=phase==Phase.PENDING&&pointers==1&&x.isFinite()&&y.isFinite()&&abs(x-this.x)<=slop&&abs(y-this.y)<=slop
        phase=Phase.ENDED
        return Update(!tap)
    }
}
