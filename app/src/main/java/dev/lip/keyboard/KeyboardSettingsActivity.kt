package dev.lip.keyboard

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.widget.*

/** UI-only, non-exported settings. No text editor, account, permission or telemetry. */
class KeyboardSettingsActivity:Activity() {
    private lateinit var store:KeyboardPreferencesStore
    private var value=KeyboardPreferences()
    private var rendering=false
    private var screen=Any()
    private var heightControls:KeyboardHeightControls?=null
    override fun onCreate(state:Bundle?){super.onCreate(state);store=KeyboardPreferencesStore(this);value=store.load();render()}
    private fun px(n:Int)=(n*resources.displayMetrics.density).toInt()
    override fun onStart(){super.onStart();if(::store.isInitialized){value=store.load();render()}}
    override fun onStop(){screen=Any();heightControls?.close();super.onStop()}
    private fun save(update:(KeyboardPreferences)->KeyboardPreferences){
        if(rendering)return
        if(store.update(update))value=store.load() else {
            Toast.makeText(this,"Keyboard settings could not be saved",Toast.LENGTH_LONG).show();render()
        }
    }
    private fun render(){
        heightControls?.close();screen=Any();val token=screen
        rendering=true
        val scroll=ScrollView(this).apply{setBackgroundColor(Color.BLACK);isFillViewport=true}
        val column=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(px(24),px(12),px(24),px(24))}
        fun label(text:String,size:Float=16f)=TextView(this).apply{this.text=text;textSize=size;setTextColor(Color.WHITE);setPadding(0,px(12),0,px(8))}
        column.addView(GlassKeyButton(this,capsule=true).apply{text="Back to typing";contentDescription="Back from keyboard preferences";setOnClickListener{finish()}})
        column.addView(label("Your keyboard.",32f).apply{typeface=Typeface.create("sans-serif-light",Typeface.NORMAL)})
        column.addView(label("Dark by design. These settings change appearance and touch feedback—not what Lip stores.",15f))
        fun <T> choices(title:String,items:List<T>,selected:T,name:(T)->String,choose:(T)->Unit){
            column.addView(label(title,20f))
            val group=RadioGroup(this).apply{orientation=RadioGroup.VERTICAL}
            items.forEach{item->
                val button=RadioButton(this).apply{id=View.generateViewId();text=name(item);contentDescription="$title: ${name(item)}";setTextColor(Color.WHITE);buttonTintList=android.content.res.ColorStateList.valueOf(Color.WHITE);minHeight=px(48);isChecked=item==selected}
                group.addView(button)
                button.setOnClickListener{if(screen===token&&!isFinishing&&!isDestroyed&&button.isAttachedToWindow&&button.hasWindowFocus())choose(item)}
            }
            column.addView(group)
        }
        column.addView(GlassKeyButton(this,capsule=true).apply{
            text="Customize toolbar";contentDescription="Customize toolbar";minHeight=px(48)
            setOnClickListener{startActivity(android.content.Intent(this@KeyboardSettingsActivity,ToolbarCustomizerActivity::class.java))}
        })
        choices("Layout",KeyboardHand.entries,value.hand,{it.label}){hand->save{it.copy(hand=hand)}}
        column.addView(label("Narrow windows use full width. Your preferred side is kept.",14f))
        heightControls=KeyboardHeightControls(this,value.heightPercent,
            {percent->!rendering&&screen===token&&!isFinishing&&!isDestroyed&&store.saveHeight(percent)},
            {outcome->if(screen===token&&!isFinishing&&!isDestroyed){
                value=store.load()
                if(outcome==KeyboardHeightDraft.Outcome.FAILED)Toast.makeText(this,"Keyboard height was not saved. Confirmed height retained.",Toast.LENGTH_LONG).show()
                render()
            }})
        column.addView(heightControls)
        val preview=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;isBaselineAligned=false}
        val previewKey=GlassKeyButton(this).apply{
            text="Ag";contentDescription="Key label size preview";labelScale=value.keyLabels;fitText(26);isClickable=false
        }
        val previewEmoji=TextView(this).apply{
            text="😀";contentDescription="Emoji size preview";gravity=android.view.Gravity.CENTER
            textSize=32*value.emojiPreview.factor
        }
        preview.addView(previewKey,LinearLayout.LayoutParams(0,px(76),1f))
        preview.addView(previewEmoji,LinearLayout.LayoutParams(0,px(96),1f));column.addView(preview)
        choices("Key label size",KeyboardScale.entries,value.keyLabels,{it.label}){
            val selected=it;save{current->current.copy(keyLabels=selected)};previewKey.labelScale=value.keyLabels
        }
        choices("Emoji preview size",KeyboardScale.entries,value.emojiPreview,{it.label}){
            val selected=it;save{current->current.copy(emojiPreview=selected)};previewEmoji.textSize=32*value.emojiPreview.factor
        }
        column.addView(label("System follows Android's font size. Larger labels fit within each key; emoji previews fit the available window. This changes the picker, not emoji size in messages.",14f))
        if(StickerBrowserFeature.enabled(this)){
            column.addView(GlassKeyButton(this,capsule=true).apply{
                text="Manage local sticker packs";contentDescription="Manage local sticker packs"
                setOnClickListener{startActivity(android.content.Intent(this@KeyboardSettingsActivity,dev.lip.stickers.StickerPacksActivity::class.java))}
            })
            val stickerStore=StickerPreferencesStore(this)
            choices("Sticker preview size",KeyboardScale.entries,stickerStore.load(),{it.label}){scale->
                if(!rendering&&!stickerStore.save(scale)){
                    Toast.makeText(this,"Sticker size could not be saved",Toast.LENGTH_LONG).show();render()
                }
            }
            column.addView(label("Changes local sticker browsing only—not original image resolution or recipient size. Import only files you may use; rights declarations are not verified.",14f))
        }
        choices("Material",KeyboardTheme.entries,value.theme,{it.label}){theme->save{it.copy(theme=theme)}}
        column.addView(Switch(this).apply{
            text="Key haptics";contentDescription="Key haptics";setTextColor(Color.WHITE);minHeight=px(56);isChecked=value.haptics
            setOnCheckedChangeListener{_,checked->if(screen===token)save{it.copy(haptics=checked)}}
        })
        column.addView(label("Haptics respect Android's system setting and device support. Changes apply when you return to typing.",14f))
        scroll.addView(column);setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener{view,insets->val bars=insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets}
        rendering=false
    }
}
