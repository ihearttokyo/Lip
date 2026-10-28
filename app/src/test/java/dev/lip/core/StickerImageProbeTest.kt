package dev.lip.core

import dev.lip.stickers.StickerImageException
import dev.lip.stickers.StickerImageMetadata
import dev.lip.stickers.StickerImageProbe as Probe
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** Original synthetic containers. VP8/VP8L fixtures intentionally do not establish codec validity. */
class StickerImageProbeTest {
    private fun put(b:ByteArray,at:Int,n:Long,count:Int=4,big:Boolean=false){
        repeat(count){b[at+it]=(n ushr (8*(if(big)count-1-it else it))).toByte()}
    }
    private fun join(vararg values:ByteArray):ByteArray=ByteArrayOutputStream().apply{values.forEach{write(it)}}.toByteArray()
    private fun pngChunk(type:String,data:ByteArray=byteArrayOf()):ByteArray {
        val result=ByteArray(data.size+12);put(result,0,data.size.toLong(),big=true)
        type.toByteArray(Charsets.US_ASCII).copyInto(result,4);data.copyInto(result,8)
        put(result,result.size-4,CRC32().apply{update(result,4,data.size+4)}.value,big=true)
        return result
    }
    private fun header(w:Long=1,h:Long=1,depth:Int=8,color:Int=6,compression:Int=0,filter:Int=0,interlace:Int=0):ByteArray=
        pngChunk("IHDR",ByteArray(13).apply{
            put(this,0,w,big=true);put(this,4,h,big=true);this[8]=depth.toByte();this[9]=color.toByte()
            this[10]=compression.toByte();this[11]=filter.toByte();this[12]=interlace.toByte()
        })
    private fun idat():ByteArray=pngChunk("IDAT",ByteArrayOutputStream().apply{
        DeflaterOutputStream(this).use{it.write(byteArrayOf(0,20,40,60,(-1).toByte()))}
    }.toByteArray())
    private fun png(vararg chunks:ByteArray):ByteArray=join(byteArrayOf(0x89.toByte(),80,78,71,13,10,26,10),*chunks)
    private fun tinyPng():ByteArray=png(header(),idat(),pngChunk("IEND"))
    private fun webpChunk(type:String,data:ByteArray,pad:Int=0):ByteArray=ByteArray(8+data.size+(data.size and 1)).apply{
        type.toByteArray(Charsets.US_ASCII).copyInto(this);put(this,4,data.size.toLong());data.copyInto(this,8)
        if(data.size and 1!=0)this[lastIndex]=pad.toByte()
    }
    private fun riff(vararg chunks:ByteArray):ByteArray {
        val body=join("WEBP".toByteArray(),*chunks)
        return join("RIFF".toByteArray(),ByteArray(4).apply{put(this,0,body.size.toLong())},body)
    }
    private fun lossless(w:Int=1,h:Int=1,hint:Boolean=false,version:Int=0):ByteArray=webpChunk("VP8L",ByteArray(5).apply{
        this[0]=0x2f;put(this,1,(w-1).toLong() or ((h-1).toLong() shl 14) or
            (if(hint)1L shl 28 else 0) or (version.toLong() shl 29))
    })
    private fun lossy(w:Int=1,h:Int=1,tag:Int=16):ByteArray=webpChunk("VP8 ",ByteArray(10).apply{
        put(this,0,tag.toLong(),3);this[3]=0x9d.toByte();this[4]=1;this[5]=0x2a
        put(this,6,w.toLong(),2);put(this,8,h.toLong(),2)
    })
    private fun extended(w:Int=1,h:Int=1,flags:Int=0):ByteArray=webpChunk("VP8X",ByteArray(10).apply{
        this[0]=flags.toByte();put(this,4,(w-1).toLong(),3);put(this,7,(h-1).toLong(),3)
    })
    private fun control():ByteArray=webpChunk("ANIM",ByteArray(6))
    private fun frame(w:Int=1,h:Int=1,xRaw:Int=0,yRaw:Int=0,time:Int=10,flags:Int=0,data:ByteArray=lossless(w,h)):ByteArray=
        webpChunk("ANMF",join(ByteArray(16).apply{
            put(this,0,xRaw.toLong(),3);put(this,3,yRaw.toLong(),3);put(this,6,(w-1).toLong(),3)
            put(this,9,(h-1).toLong(),3);put(this,12,time.toLong(),3);this[15]=flags.toByte()
        },data))
    private fun animated(w:Int=1,h:Int=1,count:Int=2,time:Int=10,frameWidth:Int=w,frameHeight:Int=h):ByteArray=
        riff(extended(w,h,2),control(),*Array(count){frame(frameWidth,frameHeight,time=time)})
    private fun refuse(bytes:ByteArray,reason:StickerImageException.Reason?=null){
        try{Probe.inspect(bytes);fail("Untrusted container was accepted")}
        catch(e:StickerImageException){
            if(reason!=null)assertEquals(reason,e.reason)
            assertEquals("Sticker image refused: ${e.reason.name}.",e.message)
            assertNull(e.cause)
        }
    }
    private val format=StickerImageException.Reason.FORMAT
    private val integrity=StickerImageException.Reason.INTEGRITY
    private val limit=StickerImageException.Reason.LIMIT

