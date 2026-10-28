package dev.lip.stickers

import java.util.zip.CRC32

/** Container/header observations only: compressed pixels and embedded metadata are NOT decoded. */
data class StickerImageMetadata(val mime:String,val width:Int,val height:Int,val frames:Int,
    val durationMs:Long,val animated:Boolean,val canvasPixelWork:Long)

class StickerImageException(val reason:Reason):IllegalArgumentException("Sticker image refused: ${reason.name}.") {
    enum class Reason { FORMAT, LIMIT, INTEGRITY, CANCELED }
}

/**
 * Pure, bounded inspection, never a decoder or an installation/preview permission.
 * PNG3: https://www.w3.org/TR/png-3/
 * WebP: https://developers.google.com/speed/webp/docs/riff_container
 * VP8L: https://developers.google.com/speed/webp/docs/webp_lossless_bitstream_specification
 * VP8 header: https://datatracker.ietf.org/doc/html/rfc6386#section-9.1
 * The caller owns the byte array and must not mutate it during inspection.
 */
object StickerImageProbe {
    const val MAX_SOURCE_BYTES=8*1024*1024
    const val MAX_AXIS=4096
    const val MAX_FRAME_PIXELS=16_777_216L
    const val MAX_FRAMES=256
    const val MAX_CYCLE_MS=60_000L
    const val MIN_FRAME_MS=10
    const val MAX_FRAME_MS=10_000
    const val MAX_CANVAS_PIXEL_WORK=67_108_864L

    private fun refuse(reason:StickerImageException.Reason):Nothing=throw StickerImageException(reason)
    private fun format():Nothing=refuse(StickerImageException.Reason.FORMAT)
    private fun integrity():Nothing=refuse(StickerImageException.Reason.INTEGRITY)
    private fun limit():Nothing=refuse(StickerImageException.Reason.LIMIT)
    private fun checkLive(live:()->Boolean){
        val running=try{live()}catch(_:Exception){false}
        if(!running)refuse(StickerImageException.Reason.CANCELED)
    }

    fun inspect(bytes:ByteArray,live:()->Boolean={true}):StickerImageMetadata {
        checkLive(live)
        if(bytes.size>MAX_SOURCE_BYTES)limit()
        val b=Bytes(bytes)
        val result=when{
            bytes.size>=8&&bytes[0]==0x89.toByte()&&b.tag(1,3)=="PNG"&&
                b.u8(4)==13&&b.u8(5)==10&&b.u8(6)==26&&b.u8(7)==10->png(b,live)
            bytes.size>=12&&b.tag(0)=="RIFF"&&b.tag(8)=="WEBP"->webp(b,live)
            else->format()
        }
        checkLive(live)
        return result
    }

    private class Bytes(val data:ByteArray) {
        val size:Int get()=data.size
        fun span(at:Int,length:Int){if(at<0||length<0||at>size-length)format()}
        fun end(at:Int,length:Long,ceiling:Int=size):Int {
            if(at<0||length<0||ceiling<0||ceiling>size||at.toLong()+length>ceiling.toLong())format()
            return (at.toLong()+length).toInt()
        }
        fun u8(at:Int):Int {span(at,1);return data[at].toInt() and 255}
        fun u16be(at:Int):Int {span(at,2);return (u8(at) shl 8) or u8(at+1)}
        fun u16le(at:Int):Int {span(at,2);return u8(at) or (u8(at+1) shl 8)}
        fun u24le(at:Int):Int {span(at,3);return u8(at) or (u8(at+1) shl 8) or (u8(at+2) shl 16)}
        fun u32le(at:Int):Long {span(at,4);return (0..3).fold(0L){n,i->n or (u8(at+i).toLong() shl (8*i))}}
        fun u32be(at:Int):Long {span(at,4);return (0..3).fold(0L){n,i->(n shl 8) or u8(at+i).toLong()}}
        fun tag(at:Int,length:Int=4):String {span(at,length);return buildString(length){repeat(length){append(u8(at+it).toChar())}}}
    }

    private fun dimensions(width:Long,height:Long):Pair<Int,Int> {
        if(width<=0||height<=0)format()
        if(width>MAX_AXIS||height>MAX_AXIS||width*height>MAX_FRAME_PIXELS)limit()
        return width.toInt() to height.toInt()
    }
    private fun metadata(mime:String,width:Int,height:Int,frames:Int,duration:Long,animated:Boolean):StickerImageMetadata {
        if(frames !in 1..MAX_FRAMES||duration>MAX_CYCLE_MS)limit()
        val work=width.toLong()*height.toLong()*frames.toLong()
        if(work>MAX_CANVAS_PIXEL_WORK)limit()
        return StickerImageMetadata(mime,width,height,frames,duration,animated,work)
    }

