package dev.lip.core

import dev.lip.stickers.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Synthetic storage semantics, not kernel NOREPLACE/Android codec/provider proof. */
abstract class StickerManagementChecks(private val newRoot:()->Path) {
    private fun hash(b:ByteArray)=MessageDigest.getInstance("SHA-256").digest(b).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun be(v:Int)=byteArrayOf((v ushr 24).toByte(),(v ushr 16).toByte(),(v ushr 8).toByte(),v.toByte())
    private fun chunk(type:String,data:ByteArray):ByteArray {
        val t=type.toByteArray();val crc=CRC32().apply{update(t);update(data)}.value
        return be(data.size)+t+data+be(crc.toInt())
    }
    private fun png(w:Int,h:Int,color:Int):ByteArray {
        val raw=ByteArrayOutputStream()
        repeat(h){raw.write(0);repeat(w){raw.write(byteArrayOf(color.toByte(),0,0,255.toByte()))}}
        val compressed=ByteArrayOutputStream().also{out->DeflaterOutputStream(out).use{it.write(raw.toByteArray())}}.toByteArray()
        return byteArrayOf(137.toByte(),80,78,71,13,10,26,10)+chunk("IHDR",be(w)+be(h)+byteArrayOf(8,6,0,0,0))+chunk("IDAT",compressed)+chunk("IEND",byteArrayOf())
    }
    private data class Fixture(val archive:ByteArray,val inspected:InspectedStickerPack,val previews:List<SandboxedStickerPreview>,val prepared:PreparedStickerPack)
    private fun fixture():Fixture {
        val tray=png(1,1,41);val first=png(2,1,91);val second=png(2,2,171)
        val notice="Original synthetic test assets; no third-party art.\n".toByteArray()
        val source="{\"originalSource\":\"synthetic\"}".toByteArray()
        fun ref(path:String,b:ByteArray)=JSONObject().put("file",path).put("sha256",hash(b)).put("bytes",b.size)
        fun item(id:String,path:String,b:ByteArray)=ref(path,b).put("id",id).put("name","Synthetic $id").put("alt","Original $id").put("mime","image/png").put("emoji",JSONArray().put("😀"))
        val manifest=JSONObject().put("schema","dev.lip.sticker-pack").put("version",1)
            .put("pack",JSONObject().put("id","fixture-pack").put("name","Original fixtures").put("author","Synthetic author").put("trayImage",ref("tray/icon.png",tray).put("mime","image/png")))
            .put("provenance",JSONObject().put("sourceName","Generated test fixture").put("sourceRevision","original-v1").put("rightsholder","Fixture author").put("license","UNKNOWN").put("sourceFormat","lip-native").put("sourceManifest",ref("source/metadata.json",source)).put("notices",JSONArray().put(ref("rights/NOTICE.txt",notice))))
            .put("stickers",JSONArray().put(item("first","stickers/first.png",first)).put(item("second","stickers/second.png",second)))
        val output=ByteArrayOutputStream()
        ZipOutputStream(output).use{zip->for((path,b) in linkedMapOf("manifest.json" to manifest.toString().toByteArray(),"tray/icon.png" to tray,"stickers/first.png" to first,"stickers/second.png" to second,"rights/NOTICE.txt" to notice,"source/metadata.json" to source)){
            zip.putNextEntry(ZipEntry(path).apply{time=1_700_000_000_000L});zip.write(b);zip.closeEntry()
        }}
        val archive=output.toByteArray();val inspected=StickerPackInspector.inspect(ByteArrayInputStream(archive))
        val images=listOf(inspected.trayImage!!.let{it.member to it.metadata})+inspected.stickers.map{it.member to it.metadata}
        // Internal constructor fixtures test only binding/storage; real isolated decode is tested separately.
        val previews=images.mapIndexed{i,(member,metadata)->val (w,h)=StickerPixelContract.target(metadata.width,metadata.height)
            SandboxedStickerPreview(member.sha256,metadata,w,h,100+i,99000,1024,ByteArray(w*h*4){n->if(n%4==3)255.toByte() else (40+i).toByte()})}
        return Fixture(archive,inspected,previews,PreparedStickerPack(archive,inspected,previews))
    }

