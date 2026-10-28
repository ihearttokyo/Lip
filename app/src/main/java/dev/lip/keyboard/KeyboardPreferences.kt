package dev.lip.keyboard

/** UI-only preferences: never store typed text or change editor policy. */
enum class KeyboardHand(val label:String) { FULL("Full width"), LEFT("Left hand"), RIGHT("Right hand") }
enum class KeyboardHeight(val label:String,val factor:Float) { COMPACT("Compact",.9f), STANDARD("Standard",1f), TALL("Tall",1.15f) }
enum class KeyboardTheme(val label:String) { GLASS("Dark glass"), MATTE("Dark matte") }
enum class KeyboardScale(val label:String,val factor:Float) {
    SMALL("Small",.85f), SYSTEM("System",1f), LARGE("Large",1.25f), EXTRA_LARGE("Extra large",1.5f)
}
/** UI-only sizing; no effect on input output, hit rectangles or recipient rendering. */
object KeyboardSizing {
    fun legendSp(base:Int,scale:KeyboardScale):Int { require(base in 8..96);return (base*scale.factor).toInt().coerceAtLeast(8) }
    fun mediaCellPixels(glyphPixels:Float,density:Float):Int {
        require(glyphPixels.isFinite()&&glyphPixels>0&&density.isFinite()&&density>0)
        return kotlin.math.ceil(maxOf(48*density,glyphPixels*1.25f+12*density).toDouble()).toInt()
    }
    data class MediaPanel(val height:Int,val cell:Int)
    /** Make room for a full first row before sacrificing glyph size. Very short
     * windows may cap the preview; never create negative layout dimensions. */
    fun mediaPanel(base:Int,available:Int,cell:Int,chrome:Int):MediaPanel {
        require(base>0&&available>0&&cell>0&&chrome>=0)
        val desired=maxOf(base.toLong(),cell.toLong()+chrome.toLong())
        val panel=desired.coerceAtMost(available.toLong()).toInt()
        return MediaPanel(panel,minOf(cell,(panel.toLong()-chrome).coerceAtLeast(1).toInt()))
    }
    fun mediaColumns(width:Int,cell:Int,maximum:Int):Int {
        require(width>0&&cell>0&&maximum>0);return (width/cell).coerceIn(1,maximum)
    }
}
data class KeyboardPreferences(val hand:KeyboardHand=KeyboardHand.FULL,val height:KeyboardHeight=KeyboardHeight.STANDARD,
    val theme:KeyboardTheme=KeyboardTheme.GLASS,val haptics:Boolean=false,
    val keyLabels:KeyboardScale=KeyboardScale.SYSTEM,val emojiPreview:KeyboardScale=KeyboardScale.SYSTEM) {
    fun encode():Map<String,Any> = mapOf("version" to 2,"hand" to hand.name,"height" to height.name,"theme" to theme.name,"haptics" to haptics,"keyLabels" to keyLabels.name,"emojiPreview" to emojiPreview.name)
    companion object {
        fun decode(values:Map<String,*>):KeyboardPreferences {
            val version=values["version"]
            if(version!=1&&version!=2)return KeyboardPreferences()
            fun scale(key:String)=if(version==2)KeyboardScale.entries.firstOrNull{it.name==values[key]}?:KeyboardScale.SYSTEM else KeyboardScale.SYSTEM
            return KeyboardPreferences(KeyboardHand.entries.firstOrNull{it.name==values["hand"]}?:KeyboardHand.FULL,
                KeyboardHeight.entries.firstOrNull{it.name==values["height"]}?:KeyboardHeight.STANDARD,
                KeyboardTheme.entries.firstOrNull{it.name==values["theme"]}?:KeyboardTheme.GLASS,values["haptics"]==true,scale("keyLabels"),scale("emojiPreview"))
        }
    }
}
object KeyboardPresentation {
    /** Usable keyboard >=320dp and a >=48dp rail; small windows retain saved preference but render full width. */
    fun contentWidth(width:Int,density:Float,hand:KeyboardHand):Int {
        require(width>0&&density.isFinite()&&density>0)
        if(hand==KeyboardHand.FULL)return width
        val proposed=(width*.86f).toInt()
        return if(proposed>=320*density&&width-proposed>=48*density)proposed else width
    }
    fun rowHeight(width:Int,height:Int,density:Float,landscape:Boolean,choice:KeyboardHeight):Int {
        require(width>0&&height>0&&density.isFinite()&&density>0)
        val base=KeyboardLayoutSpec.rowHeightPx(width,height,density,landscape)
        return (base*choice.factor).toInt().coerceIn((32*density).toInt().coerceAtLeast(1),
            (if(landscape)46*density else 65*density).toInt().coerceAtLeast(1))
    }
}

/** Shared process confirmation fence: a failed write must not expose a mutated backend cache. */
class KeyboardPreferenceRepository(read:()->Map<String,*>,private val write:(KeyboardPreferences)->Boolean) {
    private var confirmed=KeyboardPreferences.decode(read())
    @Synchronized fun load()=confirmed
    @Synchronized fun save(value:KeyboardPreferences):Boolean {
        val saved=runCatching{write(value)}.getOrDefault(false)
        if(saved)confirmed=value
        return saved
    }
}