    private val pngBeforePalette=setOf("cHRM","gAMA","iCCP","sBIT","sRGB","cICP","mDCV","cLLI")
    private val pngBeforeData=setOf("bKGD","hIST","tRNS","pHYs","sPLT","eXIf")
    private val pngSingles=pngBeforePalette+pngBeforeData-setOf("sPLT")+setOf("tIME")
    private fun png(b:Bytes,live:()->Boolean):StickerImageMetadata {
        var at=8;var width=0;var height=0;var depth=0;var color=-1;var palette=0
        var dataSeen=false;var dataEnded=false
        val seen=HashSet<String>();val paletteNames=HashSet<String>()
        while(at<b.size){
            checkLive(live);b.span(at,12)
            val length=b.u32be(at);val type=b.tag(at+4);val payload=at+8
            val dataEnd=b.end(payload,length);val end=b.end(dataEnd,4)
            if(type.any{it !in 'A'..'Z'&&it !in 'a'..'z'}||type[2] !in 'A'..'Z')format()
            val crc=CRC32();var p=at+4
            while(p<dataEnd){checkLive(live);val n=minOf(32*1024,dataEnd-p);crc.update(b.data,p,n);p+=n}
            if(crc.value!=b.u32be(dataEnd))integrity()
            if(type in setOf("acTL","fcTL","fdAT"))format() // APNG is not silently flattened.
            if(type!="IHDR"&&width==0)format()
            if(dataSeen&&type!="IDAT")dataEnded=true
            if(type in pngBeforePalette&&(palette>0||dataSeen))format()
            if(type in pngBeforeData&&dataSeen)format()
            if(type in pngSingles&&!seen.add(type))format()
            when(type){
                "IHDR"->{
                    if(at!=8||width!=0||length!=13L)format()
                    val size=dimensions(b.u32be(payload),b.u32be(payload+4));width=size.first;height=size.second
                    depth=b.u8(payload+8);color=b.u8(payload+9)
                    val allowed=when(color){0->setOf(1,2,4,8,16);2,4,6->setOf(8,16);3->setOf(1,2,4,8);else->emptySet()}
                    if(depth !in allowed||b.u8(payload+10)!=0||b.u8(payload+11)!=0||b.u8(payload+12) !in 0..1)format()
                }
                "PLTE"->{
                    if(palette>0||dataSeen||color in setOf(0,4)||length !in 3L..768L||length%3L!=0L||seen.any{it in setOf("tRNS","bKGD","hIST")})format()
                    palette=(length/3).toInt()
                    if(color==3&&palette>(1 shl depth))format()
                }
                "IDAT"->{if(dataEnded||color==3&&palette==0)format();dataSeen=true}
                "IEND"->{
                    if(length!=0L||!dataSeen||end!=b.size)format()
                    return metadata("image/png",width,height,1,0,false)
                }
                "tRNS"->when(color){
                    0->{if(length!=2L)format();pngSamples(b,payload,1,depth)}
                    2->{if(length!=6L)format();pngSamples(b,payload,3,depth)}
                    3->if(palette==0||length !in 1L..palette.toLong())format()
                    else->format()
                }
                "bKGD"->when(color){
                    0,4->{if(length!=2L)format();pngSamples(b,payload,1,depth)}
                    2,6->{if(length!=6L)format();pngSamples(b,payload,3,depth)}
                    3->if(palette==0||length!=1L||b.u8(payload)>=palette)format()
                    else->format()
                }
                "hIST"->if(palette==0||length!=palette.toLong()*2)format()
                "gAMA"->if(length!=4L||b.u32be(payload) !in 1L..Int.MAX_VALUE.toLong())format()
                "cHRM"->if(length!=32L)format()
                "sRGB"->if(length!=1L||b.u8(payload) !in 0..3)format()
                "sBIT"->{
                    val samples=when(color){0->1;2,3->3;4->2;6->4;else->format()}
                    if(length!=samples.toLong())format()
                    repeat(samples){if(b.u8(payload+it) !in 1..(if(color==3)8 else depth))format()}
                }
                "pHYs"->if(length!=9L||b.u8(payload+8) !in 0..1)format()
                "tIME"->if(length!=7L)format()
                "cICP"->if(length!=4L)format()
                "mDCV"->if(length!=24L)format()
                "cLLI"->if(length!=8L)format()
                "iCCP","zTXt"->{
                    val keyword=pngKeywordEnd(b,payload,dataEnd)
                    if(keyword+1>=dataEnd||b.u8(keyword+1)!=0)format()
                    // Compressed metadata is deliberately not inflated or trusted here.
                }
                "tEXt"->pngKeywordEnd(b,payload,dataEnd)
                "iTXt"->{
                    val keyword=pngKeywordEnd(b,payload,dataEnd)
                    if(keyword+2>=dataEnd||b.u8(keyword+1) !in 0..1||b.u8(keyword+2)!=0)format()
                    val language=zero(b,keyword+3,dataEnd,live)
                    zero(b,language+1,dataEnd,live) // translated keyword; all text remains opaque.
                }
                "sPLT"->{
                    val keyword=pngKeywordEnd(b,payload,dataEnd)
                    if(!paletteNames.add(b.tag(payload,keyword-payload))||keyword+1>=dataEnd)format()
                    val sampleDepth=b.u8(keyword+1);val stride=when(sampleDepth){8->6;16->10;else->format()}
                    if((dataEnd-keyword-2)%stride!=0)format()
                }
                else->{if(type[0] in 'A'..'Z')format()} // Unknown ancillary: CRC/bounds only.
            }
            at=end
        }
        format()
    }
    private fun pngSamples(b:Bytes,at:Int,count:Int,depth:Int){
        val maximum=(1 shl depth)-1
        repeat(count){if(b.u16be(at+2*it)>maximum)format()}
    }
    private fun pngKeywordEnd(b:Bytes,start:Int,end:Int):Int {
        var at=start
        while(at<end&&at-start<80&&b.u8(at)!=0){
            val c=b.u8(at)
            if(c !in 32..126&&c !in 161..255||c==32&&(at==start||b.u8(at-1)==32))format()
            at++
        }
        if(at>=end||at-start !in 1..79||b.u8(at)!=0||b.u8(at-1)==32)format()
        return at
    }
    private fun zero(b:Bytes,start:Int,end:Int,live:()->Boolean):Int {
        var at=start
        while(at<end){if((at-start)%(32*1024)==0)checkLive(live);if(b.u8(at)==0)return at;at++}
        format()
    }

