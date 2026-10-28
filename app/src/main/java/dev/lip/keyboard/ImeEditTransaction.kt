package dev.lip.keyboard

import java.util.ArrayDeque

/** One bounded surrounding-text read, absolute UTF16 offsets. Never a whole document. */
class ImeEditWindow(val offset:Int,val text:String,val start:Int,val end:Int,val directionVerified:Boolean=true,val selectionStamp:Long?=null) {
    val valid get()=offset>=0&&text.length<=ImeEditTransaction.MAX_EDIT+64&&offset<=Int.MAX_VALUE-text.length&&start>=offset&&end>=offset&&start<=offset+text.length&&end<=offset+text.length
    fun slice(from:Int,to:Int):String?=if(valid&&from>=offset&&to>=from&&to<=offset+text.length)text.substring(from-offset,to-offset)else null
    override fun toString()="OwnedEditWindow(length=${text.length})"
}

/** SurroundingText normalizes reverse selections on Android. Direction can be
 * supplied ONLY by a stable framework observation with corresponding fresh bounds.
 * Commanded/requested coordinates are never an observation.
 */
object ImeEditSelection {
    fun observed(window:ImeEditWindow,start:Int,end:Int,stable:Boolean,stamp:Long=0):ImeEditWindow {
        if(window.start==window.end)return window
        val same=stable&&minOf(start,end)==minOf(window.start,window.end)&&maxOf(start,end)==maxOf(window.start,window.end)
        return ImeEditWindow(window.offset,window.text,if(same)start else window.start,if(same)end else window.end,same,if(same)stamp else null)
    }
}

/** Main-thread coordinator. Port methods may reenter; all authority is published
 * before IPC and rechecked afterward. Booleans/selection events are not content ACKs.
 */
