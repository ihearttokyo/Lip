package dev.lip.keyboard
import org.junit.Test
import org.junit.Assert.*
class ToolbarActivationOwnerTest {
 private val connection=Any();private val mapping=Any();private val render=Any();private val transition=Any()
 private fun context(action:ToolbarAction=ToolbarAction.EMOJI,connection:Any?=this.connection,editor:Pair<Long,Long>?=1L to 0L,
  mapping:Any=this.mapping,render:Any=this.render,transition:Any=this.transition,start:Int=3,composing:Boolean=false)=
  ToolbarActivationContext(action,connection,editor,mapping,render,transition,start,start,1,0,1,"synthetic.example",KeyboardPage.LETTERS,composing,false,true,true,true)
 @Test fun oneActivationClaimsAndAdmitsExactlyOnce(){
  val owner=ToolbarActivationOwner();val c=context();val ticket=owner.begin(c);val claim=owner.claim(ticket,c)!!
  assertNull(owner.claim(ticket,c));assertTrue(owner.admit(claim,c,true));assertFalse(owner.admit(claim,c,true))
 }
 @Test fun changedMappingActionEditorViewOrCaretRefusesWithoutNewSlotLookup(){
  for(changed in listOf(context(mapping=Any()),context(action=ToolbarAction.STICKERS),context(connection=Any()),context(editor=2L to 0L),context(editor=1L to 2L),context(render=Any()),context(start=4),context(composing=true))){
   val owner=ToolbarActivationOwner();assertNull(owner.claim(owner.begin(context()),changed))
  }
 }
 @Test fun compositionOrMappingAbaStillChangesOpaqueAuthority(){
  val owner=ToolbarActivationOwner();val ticket=owner.begin(context())
  assertNull(owner.claim(ticket,context(transition=Any())))
  assertNull(owner.claim(ticket,context()))
 }
 @Test fun reentrantCleanupOrCancelEntireStreamCannotReviveClaimedActivation(){
  val owner=ToolbarActivationOwner();val claim=owner.claim(owner.begin(context()),context())!!
  owner.cancel();assertFalse(owner.admit(claim,context(),true))
  val fresh=owner.claim(owner.begin(context()),context())!!;assertTrue(owner.admit(fresh,context(),true))
 }
 @Test fun newerContactAndFailedFinalEligibilityConsumeWithoutFallback(){
  val owner=ToolbarActivationOwner();val old=owner.begin(context());val fresh=owner.begin(context(action=ToolbarAction.GIF))
  assertNull(owner.claim(old,context()));val claim=owner.claim(fresh,context(action=ToolbarAction.GIF))!!
  assertFalse(owner.admit(claim,context(action=ToolbarAction.GIF),false));assertFalse(owner.admit(claim,context(action=ToolbarAction.GIF),true))
 }
 @Test fun reserveBeforeExternalCaptureRefusesCanceledOrChangedOriginWithoutRevival(){
  val owner=ToolbarActivationOwner();val reserved=owner.begin(context())
  owner.cancel() // Simulated rootcancel/field departure then return during power/keyguard query.
  assertFalse(owner.isCurrent(reserved,context()));assertNull(owner.claim(reserved,context()))
  val fresh=owner.begin(context());assertTrue(owner.isCurrent(fresh,context()));owner.discard(reserved)
  assertTrue(owner.isCurrent(fresh,context()));assertTrue(owner.admit(owner.claim(fresh,context())!!,context(),true))
  val changed=owner.begin(context());assertFalse(owner.isCurrent(changed,context(editor=3L to 0L)))
 }

 @Test fun consumedLongActionStillProtectsPhysicalStreamUntilEveryPointerLifts(){
  val stream=ToolbarTouchStream();val owner=ToolbarActivationOwner();stream.begin()
  val claim=owner.claim(owner.begin(context(action=ToolbarAction.MICROPHONE)),context(action=ToolbarAction.MICROPHONE))!!
  assertTrue(owner.admit(claim,context(action=ToolbarAction.MICROPHONE),true));assertFalse(owner.hasContact())
  val second=stream.event(2,false,owner.hasContact());assertTrue(second.consume);assertTrue(second.cancel)
  assertTrue(stream.event(2,false,false).consume);assertTrue(stream.event(1,false,false).consume)
  stream.end();stream.begin();assertFalse(stream.event(1,false,false).consume)
 }
 @Test fun ordinaryTypingMultitouchDoesNotStartToolbarStream(){
  val stream=ToolbarTouchStream();assertFalse(stream.event(2,false,false).consume)
  assertTrue(stream.event(1,true,false).cancel);stream.end()
 }

}
