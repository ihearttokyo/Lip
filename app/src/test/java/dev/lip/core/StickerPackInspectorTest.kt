package dev.lip.core

import dev.lip.stickers.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StickerPackInspectorTest{
    private fun hash(b:ByteArray)=MessageDigest.getInstance("SHA-256").digest(b).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun be(v:Int)=byteArrayOf((v ushr 24).toByte(),(v ushr 16).toByte(),(v ushr 8).toByte(),v.toByte())
    private fun chunk(name:String,payload:ByteArray):ByteArray{
        val type=name.toByteArray();val crc=CRC32().apply{update(type);update(payload)}.value
        return be(payload.size)+type+payload+be(crc.toInt())
    }
    /** Real tiny RGBA PNG; large-header variant is explicitly metadata-only, NOT decode proof. */
    private fun png(width:Int=2,height:Int=2):ByteArray{
        val pixels=ByteArrayOutputStream().also{out->DeflaterOutputStream(out).use{it.write(byteArrayOf(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0))}}.toByteArray()
        return byteArrayOf(137.toByte(),80,78,71,13,10,26,10)+chunk("IHDR",be(width)+be(height)+byteArrayOf(8,6,0,0,0))+chunk("IDAT",pixels)+chunk("IEND",byteArrayOf())
    }
    private val image=png()
    private val notice="Synthetic original fixture notice.\r\n\tNo third-party assets.\n".toByteArray()
    private fun sticker(id:String="face",file:String="stickers/face.png",bytes:ByteArray=image)=JSONObject()
        .put("id",id).put("name","Original face").put("file",file).put("mime","image/png").put("sha256",hash(bytes)).put("bytes",bytes.size)
        .put("emoji",JSONArray().put("😀")).put("alt","Original synthetic transparent face")
    private fun manifest()=JSONObject().put("schema","dev.lip.sticker-pack").put("version",1)
        .put("pack",JSONObject().put("id","fixture-pack").put("name","Original fixtures").put("author","Synthetic author").put("trayImage",JSONObject.NULL))
        .put("provenance",JSONObject().put("sourceName","Generated test fixture").put("sourceRevision","").put("rightsholder","Fixture author").put("license","UNKNOWN").put("sourceFormat","lip-native").put("sourceManifest",JSONObject.NULL)
            .put("notices",JSONArray().put(JSONObject().put("file","rights/NOTICE.txt").put("sha256",hash(notice)).put("bytes",notice.size))))
        .put("stickers",JSONArray().put(sticker()))
    private fun archive(m:ByteArray=manifest().toString().toByteArray(),files:Map<String,ByteArray> =linkedMapOf("stickers/face.png" to image,"rights/NOTICE.txt" to notice)):ByteArray{
        val output=ByteArrayOutputStream();ZipOutputStream(output).use{zip->
            for((path,b) in linkedMapOf("manifest.json" to m)+files){val entry=ZipEntry(path);entry.time=1_700_000_000_000L;zip.putNextEntry(entry);zip.write(b);zip.closeEntry()}
        };return output.toByteArray()
    }
    private fun inspect(m:JSONObject=manifest(),files:Map<String,ByteArray> =linkedMapOf("stickers/face.png" to image,"rights/NOTICE.txt" to notice))=StickerPackInspector.inspect(ByteArrayInputStream(archive(m.toString().toByteArray(),files)))
    private fun reject(m:JSONObject,reason:StickerPackException.Reason?=null,files:Map<String,ByteArray> =linkedMapOf("stickers/face.png" to image,"rights/NOTICE.txt" to notice)){
        val e=assertThrows(StickerPackException::class.java){inspect(m,files)};if(reason!=null)assertEquals(reason,e.reason);assertFalse(e.message!!.contains("private"))
    }
    @Test fun canonicalPackPreservesOriginalBytesAndUnverifiedStage(){
        val m=manifest();val p=inspect(m)
        assertEquals("fixture-pack",p.identity.id);assertEquals(InspectedStickerPack.Stage.METADATA_ONLY,p.stage);assertEquals(InspectedStickerPack.Rights.UNVERIFIED,p.rights)
        assertEquals(4L,p.canvasPixelWork);assertEquals(2,p.stickers.single().metadata.width);assertArrayEquals(image,p.stickers.single().member.bytes());assertArrayEquals(notice,p.provenance.notices.single().bytes());assertArrayEquals(m.toString().toByteArray(),p.manifest.bytes())
        p.stickers.single().member.bytes().fill(0);assertArrayEquals(image,p.stickers.single().member.bytes())
    }
    @Test fun noOutputListOrByteMutability(){
        val p=inspect();assertThrows(UnsupportedOperationException::class.java){(p.stickers as MutableList<*>).clear()};assertThrows(UnsupportedOperationException::class.java){(p.provenance.notices as MutableList<*>).clear()};assertThrows(UnsupportedOperationException::class.java){(p.stickers[0].emojiAliases as MutableList<*>).clear()}
    }
    @Test fun declaredLicenseCannotAuthorizeRights(){
        for(license in listOf("MIT","CC-BY-4.0","UNKNOWN","I claim private ownership"))assertEquals(InspectedStickerPack.Rights.UNVERIFIED,inspect(manifest().also{it.getJSONObject("provenance").put("license",license)}).rights)
        reject(manifest().also{it.getJSONObject("provenance").put("authorized",true)},StickerPackException.Reason.SCHEMA)
    }
    @Test fun exactFieldsTypesAndVersion(){
        reject(manifest().put("version",2),StickerPackException.Reason.VERSION);reject(manifest().put("version","1"));reject(manifest().put("schema","foreign-pack"))
        for(key in listOf("schema","version","pack","provenance","stickers"))reject(manifest().also{it.remove(key)})
        reject(manifest().put("private-extra",true),StickerPackException.Reason.SCHEMA)
        reject(manifest().also{it.getJSONObject("pack").put("private-extra",1)})
        reject(manifest().also{it.getJSONArray("stickers").getJSONObject(0).put("private-extra",1)})
    }
    @Test fun duplicateEscapedManifestKeysRefusedThroughArchive(){
        val text=manifest().toString().replace("\"version\":1","\"version\":1,\"versi\\u006fn\":1")
        assertEquals(StickerPackException.Reason.JSON,assertThrows(StickerPackException::class.java){StickerPackInspector.inspect(ByteArrayInputStream(archive(text.toByteArray())))}.reason)
    }
    @Test fun displayLabelsRejectControlsBidiAndOverlongScalars(){
        for(value in listOf("", " ","private\u0000name","private\nname","\u202eprivate","\u2066private","x".repeat(129)))reject(manifest().also{it.getJSONObject("pack").put("name",value)})
        val m=manifest().also{it.getJSONObject("pack").put("name","😀".repeat(128))};assertEquals(256,inspect(m).identity.name.length)
        for(value in listOf("PRIVATE","-bad","bad-","bad/path",".","id."))reject(manifest().also{it.getJSONObject("pack").put("id",value)})
    }
    @Test fun referencesMustBeSafeActualUniqueAndNoOrphans(){
        for(path in listOf("https://private/x.png","file:///private.png","rights/NOTICE.txt","stickers/missing.png"))reject(manifest().also{it.getJSONArray("stickers").getJSONObject(0).put("file",path)},StickerPackException.Reason.REFERENCE)
        reject(manifest(),StickerPackException.Reason.REFERENCE,linkedMapOf("stickers/face.png" to image,"rights/NOTICE.txt" to notice,"orphan.txt" to byteArrayOf(1)))
        reject(manifest().also{it.getJSONArray("stickers").put(sticker("other"))},StickerPackException.Reason.REFERENCE)
    }
    @Test fun forgedHashLengthMimeAndExtensionRefused(){
        for((key,value) in listOf("sha256" to "0".repeat(64),"sha256" to hash(image).uppercase(),"bytes" to image.size+1,"bytes" to "100","mime" to "image/webp","mime" to "application/private"))reject(manifest().also{it.getJSONArray("stickers").getJSONObject(0).put(key,value)})
        val wrong=sticker(file="stickers/face.webp").put("mime","image/webp")
        reject(manifest().put("stickers",JSONArray().put(wrong)),files=linkedMapOf("stickers/face.webp" to image,"rights/NOTICE.txt" to notice))
    }
    @Test fun duplicateIdsAliasesAndItemCountBounds(){
        reject(manifest().put("stickers",JSONArray()),StickerPackException.Reason.LIMIT)
        reject(manifest().put("stickers",JSONArray().put(sticker()).put(sticker(file="stickers/b.png"))),StickerPackException.Reason.REFERENCE,linkedMapOf("stickers/face.png" to image,"stickers/b.png" to image,"rights/NOTICE.txt" to notice))
        reject(manifest().also{it.getJSONArray("stickers").getJSONObject(0).put("emoji",JSONArray().put("😀").put("😀"))},StickerPackException.Reason.SCHEMA)
        reject(manifest().also{it.getJSONArray("stickers").getJSONObject(0).put("emoji",JSONArray(List(9){"x$it"}))},StickerPackException.Reason.LIMIT)
    }
    @Test fun noticeBytesUtf8ControlsAndHashAreChecked(){
        for(bytes in listOf(byteArrayOf(0xff.toByte()),"private\u0000notice".toByteArray(),"private\u202enotice".toByteArray())){
            val m=manifest().also{it.getJSONObject("provenance").getJSONArray("notices").getJSONObject(0).put("bytes",bytes.size).put("sha256",hash(bytes))}
            reject(m,StickerPackException.Reason.SCHEMA,linkedMapOf("stickers/face.png" to image,"rights/NOTICE.txt" to bytes))
        }
        reject(manifest().also{it.getJSONObject("provenance").getJSONArray("notices").getJSONObject(0).put("sha256","0".repeat(64))},StickerPackException.Reason.INTEGRITY)
        val m=manifest().also{it.getJSONObject("provenance").put("notices",JSONArray())};assertEquals(0,inspect(m,mapOf("stickers/face.png" to image)).provenance.notices.size)
    }
    @Test fun imageContainerFailureIsStaticAndNotDecodeAcceptance(){
        val bad=image.copyOf().also{it[20]=(it[20].toInt() xor 1).toByte()};val m=manifest().put("stickers",JSONArray().put(sticker(bytes=bad)))
        reject(m,StickerPackException.Reason.IMAGE,linkedMapOf("stickers/face.png" to bad,"rights/NOTICE.txt" to notice))
    }
    @Test fun aggregatePixelWorkIsBoundedBeforeAnyDecode(){
        val large=png(4096,4096);val m=manifest();val items=JSONArray();val files=linkedMapOf("rights/NOTICE.txt" to notice)
        repeat(33){i->val path="stickers/face$i.png";files[path]=large;items.put(sticker("id$i",path,large))};m.put("stickers",items)
        reject(m,StickerPackException.Reason.LIMIT,files)
    }
    @Test fun staticTrayAndOpaqueSourceMetadataPreserveBytes(){
        val original="{\"source_only\":\"https://example.invalid/private\"}".toByteArray()
        val m=manifest().also{
            it.getJSONObject("pack").put("trayImage",JSONObject().put("file","tray/cover.png").put("mime","image/png").put("sha256",hash(image)).put("bytes",image.size))
            it.getJSONObject("provenance").put("sourceFormat","whatsapp-contents-json").put("sourceManifest",JSONObject().put("file","source/contents.json").put("sha256",hash(original)).put("bytes",original.size))
        }
        val p=inspect(m,linkedMapOf("stickers/face.png" to image,"rights/NOTICE.txt" to notice,"tray/cover.png" to image,"source/contents.json" to original))
        assertArrayEquals(image,p.trayImage!!.member.bytes());assertEquals(2,p.trayImage!!.metadata.width);assertArrayEquals(original,p.provenance.sourceManifest!!.bytes())
        assertEquals(InspectedStickerPack.Rights.UNVERIFIED,p.rights);assertEquals(8L,p.canvasPixelWork)
        reject(m,StickerPackException.Reason.REFERENCE)
    }
    @Test fun callerSourceRemainsOpenAndEarlyCancellationWritesNothing(){
        var closed=false;val source=object:ByteArrayInputStream(archive()){override fun close(){closed=true;super.close()}}
        StickerPackInspector.inspect(source);assertFalse(closed)
        assertEquals(StickerPackException.Reason.CANCELED,assertThrows(StickerPackException::class.java){StickerPackInspector.inspect(ByteArrayInputStream(archive())){false}}.reason)
    }
}
