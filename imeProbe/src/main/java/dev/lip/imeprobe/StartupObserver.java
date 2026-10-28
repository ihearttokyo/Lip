package dev.lip.imeprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;

/** Isolated observer UID; launching this instrumentation never stops the IME or fixture UID. */
public final class StartupObserver extends Instrumentation {
    private UiAutomation automation;
    private String launch, expected;
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        launch=args==null?"fresh":args.getString("launch","fresh");
        expected=args==null?"a":args.getString("expected","a");
        start();
    }
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private void walk(AccessibilityNodeInfo node,List<AccessibilityNodeInfo> out,int depth){
        if(node==null||depth>32||out.size()>4096)return;
        if("dev.lip.android".contentEquals(node.getPackageName()==null?"":node.getPackageName()) || "dev.lip.android.test".contentEquals(node.getPackageName()==null?"":node.getPackageName()))out.add(node);
        for(int i=0;i<node.getChildCount();i++)walk(node.getChild(i),out,depth+1);
    }
    private List<AccessibilityNodeInfo> nodes(){
        List<AccessibilityNodeInfo> out=new ArrayList<>();
        automation.getWindows().forEach(window->walk(window.getRoot(),out,0));return out;
    }
    private AccessibilityNodeInfo find(String description,long timeout){
        long end=SystemClock.uptimeMillis()+timeout;
        do{
            List<AccessibilityNodeInfo> matches=new ArrayList<>();
            for(AccessibilityNodeInfo node:nodes())if(description.contentEquals(node.getContentDescription()==null?"":node.getContentDescription()))matches.add(node);
            if(matches.size()==1)return matches.get(0);
            SystemClock.sleep(60);
        }while(SystemClock.uptimeMillis()<end);
        throw new AssertionError("Expected one native control: "+description);
    }
    private void touch(AccessibilityNodeInfo node){
        Rect bounds=new Rect();node.getBoundsInScreen(bounds);
        require(node.isVisibleToUser()&&node.isEnabled()&&!bounds.isEmpty(),"Touch target not visible/enabled");
        long down=SystemClock.uptimeMillis();
        for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP}){
            MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,bounds.exactCenterX(),bounds.exactCenterY(),0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            try{require(automation.injectInputEvent(event,true),"Native touch refused");}finally{event.recycle();}
        }
    }
    @Override public void onStart(){
        Bundle report=new Bundle();int result=Activity.RESULT_CANCELED;
        try{
            require("ranchu".equals(android.os.Build.HARDWARE)&&"dev.lip.imeprobe".equals(getTargetContext().getPackageName()),"Owned emulator observer only");
            require("fresh".equals(launch)||"existing".equals(launch),"Unknown launch mode");
            automation=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            android.accessibilityservice.AccessibilityServiceInfo service=automation.getServiceInfo();
            service.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;automation.setServiceInfo(service);
            long start=SystemClock.uptimeMillis();
            if("fresh".equals(launch)){
                String generation=java.util.UUID.randomUUID().toString();
                getTargetContext().startActivity(new Intent().setClassName("dev.lip.android.test","dev.lip.ImeFixtureActivity").putExtra("fixtureGeneration",generation).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
                find("Lip synthetic generation "+generation,5000); // Never borrow the previous fixture incarnation.
            }
            AccessibilityNodeInfo editor=find("Synthetic ordinary editor",5000);
            report.putString("before-editor-text",String.valueOf(editor.getText()));
            long touchAt=SystemClock.uptimeMillis();touch(editor); // Exactly one editor touch; no showSoftInput/restartInput call.
            find("Record with the selected local Bark model",10000);
            find("a",1000);
            report.putLong("observed-touch-to-key-tree-ms",SystemClock.uptimeMillis()-touchAt);
            automation.waitForIdle(200,5000); // Visibility is not coordinate readiness during the slide-in.
            AccessibilityNodeInfo key=find("a",1000); // Reacquire bounds after synchronization; still one key touch.
            report.putLong("observed-touch-to-key-ready-ms",SystemClock.uptimeMillis()-touchAt);
            touch(key);
            long deadline=SystemClock.uptimeMillis()+2500;String actual;
            do{actual=String.valueOf(find("Synthetic ordinary editor",500).getText());if(expected.equals(actual))break;SystemClock.sleep(60);}while(SystemClock.uptimeMillis()<deadline);
            require(expected.equals(actual),"First key text mismatch; expected="+expected+" actual="+actual);
            report.putString("editor-text",actual);report.putLong("probe-ms",SystemClock.uptimeMillis()-start);
            report.putBoolean("FIRST-EDITOR-TOUCH-ONE-A-KEY",true);
            result=Activity.RESULT_OK;report.putString("result","PASS isolated observer/native first-touch/first-key case only; no general startup reliability claim");
        }catch(Throwable error){report.putString("failure",error.toString());}
        finish(result,report);
    }
}
