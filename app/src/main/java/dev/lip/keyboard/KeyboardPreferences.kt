package dev.lip.keyboard

/** UI-only preferences: never store typed text or change editor policy. */
enum class KeyboardHand(val label:String) { FULL("Full width"), LEFT("Left hand"), RIGHT("Right hand") }
enum class KeyboardHeight(val label:String,val factor:Float,val percent:Int) { COMPACT("Compact",.9f,90), STANDARD("Standard",1f,100), TALL("Tall",1.15f,115) }
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
    val keyLabels:KeyboardScale=KeyboardScale.SYSTEM,val emojiPreview:KeyboardScale=KeyboardScale.SYSTEM,
    val heightPercent:Int=height.percent) {
    init{require(heightPercent in 80..140)}
    fun encode():Map<String,Any> = mapOf("version" to 3,"hand" to hand.name,"height" to height.name,"theme" to theme.name,"haptics" to haptics,"keyLabels" to keyLabels.name,"emojiPreview" to emojiPreview.name,"heightPercent" to heightPercent)
    companion object {
        fun decode(values:Map<String,*>):KeyboardPreferences {
            val version=values["version"]
            if(version!=1&&version!=2&&version!=3)return KeyboardPreferences()
            val legacyHeight=KeyboardHeight.entries.firstOrNull{it.name==values["height"]}?:KeyboardHeight.STANDARD
            val percent=if(version==3)(values["heightPercent"] as? Int)?.takeIf{it in 80..140}?:100 else legacyHeight.percent
            fun scale(key:String)=if(version==2||version==3)KeyboardScale.entries.firstOrNull{it.name==values[key]}?:KeyboardScale.SYSTEM else KeyboardScale.SYSTEM
            return KeyboardPreferences(KeyboardHand.entries.firstOrNull{it.name==values["hand"]}?:KeyboardHand.FULL,
                legacyHeight,
                KeyboardTheme.entries.firstOrNull{it.name==values["theme"]}?:KeyboardTheme.GLASS,values["haptics"]==true,scale("keyLabels"),scale("emojiPreview"),percent)
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
        return rowHeight(width,height,density,landscape,choice.percent)
    }
    fun rowHeight(width:Int,height:Int,density:Float,landscape:Boolean,percent:Int):Int {
        require(width>0&&height>0&&density.isFinite()&&density>0&&percent in 80..140)
        val base=KeyboardLayoutSpec.rowHeightPx(width,height,density,landscape)
        // Preserve historical Float multiplication/truncation at the exact presets.
        val factor=KeyboardHeight.entries.firstOrNull{it.percent==percent}?.factor?:percent/100f
        return (base*factor).toInt().coerceIn((32*density).toInt().coerceAtLeast(1),
            (if(landscape)46*density else 65*density).toInt().coerceAtLeast(1))
    }
}

/** Shared process confirmation fence: a failed write must not expose a mutated backend cache. */
class KeyboardPreferenceRepository(read:()->Map<String,*>,private val write:(KeyboardPreferences)->Boolean) {
    private var confirmed=KeyboardPreferences.decode(runCatching(read).getOrDefault(emptyMap<String,Any>()))
    @Synchronized fun load()=confirmed
    @Synchronized fun save(value:KeyboardPreferences):Boolean = update{value}
    /** Field-specific CPU transform of the LATEST confirmed value. No UI/IPC callbacks here. */
    @Synchronized fun update(transform:(KeyboardPreferences)->KeyboardPreferences):Boolean {
        val value=runCatching{transform(confirmed)}.getOrNull()?:return false
        val saved=runCatching{write(value)}.getOrDefault(false)
        if(saved)confirmed=value
        return saved
    }
    fun saveHeight(percent:Int):Boolean {
        if(percent !in 80..140)return false
        return update{current->current.copy(height=KeyboardHeight.entries.firstOrNull{it.percent==percent}?:current.height,heightPercent=percent)}
    }
}
