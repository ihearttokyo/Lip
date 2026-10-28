package dev.lip;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** SDK-only independent editor/IME ingress driver. No production classes, URI logging or modeled acknowledgements. */
public final class StickerImeActionDriver extends Instrumentation {
    private static final String APP="dev.lip.android.sizingfixture", TEST=APP+".test";
    private static final String SYNTHETIC="Inspect saved sticker pack Synthetic test";
    private static final String SCROLL="Local sticker browser results";
    private static final String RED_SHA="df75ecf5f620d125e80ae77bf639cecad76878216cdafb71afef124b04bbf5dc";
    private static final String ANIMATED_SHA="980b75994d7149d6135819f1dc0cd3c892d7791536c605e520581b077b4e0b91";
    private static final class Launch {
        final String generation;
        final CountDownLatch created=new CountDownLatch(1);
        final AtomicReference<StickerEditorFixtureActivity> actor=new AtomicReference<>();
        boolean duplicate;
        Launch(String generation){this.generation=generation;}
    }
    private UiAutomation ax;
    private String owner,mode,phase,stage="guard";
    private long deadline;
    private int imePid,baselineDispatches;
    private String serviceId;
    private StickerEditorFixtureActivity fixture;
    private Activity resumed;
    private Application application;
    private Application.ActivityLifecycleCallbacks lifecycle;
    private boolean registered,cleanupRequested,nativeBound;
    private final ArrayList<Launch> admitted=new ArrayList<>();
    private final ArrayList<StickerEditorFixtureActivity> actors=new ArrayList<>();
    private final JSONObject report=new JSONObject();
    private final JSONArray taps=new JSONArray(),ingress=new JSONArray();
    private static void require(boolean value,String token){if(!value)throw new AssertionError(token);}
    private void budget(){require(SystemClock.uptimeMillis()<deadline,"DRIVER_BUDGET_EXHAUSTED");}
    @Override public void onCreate(Bundle args){
        super.onCreate(args);
        try{
            require(args!=null,"ARGUMENTS_REQUIRED");owner=args.getString("owner");
            require(owner!=null&&UUID.fromString(owner).toString().equals(owner),"OWNER_REQUIRED");
            mode=args.getString("mode");require(Arrays.asList("accept","refuse","refuse-after-read","async","unsupported","composition","editor-replacement","window-replacement","stale-caret","unknown-offset","pending-cancel").contains(mode),"MODE_REQUIRED");
            phase=args.getString("phase","all");require(Arrays.asList("all","png","webp").contains(phase),"PHASE_REQUIRED");
            require("portrait".equals(args.getString("variant","portrait")),"PORTRAIT_ONLY_INITIAL_DRIVER");
            if(!Arrays.asList("accept","async").contains(mode))require(!"webp".equals(phase),"REFUSAL_AND_UNSUPPORTED_USE_PNG");
        }catch(Throwable t){Bundle out=new Bundle();out.putString("driverResult","{\"driverVersion\":1,\"passed\":false,\"cleanupConfirmed\":true,\"failureToken\":\"ARGUMENT_GUARD\"}");finish(Activity.RESULT_CANCELED,out);return;}
        deadline=SystemClock.uptimeMillis()+90_000;start();
    }
    private <T>T ui(Supplier<T> action){
        AtomicReference<T> value=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();
        runOnMainSync(()->{try{value.set(action.get());}catch(Throwable t){failure.set(t);}});
        if(failure.get()!=null)throw new AssertionError("OWNED_UI_OPERATION_FAILED");return value.get();
    }
    private boolean owned(String pkg){return APP.equals(pkg)||TEST.equals(pkg);}
    private List<AccessibilityNodeInfo> nodes(){
        budget();require(android.os.Build.VERSION.SDK_INT>=33&&ax.clearCache(),"FRESH_AX_CACHE_REQUIRED");
        List<AccessibilityNodeInfo> out=new ArrayList<>();
        java.util.function.BiConsumer<AccessibilityNodeInfo,Integer> walk=new java.util.function.BiConsumer<>(){
            @Override public void accept(AccessibilityNodeInfo n,Integer depth){
                if(n==null||depth>32||out.size()>=4096)return;
                if(owned(String.valueOf(n.getPackageName())))out.add(n);
                for(int i=0;i<n.getChildCount();i++)accept(n.getChild(i),depth+1);
            }
        };
        ax.getWindows().forEach(w->walk.accept(w.getRoot(),0));return out;
    }
    private List<AccessibilityNodeInfo> matching(String label){
        List<AccessibilityNodeInfo> found=new ArrayList<>();
        for(AccessibilityNodeInfo n:nodes())if(n.isVisibleToUser()&&owned(String.valueOf(n.getPackageName()))&&
            (label.contentEquals(n.getContentDescription()==null?"":n.getContentDescription())||
             !n.isEditable()&&label.contentEquals(n.getText()==null?"":n.getText())))found.add(n);
        require(found.size()<=1,"AMBIGUOUS_OWNED_CONTROL");return found;
    }
    private AccessibilityNodeInfo find(String label){
        long end=SystemClock.uptimeMillis()+8000;
        do{List<AccessibilityNodeInfo> n=matching(label);if(n.size()==1)return n.get(0);SystemClock.sleep(80);}while(SystemClock.uptimeMillis()<end);
        throw new AssertionError("OWNED_CONTROL_UNAVAILABLE");
    }
    private JSONObject witness() throws Exception {
        budget();byte[] bytes;
        try(java.io.InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(ax.executeShellCommand(
                "dumpsys activity service "+APP+"/dev.lip.keyboard.LipInputMethodService --lip-sizing-witness"))){bytes=in.readNBytes(65537);}
        require(bytes.length<=65536,"WITNESS_BOUND");String value=null;
        for(String line:new String(bytes,StandardCharsets.UTF_8).split("\n"))if(line.trim().startsWith("LIP_SIZING_WITNESS ")){
            require(value==null,"DUPLICATE_WITNESS");value=line.trim().substring("LIP_SIZING_WITNESS ".length());
        }
        if(value==null||"unavailable".equals(value))return null;
        JSONObject w=new JSONObject(value);require(w.getBoolean("ownedEditor"),"WITNESS_EDITOR_OWNER");
        require(w.getInt("pid")!=android.os.Process.myPid(),"INDEPENDENT_IME_PROCESS_REQUIRED");
        if(imePid!=0)require(w.getInt("pid")==imePid&&w.getString("serviceId").equals(serviceId),"IME_INCARNATION_CHANGED");
        return w;
    }
    private List<JSONObject> role(JSONObject w,String role) throws Exception {
        List<JSONObject> out=new ArrayList<>();if(w==null)return out;JSONArray rows=w.getJSONArray("views");
        for(int i=0;i<rows.length();i++){JSONObject v=rows.getJSONObject(i);if(role.equals(v.getString("role")))out.add(v);}return out;
    }
    private Rect rect(JSONObject v) throws Exception{return new Rect(v.getInt("x"),v.getInt("y"),v.getInt("x")+v.getInt("width"),v.getInt("y")+v.getInt("height"));}
    private void event(int action,float x,float y,long down){
        MotionEvent.PointerProperties pointer=new MotionEvent.PointerProperties();pointer.id=0;pointer.toolType=MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords position=new MotionEvent.PointerCoords();position.x=x;position.y=y;position.pressure=1f;position.size=1f;
        MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,1,new MotionEvent.PointerProperties[]{pointer},new MotionEvent.PointerCoords[]{position},0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        require(e.getToolType(0)==MotionEvent.TOOL_TYPE_FINGER&&e.getSource()==InputDevice.SOURCE_TOUCHSCREEN,"EXPLICIT_FINGER_PROTOCOL");
        try{require(ax.injectInputEvent(e,true),"OWNED_FINGER_INPUT_REFUSED");}finally{e.recycle();}
    }
    private interface GesturePreparation {void run() throws Exception;}
    /** role is fixed witness metadata; finder labels never enter reports or failure strings. */
    private void tap(String label,String nativeRole,String reportToken) throws Exception {
        tap(label,nativeRole,reportToken,null);
    }
    private void tap(String label,String nativeRole,String reportToken,GesturePreparation afterDown) throws Exception {
        ax.waitForIdle(150,2000);Rect previous=null,point=null;long stableSince=0,end=SystemClock.uptimeMillis()+8000;
        do{
            AccessibilityNodeInfo n=find(label);require(n.refresh()&&n.isEnabled()&&n.isVisibleToUser(),"CURRENT_CONTROL_NOT_ENABLED");
            Rect r=new Rect();n.getBoundsInScreen(r);require(!r.isEmpty(),"CURRENT_CONTROL_EMPTY");
            if(nativeRole!=null){
                JSONObject w=witness();List<Rect> overlaps=new ArrayList<>();
                for(JSONObject v:role(w,nativeRole))if(v.getBoolean("hasVisibleRect")&&v.getBoolean("enabled")){
                    Rect candidate=rect(v);if(candidate.intersect(r)&&!candidate.isEmpty())overlaps.add(candidate);
                }
                require(overlaps.size()<=1,"AMBIGUOUS_NATIVE_AX_OVERLAP");
                if(overlaps.isEmpty()){previous=null;SystemClock.sleep(80);continue;}r=overlaps.get(0);
            }
            if(previous==null||!previous.equals(r)){previous=new Rect(r);stableSince=SystemClock.uptimeMillis();}
            if(SystemClock.uptimeMillis()-stableSince>=280){point=new Rect(r);break;}
            SystemClock.sleep(70);
        }while(SystemClock.uptimeMillis()<end);
        require(point!=null,"CONTROL_NATIVE_AX_GEOMETRY_NOT_STABLE");
        // One final fresh observation; never dispatch a fallback point after a stale rectangle.
        AccessibilityNodeInfo fresh=find(label);require(fresh.refresh()&&fresh.isEnabled()&&fresh.isVisibleToUser(),"CONTROL_CHANGED_BEFORE_FINGER");
        Rect freshBounds=new Rect();fresh.getBoundsInScreen(freshBounds);
        require(point.intersect(freshBounds)&&!point.isEmpty(),"CONTROL_CHANGED_BEFORE_FINGER");
        if(nativeRole!=null){
            List<Rect> freshNative=new ArrayList<>();
            for(JSONObject v:role(witness(),nativeRole))if(v.getBoolean("hasVisibleRect")&&v.getBoolean("enabled")){
                Rect candidate=rect(v);if(candidate.intersect(point)&&!candidate.isEmpty())freshNative.add(candidate);
            }
            require(freshNative.size()==1,"FINAL_NATIVE_AX_INTERSECTION_REQUIRED");point=freshNative.get(0);
        }
        taps.put(new JSONObject().put("role",reportToken).put("rect",point.flattenToString()).put("nativeOverlap",nativeRole!=null));
        long down=SystemClock.uptimeMillis();
        try{
            event(MotionEvent.ACTION_DOWN,point.exactCenterX(),point.exactCenterY(),down);
            if(afterDown!=null)afterDown.run();
        }finally{
            // Always end this exact owned gesture, including preparation/assertion failures.
            event(MotionEvent.ACTION_UP,point.exactCenterX(),point.exactCenterY(),down);
        }
        ax.waitForIdle(100,2000);
    }
    private void configuration(JSONObject w) throws Exception {
        require(w.getInt("orientation")==1&&Math.abs(w.getDouble("fontScale")-1.0)<.005,"ACTUAL_PORTRAIT_FONT1_REQUIRED");
        require(w.getInt("windowWidth")>0&&w.getInt("windowHeight")>0,"ACTUAL_WINDOW_REQUIRED");
    }
    private JSONObject ready(String page,int previews) throws Exception {
        long end=SystemClock.uptimeMillis()+10_000;String previous=null;
        do{
            JSONObject w=witness();
            if(w!=null&&w.getBoolean("shown")&&page.equals(w.getString("page"))){
                configuration(w);
                boolean complete="LETTERS".equals(page)?role(w,"q").size()==1:
                    role(w,"sticker:error").isEmpty()&&role(w,"sticker:status").size()==1&&role(w,"sticker:scroll").size()==1&&role(w,"sticker:preview").size()==previews&&role(w,"sticker:tile").size()==2;
                String geometry=w.getJSONArray("views").toString();
                if(complete&&geometry.equals(previous))return w;previous=complete?geometry:null;
            }
            SystemClock.sleep(100);
        }while(SystemClock.uptimeMillis()<end);
        throw new AssertionError("LOADED_STICKER_ACTION_LAYOUT_NOT_READY");
    }
    private void scroll() throws Exception {
        AccessibilityNodeInfo n=find(SCROLL);require(n.refresh()&&n.isVisibleToUser()&&n.isEnabled(),"CURRENT_BROWSER_SCROLL_REQUIRED");
        Rect point=new Rect();n.getBoundsInScreen(point);List<JSONObject> nativeViews=role(witness(),"sticker:scroll");
        require(nativeViews.size()==1&&nativeViews.get(0).getBoolean("hasVisibleRect")&&point.intersect(rect(nativeViews.get(0))),"NATIVE_AX_SCROLL_INTERSECTION_REQUIRED");
        require(point.width()>20&&point.height()>48,"SCROLL_VIEWPORT_TOO_SMALL");
        float x=point.exactCenterX(),from=point.top+point.height()*.8f,to=point.top+point.height()*.25f;long down=SystemClock.uptimeMillis();
        event(MotionEvent.ACTION_DOWN,x,from,down);
        for(int i=1;i<=8;i++){SystemClock.sleep(20);event(MotionEvent.ACTION_MOVE,x,from+(to-from)*i/8f,down);}
        SystemClock.sleep(200);event(MotionEvent.ACTION_MOVE,x,to,down);event(MotionEvent.ACTION_UP,x,to,down);ax.waitForIdle(100,2000);
    }
    private void reveal(String label) throws Exception {
        for(int i=0;i<20;i++){if(matching(label).size()==1)return;scroll();}
        throw new AssertionError("FIXED_ACTION_NOT_REACHABLE_BY_FINGER_SCROLL");
    }
    private boolean cleanLifecycle(){
        if(admitted.isEmpty())return actors.isEmpty();
        for(Launch record:admitted)if(record.actor.get()==null||record.duplicate)return false;
        for(StickerEditorFixtureActivity actor:actors)if(!actor.isDestroyed())return false;
        return actors.size()==admitted.size();
    }
    private void retireIfResolved(){
        if(cleanupRequested&&registered&&cleanLifecycle()){application.unregisterActivityLifecycleCallbacks(lifecycle);registered=false;}
    }
    private void launch() throws Exception {
        application=(Application)getTargetContext().getApplicationContext();
        lifecycle=new Application.ActivityLifecycleCallbacks(){
            @Override public void onActivityCreated(Activity activity,Bundle saved){
                if(!(activity instanceof StickerEditorFixtureActivity)||!owner.equals(activity.getIntent().getStringExtra("owner")))return;
                for(Launch record:admitted)if(record.generation.equals(activity.getIntent().getStringExtra("generation"))&&record.generation.equals(activity.getIntent().getIdentifier())){
                    StickerEditorFixtureActivity exact=(StickerEditorFixtureActivity)activity;actors.add(exact);
                    if(!record.actor.compareAndSet(null,exact))record.duplicate=true;
                    fixture=exact;record.created.countDown();if(cleanupRequested)exact.finish();break;
                }
            }
            @Override public void onActivityResumed(Activity activity){if(actors.contains(activity))resumed=activity;}
            @Override public void onActivityPaused(Activity activity){if(resumed==activity)resumed=null;}
            @Override public void onActivityStarted(Activity activity){} @Override public void onActivityStopped(Activity activity){}
            @Override public void onActivitySaveInstanceState(Activity activity,Bundle saved){}
            @Override public void onActivityDestroyed(Activity activity){retireIfResolved();}
        };
        ui(()->{application.registerActivityLifecycleCallbacks(lifecycle);registered=true;return null;});
        launchExact("");
        tap("Synthetic sticker editor",null,"synthetic-editor-focus");JSONObject first=ready("LETTERS",0);
        imePid=first.getInt("pid");serviceId=first.getString("serviceId");nativeBound=true;
        baselineDispatches=first.getJSONObject("stickerAttachment").getInt("dispatches");
        report.put("driverPid",android.os.Process.myPid()).put("imePid",imePid).put("initialDispatches",baselineDispatches);
        untouched(snapshot());awaitIdle(8000);
    }
    private StickerEditorFixtureActivity launchExact(String suffix) throws Exception {
        final Launch record=new Launch(owner+"-sticker-ime-"+mode+suffix);
        ui(()->{
            admitted.add(record); // Creation may be late even if startActivity throws.
            getTargetContext().startActivity(new Intent(getTargetContext(),StickerEditorFixtureActivity.class)
                .putExtra("owner",owner).putExtra("generation",record.generation).putExtra("mode","pending-cancel".equals(mode)?"accept":mode).putExtra("phase",phase)
                .setIdentifier(record.generation).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK));return null;
        });
        require(record.created.await(8,TimeUnit.SECONDS),"OWNED_EDITOR_CREATION_DEADLINE");
        long end=SystemClock.uptimeMillis()+8000;boolean focused=false;
        do{
            focused=ui(()->{
                StickerEditorFixtureActivity actor=record.actor.get();if(actor==null||resumed!=actor||!actor.hasWindowFocus()||actor.isFinishing())return false;
                android.view.View focus=actor.getWindow().getCurrentFocus();return focus instanceof android.widget.EditText&&focus.hasFocus()&&focus.hasWindowFocus()&&"Synthetic sticker editor".contentEquals(focus.getContentDescription());
            });if(focused)break;SystemClock.sleep(80);
        }while(SystemClock.uptimeMillis()<end);
        require(focused,"OWNED_EDITOR_RESUME_WINDOW_FOCUS_DEADLINE");find("Lip sticker editor "+record.generation);
        return record.actor.get();
    }
    /** Whitelist only fixture census fields; never serialize generic AX trees or content URIs. */
    private JSONObject snapshot() throws Exception {return snapshot(fixture);}
    private JSONObject snapshot(StickerEditorFixtureActivity actor) throws Exception {
        JSONObject raw=actor.snapshot(),out=new JSONObject();
        for(String key:Arrays.asList("invocations","readStarted","readCompleted","bytes","flags","editorActions","textChanges","pendingCount","inputConnections","compositionStarts","compositionEnds","imeCompositionFinishes","composingStart","composingEnd","probeReads","probeUnexpectedReads","probeTriggerOrdinal","probeSelectionDelta","unknownOffsetResponses"))out.put(key,raw.getInt(key));
        for(String key:Arrays.asList("grantObserved","releaseCompleted","workerAlive","hasReceivedUri","probeArmed","probeTriggered","unknownOffset"))out.put(key,raw.getBoolean(key));
        for(String key:Arrays.asList("grantBeforeRequest","grantAbsentAfterRelease","grantAbsentNow"))out.put(key,raw.isNull(key)?JSONObject.NULL:raw.getBoolean(key));
        String sha=raw.getString("sha256"),mime=raw.getString("mime"),error=raw.getString("errorClass"),grantError=raw.getString("grantObservationError");
        require(sha.isEmpty()||sha.matches("[a-f0-9]{64}"),"FIXTURE_HASH_GRAMMAR");
        require(mime.isEmpty()||Arrays.asList("image/png","image/webp").contains(mime),"FIXTURE_MIME_GRAMMAR");
        require(error.isEmpty()||error.matches("[A-Za-z0-9]{1,64}"),"FIXTURE_ERROR_GRAMMAR");
        require(grantError.isEmpty()||grantError.matches("[A-Za-z0-9]{1,64}"),"FIXTURE_GRANT_ERROR_GRAMMAR");
        String focused=raw.getString("focusedEditor");require(Arrays.asList("PRIMARY","REPLACEMENT","OTHER").contains(focused),"FIXTURE_FOCUS_TOKEN");
        return out.put("sha256",sha).put("mime",mime).put("errorClass",error).put("grantObservationError",grantError).put("focusedEditor",focused);
    }
    private void untouched(JSONObject census) throws Exception {
        require(census.getInt("editorActions")==0&&census.getInt("textChanges")==0&&ui(()->fixture.syntheticTextUnchanged()),"EDITOR_TEXT_OR_SEND_CHANGED");
    }
    private boolean idle(JSONObject w) throws Exception {
        if(w==null)return false;configuration(w);
        JSONObject attachment=w.getJSONObject("stickerAttachment"),transport=w.getJSONObject("stickerTransport");
        return !attachment.getBoolean("pending")&&attachment.getInt("workerTasks")==0&&!transport.getBoolean("active")&&!transport.getBoolean("writerAlive")&&transport.getInt("queuedTasks")==0&&transport.getBoolean("revocationsConfirmed");
    }
    private JSONObject awaitIdle(long maximumMs) throws Exception {
        long end=SystemClock.uptimeMillis()+maximumMs;String previous=null;
        do{
            JSONObject w=witness();JSONObject census=snapshot();untouched(census);
            if(idle(w)&&allReceiversDrained()&&census.getInt("pendingCount")==0&&!census.getBoolean("workerAlive")){
                String state=w.getJSONObject("stickerAttachment").toString()+w.getJSONObject("stickerTransport").toString()+census.toString();
                if(state.equals(previous))return w;previous=state;
            }else previous=null;
            SystemClock.sleep(100);
        }while(SystemClock.uptimeMillis()<end);
        throw new AssertionError("ACTUAL_ATTACHMENT_TRANSPORT_RECEIVER_DRAIN_UNCONFIRMED");
    }
    private boolean allReceiversDrained() throws Exception {
        for(StickerEditorFixtureActivity actor:ui(()->new ArrayList<>(actors))){
            JSONObject state=snapshot(actor);
            require(state.getInt("editorActions")==0&&state.getInt("textChanges")==0&&ui(actor::syntheticTextUnchanged),"OWNED_ACTOR_TEXT_OR_SEND_CHANGED");
            if(state.getInt("pendingCount")!=0||state.getBoolean("workerAlive"))return false;
        }
        return true;
    }
    private void openSyntheticPack() throws Exception {
        tap("Lip keyboard menu","Lip keyboard menu","menu");tap("Local sticker packs",null,"open-sticker-library");ready("STICKERS",0);
        reveal(SYNTHETIC);tap(SYNTHETIC,"sticker:tile","open-synthetic-pack");ready("STICKERS",2);
        require(matching("Static previews 1–2 of 2").size()==1,"EXACT_SYNTHETIC_PACK_REQUIRED");
    }
    private JSONObject awaitRead(int expectedInvocations,int expectedReads,String mime,int bytes,String sha) throws Exception {
        long end=SystemClock.uptimeMillis()+10_000;boolean asyncPending=false;
        do{
            JSONObject actual=snapshot();untouched(actual);
            asyncPending|=actual.getInt("pendingCount")>0||actual.getBoolean("workerAlive");
            require(actual.getInt("invocations")<=expectedInvocations,"DUPLICATE_COMMIT_CONTENT_INVOCATION");
            if(actual.getInt("invocations")==expectedInvocations&&actual.getInt("readStarted")==expectedReads&&actual.getInt("readCompleted")==expectedReads&&actual.getInt("pendingCount")==0&&!actual.getBoolean("workerAlive")){
                require(actual.getInt("bytes")==bytes&&sha.equals(actual.getString("sha256"))&&mime.equals(actual.getString("mime")),"ACTUAL_ORIGINAL_BYTES_OR_MIME_MISMATCH");
                require(actual.getInt("flags")==1&&actual.getBoolean("grantObserved")&&actual.getBoolean("releaseCompleted")&&actual.getString("errorClass").isEmpty(),"ACTUAL_TEMPORARY_GRANT_READ_RELEASE_FAILED");
                actual.put("observedAsyncPending",asyncPending);return actual;
            }
            SystemClock.sleep(40);
        }while(SystemClock.uptimeMillis()<end);
        throw new AssertionError("ACTUAL_RECEIVER_READ_COMPLETION_DEADLINE");
    }
    private void request(String title,String mime,int bytes,String sha,int expected) throws Exception {
        String description="Request original sticker "+title;reveal(description);
        JSONObject before=witness();for(JSONObject tile:role(before,"sticker:tile"))require(tile.getBoolean("clickable")&&tile.getBoolean("enabled"),"EXPECTED_ATTACHMENT_TILE_NOT_ELIGIBLE");
        tap(description,"sticker:tile","request-"+(mime.equals("image/png")?"png":"webp"));
        if("refuse".equals(mode)){
            long end=SystemClock.uptimeMillis()+8000;JSONObject actual;
            do{actual=snapshot();untouched(actual);require(actual.getInt("invocations")<=1,"REFUSAL_DUPLICATED");if(actual.getInt("invocations")==1)break;SystemClock.sleep(70);}while(SystemClock.uptimeMillis()<end);
            require(actual.getInt("invocations")==1&&actual.getInt("readStarted")==0&&actual.getInt("readCompleted")==0&&!actual.getBoolean("grantObserved")&&!actual.getBoolean("releaseCompleted")&&actual.getInt("flags")==1&&mime.equals(actual.getString("mime"))&&actual.getInt("bytes")==0&&actual.getString("sha256").isEmpty(),"REFUSAL_MUST_NOT_READ_OR_REQUEST_GRANT");
            JSONObject idle=awaitIdle(10_000);require(idle.getJSONObject("stickerAttachment").getInt("dispatches")==baselineDispatches+1,"REFUSAL_DISPATCH_COUNT");
            JSONObject settled=snapshot();require(settled.getInt("invocations")==1&&settled.getInt("readStarted")==0,"REFUSAL_RETRIED_AFTER_DRAIN");
            require(settled.getBoolean("hasReceivedUri")&&settled.getBoolean("grantAbsentNow")&&settled.getString("grantObservationError").isEmpty(),"REFUSAL_FINAL_ANDROID_GRANT_ABSENCE_UNCONFIRMED");
            ingress.put(settled.put("case","refused-before-read"));
        }else{
            JSONObject actual=awaitRead(expected,expected,mime,bytes,sha);JSONObject settled=awaitIdle(10_000);
            require(settled.getJSONObject("stickerAttachment").getInt("dispatches")==baselineDispatches+expected,"ONE_DISPATCH_PER_GESTURE_REQUIRED");
            JSONObject finalCensus=snapshot();require(finalCensus.getInt("invocations")==expected,"READ_OR_REFUSAL_RETRIED_AFTER_DRAIN");
            require(finalCensus.getBoolean("hasReceivedUri")&&finalCensus.getBoolean("grantObserved")&&finalCensus.getBoolean("releaseCompleted")&&finalCensus.getBoolean("grantAbsentNow")&&
                finalCensus.getString("grantObservationError").isEmpty()&&finalCensus.getString("errorClass").isEmpty(),"FINAL_ANDROID_GRANT_READ_RELEASE_ABSENCE_UNCONFIRMED");
            ingress.put(finalCensus.put("observedAsyncPending",actual.getBoolean("observedAsyncPending")).put("case","refuse-after-read".equals(mode)?"read-but-api-refused":mime.equals("image/png")?"png-ingress":"webp-ingress"));
        }
    }
    private void unsupported() throws Exception {
        JSONObject w=ready("STICKERS",2);for(JSONObject tile:role(w,"sticker:tile"))require(!tile.getBoolean("clickable"),"UNSUPPORTED_TILE_MUST_REMAIN_BROWSE_ONLY");
        for(JSONObject image:role(w,"sticker:preview"))require(!image.getBoolean("clickable"),"UNSUPPORTED_PREVIEW_MUST_NOT_DISPATCH");
        reveal("Static sticker preview Red");tap("Static sticker preview Red","sticker:preview","browse-only-png-touch");
        JSONObject settled=awaitIdle(8000),actual=snapshot();untouched(actual);
        require(actual.getInt("invocations")==0&&actual.getInt("readStarted")==0&&actual.getInt("readCompleted")==0,"UNSUPPORTED_EDITOR_RECEIVED_CONTENT");
        require(settled.getJSONObject("stickerAttachment").getInt("dispatches")==baselineDispatches,"UNSUPPORTED_EDITOR_DISPATCHED");
        ingress.put(actual.put("case","unsupported-browse-only").put("dispatchDelta",0));
    }
    private interface CensusCheck {boolean test(JSONObject value) throws Exception;}
    private JSONObject awaitCensus(CensusCheck condition,String failure) throws Exception {
        long end=SystemClock.uptimeMillis()+8000;
        do{JSONObject state=snapshot();untouched(state);if(condition.test(state))return state;SystemClock.sleep(70);}while(SystemClock.uptimeMillis()<end);
        throw new AssertionError(failure);
    }
    private JSONObject zeroDispatch(String caseToken) throws Exception {
        JSONObject settled=awaitIdle(10_000),state=snapshot();untouched(state);
        require(state.getInt("invocations")==0&&state.getInt("readStarted")==0&&state.getInt("readCompleted")==0&&!state.getBoolean("hasReceivedUri"),"NEGATIVE_CASE_INGRESS_OCCURRED");
        require(settled.getJSONObject("stickerAttachment").getInt("dispatches")==baselineDispatches,"NEGATIVE_CASE_DISPATCH_OCCURRED");
        require(state.isNull("grantAbsentNow"),"UNRECEIVED_URI_GRANT_MUST_REMAIN_UNOBSERVED");
        ingress.put(state.put("case",caseToken).put("dispatchDelta",0));return state;
    }
    private void awaitTileEnabled(boolean enabled) throws Exception {
        long end=SystemClock.uptimeMillis()+8000;
        do{JSONObject w=witness();List<JSONObject> tiles=role(w,"sticker:tile");boolean matches=tiles.size()==2;
            for(JSONObject tile:tiles)matches&=tile.getBoolean("enabled")==enabled;
            if(matches)return;SystemClock.sleep(70);
        }while(SystemClock.uptimeMillis()<end);
        throw new AssertionError("EXPECTED_TILE_ENABLED_STATE_NOT_OBSERVED");
    }
    private void awaitBrowseOnlyTiles() throws Exception {
        long end=SystemClock.uptimeMillis()+8000;
        do{JSONObject w=witness();List<JSONObject> tiles=role(w,"sticker:tile");boolean browse=tiles.size()==2;
            for(JSONObject tile:tiles)browse&=!tile.getBoolean("clickable");
            if(browse)return;SystemClock.sleep(70);
        }while(SystemClock.uptimeMillis()<end);
        throw new AssertionError("COMPOSING_BROWSER_NOT_BROWSE_ONLY");
    }
    private void composition() throws Exception {
        int finishes=snapshot().getInt("imeCompositionFinishes");
        tap("Begin synthetic composition",null,"host-begin-composition");
        awaitCensus(s->s.getInt("compositionStarts")==1&&s.getInt("composingStart")==0&&s.getInt("composingEnd")==4,"ACTUAL_COMPOSING_SPAN_NOT_ESTABLISHED");
        awaitBrowseOnlyTiles();reveal("Static sticker preview Red");tap("Static sticker preview Red","sticker:preview","composing-browse-only-touch");
        JSONObject rejected=zeroDispatch("active-host-composition-refused");
        require(rejected.getInt("composingStart")==0&&rejected.getInt("composingEnd")==4&&rejected.getInt("imeCompositionFinishes")==finishes,"ORIGINAL_FLOW_MUTATED_HOST_COMPOSITION");
        tap("End synthetic composition",null,"host-end-composition");
        awaitCensus(s->s.getInt("compositionEnds")==1&&s.getInt("composingStart")==-1&&s.getInt("composingEnd")==-1,"ACTUAL_COMPOSING_SPAN_NOT_CLEARED");
        awaitTileEnabled(true);request("Red","image/png",78,RED_SHA,1);
        report.put("actualCompositionRefusalAndFreshPositive",true).put("compositionMethod","physical fixture controls call real base InputConnection and report actual composing spans");
    }
    private void staleCaret() throws Exception {
        reveal("Request original sticker Red");
        tap("Request original sticker Red","sticker:tile","stale-caret-gesture",()->{
            require(ui(fixture::armSyntheticRevalidationProbe),"FIXTURE_PROBE_ARM_REFUSED");
            JSONObject armed=snapshot();
            report.put("probePreparation",new JSONObject().put("betweenPhysicalDownAndUp",true)
                .put("armed",armed.getBoolean("probeArmed")).put("reads",armed.getInt("probeReads"))
                .put("unexpectedReads",armed.getInt("probeUnexpectedReads")));
            require(armed.getBoolean("probeArmed")&&armed.getInt("probeReads")==0,"UNINTENDED_PRE_UP_SURROUNDING_READ");
        });
        JSONObject triggered=awaitCensus(s->s.getBoolean("probeTriggered"),"CONTROLLED_SECOND_READ_NOT_TRIGGERED");
        require(triggered.getInt("probeReads")==2&&triggered.getInt("probeUnexpectedReads")==0&&triggered.getInt("probeTriggerOrdinal")==2&&triggered.getInt("probeSelectionDelta")==1,"CONTROLLED_REVALIDATION_PRECONDITION_FAILED");
        zeroDispatch("controlled-real-caret-change-refused");
        tap("Disarm synthetic revalidation change",null,"disarm-controlled-revalidation");
        awaitTileEnabled(true);request("Red","image/png",78,RED_SHA,1);
        report.put("controlledStaleRevalidationAndFreshPositive",true).put("staleProbeMethod","fixture-only probe armed between physical image DOWN/UP; changes real selection on second bounded surrounding read; delegates actual base result; not natural OS callback-delay proof");
    }
    private void unknownOffset() throws Exception {
        tap("Use unknown surrounding offset",null,"enable-unknown-offset-probe");
        reveal("Request original sticker Red");tap("Request original sticker Red","sticker:tile","unknown-offset-gesture");
        awaitCensus(s->s.getBoolean("unknownOffset")&&s.getInt("unknownOffsetResponses")>0,"CONTROLLED_UNKNOWN_OFFSET_NOT_QUERIED");
        zeroDispatch("controlled-unknown-offset-refused");
        tap("Use actual surrounding offset",null,"restore-actual-surrounding-offset");
        awaitTileEnabled(true);request("Red","image/png",78,RED_SHA,1);
        report.put("controlledUnknownOffsetAndFreshPositive",true).put("unknownOffsetMethod","fixture returns unknown offset around actual base surrounding text; synthetic SDK contract probe, not naturally occurring OS behavior");
    }
    private void replacement(boolean window) throws Exception {
        StickerEditorFixtureActivity previous=fixture;int connections=snapshot().getInt("inputConnections");
        zeroDispatch("old-browser-before-replacement");
        if(window){
            StickerEditorFixtureActivity next=launchExact("-replacement");require(next!=previous,"EXACT_NEW_EDITOR_INSTANCE_REQUIRED");
            tap("Synthetic sticker editor",null,"new-window-editor-focus");
            require(ui(()->!previous.hasWindowFocus()&&fixture==next&&resumed==next&&next.hasWindowFocus()),"ACTUAL_WINDOW_FOCUS_REPLACEMENT_REQUIRED");
        }else{
            tap("Synthetic replacement editor",null,"new-field-editor-focus");
            awaitCensus(s->"REPLACEMENT".equals(s.getString("focusedEditor"))&&s.getInt("inputConnections")>connections,"ACTUAL_INPUT_CONNECTION_REPLACEMENT_NOT_OBSERVED");
        }
        JSONObject current=ready("LETTERS",0);require(role(current,"sticker:preview").isEmpty(),"OBSOLETE_PACK_SURVIVED_REPLACEMENT");
        zeroDispatch(window?"new-window-no-stale-ingress":"new-field-no-stale-ingress");
        openSyntheticPack();request("Red","image/png",78,RED_SHA,1);
        if(window){JSONObject old=snapshot(previous);require(old.getInt("invocations")==0&&old.getInt("readStarted")==0&&ui(previous::syntheticTextUnchanged),"OLD_EDITOR_RECEIVED_NEW_WINDOW_CONTENT");}
        report.put(window?"actualWindowReplacementAndFreshPositive":"actualEditorReplacementAndFreshPositive",true)
            .put("replacementScope","physical focus/window change and fresh ingress only; no stale-node click or pre-claim pending-read race claimed");
    }
    /** UUID-only app-private coordination; no general file reader or mutation target. */
    private String barrierFile(String kind){
        require(Arrays.asList("ready","release","retire","retired").contains(kind),"BARRIER_FILE_KIND");
        return "files/sticker-read-barrier-"+owner+"-"+kind+".json";
    }
    private String boundedShell(String command) throws Exception {
        budget();byte[] bytes;
        try(java.io.InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(ax.executeShellCommand(command))){bytes=in.readNBytes(1025);}
        require(bytes.length<=1024,"BARRIER_PROTOCOL_BOUND");return new String(bytes,StandardCharsets.UTF_8);
    }
    private void awaitBarrierMarker(String kind,String flag) throws Exception {
        String file=barrierFile(kind);long end=SystemClock.uptimeMillis()+10_000;
        do{
            String raw=boundedShell("run-as "+APP+" cat "+file);
            if(!raw.isEmpty()){
                JSONObject marker=null;try{marker=new JSONObject(raw);}catch(org.json.JSONException incomplete){}
                if(marker!=null){
                    require(owner.equals(marker.getString("owner"))&&marker.getInt("runnerVersion")==1,"BARRIER_PROTOCOL_OWNER_OR_VERSION");
                    require(marker.getBoolean(flag),"BARRIER_PROTOCOL_MARKER_FALSE");return;
                }
            }
            SystemClock.sleep(80);
        }while(SystemClock.uptimeMillis()<end);
        throw new AssertionError("BARRIER_PROTOCOL_MARKER_DEADLINE");
    }
    private void signalBarrier(String kind) throws Exception {
        require("release".equals(kind)||"retire".equals(kind),"BARRIER_SIGNAL_KIND");
        // One attempt only: noclobber prevents overwrite; a missing acknowledgement is uncertainty, not a retry.
        // UiAutomation's command uses Runtime.exec, not shell quote parsing. Supply the
        // fixed, UUID-only script through the actual shell stdin instead of quoting -c.
        budget();android.os.ParcelFileDescriptor[] pipes=ax.executeShellCommandRw("run-as "+APP+" sh");
        require(pipes.length==2,"BARRIER_SIGNAL_PIPE_PROTOCOL");String response;
        try(java.io.InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(pipes[0]);
            java.io.OutputStream out=new android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipes[1])){
            // Build the entire UUID payload in an exclusive same-directory temp file.
            // No-clobber rename publishes the complete payload. mv -n can return success
            // when it skips an existing destination, so independently require temp gone.
            // An uncertain operation is never retried; either temp or final preserves bytes.
            String destination=barrierFile(kind),temporary=destination+".tmp";
            String script="set -C\nif { printf %s '"+owner+"' > '"+temporary+"' && mv -nT '"+temporary+"' '"+destination+"' && [ ! -e '"+temporary+"' ]; } 2>/dev/null; then printf SIGNAL_CREATED; else printf SIGNAL_UNCONFIRMED; fi\n";
            out.write(script.getBytes(StandardCharsets.UTF_8));out.close();
            byte[] bytes=in.readNBytes(1025);require(bytes.length<=1024,"BARRIER_PROTOCOL_BOUND");response=new String(bytes,StandardCharsets.UTF_8);
        }
        require("SIGNAL_CREATED".equals(response),"BARRIER_SIGNAL_CREATION_UNCONFIRMED");
    }
    private JSONObject barrierCensus(JSONObject w) throws Exception {
        require(w!=null&&!w.isNull("stickerReadBarrier"),"BARRIER_WITNESS_REQUIRED");
        JSONObject raw=w.getJSONObject("stickerReadBarrier"),out=new JSONObject();
        for(String key:Arrays.asList("active","claimed","entered","workerThreadAlive","canceledPredicateObserved","cancelRequested","released","callbackExited","loaderAdmissionReleased","timedOut","interrupted"))out.put(key,raw.getBoolean(key));
        return out.put("storeLiveInvocations",raw.getInt("storeLiveInvocations"));
    }
    private void noPendingIngress(JSONObject w) throws Exception {
        JSONObject state=snapshot();untouched(state);
        require(state.getInt("invocations")==0&&state.getInt("readStarted")==0&&state.getInt("readCompleted")==0&&!state.getBoolean("hasReceivedUri")&&state.isNull("grantAbsentNow"),"HELD_READ_MUST_NOT_DISPATCH_OR_OBSERVE_URI");
        require(w.getJSONObject("stickerAttachment").getInt("dispatches")==baselineDispatches,"HELD_READ_DISPATCH_OCCURRED");
        JSONObject transport=w.getJSONObject("stickerTransport");
        require(!transport.getBoolean("active")&&!transport.getBoolean("writerAlive")&&transport.getInt("queuedTasks")==0&&transport.getBoolean("revocationsConfirmed"),"HELD_READ_CREATED_TRANSPORT");
    }
    private JSONObject awaitHeldRead(boolean canceled) throws Exception {
        long end=SystemClock.uptimeMillis()+10_000;
        do{
            JSONObject w=witness();if(w!=null){
                JSONObject barrier=barrierCensus(w);noPendingIngress(w);
                require(!barrier.getBoolean("timedOut")&&!barrier.getBoolean("interrupted"),"BARRIER_FAILSAFE_OR_INTERRUPTION_NOT_A_TEST_PASS");
                JSONObject attachment=w.getJSONObject("stickerAttachment");
                if(barrier.getBoolean("active")&&barrier.getBoolean("claimed")&&barrier.getBoolean("entered")&&barrier.getBoolean("workerThreadAlive")&&
                    !barrier.getBoolean("released")&&!barrier.getBoolean("callbackExited")&&!barrier.getBoolean("loaderAdmissionReleased")&&
                    !barrier.getBoolean("cancelRequested")&&barrier.getBoolean("canceledPredicateObserved")==canceled&&
                    attachment.getBoolean("pending")&&attachment.getInt("workerTasks")==1)return barrier;
            }
            SystemClock.sleep(70);
        }while(SystemClock.uptimeMillis()<end);
        throw new AssertionError(canceled?"HELD_REAL_READ_CANCELLATION_NOT_OBSERVED":"REAL_STORE_CALLBACK_NOT_HELD");
    }
    private void pendingCancel() throws Exception {
        JSONObject unclaimed=barrierCensus(witness());
        require(unclaimed.getBoolean("active")&&!unclaimed.getBoolean("claimed")&&!unclaimed.getBoolean("entered")&&!unclaimed.getBoolean("released"),"FRESH_ARM_BEFORE_GESTURE_REQUIRED");
        reveal("Request original sticker Red");tap("Request original sticker Red","sticker:tile","pending-original-png-touch");
        JSONObject held=awaitHeldRead(false);
        tap("Return from stickers to letters","Return from stickers to letters","leave-held-original-page");ready("LETTERS",0);
        JSONObject canceled=awaitHeldRead(true);
        report.put("pendingReadHeld",held).put("pendingReadCanceledWhileHeld",canceled);
        signalBarrier("release");
        JSONObject settled=awaitIdle(10_000),released=barrierCensus(settled);noPendingIngress(settled);
        require(released.getBoolean("released")&&released.getBoolean("callbackExited")&&released.getBoolean("loaderAdmissionReleased")&&
            released.getBoolean("canceledPredicateObserved")&&!released.getBoolean("cancelRequested")&&!released.getBoolean("timedOut")&&!released.getBoolean("interrupted"),"ACTUAL_LOADER_DRAIN_OR_PHYSICAL_CANCEL_PROOF_MISSING");
        report.put("pendingReadAfterLoaderDrain",released);zeroDispatch("real-store-read-page-cancel-no-late-ingress");
        signalBarrier("retire");awaitBarrierMarker("retired","retired");
        long end=SystemClock.uptimeMillis()+10_000;boolean retired=false;
        do{JSONObject w=witness();if(w!=null&&w.has("stickerReadBarrier")&&w.isNull("stickerReadBarrier")){retired=true;break;}SystemClock.sleep(80);}while(SystemClock.uptimeMillis()<end);
        require(retired,"BARRIER_RETIREMENT_NOT_OBSERVED");
        openSyntheticPack();request("Red","image/png",78,RED_SHA,1);
        report.put("pendingStoreReadCancellationExercised",true).put("pendingCancellationScope","controlled barrier inside real Store.read live callback; physical page leave; actual loader/controller drain; fresh physical positive; no natural disk-stall or editor/window pending-race claim");
        report.remove("pendingStoreReadCancellationGap");
    }
    private boolean cleanup(){
        deadline=SystemClock.uptimeMillis()+140_000;boolean clean=true,drained=!nativeBound;
        try{ui(()->{cleanupRequested=true;return null;});}catch(Throwable t){clean=false;}
        // closePending joins a real receiver worker and MUST NOT run on the main thread.
        try{
            require(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper(),"CLEANUP_JOIN_MUST_BE_OFF_MAIN");
            List<StickerEditorFixtureActivity> exact=ui(()->new ArrayList<>(actors));
            for(StickerEditorFixtureActivity actor:exact)actor.closePending();
            if(nativeBound&&fixture!=null){awaitIdle(125_000);drained=true;}
        }catch(Throwable t){clean=false;try{report.put("drainCleanupFailure",t.getClass().getSimpleName());}catch(Exception ignored){}}
        try{
            report.put("actualDrainConfirmed",nativeBound&&drained).put("noNativeActionRouteAdmitted",!nativeBound);
            // Never finish a receiver while its actual transport/reader drain remains unconfirmed.
            if(drained)ui(()->{for(StickerEditorFixtureActivity actor:actors)if(!actor.isFinishing())actor.finish();return null;});
            long end=SystemClock.uptimeMillis()+8000;boolean destroyed=false;
            do{destroyed=ui(this::cleanLifecycle);if(destroyed)break;SystemClock.sleep(80);}while(SystemClock.uptimeMillis()<end);
            clean&=destroyed&&drained;ui(()->{retireIfResolved();return null;});
            final int[] census=ui(()->new int[]{admitted.size(),actors.size(),(int)actors.stream().filter(Activity::isDestroyed).count(),registered&&cleanupRequested?1:0});
            report.put("cleanupResolution",new JSONObject().put("admitted",census[0]).put("created",census[1]).put("destroyed",census[2])
                .put("lateCreationFenceRetained",census[3]==1).put("state",clean?"CONFIRMED":"UNCONFIRMED"));
        }catch(Throwable t){clean=false;}
        return clean;
    }
    @Override public void onStart(){
        int outcome=Activity.RESULT_CANCELED;
        try{
            require("ranchu".equals(android.os.Build.HARDWARE)&&TEST.equals(getContext().getPackageName())&&TEST.equals(getTargetContext().getPackageName()),"SELF_TARGET_OWNED_EMULATOR_ONLY");
            report.put("driverVersion",1).put("owner",owner).put("mode",mode).put("phase",phase).put("passed",false)
                .put("scope","actual separate-process IME gesture and original-byte editor ingress; no external app/Send/phone/TalkBack/visual acceptance")
                .put("screenshotsSkipped",true);
            ax=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);android.accessibilityservice.AccessibilityServiceInfo cfg=ax.getServiceInfo();cfg.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;ax.setServiceInfo(cfg);
            if("pending-cancel".equals(mode)){stage="barrier-ready";awaitBarrierMarker("ready","ready");}
            stage="startup";launch();stage="open-synthetic-pack";openSyntheticPack();
            report.put("pendingStoreReadCancellationExercised",false).put("pendingStoreReadCancellationGap","NO_DETERMINISTIC_STORE_READ_BARRIER_WITHOUT_PRODUCTION_SEAM");
            if("unsupported".equals(mode)){stage="unsupported";unsupported();}
            else if("composition".equals(mode)){stage="composition";composition();}
            else if("stale-caret".equals(mode)){stage="stale-caret";staleCaret();}
            else if("unknown-offset".equals(mode)){stage="unknown-offset";unknownOffset();}
            else if("editor-replacement".equals(mode)){stage="editor-replacement";replacement(false);}
            else if("window-replacement".equals(mode)){stage="window-replacement";replacement(true);}
            else if("pending-cancel".equals(mode)){stage="pending-cancel";pendingCancel();}
            else{
                int expected=0;
                if(!"webp".equals(phase)){stage="original-png";request("Red","image/png",78,RED_SHA,++expected);}
                if(Arrays.asList("accept","async").contains(mode)&&!"png".equals(phase)){stage="original-webp";request("Animated","image/webp",140,ANIMATED_SHA,++expected);}
            }
            report.put("passed",true);outcome=Activity.RESULT_OK;
        }catch(Throwable t){try{report.put("passed",false).put("failureStage",stage).put("failureType",t.getClass().getSimpleName());if(t instanceof AssertionError)report.put("failureToken",t.getMessage());}catch(Exception ignored){}}
        finally{boolean closed=cleanup();try{report.put("cleanupConfirmed",closed);if(!closed){report.put("passed",false);outcome=Activity.RESULT_CANCELED;}}catch(Exception ignored){outcome=Activity.RESULT_CANCELED;}}
        try{
            report.put("taps",taps).put("ingress",ingress);
            File result=new File(getTargetContext().getFilesDir(),"sticker-ime-action-"+owner+"-result.json");require(result.createNewFile(),"RESULT_MUST_NOT_OVERWRITE");
            try(FileOutputStream out=new FileOutputStream(result)){out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        }catch(Throwable t){outcome=Activity.RESULT_CANCELED;try{report.put("passed",false).put("resultWriteFailed",true);}catch(Exception ignored){}}
        Bundle result=new Bundle();result.putString("driverResult",report.toString());finish(outcome,result);
    }
}
