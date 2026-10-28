package dev.lip.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Collections
import java.util.UUID

internal class StickerPanelPack(val commitId:String,val title:String,val available:Boolean,val status:String) {
    init {
        require(runCatching{UUID.fromString(commitId).toString()==commitId}.getOrDefault(false))
        require(title.isNotBlank()&&title.length<=512&&status.length<=1024)
    }
}

/** Copy-safe premultiplied sRGB RGBA only. This presentation type grants no codec or image rights. */
internal class StickerPanelPreview(val title:String,val width:Int,val height:Int,rgba:ByteArray,val asset:StickerAssetIdentity?=null) {
    private val payload:ByteArray
    init {
        require(title.isNotBlank()&&title.length<=512&&width in 1..256&&height in 1..256)
        require(rgba.size==width*height*4)
        payload=rgba.copyOf()
        for(at in payload.indices step 4){val alpha=payload[at+3].toInt() and 255
            require((0..2).all{(payload[at+it].toInt() and 255)<=alpha})
        }
    }
    fun premultipliedRgbaSrgbBytes():ByteArray=payload.copyOf()
}

internal sealed class StickerPanelState {
    object Loading:StickerPanelState()
    class Error(val message:String):StickerPanelState(){init{require(message.isNotBlank()&&message.length<=1024)}}
    class Library(packs:List<StickerPanelPack>,val offset:Int,val total:Int):StickerPanelState(){
        val packs:List<StickerPanelPack> = Collections.unmodifiableList(ArrayList(packs))
        init{StickerLayoutSpec.validatePage(offset,packs.size,total,128);require(packs.map{it.commitId}.distinct().size==packs.size)}
        val hasPrevious get()=offset>0
        val hasNext get()=offset+packs.size<total
    }
    class Pack(val title:String,val provenance:String,previews:List<StickerPanelPreview>,val offset:Int,val total:Int):StickerPanelState(){
        val previews:List<StickerPanelPreview> = Collections.unmodifiableList(ArrayList(previews))
        init{require(title.isNotBlank()&&title.length<=512&&provenance.length<=8192&&total>0);StickerLayoutSpec.validatePage(offset,previews.size,total,121)}
        val hasPrevious get()=offset>0
        val hasNext get()=offset+previews.size<total
    }
}

/** Fixture-gated caller supplies one immutable bounded page. No I/O, decoder, delivery or activation.
 * baseHeightPx is a base pixel budget, NOT an absolute cap: measured first-row/chrome
 * expansion remains bounded by the actual parent MeasureSpec and display cap.
 */
