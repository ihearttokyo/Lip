package dev.lip;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.pm.ApplicationInfo;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;
import java.util.UUID;

/** Real two-contact injection only. No click fallback, receiver reads, URI use or production seam. */
public final class LocalStickerGestureRunner extends Instrumentation {
    private static final String APP="dev.lip.android",RECEIVER="dev.lip.android.test";
    private Bundle args;private UiAutomation automation;private final JSONArray events=new JSONArray();
    private int width,height,x1,y1,x2,y2;private long downTime,lastTime;private int cleanupCount=1;private boolean downAttempted;
    private void must(boolean okay){if(!okay)throw new IllegalStateException("Owned sticker gesture precondition failed");}
    @Override public void onCreate(Bundle b){super.onCreate(b);args=b==null?new Bundle():b;start();}
    private int number(String key){
        Object raw=args.get(key);if(raw instanceof Integer)return (Integer)raw;
        if(raw instanceof String&&((String)raw).matches("[0-9]{1,5}"))return Integer.parseInt((String)raw);
        throw new IllegalStateException("Explicit bounded integer argument required");
    }
    private void dimensions(){
        Display display=getTargetContext().getSystemService(WindowManager.class).getDefaultDisplay();DisplayMetrics m=new DisplayMetrics();display.getRealMetrics(m);
        must(display.getRotation()==Surface.ROTATION_0&&m.widthPixels==width&&m.heightPixels==height);
    }
    // Exact null-safe CharSequence comparison (TextUtils.equals semantics), kept
    // pure so null-node metadata controls do not need an Android runtime.
    static boolean same(CharSequence expected,CharSequence actual){
        if(expected==actual)return true;
        if(expected==null||actual==null||expected.length()!=actual.length())return false;
        for(int i=0;i<expected.length();i++)if(expected.charAt(i)!=actual.charAt(i))return false;
        return true;
    }
    private boolean hasReceiver(AccessibilityNodeInfo n,int depth){
        if(n==null||depth>32)return false;
        if(same(RECEIVER,n.getPackageName())&&same("Owned local sticker receiver editor",n.getContentDescription()))
            return n.isVisibleToUser()&&n.isEditable()&&!n.isPassword();
        for(int i=0;i<n.getChildCount();i++)if(hasReceiver(n.getChild(i),depth+1))return true;return false;
    }
    private boolean hasText(AccessibilityNodeInfo n,String expected,int depth){
        if(n==null||depth>32)return false;
        if(same(RECEIVER,n.getPackageName())&&same(expected,n.getText())&&n.isVisibleToUser())return true;
        for(int i=0;i<n.getChildCount();i++)if(hasText(n.getChild(i),expected,depth+1))return true;return false;
    }
    private void tile(AccessibilityNodeInfo n,String label,int x,int y,int depth,int[] matches){
        if(n==null||depth>32)return;
        if(same(APP,n.getPackageName())&&same(label,n.getContentDescription())&&n.isVisibleToUser()){
            Rect bounds=new Rect();n.getBoundsInScreen(bounds);must(n.isClickable()&&n.isEnabled()&&bounds.contains(x,y));matches[0]++;
        }
        for(int i=0;i<n.getChildCount();i++)tile(n.getChild(i),label,x,y,depth+1,matches);
    }
    private void scene(){
        dimensions();must(automation.clearCache());List<AccessibilityWindowInfo> windows=automation.getWindows();int editors=0,keyboards=0;
        for(AccessibilityWindowInfo window:windows){
            AccessibilityNodeInfo root=window.getRoot();if(root==null)continue;
            if(window.getType()==AccessibilityWindowInfo.TYPE_APPLICATION&&same(RECEIVER,root.getPackageName())&&hasReceiver(root,0)&&hasText(root,"Owned local sticker receiver · accept",0))editors++;
            if(window.getType()==AccessibilityWindowInfo.TYPE_INPUT_METHOD&&same(APP,root.getPackageName())){
                Rect bounds=new Rect();window.getBoundsInScreen(bounds);must(bounds.contains(x1,y1)&&bounds.contains(x2,y2));
                int[] first={0},second={0};tile(root,"Request original sticker Synthetic png",x1,y1,0,first);tile(root,"Request original sticker Synthetic webp",x2,y2,0,second);
                must(first[0]==1&&second[0]==1);keyboards++;
            }
        }
        must(editors==1&&keyboards==1);
    }
    private boolean emit(int action,int count,boolean cleanup){
        cleanupCount=count;long time=Math.max(SystemClock.uptimeMillis(),lastTime+1);lastTime=time;
        MotionEvent.PointerProperties[] properties=new MotionEvent.PointerProperties[count];MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[count];
        JSONArray pointers=new JSONArray();
        for(int i=0;i<count;i++){
            properties[i]=new MotionEvent.PointerProperties();properties[i].id=i;properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
            coords[i]=new MotionEvent.PointerCoords();coords[i].x=i==0?x1:x2;coords[i].y=i==0?y1:y2;coords[i].size=1;
            coords[i].pressure=(action&MotionEvent.ACTION_MASK)==MotionEvent.ACTION_CANCEL||(action&MotionEvent.ACTION_MASK)==MotionEvent.ACTION_UP||((action&MotionEvent.ACTION_MASK)==MotionEvent.ACTION_POINTER_UP&&i==1)?0:1;
            try{pointers.put(new JSONObject().put("id",i).put("x",coords[i].x).put("y",coords[i].y).put("pressure",coords[i].pressure));}catch(Exception ignored){}
        }
        JSONObject row=new JSONObject();try{row.put("action",action).put("pointerCount",count).put("pointers",pointers).put("source",InputDevice.SOURCE_TOUCHSCREEN).put("downTime",downTime).put("eventTime",time).put("cleanup",cleanup);}catch(Exception ignored){}
        events.put(row);MotionEvent event=MotionEvent.obtain(downTime,time,action,count,properties,coords,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        try{boolean accepted=automation.injectInputEvent(event,true);try{row.put("accepted",accepted);}catch(Exception ignored){}return accepted;}
        catch(RuntimeException e){try{row.put("accepted",false).put("errorType",e.getClass().getSimpleName());}catch(Exception ignored){}throw e;}
        finally{event.recycle();}
    }
    @Override public void onStart(){
        JSONObject report=new JSONObject();boolean completed=false,cleanupConfirmed=false;String stage="guard";
        try{
            must(APP.equals(getTargetContext().getPackageName())&&(getTargetContext().getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0);
            must(("ranchu".equals(Build.HARDWARE)||"goldfish".equals(Build.HARDWARE))&&Build.PRODUCT.contains("sdk")&&Build.VERSION.SDK_INT>=36);
            String owner=args.getString("owner");must(owner!=null&&UUID.fromString(owner).toString().equals(owner));must("two-pointer-cancel".equals(args.getString("mode"))&&RECEIVER.equals(args.getString("receiverPackage")));
            width=number("width");height=number("height");x1=number("x1");y1=number("y1");x2=number("x2");y2=number("y2");
            must(width==1080&&height==2400&&x1>0&&x1<width-1&&x2>0&&x2<width-1&&y1>0&&y1<height-1&&y2>0&&y2<height-1&&(x1!=x2||y1!=y2));
            int receiverUid=getTargetContext().getPackageManager().getApplicationInfo(RECEIVER,0).uid;must(receiverUid!=android.os.Process.myUid());
            report.put("owner",owner).put("receiverPackage",RECEIVER).put("receiverUid",receiverUid).put("imeUid",android.os.Process.myUid()).put("width",width).put("height",height);
            int waitForSceneMs=args.containsKey("waitForSceneMs")?number("waitForSceneMs"):0;must(waitForSceneMs>=0&&waitForSceneMs<=60000);report.put("waitRequestedMs",waitForSceneMs);
            automation=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);AccessibilityServiceInfo service=automation.getServiceInfo();service.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;automation.setServiceInfo(service);
            stage="initial-scene";long waitStarted=SystemClock.uptimeMillis(),deadline=waitStarted+waitForSceneMs;
            // Read-only rendezvous before FIRST contact, so parent can restore the
            // owned receiver/panel after normal instrumentation process startup.
            // No action/retry/fallback is injected while readiness is unavailable.
            try{
                while(true){
                    try{scene();break;}catch(IllegalStateException unavailable){
                        long remaining=deadline-SystemClock.uptimeMillis();if(remaining<=0)throw unavailable;
                        SystemClock.sleep(Math.min(250,remaining));
                    }
                }
            }finally{report.put("waitObservedMs",SystemClock.uptimeMillis()-waitStarted);}
            downTime=SystemClock.uptimeMillis();lastTime=downTime-1;
            stage="primary-down";downAttempted=true;must(emit(MotionEvent.ACTION_DOWN,1,false));SystemClock.sleep(16);
            stage="secondary-down";scene();must(emit(MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,false));SystemClock.sleep(16);
            stage="secondary-up";scene();must(emit(MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,false));SystemClock.sleep(16);
            stage="primary-up";scene();must(emit(MotionEvent.ACTION_UP,1,false));completed=true;
        }catch(Exception e){try{report.put("failedStage",stage).put("errorType",e.getClass().getSimpleName());}catch(Exception ignored){}}
        finally{if(!completed&&downAttempted&&automation!=null)try{cleanupConfirmed=emit(MotionEvent.ACTION_CANCEL,cleanupCount,true);}catch(Exception ignored){}}
        try{report.put("completedSequence",completed).put("cleanupCancelConfirmed",cleanupConfirmed).put("events",events).put("scope","actual injected two-contact sequence only; receiver zero-attempt/fresh-single success are separate parent checks");}catch(Exception ignored){}
        Bundle result=new Bundle();result.putString(REPORT_KEY_STREAMRESULT,"\nLOCAL_STICKER_GESTURE_RESULT "+report+"\n");finish(completed?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
}
