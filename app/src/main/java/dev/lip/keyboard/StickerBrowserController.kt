package dev.lip.keyboard

import android.content.Context
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import dev.lip.stickers.StickerPackTransactionStore
import dev.lip.stickers.StickerPacksActivity
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Real manifest/component gate; DEBUG alone must never expose later-major controls. */
internal object StickerBrowserFeature {
    fun enabled(context:Context):Boolean=runCatching{
        context.packageManager.getActivityInfo(ComponentName(context,StickerPacksActivity::class.java),
            PackageManager.ComponentInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong())).enabled
    }.getOrDefault(false)
}

internal sealed class StickerBrowserPage(val offset:Int) {
    class Library(offset:Int=0):StickerBrowserPage(offset)
    class Pack(val commitId:String,offset:Int=0):StickerBrowserPage(offset) {
        init{require(UUID.fromString(commitId).toString()==commitId)}
    }
    init{require(offset in 0..120&&offset%8==0)}
    fun previous():StickerBrowserPage?=if(offset==0)null else at(offset-8)
    fun next()=at(offset+8)
    private fun at(offset:Int)=when(this){is Library->Library(offset);is Pack->Pack(commitId,offset)}
}

/** Read-only adapter over the validated private store. No encoded decoder runs in the IME.
 * Store validation transiently reads a bounded whole pack (<=16MiB archive, <=32MiB RGBA).
 * Only <=8 copied previews survive into the UI result. No new store files are created here.
 */
internal class StickerBrowserController(context:Context):AutoCloseable {
    private val app=context.applicationContext
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor{r->Thread(r,"lip-sticker-browser").apply{isDaemon=true}}
    private var request:StickerBrowserAdmission.Ticket?=null
    private var closed=false
    fun cancel(){check(Looper.myLooper()==Looper.getMainLooper());request?.cancel();request=null}
    fun load(page:StickerBrowserPage,owner:()->Boolean,deliver:(StickerPanelState)->Unit){
        check(Looper.myLooper()==Looper.getMainLooper());cancel()
        if(closed||!owner())return
        if(!StickerBrowserFeature.enabled(app)){deliver(StickerPanelState.Error("Local sticker browser is unavailable."));return}
        val ticket=admission.begin()
        if(ticket==null){deliver(StickerPanelState.Error("A previous local read is still draining. Return to Packs to retry."));return}
        request=ticket;deliver(StickerPanelState.Loading)
        try{worker.execute{
            val result=try{read(page){ticket.isLive()}}catch(_:OutOfMemoryError){
                StickerPanelState.Error("Local preview memory is unavailable. Originals were preserved; no encoded-image fallback was used.")
            }catch(_:Exception){
                StickerPanelState.Error("Local pack could not be read. Originals were preserved. Return to Packs or Manage packs.")
            }finally{admission.finish(ticket)}
            main.post{
                if(!closed&&request===ticket&&ticket.isLive()&&owner()&&StickerBrowserFeature.enabled(app)){
                    request=null;deliver(result)
                }
            }
        }}catch(_:RejectedExecutionException){
            ticket.cancel();admission.finish(ticket);request=null
            if(!closed&&owner())deliver(StickerPanelState.Error("Local browser worker is unavailable."))
        }
    }
    private fun existingRoot():Path? {
        val parent=app.noBackupFilesDir.toPath().toRealPath()
        val root=parent.resolve("sticker-packs-v1")
        val attrs=try{Files.readAttributes(root,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)}
            catch(_:java.nio.file.NoSuchFileException){return null}
        check(attrs.isDirectory&&!attrs.isSymbolicLink&&root.toRealPath().parent==parent)
        return root
    }
    private fun read(page:StickerBrowserPage,live:()->Boolean):StickerPanelState {
        check(Looper.myLooper()!=Looper.getMainLooper()&&live())
        val root=existingRoot()?:return if(page is StickerBrowserPage.Library&&page.offset==0)
            StickerPanelState.Library(emptyList(),0,0) else StickerPanelState.Error("This local pack page is unavailable. Return to Packs.")
        val store=StickerPackTransactionStore(root)
        return when(page){
            is StickerBrowserPage.Library->{
                val entries=store.scan(live)
                check(live()&&(page.offset<entries.size||page.offset==0&&entries.isEmpty()))
                StickerPanelState.Library(entries.drop(page.offset).take(8).map{
                    StickerPanelPack(it.commitId,it.identity?.name?:"Unavailable local pack",it.available,
                        if(it.available)"Saved locally · rights UNVERIFIED" else "Stored data unavailable; originals preserved")
                },page.offset,entries.size)
            }
            is StickerBrowserPage.Pack->{
                val pack=store.read(page.commitId,live);check(live())
                val inspected=pack.inspected;val skip=if(inspected.trayImage==null)0 else 1
                val total=inspected.stickers.size;check(page.offset<total)
                val previews=inspected.stickers.drop(page.offset).take(8).mapIndexed{index,item->
                    check(live())
                    val pixels=pack.previews[skip+page.offset+index]
                    val asset=StickerAssetIdentity(pack.commitId,pack.archiveSha256,item.id,item.member.sha256,item.metadata.mime,item.member.size)
                    StickerPanelPreview(item.name,pixels.width,pixels.height,pixels.premultipliedRgbaSrgbBytes(),asset)
                }
                val source=inspected.provenance
                StickerPanelState.Pack(inspected.identity.name,
                    "${inspected.identity.author}\nSource assertion: ${source.sourceName}\nRights UNVERIFIED. Static local previews only.",
                    previews,page.offset,total)
            }
        }
    }
    override fun close(){check(Looper.myLooper()==Looper.getMainLooper());if(closed)return;closed=true;cancel();worker.shutdown()}
    companion object { private val admission=StickerBrowserAdmission() }
}