    @Test fun pngReturnsImmutableNumericMetadataWithoutChangingInput(){
        val bytes=tinyPng();val original=bytes.copyOf()
        assertEquals(StickerImageMetadata("image/png",1,1,1,0,false,1),Probe.inspect(bytes))
        assertArrayEquals(original,bytes)
    }
    @Test fun pngLegalColorDepthPairsAndInterlaceAreObserved(){
        for((color,depths) in listOf(0 to listOf(1,2,4,8,16),2 to listOf(8,16),3 to listOf(1,2,4,8),4 to listOf(8,16),6 to listOf(8,16))){
            for(depth in depths)for(interlace in 0..1){
                val chunks=mutableListOf(header(depth=depth,color=color,interlace=interlace))
                if(color==3)chunks+=pngChunk("PLTE",byteArrayOf(0,0,0))
                chunks+=idat();chunks+=pngChunk("IEND")
                assertEquals("image/png",Probe.inspect(png(*chunks.toTypedArray())).mime)
            }
        }
    }
    @Test fun pngIllegalIhdrCombinationsAndMethodsAreRefused(){
        for(h in listOf(header(depth=4,color=2),header(depth=16,color=3),header(color=1),header(compression=1),header(filter=1),header(interlace=2))){
            refuse(png(h,idat(),pngChunk("IEND")),format)
        }
        refuse(png(header(w=0),idat(),pngChunk("IEND")),format)
    }
    @Test fun pngCriticalOrderConsecutiveDataAndTrailerAreEnforced(){
        for(b in listOf(png(idat(),header(),pngChunk("IEND")),png(header(),header(),idat(),pngChunk("IEND")),
            png(header(),pngChunk("IEND")),png(header(),idat()),png(header(),idat(),pngChunk("tEXt","note\u0000x".toByteArray()),idat(),pngChunk("IEND")),
            tinyPng()+byteArrayOf(0),png(header(),idat(),pngChunk("IEND",byteArrayOf(0))))){refuse(b,format)}
        assertEquals(1,Probe.inspect(png(header(),idat(),pngChunk("IDAT"),pngChunk("IEND"))).frames)
    }
    @Test fun pngCrcAndBoundedChunkSizesAreChecked(){
        refuse(tinyPng().also{it[it.lastIndex]=(it.last().toInt() xor 1).toByte()},integrity)
        refuse(tinyPng().also{put(it,8,0xffffffffL,big=true)},format)
        for(type in listOf("ABCD","text","a0CD"))refuse(png(header(),pngChunk(type),idat(),pngChunk("IEND")),format)
    }
    @Test fun pngUnknownAncillaryIsCrcCheckedButNotInterpreted(){
        val unknown=pngChunk("stUF",byteArrayOf(0,(-1).toByte(),42))
        assertEquals(1,Probe.inspect(png(header(),unknown,idat(),pngChunk("IEND"))).width)
        unknown[unknown.lastIndex]=(unknown.last().toInt() xor 1).toByte()
        refuse(png(header(),unknown,idat(),pngChunk("IEND")),integrity)
    }
    @Test fun pngAnimatedChunksAreNeverFlattened(){
        for(type in listOf("acTL","fcTL","fdAT"))refuse(png(header(),pngChunk(type,ByteArray(8)),idat(),pngChunk("IEND")),format)
    }
    @Test fun pngPaletteAndTransparencyRulesAreChecked(){
        val indexed=header(depth=1,color=3);val palette=pngChunk("PLTE",ByteArray(6))
        assertEquals(1,Probe.inspect(png(indexed,palette,pngChunk("tRNS",byteArrayOf(0,127)),idat(),pngChunk("IEND"))).width)
        for(b in listOf(png(indexed,idat(),pngChunk("IEND")),png(indexed,pngChunk("PLTE",ByteArray(9)),idat(),pngChunk("IEND")),
            png(header(color=0),palette,idat(),pngChunk("IEND")),png(indexed,palette,palette,idat(),pngChunk("IEND")),
            png(indexed,palette,pngChunk("tRNS",ByteArray(3)),idat(),pngChunk("IEND")),
            png(header(),pngChunk("tRNS",ByteArray(2)),idat(),pngChunk("IEND")),
            png(header(color=2),pngChunk("tRNS",ByteArray(6)),palette,idat(),pngChunk("IEND"))))refuse(b,format)
    }
    @Test fun pngAncillaryHeaderOrderAndMultiplicityAreChecked(){
        val gamma=pngChunk("gAMA",ByteArray(4).apply{put(this,0,45455,big=true)})
        val phys=pngChunk("pHYs",ByteArray(9))
        assertEquals(1,Probe.inspect(png(header(),gamma,phys,idat(),pngChunk("tEXt","note\u0000original".toByteArray()),pngChunk("IEND"))).height)
        for(b in listOf(png(header(),gamma,gamma,idat(),pngChunk("IEND")),png(header(),idat(),phys,pngChunk("IEND")),
            png(header(color=2),pngChunk("PLTE",ByteArray(3)),gamma,idat(),pngChunk("IEND")),
            png(header(),pngChunk("sBIT",byteArrayOf(8,8,8)),idat(),pngChunk("IEND")),
            png(header(),pngChunk("sRGB",byteArrayOf(4)),idat(),pngChunk("IEND"))))refuse(b,format)
    }
    @Test fun compressedMetadataIsOnlyHeaderCheckedNotInflated(){
        val opaque=pngChunk("iCCP","original-profile\u0000\u0000not-a-zlib-stream".toByteArray())
        assertEquals(1,Probe.inspect(png(header(),opaque,idat(),pngChunk("IEND"))).width)
        refuse(png(header(),pngChunk("iCCP","bad\u0000\u0001payload".toByteArray()),idat(),pngChunk("IEND")),format)
    }
    @Test fun simpleWebpHeaderDimensionsAreObservedWithoutCodecTrust(){
        for(image in listOf(lossy(2,3),lossless(2,3))){
            val bytes=riff(image);val copy=bytes.copyOf()
            assertEquals(StickerImageMetadata("image/webp",2,3,1,0,false,6),Probe.inspect(bytes));assertArrayEquals(copy,bytes)
        }
        // These tiny VP8/VP8L payloads contain headers only, not valid compressed images.
        // A successful probe explicitly cannot grant preview/installation/decode trust.
    }
    @Test fun webpRiffLengthPaddingAndStrictKnownChunkProfileAreChecked(){
        for(b in listOf(riff(lossless())+byteArrayOf(0),riff(lossless()).also{put(it,4,0xffffffffL)},
            riff(webpChunk("VP8L",byteArrayOf(0x2f,0,0,0,0),1)),riff(lossless(),lossless()),
            riff(extended(),webpChunk("JUNK",byteArrayOf(1)),lossless()),riff()))refuse(b,format)
    }
    @Test fun webpBitstreamHeaderSignaturesVersionsAndPartitionsAreChecked(){
        refuse(riff(lossless(version=1)),format)
        refuse(riff(lossy(tag=17)),format)
        refuse(riff(lossy(tag=0)),format)
        refuse(riff(lossy(tag=16 or (4 shl 1))),format)
        refuse(riff(lossy(tag=16 or (1 shl 5))),format)
        refuse(riff(lossless().also{it[8]=0}),format)
        refuse(riff(lossy().also{it[11]=0}),format)
    }
    @Test fun extendedWebpCanvasAndObservableFeaturesMustAgree(){
        assertEquals(2,Probe.inspect(riff(extended(2,3),lossless(2,3))).width)
        refuse(riff(extended(2,3),lossless(1,3)),integrity)
        refuse(riff(extended(flags=0x80),lossless()),format)
        refuse(riff(extended().also{it[9]=1},lossless()),format)
        refuse(riff(extended(flags=32),lossless()),integrity)
        refuse(riff(extended(),webpChunk("EXIF",byteArrayOf(1)),lossless()),integrity)
        refuse(riff(extended(flags=8),lossless(),webpChunk("EXIF",byteArrayOf(1)),webpChunk("EXIF",byteArrayOf(2))),format)
    }
    @Test fun webpMetadataCanBeOpaqueAndOutOfOrderButIccPrecedesReconstruction(){
        val icc=webpChunk("ICCP",byteArrayOf(1));val exif=webpChunk("EXIF",byteArrayOf(2));val xmp=webpChunk("XMP ",byteArrayOf(3))
        assertEquals(1,Probe.inspect(riff(extended(flags=32 or 8 or 4),exif,icc,lossless(),xmp)).frames)
        refuse(riff(extended(flags=32),lossless(),icc),format)
    }
    @Test fun webpLosslessAlphaHintIsNotUsedAsAnEntitlementOrConsistencyProof(){
        for(hint in listOf(false,true))for(flags in listOf(0,16)){
            assertEquals(1,Probe.inspect(riff(extended(flags=flags),lossless(hint=hint))).frames)
        }
        assertEquals(1,Probe.inspect(riff(lossless(hint=true))).frames)
        refuse(riff(extended(flags=16),lossy()),integrity)
    }
    @Test fun observableLossyAlphaRequiresFlagAndValidHeaderAndRawLength(){
        val raw=webpChunk("ALPH",byteArrayOf(0,127))
        assertEquals(1,Probe.inspect(riff(extended(flags=16),raw,lossy())).width)
        refuse(riff(extended(),raw,lossy()),integrity)
        refuse(riff(extended(flags=16),webpChunk("ALPH",byteArrayOf(0)),lossy()),format)
        refuse(riff(extended(flags=16),webpChunk("ALPH",byteArrayOf(0xc0.toByte(),0)),lossy()),format)
        refuse(riff(extended(flags=16),webpChunk("ALPH",byteArrayOf(2,0)),lossy()),format)
        refuse(riff(extended(flags=16),raw,lossless()),format)
    }
    @Test fun animatedWebpReportsCycleFramesAndCanvasWork(){
        assertEquals(StickerImageMetadata("image/webp",4,4,2,40,true,32),Probe.inspect(animated(4,4,2,20)))
        assertEquals(2,Probe.inspect(riff(extended(4,4,2),control(),frame(2,2,xRaw=1,yRaw=1),frame(1,1))).frames)
        for(flags in 0..3)assertEquals(1,Probe.inspect(riff(extended(flags=2),control(),frame(flags=flags))).frames)
    }
    @Test fun animationControlAndFrameOrderingAreEnforced(){
        for(b in listOf(riff(extended(flags=2),frame()),riff(extended(flags=2),control()),riff(extended(),control(),frame()),
            riff(extended(flags=2),control(),control(),frame()),riff(extended(flags=2),control(),lossless()),
            riff(extended(flags=2),control(),frame(data=join(lossless(),lossless()))),
            riff(extended(flags=2),control(),frame(data=webpChunk("JUNK",byteArrayOf(1))))))refuse(b,format)
    }
    @Test fun animationRectanglesInnerDimensionsAndReservedFlagsAreEnforced(){
        refuse(riff(extended(2,2,2),control(),frame(xRaw=1)),integrity)
        refuse(riff(extended(2,2,2),control(),frame(yRaw=0xffffff)),integrity)
        refuse(riff(extended(2,2,2),control(),frame(2,2,data=lossless(1,2))),integrity)
        refuse(riff(extended(flags=2),control(),frame(flags=4)),format)
    }
    @Test fun imageAxesAndSourceSizeHaveExactBounds(){
        for(dim in listOf(1,4096)){
            assertEquals(dim,Probe.inspect(png(header(w=dim.toLong(),h=dim.toLong()),idat(),pngChunk("IEND"))).width)
            assertEquals(dim,Probe.inspect(riff(lossless(dim,dim))).height)
        }
        for(image in listOf(png(header(w=4097),idat(),pngChunk("IEND")),png(header(h=4097),idat(),pngChunk("IEND")),
            riff(lossless(4097)),riff(lossy(h=4097))))refuse(image,limit)
        refuse(ByteArray(Probe.MAX_SOURCE_BYTES+1),limit)
        val base=tinyPng().size;val padding=ByteArray(Probe.MAX_SOURCE_BYTES-base-12)
        val exact=png(header(),pngChunk("stUF",padding),idat(),pngChunk("IEND"))
        assertEquals(Probe.MAX_SOURCE_BYTES,exact.size);assertEquals(1,Probe.inspect(exact).width)
    }
    @Test fun animationFrameDurationCycleCountAndWorkHaveExactBounds(){
        assertEquals(256,Probe.inspect(animated(count=256)).frames)
        refuse(animated(count=257),limit)
        assertEquals(60_000L,Probe.inspect(animated(count=6,time=10_000)).durationMs)
        refuse(animated(count=7,time=10_000),limit)
        for(time in listOf(0,9,10_001,0xffffff))refuse(animated(count=1,time=time),limit)
        assertEquals(67_108_864L,Probe.inspect(animated(512,512,256,frameWidth=1,frameHeight=1)).canvasPixelWork)
        refuse(animated(513,512,256,frameWidth=1,frameHeight=1),limit)
        assertEquals(67_108_864L,Probe.inspect(animated(4096,4096,4)).canvasPixelWork)
        refuse(animated(4096,4096,5),limit)
    }
    @Test fun everyTruncatedPrefixHasAStaticSafeRefusal(){
        for(bytes in listOf(tinyPng(),riff(lossless()),animated(2,2))){
            for(size in bytes.indices)refuse(bytes.copyOf(size))
        }
    }
    @Test fun cancellationCoversEntryChunksCrcBlocksAndAnimationLoops(){
        for(bytes in listOf(tinyPng(),png(header(),pngChunk("stUF",ByteArray(200_000)),idat(),pngChunk("IEND")),animated(count=20))){
            try{Probe.inspect(bytes){false};fail()}catch(e:StickerImageException){assertEquals(StickerImageException.Reason.CANCELED,e.reason)}
            var checks=0
            try{Probe.inspect(bytes){++checks<4};fail()}catch(e:StickerImageException){assertEquals(StickerImageException.Reason.CANCELED,e.reason)}
        }
        var crcChecks=0
        try{Probe.inspect(png(header(),pngChunk("stUF",ByteArray(200_000)),idat(),pngChunk("IEND"))){++crcChecks<10};fail()}
        catch(e:StickerImageException){assertEquals(StickerImageException.Reason.CANCELED,e.reason);assertEquals(10,crcChecks)}
        try{Probe.inspect(tinyPng()){throw IllegalStateException("private source detail")};fail()}
        catch(e:StickerImageException){assertEquals(StickerImageException.Reason.CANCELED,e.reason);assertFalse(e.message!!.contains("private"));assertNull(e.cause)}
    }
}
