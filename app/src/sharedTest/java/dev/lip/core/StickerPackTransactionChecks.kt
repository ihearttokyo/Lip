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

/** Controlled original synthetic store inputs, NOT Android codec or durability proof. */
abstract class StickerPackTransactionChecks(private val newRoot: () -> Path) {
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
    // Intentionally retained: no test recursively deletes transaction/recovery evidence.
    private fun root():Path=newRoot()
    private fun children(path:Path):List<Path> = Files.newDirectoryStream(path).use{it.toList()}
    private fun pending(root:Path)=children(root).filter{it.fileName.toString().startsWith("pending-")}
    private fun commits(root:Path)=children(root).filter{it.fileName.toString().startsWith("commit-")}
    private fun refused(reason:StickerStoreException.Reason?=null,block:()->Unit):StickerStoreException {
        val e=assertThrows(StickerStoreException::class.java){block()}
        if(reason!=null)assertEquals(reason,e.reason)
        assertFalse(e.message!!.contains("private-test-payload"));assertNull(e.cause)
        return e
    }
    private fun snapshot(path:Path):Map<String,String> = children(path).associate{it.fileName.toString() to hash(Files.readAllBytes(it))}

    @Test fun preparationBindsArchiveOrderingMetadataAndDefensiveCopies() {
        val f=fixture();val archive=f.archive.copyOf();val list=f.previews.toMutableList();val p=PreparedStickerPack(archive,f.inspected,list)
        archive.fill(0);list.clear();p.archiveBytes().fill(0);p.previewBytes(0).fill(0)
        assertArrayEquals(f.archive,p.archiveBytes());assertArrayEquals(f.previews[0].premultipliedRgbaSrgbBytes(),p.previewBytes(0))
        assertEquals(hash(f.archive),p.archiveSha256);assertEquals(3,p.previewDescriptors.size)
        assertThrows(UnsupportedOperationException::class.java){(p.previewDescriptors as MutableList<*>).clear()}
        refused(StickerStoreException.Reason.INTEGRITY){PreparedStickerPack(f.archive,f.inspected,f.previews.reversed())}
        refused(StickerStoreException.Reason.INTEGRITY){PreparedStickerPack(f.archive,f.inspected,f.previews.drop(1))}
        val original=f.previews[0]
        val invalid=SandboxedStickerPreview(original.sourceSha256,original.metadata,original.width,original.height,100,99000,1024,byteArrayOf(1))
        refused(StickerStoreException.Reason.INTEGRITY){PreparedStickerPack(f.archive,f.inspected,listOf(invalid)+f.previews.drop(1))}
        refused(StickerStoreException.Reason.INTEGRITY){PreparedStickerPack(f.archive.copyOf().also{it[0]=0},f.inspected,f.previews)}
    }

    @Test fun commitReopenPreservesArchiveProvenancePixelsAndUnverifiedRights() {
        val f=fixture();val root=root();val receipt=StickerPackTransactionStore(root).commit(f.prepared)
        val reopened=StickerPackTransactionStore(root).read(receipt.commitId)
        assertEquals(receipt.transactionId,receipt.commitId);assertEquals(f.prepared.archiveSha256,receipt.archiveSha256)
        assertArrayEquals(f.archive,reopened.archiveBytes());assertEquals(f.inspected.identity,reopened.inspected.identity)
        assertArrayEquals(f.inspected.manifest.bytes(),reopened.inspected.manifest.bytes())
        assertArrayEquals(f.inspected.provenance.sourceManifest!!.bytes(),reopened.inspected.provenance.sourceManifest!!.bytes())
        assertArrayEquals(f.inspected.provenance.notices.single().bytes(),reopened.inspected.provenance.notices.single().bytes())
        assertEquals(InspectedStickerPack.Rights.UNVERIFIED,reopened.rights)
        reopened.previews.zip(f.previews).forEach{(a,b)->assertArrayEquals(b.premultipliedRgbaSrgbBytes(),a.premultipliedRgbaSrgbBytes());assertEquals(b.sourceSha256,a.sourceSha256);assertEquals(b.metadata,a.metadata);assertEquals(InspectedStickerPack.Rights.UNVERIFIED,a.rights)}
        reopened.archiveBytes().fill(0);reopened.previews[0].premultipliedRgbaSrgbBytes().fill(0)
        assertArrayEquals(f.archive,reopened.archiveBytes());assertArrayEquals(f.previews[0].premultipliedRgbaSrgbBytes(),reopened.previews[0].premultipliedRgbaSrgbBytes())
        assertThrows(UnsupportedOperationException::class.java){(reopened.previews as MutableList<*>).clear()}
        assertEquals(listOf(receipt.commitId),StickerPackTransactionStore(root).scan().map{assertTrue(it.available);it.commitId})
        assertEquals(0,pending(root).size);assertEquals(1,commits(root).size)
        assertEquals(children(commits(root).single()).sumOf{Files.size(it)},receipt.storedBytes)
    }

