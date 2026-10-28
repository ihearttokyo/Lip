package dev.lip.stickers

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class StickerStoreException(val reason:Reason,val transactionId:String?=null,val commitId:String?=null,
    val publication:Publication?=null):IllegalStateException("Sticker storage refused: ${reason.name}; publication=${publication?.name?:"NOT_APPLICABLE"}.") {
    enum class Reason { STATE, PATH, BUSY, LIMIT, INTEGRITY, CANCELED, IO, UNAVAILABLE }
    enum class Publication { NOT_PUBLISHED, UNCONFIRMED }
}

/** Exact caller cancellation linearization. A live predicate alone is only polled. */
internal class StickerCommitControl {
    val transactionId:String=UUID.randomUUID().toString()
    val commitId:String get()=transactionId
    private val started=AtomicBoolean(false)
    private val state=AtomicInteger(0) // open, canceled, publication-claimed, terminal
    private val renamePossible=AtomicBoolean(false)
    fun cancel():Boolean {
        while(true){when(val current=state.get()){1->return true;0->if(state.compareAndSet(current,1))return true;else->return false}}
    }
    internal fun start():Boolean=started.compareAndSet(false,true)
    internal fun canceled():Boolean=state.get()==1
    internal fun claim():Boolean=state.compareAndSet(0,2)
    internal fun noteRenameAttempt(){renamePossible.set(true)}
    internal fun publicationPossible():Boolean=state.get()==2||renamePossible.get()
    internal fun finish(){state.compareAndSet(2,3);state.compareAndSet(0,3)}
}

internal enum class StickerStorePoint {
    BEFORE_STAGE,AFTER_STAGE,AFTER_ARCHIVE,AFTER_PREVIEW,BEFORE_TERMINAL,
    BEFORE_PUBLICATION_CLAIM,AFTER_PUBLICATION_CLAIM,BEFORE_MOVE,AFTER_MOVE,
    AFTER_ROOT_SYNC,BEFORE_READBACK,BEFORE_RETURN
}

internal data class StickerCommitReceipt(val transactionId:String,val commitId:String,val archiveSha256:String,val storedBytes:Long)
internal data class StickerStoredEntry(val commitId:String,val available:Boolean,val identity:StickerPackIdentity?,val archiveSha256:String?,val reason:StickerStoreException.Reason?)

/** Raw stored pixels with integrity checks, NEVER reconstructed sandbox/codec proof. */
internal class StoredStickerPreview internal constructor(val sourceSha256:String,val metadata:StickerImageMetadata,
    val width:Int,val height:Int,rgba:ByteArray) {
    private val payload=rgba.copyOf()
    val byteCount:Int get()=payload.size
    val rights=InspectedStickerPack.Rights.UNVERIFIED
    fun premultipliedRgbaSrgbBytes():ByteArray=payload.copyOf()
}
internal class StoredStickerPack internal constructor(val commitId:String,val archiveSha256:String,
    val inspected:InspectedStickerPack,archive:ByteArray,previews:List<StoredStickerPreview>) {
    private val payload=archive.copyOf()
    val previews:List<StoredStickerPreview> = Collections.unmodifiableList(ArrayList(previews))
    val rights=InspectedStickerPack.Rights.UNVERIFIED
    fun archiveBytes():ByteArray=payload.copyOf()
}

private fun hash(bytes:ByteArray):String=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
private fun images(pack:InspectedStickerPack):List<Pair<StickerArchiveMember,StickerImageMetadata>> =
    listOfNotNull(pack.trayImage?.let{it.member to it.metadata})+pack.stickers.map{it.member to it.metadata}
private fun sameMember(a:StickerArchiveMember?,b:StickerArchiveMember?):Boolean =
    if(a==null||b==null)a==null&&b==null else a.path==b.path&&a.size==b.size&&a.sha256==b.sha256
