package dev.lip.keyboard
import org.junit.Test
import org.junit.Assert.*
class StickerPanelContactTest {
 @Test fun explicitContactClaimsOnceAndNoClickTimeFallbackIsMinted(){
  val owner=StickerPanelContact();var calls=0;val ticket=owner.bind(owner.snapshot()){calls++}!!
  owner.claim(ticket)!!.invoke();assertEquals(1,calls);assertNull(owner.claim(ticket));assertEquals(1,calls)
 }
 @Test fun downSnapshotInvalidatedDuringExternalCaptureCannotBindLater(){
  val owner=StickerPanelContact();val old=owner.snapshot();owner.cancel();assertNull(owner.bind(old){error("Stale capture")})
 }
 @Test fun claimedCallbackCannotRegainCurrentAuthorityAfterCancelAba(){
  val owner=StickerPanelContact();val old=owner.snapshot();var sent=0
  val ticket=owner.bind(old){if(owner.isCurrent(old))sent++}!!;val callback=owner.claim(ticket)!!
  owner.cancel();callback();assertEquals(0,sent);val fresh=owner.snapshot();assertTrue(owner.isCurrent(fresh))
 }
 @Test fun unsplitSecondContactCancelsWholeStreamThroughLastLift(){
  val owner=StickerPanelContact();val gate=StickerPanelTouchGate();var calls=0
  assertFalse(gate.event(1,false,false).consume);val old=owner.bind(owner.snapshot()){calls++}!!
  val multi=gate.event(2,false,false);assertTrue(multi.consume);assertTrue(multi.cancel);owner.cancel()
  assertTrue(gate.event(2,false,false).consume);assertTrue(gate.event(1,false,false).consume)
  assertTrue(gate.event(1,true,false).consume);assertFalse(gate.blocked);assertNull(owner.claim(old));assertEquals(0,calls)
  val fresh=owner.bind(owner.snapshot()){calls++}!!;owner.claim(fresh)!!.invoke();assertEquals(1,calls)
 }
 @Test fun actualPanelAndServiceCarryDownOwnerThroughCleanupIntoController(){
  fun source(name:String)=listOf(java.io.File("src/main/java/dev/lip/keyboard/$name.kt"),java.io.File("app/src/main/java/dev/lip/keyboard/$name.kt")).first{it.isFile}.readText()
  val panel=source("StickerPanel")
  assertTrue(panel.contains("ACTION_DOWN->capture()"));assertTrue(panel.contains("touchGate.event(event.pointerCount"));assertTrue(panel.contains("contactOwner.claim(ticket)?.invoke()"))
  assertTrue(panel.contains("closed=true;binding=null;contactOwner.cancel();contacts.clear();cancelAttachment()"))
  val service=source("LipInputMethodService")
  assertTrue(service.contains("captureStickerAttachment(asset"));assertTrue(service.contains("controller.request(asset,expected,contactCurrent)"))
  assertTrue(service.contains("if(stickerPanel===panel){stickerAttachment?.cancel();productionStickerAttachment?.cancel()}"))
  assertTrue(service.contains("fresh.matches(expected)&&session.manualGeneration()==generation&&contactCurrent()"))
 }
}