    @Test fun repeatedImportCreatesNewCommitWithoutMutatingEarlierCommit() {
        val root=root();val f=fixture();val store=StickerPackTransactionStore(root);val first=store.commit(f.prepared)
        val dir=root.resolve("commit-${first.commitId}");val before=snapshot(dir);val times=children(dir).associate{it.fileName.toString() to Files.getLastModifiedTime(it)}
        val second=store.commit(f.prepared)
        assertNotEquals(first.commitId,second.commitId);assertEquals(before,snapshot(dir));assertEquals(times,children(dir).associate{it.fileName.toString() to Files.getLastModifiedTime(it)})
        assertEquals(2,store.scan().size)
    }

    @Test fun faultsBeforeTerminalOrMoveRetainPendingAndNeverPublish() {
        val f=fixture()
        for(point in listOf(StickerStorePoint.BEFORE_TERMINAL,StickerStorePoint.BEFORE_MOVE)) {
            val root=root();val control=StickerCommitControl()
            val e=refused(StickerStoreException.Reason.IO){StickerPackTransactionStore(root){if(it==point)throw IOException("private-test-payload")}.commit(f.prepared,control)}
            assertEquals(StickerStoreException.Publication.NOT_PUBLISHED,e.publication);assertEquals(control.commitId,e.commitId)
            assertEquals(0,commits(root).size);assertEquals(1,pending(root).size);assertEquals(0,StickerPackTransactionStore(root).scan().size)
            val stage=pending(root).single();assertEquals(point==StickerStorePoint.BEFORE_MOVE,Files.exists(stage.resolve("terminal.bin")))
            val before=snapshot(stage);StickerPackTransactionStore(root).commit(f.prepared);assertEquals(before,snapshot(stage))
        }
    }

    @Test fun postMoveFailuresAreUnconfirmedAndReconcileOnlyTheExactId() {
        val f=fixture()
        for(point in listOf(StickerStorePoint.AFTER_MOVE,StickerStorePoint.AFTER_ROOT_SYNC,StickerStorePoint.BEFORE_READBACK,StickerStorePoint.BEFORE_RETURN)) {
            val root=root();val control=StickerCommitControl();val store=StickerPackTransactionStore(root){if(it==point)throw IOException("private-test-payload")}
            val e=refused(StickerStoreException.Reason.IO){store.commit(f.prepared,control)}
            assertEquals(StickerStoreException.Publication.UNCONFIRMED,e.publication);assertEquals(control.transactionId,e.transactionId);assertEquals(control.commitId,e.commitId)
            assertArrayEquals(f.archive,StickerPackTransactionStore(root).read(e.commitId!!).archiveBytes())
            val reused=refused(StickerStoreException.Reason.STATE){store.commit(f.prepared,control)}
            assertEquals(StickerStoreException.Publication.UNCONFIRMED,reused.publication);assertEquals(1,commits(root).size)
        }
    }

    @Test fun cancellationLinearizesBeforePublicationClaimAndLosesAfterIt() {
        val f=fixture();val root=root();val early=StickerCommitControl();assertTrue(early.cancel())
        val before=refused(StickerStoreException.Reason.CANCELED){StickerPackTransactionStore(root).commit(f.prepared,early)}
        assertEquals(StickerStoreException.Publication.NOT_PUBLISHED,before.publication);assertTrue(children(root).isEmpty())
        val control=StickerCommitControl()
        refused(StickerStoreException.Reason.CANCELED){StickerPackTransactionStore(root){if(it==StickerStorePoint.BEFORE_PUBLICATION_CLAIM)assertTrue(control.cancel())}.commit(f.prepared,control)}
        assertTrue(commits(root).isEmpty());assertEquals(1,pending(root).size)
        val late=StickerCommitControl();val live=AtomicBoolean(true)
        val committed=StickerPackTransactionStore(root){if(it==StickerStorePoint.AFTER_PUBLICATION_CLAIM){assertFalse(late.cancel());live.set(false)}}.commit(f.prepared,late){live.get()}
        assertEquals(late.commitId,committed.commitId);assertFalse(late.cancel());assertArrayEquals(f.archive,StickerPackTransactionStore(root).read(committed.commitId).archiveBytes())
    }

