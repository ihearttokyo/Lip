package dev.lip.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView

/** Draft has no storage dependency: progress and +/- never write. */
class KeyboardHeightDraft(initial:Int) {
    enum class Outcome { SAVED, UNCHANGED, FAILED, STALE }
    var draft=initial;private set
    private var confirmed=initial
    private var live=true
    private var applying=false
    init{require(initial in 80..140)}
    fun set(percent:Int):Boolean {if(!live||applying||percent !in 80..140)return false;draft=percent;return true}
    fun close(){live=false}
    fun apply(write:(Int)->Boolean):Outcome {
        if(!live||applying)return Outcome.STALE
        if(draft==confirmed)return Outcome.UNCHANGED
        applying=true
        return try{if(runCatching{write(draft)}.getOrDefault(false)){confirmed=draft;Outcome.SAVED}else Outcome.FAILED}
        finally{applying=false}
    }
}

/** Metadata-only, inert geometry illustration at this measured panel's width. */
private class KeyboardHeightPreview(context:Context):View(context) {
    var percent=100;set(value){field=value;requestLayout();invalidate()}
    private val ink=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;textAlign=Paint.Align.CENTER}
    private val glass=DarkGlassDrawable(resources.displayMetrics.density)
    private fun row()=KeyboardPresentation.rowHeight(width.coerceAtLeast(1),resources.displayMetrics.heightPixels.coerceAtLeast(1),
        resources.displayMetrics.density,resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE,percent)
    init{isClickable=false;isLongClickable=false;isFocusable=false}
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
        val w=MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val h=KeyboardPresentation.rowHeight(w,resources.displayMetrics.heightPixels.coerceAtLeast(1),resources.displayMetrics.density,
            resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE,percent)*5
        setMeasuredDimension(w,resolveSize(h,heightMeasureSpec))
    }
    override fun onDraw(canvas:Canvas){
        canvas.drawColor(Color.BLACK)
        val rows=KeyboardLayoutSpec.rows(KeyboardPage.LETTERS,false)
        val heights=KeyboardLayoutSpec.rowHeights(row()*5,rows.size);var top=0
        ink.textSize=12*resources.displayMetrics.scaledDensity
        for((index,keys)in rows.withIndex()){
            val h=heights[index];val total=keys.sumOf{it.units.toDouble()}.toFloat();var left=0f
            for(key in keys){val right=left+width*key.units/total
                if(key.action!=KeyAction.SPACER){
                    glass.bounds=Rect(left.toInt(),top,right.toInt(),top+h);glass.draw(canvas)
                    val text=when(key.action){KeyAction.SHIFT->"⇧";KeyAction.DELETE->"⌫";KeyAction.EDITOR_ACTION->"↵";else->key.label}
                    canvas.drawText(text,(left+right)/2,top+h/2f-(ink.ascent()+ink.descent())/2,ink)
                };left=right
            };top+=h
        }
    }
}

/** One native control group, no editor/clipboard/microphone/provider references. */
class KeyboardHeightControls(context:Context,confirmed:Int,private val save:(Int)->Boolean,
    private val result:(KeyboardHeightDraft.Outcome)->Unit):LinearLayout(context),AutoCloseable {
    private val model=KeyboardHeightDraft(confirmed)
    private var closed=false
    private val exact=TextView(context)
    private val preview=KeyboardHeightPreview(context)
    private val slider=SeekBar(context)
    private val minus:GlassKeyButton
    private val plus:GlassKeyButton
    private val presets=mutableMapOf<KeyboardHeight,RadioButton>()
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun label(text:String,size:Float=14f)=TextView(context).apply{this.text=text;textSize=size;setTextColor(Color.WHITE);setPadding(0,dp(8),0,dp(8))}
    private fun button(text:String,description:String=text,action:()->Unit)=GlassKeyButton(context,capsule=true).apply{
        this.text=text;contentDescription=description;minHeight=dp(48);minimumHeight=dp(48);fitText(16);setSingleLine(false)
        interactionAllowed={!closed&&isAttachedToWindow&&windowVisibility==VISIBLE&&hasWindowFocus()}
        setOnClickListener{if(interactionAllowed())action()}
    }
    init{
        orientation=VERTICAL
        addView(label("Keyboard height",20f));addView(label("Applied: $confirmed%"))
        exact.setTextColor(Color.WHITE);exact.textSize=20f;exact.setPadding(0,dp(8),0,dp(8));addView(exact)
        preview.contentDescription="Inert keyboard height preview";addView(preview,LayoutParams(-1,-2))
        slider.min=80;slider.max=140;slider.progress=confirmed;slider.contentDescription="Keyboard height percentage";slider.minimumHeight=dp(48)
        addView(slider,LayoutParams(-1,dp(48)))
        val step=LinearLayout(context).apply{orientation=HORIZONTAL;isBaselineAligned=false}
        minus=button("−1%","Decrease keyboard height by one percent"){setDraft(model.draft-1)}
        plus=button("+1%","Increase keyboard height by one percent"){setDraft(model.draft+1)}
        step.addView(minus,LayoutParams(0,-2,1f));step.addView(plus,LayoutParams(0,-2,1f));addView(step)
        addView(label("Slider and ±1 preview only until Apply. Presets and Reset save immediately. Height adapts to the actual window; existing minimum/maximum limits may make nearby percentages look alike. This inert preview uses this panel's width."))
        addView(button("Apply keyboard height"){apply()})
        val group=RadioGroup(context)
        for(preset in KeyboardHeight.entries){
            val option=RadioButton(context).apply{
                id=View.generateViewId();text="${preset.label} (${preset.percent}%)";contentDescription="Key height: ${preset.label}";setTextColor(Color.WHITE)
                buttonTintList=android.content.res.ColorStateList.valueOf(Color.WHITE);minHeight=dp(48)
                setOnClickListener{if(!closed&&isAttachedToWindow&&hasWindowFocus()){setDraft(preset.percent);apply()}}
            };presets[preset]=option;group.addView(option)
        }
        addView(group)
        addView(button("Reset standard height (100%)","Reset standard keyboard height"){setDraft(100);apply()})
        slider.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(view:SeekBar,progress:Int,fromUser:Boolean){if(!closed&&model.set(progress))refresh()}
            override fun onStartTrackingTouch(view:SeekBar){}
            override fun onStopTrackingTouch(view:SeekBar){} // Explicit Apply, never a second release write.
        })
        setDraft(confirmed)
    }
    private fun setDraft(percent:Int){if(!closed&&model.set(percent)){slider.progress=percent;refresh()}}
    private fun refresh(){
        exact.text="Preview: ${model.draft}%";exact.contentDescription="Keyboard height preview: ${model.draft} percent"
        slider.stateDescription="${model.draft} percent; not saved until Apply"
        minus.isEnabled=model.draft>80;plus.isEnabled=model.draft<140;preview.percent=model.draft
        presets.forEach{(preset,view)->view.isChecked=model.draft==preset.percent}
    }
    private fun apply(){if(!closed)result(model.apply(save))}
    override fun close(){closed=true;model.close();slider.setOnSeekBarChangeListener(null)}
    override fun onDetachedFromWindow(){close();super.onDetachedFromWindow()}
}
