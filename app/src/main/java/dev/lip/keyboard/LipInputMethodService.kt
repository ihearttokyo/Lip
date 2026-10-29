package dev.lip.keyboard

import android.content.Intent
import android.icu.text.BreakIterator
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.lip.Dictation
import dev.lip.MainActivity
import dev.lip.Palette
import dev.lip.Phase
import dev.lip.column
import dev.lip.dp
import dev.lip.label

/** Independent IME; never patches Gboard or selects itself. Clipboard text reads require explicit history consent and eligible context. */
class LipInputMethodService : InputMethodService() {
    private val main = Handler(Looper.getMainLooper())
    private val toolbarSettings by lazy{ToolbarPreferencesStore(this)}
    private var toolbarSnapshot:ToolbarSnapshot?=null
    private var toolbarRenderToken=Any()
    private var toolbarTransition=Any()
    private val toolbarOwner=ToolbarActivationOwner()
    private val toolbarTouchStream=ToolbarTouchStream()
    private var textEditingTool:Button?=null
    private var stickerTool:Button?=null
    private val toolbarChanged:()->Unit={
        // Revision changes synchronously; old callbacks fail even before this UI post runs.
        main.post{if(!destroyed){invalidateToolbarContacts();if(isInputViewShown)setInputView(onCreateInputView())}}
        Unit
    }
    private fun invalidateToolbarContacts(){toolbarOwner.cancel();toolbarTransition=Any()}
    private fun toolbarCpuContext(action:ToolbarAction,interactive:Boolean=true,unlocked:Boolean=true):ToolbarActivationContext = ToolbarActivationContext(action,currentInputConnection,session.manualGeneration(),toolbarSettings.snapshot().revision,
            toolbarRenderToken,toolbarTransition,selectionStart,selectionEnd,info?.inputType?:0,info?.imeOptions?:0,
            info?.fieldId?:0,info?.packageName,page,composingStart>=0||composingEnd>=0,dictation.busy,
            !destroyed&&isInputViewShown,interactive,unlocked)
    private fun toolbarContext(action:ToolbarAction):ToolbarActivationContext {
        val interactive=getSystemService(android.os.PowerManager::class.java).isInteractive
        val unlocked=!getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked
        return toolbarCpuContext(action,interactive,unlocked)
    }
    private val agentSettings by lazy { dev.lip.agent.AgentHandoffPreferencesStore(this) }
    private var agentController: dev.lip.agent.AgentShortcutController? = null
    private var agentSlot: GlassKeyButton? = null
    private var agentFixtureAdapter: dev.lip.agent.AgentAdapter? = null
    private val agentResultObserver: () -> Unit = { agentController?.sourceChanged(); refreshAgentShortcut() }
    private fun agentEligible(): Boolean = !destroyed && isInputViewShown && policy().voice && !dictation.busy &&
        !swipePending && !localSearchActive() && gifOwner.current == null
    private fun refreshAgentShortcut() {
        val slot = agentSlot ?: return
        val pinned = agentController?.pinned() == true
        slot.icon = if (pinned) KeyIcon.AGENT else KeyIcon.TRANSLATE
        slot.contentDescription = if (pinned) "Send last completed Lip dictation to chosen agent; hold for effort choices"
            else "Local translation not yet available in this English preview"
        slot.isEnabled = toolbarSnapshot?.valid==true && pinned && agentEligible()
        slot.stateDescription = if (pinned) "Explicit agent action; no field or clipboard text is used" else "Not available in this preview"
    }
    /** In-process disposable instrumentation only. No production adapter is
     * admitted by a saved route enum; release builds cannot use this fixture.
     */
    internal fun configureAgentFixture(adapter: dev.lip.agent.AgentAdapter?) {
        check(debuggable) { "Synthetic adapter requires a debug build" }
        agentFixtureAdapter = adapter
        agentController?.bindUi(Any())
        refreshAgentShortcut()
    }
    private val presentationStore by lazy{KeyboardPreferencesStore(this)}
    private var presentation=KeyboardPreferences()
    private val stickerPreferences by lazy{StickerPreferencesStore(this)}
    private var stickerScale=KeyboardScale.SYSTEM
    private var stickerPanel:StickerPanel?=null
    private var stickerBrowser:StickerBrowserController?=null
    private var stickerAttachment:StickerAttachmentController?=null
    private var productionStickerAttachment:ProductionStickerAttachmentController?=null
    private fun stickerSafety()=ProductionStickerSafety(info?.inputType?:0,info?.imeOptions?:0,
        !destroyed&&isInputViewShown,getSystemService(android.os.PowerManager::class.java).isInteractive,
        !getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked,
        composingStart>=0||composingEnd>=0||correction!=null,
        dictation.busy||voiceTicket!=null||swipePending||localSearchActive()||gifOwner.current!=null)
    private fun productionStickerAttachContext():ProductionStickerAttachContext? {
        if(page!=KeyboardPage.STICKERS)return null
        val safety=stickerSafety();if(!safety.eligible)return null // Before any bounded editor text read.
        val libraryEpoch=dev.lip.stickers.StickerLibraryRevision.snapshot()?:return null
        val attribute=info?:return null;val bindingUid=currentInputBinding?.uid?:return null
        val connection=currentInputConnection?:return null;val generation=session.manualGeneration()?:return null
        val token=stickerWindowToken;val start=selectionStart;val end=selectionEnd
        val types=attribute.contentMimeTypes
        if(!StickerHandoffMime.accepts("image/png",types)&&!StickerHandoffMime.accepts("image/webp",types))return null
        val receiver=ProductionStickerReceiver.capture(this,attribute.packageName,bindingUid)?:return null
        val surrounding=runCatching{connection.getSurroundingText(32,32,0)}.getOrNull()?:return null
        val cursor=StickerCursorSnapshot.capture(attribute.packageName,attribute.fieldId,surrounding.text,
            surrounding.selectionStart,surrounding.selectionEnd,surrounding.offset,start,end)?:return null
        if(currentInputConnection!==connection||currentInputBinding?.uid!=bindingUid||info!==attribute||
            session.manualGeneration()!=generation||stickerWindowToken!==token||selectionStart!=start||selectionEnd!=end||
            !dev.lip.stickers.StickerLibraryRevision.isCurrent(libraryEpoch)||stickerSafety()!=safety)return null
        return ProductionStickerAttachContext(connection,cursor,token,receiver,types,safety,libraryEpoch)
    }
    private fun stickerAttachmentAvailable()=if(StickerPrivateTransport.enabled(this))stickerAttachContext()?.composing==false else productionStickerAttachContext()!=null
    private fun stickerAttachmentBusy()=stickerAttachment?.busy==true||productionStickerAttachment?.busy==true
    private fun stickerAttachContext():StickerAttachContext? {
        if(destroyed||page!=KeyboardPage.STICKERS||!isInputViewShown||!policy().voice||dictation.busy||voiceTicket!=null||swipePending||
            localSearchActive()||gifOwner.current!=null||info?.packageName!=StickerPrivateTransport.RECEIVER)return null
        val types=info?.contentMimeTypes
        if(!StickerHandoffMime.accepts("image/png",types)&&!StickerHandoffMime.accepts("image/webp",types))return null
        val target=runCatching{StickerPrivateTransport.receiver(this)}.getOrNull()?:return null
        val connection=currentInputConnection?:return null
        val surrounding=runCatching{connection.getSurroundingText(32,32,0)}.getOrNull()?:return null
        val cursor=StickerCursorSnapshot.capture(info?.packageName?:return null,info?.fieldId?:return null,
            surrounding.text,surrounding.selectionStart,surrounding.selectionEnd,surrounding.offset,
            selectionStart,selectionEnd)?:return null
        return StickerAttachContext(connection,cursor,stickerWindowToken,target,types,composingStart>=0||composingEnd>=0)
    }
    private fun captureStickerAttachment(asset:StickerAssetIdentity,contactCurrent:()->Boolean):(() -> Unit)? {
        if(!contactCurrent())return null
        if(StickerPrivateTransport.enabled(this))return {if(contactCurrent())requestStickerOriginal(asset)}
        val generation=session.manualGeneration()?:return null
        val expected=productionStickerAttachContext()?:return null
        if(!contactCurrent()||session.manualGeneration()!=generation)return null
        return {
            val fresh=productionStickerAttachContext()
            if(fresh!=null&&fresh.matches(expected)&&session.manualGeneration()==generation&&contactCurrent())
                requestStickerOriginal(asset,expected,contactCurrent)
        }
    }
    private fun requestStickerOriginal(asset:StickerAssetIdentity,expected:ProductionStickerAttachContext?=null,contactCurrent:()->Boolean={true}){
        editBarrier()
        if(!StickerPrivateTransport.enabled(this)){
            if(expected==null||!contactCurrent())return
            val controller=productionStickerAttachment?:ProductionStickerAttachmentController(this,session,::productionStickerAttachContext,
                {stickerPanel?.setAttachmentPending(stickerAttachmentBusy()||!stickerAttachmentAvailable())},
                {outcome->android.widget.Toast.makeText(this,when(outcome){
                    ProductionStickerAttachmentController.Outcome.REQUESTED->"Image requested in the receiving app. Lip did not press Send; that app controls what happens next."
                    ProductionStickerAttachmentController.Outcome.NOT_CONFIRMED->"Image request not confirmed. Check the receiving app; nothing retried."
                    ProductionStickerAttachmentController.Outcome.UNAVAILABLE->"Image unavailable or another request is draining. Nothing retried."
                },android.widget.Toast.LENGTH_LONG).show()}).also{productionStickerAttachment=it}
            controller.request(asset,expected,contactCurrent);return
        }
        val controller=stickerAttachment?:StickerAttachmentController(this,session,::stickerAttachContext,
            {stickerPanel?.setAttachmentPending(stickerAttachmentBusy()||!stickerAttachmentAvailable())},
            {outcome->android.widget.Toast.makeText(this,when(outcome){
                StickerAttachmentController.Outcome.REQUESTED->"Image requested; check the editor. Lip did not press Send."
                StickerAttachmentController.Outcome.NOT_CONFIRMED->"Image request not confirmed; check the editor. No retry was made."
                StickerAttachmentController.Outcome.UNAVAILABLE->"Original image unavailable or another request is draining. No retry was made."
            },android.widget.Toast.LENGTH_LONG).show()}).also{stickerAttachment=it}
        controller.request(asset)
    }

