package dev.lip.keyboard
import org.junit.Test
import org.junit.Assert.*
class KeyboardFineHeightTest {
 @Test fun legacyVersionsKeepEveryUnrelatedSettingAndExactPresetPercent(){
  for(version in listOf(1,2))for(preset in KeyboardHeight.entries){
   val map=mapOf("version" to version,"hand" to "RIGHT","height" to preset.name,"theme" to "MATTE","haptics" to true,"keyLabels" to "LARGE","emojiPreview" to "SMALL")
   val value=KeyboardPreferences.decode(map)
   assertEquals(preset.percent,value.heightPercent);assertEquals(KeyboardHand.RIGHT,value.hand);assertEquals(KeyboardTheme.MATTE,value.theme);assertTrue(value.haptics)
   assertEquals(if(version==1)KeyboardScale.SYSTEM else KeyboardScale.LARGE,value.keyLabels)
  }
 }
 @Test fun percentSchemaRoundTripsAll61BoundedIntegers(){
  for(percent in 80..140){val value=KeyboardPreferences(hand=KeyboardHand.LEFT,keyLabels=KeyboardScale.LARGE,heightPercent=percent)
   assertEquals(value,KeyboardPreferences.decode(value.encode()));assertEquals(3,value.encode()["version"])}
  for(bad in listOf<Any>(79,141,"110",110L,110f,Double.NaN,true)){
   val value=KeyboardPreferences.decode(KeyboardPreferences(hand=KeyboardHand.RIGHT).encode()+mapOf("heightPercent" to bad))
   assertEquals(100,value.heightPercent);assertEquals(KeyboardHand.RIGHT,value.hand)
  }
 }
 @Test fun presetsPreserveExactHistoricalFloatTruncationAndBounds(){
  for(preset in KeyboardHeight.entries)for(density in listOf(.85f,1f,2.625f,3f))for(landscape in listOf(false,true))for(w in listOf(240,320,411,900)){
   val width=(w*density).toInt();val height=(900*density).toInt();val base=KeyboardLayoutSpec.rowHeightPx(width,height,density,landscape)
   val historical=(base*preset.factor).toInt().coerceIn((32*density).toInt().coerceAtLeast(1),((if(landscape)46 else 65)*density).toInt().coerceAtLeast(1))
   assertEquals(historical,KeyboardPresentation.rowHeight(width,height,density,landscape,preset.percent))
  }
 }
 @Test fun percentagesAreMonotoneWindowCappedAndRejectInvalidGeometry(){
  for(landscape in listOf(false,true))for(density in listOf(1f,2f,3f)){
   val heights=(80..140).map{KeyboardPresentation.rowHeight((411*density).toInt(),(900*density).toInt(),density,landscape,it)}
   assertTrue(heights.zipWithNext().all{it.first<=it.second});assertTrue(heights.all{it in (32*density).toInt()..((if(landscape)46 else 65)*density).toInt()})
  }
  for(invalid in listOf(0,79,141,Int.MAX_VALUE))assertThrows(IllegalArgumentException::class.java){KeyboardPresentation.rowHeight(411,900,1f,false,invalid)}
 }
 @Test fun manyDraftMovesAndOnePercentStepsPerformZeroWritesUntilApply(){
  val draft=KeyboardHeightDraft(100);var writes=0
  for(percent in 80..140)assertTrue(draft.set(percent));assertEquals(0,writes)
  assertEquals(KeyboardHeightDraft.Outcome.SAVED,draft.apply{writes++;assertEquals(140,it);true});assertEquals(1,writes)
  assertEquals(KeyboardHeightDraft.Outcome.UNCHANGED,draft.apply{writes++;true});assertEquals(1,writes)
 }
 @Test fun closedStaleDraftAndRecursiveApplyCannotWriteAgain(){
  val draft=KeyboardHeightDraft(100);draft.set(110);var writes=0
  assertEquals(KeyboardHeightDraft.Outcome.SAVED,draft.apply{
   writes++;assertEquals(KeyboardHeightDraft.Outcome.STALE,draft.apply{writes++;true});assertFalse(draft.set(120));true
  })
  draft.close();assertFalse(draft.set(115));assertEquals(KeyboardHeightDraft.Outcome.STALE,draft.apply{writes++;true});assertEquals(1,writes)
 }
 @Test fun failedHeightWriteRetainsConfirmedConsumerEvenWithMutatedBackendCache(){
  var backend=KeyboardPreferences().encode();val repo=KeyboardPreferenceRepository({backend}){backend=it.encode();false}
  val draft=KeyboardHeightDraft(repo.load().heightPercent);draft.set(133)
  assertEquals(KeyboardHeightDraft.Outcome.FAILED,draft.apply(repo::saveHeight));assertEquals(100,repo.load().heightPercent)
  assertEquals(133,KeyboardPreferences.decode(backend).heightPercent)
 }
 @Test fun fieldSpecificHeightSavePreservesNewerUnrelatedConfirmedSettings(){
  val repo=KeyboardPreferenceRepository({KeyboardPreferences().encode()}){true}
  val staleScreen=repo.load()
  repo.save(staleScreen.copy(hand=KeyboardHand.RIGHT,theme=KeyboardTheme.MATTE,haptics=true,keyLabels=KeyboardScale.EXTRA_LARGE,emojiPreview=KeyboardScale.SMALL))
  assertTrue(repo.saveHeight(123));val actual=repo.load()
  assertEquals(123,actual.heightPercent);assertEquals(KeyboardHand.RIGHT,actual.hand);assertEquals(KeyboardTheme.MATTE,actual.theme);assertTrue(actual.haptics)
  assertEquals(KeyboardScale.EXTRA_LARGE,actual.keyLabels);assertEquals(KeyboardScale.SMALL,actual.emojiPreview)
  assertTrue(repo.update{it.copy(haptics=false)});assertEquals(123,repo.load().heightPercent)
  assertTrue(repo.update{it.copy(hand=KeyboardHand.LEFT)});assertEquals(123,repo.load().heightPercent);assertEquals(KeyboardTheme.MATTE,repo.load().theme)
  assertFalse(repo.saveHeight(141));assertEquals(123,repo.load().heightPercent)
 }
 @Test fun actualConsumerUsesPercentAndPreviewHasNoInputCallbacks(){
  fun source(name:String)=listOf(java.io.File("src/main/java/dev/lip/keyboard/$name.kt"),java.io.File("app/src/main/java/dev/lip/keyboard/$name.kt")).first{it.isFile}.readText()
  val service=source("LipInputMethodService");assertTrue(service.contains("landscape(),presentation.heightPercent)"))
  assertTrue(service.contains("presentationStore.update{it.copy(hand=hand)}"))
  val ui=source("KeyboardHeightControls");val listener=ui.substringAfter("override fun onProgressChanged").substringBefore("private fun setDraft")
  assertFalse(listener.contains("save("));assertTrue(listener.contains("onStopTrackingTouch(view:SeekBar){}"))
  assertTrue(ui.contains("KeyboardPresentation.rowHeight"));assertFalse(ui.contains("Dictation"));assertFalse(ui.contains("ClipboardManager"));assertFalse(ui.contains("Bark"))
  assertTrue(source("KeyboardSettingsActivity").contains("store.saveHeight(percent)"))
 }
}
