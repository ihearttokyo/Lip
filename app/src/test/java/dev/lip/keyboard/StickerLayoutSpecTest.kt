package dev.lip.keyboard

import org.junit.Assert.*
import org.junit.Test

class StickerLayoutSpecTest {
    @Test fun actualWidthControlsColumnsAndBoundedScaleGeometry(){
        val expected=listOf(82,96,120,144)
        for((index,scale) in KeyboardScale.entries.withIndex()){
            val g=StickerLayoutSpec.geometry(1000,1f,scale)
            assertEquals(expected[index],g.imageEdge);assertEquals(4,g.columns)
        }
        assertEquals(3,StickerLayoutSpec.geometry(411,1f,KeyboardScale.SYSTEM).columns)
        assertEquals(2,StickerLayoutSpec.geometry(320,1f,KeyboardScale.SYSTEM).columns)
        assertEquals(1,StickerLayoutSpec.geometry(160,1f,KeyboardScale.SYSTEM).columns)
        assertEquals(1,StickerLayoutSpec.geometry(320,1f,KeyboardScale.EXTRA_LARGE).columns)
    }
    @Test fun imageFitsTileAtEveryBoundedWidthAndDensity(){
        for(density in listOf(.85f,1f,2f,2.625f,3f))for(scale in KeyboardScale.entries)for(width in 1..2400 step 13){
            val g=StickerLayoutSpec.geometry(width,density,scale)
            assertTrue(g.columns in 1..4);assertTrue(g.imageEdge>0);assertTrue(g.tileWidth>0);assertTrue(g.padding>=0)
            assertTrue(g.imageEdge.toLong()+2L*g.padding<=g.tileWidth.toLong())
            assertTrue(g.columns.toLong()*g.tileWidth+(g.columns-1L)*g.gap<=width.toLong())
        }
    }
    @Test fun independentScaleGrowsPreviewOrSaturatesAtActualWidth(){
        for(width in listOf(1,32,48,96,160,320,411,900))for(density in listOf(1f,2f,3f)){
            val edges=KeyboardScale.entries.map{StickerLayoutSpec.geometry(width,density,it).imageEdge}
            assertTrue(edges.zipWithNext().all{it.first<=it.second})
        }
        val system=StickerLayoutSpec.geometry(411,1f,KeyboardScale.SYSTEM)
        val large=StickerLayoutSpec.geometry(411,1f,KeyboardScale.EXTRA_LARGE)
        assertTrue(large.imageEdge>system.imageEdge);assertTrue(large.columns<=system.columns)
    }
    @Test fun geometryArithmeticIsSafeAtIntegerAndFiniteDensityExtremes(){
        for(width in listOf(1,Int.MAX_VALUE))for(density in listOf(Float.MIN_VALUE,Float.MAX_VALUE)){
            val g=StickerLayoutSpec.geometry(width,density,KeyboardScale.EXTRA_LARGE)
            assertTrue(g.imageEdge in 1..g.tileWidth);assertTrue(g.columns in 1..4)
            assertTrue(g.columns.toLong()*g.tileWidth+(g.columns-1L)*g.gap<=width.toLong())
        }
    }
    @Test fun measuredContentMayExceedBaseButNeverActualParentCap(){
        // Base435px may expand to626px; the actual864px parent cap is authoritative.
        assertEquals(626,StickerLayoutSpec.panelHeight(435,864,126,150,350))
        assertEquals(700,StickerLayoutSpec.panelHeight(700,1800,126,100,350))
        assertEquals(500,StickerLayoutSpec.panelHeight(435,500,126,150,350))
        assertEquals(180,StickerLayoutSpec.panelHeight(435,180,126,150,350))
        assertEquals(435,StickerLayoutSpec.panelHeight(435,864,126,60,0))
        assertEquals(100,StickerLayoutSpec.panelHeight(Int.MAX_VALUE,100,Int.MAX_VALUE,Int.MAX_VALUE,Int.MAX_VALUE))
        for(available in 1..900 step 17)for(intro in listOf(0,85,200))for(row in listOf(0,180,350)){
            val actual=StickerLayoutSpec.panelHeight(435,available,126,intro,row)
            assertTrue(actual in 1..available)
            if(available>=maxOf(435,126+intro+row))assertEquals(maxOf(435,126+intro+row),actual)
        }
    }
    @Test fun emptyAndCompleteBoundedPagesValidateWithoutOverflow(){
        StickerLayoutSpec.validatePage(0,0,0,128)
        for(total in 1..128){var offset=0;while(offset<total){StickerLayoutSpec.validatePage(offset,minOf(8,total-offset),total,128);offset+=8}}
        StickerLayoutSpec.validatePage(120,1,121,121)
        for(values in listOf(listOf(0,9,9,128),listOf(1,8,9,128),listOf(8,0,8,128),listOf(0,1,0,128),listOf(0,7,8,128),listOf(Int.MAX_VALUE,1,1,128),listOf(0,8,129,128),listOf(0,8,122,121)))
            assertThrows(IllegalArgumentException::class.java){StickerLayoutSpec.validatePage(values[0],values[1],values[2],values[3])}
    }
    @Test fun invalidGeometryIsRefused(){
        for(density in listOf(0f,-1f,Float.NaN,Float.POSITIVE_INFINITY))assertThrows(IllegalArgumentException::class.java){StickerLayoutSpec.geometry(411,density,KeyboardScale.SYSTEM)}
        assertThrows(IllegalArgumentException::class.java){StickerLayoutSpec.geometry(0,1f,KeyboardScale.SYSTEM)}
        for(values in listOf(listOf(0,100,48,0,0),listOf(100,0,48,0,0),listOf(100,100,0,0,0),listOf(100,100,48,-1,0),listOf(100,100,48,0,-1)))
            assertThrows(IllegalArgumentException::class.java){StickerLayoutSpec.panelHeight(values[0],values[1],values[2],values[3],values[4])}
    }
    @Test fun rawPreviewCopiesInputAndOutputAndRejectsNonPremultipliedOrUnboundedData(){
        val raw=byteArrayOf(64,32,16,128.toByte());val preview=StickerPanelPreview("Original synthetic pixel",1,1,raw)
        raw.fill(0);assertArrayEquals(byteArrayOf(64,32,16,128.toByte()),preview.premultipliedRgbaSrgbBytes())
        preview.premultipliedRgbaSrgbBytes().fill(0);assertEquals(64,preview.premultipliedRgbaSrgbBytes()[0].toInt())
        for(bytes in listOf(byteArrayOf(),byteArrayOf(1,0,0,0),byteArrayOf(255.toByte(),0,0,128.toByte())))
            assertThrows(IllegalArgumentException::class.java){StickerPanelPreview("Synthetic",1,1,bytes)}
        assertThrows(IllegalArgumentException::class.java){StickerPanelPreview("Synthetic",257,1,ByteArray(1028))}
        assertThrows(IllegalArgumentException::class.java){StickerPanelPreview("Synthetic",1,0,byteArrayOf())}
    }
    @Test fun presentationListsRemainImmutableAndPageNavigationIsDerived(){
        fun pack(i:Int)=StickerPanelPack("00000000-0000-0000-0000-${i.toString().padStart(12,'0')}","Synthetic $i",true,"Local browse only")
        val input=(0 until 8).map(::pack).toMutableList();val library=StickerPanelState.Library(input,0,9);input.clear()
        assertEquals(8,library.packs.size);assertFalse(library.hasPrevious);assertTrue(library.hasNext)
        assertThrows(UnsupportedOperationException::class.java){(library.packs as MutableList<*>).clear()}
        val last=StickerPanelState.Library(listOf(pack(8)),8,9);assertTrue(last.hasPrevious);assertFalse(last.hasNext)
        val empty=StickerPanelState.Library(emptyList(),0,0);assertFalse(empty.hasPrevious);assertFalse(empty.hasNext)
        val previews=mutableListOf(StickerPanelPreview("Synthetic",1,1,byteArrayOf(0,0,0,0)))
        val page=StickerPanelState.Pack("Original synthetic pack","Rights UNVERIFIED",previews,0,1);previews.clear()
        assertEquals(1,page.previews.size);assertFalse(page.hasPrevious);assertFalse(page.hasNext)
        assertThrows(UnsupportedOperationException::class.java){(page.previews as MutableList<*>).clear()}
        assertThrows(IllegalArgumentException::class.java){StickerPanelState.Pack("Synthetic","",emptyList(),0,0)}
        assertThrows(IllegalArgumentException::class.java){StickerPanelState.Library(listOf(pack(1),pack(1)),0,2)}
    }
    @Test fun previewOriginalIdentityIsOptionalImmutableAndDistinctFromDisplayPixels(){
        val pixels=byteArrayOf(0,0,0,0)
        val browse=StickerPanelPreview("Synthetic",1,1,pixels);assertNull(browse.asset)
        // Original WebP metadata is not the RGBA preview's MIME/length or a page/tray index.
        val original=StickerAssetIdentity("00000000-0000-0000-0000-000000000001","a".repeat(64),"synthetic-face","b".repeat(64),"image/webp",8192)
        val preview=StickerPanelPreview("Synthetic",1,1,pixels,original)
        pixels.fill(1)
        val bound=checkNotNull(preview.asset)
        assertSame(original,bound);assertEquals("image/webp",bound.mime);assertEquals(8192,bound.byteLength)
        assertArrayEquals(byteArrayOf(0,0,0,0),preview.premultipliedRgbaSrgbBytes())
        assertFalse(preview.toString().contains(original.commitId));assertFalse(preview.toString().contains(original.stickerId))
    }

}