    private val stickerLibraryChanged:()->Unit={main.post{
        if(!destroyed){
            val wasBrowser=page==KeyboardPage.STICKERS
            invalidateStickers()
            if(wasBrowser&&stickerSafety().eligible)loadStickers(StickerBrowserPage.Library())
        }
    };Unit}
    private var stickerPage:StickerBrowserPage=StickerBrowserPage.Library()
    private var stickerState:StickerPanelState=StickerPanelState.Loading
    private var stickerStateEpoch:Any?=null
    private var stickerWindowToken=Any()
    private fun invalidateStickers(){
        stickerAttachment?.cancel();productionStickerAttachment?.cancel()
        stickerWindowToken=Any();stickerBrowser?.cancel();stickerPanel?.close();stickerPanel=null
        stickerPage=StickerBrowserPage.Library();stickerState=StickerPanelState.Loading;stickerStateEpoch=null
    }
    private fun openStickers(){
        if(!StickerBrowserFeature.enabled(this)||!stickerSafety().eligible)return
        clearEmoji();clearGifSearch();cancelOwnedVoice();invalidateStickers()
        page=KeyboardPage.STICKERS;loadStickers(StickerBrowserPage.Library())
    }
    private fun loadStickers(target:StickerBrowserPage){
        if(page!=KeyboardPage.STICKERS||!StickerBrowserFeature.enabled(this)||!stickerSafety().eligible)return
        stickerAttachment?.cancel();productionStickerAttachment?.cancel()
        val connection=currentInputConnection?:return
        val editor=session.editorGeneration()?:return
        val token=stickerWindowToken
        val libraryEpoch=dev.lip.stickers.StickerLibraryRevision.snapshot()?:return
        stickerPage=target
        val browser=stickerBrowser?:StickerBrowserController(this).also{stickerBrowser=it}
        browser.load(target,{
            !destroyed&&page==KeyboardPage.STICKERS&&stickerWindowToken===token&&isInputViewShown&&
                currentInputConnection===connection&&session.editorGeneration()==editor&&stickerSafety().eligible&&dev.lip.stickers.StickerLibraryRevision.isCurrent(libraryEpoch)
        }){state->stickerState=state;stickerStateEpoch=libraryEpoch;renderKeys();updateState()}
    }
    private fun manageStickerPacks(){
        if(page!=KeyboardPage.STICKERS||!isInputViewShown||!StickerBrowserFeature.enabled(this))return
        invalidateStickers();startActivity(Intent(this,dev.lip.stickers.StickerPacksActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    private var presentationFrame:KeyboardPresentationFrame?=null
    private var availableWidth=0
    private var presentationViewCreates=0
    private val presentationServiceIdentity by lazy{java.util.UUID.randomUUID().toString()}
    @Volatile private var presentationWitness:String?=null
    protected override fun dump(fd:java.io.FileDescriptor,writer:java.io.PrintWriter,args:Array<out String>?){
        if(args?.contentEquals(arrayOf("--lip-sizing-witness"))==true){
            // Fixture-only observation; never fall through to a generic platform dump.
            if(!debuggable||packageName!="dev.lip.android.sizingfixture"||android.os.Build.HARDWARE!="ranchu"){
                writer.println("LIP_SIZING_WITNESS unavailable");return
            }
            val reply=java.util.concurrent.atomic.AtomicReference<String?>()
            val ready=java.util.concurrent.CountDownLatch(1)
            val capture={
                try{
                    val editor=currentInputEditorInfo
                    if(editor?.packageName=="dev.lip.android.sizingfixture.test"&&ImeFieldPolicy.forInput(editor.inputType,editor.imeOptions).voice&&
                        !localSearchActive()&&emojiSearch.query.isBlank()&&gifSearch.query.isBlank()&&
                        page in setOf(KeyboardPage.LETTERS,KeyboardPage.EMOJI,KeyboardPage.GIF,KeyboardPage.STICKERS)){
                        val record=org.json.JSONObject().put("pid",android.os.Process.myPid())
                            .put("serviceId",presentationServiceIdentity).put("shown",isInputViewShown)
                            .put("ownedEditor",true).put("page",page.name).put("fontScale",resources.configuration.fontScale.toDouble())
                            .put("orientation",resources.configuration.orientation).put("screenWidthDp",resources.configuration.screenWidthDp).put("screenHeightDp",resources.configuration.screenHeightDp)
                            .put("windowWidth",window?.window?.decorView?.width?:0).put("windowHeight",window?.window?.decorView?.height?:0)
                            .put("stickerScale",stickerScale.name).put("renderedSettings",org.json.JSONObject(presentation.encode())).put("outerToolsVisible",toolsFrame?.isShown==true||toolsBar?.isShown==true)
                        // Observation only: retain both coordinate bases; never repair one using the other.
                        window?.window?.let{ownWindow->
                            val decor=ownWindow.decorView;val screen=IntArray(2);val local=IntArray(2)
                            decor.getLocationOnScreen(screen);decor.getLocationInWindow(local)
                            val attrs=ownWindow.attributes;val metrics=ownWindow.windowManager.currentWindowMetrics.bounds
                            val node=decor.createAccessibilityNodeInfo()
                            record.put("windowGeometry",org.json.JSONObject().put("uptimeMs",SystemClock.uptimeMillis())
                                .put("decorScreenX",screen[0]).put("decorScreenY",screen[1]).put("decorWindowX",local[0]).put("decorWindowY",local[1])
                                .put("decorWidth",decor.width).put("decorHeight",decor.height).put("nativeNodeWindowId",node?.windowId?:-1)
                                .put("metrics",metrics.flattenToString()).put("gravity",attrs.gravity).put("attributeX",attrs.x).put("attributeY",attrs.y)
                                .put("attributeWidth",attrs.width).put("attributeHeight",attrs.height).put("type",attrs.type))
                        }
                        val attachment=stickerAttachment?.debugState()
                        record.put("stickerAttachment",org.json.JSONObject().put("pending",attachment?.first?:false).put("workerTasks",attachment?.second?:0).put("dispatches",attachment?.third?:0))
                        if(StickerPrivateTransport.enabled(this)){
                            val transport=StickerPrivateTransport.snapshot()
                            record.put("stickerTransport",org.json.JSONObject().put("active",transport.active).put("writerAlive",transport.writerAlive).put("queuedTasks",transport.queuedTasks).put("revocationsConfirmed",transport.revocationCallSucceeded))
                            val barrier=StickerOriginalReadBarrier.observation(this)
                            record.put("stickerReadBarrier",barrier?.let{org.json.JSONObject().put("active",it.active).put("claimed",it.claimed)
                                .put("entered",it.entered).put("workerThreadAlive",it.workerThreadAlive).put("canceledPredicateObserved",it.canceledPredicateObserved)
                                .put("cancelRequested",it.cancelRequested).put("released",it.released).put("callbackExited",it.callbackExited)
                                .put("loaderAdmissionReleased",it.loaderAdmissionReleased).put("storeLiveInvocations",it.storeLiveInvocations)
                                .put("timedOut",it.timedOut).put("interrupted",it.interrupted)}?:org.json.JSONObject.NULL)
                        }
                        val views=org.json.JSONArray();var cells=0;var visited=0;val ordinals=HashMap<String,Int>()
                        fun visit(view:View){
                            if(++visited>4096)return
                            val description=view.contentDescription?.toString().orEmpty()
                            val role=when{
                                page==KeyboardPage.LETTERS&&description in setOf("q","Emoji keyboard","Lip keyboard menu","Show local GIF catalog")->description
                                page==KeyboardPage.EMOJI&&description in setOf("Return from emoji to letters","Cancel emoji skin tone choices")->description
                                page==KeyboardPage.EMOJI&&description.startsWith("Insert emoji ")->"emoji"
                                page==KeyboardPage.EMOJI&&description.startsWith("Choose emoji ")->"tone"
                                page==KeyboardPage.GIF&&description in setOf("Search local GIF catalog","Return from GIFs to letters","Cancel pending GIF attachment")->description
                                page==KeyboardPage.GIF&&view.tag=="lip:gif:status"->"gifStatus"
                                page==KeyboardPage.GIF&&view.tag=="lip:gif:title"->"gifTitle"
                                page==KeyboardPage.GIF&&view.tag=="lip:gif:credit"->"gifCredit"
                                page==KeyboardPage.GIF&&view.tag=="lip:gif:scroll"->"gifScroll"
                                page==KeyboardPage.GIF&&description.startsWith("Attach GIF: ")->"gifTile"
                                page==KeyboardPage.STICKERS&&description in setOf("Return from stickers to letters","Show local sticker packs","Manage local sticker packs","Previous sticker browser page","Next sticker browser page")->description
                                page==KeyboardPage.STICKERS&&view.tag?.toString() in setOf("lip:sticker:status","lip:sticker:title","lip:sticker:credit","lip:sticker:scroll","lip:sticker:tile","lip:sticker:preview","lip:sticker:disclosure","lip:sticker:empty","lip:sticker:error")->view.tag.toString().removePrefix("lip:")
                                else->null
                            }
                            if(role!=null&&view.isAttachedToWindow&&view.isShown&&(role !in setOf("emoji","tone","gifTitle","gifTile")||cells++<64)){
                                val at=IntArray(2);view.getLocationOnScreen(at);val inWindow=IntArray(2);view.getLocationInWindow(inWindow)
                                val visible=android.graphics.Rect();val hasVisibleRect=view.getGlobalVisibleRect(visible)
                                val localVisible=android.graphics.Rect();var hasLocalVisible=view.getLocalVisibleRect(localVisible)
                                val drawingVisible=android.graphics.Rect(localVisible)
                                // getLocalVisibleRect includes this view's own scroll; getLocationInWindow does not.
                                // Convert drawing coordinates to the clipped viewport, without a fitted screen offset.
                                if(hasLocalVisible){
                                    val dx=inWindow[0].toLong()-view.scrollX;val dy=inWindow[1].toLong()-view.scrollY
                                    val edges=longArrayOf(localVisible.left+dx,localVisible.top+dy,localVisible.right+dx,localVisible.bottom+dy)
                                    if(edges.all{it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()})localVisible.set(edges[0].toInt(),edges[1].toInt(),edges[2].toInt(),edges[3].toInt())
                                    else {hasLocalVisible=false;localVisible.setEmpty()}
                                }else localVisible.setEmpty()
                                var ancestor:View?=view;var identityChain=true;var reachedDecor=false;var depth=0
                                while(ancestor!=null&&depth++<64){
                                    if(!ancestor.matrix.isIdentity)identityChain=false
                                    if(ancestor===window?.window?.decorView){reachedDecor=true;break}
                                    ancestor=ancestor.parent as? View
                                }
                                val text=view as? android.widget.TextView;val layout=text?.layout
                                val ordinal=ordinals[role]?:0;ordinals[role]=ordinal+1
                                val observation=org.json.JSONObject().put("role",role).put("ordinal",ordinal).put("fontPixels",text?.textSize?.toDouble()?:0.0)
                                    .put("width",view.width).put("height",view.height).put("x",at[0]).put("y",at[1]).put("enabled",view.isEnabled).put("clickable",view.isClickable)
                                    .put("inWindowX",inWindow[0]).put("inWindowY",inWindow[1])
                                    .put("hasLocalVisibleRect",hasLocalVisible).put("visibleInWindow",localVisible.flattenToString())
                                    .put("drawingVisible",drawingVisible.flattenToString()).put("selfScrollX",view.scrollX).put("selfScrollY",view.scrollY)
                                    .put("identityLocalChain",identityChain&&reachedDecor)
                                    .put("hasVisibleRect",hasVisibleRect).put("visibleHeight",if(hasVisibleRect)visible.height() else 0).put("visibleWidth",if(hasVisibleRect)visible.width() else 0).put("layoutHeight",layout?.height?:-1).put("lineCount",layout?.lineCount?:0)
                                    .put("ellipses",layout?.let{l->(0 until l.lineCount).sumOf{l.getEllipsisCount(it)}}?:0)
                                    .put("contentHeight",view.height-(text?.compoundPaddingTop?:view.paddingTop)-(text?.compoundPaddingBottom?:view.paddingBottom))
                                    .put("lineWidth",layout?.let{l->(0 until l.lineCount).maxOfOrNull{l.getLineWidth(it).toDouble()}}?:-1.0)
                                    .put("contentWidth",view.width-(text?.compoundPaddingLeft?:view.paddingLeft)-(text?.compoundPaddingRight?:view.paddingRight))
                                    .put("scale",(view as? GlassKeyButton)?.labelScale?.name?:"SYSTEM_TEXT")
                                if(role in setOf("q","Lip keyboard menu")){
                                    val node=view.createAccessibilityNodeInfo();val bounds=android.graphics.Rect();val localBounds=android.graphics.Rect()
                                    node?.getBoundsInScreen(bounds);if(android.os.Build.VERSION.SDK_INT>=36)node?.getBoundsInWindow(localBounds)
                                    observation.put("nativeNodeWindowId",node?.windowId?:-1).put("nativeNodeScreen",bounds.flattenToString()).put("nativeNodeWindow",localBounds.flattenToString())
                                }
                                views.put(observation)
                            }
                            if(view is android.view.ViewGroup)for(i in 0 until view.childCount)visit(view.getChildAt(i))
                        }
                        window?.window?.decorView?.let{visit(it)};record.put("views",views);reply.set(record.toString())
                    }
                }catch(_:Throwable){reply.set(null)}finally{ready.countDown()}
            }
            if(Looper.myLooper()==Looper.getMainLooper())capture() else if(!main.post{capture()})ready.countDown()
            val completed=runCatching{ready.await(3,java.util.concurrent.TimeUnit.SECONDS)}.getOrDefault(false)
            writer.println("LIP_SIZING_WITNESS "+if(completed)reply.get()?:"unavailable" else "unavailable");return
        }
        if(args?.contentEquals(arrayOf("--lip-presentation-witness"))==true){
            if(!debuggable){writer.println("Lip presentation witness unavailable");return}
            val reply=java.util.concurrent.atomic.AtomicReference<String?>()
            val ready=java.util.concurrent.CountDownLatch(1)
            val capture={
                try{
                    val record=org.json.JSONObject().put("creation",presentationWitness?.let{org.json.JSONObject(it)})
                    val bounds=org.json.JSONArray()
                    // Only fixed key chrome on the ordinary letter page; never host text/search/clipboard/candidates.
                    if(page==KeyboardPage.LETTERS&&!localSearchActive()){
                        val wanted=setOf("q","Space","Switch letters and symbols","Emoji keyboard",".","Send in the current editor")
                        fun visit(view:View){
                            if(view is GlassKeyButton&&view.contentDescription?.toString() in wanted){
                                val point=IntArray(2);view.getLocationOnScreen(point)
                                val full=android.graphics.Rect(point[0],point[1],point[0]+view.width,point[1]+view.height)
                                val visible=android.graphics.Rect();val shown=view.getGlobalVisibleRect(visible)
                                val origin=IntArray(2);view.rootView.getLocationOnScreen(origin);visible.offset(origin[0],origin[1])
                                bounds.put(org.json.JSONObject().put("key",view.contentDescription.toString()).put("height",view.height)
                                    .put("full",org.json.JSONArray(listOf(full.left,full.top,full.right,full.bottom)))
                                    .put("visible",org.json.JSONArray(listOf(visible.left,visible.top,visible.right,visible.bottom)))
                                    .put("complete",shown&&view.isAttachedToWindow&&view.isShown&&visible==full))
                            }
                            if(view is android.view.ViewGroup)for(i in 0 until view.childCount)visit(view.getChildAt(i))
                        }
                        keys?.let{visit(it)}
                    }
                    record.put("keys",bounds);reply.set(record.toString())
                }catch(_:Throwable){reply.set(null)}finally{ready.countDown()}
            }
            if(Looper.myLooper()==Looper.getMainLooper())capture() else if(!main.post{capture()})ready.countDown()
            val completed=runCatching{ready.await(3,java.util.concurrent.TimeUnit.SECONDS)}.getOrDefault(false)
            writer.println("LIP_PRESENTATION_WITNESS "+if(completed)reply.get()?:"unavailable" else "unavailable")
            return
        }
        super.dump(fd,writer,args)
    }
    private fun applyPresentation(){
        invalidateStickers();cancelKeyGestures();stopRepeat();stopSwipe();cancelOwnedVoice();stopGif();clearGifSearch();clearEmoji()
        setInputView(onCreateInputView())
    }
    private fun changeHand(hand:KeyboardHand){
        val next=presentation.copy(hand=hand)
        if(presentationStore.save(next))applyPresentation()
        else android.widget.Toast.makeText(this,"Keyboard settings could not be saved",android.widget.Toast.LENGTH_LONG).show()
    }
    private val session = ImeSession()
    private val dictation by lazy { Dictation.get(this) }
    private val voiceOwner = ImeVoiceOwner(session)
    private var voiceTicket: ImeTicket?
        get() = voiceOwner.ticket
        set(value) { voiceOwner.ticket = value }
    private var info: EditorInfo? = null
    private var selectionStart = -1
    private var selectionEnd = -1
    private var shift = false
    private var caps = false
    private var lastShift = 0L
    private var page = KeyboardPage.LETTERS
    private val emojiSearch=EmojiSearch()
    private val emojiRecents=EmojiRecents()
    private val emojiPickOwner=EmojiPickOwner(session)
    private var emojiCatalog:EmojiCatalog?=null
    private var emojiLoad:Any?=null
    private var emojiLoadError:String?=null
    private var emojiPanel:EmojiPanel?=null
    private var emojiPanelToken=Any()
    private var keyboardRenderToken=Any()
    private var emojiKeysNeedRebind=false
    private var emojiCategory:Int?=null
    private val emojiWorker=java.util.concurrent.ThreadPoolExecutor(1,1,0,java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(1),{r->Thread(r,"lip-emoji-catalog").apply{isDaemon=true}})
    private fun localSearchActive()=gifSearch.active||emojiSearch.active
    private fun appendQuery(value:String){if(emojiSearch.active)emojiSearch.append(value)else gifSearch.append(value)}
    private fun deleteQuery(){if(emojiSearch.active)emojiSearch.delete()else gifSearch.delete()}
    private fun finishQuery(){if(emojiSearch.active)openEmoji()else openGifs()}
    private fun privateEmojiField()=policy().protected||((info?.imeOptions?:0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)!=0
    private fun clearEmoji(resetRecents:Boolean=false){
        if(emojiSearch.active||page==KeyboardPage.EMOJI)emojiKeysNeedRebind=true
        emojiSearch.clear();if(resetRecents)emojiRecents.clear();emojiPanel?.close();emojiPanelToken=Any()
    }
    private fun openEmoji(){
        if(dictation.busy||swipePending||gifOwner.current!=null)return
        clearGifSearch();emojiSearch.finish();emojiCategory=null;emojiLoadError=null
        page=KeyboardPage.EMOJI;renderKeys();updateState();loadEmoji()
    }
    private fun loadEmoji(){
        if(emojiCatalog!=null||emojiLoad!=null||emojiLoadError!=null)return
        val load=Any();emojiLoad=load
        try{emojiWorker.execute{
            val result=runCatching{
                val paint=android.graphics.Paint().apply{typeface=android.graphics.Typeface.create("sans-serif-light",android.graphics.Typeface.NORMAL);textSize=32*resources.displayMetrics.scaledDensity}
                EmojiAssets.load(applicationContext).available{paint.hasGlyph(it)}
            }
            main.post{
                if(destroyed||emojiLoad!==load)return@post
                emojiLoad=null;result.onSuccess{emojiCatalog=it}.onFailure{emojiLoadError="Emoji unavailable. Close and reopen to retry."}
                if(page==KeyboardPage.EMOJI&&isInputViewShown){renderKeys();updateState()}
            }
        }}catch(_:java.util.concurrent.RejectedExecutionException){emojiLoad=null;emojiLoadError="Emoji unavailable. Close and reopen to retry."}
    }
    private fun beginEmojiSearch(){
        if(dictation.busy||swipePending||gifOwner.current!=null)return
        cancelKeyGestures();stopSwipe();cancelOwnedVoice();clearGifSearch()
        emojiSearch.begin();page=KeyboardPage.LETTERS;renderKeys();updateState()
    }
    private fun pickEmoji(item:EmojiItem,ticket:EmojiPickOwner.Ticket?,token:Any){
        val connection=currentInputConnection?:return
        if(ticket==null||page!=KeyboardPage.EMOJI||emojiSearch.active||dictation.busy||swipePending||!isInputViewShown||
            emojiCatalog?.items?.contains(item)!=true||!emojiPickOwner.claim(ticket,connection,emojiPanelToken,selectionStart,selectionEnd)||token!==emojiPanelToken)return
        cancelKeyGestures();stopSwipe();cancelOwnedVoice()
        val accepted=runCatching{
            connection.finishComposingText()&&emojiPickOwner.claimedCurrent(ticket,currentInputConnection,emojiPanelToken,selectionStart,selectionEnd)&&
                page==KeyboardPage.EMOJI&&isInputViewShown&&ownEdit(connection,item.sequence,ImeEditJournal.Kind.EMOJI){
                    emojiPickOwner.claimedCurrent(ticket,currentInputConnection,emojiPanelToken,selectionStart,selectionEnd)&&connection.commitText(item.sequence,1)
                }
        }.getOrDefault(false)
        val stillOwn=emojiPickOwner.claimedCurrent(ticket,currentInputConnection,emojiPanelToken,selectionStart,selectionEnd)
        if(session.editorGeneration()==ticket.generation.first)session.editorChanged() // Never invalidate a reentrant new editor.
        if(accepted&&stillOwn)emojiRecents.record(item.id,privateEmojiField())
        else android.widget.Toast.makeText(this,"Emoji was not confirmed; check the editor. No retry was made.",android.widget.Toast.LENGTH_SHORT).show()
        if(stillOwn&&page==KeyboardPage.EMOJI){renderKeys();updateState()}
    }
    private val gifSearch=GifSearch()
    private val gifConsumedHardwareKeys=HashSet<Int>()
    private var gifKeysNeedRebind=false
    private var gifPanel:GifPanel?=null
    private val gifOwner=GifRequestOwner(session)
    private val gifWorker=java.util.concurrent.ThreadPoolExecutor(1,1,0,java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(1),{r->Thread(r,"lip-gif-attachment").apply{isDaemon=true}})
    private var gifTool:Button?=null
    private var undoTool:Button?=null
    private var composingStart=-1
    private var composingEnd=-1
    private fun stopGif(){gifOwner.cancel();gifWorker.queue.clear();gifPanel?.setBusy(false)}
    private fun clearGifSearch(){if(gifSearch.active)gifKeysNeedRebind=true;gifSearch.clear()}
    private fun openGifs(){if(!policy().voice||dictation.busy)return;clearEmoji();gifSearch.finish();page=KeyboardPage.GIF;renderKeys();updateState()}
    private fun requestGif(item:GifItem){
        editBarrier()
        if(gifOwner.current!=null||dictation.busy||!policy().voice||localSearchActive())return
        stopSwipe();cancelOwnedVoice()
        val connection=currentInputConnection?:return
        if(composingStart>=0||composingEnd>=0){android.widget.Toast.makeText(this,"Finish the current composition before attaching a GIF",android.widget.Toast.LENGTH_SHORT).show();return}
        val request=gifOwner.begin(item,snapshot(connection),connection,info?.contentMimeTypes)?:return
        updateState()
        try{gifWorker.execute{
            val result=runCatching{GifAssets.read(applicationContext,item)}
            main.post{
                if(destroyed||gifOwner.current!==request)return@post
                if(result.isFailure||composingStart>=0||composingEnd>=0||currentInputConnection!==connection||!session.isCurrent(request.ticket,snapshot(connection))||
                    !connection.finishComposingText()||!gifOwner.claim(request,snapshot(connection),connection,info?.contentMimeTypes)){
                    stopGif();updateState();android.widget.Toast.makeText(this,"GIF not attached; target changed or local asset unavailable",android.widget.Toast.LENGTH_SHORT).show();return@post
                }
                val uri=android.net.Uri.Builder().scheme("content").authority(packageName+".gif").appendPath("gif").appendPath(item.id+".gif").build()
                val content=android.view.inputmethod.InputContentInfo(uri,
                    android.content.ClipDescription("${item.title} · Noto Emoji by Google · CC BY4.0",arrayOf("image/gif")),android.net.Uri.parse(item.source))
                val accepted=runCatching{connection.commitContent(content,InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,null)}.getOrDefault(false)
                session.editorChanged();updateState()
                android.widget.Toast.makeText(this,if(accepted)"GIF requested; check the editor" else "GIF was not confirmed; check the editor. No retry was made.",android.widget.Toast.LENGTH_SHORT).show()
            }
        }}catch(_:java.util.concurrent.RejectedExecutionException){stopGif();updateState()}
    }

    private var keys: LinearLayout? = null
    private var status: TextView? = null
    private var mic: Button? = null
    private var clipboardTool:Button?=null
    private var clipboardCopy:Button?=null
    private var clipboardPaste:Button?=null
    private var clipboardLease:ImeRepeatLease?=null
    private var history:ClipboardHistoryController?=null
    private var historyArea:LinearLayout?=null
    private var historyRowsToken=Any()
    private var clipboardWindowVisible=false
    private var clipboardListenerRegistered=false
    private val historyConfirmation=ClipboardConfirmation()
    private var historyCaptureContext=false
    private var historyCaptureAfter=Long.MAX_VALUE
    private val clipboardListener=android.content.ClipboardManager.OnPrimaryClipChangedListener{captureClipboard()}
    private val historyScreenReceiver=object:android.content.BroadcastReceiver(){
        override fun onReceive(context:android.content.Context?,intent:Intent?){
            if(intent?.action==Intent.ACTION_SCREEN_OFF){
                invalidateToolbarContacts()
                invalidateStickers()
                closeTextEditing();if(page==KeyboardPage.TEXT_EDITING){page=KeyboardPage.LETTERS;renderKeys();updateState()}
                clearOwnedEdits();revokePersonalAccess();stopSwipe()
            }
            updateHistoryEligibility();history?.revalidateTime()
        }
    }
    private fun historyContextAllowed():Boolean = clipboardWindowVisible&&isInputViewShown&&clipboardAvailability().paste&&
        getSystemService(android.os.PowerManager::class.java).isInteractive&&
        !getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked&&
        android.provider.Settings.Secure.getString(contentResolver,android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)==
            android.content.ComponentName(this,LipInputMethodService::class.java).flattenToString()
    private fun updateHistoryEligibility(){
        val controller=history?:return
        val allowed=historyContextAllowed()
        val capture=allowed&&controller.enabled&&!controller.paused
        if(capture&&!historyCaptureContext)historyCaptureAfter=System.currentTimeMillis()
        if(!capture)historyCaptureAfter=Long.MAX_VALUE
        historyCaptureContext=capture
        controller.setEligible(allowed)
        val listen=controller.enabled&&!controller.paused
        val clipboard=getSystemService(android.content.ClipboardManager::class.java)
        if(listen&&!clipboardListenerRegistered){clipboard.addPrimaryClipChangedListener(clipboardListener);clipboardListenerRegistered=true}
        else if(!listen&&clipboardListenerRegistered){clipboard.removePrimaryClipChangedListener(clipboardListener);clipboardListenerRegistered=false}
    }
    private fun historyChanged(){
        if(destroyed)return
        updateHistoryEligibility()
        if(page==KeyboardPage.CLIPBOARD)renderHistoryArea()
    }
    private fun captureClipboard(manual:Boolean=false){
        updateHistoryEligibility()
        val controller=history?:return
        val text=ClipboardHistoryRead.read({updateHistoryEligibility();controller.canCapture()&&historyContextAllowed()},{
            runCatching{ClipboardHistoryController.plainText(getSystemService(android.content.ClipboardManager::class.java),if(manual)Long.MIN_VALUE else historyCaptureAfter)}.getOrNull()
        })?:return
        controller.capture(text)
    }
    private fun pasteSavedClip(id:String,lease:ImeRepeatLease){
        val controller=history?:return
        updateHistoryEligibility()
        if(page!=KeyboardPage.CLIPBOARD||dictation.busy||!lease.canRun(currentInputConnection)||!historyContextAllowed())return
        val item=controller.item(id)?:return
        stopSwipe();cancelOwnedVoice();session.editorChanged()
        currentInputConnection?.let{if(it.finishComposingText())ownEdit(it,item.text,ImeEditJournal.Kind.CLIP){lease.canRun(currentInputConnection)&&it.commitText(item.text,1)}} // Explicit saved-item tap; no clipboard write/retry.
    }
    private fun clipboardAvailability()=ClipboardActions.available(info?.inputType?:0,info?.imeOptions?:0,
        selectionStart,selectionEnd,currentInputConnection!=null)
    private fun closeClipboard(){clipboardLease?.close();clipboardLease=null;clipboardCopy=null;clipboardPaste=null;historyArea=null;historyConfirmation.cancel()}
    private fun clipboardAction(copy:Boolean,lease:ImeRepeatLease){
        val allowed=clipboardAvailability()
        if(page!=KeyboardPage.CLIPBOARD||dictation.busy||!lease.canRun(currentInputConnection)||
            !(if(copy)allowed.copy else allowed.paste))return
        stopSwipe();cancelOwnedVoice();session.editorChanged()
        editBarrier()
        val dispatched=currentInputConnection?.performContextMenuAction(if(copy)android.R.id.copy else android.R.id.paste)==true
        android.widget.Toast.makeText(this,if(dispatched)"Clipboard action sent to editor" else "This editor did not accept the clipboard action",android.widget.Toast.LENGTH_SHORT).show()
        // Remote dispatch is not a host acknowledgement. Never retry or claim observed success.
        updateState()
    }
    private val observer: () -> Unit = { updateState() }
    private var cursorGestureCancel:(()->Unit)?=null
    private var wordDeleteCancel:(()->Unit)?=null
    private var wordDeleteTransaction:WordDeleteTransaction?=null
    private var repeatDelete: Runnable? = null
    private var repeatLease: ImeRepeatLease? = null
    companion object {
        /** Debug instrumentation only; no exported component or cross-app control route. */
        @Volatile internal var debugInstance:java.lang.ref.WeakReference<LipInputMethodService>?=null
    }
    private var debugConnectionOverride:InputConnection?=null
    private var debuggable=false
    override fun getCurrentInputConnection():InputConnection? = if(debuggable)debugConnectionOverride?:super.getCurrentInputConnection() else super.getCurrentInputConnection()
    private val ownedEdits=ImeEditJournal()
    private val editTransactions=ImeEditTransaction(ownedEdits){SystemClock.uptimeMillis()}
    private var editContext:Any=Any()
    private var editConnection:InputConnection?=null
    private var editPopup:android.widget.PopupWindow?=null
    private val editAckExpiry=Runnable{editTransactions.expireSelectionAcknowledgements()}
    private val editReplayExpiry=Runnable{editTransactions.expirePendingReplay()}
    private var editSelectionObservation=0L
    private fun clearOwnedEdits(){
        editPopup?.dismiss();editPopup=null
        main.removeCallbacks(editAckExpiry);main.removeCallbacks(editReplayExpiry)
        editConnection=null;editContext=Any();editTransactions.bind(editContext)
    }
    private fun editBarrier(){editPopup?.dismiss();editPopup=null;editTransactions.barrier()}
    private fun ownedEditEligible():Boolean {
        val interactive=getSystemService(android.os.PowerManager::class.java).isInteractive
        val unlocked=!getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked
        return interactive&&unlocked&&!destroyed&&isInputViewShown&&!localSearchActive()&&policy().voice
    }
    private fun editPort(connection:InputConnection,allowComposition:Boolean=false):ImeEditTransaction.Port {
        if(editConnection!==connection){editConnection=connection;editContext=Any();editTransactions.bind(editContext)}
        val context=editContext;val epoch=session.editorGeneration()
        return object:ImeEditTransaction.Port {
            override fun eligible()=ownedEditEligible()&&editContext===context&&currentInputConnection===connection&&session.editorGeneration()==epoch&&
                (allowComposition||composingStart<0&&composingEnd<0)
            override fun read(before:Int,after:Int):ImeEditWindow? {
                if(!eligible()||before !in 0..ImeEditTransaction.MAX_EDIT+32||after !in 0..ImeEditTransaction.MAX_EDIT+32||
                    selectionStart<0||selectionEnd<0||kotlin.math.abs(selectionEnd-selectionStart)>ImeEditTransaction.MAX_EDIT)return null
                val observation=editSelectionObservation;val observedStart=selectionStart;val observedEnd=selectionEnd
                val value=connection.getSurroundingText(before,after,0)?:return null
                if(value.offset<0||value.text.length>ImeEditTransaction.MAX_EDIT+64||value.selectionStart<0||value.selectionEnd<0||value.selectionStart>value.text.length||
                    value.selectionEnd>value.text.length||value.offset>Int.MAX_VALUE-value.text.length)return null
                val fresh=ImeEditWindow(value.offset,value.text.toString(),value.offset+value.selectionStart,value.offset+value.selectionEnd)
                val current=eligible()
                return ImeEditSelection.observed(fresh,observedStart,observedEnd,current&&observation==editSelectionObservation,observation)
            }
            override fun select(start:Int,end:Int)=eligible()&&connection.setSelection(start,end)
            override fun selectionStamp()=editSelectionObservation
            override fun selectObserved(start:Int,end:Int,beforeDispatch:(Long)->Boolean):Boolean {
                if(!eligible())return false
                // No IPC between this actual observation baseline and dispatch.
                return beforeDispatch(editSelectionObservation)&&connection.setSelection(start,end)
            }
            override fun replace(text:String)=eligible()&&connection.finishComposingText()&&eligible()&&connection.commitText(text,1)
        }
    }
    private fun ownEdit(connection:InputConnection,inserted:String,kind:ImeEditJournal.Kind,removeBefore:Int=0,range:IntRange?=null,
        allowComposition:Boolean=false,originalSelection:Pair<Int,Int>?=null,dispatch:()->Boolean):Boolean {
        val epoch=session.editorGeneration()
        val accepted=editTransactions.edit(editPort(connection,allowComposition),inserted,kind,removeBefore,range,originalSelection){
            val current=!destroyed&&currentInputConnection===connection&&session.editorGeneration()==epoch
            current&&(allowComposition||connection.finishComposingText())&&
                !destroyed&&currentInputConnection===connection&&session.editorGeneration()==epoch&&dispatch()
        }
        main.removeCallbacks(editAckExpiry);main.postDelayed(editAckExpiry,500)
        return accepted
    }
    private fun showOwnedUndo(anchor:View){
        if(!anchor.isAttachedToWindow||!isInputViewShown||localSearchActive())return
        cancelKeyGestures();stopRepeat();stopSwipe();cancelOwnedVoice()
        editPopup?.dismiss()
        val connection=currentInputConnection?:return
        if(!anchor.isAttachedToWindow||!isInputViewShown)return
        val port=editPort(connection);val context=editContext
        val anchorBounds=android.graphics.Rect();if(!anchor.getGlobalVisibleRect(anchorBounds))return
        val anchorWindow=anchor.windowToken
        fun anchorCurrent():Boolean {
            val current=android.graphics.Rect()
            return anchor.isAttachedToWindow&&anchor.windowToken===anchorWindow&&anchor.getGlobalVisibleRect(current)&&current==anchorBounds
        }
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(8),dp(8),dp(8),dp(8));setBackgroundColor(KeyboardVisualTokens.BACKDROP)}
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;layoutDirection=anchor.layoutDirection}
        lateinit var popup:android.widget.PopupWindow
        fun button(title:String,direction:ImeEditJournal.Direction,available:Boolean):GlassKeyButton = GlassKeyButton(this).apply{
            this.text=title;contentDescription="$title last Lip-owned text edit";minimumHeight=dp(48);fitText(18)
            isEnabled=available&&port.eligible()&&!dictation.busy&&!swipePending&&!editTransactions.busy
            interactionAllowed={editPopup===popup&&popup.isShowing&&anchorCurrent()&&editContext===context&&port.eligible()}
            setOnClickListener{
                if(editPopup!==popup||!popup.isShowing||!anchorCurrent()||editContext!==context||!port.eligible())return@setOnClickListener
                popup.dismiss() // Consume this actual button action before editor IPC.
                val succeeded=editTransactions.replay(port,direction)
                if(editTransactions.busy){main.removeCallbacks(editReplayExpiry);main.postDelayed(editReplayExpiry,250)}
                session.editorChanged();clearCandidates()
                main.removeCallbacks(editAckExpiry);main.postDelayed(editAckExpiry,500)
                android.widget.Toast.makeText(this@LipInputMethodService,
                    if(succeeded)"$title confirmed" else if(editTransactions.busy)"$title requested; waiting for editor selection confirmation" else "Local $title unavailable or unconfirmed; check the editor. No host-undo fallback.",android.widget.Toast.LENGTH_SHORT).show()
                updateState()
            }
        }
        row.addView(button("Undo",ImeEditJournal.Direction.UNDO,ownedEdits.canUndo),LinearLayout.LayoutParams(0,dp(48),1f))
        row.addView(button("Redo",ImeEditJournal.Direction.REDO,ownedEdits.canRedo),LinearLayout.LayoutParams(0,dp(48),1f))
        panel.addView(row)
        panel.addView(label(when(editTransactions.notice){
            ImeEditTransaction.Notice.RESOURCE->"Local undo paused: memory unavailable. Reopen the field to reset."
            ImeEditTransaction.Notice.UNCONFIRMED->"Last edit unconfirmed; history cleared. Check the editor."
            ImeEditTransaction.Notice.UNSUPPORTED->"This editor, composition or large edit could not be journaled."
            else->"Lip-owned text only · RAM only · cleared on editor/privacy departure"
        },11f))
        popup=android.widget.PopupWindow(panel,minOf(dp(330),resources.displayMetrics.widthPixels-dp(16)),android.view.ViewGroup.LayoutParams.WRAP_CONTENT,true)
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(KeyboardVisualTokens.BACKDROP));popup.isOutsideTouchable=true;popup.elevation=dp(8).toFloat()
        val anchorLayout=android.view.ViewTreeObserver.OnGlobalLayoutListener{if(!anchorCurrent())popup.dismiss()}
        anchor.viewTreeObserver.addOnGlobalLayoutListener(anchorLayout)
        val anchorAttachment=object:View.OnAttachStateChangeListener{
            override fun onViewAttachedToWindow(view:View){}
            override fun onViewDetachedFromWindow(view:View){popup.dismiss()}
        }
        anchor.addOnAttachStateChangeListener(anchorAttachment)
        popup.setOnDismissListener{
            anchor.removeOnAttachStateChangeListener(anchorAttachment)
            if(anchor.viewTreeObserver.isAlive)anchor.viewTreeObserver.removeOnGlobalLayoutListener(anchorLayout)
            if(editPopup===popup)editPopup=null
        }
        panel.setOnTouchListener{_,event->if(event.actionMasked==android.view.MotionEvent.ACTION_CANCEL)popup.dismiss();false}
        editPopup=popup
        try{popup.showAsDropDown(anchor,0,-anchor.height)}catch(_:RuntimeException){
            anchor.removeOnAttachStateChangeListener(anchorAttachment)
            if(anchor.viewTreeObserver.isAlive)anchor.viewTreeObserver.removeOnGlobalLayoutListener(anchorLayout)
            popup.dismiss();if(editPopup===popup)editPopup=null
        }
    }

