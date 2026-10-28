package dev.lip.keyboard

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.view.inputmethod.InputConnection
import java.security.MessageDigest

/** Constructor is private: caller supplies the actual current InputBinding.uid to capture(). */
internal class ProductionStickerReceiver private constructor(internal val facts:ProductionStickerReceiverFacts) {
    internal val binding get()=StickerReceiverBinding(facts.packageName,facts.uid)
    internal fun isCurrent(context:Context)=capture(context,facts.packageName,facts.uid)?.facts==facts
    override fun equals(other:Any?)=other is ProductionStickerReceiver&&facts==other.facts
    override fun hashCode()=facts.hashCode()
    override fun toString()="ProductionStickerReceiver(redacted)"
    companion object {
        fun capture(context:Context,editorPackage:String?,actualInputBindingUid:Int):ProductionStickerReceiver? {
            val identity=android.os.Binder.clearCallingIdentity()
            return try{
            if(editorPackage==null)return null
            val pm=context.packageManager
            val info=pm.getPackageInfo(editorPackage,PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            val app=info.applicationInfo?:return null
            val signatures=info.signingInfo?.apkContentsSigners?:return null
            if(signatures.size !in 1..16)return null
            val signers=signatures.map{signature->
                val bytes=signature.toByteArray();if(bytes.size !in 1..65_536)return null
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
            }.toSet()
            val facts=ProductionStickerReceiverPolicy.admit(editorPackage,actualInputBindingUid,android.os.Process.myUid(),app.uid,
                pm.getPackagesForUid(actualInputBindingUid)?.toList(),info.sharedUserId,signers,info.firstInstallTime,info.lastUpdateTime,
                info.longVersionCode,app.enabled,app.flags and ApplicationInfo.FLAG_SUSPENDED!=0)?:return null
            ProductionStickerReceiver(facts)
        }catch(_:Exception){null}finally{android.os.Binder.restoreCallingIdentity(identity)}
        }
    }
}

/** Immutable in-memory snapshot; service gates safety BEFORE reading the bounded cursor
 * and rechecks binding/connection/session/window/safety after that external read.
 */
internal class ProductionStickerAttachContext(val connection:InputConnection,val cursor:ImeCursor,val window:Any,
    val receiver:ProductionStickerReceiver,mimeTypes:Array<String>?,val safety:ProductionStickerSafety,val libraryEpoch:Any) {
    private val stamp=ProductionStickerContextStamp(connection,cursor,window,receiver.facts,mimeTypes,safety,libraryEpoch)
    private val types=mimeTypes?.copyOf()
    fun mimeTypes()=types?.copyOf()
    fun matches(other:ProductionStickerAttachContext)=stamp.matches(other.stamp)
    override fun toString()="ProductionStickerAttachContext(redacted)"
}
