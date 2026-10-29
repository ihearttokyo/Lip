package dev.lip.keyboard

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.*

/** Local, metadata-only customization. Preview has NO consumer callbacks. */
class ToolbarCustomizerActivity:Activity() {
    private lateinit var store:ToolbarPreferencesStore
    private lateinit var baseline:ToolbarSnapshot
    private var draft=ToolbarPreferences()
    private var screen=Any()
    override fun onCreate(state:Bundle?){super.onCreate(state);store=ToolbarPreferencesStore(this);baseline=store.snapshot();draft=baseline.value;render()}
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun label(value:String,size:Float=16f)=TextView(this).apply{text=value;textSize=size;setTextColor(Color.WHITE);setPadding(0,dp(10),0,dp(8))}
    private fun button(value:String,enabled:Boolean=true,action:()->Unit):GlassKeyButton {
        val token=screen
        return GlassKeyButton(this,capsule=true).apply{
            text=value;contentDescription=value;isEnabled=enabled;minHeight=dp(48);minimumHeight=dp(48);fitText(16);setSingleLine(false)
            setPadding(dp(10),dp(8),dp(10),dp(8));interactionAllowed={screen===token&&!isFinishing&&!isDestroyed&&isAttachedToWindow&&hasWindowFocus()}
            setOnClickListener{if(interactionAllowed())action()}
        }
    }
    private fun persist(value:ToolbarPreferences){
        if(store.save(value,baseline.revision)){baseline=store.snapshot();draft=baseline.value;render("Toolbar saved. Return to typing to use it.")}
        else{render("Not saved. The confirmed toolbar is unchanged. If another screen saved, choose Reload before making a new change.")}
    }
    private fun render(message:String?=null){
        screen=Any();val scroll=ScrollView(this).apply{setBackgroundColor(Color.BLACK);isFillViewport=true;contentDescription="Toolbar customization controls"}
        val column=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(12),dp(20),dp(24))}
        column.addView(button("Back to keyboard preferences"){finish()})
        column.addView(label("Your shortcuts.",32f).apply{typeface=Typeface.create("sans-serif-light",Typeface.NORMAL)})
        column.addView(label("Tools and microphone stay at the ends. Choose and order the five middle shortcuts. Nothing runs in this preview."))
        if(!baseline.valid)column.addView(label("Saved toolbar data is unavailable. Defaults are shown; middle shortcuts stay disabled until you explicitly Save or Reset default."))
        message?.let{column.addView(label(it,14f))}
        val preview=GlassKeyboardToolbar(this).apply{contentDescription="Inert seven-position toolbar preview"}
        ToolbarActionRegistry.actions(draft).forEach{action->preview.addView(GlassKeyButton(this,flat=true).apply{
            icon=ToolbarIcons.icon(action);contentDescription="Preview: ${action.label}; no action";isClickable=false;isLongClickable=false;isFocusable=false;interactionAllowed={false}
            importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
        })}
        column.addView(preview,LinearLayout.LayoutParams(-1,dp(48)))
        for(index in 0..4){
            column.addView(label("Slot ${index+1}: ${draft.slots[index].label}",20f))
            column.addView(button("Choose shortcut for slot ${index+1}"){choose(index)})
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;isBaselineAligned=false}
            row.addView(button("Move slot ${index+1} up",index>0){draft=ToolbarActionRegistry.move(draft,index,-1);render()},LinearLayout.LayoutParams(0,-2,1f))
            row.addView(button("Move slot ${index+1} down",index<4){draft=ToolbarActionRegistry.move(draft,index,1);render()},LinearLayout.LayoutParams(0,-2,1f))
            column.addView(row)
        }
        column.addView(label("Choosing an already-used shortcut swaps its two slots. Agent / future translation is an unavailable placeholder unless a real supported agent route is admitted. This screen never admits a route, sends a query or enables translation.",14f))
        column.addView(button("Save toolbar"){persist(draft)})
        column.addView(button("Reset toolbar default"){persist(ToolbarPreferences())})
        column.addView(button("Reload confirmed toolbar"){baseline=store.snapshot();draft=baseline.value;render("Confirmed toolbar loaded. Unsaved choices were discarded.")})
        scroll.addView(column);setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener{view,insets->val safe=insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout());view.setPadding(safe.left,safe.top,safe.right,safe.bottom);insets}
    }
    private fun choose(index:Int){
        val token=screen;val choices=ToolbarAction.entries.filter{it.configurable}
        AlertDialog.Builder(this).setTitle("Slot ${index+1}").setItems(choices.map{it.label}.toTypedArray()){_,at->
            if(screen===token&&!isFinishing&&!isDestroyed){draft=ToolbarActionRegistry.replace(draft,index,choices[at]);render()}
        }.setNegativeButton("Cancel",null).show()
    }
}
/** Native vector icons only; preview and consumer share this mapping. */
internal object ToolbarIcons {
    fun icon(action:ToolbarAction)=when(action){
        ToolbarAction.TOOLS->KeyIcon.MENU;ToolbarAction.AGENT->KeyIcon.TRANSLATE;ToolbarAction.EMOJI->KeyIcon.FACE
        ToolbarAction.GIF->KeyIcon.GIF;ToolbarAction.CLIPBOARD->KeyIcon.CLIPBOARD;ToolbarAction.UNDO->KeyIcon.UNDO
        ToolbarAction.TEXT_EDITING->KeyIcon.TEXT_EDITING;ToolbarAction.STICKERS->KeyIcon.STICKERS;ToolbarAction.MICROPHONE->KeyIcon.MIC
    }
}
