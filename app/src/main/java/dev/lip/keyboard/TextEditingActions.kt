package dev.lip.keyboard

/** Manual host commands only. No clipboard/content reader or guessed UTF16 move. */
enum class TextEditingAction(val label:String,val description:String) {
    SELECT_ALL("Select all","Select all text in current editor"),
    CUT("Cut","Cut selected text using current editor"),
    COPY("Copy","Copy selected text using current editor"),
    PASTE("Paste","Paste system clipboard using current editor"),
    LEFT("←","Move cursor left"), UP("↑","Move cursor up"), RIGHT("→","Move cursor right"), DOWN("↓","Move cursor down"),
    SELECT_MODE("Select","Select text with arrow keys"),
    LINE_START("Line start","Move to line start"), LINE_END("Line end","Move to line end"),
    TEXT_START("Text start","Move to text start"), TEXT_END("Text end","Move to text end")
}
data class TextEditingState(val inputType:Int,val imeOptions:Int,val start:Int,val end:Int,
    val connected:Boolean,val visible:Boolean,val busy:Boolean,val composing:Boolean,val interactive:Boolean,val unlocked:Boolean)
sealed interface TextEditingCommand {
    data class Menu(val id:Int):TextEditingCommand
    data class Key(val code:Int,val modifiers:Int):TextEditingCommand
}
object TextEditingActions {
    fun allowed(state:TextEditingState):Boolean = state.connected&&state.visible&&!state.busy&&!state.composing&&state.interactive&&state.unlocked&&
        state.inputType and 0xf in 1..4&&!ImeFieldPolicy.forInput(state.inputType,state.imeOptions).protected&&
        state.imeOptions and 0x1000000==0&&state.start>=0&&state.end>=0
    fun available(action:TextEditingAction,state:TextEditingState)=allowed(state)&&
        (action !in setOf(TextEditingAction.CUT,TextEditingAction.COPY)||state.start!=state.end)
    fun command(action:TextEditingAction,selecting:Boolean):TextEditingCommand? {
        val shift=if(selecting)1 else 0 // Marks a bounded native Shift chord, not metadata-only selection.
        return when(action){
            TextEditingAction.SELECT_ALL->TextEditingCommand.Menu(16908319) // android.R.id.selectAll
            TextEditingAction.CUT->TextEditingCommand.Menu(16908320)
            TextEditingAction.COPY->TextEditingCommand.Menu(16908321)
            TextEditingAction.PASTE->TextEditingCommand.Menu(16908322)
            TextEditingAction.LEFT->TextEditingCommand.Key(21,shift)
            TextEditingAction.RIGHT->TextEditingCommand.Key(22,shift)
            TextEditingAction.UP->TextEditingCommand.Key(19,shift)
            TextEditingAction.DOWN->TextEditingCommand.Key(20,shift)
            TextEditingAction.LINE_START->TextEditingCommand.Key(122,shift)
            TextEditingAction.LINE_END->TextEditingCommand.Key(123,shift)
            TextEditingAction.TEXT_START->TextEditingCommand.Key(122,shift or 0x1000) // Ctrl+Home
            TextEditingAction.TEXT_END->TextEditingCommand.Key(123,shift or 0x1000)
            TextEditingAction.SELECT_MODE->null // Local desired mode; never a host command by itself.
        }
    }
}
enum class TextEditingOutcome { DISPATCHED, UNCONFIRMED, REFUSED }
/** A captured port is bound to the OLD connection. UP is cleanup, not a second
 * action, and must still use that port if DOWN throws/reenters/changes focus.
 */
object TextEditingDispatch {
    interface Port {
        fun menu(id:Int):Boolean
        fun key(down:Boolean,code:Int,modifiers:Int):Boolean
        fun chordedModifiers():Boolean=false // Actual virtual-device capability; unknown fails closed.
        fun continueKeys():Boolean=true // Fresh post-modifier context, never used to gate cleanup UP.
    }
    fun execute(command:TextEditingCommand,port:Port,admit:()->Boolean={true}):TextEditingOutcome {
        val shift=command is TextEditingCommand.Key&&command.modifiers and 1!=0
        // Query external platform capability BEFORE the final fresh admission.
        if(shift&&!runCatching{port.chordedModifiers()}.getOrDefault(false))return TextEditingOutcome.REFUSED
        if(!runCatching(admit).getOrDefault(false))return TextEditingOutcome.REFUSED
        return when(command){
            is TextEditingCommand.Menu->if(runCatching{port.menu(command.id)}.getOrDefault(false))TextEditingOutcome.DISPATCHED else TextEditingOutcome.UNCONFIRMED
            is TextEditingCommand.Key->{
                if(!shift)keyPair(command,port)
                else {
                    var modifierDown=false;var modifierUp=false;var movement=TextEditingOutcome.UNCONFIRMED
                    try{
                        modifierDown=port.key(true,59,1) // KEYCODE_SHIFT_LEFT / META_SHIFT_ON.
                        if(modifierDown&&runCatching{port.continueKeys()}.getOrDefault(false))movement=keyPair(command,port)
                    }catch(_:Throwable){}
                    finally{try{modifierUp=port.key(false,59,0)}catch(_:Throwable){}}
                    if(modifierDown&&modifierUp&&movement==TextEditingOutcome.DISPATCHED)TextEditingOutcome.DISPATCHED else TextEditingOutcome.UNCONFIRMED
                }
            }
        }
    }
    private fun keyPair(command:TextEditingCommand.Key,port:Port):TextEditingOutcome {
        var down=false;var up=false
        try{down=port.key(true,command.code,command.modifiers)}catch(_:Throwable){}
        finally{try{up=port.key(false,command.code,command.modifiers)}catch(_:Throwable){}}
        return if(down&&up)TextEditingOutcome.DISPATCHED else TextEditingOutcome.UNCONFIRMED
    }

}
