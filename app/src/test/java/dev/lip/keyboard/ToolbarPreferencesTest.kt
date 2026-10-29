package dev.lip.keyboard

import org.junit.Test
import org.junit.Assert.*

class ToolbarPreferencesTest {
 @Test fun exactLegacyDefaultAndFixedEndpoints(){
  assertEquals(listOf(ToolbarAction.TOOLS,ToolbarAction.AGENT,ToolbarAction.EMOJI,ToolbarAction.GIF,ToolbarAction.CLIPBOARD,ToolbarAction.UNDO,ToolbarAction.MICROPHONE),ToolbarActionRegistry.actions(ToolbarPreferences()))
  assertEquals(ToolbarPreferences(),ToolbarPreferences.decode(emptyMap<String,Any>()))
 }
 @Test fun strictRoundTripAndCorruptUnknownDuplicateRefusal(){
  val value=ToolbarPreferences(listOf(ToolbarAction.TEXT_EDITING,ToolbarAction.STICKERS,ToolbarAction.EMOJI,ToolbarAction.UNDO,ToolbarAction.CLIPBOARD))
  assertEquals(value,ToolbarPreferences.decode(value.encode()))
  val malformed=listOf(mapOf("version" to 2,"slots" to "AGENT,EMOJI,GIF,CLIPBOARD,UNDO"),
   mapOf("version" to 1,"slots" to "AGENT,EMOJI,GIF,CLIPBOARD,UNKNOWN"),mapOf("version" to 1,"slots" to "EMOJI,EMOJI,GIF,CLIPBOARD,UNDO"),
   mapOf("version" to 1,"slots" to "TOOLS,EMOJI,GIF,CLIPBOARD,UNDO"),mapOf("version" to 1,"slots" to "AGENT,EMOJI"),
   mapOf("version" to 1,"slots" to listOf("AGENT")),mapOf("version" to 1,"slots" to "AGENT,EMOJI,GIF,CLIPBOARD,UNDO","extra" to true))
  malformed.forEach{assertNull(ToolbarPreferences.decode(it))}
 }
 @Test fun immutableFiveSlotValueRejectsAliasesAndEndpoints(){
  val input=ToolbarPreferences.DEFAULT.toMutableList();val value=ToolbarPreferences(input);input[0]=ToolbarAction.STICKERS
  assertEquals(ToolbarAction.AGENT,value.slots[0])
  assertThrows(UnsupportedOperationException::class.java){(value.slots as MutableList<ToolbarAction>).clear()}
  assertThrows(IllegalArgumentException::class.java){ToolbarPreferences(listOf(ToolbarAction.MICROPHONE))}
 }
 @Test fun movingAndChoosingUsedActionSwapWithoutDuplicatesOrEndpointChanges(){
  val value=ToolbarPreferences();val moved=ToolbarActionRegistry.move(value,1,-1)
  assertEquals(listOf(ToolbarAction.EMOJI,ToolbarAction.AGENT,ToolbarAction.GIF,ToolbarAction.CLIPBOARD,ToolbarAction.UNDO),moved.slots)
  assertEquals(moved,ToolbarActionRegistry.replace(value,0,ToolbarAction.EMOJI))
  val replaced=ToolbarActionRegistry.replace(value,0,ToolbarAction.STICKERS)
  assertEquals(ToolbarAction.STICKERS,replaced.slots[0]);assertFalse(replaced.slots.contains(ToolbarAction.AGENT))
  assertEquals(ToolbarAction.TOOLS,ToolbarActionRegistry.actions(replaced).first());assertEquals(ToolbarAction.MICROPHONE,ToolbarActionRegistry.actions(replaced).last())
 }
 @Test fun geometryExactlyMatchesLegacyFormulaAcrossMeasuredWidths(){
  for(width in listOf(0,240,320,720,1080,1920))for(index in 0..6){
   val left=(width*.15f*index).toInt();assertEquals(left to left+(width*.1f).toInt(),ToolbarActionRegistry.bounds(width,index))
   assertTrue(ToolbarActionRegistry.bounds(width,index).second<=width)
  }
 }
 @Test fun missingDataValidButCorruptionIsUnavailableWithoutWritingOrReinterpreting(){
  var writes=0;val corrupt=mapOf("version" to 44,"slots" to "private-untrusted-value")
  val repository=ToolbarPreferenceRepository({corrupt}){writes++;true}
  assertFalse(repository.snapshot().valid);assertEquals(ToolbarPreferences(),repository.snapshot().value);assertEquals(0,writes)
  assertTrue(ToolbarPreferenceRepository({emptyMap<String,Any>()}){true}.snapshot().valid)
 }
 @Test fun falseOrThrowingCommitPreservesConfirmedValueAndRevisionEvenIfBackendCacheChanged(){
  var backend:Map<String,Any> = ToolbarPreferences().encode();var throws=false
  val repository=ToolbarPreferenceRepository({backend}){next->backend=next.encode();if(throws)error("Synthetic save failure");false}
  val before=repository.snapshot();val next=ToolbarActionRegistry.replace(before.value,0,ToolbarAction.TEXT_EDITING)
  assertFalse(repository.save(next));assertSame(before,repository.snapshot());assertNotEquals(before.value.encode(),backend)
  throws=true;assertFalse(repository.save(next));assertSame(before,repository.snapshot())
 }
 @Test fun equalSuccessfulSavesAdvanceRevisionAndAbaCannotOverwriteNewConfirmedSettings(){
  val repository=ToolbarPreferenceRepository({emptyMap<String,Any>()}){true};val a=repository.snapshot()
  assertTrue(repository.save(a.value,a.revision));val equal=repository.snapshot();assertNotSame(a.revision,equal.revision)
  assertFalse(repository.save(ToolbarActionRegistry.replace(a.value,0,ToolbarAction.STICKERS),a.revision))
  assertSame(equal,repository.snapshot())
 }
 @Test fun observersSeeOnlyConfirmedSuccessOutsideLocksIncludingEqualSave(){
  var success=true;var calls=0;val repository=ToolbarPreferenceRepository({emptyMap<String,Any>()}){success}
  val observer:()->Unit={val thread=Thread{repository.snapshot()};thread.start();thread.join(1000);if(!thread.isAlive)calls++}
  repository.observe(observer);assertTrue(repository.save(repository.snapshot().value));assertEquals(1,calls)
  success=false;assertFalse(repository.save(repository.snapshot().value));assertEquals(1,calls)
  repository.unobserve(observer);success=true;repository.save(repository.snapshot().value);assertEquals(1,calls)
 }
 @Test fun actualPreviewAndConsumerShareGeometryAndPreviewCannotInvokeAnything(){
  fun source(name:String)=listOf(java.io.File("src/main/java/dev/lip/keyboard/$name.kt"),java.io.File("app/src/main/java/dev/lip/keyboard/$name.kt")).first{it.isFile}.readText()
  val ui=source("ToolbarCustomizerActivity")
  assertTrue(ui.contains("ToolbarActionRegistry.actions(draft)"));assertTrue(ui.contains("GlassKeyboardToolbar(this)"));assertTrue(ui.contains("interactionAllowed={false}"))
  assertFalse(ui.contains("Dictation"));assertFalse(ui.contains("ClipboardManager"));assertFalse(ui.contains("BarkModel"))
  assertTrue(source("KeyboardGlass").contains("ToolbarActionRegistry.bounds(width,i)"))
  val service=source("LipInputMethodService")
  assertTrue(service.contains("for(actionId in mapping.value.slots)"));assertTrue(service.contains("mapping.revision===toolbarSettings.snapshot().revision"))
  assertTrue(service.contains("fun currentAllowed()=!destroyed&&isInputViewShown&&root.isAttachedToWindow&&root.isShown&&root.windowVisibility==View.VISIBLE"))
  assertTrue(service.contains("toolbarOwner.admit(claim,toolbarContext(actionId),currentAllowed()&&!swipePending)"))
  assertTrue(service.contains("toolbarTouchStream.event(event.pointerCount"));assertTrue(service.contains("toolbarTouchStream.begin();capture()"))
  assertTrue(service.contains("if(terminal&&rootToken===toolbarRenderToken)toolbarTouchStream.end()"))
  val capture=service.substringAfter("fun capture(){").substringBefore("fun invoke(long:Boolean)")
  assertTrue(capture.indexOf("toolbarOwner.begin(toolbarCpuContext(actionId))")<capture.indexOf("val observed=toolbarContext(actionId)"))
 }
}
