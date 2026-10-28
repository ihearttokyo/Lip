package dev.lip.keyboard

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.EnumMap

/** Native actionable editing surface. It keeps metadata tickets only, never
 * field text. Tap-only: a held button does not start a repeat timer.
 */
class TextEditingPanel(context:Context,height:Int,private val capture:(TextEditingAction)->TextEditingOwner.Ticket?,
    private val invoke:(TextEditingOwner.Ticket)->Unit,private val cancel:(TextEditingOwner.Ticket?)->Unit,
    private val allowed:()->Boolean,private val returnKeyboard:()->Unit,private val lifetimeClosed:()->Unit):LinearLayout(context) {
    private val buttons=EnumMap<TextEditingAction,GlassKeyButton>(TextEditingAction::class.java)
    private val contacts=EnumMap<TextEditingAction,TextEditingOwner.Ticket>(TextEditingAction::class.java)
    private val touched=mutableSetOf<TextEditingAction>()
    private val note=TextView(context)
    private var closed=false
    private val touchGate=TextEditingTouchGate()
    private val interactionEpoch=TextEditingInteractionEpoch()
    val isOpen get()=!closed
    fun interactionSnapshot():Any?=interactionEpoch.snapshot(!closed&&!touchGate.blocked)
    fun ownsInteraction(snapshot:Any)=interactionEpoch.owns(snapshot,!closed&&!touchGate.blocked)
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    init{
        orientation=VERTICAL;setBackgroundColor(KeyboardVisualTokens.BACKDROP)
        layoutParams=LinearLayout.LayoutParams(-1,height.coerceAtLeast(dp(120)))
        val header=LinearLayout(context).apply{orientation=HORIZONTAL;isBaselineAligned=false}
        header.addView(TextView(context).apply{text="Text editing";setTextColor(KeyboardVisualTokens.FOREGROUND);textSize=16f;gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(dp(8),0,0,0)},LayoutParams(0,dp(48),1f))
        header.addView(GlassKeyButton(context).apply{
            text="ABC";fitText(16);contentDescription="Return from text editing to letters"
            interactionAllowed={!closed&&isAttachedToWindow};setOnClickListener{if(!closed&&isAttachedToWindow)returnKeyboard()}
        },LayoutParams(dp(64),dp(48)))
        addView(header)
        val content=LinearLayout(context).apply{orientation=VERTICAL}
        fun row(actions:List<TextEditingAction>){
            val row=LinearLayout(context).apply{orientation=HORIZONTAL;isBaselineAligned=false}
            for(action in actions)row.addView(button(action),LayoutParams(0,dp(48),1f))
            content.addView(row,LayoutParams(-1,dp(48)))
        }
        row(listOf(TextEditingAction.SELECT_ALL,TextEditingAction.CUT,TextEditingAction.COPY,TextEditingAction.PASTE))
        row(listOf(TextEditingAction.LINE_START,TextEditingAction.UP,TextEditingAction.LINE_END))
        row(listOf(TextEditingAction.LEFT,TextEditingAction.SELECT_MODE,TextEditingAction.RIGHT))
        row(listOf(TextEditingAction.TEXT_START,TextEditingAction.DOWN,TextEditingAction.TEXT_END))
        note.setTextColor(KeyboardVisualTokens.FOREGROUND);note.textSize=11f;note.setPadding(dp(8),dp(6),dp(8),dp(8));content.addView(note)
        addView(ScrollView(context).apply{contentDescription="Text editing controls";isFillViewport=false;addView(content)},LayoutParams(-1,0,1f))
    }
    override fun dispatchTouchEvent(event:MotionEvent):Boolean {
        if(closed)return true
        val action=when(event.actionMasked){
            MotionEvent.ACTION_DOWN->TextEditingTouchGate.Action.DOWN
            MotionEvent.ACTION_MOVE->TextEditingTouchGate.Action.MOVE
            MotionEvent.ACTION_POINTER_DOWN->TextEditingTouchGate.Action.POINTER_DOWN
            MotionEvent.ACTION_POINTER_UP->TextEditingTouchGate.Action.POINTER_UP
            MotionEvent.ACTION_UP->TextEditingTouchGate.Action.UP
            MotionEvent.ACTION_CANCEL->TextEditingTouchGate.Action.CANCEL
            else->TextEditingTouchGate.Action.OTHER
        }
        val decision=touchGate.event(action,event.pointerCount)
        if(decision.cancelAll){
            interactionEpoch.invalidate()
            lifetimeClosed() // Exact-incarnation callback revokes claimed intent as well as contacts.
            contacts.values.toList().forEach(cancel);contacts.clear();touched.clear()
            buttons.values.forEach{it.cancelPendingInputEvents();it.isPressed=false}
            if(decision.consume){
                val cancellation=MotionEvent.obtain(event)
                try{cancellation.action=MotionEvent.ACTION_CANCEL;super.dispatchTouchEvent(cancellation)}finally{cancellation.recycle()}
            }
        }
        return if(decision.consume)true else super.dispatchTouchEvent(event)
    }
    private fun button(action:TextEditingAction)=GlassKeyButton(context).apply{
        text=action.label;fitText(if(action.label.length>6)12 else 16);maxLines=2;contentDescription=action.description
        interactionAllowed={!closed&&!touchGate.blocked&&this@TextEditingPanel.isAttachedToWindow&&allowed()}
        setOnTouchListener{_,event->
            when(event.actionMasked){
                MotionEvent.ACTION_DOWN->{
                    cancelPendingInputEvents();contacts.remove(action)?.let(cancel)
                    touched.add(action);capture(action)?.let{contacts[action]=it}
                }
                MotionEvent.ACTION_UP->{
                    if(event.x<0||event.y<0||event.x>=width||event.y>=this.height){
                        cancelPendingInputEvents();contacts.remove(action)?.let(cancel);touched.remove(action);isPressed=false
                        return@setOnTouchListener true
                    }
                }
                MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN,MotionEvent.ACTION_POINTER_UP->{
                    cancelPendingInputEvents();contacts.remove(action)?.let(cancel);touched.remove(action);isPressed=false
                    return@setOnTouchListener true
                }
            }
            false
        }
        setOnClickListener{
            val wasTouch=touched.remove(action)
            val ticket=contacts.remove(action)
            if(wasTouch&&ticket!=null)invoke(ticket) // A duplicate queued click never creates a fresh activation.
        }
        accessibilityDelegate=object:View.AccessibilityDelegate(){
            override fun performAccessibilityAction(host:View,requested:Int,args:android.os.Bundle?):Boolean {
                if(requested!=android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)return super.performAccessibilityAction(host,requested,args)
                if(!isEnabled||closed||!isAttachedToWindow||!interactionAllowed())return false
                contacts.remove(action)?.let(cancel);touched.remove(action)
                val ticket=capture(action)?:return false
                invoke(ticket);return true // One actual accessibility activation, not a semantic fallback from an old touch.
            }
            override fun onInitializeAccessibilityNodeInfo(host:View,node:android.view.accessibility.AccessibilityNodeInfo){
                super.onInitializeAccessibilityNodeInfo(host,node)
                if(action==TextEditingAction.SELECT_MODE){node.isCheckable=true;node.isChecked=accessibilityToggle==true;node.className="android.widget.ToggleButton"}
            }
        }
        setOnKeyListener{_,code,event->
            if(code!=android.view.KeyEvent.KEYCODE_ENTER&&code!=android.view.KeyEvent.KEYCODE_DPAD_CENTER&&code!=android.view.KeyEvent.KEYCODE_SPACE)return@setOnKeyListener false
            if(closed||touchGate.blocked)return@setOnKeyListener true
            if(event.action==android.view.KeyEvent.ACTION_DOWN&&event.repeatCount==0){
                cancelPendingInputEvents();contacts.remove(action)?.let(cancel);touched.add(action);capture(action)?.let{contacts[action]=it}
            }
            false
        }
        buttons[action]=this
    }
    fun update(state:TextEditingState,selecting:Boolean,message:String){
        for((action,button)in buttons){
            button.isEnabled=!closed&&TextEditingActions.available(action,state)
            if(action==TextEditingAction.SELECT_MODE){button.text=if(selecting)"Select on" else "Select";button.accessibilityToggle=selecting;button.stateDescription=if(selecting)"Selection mode on; arrows request extension" else "Selection mode off"}
        }
        note.text=message
    }
    fun close(){
        if(closed)return;closed=true
        interactionEpoch.invalidate()
        lifetimeClosed() // Revokes claimed-but-no-longer-contact-held ownership too.
        contacts.values.toList().forEach(cancel);contacts.clear();touched.clear()
        buttons.values.forEach{it.cancelPendingInputEvents();it.isPressed=false}
    }
    override fun onDetachedFromWindow(){close();super.onDetachedFromWindow()}
}
