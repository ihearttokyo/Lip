package dev.lip.keyboard
import org.junit.Test
import org.junit.Assert.*
class FloatingIntegrationPolicyTest {
 @Test fun consumedUpCannotPersistAfterCancelOrNewHandleEvenWhenEditorStateReturnsEqual(){
  val authority=FloatingHandleAuthority();val old=authority.reserve();var writes=0
  fun writeAfterQuery(query:()->Unit){query();if(authority.current(old))writes++}
  writeAfterQuery{authority.cancel()};assertEquals(0,writes)
  val fresh=authority.reserve();assertTrue(authority.current(fresh));assertFalse(authority.current(old))
  assertTrue(authority.current(fresh));authority.cancel();assertFalse(authority.current(fresh))
 }
 @Test fun newerContactSurvivesStaleOldCallbackDiscard(){
  val authority=FloatingHandleAuthority();val old=authority.reserve();val new=authority.reserve()
  if(authority.current(old))authority.cancel()
  assertTrue(authority.current(new));assertFalse(authority.current(old))
 }
 @Test fun wrapRequestDoesNotRetireFullViewportBeforeActualDockTraversal(){
  assertFalse(FloatingWindowTransition.dockTraversed(false,false,0,2400,126,true))
  assertFalse(FloatingWindowTransition.dockTraversed(true,true,800,2400,126,true))
  assertFalse(FloatingWindowTransition.dockTraversed(true,true,800,800,126,false))
  assertTrue(FloatingWindowTransition.dockTraversed(true,true,800,926,126,true))
  assertFalse(FloatingWindowTransition.dockTraversed(true,true,800,926,Int.MAX_VALUE,true))
 }
 @Test fun actualInsetsAndHandleCallbacksAreWiredToFinalAuthority(){
  fun source(name:String)=listOf(java.io.File("src/main/java/dev/lip/keyboard/$name.kt"),java.io.File("app/src/main/java/dev/lip/keyboard/$name.kt")).first{it.isFile}.readText()
  val service=source("LipInputMethodService");val frame=source("FloatingKeyboardFrame")
  assertTrue(service.contains("floatingWindowOwned=true"));assertTrue(service.contains("FloatingWindowTransition.dockTraversed"))
  assertTrue(service.contains("touchableRegion.setEmpty()"));assertTrue(service.contains("floatingFrame?.ownedRectInWindow()"))
  assertTrue(service.contains("floatingCurrent(origin)&&contactCurrent()&&floatingSettings.saveGeometry"))
  assertTrue(service.contains("floatingCurrent(prepared)&&contactCurrent()&&floatingSettings.save"))
  assertTrue(service.contains("if(floatingBusy()&&floatingSnapshot?.revision!=requestedFloating.revision)"))
  assertTrue(frame.contains("authority.current(op.ticket)||(operation!=null&&operation!==op)"))
  assertTrue(frame.indexOf("operation=op")<frame.indexOf("val prepared=prepare(original)"))
  assertTrue(frame.contains("if(!viewportCurrent()||closed"))
  assertTrue(frame.contains("operation=op\n        if(!current(origin)||operation!==op||!authority.current(op.ticket))"))
  assertTrue(frame.contains("authority.dispatch(op.ticket,{contactCurrent()},{settings(origin,contactCurrent)})"))
  assertTrue(frame.contains("failedViewport===viewportToken&&reportingNoFit&&fitted==null"))
  assertFalse(service.contains("main.post{if(!destroyed&&floatingFrame===floating){setInputView(onCreateInputView())"))
  assertTrue(service.contains("if(floatingFrame!==floating||!floatingCurrent(origin)||!contactCurrent())false"));assertFalse(frame.contains("scaleX="));assertFalse(frame.contains("scaleY="))
 }
 @Test fun busyOrReentrantCanceledAccessibleActivationNeverOpensSettings(){
  val authority=FloatingHandleAuthority();var launches=0;val busy=authority.reserve()
  assertFalse(authority.dispatch(busy,{false},{launches++;true}));assertEquals(0,launches)
  val canceled=authority.reserve();assertFalse(authority.dispatch(canceled,{authority.cancel();true},{launches++;true}));assertEquals(0,launches)
  val fresh=authority.reserve();assertTrue(authority.dispatch(fresh,{true},{launches++;true}));assertEquals(1,launches)
  assertFalse(authority.dispatch(fresh,{true},{launches++;true}));assertEquals(1,launches)
 }

}
