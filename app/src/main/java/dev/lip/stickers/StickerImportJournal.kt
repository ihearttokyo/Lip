package dev.lip.stickers

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** One application process-wide admission, held through commit/draining and reconciliation. */
internal object StickerImportAdmission {
    private val active=AtomicBoolean(false)
    fun isActive():Boolean=active.get()
    fun claim():Boolean=active.compareAndSet(false,true)
    fun release(){check(active.compareAndSet(true,false))}
}

/** Bounded append-only UUID records, outside the managed pack root. Never restores authorization. */
internal class StickerImportJournal(private val root:Path) {
    enum class Outcome { PUBLISHED, ABSENT }
    private val rootKey:Any
    private val idPattern="[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"
    private val recordPattern=Regex("(intent|published|absent)-($idPattern)")
    init {
        val a=Files.readAttributes(root,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
        check(a.isDirectory&&!a.isSymbolicLink){"Sticker journal unavailable."}
        rootKey=a.fileKey()?:error("Sticker journal unavailable.")
    }
    private fun checkRoot(){
        val a=Files.readAttributes(root,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
        check(a.isDirectory&&!a.isSymbolicLink&&a.fileKey()==rootKey){"Sticker journal changed."}
    }
    private fun <T> withRoot(action:(SecureDirectoryStream<Path>)->T):T {
        checkRoot()
        Files.newDirectoryStream(root).use{stream->
            check(stream is SecureDirectoryStream<*>){"Secure sticker journal unavailable."}
            @Suppress("UNCHECKED_CAST") val secure=stream as SecureDirectoryStream<Path>
            val a=secure.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes()
            check(a.fileKey()==rootKey&&a.isDirectory&&!a.isSymbolicLink){"Sticker journal changed."}
            checkRoot();return action(secure).also{checkRoot()}
        }
    }
    private fun name(value:String):Path=root.fileSystem.getPath(value)
    private fun channel(dir:SecureDirectoryStream<Path>,file:String,options:Set<OpenOption>):FileChannel {
        val result=dir.newByteChannel(name(file),options)
        if(result !is FileChannel){result.close();error("Secure sticker journal unavailable.")}
        return result
    }
    private fun payload(id:String):ByteArray {
        check(Regex(idPattern).matches(id)&&UUID.fromString(id).toString()==id){"Invalid sticker transaction."}
        return "lip-sticker-import-v1\n$id\n".toByteArray(Charsets.US_ASCII)
    }
    private data class Records(val intents:Set<String>,val settled:Map<String,Outcome>,val count:Int)
    private fun records(dir:SecureDirectoryStream<Path>):Records {
        val intents=linkedSetOf<String>();val settled=linkedMapOf<String,Outcome>();var count=0
        for(path in dir){
            check(++count<=128){"Sticker journal limit reached."}
            val file=path.fileName.toString()
            val match=recordPattern.matchEntire(file)?:error("Sticker journal needs reconciliation.")
            val kind=match.groupValues[1];val id=match.groupValues[2];val expected=payload(id)
            fun attributes()=dir.getFileAttributeView(name(file),BasicFileAttributeView::class.java,LinkOption.NOFOLLOW_LINKS).readAttributes()
            val before=attributes()
            check(before.isRegularFile&&!before.isSymbolicLink&&before.size()==expected.size.toLong()){"Sticker journal needs reconciliation."}
            val bytes=ByteArray(expected.size)
            channel(dir,file,setOf(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)).use{input->
                check(input.size()==bytes.size.toLong()){"Sticker journal changed."};val buffer=ByteBuffer.wrap(bytes)
                while(buffer.hasRemaining())check(input.read(buffer)>0){"Sticker journal incomplete."}
                check(input.read(ByteBuffer.allocate(1))==-1){"Sticker journal changed."}
            }
            val after=attributes()
            check(after.fileKey()==before.fileKey()&&after.size()==before.size()&&bytes.contentEquals(expected)){"Sticker journal needs reconciliation."}
            if(kind=="intent")check(intents.add(id)) else {
                val outcome=if(kind=="published")Outcome.PUBLISHED else Outcome.ABSENT
                check(settled.put(id,outcome)==null){"Sticker journal has conflicting outcomes."}
            }
        }
        check(settled.keys.all{it in intents}){"Sticker journal has an orphan outcome."}
        return Records(intents,settled,count)
    }
    fun unresolved():List<String> = withRoot{dir->records(dir).let{r->r.intents.filter{it !in r.settled}.sorted()}}
    fun begin(id:String)=withRoot{dir->
        val r=records(dir)
        check(r.intents.none{it !in r.settled}){"Reconcile the previous import first."}
        // Reserve both immutable intent and outcome records. Retain all prior records.
        check(r.count<=126&&id !in r.intents){"Sticker journal limit reached."}
        append(dir,"intent-$id",payload(id))
    }
    fun settle(id:String,outcome:Outcome)=withRoot{dir->
        val r=records(dir);check(id in r.intents){"Sticker import intent unavailable."}
        val previous=r.settled[id]
        if(previous!=null){check(previous==outcome){"Sticker outcome conflict."}} else {
            check(r.count<128){"Sticker journal limit reached."}
            append(dir,"${if(outcome==Outcome.PUBLISHED)"published" else "absent"}-$id",payload(id))
        }
    }
    private fun append(dir:SecureDirectoryStream<Path>,file:String,bytes:ByteArray){
        checkRoot()
        channel(dir,file,setOf(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)).use{output->
            val data=ByteBuffer.wrap(bytes);while(data.hasRemaining())check(output.write(data)>0){"Sticker journal write failed."}
            output.force(true)
        }
        channel(dir,".",setOf(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)).use{it.force(true)}
        checkRoot()
    }
}
