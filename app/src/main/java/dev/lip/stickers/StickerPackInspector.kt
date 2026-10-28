package dev.lip.stickers

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Collections

class StickerPackException(val reason:Reason):IllegalArgumentException("Sticker pack refused: ${reason.name}.") {
    enum class Reason{JSON,SCHEMA,VERSION,REFERENCE,INTEGRITY,LIMIT,IMAGE,CANCELED}
}

data class StickerPackIdentity(val id:String,val name:String,val author:String)
/** Statements supplied by a pack author are not verified rights or permissions. */
class StickerProvenanceAssertion internal constructor(val sourceName:String,val sourceRevision:String,val rightsholder:String,val license:String,val sourceFormat:String,val sourceManifest:StickerArchiveMember?,notices:List<StickerArchiveMember>){
    val notices:List<StickerArchiveMember> = Collections.unmodifiableList(ArrayList(notices))
}
class InspectedStickerImage internal constructor(val member:StickerArchiveMember,val metadata:StickerImageMetadata)
class InspectedSticker internal constructor(val id:String,val name:String,val alt:String,emoji:List<String>,val member:StickerArchiveMember,val metadata:StickerImageMetadata){
    val emojiAliases:List<String> = Collections.unmodifiableList(ArrayList(emoji))
}
class InspectedStickerPack internal constructor(val identity:StickerPackIdentity,val provenance:StickerProvenanceAssertion,val manifest:StickerArchiveMember,val trayImage:InspectedStickerImage?,stickers:List<InspectedSticker>,val canvasPixelWork:Long){
    enum class Stage{METADATA_ONLY}
    enum class Rights{UNVERIFIED}
    val stage=Stage.METADATA_ONLY
    val rights=Rights.UNVERIFIED
    val stickers:List<InspectedSticker> = Collections.unmodifiableList(ArrayList(stickers))
}