    private data class Chunk(val type:String,val start:Int,val size:Int,val end:Int)
    private fun chunk(b:Bytes,at:Int,end:Int):Chunk {
        if(at<0||end-at<8)format()
        val size=b.u32le(at+4);val payload=at+8;val dataEnd=b.end(payload,size,end)
        val padded=b.end(dataEnd,size and 1L,end)
        if(padded!=dataEnd&&b.u8(dataEnd)!=0)format()
        return Chunk(b.tag(at),payload,size.toInt(),padded)
    }
    private data class ImageHeader(val width:Int,val height:Int,val lossless:Boolean)
    private fun imageHeader(b:Bytes,c:Chunk):ImageHeader = when(c.type){
        "VP8 "->{
            if(c.size<10)format()
            val tag=b.u24le(c.start)
            if((tag and 1)!=0||((tag ushr 1) and 7) !in 0..3||(tag and 16)==0||
                b.u8(c.start+3)!=0x9d||b.u8(c.start+4)!=1||b.u8(c.start+5)!=0x2a)format()
            if((tag ushr 5)>c.size-10)format()
            val size=dimensions((b.u16le(c.start+6) and 0x3fff).toLong(),(b.u16le(c.start+8) and 0x3fff).toLong())
            ImageHeader(size.first,size.second,false)
        }
        "VP8L"->{
            if(c.size<5||b.u8(c.start)!=0x2f)format()
            val bits=b.u32le(c.start+1)
            if(bits ushr 29!=0L)format()
            val size=dimensions((bits and 0x3fffL)+1,((bits ushr 14) and 0x3fffL)+1)
            // Bit28 is alpha_is_used, an explicitly non-authoritative hint. Ignore it.
            ImageHeader(size.first,size.second,true)
        }
        else->format()
    }
    private fun alpha(b:Bytes,c:Chunk,image:ImageHeader){
        if(c.size<1||image.lossless)format()
        val flags=b.u8(c.start)
        if((flags and 0xc0)!=0||((flags ushr 4) and 3) !in 0..1||(flags and 3) !in 0..1)format()
        if(flags and 3==0&&c.size.toLong()!=1L+image.width.toLong()*image.height)format()
        if(flags and 3==1&&c.size<2)format() // compressed alpha remains opaque.
    }
    private data class FrameImage(val image:ImageHeader,val alpha:Boolean)
    private fun frameImage(b:Bytes,start:Int,end:Int,live:()->Boolean):FrameImage {
        var at=start;var alphaChunk:Chunk?=null;var image:ImageHeader?=null
        while(at<end){
            checkLive(live);val c=chunk(b,at,end)
            when(c.type){
                "ALPH"->{if(alphaChunk!=null||image!=null)format();alphaChunk=c}
                "VP8 ","VP8L"->{if(image!=null)format();image=imageHeader(b,c)}
                else->format()
            }
            at=c.end
        }
        val observed=image?:format()
        alphaChunk?.let{alpha(b,it,observed)}
        return FrameImage(observed,alphaChunk!=null)
    }

