package dev.lip.keyboard

import org.junit.Assert.*
import org.junit.Test

class KeyboardPreferencesTest {
    @Test fun defaultsPreserveReference(){
        val config=KeyboardPreferences()
        assertEquals(KeyboardHand.FULL,config.hand);assertEquals(KeyboardTheme.GLASS,config.theme);assertFalse(config.haptics)
        for(landscape in listOf(false,true))for(width in listOf(320,411,800)){
            val height=if(landscape)411 else 900
            assertEquals(KeyboardLayoutSpec.rowHeightPx(width,height,1f,landscape),KeyboardPresentation.rowHeight(width,height,1f,landscape,config.height))
        }
    }
    @Test fun completeRoundTripAndNoTextStorage(){
        for(hand in KeyboardHand.entries)for(height in KeyboardHeight.entries)for(theme in KeyboardTheme.entries)for(haptics in listOf(false,true)){
            val config=KeyboardPreferences(hand,height,theme,haptics)
            assertEquals(config,KeyboardPreferences.decode(config.encode()))
            assertEquals(setOf("version","hand","height","theme","haptics","keyLabels","emojiPreview"),config.encode().keys)
        }
    }
    @Test fun malformedAndFutureVersionFailToSafeDefaults(){
        assertEquals(KeyboardPreferences(),KeyboardPreferences.decode(emptyMap<String,Any>()))
        assertEquals(KeyboardPreferences(),KeyboardPreferences.decode(mapOf("version" to 3,"haptics" to true)))
        assertEquals(KeyboardPreferences(),KeyboardPreferences.decode(mapOf("version" to 1,"hand" to 1,"height" to "huge","theme" to false,"haptics" to "true")))
    }
    @Test fun measuredWindowNotScreenGovernsOneHand(){
        for(density in listOf(1f,2f,3f))for(dp in 240..1400){
            val width=(dp*density).toInt()
            assertEquals(width,KeyboardPresentation.contentWidth(width,density,KeyboardHand.FULL))
            val left=KeyboardPresentation.contentWidth(width,density,KeyboardHand.LEFT)
            assertEquals(left,KeyboardPresentation.contentWidth(width,density,KeyboardHand.RIGHT))
            assertTrue(left<=width)
            if(left<width){assertTrue(left>=320*density);assertTrue(width-left>=48*density)}
            else if(dp<372)assertEquals(width,left)
        }
    }
    @Test fun heightChoicesAreBoundedAndOrdered(){
        for(density in listOf(1f,2f,3f))for(landscape in listOf(false,true))for(width in listOf(320,411,900)){
            val values=KeyboardHeight.entries.map{KeyboardPresentation.rowHeight((width*density).toInt(),(900*density).toInt(),density,landscape,it)}
            assertTrue(values[0]<=values[1]);assertTrue(values[1]<=values[2]);assertTrue(values.all{it>=32*density&&it<=65*density})
        }
    }
    @Test fun invalidGeometryRejected(){
        for(density in listOf(0f,-1f,Float.NaN,Float.POSITIVE_INFINITY))assertThrows(IllegalArgumentException::class.java){KeyboardPresentation.contentWidth(411,density,KeyboardHand.LEFT)}
        assertThrows(IllegalArgumentException::class.java){KeyboardPresentation.rowHeight(0,900,1f,false,KeyboardHeight.TALL)}
    }
    @Test fun failedCommitMutatesCacheButNotConfirmedConsumers(){
        var cache=KeyboardPreferences().encode()
        var success=false
        val repo=KeyboardPreferenceRepository({cache}){value->cache=value.encode();success}
        val failed=KeyboardPreferences(hand=KeyboardHand.RIGHT,haptics=true)
        assertFalse(repo.save(failed));assertEquals(failed,KeyboardPreferences.decode(cache))
        assertEquals(KeyboardPreferences(),repo.load())
        // Reconstructed store wrappers share THIS repository, never reconstruct from failed backend cache.
        val consumerA={repo.load()};val consumerB={repo.load()}
        assertEquals(consumerA(),consumerB());assertEquals(KeyboardPreferences(),consumerB())
        success=true;val next=KeyboardPreferences(theme=KeyboardTheme.MATTE)
        assertTrue(repo.save(next));assertEquals(next,consumerA());assertEquals(next,KeyboardPreferences.decode(cache))
    }
    @Test fun thrownWritePreservesConfirmation(){
        val original=KeyboardPreferences(hand=KeyboardHand.LEFT)
        val repo=KeyboardPreferenceRepository({original.encode()}){throw IllegalStateException("Synthetic storage failure")}
        assertFalse(repo.save(original.copy(haptics=true)));assertEquals(original,repo.load())
    }
    @Test fun sizingChoicesRoundTripIndependentlyWithLegacyPreservation(){
        for(key in KeyboardScale.entries)for(emoji in KeyboardScale.entries){
            val value=KeyboardPreferences(hand=KeyboardHand.LEFT,height=KeyboardHeight.TALL,haptics=true,keyLabels=key,emojiPreview=emoji)
            assertEquals(value,KeyboardPreferences.decode(value.encode()))
        }
        val legacy=mapOf("version" to 1,"hand" to "RIGHT","height" to "COMPACT","theme" to "MATTE","haptics" to true,"keyLabels" to "EXTRA_LARGE")
        assertEquals(KeyboardPreferences(KeyboardHand.RIGHT,KeyboardHeight.COMPACT,KeyboardTheme.MATTE,true),KeyboardPreferences.decode(legacy))
        for(bad in listOf<Any>("giant",1,Float.NaN,false)){
            assertEquals(KeyboardScale.SYSTEM,KeyboardPreferences.decode(mapOf("version" to 2,"keyLabels" to bad)).keyLabels)
        }
    }
    @Test fun scaleDoesNotChangeGeometryAndCellGrowthIsBoundedByMeasuredWidth(){
        val key=KeyboardLayoutSpec.rowHeightPx(411,900,1f,false)
        val values=KeyboardScale.entries.map{KeyboardSizing.legendSp(26,it)}
        assertEquals(listOf(22,26,32,39),values)
        for(scale in KeyboardScale.entries)for(density in listOf(1f,2f,3f))for(font in listOf(.85f,1f,1.3f,2f)){
            val cell=KeyboardSizing.mediaCellPixels(32*scale.factor*density*font,density)
            assertTrue(cell>=48*density)
            for(width in listOf(160,240,320,411,900)){
                val pixels=(width*density).toInt();val columns=KeyboardSizing.mediaColumns(pixels,cell,10)
                assertTrue(columns in 1..10)
                if(columns>1)assertTrue(columns*cell<=pixels)
            }
            assertEquals(key,KeyboardLayoutSpec.rowHeightPx(411,900,1f,false))
        }
        for(invalid in listOf(0f,Float.NaN,Float.POSITIVE_INFINITY,-1f)){
            assertThrows(IllegalArgumentException::class.java){KeyboardSizing.mediaCellPixels(invalid,1f)}
            assertThrows(IllegalArgumentException::class.java){KeyboardSizing.mediaCellPixels(32f,invalid)}
        }
    }
    @Test fun failedSizingWriteKeepsConfirmedAppearance(){
        var cache=KeyboardPreferences().encode()
        val repo=KeyboardPreferenceRepository({cache}){cache=it.encode();false}
        assertFalse(repo.save(KeyboardPreferences(keyLabels=KeyboardScale.EXTRA_LARGE,emojiPreview=KeyboardScale.SMALL)))
        assertEquals(KeyboardPreferences(),repo.load())
        assertEquals(KeyboardScale.EXTRA_LARGE,KeyboardPreferences.decode(cache).keyLabels)
    }

    @Test fun emojiPanelMakesRoomForFullRowOrCapsToAvailableViewport(){
        assertEquals(KeyboardSizing.MediaPanel(254,132),KeyboardSizing.mediaPanel(192,313,132,122))
        assertEquals(KeyboardSizing.MediaPanel(180,58),KeyboardSizing.mediaPanel(192,180,132,122))
        for(available in 160..900 step 10)for(cell in listOf(48,52,80,132))for(chrome in listOf(80,102,122)){
            val sizing=KeyboardSizing.mediaPanel(192,available,cell,chrome)
            assertTrue(sizing.height<=available);assertTrue(sizing.cell in 1..cell)
            assertTrue(sizing.cell+chrome<=sizing.height)
            if(available>=cell+chrome)assertEquals(cell,sizing.cell)
        }
        assertEquals(KeyboardSizing.MediaPanel(100,1),KeyboardSizing.mediaPanel(Int.MAX_VALUE,100,Int.MAX_VALUE,Int.MAX_VALUE))
    }

}
