package dev.lip.keyboard

import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/** All metadata must come from the current visible IME context, never an imported pack. */
internal data class ProductionStickerSafety(val inputType:Int,val imeOptions:Int,val visible:Boolean,
    val interactive:Boolean,val unlocked:Boolean,val composing:Boolean,val busy:Boolean) {
    val eligible:Boolean get()=visible&&interactive&&unlocked&&!composing&&!busy&&ImeFieldPolicy.forInput(inputType,imeOptions).voice&&imeOptions and 0x1000000==0
}

/** Observed package incarnation, not a trust assertion or an editor binding by itself. */
internal class ProductionStickerReceiverFacts(val packageName:String,val uid:Int,signers:Set<String>,
    val firstInstallTime:Long,val lastUpdateTime:Long,val version:Long) {
    val signers:Set<String> = Collections.unmodifiableSet(signers.toSortedSet())
    override fun equals(other:Any?):Boolean=other is ProductionStickerReceiverFacts&&packageName==other.packageName&&uid==other.uid&&
        signers==other.signers&&firstInstallTime==other.firstInstallTime&&lastUpdateTime==other.lastUpdateTime&&version==other.version
    override fun hashCode()=listOf(packageName,uid,signers,firstInstallTime,lastUpdateTime,version).hashCode()
    override fun toString()="ProductionStickerReceiverFacts(redacted)"
}
internal object ProductionStickerReceiverPolicy {
    fun admit(editorPackage:String?,bindingUid:Int,ownUid:Int,applicationUid:Int,uidPackages:List<String>?,sharedUserId:String?,
        signers:Set<String>?,firstInstallTime:Long,lastUpdateTime:Long,version:Long,enabled:Boolean,suspended:Boolean):ProductionStickerReceiverFacts? {
        if(editorPackage==null||editorPackage.length>255||!Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+").matches(editorPackage)||
            bindingUid<=0||bindingUid==ownUid||applicationUid!=bindingUid||uidPackages!=listOf(editorPackage)||sharedUserId!=null||!enabled||suspended||
            firstInstallTime<=0||lastUpdateTime<firstInstallTime||version<0||signers.isNullOrEmpty()||signers.size>16||signers.any{!Regex("[a-f0-9]{64}").matches(it)})return null
        return ProductionStickerReceiverFacts(editorPackage,bindingUid,signers,firstInstallTime,lastUpdateTime,version)
    }
}

/** A canceled reader/IPC still owns capacity until the actual callback returns. */
internal class ProductionStickerOperationAdmission {
    class Ticket internal constructor(){private val live=AtomicBoolean(true);fun cancel(){live.set(false)};fun isLive()=live.get()}
    private var active:Ticket?=null
    @Synchronized fun begin():Ticket?=if(active!=null)null else Ticket().also{active=it}
    @Synchronized fun finish(ticket:Ticket):Boolean=if(active!==ticket)false else{ticket.cancel();active=null;true}
    @Synchronized fun busy()=active!=null
}

/** Retiring during an external grant cannot finish before that grant returns.
 * Only the return value admits the separate once-only OS revocation callback.
 */
internal class ProductionStickerGrantPublication {
    private var finished=false
    private var retired=false
    @Synchronized fun requestRetirement():Boolean { retired=true;return finished }
    @Synchronized fun finishGrant():Boolean { finished=true;return retired }
    @Synchronized fun retirementRequested():Boolean=retired
}

/** CPU-only exact contact snapshot comparison. Identity-bearing values cannot be
 * repaired from equal text after an editor/window/library incarnation changed.
 */
internal class ProductionStickerContextStamp(val connection:Any,val cursor:ImeCursor,val window:Any,
    val receiver:ProductionStickerReceiverFacts,mimeTypes:Array<String>?,val safety:ProductionStickerSafety,val libraryEpoch:Any) {
    private val types=StickerHandoffMime.snapshot(mimeTypes)
    fun matches(other:ProductionStickerContextStamp):Boolean=safety.eligible&&other.safety.eligible&&
        connection===other.connection&&cursor==other.cursor&&window===other.window&&receiver==other.receiver&&
        types!=null&&types==other.types&&safety==other.safety&&libraryEpoch===other.libraryEpoch
    override fun toString()="ProductionStickerContextStamp(redacted)"
}

/** Constructed before starting the pipe thread (inside open's cleanup scope).
 * Fallible work, including its initial allocation/clock read, is always covered
 * by the actual close attempt. Callback failure is sticky quarantine, not drain.
 */
internal class ProductionStickerWriterTask(private val work:()->Boolean,private val close:(Boolean)->Unit,
    private val closeFailed:()->Unit):Runnable {
    private val started=AtomicBoolean(false)
    override fun run() {
        if(!started.compareAndSet(false,true))return
        var successful=false
        try{successful=work()}catch(_:Throwable){}finally{
            try{close(successful)}catch(_:Throwable){closeFailed()}
        }
    }
}