class ImeEditTransaction(private val journal:ImeEditJournal,private val clock:()->Long) {
    interface Port {
        fun eligible():Boolean
        fun read(before:Int,after:Int):ImeEditWindow?
        fun select(start:Int,end:Int):Boolean
        fun selectionStamp():Long=0
        /** Publish the real framework baseline immediately before selection IPC. */
        fun selectObserved(start:Int,end:Int,beforeDispatch:(Long)->Boolean):Boolean = beforeDispatch(selectionStamp())&&select(start,end)
        fun replace(text:String):Boolean
    }
    enum class Notice { READY, UNCONFIRMED, UNSUPPORTED, RESOURCE, CONTEXT }
    var notice=Notice.READY;private set
    private var generation=0L
    private var context:Any?=null
    private var pending:Pair<Int,Int>?=null
    private var ownedComposition:Pair<Int,Int>?=null
    private class Head(val edit:ImeEditJournal.Edit,val after:Boolean,val selected:Boolean=false,val collapsed:Boolean=false)
    private var confirmedHead:Head?=null
    private var pendingHead:Head?=null
    private var navigationHead:Head?=null
    private var observingSelection=false
    private class Restore(val replay:ImeEditJournal.Replay,val edit:ImeEditJournal.Edit,val generation:Long,val deadline:Long,var baseline:Long?=null)
    private var restoring:Restore?=null
    private var completedReplay:ImeEditJournal.Replay?=null
    private val selectionDebt=ArrayDeque<Pair<Int,Int>>()
    val busy get()=pending!=null||journal.isReplaying
    fun bind(value:Any) { if(context!==value){context=value;generation++;pending=null;ownedComposition=null;confirmedHead=null;pendingHead=null;navigationHead=null;restoring=null;completedReplay=null;selectionDebt.clear();journal.reset();notice=Notice.READY} }
    fun barrier(reason:Notice=Notice.CONTEXT) { generation++;pending=null;ownedComposition=null;confirmedHead=null;pendingHead=null;navigationHead=null;restoring=null;completedReplay=null;selectionDebt.clear();journal.clear();notice=when{journal.resourceUnavailable->Notice.RESOURCE;reason==Notice.CONTEXT&&notice!=Notice.READY->notice;else->reason} }
    fun expectSelection(start:Int,end:Int,origin:ImeEditWindow):Boolean {
        if(!origin.valid||start<origin.offset||end<start||end>origin.offset+origin.text.length)return false
        val body=origin.slice(start,end)?:return false
        val left=origin.slice(maxOf(origin.offset,start-32),start)?:return false
        val right=origin.slice(end,minOf(origin.offset+origin.text.length,end+32))?:return false
        val edit=ImeEditJournal.Edit(start,body,body,left,right,ImeEditJournal.Kind.DELETE,beforeStart=start,beforeEnd=end)
        navigationHead=Head(edit,false) // Exact requested selection/content, published BEFORE IPC.
        selectionDebt.addLast(start to end)
        return true
    }
    fun expectComposition(start:Int,end:Int){ownedComposition=start to end}
    fun composition(start:Int,end:Int) {
        if(start<0&&end<0){ownedComposition=null;return}
        if(ownedComposition!=(start to end))barrier()
    }
    fun selection(start:Int,end:Int,port:Port?=null) {
        val value=start to end
        if(!selectionDebt.contains(value)&&pending!=value){barrier();return}
        // Coordinate debt is NOT provenance. Observe the actual current head:
        // a delayed own ACK(1) with actual caret(2) differs from a real departure
        // to caret(1). Neither equality nor a return to old coordinates revives it.
        if(port==null||observingSelection){barrier();return}
        val expected=generation
        observingSelection=true
        var observedHead:Head?=null
        var observedWindow:ImeEditWindow?=null
        val valid=try {
            val restore=restoring
            // A queued ACK for an earlier owned phase can arrive while the
            // actual editor already holds the final reverse selection. Its
            // normalized bounds/content can justify waiting, NEVER direction
            // completion or another editor dispatch.
            val waiting=if(restore!=null&&value!=(restore.edit.beforeStart to restore.edit.beforeEnd)&&clock()<restore.deadline){
                readEdit(port,restore.edit,false,expected)?.let{unorderedRestored(it,restore.edit)}==true
            }else false
            val observed=if(waiting)null else listOfNotNull(pendingHead,navigationHead,confirmedHead).firstOrNull { head ->
                val window=readEdit(port,head.edit,head.after,expected,head.selected,head.collapsed)
                val matches=window!=null&&matches(window,head.edit,head.after,head.selected,head.collapsed)
                if(matches)observedWindow=window
                matches
            }
            if(observed!=null&&observed===navigationHead)confirmedHead=observed
            observedHead=observed
            waiting||observed!=null
        }catch(_:OutOfMemoryError){journal.resourceFailure();false}catch(_:RuntimeException){false}finally{observingSelection=false}
        if(!valid||expected!=generation){barrier();return}
        if(selectionDebt.contains(value))while(selectionDebt.isNotEmpty()){if(selectionDebt.removeFirst()==value)break}
        val restore=restoring
        if(restore!=null&&restore.generation==generation&&clock()<restore.deadline&&observedHead===pendingHead&&
            start==restore.edit.beforeStart&&end==restore.edit.beforeEnd&&freshDirection(observedWindow,restore))completeReplay(restore.replay,restore.edit,false)
    }
    private fun freshDirection(window:ImeEditWindow?,restore:Restore):Boolean {
        val baseline=restore.baseline?:return false
        val observed=window?.selectionStamp?:return false
        return window.directionVerified&&observed>baseline&&clock()<restore.deadline
    }
    fun expirePendingReplay(){if(restoring?.let{clock()>=it.deadline}==true)barrier(Notice.UNCONFIRMED)}
    private fun completeReplay(replay:ImeEditJournal.Replay,edit:ImeEditJournal.Edit,after:Boolean){
        journal.complete(replay);restoring=null;pending=null;pendingHead=null;confirmedHead=Head(edit,after);completedReplay=replay;notice=Notice.READY
    }
    fun expireSelectionAcknowledgements(){if(!busy)selectionDebt.clear()}
    private fun read(port:Port,before:Int,after:Int,expected:Long):ImeEditWindow? {
        if(!port.eligible()||expected!=generation)return null
        val began=clock()
        val value=port.read(before,after)
        val eligible=port.eligible() // External code may reenter; resample generation/time AFTER it.
        val ended=clock()
        return value?.takeIf{eligible&&expected==generation&&it.valid&&ended>=began&&ended-began<250}
    }
    private fun matches(window:ImeEditWindow,edit:ImeEditJournal.Edit,after:Boolean,selected:Boolean=false,collapsed:Boolean=false):Boolean {
        val body=if(after)edit.inserted else edit.removed
        val end=edit.at+body.length
        val caret=if(after)edit.afterCaret else edit.beforeCaret
        val expectedStart=if(selected)edit.at else if(after||collapsed)caret else edit.beforeStart
        val expectedEnd=if(selected)end else if(after||collapsed)caret else edit.beforeEnd
        // Intermediate replacement selection authorizes an exact text span,
        // not its direction. A normalized host range plus stale reverse framework
        // metadata must not block Redo. Final original selection still requires
        // independently observed exact direction below.
        val selectionMatches=if(selected)minOf(window.start,window.end)==edit.at&&maxOf(window.start,window.end)==end
            else (expectedStart==expectedEnd||window.directionVerified)&&window.start==expectedStart&&window.end==expectedEnd
        return selectionMatches&&window.slice(edit.at,end)==body&&window.slice(edit.at-edit.left.length,edit.at)==edit.left&&window.slice(end,end+edit.right.length)==edit.right
    }
    private fun unorderedRestored(window:ImeEditWindow,edit:ImeEditJournal.Edit):Boolean {
        val end=edit.at+edit.removed.length
        return minOf(window.start,window.end)==minOf(edit.beforeStart,edit.beforeEnd)&&
            maxOf(window.start,window.end)==maxOf(edit.beforeStart,edit.beforeEnd)&&
            window.slice(edit.at,end)==edit.removed&&window.slice(edit.at-edit.left.length,edit.at)==edit.left&&
            window.slice(end,end+edit.right.length)==edit.right
    }
    private fun readEdit(port:Port,edit:ImeEditJournal.Edit,after:Boolean,expected:Long,selected:Boolean=false,collapsed:Boolean=false):ImeEditWindow? {
        val length=if(after)edit.inserted.length else edit.removed.length
        val caret=edit.at+length
        val start=if(selected)edit.at else if(after||collapsed)caret else edit.beforeStart
        val end=if(selected)caret else if(after||collapsed)caret else edit.beforeEnd
        // Selected text is already included by SurroundingText. Request only
        // missing span/context, rather than reading its size again on each side.
        val before=maxOf(32,minOf(start,end)-edit.at+32)
        val following=maxOf(32,caret-maxOf(start,end)+32)
        if(before>MAX_EDIT+32||following>MAX_EDIT+32)return null
        return read(port,before,following,expected)
    }
    private fun prepare(port:Port,inserted:String,kind:ImeEditJournal.Kind,removeBefore:Int,range:IntRange?,originalSelection:Pair<Int,Int>?,expected:Long):ImeEditJournal.Edit? {
        if(journal.resourceUnavailable||inserted.length>MAX_EDIT||removeBefore !in 0..MAX_EDIT)return null
        if(range!=null&&(range.first<0||range.last<range.first||range.last-range.first>=MAX_EDIT))return null
        val required=maxOf(32,removeBefore+32,range?.let{it.last-it.first+33}?:32)
        if(required>MAX_EDIT+32)return null
        val window=read(port,required,32,expected)?:return null
        if(window.start!=window.end&&!window.directionVerified)return null
        val at=range?.first?:if(window.start==window.end)window.start-removeBefore else minOf(window.start,window.end)
        val end=range?.let{it.last+1}?:maxOf(window.start,window.end)
        if(at<0||end<at||end-at>MAX_EDIT||at>Int.MAX_VALUE-inserted.length)return null
        val removed=window.slice(at,end)?:return null
        val left=window.slice(maxOf(window.offset,at-32),at)?:return null
        val right=window.slice(end,minOf(window.offset+window.text.length,end+32))?:return null
        val original=originalSelection?: (window.start to window.end)
        if(original.first !in window.offset..window.offset+window.text.length||original.second !in window.offset..window.offset+window.text.length)return null
        return ImeEditJournal.Edit(at,removed,inserted,left,right,kind,
            kind==ImeEditJournal.Kind.TYPING&&inserted.lastOrNull()?.let{it.isLetterOrDigit()||it=='\''}==true,original.first,original.second)
    }
    /** Manual action dispatches exactly once even when its journal snapshot is
     * unsupported. Ordered post-read confirms the delta; no delayed key queues.
     */
    fun edit(port:Port,inserted:String,kind:ImeEditJournal.Kind,removeBefore:Int=0,range:IntRange?=null,originalSelection:Pair<Int,Int>?=null,dispatch:()->Boolean):Boolean {
        if(busy)barrier(Notice.UNCONFIRMED) // A reentrant manual action revokes, never joins an in-flight inverse.
        val expected=generation
        var edit=try{prepare(port,inserted,kind,removeBefore,range,originalSelection,expected)}catch(_:OutOfMemoryError){journal.resourceFailure();notice=Notice.RESOURCE;null}catch(_:RuntimeException){null}
        if(edit==null)barrier(if(journal.resourceUnavailable)Notice.RESOURCE else Notice.UNSUPPORTED)
        else try {
            pending=edit.afterCaret to edit.afterCaret
            pendingHead=Head(edit,true)
            selectionDebt.addLast(checkNotNull(pending))
        }catch(_:OutOfMemoryError){journal.resourceFailure();barrier(Notice.RESOURCE);edit=null}
        val dispatchGeneration=generation
        val accepted=try{dispatch()}catch(_:RuntimeException){false}
        if(!accepted){barrier(Notice.UNCONFIRMED);return false}
        if(edit==null)return true
        if(dispatchGeneration!=generation||expected!=generation){if(notice!=Notice.RESOURCE)notice=Notice.UNCONFIRMED;return true}
        try {
            val after=read(port,minOf(MAX_EDIT+32,edit.inserted.length+32),32,expected)
            pending=null;pendingHead=null
            if(after!=null&&matches(after,edit,true)){journal.confirm(edit);confirmedHead=Head(edit,true);navigationHead=null;notice=Notice.READY}
            else barrier(Notice.UNCONFIRMED)
        }catch(_:OutOfMemoryError){journal.resourceFailure();barrier(Notice.RESOURCE)}catch(_:RuntimeException){barrier(Notice.UNCONFIRMED)}
        return true
    }
    fun replay(port:Port,direction:ImeEditJournal.Direction):Boolean {
        if(busy||journal.resourceUnavailable)return false
        val expected=generation
        completedReplay=null
        try {
            val replay=journal.take(direction)?:return false
            val edit=replay.edit;val undo=direction==ImeEditJournal.Direction.UNDO
            val current=if(undo)edit.inserted else edit.removed
            val replacement=if(undo)edit.removed else edit.inserted
            val before=readEdit(port,edit,undo,expected)
            if(before==null||!matches(before,edit,undo)){barrier();return false}
            val end=edit.at+current.length
            pending=edit.at to end // Published BEFORE selection IPC/reentrant callback.
            pendingHead=Head(edit,undo,selected=true)
            selectionDebt.addLast(checkNotNull(pending))
            if(!port.eligible()||expected!=generation||!port.select(edit.at,end)){barrier(Notice.UNCONFIRMED);return false}
            val selected=readEdit(port,edit,undo,expected,selected=true)
            if(selected==null||!matches(selected,edit,undo,true)){barrier(Notice.UNCONFIRMED);return false}
            val caret=edit.at+replacement.length
            pending=caret to caret;pendingHead=Head(edit,!undo,collapsed=true);selectionDebt.addLast(checkNotNull(pending))
            if(!port.eligible()||expected!=generation||!port.replace(replacement)){barrier(Notice.UNCONFIRMED);return false}
            val after=readEdit(port,edit,!undo,expected,collapsed=true)
            pending=null;pendingHead=null
            if(after==null||!matches(after,edit,!undo,collapsed=true)){barrier(Notice.UNCONFIRMED);return false}
            if(undo&&(edit.beforeStart!=edit.beforeCaret||edit.beforeEnd!=edit.beforeCaret)){
                pending=edit.beforeStart to edit.beforeEnd;pendingHead=Head(edit,false);selectionDebt.addLast(checkNotNull(pending))
                val restore=Restore(replay,edit,expected,clock()+250)
                restoring=restore // BEFORE potentially synchronous framework ACK.
                val accepted=port.eligible()&&expected==generation&&port.selectObserved(edit.beforeStart,edit.beforeEnd){baseline->
                    if(expected!=generation||clock()>=restore.deadline)false
                    else {restore.baseline=baseline;true}
                }
                if(!accepted||clock()>=restore.deadline){barrier(Notice.UNCONFIRMED);return false}
                if(restoring==null)return completedReplay===replay&&expected==generation
                val restored=readEdit(port,edit,false,expected)
                if(restored!=null&&matches(restored,edit,false)&&freshDirection(restored,restore)){completeReplay(replay,edit,false);return true}
                // Normalized range/text is not a directional ACK. Keep ownership
                // pending for the actual framework selection callback, bounded by
                // the deadline. No repeated setSelection/write is permitted.
                if(restored!=null&&clock()<restore.deadline&&unorderedRestored(restored,edit))return false
                barrier(Notice.UNCONFIRMED);return false
            }
            completeReplay(replay,edit,!undo);return true
        }catch(_:OutOfMemoryError){journal.resourceFailure();barrier(Notice.RESOURCE);return false}catch(_:RuntimeException){barrier(Notice.UNCONFIRMED);return false}
    }
    companion object { const val MAX_EDIT=65_536 }
}