private fun samePack(a:InspectedStickerPack,b:InspectedStickerPack):Boolean {
    val x=a.provenance;val y=b.provenance
    return a.identity==b.identity&&a.canvasPixelWork==b.canvasPixelWork&&sameMember(a.manifest,b.manifest)&&
        x.sourceName==y.sourceName&&x.sourceRevision==y.sourceRevision&&x.rightsholder==y.rightsholder&&x.license==y.license&&
        x.sourceFormat==y.sourceFormat&&sameMember(x.sourceManifest,y.sourceManifest)&&x.notices.size==y.notices.size&&
        x.notices.zip(y.notices).all{sameMember(it.first,it.second)}&&
        (a.trayImage==null)==(b.trayImage==null)&&
        (a.trayImage==null||sameMember(a.trayImage.member,b.trayImage!!.member)&&a.trayImage.metadata==b.trayImage.metadata)&&
        a.stickers.size==b.stickers.size&&a.stickers.zip(b.stickers).all{(p,q)->
            p.id==q.id&&p.name==q.name&&p.alt==q.alt&&p.emojiAliases==q.emojiAliases&&sameMember(p.member,q.member)&&p.metadata==q.metadata
        }
}

internal data class PreparedStickerPreview internal constructor(val sourceSha256:String,val metadata:StickerImageMetadata,
    val width:Int,val height:Int,val byteCount:Int,val rgbaSha256:String)

/** Tray first (when present), followed by manifest sticker order. No activation or rights grant. */
internal class PreparedStickerPack internal constructor(archive:ByteArray,inspected:InspectedStickerPack,previews:List<SandboxedStickerPreview>) {
    private val payload:ByteArray
    private val pixels:List<ByteArray>
    val archiveSha256:String
    val inspected:InspectedStickerPack
    val previewDescriptors:List<PreparedStickerPreview>
    internal val archiveByteCount:Int get()=payload.size
    init {
        if(archive.size !in 1..StickerArchiveReader.MAX_SOURCE_BYTES)throw StickerStoreException(StickerStoreException.Reason.LIMIT)
        payload=archive.copyOf();archiveSha256=hash(payload)
        val actual=try{StickerPackInspector.inspect(ByteArrayInputStream(payload))}catch(_:Exception){throw StickerStoreException(StickerStoreException.Reason.INTEGRITY)}
        if(!samePack(inspected,actual))throw StickerStoreException(StickerStoreException.Reason.INTEGRITY)
        this.inspected=actual
        val expected=images(actual)
        if(previews.size!=expected.size)throw StickerStoreException(StickerStoreException.Reason.INTEGRITY)
        val copies=ArrayList<ByteArray>();val descriptors=ArrayList<PreparedStickerPreview>();var total=0L
        previews.zip(expected).forEach{(preview,image)->
            if(preview.sourceSha256!=image.first.sha256||preview.metadata!=image.second||preview.workerPid<=0||preview.workerUid<=0||preview.observedPeakRssKb<0)throw StickerStoreException(StickerStoreException.Reason.INTEGRITY)
            try{StickerPixelContract.validate(image.second,preview.metadata.width,preview.metadata.height,preview.width,preview.height,preview.metadata.frames,preview.metadata.durationMs,preview.byteCount)}
            catch(_:Exception){throw StickerStoreException(StickerStoreException.Reason.INTEGRITY)}
            total+=preview.byteCount
            if(total>StickerPixelContract.MAX_PACK_RGBA_BYTES)throw StickerStoreException(StickerStoreException.Reason.LIMIT)
            val bytes=preview.premultipliedRgbaSrgbBytes()
            if(bytes.size!=preview.byteCount)throw StickerStoreException(StickerStoreException.Reason.INTEGRITY)
            copies+=bytes;descriptors+=PreparedStickerPreview(preview.sourceSha256,image.second,preview.width,preview.height,bytes.size,hash(bytes))
        }
        pixels=Collections.unmodifiableList(copies);previewDescriptors=Collections.unmodifiableList(descriptors)
    }
    fun archiveBytes():ByteArray=payload.copyOf()
    internal fun previewBytes(index:Int):ByteArray=pixels[index].copyOf()
}

/**
 * Dedicated existing caller-owned private root. Leaf symlinks and changed root
 * identity are refused. OS parent aliases are canonicalized once; all managed
 * payload operations are relative to secure directory handles with NOFOLLOW.
 * No deletion, overwrite, codec invocation, external-path or nonatomic fallback.
 */
