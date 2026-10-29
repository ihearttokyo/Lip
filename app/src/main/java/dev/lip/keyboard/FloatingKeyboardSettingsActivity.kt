package dev.lip.keyboard

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** UI geometry only; never opens an editor, clipboard, microphone or provider. */
class FloatingKeyboardSettingsActivity:Activity(){
    private lateinit var store:FloatingKeyboardPreferencesStore
    private lateinit var baseline:FloatingKeyboardSnapshot
    private var draft=FloatingKeyboardPreferences()
    private var screen=Any()
    override fun onCreate(state:Bundle?){super.onCreate(state);store=FloatingKeyboardPreferencesStore(this);reload()}
    override fun onStop(){screen=Any();super.onStop()}
    override fun onStart(){super.onStart();if(::store.isInitialized)reload()}
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun reload(){baseline=store.snapshot();draft=baseline.value;render()}
    private fun render(message:String?=null){
        screen=Any();val token=screen
        val scroll=ScrollView(this).apply{setBackgroundColor(Color.BLACK);isFillViewport=true;contentDescription="Floating keyboard settings"}
        val column=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(12),dp(20),dp(24))}
        fun label(text:String,size:Float=16f)=TextView(this).apply{this.text=text;textSize=size;setTextColor(Color.WHITE);setPadding(0,dp(10),0,dp(8))}
        fun action(text:String,run:()->Unit){column.addView(GlassKeyButton(this,capsule=true).apply{
            this.text=text;contentDescription=text;minHeight=dp(48);minimumHeight=dp(48);fitText(16);setSingleLine(false)
            interactionAllowed={screen===token&&isAttachedToWindow&&hasWindowFocus()&&!isFinishing&&!isDestroyed}
            setOnClickListener{if(interactionAllowed())run()}
        })}
        action("Back to keyboard preferences"){finish()}
        column.addView(label("Floating keyboard",30f))
        column.addView(label("Move and resize the actual keyboard card over an editor. Outside the card, taps belong to the app. System navigation stays under Android's control. Docked height and one-hand settings stay separate."))
        if(!baseline.valid)column.addView(label("Saved floating geometry is unavailable. Docked defaults retained until an explicit save."))
        message?.let{column.addView(label(it))}
        column.addView(label("Preferred mode: ${baseline.value.mode.name.lowercase()}"))
        action("Use floating keyboard"){
            if(store.setMode(FloatingKeyboardMode.FLOATING))reload()else render("Mode was not saved. Confirmed settings retained.")
        }
        action("Dock keyboard"){
            if(store.setMode(FloatingKeyboardMode.DOCKED))reload()else render("Mode was not saved. Confirmed settings retained.")
        }
        column.addView(label("Position: ${draft.xBp/100}% across free space, ${draft.yBp/100}% down free space. Size: ${draft.widthBp/100}% of window width, ${draft.heightBp/100}% of window height."))
        fun change(x:Int=0,y:Int=0,w:Int=0,h:Int=0){
            draft=draft.copy(xBp=(draft.xBp+x).coerceIn(0,10000),yBp=(draft.yBp+y).coerceIn(0,10000),
                widthBp=(draft.widthBp+w).coerceIn(1,10000),heightBp=(draft.heightBp+h).coerceIn(1,10000));render()
        }
        action("Move floating keyboard left"){change(x=-500)};action("Move floating keyboard right"){change(x=500)}
        action("Move floating keyboard up"){change(y=-500)};action("Move floating keyboard down"){change(y=500)}
        action("Make floating keyboard narrower"){change(w=-500)};action("Make floating keyboard wider"){change(w=500)}
        action("Make floating keyboard shorter"){change(h=-500)};action("Make floating keyboard taller"){change(h=500)}
        column.addView(label("These controls preview a draft only. Requested size is fitted to the actual safe window and minimum key/control sizes. A window that cannot fit the minimum keyboard uses Docked mode with an explanation. Busy dictation or attachment work must finish before a requested layout transition."))
        action("Apply floating position and size"){
            if(store.saveGeometry(draft,baseline.revision))reload()else render("Geometry was not saved. Reload confirmed settings before a new attempt.")
        }
        action("Reset floating position and size draft"){draft=FloatingKeyboardPreferences().copy(mode=baseline.value.mode);render()}
        action("Reload confirmed floating settings"){reload()}
        scroll.addView(column);setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener{view,insets->val safe=insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout());view.setPadding(safe.left,safe.top,safe.right,safe.bottom);insets}
    }
}
