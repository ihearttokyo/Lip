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
    override fun onCreate(state:Bundle?){super.onCreate(state);store=KeyboardPreferencesStore(this);value=store.load();render()}
    private fun px(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun save(next:KeyboardPreferences){
        if(rendering)return
        if(store.save(next))value=next else {
            Toast.makeText(this,"Keyboard settings could not be saved",Toast.LENGTH_LONG).show();render()
        }
    }
    private fun render(){
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
                button.setOnClickListener{choose(item)}
            }
            column.addView(group)
        }
        choices("Layout",KeyboardHand.entries,value.hand,{it.label}){save(value.copy(hand=it))}
        column.addView(label("Narrow windows use full width. Your preferred side is kept.",14f))
        choices("Key height",KeyboardHeight.entries,value.height,{it.label}){save(value.copy(height=it))}
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
            save(value.copy(keyLabels=it));previewKey.labelScale=value.keyLabels
        }
        choices("Emoji preview size",KeyboardScale.entries,value.emojiPreview,{it.label}){
            save(value.copy(emojiPreview=it));previewEmoji.textSize=32*value.emojiPreview.factor
        }
        column.addView(label("System follows Android's font size. Larger labels fit within each key; emoji previews fit the available window. This changes the picker, not emoji size in messages.",14f))
        if(StickerBrowserFeature.enabled(this)){
            val stickerStore=StickerPreferencesStore(this)
            choices("Sticker preview size",KeyboardScale.entries,stickerStore.load(),{it.label}){scale->
                if(!rendering&&!stickerStore.save(scale)){
                    Toast.makeText(this,"Sticker size could not be saved",Toast.LENGTH_LONG).show();render()
                }
            }
            column.addView(label("Changes local sticker browsing only—not original image resolution or the size in another app. Later-major preview; no sending yet.",14f))
        }
        choices("Material",KeyboardTheme.entries,value.theme,{it.label}){save(value.copy(theme=it))}
        column.addView(Switch(this).apply{
            text="Key haptics";contentDescription="Key haptics";setTextColor(Color.WHITE);minHeight=px(56);isChecked=value.haptics
            setOnCheckedChangeListener{_,checked->save(value.copy(haptics=checked))}
        })
        column.addView(label("Haptics respect Android's system setting and device support. Changes apply when you return to typing.",14f))
        scroll.addView(column);setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener{view,insets->val bars=insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets}
        rendering=false
    }
}
