package dev.lip.keyboard

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.roundToInt

/** Actual full-viewport TRANSPARENT host. Only the measured card is painted and
 * returned as an owned touch region; Android still owns system navigation.
 */
class FloatingKeyboardFrame(context:Context,private val content:View,private val snapshot:FloatingKeyboardSnapshot,
    private val captureOrigin:()->Any?,private val prepare:(Any)->Any?,private val current:(Any)->Boolean,
    private val save:(FloatingKeyboardPreferences,Long,Any,()->Boolean)->Boolean,private val dock:(Long,Any,()->Boolean)->Boolean,
    private val settings:(Any,()->Boolean)->Boolean,private val chrome:()->Int,private val minimumBody:(Int)->Int,
    private val budget:(Int,Int)->Unit,private val noFit:(String)->Unit):FrameLayout(context),AutoCloseable {
    private enum class Kind { MOVE, RESIZE, DOCK, SETTINGS }
    private class Operation(val kind:Kind,var origin:Any,val initial:FloatingKeyboardGeometry.Rect,val viewport:Any,val ticket:Any,val x:Float,val y:Float)
    private val card=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.BLACK)}
    private val header=LinearLayout(context).apply{orientation=LinearLayout.HORIZONTAL;isBaselineAligned=false}
    private var operation:Operation?=null
    private val authority=FloatingHandleAuthority()
    private var fitted:FloatingKeyboardGeometry.Fit.Floating?=null
    private var draft:FloatingKeyboardGeometry.Rect?=null
    private var viewportToken=Any()
    private var viewportKey:List<Int>?=null
    private var appliedInsets:List<Int>?=null
    private var ready=false
    private var closed=false
    private var blocked=false
    private var reportingNoFit=false
    private var lastBudget:Pair<Int,Int>?=null
    private var windowRect=FloatingKeyboardGeometry.Rect(0,0,0,0)
    private var windowInsets=FloatingKeyboardGeometry.Insets(0,0,0,0)
    private var limits=FloatingKeyboardGeometry.Limits(1,1)
    private fun dp(value:Int)=(value*resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
    init{
        setBackgroundColor(Color.TRANSPARENT);isClickable=false;isFocusable=false;clipChildren=true
        fun handle(label:String,description:String,kind:Kind):GlassKeyButton=GlassKeyButton(context,capsule=true).apply{
            text=label;contentDescription=description;minHeight=dp(48);minimumHeight=dp(48);fitText(16)
            interactionAllowed={!closed&&isAttachedToWindow&&windowVisibility==VISIBLE}
            setOnTouchListener{view,event->
                when(event.actionMasked){
                    MotionEvent.ACTION_DOWN->begin(kind,event.rawX,event.rawY)
                    MotionEvent.ACTION_MOVE->move(event.rawX,event.rawY)
                    MotionEvent.ACTION_UP->finish(event.rawX,event.rawY)
                    MotionEvent.ACTION_CANCEL->cancelGesture()
                }
                view.isPressed=event.actionMasked!=MotionEvent.ACTION_UP&&event.actionMasked!=MotionEvent.ACTION_CANCEL
                true // No delayed native click can create another geometry operation.
            }
            accessibilityDelegate=object:View.AccessibilityDelegate(){
                override fun performAccessibilityAction(host:View,action:Int,args:Bundle?):Boolean{
                    if(action!=android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)return super.performAccessibilityAction(host,action,args)
                    if(closed||!ready||!isAttachedToWindow)return false
                    if(blocked)return false
                    return if(kind==Kind.DOCK){begin(kind,0f,0f);finish(0f,0f);true}else openSettings()
                }
            }
            setOnKeyListener{_,code,event->
                if(code !in listOf(android.view.KeyEvent.KEYCODE_ENTER,android.view.KeyEvent.KEYCODE_DPAD_CENTER,android.view.KeyEvent.KEYCODE_SPACE))return@setOnKeyListener false
                if(closed||!ready||blocked||!isAttachedToWindow||windowVisibility!=VISIBLE)return@setOnKeyListener true
                if(event.action==android.view.KeyEvent.ACTION_DOWN&&event.repeatCount==0){
                    if(kind==Kind.DOCK){begin(kind,0f,0f);finish(0f,0f)}else openSettings()
                };true
            }
        }
        header.addView(handle("Move","Move floating keyboard; drag or activate for position settings",Kind.MOVE),LinearLayout.LayoutParams(0,dp(48),1f))
        header.addView(handle("Resize","Resize floating keyboard; drag or activate for size settings",Kind.RESIZE),LinearLayout.LayoutParams(dp(80),dp(48)))
        header.addView(handle("Dock","Dock keyboard",Kind.DOCK),LinearLayout.LayoutParams(dp(64),dp(48)))
        card.addView(header,LinearLayout.LayoutParams(-1,dp(48)));card.addView(content,LinearLayout.LayoutParams(-1,0,1f))
        addView(card,LayoutParams(1,1));card.visibility=INVISIBLE
        setOnApplyWindowInsetsListener{_,insets->
            val safe=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.mandatorySystemGestures())
            val key=listOf(safe.left,safe.top,safe.right,safe.bottom)
            if(appliedInsets!=key){appliedInsets=key;viewportToken=Any();cancelGesture();ready=false}
            requestLayout();insets
        }
    }
    private fun openSettings():Boolean {
        if(closed||!ready||blocked||!isAttachedToWindow||windowVisibility!=VISIBLE)return false
        cancelGesture()
        val fit=fitted?:return false
        val origin=captureOrigin()?:return false // Reserve original CPU metadata before platform checks.
        val op=Operation(Kind.SETTINGS,origin,fit.rect,viewportToken,authority.reserve(),0f,0f);operation=op
        if(!current(origin)||operation!==op||!authority.current(op.ticket)){discard(op);return false}
        operation=null
        val contactCurrent={!closed&&!blocked&&op.viewport===viewportToken&&authority.current(op.ticket)&&viewportCurrent()}
        return authority.dispatch(op.ticket,{contactCurrent()},{settings(origin,contactCurrent)})
    }
    private fun begin(kind:Kind,x:Float,y:Float){
        cancelGesture()
        val fit=fitted?:return
        if(closed||!ready||blocked||!x.isFinite()||!y.isFinite())return
        val original=captureOrigin()?:return // CPU metadata only, BEFORE any platform callback.
        val op=Operation(kind,original,fit.rect,viewportToken,authority.reserve(),x,y);operation=op
        if(kind!=Kind.DOCK){
            val prepared=prepare(original)
            if(operation!==op||closed||op.viewport!==viewportToken||!authority.current(op.ticket)||prepared==null){discard(op);return}
            op.origin=prepared
        }else if(!current(original)){discard(op)}
    }
    private fun move(x:Float,y:Float){
        val op=operation?:return
        if(closed||op.viewport!==viewportToken||!viewportCurrent()||!x.isFinite()||!y.isFinite()||!current(op.origin)||operation!==op||!authority.current(op.ticket)){discard(op);return}
        if(op.kind==Kind.DOCK)return
        val fit=fitted?:return
        val dx=(x-op.x).roundToInt();val dy=(y-op.y).roundToInt();val safe=fit.safe
        fun bounded(value:Long,min:Int,max:Int)=value.coerceIn(min.toLong(),max.toLong()).toInt()
        draft=if(op.kind==Kind.MOVE){
            val left=bounded(op.initial.left.toLong()+dx,safe.left,safe.right-op.initial.width)
            val top=bounded(op.initial.top.toLong()+dy,safe.top,safe.bottom-op.initial.height)
            FloatingKeyboardGeometry.Rect(left,top,left+op.initial.width,top+op.initial.height)
        }else{
            val width=bounded(op.initial.width.toLong()+dx,limits.minWidth,minOf(limits.maxWidth,safe.right-op.initial.left))
            val height=bounded(op.initial.height.toLong()+dy,limits.minHeight,minOf(limits.maxHeight,safe.bottom-op.initial.top))
            FloatingKeyboardGeometry.Rect(op.initial.left,op.initial.top,op.initial.left+width,op.initial.top+height)
        }
        requestLayout() // RAM preview only. No preference writes on MOVE.
    }
    private fun finish(x:Float,y:Float){
        val op=operation?:return
        if(op.kind!=Kind.DOCK)move(x,y)
        if(operation!==op)return
        operation=null // Consume UP before external cleanup/store callbacks.
        val contactCurrent={!closed&&op.viewport===viewportToken&&authority.current(op.ticket)&&viewportCurrent()}
        if(!current(op.origin)||!contactCurrent()){discard(op);return}
        val success=if(op.kind==Kind.DOCK)dock(snapshot.revision,op.origin,contactCurrent) else {
            val fit=fitted;val chosen=draft?:op.initial
            val normalized=fit?.let{FloatingKeyboardGeometry.normalize(chosen,it.safe)}
            normalized!=null&&save(normalized,snapshot.revision,op.origin,contactCurrent)
        }
        if(authority.current(op.ticket)){draft=null;ready=false;card.visibility=INVISIBLE;requestLayout()}
        if(!success&&authority.current(op.ticket)){ // Restore confirmed bounds; no automatic retry or overwrite.
            android.widget.Toast.makeText(context,"Keyboard layout was not saved. Confirmed position retained.",android.widget.Toast.LENGTH_LONG).show()
        }
    }
    private fun discard(op:Operation){
        // A stale return must not cancel/restore a newer reentrant handle's draft.
        if(!authority.current(op.ticket)||(operation!=null&&operation!==op))return
        authority.cancel();operation=null;draft=null;if(!closed)requestLayout()
    }
    fun cancelGesture(){authority.cancel();operation=null;draft=null;if(!closed)requestLayout()}
    override fun dispatchTouchEvent(event:MotionEvent):Boolean{
        if(closed)return true
        if(event.actionMasked==MotionEvent.ACTION_CANCEL)cancelGesture()
        if(blocked){if(event.actionMasked==MotionEvent.ACTION_UP||event.actionMasked==MotionEvent.ACTION_CANCEL)blocked=false;return true}
        if(event.pointerCount>1&&operation!=null){
            cancelGesture();blocked=true
            val cancel=MotionEvent.obtain(event)
            try{cancel.action=MotionEvent.ACTION_CANCEL;super.dispatchTouchEvent(cancel)}finally{cancel.recycle()}
            return true
        }
        return super.dispatchTouchEvent(event)
    }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
        val width=MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1);val height=MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1)
        setMeasuredDimension(width,height)
        val location=IntArray(2);getLocationInWindow(location)
        windowRect=FloatingKeyboardGeometry.Rect(location[0],location[1],location[0]+width,location[1]+height)
        val raw=rootWindowInsets?.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.mandatorySystemGestures())
        val margin=dp(8)
        windowInsets=FloatingKeyboardGeometry.Insets((raw?.left?:0)+margin,(raw?.top?:0)+margin,(raw?.right?:0)+margin,(raw?.bottom?:0)+margin)
        val key=listOf(width,height,location[0],location[1],windowInsets.left,windowInsets.top,windowInsets.right,windowInsets.bottom)
        if(key!=viewportKey){viewportKey=key;viewportToken=Any();authority.cancel();operation=null;draft=null;ready=false;reportingNoFit=false}
        var selected:FloatingKeyboardGeometry.Fit?=null
        repeat(2){
            val prior=(selected as? FloatingKeyboardGeometry.Fit.Floating)?.rect?:fitted?.rect
            val preliminaryWidth=prior?.width?:((width*.9f).toInt().coerceAtLeast(1))
            content.measure(MeasureSpec.makeMeasureSpec(preliminaryWidth,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
            val chromePx=chrome().coerceAtLeast(0)
            val naturalBody=(content.measuredHeight-chromePx).coerceAtLeast(1)
            limits=FloatingKeyboardGeometry.Limits(dp(320),dp(48)+chromePx+minimumBody(naturalBody).coerceAtLeast(dp(160)))
            val preferred=draft?.let{draftRect->fitted?.safe?.let{FloatingKeyboardGeometry.normalize(draftRect,it)}}?:snapshot.value
            selected=FloatingKeyboardGeometry.resolve(preferred,windowRect,windowInsets,limits)
            val fit=selected as? FloatingKeyboardGeometry.Fit.Floating
            if(fit!=null){
                val keyBudget=(fit.rect.height-dp(48)-chromePx).coerceAtLeast(1)
                val next=fit.rect.width to keyBudget
                if(next!=lastBudget){lastBudget=next;budget(next.first,next.second)}
                content.measure(MeasureSpec.makeMeasureSpec(fit.rect.width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(fit.rect.height-dp(48),MeasureSpec.EXACTLY))
            }
        }
        val fit=selected as? FloatingKeyboardGeometry.Fit.Floating
        fitted=fit
        if(fit==null){
            ready=false;card.visibility=INVISIBLE
            if(!reportingNoFit){
                reportingNoFit=true;val failedViewport=viewportToken
                post{if(!closed&&failedViewport===viewportToken&&reportingNoFit&&fitted==null)noFit("Floating keyboard cannot fit this window. Docked; preferred geometry retained.")}
            }
            return
        }
        reportingNoFit=false
        card.measure(MeasureSpec.makeMeasureSpec(fit.rect.width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(fit.rect.height,MeasureSpec.EXACTLY))
    }
    override fun onLayout(changed:Boolean,left:Int,top:Int,right:Int,bottom:Int){
        val fit=fitted?:return
        val origin=IntArray(2);getLocationInWindow(origin)
        if(origin[0]!=windowRect.left||origin[1]!=windowRect.top){ready=false;card.visibility=INVISIBLE;requestLayout();return}
        card.layout(fit.rect.left-origin[0],fit.rect.top-origin[1],fit.rect.right-origin[0],fit.rect.bottom-origin[1])
        ready=!closed&&card.width>0&&card.height>dp(48);card.visibility=if(ready)VISIBLE else INVISIBLE
    }
    private fun viewportCurrent():Boolean {
        val point=IntArray(2);getLocationInWindow(point)
        val raw=rootWindowInsets?.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.mandatorySystemGestures())
        val margin=dp(8)
        return viewportKey==listOf(width,height,point[0],point[1],(raw?.left?:0)+margin,(raw?.top?:0)+margin,(raw?.right?:0)+margin,(raw?.bottom?:0)+margin)
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){super.onSizeChanged(w,h,oldw,oldh);if(w!=oldw||h!=oldh){viewportToken=Any();cancelGesture();ready=false}}
    fun ownedRectInWindow():Rect?{
        if(!viewportCurrent()||closed||!ready||!isAttachedToWindow||!isShown||windowVisibility!=VISIBLE||card.visibility!=VISIBLE)return null
        val visible=Rect();if(!card.getLocalVisibleRect(visible)||visible!=Rect(0,0,card.width,card.height))return null
        val point=IntArray(2);card.getLocationInWindow(point)
        return Rect(point[0],point[1],point[0]+card.width,point[1]+card.height)
    }
    override fun onDetachedFromWindow(){close();super.onDetachedFromWindow()}
    override fun close(){if(closed)return;closed=true;authority.cancel();operation=null;draft=null;ready=false;card.visibility=INVISIBLE}
}