    @Test fun reusedInFlightControlNeverPromisesNothingPublishedAndWritersAreExcluded() {
        val root=root();val f=fixture();val control=StickerCommitControl();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val result=AtomicReference<StickerCommitReceipt>();val failure=AtomicReference<Throwable>()
        val thread=Thread{try{result.set(StickerPackTransactionStore(root){if(it==StickerStorePoint.AFTER_STAGE){entered.countDown();check(release.await(10,TimeUnit.SECONDS))}}.commit(f.prepared,control))}catch(t:Throwable){failure.set(t)}}
        thread.start()
        try {
            assertTrue(entered.await(10,TimeUnit.SECONDS))
            val reused=refused(StickerStoreException.Reason.STATE){StickerPackTransactionStore(root).commit(f.prepared,control)}
            assertEquals(StickerStoreException.Publication.UNCONFIRMED,reused.publication);assertEquals(control.commitId,reused.commitId)
            refused(StickerStoreException.Reason.BUSY){StickerPackTransactionStore(root).commit(f.prepared)}
            assertEquals(1,pending(root).size)
        } finally {release.countDown();thread.join(10_000)}
        assertFalse(thread.isAlive);assertNull(failure.get());assertEquals(control.commitId,result.get().commitId)
        assertArrayEquals(f.archive,StickerPackTransactionStore(root).read(control.commitId).archiveBytes())
    }

    @Test fun readAndScanCancellationRemainTypedAtAllBoundedInspectionCheckpoints() {
        val root=root();val f=fixture();val store=StickerPackTransactionStore(root);val id=store.commit(f.prepared).commitId
        var total=0;store.read(id){total++;true};assertTrue(total>24)
        for(cutoff in 1..total){var calls=0;refused(StickerStoreException.Reason.CANCELED){store.read(id){++calls<cutoff}}}
        refused(StickerStoreException.Reason.CANCELED){store.scan{false}}
        assertArrayEquals(f.archive,store.read(id).archiveBytes())
    }

    @Test fun symlinksUnsafeNamesAndTargetCollisionAreRefusedWithoutFollowingOrOverwrite() {
        val external=root();val target=external.resolve("preserved.bin");val original=byteArrayOf(21,42,63);Files.write(target,original)
        val alias=external.resolve("root-alias");Files.createSymbolicLink(alias,root())
        refused(StickerStoreException.Reason.PATH){StickerPackTransactionStore(alias)}
        val f=fixture();val root=root();val control=StickerCommitControl()
        refused {StickerPackTransactionStore(root){if(it==StickerStorePoint.AFTER_STAGE)Files.createSymbolicLink(root.resolve("pending-${control.transactionId}/archive.zip"),target)}.commit(f.prepared,control)}
        assertArrayEquals(original,Files.readAllBytes(target));assertTrue(commits(root).isEmpty())
        val collisionRoot=root();val collision=StickerCommitControl()
        val e=refused(StickerStoreException.Reason.STATE){StickerPackTransactionStore(collisionRoot){if(it==StickerStorePoint.BEFORE_MOVE)Files.createDirectory(collisionRoot.resolve("commit-${collision.commitId}"))}.commit(f.prepared,collision)}
        assertEquals(StickerStoreException.Publication.NOT_PUBLISHED,e.publication);assertTrue(children(collisionRoot.resolve("commit-${collision.commitId}")).isEmpty());assertEquals(1,pending(collisionRoot).size)
        val unsafe=root();Files.write(unsafe.resolve("unexpected.bin"),byteArrayOf(1))
        refused(StickerStoreException.Reason.PATH){StickerPackTransactionStore(unsafe).scan()}
        refused(StickerStoreException.Reason.PATH){StickerPackTransactionStore(root()).read("../private-test-payload")}
    }

    @Test fun readRejectsSymlinkMemberWithoutTouchingItsPreservedOriginal() {
        val f=fixture();val root=root();val store=StickerPackTransactionStore(root);val id=store.commit(f.prepared).commitId
        val archive=root.resolve("commit-$id/archive.zip");val backup=root().resolve("original.zip")
        Files.move(archive,backup);Files.createSymbolicLink(archive,backup)
        refused(StickerStoreException.Reason.PATH){store.read(id)};refused(StickerStoreException.Reason.PATH){store.scan()}
        assertArrayEquals(f.archive,Files.readAllBytes(backup))
    }

