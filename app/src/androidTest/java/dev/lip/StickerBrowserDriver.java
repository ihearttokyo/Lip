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
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import dev.lip.uiprotocol.NativeWindowIntersection;
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

/** Self-targeted TEST-package driver. No production classes, preference writes or decoded-content diagnostics. */
public final class StickerBrowserDriver extends Instrumentation {
    private static final String APP="dev.lip.android.sizingfixture", TEST=APP+".test";
    private static final String SYNTHETIC="Inspect saved sticker pack Synthetic test";
    private static final String SELECTED="Inspect saved sticker pack WeChat selected local source";
    private static final String SCROLL="Local sticker browser results";
    private static final String BACK="Return from stickers to letters", PACKS="Show local sticker packs";
    private static final String NEXT="Next sticker browser page", PREVIOUS="Previous sticker browser page";
    private UiAutomation ax;
    private String owner,variant,mode,stage="guard";
    private long deadline;
    private int imePid;
    private String serviceId;
    private JSONObject frozen;
    private KeyboardSizingEditorActivity fixture;
    private Activity resumed;
    private int admittedEditorLaunches;
    private boolean cleanupRequested;
    private boolean lifecycleRegistered;
    private final ArrayList<KeyboardSizingEditorActivity> actors=new ArrayList<>();
    private Application application;
    private Application.ActivityLifecycleCallbacks lifecycle;
    private final JSONObject report=new JSONObject();
    private final JSONArray taps=new JSONArray(),scales=new JSONArray(),projectionProofs=new JSONArray();
    private final java.util.HashSet<String> recordedProjectionKeys=new java.util.HashSet<>();
    private JSONObject lastProjectionRejection;

