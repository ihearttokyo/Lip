package dev.lip.keyboard

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.ViewGroup
import android.widget.Button
import kotlin.math.min

/** Android-local glass material: no cross-app screenshots, blur of labels or native Apple API claim. */
class DarkGlassDrawable(private val density: Float, private val capsule: Boolean = false, private val toolbar: Boolean = false, private val circle: Boolean = false, private val flat: Boolean = false) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pressed = false
    private var focused = false
    private var touchX = -1f
    private var touchY = -1f
    private val lens by lazy { GlassMaterial.shader() }
    private var opacity = 255
    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean {
        val value=state.contains(android.R.attr.state_pressed)
        val focus=state.contains(android.R.attr.state_focused)
        if(value==pressed && focus==focused)return false
        pressed=value;focused=focus;invalidateSelf();return true
    }
    fun highlight(x: Float,y: Float) { touchX=x;touchY=y;if(pressed)invalidateSelf() }
    override fun draw(canvas: Canvas) {
        if(flat && !pressed && !focused)return
        val gap = if (toolbar) 0f else KeyboardVisualTokens.KEY_GAP_DP * density
        val r = RectF(bounds).apply { inset(gap, gap) }
        if(circle){val size=min(r.width(),r.height())*.80f;val cx=r.centerX();val cy=r.centerY();r.set(cx-size/2,cy-size/2,cx+size/2,cy+size/2)}
        val radius = if (capsule) r.height() / 2 else KeyboardVisualTokens.KEY_RADIUS_DP * density
        paint.style = Paint.Style.FILL; paint.alpha = opacity
        if(GlassMaterial.matte){
            paint.shader=null;paint.color=if(pressed)0xff48484b.toInt() else if(toolbar)KeyboardVisualTokens.TOOLBAR else KeyboardVisualTokens.KEY_SURFACE
            canvas.drawRoundRect(r,radius,radius,paint);return
        }
        val optical=if(!toolbar && canvas.isHardwareAccelerated)lens else null
        if(optical!=null){
            optical.setFloatUniform("size",r.width(),r.height());optical.setFloatUniform("origin",r.left,r.top)
            optical.setFloatUniform("radius",radius);optical.setFloatUniform("pressed",if(pressed)1f else 0f)
            optical.setFloatUniform("touch",if(touchX<0 || GlassMaterial.reducedEffects)r.width()/2 else touchX-r.left,if(touchY<0 || GlassMaterial.reducedEffects)r.height()/2 else touchY-r.top)
            paint.shader=optical;canvas.drawRoundRect(r,radius,radius,paint);paint.shader=null
            return
        }
        val top = if (pressed) 0xff48484b.toInt() else if (toolbar) KeyboardVisualTokens.TOOLBAR else 0xff37373a.toInt()
        val bottom = if (toolbar) 0xff303032.toInt() else 0xff242426.toInt()
        paint.shader = LinearGradient(r.left, r.top, r.right, r.bottom, intArrayOf(top, if (toolbar) KeyboardVisualTokens.TOOLBAR else KeyboardVisualTokens.KEY_SURFACE, bottom), floatArrayOf(0f,.48f,1f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(r, radius, radius, paint)
        // Restrained optical rim: highlights sit behind, never over, the pure-white glyphs.
        paint.style = Paint.Style.STROKE; paint.strokeWidth = .7f * density
        paint.shader = LinearGradient(r.left, r.top, r.right, r.bottom,
            intArrayOf(if (pressed) 0x70ffffff else 0x36ffffff, 0x08ffffff, 0x18000000), floatArrayOf(0f,.5f,1f), Shader.TileMode.CLAMP)
        val edge = RectF(r).apply { inset(.5f*density,.5f*density) }
        canvas.drawRoundRect(edge, radius, radius, paint)
        paint.shader = null
    }
    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { /* Deliberately no theme tint over the fixed gray material. */ }
    @Deprecated("Android Drawable contract") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Own-material refraction and Fresnel lighting only; never samples another app. */
object GlassMaterial {
    var matte=false
    var haptics=false
    var reducedEffects=false
    private var warned=false
    fun refresh() { reducedEffects=!android.animation.ValueAnimator.areAnimatorsEnabled() }
    fun shader(): RuntimeShader? = try { RuntimeShader(PROGRAM) } catch(error:IllegalArgumentException) {
        if(!warned){warned=true;android.util.Log.w("LipGlass","Optical renderer unavailable; reduced-effects material used",error)}
        null
    }
    private const val PROGRAM="""
        uniform float2 size;
        uniform float2 origin;
        uniform float2 touch;
        uniform float radius;
        uniform float pressed;
        half4 main(float2 coord) {
            float2 p=coord-origin;
            float2 centered=p-size*0.5;
            float2 q=abs(centered)-(size*0.5-float2(radius));
            float sd=length(max(q,float2(0)))+min(max(q.x,q.y),0.0)-radius;
            float2 outside=max(q,float2(0));
            float2 side=length(outside)>0.0001 ? normalize(outside)*sign(centered) : (q.x>q.y ? float2(sign(centered.x),0) : float2(0,sign(centered.y)));
            float bend=exp(-max(0.0,-sd)/max(2.0,radius*0.65));
            float3 n=normalize(float3(side*bend,sqrt(max(0.02,1.0-bend*bend))));
            float3 view=float3(0,0,1);
            float3 ray=refract(-view,n,0.689655);
            float2 uv=(p+ray.xy*(4.0+radius*0.25))/size;
            float fresnel=0.04+0.96*pow(1.0-clamp(n.z,0.0,1.0),5.0);
            float3 light=normalize(float3(-0.5,-0.5,0.707107));
            float spec=pow(max(0.0,dot(reflect(-light,n),view)),28.0);
            float lamp=exp(-pow((uv.y+uv.x*0.18-0.08)/0.18,2.0));
            float edge=exp(-max(0.0,-sd)/2.0);
            float distanceToTouch=length((p-touch)/max(size,float2(1)));
            float shade=0.142+0.018*(1.0-uv.y)+0.028*lamp+0.16*fresnel+0.18*spec+0.025*edge+pressed*0.04*exp(-distanceToTouch*4.0);
            float3 color=clamp(float3(shade,shade,shade+0.008),float3(0.12),float3(0.34));
            float alpha=1.0-smoothstep(-0.8,0.8,sd);
            return half4(half3(color*alpha),half(alpha));
        }
    """
}

enum class KeyIcon { NONE, SHIFT, SHIFT_ON, CAPS, DELETE, MIC, STOP, MENU, TRANSLATE, FACE, GIF, CLIPBOARD, UNDO, ENTER, DONE, SEARCH, SPACE, PERSON, LEAF, FOOD, PLANE, BALL, BULB, FLAG, RECENT, AGENT }

class GlassKeyButton(context: Context, capsule: Boolean = false, private val flat: Boolean = false) : Button(context) {
    var interactionAllowed:()->Boolean={true}
    override fun performClick():Boolean=if(interactionAllowed())super.performClick() else false
    override fun performLongClick():Boolean=if(interactionAllowed())super.performLongClick() else false

    var icon = KeyIcon.NONE
        set(value) { field = value; invalidate() }
    var hintLabel: String? = null
        set(value) { field=value;invalidate() }
    var accessibilityToggle: Boolean? = null
    private var explicitInputFocus=false
    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        if(action==android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)explicitInputFocus=true
        if(action==android.view.accessibility.AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)explicitInputFocus=false
        val result=super.performAccessibilityAction(action,arguments);invalidate();return result
    }
    private var baseMaxSp=26
    var labelScale=KeyboardScale.SYSTEM
        set(value){field=value;fitText(baseMaxSp);invalidate()}
    fun fitText(maxSp: Int) {
        baseMaxSp=maxSp
        val scaled=KeyboardSizing.legendSp(maxSp,labelScale)
        // The fallback floor must not rise with requested size: compact keys at
        // large system font scale still need room to fit instead of clipping.
        setAutoSizeTextTypeUniformWithConfiguration(8,scaled,1,android.util.TypedValue.COMPLEX_UNIT_SP)
    }
    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        accessibilityToggle?.let { info.isCheckable=true;info.isChecked=it;info.className="android.widget.ToggleButton" }
        hintLabel?.let { info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK,"Type $it")) }
    }
    override fun dispatchTouchEvent(event:android.view.MotionEvent):Boolean {
        if(!interactionAllowed())return true
        if(event.actionMasked==android.view.MotionEvent.ACTION_DOWN&&isEnabled&&GlassMaterial.haptics)
            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        return super.dispatchTouchEvent(event)
    }
    override fun onTouchEvent(event:android.view.MotionEvent):Boolean {
        if(!interactionAllowed())return true
        if(event.actionMasked==android.view.MotionEvent.ACTION_DOWN || event.actionMasked==android.view.MotionEvent.ACTION_MOVE)
            (background as? DarkGlassDrawable)?.highlight(event.x,event.y)
        return super.onTouchEvent(event)
    }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = KeyboardVisualTokens.FOREGROUND }
    private val density = resources.displayMetrics.density
    init {
        isHapticFeedbackEnabled=GlassMaterial.haptics
        isFocusableInTouchMode=false // First touch must type, never merely focus the key.
        isAllCaps = false; setTextColor(KeyboardVisualTokens.FOREGROUND)
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); textSize = 26f
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding((3*density).toInt(),(2*density).toInt(),(3*density).toInt(),(2*density).toInt()); includeFontPadding = false; backgroundTintList = null
        stateListAnimator = null; elevation = 0f
        background = DarkGlassDrawable(density,if(flat)true else capsule,circle=flat,flat=flat)
        fitText(26)
    }
    override fun onDraw(canvas: Canvas) {
        if (icon == KeyIcon.NONE) super.onDraw(canvas)
        else {
            val size = min(width.toFloat(), height.toFloat()).coerceAtMost(30*density)
            canvas.save(); canvas.translate((width-size)/2,(height-size)/2); canvas.scale(size/28,size/28)
            ink.color = KeyboardVisualTokens.FOREGROUND; ink.shader = null; ink.style = Paint.Style.STROKE; ink.strokeWidth = 1.8f; ink.strokeCap = Paint.Cap.ROUND; ink.strokeJoin = Paint.Join.ROUND
            when (icon) {
                KeyIcon.SHIFT, KeyIcon.SHIFT_ON, KeyIcon.CAPS -> {
                    val p = Path().apply { moveTo(9f,23f); lineTo(9f,14f); lineTo(3f,14f); lineTo(14f,3f); lineTo(25f,14f); lineTo(19f,14f); lineTo(19f,23f); close() }
                    if (icon != KeyIcon.SHIFT) ink.style = Paint.Style.FILL
                    canvas.drawPath(p,ink)
                    if (icon == KeyIcon.CAPS) { ink.style = Paint.Style.STROKE; canvas.drawLine(8f,26f,20f,26f,ink) }
                }
                KeyIcon.DELETE -> {
                    canvas.drawPath(Path().apply { moveTo(10f,6f); lineTo(25f,6f); lineTo(25f,22f); lineTo(10f,22f); lineTo(2f,14f); close() },ink)
                    canvas.drawLine(13f,10f,20f,18f,ink); canvas.drawLine(20f,10f,13f,18f,ink)
                }
                KeyIcon.MIC -> { canvas.drawRoundRect(10f,3f,18f,17f,4f,4f,ink); canvas.drawArc(7f,9f,21f,21f,0f,180f,false,ink); canvas.drawLine(14f,21f,14f,25f,ink);canvas.drawLine(10f,25f,18f,25f,ink) }
                KeyIcon.STOP -> { ink.style=Paint.Style.FILL;canvas.drawRoundRect(6f,6f,22f,22f,3f,3f,ink) }
                KeyIcon.MENU -> { ink.style=Paint.Style.FILL;for(x in listOf(4f,16f))for(y in listOf(4f,16f))canvas.drawRoundRect(x,y,x+8,y+8,2f,2f,ink) }
                KeyIcon.AGENT -> { canvas.drawRoundRect(3f,4f,25f,21f,5f,5f,ink);canvas.drawPath(Path().apply{moveTo(8f,21f);lineTo(7f,26f);lineTo(14f,21f)},ink);ink.style=Paint.Style.FILL;for(x in listOf(9f,14f,19f))canvas.drawCircle(x,12f,1.2f,ink) }
                KeyIcon.FACE -> { canvas.drawCircle(14f,14f,11f,ink);canvas.drawArc(8f,9f,20f,21f,10f,160f,false,ink);ink.style=Paint.Style.FILL;canvas.drawCircle(10f,10f,1.4f,ink);canvas.drawCircle(18f,10f,1.4f,ink) }
                KeyIcon.PERSON -> { canvas.drawCircle(14f,7f,4f,ink);canvas.drawPath(Path().apply{moveTo(5f,25f);lineTo(5f,21f);cubicTo(5f,12f,23f,12f,23f,21f);lineTo(23f,25f);close()},ink) }
                KeyIcon.LEAF -> { canvas.drawPath(Path().apply{moveTo(5f,23f);cubicTo(1f,10f,10f,3f,24f,4f);cubicTo(25f,18f,16f,27f,5f,23f);moveTo(4f,25f);lineTo(19f,10f)},ink) }
                KeyIcon.FOOD -> { for(x in listOf(5f,9f,13f))canvas.drawLine(x,3f,x,10f,ink);canvas.drawArc(5f,6f,13f,15f,0f,180f,false,ink);canvas.drawLine(9f,14f,9f,25f,ink);canvas.drawPath(Path().apply{moveTo(23f,25f);lineTo(23f,3f);cubicTo(17f,6f,16f,15f,23f,15f)},ink) }
                KeyIcon.PLANE -> { canvas.drawPath(Path().apply{moveTo(14f,2f);lineTo(16f,11f);lineTo(25f,17f);lineTo(25f,19f);lineTo(16f,16f);lineTo(16f,23f);lineTo(20f,26f);lineTo(8f,26f);lineTo(12f,23f);lineTo(12f,16f);lineTo(3f,19f);lineTo(3f,17f);lineTo(12f,11f);close()},ink) }
                KeyIcon.BALL -> { canvas.drawCircle(14f,14f,11f,ink);canvas.drawPath(Path().apply{moveTo(14f,8f);lineTo(20f,12f);lineTo(18f,19f);lineTo(10f,19f);lineTo(8f,12f);close();moveTo(14f,8f);lineTo(14f,3f);moveTo(20f,12f);lineTo(25f,10f);moveTo(18f,19f);lineTo(21f,23f);moveTo(10f,19f);lineTo(7f,23f);moveTo(8f,12f);lineTo(3f,10f)},ink) }
                KeyIcon.BULB -> { canvas.drawPath(Path().apply{moveTo(10f,21f);lineTo(10f,18f);cubicTo(0f,7f,9f,1f,14f,2f);cubicTo(25f,2f,27f,11f,18f,18f);lineTo(18f,21f);close();moveTo(10f,25f);lineTo(18f,25f)},ink) }
                KeyIcon.FLAG -> { canvas.drawPath(Path().apply{moveTo(6f,26f);lineTo(6f,3f);lineTo(23f,3f);lineTo(18f,9f);lineTo(23f,15f);lineTo(6f,15f)},ink) }
                KeyIcon.RECENT -> { canvas.drawCircle(14f,14f,11f,ink);canvas.drawLine(14f,6f,14f,14f,ink);canvas.drawLine(14f,14f,20f,17f,ink) }
                KeyIcon.CLIPBOARD -> { canvas.drawRoundRect(6f,5f,22f,25f,2f,2f,ink);canvas.drawRoundRect(10f,2f,18f,7f,1.5f,1.5f,ink);for(y in listOf(12f,17f,21f))canvas.drawLine(10f,y,18f,y,ink) }
                KeyIcon.UNDO -> { canvas.drawArc(6f,8f,24f,24f,-140f,260f,false,ink);canvas.drawLine(4f,9f,4f,16f,ink);canvas.drawLine(4f,16f,11f,16f,ink) }
                KeyIcon.SPACE -> { canvas.drawPath(Path().apply { moveTo(4f,11f);lineTo(4f,18f);lineTo(24f,18f);lineTo(24f,11f) },ink) }
                KeyIcon.ENTER -> { canvas.drawPath(Path().apply { moveTo(24f,6f);lineTo(24f,17f);lineTo(5f,17f);moveTo(11f,10f);lineTo(4f,17f);lineTo(11f,24f) },ink) }
                KeyIcon.DONE -> { canvas.drawPath(Path().apply { moveTo(5f,15f);lineTo(11f,21f);lineTo(24f,6f) },ink) }
                KeyIcon.SEARCH -> { canvas.drawCircle(12f,12f,8f,ink);canvas.drawLine(18f,18f,25f,25f,ink) }
                KeyIcon.TRANSLATE, KeyIcon.GIF -> { ink.style=Paint.Style.FILL;ink.textAlign=Paint.Align.CENTER;ink.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);ink.textSize=if(icon==KeyIcon.GIF)12f else 20f;canvas.drawText(if(icon==KeyIcon.GIF)"GIF" else "文",14f,20f,ink) }
                else -> Unit
            }
            canvas.restore()
        }
        hintLabel?.let { hint ->
            ink.shader=null;ink.color=KeyboardVisualTokens.FOREGROUND;ink.style=Paint.Style.FILL;ink.typeface=Typeface.create("sans-serif",Typeface.NORMAL);ink.textAlign=Paint.Align.RIGHT
            ink.textSize=android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,9f*labelScale.factor,resources.displayMetrics)
            val maxHeight=height*.26f
            var metrics=ink.fontMetrics
            if(metrics.descent-metrics.ascent>maxHeight){ink.textSize*=maxHeight/(metrics.descent-metrics.ascent);metrics=ink.fontMetrics}
            val top=KeyboardVisualTokens.KEY_GAP_DP*density+1.5f*density
            val baseline=top-metrics.ascent
            canvas.save();canvas.clipRect(3*density,top,width-3*density,(baseline+metrics.descent).coerceAtMost(height-3*density))
            canvas.drawText(hint,width-5f*density,baseline,ink);canvas.restore()
        }
        if(!isEnabled){
            // Explicit unavailable badge, not off-white text or a misleading enabled-looking icon.
            val cx=width-6*density;val cy=6*density;val radius=3.5f*density
            ink.shader=null;ink.style=Paint.Style.FILL;ink.color=KeyboardVisualTokens.BACKDROP;canvas.drawCircle(cx,cy,radius+1.2f*density,ink)
            ink.style=Paint.Style.STROKE;ink.strokeWidth=1.1f*density;ink.color=KeyboardVisualTokens.FOREGROUND;canvas.drawCircle(cx,cy,radius,ink)
            canvas.drawLine(cx-radius*.7f,cy+radius*.7f,cx+radius*.7f,cy-radius*.7f,ink)
        }
        if((isFocused && (!isInTouchMode || explicitInputFocus)) || isAccessibilityFocused){
            ink.shader=null;ink.style=Paint.Style.STROKE;ink.strokeWidth=1.5f*density;ink.color=KeyboardVisualTokens.FOREGROUND
            val r=RectF(1.5f*density,1.5f*density,width-1.5f*density,height-1.5f*density)
            canvas.drawRoundRect(r,KeyboardVisualTokens.KEY_RADIUS_DP*density,KeyboardVisualTokens.KEY_RADIUS_DP*density,ink)
        }
    }
}

/** Seven familiar toolbar positions, independent of icon asset metrics. */
class GlassKeyboardToolbar(context: Context) : ViewGroup(context) {
    init { background = DarkGlassDrawable(resources.displayMetrics.density, toolbar = true) }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w=MeasureSpec.getSize(widthMeasureSpec);val h=MeasureSpec.getSize(heightMeasureSpec)
        for(i in 0 until childCount)getChildAt(i).measure(MeasureSpec.makeMeasureSpec((w*.1f).toInt(),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(h,MeasureSpec.EXACTLY))
        setMeasuredDimension(w,h)
    }
    override fun onLayout(changed: Boolean,l:Int,t:Int,r:Int,b:Int) {
        for(i in 0 until childCount){val left=(width*.15f*i).toInt();val child=getChildAt(i);child.layout(left,0,left+child.measuredWidth,height)}
    }
}