    private val candidateOwner=SwipeCandidateOwner(session)
    private val nextWordOwner=NextWordOwner(session)
    private var nextWordEngine:NextWordSuggestions?=null
    private var renderedNextWord:NextWordRequest?=null
    private var previousWordChoices=false
    private var candidateBar:LinearLayout?=null
    private var candidateMic:GlassKeyButton?=null
    private var renderedCandidate:SwipeCandidateState?=null
    private var candidateVocabularyEpoch:SwipeVocabularyEpoch.Snapshot?=null
    private var swipePending=false
    private fun clearCandidates(){candidateVocabularyEpoch=null;nextWordOwner.clear();renderedNextWord=null;previousWordChoices=false;candidateOwner.clear();renderedCandidate=null;candidateMic=null;candidateBar?.removeAllViews()}
    private fun stageCandidates(base:ImeCursor,text:String,choices:List<String>,connection:InputConnection,expectedEpoch:Long,origin:CandidateOrigin=CandidateOrigin.SWIPE,automatic:Int?=null,expectSelection:Boolean=true,automaticAllowed:Boolean=true,expectedVocabulary:SwipeVocabularyEpoch.Snapshot?=null){
        val vocabularyEpoch=expectedVocabulary?:swipeVocabulary.snapshot()?:return
        if(!swipeVocabulary.isCurrent(vocabularyEpoch)||currentInputConnection!==connection||session.editorGeneration()!=expectedEpoch)return
        nextWordOwner.clear();previousWordChoices=false
        val state=candidateOwner.stage(base,text,choices,connection,origin,expectSelection,automaticAllowed)?:return
        candidateVocabularyEpoch=vocabularyEpoch
        updateState()
        main.postDelayed({
            if(candidateOwner.current!==state)return@postDelayed
            if(!swipeVocabulary.isCurrent(vocabularyEpoch)){clearCandidates();updateState();return@postDelayed}
            val current=if(currentInputConnection===connection)candidateCursor(state,connection) else null
            val exact=if(current!=null)connection.getTextBeforeCursor(text.length,0)?.toString() else null
            if(!candidateOwner.confirm(state,current,exact,currentInputConnection))swipeMessage="Check the editor; insertion could not be confirmed"
            updateState()
            if(state.ready&&candidateOwner.current===state){
                if(automatic!=null)chooseCandidate(state,automatic,true)
                else if(text.endsWith(" "))stageNextWords(state,connection)
            }
        },80)
    }
    private data class CandidateCorrection(val state:SwipeCandidateState,val replacement:String,val connection:InputConnection,val cursor:ImeCursor,val automatic:Boolean,val vocabularyEpoch:SwipeVocabularyEpoch.Snapshot)
    private var correction:CandidateCorrection?=null
    private fun cancelCorrection(){
        val pending=correction?:return;correction=null
        if(currentInputConnection===pending.connection&&session.editorGeneration()==pending.state.epoch&&
            composingStart==pending.state.start&&composingEnd==pending.state.end)
            pending.connection.finishComposingText() // End markers only; never retry/roll back text.
    }
    private fun correctionFailed(){swipeMessage="Check the editor; correction could not be confirmed";updateState()}
    private fun chooseCandidate(state:SwipeCandidateState,index:Int,automatic:Boolean=false){
        if(swipePending||dictation.busy||correction!=null||candidateOwner.current!==state||automatic&&!state.automaticAllowed)return
        if(state.origin!=CandidateOrigin.SWIPE&&(composingStart>=0||composingEnd>=0)){clearCandidates();updateState();return}
        val vocabularyEpoch=candidateVocabularyEpoch?:run{clearCandidates();updateState();return}
        if(!swipeVocabulary.isCurrent(vocabularyEpoch)){clearCandidates();updateState();return}
        val connection=currentInputConnection?:run{clearCandidates();updateState();return}
        val cursor=candidateCursor(state,connection)
        val exact=if(cursor!=null)connection.getTextBeforeCursor(state.inserted.length,0)?.toString() else null
        var claimedReplacement:String?=null
        swipeVocabulary.claim(vocabularyEpoch) { claimedReplacement=candidateOwner.claim(state,index,cursor,exact,connection);claimedReplacement!=null }
        val replacement=claimedReplacement
        renderedCandidate=null;candidateVocabularyEpoch=null;updateState()
        if(replacement==null||replacement==state.inserted)return
        val pending=CandidateCorrection(state,replacement,connection,checkNotNull(cursor),automatic,vocabularyEpoch);correction=pending
        // RemoteInputConnection returns dispatch success, not the host's Boolean.
        // Only a matching editor-reported composing span can authorize the write.
        editTransactions.expectComposition(state.start,state.end)
        if(!connection.setComposingRegion(state.start,state.end)){
            cancelCorrection();correctionFailed();return
        }
        main.postDelayed({if(correction===pending){cancelCorrection();correctionFailed()}},800)
    }
    private fun acknowledgeCorrection(start:Int,end:Int){
        val pending=correction?:return
        if(start!=pending.state.start||end!=pending.state.end){cancelCorrection();correctionFailed();return}
        correction=null // Consume before any text IPC, even if the host reports failure.
        val connection=pending.connection;val state=pending.state
        val current=if(currentInputConnection===connection)candidateCursor(state,connection) else null
        val valid=current==pending.cursor&&session.claimedContextCurrent(checkNotNull(state.ticket),current)&&
            connection.getTextBeforeCursor(state.inserted.length,0)?.toString()==state.inserted
        if(!valid){connection.finishComposingText();correctionFailed();return}
        val accepted=ownEdit(connection,pending.replacement,ImeEditJournal.Kind.REPLACEMENT,range=state.start until state.end,allowComposition=true){
            if(session.claimedContextCurrent(checkNotNull(state.ticket),candidateCursor(state,connection))&&composingStart==state.start&&composingEnd==state.end){
                editTransactions.expectComposition(state.start,state.start+pending.replacement.length)
                connection.setComposingText(pending.replacement,1)
            }else false
        }
        val cleaned=connection.finishComposingText()
        session.editorChanged()
        if(accepted&&cleaned)stageCandidates(state.base,pending.replacement,state.alternatives,connection,state.epoch,if(pending.automatic)CandidateOrigin.AUTO else state.origin.let{if(it==CandidateOrigin.AUTO)CandidateOrigin.TYPED else it},
            automaticAllowed=if(!pending.automatic&&state.origin!=CandidateOrigin.SWIPE)false else state.automaticAllowed,expectedVocabulary=pending.vocabularyEpoch)
        else correctionFailed()
    }
    private fun nextWordsAllowed():Boolean = !dictation.busy&&voiceTicket==null&&composingStart<0&&composingEnd<0&&
        TypingPolicy.forInput(info?.inputType?:0,info?.imeOptions?:0).suggest&&
        getSharedPreferences("preferences",MODE_PRIVATE).getBoolean("typingSuggestions",true)
    private fun stageNextWords(state:SwipeCandidateState,connection:InputConnection){
        val engine=nextWordEngine?:return
        if(!nextWordsAllowed()||correction!=null||candidateOwner.current!==state||currentInputConnection!==connection)return
        val cursor=typingCursor(connection)?:return
        // This is a continuation of an already confirmed insertion, not a parked
        // cursor inferred from cached coordinates or an arbitrary editor selection.
        if(!session.isCurrent(state.ticket?:return,cursor))return
        val request=nextWordOwner.begin(cursor,connection)?:return
        try{typingWorker.execute{
            val words=engine.suggest(cursor)
            main.post{
                if(destroyed||nextWordEngine!==engine||candidateOwner.current!==state||!nextWordsAllowed()){
                    if(nextWordOwner.current===request)nextWordOwner.clear();updateState();return@post
                }
                val current=if(currentInputConnection===connection)typingCursor(connection) else null
                if(nextWordOwner.complete(request,words,current,currentInputConnection)){
                    if(state.origin==CandidateOrigin.SWIPE)previousWordChoices=true // Keep the three swipe alternatives immediately accessible.
                    renderedCandidate=null
                }
                updateState()
            }
        }}catch(_:java.util.concurrent.RejectedExecutionException){if(nextWordOwner.current===request)nextWordOwner.clear()}
    }
    private fun chooseNextWord(request:NextWordRequest,index:Int){
        if(!nextWordsAllowed()||nextWordOwner.current!==request||correction!=null||swipePending)return
        val connection=currentInputConnection?:return
        val cursor=typingCursor(connection)
        val word=nextWordOwner.claim(request,index,cursor,connection)?:return
        val expectedText=SwipeTextFormat.insertion(word,checkNotNull(cursor),false,false,true)
        val inserted=if(connection.finishComposingText()&&ownEdit(connection,expectedText,ImeEditJournal.Kind.NEXT_WORD){
            currentInputConnection===connection&&nextWordsAllowed()&&session.claimedContextCurrent(request.ticket,typingCursor(connection))&&connection.commitText(expectedText,1)
        })expectedText else null
        stopSwipe();session.editorChanged()
        if(inserted!=null)stageCandidates(checkNotNull(cursor),inserted,listOf(inserted),connection,session.editorGeneration()?:return,CandidateOrigin.TYPED,automaticAllowed=false)
        else android.widget.Toast.makeText(this,"Next-word insertion was not confirmed; check the editor",android.widget.Toast.LENGTH_SHORT).show()
        updateState() // Dispatch is not a host acknowledgement; never retry or auto-send.
    }
    private fun renderCandidates(){
        val row=candidateBar?:return;val state=candidateOwner.current
        val next=nextWordOwner.current?.takeIf{it.choices.isNotEmpty()&&!previousWordChoices}
        if(next!=null){
            if(renderedNextWord!==next){
                row.removeAllViews();renderedCandidate=null;renderedNextWord=next
                row.addView(GlassKeyButton(this,flat=true).apply{icon=KeyIcon.UNDO;contentDescription="Show previous word alternatives";setOnClickListener{previousWordChoices=true;renderedCandidate=null;updateState()}},LinearLayout.LayoutParams(0,-1,1f))
                next.choices.forEachIndexed{index,word->
                    row.addView(GlassKeyButton(this,flat=true).apply{
                        text=word;fitText(18);setSingleLine();ellipsize=android.text.TextUtils.TruncateAt.END
                        contentDescription="Insert next word $word";stateDescription="Append at the verified caret, not replace the previous word"
                        setOnClickListener{chooseNextWord(next,index)}
                    },LinearLayout.LayoutParams(0,-1,8f/next.choices.size))
                }
                candidateMic=GlassKeyButton(this,flat=true).apply{icon=KeyIcon.MIC;contentDescription="Record with the selected local Bark model";setOnClickListener{toggleVoice()}}
                row.addView(candidateMic,LinearLayout.LayoutParams(0,-1,1f))
            }
            for(i in 1 until row.childCount-1)row.getChildAt(i).isEnabled=nextWordsAllowed()&&!swipePending
            return
        }
        if(renderedNextWord!=null){row.removeAllViews();renderedNextWord=null;renderedCandidate=null}
        if(state!==renderedCandidate){
            row.removeAllViews();renderedCandidate=state;candidateMic=null
            if(state!=null){
                row.addView(GlassKeyButton(this,flat=true).apply{
                    val canPredict=nextWordOwner.current?.choices?.isNotEmpty()==true
                    icon=KeyIcon.MENU
                    contentDescription=if(canPredict)"Show next-word predictions" else "Show keyboard tools"
                    setOnClickListener{if(canPredict){previousWordChoices=false;updateState()}else{clearCandidates();updateState()}}
                    setOnLongClickListener{clearCandidates();updateState();true}
                },LinearLayout.LayoutParams(0,-1,1f))
                state.alternatives.forEachIndexed{index,text->
                    row.addView(GlassKeyButton(this,flat=true).apply{
                        this.text=text.trim();fitText(18);setSingleLine();ellipsize=android.text.TextUtils.TruncateAt.END
                        contentDescription="Choose ${if(state.origin==CandidateOrigin.SWIPE)"swipe" else "typing"} suggestion ${text.trim()}"
                        tag=index
                        setOnClickListener{chooseCandidate(state,index)}
                    },LinearLayout.LayoutParams(0,-1,8f/state.alternatives.size))
                }
                candidateMic=GlassKeyButton(this,flat=true).apply{icon=KeyIcon.MIC;contentDescription="Record with the selected local Bark model";setOnClickListener{toggleVoice()}}
                row.addView(candidateMic,LinearLayout.LayoutParams(0,-1,1f))
            }
        }
        if(state!=null)for(i in 1 until row.childCount-1)row.getChildAt(i).apply{
            isEnabled=state.ready&&!dictation.busy;stateDescription=if(state.ready)"Replace only the verified last ${if(state.origin==CandidateOrigin.SWIPE)"swipe" else "typed word"}" else "Verifying insertion"
        }
    }

