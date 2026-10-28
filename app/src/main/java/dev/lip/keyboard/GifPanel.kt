package dev.lip.keyboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Local-only, measured-width GIF browser. Text flows; preview decoding stays bounded. */
class GifPanel(context:Context,height:Int,query:String,private val compatible:Boolean,
    private val pick:(GifItem)->Unit,search:()->Unit,back:()->Unit,cancel:()->Unit,
):LinearLayout(context),AutoCloseable{
    private val main=Handler(Looper.getMainLooper())
    private val decoder=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue<Runnable>(12),{r->Thread(r,"lip-gif-preview").apply{isDaemon=true}})
    private val tiles=ArrayList<Pair<View,ImageView>>()
    private val animations=ArrayList<Pair<View,AnimatedImageDrawable>>()
    private var closed=false
    private var busy=false
    private val baseHeight=height
    private val headerHeight:Int
    private val content:LinearLayout
    private val grid:GifGrid
    private var emptyState:TextView?=null
    private val caption:TextView
    private val restingCaption=if(compatible)"Local GIFs · ${query.ifEmpty{"all reactions"}}" else "This editor does not advertise GIF attachments · browse only"
    private val cancelKey:GlassKeyButton
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
        val width=View.MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val contentWidth=(width-content.paddingLeft-content.paddingRight).coerceAtLeast(1)
        val exactWidth=View.MeasureSpec.makeMeasureSpec(contentWidth,View.MeasureSpec.EXACTLY)
        val naturalHeight=View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED)
        caption.measure(exactWidth,naturalHeight)
        grid.measure(exactWidth,naturalHeight)
        emptyState?.measure(exactWidth,naturalHeight)
        val firstRow=emptyState?.measuredHeight?:grid.firstRowHeight()
        val intro=caption.measuredHeight+content.paddingTop+content.paddingBottom
        val displayLimit=(resources.displayMetrics.heightPixels*.8f).toInt().coerceAtLeast(1)
        val available=if(View.MeasureSpec.getMode(heightMeasureSpec)==View.MeasureSpec.UNSPECIFIED)displayLimit
            else minOf(displayLimit,View.MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1))
        val panel=GifLayoutSpec.panelHeight(baseHeight,available,headerHeight,intro,firstRow)
        super.onMeasure(widthMeasureSpec,View.MeasureSpec.makeMeasureSpec(panel,View.MeasureSpec.EXACTLY))
    }
    init{
        require(height>0)
        orientation=VERTICAL;setBackgroundColor(KeyboardVisualTokens.BACKDROP)
        layoutParams=LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT)
        addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->motion()}
        val density=resources.displayMetrics.density
        val padding=kotlin.math.ceil((8*density).toDouble()).toInt()
        headerHeight=kotlin.math.ceil((48*density).toDouble()).toInt()
        fun button(text:String,description:String,action:()->Unit)=GlassKeyButton(context).apply{
            this.text=text;contentDescription=description;fitText(14);setOnClickListener{if(!closed)action()}
        }
        val header=LinearLayout(context).apply{isBaselineAligned=false}
        header.addView(button("Search","Search local GIF catalog",search),LayoutParams(0,headerHeight,2f))
        cancelKey=button("Cancel","Cancel pending GIF attachment",cancel).apply{visibility=View.GONE}
        header.addView(cancelKey,LayoutParams(0,headerHeight,1.5f))
        header.addView(button("ABC","Return from GIFs to letters",back),LayoutParams(0,headerHeight,1f));addView(header,LayoutParams(-1,headerHeight))
        content=LinearLayout(context).apply{orientation=VERTICAL;setPadding(padding,padding,padding,padding)}
        caption=TextView(context).apply{tag="lip:gif:status"
            setTextColor(KeyboardVisualTokens.FOREGROUND);textSize=12f
            text=restingCaption;setPadding(0,0,0,padding)
        };content.addView(caption,LayoutParams(-1,LayoutParams.WRAP_CONTENT))
        grid=GifGrid(context)
        val entries=GifCatalog.search(query)
        if(entries.isEmpty()){
            emptyState=TextView(context).apply{text="No local match. Try happy, love, thanks or party.";textSize=12f;setTextColor(KeyboardVisualTokens.FOREGROUND)}
            content.addView(emptyState,LayoutParams(-1,LayoutParams.WRAP_CONTENT))
        }
        entries.forEach{item->
            val tile=LinearLayout(context).apply{
                orientation=VERTICAL;background=DarkGlassDrawable(resources.displayMetrics.density)
                contentDescription="Attach GIF: ${item.title}";isClickable=true;isFocusable=true;isEnabled=compatible
                setOnClickListener{if(!closed&&!busy&&compatible)pick(item)}
            }
            val image=ImageView(context).apply{scaleType=ImageView.ScaleType.FIT_CENTER;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
            tile.addView(image,LayoutParams(-1,kotlin.math.ceil((96*density).toDouble()).toInt()))
            tile.addView(TextView(context).apply{
                tag="lip:gif:title";text=item.title;textSize=11f;setTextColor(KeyboardVisualTokens.FOREGROUND)
                gravity=android.view.Gravity.CENTER_HORIZONTAL;setPadding(padding,0,padding,padding)
            },LayoutParams(-1,LayoutParams.WRAP_CONTENT))
            grid.addView(tile,GridLayout.LayoutParams());tiles.add(tile to image)
            decoder.execute{
                val result=runCatching{
                    val data=GifAssets.read(context,item)
                    ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(data))){d,info,_->
                        val target=(96*resources.displayMetrics.density).toInt().coerceAtMost(256)
                        d.setTargetSize(target,target);d.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                }
                main.post{
                    if(!closed)result.onSuccess{drawable->image.setImageDrawable(drawable);if(drawable is AnimatedImageDrawable){animations.add(tile to drawable);motion()}}
                }
            }
        }
        if(entries.isNotEmpty())content.addView(grid,LayoutParams(-1,LayoutParams.WRAP_CONTENT))
        content.addView(TextView(context).apply{
            tag="lip:gif:credit";text="Animated Noto Emoji by Google · CC BY4.0 · credits embedded in GIFs";textSize=10f;setTextColor(KeyboardVisualTokens.FOREGROUND)
            setPadding(0,padding,0,0)
            contentDescription=GifCatalog.CREDIT+". Source googlefonts.github.io/noto-emoji-files; license creativecommons.org/licenses/by/4.0."
        },LayoutParams(-1,LayoutParams.WRAP_CONTENT))
        val scroll=ScrollView(context).apply{tag="lip:gif:scroll";contentDescription="Local GIF results";addView(content);viewTreeObserver.addOnScrollChangedListener{motion()}}
        addView(scroll,LayoutParams(-1,0,1f))
    }
    fun setBusy(value:Boolean){if(closed)return;busy=value;cancelKey.visibility=if(value)View.VISIBLE else View.GONE;tiles.forEach{it.first.isEnabled=!value&&compatible};caption.text=if(value)"Preparing licensed GIF locally…" else restingCaption}
    private fun motion(){
        val enabled=!closed&&isShown&&ValueAnimator.areAnimatorsEnabled()
        animations.forEach{(tile,drawable)->val bounds=android.graphics.Rect();if(enabled&&tile.getGlobalVisibleRect(bounds))drawable.start()else drawable.stop()}
    }
    override fun onAttachedToWindow(){super.onAttachedToWindow();post{motion()}}
    override fun onDetachedFromWindow(){close();super.onDetachedFromWindow()}
    override fun close(){closed=true;animations.forEach{it.second.stop()};tiles.forEach{it.second.setImageDrawable(null)};animations.clear();decoder.shutdownNow()}

    /** GridLayout owns intrinsic wrapped row heights; only width/column geometry is fixed. */
    private class GifGrid(context:Context):GridLayout(context){
        private var lastWidth=-1
        private var lastColumns=0
        private var lastCount=-1
        init{useDefaultMargins=false;orientation=GridLayout.HORIZONTAL}
        override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
            val width=(View.MeasureSpec.getSize(widthMeasureSpec)-paddingLeft-paddingRight).coerceAtLeast(1)
            val count=GifLayoutSpec.columns(width,resources.displayMetrics.density)
            if(width!=lastWidth||count!=lastColumns||childCount!=lastCount){
                val gap=kotlin.math.ceil((6*resources.displayMetrics.density).toDouble()).toInt()
                val tileWidth=((width-gap*(count-1))/count).coerceAtLeast(1)
                // Expansion must precede larger child spans; shrinking must follow
                // reassigned smaller spans. Android validates both operations.
                if(count>columnCount)columnCount=count
                for(i in 0 until childCount){
                    val params=getChildAt(i).layoutParams as GridLayout.LayoutParams
                    params.width=tileWidth;params.height=ViewGroup.LayoutParams.WRAP_CONTENT
                    params.columnSpec=GridLayout.spec(i%count,1,GridLayout.FILL);params.rowSpec=GridLayout.spec(i/count,1,GridLayout.FILL)
                    params.setMargins(if(i%count==0)0 else gap,if(i<count)0 else gap,0,0)
                    getChildAt(i).layoutParams=params
                }
                if(columnCount!=count)columnCount=count
                lastWidth=width;lastColumns=count;lastCount=childCount
            }
            super.onMeasure(widthMeasureSpec,heightMeasureSpec)
        }
        fun firstRowHeight():Int = (0 until minOf(childCount,lastColumns)).maxOfOrNull{index->
            val child=getChildAt(index);val params=child.layoutParams as GridLayout.LayoutParams
            child.measuredHeight+params.topMargin+params.bottomMargin
        }?:0
    }
}
