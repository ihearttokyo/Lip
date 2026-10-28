package dev.lip.keyboard

import java.util.ArrayDeque

/** Lip-owned text deltas only. RAM-only; no entry-count cap, persistence, global
 * keyboard capture or host-undo fallback. Available process memory is finite.
 */
class ImeEditJournal {
    enum class Kind { TYPING, DELETE, SWIPE, DICTATION, EMOJI, CLIP, NEXT_WORD, REPLACEMENT, NEWLINE }
    class Edit(val at:Int,val removed:String,val inserted:String,val left:String,val right:String,val kind:Kind,val typingOpen:Boolean=false,val beforeStart:Int=at+removed.length,val beforeEnd:Int=at+removed.length) {
        val beforeCaret get()=at+removed.length
        val afterCaret get()=at+inserted.length
        override fun toString()="OwnedEdit(kind=$kind, removed=${removed.length}, inserted=${inserted.length})"
    }
    enum class Direction { UNDO, REDO }
    class Replay internal constructor(val edit:Edit,val direction:Direction)
    private val undo=ArrayDeque<Edit>()
    private val redo=ArrayDeque<Edit>()
    private var replay:Replay?=null
    var resourceUnavailable=false;private set
    val isReplaying get()=replay!=null
    val canUndo get()=!resourceUnavailable&&replay==null&&undo.isNotEmpty()
    val canRedo get()=!resourceUnavailable&&replay==null&&redo.isNotEmpty()
    val undoCount get()=undo.size
    val redoCount get()=redo.size
    fun clear(){undo.clear();redo.clear();replay=null}
    fun reset(){clear();resourceUnavailable=false}
    fun resourceFailure(){clear();resourceUnavailable=true}
    fun confirm(edit:Edit) {
        check(!resourceUnavailable&&replay==null)
        redo.clear()
        val prior=undo.peekLast()
        val merged=if(prior!=null)coalesce(prior,edit) else null
        if(merged!=null){undo.removeLast();undo.addLast(merged)}else undo.addLast(edit)
    }
    private fun coalesce(a:Edit,b:Edit):Edit? {
        if(a.kind==Kind.TYPING&&b.kind==Kind.TYPING&&a.typingOpen&&a.removed.isEmpty()&&b.removed.isEmpty()&&
            a.inserted.length<=ImeEditTransaction.MAX_EDIT-b.inserted.length&&a.afterCaret==b.at&&a.right==b.right&&(a.left+a.inserted).takeLast(32)==b.left) {
            // A boundary closes the group. No keystroke queue or key dropping.
            return Edit(a.at,"",a.inserted+b.inserted,a.left,b.right,Kind.TYPING,b.typingOpen,a.beforeStart,a.beforeEnd)
        }
        if(a.kind==Kind.DELETE&&b.kind==Kind.DELETE&&a.inserted.isEmpty()&&b.inserted.isEmpty()&&
            a.beforeStart==a.beforeEnd&&b.beforeStart==b.beforeEnd&&a.removed.length<=ImeEditTransaction.MAX_EDIT-b.removed.length&&b.beforeCaret==a.at&&a.right==b.right)
            return Edit(b.at,b.removed+a.removed,"",b.left,a.right,Kind.DELETE,beforeStart=a.beforeStart,beforeEnd=a.beforeEnd)
        return null
    }
    fun take(direction:Direction):Replay? {
        if(resourceUnavailable||replay!=null)return null
        val stack=if(direction==Direction.UNDO)undo else redo
        if(stack.isEmpty())return null
        return Replay(stack.removeLast(),direction).also{replay=it} // Consume BEFORE any editor IPC.
    }
    fun complete(value:Replay) {
        check(replay===value)
        (if(value.direction==Direction.UNDO)redo else undo).addLast(value.edit)
        replay=null
    }
}