    private val typingPreferences by lazy { getSharedPreferences("preferences", android.content.Context.MODE_PRIVATE) }
    private val swipeVocabulary = SwipeVocabularyEpoch.process
    private var personalAccess: SwipeVocabularyAccess? = null
    private val vocabularyMutation: () -> Unit = { main.post { if (!destroyed) vocabularyChanged() } }
    private fun personalSwipeEligible() = !destroyed && isInputViewShown && session.editorGeneration() != null &&
        getSystemService(android.os.PowerManager::class.java).isInteractive &&
        !getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked &&
        info?.let { ImeFieldPolicy.forInput(it.inputType,it.imeOptions).swipe } == true
    private fun personalAccess(): SwipeVocabularyAccess? {
        if (!personalSwipeEligible()) return null
        return personalAccess?.takeIf { it.isCurrent() } ?: SwipeVocabularyAccess().also { personalAccess = it }
    }
    private fun revokePersonalAccess() {
        personalAccess?.revoke(); personalAccess = null
        typingLoad=null;typingEngine=null;nextWordEngine=null;typingAutomatic=false;typingVocabularyEpoch=null
    }
    private fun vocabularyChanged() {
        revokePersonalAccess();stopSwipe();swipeWorker?.vocabularyChanged()
        if (personalSwipeEligible()) loadTyping()
    }
    private val vocabularyChanges=android.content.SharedPreferences.OnSharedPreferenceChangeListener{_,key->
        if(key in setOf("portableVocabularyRevision","dictionaryRevision")) main.post { if(!destroyed) vocabularyChanged() }
        else if(key in setOf("typingSuggestions","typingAutocorrect")) main.post { if(!destroyed&&session.editorGeneration()!=null) loadTyping() }
    }
    private var typingEngine:TypingSuggestions?=null
    private var typingAutomatic=false
    private var typingVocabularyEpoch:SwipeVocabularyEpoch.Snapshot?=null
    private var typingLoad:Any?=null
    private val typingWorker=java.util.concurrent.ThreadPoolExecutor(1,1,0,java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(1),{r->Thread(r,"lip-typing-suggestions").apply{isDaemon=true}})
    private fun loadTyping(){
        val epoch=session.editorGeneration()?:return
        val load=Any();typingLoad=load;typingEngine=null;nextWordEngine=null;typingAutomatic=false;typingVocabularyEpoch=null;typingWorker.queue.clear()
        // Do not schedule personal-vocabulary reads from protected/no-learning fields.
        if(info?.let{TypingPolicy.forInput(it.inputType,it.imeOptions).suggest}!=true)return
        val access=personalAccess()?:return
        val vocabularyEpoch=swipeVocabulary.snapshot()?:return
        try{typingWorker.execute{
            var next:NextWordSuggestions?=null
            val loaded=runCatching{
                check(access.isCurrent()&&swipeVocabulary.isCurrent(vocabularyEpoch))
                val store=dev.lip.AppStore(applicationContext)
                if(!store.typingSuggestions)null else {
                    next=runCatching{NextWordAssets.load(applicationContext)}.getOrNull()
                    check(access.isCurrent()&&swipeVocabulary.isCurrent(vocabularyEpoch))
                    val personal=store.typingDictionarySnapshot()
                    check(access.isCurrent()&&swipeVocabulary.isCurrent(vocabularyEpoch))
                    // Missing/corrupt/unsupported portable protection must not
                    // disable ordinary suggestions, nor enable unsafe autocorrect.
                    TypingSuggestions(SwipeAssets.dictionary(applicationContext).entries,personal.words) to
                        (store.typingAutocorrect&&personal.portableAvailable)
                }
            }
            main.post{
                if(!destroyed&&typingLoad===load&&session.editorGeneration()==epoch&&access.isCurrent()&&swipeVocabulary.isCurrent(vocabularyEpoch)){
                typingEngine=loaded.getOrNull()?.first;nextWordEngine=next;typingAutomatic=loaded.getOrNull()?.second?:false;typingVocabularyEpoch=vocabularyEpoch
                val state=candidateOwner.current;val connection=currentInputConnection
                if(state?.origin==CandidateOrigin.TYPED&&connection!=null&&ownsTyped(state,typingCursor(connection),connection)){
                    if(typingEngine!=null)typedCandidates(state.base,state.inserted,connection,epoch,state.automaticAllowed)
                    else {clearCandidates();updateState()}
                }
            }}
        }}catch(_:java.util.concurrent.RejectedExecutionException){}
    }
    private fun ownsTyped(state:SwipeCandidateState,cursor:ImeCursor?,connection:InputConnection):Boolean =
        state.origin==CandidateOrigin.TYPED&&cursor?.eligible==true&&state.connection===connection&&session.editorGeneration()==state.epoch&&
        cursor.packageName==state.base.packageName&&cursor.fieldId==state.base.fieldId&&cursor.start==state.end&&cursor.end==state.end&&
        cursor.before==(state.base.before+state.inserted).takeLast(32)&&cursor.after==state.base.after&&
        connection.getTextBeforeCursor(state.inserted.length,0)?.toString()==state.inserted
    private fun typedCandidates(base:ImeCursor,text:String,connection:InputConnection,epoch:Long,automaticAllowed:Boolean=true){
        val suffix=TypingBoundary.suffix(text)
        val token=TypingBoundary.token(text)
        stageCandidates(base,text,listOf(text),connection,epoch,CandidateOrigin.TYPED,automaticAllowed=automaticAllowed)
        val placeholder=candidateOwner.current?:return
        val engine=typingEngine?:return
        val vocabularyEpoch=typingVocabularyEpoch?:return
        if(!swipeVocabulary.isCurrent(vocabularyEpoch))return
        typingWorker.queue.clear()
        try{typingWorker.execute{
            val choices=engine.suggest(token)
            main.post{
                if(destroyed||candidateOwner.current!==placeholder||typingEngine!==engine||currentInputConnection!==connection||!swipeVocabulary.isCurrent(vocabularyEpoch))return@post
                if(!ownsTyped(placeholder,typingCursor(connection),connection)){clearCandidates();updateState();return@post}
                val values=choices.choices.map{it+suffix}
                stageCandidates(base,text,values,connection,epoch,CandidateOrigin.TYPED,
                    if(suffix.isNotEmpty()&&placeholder.automaticAllowed&&typingAutomatic&&TypingPolicy.forInput(info?.inputType?:0,info?.imeOptions?:0).automatic)choices.automatic else null,placeholder.expectedSelection,placeholder.automaticAllowed,expectedVocabulary=vocabularyEpoch)
            }
        }}catch(_:java.util.concurrent.RejectedExecutionException){}
    }

    private var destroyed=false
    private var swipeOverlay:GlassKeyButton?=null
    private var toolsBar:View?=null
    private var toolsFrame:View?=null
    private val swipeOwner=SwipeRequestOwner(session)
    private var swipeRequest:SwipeRequest?=null
    private var swipeConnection:InputConnection?=null
    private var swipeVocabularyRequest:SwipeVocabularyEpoch.Snapshot?=null
    private var swipeVocabularyAccess:SwipeVocabularyAccess?=null
    private var swipeFrame:SwipeKeyboardFrame?=null
    private var swipeWorker:SwipeDecodeWorker?=null
    private var swipeCapital=false
    private var swipeCaps=false
    private var swipeMessage:String?=null
    private fun worker():SwipeDecodeWorker { check(!destroyed);return swipeWorker?:SwipeDecodeWorker(applicationContext).also{swipeWorker=it} }
    private fun invalidateSwipe(){swipeOwner.cancel();swipeRequest=null;swipeConnection=null;swipeVocabularyRequest=null;swipeVocabularyAccess=null;swipeWorker?.cancelQueued();swipePending=false;swipeMessage=null;updateState()}
    private fun stopSwipe(){stopGif();swipeFrame?.abort();cancelCorrection();clearCandidates();invalidateSwipe()}
    private fun beginSwipe():Boolean {
        cancelKeyGestures()
        if(swipePending||localSearchActive()||gifOwner.current!=null)return false
        stopRepeat();cancelOwnedVoice();cancelCorrection();clearCandidates();invalidateSwipe()
        if(!personalSwipeEligible()||dictation.busy)return false
        val vocabularyEpoch=swipeVocabulary.snapshot()?:return false
        val access=personalAccess()?:return false
        val connection=currentInputConnection?:return false
        val cursor=snapshot(connection) // Editor IPC stays outside vocabulary admission locks.
        var request:SwipeRequest?=null
        if(!swipeVocabulary.claim(vocabularyEpoch) { request=swipeOwner.begin(cursor,connection); request!=null })return false
        swipeRequest=request;swipeConnection=connection;swipeVocabularyRequest=vocabularyEpoch;swipeVocabularyAccess=access;swipeCapital=shift;swipeCaps=caps
        return true
    }
    private fun finishSwipe(shape:SwipeGeometry,points:List<SwipePoint>){
        val request=swipeRequest?:return
        val connection=swipeConnection?:return
        val vocabularyEpoch=swipeVocabularyRequest?:return
        val access=swipeVocabularyAccess?:return
        val capital=swipeCapital;val lockedCaps=swipeCaps
        swipePending=true;swipeMessage="Decoding swipe locally…";updateState()
        worker().decode(shape,points,vocabularyEpoch,access){result->
            if(swipeRequest!==request)return@decode
            val word=result.getOrNull()?.firstOrNull()
            if(word==null||!personalSwipeEligible()||!access.isCurrent()||!swipeVocabulary.isCurrent(vocabularyEpoch)||currentInputConnection!==connection||!session.isCurrent(request.ticket,snapshot(connection))){
                invalidateSwipe();swipeMessage=when{result.isFailure->"Swipe unavailable; reopen keyboard to retry or type normally";word==null->"No word recognized; try again or type";else->null};updateState();return@decode
            }
            if(!connection.finishComposingText()){
                invalidateSwipe();swipeMessage="Check the editor; swipe composition could not be finished";updateState();return@decode
            }
            val cursor=snapshot(connection) // Fresh cursor IPC BEFORE the final CPU-only claim gate.
            if(!personalSwipeEligible()||!access.isCurrent()||!swipeVocabulary.claim(vocabularyEpoch) { swipeOwner.claim(request,cursor,connection) }){invalidateSwipe();return@decode}
            swipeRequest=null;swipeConnection=null;swipeVocabularyRequest=null;swipeVocabularyAccess=null
            val variation=(info?.inputType?:0) and 0xff0
            val text=SwipeTextFormat.insertion(word,request.ticket.cursor,capital,lockedCaps,variation !in setOf(0x10,0x20,0xd0))
            // A dispatch already claimed before a later vocabulary mutation may finish once.
            val accepted=ownEdit(connection,text,ImeEditJournal.Kind.SWIPE){session.claimedContextCurrent(request.ticket,snapshot(connection))&&connection.commitText(text,1)} // One consumed dispatch; no ambiguous retry.
            session.editorChanged();swipePending=false
            val warning=if(accepted)null else "Check the editor; swipe insertion could not be confirmed"
            if(shift&&!caps){shift=false;renderKeys()}
            swipeMessage=warning
            if(accepted&&swipeVocabulary.isCurrent(vocabularyEpoch))stageCandidates(request.ticket.cursor,text,result.getOrThrow().map{SwipeTextFormat.insertion(it,request.ticket.cursor,capital,lockedCaps,variation !in setOf(0x10,0x20,0xd0))}.distinct(),connection,request.ticket.epoch,expectedVocabulary=vocabularyEpoch)
            updateState()
        }
    }