internal class StickerPanel(context:Context,baseHeightPx:Int,state:StickerPanelState,previewSize:KeyboardScale,
    back:()->Unit,manage:()->Unit,library:()->Unit,selectPack:(String)->Unit,previous:()->Unit,next:()->Unit,
    attach:((StickerAssetIdentity)->Unit)?=null,
):LinearLayout(context),AutoCloseable {
    private var closed=false
    private var binding:Any?=Any()
    private val baseHeight=baseHeightPx
    private val header:LinearLayout
    private val intro:LinearLayout
    private val content:LinearLayout
    private val grid:StickerGrid
    private val images=ArrayList<Pair<ImageView,Bitmap>>()
    private val eligibleImageTiles=ArrayList<View>()
    private var attachmentPending=false
    private val density=resources.displayMetrics.density
    private fun active(view:View,token:Any?):Boolean {
        if(closed||token==null||binding!==token||!isAttachedToWindow||!isShown||windowVisibility!=VISIBLE||
            !view.isAttachedToWindow||!view.isShown||!view.isEnabled)return false
        val visible=Rect()
        return view.getGlobalVisibleRect(visible)&&!visible.isEmpty
    }
    private fun pixels(dp:Int)=kotlin.math.ceil((dp*density).toDouble()).toInt().coerceAtLeast(1)
    private fun button(value:String,description:String,enabled:Boolean=true,action:()->Unit):GlassKeyButton {
        val token=binding
        return GlassKeyButton(context,capsule=true).apply{
            text=value;contentDescription=description;isEnabled=enabled
            setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE);textSize=16f
            setSingleLine(false);includeFontPadding=true;setPadding(pixels(8),pixels(8),pixels(8),pixels(8))
            minHeight=pixels(48);minimumHeight=pixels(48)
            interactionAllowed={active(this,token)}
            setOnClickListener{if(active(this,token))action()}
        }
    }
    private fun label(value:String,role:String,size:Float=14f)=TextView(context).apply{
        text=value;tag="lip:sticker:$role";textSize=size;setTextColor(Color.WHITE)
        includeFontPadding=true;setPadding(0,0,0,pixels(8));setLineSpacing(pixels(1).toFloat(),1f)
    }
    init {
        require(baseHeightPx>0)
        orientation=VERTICAL;setBackgroundColor(Color.BLACK);layoutParams=LayoutParams(-1,LayoutParams.WRAP_CONTENT)
        header=LinearLayout(context).apply{isBaselineAligned=false}
        header.addView(button("ABC","Return from stickers to letters",action=back),LayoutParams(0,-2,1f))
        header.addView(button("Packs","Show local sticker packs",state !is StickerPanelState.Library,library),LayoutParams(0,-2,1f))
        addView(header,LayoutParams(-1,-2))
        content=LinearLayout(context).apply{orientation=VERTICAL;setPadding(pixels(8),pixels(8),pixels(8),pixels(8))}
        intro=LinearLayout(context).apply{orientation=VERTICAL};content.addView(intro,LayoutParams(-1,-2))
        val status=when(state){
            StickerPanelState.Loading->"Loading local sticker packs…"
            is StickerPanelState.Error->state.message
            is StickerPanelState.Library->if(state.total==0)"No saved sticker packs." else "Local packs ${state.offset+1}–${state.offset+state.packs.size} of ${state.total}"
            is StickerPanelState.Pack->"Static previews ${state.offset+1}–${state.offset+state.previews.size} of ${state.total}"
        }
        intro.addView(label(status,"status"),LayoutParams(-1,-2))
        if(state is StickerPanelState.Pack){
            intro.addView(label(state.title,"title",20f),LayoutParams(-1,-2))
            intro.addView(label(state.provenance,"credit",12f),LayoutParams(-1,-2))
        }
        val attachmentAllowed=attach!=null&&state is StickerPanelState.Pack&&state.previews.any{it.asset!=null}
        val disclosure=if(attachmentAllowed)
            "Tap a sticker to request its original in the owned test editor. Rights are UNVERIFIED. No Send, activation, high-resolution or animated-playback claim."
            else "Browse only. Rights are UNVERIFIED. No sending, activation, high-resolution or animated-playback claim."
        intro.addView(label(disclosure,"disclosure",12f),LayoutParams(-1,-2))
        intro.addView(button("Manage packs","Manage local sticker packs",action=manage),LayoutParams(-1,-2))
        grid=StickerGrid(context,previewSize)
        content.addView(grid,LayoutParams(-1,-2))
        when(state){
            is StickerPanelState.Library->{
                if(state.total==0)content.addView(label("Import a pack in Manage packs, then return here. Original files stay on this device.","empty"),LayoutParams(-1,-2))
                state.packs.forEach{pack->
                    val tile=LinearLayout(context).apply{
                        orientation=VERTICAL;tag="lip:sticker:tile";background=DarkGlassDrawable(density)
                        setPadding(pixels(8),pixels(8),pixels(8),pixels(8));minimumHeight=pixels(48)
                        isClickable=pack.available;isFocusable=pack.available;isEnabled=pack.available
                        contentDescription=if(pack.available)"Inspect saved sticker pack ${pack.title}" else "Unavailable saved sticker pack ${pack.title}"
                    }
                    val token=binding
                    tile.setOnClickListener{if(pack.available&&active(tile,token))selectPack(pack.commitId)}
                    tile.addView(label(pack.title,"title",16f),LayoutParams(-1,-2))
                    tile.addView(label(pack.status,"credit",12f),LayoutParams(-1,-2));grid.addView(tile,GridLayout.LayoutParams())
                }
                pagination(state.hasPrevious,state.hasNext,previous,next)
            }
            is StickerPanelState.Pack->{
                var unavailable=false
                state.previews.forEach{preview->
                    if(!unavailable){
                        val asset=preview.asset
                        val eligible=asset!=null&&attach!=null
                        val tile=LinearLayout(context).apply{
                            orientation=VERTICAL;tag="lip:sticker:tile";background=DarkGlassDrawable(density)
                            isClickable=eligible;isFocusable=eligible
                            if(eligible){minimumHeight=pixels(48);minimumWidth=pixels(48);contentDescription="Request original sticker ${preview.title}"}
                        }
                        if(asset!=null&&attach!=null){
                            val token=binding
                            tile.setOnClickListener{if(!attachmentPending&&active(tile,token))attach(asset)}
                        }
                        val image=ImageView(context).apply{
                            tag="lip:sticker:preview";contentDescription="Static sticker preview ${preview.title}"
                            scaleType=ImageView.ScaleType.FIT_CENTER;isClickable=false;isFocusable=false
                        }
                        val bitmap=try{bitmap(preview)}catch(_:OutOfMemoryError){null}catch(_:RuntimeException){null}
                        if(bitmap==null){unavailable=true;content.addView(label("Preview display unavailable. No encoded-image fallback was used; return to packs or Manage packs.","error"),LayoutParams(-1,-2))}
                        else {
                            image.setImageBitmap(bitmap);images+=image to bitmap
                            tile.addView(image,LayoutParams(-1,1))
                            tile.addView(label(preview.title,"title",13f).apply{gravity=android.view.Gravity.CENTER_HORIZONTAL},LayoutParams(-1,-2))
                            grid.addView(tile,GridLayout.LayoutParams())
                            if(eligible)eligibleImageTiles.add(tile)
                        }
                    }
                }
                pagination(state.hasPrevious,state.hasNext,previous,next)
            }
            else->Unit
        }
        val scroll=ScrollView(context).apply{tag="lip:sticker:scroll";contentDescription="Local sticker browser results";addView(content)}
        addView(scroll,LayoutParams(-1,0,1f))
    }
    private fun pagination(hasPrevious:Boolean,hasNext:Boolean,previous:()->Unit,next:()->Unit){
        content.addView(button("Previous","Previous sticker browser page",hasPrevious,previous),LayoutParams(-1,-2))
        content.addView(button("Next","Next sticker browser page",hasNext,next),LayoutParams(-1,-2))
    }
    private fun bitmap(preview:StickerPanelPreview):Bitmap {
        val rgba=preview.premultipliedRgbaSrgbBytes();val colors=IntArray(preview.width*preview.height)
        for(n in colors.indices){val at=n*4;val alpha=rgba[at+3].toInt() and 255
            fun channel(offset:Int)=if(alpha==0)0 else (((rgba[at+offset].toInt() and 255)*255+alpha/2)/alpha).coerceIn(0,255)
            colors[n]=(alpha shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
        }
        val bitmap=Bitmap.createBitmap(preview.width,preview.height,Bitmap.Config.ARGB_8888)
        try{bitmap.setPixels(colors,0,preview.width,0,0,preview.width,preview.height);return bitmap}
        catch(t:Throwable){bitmap.recycle();throw t}
    }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
        val width=View.MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val exact=View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY)
        val natural=View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED)
        header.measure(exact,natural)
        val bodyWidth=(width-content.paddingLeft-content.paddingRight).coerceAtLeast(1)
        val bodySpec=View.MeasureSpec.makeMeasureSpec(bodyWidth,View.MeasureSpec.EXACTLY)
        intro.measure(bodySpec,natural);grid.measure(bodySpec,natural)
        val display=(resources.displayMetrics.heightPixels*.8f).toInt().coerceAtLeast(1)
        val available=if(View.MeasureSpec.getMode(heightMeasureSpec)==View.MeasureSpec.UNSPECIFIED)display else minOf(display,View.MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1))
        val panel=StickerLayoutSpec.panelHeight(baseHeight,available,header.measuredHeight.coerceAtLeast(1),intro.measuredHeight+content.paddingTop+content.paddingBottom,grid.firstRowHeight())
        super.onMeasure(widthMeasureSpec,View.MeasureSpec.makeMeasureSpec(panel,View.MeasureSpec.EXACTLY))
    }
    /** Pending original reads disable only attachment cells; pixels and browser navigation are retained. */
    fun setAttachmentPending(value:Boolean){
        if(closed)return
        attachmentPending=value
        eligibleImageTiles.forEach{it.isEnabled=!value}
    }
    override fun onDetachedFromWindow(){close();super.onDetachedFromWindow()}
    override fun close(){
        if(closed)return
        closed=true;binding=null
        eligibleImageTiles.forEach{it.isEnabled=false};eligibleImageTiles.clear()
        images.forEach{(view,bitmap)->view.setImageDrawable(null);if(!bitmap.isRecycled)bitmap.recycle()};images.clear()
    }
    private class StickerGrid(context:Context,private val scale:KeyboardScale):GridLayout(context){
        private var columns=1
        private var lastGeometry:StickerLayoutSpec.Geometry?=null
        private var lastCount=-1
        init{orientation=HORIZONTAL;useDefaultMargins=false}
        override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
            val width=View.MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
            val g=StickerLayoutSpec.geometry(width,resources.displayMetrics.density,scale);columns=g.columns
            if(g!=lastGeometry||childCount!=lastCount){
            if(g.columns>columnCount)columnCount=g.columns
            for(i in 0 until childCount){val tile=getChildAt(i)
                tile.setPadding(g.padding,g.padding,g.padding,g.padding)
                if(tile is ViewGroup)for(at in 0 until tile.childCount){val child=tile.getChildAt(at)
                    if(child.tag=="lip:sticker:preview"&&child.layoutParams.height!=g.imageEdge)child.layoutParams=child.layoutParams.apply{height=g.imageEdge}
                }
                val p=tile.layoutParams as GridLayout.LayoutParams
                p.width=g.tileWidth;p.height=ViewGroup.LayoutParams.WRAP_CONTENT
                p.columnSpec=GridLayout.spec(i%g.columns,1,GridLayout.FILL);p.rowSpec=GridLayout.spec(i/g.columns,1,GridLayout.FILL)
                p.setMargins(if(i%g.columns==0)0 else g.gap,if(i<g.columns)0 else g.gap,0,0);tile.layoutParams=p
            }
            if(columnCount!=g.columns)columnCount=g.columns
            lastGeometry=g;lastCount=childCount
            }
            super.onMeasure(widthMeasureSpec,heightMeasureSpec)
        }
        fun firstRowHeight():Int=(0 until minOf(childCount,columns)).maxOfOrNull{index->getChildAt(index).let{view->
            val p=view.layoutParams as GridLayout.LayoutParams;view.measuredHeight+p.topMargin+p.bottomMargin
        }}?:0
    }
}