    @Test fun tamperedAndTruncatedPayloadsAreUnavailableNotSilentlySkipped() {
        val f=fixture()
        for(file in listOf("archive.zip","preview-000.rgba","terminal.bin"))for(truncate in listOf(false,true)) {
            val root=root();val store=StickerPackTransactionStore(root);val id=store.commit(f.prepared).commitId;val path=root.resolve("commit-$id/$file")
            val original=Files.readAllBytes(path);val backup=root().resolve("original.bin");Files.write(backup,original)
            FileChannel.open(path,StandardOpenOption.WRITE).use{if(truncate)it.truncate(1) else it.write(ByteBuffer.wrap(byteArrayOf((original[0].toInt() xor 1).toByte())),0)}
            refused{store.read(id)}
            val entry=store.scan().single();assertFalse(entry.available);assertEquals(id,entry.commitId);assertNotNull(entry.reason);assertNull(entry.identity);assertNull(entry.archiveSha256)
            assertArrayEquals(original,Files.readAllBytes(backup))
        }
    }

    @Test fun retainedPendingBytesAndDirectoryCountsAreChargedBeforeNewStage() {
        val f=fixture();val root=root()
        repeat(16){val stage=Files.createDirectory(root.resolve("pending-${UUID.randomUUID()}"));FileChannel.open(stage.resolve("archive.zip"),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE).use{it.position(16L*1024*1024-1);it.write(ByteBuffer.wrap(byteArrayOf(0)))}}
        val before=pending(root).map{it.fileName.toString()}.toSet()
        refused(StickerStoreException.Reason.LIMIT){StickerPackTransactionStore(root).commit(f.prepared)}
        assertEquals(before,pending(root).map{it.fileName.toString()}.toSet());assertTrue(commits(root).isEmpty())
        val full=root();repeat(128){Files.createDirectory(full.resolve("pending-${UUID.randomUUID()}"))}
        refused(StickerStoreException.Reason.LIMIT){StickerPackTransactionStore(full).commit(f.prepared)};assertEquals(128,pending(full).size)
        val boundary=root();repeat(127){Files.createDirectory(boundary.resolve("pending-${UUID.randomUUID()}"))}
        StickerPackTransactionStore(boundary).commit(f.prepared);assertEquals(128,pending(boundary).size+commits(boundary).size)
        refused(StickerStoreException.Reason.LIMIT){StickerPackTransactionStore(boundary).commit(f.prepared)}
    }

    fun checkSeparateJvmHoldingExistingWriterLockExcludesNewTransaction() {
        val root=root();val f=fixture();val store=StickerPackTransactionStore(root);store.commit(f.prepared)
        val classpath=listOf(StickerStoreLockChild::class.java,StickerPackTransactionStore::class.java,kotlin.Unit::class.java).map{Path.of(it.protectionDomain.codeSource.location.toURI()).toString()}.distinct().joinToString(java.io.File.pathSeparator)
        val child=ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",classpath,StickerStoreLockChild::class.java.name,root.resolve("writer.lock").toString()).redirectErrorStream(true).start()
        val ready=AtomicReference<String>();val reader=Thread{ready.set(child.inputStream.bufferedReader().readLine())};reader.start()
        try {
            reader.join(10_000);assertFalse("Owned lock helper did not become ready",reader.isAlive);assertEquals("LOCKED",ready.get())
            refused(StickerStoreException.Reason.BUSY){store.commit(f.prepared)};assertEquals(1,commits(root).size);assertTrue(pending(root).isEmpty())
        } finally {
            child.outputStream.use{it.write(1);it.flush()}
            if(!child.waitFor(5,TimeUnit.SECONDS)){child.destroyForcibly();child.waitFor(5,TimeUnit.SECONDS)}
            reader.join(5_000)
        }
        assertEquals(0,child.exitValue());store.commit(f.prepared);assertEquals(2,commits(root).size)
    }
}

/** Bounded, owned helper JVM: never creates/replaces a lock, and touches no other file. */
object StickerStoreLockChild {
    @JvmStatic fun main(args:Array<String>) {
        check(args.size==1);val path=Path.of(args[0]);check(path.fileName.toString()=="writer.lock")
        check(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(path))
        FileChannel.open(path,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS).use{channel->channel.lock().use{
            println("LOCKED");System.out.flush();System.`in`.read()
        }}
    }
}