    override fun onCreate() {
        super.onCreate()
        toolbarSettings.observe(toolbarChanged)
        dev.lip.stickers.StickerLibraryRevision.observe(stickerLibraryChanged)
        typingPreferences.registerOnSharedPreferenceChangeListener(vocabularyChanges)
        swipeVocabulary.observe(vocabularyMutation)
        debuggable=applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE!=0
        if(debuggable)debugInstance=java.lang.ref.WeakReference(this)
        agentController = dev.lip.agent.AgentShortcutController(this, dictation::currentCompletedForAgent,
            dictation::isCurrentCompletedForAgent, agentSettings,
            { route -> if (debuggable) agentFixtureAdapter?.takeIf { it.destination.route == route } else null },
            ::agentEligible, { message -> android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show() })
        dictation.observeCompletedForAgent(agentResultObserver)
        dictation.observe(observer)
        history=ClipboardHistoryController(this,::historyChanged)
        registerReceiver(historyScreenReceiver,android.content.IntentFilter().apply{
            addAction(Intent.ACTION_SCREEN_OFF);addAction(Intent.ACTION_USER_PRESENT);addAction(Intent.ACTION_TIME_CHANGED);addAction(Intent.ACTION_TIMEZONE_CHANGED)
        },android.content.Context.RECEIVER_NOT_EXPORTED)
        updateHistoryEligibility()
    }
    override fun onDestroy() {
        toolbarSettings.unobserve(toolbarChanged);invalidateToolbarContacts()
        dev.lip.stickers.StickerLibraryRevision.unobserve(stickerLibraryChanged)
        closeTextEditing()
        clearOwnedEdits()
        typingPreferences.unregisterOnSharedPreferenceChangeListener(vocabularyChanges)
        swipeVocabulary.unobserve(vocabularyMutation);revokePersonalAccess()
        dictation.unobserveCompletedForAgent(agentResultObserver)
        agentController?.close(); agentController = null; agentSlot = null; agentFixtureAdapter = null
        cancelKeyGestures()
        invalidateStickers();stickerBrowser?.close();stickerBrowser=null
        stickerAttachment?.close();stickerAttachment=null;productionStickerAttachment?.close();productionStickerAttachment=null
        destroyed=true;debugConnectionOverride=null;typingLoad=null;typingEngine=null;typingWorker.shutdownNow()
        clearEmoji(true);emojiLoad=null;emojiWorker.shutdownNow();clearGifSearch();gifPanel?.close();gifPanel=null;gifWorker.shutdownNow()
        if(clipboardListenerRegistered)getSystemService(android.content.ClipboardManager::class.java).removePrimaryClipChangedListener(clipboardListener)
        clipboardListenerRegistered=false;unregisterReceiver(historyScreenReceiver);history?.close();history=null
        if(debugInstance?.get()===this)debugInstance=null
        stopRepeat(); stopSwipe(); swipeWorker?.close();swipeWorker=null;cancelOwnedVoice(); session.finishEditor()
        dictation.setImeAgentProtected(false)
        dictation.unobserve(observer); super.onDestroy()
    }
    override fun onEvaluateFullscreenMode() = false
    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        invalidateToolbarContacts()
        closeTextEditing()
        clearOwnedEdits()
        revokePersonalAccess()
        agentController?.bindUi(Any())
        val privateField = ImeFieldPolicy.forInput(attribute.inputType, attribute.imeOptions).protected ||
            attribute.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
        dictation.setImeAgentProtected(privateField) // Metadata only, before observer/capture callbacks.
        cancelKeyGestures()
        invalidateStickers();clearEmoji(true);clearGifSearch();composingStart=-1;composingEnd=-1
        clipboardWindowVisible=false;history?.setEligible(false)
        debugConnectionOverride=null
        super.onStartInput(attribute, restarting)
        stopRepeat(); stopSwipe();cancelOwnedVoice(); session.beginEditor()
        info = attribute; selectionStart = attribute.initialSelStart; selectionEnd = attribute.initialSelEnd
        shift = false; caps = false; page = KeyboardPage.LETTERS
        loadTyping();renderKeys(); updateState()
    }
    override fun onFinishInput() {
        invalidateToolbarContacts()
        closeTextEditing()
        clearOwnedEdits()
        revokePersonalAccess()
        agentController?.bindUi(null)
        cancelKeyGestures()
        invalidateStickers();clearEmoji(true);clearGifSearch();gifPanel?.close()
        clipboardWindowVisible=false;history?.setEligible(false)
        stopRepeat(); stopSwipe();cancelOwnedVoice(); session.finishEditor()
        info = null; selectionStart = -1; selectionEnd = -1
        dictation.setImeAgentProtected(false) // Never restores a revoked result/attempt.
        super.onFinishInput()
    }
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        revokePersonalAccess();stopSwipe()
        super.onStartInputView(info,restarting)
        // Re-read a durable vocabulary after returning from Settings/import, even
        // when the host retains the same InputConnection/editor generation.
        loadTyping()
        agentController?.bindUi(Any()); refreshAgentShortcut()
        if(presentationStore.load()!=presentation)applyPresentation()
        else if(toolbarSnapshot?.revision!==toolbarSettings.snapshot().revision)setInputView(onCreateInputView())
        stickerScale=stickerPreferences.load()
        GlassMaterial.refresh();clipboardWindowVisible=true
        main.post{if(!destroyed)updateHistoryEligibility()}
        if(page==KeyboardPage.STICKERS){loadStickers(StickerBrowserPage.Library())}
        else if(page==KeyboardPage.CLIPBOARD||page==KeyboardPage.TEXT_EDITING||page==KeyboardPage.GIF||gifKeysNeedRebind||emojiKeysNeedRebind||page==KeyboardPage.EMOJI){renderKeys();updateState()}else keys?.invalidate()
    }
    override fun onFinishInputView(finishingInput: Boolean) {
        invalidateToolbarContacts()
        closeTextEditing()
        clearOwnedEdits()
        revokePersonalAccess()
        agentController?.bindUi(null)
        cancelKeyGestures()
        invalidateStickers();clearEmoji(true);clearGifSearch();gifPanel?.close()
        clipboardWindowVisible=false;history?.setEligible(false)
        closeClipboard();stopRepeat(); stopSwipe();cancelOwnedVoice(); super.onFinishInputView(finishingInput)
    }
    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        wordDeleteTransaction?.selectionChanged(newSelStart,newSelEnd)
        val compositionChanged=composingStart!=candidatesStart||composingEnd!=candidatesEnd
        editSelectionObservation++;selectionStart=newSelStart;selectionEnd=newSelEnd
        if(oldSelStart!=newSelStart||oldSelEnd!=newSelEnd||compositionChanged){invalidateToolbarContacts();textEditingOwner.cancel()}
        if(oldSelStart!=newSelStart||oldSelEnd!=newSelEnd){
            val owned=editConnection?.takeIf{it===currentInputConnection}
            editTransactions.selection(newSelStart,newSelEnd,owned?.let{editPort(it,allowComposition=true)})
        }
        if(compositionChanged)editTransactions.composition(candidatesStart,candidatesEnd)
        if(compositionChanged||oldSelStart!=newSelStart||oldSelEnd!=newSelEnd){stickerAttachment?.cancel();productionStickerAttachment?.cancel()}
        if(compositionChanged)wordDeleteCancel?.invoke()
        composingStart=candidatesStart;composingEnd=candidatesEnd
        if(compositionChanged){nextWordOwner.clear();updateState()}
        if(compositionChanged&&gifOwner.current!=null){stopGif();updateState()}
        selectionStart = newSelStart; selectionEnd = newSelEnd
        if (oldSelStart != newSelStart || oldSelEnd != newSelEnd) {
            session.editorChanged();nextWordOwner.clear()
            if(emojiSearch.active){clearEmoji();page=KeyboardPage.LETTERS;renderKeys();updateState()}
            else if(page==KeyboardPage.EMOJI&&wordDeleteTransaction?.running!=true){renderKeys();updateState()}
            val warning=swipeMessage?.takeIf{it.startsWith("Check the editor;")}
            val typed=candidateOwner.current;val connection=currentInputConnection
            if(candidateOwner.ownsSelection(newSelStart,newSelEnd)){
                if(typed!=null&&typed.origin!=CandidateOrigin.SWIPE&&connection!=null){
                    val confirmed=candidateOwner.confirm(typed,typingCursor(connection),connection.getTextBeforeCursor(typed.inserted.length,0)?.toString(),connection)
                    if(confirmed&&typed.inserted.endsWith(" "))stageNextWords(typed,connection)
                }
                invalidateSwipe()
            }else stopSwipe() // A real departure revokes even when a later snapshot has returned (ABA).
            if(warning!=null){swipeMessage=warning;updateState()}
            cancelOwnedVoice()
        }
        acknowledgeCorrection(candidatesStart,candidatesEnd)
        if(compositionChanged&&candidatesStart<0&&candidatesEnd<0&&oldSelStart==newSelStart&&oldSelEnd==newSelEnd){
            val state=candidateOwner.current;val connection=currentInputConnection
            if(state?.ready==true&&state.inserted.endsWith(" ")&&connection!=null)stageNextWords(state,connection)
            // stageNextWords requires the EXISTING exact current ticket; never refresh an old span on context equality.
        }
        if(page==KeyboardPage.STICKERS&&(compositionChanged||oldSelStart!=newSelStart||oldSelEnd!=newSelEnd)){renderKeys();updateState()}
        if(page==KeyboardPage.CLIPBOARD||page==KeyboardPage.TEXT_EDITING)updateState()
    }
    private fun policy() = info?.let { ImeFieldPolicy.forInput(it.inputType, it.imeOptions) }
        ?: ImeFieldPolicy.forInput(0, 0)

    private fun landscape() = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    private fun rowHeight(): Int = resources.displayMetrics.let {
        KeyboardPresentation.rowHeight(availableWidth.takeIf{w->w>0}?:it.widthPixels,it.heightPixels,it.density,landscape(),presentation.height)
    }
    override fun onCreateInputView(): View {
        invalidateToolbarContacts();toolbarRenderToken=Any();toolbarTouchStream.end()
        toolbarSnapshot=toolbarSettings.snapshot()
        agentSlot=null;gifTool=null;clipboardTool=null;undoTool=null;textEditingTool=null;stickerTool=null
        closeTextEditing()
        editPopup?.dismiss();editPopup=null
        revokePersonalAccess() // A newly-created input view never inherits a prior context token.
        agentController?.bindUi(Any())
        invalidateStickers()
        cancelKeyGestures();stopRepeat();stopSwipe();GlassMaterial.refresh()
        stickerScale=stickerPreferences.load()
        presentation=presentationStore.load();GlassMaterial.matte=presentation.theme==KeyboardTheme.MATTE
        GlassMaterial.haptics=presentation.haptics
        val rootToken=toolbarRenderToken
        val root = object:LinearLayout(this){
            override fun dispatchTouchEvent(event:android.view.MotionEvent):Boolean {
                if(rootToken!==toolbarRenderToken)return true
                val terminal=event.actionMasked==android.view.MotionEvent.ACTION_UP||event.actionMasked==android.view.MotionEvent.ACTION_CANCEL
                val decision=toolbarTouchStream.event(event.pointerCount,event.actionMasked==android.view.MotionEvent.ACTION_CANCEL,toolbarOwner.hasContact())
                try{
                    if(decision.cancel){
                        invalidateToolbarContacts()
                        if(decision.consume){
                            val cancel=android.view.MotionEvent.obtain(event)
                            try{cancel.action=android.view.MotionEvent.ACTION_CANCEL;super.dispatchTouchEvent(cancel)}finally{cancel.recycle()}
                        }
                    }
                    return if(decision.consume)true else super.dispatchTouchEvent(event)
                }finally{if(terminal&&rootToken===toolbarRenderToken)toolbarTouchStream.end()}
            }
        }.apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(KeyboardVisualTokens.BACKDROP)
            setOnApplyWindowInsetsListener { view, insets ->
                val bottom = insets.getInsets(android.view.WindowInsets.Type.navigationBars() or android.view.WindowInsets.Type.systemGestures()).bottom
                view.setPadding(0,0,0,bottom); insets
            }
        }
        // A replacement input view must include the current nav area in its FIRST wrap-content measurement.
        // Waiting for a later insets dispatch can leave the new keyboard measured one nav-height too short.
        val priorInsets=window?.window?.decorView?.rootWindowInsets
        if(debuggable){
            presentationViewCreates++
            presentationWitness=org.json.JSONObject().put("creates",presentationViewCreates).put("height",presentation.height.name)
                .put("hand",presentation.hand.name).put("theme",presentation.theme.name).put("priorInsetsKnown",priorInsets!=null)
                .put("initialBottom",priorInsets?.getInsets(android.view.WindowInsets.Type.navigationBars() or android.view.WindowInsets.Type.systemGestures())?.bottom?:0)
                .put("pid",android.os.Process.myPid()).put("serviceId",presentationServiceIdentity).toString()
        }
        priorInsets?.let{insets->
            root.setPadding(0,0,0,insets.getInsets(android.view.WindowInsets.Type.navigationBars() or android.view.WindowInsets.Type.systemGestures()).bottom)
        }
        val toolbar = GlassKeyboardToolbar(this)
        val renderToken=toolbarRenderToken
        val mapping=checkNotNull(toolbarSnapshot)
        fun tool(actionId:ToolbarAction,action:(GlassKeyButton)->Unit,longAction:((GlassKeyButton)->Boolean)?=null):GlassKeyButton {
            var ticket:ToolbarActivationOwner.Ticket?=null
            var longConsumed=false
            lateinit var view:GlassKeyButton
            fun currentAllowed()=!destroyed&&isInputViewShown&&root.isAttachedToWindow&&root.isShown&&root.windowVisibility==View.VISIBLE&&
                view.isAttachedToWindow&&view.isShown&&view.windowVisibility==View.VISIBLE&&view.isEnabled&&
                renderToken===toolbarRenderToken&&mapping.revision===toolbarSettings.snapshot().revision&&
                (!actionId.configurable||mapping.valid)&&!toolbarTouchStream.blocked
            fun capture(){
                ticket=null;longConsumed=false
                if(!currentAllowed())return
                // Reserve original DOWN metadata BEFORE cleanup/platform callbacks.
                val reserved=toolbarOwner.begin(toolbarCpuContext(actionId))
                view.cancelPendingInputEvents()
                val observed=toolbarContext(actionId)
                if(currentAllowed()&&toolbarOwner.isCurrent(reserved,observed))ticket=reserved
                else toolbarOwner.discard(reserved) // Never cancels a newer reentrant contact.
            }
            fun invoke(long:Boolean):Boolean {
                val held=ticket?:return false;ticket=null
                if(!currentAllowed())return false
                val claim=toolbarOwner.claim(held,toolbarContext(actionId))?:return false
                cancelKeyGestures() // May reenter; final fresh CPU claim still binds the old action/context.
                if(!toolbarOwner.admit(claim,toolbarContext(actionId),currentAllowed()&&!swipePending))return false
                return if(long){longConsumed=true;longAction?.invoke(view)?:false}else{if(!longConsumed)action(view);true}
            }
            view=GlassKeyButton(this,flat=true).apply{
                icon=ToolbarIcons.icon(actionId);contentDescription=actionId.description
                interactionAllowed={currentAllowed()}
                isEnabled=!actionId.configurable||mapping.valid
                if(!isEnabled)stateDescription="Saved toolbar unavailable; customize to restore"
                setOnTouchListener{_,event->
                    when(event.actionMasked){
                        android.view.MotionEvent.ACTION_DOWN->{toolbarTouchStream.begin();capture()}
                        android.view.MotionEvent.ACTION_MOVE,android.view.MotionEvent.ACTION_UP->if(event.x<0||event.y<0||event.x>=width||event.y>=height){ticket=null;invalidateToolbarContacts();cancelPendingInputEvents();isPressed=false;return@setOnTouchListener true}
                        android.view.MotionEvent.ACTION_CANCEL->{ticket=null;invalidateToolbarContacts();cancelPendingInputEvents();isPressed=false}
                    };false
                }
                setOnClickListener{invoke(false)}
                if(longAction!=null)setOnLongClickListener{invoke(true)}
                setOnKeyListener{_,code,event->
                    if(code !in listOf(KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_SPACE))return@setOnKeyListener false
                    if(event.action==KeyEvent.ACTION_DOWN&&event.repeatCount==0)capture()
                    false
                }
                accessibilityDelegate=object:View.AccessibilityDelegate(){
                    override fun performAccessibilityAction(host:View,action:Int,args:android.os.Bundle?):Boolean {
                        if(action!=android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK&&action!=android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK)return super.performAccessibilityAction(host,action,args)
                        if(!currentAllowed()||(action==android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK&&longAction==null))return false
                        capture();return invoke(action==android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK)
                    }
                }
            }
            toolbar.addView(view);return view
        }
        tool(ToolbarAction.TOOLS,{showKeyboardMenu(it)})
        for(actionId in mapping.value.slots)when(actionId){
            ToolbarAction.AGENT->agentSlot=tool(actionId,{agentController?.tap(it)},{if(agentController?.pinned()==true)agentController?.showModes(it)==true else false}).apply{isEnabled=false}
            ToolbarAction.EMOJI->tool(actionId,{cancelOwnedVoice();openEmoji()})
            ToolbarAction.GIF->gifTool=tool(actionId,{cancelOwnedVoice();openGifs()})
            ToolbarAction.CLIPBOARD->clipboardTool=tool(actionId,{
                if(clipboardAvailability().paste&&!dictation.busy){clearEmoji();clearGifSearch();cancelOwnedVoice();page=KeyboardPage.CLIPBOARD;renderKeys();updateState()}
            })
            ToolbarAction.UNDO->undoTool=tool(actionId,{showOwnedUndo(it)})
            ToolbarAction.TEXT_EDITING->textEditingTool=tool(actionId,{openTextEditing()})
            ToolbarAction.STICKERS->stickerTool=tool(actionId,{openStickers()})
            else->error("Invalid middle toolbar action")
        }
        mic=tool(ToolbarAction.MICROPHONE,{toggleVoice()},{cancelOwnedVoice();true}).apply{
            background=DarkGlassDrawable(resources.displayMetrics.density,capsule=true,circle=true)
        }
        val toolbarHeight=if(landscape())dp(32) else (resources.displayMetrics.widthPixels*.09f).toInt().coerceIn(dp(36),dp(44))
        val toolbarFrame=android.widget.FrameLayout(this);toolsBar=toolbar;toolsFrame=toolbarFrame
        toolbarFrame.addView(toolbar,android.widget.FrameLayout.LayoutParams(-1,-1))
        candidateBar=LinearLayout(this).apply{isBaselineAligned=false;setBackgroundColor(KeyboardVisualTokens.TOOLBAR);visibility=View.GONE}
        renderedCandidate=null;toolbarFrame.addView(candidateBar,android.widget.FrameLayout.LayoutParams(-1,-1))
        swipeOverlay=GlassKeyButton(this).apply {
            fitText(15);setBackgroundColor(KeyboardVisualTokens.TOOLBAR);visibility=View.GONE
            setOnClickListener{stopSwipe();updateState()}
        }
        toolbarFrame.addView(swipeOverlay,android.widget.FrameLayout.LayoutParams(-1,-1))
        root.addView(toolbarFrame,LinearLayout.LayoutParams(-1,toolbarHeight))
        status = label("", 12f).apply {
            setTextColor(KeyboardVisualTokens.FOREGROUND); setPadding(dp(8),dp(2),dp(8),dp(2)); visibility=View.GONE
        }
        root.addView(status, LinearLayout.LayoutParams(-1,-2))
        keys = column()
        swipeFrame=SwipeKeyboardFrame(this,{personalSwipeEligible()&&!dictation.busy&&page==KeyboardPage.LETTERS&&!localSearchActive()},
            {shape->val access=personalAccess();val epoch=swipeVocabulary.snapshot();if(access!=null&&epoch!=null)worker().prepare(shape,epoch,access)},::beginSwipe,::finishSwipe,::invalidateSwipe,
            {android.widget.Toast.makeText(this,"That input was not accepted while decoding. Try again.",android.widget.Toast.LENGTH_SHORT).show()})
        swipeFrame!!.addView(keys);root.addView(swipeFrame)
        renderKeys(); updateState()
        lateinit var frame:KeyboardPresentationFrame
        frame=KeyboardPresentationFrame(this,root,presentation,
            {hand->if(presentationFrame===frame&&frame.isAttachedToWindow)changeHand(hand)},
            {width->if(presentationFrame===frame&&availableWidth!=width){availableWidth=width;renderKeys();updateState()}})
        presentationFrame=frame
        main.post { if (presentationFrame===frame && frame.isAttachedToWindow && personalSwipeEligible()) loadTyping() }
        return frame
    }
    private val textEditingOwner=TextEditingOwner()
    private var textEditingPanel:TextEditingPanel?=null
    private var textEditingToken:Any=Any()
    private var textSelecting=false
    private var textEditingMessage="Tap-only editing requests; host completion is not verified. No hold repeat."
    private fun textEditingState():TextEditingState {
        val visible=!destroyed&&isInputViewShown
        val interactive=visible&&getSystemService(android.os.PowerManager::class.java).isInteractive
        val unlocked=visible&&!getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked
        return TextEditingState(info?.inputType?:0,info?.imeOptions?:0,selectionStart,selectionEnd,
            currentInputConnection!=null,visible,dictation.busy||voiceTicket!=null||swipePending||gifOwner.current!=null||stickerAttachmentBusy(),
            composingStart>=0||composingEnd>=0||correction!=null,interactive,unlocked)
    }
    private fun textEditingContext():TextEditingContext {
        val state=textEditingState() // Any external platform metadata queries precede the final CPU snapshot.
        return TextEditingContext(currentInputConnection,session.manualGeneration(),textEditingToken,
            info?.fieldId?:0,info?.packageName,state)
    }
    private fun closeTextEditing(){
        textEditingOwner.cancel();textEditingPanel?.close();textEditingPanel=null;textEditingToken=Any();textSelecting=false
        textEditingMessage="Tap-only editing requests; host completion is not verified. No hold repeat."
    }
    private fun openTextEditing(){
        if(!TextEditingActions.allowed(textEditingState())||info?.packageName.isNullOrBlank()||session.editorGeneration()==null)return
        cancelKeyGestures();stopRepeat();stopSwipe();clearEmoji();clearGifSearch()
        page=KeyboardPage.TEXT_EDITING;renderKeys();updateState()
    }
    private fun updateTextEditing(){
        val panel=textEditingPanel?:return
        val state=textEditingState()
        if(!TextEditingActions.allowed(state)){textEditingOwner.cancel();textSelecting=false}
        panel.update(state,textSelecting,if(TextEditingActions.allowed(state))textEditingMessage else "Editing unavailable in this field/context. Return to letters to type normally.")
    }
    private fun performTextEditing(ticket:TextEditingOwner.Ticket){
        val panel=textEditingPanel?:return
        if(page!=KeyboardPage.TEXT_EDITING||!panel.isAttachedToWindow||!panel.isOpen)return
        val interaction=panel.interactionSnapshot()?:return // Before external reads/cleanup or any host key.
        val captured=textEditingContext()
        val claim=textEditingOwner.claim(ticket,captured,SystemClock.uptimeMillis())?:run{
            textEditingMessage="Editing request refused: context changed. Nothing retried.";updateTextEditing();return
        }
        if(claim.action==TextEditingAction.SELECT_MODE){
            if(!textEditingOwner.admit(claim,textEditingPanel===panel&&panel.ownsInteraction(interaction)))return
            textSelecting=!claim.selecting;textEditingOwner.cancel()
            textEditingMessage=if(textSelecting)"Selection mode on: arrows/endpoints request extension. Host behavior may vary." else "Selection mode off. Tap-only cursor requests."
            updateTextEditing();return
        }
        val connection=claim.connection as? InputConnection?:return
        val command=TextEditingActions.command(claim.action,claim.selecting)?:return
        editBarrier() // Cleanup can reenter; it is not authority to follow a new editor/caret.
        val downTime=SystemClock.uptimeMillis()
        var continuedContext:TextEditingContext?=null
        val result=TextEditingDispatch.execute(command,object:TextEditingDispatch.Port{
            override fun chordedModifiers()=android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD).modifierBehavior==android.view.KeyCharacterMap.MODIFIER_BEHAVIOR_CHORDED
            override fun continueKeys():Boolean {
                val fresh=textEditingContext()
                return page==KeyboardPage.TEXT_EDITING&&textEditingPanel===panel&&panel.isAttachedToWindow&&panel.ownsInteraction(interaction)&&continuedContext?.same(fresh)==true
            }
            override fun menu(id:Int)=connection.performContextMenuAction(id)
            override fun key(down:Boolean,code:Int,modifiers:Int):Boolean {
                val event=KeyEvent(downTime,SystemClock.uptimeMillis(),if(down)KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
                    code,0,modifiers,android.view.KeyCharacterMap.VIRTUAL_KEYBOARD,0,
                    KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,android.view.InputDevice.SOURCE_KEYBOARD)
                return connection.sendKeyEvent(event) // Captured OLD connection, including UP after focus change/throw.
            }
        }){
            val fresh=textEditingContext() // External metadata checks OUTSIDE the owner monitor.
            val allowed=page==KeyboardPage.TEXT_EDITING&&textEditingPanel===panel&&panel.isAttachedToWindow&&panel.ownsInteraction(interaction)&&captured.same(fresh)
            if(!textEditingOwner.admit(claim,allowed))false
            else {
                session.editorChanged() // Deliberate self revision only AFTER exact pre-cleanup revision matched.
                // CPU-only baseline: a modifier callback must not follow a changed editor/caret/panel.
                continuedContext=TextEditingContext(connection,session.manualGeneration(),captured.panel,captured.fieldId,captured.packageName,captured.state)
                true
            }
        }
        textEditingMessage=when(result){
            TextEditingOutcome.DISPATCHED->"Editing command sent to editor; completion not verified."
            TextEditingOutcome.UNCONFIRMED->"Editing request unconfirmed; check the editor. No retry or fallback."
            TextEditingOutcome.REFUSED->"Editing request refused before dispatch; context or modifier capability unavailable. No command sent."
        }
        updateTextEditing()
    }
    private fun renderTextEditing(container:LinearLayout){
        val token=textEditingToken
        lateinit var panel:TextEditingPanel
        panel=TextEditingPanel(this,rowHeight()*5,
            {action->if(page==KeyboardPage.TEXT_EDITING&&textEditingPanel===panel&&textEditingToken===token)
                textEditingOwner.begin(action,textSelecting,textEditingContext(),SystemClock.uptimeMillis())else null},
            ::performTextEditing,textEditingOwner::cancel,
            {page==KeyboardPage.TEXT_EDITING&&textEditingPanel===panel&&textEditingToken===token&&TextEditingActions.allowed(textEditingState())},
            {if(page==KeyboardPage.TEXT_EDITING&&textEditingPanel===panel){page=KeyboardPage.LETTERS;renderKeys();updateState()}},
            {if(textEditingPanel===panel&&textEditingToken===token)textEditingOwner.closePanel(token)})
        textEditingPanel=panel;container.addView(panel);updateTextEditing()
    }
    private fun showKeyboardMenu(anchor: View) {
        val menu = android.widget.PopupMenu(this, anchor)
        val browserToken=keyboardRenderToken
        menu.menu.add("Text editing").apply{
            contentDescription="Open text editing"
            isEnabled=TextEditingActions.allowed(textEditingState())&&!info?.packageName.isNullOrBlank()
            setOnMenuItemClickListener{
                if(browserToken===keyboardRenderToken&&anchor.isAttachedToWindow&&isInputViewShown)openTextEditing()
                true
            }
        }
        if(StickerBrowserFeature.enabled(this))menu.menu.add("Local sticker packs").apply{contentDescription="Open local sticker packs";isEnabled=stickerSafety().eligible}.setOnMenuItemClickListener {
            if(browserToken===keyboardRenderToken&&anchor.isAttachedToWindow&&isInputViewShown)openStickers()
            true
        }
        menu.menu.add("Keyboard preferences").setOnMenuItemClickListener {
            stopSwipe();cancelOwnedVoice();startActivity(Intent(this,KeyboardSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));true
        }
        menu.menu.add("Lip settings").setOnMenuItemClickListener {
            stopSwipe();cancelOwnedVoice(); startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        }
        menu.menu.add("Choose keyboard").setOnMenuItemClickListener {
            stopSwipe();cancelOwnedVoice(); getSystemService(android.view.inputmethod.InputMethodManager::class.java).showInputMethodPicker(); true
        }
        menu.menu.add("Cancel dictation").setOnMenuItemClickListener { stopSwipe();cancelOwnedVoice(); true }
        menu.show()
    }
    private fun key(text: String, description: String = text, capsule: Boolean = false, action: () -> Unit): GlassKeyButton = GlassKeyButton(this,capsule).apply {
        val inputOwner=gifSearch.token;val emojiOwner=emojiSearch.token;val renderOwner=keyboardRenderToken;interactionAllowed={inputOwner===gifSearch.token&&emojiOwner===emojiSearch.token&&renderOwner===keyboardRenderToken}
        this.text=text;labelScale=presentation.keyLabels; contentDescription=description; setOnClickListener { if(!swipePending&&isAttachedToWindow&&interactionAllowed()){cancelKeyGestures();action()} }
    }
    private fun cancelKeyGestures(){
        val cursor=cursorGestureCancel;cursorGestureCancel=null;cursor?.invoke()
        val deletion=wordDeleteCancel;wordDeleteCancel=null;deletion?.invoke()
    }
    private fun cursorGestureAllowed()=ImeFieldPolicy.manualCursor(info?.inputType?:0,selectionStart,selectionEnd)&&!swipePending&&!dictation.busy&&!localSearchActive()&&gifOwner.current==null&&isInputViewShown
    private fun cursorSteps(connection:InputConnection,lease:ImeRepeatLease,steps:Int):Boolean {
        editBarrier()
        val code=if(steps<0)KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(kotlin.math.abs(steps)){
            if(!cursorGestureAllowed()||!lease.canRun(currentInputConnection))return false
            val at=SystemClock.uptimeMillis()
            fun event(action:Int)=KeyEvent(at,at,action,code,0,0,android.view.KeyCharacterMap.VIRTUAL_KEYBOARD,0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,android.view.InputDevice.SOURCE_KEYBOARD)
            val down=connection.sendKeyEvent(event(KeyEvent.ACTION_DOWN))
            val up=connection.sendKeyEvent(event(KeyEvent.ACTION_UP)) // Same connection, even if focus changes after DOWN.
            if(!down||!up)return false // Dispatch is not a host ACK. No retry/rollback or text fallback.
        }
        return true
    }
    private fun spaceKey():GlassKeyButton {
        val view=key("English","Space",true){}
        val slop=android.view.ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        val motion=HorizontalKeyDrag(slop,dp(14).toFloat().coerceAtLeast(slop+1),android.view.ViewConfiguration.getLongPressTimeout().toLong())
        var lease:ImeRepeatLease?=null;var connection:InputConnection?=null;var touchContact=false
        lateinit var cancel:()->Unit
        fun finish(){lease?.close();lease=null;connection=null;if(cursorGestureCancel===cancel)cursorGestureCancel=null}
        cancel={motion.cancel();view.cancelPendingInputEvents();view.isPressed=false;finish()}
        view.fitText(20)
        view.setOnClickListener{
            val held=lease
            val valid=view.isAttachedToWindow&&isInputViewShown&&!swipePending&&view.interactionAllowed()&&(held==null||held.canRun(currentInputConnection))
            finish();if(valid)type(" ")
        }
        view.setOnLongClickListener {
            val valid=lease?.canRun(currentInputConnection)!=false&&view.isAttachedToWindow&&isInputViewShown&&!swipePending&&view.interactionAllowed()
            cancel()
            if(valid){cancelOwnedVoice();getSystemService(android.view.inputmethod.InputMethodManager::class.java).showInputMethodPicker()}
            true
        }
        view.setOnTouchListener { _,event->
            val exploration=getSystemService(android.view.accessibility.AccessibilityManager::class.java).isTouchExplorationEnabled
            if(exploration){
                if(touchContact){cancel();if(event.actionMasked==android.view.MotionEvent.ACTION_UP||event.actionMasked==android.view.MotionEvent.ACTION_CANCEL)touchContact=false;return@setOnTouchListener true}
                return@setOnTouchListener false // Preserve native TalkBack button gestures.
            }
            when(event.actionMasked){
                android.view.MotionEvent.ACTION_DOWN->{
                    cancelKeyGestures();view.cancelPendingInputEvents();touchContact=true
                    connection=currentInputConnection;lease=connection?.let{ImeRepeatLease(session,it)}
                    motion.down(event.x,event.y,event.eventTime);cursorGestureCancel=cancel
                    if(lease==null||!view.interactionAllowed()){cancel();true}else false
                }
                android.view.MotionEvent.ACTION_MOVE->{
                    if(!view.isAttachedToWindow||lease?.canRun(currentInputConnection)!=true||!view.interactionAllowed()){cancel();true}
                    else{
                        val update=motion.move(event.x,event.y,event.eventTime,event.pointerCount)
                        if(update.consume){view.cancelPendingInputEvents();view.isPressed=false}
                        if(update.begin){
                            if(!cursorGestureAllowed()){cancel();return@setOnTouchListener true}
                            stopRepeat();stopSwipe();cancelOwnedVoice();session.editorChanged()
                        }
                        if(update.steps!=0&&!cursorSteps(checkNotNull(connection),checkNotNull(lease),update.steps))cancel()
                        update.consume
                    }
                }
                android.view.MotionEvent.ACTION_UP->{
                    val valid=lease?.canRun(currentInputConnection)==true&&view.isAttachedToWindow&&view.interactionAllowed()
                    val consume=motion.up(event.x,event.y,event.pointerCount).consume||!valid
                    touchContact=false
                    if(consume)cancel() // A drag never dispatches a trailing Space.
                    consume // Ordinary tap keeps its lease until the native queued click runs.
                }
                android.view.MotionEvent.ACTION_CANCEL,android.view.MotionEvent.ACTION_POINTER_DOWN,android.view.MotionEvent.ACTION_POINTER_UP->{cancel();true}
                else->false
            }
        }
        view.accessibilityDelegate=object:View.AccessibilityDelegate(){
            override fun onInitializeAccessibilityNodeInfo(host:View,node:android.view.accessibility.AccessibilityNodeInfo){
                super.onInitializeAccessibilityNodeInfo(host,node)
                node.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id,"Move cursor left"))
                node.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id,"Move cursor right"))
            }
            override fun performAccessibilityAction(host:View,action:Int,args:android.os.Bundle?):Boolean{
                val step=when(action){android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id->-1;android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id->1;else->return super.performAccessibilityAction(host,action,args)}
                if(!host.isAttachedToWindow||!view.interactionAllowed()||!cursorGestureAllowed())return false
                cancelKeyGestures();stopRepeat();stopSwipe();cancelOwnedVoice();session.editorChanged()
                val conn=currentInputConnection?:return false;val once=ImeRepeatLease(session,conn)
                return try{cursorSteps(conn,once,step)}finally{once.close()}
            }
        }
        return view
    }
    private fun wordDeleteAllowed()=policy().voice&&selectionStart>=0&&selectionEnd>=selectionStart&&composingStart<0&&composingEnd<0&&
        !swipePending&&!dictation.busy&&!localSearchActive()&&gifOwner.current==null&&isInputViewShown
    private fun wordDeleteRead(connection:InputConnection):WordDeleteWindow? {
        if(!wordDeleteAllowed())return null // Refuse private fields BEFORE reading anything.
        val read=connection.getSurroundingText(64,64,0)?:return null
        if(read.offset<0||read.text.length>192||read.selectionStart<0||read.selectionEnd<read.selectionStart||
            read.selectionEnd>read.text.length||read.offset>Int.MAX_VALUE-read.text.length)return null
        return WordDeleteWindow(read.offset,read.text.toString(),read.offset+read.selectionStart,read.offset+read.selectionEnd).takeIf{it.valid}
    }
    private fun deleteKey(): GlassKeyButton {
        val view=key("", "Delete previous character; hold to repeat"){}
        view.icon=KeyIcon.DELETE
        val slop=android.view.ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        val motion=HorizontalKeyDrag(slop,dp(28).toFloat().coerceAtLeast(slop+1),android.view.ViewConfiguration.getLongPressTimeout().toLong())
        var lease:ImeRepeatLease?=null;var connection:InputConnection?=null;var transaction:WordDeleteTransaction?=null
        var touchContact=false;var pulse:Runnable?=null;var contactStarted=0L
        lateinit var cancel:()->Unit
        fun finish(){
            pulse?.let(main::removeCallbacks);pulse=null
            if(wordDeleteTransaction===transaction)wordDeleteTransaction=null
            val target=connection;val epoch=session.editorGeneration();val panel=emojiPanelToken
            transaction=null;lease?.close();lease=null;connection=null
            if(wordDeleteCancel===cancel)wordDeleteCancel=null
            if(page==KeyboardPage.EMOJI&&target!=null)main.post{
                if(page==KeyboardPage.EMOJI&&isInputViewShown&&currentInputConnection===target&&session.editorGeneration()==epoch&&emojiPanelToken===panel){renderKeys();updateState()}
            }
        }
        cancel={transaction?.cancel();motion.cancel();view.cancelPendingInputEvents();view.isPressed=false;stopRepeat();finish()}
        fun tick(){
            val tx=transaction?:return
            tx.advance()
            if(tx.running){
                if(pulse==null)pulse=Runnable{pulse=null;tick()}
                main.postDelayed(checkNotNull(pulse),16)
            }else{
                if(tx.state==WordDeleteTransaction.State.REFUSED)android.widget.Toast.makeText(this,"Editor did not confirm word deletion; check the selected text",android.widget.Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        view.setOnClickListener{
            val held=lease
            val valid=view.isAttachedToWindow&&isInputViewShown&&!swipePending&&view.interactionAllowed()&&(held==null||held.canRun(currentInputConnection))
            finish();if(valid)deletePrevious()
        }
        view.setOnLongClickListener{
            val valid=lease?.canRun(currentInputConnection)!=false&&view.isAttachedToWindow&&isInputViewShown&&!swipePending&&view.interactionAllowed()
            cancel();if(valid)startRepeat();true
        }
        view.setOnTouchListener{_,event->
            val exploration=getSystemService(android.view.accessibility.AccessibilityManager::class.java).isTouchExplorationEnabled
            if(exploration){
                if(touchContact){cancel();if(event.actionMasked==android.view.MotionEvent.ACTION_UP||event.actionMasked==android.view.MotionEvent.ACTION_CANCEL)touchContact=false;return@setOnTouchListener true}
                return@setOnTouchListener false
            }
            when(event.actionMasked){
                android.view.MotionEvent.ACTION_DOWN->{
                    cancelKeyGestures();view.cancelPendingInputEvents();touchContact=true
                    connection=currentInputConnection;lease=connection?.let{ImeRepeatLease(session,it)}
                    contactStarted=SystemClock.uptimeMillis();motion.down(event.x,event.y,event.eventTime);wordDeleteCancel=cancel
                    if(lease==null||!view.interactionAllowed()){cancel();true}else false
                }
                android.view.MotionEvent.ACTION_MOVE->{
                    if(!view.isAttachedToWindow||lease?.canRun(currentInputConnection)!=true||!view.interactionAllowed()){cancel();true}
                    else{
                        val update=motion.move(event.x,event.y,event.eventTime,event.pointerCount)
                        if(update.consume){view.cancelPendingInputEvents();view.isPressed=false}
                        if(update.begin){
                            if(update.steps>0||!wordDeleteAllowed()||selectionStart!=selectionEnd){cancel();return@setOnTouchListener true}
                            val conn=checkNotNull(connection)
                            val origin=WordDeleteCapture.read(contactStarted,{SystemClock.uptimeMillis()},
                                {lease?.canRun(currentInputConnection)==true&&view.isAttachedToWindow&&view.interactionAllowed()&&wordDeleteAllowed()&&selectionStart==selectionEnd},
                                {wordDeleteRead(conn)})
                            if(origin==null||origin.start!=selectionStart||origin.end!=selectionEnd||origin.start-origin.offset>64||origin.offset+origin.text.length-origin.end>64){cancel();return@setOnTouchListener true}
                            val starts=WordDeleteBoundaries.starts(origin)
                            if(starts.isEmpty()){cancel();return@setOnTouchListener true}
                            stopRepeat();stopSwipe();cancelOwnedVoice();session.editorChanged()
                            transaction=WordDeleteTransaction(origin,starts,object:WordDeleteTransaction.Port{
                                override fun valid()=lease?.canRun(currentInputConnection)==true&&view.isAttachedToWindow&&view.interactionAllowed()&&wordDeleteAllowed()
                                override fun read()=if(valid())wordDeleteRead(conn) else null
                                override fun select(start:Int,end:Int):Boolean {
                                    if(!valid())return false
                                    if(!editTransactions.expectSelection(start,end,ImeEditWindow(origin.offset,origin.text,origin.start,origin.end)))editBarrier()
                                    val accepted=conn.setSelection(start,end)
                                    if(!accepted)editBarrier()
                                    return accepted
                                }
                                override fun deleteSelection()=valid()&&ownEdit(conn,"",ImeEditJournal.Kind.DELETE,originalSelection=origin.start to origin.end){valid()&&conn.commitText("",1)} // Exact selected span, single attempt; no markers/fallback.
                            },{SystemClock.uptimeMillis()},contactStarted)
                            wordDeleteTransaction=transaction
                        }
                        transaction?.move(update.steps)
                        if(transaction!=null&&pulse==null)tick()
                        update.consume
                    }
                }
                android.view.MotionEvent.ACTION_UP->{
                    stopRepeat();touchContact=false
                    val valid=lease?.canRun(currentInputConnection)==true&&view.isAttachedToWindow&&view.interactionAllowed()
                    // Release coordinates can differ from the last MOVE. Validate them before destructive release.
                    if(transaction!=null){
                        val finalMove=motion.move(event.x,event.y,event.eventTime,event.pointerCount)
                        if(!valid||!motion.active){cancel();return@setOnTouchListener true}
                        transaction?.move(finalMove.steps)
                        motion.up(event.x,event.y,event.pointerCount)
                        transaction?.release();if(pulse==null)tick()
                        return@setOnTouchListener true
                    }
                    val consume=motion.up(event.x,event.y,event.pointerCount).consume||!valid
                    if(consume)cancel()
                    consume
                }
                android.view.MotionEvent.ACTION_CANCEL,android.view.MotionEvent.ACTION_POINTER_DOWN,android.view.MotionEvent.ACTION_POINTER_UP->{cancel();true}
                else->false
            }
        }
        return view
    }
    private fun actionKey(): GlassKeyButton = key(actionTitle(), if(emojiSearch.active)"Show offline emoji search results" else if(gifSearch.active)"Show local GIF search results" else "${actionTitle()} in the current editor", true) { editorAction() }.apply {
        fitText(18)
        icon=when(actionTitle()) { "Enter"->KeyIcon.ENTER;"Done"->KeyIcon.DONE;"Search"->KeyIcon.SEARCH;else->KeyIcon.NONE }
    }
    private fun renderSpec(spec: KeySpec): View = when(spec.action) {
        KeyAction.SPACER -> View(this)
        KeyAction.DELETE -> deleteKey()
        KeyAction.EDITOR_ACTION -> actionKey()
        KeyAction.SHIFT -> key("", "Shift; double-tap to lock capitals") {
            cancelOwnedVoice(); val now=SystemClock.uptimeMillis()
            if(now-lastShift in 1..350){caps=!caps;shift=false}else{shift=!shift;caps=false}
            lastShift=now;renderKeys()
        }.apply { icon=if(caps)KeyIcon.CAPS else if(shift)KeyIcon.SHIFT_ON else KeyIcon.SHIFT;accessibilityToggle=shift||caps;stateDescription=if(caps)"Caps Lock on" else if(shift)"Shift on" else "Shift off";setOnLongClickListener { if(swipePending||!isAttachedToWindow||!interactionAllowed())return@setOnLongClickListener true;caps=!caps;shift=false;renderKeys();true } }
        KeyAction.MODE -> key(spec.label,if(spec.label=="!?#")"Show symbols" else "Switch letters and symbols",true) {
            cancelOwnedVoice();page=if(spec.label=="!?#" || page==KeyboardPage.LETTERS)KeyboardPage.SYMBOLS else KeyboardPage.LETTERS;renderTypedMode()
        }.apply { fitText(20) }
        KeyAction.EMOJI -> key("","Emoji keyboard") { cancelOwnedVoice();openEmoji() }.apply { icon=KeyIcon.FACE }
        KeyAction.SPACE -> spaceKey()
        KeyAction.EXTRA_SYMBOLS -> key(spec.label,"More symbols") { cancelOwnedVoice();page=if(page==KeyboardPage.SYMBOLS)KeyboardPage.EXTRA_SYMBOLS else KeyboardPage.SYMBOLS;renderKeys() }.apply { fitText(17) }
        KeyAction.NUMBER_PAD -> key(spec.label,"Show number pad") { cancelOwnedVoice();page=KeyboardPage.NUMBER_PAD;renderKeys() }.apply { fitText(12) }
        KeyAction.TYPE -> key(spec.label) { type(spec.label) }.apply {
            if(spec.hint!=null&&spec.label.length==1&&spec.label[0].isLetter())tag="swipe:"+spec.label.lowercase(java.util.Locale.ROOT)
            hintLabel=spec.hint
            spec.hint?.let { hint ->setOnLongClickListener { if(isAttachedToWindow&&interactionAllowed())type(hint);true };accessibilityPaneTitle=null }
        }
    }
    private fun renderTypedMode(){
        // A letters/symbols layout switch is not an editor mutation. Carry only
        // an exact owned typed span, never a pending correction or swipe claim.
        val state=candidateOwner.current
        val vocabularyEpoch=candidateVocabularyEpoch
        val connection=currentInputConnection
        val keep=vocabularyEpoch!=null&&swipeVocabulary.isCurrent(vocabularyEpoch)&&state?.origin==CandidateOrigin.TYPED&&connection!=null&&correction==null&&
            ownsTyped(state,typingCursor(connection),connection)
        renderKeys()
        if(keep&&state!=null&&connection!=null&&vocabularyEpoch!=null&&ownsTyped(state,typingCursor(connection),connection))
            stageCandidates(state.base,state.inserted,state.alternatives,connection,state.epoch,CandidateOrigin.TYPED,
                expectSelection=state.expectedSelection,automaticAllowed=state.automaticAllowed,expectedVocabulary=vocabularyEpoch)
    }
    private fun renderKeys() {
        closeTextEditing()
        cancelKeyGestures()
        closeClipboard();stopRepeat();stopSwipe();gifPanel?.close();gifPanel=null;gifKeysNeedRebind=false
        emojiPanel?.close();emojiPanel=null;emojiPanelToken=Any();keyboardRenderToken=Any();emojiKeysNeedRebind=false
        stickerPanel?.close();stickerPanel=null
        if(page!=KeyboardPage.STICKERS)invalidateStickers()
        val container=keys?:return;container.removeAllViews()
        if(page==KeyboardPage.CLIPBOARD){renderClipboard(container);return}
        if(page==KeyboardPage.TEXT_EDITING){renderTextEditing(container);return}
        if(page==KeyboardPage.STICKERS){
            if(!StickerBrowserFeature.enabled(this)){page=KeyboardPage.LETTERS;invalidateStickers();renderKeys();return}
            val libraryEpoch=stickerStateEpoch
            val attachment:((StickerAssetIdentity)->Unit)?=if(libraryEpoch!=null&&dev.lip.stickers.StickerLibraryRevision.isCurrent(libraryEpoch)&&stickerAttachmentAvailable())
                {asset->if(dev.lip.stickers.StickerLibraryRevision.isCurrent(libraryEpoch))requestStickerOriginal(asset)} else null
            lateinit var panel:StickerPanel
            panel=StickerPanel(this,rowHeight()*5,stickerState,stickerScale,
                {invalidateStickers();page=KeyboardPage.LETTERS;renderKeys();updateState()},
                ::manageStickerPacks,{loadStickers(StickerBrowserPage.Library())},
                {id->loadStickers(StickerBrowserPage.Pack(id))},
                {stickerPage.previous()?.let(::loadStickers)},{loadStickers(stickerPage.next())},
                attachment,
                {asset,current->captureStickerAttachment(asset){libraryEpoch!=null&&dev.lip.stickers.StickerLibraryRevision.isCurrent(libraryEpoch)&&current()}},
                {if(stickerPanel===panel){stickerAttachment?.cancel();productionStickerAttachment?.cancel()}})
            stickerPanel=panel
            stickerPanel?.setAttachmentPending(stickerAttachmentBusy()||!stickerAttachmentAvailable())
            container.addView(stickerPanel)
            toolsBar?.visibility=View.GONE;toolsFrame?.visibility=View.GONE
            return
        }
        if(page==KeyboardPage.GIF){
            gifPanel=GifPanel(this,rowHeight()*5,gifSearch.query,GifCatalog.accepts(info?.contentMimeTypes),::requestGif,
                {if(gifOwner.current==null){cancelOwnedVoice();gifSearch.begin();page=KeyboardPage.LETTERS;renderKeys();updateState()}},
                {clearGifSearch();page=KeyboardPage.LETTERS;renderKeys();updateState()},
                {stopGif();updateState()})
            container.addView(gifPanel)
            toolsBar?.visibility=View.GONE;toolsFrame?.visibility=View.GONE
            return
        }
        val numeric=ImeKeyLayout.numericRows(info?.inputType?:0)
        if(numeric!=null){
            numeric.forEach { values ->
                val row=LinearLayout(this).apply { isBaselineAligned=false }
                values.forEach { value->row.addView(key(value){type(value)},LinearLayout.LayoutParams(0,rowHeight(),1f)) }
                container.addView(row)
            }
            val row=LinearLayout(this).apply { isBaselineAligned=false }
            row.addView(renderSpec(KeySpec("English",KeyAction.SPACE)),LinearLayout.LayoutParams(0,rowHeight(),2f))
            row.addView(deleteKey(),LinearLayout.LayoutParams(0,rowHeight(),1f))
            row.addView(actionKey(),LinearLayout.LayoutParams(0,rowHeight(),1f));container.addView(row)
            return
        }
        if(page==KeyboardPage.NUMBER_PAD){renderNumberPad(container);return}
        if(page==KeyboardPage.EMOJI){renderEmoji(container);return}
        val rows=KeyboardLayoutSpec.rows(page,shift||caps)
        val heights=KeyboardLayoutSpec.rowHeights(rowHeight()*5,rows.size)
        rows.forEachIndexed { index,specs->
            val h=heights[index]
            val row=LinearLayout(this).apply { isBaselineAligned=false }
            specs.forEach { spec->row.addView(renderSpec(spec),LinearLayout.LayoutParams(0,h,spec.units)) }
            container.addView(row)
        }
    }
    private fun renderNumberPad(container: LinearLayout) {
        val height=rowHeight()*5
        val row=LinearLayout(this).apply { isBaselineAligned=false }
        val left=column();val center=column();val right=column()
        listOf("+","-","*","/").forEach { value->left.addView(key(value){type(value)},LinearLayout.LayoutParams(-1,0,1f)) }
        left.addView(key("ABC","Switch letters and symbols",true){page=KeyboardPage.LETTERS;renderKeys()},LinearLayout.LayoutParams(-1,0,1f))
        listOf("123","456","789").forEach { values->
            val line=LinearLayout(this).apply { isBaselineAligned=false };values.forEach { c->line.addView(key(c.toString()){type(c.toString())},LinearLayout.LayoutParams(0,-1,1f)) };center.addView(line,LinearLayout.LayoutParams(-1,0,1f))
        }
        val last=LinearLayout(this).apply { isBaselineAligned=false }
        listOf(KeySpec(","),KeySpec("!?#",KeyAction.MODE),KeySpec("0"),KeySpec("="),KeySpec(".")).forEach { spec->last.addView(renderSpec(spec),LinearLayout.LayoutParams(0,-1,if(spec.label=="0")1.5f else 1f)) }
        center.addView(last,LinearLayout.LayoutParams(-1,0,1f))
        right.addView(key("%"){type("%")},LinearLayout.LayoutParams(-1,0,1f))
        right.addView(key("", "Space") { type(" ") }.apply { icon=KeyIcon.SPACE },LinearLayout.LayoutParams(-1,0,1f))
        right.addView(deleteKey(),LinearLayout.LayoutParams(-1,0,1f));right.addView(actionKey(),LinearLayout.LayoutParams(-1,0,1f))
        row.addView(left,LinearLayout.LayoutParams(0,height,1.5f));row.addView(center,LinearLayout.LayoutParams(0,height,7f));row.addView(right,LinearLayout.LayoutParams(0,height,1.5f));container.addView(row)
    }
    private fun renderEmoji(container: LinearLayout) {
        val token=emojiPanelToken
        val ticket=emojiPickOwner.capture(currentInputConnection,token,selectionStart,selectionEnd)
        emojiPanel=EmojiPanel(this,rowHeight()*(if(landscape())6 else 7),if(landscape())10 else 7,
            emojiCatalog,emojiLoadError,emojiSearch.query,emojiCategory,emojiRecents,privateEmojiField(),deleteKey(),
            {item->pickEmoji(item,ticket,token)},::beginEmojiSearch,
            {clearEmoji();page=KeyboardPage.LETTERS;renderKeys();updateState()},::openGifs,
            {group->emojiCategory=group;renderKeys();updateState()},
            {if(!privateEmojiField()){emojiRecents.enable();renderKeys();updateState()}},presentation.emojiPreview)
        container.addView(emojiPanel)
        toolsBar?.visibility=View.GONE;toolsFrame?.visibility=View.GONE
    }
    private fun renderClipboard(container:LinearLayout){
        val connection=currentInputConnection?:return
        val lease=ImeRepeatLease(session,connection);clipboardLease=lease
        val h=rowHeight()
        val actions=LinearLayout(this).apply{isBaselineAligned=false}
        actions.addView(label("Clipboard",16f).apply{setTextColor(KeyboardVisualTokens.FOREGROUND);setPadding(dp(8),0,dp(4),0)},LinearLayout.LayoutParams(0,h,1.5f))
        clipboardCopy=key("Copy","Copy selected text to the system clipboard"){clipboardAction(true,lease)}.apply{fitText(16)}
        clipboardPaste=key("Paste","Paste system clipboard using the current editor"){clipboardAction(false,lease)}.apply{fitText(16)}
        actions.addView(clipboardCopy,LinearLayout.LayoutParams(0,h,.9f));actions.addView(clipboardPaste,LinearLayout.LayoutParams(0,h,.8f))
        actions.addView(key("ABC","Return from clipboard to letters"){page=KeyboardPage.LETTERS;renderKeys();updateState()}.apply{fitText(16)},LinearLayout.LayoutParams(0,h,.8f))
        container.addView(actions)
        historyArea=column()
        container.addView(android.widget.ScrollView(this).apply{isFillViewport=true;addView(historyArea)},LinearLayout.LayoutParams(-1,h*4))
        renderHistoryArea();updateState()
    }
    private fun historyKey(view:GlassKeyButton,id:String,command:String):GlassKeyButton=view.apply{
        accessibilityDelegate=object:View.AccessibilityDelegate(){
            override fun onInitializeAccessibilityNodeInfo(host:View,info:android.view.accessibility.AccessibilityNodeInfo){
                super.onInitializeAccessibilityNodeInfo(host,info)
                info.extras.putString("dev.lip.clipId",id);info.extras.putString("dev.lip.clipCommand",command)
            }
        }
    }
    private fun renderHistoryArea(){
        val area=historyArea?:return;val controller=history?:return;val lease=clipboardLease?:return
        val rowsToken=Any();historyRowsToken=rowsToken
        area.removeAllViews()
        fun note(text:String){area.addView(label(text,14f).apply{setTextColor(KeyboardVisualTokens.FOREGROUND);setPadding(dp(12),dp(6),dp(12),dp(6))},LinearLayout.LayoutParams(-1,-2))}
        fun action(text:String,description:String=text,enabled:Boolean=true,run:()->Unit){
            area.addView(key(text,description){
                if(page==KeyboardPage.CLIPBOARD&&lease.canRun(currentInputConnection)&&clipboardAvailability().paste&&historyContextAllowed())run()
            }.apply{fitText(16);isEnabled=enabled},LinearLayout.LayoutParams(-1,rowHeight()))
        }
        val prompt=historyConfirmation.current
        when(prompt?.kind){
            "enable"->{
                note("Save future copied text only while Lip's keyboard is visible in an ordinary field. Private/incognito/locked contexts and sensitive-marked items are skipped. Apps may not flag secrets: avoid copying secrets while this is enabled. Up to20 text items,4096 characters each; unpinned items expire after1hour. Up to10 pinned items remain until removed. Encrypted on this device, not backed up. The existing clipboard is not imported.")
                action("Agree and enable","Agree and enable clipboard text history"){if(historyConfirmation.claim(prompt)){controller.enable();renderHistoryArea()}}
                action("Cancel"){if(historyConfirmation.claim(prompt))renderHistoryArea()};return
            }
            "clear","off"->{
                val disable=prompt.kind=="off"
                note(if(disable)"Turn off and erase Lip's saved clipboard text? The system clipboard is not changed." else "Erase Lip's saved clipboard text? Future capture remains enabled. The system clipboard is not changed.")
                action("Confirm",if(disable)"Confirm turn off and erase clipboard history" else "Confirm erase clipboard history"){if(historyConfirmation.claim(prompt))controller.clear(disable)}
                action("Cancel"){if(historyConfirmation.claim(prompt))renderHistoryArea()};return
            }
        }
        if(controller.busy)note("Updating encrypted history…")
        controller.message?.let{note(it);action("Retry history",enabled=!controller.busy){controller.retry()}}
        if(!controller.enabled){
            note("History is off. Lip does not read or save clipboard content without consent.")
            action("Enable text history","Enable clipboard text history",!controller.busy&&!controller.paused){historyConfirmation.begin("enable");renderHistoryArea()}
            if(controller.paused)action("Turn off and erase","Turn off and erase clipboard history",!controller.busy){historyConfirmation.begin("off");renderHistoryArea()}
            return
        }
        note("History on while keyboard is visible ·20 items ·1h unpinned ·10 pins")
        action("Save current clipboard","Save current clipboard to encrypted history",!controller.busy&&!controller.paused){captureClipboard(manual=true)}
        for(item in controller.items){
            val row=LinearLayout(this).apply{isBaselineAligned=false}
            val preview=item.text.replace("\n"," ↵ ").let{if(it.codePointCount(0,it.length)>48)it.substring(0,it.offsetByCodePoints(0,48))+"…" else it}
            row.addView(historyKey(key(preview,"Paste saved clipboard text: $preview"){if(historyRowsToken===rowsToken)pasteSavedClip(item.id,lease)},item.id,"paste").apply{
                fitText(16);setSingleLine();ellipsize=android.text.TextUtils.TruncateAt.END;isEnabled=!controller.busy
            },LinearLayout.LayoutParams(0,rowHeight(),4f))
            row.addView(historyKey(key(if(item.pinned)"Unpin" else "Pin","${if(item.pinned)"Unpin" else "Pin"} saved clipboard text: $preview"){
                if(historyRowsToken===rowsToken&&lease.canRun(currentInputConnection))controller.pin(item.id,!item.pinned)
            },item.id,"pin").apply{accessibilityToggle=item.pinned;stateDescription=if(item.pinned)"Pinned" else "Unpinned";fitText(12);isEnabled=!controller.busy&&(item.pinned||controller.items.count{it.pinned}<ClipboardHistory.MAX_PINS)},LinearLayout.LayoutParams(0,rowHeight(),1.2f))
            row.addView(historyKey(key("×","Delete saved clipboard text: $preview"){
                if(historyRowsToken===rowsToken&&lease.canRun(currentInputConnection))controller.delete(item.id)
            },item.id,"delete").apply{fitText(18);isEnabled=!controller.busy},LinearLayout.LayoutParams(0,rowHeight(),.8f))
            area.addView(row)
        }
        action("Clear saved items","Clear clipboard history"){historyConfirmation.begin("clear");renderHistoryArea()}
        action("Turn off and erase","Turn off and erase clipboard history"){historyConfirmation.begin("off");renderHistoryArea()}
    }
    override fun onKeyDown(keyCode:Int,event:KeyEvent):Boolean{
        if(!localSearchActive()){editBarrier();return if(keyCode in gifConsumedHardwareKeys)true else super.onKeyDown(keyCode,event)}
        if(event.isSystem&&keyCode!=KeyEvent.KEYCODE_BACK)return super.onKeyDown(keyCode,event)
        if(keyCode in 0..KeyEvent.getMaxKeyCode())gifConsumedHardwareKeys.add(keyCode)
        when(keyCode){
            KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_NUMPAD_ENTER,KeyEvent.KEYCODE_BACK,KeyEvent.KEYCODE_ESCAPE->finishQuery()
            KeyEvent.KEYCODE_DEL->deleteQuery()
            else->{
                val code=event.unicodeChar
                if(!event.isCtrlPressed&&!event.isMetaPressed&&code!=0&&code and android.view.KeyCharacterMap.COMBINING_ACCENT==0&&Character.isValidCodePoint(code))
                    appendQuery(String(Character.toChars(code)))
            }
        }
        updateState();return true // Unknown editing shortcuts are refused, never forwarded into the host search target.
    }
    override fun onKeyUp(keyCode:Int,event:KeyEvent):Boolean{
        if(gifConsumedHardwareKeys.remove(keyCode))return true
        if(localSearchActive()&&!event.isSystem)return true
        return super.onKeyUp(keyCode,event)
    }
    override fun onKeyMultiple(keyCode:Int,count:Int,event:KeyEvent):Boolean{
        if(!localSearchActive()){editBarrier();return super.onKeyMultiple(keyCode,count,event)}
        event.characters?.let(::appendQuery);updateState();return true
    }
    private fun type(value: String) {
        cancelKeyGestures()
        if(localSearchActive()){appendQuery(value);updateState();return}
        if(swipePending)return
        val connection=currentInputConnection
        val prior=candidateOwner.current
        val cursor=if(connection!=null&&getSharedPreferences("preferences",MODE_PRIVATE).getBoolean("typingSuggestions",true)&&TypingPolicy.forInput(info?.inputType?:0,info?.imeOptions?:0).suggest) typingCursor(connection) else null
        val continuing=connection!=null&&prior!=null&&ownsTyped(prior,cursor,connection)&&TypingBoundary.suffix(prior.inserted).isEmpty()
        val letter=value.length==1&&(value[0] in 'a'..'z'||value[0] in 'A'..'Z'||value=="'")
        val boundary=cursor!=null&&cursor.before.lastOrNull()?.let{!it.isLetterOrDigit()&&it!='\''}!=false&&cursor.after.firstOrNull()?.let{!it.isLetterOrDigit()&&it!='\''}!=false
        val base=if(continuing)prior!!.base else cursor?.takeIf{boundary&&letter&&value!="'"}
        val text=if(continuing)prior!!.inserted+value else value
        stopSwipe();cancelOwnedVoice(); session.editorChanged()
        // A deliberate key inserts at the current caret. Finish markers first so a canceled,
        // late composing-region request cannot make this new key replace an earlier word.
        val accepted=connection!=null&&connection.finishComposingText()&&ownEdit(connection,value,ImeEditJournal.Kind.TYPING){connection.commitText(value,1)}
        if(!accepted)android.widget.Toast.makeText(this,"Editor did not confirm the typing request",android.widget.Toast.LENGTH_SHORT).show()
        val epoch=session.editorGeneration()
        if (shift && !caps && value.any(Char::isLetter)) { shift = false; renderKeys() }
        if(accepted&&base!=null&&epoch!=null&&TypingBoundary.valid(text)&&(letter||continuing&&TypingBoundary.isDelimiter(value)))typedCandidates(base,text,connection!!,epoch,if(continuing&&TypingBoundary.isDelimiter(value))prior!!.automaticAllowed else true)
    }
    private fun deletePrevious() {
        cancelKeyGestures()
        if(localSearchActive()){deleteQuery();updateState();return}
        if(swipePending)return
        val undo=candidateOwner.current
        if(undo?.origin==CandidateOrigin.AUTO&&undo.ready){chooseCandidate(undo,0);return}
        val ownedConnection=currentInputConnection
        val ownedCursor=if(undo?.origin==CandidateOrigin.TYPED&&ownedConnection!=null)typingCursor(ownedConnection) else null
        val owned=if(undo!=null&&ownedConnection!=null&&ownsTyped(undo,ownedCursor,ownedConnection))undo else null
        stopSwipe();cancelOwnedVoice(); session.editorChanged()
        val connection = currentInputConnection ?: return
        if(!connection.finishComposingText())return // Explicit Delete targets the caret, never a late earlier composing span.
        if (owned==null&&selectionStart >= 0 && selectionEnd >= 0 && selectionStart != selectionEnd) {
            ownEdit(connection,"",ImeEditJournal.Kind.DELETE){connection.commitText("",1)}; return // Explicit user's Delete key, not dictation replacement.
        }
        if (!policy().voice) { editBarrier();connection.deleteSurroundingTextInCodePoints(1, 0); return }
        val before = connection.getTextBeforeCursor(64, 0)?.toString()
        if (before.isNullOrEmpty()) { editBarrier();connection.deleteSurroundingTextInCodePoints(1, 0); return }
        val iterator = BreakIterator.getCharacterInstance(); iterator.setText(before)
        val boundary = iterator.preceding(before.length)
        if (boundary >= 0){
            val removed=before.length-boundary
            val accepted=ownEdit(connection,"",ImeEditJournal.Kind.DELETE,removeBefore=removed){connection.deleteSurroundingText(removed,0)}
            val remaining=owned?.inserted?.dropLast(removed)
            val epoch=session.editorGeneration()
            if(accepted&&owned!=null&&connection===ownedConnection&&epoch==owned.epoch&&!remaining.isNullOrEmpty()&&
                remaining.length<=48&&remaining.all{it in 'a'..'z'||it in 'A'..'Z'||it=='\''})
                typedCandidates(owned.base,remaining,connection,epoch,if(TypingBoundary.suffix(owned.inserted).isNotEmpty())owned.automaticAllowed else true) // No inference over arbitrary host words.
        }
    }
    private fun resolvedAction(): ImeResolvedAction = info?.let {
        ImeResolvedAction.resolve(it.imeOptions, it.actionLabel?.toString(), it.actionId)
    } ?: ImeResolvedAction("Enter", null)
    private fun actionTitle(): String = if(localSearchActive())"Search" else resolvedAction().label
    private fun editorAction() {
        cancelKeyGestures()
        if(localSearchActive()){finishQuery();return}
        if(swipePending)return
        stopSwipe();cancelOwnedVoice(); session.editorChanged()
        val connection = currentInputConnection ?: return
        val attribute = info ?: return
        if(!connection.finishComposingText())return // Explicit Enter/action must not replace a late earlier span.
        val action = resolvedAction()
        if (action.actionId != null) {
            editBarrier()
            connection.performEditorAction(action.actionId) // Only an explicit user's action-key tap.
        } else if (attribute.inputType == InputType.TYPE_NULL) {
            editBarrier()
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        } else ownEdit(connection,"\n",ImeEditJournal.Kind.NEWLINE){connection.commitText("\n",1)}
    }
    private fun candidateCursor(state:SwipeCandidateState,connection:InputConnection)=
        if(state.origin==CandidateOrigin.SWIPE)snapshot(connection) else typingCursor(connection)
    private fun typingCursor(connection:InputConnection):ImeCursor?{
        val attribute=info?:return null
        if(!TypingPolicy.forInput(attribute.inputType,attribute.imeOptions).suggest)return null
        val surrounding=connection.getSurroundingText(32,32,0)?:return null
        val start=surrounding.selectionStart;val end=surrounding.selectionEnd;val text=surrounding.text
        if(surrounding.offset<0||start<0||end!=start||start>text.length||surrounding.offset>Int.MAX_VALUE-start)return null
        val at=surrounding.offset+start
        return ImeCursor(attribute.packageName?:return null,attribute.fieldId,at,at,text.subSequence(0,start).toString().takeLast(32),text.subSequence(end,text.length).toString().take(32),false)
    }
    private fun snapshot(connection: InputConnection): ImeCursor? {
        val attribute = info ?: return null
        val field = policy()
        if (field.protected || !field.voice) return null // Never read password contents.
        if (selectionStart < 0 || selectionEnd != selectionStart) return null
        val before = connection.getTextBeforeCursor(32, 0)?.toString() ?: return null
        val after = connection.getTextAfterCursor(32, 0)?.toString() ?: return null
        return ImeCursor(attribute.packageName ?: return null, attribute.fieldId, selectionStart, selectionEnd, before, after, field.protected)
    }
    private fun toggleVoice() {
        cancelKeyGestures()
        if(swipePending||localSearchActive()||gifOwner.current!=null)return
        stopSwipe()
        if (voiceTicket != null && dictation.phase == Phase.LISTENING) { dictation.stop(); return }
        if (dictation.busy || !policy().voice) { status?.text = "Voice unavailable in this field"; return }
        val connection = currentInputConnection ?: return
        val ticket = session.capture(snapshot(connection)) ?: run { status?.text = "Place an unselected text cursor first"; return }
        voiceTicket = ticket
        dictation.start(insert = { text, done ->
            if (currentInputConnection !== connection || text.isBlank() || text.length > 16_384 ||
                !session.claim(ticket, snapshot(connection))) { voiceTicket = null; done(false) }
            else {
                voiceTicket = null // Own selection callback must not cancel the already dispatched operation.
                val accepted = ownEdit(connection,text,ImeEditJournal.Kind.DICTATION){session.claimedContextCurrent(ticket,snapshot(connection))&&connection.commitText(text,1)}
                session.editorChanged()
                if (!accepted) done(false) // A false return can still be ambiguous: no retry/fallback.
                else main.postDelayed({
                    val current = if (currentInputConnection === connection) snapshot(connection) else null
                    val expectedBefore = (ticket.cursor.before + text).takeLast(32)
                    done(session.sameEditor(ticket) && current != null && current.packageName == ticket.cursor.packageName &&
                        current.fieldId == ticket.cursor.fieldId && current.start == ticket.cursor.start + text.length &&
                        current.end == current.start && current.before == expectedBefore && current.after == ticket.cursor.after)
                }, 80)
            }
        }, stillValid = { currentInputConnection === connection && session.isCurrent(ticket, snapshot(connection)) },
            retainHistory = info?.imeOptions?.and(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0)
        updateState()
    }
    private fun cancelOwnedVoice() {
        voiceOwner.cancel { dictation.interrupt() }
    }
    private fun updateState() {
        refreshAgentShortcut()
        val toolbarValid=toolbarSnapshot?.valid==true
        updateTextEditing()
        swipeFrame?.setInputPaused(swipePending)
        swipeOverlay?.apply{text=swipeMessage?.let{"$it · Tap to cancel"}?:"";contentDescription=text;visibility=if(swipeMessage!=null)View.VISIBLE else View.GONE}
        renderCandidates()
        val clipboard=clipboardAvailability()
        clipboardTool?.isEnabled=toolbarValid&&clipboard.paste&&!dictation.busy
        clipboardCopy?.isEnabled=clipboard.copy&&!dictation.busy
        clipboardPaste?.isEnabled=clipboard.paste&&!dictation.busy
        val candidatesVisible=(candidateOwner.current!=null||nextWordOwner.current?.choices?.isNotEmpty()==true)&&swipeMessage==null
        candidateBar?.visibility=if(candidatesVisible)View.VISIBLE else View.GONE
        val dedicatedMediaHeader=page==KeyboardPage.EMOJI||page==KeyboardPage.GIF||page==KeyboardPage.STICKERS
        toolsFrame?.visibility=if(dedicatedMediaHeader)View.GONE else View.VISIBLE
        toolsBar?.visibility=if(dedicatedMediaHeader)View.GONE else if(swipeMessage!=null||candidatesVisible)View.INVISIBLE else View.VISIBLE
        candidateMic?.isEnabled=policy().voice&&!dictation.busy
        gifPanel?.setBusy(gifOwner.current!=null)
        stickerPanel?.setAttachmentPending(stickerAttachmentBusy()||!stickerAttachmentAvailable())
        gifTool?.isEnabled=toolbarValid&&policy().voice&&!dictation.busy
        undoTool?.isEnabled=toolbarValid&&!localSearchActive()
        textEditingTool?.isEnabled=toolbarValid&&TextEditingActions.allowed(textEditingState())&&!info?.packageName.isNullOrBlank()
        stickerTool?.isEnabled=toolbarValid&&stickerSafety().eligible
        mic?.isEnabled = !localSearchActive()&&gifOwner.current==null&&policy().voice && (!dictation.busy || voiceTicket != null)
        (mic as? GlassKeyButton)?.icon=if(voiceTicket!=null && dictation.phase==Phase.LISTENING)KeyIcon.STOP else KeyIcon.MIC
        status?.apply{
            maxLines=if(localSearchActive())1 else Int.MAX_VALUE
            ellipsize=if(localSearchActive())android.text.TextUtils.TruncateAt.END else null
            minimumHeight=if(localSearchActive())dp(40) else 0
            textSize=if(emojiSearch.active)16f else 12f
        }
        status?.text = when {
            emojiSearch.active->"Search emoji: ${emojiSearch.query}"
            gifSearch.active->"Search local GIFs: ${gifSearch.query} · Search key shows results"
            policy().protected || (info?.imeOptions?.and(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) ?: 0) != 0 -> "Private field · manual keys only"
            voiceTicket != null || dictation.phase == Phase.ERROR -> dictation.message
            toolbarSnapshot?.valid==false->"Toolbar settings unavailable · Tools → Keyboard preferences → Customize toolbar"
            else -> "Lip keyboard preview · swipe not yet qualified"
        }
        status?.visibility=if(localSearchActive()||voiceTicket!=null || dictation.phase==Phase.ERROR || policy().protected || toolbarSnapshot?.valid==false)View.VISIBLE else View.GONE
        if (!dictation.busy) voiceTicket = null
    }
    override fun onWindowHidden() {
        invalidateToolbarContacts()
        closeTextEditing()
        clearOwnedEdits()
        revokePersonalAccess()
        agentController?.bindUi(null)
        cancelKeyGestures()
        invalidateStickers();clearEmoji(true);clearGifSearch();gifPanel?.close()
        clipboardWindowVisible=false;history?.setEligible(false)
        closeClipboard();stopRepeat(); stopSwipe();cancelOwnedVoice(); session.editorChanged(); super.onWindowHidden()
    }
    private fun startRepeat() {
        if(swipePending)return
        stopRepeat()
        val connection = currentInputConnection ?: return
        val lease = ImeRepeatLease(session, connection); repeatLease = lease
        val task = object : Runnable {
            override fun run() {
                if (!lease.canRun(currentInputConnection) || repeatLease !== lease) { stopRepeat(); return }
                deletePrevious(); main.postDelayed(this, 65)
            }
        }
        repeatDelete = task; main.post(task)
    }
    private fun stopRepeat() {
        repeatLease?.close(); repeatLease = null
        repeatDelete?.let(main::removeCallbacks); repeatDelete = null
    }
}