/** No files, decode, activation, permission grants, external references or rights authorization. */
object StickerPackInspector {
    const val MAX_STICKERS=120
    const val MAX_PACK_PIXEL_WORK=536_870_912L
    private val identifier=Regex("[a-z0-9](?:[a-z0-9_-]{0,62}[a-z0-9])?")
    private val sha=Regex("[a-f0-9]{64}")
    private fun refuse(reason:StickerPackException.Reason):Nothing=throw StickerPackException(reason)
    private fun checkLive(live:()->Boolean){if(!live())refuse(StickerPackException.Reason.CANCELED)}
    private fun obj(value:Any?,keys:Set<String>):Map<String,Any?>{
        if(value !is Map<*,*>||value.keys!=keys)refuse(StickerPackException.Reason.SCHEMA)
        @Suppress("UNCHECKED_CAST") return value as Map<String,Any?>
    }
    private fun array(value:Any?,range:IntRange):List<*>{
        if(value !is List<*>)refuse(StickerPackException.Reason.SCHEMA)
        if(value.size !in range)refuse(StickerPackException.Reason.LIMIT)
        return value
    }
    private fun unsafe(c:Char,notice:Boolean=false)=c in '\u007f'..'\u009f'||c=='\u061c'||c in '\u200e'..'\u200f'||c in '\u202a'..'\u202e'||c in '\u2066'..'\u2069'||(c<' '&&(!notice||c !in "\t\r\n"))
    private fun label(value:Any?,max:Int,empty:Boolean=false):String{
        val s=value as? String?:refuse(StickerPackException.Reason.SCHEMA)
        val n=s.codePointCount(0,s.length)
        if(n !in (if(empty)0 else 1)..max)refuse(StickerPackException.Reason.LIMIT)
        if(s.any{unsafe(it)}||(!empty&&s.isBlank()))refuse(StickerPackException.Reason.SCHEMA)
        return s
    }
    private fun id(value:Any?):String=label(value,64).also{if(!identifier.matches(it))refuse(StickerPackException.Reason.SCHEMA)}
    private fun reference(record:Map<String,Any?>,files:Map<String,StickerArchiveMember>,used:MutableSet<String>,prefix:String,suffix:Set<String>):StickerArchiveMember{
        val path=record["file"] as? String?:refuse(StickerPackException.Reason.SCHEMA)
        if(!path.startsWith(prefix)||path.substringAfterLast('.',"") !in suffix)refuse(StickerPackException.Reason.REFERENCE)
        val member=files[path]?:refuse(StickerPackException.Reason.REFERENCE)
        if(!used.add(path))refuse(StickerPackException.Reason.REFERENCE)
        val bytes=record["bytes"] as? Long?:refuse(StickerPackException.Reason.SCHEMA)
        val hash=record["sha256"] as? String?:refuse(StickerPackException.Reason.SCHEMA)
        if(!sha.matches(hash)||bytes!=member.size.toLong()||hash!=member.sha256)refuse(StickerPackException.Reason.INTEGRITY)
        return member
    }
    fun inspect(source:InputStream,live:()->Boolean={true}):InspectedStickerPack {
        checkLive(live)
        val members=StickerArchiveReader.read(source,live);val files=members.associateBy{it.path}
        val manifest=files["manifest.json"]?:refuse(StickerPackException.Reason.REFERENCE)
        val root=obj(StickerManifestJson.parse(manifest.bytes(),live),setOf("schema","version","pack","provenance","stickers"))
        if(root["schema"]!="dev.lip.sticker-pack"||root["version"]!=1L)refuse(StickerPackException.Reason.VERSION)
        val pack=obj(root["pack"],setOf("id","name","author","trayImage"))
        val identity=StickerPackIdentity(id(pack["id"]),label(pack["name"],128),label(pack["author"],128))
        val declared=obj(root["provenance"],setOf("sourceName","sourceRevision","rightsholder","license","notices","sourceFormat","sourceManifest"))
        val sourceName=label(declared["sourceName"],256);val revision=label(declared["sourceRevision"],128,true)
        val rightsholder=label(declared["rightsholder"],128);val license=label(declared["license"],128)
        val sourceFormat=id(declared["sourceFormat"])
        val used=hashSetOf("manifest.json")
        fun textMember(value:Any?,prefix:String,suffix:Set<String>):StickerArchiveMember {
            val item=obj(value,setOf("file","sha256","bytes"));val member=reference(item,files,used,prefix,suffix)
            if(member.size !in 1..65_536)refuse(StickerPackException.Reason.LIMIT)
            val text=try{Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(member.bytes())).toString()}
                catch(_:java.nio.charset.CharacterCodingException){refuse(StickerPackException.Reason.SCHEMA)}
            if(text.any{unsafe(it,true)})refuse(StickerPackException.Reason.SCHEMA)
            return member
        }
        val sourceManifest=declared["sourceManifest"]?.let{textMember(it,"source/",setOf("json","txt"))}
        val notices=array(declared["notices"],0..4).map{v->
            checkLive(live);textMember(v,"rights/",setOf("txt"))
        }
        var work=0L;val ids=HashSet<String>()
        fun probe(member:StickerArchiveMember,mime:Any?):StickerImageMetadata{
            if(mime !is String||mime !in setOf("image/png","image/webp")||member.path.substringAfterLast('.')!=mime.substringAfter('/'))refuse(StickerPackException.Reason.INTEGRITY)
            val metadata=try{StickerImageProbe.inspect(member.bytes(),live)}catch(e:StickerImageException){
                refuse(if(e.reason==StickerImageException.Reason.CANCELED)StickerPackException.Reason.CANCELED else StickerPackException.Reason.IMAGE)
            }
            if(metadata.mime!=mime)refuse(StickerPackException.Reason.INTEGRITY)
            if(metadata.canvasPixelWork<=0||metadata.canvasPixelWork>MAX_PACK_PIXEL_WORK-work)refuse(StickerPackException.Reason.LIMIT)
            work+=metadata.canvasPixelWork
            return metadata
        }
        val tray=pack["trayImage"]?.let{v->
            checkLive(live);val item=obj(v,setOf("file","mime","sha256","bytes"));val member=reference(item,files,used,"tray/",setOf("png","webp"))
            val metadata=probe(member,item["mime"]);if(metadata.animated||metadata.frames!=1)refuse(StickerPackException.Reason.IMAGE)
            InspectedStickerImage(member,metadata)
        }
        val stickers=array(root["stickers"],1..MAX_STICKERS).map{v->
            checkLive(live);val item=obj(v,setOf("id","name","file","mime","sha256","bytes","emoji","alt"))
            val itemId=id(item["id"]);if(!ids.add(itemId))refuse(StickerPackException.Reason.REFERENCE)
            val name=label(item["name"],120);val alt=label(item["alt"],240)
            val aliases=array(item["emoji"],0..8).map{a->label(a,32).also{if(it.toByteArray(Charsets.UTF_8).size>128)refuse(StickerPackException.Reason.LIMIT)}}
            if(aliases.distinct().size!=aliases.size)refuse(StickerPackException.Reason.SCHEMA)
            val member=reference(item,files,used,"stickers/",setOf("png","webp"))
            val metadata=probe(member,item["mime"])
            InspectedSticker(itemId,name,alt,aliases,member,metadata)
        }
        if(used!=files.keys)refuse(StickerPackException.Reason.REFERENCE)
        checkLive(live)
        return InspectedStickerPack(identity,StickerProvenanceAssertion(sourceName,revision,rightsholder,license,sourceFormat,sourceManifest,notices),manifest,tray,stickers,work)
    }
}
