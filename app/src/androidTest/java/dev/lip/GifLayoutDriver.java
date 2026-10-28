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
public final class GifLayoutDriver extends Instrumentation {
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
            require(java.util.Arrays.asList("gifs").contains(mode),"Unknown mode");
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
        if(java.util.Arrays.asList("q","Emoji keyboard","Lip keyboard menu","Return from emoji to letters","Cancel emoji skin tone choices","Show local GIF catalog","Search local GIF catalog","Return from GIFs to letters","Cancel pending GIF attachment").contains(label)){
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
    private JSONObject readyGifs() throws Exception {
        long end=SystemClock.uptimeMillis()+8000;String previous=null;
        do{
            JSONObject w=witness();
            if(w!=null&&w.getBoolean("shown")&&w.getString("page").equals("GIF")){
                JSONArray rows=w.getJSONArray("views");int titles=0,tiles=0,status=0,credit=0;boolean laid=true;
                for(int i=0;i<rows.length();i++){JSONObject v=rows.getJSONObject(i);String role=v.getString("role");
                    if(role.equals("gifTitle"))titles++;if(role.equals("gifTile"))tiles++;if(role.equals("gifStatus"))status++;if(role.equals("gifCredit"))credit++;
                    if(java.util.Arrays.asList("gifTitle","gifStatus","gifCredit").contains(role)&&v.getInt("layoutHeight")<=0)laid=false;}
                String identity=rows.toString();
                if(titles==12&&tiles==12&&status==1&&credit==1&&laid&&identity.equals(previous))return w;
                previous=identity;
            }
            SystemClock.sleep(100);
        }while(SystemClock.uptimeMillis()<end);
        throw new AssertionError("Owned GIF catalog did not finish bounded stable layout");
    }
    private void scanGifs() throws Exception {
        boolean[] seen=new boolean[12];boolean statusSeen=false,creditSeen=false;JSONArray frames=new JSONArray();report.put("gifFrames",frames);
        for(int sweep=0;sweep<=32;sweep++){
            JSONObject w=readyGifs();JSONArray rows=w.getJSONArray("views");frames.put(rows);int tiles=0;
            for(int i=0;i<rows.length();i++){
                JSONObject v=rows.getJSONObject(i);String role=v.getString("role");
                if(role.equals("gifTile")){tiles++;require(!v.getBoolean("enabled"),"Ordinary editor GIF tile must remain browse-only");}
                if(!java.util.Arrays.asList("gifTitle","gifStatus","gifCredit").contains(role))continue;
                require(v.getInt("layoutHeight")>0&&v.getInt("layoutHeight")<=v.getInt("contentHeight"),"GIF text vertically clipped: "+role+"/"+v.getInt("ordinal"));
                require(v.getDouble("lineWidth")<=v.getInt("contentWidth"),"GIF text horizontally clipped");
                require(v.getInt("ellipses")==0,"GIF text ellipsized instead of reflowed");
                if(v.getBoolean("hasVisibleRect")&&v.getInt("visibleHeight")>=v.getInt("height")&&v.getInt("visibleWidth")>=v.getInt("width")&&v.getInt("height")>0){
                    if(role.equals("gifTitle")){int index=v.getInt("ordinal");require(index>=0&&index<12,"Unexpected title identity");seen[index]=true;}
                    if(role.equals("gifStatus"))statusSeen=true;if(role.equals("gifCredit"))creditSeen=true;
                }
            }
            require(tiles==12,"Exact fixed licensed catalog required");
            boolean all=statusSeen&&creditSeen;for(boolean v:seen)all&=v;
            if(all){report.put("all12TitlesAndStatusAndCreditSeen",true).put("scrolls",sweep);return;}
            require(sweep<32,"Bounded native scrolling did not reveal all text");scrollGifs();
        }
    }
    private void scrollGifs() throws Exception {
        Rect point=null;long end=SystemClock.uptimeMillis()+8000;
        do{
            List<AccessibilityNodeInfo> found=matching("Local GIF results");require(found.size()==1,"Fresh unique owned GIF scroll required");
            AccessibilityNodeInfo n=found.get(0);require(n.refresh()&&n.isEnabled()&&n.isVisibleToUser()&&APP.equals(String.valueOf(n.getPackageName()))&&"Local GIF results".contentEquals(n.getContentDescription()),"GIF scroll ownership");
            Rect axRect=new Rect();n.getBoundsInScreen(axRect);JSONObject v=view(witness(),"gifScroll");Rect nativeRect=new Rect(v.getInt("x"),v.getInt("y"),v.getInt("x")+v.getInt("width"),v.getInt("y")+v.getInt("height"));
            if(axRect.intersect(nativeRect)&&axRect.height()>40&&axRect.width()>20){point=axRect;break;}SystemClock.sleep(100);
        }while(SystemClock.uptimeMillis()<end);
        require(point!=null,"No shared AX/native GIF scroll viewport; no gesture");
        float x=point.exactCenterX(),start=point.top+point.height()*.8f,finish=point.top+point.height()*.3f;long down=SystemClock.uptimeMillis();
        for(int i=0;i<=10;i++){
            int action=i==0?MotionEvent.ACTION_DOWN:i==10?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE;
            if(i==10)SystemClock.sleep(150);else if(i>0)SystemClock.sleep(30);
            float y=start+(finish-start)*Math.min(i,9)/9f;
            MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);e.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            try{require(ax.injectInputEvent(e,true),"Owned GIF scroll input refused");}finally{e.recycle();}
        }
    }
    @Override public void onStart(){
        int outcome=Activity.RESULT_CANCELED;
        try{
            require("ranchu".equals(android.os.Build.HARDWARE)&&TEST.equals(getContext().getPackageName())&&TEST.equals(getTargetContext().getPackageName()),"Self test package on owned emulator only");
            report.put("driverVersion",2).put("owner",owner).put("driverPid",android.os.Process.myPid()).put("variant",variant).put("mode",mode).put("scope","actual separate-process GIF browse-only IME; synthetic editor, no attachment/phone/TalkBack/full9.2");
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
            stage="open-gifs";tap("Show local GIF catalog");find("Search local GIF catalog");
            JSONObject ready=readyGifs();report.put("gifInitial",ready);require(!ready.getBoolean("outerToolsVisible"),"Duplicate outer toolbar must stay hidden in GIF browser");screenshot("gif-top");
            stage="text-fit-and-scroll";scanGifs();screenshot("gif-bottom");
            stage="return-and-type";tap("Return from GIFs to letters");require(shown().getBoolean("outerToolsVisible"),"Normal toolbar must return with letters");find("q");tap("q");expectText("q");
            report.put("browseOnlyTextUnchanged",true);
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