    private static void require(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    private void budget(){require(SystemClock.uptimeMillis()<deadline,"DRIVER_BUDGET_EXHAUSTED");}
    @Override public void onCreate(Bundle args){
        super.onCreate(args);
        try{
            require(args!=null,"ARGUMENTS_REQUIRED");owner=args.getString("owner");
            require(owner!=null&&UUID.fromString(owner).toString().equals(owner),"OWNER_REQUIRED");
            variant=args.getString("variant");require(Arrays.asList("portrait","compact-landscape-font2").contains(variant),"VARIANT_REQUIRED");
            mode=args.getString("mode","browser");require(Arrays.asList("browser","replacement").contains(mode),"MODE_REQUIRED");
        }catch(Throwable t){Bundle b=new Bundle();b.putString("driverResult","{\"passed\":false,\"driverVersion\":1,\"cleanupConfirmed\":true,\"failure\":\"ARGUMENT_GUARD\"}");finish(Activity.RESULT_CANCELED,b);return;}
        deadline=SystemClock.uptimeMillis()+210_000;start();
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
    private void variant(JSONObject w) throws Exception {
        require(w.getInt("orientation")==("portrait".equals(variant)?1:2),"CURRENT_ORIENTATION_PRECONDITION");
        require(Math.abs(w.getDouble("fontScale")-("portrait".equals(variant)?1.0:2.0))<.005,"CURRENT_FONT_SCALE_PRECONDITION");
        require(w.getInt("windowWidth")>0&&w.getInt("windowHeight")>0,"CURRENT_WINDOW_PRECONDITION");
    }
    private void unchanged(JSONObject w) throws Exception {
        variant(w);
        JSONObject current=w.getJSONObject("renderedSettings");
        for(String key:Arrays.asList("version","hand","height","theme","haptics","keyLabels","emojiPreview"))
            require(frozen.get(key).equals(current.get(key)),"FROZEN_KEYBOARD_PREFERENCE_CHANGED");
        require(current.length()==7&&frozen.length()==7,"FROZEN_PREFERENCE_SCHEMA_CHANGED");
    }
    private JSONObject ready(String kind,String expectedStatus,int previews,String scale) throws Exception {
        long end=SystemClock.uptimeMillis()+10_000;String previous=null;
        do{
            JSONObject w=witness();
            if(w!=null&&w.getBoolean("shown")&&kind.equals(w.getString("page"))){
                variant(w);if(frozen!=null)unchanged(w);
                boolean settled=scale==null||scale.equals(w.getString("stickerScale"));
                if("STICKERS".equals(kind)){
                    settled&=role(w,"sticker:error").isEmpty()&&role(w,"sticker:status").size()==1&&role(w,"sticker:scroll").size()==1;
                    settled&=role(w,"sticker:preview").size()==previews;
                    settled&=role(w,"sticker:tile").size()==(previews==0?2:previews);
                    if(expectedStatus!=null)settled&=matching(expectedStatus).size()==1;
                    require(!w.getBoolean("outerToolsVisible"),"DUPLICATE_STICKER_TOOLBAR");
                }else settled&=role(w,"q").size()==1&&w.getBoolean("outerToolsVisible");
                String identity=w.getJSONArray("views").toString();
                if(settled&&identity.equals(previous))return w;previous=settled?identity:null;
            }
            SystemClock.sleep(100);
        }while(SystemClock.uptimeMillis()<end);
        throw new AssertionError("LOADED_NATIVE_LAYOUT_NOT_STABLE");
    }
    private void event(int action,float x,float y,long down){
        MotionEvent.PointerProperties pointer=new MotionEvent.PointerProperties();pointer.id=0;pointer.toolType=MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords position=new MotionEvent.PointerCoords();position.x=x;position.y=y;position.pressure=1f;position.size=1f;
        MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,1,new MotionEvent.PointerProperties[]{pointer},new MotionEvent.PointerCoords[]{position},0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        require(e.getToolType(0)==MotionEvent.TOOL_TYPE_FINGER&&e.getSource()==InputDevice.SOURCE_TOUCHSCREEN,"EXPLICIT_FINGER_PROTOCOL");
        try{require(ax.injectInputEvent(e,true),"OWNED_FINGER_INPUT_REFUSED");}finally{e.recycle();}
    }
    private static final class TapObservation {
        final Rect region;final int windowId;final String proof;
        TapObservation(Rect region,int windowId,String proof){this.region=new Rect(region);this.windowId=windowId;this.proof=proof;}
        boolean same(TapObservation other){return other!=null&&windowId==other.windowId&&region.equals(other.region)&&proof.equals(other.proof);}
    }
    private JSONObject lastTapGeometry;
    private String lastProjectionFingerprint;
    private static final class TapBinding {
        final KeyboardSizingEditorActivity actor;final android.widget.EditText editor;
        TapBinding(KeyboardSizingEditorActivity actor,android.widget.EditText editor){this.actor=actor;this.editor=editor;}
    }
    private TapBinding captureTapBinding(){return ui(()->{
        require(fixture!=null&&actors.contains(fixture)&&!fixture.isFinishing()&&!fixture.isDestroyed(),"OWNED_EDITOR_IDENTITY_CHANGED");
        java.util.ArrayDeque<android.view.View> pending=new java.util.ArrayDeque<>();pending.add(fixture.getWindow().getDecorView());
        ArrayList<android.widget.EditText> fields=new ArrayList<>();int visited=0;
        while(!pending.isEmpty()){
            android.view.View view=pending.remove();require(++visited<=4096,"OWNED_EDITOR_TREE_BOUND");
            if(view instanceof android.widget.EditText&&"Synthetic ordinary editor".contentEquals(view.getContentDescription()))fields.add((android.widget.EditText)view);
            if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)pending.add(group.getChildAt(i));}
        }
        require(fields.size()==1,"EXACT_OWNED_EDITOR_FIELD_REQUIRED");return new TapBinding(fixture,fields.get(0));
    });}
    /** A newly created actor/field cannot be adopted by this already admitted tap. */
    private boolean ownedEditorReady(TapBinding binding){return ui(()->{
        require(fixture==binding.actor&&actors.contains(binding.actor)&&!binding.actor.isFinishing()&&!binding.actor.isDestroyed(),"OWNED_EDITOR_IDENTITY_CHANGED");
        String generation=binding.actor.getIntent().getStringExtra("fixtureGeneration");
        require(owner.equals(generation)||(owner+"-replacement").equals(generation),"OWNED_EDITOR_GENERATION_CHANGED");
        require(binding.editor.isAttachedToWindow()&&binding.editor.getRootView()==binding.actor.getWindow().getDecorView(),"OWNED_EDITOR_FIELD_IDENTITY_CHANGED");
        require(resumed==null||resumed==binding.actor,"OWNED_EDITOR_ACTOR_CHANGED");
        android.view.View focus=binding.actor.getWindow().getCurrentFocus();
        require(focus==null||focus==binding.editor,"OWNED_EDITOR_FIELD_CHANGED");
        require(binding.actor.syntheticText().isEmpty(),"OWNED_EDITOR_TEXT_CHANGED");
        return resumed==binding.actor&&binding.actor.hasWindowFocus()&&focus==binding.editor&&binding.editor.hasFocus()&&binding.editor.hasWindowFocus();
    });}
    private TapObservation observeTap(String label,String nativeRole,String token,TapBinding binding) throws Exception {
        boolean editorReady=ownedEditorReady(binding);if(nativeRole!=null&&!editorReady)return null;
        List<AccessibilityNodeInfo> controls=matching(label);if(controls.isEmpty())return null;
        AccessibilityNodeInfo n=controls.get(0);require(n.refresh()&&n.isEnabled()&&n.isVisibleToUser(),"CURRENT_CONTROL_NOT_ENABLED");
        Rect screen=new Rect();n.getBoundsInScreen(screen);require(!screen.isEmpty(),"CURRENT_CONTROL_EMPTY");
        if(nativeRole==null)return new TapObservation(screen,n.getWindowId(),String.valueOf(n.getPackageName())+":"+screen.flattenToString());
        JSONObject w=witness();if(w==null)return null;
        JSONArray nativeGeometry=new JSONArray();List<TapObservation> overlaps=new ArrayList<>();
        for(JSONObject v:role(w,nativeRole)){
            nativeGeometry.put(new JSONObject().put("rect",rect(v).flattenToString()).put("nativeLocal",v.getString("visibleInWindow"))
                .put("visible",v.getBoolean("hasVisibleRect")).put("enabled",v.getBoolean("enabled")));
            if(v.getBoolean("hasVisibleRect")&&v.getBoolean("enabled")){
                lastProjectionRejection=null;Rect projected=projectRect(w,v,n);
                if(projected!=null&&projected.intersect(screen)&&!projected.isEmpty())
                    overlaps.add(new TapObservation(projected,n.getWindowId(),lastProjectionFingerprint));
            }
        }
        lastTapGeometry=new JSONObject().put("role",token).put("axRect",screen.flattenToString()).put("axNodeWindowId",n.getWindowId())
            .put("native",nativeGeometry).put("windowGeometry",w.getJSONObject("windowGeometry")).put("ownedAXWindows",ownedWindows())
            .put("rejection",lastProjectionRejection==null?JSONObject.NULL:lastProjectionRejection);
        require(overlaps.size()<=1,"AMBIGUOUS_NATIVE_AX_OVERLAP");return overlaps.isEmpty()?null:overlaps.get(0);
    }
    /** Only observations repeat. Exactly one physical DOWN/UP follows stable, final fresh proof. */
    private void tap(String label,String nativeRole,String reportToken) throws Exception {
        TapBinding binding=captureTapBinding();ax.waitForIdle(150,2000);long end=SystemClock.uptimeMillis()+8000,since=0;
        TapObservation previous=null,accepted=null;JSONArray rejectedFinals=new JSONArray();lastTapGeometry=null;
        do{
            TapObservation current=observeTap(label,nativeRole,reportToken,binding);
            if(current==null){previous=null;since=0;SystemClock.sleep(70);continue;}
            if(!current.same(previous)){previous=current;since=SystemClock.uptimeMillis();}
            if(SystemClock.uptimeMillis()-since>=280){
                TapObservation fresh=observeTap(label,nativeRole,reportToken,binding);
                if(current.same(fresh)){accepted=fresh;break;}
                if(rejectedFinals.length()<16)rejectedFinals.put(new JSONObject().put("candidate",current.region.flattenToString())
                    .put("fresh",fresh==null?JSONObject.NULL:fresh.region.flattenToString()).put("windowId",current.windowId)
                    .put("freshWindowId",fresh==null?JSONObject.NULL:fresh.windowId).put("reason","FINAL_PROOF_CHANGED")
                    .put("observation",lastTapGeometry==null?JSONObject.NULL:lastTapGeometry));
                previous=null;since=0; // No input occurred: restore full stability, not a stale point.
            }
            SystemClock.sleep(70);
        }while(SystemClock.uptimeMillis()<end);
        if(rejectedFinals.length()>0)report.getJSONArray("finalProofReobservations").put(new JSONObject().put("role",reportToken).put("rejected",rejectedFinals));
        if(accepted==null&&lastTapGeometry!=null){report.put("lastFailedTapGeometry",lastTapGeometry);captureFailedGeometry(lastTapGeometry);}
        require(accepted!=null,"CONTROL_NATIVE_AX_GEOMETRY_NOT_STABLE");
        boolean finalOwnedReady=ownedEditorReady(binding);if(nativeRole!=null)require(finalOwnedReady,"OWNED_EDITOR_NOT_CURRENT_BEFORE_DOWN");
        budget();require(SystemClock.uptimeMillis()<end,"TAP_OBSERVATION_DEADLINE_BEFORE_DOWN");
        Rect point=accepted.region;
        taps.put(new JSONObject().put("role",reportToken).put("rect",point.flattenToString()).put("windowId",accepted.windowId).put("nativeOverlap",nativeRole!=null)
            .put("nativeGeometryBasis",nativeRole==null?"not-applicable":"identified-window-local-translation-v2"));
        long down=SystemClock.uptimeMillis();require(down<end,"TAP_OBSERVATION_DEADLINE_BEFORE_DOWN");event(MotionEvent.ACTION_DOWN,point.exactCenterX(),point.exactCenterY(),down);
        event(MotionEvent.ACTION_UP,point.exactCenterX(),point.exactCenterY(),down);ax.waitForIdle(100,2000);
    }
    private NativeWindowIntersection.Bounds bounds(Rect value){return new NativeWindowIntersection.Bounds(value.left,value.top,value.right,value.bottom);}
    /** Fresh identified OS-window translation, NOT a fitted correction to the old attachment screen offset. */
    private Rect projectSingle(JSONObject w,JSONObject nativeView,AccessibilityNodeInfo node) throws Exception {
        if(android.os.Build.VERSION.SDK_INT<36||w==null||!node.refresh()||!node.isVisibleToUser()||
            !APP.equals(String.valueOf(node.getPackageName()))||!nativeView.getBoolean("hasLocalVisibleRect")||
            !nativeView.getBoolean("identityLocalChain")){lastProjectionRejection=new JSONObject().put("reason","NODE_VISIBILITY_OR_NATIVE_IDENTITY").put("native",nativeView);return null;}
        JSONObject geometry=w.getJSONObject("windowGeometry");long age=SystemClock.uptimeMillis()-geometry.getLong("uptimeMs");
        if(age<0||age>1000){lastProjectionRejection=new JSONObject().put("reason","STALE_NATIVE_OBSERVATION").put("ageMs",age);return null;}
        int count=0;android.view.accessibility.AccessibilityWindowInfo selected=null;AccessibilityNodeInfo root=null;
        for(android.view.accessibility.AccessibilityWindowInfo window:ax.getWindows()){
            if(window.getType()!=android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD)continue;
            AccessibilityNodeInfo candidate=window.getRoot();
            if(candidate!=null&&APP.equals(String.valueOf(candidate.getPackageName()))){count++;selected=window;root=candidate;}
        }
        if(count!=1||selected==null||root==null||!root.refresh()){lastProjectionRejection=new JSONObject().put("reason","IME_WINDOW_UNAVAILABLE_OR_AMBIGUOUS").put("count",count);return null;}
        Rect windowRect=new Rect(),rootRect=new Rect(),local=new Rect(),screen=new Rect();
        selected.getBoundsInScreen(windowRect);root.getBoundsInScreen(rootRect);node.getBoundsInWindow(local);node.getBoundsInScreen(screen);
        Rect nativeLocal=Rect.unflattenFromString(nativeView.getString("visibleInWindow"));if(nativeLocal==null)return null;
        NativeWindowIntersection.Bounds projected=NativeWindowIntersection.project(new NativeWindowIntersection.Input(
            bounds(nativeLocal),bounds(local),bounds(screen),bounds(windowRect),bounds(rootRect),geometry.getInt("nativeNodeWindowId"),
            node.getWindowId(),selected.getId(),root.getWindowId(),count,geometry.getInt("decorWidth"),geometry.getInt("decorHeight")));
        if(projected==null)lastProjectionRejection=new JSONObject().put("reason","EXACT_PROJECTION_REFUSED").put("nativeLocal",nativeLocal.flattenToString())
            .put("axLocal",local.flattenToString()).put("axScreen",screen.flattenToString()).put("window",windowRect.flattenToString()).put("root",rootRect.flattenToString())
            .put("nativeWindowId",geometry.getInt("nativeNodeWindowId")).put("nodeWindowId",node.getWindowId()).put("windowId",selected.getId()).put("rootWindowId",root.getWindowId());
        return projected==null?null:new Rect(projected.left,projected.top,projected.right,projected.bottom);
    }
    private Rect projectRect(JSONObject w,JSONObject nativeView,AccessibilityNodeInfo node) throws Exception {
        Rect projected=projectSingle(w,nativeView,node);if(projected==null)return null;
        // Corroborate translation at distinct fixed chrome locations; never accept one fitted control.
        String[] labels="LETTERS".equals(w.getString("page"))?new String[]{"Lip keyboard menu","q"}:
            new String[]{"Return from stickers to letters","Show local sticker packs"};
        Rect previous=null;JSONArray corroboration=new JSONArray();
        for(String label:labels){
            List<JSONObject> nativeChrome=role(w,label);List<AccessibilityNodeInfo> axChrome=matching(label);
            if(nativeChrome.size()!=1||axChrome.size()!=1){lastProjectionRejection=new JSONObject().put("reason","CORROBORATING_CHROME_MISSING").put("role",label).put("nativeCount",nativeChrome.size()).put("axCount",axChrome.size());return null;}
            Rect current=projectSingle(w,nativeChrome.get(0),axChrome.get(0));if(current==null||current.equals(previous))return null;
            Rect axLocal=new Rect();axChrome.get(0).getBoundsInWindow(axLocal);
            corroboration.put(new JSONObject().put("role",label).put("nativeLocal",nativeChrome.get(0).getString("visibleInWindow"))
                .put("axLocal",axLocal.flattenToString()).put("projected",current.flattenToString()).put("windowId",axChrome.get(0).getWindowId()));
            previous=current;
        }
        String key=w.getJSONObject("windowGeometry").getInt("nativeNodeWindowId")+":"+rect(nativeView).flattenToString()+":"+projected.flattenToString()+":"+nativeView.getInt("selfScrollX")+":"+nativeView.getInt("selfScrollY");
        if(projectionProofs.length()<128&&recordedProjectionKeys.add(key)){
            Rect axLocal=new Rect(),axScreen=new Rect();node.getBoundsInWindow(axLocal);node.getBoundsInScreen(axScreen);
            projectionProofs.put(new JSONObject().put("nativeRole",nativeView.getString("role")).put("nativeAttachmentScreen",rect(nativeView).flattenToString())
                .put("nativeLocal",nativeView.getString("visibleInWindow")).put("axLocal",axLocal.flattenToString()).put("axScreen",axScreen.flattenToString())
                .put("drawingVisible",nativeView.getString("drawingVisible")).put("selfScrollX",nativeView.getInt("selfScrollX")).put("selfScrollY",nativeView.getInt("selfScrollY"))
                .put("inWindowX",nativeView.getInt("inWindowX")).put("inWindowY",nativeView.getInt("inWindowY"))
                .put("projected",projected.flattenToString()).put("windowGeometry",w.getJSONObject("windowGeometry")).put("ownedAXWindows",ownedWindows())
                .put("rawScreenWouldMiss",!Rect.intersects(rect(nativeView),axScreen)).put("corroboration",corroboration));
        }
        lastProjectionFingerprint=w.getJSONObject("windowGeometry").getInt("nativeNodeWindowId")+":"+nativeView.getString("visibleInWindow")+":"+
            projected.flattenToString()+":"+w.getJSONObject("windowGeometry").getInt("decorWidth")+":"+w.getJSONObject("windowGeometry").getInt("decorHeight")+":"+corroboration.toString();
        return projected;
    }
    private JSONArray ownedWindows() throws Exception {
        JSONArray result=new JSONArray();
        for(android.view.accessibility.AccessibilityWindowInfo window:ax.getWindows()){
            AccessibilityNodeInfo root=window.getRoot();if(root==null||!owned(String.valueOf(root.getPackageName())))continue;
            Rect bounds=new Rect(),rootBounds=new Rect();window.getBoundsInScreen(bounds);root.getBoundsInScreen(rootBounds);
            result.put(new JSONObject().put("id",window.getId()).put("type",window.getType()).put("bounds",bounds.flattenToString())
                .put("rootWindowId",root.getWindowId()).put("rootBounds",rootBounds.flattenToString()).put("active",window.isActive()).put("focused",window.isFocused()));
        }
        return result;
    }
    /** Controlled emulator only; retain just the failing fixed chrome union, never a full screen. */
    private void captureFailedGeometry(JSONObject geometry){
        android.graphics.Bitmap screen=null,crop=null;
        try{
            require("ranchu".equals(android.os.Build.HARDWARE)&&TEST.equals(getTargetContext().getPackageName()),"OWNED_CROP_GUARD");
            require(ui(()->fixture!=null&&fixture.hasWindowFocus()&&fixture.syntheticText().isEmpty()),"OWNED_EMPTY_EDITOR_REQUIRED");
            require("menu".equals(geometry.getString("role")),"FIXED_CHROME_ONLY");
            Rect region=Rect.unflattenFromString(geometry.getString("axRect"));require(region!=null,"FIXED_REGION_REQUIRED");
            JSONArray items=geometry.getJSONArray("native");require(items.length()==1,"SINGLE_NATIVE_CHROME_REQUIRED");
            Rect nativeRect=Rect.unflattenFromString(items.getJSONObject(0).getString("rect"));require(nativeRect!=null,"FIXED_REGION_REQUIRED");region.union(nativeRect);
            screen=ax.takeScreenshot();require(screen!=null,"CONTROLLED_SCREEN_UNAVAILABLE");
            require(region.intersect(0,0,screen.getWidth(),screen.getHeight())&&!region.isEmpty()&&region.width()<=480&&region.height()<=800,"FIXED_CROP_BOUND");
            crop=android.graphics.Bitmap.createBitmap(screen,region.left,region.top,region.width(),region.height());
            File path=new File(getTargetContext().getFilesDir(),"sticker-browser-"+owner+"-tap-crop.png");require(path.createNewFile(),"CROP_MUST_NOT_OVERWRITE");
            try(FileOutputStream out=new FileOutputStream(path)){require(crop.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out),"CROP_WRITE_FAILED");out.getFD().sync();}
            report.put("failedGeometryCrop",new JSONObject().put("file",path.getName()).put("region",region.flattenToString()).put("scope","owned emulator empty-editor fixed-menu chrome union only"));
        }catch(Throwable t){try{report.put("failedGeometryCropError",t.getClass().getSimpleName());}catch(Exception ignored){}}
        finally{if(crop!=null&&crop!=screen)crop.recycle();if(screen!=null)screen.recycle();}
    }
    private Rect scrollRect(boolean browser) throws Exception {
        if(browser){
            AccessibilityNodeInfo n=find(SCROLL);Rect a=new Rect();n.getBoundsInScreen(a);JSONObject w=witness();List<JSONObject> v=role(w,"sticker:scroll");require(v.size()==1,"UNIQUE_BROWSER_SCROLL_REQUIRED");
            Rect projected=projectRect(w,v.get(0),n);
            if(projected==null)report.put("failedScrollProjection",new JSONObject().put("native",v.get(0)).put("windowGeometry",w.getJSONObject("windowGeometry")).put("ownedAXWindows",ownedWindows()));
            require(v.get(0).getBoolean("hasVisibleRect")&&projected!=null&&a.intersect(projected),"BROWSER_SCROLL_NATIVE_AX_OVERLAP");return a;
        }
        AccessibilityNodeInfo active=ax.getRootInActiveWindow();require(active!=null&&APP.equals(String.valueOf(active.getPackageName())),"OWNED_SETTINGS_ACTIVE_WINDOW_REQUIRED");
        List<AccessibilityNodeInfo> list=new ArrayList<>();for(AccessibilityNodeInfo n:nodes())if(n.isVisibleToUser()&&APP.equals(String.valueOf(n.getPackageName()))&&"android.widget.ScrollView".contentEquals(n.getClassName()))list.add(n);
        require(list.size()==1,"UNIQUE_SETTINGS_SCROLL_REQUIRED");Rect r=new Rect();list.get(0).getBoundsInScreen(r);return r;
    }
    private void scroll(boolean browser,boolean up) throws Exception {
        budget();Rect r=scrollRect(browser);require(r.height()>48&&r.width()>20,"SCROLL_VIEWPORT_TOO_SMALL");
        float x=r.exactCenterX(),from=r.top+r.height()*(up?.25f:.8f),to=r.top+r.height()*(up?.8f:.25f);long down=SystemClock.uptimeMillis();
        event(MotionEvent.ACTION_DOWN,x,from,down);
        for(int i=1;i<=8;i++){SystemClock.sleep(20);event(MotionEvent.ACTION_MOVE,x,from+(to-from)*i/8f,down);}
        // A held stationary endpoint removes fling velocity; this remains a real finger swipe.
        SystemClock.sleep(200);event(MotionEvent.ACTION_MOVE,x,to,down);
        event(MotionEvent.ACTION_UP,x,to,down);ax.waitForIdle(100,2000);
    }
    private void reveal(String label,boolean browser,boolean up) throws Exception {
        for(int i=0;i<20;i++){if(matching(label).size()==1)return;scroll(browser,up);}
        throw new AssertionError("CONTROL_NOT_REACHABLE_BY_PHYSICAL_SCROLL");
    }
    private void openBrowser() throws Exception {
        tap("Lip keyboard menu","Lip keyboard menu","menu");tap("Local sticker packs",null,"open-browser");
        ready("STICKERS","Local packs 1–2 of 2",0,null);
        reveal(SYNTHETIC,true,false);require(matching(SYNTHETIC).size()==1,"SYNTHETIC_PACK_NOT_PRESENT");
        reveal(SELECTED,true,false);require(matching(SELECTED).size()==1,"SELECTED_PACK_NOT_PRESENT");
        report.put("twoExpectedOwnedPacksObserved",true);
    }
    private JSONObject selectedFirst(String scale) throws Exception {
        openBrowser();tap(SELECTED,"sticker:tile","select-approved-local-pack");
        return ready("STICKERS","Static previews 1–8 of 75",8,scale);
    }
    private JSONObject previewGeometry(JSONObject w) throws Exception {
        List<JSONObject> rows=role(w,"sticker:preview");require(rows.size()==8,"EXACT_EIGHT_PREVIEWS_REQUIRED");
        JSONObject first=rows.get(0);require(first.getInt("width")>0&&first.getInt("height")>0,"PREVIEW_NOT_MEASURED");
        return new JSONObject().put("width",first.getInt("width")).put("height",first.getInt("height"));
    }
    private JSONObject stablePreviewWitness(int count) throws Exception {
        long end=SystemClock.uptimeMillis()+8000;String previous=null;
        do{
            JSONObject w=witness();
            require(w!=null&&"STICKERS".equals(w.getString("page")),"BROWSER_PAGE_LOST");unchanged(w);
            require(role(w,"sticker:preview").size()==count,"LOADED_PREVIEW_COUNT_CHANGED");
            String geometry=w.getJSONArray("views").toString();
            if(geometry.equals(previous))return w;
            previous=geometry;SystemClock.sleep(100);
        }while(SystemClock.uptimeMillis()<end);
        throw new AssertionError("PREVIEW_SCROLL_GEOMETRY_NOT_STABLE");
    }
    private void previewSweep(int count) throws Exception {
        boolean[] seen=new boolean[count];int scrolls=0;
        JSONArray runs=report.optJSONArray("previewSweeps");
        if(runs==null){runs=new JSONArray();report.put("previewSweeps",runs);}
        JSONArray frames=new JSONArray();runs.put(new JSONObject().put("phase",stage).put("frames",frames));
        for(int sweep=0;sweep<=24;sweep++){
            // Observe two identical native layouts after each physical swipe, not AX text/status.
            JSONObject w=stablePreviewWitness(count);
            List<JSONObject> previews=role(w,"sticker:preview");
            JSONArray positions=new JSONArray(),fullyVisible=new JSONArray(),seenIndices=new JSONArray();
            for(JSONObject v:previews){int ordinal=v.getInt("ordinal");require(ordinal>=0&&ordinal<count,"PREVIEW_ORDINAL_BOUND");
                require(v.getInt("height")>0&&v.getInt("width")>0,"PREVIEW_EMPTY");
                boolean full=v.getBoolean("hasVisibleRect")&&v.getInt("visibleHeight")>=v.getInt("height")&&v.getInt("visibleWidth")>=v.getInt("width");
                if(full){seen[ordinal]=true;fullyVisible.put(ordinal);}
                positions.put(new JSONObject().put("ordinal",ordinal).put("x",v.getInt("x")).put("y",v.getInt("y"))
                    .put("width",v.getInt("width")).put("height",v.getInt("height")).put("hasVisibleRect",v.getBoolean("hasVisibleRect"))
                    .put("visibleWidth",v.getInt("visibleWidth")).put("visibleHeight",v.getInt("visibleHeight")));
            }
            for(int i=0;i<count;i++)if(seen[i])seenIndices.put(i);
            frames.put(new JSONObject().put("sweep",sweep).put("scrolls",scrolls).put("scale",w.getString("stickerScale"))
                .put("previews",positions).put("fullyVisibleIndices",fullyVisible).put("seenIndices",seenIndices));
            for(String token:Arrays.asList("sticker:status","sticker:title","sticker:credit","sticker:disclosure"))for(JSONObject v:role(w,token))
                require(v.getInt("layoutHeight")>0&&v.getInt("layoutHeight")<=v.getInt("contentHeight")&&v.getDouble("lineWidth")<=v.getInt("contentWidth")&&v.getInt("ellipses")==0,"WRAPPED_STICKER_TEXT_CLIPPED");
            boolean all=true;for(boolean value:seen)all&=value;
            if(all){report.put("lastEightPreviewSweepScrolls",scrolls);return;}
            require(sweep<24,"EIGHT_PREVIEWS_NOT_FULLY_REACHABLE");scroll(true,false);scrolls++;
        }
    }
    private void letters() throws Exception {tap(BACK,BACK,"return-letters");ready("LETTERS",null,0,null);require(ui(()->fixture.syntheticText().isEmpty()),"BROWSE_CHANGED_EDITOR_TEXT");}
    private void setScale(String enumName,String choice) throws Exception {
        tap("Lip keyboard menu","Lip keyboard menu","menu");tap("Keyboard preferences",null,"preferences");find("Your keyboard.");
        reveal("Sticker preview size: "+choice,false,false);tap("Sticker preview size: "+choice,null,"sticker-scale-"+enumName);
        require(find("Sticker preview size: "+choice).isChecked(),"ACTUAL_SCALE_CHOICE_NOT_CHECKED");
        reveal("Back from keyboard preferences",false,true);tap("Back from keyboard preferences",null,"preferences-back");
        find("Lip synthetic generation "+owner);ready("LETTERS",null,0,enumName);
    }
    private void launch() throws Exception {
        CountDownLatch created=new CountDownLatch(1);application=(Application)getTargetContext().getApplicationContext();
        lifecycle=new Application.ActivityLifecycleCallbacks(){
            @Override public void onActivityCreated(Activity a,Bundle b){if(a instanceof KeyboardSizingEditorActivity&&Arrays.asList(owner,owner+"-replacement").contains(a.getIntent().getStringExtra("fixtureGeneration"))){actors.add((KeyboardSizingEditorActivity)a);fixture=(KeyboardSizingEditorActivity)a;created.countDown();if(cleanupRequested)a.finish();}}
            @Override public void onActivityStarted(Activity a){} @Override public void onActivityResumed(Activity a){if(actors.contains(a))resumed=a;} @Override public void onActivityPaused(Activity a){if(resumed==a)resumed=null;}
            @Override public void onActivityStopped(Activity a){} @Override public void onActivitySaveInstanceState(Activity a,Bundle b){}
            @Override public void onActivityDestroyed(Activity a){
                if(cleanupRequested&&actors.size()==admittedEditorLaunches&&actors.stream().allMatch(Activity::isDestroyed)&&lifecycleRegistered){
                    application.unregisterActivityLifecycleCallbacks(this);lifecycleRegistered=false;
                }
            }
        };
        ui(()->{application.registerActivityLifecycleCallbacks(lifecycle);lifecycleRegistered=true;admittedEditorLaunches++;getTargetContext().startActivity(new Intent(getTargetContext(),KeyboardSizingEditorActivity.class)
            .putExtra("fixtureGeneration",owner).putExtra("landscapeFixture",!"portrait".equals(variant)).setIdentifier(owner).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK));return null;});
        require(created.await(8,TimeUnit.SECONDS),"FRESH_EDITOR_CREATION_DEADLINE");find("Lip synthetic generation "+owner);require(fixture!=null,"FRESH_OWNED_EDITOR_REQUIRED");
        tap("Synthetic ordinary editor",null,"synthetic-editor-focus");JSONObject first=ready("LETTERS",null,0,null);
        imePid=first.getInt("pid");serviceId=first.getString("serviceId");frozen=new JSONObject(first.getJSONObject("renderedSettings").toString());
        require(first.getInt("orientation")== ("portrait".equals(variant)?1:2),"ACTUAL_ORIENTATION_PRECONDITION");
        require(Math.abs(first.getDouble("fontScale")-("portrait".equals(variant)?1.0:2.0))<.005,"ACTUAL_FONT_SCALE_PRECONDITION");
        require("STANDARD".equals(frozen.getString("height")),"EMPTY_OLD_PREFERENCES_EXPECT_STANDARD");
        require("SYSTEM".equals(first.getString("stickerScale")),"NEW_SCALE_BASELINE_EXPECT_SYSTEM");
        require(ui(()->fixture.syntheticText().isEmpty()),"FRESH_SYNTHETIC_EDITOR_NOT_EMPTY");
        report.put("imePid",imePid).put("driverPid",android.os.Process.myPid()).put("fontScale",first.getDouble("fontScale"))
            .put("orientation",first.getInt("orientation")).put("oldKeyboardHeight",frozen.getString("height"));
    }
    private void replaceEditor() throws Exception {
        stage="replacement-open";selectedFirst("SYSTEM");
        final KeyboardSizingEditorActivity previous=fixture;
        final String replacementGeneration=owner+"-replacement";
        stage="replacement-new-window";
        ui(()->{admittedEditorLaunches++;getTargetContext().startActivity(new Intent(getTargetContext(),KeyboardSizingEditorActivity.class)
            .putExtra("fixtureGeneration",replacementGeneration).putExtra("landscapeFixture",!"portrait".equals(variant))
            .setIdentifier(replacementGeneration).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK));return null;});
        long end=SystemClock.uptimeMillis()+8000;boolean newActor=false;
        do{newActor=ui(()->{
            if(fixture==null||fixture==previous||actors.size()!=2||resumed!=fixture||!fixture.hasWindowFocus())return false;
            android.view.View focus=fixture.getWindow().getCurrentFocus();
            return focus instanceof android.widget.EditText&&focus.hasFocus()&&focus.hasWindowFocus()&&
                "Synthetic ordinary editor".contentEquals(focus.getContentDescription());
        });if(newActor)break;SystemClock.sleep(80);}while(SystemClock.uptimeMillis()<end);
        require(newActor,"NEW_EDITOR_WINDOW_CREATION_DEADLINE");
        find("Lip synthetic generation "+replacementGeneration);
        AccessibilityNodeInfo active=ax.getRootInActiveWindow();
        require(active!=null&&TEST.equals(String.valueOf(active.getPackageName())),"REPLACEMENT_ACTIVE_WINDOW_REQUIRED");
        tap("Synthetic ordinary editor",null,"replacement-editor-focus");
        JSONObject after=ready("LETTERS",null,0,"SYSTEM");unchanged(after);
        require(role(after,"sticker:preview").isEmpty(),"OLD_PACK_VIEW_SURVIVED_EDITOR_REPLACEMENT");
        require(ui(()->previous.syntheticText().isEmpty()&&fixture.syntheticText().isEmpty()),"REPLACEMENT_CHANGED_EDITOR_TEXT");
        selectedFirst("SYSTEM");letters();
        report.put("actualEditorWindowReplacement",true).put("oldAndNewEditorsUnchanged",true)
            .put("freshLibraryAfterReplacement",true).put("ownedEditorInstances",2)
            .put("replacementScope","actual second Activity/editor window, not physical rotation or forced pending-read timing");
    }
    private boolean cleanup(){
        boolean ok=true;
        try{
            // Close only an admitted, currently identifiable owned keyboard popup.
            if(ax!=null&&(matching("Local sticker packs").size()==1||matching("Keyboard preferences").size()==1)){
                AccessibilityNodeInfo root=ax.getRootInActiveWindow();require(root!=null&&APP.equals(String.valueOf(root.getPackageName())),"CLEANUP_POPUP_OWNER");
                long now=SystemClock.uptimeMillis();
                require(ax.injectInputEvent(new KeyEvent(now,now,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK,0),true),"CLEANUP_POPUP_BACK_DOWN");
                require(ax.injectInputEvent(new KeyEvent(now,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK,0),true),"CLEANUP_POPUP_BACK_UP");
                ax.waitForIdle(100,2000);
            }
            // Only the admitted keyboard-preferences Activity can be closed by this route.
            AccessibilityNodeInfo root=ax==null?null:ax.getRootInActiveWindow();
            if(root!=null&&APP.equals(String.valueOf(root.getPackageName()))){
                boolean settings=false;
                for(AccessibilityNodeInfo n:nodes())if("Back from keyboard preferences".contentEquals(n.getContentDescription()==null?"":n.getContentDescription()))settings=true;
                require(settings,"CLEANUP_UNKNOWN_APP_SURFACE");
                reveal("Back from keyboard preferences",false,true);tap("Back from keyboard preferences",null,"cleanup-preferences-back");
            }
        }catch(Throwable t){ok=false;}
        try{ui(()->{cleanupRequested=true;for(KeyboardSizingEditorActivity a:actors)if(!a.isFinishing())a.finish();return null;});
            long end=SystemClock.uptimeMillis()+5000;boolean done=false;
            do{done=ui(()->{if(actors.size()!=admittedEditorLaunches)return false;for(KeyboardSizingEditorActivity a:actors)if(!a.isDestroyed())return false;return true;});if(done)break;SystemClock.sleep(80);}while(SystemClock.uptimeMillis()<end);
            ok&=done;
        }catch(Throwable t){ok=false;}
        try{if(application!=null&&lifecycle!=null)ui(()->{
            boolean resolved=actors.size()==admittedEditorLaunches&&actors.stream().allMatch(Activity::isDestroyed);
            if(resolved&&lifecycleRegistered){application.unregisterActivityLifecycleCallbacks(lifecycle);lifecycleRegistered=false;}
            return null;
        });}catch(Throwable t){ok=false;}
        return ok;
    }
    @Override public void onStart(){
        int outcome=Activity.RESULT_CANCELED;
        try{
            require("ranchu".equals(android.os.Build.HARDWARE)&&TEST.equals(getContext().getPackageName())&&TEST.equals(getTargetContext().getPackageName()),"SELF_TEST_OWNED_EMULATOR_ONLY");
            report.put("finalProofReobservations",new JSONArray()).put("driverVersion",1).put("owner",owner).put("variant",variant).put("mode",mode).put("passed",false)
                .put("scope","actual independent-process native keyboard browsing and UI-only sticker scale; no send/phone/TalkBack/visual9.2")
                .put("screenshotsSkipped",true).put("screenshotReason","initial semantic-only run; no broad-window capture");
            ax=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            android.accessibilityservice.AccessibilityServiceInfo cfg=ax.getServiceInfo();cfg.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;ax.setServiceInfo(cfg);
            stage="startup";launch();
            if("replacement".equals(mode)){
                replaceEditor();report.put("passed",true);outcome=Activity.RESULT_OK;
            }else{
            String[] order={"SYSTEM","EXTRA_LARGE","LARGE","SMALL"};String[] labels={"System","Extra large","Large","Small"};
            int system=0,extra=0,large=0,small=0;
            for(int i=0;i<order.length;i++){
                budget();stage="scale-"+order[i];if(i>0)setScale(order[i],labels[i]);
                JSONObject first=selectedFirst(order[i]);JSONObject geometry=previewGeometry(first);previewSweep(8);
                scales.put(new JSONObject().put("scale",order[i]).put("preview",geometry).put("allEightFullyObserved",true).put("frozenKeyboardPreferencesUnchanged",true).put("orientation",first.getInt("orientation")).put("fontScale",first.getDouble("fontScale")).put("windowWidth",first.getInt("windowWidth")).put("windowHeight",first.getInt("windowHeight")));
                int h=geometry.getInt("height");if(i==0)system=h;else if(i==1)extra=h;else if(i==2)large=h;else small=h;
                if(i==0){
                    stage="page-next";reveal(NEXT,true,false);tap(NEXT,NEXT,"next-page");ready("STICKERS","Static previews 9–16 of 75",8,order[i]);previewSweep(8);
                    stage="page-previous";reveal(PREVIOUS,true,false);tap(PREVIOUS,PREVIOUS,"previous-page");ready("STICKERS","Static previews 1–8 of 75",8,order[i]);report.put("actualPageForwardBack",true);
                }
                letters();
            }
            require(small<system&&system<large&&large<extra,"ACTUAL_FOUR_SCALE_GEOMETRY_NOT_STRICTLY_ORDERED");
            report.put("fourScaleGeometryOrdered",true).put("browseOnlyEditorUnchanged",true).put("passed",true);outcome=Activity.RESULT_OK;
            }
        }catch(Throwable t){try{report.put("passed",false).put("failureStage",stage).put("failureType",t.getClass().getSimpleName());if(t instanceof AssertionError)report.put("failureToken",String.valueOf(t.getMessage()));}catch(Exception ignored){}}
        finally{deadline=SystemClock.uptimeMillis()+15_000;boolean closed=cleanup();try{report.put("cleanupConfirmed",closed);if(!closed){report.put("passed",false);outcome=Activity.RESULT_CANCELED;}}catch(Exception ignored){outcome=Activity.RESULT_CANCELED;}}
        try{
            report.put("taps",taps).put("scales",scales).put("projectionProofs",projectionProofs);
            File file=new File(getTargetContext().getFilesDir(),"sticker-browser-"+owner+"-result.json");require(file.createNewFile(),"RESULT_MUST_NOT_OVERWRITE");
            try(FileOutputStream out=new FileOutputStream(file)){out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        }catch(Throwable t){outcome=Activity.RESULT_CANCELED;try{report.put("passed",false).put("resultWriteFailed",true);}catch(Exception ignored){}}
        Bundle result=new Bundle();result.putString("driverResult",report.toString());finish(outcome,result);
    }
}
