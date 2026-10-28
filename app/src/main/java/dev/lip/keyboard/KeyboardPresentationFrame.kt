package dev.lip.keyboard

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/** Width measured by the IME window, not guessed from the display/screen. */
class KeyboardPresentationFrame(context:Context,private val content:View,private val config:KeyboardPreferences,
    private val changeHand:(KeyboardHand)->Unit,private val resized:(Int)->Unit):FrameLayout(context) {
    private var lastAvailable=0
    private val rail=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER}
    init {
        setBackgroundColor(KeyboardVisualTokens.BACKDROP)
        setOnApplyWindowInsetsListener{view,insets->
            val safe=insets.getInsets(android.view.WindowInsets.Type.navigationBars() or android.view.WindowInsets.Type.displayCutout())
            view.setPadding(safe.left,0,safe.right,0);insets
        }
        addView(content,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT))
        fun button(text:String,description:String,action:()->Unit)=GlassKeyButton(context).apply{
            this.text=text;contentDescription=description;fitText(20)
            setOnClickListener{if(isAttachedToWindow)action()}
        }
        rail.addView(button("↔","Use full-width keyboard"){changeHand(KeyboardHand.FULL)},LinearLayout.LayoutParams(-1,(48*resources.displayMetrics.density).toInt()))
        rail.addView(button(if(config.hand==KeyboardHand.LEFT)"→" else "←","Move keyboard ${if(config.hand==KeyboardHand.LEFT)"right" else "left"}"){
            changeHand(if(config.hand==KeyboardHand.LEFT)KeyboardHand.RIGHT else KeyboardHand.LEFT)
        },LinearLayout.LayoutParams(-1,(48*resources.displayMetrics.density).toInt()))
        addView(rail,LayoutParams(0,LayoutParams.WRAP_CONTENT))
    }
    override fun onAttachedToWindow(){super.onAttachedToWindow();requestApplyInsets()}
    override fun onMeasure(widthSpec:Int,heightSpec:Int){
        val width=(MeasureSpec.getSize(widthSpec)-paddingLeft-paddingRight).coerceAtLeast(1)
        val keyboard=KeyboardPresentation.contentWidth(width,resources.displayMetrics.density,config.hand)
        (content.layoutParams as LayoutParams).apply{this.width=keyboard;gravity=if(config.hand==KeyboardHand.RIGHT)Gravity.RIGHT else Gravity.LEFT}
        rail.visibility=if(keyboard==width)GONE else VISIBLE
        (rail.layoutParams as LayoutParams).apply{this.width=width-keyboard;gravity=(if(config.hand==KeyboardHand.RIGHT)Gravity.LEFT else Gravity.RIGHT) or Gravity.CENTER_VERTICAL}
        super.onMeasure(widthSpec,heightSpec)
        // Insets can change available width without changing the outer window's size.
        if(width!=lastAvailable){lastAvailable=width;post{if(isAttachedToWindow)resized((this.width-paddingLeft-paddingRight).coerceAtLeast(1))}}
    }
}