    private fun webp(b:Bytes,live:()->Boolean):StickerImageMetadata {
        if(b.size<20||b.u32le(4)!=b.size.toLong()-8||b.u32le(4) and 1L!=0L)format()
        val first=chunk(b,12,b.size)
        if(first.type in setOf("VP8 ","VP8L")){
            if(first.end!=b.size)format()
            val image=imageHeader(b,first)
            return metadata("image/webp",image.width,image.height,1,0,false)
        }
        if(first.type!="VP8X"||first.size!=10)format()
        val flags=b.u8(first.start)
        if(flags and 0xc1!=0||b.u24le(first.start+1)!=0)format()
        val canvas=dimensions(b.u24le(first.start+4).toLong()+1,b.u24le(first.start+7).toLong()+1)
        val animated=flags and 2!=0;var at=first.end
        var icc=false;var exif=false;var xmp=false;var animation=false;var reconstruction=false
        var image:ImageHeader?=null;var pendingAlpha:Chunk?=null
        var alphaSeen=false;var losslessSeen=false;var frames=0;var duration=0L
        while(at<b.size){
            checkLive(live);val c=chunk(b,at,b.size)
            when(c.type){
                "ICCP"->{if(icc||reconstruction||c.size==0)format();icc=true}
                "EXIF"->{if(exif||c.size==0)format();exif=true}
                "XMP "->{if(xmp||c.size==0)format();xmp=true}
                "ANIM"->{
                    if(!animated||animation||reconstruction||c.size!=6)format()
                    animation=true;reconstruction=true // background/loop fields are bounded unsigned values.
                }
                "ANMF"->{
                    if(!animated||!animation||c.size<16)format()
                    frames++;if(frames>MAX_FRAMES)limit()
                    val x=b.u24le(c.start).toLong()*2;val y=b.u24le(c.start+3).toLong()*2
                    val frame=dimensions(b.u24le(c.start+6).toLong()+1,b.u24le(c.start+9).toLong()+1)
                    if(x+frame.first>canvas.first||y+frame.second>canvas.second)integrity()
                    val time=b.u24le(c.start+12)
                    if(time !in MIN_FRAME_MS..MAX_FRAME_MS)limit()
                    duration+=time;metadata("image/webp",canvas.first,canvas.second,frames,duration,true)
                    if(b.u8(c.start+15) and 0xfc!=0)format()
                    val observed=frameImage(b,c.start+16,c.start+c.size,live)
                    if(observed.image.width!=frame.first||observed.image.height!=frame.second)integrity()
                    alphaSeen=alphaSeen||observed.alpha;losslessSeen=losslessSeen||observed.image.lossless
                }
                "ALPH"->{if(animated||pendingAlpha!=null||image!=null)format();pendingAlpha=c;reconstruction=true}
                "VP8 ","VP8L"->{
                    if(animated||image!=null)format()
                    val observed=imageHeader(b,c)
                    if(observed.width!=canvas.first||observed.height!=canvas.second)integrity()
                    pendingAlpha?.let{alpha(b,it,observed);alphaSeen=true};pendingAlpha=null
                    image=observed;losslessSeen=observed.lossless;reconstruction=true
                }
                else->format() // Explicit strict known-chunk WebP profile, not every future dialect.
            }
            at=c.end
        }
        if(icc!=(flags and 32!=0)||exif!=(flags and 8!=0)||xmp!=(flags and 4!=0))integrity()
        if(pendingAlpha!=null||animated&&(!animation||frames==0)||!animated&&image==null)format()
        if(alphaSeen&&flags and 16==0||flags and 16!=0&&!alphaSeen&&!losslessSeen)integrity()
        // Lossless transparency cannot be proved from the VP8L hint: actual alpha
        // consistency, compressed metadata/pixels and safe decode remain later gates.
        return metadata("image/webp",canvas.first,canvas.second,if(animated)frames else 1,duration,animated)
    }
}
