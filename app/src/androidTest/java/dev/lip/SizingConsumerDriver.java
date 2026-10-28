package dev.lip;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Separate Java test-package process. Never depends on production Kotlin classes. */
public final class SizingConsumerDriver extends Instrumentation {
    private static final String APP="dev.lip.android.sizingfixture", TEST=APP+".test";
    private UiAutomation ax;
    private KeyboardSizingEditorActivity fixture;
    private String owner, variant, mode, stage="guard";
    private int imePid;
    private boolean scene;
    private final JSONObject report=new JSONObject();
    private final JSONArray images=new JSONArray();
    @Override public void onCreate(Bundle args){
        super.onCreate(args);
        try{
            owner=args.getString("owner");require(UUID.fromString(owner).toString().equals(owner),"Explicit owner required");
            variant=args.getString("variant");mode=args.getString("mode");
            require(java.util.Arrays.asList("portrait","compact-landscape-font2").contains(variant),"Unknown variant");
            require(java.util.Arrays.asList("startup","sizing","tones").contains(mode),"Unknown mode");
        }catch(Exception e){finish(Activity.RESULT_CANCELED,new Bundle());return;}
        start();
    }
    private static void require(boolean value,String why){if(!value)throw new AssertionError(why);}
    private <T>T ui(Supplier<T> action){
        AtomicReference<T> value=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();
        runOnMainSync(()->{try{value.set(action.get());}catch(Throwable t){error.set(t);}});
        if(error.get()!=null)throw new AssertionError("Owned editor operation failed",error.get());return value.get();
    }
    private List<AccessibilityNodeInfo> nodes(){
        require(android.os.Build.VERSION.SDK_INT>=33&&ax.clearCache(),"Fresh accessibility cache required");
        List<AccessibilityNodeInfo> out=new ArrayList<>();
        java.util.function.BiConsumer<AccessibilityNodeInfo,Integer> walk=new java.util.function.BiConsumer<>(){
            @Override public void accept(AccessibilityNodeInfo n,Integer depth){if(n==null||depth>32||out.size()>4096)return;out.add(n);for(int i=0;i<n.getChildCount();i++)accept(n.getChild(i),depth+1);}
        };
        ax.getWindows().forEach(w->walk.accept(w.getRoot(),0));return out;
    }
    private List<AccessibilityNodeInfo> matching(String label){
        List<AccessibilityNodeInfo> out=new ArrayList<>();
        for(AccessibilityNodeInfo n:nodes())if(n.isVisibleToUser()&&(APP.equals(String.valueOf(n.getPackageName()))||TEST.equals(String.valueOf(n.getPackageName())))&&
            (label.contentEquals(n.getContentDescription()==null?"":n.getContentDescription())||!n.isEditable()&&label.contentEquals(n.getText()==null?"":n.getText())))out.add(n);
        return out;
    }
    private AccessibilityNodeInfo find(String label){
        long end=SystemClock.uptimeMillis()+8000;
        do{List<AccessibilityNodeInfo> found=matching(label);if(found.size()==1)return found.get(0);SystemClock.sleep(80);}while(SystemClock.uptimeMillis()<end);
        throw new AssertionError("Unique owned control unavailable: "+label);
    }
    private void tap(String label) throws Exception { press(label,0); }
    private void press(String label,long holdMs) throws Exception {
        ax.waitForIdle(200,3000);AccessibilityNodeInfo node=find(label);require(node.isEnabled(),"Disabled control");
        require(node.refresh(),"Owned node refresh failed");Rect r=new Rect();node.getBoundsInScreen(r);require(!r.isEmpty(),"Empty control rectangle");
        JSONArray taps=report.optJSONArray("tapCoordinates");if(taps==null){taps=new JSONArray();report.put("tapCoordinates",taps);}
        JSONObject point=new JSONObject().put("label",label).put("ax",r.toShortString());
        if(java.util.Arrays.asList("q","Emoji keyboard","Lip keyboard menu","Return from emoji to letters","Cancel emoji skin tone choices").contains(label)){
            long end=SystemClock.uptimeMillis()+8000;boolean agreed=false;int observations=0;
            do{
                List<AccessibilityNodeInfo> current=matching(label);
                require(current.size()==1,"Fresh unique owned control required before EVERY overlap observation");
                node=current.get(0);
                require(node.refresh()&&node.isEnabled()&&node.isVisibleToUser()&&
                    (APP.equals(String.valueOf(node.getPackageName()))||TEST.equals(String.valueOf(node.getPackageName())))&&
                    label.equals(String.valueOf(node.getContentDescription())),"Current fixed chrome ownership changed");
                node.getBoundsInScreen(r);require(!r.isEmpty(),"Current owned rectangle empty");
                JSONObject nativeView=view(witness(),label);Rect nativeRect=new Rect(nativeView.getInt("x"),nativeView.getInt("y"),nativeView.getInt("x")+nativeView.getInt("width"),nativeView.getInt("y")+nativeView.getInt("height"));
                Rect intersection=new Rect(r);agreed=intersection.intersect(nativeRect)&&!intersection.isEmpty();
                observations++;
                if(observations==1)point.put("firstAx",r.toShortString()).put("firstNative",nativeRect.toShortString());
                point.put("native",nativeRect.toShortString()).put("ax",r.toShortString()).put("axMatchesNative",r.equals(nativeRect)).put("basis","physical point inside BOTH native and AX chrome").put("observations",observations);
                if(agreed){r=intersection;point.put("intersection",r.toShortString()).put("x",r.exactCenterX()).put("y",r.exactCenterY());break;}
                SystemClock.sleep(100);
            }while(SystemClock.uptimeMillis()<end);
            require(agreed,"Native/AX fixed control rectangles never overlapped; NO tap dispatched");
        }
        taps.put(point);
        long down=SystemClock.uptimeMillis();
        for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP}){
            if(action==MotionEvent.ACTION_UP&&holdMs>0)SystemClock.sleep(holdMs);
            MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,r.exactCenterX(),r.exactCenterY(),0);e.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            try{require(ax.injectInputEvent(e,true),"Physical guest tap refused");}finally{e.recycle();}
        }
        ax.waitForIdle(100,2000);
    }
    private void reveal(String label,boolean backwards){
        for(int i=0;i<24;i++){
            if(matching(label).size()==1)return;
            AccessibilityNodeInfo scroll=null;
            for(AccessibilityNodeInfo n:nodes())if(n.isVisibleToUser()&&APP.equals(String.valueOf(n.getPackageName()))&&"android.widget.ScrollView".equals(String.valueOf(n.getClassName()))){require(scroll==null,"Ambiguous settings scroll");scroll=n;}
            require(scroll!=null&&scroll.performAction(backwards?AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD:AccessibilityNodeInfo.ACTION_SCROLL_FORWARD),"Cannot reach settings control");SystemClock.sleep(100);
        }
        throw new AssertionError("Settings row unreachable");
    }
    private JSONObject witness() throws Exception {
        byte[] bytes;
        try(java.io.InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(ax.executeShellCommand("dumpsys activity service "+APP+"/dev.lip.keyboard.LipInputMethodService --lip-sizing-witness"))){bytes=in.readNBytes(65537);}
        require(bytes.length<=65536,"Witness response bound");String value=null;
        for(String line:new String(bytes,StandardCharsets.UTF_8).split("\n"))if(line.trim().startsWith("LIP_SIZING_WITNESS ")){require(value==null,"Duplicate witness");value=line.trim().substring("LIP_SIZING_WITNESS ".length());}
        if(value==null||value.equals("unavailable"))return null;
        JSONObject result=new JSONObject(value);require(result.getBoolean("ownedEditor"),"Witness ownership");
        require(result.getInt("pid")!=android.os.Process.myPid(),"Driver must be a DIFFERENT process from IME");
        if(imePid!=0)require(result.getInt("pid")==imePid,"IME process unexpectedly restarted during consumer run");
        return result;
    }
    private JSONObject shown() throws Exception {
        long begin=SystemClock.uptimeMillis(),end=begin+10000;
        do{JSONObject value=witness();if(value!=null&&value.getBoolean("shown")){report.put("lastShownWaitMs",SystemClock.uptimeMillis()-begin);return value;}SystemClock.sleep(100);}while(SystemClock.uptimeMillis()<end);
        throw new AssertionError("Separate-process owned IME did not become visible");
    }
    private JSONObject view(JSONObject value,String role) throws Exception {
        JSONArray views=value.getJSONArray("views");
        for(int i=0;i<views.length();i++)if(views.getJSONObject(i).getString("role").equals(role))return views.getJSONObject(i);
        throw new AssertionError("Measured owned view unavailable: "+role);
    }
    private JSONObject measured(String role,String scale) throws Exception {
        long end=SystemClock.uptimeMillis()+8000;String previous=null;
        do{
            JSONObject w=witness();
            if(w!=null&&w.getBoolean("shown")){
                JSONArray rows=w.getJSONArray("views");
                for(int i=0;i<rows.length();i++){
                    JSONObject v=rows.getJSONObject(i);
                    if(v.getString("role").equals(role)&&v.getString("scale").equals(scale)&&v.getInt("width")>0&&v.getInt("visibleHeight")>0){
                        String identity=v.toString();if(identity.equals(previous))return v;previous=identity;break;
                    }
                }
            }
            SystemClock.sleep(100);
        }while(SystemClock.uptimeMillis()<end);
        throw new AssertionError("Stable owned measured view missing: "+role+" / "+scale);
    }
    private void emojiFit() throws Exception {
        JSONArray rows=witness().getJSONArray("views");int count=0;
        for(int i=0;i<rows.length();i++){
            JSONObject v=rows.getJSONObject(i);if(!v.getString("role").equals("emoji"))continue;
            count++;
            require(v.getInt("layoutHeight")>0&&v.getInt("layoutHeight")<=v.getInt("contentHeight"),"Emoji glyph clipped vertically");
            require(v.getDouble("lineWidth")<=v.getInt("contentWidth"),"Emoji glyph overlaps cell horizontally");
        }
        require(count>0,"No actual emoji cells qualified");
    }
    private void sameMetrics(JSONObject a,JSONObject b,String... fields) throws Exception {
        for(String key:fields)require(Math.abs(a.getDouble(key)-b.getDouble(key))<.01,"Unchanged channel/geometry drift: "+key);
    }
    private void expectText(String expected) throws Exception {
        long start=SystemClock.uptimeMillis(),end=start+2000;String observed;
        do{observed=ui(()->fixture.syntheticText());if(expected.equals(observed)){report.put("typedObserved",observed).put("typedObservedMs",SystemClock.uptimeMillis()-start);return;}SystemClock.sleep(50);}while(SystemClock.uptimeMillis()<end);
        report.put("typedObserved",observed).put("typedObservedMs",SystemClock.uptimeMillis()-start);
        throw new AssertionError("Actual synthetic insertion mismatch; no retry");
    }
    private void screenshot(String name) throws Exception {
        require(scene,"No foreign scene capture");
        boolean owned=false;for(AccessibilityNodeInfo n:nodes())if(n.isVisibleToUser()&&(APP.equals(String.valueOf(n.getPackageName()))||TEST.equals(String.valueOf(n.getPackageName()))))owned=true;
        require(owned,"No owned visible scene");android.graphics.Bitmap bitmap=ax.takeScreenshot();require(bitmap!=null,"Screenshot unavailable");
        String filename="driver-"+owner+"-"+name+".png";
        try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getFilesDir(),filename))){require(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out),"PNG failed");out.getFD().sync();}finally{bitmap.recycle();}
        images.put(filename);
    }
    private void settings() throws Exception {tap("Lip keyboard menu");tap("Keyboard preferences");find("Your keyboard.");}
    private void back() throws Exception {reveal("Back from keyboard preferences",true);tap("Back from keyboard preferences");find("Lip synthetic generation "+owner);shown();find("q");}
    @Override public void onStart(){
        int outcome=Activity.RESULT_CANCELED;
        try{
            require("ranchu".equals(android.os.Build.HARDWARE)&&TEST.equals(getContext().getPackageName())&&TEST.equals(getTargetContext().getPackageName()),"Self test package on owned emulator only");
            report.put("driverVersion",8).put("owner",owner).put("driverPid",android.os.Process.myPid()).put("variant",variant).put("mode",mode).put("scope","actual separate-process IME/settings; synthetic editor, no phone/TalkBack/full9.2");
            ax=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);android.accessibilityservice.AccessibilityServiceInfo config=ax.getServiceInfo();config.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;ax.setServiceInfo(config);
            AtomicReference<KeyboardSizingEditorActivity> created=new AtomicReference<>();
            Application app=(Application)getTargetContext().getApplicationContext();
            Application.ActivityLifecycleCallbacks lifecycle=new Application.ActivityLifecycleCallbacks(){
                @Override public void onActivityCreated(Activity a,Bundle b){if(a instanceof KeyboardSizingEditorActivity&&owner.equals(a.getIntent().getStringExtra("fixtureGeneration")))created.set((KeyboardSizingEditorActivity)a);}
                @Override public void onActivityStarted(Activity a){} @Override public void onActivityResumed(Activity a){} @Override public void onActivityPaused(Activity a){}
                @Override public void onActivityStopped(Activity a){} @Override public void onActivitySaveInstanceState(Activity a,Bundle b){} @Override public void onActivityDestroyed(Activity a){}
            };
            ui(()->{app.registerActivityLifecycleCallbacks(lifecycle);return null;});
            getTargetContext().startActivity(new Intent(getTargetContext(),KeyboardSizingEditorActivity.class).putExtra("fixtureGeneration",owner).putExtra("landscapeFixture",!"portrait".equals(variant)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
            find("Lip synthetic generation "+owner);fixture=created.get();require(fixture!=null,"Fresh owned editor instance");scene=true;
            tap("Synthetic ordinary editor");JSONObject initial=shown();imePid=initial.getInt("pid");report.put("initial",initial);find("q");
            require(initial.getInt("orientation")==("portrait".equals(variant)?1:2),"Actual orientation precondition");
            require(Math.abs(initial.getDouble("fontScale")-("portrait".equals(variant)?1.0:2.0))<.005,"Actual font-scale precondition");
            require(initial.getInt("windowWidth")>0&&initial.getInt("windowHeight")>0,"Actual window precondition");
            require(initial.getJSONObject("renderedSettings").getString("height").equals("portrait".equals(variant)?"STANDARD":"COMPACT"),"Actual height preference precondition");
            screenshot("startup");
            if(mode.equals("sizing")){
                JSONObject base=measured("q","SYSTEM");report.put("baseQ",base);
                tap("Emoji keyboard");find("Offline emoji results");JSONObject baseEmoji=measured("emoji","SYSTEM");report.put("baseEmoji",baseEmoji);emojiFit();tap("Return from emoji to letters");
                stage="key-only-large";settings();reveal("Key label size: Extra large",false);tap("Key label size: Extra large");back();
                JSONObject large=measured("q","EXTRA_LARGE");report.put("largeQ",large);
                require(large.getDouble("fontPixels")>=base.getDouble("fontPixels"),"Large key did not grow/saturate");
                if(variant.equals("portrait"))require(large.getDouble("fontPixels")>base.getDouble("fontPixels"),"Portrait key did not grow");
                sameMetrics(base,large,"width","height","x","y");
                require(large.getInt("layoutHeight")>0&&large.getInt("layoutHeight")<=large.getInt("contentHeight"),"Key clipped vertically");
                require(large.getDouble("lineWidth")<=large.getInt("contentWidth"),"Key clipped horizontally");
                tap("Emoji keyboard");find("Offline emoji results");JSONObject unchangedEmoji=measured("emoji","SYSTEM");report.put("emojiAfterKeyOnly",unchangedEmoji);
                sameMetrics(baseEmoji,unchangedEmoji,"fontPixels","width","height");tap("Return from emoji to letters");
                stage="emoji-only-large";settings();reveal("Emoji preview size: Extra large",false);tap("Emoji preview size: Extra large");screenshot("large-settings");back();
                JSONObject unchangedKey=measured("q","EXTRA_LARGE");report.put("keyAfterEmojiOnly",unchangedKey);sameMetrics(large,unchangedKey,"fontPixels","width","height","x","y");
                stage="typed-q";tap("q");expectText("q");screenshot("large-keys");
                stage="large-emoji";tap("Emoji keyboard");find("Offline emoji results");JSONObject bigEmoji=measured("emoji","EXTRA_LARGE");report.put("largeEmoji",bigEmoji);emojiFit();screenshot("large-emoji");
                require(bigEmoji.getInt("visibleHeight")>=bigEmoji.getInt("height"),"Large emoji row clipped by viewport");
                stage="key-only-small";tap("Return from emoji to letters");settings();reveal("Key label size: Small",false);tap("Key label size: Small");back();
                JSONObject small=measured("q","SMALL");report.put("smallQ",small);require(small.getDouble("fontPixels")<=base.getDouble("fontPixels"),"Small key did not shrink/saturate");sameMetrics(base,small,"width","height","x","y");
                tap("Emoji keyboard");find("Offline emoji results");JSONObject unchangedLargeEmoji=measured("emoji","EXTRA_LARGE");report.put("emojiAfterSmallKeyOnly",unchangedLargeEmoji);sameMetrics(bigEmoji,unchangedLargeEmoji,"fontPixels","width","height");tap("Return from emoji to letters");
                stage="emoji-only-small";settings();reveal("Emoji preview size: Small",false);tap("Emoji preview size: Small");back();JSONObject unchangedSmallKey=measured("q","SMALL");sameMetrics(small,unchangedSmallKey,"fontPixels","width","height","x","y");
                tap("Emoji keyboard");find("Offline emoji results");JSONObject smallEmoji=measured("emoji","SMALL");report.put("smallEmoji",smallEmoji);emojiFit();screenshot("small-emoji");
                require(bigEmoji.getDouble("fontPixels")>smallEmoji.getDouble("fontPixels")&&bigEmoji.getInt("height")>smallEmoji.getInt("height"),"Independent emoji size had no effect");
                stage="persisted-selection";tap("Return from emoji to letters");settings();reveal("Key label size: Small",false);require(find("Key label size: Small").isChecked(),"Key selection not persisted");reveal("Emoji preview size: Small",false);require(find("Emoji preview size: Small").isChecked(),"Emoji selection not persisted");back();
                expectText("q");report.put("independentChannelsObserved",true).put("sizingTaskCompleted",true);
            }
            if(mode.equals("tones")){
                stage="tone-size-settings";settings();reveal("Emoji preview size: Extra large",false);tap("Emoji preview size: Extra large");back();
                stage="tone-open";tap("Emoji keyboard");find("Offline emoji results");tap("Show emoji category People & Body");find("Insert emoji waving hand");press("Insert emoji waving hand",700);
                find("Cancel emoji skin tone choices");JSONObject firstTone=measured("tone","EXTRA_LARGE");report.put("firstTone",firstTone);screenshot("tone-chooser");
                require(firstTone.getInt("visibleHeight")>=firstTone.getInt("height"),"Tone first row clipped by chooser viewport");
                JSONArray tones=witness().getJSONArray("views");int qualified=0;
                for(int i=0;i<tones.length();i++){JSONObject v=tones.getJSONObject(i);if(!v.getString("role").equals("tone"))continue;qualified++;
                    require(v.getInt("layoutHeight")<=v.getInt("contentHeight")&&v.getDouble("lineWidth")<=v.getInt("contentWidth"),"Tone glyph does not fit");}
                require(qualified>0,"No tone cells qualified");tap("Cancel emoji skin tone choices");expectText("");
                press("Insert emoji waving hand",700);find("Choose emoji waving hand: light skin tone");tap("Choose emoji waving hand: light skin tone");expectText("👋🏻");report.put("toneCancelAndUnicodeInsertion",true);
            }
            report.put("passed",true);outcome=Activity.RESULT_OK;
        }catch(Throwable t){try{report.put("passed",false).put("failure",stage+": "+android.util.Log.getStackTraceString(t));if(scene)screenshot("failure");}catch(Exception ignored){}}
        finally{if(fixture!=null)try{ui(()->{fixture.finish();return null;});}catch(Throwable ignored){}}
        try{
            report.put("screenshots",images);
            String name="driver-"+owner+"-result.json";File file=new File(getTargetContext().getFilesDir(),name);require(file.createNewFile(),"Result must not overwrite");
            try(FileOutputStream out=new FileOutputStream(file)){out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        }catch(Exception e){outcome=Activity.RESULT_CANCELED;}
        Bundle result=new Bundle();result.putString("driverResult",report.toString());finish(outcome,result);
    }
}