    private fun store(root:Path,mover:(Path,Path)->Unit={a,b->Files.move(a,b);Unit})=StickerPackTransactionStore(root,moveNoReplace={anchor,_,from,to->mover(anchor.resolve(from),anchor.resolve(to))})
    @Test fun removeRestorePreservesExactArchiveAndAllOriginalBytes() {
        val root=newRoot();val fixture=fixture();val store=store(root);val id=store.commit(fixture.prepared).commitId
        val before=Files.walk(root).use{paths->paths.filter{Files.isRegularFile(it)}.mapToLong{Files.size(it)}.sum()}
        assertArrayEquals(fixture.archive,store.relocate(id,true).archiveBytes())
        assertTrue(store.scan().isEmpty());assertEquals(id,store.scanRemoved().single().commitId)
        assertThrows(StickerStoreException::class.java){store.read(id)}
        assertArrayEquals(fixture.archive,store.relocate(id,false).archiveBytes())
        assertTrue(store.scanRemoved().isEmpty());assertEquals(id,store.scan().single().commitId)
        assertEquals(before,Files.walk(root).use{paths->paths.filter{Files.isRegularFile(it)}.mapToLong{Files.size(it)}.sum()})
    }
    @Test fun canceledAndExistingTargetNeverInvokeMoveOrOverwriteEitherOriginal() {
        val root=newRoot();var moves=0;val store=store(root){_,_->moves++;error("Must not move")};val id=store.commit(fixture().prepared).commitId
        val canceled=StickerCommitControl().apply{cancel()}
        assertEquals(StickerStoreException.Publication.NOT_PUBLISHED,assertThrows(StickerStoreException::class.java){store.relocate(id,true,canceled)}.publication)
        Files.createDirectory(root.resolve("bin-$id"))
        assertThrows(StickerStoreException::class.java){store.relocate(id,true)}
        assertEquals(0,moves);assertTrue(Files.isDirectory(root.resolve("commit-$id")));assertTrue(Files.isDirectory(root.resolve("bin-$id")))
    }
    @Test fun postRenameFailureIsUnconfirmedAndFreshInventoryFindsRetainedBinWithoutReplay() {
        val root=newRoot();var moves=0;val store=store(root){from,to->moves++;Files.move(from,to);throw IOException("Synthetic post-effect failure")}
        val id=store.commit(fixture().prepared).commitId
        val error=assertThrows(StickerStoreException::class.java){store.relocate(id,true)}
        assertEquals(StickerStoreException.Publication.UNCONFIRMED,error.publication);assertEquals(1,moves)
        assertTrue(store.scan().isEmpty());assertTrue(store.scanRemoved().single().available);assertEquals(1,moves)
    }
    @Test fun removedAndInterruptedDirectoriesStillConsumeSameQuota() {
        val root=newRoot();val store=store(root);val prepared=fixture().prepared;val id=store.commit(prepared).commitId;store.relocate(id,true)
        repeat(127){Files.createDirectory(root.resolve("pending-${UUID.randomUUID()}"))}
        assertEquals(StickerStoreException.Reason.LIMIT,assertThrows(StickerStoreException::class.java){store.commit(prepared)}.reason)
        assertTrue(store.scanRemoved().single().available);assertTrue(store.scan().isEmpty())
    }
    @Test fun restoredOriginalExportSnapshotIsDefensiveAndKeepsProvenance() {
        val root=newRoot();val store=store(root);val fixture=fixture();val id=store.commit(fixture.prepared).commitId
        store.relocate(id,true);store.relocate(id,false)
        val pack=store.read(id);val exported=pack.archiveBytes();assertArrayEquals(fixture.archive,exported);exported.fill(0)
        assertArrayEquals(fixture.archive,store.read(id).archiveBytes());assertEquals("UNKNOWN",pack.inspected.provenance.license)
    }
}
