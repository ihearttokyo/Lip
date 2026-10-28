package dev.lip.keyboard

import org.junit.Assert.*
import org.junit.Test

/** Pure metadata/dispatch tests, never a clipboard/editor content reader. */
class TextEditingTest {
    private val state=TextEditingState(1,0,2,2,true,true,false,false,true,true)
    private val connection=Any();private val panel=Any()
    private fun context(value:TextEditingState=state,connection:Any?=this.connection,panel:Any=this.panel,editor:Pair<Long,Long>?=1L to 0L,
        field:Int=4,pkg:String?="owned.synthetic")=TextEditingContext(connection,editor,panel,field,pkg,value)
    @Test fun fullSurfaceMapsToRealPlatformCommandsWithoutTextOffsets() {
        assertEquals(android.R.id.selectAll,(TextEditingActions.command(TextEditingAction.SELECT_ALL,false) as TextEditingCommand.Menu).id)
        assertEquals(android.R.id.cut,(TextEditingActions.command(TextEditingAction.CUT,false) as TextEditingCommand.Menu).id)
        assertEquals(android.R.id.copy,(TextEditingActions.command(TextEditingAction.COPY,false) as TextEditingCommand.Menu).id)
        assertEquals(android.R.id.paste,(TextEditingActions.command(TextEditingAction.PASTE,false) as TextEditingCommand.Menu).id)
        assertEquals(TextEditingCommand.Key(android.view.KeyEvent.KEYCODE_DPAD_UP,0),TextEditingActions.command(TextEditingAction.UP,false))
        assertEquals(TextEditingCommand.Key(android.view.KeyEvent.KEYCODE_DPAD_DOWN,1),TextEditingActions.command(TextEditingAction.DOWN,true))
        assertEquals(TextEditingCommand.Key(android.view.KeyEvent.KEYCODE_DPAD_LEFT,1),TextEditingActions.command(TextEditingAction.LEFT,true))
        assertEquals(TextEditingCommand.Key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT,0),TextEditingActions.command(TextEditingAction.RIGHT,false))
        assertEquals(TextEditingCommand.Key(android.view.KeyEvent.KEYCODE_MOVE_HOME,0),TextEditingActions.command(TextEditingAction.LINE_START,false))
        assertEquals(TextEditingCommand.Key(android.view.KeyEvent.KEYCODE_MOVE_END,1),TextEditingActions.command(TextEditingAction.LINE_END,true))
        assertEquals(TextEditingCommand.Key(android.view.KeyEvent.KEYCODE_MOVE_HOME,android.view.KeyEvent.META_CTRL_ON),TextEditingActions.command(TextEditingAction.TEXT_START,false))
        assertEquals(TextEditingCommand.Key(android.view.KeyEvent.KEYCODE_MOVE_END,android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_SHIFT_ON),TextEditingActions.command(TextEditingAction.TEXT_END,true))
        assertNull(TextEditingActions.command(TextEditingAction.SELECT_MODE,true))
    }
    @Test fun privateNoLearningUnknownAndDisconnectedRefuseEveryAction() {
        for(type in listOf(0,0x81,0x91,0xe1,0x12,5))for(action in TextEditingAction.entries)
            assertFalse(TextEditingActions.available(action,state.copy(inputType=type,start=0,end=4)))
        for(action in TextEditingAction.entries){
            assertFalse(TextEditingActions.available(action,state.copy(imeOptions=0x1000000)))
            assertFalse(TextEditingActions.available(action,state.copy(connected=false)))
            assertFalse(TextEditingActions.available(action,state.copy(start=-1)))
        }
        assertTrue(TextEditingActions.allowed(state.copy(inputType=2)))
        assertTrue(TextEditingActions.allowed(state.copy(inputType=3)))
        assertTrue(TextEditingActions.allowed(state.copy(inputType=4)))
    }
    @Test fun hiddenBusyCompositionAndLockNeverEnableCommands() {
        for(restricted in listOf(state.copy(visible=false),state.copy(busy=true),state.copy(composing=true),state.copy(interactive=false),state.copy(unlocked=false)))
            for(action in TextEditingAction.entries)assertFalse(TextEditingActions.available(action,restricted))
    }
    @Test fun cutCopyRequireSelectionInEitherDirectionButPasteDoesNotReadClipboard() {
        for(action in listOf(TextEditingAction.CUT,TextEditingAction.COPY)){
            assertFalse(TextEditingActions.available(action,state))
            assertTrue(TextEditingActions.available(action,state.copy(start=7,end=5)))
            assertTrue(TextEditingActions.available(action,state.copy(start=5,end=7)))
        }
        assertTrue(TextEditingActions.available(TextEditingAction.PASTE,state))
        assertTrue(TextEditingActions.available(TextEditingAction.SELECT_ALL,state))
    }
    @Test fun oneActivationClaimsOnceAndFreshAccessibilityActivationIsSeparate() {
        val owner=TextEditingOwner();val first=owner.begin(TextEditingAction.RIGHT,true,context(),1)!!
        val claim=owner.claim(first,context(),2)!!
        assertTrue(claim.selecting);assertSame(connection,claim.connection);assertNull(owner.claim(first,context(),3))
        val second=owner.begin(TextEditingAction.RIGHT,false,context(),4)!!
        assertNotNull(owner.claim(second,context(),4))
    }
    @Test fun caretAndEditorAbaNeverRevalidateOldContact() {
        val owner=TextEditingOwner();val captured=context()
        val ticket=owner.begin(TextEditingAction.PASTE,false,captured,1)!!
        assertNull(owner.claim(ticket,context(editor=1L to 2L),2)) // Away/back to same coordinates.
        assertNull(owner.claim(ticket,captured,3))
        val restart=owner.begin(TextEditingAction.SELECT_ALL,false,captured,4)!!
        assertNull(owner.claim(restart,context(editor=2L to 0L),5))
    }
    @Test fun anotherConnectionFieldPanelOrChangedSelectionRefusesHeldClick() {
        val changed=listOf(context(connection=Any()),context(panel=Any()),context(field=5),context(pkg="other.synthetic"),
            context(state.copy(start=3,end=3)),context(state.copy(inputType=2)),context(state.copy(imeOptions=1)))
        for(value in changed){val owner=TextEditingOwner();val ticket=owner.begin(TextEditingAction.RIGHT,false,context(),1)!!;assertNull(owner.claim(ticket,value,2))}
    }
    @Test fun missingIdentityAndExpiredOrClockReversedContactRefuse() {
        val owner=TextEditingOwner()
        for(value in listOf(context(connection=null),context(editor=null),context(pkg=null),context(pkg="")))
            assertNull(owner.begin(TextEditingAction.SELECT_ALL,false,value,1))
        val old=owner.begin(TextEditingAction.RIGHT,false,context(),1)!!;assertNull(owner.claim(old,context(),30_002))
        val reversed=owner.begin(TextEditingAction.RIGHT,false,context(),10)!!;assertNull(owner.claim(reversed,context(),9))
    }
    @Test fun canceledSupersededAndClosedTicketsNeverDispatch() {
        val owner=TextEditingOwner();val first=owner.begin(TextEditingAction.RIGHT,false,context(),1)!!
        val next=owner.begin(TextEditingAction.LEFT,false,context(),2)!!
        owner.cancel(first);assertNull(owner.claim(first,context(),3));assertNotNull(owner.claim(next,context(),3))
        val canceled=owner.begin(TextEditingAction.PASTE,false,context(),4)!!;owner.cancel();assertNull(owner.claim(canceled,context(),5))
    }
    @Test fun keyUpAlwaysCleansExactOldPortAfterDownThrowsOrChangesFocus() {
        val events=mutableListOf<String>();var focused="old"
        val old=object:TextEditingDispatch.Port{
            override fun menu(id:Int):Boolean=error("Unexpected menu fallback")
            override fun key(down:Boolean,code:Int,modifiers:Int):Boolean{
                events+="old:${if(down)"down"else"up"}:$code:$modifiers"
                if(down){focused="new";throw IllegalStateException("synthetic dispatch failure")};return true
            }
        }
        assertEquals(TextEditingOutcome.UNCONFIRMED,TextEditingDispatch.execute(TextEditingCommand.Key(21,0),old))
        assertEquals("new",focused);assertEquals(listOf("old:down:21:0","old:up:21:0"),events)
    }
    @Test fun falseDownStillSendsOneUpAndNoFallbackOrRepeat() {
        val events=mutableListOf<Boolean>()
        val port=object:TextEditingDispatch.Port{
            override fun menu(id:Int):Boolean=error("Unexpected menu")
            override fun key(down:Boolean,code:Int,modifiers:Int):Boolean{events+=down;return !down}
        }
        assertEquals(TextEditingOutcome.UNCONFIRMED,TextEditingDispatch.execute(TextEditingCommand.Key(22,0),port))
        assertEquals(listOf(true,false),events)
    }
    @Test fun hostMenuFalseOrThrowIsAmbiguousOneRequestNotSuccessOrRetry() {
        var calls=0
        val port=object:TextEditingDispatch.Port{
            override fun menu(id:Int):Boolean{calls++;if(calls==1)return false;throw IllegalStateException("synthetic post-effect refusal")}
            override fun key(down:Boolean,code:Int,modifiers:Int):Boolean=error("Unexpected key fallback")
        }
        assertEquals(TextEditingOutcome.UNCONFIRMED,TextEditingDispatch.execute(TextEditingCommand.Menu(16908322),port));assertEquals(1,calls)
        assertEquals(TextEditingOutcome.UNCONFIRMED,TextEditingDispatch.execute(TextEditingCommand.Menu(16908320),port));assertEquals(2,calls)
    }
    @Test fun successfulBooleanMeansOnlyDispatchedAndTicketDescriptionsContainNoContext() {
        val port=object:TextEditingDispatch.Port{override fun menu(id:Int)=true;override fun key(down:Boolean,code:Int,modifiers:Int)=true}
        assertEquals(TextEditingOutcome.DISPATCHED,TextEditingDispatch.execute(TextEditingCommand.Menu(16908319),port))
        val owner=TextEditingOwner();val captured=context(pkg="do.not.log.this");val ticket=owner.begin(TextEditingAction.LEFT,false,captured,0)!!
        assertFalse(captured.toString().contains("do.not.log.this"));assertFalse(ticket.toString().contains("do.not.log.this"))
    }
    @Test fun nonRuntimeDownFailureStillReleasesOldPortAndReturnsUnconfirmed() {
        val events=mutableListOf<Boolean>()
        val port=object:TextEditingDispatch.Port{
            override fun menu(id:Int):Boolean=error("Unexpected menu")
            override fun key(down:Boolean,code:Int,modifiers:Int):Boolean{events+=down;if(down)throw AssertionError("synthetic unknown failure");return true}
        }
        assertEquals(TextEditingOutcome.UNCONFIRMED,TextEditingDispatch.execute(TextEditingCommand.Key(19,0),port))
        assertEquals(listOf(true,false),events)
    }
    @Test fun upFailureNeverStartsAnotherDownOrFallback() {
        val events=mutableListOf<Boolean>()
        val port=object:TextEditingDispatch.Port{
            override fun menu(id:Int):Boolean=error("Unexpected fallback")
            override fun key(down:Boolean,code:Int,modifiers:Int):Boolean{events+=down;if(!down)throw IllegalStateException("synthetic cleanup refusal");return true}
        }
        assertEquals(TextEditingOutcome.UNCONFIRMED,TextEditingDispatch.execute(TextEditingCommand.Key(20,0),port))
        assertEquals(listOf(true,false),events)
    }

    @Test fun cleanupDepartureBeforeFinalAdmissionSendsNeitherDownUpNorMenu() {
        for(command in listOf(TextEditingCommand.Menu(16908322),TextEditingCommand.Key(21,0))){
            val owner=TextEditingOwner();val captured=context();val ticket=owner.begin(TextEditingAction.PASTE,false,captured,1)!!
            val claim=owner.claim(ticket,captured,2)!!
            var calls=0
            val port=object:TextEditingDispatch.Port{
                override fun menu(id:Int):Boolean{calls++;return true}
                override fun key(down:Boolean,code:Int,modifiers:Int):Boolean{calls++;return true}
            }
            val afterCleanup=context(connection=null,editor=null) // Reentrant onFinishInput during popup cleanup.
            owner.cancel()
            assertEquals(TextEditingOutcome.REFUSED,TextEditingDispatch.execute(command,port){owner.admit(claim,captured.same(afterCleanup))})
            assertEquals(0,calls);assertFalse(owner.admit(claim,true));assertNull(owner.claim(ticket,captured,3))
        }
        val owner=TextEditingOwner();val captured=context();val claim=owner.claim(owner.begin(TextEditingAction.RIGHT,false,captured,1)!!,captured,2)!!
        owner.cancel() // Even an equal-value context after a revoke cannot revive a claimed action.
        assertFalse(owner.admit(claim,true))
    }
    @Test fun onceAdmittedDownDepartureStillReleasesOldPortButCannotReadmitClaim() {
        val owner=TextEditingOwner();val captured=context();val claim=owner.claim(owner.begin(TextEditingAction.RIGHT,false,captured,1)!!,captured,2)!!
        val events=mutableListOf<Boolean>()
        val oldPort=object:TextEditingDispatch.Port{
            override fun menu(id:Int):Boolean=error("Unexpected menu")
            override fun key(down:Boolean,code:Int,modifiers:Int):Boolean{events+=down;if(down)owner.cancel();return true}
        }
        assertEquals(TextEditingOutcome.DISPATCHED,TextEditingDispatch.execute(TextEditingCommand.Key(22,0),oldPort){owner.admit(claim,true)})
        assertEquals(listOf(true,false),events)
        assertEquals(TextEditingOutcome.REFUSED,TextEditingDispatch.execute(TextEditingCommand.Key(22,0),oldPort){owner.admit(claim,true)})
        assertEquals(listOf(true,false),events)
    }

    @Test fun detachedThenReattachedSamePanelCannotReviveClaimedIntent() {
        val owner=TextEditingOwner();val captured=context()
        val claim=owner.claim(owner.begin(TextEditingAction.RIGHT,false,captured,1)!!,captured,2)!!
        owner.closePanel(panel) // Actual panel lifetime callback, after contact was removed for invocation.
        var calls=0
        val port=object:TextEditingDispatch.Port{override fun menu(id:Int):Boolean{calls++;return true};override fun key(down:Boolean,code:Int,modifiers:Int):Boolean{calls++;return true}}
        assertEquals(TextEditingOutcome.REFUSED,TextEditingDispatch.execute(TextEditingCommand.Key(22,0),port){owner.admit(claim,captured.same(context()))})
        assertEquals(0,calls)
        val newer=Any();val fresh=context(panel=newer)
        val freshTicket=owner.begin(TextEditingAction.LEFT,false,fresh,3)!!
        owner.closePanel(panel) // Old panel closing after a new incarnation must not cancel it.
        val freshClaim=owner.claim(freshTicket,fresh,4)!!;assertTrue(owner.admit(freshClaim,true))
    }
    @Test fun actualNativeCloseHookRevokesClaimedLifetimeWithExactServiceIncarnationGuard() {
        fun source(name:String):String {
            val candidates=listOf(java.io.File("src/main/java/dev/lip/keyboard/$name"),java.io.File("app/src/main/java/dev/lip/keyboard/$name"))
            return candidates.first{it.isFile}.readText()
        }
        val panelSource=source("TextEditingPanel.kt");val close=panelSource.substringAfter("fun close(){").substringBefore("override fun onDetachedFromWindow")
        assertTrue(close.contains("lifetimeClosed()"));assertTrue(close.indexOf("lifetimeClosed()")<close.indexOf("contacts.values"))
        assertTrue(panelSource.contains("onDetachedFromWindow(){close();"))
        val service=source("LipInputMethodService.kt")
        assertTrue(service.contains("if(textEditingPanel===panel&&textEditingToken===token)textEditingOwner.closePanel(token)"))
        assertTrue(service.contains("panel.ownsInteraction(interaction)&&captured.same(fresh)"))
    }

    @Test fun unsplitMultiPointerStreamCancelsAllOnceAndConsumesEveryRemainingLift() {
        val gate=TextEditingTouchGate()
        assertFalse(gate.event(TextEditingTouchGate.Action.DOWN,1).consume)
        val multi=gate.event(TextEditingTouchGate.Action.POINTER_DOWN,2);assertTrue(multi.consume);assertTrue(multi.cancelAll);assertTrue(gate.blocked)
        assertFalse(gate.event(TextEditingTouchGate.Action.MOVE,2).cancelAll)
        assertTrue(gate.event(TextEditingTouchGate.Action.POINTER_UP,2).consume);assertTrue(gate.blocked)
        assertTrue(gate.event(TextEditingTouchGate.Action.MOVE,1).consume)
        assertTrue(gate.event(TextEditingTouchGate.Action.UP,1).consume);assertFalse(gate.blocked)
        assertFalse(gate.event(TextEditingTouchGate.Action.DOWN,1).consume)
        assertTrue(gate.event(TextEditingTouchGate.Action.CANCEL,1).cancelAll)
    }
    @Test fun splitChildBDownNeverGetsTicketAndNewSingleGestureCanActAfterAllLift() {
        val owner=TextEditingOwner();val gate=TextEditingTouchGate();val captured=context(state.copy(start=0,end=4))
        var childDowns=0
        fun parentDown(action:TextEditingTouchGate.Action,count:Int):TextEditingOwner.Ticket? {
            val decision=gate.event(action,count)
            if(decision.cancelAll)owner.closePanel(panel)
            if(decision.consume)return null
            childDowns++;return owner.begin(TextEditingAction.CUT,false,captured,childDowns.toLong())
        }
        val first=parentDown(TextEditingTouchGate.Action.DOWN,1)!!
        assertNull(parentDown(TextEditingTouchGate.Action.POINTER_DOWN,2));assertEquals(1,childDowns)
        assertTrue(gate.event(TextEditingTouchGate.Action.POINTER_UP,2).consume)
        assertTrue(gate.event(TextEditingTouchGate.Action.UP,1).consume)
        assertNull(owner.claim(first,captured,3))
        val fresh=parentDown(TextEditingTouchGate.Action.DOWN,1)!!
        assertTrue(owner.admit(owner.claim(fresh,captured,4)!!,true));assertEquals(2,childDowns)
    }
    @Test fun actualNativeParentGatesBeforeSplitDispatchAndCancelsClaimedLifetime() {
        val source=listOf(java.io.File("src/main/java/dev/lip/keyboard/TextEditingPanel.kt"),java.io.File("app/src/main/java/dev/lip/keyboard/TextEditingPanel.kt")).first{it.isFile}.readText()
        val dispatch=source.substringAfter("override fun dispatchTouchEvent").substringBefore("private fun button")
        assertTrue(dispatch.contains("touchGate.event(action,event.pointerCount)"))
        assertTrue(dispatch.contains("lifetimeClosed()"));assertTrue(dispatch.contains("MotionEvent.ACTION_CANCEL;super.dispatchTouchEvent(cancellation)"))
        assertTrue(dispatch.indexOf("touchGate.event")<dispatch.indexOf("super.dispatchTouchEvent"))
        assertTrue(source.contains("!touchGate.blocked&&this@TextEditingPanel.isAttachedToWindow"))
    }

    private class ChordPort(val capability:()->Boolean={true},val continuation:()->Boolean={true},val event:(Boolean,Int)->Boolean={_,_->true}):TextEditingDispatch.Port {
        val events=mutableListOf<Triple<Boolean,Int,Int>>()
        override fun menu(id:Int):Boolean=error("No menu fallback")
        override fun chordedModifiers()=capability()
        override fun continueKeys()=continuation()
        override fun key(down:Boolean,code:Int,modifiers:Int):Boolean {events+=Triple(down,code,modifiers);return event(down,code)}
    }
    private val shiftDown=Triple(true,android.view.KeyEvent.KEYCODE_SHIFT_LEFT,android.view.KeyEvent.META_SHIFT_ON)
    private val shiftUp=Triple(false,android.view.KeyEvent.KEYCODE_SHIFT_LEFT,0)
    @Test fun selectionUsesBoundedNativeShiftChordWithCtrlPreservedAndNoStickyState() {
        val port=ChordPort()
        val command=TextEditingCommand.Key(123,0x1001)
        repeat(2){assertEquals(TextEditingOutcome.DISPATCHED,TextEditingDispatch.execute(command,port))}
        val chord=listOf(shiftDown,Triple(true,123,0x1001),Triple(false,123,0x1001),shiftUp)
        assertEquals(chord+chord,port.events)
    }
    @Test fun unsupportedOrUnknownModifierCapabilitySendsZeroEventsAndNeverAdmits() {
        for(capability in listOf<()->Boolean>({false},{throw AssertionError("Unknown virtual keyboard behavior")})){
            var admissions=0;val port=ChordPort(capability=capability)
            assertEquals(TextEditingOutcome.REFUSED,TextEditingDispatch.execute(TextEditingCommand.Key(21,1),port){admissions++;true})
            assertEquals(0,admissions);assertTrue(port.events.isEmpty())
        }
    }
    @Test fun capabilityExternalQueryPrecedesFinalFreshAdmission() {
        val order=mutableListOf<String>();var revoked=false
        val port=ChordPort(capability={order+="capability";revoked=true;true})
        assertEquals(TextEditingOutcome.REFUSED,TextEditingDispatch.execute(TextEditingCommand.Key(21,1),port){order+="admit";!revoked})
        assertEquals(listOf("capability","admit"),order);assertTrue(port.events.isEmpty())
    }
    @Test fun departureAfterShiftDownSuppressesArrowButAlwaysReleasesOldShiftAndIsUnconfirmed() {
        var current=true
        val port=ChordPort(continuation={current},event={down,code->if(down&&code==59)current=false;true})
        assertEquals(TextEditingOutcome.UNCONFIRMED,TextEditingDispatch.execute(TextEditingCommand.Key(21,1),port))
        assertEquals(listOf(shiftDown,shiftUp),port.events)
        val throws=ChordPort(continuation={throw AssertionError("Unavailable current context")})
        assertEquals(TextEditingOutcome.UNCONFIRMED,TextEditingDispatch.execute(TextEditingCommand.Key(21,1),throws))
        assertEquals(listOf(shiftDown,shiftUp),throws.events)
    }
    @Test fun falseOrThrowingShiftDownNeverSendsArrowAndAlwaysAttemptsOldShiftUp() {
        for(fail in listOf(false,true)){
            val port=ChordPort(event={down,_->if(down){if(fail)throw AssertionError("Unknown partial modifier dispatch");false}else true})
            assertEquals(TextEditingOutcome.UNCONFIRMED,TextEditingDispatch.execute(TextEditingCommand.Key(21,1),port))
            assertEquals(listOf(shiftDown,shiftUp),port.events)
        }
    }
    @Test fun arrowFailureStillAttemptsArrowUpThenShiftUpEvenIfArrowUpAlsoThrows() {
        for(fail in listOf(false,true)){
            val port=ChordPort(event={down,code->if(code!=59){if(fail||!down)throw AssertionError("Unknown movement dispatch");false}else true})
            assertEquals(TextEditingOutcome.UNCONFIRMED,TextEditingDispatch.execute(TextEditingCommand.Key(21,1),port))
            assertEquals(listOf(shiftDown,Triple(true,21,1),Triple(false,21,1),shiftUp),port.events)
        }
    }
    @Test fun shiftUpFailureNeverClaimsSuccessfulChordOrRetriesAnything() {
        val port=ChordPort(event={down,code->if(!down&&code==59)throw AssertionError("Unknown modifier release");true})
        assertEquals(TextEditingOutcome.UNCONFIRMED,TextEditingDispatch.execute(TextEditingCommand.Key(21,1),port))
        assertEquals(listOf(shiftDown,Triple(true,21,1),Triple(false,21,1),shiftUp),port.events)
    }
    @Test fun normalMovementDoesNotQueryOrDispatchModifierKeysAndProductionQueriesActualVirtualDevice() {
        val port=ChordPort(capability={error("Normal command must not query modifiers")},continuation={error("No modifier continuation")})
        assertEquals(TextEditingOutcome.DISPATCHED,TextEditingDispatch.execute(TextEditingCommand.Key(21,0),port))
        assertEquals(listOf(Triple(true,21,0),Triple(false,21,0)),port.events)
        val source=listOf(java.io.File("src/main/java/dev/lip/keyboard/LipInputMethodService.kt"),java.io.File("app/src/main/java/dev/lip/keyboard/LipInputMethodService.kt")).first{it.isFile}.readText()
        assertTrue(source.contains("KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD).modifierBehavior==android.view.KeyCharacterMap.MODIFIER_BEHAVIOR_CHORDED"))
        assertTrue(source.contains("continuedContext?.same(fresh)==true"))
    }

    @Test fun canceledEntireMultiTouchStreamDuringShiftCannotAbaReviveMovement() {
        val epoch=TextEditingInteractionEpoch();val gate=TextEditingTouchGate()
        val before=epoch.snapshot(true)!!
        val port=ChordPort(continuation={epoch.owns(before,!gate.blocked)},event={down,code->
            if(down&&code==59){
                assertFalse(gate.event(TextEditingTouchGate.Action.DOWN,1).consume)
                if(gate.event(TextEditingTouchGate.Action.POINTER_DOWN,2).cancelAll)epoch.invalidate()
                assertNull(epoch.snapshot(!gate.blocked))
                gate.event(TextEditingTouchGate.Action.POINTER_UP,2);gate.event(TextEditingTouchGate.Action.UP,1)
                assertFalse(gate.blocked) // Equal visible flags do not restore the old epoch.
            };true
        })
        assertEquals(TextEditingOutcome.UNCONFIRMED,TextEditingDispatch.execute(TextEditingCommand.Key(21,1),port))
        assertEquals(listOf(shiftDown,shiftUp),port.events);assertFalse(epoch.owns(before,true))
        val fresh=epoch.snapshot(true)!!;assertTrue(epoch.owns(fresh,true))
        val freshPort=ChordPort(continuation={epoch.owns(fresh,true)})
        assertEquals(TextEditingOutcome.DISPATCHED,TextEditingDispatch.execute(TextEditingCommand.Key(21,1),freshPort))
        assertEquals(listOf(shiftDown,Triple(true,21,1),Triple(false,21,1),shiftUp),freshPort.events)
    }
    @Test fun actualPanelCancellationEpochFencesBothFinalAdmissionAndPostShiftContinuation() {
        fun source(name:String)=listOf(java.io.File("src/main/java/dev/lip/keyboard/$name"),java.io.File("app/src/main/java/dev/lip/keyboard/$name")).first{it.isFile}.readText()
        val panel=source("TextEditingPanel.kt")
        assertTrue(panel.substringAfter("if(decision.cancelAll){").substringBefore("contacts.values").contains("interactionEpoch.invalidate()"))
        assertTrue(panel.substringAfter("fun close(){").substringBefore("contacts.values").contains("interactionEpoch.invalidate()"))
        assertTrue(panel.contains("interactionEpoch.owns(snapshot,!closed&&!touchGate.blocked)"))
        val service=source("LipInputMethodService.kt").substringAfter("private fun performTextEditing").substringBefore("private fun renderTextEditing")
        assertTrue(service.indexOf("val interaction=panel.interactionSnapshot()")<service.indexOf("val captured=textEditingContext()"))
        assertTrue(service.contains("panel.ownsInteraction(interaction)&&continuedContext?.same(fresh)==true"))
        assertTrue(service.contains("panel.ownsInteraction(interaction)&&captured.same(fresh)"))
        assertTrue(service.contains("textEditingOwner.admit(claim,textEditingPanel===panel&&panel.ownsInteraction(interaction))"))
    }

}
