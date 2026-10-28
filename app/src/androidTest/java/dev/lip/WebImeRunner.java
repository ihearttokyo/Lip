package dev.lip;

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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.json.JSONObject;

/** Actual Chromium editor/native touches. DOM scripts only set up fixtures and observe results. */
public final class WebImeRunner extends Instrumentation{
    private UiAutomation automation;
    private WebImeFixtureActivity fixture;
    private android.content.ClipboardManager clipboard;
    private android.content.ClipData originalClipboard;
    private boolean clipboardCaptured;
    private boolean metadataOnly, privacyOnly, cursorOnly;
    @Override public void onCreate(Bundle args){super.onCreate(args);metadataOnly=args!=null&&"true".equals(args.getString("metadataOnly"));privacyOnly=args!=null&&"true".equals(args.getString("privacyOnly"));cursorOnly=args!=null&&"true".equals(args.getString("cursorOnly"));start();}
    private static void require(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
    private <T>T ui(Supplier<T> action){AtomicReference<T> out=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();runOnMainSync(()->{try{out.set(action.get());}catch(Throwable e){error.set(e);}});if(error.get()!=null)throw new AssertionError("Synthetic WebView UI operation failed",error.get());return out.get();}
    private String eval(String script)throws Exception{
        AtomicReference<String> out=new AtomicReference<>();CountDownLatch ready=new CountDownLatch(1);
        ui(()->{fixture.web.evaluateJavascript(script,value->{out.set(value);ready.countDown();});return null;});
        require(ready.await(5,TimeUnit.SECONDS),"Local page evaluation timeout");return out.get();
    }
    private JSONObject state()throws Exception{return new JSONObject(eval("fixture.inspect()"));}
    private void collect(AccessibilityNodeInfo n,List<AccessibilityNodeInfo> out,int depth){if(n==null||depth>32||out.size()>4096)return;out.add(n);for(int i=0;i<n.getChildCount();i++)collect(n.getChild(i),out,depth+1);}
    private List<AccessibilityNodeInfo> nodes(){List<AccessibilityNodeInfo> out=new ArrayList<>();automation.getWindows().forEach(w->collect(w.getRoot(),out,0));return out;}
    private AccessibilityNodeInfo key(String description){
        long end=SystemClock.uptimeMillis()+5000;
        do{List<AccessibilityNodeInfo> found=new ArrayList<>();for(AccessibilityNodeInfo n:nodes())if("dev.lip.android".contentEquals(n.getPackageName()==null?"":n.getPackageName())&&description.contentEquals(n.getContentDescription()==null?"":n.getContentDescription()))found.add(n);if(found.size()==1)return found.get(0);SystemClock.sleep(60);}while(SystemClock.uptimeMillis()<end);
        throw new AssertionError("Expected one Lip WebView keyboard control: "+description);
    }
    private void touch(AccessibilityNodeInfo node){
        require(node.isVisibleToUser()&&node.isEnabled(),"Native touch target unavailable");Rect r=new Rect();node.getBoundsInScreen(r);require(!r.isEmpty(),"Empty native bounds");long now=SystemClock.uptimeMillis();
        for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP}){MotionEvent e=MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,r.exactCenterX(),r.exactCenterY(),0);e.setSource(InputDevice.SOURCE_TOUCHSCREEN);try{require(automation.injectInputEvent(e,true),"Native WebView touch refused");}finally{e.recycle();}}
        SystemClock.sleep(90);
    }
    private void tap(String description)throws Exception{
        automation.waitForIdle(300,5000);AccessibilityNodeInfo n=key(description);long end=SystemClock.uptimeMillis()+5000;while(!n.isEnabled()&&SystemClock.uptimeMillis()<end){SystemClock.sleep(60);n=key(description);}touch(n);
    }
    private void spell(String value)throws Exception{for(char c:value.toCharArray())tap(String.valueOf(c));}
    private void editorTouch()throws Exception{
        automation.waitForIdle(300,5000);long end=SystemClock.uptimeMillis()+5000;
        do{List<AccessibilityNodeInfo> found=new ArrayList<>();for(AccessibilityNodeInfo n:nodes())if("dev.lip.android.test".contentEquals(n.getPackageName()==null?"":n.getPackageName())&&n.isEditable()&&n.isVisibleToUser())found.add(n);if(found.size()==1){touch(found.get(0));return;}SystemClock.sleep(60);}while(SystemClock.uptimeMillis()<end);
        throw new AssertionError("Expected unique real Chromium editable node");
    }
    private void field(String kind,String value,int at,boolean automatic)throws Exception{
        String identity=UUID.randomUUID().toString();
        String result=eval("fixture.reset("+JSONObject.quote(kind)+","+JSONObject.quote(value)+","+at+","+at+","+automatic+","+JSONObject.quote(identity)+")");
        require(JSONObject.quote(identity).equals(result)&&identity.equals(state().getString("identity")),"Stale DOM fixture identity");
        editorTouch();eval("fixture.place("+at+","+at+")");SystemClock.sleep(250);key("Record with the selected local Bark model");
    }
    private void cursorDrag(float units)throws Exception{
        automation.waitForIdle(300,5000);Rect r=new Rect();key("Space").getBoundsInScreen(r);float q=ui(()->Math.max((int)(14*fixture.getResources().getDisplayMetrics().density),android.view.ViewConfiguration.get(fixture).getScaledTouchSlop()+1));
        long down=SystemClock.uptimeMillis();float x=r.exactCenterX(),y=r.exactCenterY();MotionEvent e=MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,x,y,0);e.setSource(InputDevice.SOURCE_TOUCHSCREEN);require(automation.injectInputEvent(e,false),"Web cursor DOWN refused");e.recycle();
        for(float step:new float[]{Math.signum(units)*1.1f,units}){e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_MOVE,x+step*q,y,0);e.setSource(InputDevice.SOURCE_TOUCHSCREEN);require(e.getEventTime()-down<android.view.ViewConfiguration.getLongPressTimeout(),"Web cursor fixture movement missed longpress window");require(automation.injectInputEvent(e,false),"Web cursor MOVE refused");e.recycle();SystemClock.sleep(25);}
        e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,x+units*q,y,0);e.setSource(InputDevice.SOURCE_TOUCHSCREEN);require(automation.injectInputEvent(e,true),"Web cursor UP refused");e.recycle();SystemClock.sleep(160);
    }
    private void cursorAt(int expected)throws Exception{
        long end=SystemClock.uptimeMillis()+5000;do{JSONObject s=state();if(s.getInt("start")==expected&&s.getInt("end")==expected)return;SystemClock.sleep(40);}while(SystemClock.uptimeMillis()<end);
        throw new AssertionError("Synthetic actual Chromium cursor mismatch; expected="+expected+" observed="+state().getInt("start"));
    }
    private void expect(String value)throws Exception{
        long end=SystemClock.uptimeMillis()+5000;
        do{if(value.equals(state().getString("text")))return;SystemClock.sleep(60);}while(SystemClock.uptimeMillis()<end);
        throw new AssertionError("Synthetic Chromium text mismatch; expected="+value+" actual="+state().getString("text"));
    }
    private boolean choicesVisible(){for(AccessibilityNodeInfo n:nodes())if(n.isVisibleToUser()&&"dev.lip.android".contentEquals(n.getPackageName()==null?"":n.getPackageName())&&String.valueOf(n.getContentDescription()).startsWith("Choose typing suggestion "))return true;return false;}
    @Override public void onStart(){
        Bundle report=new Bundle();int outcome=Activity.RESULT_CANCELED;
        try{
            require("ranchu".equals(android.os.Build.HARDWARE)&&"dev.lip.android.test".equals(getTargetContext().getPackageName()),"Owned emulator/test UID only");
            String generation=UUID.randomUUID().toString();fixture=(WebImeFixtureActivity)startActivitySync(new Intent().setClassName("dev.lip.android.test",WebImeFixtureActivity.class.getName()).putExtra("fixtureGeneration",generation).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            automation=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);android.accessibilityservice.AccessibilityServiceInfo service=automation.getServiceInfo();service.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;automation.setServiceInfo(service);
            long deadline=SystemClock.uptimeMillis()+10000;while(!ui(()->fixture.ready)&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(60);require(ui(()->fixture.ready),"Local WebView page not ready");
            long focusDeadline=SystemClock.uptimeMillis()+5000;while(!ui(()->fixture.hasWindowFocus())&&SystemClock.uptimeMillis()<focusDeadline)SystemClock.sleep(60);
            require(ui(()->fixture.hasWindowFocus()),"Synthetic WebView must own foreground focus before clipboard observation");
            require(nodes().stream().anyMatch(n->("Lip web generation "+generation).contentEquals(n.getContentDescription()==null?"":n.getContentDescription())),"WebView generation marker not active");
            android.content.pm.PackageInfo provider=android.webkit.WebView.getCurrentWebViewPackage();report.putString("provider",provider==null?"unavailable":provider.packageName+" "+provider.versionName);
            clipboard=fixture.getSystemService(android.content.ClipboardManager.class);originalClipboard=ui(()->clipboard.getPrimaryClip());clipboardCaptured=true;
            if(cursorOnly){
                field("textarea","abcd",4,true);int reads=fixture.surroundingCalls;cursorDrag(-2.1f);expect("abcd");cursorAt(2);require(fixture.surroundingCalls==reads,"Web cursor queried surrounding context");report.putBoolean("WEB-CURSOR-TEXTAREA-NO-CONTEXT-READ",true);
                field("contenteditable","abcd",4,true);cursorDrag(-1.1f);expect("abcd");cursorAt(3);report.putBoolean("WEB-CURSOR-CONTENTEDITABLE",true);
                String family="A👨‍👩‍👧‍👦B";field("textarea",family,family.length(),true);cursorDrag(-2.1f);expect(family);cursorAt(1);report.putBoolean("WEB-CURSOR-HOST-FAMILY-GRAPHEME",true);
                field("password","SAFE",4,true);reads=fixture.surroundingCalls;cursorDrag(-1.1f);expect("SAFE");cursorAt(3);require(fixture.surroundingCalls==reads,"Password cursor queried context");report.putBoolean("WEB-CURSOR-PRIVATE-NO-CONTEXT-READ",true);
                require(state().getInt("sent")==0&&LipImeRunner.sameClipContent(originalClipboard,ui(()->clipboard.getPrimaryClip())),"Web cursor sent or changed clipboard");report.putBoolean("WEB-CURSOR-NO-SEND-CLIPBOARD",true);
                report.putString("result","PASS actual local Chromium manual cursor controls only; predictions still unsupported and no generic browser/device/quality qualification");outcome=Activity.RESULT_OK;
            }else if(metadataOnly){
                field("textarea","z".repeat(50000),50000,true);
                android.view.inputmethod.InputConnection connection=fixture.lastConnection;require(connection!=null,"Real Chromium connection unavailable");
                CountDownLatch queried=new CountDownLatch(1);AtomicReference<Throwable> failed=new AtomicReference<>();
                android.os.Handler handler=connection.getHandler();if(handler==null)handler=new android.os.Handler(fixture.getMainLooper());
                handler.post(()->{try{
                    android.view.inputmethod.ExtractedTextRequest request=new android.view.inputmethod.ExtractedTextRequest();request.hintMaxChars=64;request.hintMaxLines=1;
                    android.view.inputmethod.ExtractedText extracted=connection.getExtractedText(request,0);
                    report.putInt("extract-length",extracted==null||extracted.text==null?-1:extracted.text.length());
                    report.putInt("extract-offset",extracted==null?-1:extracted.startOffset);report.putInt("extract-selection",extracted==null?-1:extracted.selectionStart);
                    android.view.inputmethod.SurroundingText surrounding=connection.getSurroundingText(32,32,0);
                    report.putInt("surrounding-offset",surrounding==null?-999:surrounding.getOffset());
                    report.putInt("surrounding-length",surrounding==null?-1:surrounding.getText().length());
                }catch(Throwable error){failed.set(error);}finally{queried.countDown();}});
                require(queried.await(5,TimeUnit.SECONDS),"Real connection metadata timeout");require(failed.get()==null,"Metadata probe failed");
                report.putString("result","DIAGNOSTIC synthetic 50000-character field/public APIs only; no production fallback or browser correctness acceptance");outcome=Activity.RESULT_OK;
            }else{
            if(!privacyOnly){
            field("textarea","LEFT  RIGHT",5,true);spell("recieve");tap("Choose typing suggestion receive");expect("LEFT receive RIGHT");report.putBoolean("WEB-TEXTAREA-OWNED-CORRECTION",true);
            field("textarea","",0,true);spell("teh");tap("Space");expect("the ");tap("Delete previous character; hold to repeat");expect("teh ");report.putBoolean("WEB-AUTOCORRECT-AND-ORIGINAL-UNDO",true);
            AccessibilityNodeInfo stale=key("Choose typing suggestion the");field("textarea","KEEP",0,true);stale.performAction(AccessibilityNodeInfo.ACTION_CLICK);SystemClock.sleep(200);expect("KEEP");report.putBoolean("WEB-DOM-REPLACEMENT-STALE-CHOICE-REFUSED",true);
            field("contenteditable","",0,true);spell("recieve");tap("Choose typing suggestion receive");expect("receive");report.putBoolean("WEB-CONTENTEDITABLE-CORRECTION",true);
            }
            field("email","",0,true);int emailReads=fixture.surroundingCalls;spell("teh");expect("teh");require(fixture.surroundingCalls==emailReads,"Email context queried by predictor");require(!choicesVisible(),"Email Chromium field offered suggestions");report.putBoolean("WEB-EMAIL-REFUSAL",true);
            field("password","",0,true);int passwordReads=fixture.surroundingCalls;spell("teh");expect("teh");require(fixture.surroundingCalls==passwordReads,"Password context queried by predictor");require(!choicesVisible()&&!key("Record with the selected local Bark model").isEnabled(),"Password Chromium field enabled private tools");report.putBoolean("WEB-PASSWORD-PROTECTION",true);
            field("textarea","",0,false);spell("teh");tap("Space");SystemClock.sleep(300);expect("teh ");require((fixture.lastInputType&0x8000)==0,"Renderer did not expose HTML auto-off signal");report.putBoolean("WEB-HTML-AUTO-OFF-LITERAL-OBSERVATION",true);report.putString("html-optout-input-type-hex",Integer.toHexString(fixture.lastInputType));
            String family="LEFT 👨‍👩‍👧‍👦";field("textarea",family,family.length(),true);tap("Delete previous character; hold to repeat");expect("LEFT ");report.putBoolean("WEB-UNICODE-GRAPHEME-DELETE",true);
            require(state().getInt("sent")==0,"Keyboard submitted local form");require(LipImeRunner.sameClipContent(originalClipboard,ui(()->clipboard.getPrimaryClip())),"Web typing replaced clipboard");report.putBoolean("WEB-NO-SEND-CLIPBOARD-UNCHANGED",true);
            report.putInt("blocked-resource-requests",fixture.blockedRequests.get());outcome=Activity.RESULT_OK;report.putString("result",privacyOnly?"PASS privacy/literal/Unicode local Chromium controls only; offset-dependent predictions NOT RUN/unsupported; no browser parity qualification":"PASS real local WebView/Chromium synthetic editor controls only; no Chrome APK/other provider/phone/speech/quality qualification");
            }
        }catch(Throwable error){
            report.putString("failure",error.getClass().getSimpleName()+": "+error.getMessage());
            if(fixture!=null){
                report.putString("input-type-hex",Integer.toHexString(fixture.lastInputType));report.putInt("surrounding-calls",fixture.surroundingCalls);report.putInt("surrounding-offset",fixture.lastSurroundingOffset);report.putInt("surrounding-selection",fixture.lastSurroundingSelection);
                try{if(!metadataOnly)report.putString("synthetic-dom-state",state().toString());}catch(Throwable ignored){}
                try{android.graphics.Bitmap image=automation.takeScreenshot();if(image!=null){try(java.io.FileOutputStream output=new java.io.FileOutputStream(new java.io.File(getTargetContext().getFilesDir(),"web-failure.png"))){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);}image.recycle();}}catch(Throwable ignored){}
            }
        }
        finally{
            try{if(clipboardCaptured&&!LipImeRunner.sameClipContent(originalClipboard,ui(()->clipboard.getPrimaryClip()))){ui(()->{if(originalClipboard==null)clipboard.clearPrimaryClip();else clipboard.setPrimaryClip(originalClipboard);return null;});require(LipImeRunner.sameClipContent(originalClipboard,ui(()->clipboard.getPrimaryClip())),"Clipboard restoration not observed");}}catch(Throwable error){report.putString("cleanup-failure",error.getClass().getSimpleName());outcome=Activity.RESULT_CANCELED;}
            if(fixture!=null)ui(()->{fixture.finish();return null;});
        }
        finish(outcome,report);
    }
}