internal class StickerPackTransactionStore(root:Path,
    private val moveNoReplace:(Path,Any,String,String)->Unit={root,key,from,to->RootedStickerRename.move(root,key,from,to)},
    private val hook:(StickerStorePoint)->Unit={}) {
    private val root:Path
    private val rootKey:Any
    private val fs=root.fileSystem
    companion object {
        const val MAX_STORE_BYTES=256L*1024*1024
        const val MAX_DIRECTORIES=128
        const val MAX_DESCRIPTOR_BYTES=65_536
        private val magic="LIP-STICKERS-V1\u0000".toByteArray(Charsets.US_ASCII)
        private val idPattern=Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        private val directoryPattern=Regex("(?:pending|commit|bin)-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        private val previewPattern=Regex("preview-(?:0[0-9]{2}|1[01][0-9]|120)\\.rgba")
        private val readOptions=setOf<OpenOption>(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)
        private val createOptions=setOf<OpenOption>(StandardOpenOption.WRITE,StandardOpenOption.CREATE_NEW,LinkOption.NOFOLLOW_LINKS)
    }
    init {
        try {
            val original=Files.readAttributes(root,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
            if(!original.isDirectory||original.isSymbolicLink)fail(StickerStoreException.Reason.PATH)
            this.root=root.toRealPath()
            val canonical=Files.readAttributes(this.root,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
            rootKey=canonical.fileKey()?:fail(StickerStoreException.Reason.STATE)
            if(!canonical.isDirectory||canonical.isSymbolicLink||original.fileKey()!=rootKey)fail(StickerStoreException.Reason.PATH)
        }catch(e:StickerStoreException){throw e}catch(_:Throwable){fail(StickerStoreException.Reason.PATH)}
    }
    private fun fail(reason:StickerStoreException.Reason):Nothing=throw StickerStoreException(reason)
    private fun name(value:String):Path=fs.getPath(value)
    private fun uuid(id:String):UUID {
        if(!idPattern.matches(id))fail(StickerStoreException.Reason.PATH)
        return try{UUID.fromString(id).also{if(it.toString()!=id)fail(StickerStoreException.Reason.PATH)}}catch(e:StickerStoreException){throw e}catch(_:Exception){fail(StickerStoreException.Reason.PATH)}
    }
    private fun checkRoot(){
        val a=Files.readAttributes(root,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
        if(!a.isDirectory||a.isSymbolicLink||a.fileKey()!=rootKey)fail(StickerStoreException.Reason.PATH)
    }
    private fun <T> withRoot(action:(SecureDirectoryStream<Path>)->T):T {
        checkRoot()
        Files.newDirectoryStream(root).use{stream->
            if(stream !is SecureDirectoryStream<*>)fail(StickerStoreException.Reason.STATE)
            @Suppress("UNCHECKED_CAST") val secure=stream as SecureDirectoryStream<Path>
            val a=secure.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes()
            if(a.fileKey()!=rootKey||!a.isDirectory||a.isSymbolicLink)fail(StickerStoreException.Reason.PATH)
            checkRoot();return action(secure)
        }
    }
    private fun attributes(dir:SecureDirectoryStream<Path>,file:String):BasicFileAttributes? = try{
        dir.getFileAttributeView(name(file),BasicFileAttributeView::class.java,LinkOption.NOFOLLOW_LINKS).readAttributes()
    }catch(_:NoSuchFileException){null}
    private fun regular(dir:SecureDirectoryStream<Path>,file:String):BasicFileAttributes {
        val a=attributes(dir,file)?:fail(StickerStoreException.Reason.UNAVAILABLE)
        if(!a.isRegularFile||a.isSymbolicLink)fail(StickerStoreException.Reason.PATH)
        return a
    }
    private fun channel(dir:SecureDirectoryStream<Path>,file:String,options:Set<OpenOption>):FileChannel {
        val raw=dir.newByteChannel(name(file),options)
        if(raw !is FileChannel){raw.close();fail(StickerStoreException.Reason.STATE)}
        return raw
    }
    private fun forceDirectory(dir:SecureDirectoryStream<Path>){channel(dir,".",readOptions).use{it.force(true)}}
    private fun child(dir:SecureDirectoryStream<Path>,file:String):SecureDirectoryStream<Path> {
        val a=attributes(dir,file)?:fail(StickerStoreException.Reason.UNAVAILABLE)
        if(!a.isDirectory||a.isSymbolicLink)fail(StickerStoreException.Reason.PATH)
        return dir.newDirectoryStream(name(file),LinkOption.NOFOLLOW_LINKS).also{
            if(it.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes().fileKey()!=a.fileKey()){
                it.close();fail(StickerStoreException.Reason.PATH)
            }
        }
    }
    private data class Inventory(val directories:List<String>,val bytes:Long)
    private fun allowedFile(file:String)=file=="archive.zip"||file=="terminal.bin"||previewPattern.matches(file)
    private fun files(dir:SecureDirectoryStream<Path>):Map<String,BasicFileAttributes> {
        val found=LinkedHashMap<String,BasicFileAttributes>()
        for(path in dir){
            val file=path.fileName.toString()
            if(found.size>=123||!allowedFile(file))fail(StickerStoreException.Reason.PATH)
            found[file]=regular(dir,file)
        }
        return found
    }
    private fun inventory(dir:SecureDirectoryStream<Path>):Inventory {
        val directories=ArrayList<String>();var bytes=0L
        fun charge(size:Long){if(size<0)fail(StickerStoreException.Reason.INTEGRITY);bytes=if(size>MAX_STORE_BYTES-bytes)MAX_STORE_BYTES+1 else bytes+size}
        for(path in dir){
            val file=path.fileName.toString()
            if(file=="writer.lock"){
                if(regular(dir,file).size()!=0L)fail(StickerStoreException.Reason.PATH)
            }else{
                if(!directoryPattern.matches(file))fail(StickerStoreException.Reason.PATH)
                if(directories.size>=MAX_DIRECTORIES)fail(StickerStoreException.Reason.LIMIT)
                directories+=file
                child(dir,file).use{entry->files(entry).values.forEach{charge(it.size())}}
            }
        }
        return Inventory(directories,bytes)
    }
    private fun writerLock(dir:SecureDirectoryStream<Path>):FileChannel {
        if(attributes(dir,"writer.lock")==null){
            try{channel(dir,"writer.lock",setOf(StandardOpenOption.READ,StandardOpenOption.WRITE,StandardOpenOption.CREATE_NEW,LinkOption.NOFOLLOW_LINKS)).use{it.force(true)};forceDirectory(dir)}
            catch(_:FileAlreadyExistsException){} // Another conforming opener created the one lock inode.
        }
        if(regular(dir,"writer.lock").size()!=0L)fail(StickerStoreException.Reason.PATH)
        return channel(dir,"writer.lock",setOf(StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS))
    }
    private fun running(live:()->Boolean):Boolean=try{live()}catch(_:Throwable){false}
    private fun checkCancel(control:StickerCommitControl,live:()->Boolean){
        if(Thread.currentThread().isInterrupted||!running(live))control.cancel()
        if(control.canceled())fail(StickerStoreException.Reason.CANCELED)
    }
    private fun readBytes(dir:SecureDirectoryStream<Path>,file:String,maximum:Int,live:()->Boolean):ByteArray {
        val before=regular(dir,file)
        if(before.size() !in 0L..maximum.toLong())fail(StickerStoreException.Reason.LIMIT)
        val out=ByteArray(before.size().toInt())
        channel(dir,file,readOptions).use{input->
            if(input.size()!=before.size())fail(StickerStoreException.Reason.INTEGRITY)
            var at=0
            while(at<out.size){
                if(!running(live))fail(StickerStoreException.Reason.CANCELED)
                val n=input.read(ByteBuffer.wrap(out,at,minOf(32*1024,out.size-at)))
                if(n<=0)fail(StickerStoreException.Reason.INTEGRITY)
                at+=n
            }
            if(input.read(ByteBuffer.allocate(1))!=-1||input.size()!=out.size.toLong())fail(StickerStoreException.Reason.INTEGRITY)
        }
        val after=regular(dir,file)
        if(before.fileKey()!=after.fileKey()||after.size()!=out.size.toLong())fail(StickerStoreException.Reason.INTEGRITY)
        if(!running(live))fail(StickerStoreException.Reason.CANCELED)
        return out
    }
    private fun writeBytes(dir:SecureDirectoryStream<Path>,file:String,bytes:ByteArray,control:StickerCommitControl,live:()->Boolean){
        checkCancel(control,live)
        channel(dir,file,createOptions).use{output->
            var at=0
            while(at<bytes.size){
                checkCancel(control,live)
                val n=output.write(ByteBuffer.wrap(bytes,at,minOf(32*1024,bytes.size-at)))
                if(n<=0)fail(StickerStoreException.Reason.IO)
                at+=n
            }
            output.force(true)
        }
        forceDirectory(dir)
    }
    private fun previewName(index:Int)="preview-%03d.rgba".format(java.util.Locale.ROOT,index)
    private data class PixelRecord(val width:Int,val height:Int,val bytes:Int,val sourceSha:String,val rgbaSha:String)
    private data class Terminal(val id:String,val archiveBytes:Int,val archiveSha:String,val previews:List<PixelRecord>)
    private fun binaryHash(output:DataOutputStream,value:String){
        if(!Regex("[a-f0-9]{64}").matches(value))fail(StickerStoreException.Reason.INTEGRITY)
        repeat(32){output.writeByte(value.substring(2*it,2*it+2).toInt(16))}
    }
    private fun binaryHash(input:DataInputStream):String=ByteArray(32).also{input.readFully(it)}.joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun terminal(prepared:PreparedStickerPack,id:String):ByteArray=ByteArrayOutputStream().also{bytes->
        DataOutputStream(bytes).use{out->
            out.write(magic);out.writeByte(0) // Rights remain UNVERIFIED; no permission field exists.
            val owner=uuid(id);out.writeLong(owner.mostSignificantBits);out.writeLong(owner.leastSignificantBits)
            out.writeInt(prepared.archiveByteCount);binaryHash(out,prepared.archiveSha256);out.writeInt(prepared.previewDescriptors.size)
            prepared.previewDescriptors.forEach{p->out.writeInt(p.width);out.writeInt(p.height);out.writeInt(p.byteCount);binaryHash(out,p.sourceSha256);binaryHash(out,p.rgbaSha256)}
        }
    }.toByteArray().also{if(it.size>MAX_DESCRIPTOR_BYTES)fail(StickerStoreException.Reason.LIMIT)}
    private fun terminal(bytes:ByteArray):Terminal {
        if(bytes.size>MAX_DESCRIPTOR_BYTES)fail(StickerStoreException.Reason.LIMIT)
        try{
            DataInputStream(ByteArrayInputStream(bytes)).use{input->
                val signature=ByteArray(magic.size);input.readFully(signature)
                if(!signature.contentEquals(magic)||input.readUnsignedByte()!=0)fail(StickerStoreException.Reason.INTEGRITY)
                val id=UUID(input.readLong(),input.readLong()).toString();val archiveBytes=input.readInt()
                if(archiveBytes !in 1..StickerArchiveReader.MAX_SOURCE_BYTES)fail(StickerStoreException.Reason.LIMIT)
                val archiveSha=binaryHash(input);val count=input.readInt()
                if(count !in 1..121)fail(StickerStoreException.Reason.LIMIT)
                var total=0L
                val records=(0 until count).map{
                    val width=input.readInt();val height=input.readInt();val length=input.readInt()
                    val expected=try{StickerPixelContract.byteCount(width,height)}catch(_:Exception){fail(StickerStoreException.Reason.INTEGRITY)}
                    if(length!=expected)fail(StickerStoreException.Reason.INTEGRITY)
                    total+=length;if(total>StickerPixelContract.MAX_PACK_RGBA_BYTES)fail(StickerStoreException.Reason.LIMIT)
                    PixelRecord(width,height,length,binaryHash(input),binaryHash(input))
                }
                if(input.read()!=-1)fail(StickerStoreException.Reason.INTEGRITY)
                return Terminal(id,archiveBytes,archiveSha,records)
            }
        }catch(e:StickerStoreException){throw e}catch(_:Exception){fail(StickerStoreException.Reason.INTEGRITY)}
    }
    private fun inspectionFailure(t:Throwable):Nothing {
        val canceled=when(t){
            is StickerStoreException->t.reason==StickerStoreException.Reason.CANCELED
            is StickerPackException->t.reason==StickerPackException.Reason.CANCELED
            is StickerArchiveException->t.reason==StickerArchiveException.Reason.CANCELED
            is StickerImageException->t.reason==StickerImageException.Reason.CANCELED
            else->false
        }
        fail(if(canceled)StickerStoreException.Reason.CANCELED else StickerStoreException.Reason.INTEGRITY)
    }
    private fun verifyPayloads(dir:SecureDirectoryStream<Path>,prepared:PreparedStickerPack,control:StickerCommitControl,live:()->Boolean){
        if(files(dir).keys!=(setOf("archive.zip")+prepared.previewDescriptors.indices.map(::previewName)))fail(StickerStoreException.Reason.INTEGRITY)
        val archive=readBytes(dir,"archive.zip",StickerArchiveReader.MAX_SOURCE_BYTES){checkCancel(control,live);true}
        if(archive.size!=prepared.archiveByteCount||hash(archive)!=prepared.archiveSha256)fail(StickerStoreException.Reason.INTEGRITY)
        val actual=try{StickerPackInspector.inspect(ByteArrayInputStream(archive)){checkCancel(control,live);true}}catch(t:Throwable){inspectionFailure(t)}
        if(!samePack(actual,prepared.inspected))fail(StickerStoreException.Reason.INTEGRITY)
        prepared.previewDescriptors.forEachIndexed{i,p->
            val bytes=readBytes(dir,previewName(i),StickerPixelContract.MAX_RGBA_BYTES){checkCancel(control,live);true}
            if(bytes.size!=p.byteCount||hash(bytes)!=p.rgbaSha256)fail(StickerStoreException.Reason.INTEGRITY)
        }
    }
    private fun readCommit(root:SecureDirectoryStream<Path>,id:String,live:()->Boolean,removed:Boolean=false):StoredStickerPack {
        uuid(id)
        child(root,"${if(removed)"bin" else "commit"}-$id").use{dir->
            val actualFiles=files(dir)
            val record=terminal(readBytes(dir,"terminal.bin",MAX_DESCRIPTOR_BYTES,live))
            if(record.id!=id||actualFiles.keys!=(setOf("archive.zip","terminal.bin")+record.previews.indices.map(::previewName)))fail(StickerStoreException.Reason.INTEGRITY)
            val archive=readBytes(dir,"archive.zip",StickerArchiveReader.MAX_SOURCE_BYTES,live)
            if(archive.size!=record.archiveBytes||hash(archive)!=record.archiveSha)fail(StickerStoreException.Reason.INTEGRITY)
            val inspected=try{StickerPackInspector.inspect(ByteArrayInputStream(archive),live)}catch(t:Throwable){inspectionFailure(t)}
            val expected=images(inspected)
            if(expected.size!=record.previews.size)fail(StickerStoreException.Reason.INTEGRITY)
            val previews=record.previews.mapIndexed{i,p->
                val source=expected[i]
                if(p.sourceSha!=source.first.sha256)fail(StickerStoreException.Reason.INTEGRITY)
                try{StickerPixelContract.validate(source.second,source.second.width,source.second.height,p.width,p.height,source.second.frames,source.second.durationMs,p.bytes)}
                catch(_:Exception){fail(StickerStoreException.Reason.INTEGRITY)}
                val bytes=readBytes(dir,previewName(i),StickerPixelContract.MAX_RGBA_BYTES,live)
                if(bytes.size!=p.bytes||hash(bytes)!=p.rgbaSha)fail(StickerStoreException.Reason.INTEGRITY)
                StoredStickerPreview(p.sourceSha,source.second,p.width,p.height,bytes)
            }
            if(!running(live))fail(StickerStoreException.Reason.CANCELED)
            checkRoot();return StoredStickerPack(id,record.archiveSha,inspected,archive,previews)
        }
    }
    fun commit(prepared:PreparedStickerPack,control:StickerCommitControl=StickerCommitControl(),live:()->Boolean={true}):StickerCommitReceipt {
        var renameAttempted=false
        // A rejected reused invocation cannot establish whether the original is still
        // in flight, failed, or published. Reconcile this exact ID; never retry blindly.
        if(!control.start())throw StickerStoreException(StickerStoreException.Reason.STATE,control.transactionId,control.commitId,StickerStoreException.Publication.UNCONFIRMED)
        try{
            checkCancel(control,live)
            val descriptor=terminal(prepared,control.commitId)
            val payloadBytes=prepared.archiveByteCount.toLong()+prepared.previewDescriptors.sumOf{it.byteCount.toLong()}+descriptor.size
            if(payloadBytes>MAX_STORE_BYTES)fail(StickerStoreException.Reason.LIMIT)
            return withRoot{root->writerLock(root).use{lockChannel->
                val lock=try{lockChannel.tryLock()}catch(_:OverlappingFileLockException){null}?:fail(StickerStoreException.Reason.BUSY)
                lock.use{
                    val current=inventory(root)
                    if(current.directories.size>=MAX_DIRECTORIES||current.bytes>MAX_STORE_BYTES-payloadBytes)fail(StickerStoreException.Reason.LIMIT)
                    hook(StickerStorePoint.BEFORE_STAGE);checkCancel(control,live);checkRoot()
                    val stage="pending-${control.transactionId}";val target="commit-${control.commitId}"
                    if(attributes(root,stage)!=null||attributes(root,target)!=null)fail(StickerStoreException.Reason.STATE)
                    Files.createDirectory(this.root.resolve(stage));checkRoot();forceDirectory(root)
                    child(root,stage).use{dir->
                        hook(StickerStorePoint.AFTER_STAGE)
                        writeBytes(dir,"archive.zip",prepared.archiveBytes(),control,live);hook(StickerStorePoint.AFTER_ARCHIVE)
                        prepared.previewDescriptors.indices.forEach{i->writeBytes(dir,previewName(i),prepared.previewBytes(i),control,live);hook(StickerStorePoint.AFTER_PREVIEW)}
                        hook(StickerStorePoint.BEFORE_TERMINAL);verifyPayloads(dir,prepared,control,live)
                        writeBytes(dir,"terminal.bin",descriptor,control,live)
                        if(!readBytes(dir,"terminal.bin",MAX_DESCRIPTOR_BYTES){checkCancel(control,live);true}.contentEquals(descriptor))fail(StickerStoreException.Reason.INTEGRITY)
                        forceDirectory(dir)
                        hook(StickerStorePoint.BEFORE_PUBLICATION_CLAIM);checkCancel(control,live);checkRoot()
                        if(!control.claim())fail(StickerStoreException.Reason.CANCELED)
                        // No live/cancel checks after this CAS: cancellation lost
                        // publication admission and cannot promise a rollback.
                        hook(StickerStorePoint.AFTER_PUBLICATION_CLAIM);hook(StickerStorePoint.BEFORE_MOVE)
                        checkRoot();if(attributes(root,target)!=null)fail(StickerStoreException.Reason.STATE)
                        control.noteRenameAttempt();renameAttempted=true
                        root.move(name(stage),root,name(target)) // SecureDirectoryStream contract: atomic, no fallback.
                        hook(StickerStorePoint.AFTER_MOVE);forceDirectory(root);hook(StickerStorePoint.AFTER_ROOT_SYNC)
                        hook(StickerStorePoint.BEFORE_READBACK)
                        val stored=readCommit(root,control.commitId,{true})
                        if(stored.archiveSha256!=prepared.archiveSha256||!samePack(stored.inspected,prepared.inspected))fail(StickerStoreException.Reason.INTEGRITY)
                        hook(StickerStorePoint.BEFORE_RETURN);checkRoot();control.finish()
                        StickerCommitReceipt(control.transactionId,control.commitId,prepared.archiveSha256,payloadBytes)
                    }
                }
            }}
        }catch(t:Throwable){
            control.finish()
            val reason=(t as? StickerStoreException)?.reason?:StickerStoreException.Reason.IO
            throw StickerStoreException(reason,control.transactionId,control.commitId,
                if(renameAttempted)StickerStoreException.Publication.UNCONFIRMED else StickerStoreException.Publication.NOT_PUBLISHED)
        }
    }
    fun read(commitId:String,live:()->Boolean={true}):StoredStickerPack=try{
        uuid(commitId);withRoot{root->inventory(root);readCommit(root,commitId,live)}
    }catch(e:StickerStoreException){throw e}catch(_:Throwable){fail(StickerStoreException.Reason.IO)}
    fun scan(live:()->Boolean={true}):List<StickerStoredEntry> = scanLocation(false,live)
    fun scanRemoved(live:()->Boolean={true}):List<StickerStoredEntry> = scanLocation(true,live)
    private fun scanLocation(removed:Boolean,live:()->Boolean):List<StickerStoredEntry> = try{
        val prefix=if(removed)"bin-" else "commit-"
        withRoot{root->
            val names=inventory(root).directories.filter{it.startsWith(prefix)}.sorted()
            names.map{file->
                if(!running(live))fail(StickerStoreException.Reason.CANCELED)
                val id=file.removePrefix(prefix)
                try{val stored=readCommit(root,id,live,removed);StickerStoredEntry(id,true,stored.inspected.identity,stored.archiveSha256,null)}
                catch(e:StickerStoreException){if(e.reason==StickerStoreException.Reason.CANCELED)throw e;StickerStoredEntry(id,false,null,null,e.reason)}
                catch(_:Throwable){StickerStoredEntry(id,false,null,null,StickerStoreException.Reason.IO)}
            }
        }
    }catch(e:StickerStoreException){throw e}catch(_:Throwable){fail(StickerStoreException.Reason.IO)}
    /** Reversible same-root relocation. Bin contents remain in the SAME inventory
     * and quota; no archive or preview is rewritten, evicted or permanently deleted.
     * A thrown result after the kernel attempt is explicitly uncertain: inspect
     * active/bin lists before a new deliberate action, never replay automatically.
     */
    fun relocate(commitId:String,toBin:Boolean,control:StickerCommitControl=StickerCommitControl(),live:()->Boolean={true}):StoredStickerPack {
        var attempted=false
        if(!control.start())throw StickerStoreException(StickerStoreException.Reason.STATE,control.transactionId,commitId,StickerStoreException.Publication.UNCONFIRMED)
        try{
            uuid(commitId);checkCancel(control,live)
            return withRoot{root->writerLock(root).use{lockChannel->
                val lock=try{lockChannel.tryLock()}catch(_:OverlappingFileLockException){null}?:fail(StickerStoreException.Reason.BUSY)
                lock.use{
                    inventory(root) // Counts active + removed + interrupted directories and bytes.
                    val source="${if(toBin)"commit" else "bin"}-$commitId"
                    val target="${if(toBin)"bin" else "commit"}-$commitId"
                    if(attributes(root,target)!=null)fail(StickerStoreException.Reason.STATE)
                    val before=readCommit(root,commitId,{checkCancel(control,live);true},!toBin)
                    checkRoot();checkCancel(control,live)
                    if(!control.claim())fail(StickerStoreException.Reason.CANCELED)
                    attempted=true
                    moveNoReplace(this.root,rootKey,source,target) // Verified root-bound dirfd NOREPLACE; no path fallback.
                    forceDirectory(root);checkRoot()
                    val after=readCommit(root,commitId,{true},toBin)
                    if(after.archiveSha256!=before.archiveSha256)fail(StickerStoreException.Reason.INTEGRITY)
                    control.finish();after
                }
            }}
        }catch(t:Throwable){
            control.finish()
            throw StickerStoreException((t as? StickerStoreException)?.reason?:StickerStoreException.Reason.IO,
                control.transactionId,commitId,if(attempted)StickerStoreException.Publication.UNCONFIRMED else StickerStoreException.Publication.NOT_PUBLISHED)
        }
    }

}
