package dev.lip.core
import dev.lip.stickers.*
import org.junit.Assert.*
import org.junit.Test
class StickerPixelContractTest{
 @Test fun targetNeverUpscalesAndHasBoundedNearestIntegerAspect(){
  assertEquals(100 to 50,StickerPixelContract.target(100,50));assertEquals(256 to 128,StickerPixelContract.target(512,256));assertEquals(1 to 256,StickerPixelContract.target(1,4096))
  for(w in listOf(1,2,100,255,256,257,512,4096))for(h in listOf(1,2,100,255,256,257,512,4096)){
   val(a,b)=StickerPixelContract.target(w,h);assertTrue(a in 1..minOf(256,w)&&b in 1..minOf(256,h));assertTrue(StickerPixelContract.byteCount(a,b)<=262144)
  }
 }
 @Test fun pixelFramingCannotOverflowOrExceedCap(){
  for(w in listOf(0,-1,257,Int.MAX_VALUE))assertThrows(StickerDecodeException::class.java){StickerPixelContract.byteCount(w,1)}
  for(h in listOf(0,-1,257,Int.MAX_VALUE))assertThrows(StickerDecodeException::class.java){StickerPixelContract.byteCount(1,h)}
  assertEquals(262144,StickerPixelContract.byteCount(256,256));assertTrue(121L*262144<=StickerPixelContract.MAX_PACK_RGBA_BYTES)
 }
 @Test fun expectedSourceFramesDurationAndBytesAllBind(){
  val m=StickerImageMetadata("image/png",512,256,1,0,false,131072)
  StickerPixelContract.validate(m,512,256,256,128,1,0,131072)
  for(v in listOf(intArrayOf(513,256,256,128,1,131072),intArrayOf(512,256,256,127,1,130048),intArrayOf(512,256,256,128,2,131072),intArrayOf(512,256,256,128,1,0))){
   assertThrows(StickerDecodeException::class.java){StickerPixelContract.validate(m,v[0],v[1],v[2],v[3],v[4],0,v[5])}
  }
  assertThrows(StickerDecodeException::class.java){StickerPixelContract.validate(m,512,256,256,128,1,10,131072)}
 }
 @Test fun previewPixelsCopyAndRightsRemainUnverified(){
  val raw=byteArrayOf(1,2,3,4);val m=StickerImageMetadata("image/png",1,1,1,0,false,1)
  val p=SandboxedStickerPreview("0".repeat(64),m,1,1,1,2,100,raw);raw[0]=9;p.premultipliedRgbaSrgbBytes()[0]=8
  assertArrayEquals(byteArrayOf(1,2,3,4),p.premultipliedRgbaSrgbBytes());assertEquals(InspectedStickerPack.Rights.UNVERIFIED,p.rights)
 }
}
