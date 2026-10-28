package dev.lip.keyboard

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/** Reference-density native browser. GridView bounds live cells; no pages or full-catalog view inflation. */
class EmojiPanel(context:Context,height:Int,columns:Int,catalog:EmojiCatalog?,error:String?,query:String,category:Int?,
    recents:EmojiRecents,privateField:Boolean,delete:View,
    private val pick:(EmojiItem)->Unit,search:()->Unit,back:()->Unit,gifs:()->Unit,
    chooseCategory:(Int?)->Unit,enableRecent:()->Unit,
    previewSize:KeyboardScale=KeyboardScale.SYSTEM,
):LinearLayout(context){
    private var closed=false
    private val baseHeight=height
    private var cellPixels=1
    private var requestedCellPixels=1
    private var minimumCellPixels=1
    private lateinit var mediaBody:FrameLayout
    private lateinit var roomWarning:TextView
    private var activeMedia:View?=null
    private var toolbarPixels=1
    private var toneChoicesOpen=false
    private lateinit var sectionHeading:TextView
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
        // Headings wrap at the actual keyboard/one-hand width, not display width.
        // The tone chooser owns an extra Cancel row. Budget it before its grid.
        val width=View.MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        sectionHeading.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        val displayLimit=(resources.displayMetrics.heightPixels*.8f).toInt().coerceAtLeast(1)
        val parentLimit=if(View.MeasureSpec.getMode(heightMeasureSpec)==View.MeasureSpec.UNSPECIFIED)displayLimit
            else minOf(displayLimit,View.MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1))
        val chrome=(if(toneChoicesOpen)3 else 2)*toolbarPixels+sectionHeading.measuredHeight
        val budget=KeyboardSizing.mediaPanel(baseHeight,parentLimit,requestedCellPixels,chrome)
        cellPixels=maxOf(minimumCellPixels,budget.cell)
        // Never expose an undersized/torn first row under a tighter parent cap.
        // Preserve the current chooser while a resize temporarily hides it.
        activeMedia?.visibility=if(budget.cell>=minimumCellPixels)View.VISIBLE else View.INVISIBLE
        roomWarning.visibility=if(activeMedia!=null&&budget.cell<minimumCellPixels)View.VISIBLE else View.GONE
        fun resizeRows(view:View){
            if(view is GridView)for(i in 0 until view.childCount){
                val child=view.getChildAt(i)
                if(child.layoutParams.height!=cellPixels)child.layoutParams=child.layoutParams.apply{this.height=cellPixels}
            }else if(view is ViewGroup)for(i in 0 until view.childCount)resizeRows(view.getChildAt(i))
        }
        resizeRows(mediaBody)
        super.onMeasure(widthMeasureSpec,View.MeasureSpec.makeMeasureSpec(budget.height,View.MeasureSpec.EXACTLY))
    }
    init {
        orientation=VERTICAL;setBackgroundColor(KeyboardVisualTokens.TOOLBAR);layoutParams=LayoutParams(-1,height)
        val density=resources.displayMetrics.density
        val previewSp=KeyboardSizing.legendSp(32,previewSize)
        val glyphPixels=android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,previewSp.toFloat(),resources.displayMetrics)
        val desiredCell=KeyboardSizing.mediaCellPixels(glyphPixels,density)
        val h=((if(columns>7)40 else 48)*density).toInt()
        requestedCellPixels=desiredCell;cellPixels=desiredCell;toolbarPixels=h
        minimumCellPixels=kotlin.math.ceil((48*density).toDouble()).toInt()
        layoutParams=LayoutParams(-1,LayoutParams.WRAP_CONTENT)
        fun button(text:String,description:String,action:()->Unit)=GlassKeyButton(context).apply{
            this.text=text;contentDescription=description;fitText(18);setOnClickListener{if(!closed)action()}
        }
        val header=LinearLayout(context).apply{isBaselineAligned=false}
        header.addView(button("‹","Close emoji browser",back).apply{background=DarkGlassDrawable(density,capsule=true,circle=true)},LayoutParams((40*density).toInt(),h))
        header.addView(button(if(query.isBlank())"Search" else query,"Search offline emoji",search).apply{
            setSingleLine();ellipsize=android.text.TextUtils.TruncateAt.END;background=DarkGlassDrawable(density,capsule=true)
        },LayoutParams((100*density).toInt(),h))
        val categories=LinearLayout(context).apply{isBaselineAligned=false}
        val glyphs=listOf("","","","","","","","","#","")
        val icons=listOf(KeyIcon.RECENT,KeyIcon.FACE,KeyIcon.PERSON,KeyIcon.LEAF,KeyIcon.FOOD,KeyIcon.PLANE,KeyIcon.BALL,KeyIcon.BULB,KeyIcon.NONE,KeyIcon.FLAG)
        glyphs.forEachIndexed{i,glyph->
            val group=if(i==0)-1 else i-1
            categories.addView(button(glyph,if(i==0)"Show session recent emoji" else "Show emoji category ${EmojiCatalog.CATEGORIES[group]}",{chooseCategory(group)}).apply{
                icon=icons[i];accessibilityToggle=category==group;stateDescription=if(category==group)"Selected" else "Not selected"
                background=DarkGlassDrawable(density,capsule=true,circle=category==group,flat=category!=group)
            },LayoutParams((40*density).toInt(),h))
        }
        header.addView(HorizontalScrollView(context).apply{
            contentDescription="Emoji categories";isHorizontalScrollBarEnabled=false;addView(categories)
            post{if(!closed&&category!=null)scrollTo(((category+1-1).coerceAtLeast(0)*40*density).toInt(),0)}
        },LayoutParams(0,h,1f));addView(header)
        val recent=category==-1
        val entries=when{catalog==null->emptyList();recent->recents.items(catalog);else->catalog.search(query,category)}
        val heading=when{catalog==null->error?:"Loading offline emoji…";recent->"Recent picks · this field only";query.isNotBlank()->if(entries.size==1)"1 match" else "${entries.size} matches";category==null->"Emoji";else->EmojiCatalog.CATEGORIES[category]}
        // A compact heading, not another toolbar; wrap to preserve error recovery at large text sizes.
        val headingView=TextView(context).apply{text=heading;setTextColor(Color.WHITE);textSize=13f;setPadding((8*density).toInt(),0,0,0)}
        sectionHeading=headingView
        addView(headingView,LayoutParams(-1,-2))
        val body=FrameLayout(context);mediaBody=body
        addView(body,LayoutParams(-1,0,1f))
        roomWarning=TextView(context).apply{
            text="More room needed for emoji. Enlarge this window or return to typing."
            contentDescription="Emoji needs more room";setTextColor(Color.WHITE);textSize=13f;visibility=View.GONE
        }
        if(recent&&!recents.enabled){
            val content=LinearLayout(context).apply{orientation=VERTICAL}
            content.addView(TextView(context).apply{text=if(privateField)"Recent picks are off in private fields." else "Off by default. Remember picks only while this field is open; nothing is saved.";setTextColor(Color.WHITE);textSize=14f})
            if(!privateField)content.addView(button("Enable session recents","Enable emoji recents for this field only",enableRecent),LayoutParams(-1,h))
            body.addView(content,FrameLayout.LayoutParams(-1,-1))
        }else if(catalog!=null&&entries.isEmpty()){
            body.addView(TextView(context).apply{text=if(recent)"No picks in this field yet." else "No match. Try coffee, heart or Japan.";setTextColor(Color.WHITE);textSize=14f},FrameLayout.LayoutParams(-1,-1))
        }else{
            lateinit var grid:GridView
            fun showTones(item:EmojiItem,variants:List<EmojiItem>){
                if(closed||!isAttachedToWindow||grid.visibility!=View.VISIBLE||variants.isEmpty())return
                grid.visibility=View.GONE;toneChoicesOpen=true
                headingView.text="Skin tones · ${item.title}"
                val chooser=LinearLayout(context).apply{orientation=VERTICAL;contentDescription="Emoji skin tone choices"}
                var active=true
                chooser.addView(button("Cancel","Cancel emoji skin tone choices"){
                    if(active&&chooser.parent===body&&chooser.isAttachedToWindow&&chooser.isShown){
                        active=false;toneChoicesOpen=false;body.removeView(chooser);activeMedia=grid;grid.visibility=View.VISIBLE;headingView.text=heading
                    }
                },LayoutParams(-1,h))
                chooser.addView(object:GridView(context){
                    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
                        val width=(View.MeasureSpec.getSize(widthMeasureSpec)-paddingLeft-paddingRight).coerceAtLeast(1)
                        val count=KeyboardSizing.mediaColumns(width,cellPixels,if(columns>7)8 else 6)
                        if(numColumns!=count)numColumns=count
                        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
                    }
                }.apply{
                    numColumns=if(columns>7)8 else 6;stretchMode=GridView.STRETCH_COLUMN_WIDTH
                    selector=android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                    adapter=object:BaseAdapter(){
                        override fun getCount()=variants.size
                        override fun getItem(position:Int)=variants[position]
                        override fun getItemId(position:Int)=position.toLong()
                        override fun getView(position:Int,convertView:View?,parent:ViewGroup):View {
                            val variant=variants[position]
                            return ((convertView as? GlassKeyButton)?:GlassKeyButton(context)).apply{
                                text=variant.sequence;contentDescription="Choose emoji ${variant.title}";labelScale=previewSize;fitText(30)
                                background=DarkGlassDrawable(density,flat=true)
                                if(layoutParams==null)layoutParams=android.widget.AbsListView.LayoutParams(-1,cellPixels)
                                else if(layoutParams.height!=cellPixels)layoutParams=layoutParams.apply{this.height=cellPixels}
                                setOnClickListener{if(!closed&&active&&chooser.parent===body&&isAttachedToWindow&&isShown&&chooser.isShown){active=false;pick(variant)}}
                            }
                        }
                    }
                },LayoutParams(-1,0,1f))
                activeMedia=chooser;body.addView(chooser,FrameLayout.LayoutParams(-1,-1));roomWarning.bringToFront()
            }
            grid=object:GridView(context){
                override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
                    val width=(View.MeasureSpec.getSize(widthMeasureSpec)-paddingLeft-paddingRight).coerceAtLeast(1)
                    val count=KeyboardSizing.mediaColumns(width,cellPixels,columns)
                    if(numColumns!=count)numColumns=count
                    super.onMeasure(widthMeasureSpec,heightMeasureSpec)
                }
            }.apply{
                contentDescription="Offline emoji results";numColumns=columns;stretchMode=GridView.STRETCH_COLUMN_WIDTH
                horizontalSpacing=0;verticalSpacing=0;isVerticalScrollBarEnabled=false
                selector=android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                adapter=object:BaseAdapter(){
                    override fun getCount()=entries.size
                    override fun getItem(position:Int)=entries[position]
                    override fun getItemId(position:Int)=position.toLong()
                    override fun getView(position:Int,convertView:View?,parent:ViewGroup):View {
                        val item=entries[position]
                        return ((convertView as? GlassKeyButton)?:GlassKeyButton(context)).apply{
                            text=item.sequence;contentDescription="Insert emoji ${item.title}";labelScale=previewSize;fitText(32)
                            background=DarkGlassDrawable(density,flat=true)
                            // Preserve framework LayoutParams.forceAdd bookkeeping on measured/recycled cells.
                            if(layoutParams==null)layoutParams=android.widget.AbsListView.LayoutParams(-1,cellPixels)
                                else if(layoutParams.height!=cellPixels)layoutParams=layoutParams.apply{this.height=cellPixels}
                            val variants=catalog?.skinToneVariants(item).orEmpty()
                            stateDescription=if(variants.isEmpty())null else "Touch and hold for skin tone choices"
                            setOnLongClickListener(if(variants.isEmpty())null else View.OnLongClickListener{
                                showTones(item,variants);true
                            })
                            isLongClickable=variants.isNotEmpty() // Recycling must clear framework long-click semantics too.
                            setOnClickListener{if(!closed&&grid.visibility==View.VISIBLE&&isAttachedToWindow)pick(item)}
                        }
                    }
                }
            }
            activeMedia=grid;body.addView(grid,FrameLayout.LayoutParams(-1,-1))
        }
        body.addView(roomWarning,FrameLayout.LayoutParams(-1,-1))
        val footer=LinearLayout(context).apply{isBaselineAligned=false;setBackgroundColor(Color.BLACK)}
        footer.addView(button("ABC","Return from emoji to letters",back),LayoutParams(0,h,1.5f))
        footer.addView(button("","Emoji mode selected"){}.apply{
            icon=KeyIcon.FACE;accessibilityToggle=true;stateDescription="Selected";background=DarkGlassDrawable(density,capsule=true)
        },LayoutParams(0,h,2f))
        footer.addView(button("GIF","Open GIFs from emoji",gifs).apply{isEnabled=!privateField},LayoutParams(0,h,2f))
        footer.addView(delete,LayoutParams(0,h,1.5f));addView(footer)
    }
    fun close(){closed=true}
    override fun onDetachedFromWindow(){close();super.onDetachedFromWindow()}
}
