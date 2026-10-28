package dev.lip.keyboard

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout

/** Intercepts only a deliberate letter-to-letter glide; ordinary keys/long-press stay native. */
class SwipeKeyboardFrame(context:Context,private val enabled:()->Boolean,
    private val prepare:(SwipeGeometry)->Unit,private val begin:()->Boolean,
    private val finish:(SwipeGeometry,List<SwipePoint>)->Unit,private val cancel:()->Unit,private val refused:()->Unit):FrameLayout(context) {
    private data class Layout(val shape:SwipeGeometry,val top:Float,val height:Float,val letters:List<View>)
    private var inputPaused=false
    private var blocked=false
    fun setInputPaused(value:Boolean){
        inputPaused=value
        for(i in 0 until childCount)getChildAt(i).importantForAccessibility=if(value)View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        contentDescription=if(value)"Keyboard input paused while the accepted swipe is decoding. Wait or tap Cancel." else null
        importantForAccessibility=if(value)View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }
    private var layout:Layout?=null
    private var prepared:SwipeGeometry?=null
    private var tracking=false;private var gliding=false;private var downTime=0L
    private var pointer=-1;private var startX=0f;private var startY=0f
    private val points=ArrayList<SwipePoint>()
    private fun measured():Layout? {
        if(width<=0)return null
        val views=HashMap<Char,View>()
        fun collect(v:View){
            val tag=v.tag as? String
            if(tag?.startsWith("swipe:")==true && tag.length==7 && v.width>0 && v.height>0 && v.visibility==View.VISIBLE)views[tag.last()]=v
            if(v is ViewGroup)for(i in 0 until v.childCount)collect(v.getChildAt(i))
        }
        for(i in 0 until childCount)collect(getChildAt(i))
        if(views.size!=26)return null
        val origin=IntArray(2);getLocationInWindow(origin)
        val locations=views.mapValues{(_,v)->IntArray(2).also{v.getLocationInWindow(it);it[0]-=origin[0];it[1]-=origin[1]}}
        val top=locations.values.minOf{it[1]}.toFloat();val bottom=views.maxOf{(c,v)->locations.getValue(c)[1]+v.height}.toFloat()
        if(bottom<=top)return null
        val height=bottom-top;val gap=KeyboardVisualTokens.KEY_GAP_DP*resources.displayMetrics.density
        val rects=FloatArray(104)
        SwipeModel.LETTERS.forEachIndexed{i,c->val v=views.getValue(c);val p=locations.getValue(c)
            rects[i*4]=(p[0]+v.width/2f)/width;rects[i*4+1]=(p[1]+v.height/2f-top)/height
            rects[i*4+2]=(v.width/2f-gap)/width;rects[i*4+3]=(v.height/2f-gap)/height
        }
        return Layout(SwipeGeometry(rects,height/width),top,height,views.values.toList())
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){
        super.onLayout(changed,l,t,r,b)
        if(enabled())measured()?.let{actual->
            val previous=prepared
            if(previous==null||previous.aspect!=actual.shape.aspect||!previous.rectangles.contentEquals(actual.shape.rectangles)){
                prepared=actual.shape;post{if(isAttachedToWindow&&enabled())prepare(actual.shape)}
            }
        }
    }
    private fun add(event:MotionEvent):Boolean {
        val bounds=layout?:return false;val at=event.findPointerIndex(pointer);if(at<0)return false
        fun sample(x:Float,y:Float,time:Long):Boolean {
            val point=SwipePoint(x/width,(y-bounds.top)/bounds.height,(time-downTime).toFloat())
            if(points.size>=4096 || point.x !in -.25f..1.25f || point.y !in -.25f..1.25f || point.milliseconds !in 0f..30000f)return false
            if(points.isEmpty()||points.last()!=point)points.add(point)
            return true
        }
        for(h in 0 until event.historySize)if(!sample(event.getHistoricalX(at,h),event.getHistoricalY(at,h),event.getHistoricalEventTime(h)))return false
        return sample(event.getX(at),event.getY(at),event.eventTime)
    }
    fun abort(){val active=gliding;tracking=false;gliding=false;blocked=false;pointer=-1;layout=null;points.clear();if(active)cancel()}
    override fun onInterceptTouchEvent(event:MotionEvent):Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN){
            abort();if(inputPaused){blocked=true;refused();return true};if(!enabled())return false
            val actual=measured()?:return false
            if(event.y<actual.top||event.y>=actual.top+actual.height)return false
            val locations=IntArray(2);getLocationInWindow(locations)
            if(actual.letters.none{v->val p=IntArray(2);v.getLocationInWindow(p);event.x>=p[0]-locations[0]&&event.x<p[0]-locations[0]+v.width&&event.y>=p[1]-locations[1]&&event.y<p[1]-locations[1]+v.height})return false
            layout=actual;pointer=event.getPointerId(0);downTime=event.eventTime;startX=event.x;startY=event.y;tracking=add(event);return false
        }
        if(!tracking)return false
        if(event.pointerCount!=1||event.actionMasked==MotionEvent.ACTION_CANCEL){abort();return false}
        if(event.actionMasked==MotionEvent.ACTION_MOVE){
            if(event.eventTime-downTime>=ViewConfiguration.getLongPressTimeout()){abort();return false}
            if(!add(event)){abort();return false}
            if(kotlin.math.hypot(event.x-startX,event.y-startY)>=width*.05f){
                if(!begin()){abort();return false}
                gliding=true;return true // Framework sends CANCEL to the original Button.
            }
        }
        if(event.actionMasked==MotionEvent.ACTION_UP)abort()
        return false
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(blocked){if(event.actionMasked==MotionEvent.ACTION_UP||event.actionMasked==MotionEvent.ACTION_CANCEL){blocked=false};return true}
        if(!gliding)return false
        if(event.pointerCount!=1||event.actionMasked==MotionEvent.ACTION_CANCEL||!enabled()){abort();return true}
        if(!add(event)){abort();return true}
        if(event.actionMasked==MotionEvent.ACTION_UP){
            val actual=layout;val trace=points.toList();tracking=false;gliding=false;pointer=-1;layout=null;points.clear()
            if(actual!=null&&trace.size>=2&&trace.last().milliseconds>trace.first().milliseconds)finish(actual.shape,trace)else cancel()
        }
        return true
    }
    override fun onDetachedFromWindow(){abort();super.onDetachedFromWindow()}
}
